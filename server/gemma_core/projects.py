"""
Projects: a working environment of chats, documents, files, instructions,
tasks and an optional development workspace.

Project context only reaches conversations in that project: its instructions
are added (briefly) to those chats' prompts and its documents are available to
their document questions. Nothing leaks into unrelated conversations.
"""
from __future__ import annotations

from . import observe, store

MAX_INSTRUCTIONS = 2000


def listing(user_id: int = 1) -> list[dict]:
    with store.db() as con:
        rows = con.execute(
            "SELECT p.*, (SELECT COUNT(*) FROM conversations c WHERE c.project_id=p.id AND c.deleted_at IS NULL) AS chats, "
            "(SELECT COUNT(*) FROM artifacts a WHERE a.conversation_id IN (SELECT id FROM conversations WHERE project_id=p.id) AND a.deleted_at IS NULL) AS files, "
            "(SELECT COUNT(*) FROM jobs j WHERE j.conversation_id IN (SELECT id FROM conversations WHERE project_id=p.id)) AS tasks "
            "FROM projects p WHERE p.user_id=? AND p.archived_at IS NULL ORDER BY p.updated_at DESC", (user_id,)).fetchall()
    return [dict(r) for r in rows]


def get(pid: int, user_id: int = 1) -> dict | None:
    with store.db() as con:
        r = con.execute("SELECT * FROM projects WHERE id=? AND user_id=?", (pid, user_id)).fetchone()
        if not r:
            return None
        chats = [dict(x) for x in con.execute("SELECT id, title, updated_at FROM conversations WHERE project_id=? AND deleted_at IS NULL ORDER BY updated_at DESC", (pid,))]
    return dict(r, conversations=chats)


def create(name: str, instructions: str = "", user_id: int = 1) -> dict:
    with store.lock(), store.db() as con:
        pid = con.execute("INSERT INTO projects(user_id,name,instructions,created_at,updated_at) VALUES(?,?,?,?,?)",
                          (user_id, name.strip()[:120] or "Project", instructions[:MAX_INSTRUCTIONS], store.now(), store.now())).lastrowid
    observe.event("API", "project created", project=pid)
    return get(pid)


def update(pid: int, name: str | None = None, instructions: str | None = None, archived: bool | None = None) -> dict:
    with store.lock(), store.db() as con:
        if name is not None:
            con.execute("UPDATE projects SET name=?, updated_at=? WHERE id=?", (name.strip()[:120], store.now(), pid))
        if instructions is not None:
            con.execute("UPDATE projects SET instructions=?, updated_at=? WHERE id=?", (instructions[:MAX_INSTRUCTIONS], store.now(), pid))
        if archived is not None:
            con.execute("UPDATE projects SET archived_at=? WHERE id=?", (store.now() if archived else None, pid))
    return get(pid)


def assign(pid: int | None, conversation_id: int) -> dict:
    with store.lock(), store.db() as con:
        con.execute("UPDATE conversations SET project_id=? WHERE id=?", (pid, conversation_id))
        if pid:
            con.execute("UPDATE projects SET updated_at=? WHERE id=?", (store.now(), pid))
    return {"conversation_id": conversation_id, "project_id": pid}


def for_conversation(conversation_id: int) -> dict | None:
    with store.db() as con:
        r = con.execute("SELECT p.* FROM projects p JOIN conversations c ON c.project_id=p.id WHERE c.id=? AND p.archived_at IS NULL",
                        (conversation_id,)).fetchone()
    return dict(r) if r else None


def conversation_ids(pid: int) -> list[int]:
    with store.db() as con:
        return [r["id"] for r in con.execute("SELECT id FROM conversations WHERE project_id=? AND deleted_at IS NULL", (pid,))]


def prompt_block(conversation_id: int) -> str:
    p = for_conversation(conversation_id)
    if not p:
        return ""
    text = (p.get("instructions") or "").strip()
    return (f"\n\nThis chat belongs to Glen's project \"{p['name']}\"." +
            (f" His notes for this project (context, not your personality):\n{text}" if text else ""))
