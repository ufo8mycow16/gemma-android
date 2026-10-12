"""
Credentials for plugins (GitHub token, MCP server keys...).

Kept in secrets.json next to app.py, readable only by Glen's user (0600), never
in the database, never in prompts, memory, diagnostics or logs. Google keeps
its own google_token.json as before.
"""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path

from . import store

_LOCK = threading.Lock()


def _path() -> Path:
    return store.app_dir() / "secrets.json"


def _load() -> dict:
    p = _path()
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text())
    except Exception:
        return {}


def get(plugin: str, key: str, default=None):
    env = os.environ.get(f"GEMMA_{plugin.upper()}_{key.upper()}")
    if env:
        return env
    return _load().get(plugin, {}).get(key, default)


def put(plugin: str, key: str, value):
    with _LOCK:
        data = _load()
        if value is None:
            data.get(plugin, {}).pop(key, None)
        else:
            data.setdefault(plugin, {})[key] = value
        p = _path()
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=1))
        os.chmod(tmp, 0o600)
        tmp.replace(p)


def forget(plugin: str):
    with _LOCK:
        data = _load()
        data.pop(plugin, None)
        p = _path()
        p.write_text(json.dumps(data, indent=1))
        os.chmod(p, 0o600)


def present(plugin: str, key: str) -> bool:
    return bool(get(plugin, key))
