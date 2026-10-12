"""Start a real Gemma server (a copy of this app.py in a temp folder) against the stand-in model."""
import base64, json, os, shutil, socket, subprocess, sys, tempfile, time, urllib.request, uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVER = HERE.parent
KEY = "test-key-0123456789abcdef"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Live:
    def __init__(self, env=None, rules=None, scale="0.02"):
        self.dir = Path(tempfile.mkdtemp(prefix="gemma-live-"))
        shutil.copy(SERVER / "app.py", self.dir / "app.py")
        for sub in ("gemma_core", "web", "plugins", "skills"):
            if (SERVER / sub).exists():
                shutil.copytree(SERVER / sub, self.dir / sub, ignore=shutil.ignore_patterns("__pycache__"))
        self.log, self.dump = self.dir / "fake.jsonl", self.dir / "dump.jsonl"
        self.rules = self.dir / "rules.json"
        self.rules.write_text(json.dumps(rules or []))
        self.oport, self.port = free_port(), free_port()
        fenv = dict(os.environ, FAKE_SCALE=scale, FAKE_LOG=str(self.log), FAKE_DUMP=str(self.dump), FAKE_RULES=str(self.rules))
        self.fake = subprocess.Popen([sys.executable, str(HERE / "fake_ollama.py"), str(self.oport)], env=fenv,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        aenv = dict(os.environ, GEMMA_PORT=str(self.port), GEMMA_TOKEN=KEY, GEMMA_OLLAMA_URL=f"http://127.0.0.1:{self.oport}",
                    GEMMA_GEOCODER_URL="", GEMMA_TTS_URL="http://127.0.0.1:9", GEMMA_BACKGROUND="off", GEMMA_VOICE_DIAG="off")
        aenv.update(env or {})
        self.boot = open(self.dir / "boot.log", "w")
        self.app = subprocess.Popen([sys.executable, "-u", "app.py"], cwd=self.dir, env=aenv, stdout=self.boot, stderr=subprocess.STDOUT)
        self.base = f"http://127.0.0.1:{self.port}"
        for _ in range(120):
            try:
                urllib.request.urlopen(self.base + "/api/v1/health", timeout=1)
                break
            except Exception:
                time.sleep(0.1)
        else:
            raise RuntimeError("server did not start:\n" + (self.dir / "boot.log").read_text()[-3000:])

    def set_rules(self, rules):
        self.rules.write_text(json.dumps(rules))

    def call(self, method, path, body=None, raw=None, headers=None, timeout=120):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + KEY)
        if raw is None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
                return r.status, (json.loads(body) if body and r.headers.get("Content-Type", "").startswith("application/json") else body)
        except urllib.error.HTTPError as e:
            body = e.read()
            try:
                return e.code, json.loads(body)
            except Exception:
                return e.code, body

    def new_chat(self, title="test"):
        return self.call("POST", "/api/v1/conversations", {"title": title})[1]["id"]

    def turn(self, cid, text, documents=None, images=None, extra=None, timeout=300):
        body = {"message": text, "turn_id": "t" + uuid.uuid4().hex[:16]}
        if documents:
            body["documents"] = documents
        if images:
            body["images"] = images
        body.update(extra or {})
        req = urllib.request.Request(f"{self.base}/api/v1/conversations/{cid}/turns", data=json.dumps(body).encode(), method="POST")
        req.add_header("Authorization", "Bearer " + KEY)
        req.add_header("Content-Type", "application/json")
        events = []
        with urllib.request.urlopen(req, timeout=timeout) as r:
            for line in r:
                if line.strip():
                    events.append(json.loads(line))
        return events

    def chat_requests(self):
        if not self.dump.exists():
            return []
        return [json.loads(l) for l in self.dump.read_text().splitlines() if l.strip()]

    def calls(self):
        if not self.log.exists():
            return []
        return [json.loads(l) for l in self.log.read_text().splitlines() if l.strip()]

    def clear_calls(self):
        for p in (self.log, self.dump):
            if p.exists():
                p.write_text("")

    def stop(self):
        for p in (self.app, self.fake):
            p.terminate()
        for p in (self.app, self.fake):
            try:
                p.wait(5)
            except Exception:
                p.kill()


def doc_payload(name, data, mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document"):
    return {"name": name, "mime": mime, "data": f"data:{mime};base64," + base64.b64encode(data).decode()}
