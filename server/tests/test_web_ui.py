"""
Drives the real web client in a real browser (Chromium via Playwright) against a real server,
the stand-in model and a stand-in ComfyUI, and checks what Glen would see.
    python3 tests/test_web_ui.py            screenshots go to tests/results/ui-*.png
"""
import json, os, subprocess, sys, tempfile, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from harness import Live, KEY, free_port    # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

from fixtures import WORKFLOW  # noqa: E402


OUT = HERE / "results"
OUT.mkdir(exist_ok=True)
checks = 0


def ok(cond, what):
    global checks
    checks += 1
    print(("ok   " if cond else "FAIL ") + what, flush=True)
    if not cond:
        raise AssertionError("FAILED: " + what)


CODE_REPLY = ("Here's a small player controller.\n\n```gdscript player.gd\nextends CharacterBody2D\n\nconst SPEED = 300.0\n\n"
              "func _physics_process(delta):\n\tvar dir = Input.get_axis(\"left\", \"right\")\n\tvelocity.x = dir * SPEED  # move\n\tmove_and_slide()\n```\n\n"
              "And the Python helper:\n\n```python\ndef add(a, b):\n    \"\"\"Add two numbers.\"\"\"\n    return a + b\n```\n\n| Key | Action |\n|---|---|\n| A | left |\n| D | right |\n")
LONG = " ".join(f"Sentence number {i} of a long answer that keeps going." for i in range(260))

comfy_port = free_port()
comfy_log = Path(tempfile.mkdtemp()) / "comfy.jsonl"
comfy = subprocess.Popen([sys.executable, str(HERE / "fake_comfy.py"), str(comfy_port), "12", "0.35"],
                         env=dict(os.environ, FAKE_COMFY_LOG=str(comfy_log)), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
repo = Path(tempfile.mkdtemp(prefix="gemma-ui-repo-"))
subprocess.run(["git", "init", "-q", "-b", "main", str(repo)], check=True)
(repo / "a.txt").write_text("one\n")
subprocess.run(["git", "-C", str(repo), "-c", "user.email=t@t", "-c", "user.name=t", "add", "."], check=True)
subprocess.run(["git", "-C", str(repo), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "first"], check=True)
(repo / "a.txt").write_text("two\n")
subprocess.run(["git", "-C", str(repo), "config", "user.email", "glen@example.com"], check=True)
subprocess.run(["git", "-C", str(repo), "config", "user.name", "Glen"], check=True)

live = Live(env={"GEMMA_COMFY_URL": f"http://127.0.0.1:{comfy_port}", "GEMMA_IMAGE_FREE_LLM": "never"},
            files={"workflows/image_generate.json": json.dumps(WORKFLOW)}, scale="0.05")
live.call("POST", "/api/v1/plugins/local_files/settings", {"roots": [{"path": str(repo.parent), "mode": "write"}]})
live.set_rules([
    {"match": "controller", "reply": CODE_REPLY},
    {"match": "utility file", "steps": [{"tool": {"name": "create_file", "arguments": {"filename": "utility.py", "content": "def hello():\n    return 'hi'\n"}}},
                                        {"reply": "Here's **utility.py**."}]},
    {"match": "commit", "steps": [{"tool": {"name": "git_commit", "arguments": {"repo": str(repo), "message": "Update a.txt"}}}, {"reply": "Ready when you are."}]},
    {"match": "lighthouse", "steps": [{"tool": {"name": "generate_image", "arguments": {"prompt": "a lighthouse at dusk"}}}, {"reply": "Making it now."}]},
    {"match": "long story", "reply": LONG},
])
errors = []
try:
    with sync_playwright() as p:
        b = p.chromium.launch()
        ctx = b.new_context(viewport={"width": 1400, "height": 900})
        pg = ctx.new_page()
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: errors.append("console: " + m.text) if m.type == "error" and "401" not in m.text else None)

        # ---- key gate
        pg.goto(live.base + "/")
        pg.wait_for_selector("#keyGate.show", timeout=5000)
        ok(pg.is_visible("#keyInput"), "without a key the page asks for one")
        pg.fill("#keyInput", "wrong-key")
        pg.click("#keySave")
        pg.wait_for_timeout(400)
        ok("not accepted" in pg.inner_text("#keyErr"), "a wrong key is refused")
        pg.fill("#keyInput", KEY)
        pg.click("#keySave")
        pg.wait_for_selector("#keyGate", state="hidden", timeout=5000)
        ok(pg.evaluate("localStorage.getItem('gemma_key')") == KEY, "the key is kept in this browser")
        pg.wait_for_timeout(600)
        pg.screenshot(path=str(OUT / "ui-01-empty.png"))

        def say(text, wait_done=True):
            pg.fill("#input", text)
            pg.click("#send")
            if wait_done:
                pg.wait_for_function("() => !document.querySelector('#send').classList.contains('stop')", timeout=30000)
                pg.wait_for_timeout(300)

        # ---- code blocks, markdown, timestamps on both sides
        say("Write me a GDScript player controller")
        ok(pg.locator(".row.user .bubble").last.inner_text() == "Write me a GDScript player controller", "my message shows as I typed it")
        ok(pg.locator(".row.user [data-created-at]").count() >= 1 and pg.locator(".row.user .when").last.inner_text() == "Just now",
           "my message has a relative timestamp ('Just now')")
        ok(pg.locator(".row.assistant .when").last.inner_text() == "Just now", "Gemma's reply has one too")
        ok(pg.locator(".row.user .speak").count() == 0 and pg.locator(".row.assistant .speak").count() >= 1, "play button only on Gemma's replies")
        ok(pg.locator(".code").count() == 2, "two code blocks rendered")
        ok(pg.locator(".code .file").first.inner_text() == "player.gd", "the code block shows its filename")
        ok(pg.locator(".code").first.locator(".tok-k").count() >= 3, "GDScript keywords are highlighted")
        ok(pg.locator(".code").nth(1).locator(".tok-s").count() >= 1 and pg.locator(".code").nth(1).locator(".tok-k").count() >= 2, "Python is highlighted")
        ok("\tvar dir" in pg.locator(".code pre").first.inner_text(), "indentation (tabs) is kept exactly")
        ok(pg.locator(".md table").count() == 1, "markdown table renders")
        ctx.grant_permissions(["clipboard-read", "clipboard-write"])
        pg.locator(".code [data-a=copy]").first.click()
        pg.wait_for_timeout(200)
        ok(pg.evaluate("navigator.clipboard.readText()").startswith("extends CharacterBody2D"), "Copy code copies exactly the code")
        pg.screenshot(path=str(OUT / "ui-02-code.png"))

        # ---- file artifact card and preview
        say("Make me a utility file")
        ok(pg.locator(".card.file b", has_text="utility.py").count() == 1, "the file Gemma made appears as a file card")
        pg.locator(".card.file .nm").last.click()
        pg.wait_for_selector("#modal.show", timeout=5000)
        ok("def hello" in pg.inner_text("#modalBody") and pg.locator("#modalBody .tok-k").count() >= 1, "opening the file previews it with highlighting")
        pg.screenshot(path=str(OUT / "ui-03-file-preview.png"))
        pg.click("#modalClose")

        # ---- approval card: nothing happens until allowed; allowed once
        say("Please commit that")
        ok(pg.locator(".card.approval").count() == 1 and pg.locator(".card.approval [data-a=once]").is_visible(), "a commit shows an approval card")
        log = subprocess.run(["git", "-C", str(repo), "log", "--oneline"], capture_output=True, text=True).stdout
        ok(log.count("\n") == 1, "nothing committed before I allow it")
        pg.screenshot(path=str(OUT / "ui-04-approval.png"))
        pg.click(".card.approval [data-a=once]")
        pg.wait_for_selector(".card.approval .state", timeout=10000)
        ok(pg.inner_text(".card.approval .state") == "Done", "the card says it was done")
        log = subprocess.run(["git", "-C", str(repo), "log", "--oneline"], capture_output=True, text=True).stdout
        ok(log.count("\n") == 2 and "Update a.txt" in log, "the commit happened exactly once")

        # ---- picture: a real percentage from ComfyUI, then the picture
        say("Generate an image of a lighthouse")
        pg.wait_for_selector(".card.job", timeout=5000)
        seen = set()
        for _ in range(80):
            t = pg.locator(".card.job .pct").first.inner_text() if pg.locator(".card.job").count() else ""
            if t.endswith("%"):
                seen.add(t)
                if len(seen) == 3:
                    pg.screenshot(path=str(OUT / "ui-05-image-progress.png"))
            if pg.locator(".card.imgcard img[src^='blob:']").count():
                break
            pg.wait_for_timeout(150)
        ok(len(seen) >= 3, f"the card showed several real percentages ({sorted(seen)})")
        pg.wait_for_selector(".card.imgcard img[src^='blob:']", timeout=15000)
        ok(pg.locator(".card.imgcard .cap").last.inner_text() == "a lighthouse at dusk", "the finished picture replaces the card, with its prompt")
        pg.wait_for_timeout(400)
        pg.screenshot(path=str(OUT / "ui-06-image-done.png"))

        # ---- scrolling: no yanking while I read; the down-arrow appears
        say("Tell me a long story", wait_done=False)
        pg.wait_for_function("() => document.querySelector('.row.assistant:last-child .bubble').innerText.length > 200", timeout=20000)
        pg.evaluate("document.getElementById('scroller').scrollTop = 0")
        pg.wait_for_timeout(250)
        top_before = pg.evaluate("document.getElementById('scroller').scrollTop")
        pg.wait_for_timeout(900)
        top_after = pg.evaluate("document.getElementById('scroller').scrollTop")
        ok(top_before == top_after, f"streaming does not pull me back down while I'm reading ({top_before} → {top_after})")
        ok(pg.is_visible("#jump"), "the jump-to-latest button appears")
        pg.screenshot(path=str(OUT / "ui-07-scrolled-up.png"))
        pg.wait_for_function("() => !document.querySelector('#send').classList.contains('stop')", timeout=60000)
        pg.click("#jump")
        pg.wait_for_timeout(700)
        ok(pg.evaluate("(()=>{const s=document.getElementById('scroller');return s.scrollHeight-s.scrollTop-s.clientHeight<5})()"), "the button takes me to the latest message")
        ok(not pg.is_visible("#jump"), "and then hides")

        # ---- reload: everything is still there (from the server, not the page)
        pg.reload()
        pg.wait_for_selector(".row.assistant", timeout=8000)
        pg.wait_for_timeout(800)
        ok(pg.locator(".code").count() >= 2 and pg.locator(".card.file b", has_text="utility.py").count() == 1, "after a reload code and files are still shown")
        ok(pg.locator(".card.approval .state").first.inner_text() == "Done", "the approval keeps its outcome after a reload")
        ok(pg.locator(".card.imgcard .cap", has_text="a lighthouse at dusk").count() == 1, "the picture is still there after a reload, shown once")
        nu = pg.locator(".row.user").count()
        cid = pg.evaluate("S.cid")
        ok(nu >= 5 and pg.locator(".row.user .when").count() == nu, f"every one of my messages has a timestamp after a reload ({nu})")

        # ---- panels
        pg.click(".side-foot button[data-panel=activity]")
        pg.wait_for_timeout(600)
        ok(pg.locator("#panelBody .card.job").count() >= 1, "Activity lists the background task")
        pg.screenshot(path=str(OUT / "ui-08-activity.png"))
        pg.click(".side-foot button[data-panel=settings]")
        pg.wait_for_timeout(1500)
        ok(pg.locator("#panelBody .li").count() >= 6, "Settings lists the connections with their state")
        pg.screenshot(path=str(OUT / "ui-09-settings.png"))
        pg.click("#panelClose")

        # ---- search
        pg.fill("#searchBox", "lighthouse")
        pg.wait_for_timeout(700)
        ok(pg.locator("#chatList .chat-item").count() >= 1, "search finds the chat")
        pg.fill("#searchBox", "")

        # ---- phone width
        mob = ctx.new_page()
        mob.set_viewport_size({"width": 390, "height": 844})
        mob.goto(live.base + "/")
        mob.wait_for_selector(".row.assistant", timeout=8000)
        mob.wait_for_timeout(700)
        overflow = mob.evaluate("document.documentElement.scrollWidth > window.innerWidth")
        ok(not overflow, "no sideways scrolling at phone width")
        mob.screenshot(path=str(OUT / "ui-10-phone.png"))
        b.close()
    ok(not errors, f"no script errors in the page ({errors[:5]})")
    print(f"\nweb UI: {checks} checks passed")
finally:
    live.stop() if hasattr(live, "stop") else None
    comfy.kill()
