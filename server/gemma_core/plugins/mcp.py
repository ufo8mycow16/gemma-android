"""
Model Context Protocol servers as Gemma plugins.

mcp.json (next to app.py), the same shape most MCP clients use:
  {"mcpServers": {"sqlite": {"command": "uvx", "args": ["mcp-server-sqlite", "--db-path", "/home/glend/x.db"]},
                  "fetch":  {"command": "uvx", "args": ["mcp-server-fetch"], "mutations": false}}}

Each server is started on first use (stdio, JSON-RPC 2.0), its tool list is
read once and cached, and its tools appear as mcp_<server>_<tool>. Unless a
server is marked "mutations": false (read-only), its tools are treated as able
to change things and ask before running. Their output is outside content.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import threading
import time
from pathlib import Path

from .. import observe, store
from ..tools import Tool, ToolFailure, register
from . import Plugin, add

PROTOCOL = "2024-11-05"


class StdioServer:
    def __init__(self, name: str, spec: dict):
        self.name, self.spec = name, spec
        self.proc = None
        self.lock = threading.Lock()
        self.next_id = 1
        self.tools: list[dict] | None = None
        self.started_at = None

    def _ensure(self):
        if self.proc and self.proc.poll() is None:
            return
        cmd = [self.spec["command"], *self.spec.get("args", [])]
        env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG", "USER")}
        env.update({k: str(v) for k, v in (self.spec.get("env") or {}).items()})
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env,
                                     cwd=self.spec.get("cwd") or None, text=True, bufsize=1)
        self.started_at = time.time()
        self._rpc("initialize", {"protocolVersion": PROTOCOL, "capabilities": {}, "clientInfo": {"name": "gemma", "version": "2.0"}})
        self._send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        observe.event("PLUGIN", f"MCP server {self.name} started", command=cmd[0])

    def _send(self, msg):
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()

    def _rpc(self, method, params, timeout=60):
        rid = self.next_id
        self.next_id += 1
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        deadline = time.time() + timeout
        while time.time() < deadline:
            line = self.proc.stdout.readline()
            if not line:
                raise ToolFailure("unavailable", f"The MCP server {self.name} stopped.")
            try:
                msg = json.loads(line)
            except Exception:
                continue
            if msg.get("id") == rid:
                if "error" in msg:
                    raise ToolFailure("upstream_error", f"{self.name}: {msg['error'].get('message', msg['error'])}")
                return msg.get("result") or {}
        raise ToolFailure("network_error", f"The MCP server {self.name} did not answer in time.")

    def list_tools(self, fresh=False):
        with self.lock:
            if self.tools is None or fresh:
                self._ensure()
                self.tools = self._rpc("tools/list", {}).get("tools", [])
            return self.tools

    def call(self, tool, args):
        with self.lock:
            self._ensure()
            res = self._rpc("tools/call", {"name": tool, "arguments": args}, timeout=self.spec.get("timeout", 120))
        parts = []
        for c in res.get("content", []):
            if c.get("type") == "text":
                parts.append(c.get("text", ""))
            else:
                parts.append(f"[{c.get('type')} content]")
        return {"is_error": bool(res.get("isError")), "content": "\n".join(parts)[:20000]}

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()


SERVERS: dict[str, StdioServer] = {}


def _config() -> dict:
    p = store.app_dir() / "mcp.json"
    if not p.exists():
        return {}
    try:
        data = json.loads(p.read_text())
        return data.get("mcpServers") or data.get("servers") or {}
    except Exception as e:
        observe.warn("PLUGIN", "mcp.json could not be read", error=str(e))
        return {}


def _cache_path() -> Path:
    return store.app_dir() / "mcp_tools_cache.json"


class MCPPlugin(Plugin):
    id, name, version, auth = "mcp", "MCP servers", "1.0", "local"
    description = "Tools from Model Context Protocol servers listed in mcp.json. Started on first use; their tool lists are cached."
    permissions = ("start the MCP servers listed in mcp.json", "their tools ask before running unless a server is marked read-only")

    def health(self):
        cfg = _config()
        if not cfg:
            return {"status": "ok", "message": "No MCP servers configured (add them to mcp.json)."}
        return {"status": "ok", "message": f"{len(cfg)} server(s) configured", "servers": list(cfg)}

    def register(self):
        cfg = _config()
        cache = {}
        try:
            cache = json.loads(_cache_path().read_text())
        except Exception:
            pass
        for name, spec in cfg.items():
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", name) or not spec.get("command"):
                continue
            server = SERVERS.get(name) or StdioServer(name, spec)
            SERVERS[name] = server
            tools = cache.get(name, {}).get("tools") if cache.get(name, {}).get("spec") == spec else None
            if tools is None:
                # Not known yet: one tool that lists (and caches) this server's tools on first use.
                register(Tool(f"mcp_{name}_discover", self.id, f"Start the '{name}' MCP server and see what tools it offers.", {},
                              lambda a, c, n=name: self.discover(n), (), keywords=(name.lower(), "mcp"), label=f"Starting {name}"))
                continue
            self._register_tools(name, spec, tools)

    def discover(self, name):
        server = SERVERS[name]
        tools = server.list_tools(fresh=True)
        try:
            cache = json.loads(_cache_path().read_text())
        except Exception:
            cache = {}
        cache[name] = {"spec": server.spec, "tools": tools, "at": time.time()}
        _cache_path().write_text(json.dumps(cache))
        self._register_tools(name, server.spec, tools)
        return {"server": name, "tools": [t["name"] for t in tools], "note": "These tools are now available."}

    def _register_tools(self, name, spec, tools):
        read_only = spec.get("mutations") is False
        for t in tools:
            tool_name = re.sub(r"[^A-Za-z0-9_]", "_", f"mcp_{name}_{t['name']}")[:64]
            schema = t.get("inputSchema") or {"type": "object", "properties": {}}
            register(Tool(tool_name, self.id, (t.get("description") or t["name"])[:600], schema.get("properties", {}),
                          lambda a, c, n=name, tn=t["name"]: SERVERS[n].call(tn, a), tuple(schema.get("required", [])),
                          mutation=not read_only, risk="medium", keywords=(name.lower(),), label=f"Using {name}",
                          reads_untrusted=True,
                          preview=lambda a, c, n=name, tn=t["name"]: {"title": f"Run {tn} on the {n} MCP server",
                                                                         "fields": [[k, str(v)[:200]] for k, v in a.items()]}))


add(MCPPlugin())
