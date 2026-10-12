"""
Platform tests against a real server, the stand-in model, a stand-in ComfyUI,
a stand-in Claude Code and a real (tiny) MCP server.
    python3 tests/test_platform.py
"""
import base64, io, json, os, subprocess, sys, tempfile, time, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from harness import Live, free_port    # noqa: E402

checks = 0


def ok(cond, what):
    global checks
    checks += 1
    if not cond:
        raise AssertionError("FAILED: " + what)


def done_of(events):
    d = [e for e in events if e["type"] == "done"]
    return d[0] if d else {}


def wait_job(live, jid, timeout=60, states=("done", "failed", "cancelled", "interrupted")):
    end = time.time() + timeout
    while time.time() < end:
        j = live.call("GET", f"/api/v1/jobs/{jid}")[1]
        if j["state"] in states:
            return j
        time.sleep(0.2)
    raise AssertionError(f"job {jid} did not finish: {j}")


WORKFLOW = {
    "3": {"class_type": "KSampler", "inputs": {"seed": 1, "steps": 20, "cfg": 2.5, "positive": ["6", 0], "negative": ["7", 0],
                                                "latent_image": ["5", 0], "model": ["4", 0]}},
    "4": {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_fp8.safetensors"}},
    "5": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
    "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "placeholder", "clip": ["8", 0]}},
    "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["8", 0]}},
    "8": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_2.5_vl.safetensors"}},
    "10": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0]}},
    "9": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "gemma"}},
}

comfy_port = free_port()
comfy_log = Path(tempfile.mkdtemp()) / "comfy.jsonl"
comfy = subprocess.Popen([sys.executable, str(HERE / "fake_comfy.py"), str(comfy_port), "10", "0.12"],
                         env=dict(os.environ, FAKE_COMFY_LOG=str(comfy_log)), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(0.5)
root = Path(tempfile.mkdtemp(prefix="gemma-root-"))
repo = root / "proj"
repo.mkdir()
subprocess.run(["git", "init", "-q", "-b", "main", str(repo)], check=True)
(repo / "calc.py").write_text("def add(a, b):\n    return a - b\n")
subprocess.run(["git", "-C", str(repo), "-c", "user.email=t@t", "-c", "user.name=t", "add", "."], check=True)
subprocess.run(["git", "-C", str(repo), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "first"], check=True)
subprocess.run(["git", "-C", str(repo), "config", "user.email", "glen@example.com"], check=True)
subprocess.run(["git", "-C", str(repo), "config", "user.name", "Glen"], check=True)

live = Live(env={"GEMMA_COMFY_URL": f"http://127.0.0.1:{comfy_port}", "GEMMA_CLAUDE": str(HERE / "stubs" / "claude"),
                 "GEMMA_IMAGE_FREE_LLM": "never"},
            files={"workflows/image_generate.json": json.dumps(WORKFLOW),
                   "mcp.json": json.dumps({"mcpServers": {"echo": {"command": sys.executable, "args": [str(HERE / "stubs" / "mcp_echo.py")],
                                                                    "mutations": False}}})})
try:
    cid = live.new_chat("platform")
    live.call("POST", "/api/v1/plugins/local_files/settings", {"roots": [{"path": str(root), "mode": "write"}]})

    # ---- 1. a real file instead of pasted code
    live.set_rules([{"match": "Python utility", "steps": [
        {"tool": {"name": "create_file", "arguments": {"filename": "utility.py", "content": "def hello():\n    return 'hi'\n"}}},
        {"reply": "Here's utility.py."}]}])
    ev = live.turn(cid, "Write me a Python utility that says hi and give me the file.")
    d = done_of(ev)
    ok(d.get("artifacts") and d["artifacts"][0]["filename"] == "utility.py", "the reply carries the utility.py file card")
    aid = d["artifacts"][0]["id"]
    st, body = live.call("GET", f"/api/v1/artifacts/{aid}/content")
    ok(st == 200 and b"def hello" in body, "the file downloads with its content")
    st, pv = live.call("GET", f"/api/v1/artifacts/{aid}/preview")
    ok(pv["language"] == "python" and "def hello" in pv["content"], "preview gives highlighted-code text and language")
    msgs = live.call("GET", f"/api/v1/conversations/{cid}/messages")[1]["messages"]
    ok(any(a["id"] == aid for m in msgs for a in m.get("artifacts", [])), "the file is still on the message after reloading the chat")

    # ---- 2. GDScript file and a ZIP with folders
    live.set_rules([{"match": "player script", "steps": [
        {"tool": {"name": "create_archive", "arguments": {"filename": "game.zip", "files": [
            {"path": "scripts/player.gd", "content": "extends CharacterBody2D\n\nfunc _physics_process(delta):\n\tmove_and_slide()\n"},
            {"path": "README.md", "content": "# Game\n"}]}}}, {"reply": "Zipped."}]}])
    d = done_of(live.turn(cid, "Make me a player script and a readme, zipped."))
    z = d["artifacts"][0]
    data = live.call("GET", f"/api/v1/artifacts/{z['id']}/content")[1]
    names = zipfile.ZipFile(io.BytesIO(data)).namelist()
    ok(sorted(names) == ["README.md", "scripts/player.gd"], f"ZIP keeps the folder structure ({names})")

    # ---- 3. documents: PDF, DOCX, CSV
    for fmt, magic in (("pdf", b"%PDF"), ("docx", b"PK"), ("csv", b"Item")):
        live.set_rules([{"match": f"as {fmt}", "steps": [{"tool": {"name": "make_document", "arguments": {
            "format": fmt, "title": "Summary", "content": "# Summary\n\n- one\n- two\n\n| Item | Cost |\n|---|---|\n| Tea | 4 |"}}}, {"reply": "Done."}]}])
        d = done_of(live.turn(cid, f"Save that as {fmt} please"))
        art = d["artifacts"][0]
        blob = live.call("GET", f"/api/v1/artifacts/{art['id']}/content?download=1")[1]
        ok(blob.startswith(magic), f"{fmt} is a real {fmt} file")

    # ---- 4. upload a broken script, fix it in a workspace, test, diff, give it back
    broken = "def add(a, b):\n    return a - b\n"
    test = "from calc import add\n\nassert add(2, 3) == 5, 'add(2, 3) should be 5'\nprint('1 passed')\n"
    HAVE_PYTEST = subprocess.run([sys.executable, "-c", "import pytest"], capture_output=True).returncode == 0
    up = live.call("POST", f"/api/v1/artifacts?conversation_id={cid}", raw=broken.encode(), headers={"X-Filename": "calc.py"})[1]
    up2 = live.call("POST", f"/api/v1/artifacts?conversation_id={cid}", raw=test.encode(), headers={"X-Filename": "test_calc.py"})[1]
    live.set_rules([{"match": "Fix this", "steps": [
        {"tool": {"name": "workspace_open", "arguments": {"artifact_id": up["id"], "name": "calc"}}},
        {"tool": {"name": "workspace_write", "arguments": {"workspace": "{w}", "path": "test_calc.py", "content": test}}},
        {"tool": {"name": "workspace_run", "arguments": {"workspace": "{w}", "action": "pytest"}}},
        {"tool": {"name": "workspace_run", "arguments": {"workspace": "{w}", "action": "run_python", "target": "test_calc.py"}}},
        {"tool": {"name": "workspace_write", "arguments": {"workspace": "{w}", "path": "calc.py", "content": "def add(a, b):\n    return a + b\n"}}},
        {"tool": {"name": "workspace_run", "arguments": {"workspace": "{w}", "action": "run_python", "target": "test_calc.py"}}},
        {"tool": {"name": "workspace_diff", "arguments": {"workspace": "{w}"}}},
        {"tool": {"name": "workspace_export_file", "arguments": {"workspace": "{w}", "path": "calc.py", "filename": "calc_fixed.py"}}},
        {"reply": "Fixed and tested."}]}])
    ev = live.turn(cid, "Fix this and give it back to me.", extra={"artifact_ids": [up["id"]]})
    d = done_of(ev)
    reqs = [r for r in live.chat_requests() if r["purpose"] == "chat"]
    tool_msgs = [m for m in reqs[-1]["messages"] if m["role"] == "tool"]
    runs = [json.loads(m["content"]) for m in tool_msgs if '"action":' in m["content"]]
    pt = [r for r in runs if r.get("action") == "pytest"]
    if not HAVE_PYTEST:
        ok(pt and pt[0]["ran"] is False and "not installed" in pt[0]["reason"], "a missing pytest is reported, not faked")
    pyt = [r for r in runs if r.get("action") == "run_python"]
    ok(len(pyt) == 2 and pyt[0]["exit_code"] != 0 and "should be 5" in pyt[0]["output"], f"the test really ran and failed before the fix ({pyt[:1]})")
    ok(pyt[1]["exit_code"] == 0 and "1 passed" in pyt[1]["output"], "the test really ran and passed after the fix")
    diff = next(json.loads(m["content"]) for m in tool_msgs if '"diff"' in m["content"] and '"files"' in m["content"])
    calc = next(f for f in diff["files"] if f["path"] == "calc.py")
    ok(calc["additions"] == 1 and calc["deletions"] == 1 and calc["by"] == ["gemma"], f"diff shows the change and who made it ({calc})")
    fixed = next(a for a in d["artifacts"] if a["filename"] == "calc_fixed.py")
    ok(b"a + b" in live.call("GET", f"/api/v1/artifacts/{fixed['id']}/content")[1], "calc_fixed.py comes back fixed")
    ok(fixed["provenance"].get("workspace"), "the returned file records where it came from")
    wid = fixed["provenance"]["workspace"]
    st, wsd = live.call("GET", f"/api/v1/workspaces/{wid}/diff")
    ok(st == 200 and "+    return a + b" in wsd["diff"], "the review endpoint shows the real diff")

    # ---- 5. Claude Code: delegate, observed progress, provenance, report back
    live.set_rules([{"match": "get Claude", "steps": [
        {"tool": {"name": "workspace_open", "arguments": {"artifact_id": up["id"], "name": "calc2"}}},
        {"tool": {"name": "claude_code", "arguments": {"workspace": "{w}", "mode": "implement", "task": "Fix add()", "acceptance": "pytest passes"}}},
        {"reply": "Claude is on it."}]},
        {"match": "[Background task finished", "reply": "Claude fixed add() and the tests passed."}])
    d = done_of(live.turn(cid, "Can you get Claude to fix this?", extra={"artifact_ids": [up["id"]]}))
    ok(d.get("jobs") and d["jobs"][0]["kind"] == "claude_code", "Claude Code runs as a background job on the reply")
    j = wait_job(live, d["jobs"][0]["id"])
    ok(j["state"] == "done" and "Fixed add()" in j["result"]["summary"], f"Claude Code's own result is returned ({j['state']}: {j.get('error')})")
    ok(any(c["path"] == "calc.py" and c["action"] == "modified" for c in j["result"]["files_changed"]), "the file Claude changed is recorded")
    evs = live.call("GET", f"/api/v1/jobs/{j['id']}/events")[1]["events"]
    ok(any("editing calc.py" in e["text"] for e in evs), "progress shows what Claude was observed doing")
    time.sleep(1.5)
    msgs = live.call("GET", f"/api/v1/conversations/{cid}/messages?limit=200")[1]["messages"]
    ok(any(m["role"] == "assistant" and "Claude fixed add()" in m["text"] for m in msgs), "Gemma reports back in the chat when Claude finishes")
    with __import__("sqlite3").connect(live.dir / "gemma.db") as con:
        actors = [r[0] for r in con.execute("SELECT DISTINCT actor FROM workspace_changes")]
    ok("claude_code" in actors and "gemma" in actors, f"provenance distinguishes Gemma's and Claude's changes ({actors})")

    # ---- 6. approvals: discussing doesn't commit; asking does, once
    (repo / "calc.py").write_text("def add(a, b):\n    return a + b\n")
    live.set_rules([{"match": "commit my fix", "steps": [
        {"tool": {"name": "git_commit", "arguments": {"repo": str(repo), "message": "Fix add"}}}, {"reply": "Ready for your OK."}]}])
    live.turn(cid, "Should I commit this, do you think?")
    ok(subprocess.run(["git", "-C", str(repo), "log", "--oneline"], capture_output=True, text=True).stdout.count("\n") == 1, "discussing a commit commits nothing")
    d = done_of(live.turn(cid, "Please commit my fix."))
    ok(d.get("approvals") and d["approvals"][0]["state"] == "pending", "a commit becomes an approval card")
    ok(subprocess.run(["git", "-C", str(repo), "log", "--oneline"], capture_output=True, text=True).stdout.count("\n") == 1, "nothing committed before approval")
    pid = d["approvals"][0]["id"]
    a1 = live.call("POST", f"/api/v1/approvals/{pid}/approve", {})[1]
    a2 = live.call("POST", f"/api/v1/approvals/{pid}/approve", {})[1]
    log = subprocess.run(["git", "-C", str(repo), "log", "--oneline"], capture_output=True, text=True).stdout
    ok(a1["state"] == "executed" and log.count("\n") == 2 and "Fix add" in log, f"approving commits exactly once ({a1['state']})")
    ok(a2["state"] == "executed", "a second approve does nothing more")
    live.set_rules([{"match": "anything new", "reply": "ok"}])
    live.turn(cid, "anything new?")
    sysmsg = [r for r in live.chat_requests() if r["purpose"] == "chat"][-1]["messages"][0]["content"]
    ok("was approved and done" in sysmsg, "Gemma is told the approved action really happened")

    # ---- 7. images: real progress, percentage, artifact, cancel
    live.set_rules([{"match": "Generate an image", "steps": [
        {"tool": {"name": "generate_image", "arguments": {"prompt": "a lighthouse at dusk", "aspect": "landscape"}}}, {"reply": "Making it!"}]}])
    d = done_of(live.turn(cid, "Generate an image of a lighthouse"))
    jid = d["jobs"][0]["id"]
    j = wait_job(live, jid)
    evs = live.call("GET", f"/api/v1/jobs/{jid}/events")[1]["events"]
    texts = [e["text"] for e in evs]
    pcts = [e["data"].get("fraction") for e in evs if e["data"] and e["data"].get("phase") == "generating" and e["data"].get("step")]
    ok(j["state"] == "done", f"image job finished ({j.get('error')})")
    ok(any(t.startswith("Generating… 50%") for t in texts) and len(pcts) == 10 and pcts == sorted(pcts), "percentages follow ComfyUI's own step counter")
    ok("Loading model…" in texts and "Finalising…" in texts, "loading and finalising states come from ComfyUI events")
    img = live.call("GET", f"/api/v1/artifacts/{j['result']['artifact_id']}")[1]
    ok(img["kind"] == "image" and img["provenance"]["prompt"] == "a lighthouse at dusk" and img["provenance"]["width"] == 1664, "image artifact keeps prompt and size")
    sent = [json.loads(l) for l in comfy_log.read_text().splitlines() if '"prompt"' in l and "class_type" in l][-1]["prompt"]
    ok(sent["6"]["inputs"]["text"] == "a lighthouse at dusk" and sent["5"]["inputs"]["width"] == 1664, "prompt and size went into the workflow's own nodes")
    msgs = live.call("GET", f"/api/v1/conversations/{cid}/messages?limit=200")[1]["messages"]
    ok(any(a["id"] == img["id"] for m in msgs for a in m.get("artifacts", [])), "the picture is attached to the reply after it finishes")
    d = done_of(live.turn(cid, "Generate an image of a lighthouse"))
    jid2 = d["jobs"][0]["id"]
    time.sleep(0.6)
    live.call("POST", f"/api/v1/jobs/{jid2}/cancel")
    j2 = wait_job(live, jid2)
    for _ in range(20):
        if '"interrupted"' in comfy_log.read_text():
            break
        time.sleep(0.1)
    if '"interrupted"' not in comfy_log.read_text():
        print("DEBUG job events:", [e["text"] for e in live.call("GET", f"/api/v1/jobs/{jid2}/events")[1]["events"]])
        print("DEBUG comfy:", comfy_log.read_text().splitlines()[-6:])
    ok(j2["state"] == "cancelled" and '"interrupted"' in comfy_log.read_text(), f"cancelling stops the ComfyUI job ({j2['state']})")

    # ---- 8. plugins: MCP server, disconnected Google, live GitHub read
    live.set_rules([{"match": "shout", "steps": [{"tool": {"name": "mcp_echo_discover", "arguments": {}}},
                                                  {"tool": {"name": "mcp_echo_shout", "arguments": {"text": "hello"}}}, {"reply": "done"}]}])
    live.turn(cid, "use echo to shout hello")
    tm = [m for m in [r for r in live.chat_requests() if r["purpose"] == "chat"][-1]["messages"] if m["role"] == "tool"]
    ok(any("HELLO" in m["content"] for m in tm), "an MCP server's tool was discovered and used")
    live.set_rules([{"match": "inbox", "steps": [{"tool": {"name": "gmail_needs_attention", "arguments": {}}}, {"reply": "Can't."}]}])
    live.turn(cid, "Anything in my inbox?")
    tm = [m for m in [r for r in live.chat_requests() if r["purpose"] == "chat"][-1]["messages"] if m["role"] == "tool"]
    ok(any("not_connected" in m["content"] for m in tm), "a disconnected plugin says so instead of inventing email")
    gh = live.call("GET", "/api/v1/plugins")[1]
    if next(p for p in gh["plugins"] if p["id"] == "github")["health"]["status"] == "ok":
        live.set_rules([{"match": "on GitHub", "steps": [{"tool": {"name": "github_repo", "arguments": {"repo": "ufo8mycow16/gemma-android"}}}, {"reply": "ok"}]}])
        live.turn(cid, "What's the default branch of gemma-android on GitHub?")
        tm = [m for m in [r for r in live.chat_requests() if r["purpose"] == "chat"][-1]["messages"] if m["role"] == "tool"]
        ok(any('"default_branch": "main"' in m["content"] for m in tm), "GitHub is read live")

    # ---- 9. search, export, diagnostics
    s = live.call("GET", "/api/v1/search?q=utility")[1]["results"]
    ok(s["artifacts"] and s["conversations"], "global search finds the file and the conversation")
    exp = live.call("GET", "/api/v1/export")[1]
    zn = zipfile.ZipFile(io.BytesIO(exp)).namelist()
    ok(any(n.startswith("conversations/") for n in zn) and any("utility.py" in n for n in zn), "export holds conversations and files")
    evs = live.call("GET", "/api/v1/diagnostics/events?cats=TOOLS,PLUGIN&limit=500")[1]["events"]
    ok(evs and all(e["rid"] for e in evs if e["msg"].startswith("git_commit")), "tool calls in Diagnostics carry their request id")
    ok(not any("test-key-0123" in json.dumps(e) for e in live.call("GET", "/api/v1/diagnostics/events?limit=5000")[1]["events"]),
       "the access key never appears in Diagnostics")
    print(f"PLATFORM TESTS OK: {checks} checks")
finally:
    live.stop()
    comfy.terminate()
