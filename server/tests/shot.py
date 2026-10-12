"""Screenshots of the running web pages, for checking the interface by eye.
    python3 tests/shot.py <base url> <key> <path> <out.png> [width height] [--wait ms] [--js code]"""
import sys
from playwright.sync_api import sync_playwright

base, key, path, out = sys.argv[1:5]
w, h = (int(sys.argv[5]), int(sys.argv[6])) if len(sys.argv) > 6 and sys.argv[5].isdigit() else (1400, 900)
wait = int(sys.argv[sys.argv.index("--wait") + 1]) if "--wait" in sys.argv else 1500
js = sys.argv[sys.argv.index("--js") + 1] if "--js" in sys.argv else None
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": w, "height": h})
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.on("console", lambda m: errors.append("console: " + m.text) if m.type == "error" else None)
    pg.goto(base + "/api/v1/health")
    pg.evaluate(f"localStorage.setItem('gemma_key', {key!r})")
    pg.goto(base + path)
    pg.wait_for_timeout(wait)
    if js:
        pg.evaluate(js)
        pg.wait_for_timeout(800)
    pg.screenshot(path=out)
    print("errors:", errors[:10])
    b.close()
