"""
Plugin manager.

A plugin is a package of tools around one service (GitHub, Gmail, local Git,
an MCP server...). It describes itself (id, name, version, what it needs to
connect, what it can read and change) and registers Tools with the router.
Adding a plugin means adding a module here; the conversation engine does not
change. Enabled/disabled state and settings are stored per user; health is
cached so the settings screen is quick and chat never waits on it.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field

from .. import observe, store, tools

REGISTRY: dict = {}
_HEALTH: dict = {}
HEALTH_TTL = 60


class Plugin:
    id = "base"
    name = "Base"
    description = ""
    version = "1.0"
    auth = "none"                 # "none", "token", "google", "local"
    permissions: tuple = ()       # human-readable: "read repositories", "send email (asks first)"
    default_enabled = True

    def register(self):           # add Tools to tools.TOOLS
        pass

    def health(self) -> dict:     # {"status": "ok"|"not_connected"|"auth_expired"|"unavailable"|..., "message": str}
        return {"status": "ok", "message": ""}

    def settings_schema(self) -> dict:
        return {}

    def connect_info(self) -> dict:
        return {}

    # ---- helpers
    def settings(self) -> dict:
        with store.db() as con:
            r = con.execute("SELECT settings FROM plugin_state WHERE user_id=1 AND plugin=?", (self.id,)).fetchone()
        return store.jload(r["settings"], {}) if r else {}

    def save_settings(self, values: dict):
        merged = dict(self.settings(), **values)
        with store.lock(), store.db() as con:
            con.execute("INSERT INTO plugin_state(user_id,plugin,enabled,settings,updated_at) VALUES(1,?,?,?,?) "
                        "ON CONFLICT(user_id,plugin) DO UPDATE SET settings=excluded.settings, updated_at=excluded.updated_at",
                        (self.id, int(enabled(self.id)), store.jdump(merged), store.now()))
        _HEALTH.pop(self.id, None)


def add(plugin: Plugin):
    REGISTRY[plugin.id] = plugin


def enabled(pid: str) -> bool:
    with store.db() as con:
        r = con.execute("SELECT enabled FROM plugin_state WHERE user_id=1 AND plugin=?", (pid,)).fetchone()
    return bool(r["enabled"]) if r else REGISTRY[pid].default_enabled


def health(pid: str, fresh: bool = False) -> dict:
    cached = _HEALTH.get(pid)
    if cached and not fresh and time.time() - cached[0] < HEALTH_TTL:
        return cached[1]
    p = REGISTRY[pid]
    try:
        h = p.health()
    except Exception as e:
        h = {"status": "unavailable", "message": f"{type(e).__name__}: {e}"}
    _HEALTH[pid] = (time.time(), h)
    return h


def _gate_for(pid: str):
    def gate():
        if not enabled(pid):
            return False, "disabled", f"The {REGISTRY[pid].name} connection is switched off in Gemma's settings."
        h = health(pid)
        if h["status"] == "ok":
            return True, "ok", ""
        return False, h["status"], h.get("message") or f"{REGISTRY[pid].name} is not available."
    return gate


def setup():
    for pid, p in REGISTRY.items():
        tools.unregister_plugin(pid)
        if enabled(pid):
            try:
                p.register()
            except Exception as e:
                observe.error("PLUGIN", f"{p.name} failed to load", error=f"{type(e).__name__}: {e}")
        tools.gate(pid, _gate_for(pid))
    observe.event("PLUGIN", "plugins loaded", plugins={pid: enabled(pid) for pid in REGISTRY}, tools=len(tools.TOOLS))


def set_enabled(pid: str, on: bool):
    with store.lock(), store.db() as con:
        con.execute("INSERT INTO plugin_state(user_id,plugin,enabled,settings,updated_at) VALUES(1,?,?,'{}',?) "
                    "ON CONFLICT(user_id,plugin) DO UPDATE SET enabled=excluded.enabled, updated_at=excluded.updated_at",
                    (pid, int(on), store.now()))
    _HEALTH.pop(pid, None)
    observe.event("SECURITY", f"plugin {'enabled' if on else 'disabled'}", plugin=pid)
    setup()


def describe(pid: str, fresh: bool = False) -> dict:
    p = REGISTRY[pid]
    on = enabled(pid)
    h = health(pid, fresh) if on else {"status": "disabled", "message": "Switched off"}
    return {"id": p.id, "name": p.name, "description": p.description, "version": p.version, "auth": p.auth,
            "permissions": list(p.permissions), "enabled": on, "health": h, "settings": p.settings_schema(),
            "values": {k: v for k, v in p.settings().items() if "token" not in k.lower() and "secret" not in k.lower()},
            "connect": p.connect_info(),
            "tools": [{"name": t.name, "mutation": t.mutation, "risk": t.risk, "description": t.description[:160]}
                      for t in tools.TOOLS.values() if t.plugin == pid]}


def listing(fresh: bool = False) -> list[dict]:
    return [describe(pid, fresh) for pid in REGISTRY]
