#!/usr/bin/env python3
"""
For the emulator test: the REAL Gemma server (server/app.py) on port 7862, with the
stand-in model (server/tests/fake_ollama.py) and stand-in ComfyUI behind it, so the
Android app is driven end to end against the same code that runs on Vision.
    python3 tools/ci_server.py          (runs until killed; prints "ci server ready")
"""
import json, os, shutil, subprocess, sys, tempfile, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER, TESTS = ROOT / "server", ROOT / "server" / "tests"
KEY = "emulator-key-0123456789"
PORT, OLLAMA, COMFY = 7862, 11500, 11501

work = Path(tempfile.mkdtemp(prefix="gemma-ci-"))
for name in ("app.py",):
    shutil.copy(SERVER / name, work / name)
for sub in ("gemma_core", "web", "skills"):
    shutil.copytree(SERVER / sub, work / sub, ignore=shutil.ignore_patterns("__pycache__"))
sys.path.insert(0, str(TESTS))
from fixtures import WORKFLOW  # noqa: E402  (the same Qwen-style workflow the server tests use)
(work / "workflows").mkdir()
(work / "workflows" / "image_generate.json").write_text(json.dumps(WORKFLOW))

repo = work / "proj"
repo.mkdir()
git = lambda *a: subprocess.run(["git", "-C", str(repo), "-c", "user.email=ci@example.com", "-c", "user.name=CI", *a], check=True, capture_output=True)
subprocess.run(["git", "init", "-q", "-b", "main", str(repo)], check=True)
(repo / "a.txt").write_text("one\n")
git("add", ".")
git("commit", "-qm", "first")
(repo / "a.txt").write_text("two\n")
git("config", "user.email", "glen@example.com")
git("config", "user.name", "Glen")

CODE = ("Here's a player controller.\n\n```gdscript player.gd\nextends CharacterBody2D\n\nconst SPEED = 300.0\n\n"
        "func _physics_process(delta):\n\tvelocity.x = Input.get_axis(\"left\", \"right\") * SPEED\n\tmove_and_slide()\n```\n")
rules = [
    {"match": "Hello Gem", "reply": "Fake Gemma heard you: Hello Gem."},
    {"match": "controller", "reply": CODE},
    {"match": "utility file", "steps": [{"tool": {"name": "create_file", "arguments": {"filename": "utility.py", "content": "def hello():\n    return 'hi'\n"}}},
                                        {"reply": "Here's utility.py."}]},
    {"match": "commit", "steps": [{"tool": {"name": "git_commit", "arguments": {"repo": str(repo), "message": "Update a.txt"}}}, {"reply": "Ready when you are."}]},
    {"match": "lighthouse", "steps": [{"tool": {"name": "generate_image", "arguments": {"prompt": "a lighthouse at dusk"}}}, {"reply": "Making it now."}]},
]
(work / "rules.json").write_text(json.dumps(rules))
env = dict(os.environ, FAKE_SCALE="1", FAKE_RULES=str(work / "rules.json"), FAKE_LOG=str(work / "fake.jsonl"))
procs = [
    subprocess.Popen([sys.executable, str(TESTS / "fake_ollama.py"), str(OLLAMA)], env=env),
    subprocess.Popen([sys.executable, str(TESTS / "fake_comfy.py"), str(COMFY), "12", "0.5"], env=dict(os.environ, FAKE_COMFY_LOG=str(work / "comfy.jsonl"))),
]
time.sleep(1)
procs.append(subprocess.Popen([sys.executable, "-u", "app.py"], cwd=work, env=dict(
    os.environ, GEMMA_PORT=str(PORT), GEMMA_HOST="127.0.0.1", GEMMA_TOKEN=KEY, GEMMA_OLLAMA_URL=f"http://127.0.0.1:{OLLAMA}",
    GEMMA_COMFY_URL=f"http://127.0.0.1:{COMFY}", GEMMA_IMAGE_FREE_LLM="never", GEMMA_GEOCODER_URL="", GEMMA_TTS_URL="http://127.0.0.1:9",
    GEMMA_BACKGROUND="off", GEMMA_VOICE_DIAG="off")))
for _ in range(200):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{PORT}/api/v1/health", timeout=1)
        break
    except Exception:
        time.sleep(0.2)
req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/v1/plugins/local_files/settings", method="POST",
                             data=json.dumps({"roots": [{"path": str(work), "mode": "write"}]}).encode(),
                             headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
urllib.request.urlopen(req, timeout=10)
print("ci server ready", work, flush=True)
try:
    procs[-1].wait()
finally:
    for p in procs:
        p.kill()
