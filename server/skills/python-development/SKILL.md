---
name: python-development
description: Writing, fixing, refactoring and testing Python, including existing codebases like Gemma's own app.py.
triggers: python, .py, pip, venv, traceback, exception, pytest, flask, sqlite, asyncio, dataclass, type hint, refactor, script, module
tools: workspace, artifacts, local_git, claude_code
---
Working on Python for Glen:

Know which of these he is asking for, and do only that:
- explain: say what the code does. No files changed.
- propose: describe a change (and show the key lines). No files changed.
- patch: produce the changed file(s) as artifacts (create_file, or workspace_export_file).
- modify: change files in a workspace (workspace_write), then show the diff (workspace_diff).
- execute: run something (workspace_run). Report the real exit code and output.
- commit / push: only when he says so; both show an approval card.

Before changing an existing codebase:
1. Open it in a workspace (workspace_open for an uploaded file or ZIP, open_folder_as_workspace for a folder on Vision).
2. Read the files involved (workspace_files, workspace_search for the symbol, workspace_read). Never edit a file you have not read.
3. Find the Python version and dependencies (requirements.txt, pyproject.toml, setup.cfg, imports). Don't use syntax the target version lacks
   (Glen's Gemma runs Python 3.11: no backslashes inside f-string expressions before 3.12, `match` is fine on 3.10+).

Making the change:
- Smallest correct change; keep the existing style, names and structure. Don't rewrite working code for taste.
- Write complete files with workspace_write (the tool replaces the whole file).
- Idiomatic modern Python: pathlib, f-strings, dataclasses where they help, context managers for files/locks/DB, type hints on new
  functions, specific exceptions (never a bare `except:` that swallows errors silently), logging over print in libraries.
- SQLite: parameterised queries only, short transactions, `with` connections; never build SQL from user text.
- subprocess: argument lists, never shell=True with outside input; always a timeout.
- async: don't block the event loop (no time.sleep / requests in async def); use asyncio.to_thread for blocking work.

Proving it works:
- Run python_compile first, then the tests (pytest if installed, otherwise run_python on a test script).
- If there are no tests, write a small one for the bug you fixed.
- A traceback: read it bottom-up; find the frame in the project's own code; reproduce before fixing.
- Never say "tested", "passed" or "works" unless workspace_run actually ran and said so. If a tool is missing, say which.

Giving it back:
- Show the diff summary (files, +/− lines) and explain the change in a few sentences.
- One file: workspace_export_file (e.g. broken_script_fixed.py). A project: workspace_package (e.g. project-fixed.zip).
- Offer Claude Code for an independent review when the change is risky ("Want me to get Claude to review it?").
