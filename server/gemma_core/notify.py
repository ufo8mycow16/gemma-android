"""
Notifications and activity: one table (the existing `notifications`, extended),
read by the web page and by the Android app (polling /api/v1/notifications),
each with an optional deep link to a conversation, job, artifact or approval.
"""
from __future__ import annotations

from . import observe, store


def add(kind: str, text: str, *, conversation_id=None, title: str | None = None, link: dict | None = None,
        level: str = "info", detail: str | None = None, user_id: int = 1) -> int:
    with store.lock(), store.db() as con:
        nid = con.execute(
            "INSERT INTO notifications(conversation_id,kind,text,created_at,title,link,level,user_id) VALUES(?,?,?,?,?,?,?,?)",
            (conversation_id, kind, text[:600], store.now(), title, store.jdump(link) if link else None, level, user_id)).lastrowid
    observe.event("SYSTEM", "notification", kind=kind, text=text[:120], link=link, detail=detail)
    return nid


def listing(after: int | None, user_id: int = 1, limit: int = 50) -> dict:
    with store.db() as con:
        latest = con.execute("SELECT coalesce(max(id),0) FROM notifications").fetchone()[0]
        rows = [] if after is None else con.execute(
            "SELECT * FROM notifications WHERE id>? AND coalesce(user_id,1)=? ORDER BY id LIMIT ?", (after, user_id, limit)).fetchall()
    items = []
    for r in rows:
        d = dict(r)
        d["link"] = store.jload(d.get("link"))
        items.append({k: d.get(k) for k in ("id", "conversation_id", "kind", "text", "created_at", "title", "link", "level", "read_at")})
    return {"latest": latest, "items": items}


def recent(user_id: int = 1, limit: int = 30) -> list[dict]:
    with store.db() as con:
        rows = con.execute("SELECT * FROM notifications WHERE coalesce(user_id,1)=? ORDER BY id DESC LIMIT ?", (user_id, limit)).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        d["link"] = store.jload(d.get("link"))
        out.append(d)
    return out


def mark_read(ids: list[int]) -> None:
    if not ids:
        return
    with store.lock(), store.db() as con:
        con.execute("UPDATE notifications SET read_at=? WHERE id IN (%s) AND read_at IS NULL" % ",".join("?" * len(ids)),
                    [store.now(), *ids])
