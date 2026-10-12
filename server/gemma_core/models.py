"""
Which model does which job, and the one way Gemma talks to Ollama.

Purposes (chat, classify, summarise, vision, code, performance, embed) each map
to a model. By default they all use Gemma's own model, so nothing changes until
Glen chooses otherwise in models.json (next to app.py), for example:

    {"chat": "gemma4:12b", "code": "qwen2.5-coder:14b", "embed": "nomic-embed-text"}

Speed rule: Ollama reloads a model whenever the context size of a request
differs from the one it is loaded with, which costs seconds. Every call for the
same model therefore uses the same num_ctx (CONTEXT_FOR[model]); callers cannot
accidentally choose another.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

import requests

from . import observe

OLLAMA = os.environ.get("GEMMA_OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/")
DEFAULT_MODEL = os.environ.get("GEMMA_MODEL", "gemma4:12b").strip()
DEFAULT_CONTEXT = int(os.environ.get("GEMMA_NUM_CTX", "32768"))
PURPOSES = ("chat", "classify", "summarise", "research", "vision", "code", "performance", "embed")

_ROLES: dict[str, str] = {}
_CONTEXT: dict[str, int] = {}


def configure(app_dir: Path, chat_model: str, embed_model: str, context: int) -> None:
    roles = {p: chat_model.strip() for p in PURPOSES}
    roles["embed"] = embed_model.strip()
    contexts: dict[str, int] = {}
    path = app_dir / "models.json"
    if path.exists():
        try:
            data = json.loads(path.read_text())
            for k, v in (data.items() if isinstance(data, dict) else []):
                if k in PURPOSES and isinstance(v, str) and v.strip():
                    roles[k] = v.strip()
                if k == "contexts" and isinstance(v, dict):
                    contexts.update({str(m).strip(): int(n) for m, n in v.items() if str(n).isdigit()})
        except Exception as e:
            observe.warn("MODEL", "models.json could not be read; using defaults", error=str(e))
    _ROLES.clear()
    _ROLES.update(roles)
    _CONTEXT.clear()
    _CONTEXT.update(contexts)
    _CONTEXT.setdefault(roles["chat"], context)
    observe.event("SYSTEM", "model roles", roles=roles, contexts=dict(_CONTEXT))


def model_for(purpose: str) -> str:
    return _ROLES.get(purpose) or _ROLES.get("chat") or DEFAULT_MODEL


def context_for(model: str) -> int:
    return _CONTEXT.get(model.strip(), DEFAULT_CONTEXT)


def roles() -> dict:
    return dict(_ROLES)


def _prepare(purpose: str, payload: dict) -> dict:
    payload = dict(payload)
    model = str(payload.get("model") or model_for(purpose)).strip()
    if purpose != "chat" and model == model_for("chat"):
        model = model_for(purpose)
    payload["model"] = model
    options = dict(payload.get("options") or {})
    options["num_ctx"] = context_for(model)        # the same for every call to this model
    payload["options"] = options
    return payload


def _size(payload: dict) -> int:
    return sum(len(str(m.get("content") or "")) for m in payload.get("messages") or [])


def post(purpose: str, payload: dict, *, stream: bool = False, timeout=600):
    """POST /api/chat. Returns the requests.Response. Records a MODEL event (for a stream: when the request is accepted)."""
    payload = _prepare(purpose, payload)
    payload["stream"] = stream
    started = time.perf_counter()
    info = {"purpose": purpose, "model": payload["model"], "num_ctx": payload["options"]["num_ctx"],
            "think": payload.get("think"), "stream": stream, "messages": len(payload.get("messages") or []),
            "prompt_chars": _size(payload), "tools": len(payload.get("tools") or []),
            "format": payload.get("format"), "temperature": payload["options"].get("temperature")}
    try:
        r = requests.post(f"{OLLAMA}/api/chat", json=payload, stream=stream, timeout=timeout)
    except Exception as e:
        observe.error("MODEL", f"{purpose} call failed to reach Ollama", error=f"{type(e).__name__}: {e}", **info)
        raise
    info["ms_to_response"] = round((time.perf_counter() - started) * 1000, 1)
    if not r.ok:
        observe.error("MODEL", f"{purpose} call refused", status=r.status_code, body=(r.text or "")[:300], **info)
    elif not stream:
        try:
            body = r.json()
            info["eval_count"] = body.get("eval_count")
            info["prompt_eval_count"] = body.get("prompt_eval_count")
            info["load_ms"] = round((body.get("load_duration") or 0) / 1e6, 1)
            info["done_reason"] = body.get("done_reason")
        except Exception:
            pass
        observe.event("MODEL", f"{purpose} call", **info)
    else:
        observe.debug("MODEL", f"{purpose} stream opened", **info)
    return r


def embed(texts: list[str], timeout=60):
    model = model_for("embed")
    started = time.perf_counter()
    r = requests.post(f"{OLLAMA}/api/embed", json={"model": model, "input": texts}, timeout=timeout)
    observe.debug("MODEL", "embed call", model=model, count=len(texts), ms=round((time.perf_counter() - started) * 1000, 1), status=r.status_code)
    return r


def loaded() -> list[dict]:
    """What Ollama has in memory right now (for GPU coordination and diagnostics)."""
    try:
        r = requests.get(f"{OLLAMA}/api/ps", timeout=2)
        r.raise_for_status()
        return [{"name": m.get("name"), "vram_bytes": m.get("size_vram"), "expires_at": m.get("expires_at")}
                for m in r.json().get("models") or []]
    except Exception:
        return []


def unload(model: str) -> bool:
    """Ask Ollama to release a model's memory now (keep_alive 0). Used before big image jobs."""
    try:
        r = requests.post(f"{OLLAMA}/api/generate", json={"model": model, "keep_alive": 0}, timeout=30)
        observe.event("MODEL", "model released from GPU memory", model=model, ok=r.ok)
        return r.ok
    except Exception as e:
        observe.warn("MODEL", "model release failed", model=model, error=str(e))
        return False
