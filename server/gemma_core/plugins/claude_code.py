"""
Claude Code as Gemma's development colleague.

Gemma hands Claude Code a focused brief (the task, the workspace, the files
that matter, constraints, acceptance criteria, any error output) rather than
her whole conversation. Claude Code works inside a Gemma workspace (or an
allowed local repository), as a background job that survives clients closing.
Gemma reports only what was observed in Claude Code's own event stream
("editing app.py", "running pytest"); when it finishes she gets a structured
result: status, files changed with a real diff, commands run, test outcomes,
Claude's own summary, warnings and the session id (to continue later).

Never allowed: pushing, force operations, history rewrites, deleting the
repository. Committing only when the brief explicitly authorises it.

Modes:
  implement   change code and run tests
  investigate find the cause, change nothing
  review      read-only review of a diff or workspace, independent of Gemma's view
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import time

from .. import jobs, observe, store, workspace
from ..tools import Tool, ToolFailure, register
from . import Plugin, add

WORDS = ("claude", "claude code", "get claude", "ask claude", "have claude", "both of you", "review", "second opinion", "delegate")
READ_TOOLS = "Read,Grep,Glob,LS"
DENY = ["Bash(git push:*)", "Bash(git push)", "Bash(rm -rf:*)", "Bash(git reset --hard:*)", "Bash(git rebase:*)",
        "Bash(git filter-branch:*)", "Bash(curl:*)", "Bash(wget:*)", "WebFetch", "WebSearch"]


def claude_bin():
    return os.environ.get("GEMMA_CLAUDE", "") or shutil.which("claude")


def _brief(p: dict) -> str:
    mode = p.get("mode", "implement")
    lines = [f"You are working for Gemma, Glen's AI assistant, on a {mode} task in this folder.",
             "", "TASK:", p.get("task", "").strip()]
    for key, title in (("files", "FILES THAT MATTER"), ("context", "PROJECT CONTEXT"), ("errors", "ERRORS / LOGS"),
                       ("constraints", "CONSTRAINTS"), ("acceptance", "ACCEPTANCE CRITERIA"), ("diff", "DIFF TO REVIEW")):
        v = p.get(key)
        if v:
            lines += ["", f"{title}:", v if isinstance(v, str) else "\n".join(map(str, v))]
    if mode == "investigate":
        lines += ["", "Investigate only. Do not modify any files. Report the root cause with evidence (file:line) and a proposed fix."]
    elif mode == "review":
        lines += ["", "Review only; do not modify files. Give an independent review: correctness, bugs, risks, missing tests, "
                      "and concrete suggestions. Say plainly if it looks correct."]
    else:
        lines += ["", "Make the change, then run the relevant tests or checks and report their real output. "
                      + ("You may commit when done." if p.get("allow_commit") else "Do not commit.")]
    lines += ["Never push, never force anything, never rewrite history.",
              "Finish with a short summary: what you found or changed, which files, which commands you ran and their results, "
              "anything unresolved."]
    return "\n".join(lines)


def _describe_tool(name, inp):
    inp = inp or {}
    if name in ("Edit", "Write", "MultiEdit"):
        return f"editing {os.path.basename(str(inp.get('file_path', '')))}"
    if name == "Read":
        return f"reading {os.path.basename(str(inp.get('file_path', '')))}"
    if name == "Bash":
        return "running " + str(inp.get("command", ""))[:80]
    if name in ("Grep", "Glob"):
        return f"searching for {str(inp.get('pattern', ''))[:60]}"
    return name


def run_job(ctx: jobs.JobContext) -> dict:
    p = ctx.params
    exe = claude_bin()
    if not exe:
        raise RuntimeError("Claude Code is not installed on Vision (npm install -g @anthropic-ai/claude-code), or GEMMA_CLAUDE is not set.")
    wid = p.get("workspace")
    cwd = str(workspace.work_dir(wid)) if wid else p.get("repo")
    if not cwd or not os.path.isdir(cwd):
        raise RuntimeError("No workspace or repository to work in.")
    before = workspace.snapshot(wid) if wid else {}
    mode = p.get("mode", "implement")
    cmd = [exe, "-p", _brief(p), "--output-format", "stream-json", "--verbose", "--disallowedTools", ",".join(DENY)]
    if mode in ("investigate", "review"):
        cmd += ["--allowedTools", READ_TOOLS, "--permission-mode", "plan"]
    else:
        cmd += ["--permission-mode", "acceptEdits", "--allowedTools", "Read,Grep,Glob,LS,Edit,Write,MultiEdit,Bash"]
    if p.get("session_id"):
        cmd += ["--resume", p["session_id"]]
    if p.get("model"):
        cmd += ["--model", p["model"]]
    env = {k: v for k, v in os.environ.items() if not k.startswith("GEMMA_")}
    ctx.progress(0.02, "Claude Code is starting…")
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, bufsize=1)
    session, final, commands, edits, steps = None, None, [], set(), 0
    raw_log = []
    try:
        for line in proc.stdout:
            if ctx.cancelled:
                proc.terminate()
                raise jobs.Cancelled()
            raw_log.append(line[:4000])
            try:
                ev = json.loads(line)
            except Exception:
                continue
            t = ev.get("type")
            if t == "system" and ev.get("session_id"):
                session = ev["session_id"]
                ctx.log("claude", "session started", session=session)
            elif t == "assistant":
                for block in (ev.get("message") or {}).get("content", []):
                    if block.get("type") == "tool_use":
                        steps += 1
                        what = _describe_tool(block.get("name"), block.get("input"))
                        if block.get("name") == "Bash":
                            commands.append(str((block.get("input") or {}).get("command", ""))[:300])
                        if block.get("name") in ("Edit", "Write", "MultiEdit"):
                            edits.add(os.path.relpath(str((block.get("input") or {}).get("file_path", "")), cwd))
                        ctx.progress(min(0.9, 0.05 + steps * 0.03), f"Claude is {what}…", files_changed=len(edits))
            elif t == "result":
                final = ev
    finally:
        try:
            proc.wait(timeout=30)
        except Exception:
            proc.kill()
    err = (proc.stderr.read() if proc.stderr else "")[-2000:]
    log_path = store.app_dir() / "logs" / f"claude-{ctx.id}.jsonl"
    try:
        log_path.write_text("".join(raw_log))
    except Exception:
        pass
    result = {"mode": mode, "session_id": session, "commands": commands, "workspace": wid,
              "summary": (final or {}).get("result") or "", "status": "failed" if not final or final.get("is_error") else "done",
              "cost_usd": (final or {}).get("total_cost_usd"), "turns": (final or {}).get("num_turns"), "raw_log": str(log_path.name)}
    if wid:
        changed = workspace.record_external_changes(wid, "claude_code", before)
        result["files_changed"] = changed
        d = workspace.diff(wid, 30000)
        result["diff_stat"] = d["files"]
    if not final:
        raise RuntimeError("Claude Code stopped without a result. " + err[-400:])
    tests = [c for c in commands if any(x in c for x in ("pytest", "unittest", "godot", "npm test", "go test", "cargo test"))]
    result["tests_run"] = tests
    ctx.progress(1.0, "Claude Code finished")
    store.usage("claude_code_usd", result.get("cost_usd") or 0, "usd", ctx.id)
    return result


def delegate(a, c):
    mode = a.get("mode") or "implement"
    if mode not in ("implement", "investigate", "review"):
        raise ToolFailure("invalid_request", "mode must be implement, investigate or review")
    wid = a.get("workspace")
    if not wid:
        raise ToolFailure("invalid_request", "Open a workspace first (workspace_open or open_folder_as_workspace) and pass its id.")
    workspace.work_dir(wid)
    params = {k: a.get(k) for k in ("task", "files", "context", "errors", "constraints", "acceptance", "session_id") if a.get(k)}
    params.update(mode=mode, workspace=wid, allow_commit=bool(a.get("allow_commit")))
    if mode == "review" and not a.get("diff"):
        params["diff"] = workspace.diff(wid, 40000)["diff"] or "(no changes yet; review the code as it stands)"
    job = jobs.submit("claude_code", f"Claude Code: {a.get('task', '')[:80]}", params, conversation_id=c.get("cid"),
                      dedupe_key=f"claude:{c.get('request_id')}:{wid}:{mode}:{hash(a.get('task'))}")
    with store.lock(), store.db() as con:
        con.execute("UPDATE jobs SET report_back=1 WHERE id=?", (job["id"],))
    c.setdefault("jobs", []).append(job["id"])
    return {"job_id": job["id"], "state": job["state"],
            "note": "Claude Code is working on this in the background. Glen can keep chatting; you'll get the result when it finishes. "
                    "Tell him it's started; don't claim any result yet."}


def job_status(a, c):
    j = jobs.get(a.get("job_id"))
    if not j:
        raise ToolFailure("not_found", "No such job.")
    return j


class ClaudeCodePlugin(Plugin):
    id, name, version, auth = "claude_code", "Claude Code", "1.0", "local"
    description = "Hand development work to Claude Code (implement, investigate or review) in a Gemma workspace, as a background job."
    permissions = ("run Claude Code inside Gemma's workspaces", "never pushes, forces or rewrites history; commits only when you say so")

    def health(self):
        exe = claude_bin()
        if not exe:
            return {"status": "unavailable", "message": "Claude Code isn't installed on Vision (npm install -g @anthropic-ai/claude-code, then run `claude` once to sign in)."}
        try:
            v = subprocess.run([exe, "--version"], capture_output=True, text=True, timeout=20)
            return {"status": "ok", "message": (v.stdout or v.stderr).strip()[:80]}
        except Exception as e:
            return {"status": "unavailable", "message": f"Claude Code didn't start: {e}"}

    def register(self):
        register(Tool("claude_code", self.id,
                      "Give a development task to Claude Code, working in a workspace as a background job. mode: implement (make "
                      "the change and test it), investigate (find the cause, change nothing) or review (independent review of the "
                      "workspace or diff). Give it only what it needs: task, files, constraints, acceptance criteria, errors. "
                      "For an independent second opinion, don't include your own conclusion.",
                      {"task": {"type": "string"}, "mode": {"type": "string", "enum": ["implement", "investigate", "review"]},
                       "workspace": {"type": "string"}, "files": {"type": "string"}, "context": {"type": "string"},
                       "errors": {"type": "string"}, "constraints": {"type": "string"}, "acceptance": {"type": "string"},
                       "allow_commit": {"type": "boolean"}, "session_id": {"type": "string", "description": "to continue an earlier Claude session"}},
                      delegate, ("task", "workspace"), keywords=WORDS, label="Briefing Claude Code"))
        register(Tool("job_status", self.id, "Check on a background job (Claude Code, image, document, specialist).",
                      {"job_id": {"type": "string"}}, job_status, ("job_id",), keywords=WORDS + ("job", "progress", "how is", "status"),
                      label="Checking progress"))


jobs.register("claude_code", run_job, slot="claude", resumable=False, title="Claude Code")
add(ClaudeCodePlugin())
