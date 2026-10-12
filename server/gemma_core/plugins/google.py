"""
Google: Drive, Gmail, Contacts (and the existing Calendar tools), all through
the one Google sign-in Gemma already uses (google_credentials.json +
google_token.json, `python3 app.py --connect-google`).

Reading is direct. Sending email, archiving/labelling, uploading to Drive and
changing contacts always show an approval card first. Email bodies, documents
and attachments are outside content: they are data, never instructions.
"""
from __future__ import annotations

import base64
import email.message
import email.utils
import re
import time

import requests

from .. import artifacts, observe, store
from ..tools import Tool, ToolFailure, register
from . import Plugin, add

SCOPES = {
    "calendar": ("https://www.googleapis.com/auth/calendar.events", "https://www.googleapis.com/auth/calendar.readonly"),
    "drive": ("https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/drive.file"),
    "gmail": ("https://www.googleapis.com/auth/gmail.modify",),
    "contacts": ("https://www.googleapis.com/auth/contacts",),
}
ALL_SCOPES = " ".join(s for group in SCOPES.values() for s in group)
DOC_MIMES = {"application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"}
GOOGLE_EXPORT = {"application/vnd.google-apps.document": ("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
                 "application/vnd.google-apps.spreadsheet": ("text/csv", ".csv"),
                 "application/vnd.google-apps.presentation": ("application/pdf", ".pdf")}
MAIL_WORDS = ("email", "emails", "e-mail", "gmail", "inbox", "mail", "message from", "reply to", "send", "attachment", "attached", "unread", "sender")
DRIVE_WORDS = ("drive", "google drive", "google doc", "google docs", "sheet", "shared folder", "my drive", "the doc")
CONTACT_WORDS = ("contact", "contacts", "phone number", "email address", "address book", "nikki", "who is", "number for")


def _token():
    fn = store.S.get("google_token")
    if not fn:
        raise ToolFailure("unavailable", "Google sign-in is not available in this server.")
    try:
        return fn(False)
    except Exception:
        raise ToolFailure("not_connected", "Google isn't connected. On Vision run: python3 app.py --connect-google")


def granted() -> set:
    fn = store.S.get("google_scopes")
    try:
        return set(fn() or []) if fn else set()
    except Exception:
        return set()


def need(service: str):
    have = granted()
    if have and not all(s in have for s in SCOPES[service]):
        raise ToolFailure("permission_denied", f"Gemma's Google sign-in doesn't include {service.capitalize()} yet. On Vision run: "
                                               "python3 app.py --connect-google  (and allow the new permissions).")


def gapi(method, url, *, params=None, json_body=None, data=None, headers=None, raw=False, timeout=30):
    for attempt in (0, 1):
        tok = _token() if attempt == 0 else store.S["google_token"](True)
        h = {"Authorization": "Bearer " + tok, **(headers or {})}
        try:
            r = requests.request(method, url, params=params, json=json_body, data=data, headers=h, timeout=timeout)
        except requests.RequestException as e:
            raise ToolFailure("network_error", f"Couldn't reach Google ({type(e).__name__}).")
        observe.debug("PLUGIN", f"google {method} {url.split('?')[0][-60:]}", status=r.status_code)
        if r.status_code == 401 and attempt == 0:
            continue
        if r.status_code == 401:
            raise ToolFailure("auth_expired", "The Google sign-in has expired. On Vision run: python3 app.py --connect-google")
        if r.status_code == 403:
            text = r.text[:400]
            if "insufficient" in text.lower() or "scope" in text.lower():
                raise ToolFailure("permission_denied", "Gemma's Google sign-in doesn't allow that yet; reconnect with --connect-google.")
            if "has not been used" in text or "disabled" in text:
                raise ToolFailure("unavailable", "That Google API isn't switched on for Gemma's Google Cloud project. " + text[:200])
            if "rateLimit" in text:
                raise ToolFailure("rate_limited", "Google is rate-limiting for a moment.")
            raise ToolFailure("permission_denied", "Google refused that request.")
        if r.status_code == 404:
            raise ToolFailure("not_found", "Google says that doesn't exist.")
        if r.status_code == 429:
            raise ToolFailure("rate_limited", "Google is rate-limiting for a moment.")
        if r.status_code >= 400:
            raise ToolFailure("upstream_error", f"Google error {r.status_code}: {r.text[:300]}")
        if raw:
            return r
        return r.json() if r.text else {}
    raise ToolFailure("auth_expired", "Google sign-in failed.")


# ---------------------------------------------------------------- Gmail

GM = "https://gmail.googleapis.com/gmail/v1/users/me"


def _headers(msg):
    return {h["name"].lower(): h["value"] for h in (msg.get("payload") or {}).get("headers", [])}


def _body_text(payload, depth=0):
    if depth > 8 or not payload:
        return ""
    mime = payload.get("mimeType", "")
    data = (payload.get("body") or {}).get("data")
    if mime == "text/plain" and data:
        return base64.urlsafe_b64decode(data + "==").decode("utf-8", "replace")
    parts = payload.get("parts") or []
    texts = [_body_text(p, depth + 1) for p in parts]
    plain = next((t for p, t in zip(parts, texts) if p.get("mimeType") == "text/plain" and t), "")
    if plain:
        return plain
    if mime == "text/html" and data:
        html = base64.urlsafe_b64decode(data + "==").decode("utf-8", "replace")
        return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", re.sub(r"(?is)<(script|style).*?</\1>", "", html)))
    return next((t for t in texts if t), "")


def _attachments(payload, out=None):
    out = [] if out is None else out
    for p in (payload or {}).get("parts") or []:
        if p.get("filename") and (p.get("body") or {}).get("attachmentId"):
            out.append({"attachment_id": p["body"]["attachmentId"], "filename": p["filename"], "mime": p.get("mimeType"), "size": p["body"].get("size")})
        _attachments(p, out)
    return out


def _summary(m):
    h = _headers(m)
    return {"id": m["id"], "thread": m.get("threadId"), "from": h.get("from"), "to": h.get("to"), "subject": h.get("subject"),
            "date": h.get("date"), "snippet": m.get("snippet", "")[:300], "unread": "UNREAD" in m.get("labelIds", []),
            "labels": m.get("labelIds", [])}


def gmail_search(a, c):
    need("gmail")
    q = str(a.get("query") or "in:inbox")
    ids = gapi("GET", f"{GM}/messages", params={"q": q, "maxResults": min(25, int(a.get("max") or 10))}).get("messages", [])
    msgs = [gapi("GET", f"{GM}/messages/{x['id']}", params={"format": "metadata", "metadataHeaders": ["From", "To", "Subject", "Date"]}) for x in ids]
    c["tainted"] = True
    return {"query": q, "messages": [_summary(m) for m in msgs]}


def gmail_read(a, c):
    need("gmail")
    if a.get("thread_id"):
        t = gapi("GET", f"{GM}/threads/{a['thread_id']}", params={"format": "full"})
        msgs = t.get("messages", [])
    else:
        msgs = [gapi("GET", f"{GM}/messages/{a.get('message_id')}", params={"format": "full"})]
    c["tainted"] = True
    out = []
    for m in msgs[-10:]:
        s = _summary(m)
        s["body"] = _body_text(m.get("payload"))[:8000]
        s["attachments"] = _attachments(m.get("payload"))
        out.append(s)
    return {"messages": out}


def gmail_attention(a, c):
    need("gmail")
    return gmail_search({"query": "in:inbox is:unread (is:important OR is:starred) newer_than:7d", "max": 15}, c)


def gmail_attachment(a, c):
    """Fetch one attachment; PDFs/Word/text go into the document system, anything else becomes a file card."""
    need("gmail")
    mid, att = a.get("message_id"), a.get("attachment_id")
    meta = next((x for x in _attachments(gapi("GET", f"{GM}/messages/{mid}", params={"format": "full"}).get("payload")) if x["attachment_id"] == att), None)
    if not meta:
        raise ToolFailure("not_found", "That attachment isn't on that message.")
    body = gapi("GET", f"{GM}/messages/{mid}/attachments/{att}")
    data = base64.urlsafe_b64decode(body["data"] + "==")
    return _ingest_or_store(meta["filename"], meta.get("mime") or artifacts.mime_for(meta["filename"]), data, c,
                            {"source": "gmail", "message_id": mid, "attachment": meta["filename"]})


def _ingest_or_store(name, mime, data, c, provenance):
    if mime == "application/msword":
        mime = "application/octet-stream"
    if name.lower().endswith(".docx"):
        mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    if name.lower().endswith(".pdf"):
        mime = "application/pdf"
    ingest = store.S.get("ingest_document")
    c["tainted"] = True
    if mime in DOC_MIMES and ingest and c.get("user_message_id"):
        saved = ingest(c["user_message_id"], [(name, mime, data)], c.get("progress"))
        return {"added_to_documents": [{"attachment_id": s["id"], "name": s["name"], "chars": len(s.get("text") or "")} for s in saved],
                "provenance": provenance, "note": "It is now one of this conversation's documents."}
    art = artifacts.save(data, name, created_by="user", conversation_id=c.get("cid"), provenance=provenance,
                         description=f"From {provenance.get('source')}")
    c.setdefault("artifacts", []).append(art["id"])
    return {"saved_as_file": art["id"], "filename": art["filename"], "mime": art["mime"], "provenance": provenance}


def _address(text):
    """An email address, or a contact name resolved through Google Contacts."""
    text = str(text or "").strip()
    if re.fullmatch(r"[^@\s,]+@[^@\s,]+\.[^@\s,]+", text):
        return text
    found = contacts_search({"query": text}, {}).get("contacts", [])
    with_mail = [x for x in found if x.get("emails")]
    if len(with_mail) == 1:
        return with_mail[0]["emails"][0]
    if not with_mail:
        raise ToolFailure("not_found", f"I couldn't find an email address for {text!r} in Glen's contacts.")
    raise ToolFailure("invalid_request", f"{text!r} matches several contacts: " + "; ".join(f"{x['name']} <{x['emails'][0]}>" for x in with_mail[:5]))


def _mime(a, c):
    msg = email.message.EmailMessage()
    msg["To"] = ", ".join(_address(x) for x in (a.get("to") if isinstance(a.get("to"), list) else [a.get("to")]) if x)
    if a.get("cc"):
        msg["Cc"] = ", ".join(_address(x) for x in (a["cc"] if isinstance(a["cc"], list) else [a["cc"]]))
    msg["Subject"] = str(a.get("subject") or "")
    if a.get("in_reply_to"):
        msg["In-Reply-To"] = msg["References"] = a["in_reply_to"]
    msg.set_content(str(a.get("body") or ""))
    for aid in a.get("attachments") or []:
        meta = artifacts.get(aid)
        data = artifacts.read(aid)
        if not meta or data is None:
            raise ToolFailure("not_found", f"There is no file {aid} to attach.")
        main, _, sub = meta["mime"].partition("/")
        msg.add_attachment(data, maintype=main or "application", subtype=sub or "octet-stream", filename=meta["filename"])
    return msg


def _mail_preview(a, c):
    try:
        msg = _mime(a, c)
        to = msg["To"]
    except ToolFailure as f:
        to = f"(problem: {f})"
    names = [(artifacts.get(x) or {}).get("filename", x) for x in a.get("attachments") or []]
    return {"title": "Send an email" if not a.get("draft") else "Save a draft", "fields": [["To", to], ["Subject", a.get("subject", "")],
            ["Attachments", ", ".join(names) or "none"]], "body": str(a.get("body") or "")[:3000]}


def gmail_send(a, c):
    need("gmail")
    msg = _mime(a, c)
    raw = base64.urlsafe_b64encode(msg.as_bytes()).decode()
    body = {"raw": raw}
    if a.get("thread_id"):
        body["threadId"] = a["thread_id"]
    r = gapi("POST", f"{GM}/messages/send", json_body=body)
    return {"sent": True, "id": r.get("id"), "to": msg["To"]}


def gmail_draft(a, c):
    need("gmail")
    msg = _mime(a, c)
    body = {"message": {"raw": base64.urlsafe_b64encode(msg.as_bytes()).decode(), **({"threadId": a["thread_id"]} if a.get("thread_id") else {})}}
    r = gapi("POST", f"{GM}/drafts", json_body=body)
    return {"draft_saved": True, "id": r.get("id"), "to": msg["To"]}


def gmail_modify(a, c):
    need("gmail")
    add_l = a.get("add_labels") or []
    rem = list(a.get("remove_labels") or [])
    if a.get("archive"):
        rem.append("INBOX")
    gapi("POST", f"{GM}/messages/{a['message_id']}/modify", json_body={"addLabelIds": add_l, "removeLabelIds": rem})
    return {"updated": a["message_id"], "added": add_l, "removed": rem}


# ---------------------------------------------------------------- Drive

DR = "https://www.googleapis.com/drive/v3"


def drive_search(a, c):
    need("drive")
    q = str(a.get("query") or "").replace("'", "\\'")
    query = f"(name contains '{q}' or fullText contains '{q}') and trashed = false" if q else "trashed = false"
    r = gapi("GET", f"{DR}/files", params={"q": query, "pageSize": 20, "orderBy": "modifiedTime desc" if not q else None,
                                           "fields": "files(id,name,mimeType,modifiedTime,size,parents,webViewLink,owners(displayName))"})
    return {"files": [{"id": f["id"], "name": f["name"], "type": f["mimeType"], "modified": f.get("modifiedTime"), "size": f.get("size"),
                       "link": f.get("webViewLink")} for f in r.get("files", [])]}


def drive_list(a, c):
    need("drive")
    folder = a.get("folder_id") or "root"
    r = gapi("GET", f"{DR}/files", params={"q": f"'{folder}' in parents and trashed = false", "pageSize": 100,
                                           "fields": "files(id,name,mimeType,modifiedTime,size)", "orderBy": "folder,name"})
    return {"folder": folder, "items": r.get("files", [])}


def drive_read(a, c):
    """A Drive file into Gemma's documents (Google Docs are exported to Word first) with a link back to Drive."""
    need("drive")
    fid = a.get("file_id")
    meta = gapi("GET", f"{DR}/files/{fid}", params={"fields": "id,name,mimeType,size,webViewLink,modifiedTime"})
    name, mime = meta["name"], meta["mimeType"]
    if mime in GOOGLE_EXPORT:
        out_mime, ext = GOOGLE_EXPORT[mime]
        data = gapi("GET", f"{DR}/files/{fid}/export", params={"mimeType": out_mime}, raw=True).content
        name, mime = name + ext, out_mime
    else:
        if int(meta.get("size") or 0) > 25 * 1024 * 1024:
            raise ToolFailure("invalid_request", "That file is larger than 25 MB.")
        data = gapi("GET", f"{DR}/files/{fid}", params={"alt": "media"}, raw=True).content
    return _ingest_or_store(name, mime, data, c, {"source": "google_drive", "file_id": fid, "link": meta.get("webViewLink"),
                                                  "modified": meta.get("modifiedTime")})


def drive_upload(a, c):
    need("drive")
    aid = a.get("artifact_id")
    meta, data = artifacts.get(aid), artifacts.read(aid)
    if not meta or data is None:
        raise ToolFailure("not_found", "There is no such file to upload.")
    boundary = "gemma" + str(int(time.time() * 1000))
    info = {"name": a.get("name") or meta["filename"], **({"parents": [a["folder_id"]]} if a.get("folder_id") else {})}
    import json as _json
    body = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{_json.dumps(info)}\r\n"
            f"--{boundary}\r\nContent-Type: {meta['mime']}\r\n\r\n").encode() + data + f"\r\n--{boundary}--".encode()
    r = gapi("POST", "https://www.googleapis.com/upload/drive/v3/files", params={"uploadType": "multipart", "fields": "id,webViewLink"},
             data=body, headers={"Content-Type": f"multipart/related; boundary={boundary}"})
    return {"uploaded": r.get("id"), "link": r.get("webViewLink")}


# ---------------------------------------------------------------- Contacts

PE = "https://people.googleapis.com/v1"


def _person(p):
    return {"resource": p.get("resourceName"), "name": ((p.get("names") or [{}])[0]).get("displayName"),
            "emails": [e["value"] for e in p.get("emailAddresses", [])][:3], "phones": [x["value"] for x in p.get("phoneNumbers", [])][:3]}


def contacts_search(a, c):
    need("contacts")
    q = str(a.get("query") or "").strip()
    if not q:
        raise ToolFailure("invalid_request", "say who to look for")
    gapi("GET", f"{PE}/people:searchContacts", params={"query": "", "readMask": "names"})        # warm-up the cache (Google requirement)
    r = gapi("GET", f"{PE}/people:searchContacts", params={"query": q, "readMask": "names,emailAddresses,phoneNumbers", "pageSize": 10})
    return {"contacts": [_person(x["person"]) for x in r.get("results", [])]}


def contacts_update(a, c):
    need("contacts")
    res = a.get("resource")
    cur = gapi("GET", f"{PE}/{res}", params={"personFields": "names,emailAddresses,phoneNumbers,metadata"})
    fields = []
    body = {"etag": cur.get("etag")}
    if a.get("add_email"):
        body["emailAddresses"] = cur.get("emailAddresses", []) + [{"value": a["add_email"]}]
        fields.append("emailAddresses")
    if a.get("add_phone"):
        body["phoneNumbers"] = cur.get("phoneNumbers", []) + [{"value": a["add_phone"]}]
        fields.append("phoneNumbers")
    if not fields:
        raise ToolFailure("invalid_request", "nothing to change")
    r = gapi("PATCH", f"{PE}/{res}:updateContact", params={"updatePersonFields": ",".join(fields)}, json_body=body)
    return {"updated": _person(r)}


class GooglePlugin(Plugin):
    id, name, version, auth = "google", "Google (Gmail, Drive, Contacts)", "1.0", "google"
    description = "Gmail, Google Drive and Google Contacts through Gemma's Google sign-in (Calendar uses the same sign-in)."
    permissions = ("read email, Drive files and contacts", "email attachments and Drive files can be read into Gemma's documents",
                   "send email, save drafts, archive or label, upload to Drive, change contacts: always asks first")

    def connect_info(self):
        return {"how": "On Vision run: python3 app.py --connect-google  and allow Calendar, Gmail, Drive and Contacts. In the Google Cloud "
                       "project behind google_credentials.json, enable the Gmail API, Google Drive API and People API.",
                "scopes": ALL_SCOPES.split()}

    def health(self):
        if not store.S.get("google_token"):
            return {"status": "unavailable", "message": "Google support is not loaded."}
        try:
            _token()
        except ToolFailure as f:
            return {"status": f.status, "message": str(f)}
        have = granted()
        missing = [svc for svc, sc in SCOPES.items() if have and not all(s in have for s in sc)]
        return {"status": "ok", "message": "Connected" + (f"; reconnect to add: {', '.join(missing)}" if missing else ""),
                "services": {svc: (not have) or all(s in have for s in sc) for svc, sc in SCOPES.items()}}

    def register(self):
        reg = lambda name, desc, params, fn, req=(), kw=MAIL_WORDS, **x: register(Tool(name, self.id, desc, params, fn, req, keywords=kw, **x))
        reg("gmail_search", "Search Gmail with Gmail search syntax (e.g. 'from:bob newer_than:2d', 'has:attachment subject:invoice').",
            {"query": {"type": "string"}, "max": {"type": "integer"}}, gmail_search, ("query",), label="Searching email", reads_untrusted=True)
        reg("gmail_read", "Read an email (message_id) or a whole thread (thread_id), with its attachments listed.",
            {"message_id": {"type": "string"}, "thread_id": {"type": "string"}}, gmail_read, (), label="Reading email", reads_untrusted=True)
        reg("gmail_needs_attention", "Unread important or starred email from the last week.", {}, gmail_attention, (), label="Checking email", reads_untrusted=True)
        reg("gmail_get_attachment", "Fetch an email attachment: PDFs, Word and text files go into this conversation's documents; other files become a file card.",
            {"message_id": {"type": "string"}, "attachment_id": {"type": "string"}}, gmail_attachment, ("message_id", "attachment_id"), label="Opening the attachment")
        mail_params = {"to": {"type": "array", "items": {"type": "string"}, "description": "addresses or contact names"},
                       "cc": {"type": "array", "items": {"type": "string"}}, "subject": {"type": "string"}, "body": {"type": "string"},
                       "attachments": {"type": "array", "items": {"type": "string"}, "description": "artifact ids of files to attach"},
                       "thread_id": {"type": "string"}, "in_reply_to": {"type": "string"}}
        reg("gmail_send", "Send an email (contact names are looked up in Google Contacts). Glen approves it before it goes.",
            mail_params, gmail_send, ("to", "subject", "body"), mutation=True, risk="high", label="Send an email", preview=_mail_preview)
        reg("gmail_draft", "Save an email as a Gmail draft for Glen to finish.", mail_params, gmail_draft, ("to", "subject", "body"),
            mutation=True, risk="low", label="Save an email draft", preview=lambda a, c: dict(_mail_preview(dict(a, draft=True), c)))
        reg("gmail_modify", "Archive an email or add/remove labels.", {"message_id": {"type": "string"}, "archive": {"type": "boolean"},
            "add_labels": {"type": "array", "items": {"type": "string"}}, "remove_labels": {"type": "array", "items": {"type": "string"}}},
            gmail_modify, ("message_id",), mutation=True, risk="medium", label="Change an email's labels",
            preview=lambda a, c: {"title": "Archive email" if a.get("archive") else "Change email labels", "fields": [["Message", a.get("message_id")],
                                  ["Add", ", ".join(a.get("add_labels") or [])], ["Remove", ", ".join(a.get("remove_labels") or [])]]})
        reg("drive_search", "Search Google Drive by name or content.", {"query": {"type": "string"}}, drive_search, (), DRIVE_WORDS, label="Searching Drive")
        reg("drive_list_folder", "List a Drive folder (folder_id, default My Drive).", {"folder_id": {"type": "string"}}, drive_list, (), DRIVE_WORDS, label="Browsing Drive")
        reg("drive_read_file", "Bring a Drive file into this conversation's documents (Google Docs are exported first).",
            {"file_id": {"type": "string"}}, drive_read, ("file_id",), DRIVE_WORDS, label="Reading from Drive")
        reg("drive_upload", "Upload one of Gemma's files (artifact) to Google Drive.", {"artifact_id": {"type": "string"}, "folder_id": {"type": "string"},
            "name": {"type": "string"}}, drive_upload, ("artifact_id",), DRIVE_WORDS, mutation=True, risk="medium", label="Upload to Drive",
            preview=lambda a, c: {"title": "Upload a file to Google Drive", "fields": [["File", (artifacts.get(a.get("artifact_id")) or {}).get("filename", a.get("artifact_id"))],
                                  ["Folder", a.get("folder_id") or "My Drive"]]})
        reg("contacts_search", "Find someone in Google Contacts (name, email, phone).", {"query": {"type": "string"}}, contacts_search, ("query",),
            CONTACT_WORDS + MAIL_WORDS, label="Looking up a contact")
        reg("contacts_update", "Add an email address or phone number to a contact.", {"resource": {"type": "string"}, "add_email": {"type": "string"},
            "add_phone": {"type": "string"}}, contacts_update, ("resource",), CONTACT_WORDS, mutation=True, risk="medium", label="Update a contact",
            preview=lambda a, c: {"title": "Update a contact", "fields": [["Contact", a.get("resource")], ["Add email", a.get("add_email") or ""],
                                  ["Add phone", a.get("add_phone") or ""]]})


add(GooglePlugin())
