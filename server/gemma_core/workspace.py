"""
Development workspaces and the controlled execution layer.

A workspace is a folder under workspaces/<id>/ with two copies of a project:
original/ (exactly what Glen gave, never modified) and work/ (where Gemma and
Claude Code make changes). That gives a real diff, a clean revert and a clean
package at the end. Every write is recorded with who made it (gemma,
claude_code, build_tool) for provenance.

Execution is not a shell. Gemma chooses a named action (compile, test, lint,
run a script, check a Godot project); each maps to a fixed argument list run in
the workspace with a time limit, CPU/memory/file-size limits, a scrubbed
environment (no tokens or keys) and, where the system allows it, no network.
Results are what the process actually printed and its real exit code; nothing
is reported as "tested" or "passed" unless the command ran and said so.
"""
from __future__ import annotations

import difflib
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

from . import artifacts, observe, store

RUN_TIMEOUT = 120
OUTPUT_LIMIT = 12000
_NET_ISOLATION: list = []          # probed once: [True] if `unshare -rn` works here


def root() -> Path:
    p = store.app_dir() / "workspaces"
    p.mkdir(exist_ok=True)
    return p


def _sha(p: Path) -> str | None:
    return hashlib.sha256(p.read_bytes()).hexdigest()[:16] if p.exists() and p.is_file() else None


def create(name: str, *, conversation_id=None, files: dict | None = None, archive: bytes | None = None,
           source: str = "", project_id=None) -> dict:
    wid = "w" + uuid.uuid4().hex[:10]
    base = root() / wid
    (base / "original").mkdir(parents=True)
    if archive is not None:
        artifacts.safe_unzip(archive, base / "original")
        # a single top-level folder inside the zip is the project itself
        inner = [p for p in (base / "original").iterdir()]
        if len(inner) == 1 and inner[0].is_dir():
            tmp = base / "_inner"
            inner[0].rename(tmp)
            shutil.rmtree(base / "original")
            tmp.rename(base / "original")
    for rel, data in (files or {}).items():
        clean = artifacts.clean_relpath(rel)
        if clean is None:
            raise ValueError(f"unsafe path {rel!r}")
        target = base / "original" / clean
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data if isinstance(data, bytes) else str(data).encode())
    shutil.copytree(base / "original", base / "work", symlinks=False)
    with store.lock(), store.db() as con:
        con.execute("INSERT INTO workspaces(id,user_id,conversation_id,project_id,name,path,source,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    (wid, 1, conversation_id, project_id, name[:120], str(base), source[:300], store.now()))
    observe.event("TOOLS", "workspace created", workspace=wid, name=name, files=len(list_files(wid)), source=source)
    return info(wid)


def _row(wid: str):
    with store.db() as con:
        return con.execute("SELECT * FROM workspaces WHERE id=?", (wid,)).fetchone()


def work_dir(wid: str) -> Path:
    r = _row(wid)
    if not r:
        raise LookupError("There is no workspace with that id.")
    return Path(r["path"]) / "work"


def resolve(wid: str, rel: str) -> Path:
    """A path inside the workspace, or an error: no traversal, no absolute paths, no symlink escape."""
    base = work_dir(wid).resolve()
    clean = artifacts.clean_relpath(rel)
    if clean is None:
        raise PermissionError(f"{rel!r} is not a path inside the workspace.")
    target = (base / clean).resolve()
    if target != base and base not in target.parents:
        raise PermissionError(f"{rel!r} points outside the workspace.")
    return target


def info(wid: str) -> dict:
    r = _row(wid)
    return {"id": wid, "name": r["name"], "conversation_id": r["conversation_id"], "source": r["source"],
            "created_at": r["created_at"], "files": len(list_files(wid)), "tools": toolchain()}


def list_files(wid: str, limit: int = 2000) -> list[dict]:
    base = work_dir(wid)
    out = []
    for p in sorted(base.rglob("*")):
        if p.is_symlink() or not p.is_file():
            continue
        rel = p.relative_to(base)
        if any(part in (".git", "__pycache__", ".godot", ".import") for part in rel.parts):
            continue
        out.append({"path": str(rel).replace(os.sep, "/"), "size": p.stat().st_size})
        if len(out) >= limit:
            break
    return out


def read(wid: str, rel: str, limit: int = 60000) -> str:
    p = resolve(wid, rel)
    if not p.is_file():
        raise FileNotFoundError(f"{rel} does not exist in the workspace.")
    data = p.read_bytes()
    if b"\x00" in data[:4096]:
        raise ValueError(f"{rel} is a binary file.")
    text = data.decode("utf-8", errors="replace")
    return text if len(text) <= limit else text[:limit] + f"\n…[{len(text) - limit} more characters]"


def write(wid: str, rel: str, content: str, actor: str = "gemma", note: str = "") -> dict:
    p = resolve(wid, rel)
    before = _sha(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
    after = _sha(p)
    with store.lock(), store.db() as con:
        con.execute("INSERT INTO workspace_changes(workspace_id,path,actor,action,before_sha,after_sha,request_id,job_id,note,created_at) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?)", (wid, rel, actor, "modified" if before else "created", before, after,
                                                    observe.request_id.get(), observe.job_id.get(), note[:300], store.now()))
    observe.event("TOOLS", f"{actor} {'modified' if before else 'created'} {rel}", workspace=wid, actor=actor, bytes=len(content))
    from . import hooks
    hooks.emit("code.modified", workspace=wid, path=rel, actor=actor)
    return {"path": rel, "action": "modified" if before else "created", "bytes": len(content.encode())}


def record_external_changes(wid: str, actor: str, before: dict):
    """After another tool (Claude Code) worked in the folder: record what changed, by whom."""
    after = snapshot(wid)
    changed = []
    for rel in sorted(set(before) | set(after)):
        if before.get(rel) != after.get(rel):
            action = "created" if rel not in before else "deleted" if rel not in after else "modified"
            changed.append({"path": rel, "action": action})
            with store.lock(), store.db() as con:
                con.execute("INSERT INTO workspace_changes(workspace_id,path,actor,action,before_sha,after_sha,request_id,job_id,created_at) "
                            "VALUES(?,?,?,?,?,?,?,?,?)", (wid, rel, actor, action, before.get(rel), after.get(rel),
                                                          observe.request_id.get(), observe.job_id.get(), store.now()))
    return changed


def snapshot(wid: str) -> dict:
    base = work_dir(wid)
    return {f["path"]: _sha(base / f["path"]) for f in list_files(wid, 20000)}


def changes(wid: str) -> list[dict]:
    with store.db() as con:
        return [dict(r) for r in con.execute("SELECT path, actor, action, note, created_at FROM workspace_changes WHERE workspace_id=? ORDER BY id", (wid,))]


def diff(wid: str, max_chars: int = 60000) -> dict:
    """Unified diff of work/ against original/, with per-file additions and deletions."""
    r = _row(wid)
    orig, work = Path(r["path"]) / "original", Path(r["path"]) / "work"
    paths = set()
    for base in (orig, work):
        for p in base.rglob("*"):
            if p.is_file() and not p.is_symlink() and not any(x in (".git", "__pycache__", ".godot", ".import") for x in p.relative_to(base).parts):
                paths.add(str(p.relative_to(base)).replace(os.sep, "/"))
    files, text = [], []
    for rel in sorted(paths):
        a, b = orig / rel, work / rel
        da = a.read_bytes() if a.exists() else b""
        db_ = b.read_bytes() if b.exists() else b""
        if da == db_:
            continue
        if b"\x00" in da[:4096] or b"\x00" in db_[:4096]:
            files.append({"path": rel, "binary": True, "status": "added" if not a.exists() else "deleted" if not b.exists() else "modified"})
            continue
        al = da.decode("utf-8", "replace").splitlines(keepends=True)
        bl = db_.decode("utf-8", "replace").splitlines(keepends=True)
        d = list(difflib.unified_diff(al, bl, f"a/{rel}", f"b/{rel}"))
        adds = sum(1 for x in d if x.startswith("+") and not x.startswith("+++"))
        dels = sum(1 for x in d if x.startswith("-") and not x.startswith("---"))
        who = sorted({c["actor"] for c in changes(wid) if c["path"] == rel})
        files.append({"path": rel, "additions": adds, "deletions": dels, "by": who,
                      "status": "added" if not a.exists() else "deleted" if not b.exists() else "modified"})
        text.append("".join(d))
    full = "".join(text)
    return {"workspace": wid, "files": files, "diff": full[:max_chars], "truncated": len(full) > max_chars}


def revert(wid: str, rel: str | None = None) -> dict:
    r = _row(wid)
    orig, work = Path(r["path"]) / "original", Path(r["path"]) / "work"
    if rel:
        src, dst = orig / artifacts.clean_relpath(rel), resolve(wid, rel)
        if src.exists():
            shutil.copy2(src, dst)
        elif dst.exists():
            dst.unlink()
    else:
        shutil.rmtree(work)
        shutil.copytree(orig, work)
    observe.event("TOOLS", "workspace reverted", workspace=wid, path=rel or "(everything)")
    return {"reverted": rel or "all"}


def accept(wid: str) -> dict:
    """Make the current work the new baseline (after Glen accepts a reviewed diff)."""
    r = _row(wid)
    orig, work = Path(r["path"]) / "original", Path(r["path"]) / "work"
    shutil.rmtree(orig)
    shutil.copytree(work, orig)
    observe.event("TOOLS", "workspace changes accepted", workspace=wid)
    return {"accepted": True}


def package(wid: str, filename: str, conversation_id=None) -> dict:
    data = artifacts.folder_to_zip(work_dir(wid))
    return artifacts.save(data, filename if filename.lower().endswith(".zip") else filename + ".zip", created_by="build_tool",
                          conversation_id=conversation_id, provenance={"workspace": wid, "changes": changes(wid)[-50:]},
                          description=f"Packaged from workspace {wid}")


# ------------------------------------------------------------- execution

def toolchain() -> dict:
    found = {}
    for name, cands in {"python": [sys.executable, "python3"], "pytest": None, "ruff": ["ruff"], "mypy": ["mypy"],
                        "black": ["black"], "godot": [os.environ.get("GEMMA_GODOT", ""), "godot4", "godot", "Godot"],
                        "gdlint": ["gdlint"], "gdformat": ["gdformat"], "node": ["node"], "git": ["git"]}.items():
        if name == "pytest":
            found[name] = _has_module("pytest")
            continue
        path = next((shutil.which(c) or (c if c and os.path.isabs(c) and os.access(c, os.X_OK) else None) for c in cands if c), None)
        found[name] = bool(path)
    return found


def _has_module(mod: str) -> bool:
    try:
        r = subprocess.run([sys.executable, "-c", f"import {mod}"], capture_output=True, timeout=10)
        return r.returncode == 0
    except Exception:
        return False


def _which(*cands):
    for c in cands:
        if c and (shutil.which(c) or (os.path.isabs(c) and os.access(c, os.X_OK))):
            return shutil.which(c) or c
    return None


def _env() -> dict:
    keep = ("PATH", "HOME", "LANG", "LC_ALL", "TERM", "TMPDIR", "XDG_RUNTIME_DIR")
    env = {k: v for k, v in os.environ.items() if k in keep}
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["PYTHONUNBUFFERED"] = "1"
    return env


def _limits():
    try:
        import resource
        resource.setrlimit(resource.RLIMIT_CPU, (RUN_TIMEOUT, RUN_TIMEOUT + 5))
        resource.setrlimit(resource.RLIMIT_AS, (4 * 1024 ** 3, 4 * 1024 ** 3))
        resource.setrlimit(resource.RLIMIT_FSIZE, (500 * 1024 ** 2, 500 * 1024 ** 2))
        os.setsid()
    except Exception:
        pass


def _net_prefix() -> list:
    if not _NET_ISOLATION:
        ok = False
        if shutil.which("unshare"):
            try:
                ok = subprocess.run(["unshare", "-rn", "true"], capture_output=True, timeout=5).returncode == 0
            except Exception:
                ok = False
        _NET_ISOLATION.append(ok)
        observe.event("SECURITY", "code execution network isolation " + ("available" if ok else "not available on this system"))
    return ["unshare", "-rn"] if _NET_ISOLATION[0] else []


ACTIONS = {
    "python_compile": "Check every .py file compiles (syntax errors)",
    "pytest": "Run the Python tests with pytest",
    "run_python": "Run one Python file (args allowed)",
    "ruff": "Lint Python with ruff",
    "mypy": "Type-check Python with mypy",
    "godot_check": "Load the Godot project headless and report script/scene errors",
    "gdscript_check": "Check one GDScript file parses (Godot headless --check-only)",
    "gdlint": "Lint GDScript with gdlint (gdtoolkit)",
    "node": "Run one JavaScript file with node",
}


def run(wid: str, action: str, target: str = "", args: list | None = None, timeout: int = RUN_TIMEOUT, network: bool = False) -> dict:
    cwd = work_dir(wid)
    args = [str(a) for a in (args or [])][:20]
    if any(a.startswith("-") and action in ("run_python", "node") and len(a) > 1 and a[1] not in "-" and False for a in args):
        pass
    py = _which(sys.executable, "python3")
    godot = _which(os.environ.get("GEMMA_GODOT", ""), "godot4", "godot", "Godot")
    if target:
        resolve(wid, target)        # must be inside the workspace
    if action == "python_compile":
        files = [f["path"] for f in list_files(wid) if f["path"].endswith(".py")]
        if not files:
            return {"action": action, "ran": False, "reason": "There are no .py files in the workspace."}
        cmd = [py, "-m", "py_compile", *files]
    elif action == "pytest":
        if not _has_module("pytest"):
            return {"action": action, "ran": False, "reason": "pytest is not installed on Vision (pip install pytest)."}
        cmd = [py, "-m", "pytest", "-q", "--no-header", "-p", "no:cacheprovider", *([target] if target else []), *args]
    elif action == "run_python":
        if not target.endswith(".py"):
            return {"action": action, "ran": False, "reason": "Say which .py file to run."}
        cmd = [py, target, *args]
    elif action == "ruff":
        r = _which("ruff")
        if not r:
            return {"action": action, "ran": False, "reason": "ruff is not installed on Vision (pip install ruff)."}
        cmd = [r, "check", "--no-cache", target or "."]
    elif action == "mypy":
        r = _which("mypy")
        if not r:
            return {"action": action, "ran": False, "reason": "mypy is not installed on Vision (pip install mypy)."}
        cmd = [r, "--no-incremental", target or "."]
    elif action in ("godot_check", "gdscript_check"):
        if not godot:
            return {"action": action, "ran": False, "reason": "Godot is not installed on Vision, or GEMMA_GODOT does not point to it."}
        proj = _find_godot_project(cwd)
        if proj is None:
            return {"action": action, "ran": False, "reason": "No project.godot was found in the workspace."}
        if action == "godot_check":
            cmd = [godot, "--headless", "--path", str(proj), "--quit-after", "2"]
        else:
            if not target.endswith(".gd"):
                return {"action": action, "ran": False, "reason": "Say which .gd file to check."}
            rel = os.path.relpath(cwd / target, proj)
            cmd = [godot, "--headless", "--path", str(proj), "--check-only", "--script", "res://" + rel.replace(os.sep, "/")]
    elif action == "gdlint":
        r = _which("gdlint")
        if not r:
            return {"action": action, "ran": False, "reason": "gdlint is not installed on Vision (pip install gdtoolkit)."}
        cmd = [r, target or "."]
    elif action == "node":
        r = _which("node")
        if not r or not target.endswith((".js", ".mjs")):
            return {"action": action, "ran": False, "reason": "node is not installed, or no .js file was given."}
        cmd = [r, target, *args]
    else:
        return {"action": action, "ran": False, "reason": f"Unknown action. Available: {', '.join(ACTIONS)}"}
    prefix = [] if network else _net_prefix()
    started = time.time()
    try:
        p = subprocess.run(prefix + cmd, cwd=cwd, env=_env(), capture_output=True, timeout=timeout, preexec_fn=_limits)
        out = (p.stdout + b"\n" + p.stderr).decode("utf-8", "replace")
        code, timed_out = p.returncode, False
    except subprocess.TimeoutExpired as e:
        out = ((e.stdout or b"") + b"\n" + (e.stderr or b"")).decode("utf-8", "replace")
        code, timed_out = None, True
    took = round(time.time() - started, 2)
    shown = out.strip()
    if len(shown) > OUTPUT_LIMIT:
        shown = shown[:OUTPUT_LIMIT // 2] + f"\n…[{len(out) - OUTPUT_LIMIT} characters cut]…\n" + shown[-OUTPUT_LIMIT // 2:]
    summary = _summarise(action, code, out, timed_out)
    observe.event("TOOLS", f"ran {action}: {summary}", workspace=wid, command=[os.path.basename(cmd[0]), *cmd[1:6]],
                  exit_code=code, seconds=took, timed_out=timed_out, network_isolated=bool(prefix))
    store.usage("exec_seconds", took, "s", action)
    return {"action": action, "ran": True, "exit_code": code, "timed_out": timed_out, "seconds": took, "summary": summary,
            "output": shown, "network_isolated": bool(prefix),
            "command": " ".join([os.path.basename(cmd[0]), *[c if len(c) < 80 else c[:77] + "…" for c in cmd[1:]]])}


def _summarise(action, code, out, timed_out) -> str:
    if timed_out:
        return "stopped: took too long"
    if action == "pytest":
        m = re.findall(r"(\d+) (passed|failed|error|errors|skipped)", out)
        if m:
            return ", ".join(f"{n} {w}" for n, w in m)
    if action in ("godot_check", "gdscript_check"):
        errs = [l for l in out.splitlines() if "SCRIPT ERROR" in l or "Parse Error" in l or l.startswith("ERROR")]
        if errs:
            return f"{len(errs)} error line(s)"
    return "succeeded" if code == 0 else f"failed (exit code {code})"


def _find_godot_project(cwd: Path):
    for p in [cwd, *sorted(cwd.rglob("project.godot"))]:
        if p.is_file() and p.name == "project.godot":
            return p.parent
        if p.is_dir() and (p / "project.godot").exists():
            return p
    return None


def godot_version(wid: str) -> dict:
    """What Godot generation the project targets, read from project.godot (not assumed)."""
    proj = _find_godot_project(work_dir(wid))
    if proj is None:
        return {"found": False}
    text = (proj / "project.godot").read_text(encoding="utf-8", errors="replace")
    feats = re.search(r'config/features=PackedStringArray\(([^)]*)\)', text)
    ver = None
    if feats:
        nums = re.findall(r'"(\d+\.\d+)"', feats.group(1))
        ver = nums[0] if nums else None
    cfg = re.search(r"config_version=(\d+)", text)
    gen = "4.x" if (cfg and int(cfg.group(1)) >= 5) or (ver and ver.startswith("4")) else "3.x" if cfg else "unknown"
    return {"found": True, "project_dir": str(proj.relative_to(work_dir(wid))) or ".", "engine_version": ver, "generation": gen,
            "config_version": int(cfg.group(1)) if cfg else None}
