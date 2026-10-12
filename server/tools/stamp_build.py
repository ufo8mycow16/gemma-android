#!/usr/bin/env python3
"""Write BUILD_INFO.json next to app.py (version, build number, commit, time) for copies made without git."""
import json, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path
here = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(here))
from gemma_core.observe import APP_VERSION
run = lambda *a: subprocess.run(list(a), cwd=here, capture_output=True, text=True).stdout.strip()
info = {"version": APP_VERSION, "build": int(run("git", "rev-list", "--count", "HEAD") or 0) or None,
        "commit": run("git", "rev-parse", "--short=10", "HEAD") or None,
        "dirty": bool(run("git", "status", "--porcelain", "--", ".")),
        "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
(here / "BUILD_INFO.json").write_text(json.dumps(info, indent=2) + "\n")
print(info)
