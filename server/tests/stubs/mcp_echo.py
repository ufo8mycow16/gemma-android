#!/usr/bin/env python3
"""A tiny MCP server over stdio with one read-only tool: shout(text) -> TEXT."""
import json, sys
for line in sys.stdin:
    msg = json.loads(line)
    mid, method = msg.get("id"), msg.get("method")
    if method == "initialize":
        res = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "echo", "version": "1"}}
    elif method == "tools/list":
        res = {"tools": [{"name": "shout", "description": "Return the text in capitals.", "inputSchema": {"type": "object",
               "properties": {"text": {"type": "string"}}, "required": ["text"]}}]}
    elif method == "tools/call":
        res = {"content": [{"type": "text", "text": str(msg["params"]["arguments"].get("text", "")).upper()}]}
    else:
        continue
    if mid is not None:
        print(json.dumps({"jsonrpc": "2.0", "id": mid, "result": res}), flush=True)
