"""One search box over everything Gemma holds: conversations, documents, files she made, tasks (and, when asked, Gmail, Drive and local files)."""
from __future__ import annotations

import re

from . import observe, store


def _snip(text: str, q: str, width: int = 180) -> str:
    low = text.lower()
    at = low.find(q.lower())
    if at < 0:
        words = [w for w in q.lower().split() if len(w) > 2]
        at = min([p for p in (low.find(w) for w in words) if p >= 0] or [0])
    start = max(0, at - width // 3)
    return ("…" if start else "") + " ".join(text[start:start + width].split()) + ("…" if start + width < len(text) else "")


def run(query: str, include: list | None = None, user_id: int = 1, limit: int = 20) -> dict:
    q = " ".join(str(query or "").split())
    if len(q) < 2:
        return {"query": q, "results": {}}
    like = f"%{q}%"
    out: dict = {}
    with observe.span("API", "global search", query_chars=len(q), include=include or []) as info:
        with store.db() as con:
            out["conversations"] = [
                {"conversation_id": r["conversation_id"], "title": r["title"], "message_id": r["id"], "role": r["role"],
                 "snippet": _snip(r["content"], q), "created_at": r["created_at"]}
                for r in con.execute(
                    "SELECT m.id, m.conversation_id, m.role, m.content, m.created_at, c.title FROM messages m JOIN conversations c ON c.id=m.conversation_id "
                    "WHERE m.content LIKE ? AND c.deleted_at IS NULL ORDER BY m.id DESC LIMIT ?", (like, limit))]
            out["documents"] = [
                {"attachment_id": r["attachment_id"], "name": r["original_name"], "where": r["heading_path"], "snippet": _snip(r["text"], q)}
                for r in con.execute(
                    "SELECT dc.attachment_id, dc.heading_path, dc.text, a.original_name FROM doc_chunks dc JOIN attachments a ON a.id=dc.attachment_id "
                    "WHERE dc.text LIKE ? OR a.original_name LIKE ? ORDER BY dc.id DESC LIMIT ?", (like, like, limit))]
            out["artifacts"] = [
                {"artifact_id": r["id"], "filename": r["filename"], "kind": r["kind"], "description": r["description"], "created_at": r["created_at"],
                 "conversation_id": r["conversation_id"]}
                for r in con.execute(
                    "SELECT * FROM artifacts WHERE deleted_at IS NULL AND user_id=? AND (filename LIKE ? OR description LIKE ? OR provenance LIKE ?) "
                    "ORDER BY created_at DESC LIMIT ?", (user_id, like, like, like, limit))]
            out["tasks"] = [
                {"job_id": r["id"], "title": r["title"], "state": r["state"], "created_at": r["created_at"], "conversation_id": r["conversation_id"]}
                for r in con.execute("SELECT * FROM jobs WHERE user_id=? AND (title LIKE ? OR result LIKE ?) ORDER BY created_at DESC LIMIT ?",
                                     (user_id, like, like, limit))]
            out["projects"] = [{"project_id": r["id"], "name": r["name"]} for r in con.execute(
                "SELECT id, name FROM projects WHERE user_id=? AND archived_at IS NULL AND (name LIKE ? OR instructions LIKE ?) LIMIT ?",
                (user_id, like, like, limit))]
        from . import tools
        for name, tool in (("gmail", "gmail_search"), ("drive", "drive_search"), ("files", "find_files")):
            if name in (include or []) and tool in tools.TOOLS:
                args = {"query": q} if name != "gmail" else {"query": q, "max": 8}
                res = tools.call(tool, args, {"cid": None, "user_message_id": None})
                out[name] = res.get("messages") or res.get("files") or res.get("results") or ([{"error": res["error"]}] if res.get("error") else [])
        info["hits"] = {k: len(v) for k, v in out.items()}
    return {"query": q, "results": out}
