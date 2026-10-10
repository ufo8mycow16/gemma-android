#!/usr/bin/env python3
"""
A stand-in for Gemma's server, speaking the same API v1, for the emulator test.
It has no model: it echoes. Its only job is to let the Android app be driven
end to end on a machine that has no Vision.
"""
import json, re, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = "emulator-key-0123456789"
MESSAGES = [
    {"id": 1, "role": "user", "text": "Earlier message from Glen", "research_log": None, "attachments": [], "created_at": "2026-10-10T00:00:00+00:00"},
    {"id": 2, "role": "assistant", "text": "Earlier reply from Gemma.", "research_log": None, "attachments": [], "created_at": "2026-10-10T00:00:05+00:00"},
]
TURNS, STOP, LOCK = {}, set(), threading.Lock()


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("fake-gemma: " + fmt % args + "\n")

    def _json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorised(self):
        if self.headers.get("Authorization", "") == "Bearer " + KEY:
            return True
        self._json(401, {"error": "access key required", "code": "auth_required"})
        return False

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/api/v1/health":
            return self._json(200, {"ok": True, "service": "gemma", "api_version": 1, "auth_required": True})
        if not self._authorised():
            return
        if path == "/api/v1/status":
            return self._json(200, {"ok": True, "service": "gemma", "api_version": 1, "build": "fake",
                                    "model": {"name": "fake", "ready": True, "reason": ""}, "voice": {"ready": False},
                                    "primary_conversation_id": 1, "capabilities": {"text_streaming": True},
                                    "limits": {"max_images_per_turn": 3, "max_image_bytes": 8388608}})
        if path == "/api/v1/conversations/primary":
            return self._json(200, {"id": 1, "title": "Emulator chat", "primary": True})
        if path == "/api/v1/conversations/1/messages":
            with LOCK:
                return self._json(200, {"conversation_id": 1, "messages": list(MESSAGES), "has_more": False, "active_turn_id": None})
        m = re.match(r"^/api/v1/turns/([\w-]+)$", path)
        if m:
            return self._json(200, TURNS.get(m.group(1), {"turn_id": m.group(1), "state": "unknown"}))
        self._json(404, {"error": "not found", "code": "not_found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        if not self._authorised():
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        m = re.match(r"^/api/v1/turns/([\w-]+)/stop$", path)
        if m:
            STOP.add(m.group(1))
            return self._json(200, {"ok": True, "was_running": TURNS.get(m.group(1), {}).get("state") == "active"})
        if path != "/api/v1/conversations/1/turns":
            return self._json(404, {"error": "not found", "code": "not_found"})

        turn_id, text = body.get("turn_id") or "t%d" % time.time(), str(body.get("message", "")).strip()
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Connection", "close")
        self.end_headers()

        def emit(event):
            self.wfile.write((json.dumps(event) + "\n").encode())
            self.wfile.flush()

        with LOCK:
            MESSAGES.append({"id": len(MESSAGES) + 1, "role": "user", "text": text, "research_log": None, "attachments": [], "created_at": ""})
        TURNS[turn_id] = {"turn_id": turn_id, "state": "active", "conversation_id": 1}
        emit({"type": "start", "turn_id": turn_id, "conversation_id": 1, "api_version": 1})
        emit({"type": "status", "stage": "thinking", "label": "Thinking"})
        long = "long story" in text.lower()
        words = (["word"] * 60 + ["THE", "END"]) if long else ("Fake Gemma heard you: " + text).split(" ")
        said, stopped = [], False
        for w in words:
            if turn_id in STOP:
                stopped = True
                break
            said.append(w)
            emit({"type": "delta", "text": w + " "})
            time.sleep(0.25 if long else 0.05)
        reply = " ".join(said)
        with LOCK:
            MESSAGES.append({"id": len(MESSAGES) + 1, "role": "assistant", "text": reply, "research_log": None, "attachments": [], "created_at": ""})
        TURNS[turn_id] = {"turn_id": turn_id, "state": "stopped" if stopped else "done", "conversation_id": 1, "reply": reply, "research_log": None}
        emit({"type": "done", "reply": reply, "research_log": None, "stopped": stopped, "turn_id": turn_id, "http_status": 200})
        self.close_connection = True


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 7862
    server = ThreadingHTTPServer(("0.0.0.0", port), H)
    server.daemon_threads = True
    print(f"fake Gemma on port {port}", flush=True)
    server.serve_forever()
