"""
Approval cards for actions that change something outside Gemma.

Gemma prepares the action; Glen sees what will happen (to whom, what, where)
and chooses Allow once / Always allow / Cancel. Approving runs the action
exactly once, whichever client (web or phone) approves it, however many times
the button is pressed. The outcome is recorded and given to Gemma on her next
turn, so she knows whether it happened.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from . import observe, store

EXPIRE_HOURS = 72


def request(tool, args: dict, ctx: dict, idem: str) -> dict:
    preview = {"title": f"{tool.label}", "fields": [], "body": ""}
    if tool.preview:
        try:
            preview = tool.preview(args, ctx) or preview
        except Exception as e:
            preview = {"title": tool.label, "fields": [["Details", str(args)[:400]]], "body": f"(preview unavailable: {e})"}
    with store.lock(), store.db() as con:
        existing = con.execute("SELECT * FROM approvals WHERE idempotency_key=?", (idem,)).fetchone()
        if existing:
            return dict(existing)
        aid = "p" + uuid.uuid4().hex[:12]
        expires = (datetime.now(timezone.utc) + timedelta(hours=EXPIRE_HOURS)).isoformat(timespec="seconds")
        con.execute(
            "INSERT INTO approvals(id,user_id,conversation_id,request_id,plugin,tool,title,preview,arguments,state,idempotency_key,created_at,expires_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (aid, 1, ctx.get("cid"), ctx.get("request_id"), tool.plugin, tool.name, preview.get("title") or tool.label,
             store.jdump(dict(preview, risk=tool.risk)), store.jdump(args), "pending", idem, store.now(), expires))
        row = dict(con.execute("SELECT * FROM approvals WHERE id=?", (aid,)).fetchone())
    observe.event("SECURITY", "approval requested", approval=aid, plugin=tool.plugin, tool=tool.name, risk=tool.risk)
    from . import notify
    notify.add("approval", f"Gemma wants to: {row['title']}", conversation_id=ctx.get("cid"), link={"approval": aid},
               level="action")
    return row


def public(r: dict) -> dict:
    return {"id": r["id"], "conversation_id": r["conversation_id"], "message_id": r["message_id"], "plugin": r["plugin"],
            "tool": r["tool"], "title": r["title"], "preview": store.jload(r["preview"], {}), "state": r["state"],
            "result": store.jload(r["result"]), "created_at": r["created_at"], "decided_at": r["decided_at"],
            "executed_at": r["executed_at"], "expires_at": r["expires_at"]}


def get(aid: str) -> dict | None:
    with store.db() as con:
        r = con.execute("SELECT * FROM approvals WHERE id=?", (aid,)).fetchone()
    return public(dict(r)) if r else None


def listing(conversation_id=None, state=None) -> list[dict]:
    q, a = "SELECT * FROM approvals WHERE user_id=1", []
    if conversation_id is not None:
        q += " AND conversation_id=?"
        a.append(conversation_id)
    if state:
        q += " AND state=?"
        a.append(state)
    q += " ORDER BY created_at DESC LIMIT 100"
    with store.db() as con:
        return [public(dict(r)) for r in con.execute(q, a)]


def attach_to_message(ids: list[str], message_id: int):
    if not ids:
        return
    with store.lock(), store.db() as con:
        con.execute("UPDATE approvals SET message_id=? WHERE id IN (%s) AND message_id IS NULL" % ",".join("?" * len(ids)),
                    [message_id, *ids])


def by_messages(message_ids: list[int]) -> dict:
    if not message_ids:
        return {}
    with store.db() as con:
        rows = con.execute("SELECT * FROM approvals WHERE message_id IN (%s) ORDER BY created_at" % ",".join("?" * len(message_ids)),
                           message_ids).fetchall()
    out: dict = {}
    for r in rows:
        out.setdefault(r["message_id"], []).append(public(dict(r)))
    return out


def decide(aid: str, approve: bool, always: bool = False) -> dict:
    """Allow or cancel. Allowing executes the action once; a second Allow returns the first result."""
    from . import tools
    with store.lock(), store.db() as con:
        r = con.execute("SELECT * FROM approvals WHERE id=?", (aid,)).fetchone()
        if not r:
            raise LookupError("unknown approval")
        r = dict(r)
        if r["state"] != "pending":
            return public(r)
        if r["expires_at"] and r["expires_at"] < datetime.now(timezone.utc).isoformat(timespec="seconds"):
            con.execute("UPDATE approvals SET state='expired', decided_at=? WHERE id=?", (store.now(), aid))
            r["state"] = "expired"
            return public(r)
        con.execute("UPDATE approvals SET state=?, decided_at=? WHERE id=?", ("approved" if approve else "denied", store.now(), aid))
    observe.event("SECURITY", "approval " + ("granted" if approve else "declined"), approval=aid, tool=r["tool"], always=always)
    if not approve:
        return get(aid)
    if always:
        tools.set_policy(r["plugin"], r["tool"], "allow")
    ctx = {"cid": r["conversation_id"], "request_id": r["request_id"], "user_message_id": None, "approved": True,
           "approval_id": aid}
    args = store.jload(r["arguments"], {})
    result = tools.call(r["tool"], args, dict(ctx, request_id="approval:" + r["idempotency_key"]))
    state = "failed" if result.get("error") else "executed"
    with store.lock(), store.db() as con:
        con.execute("UPDATE approvals SET state=?, result=?, executed_at=? WHERE id=?", (state, store.jdump(result), store.now(), aid))
    return get(aid)


def outcomes_since(conversation_id: int, since_iso: str | None) -> list[dict]:
    """Decided approvals Gemma has not yet been told about (given to her on the next turn)."""
    with store.db() as con:
        rows = con.execute("SELECT * FROM approvals WHERE conversation_id=? AND state IN ('executed','failed','denied','expired') "
                           "AND decided_at > ? ORDER BY decided_at", (conversation_id, since_iso or "")).fetchall()
    return [public(dict(r)) for r in rows]
