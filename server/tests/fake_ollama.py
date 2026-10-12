#!/usr/bin/env python3
"""
A stand-in for Ollama (and the reverse geocoder) for tests and benchmarks.

It behaves like the real server in the ways that matter for Gemma's speed:
  * switching model or num_ctx costs a "model load" (Ollama reloads the model
    when the context size changes), charged as LOAD_SECONDS
  * reading the prompt costs time in proportion to its length
  * writing the reply costs time per word
All times are multiplied by FAKE_SCALE (default 0.05, so tests stay quick).

Every request is appended to FAKE_LOG as one JSON line: purpose, num_ctx,
prompt size, whether a reload happened, and the simulated seconds, so a test
can say exactly how many model calls a turn made and how big they were.

Replies are chosen by rules in FAKE_RULES (a JSON file, re-read on every
request), each {"match": "<substring of the last user message>", "reply": "...",
"tool": {"name": ..., "arguments": {...}}}; otherwise a plain default.
"""
import hashlib, json, math, os, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCALE = float(os.environ.get("FAKE_SCALE", "0.05"))
LOAD_SECONDS = 4.0          # a 12B model reload on a laptop GPU, roughly
PROMPT_CHARS_PER_SECOND = 9000.0
WORDS_PER_SECOND = 28.0
LOG_PATH = os.environ.get("FAKE_LOG", "fake_ollama.jsonl")
RULES_PATH = os.environ.get("FAKE_RULES", "")
STATE = {"loaded": None}
LOCK = threading.Lock()


def purpose_of(payload):
    system = ""
    for m in payload.get("messages") or []:
        if m.get("role") == "system":
            system = m.get("content") or ""
            break
    if "classifying Glen" in system:
        return "provenance_classifier"
    if "private research decision" in system:
        return "research_planner"
    if "memory extraction" in system or "Only facts about Glen" in system:
        return "memory_extraction"
    if "retrieval index for a document" in system:
        return "document_summary"
    if "prepare one line of Gemma" in system:
        return "voice_performance"
    if "summary" in system.lower() and "conversation" in system.lower() and payload.get("format") != "json":
        return "conversation_summary"
    if payload.get("format") == "json":
        return "json_other"
    return "chat"


def rules():
    if not RULES_PATH or not os.path.exists(RULES_PATH):
        return []
    try:
        with open(RULES_PATH) as f:
            return json.load(f)
    except Exception:
        return []


def last_user(payload):
    for m in reversed(payload.get("messages") or []):
        if m.get("role") == "user":
            return m.get("content") or ""
    return ""


def has_tool_result(payload):
    return any(m.get("role") == "tool" for m in payload.get("messages") or [])


def vector(text):
    """A deterministic bag-of-words embedding: similar words give similar vectors."""
    v = [0.0] * 64
    for w in str(text).lower().split():
        h = int(hashlib.md5(w.strip(".,!?;:'\"()").encode()).hexdigest(), 16)
        v[h % 64] += 1.0
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, d, code=200):
        b = json.dumps(d).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith("/api/tags"):
            return self._send({"models": [{"name": "gemma4:12b"}, {"name": "nomic-embed-text:latest"}]})
        if self.path.startswith("/api/ps"):
            return self._send({"models": [{"name": STATE["loaded"][0], "size_vram": 9_000_000_000}] if STATE["loaded"] else []})
        # Reverse geocoder
        self._send({"address": {"suburb": "Fyshwick", "city": "Canberra", "state": "Australian Capital Territory", "country": "Australia"}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        payload = json.loads(self.rfile.read(length) or b"{}")
        if self.path.endswith("/api/embed"):
            inputs = payload.get("input")
            inputs = inputs if isinstance(inputs, list) else [inputs]
            return self._send({"embeddings": [vector(t) for t in inputs]})
        if not self.path.endswith("/api/chat"):
            return self._send({"error": "not found"}, 404)

        purpose = purpose_of(payload)
        ctx = (payload.get("options") or {}).get("num_ctx")
        key = (str(payload.get("model")).strip(), ctx)
        prompt_chars = sum(len(str(m.get("content") or "")) for m in payload.get("messages") or [])
        with LOCK:
            reload = STATE["loaded"] != key
            STATE["loaded"] = key
        prompt_cost = (LOAD_SECONDS if reload else 0.0) + prompt_chars / PROMPT_CHARS_PER_SECOND

        text, tool = None, None
        user = last_user(payload)
        for r in rules():
            if r.get("purpose", "chat") == purpose and r.get("match", "") in user:
                if r.get("tool") and not has_tool_result(payload):
                    tool = r["tool"]
                text = r.get("reply")
                break
        if text is None and tool is None:
            if purpose == "provenance_classifier":
                text = '{"provenance": false}'
            elif purpose == "research_planner":
                text = '{"research": false}'
            elif purpose == "memory_extraction":
                text = "[]"
            elif purpose == "document_summary":
                text = "Summary of the supplied source: " + " ".join(user.split()[:40])
            elif purpose == "json_other":
                text = "{}"
            else:
                text = "Fake Gemma heard you."
        text = text or ""
        words = text.split(" ")
        if "long story" in user and purpose == "chat":
            words = ["word"] * 80
        gen_cost = len(words) / WORDS_PER_SECOND

        record = {"t": time.time(), "purpose": purpose, "model": key[0], "num_ctx": ctx, "prompt_chars": prompt_chars,
                  "messages": len(payload.get("messages") or []), "reload": reload, "stream": bool(payload.get("stream")),
                  "tools": len(payload.get("tools") or []), "think": payload.get("think"),
                  "simulated_seconds": round(prompt_cost + gen_cost, 3), "user_tail": user[-160:]}
        with LOCK, open(LOG_PATH, "a") as f:
            f.write(json.dumps(record) + "\n")
        if os.environ.get("FAKE_DUMP"):
            with LOCK, open(os.environ["FAKE_DUMP"], "a") as f:
                f.write(json.dumps({"purpose": purpose, "messages": payload.get("messages"), "tools": [t.get("function", {}).get("name") for t in payload.get("tools") or []]}) + "\n")

        time.sleep(prompt_cost * SCALE)
        if tool:
            msg = {"role": "assistant", "content": "", "tool_calls": [{"function": {"name": tool["name"], "arguments": tool.get("arguments") or {}}}]}
            if payload.get("stream"):
                self.send_response(200)
                self.send_header("Content-Type", "application/x-ndjson")
                self.end_headers()
                self.wfile.write((json.dumps({"message": msg, "done": True}) + "\n").encode())
                self.wfile.flush()
                self.close_connection = True
                return
            return self._send({"message": msg, "done": True})
        if payload.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            try:
                for i, w in enumerate(words):
                    piece = w if i == len(words) - 1 else w + " "
                    self.wfile.write((json.dumps({"message": {"content": piece}}) + "\n").encode())
                    self.wfile.flush()
                    time.sleep(SCALE / WORDS_PER_SECOND * (3 if "long story" in user else 1))
                self.wfile.write((json.dumps({"message": {"content": ""}, "done": True, "eval_count": len(words)}) + "\n").encode())
            except Exception:
                pass
            self.close_connection = True
            return
        time.sleep(gen_cost * SCALE)
        self._send({"message": {"role": "assistant", "content": text}, "done": True, "eval_count": len(words)})


def serve(ports):
    for port in ports:
        server = ThreadingHTTPServer(("127.0.0.1", port), H)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()


if __name__ == "__main__":
    serve([int(p) for p in (sys.argv[1:] or ["11434", "8899"])])
    print("fake ollama ready", flush=True)
    threading.Event().wait()
