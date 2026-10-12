"""
Events and hooks.

Gemma emits named events (conversation.started, message.received,
document.ingested, artifact.created, plugin.called, code.modified,
git.commit.created, task.completed, task.failed, image.completed,
error.raised). Hooks react to them, but only with built-in actions from a
fixed list: a hook can never run an arbitrary command, so hooks.json cannot be
used to escalate what Gemma is allowed to do.

hooks.json (next to app.py), for example:
  [{"event": "code.modified", "match": {"ext": ".py"}, "action": "validate_python"},
   {"event": "code.modified", "match": {"ext": ".gd"}, "action": "validate_gdscript"},
   {"event": "task.completed", "action": "notify"},
   {"event": "git.push.requested", "action": "run_tests"}]
"""
from __future__ import annotations

import json
import threading
from pathlib import Path

from . import observe, store

EVENTS = ("conversation.started", "message.received", "document.ingested", "artifact.created", "plugin.called",
          "code.modified", "git.commit.created", "git.push.requested", "task.completed", "task.failed",
          "image.completed", "error.raised")
ACTIONS: dict = {}
_HOOKS: list[dict] = []
_DEFAULT = [
    {"event": "code.modified", "match": {"ext": ".py"}, "action": "validate_python"},
    {"event": "code.modified", "match": {"ext": ".gd"}, "action": "validate_gdscript"},
]


def action(name):
    def deco(fn):
        ACTIONS[name] = fn
        return fn
    return deco


def load(app_dir: Path):
    path = app_dir / "hooks.json"
    hooks = list(_DEFAULT)
    if path.exists():
        try:
            data = json.loads(path.read_text())
            hooks = [h for h in data if isinstance(h, dict) and h.get("event") in EVENTS]
        except Exception as e:
            observe.warn("SYSTEM", "hooks.json could not be read; using the defaults", error=str(e))
    _HOOKS[:] = hooks
    observe.event("SYSTEM", "hooks loaded", hooks=[f"{h['event']}→{h.get('action')}" for h in _HOOKS])


def _matches(h: dict, data: dict) -> bool:
    m = h.get("match") or {}
    if "ext" in m and not str(data.get("path", "")).lower().endswith(m["ext"].lower()):
        return False
    if "plugin" in m and data.get("plugin") != m["plugin"]:
        return False
    return True


def emit(event: str, **data):
    """Never blocks the caller; hook actions run on their own thread."""
    if event not in EVENTS:
        return
    for h in _HOOKS:
        if h.get("event") != event or not _matches(h, data):
            continue
        fn = ACTIONS.get(h.get("action"))
        if fn is None:
            observe.warn("SYSTEM", "hook names an unknown action; ignored", action=h.get("action"))
            continue
        observe.debug("SYSTEM", f"hook {event} → {h.get('action')}")
        threading.Thread(target=observe.run_in_context(_safe, fn, data), daemon=True).start()


def _safe(fn, data):
    try:
        fn(**data)
    except Exception as e:
        observe.warn("SYSTEM", "hook action failed", error=f"{type(e).__name__}: {e}")


@action("notify")
def _notify(job=None, **_):
    if job:
        from . import notify
        notify.add("hook", f"{job.get('title')}: {job.get('state')}", conversation_id=job.get("conversation_id"), link={"job": job.get("id")})
