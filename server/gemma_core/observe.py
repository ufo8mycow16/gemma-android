"""
Gemma's one observability system.

Every part of Gemma reports what it decided through `event(...)`. Each event
goes, without blocking the caller, to:
  * an in-memory ring buffer that the live Diagnostics window reads and follows;
  * a rotating JSON-lines file under logs/ for later inspection.
There is no second logging system: the Diagnostics window and the files show
the same events.

Diagnostics are never conversation: nothing here is ever given to the model,
stored as a message or written to memory.

Safety: field names that look like secrets are masked, bearer tokens and keys
inside strings are masked, and long strings are cut, so a whole document or
prompt is never dumped by accident. Deep debug mode (GEMMA_DIAG_DEEP=on) allows
longer excerpts but still masks secrets.
"""
from __future__ import annotations

import contextvars
import json
import os
import queue
import re
import subprocess
import threading
import time
import uuid
from collections import deque
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

APP_VERSION = "2.0.0"

CATEGORIES = ("CHAT", "INTENT", "CONTEXT", "DOCUMENT", "RETRIEVAL", "MODEL", "TOOLS", "PLUGIN",
              "CLAUDE_CODE", "IMAGE", "TTS", "API", "JOB", "ARTIFACT", "SECURITY", "SYSTEM", "ERROR")
LEVELS = ("DEBUG", "INFO", "WARNING", "ERROR")

DEEP = os.environ.get("GEMMA_DIAG_DEEP", "off").strip().lower() in ("on", "1", "true", "yes")
MAX_TEXT = 4000 if DEEP else 400
RING_SIZE = int(os.environ.get("GEMMA_DIAG_RING", "8000"))
LOG_MAX_BYTES = 10 * 1024 * 1024
LOG_KEEP = 5

request_id: contextvars.ContextVar[str | None] = contextvars.ContextVar("gemma_request_id", default=None)
conversation_id: contextvars.ContextVar[int | None] = contextvars.ContextVar("gemma_conversation_id", default=None)
job_id: contextvars.ContextVar[str | None] = contextvars.ContextVar("gemma_job_id", default=None)

_RING: deque = deque(maxlen=RING_SIZE)
_RING_LOCK = threading.Lock()
_SEQ = [0]
_SUBSCRIBERS: list[queue.Queue] = []
_SUB_LOCK = threading.Lock()
_WRITE_QUEUE: queue.Queue = queue.Queue(maxsize=20000)
_LOG_DIR: list[Path | None] = [None]
_BUILD: dict = {}

_SECRET_KEY = re.compile(r"(pass(word)?|secret|token|api[_-]?key|authorization|cookie|credential|private[_-]?key|refresh)", re.I)
_SECRET_VALUE = re.compile(
    r"(Bearer\s+[A-Za-z0-9._~+/=-]{8,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
    r"ya29\.[A-Za-z0-9._-]{20,}|1//[A-Za-z0-9._-]{20,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,})")


def new_id(prefix: str = "r") -> str:
    return prefix + uuid.uuid4().hex[:12]


def mask(value, depth: int = 0):
    """A copy of `value` that is safe to log."""
    if depth > 6:
        return "…"
    if isinstance(value, dict):
        out = {}
        for k, v in list(value.items())[:80]:
            if _SECRET_KEY.search(str(k)) and not str(k).endswith(("_count", "_ok", "_present", "_status", "_required")):
                out[k] = "•••" if v else v
            else:
                out[k] = mask(v, depth + 1)
        return out
    if isinstance(value, (list, tuple)):
        items = [mask(v, depth + 1) for v in list(value)[:60]]
        if len(value) > 60:
            items.append(f"… {len(value) - 60} more")
        return items
    if isinstance(value, str):
        s = _SECRET_VALUE.sub("•••", value)
        if len(s) > MAX_TEXT:
            s = s[:MAX_TEXT] + f" …[{len(s)} chars]"
        return s
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return mask(str(value), depth + 1)


def configure(app_dir: Path) -> None:
    """Start the background log writer and work out this build's identity."""
    log_dir = app_dir / "logs"
    log_dir.mkdir(exist_ok=True)
    _LOG_DIR[0] = log_dir
    _BUILD.update(build_identity(app_dir))
    threading.Thread(target=_writer, name="gemma-diag-writer", daemon=True).start()


def build_identity(app_dir: Path) -> dict:
    """Version, build number, commit and build time, so a screenshot can be traced to its code."""
    info = {"version": APP_VERSION, "build": None, "commit": None, "dirty": None, "built_at": None}
    stamp = app_dir / "BUILD_INFO.json"
    if stamp.exists():
        try:
            info.update({k: v for k, v in json.loads(stamp.read_text()).items() if k in info})
        except Exception:
            pass
    if info["commit"] is None:
        try:
            top = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=app_dir, capture_output=True, text=True, timeout=3)
            if top.returncode == 0:
                commit = subprocess.run(["git", "rev-parse", "--short=10", "HEAD"], cwd=app_dir, capture_output=True, text=True, timeout=3)
                count = subprocess.run(["git", "rev-list", "--count", "HEAD"], cwd=app_dir, capture_output=True, text=True, timeout=3)
                dirty = subprocess.run(["git", "status", "--porcelain", "--", "."], cwd=app_dir, capture_output=True, text=True, timeout=5)
                info["commit"] = commit.stdout.strip() or None
                info["build"] = int(count.stdout.strip()) if count.stdout.strip().isdigit() else None
                info["dirty"] = bool(dirty.stdout.strip())
        except Exception:
            pass
    if info["built_at"] is None:
        try:
            newest = max(p.stat().st_mtime for p in [app_dir / "app.py", *(app_dir / "gemma_core").glob("*.py")] if p.exists())
            info["built_at"] = datetime.fromtimestamp(newest, timezone.utc).isoformat(timespec="seconds")
        except Exception:
            pass
    return info


def build_info() -> dict:
    return dict(_BUILD)


def event(category: str, message: str, level: str = "INFO", **fields) -> dict:
    """Record one decision. Never raises and never blocks."""
    try:
        category = category.upper().replace(" ", "_")
        if level not in LEVELS:
            level = "INFO"
        with _RING_LOCK:
            _SEQ[0] += 1
            seq = _SEQ[0]
        rec = {
            "seq": seq,
            "ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "level": level,
            "cat": category,
            "msg": mask(message) if isinstance(message, str) else mask(str(message)),
            "rid": fields.pop("rid", None) or request_id.get(),
            "cid": fields.pop("cid", None) or conversation_id.get(),
            "jid": fields.pop("jid", None) or job_id.get(),
        }
        doc = fields.pop("doc", None)
        if doc is not None:
            rec["doc"] = doc
        if fields:
            rec["data"] = mask(fields)
        with _RING_LOCK:
            _RING.append(rec)
        with _SUB_LOCK:
            subs = list(_SUBSCRIBERS)
        for q in subs:
            try:
                q.put_nowait(rec)
            except queue.Full:
                pass
        try:
            _WRITE_QUEUE.put_nowait(rec)
        except queue.Full:
            pass
        return rec
    except Exception:
        return {}


def warn(category: str, message: str, **fields) -> dict:
    return event(category, message, "WARNING", **fields)


def error(category: str, message: str, **fields) -> dict:
    return event(category, message, "ERROR", **fields)


def debug(category: str, message: str, **fields) -> dict:
    return event(category, message, "DEBUG", **fields)


@contextmanager
def span(category: str, label: str, **fields):
    """Time a stage. Records one event at the end with ms and any fields added to the yielded dict."""
    started = time.perf_counter()
    extra: dict = {}
    failed = None
    try:
        yield extra
    except BaseException as e:            # recorded, then re-raised unchanged
        failed = e
        raise
    finally:
        ms = round((time.perf_counter() - started) * 1000, 1)
        data = dict(fields, **extra, ms=ms)
        if failed is not None and type(failed).__name__ not in ("TurnCancelled", "GeneratorExit"):
            event(category, f"{label} failed: {type(failed).__name__}", "ERROR", **data)
        else:
            event(category, label, **data)


def recent(after: int = 0, limit: int = 500, rid=None, cid=None, jid=None, doc=None, cats=None,
           levels=None, text=None) -> list[dict]:
    with _RING_LOCK:
        rows = [r for r in _RING if r["seq"] > after]
    out = []
    t = (text or "").lower()
    for r in rows:
        if rid and r.get("rid") != rid:
            continue
        if cid and str(r.get("cid")) != str(cid):
            continue
        if jid and r.get("jid") != jid:
            continue
        if doc and str(r.get("doc")) != str(doc):
            continue
        if cats and r["cat"] not in cats:
            continue
        if levels and r["level"] not in levels:
            continue
        if t and t not in json.dumps(r, ensure_ascii=False).lower():
            continue
        out.append(r)
    return out[-limit:]


def subscribe() -> queue.Queue:
    q: queue.Queue = queue.Queue(maxsize=5000)
    with _SUB_LOCK:
        _SUBSCRIBERS.append(q)
    return q


def unsubscribe(q: queue.Queue) -> None:
    with _SUB_LOCK:
        if q in _SUBSCRIBERS:
            _SUBSCRIBERS.remove(q)


def clear() -> None:
    """Clears the live window's buffer. The log files are not touched."""
    with _RING_LOCK:
        _RING.clear()


def _writer() -> None:
    current = None
    size = 0
    while True:
        rec = _WRITE_QUEUE.get()
        try:
            log_dir = _LOG_DIR[0]
            if log_dir is None:
                continue
            path = log_dir / "gemma-events.jsonl"
            if current is None:
                current = open(path, "a", encoding="utf-8")
                size = path.stat().st_size if path.exists() else 0
            line = json.dumps(rec, ensure_ascii=False) + "\n"
            current.write(line)
            size += len(line)
            if _WRITE_QUEUE.empty():
                current.flush()
            if size > LOG_MAX_BYTES:
                current.close()
                for i in range(LOG_KEEP - 1, 0, -1):
                    older = log_dir / f"gemma-events.{i}.jsonl"
                    newer = log_dir / (f"gemma-events.{i - 1}.jsonl" if i > 1 else "gemma-events.jsonl")
                    if newer.exists():
                        newer.replace(older)
                current = open(path, "a", encoding="utf-8")
                size = 0
        except Exception:
            current = None


def run_in_context(target, *args, **kwargs):
    """For threads: carry the current request/conversation/job ids into the new thread."""
    ctx = contextvars.copy_context()
    return lambda: ctx.run(target, *args, **kwargs)
