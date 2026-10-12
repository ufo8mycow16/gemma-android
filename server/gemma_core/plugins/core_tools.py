"""
Gemma's own making-things tools: files, archives, office documents, and the
development workspace (inspect, edit, run, test, diff, package).

These are not plugins to an outside service; they are registered under the
plugin ids "artifacts" and "workspace" so they appear in the same settings,
diagnostics and router as everything else.
"""
from __future__ import annotations

import csv
import io
import json
import re

from .. import artifacts, observe, store, workspace
from ..tools import Tool, ToolFailure, register
from . import Plugin, add

FILE_WORDS = ("file", "save", "download", "zip", "archive", "pdf", "docx", "word doc", "spreadsheet", "xlsx", "excel",
              "csv", "pptx", "powerpoint", "slides", "markdown", "readme", "json", "script", ".py", ".gd", "give me",
              "export", "report", "document", "package", "attachment", "code", "utility", "write me")
DEV_WORDS = ("code", "bug", "fix", "error", "traceback", "exception", "test", "tests", "pytest", "refactor", "python",
             ".py", "gdscript", "godot", ".gd", "scene", "project", "repo", "repository", "compile", "build", "lint",
             "diff", "patch", "function", "class", "script", "crash", "debug", "zip", "workspace", "run it")


def _ctx_conv(ctx):
    return ctx.get("cid")


def _remember(ctx, art):
    ctx.setdefault("artifacts", []).append(art["id"])
    return {"artifact_id": art["id"], "filename": art["filename"], "size": art["size"], "mime": art["mime"],
            "note": "The file is attached to your reply as a card Glen can open, download and share. "
                    "Don't paste its whole content into your reply."}


# ------------------------------------------------------------- artifacts

def create_file(args, ctx):
    name = artifacts.safe_filename(args.get("filename") or "file.txt")
    content = args.get("content")
    if not isinstance(content, str):
        raise ToolFailure("invalid_request", "content must be the full text of the file")
    art = artifacts.save(content.encode("utf-8"), name, created_by="gemma", conversation_id=_ctx_conv(ctx),
                         description=args.get("description", ""), provenance={"tool": "create_file", "request": ctx.get("request_id")})
    out = _remember(ctx, art)
    if name.endswith(".py"):
        try:
            compile(content, name, "exec")
            out["syntax_check"] = "Python syntax OK (compiled, not executed)"
        except SyntaxError as e:
            out["syntax_check"] = f"Python syntax error at line {e.lineno}: {e.msg}"
    return out


def create_archive(args, ctx):
    files = args.get("files")
    if not isinstance(files, list) or not files:
        raise ToolFailure("invalid_request", "files must be a list of {path, content}")
    data = {}
    for f in files[:400]:
        rel = artifacts.clean_relpath((f or {}).get("path", ""))
        if rel is None:
            raise ToolFailure("invalid_request", f"unsafe path {f!r}")
        data[rel] = str(f.get("content", "")).encode("utf-8")
    name = artifacts.safe_filename(args.get("filename") or "files.zip")
    if not name.lower().endswith(".zip"):
        name += ".zip"
    art = artifacts.save(artifacts.make_zip(data), name, created_by="gemma", conversation_id=_ctx_conv(ctx), kind="archive",
                         description=args.get("description", ""), provenance={"tool": "create_archive", "files": sorted(data)})
    out = _remember(ctx, art)
    out["files"] = sorted(data)
    return out


def _table(args):
    rows = args.get("rows")
    if isinstance(rows, list) and rows and isinstance(rows[0], list):
        return [[str(c) for c in r] for r in rows]
    text = args.get("content") or ""
    lines = [l for l in text.splitlines() if l.strip().startswith("|")]
    out = []
    for l in lines:
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        if all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
            continue
        out.append(cells)
    return out


def make_document(args, ctx):
    fmt = str(args.get("format") or "").lower().strip(".")
    title = str(args.get("title") or "Document")[:200]
    content = str(args.get("content") or "")
    base = artifacts.safe_filename(args.get("filename") or re.sub(r"[^\w\- ]", "", title).strip().replace(" ", "_") or "document")
    base = re.sub(r"\.(pdf|docx|xlsx|pptx|csv|md|txt|html)$", "", base, flags=re.I)
    if fmt in ("md", "markdown", "txt"):
        data, name = content.encode(), base + (".md" if fmt != "txt" else ".txt")
    elif fmt == "csv":
        buf = io.StringIO()
        csv.writer(buf).writerows(_table(args) or [[l] for l in content.splitlines()])
        data, name = buf.getvalue().encode(), base + ".csv"
    elif fmt == "docx":
        data, name = _docx(title, content), base + ".docx"
    elif fmt == "pdf":
        data, name = _pdf(title, content), base + ".pdf"
    elif fmt == "xlsx":
        data, name = _xlsx(title, _table(args)), base + ".xlsx"
    elif fmt == "pptx":
        data, name = _pptx(title, content), base + ".pptx"
    else:
        raise ToolFailure("invalid_request", "format must be one of pdf, docx, xlsx, pptx, csv, md, txt")
    art = artifacts.save(data, name, created_by="gemma", conversation_id=_ctx_conv(ctx), description=title,
                         provenance={"tool": "make_document", "format": fmt})
    return _remember(ctx, art)


def _blocks(content: str):
    """Very small Markdown reader for documents: headings, bullets, numbered items, paragraphs, tables."""
    out, para = [], []
    for line in content.splitlines():
        s = line.rstrip()
        if not s.strip():
            if para:
                out.append(("p", " ".join(para)))
                para = []
            continue
        m = re.match(r"^(#{1,4})\s+(.*)", s)
        if m:
            if para:
                out.append(("p", " ".join(para)))
                para = []
            out.append((f"h{len(m.group(1))}", m.group(2).strip()))
        elif re.match(r"^\s*[-*•]\s+", s):
            if para:
                out.append(("p", " ".join(para)))
                para = []
            out.append(("li", re.sub(r"^\s*[-*•]\s+", "", s)))
        elif re.match(r"^\s*\d+[.)]\s+", s):
            if para:
                out.append(("p", " ".join(para)))
                para = []
            out.append(("ol", re.sub(r"^\s*\d+[.)]\s+", "", s)))
        else:
            para.append(s.strip())
    if para:
        out.append(("p", " ".join(para)))
    return out


def _plain(text):
    return re.sub(r"\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`", lambda m: m.group(1) or m.group(2) or m.group(3), text)


def _docx(title, content):
    try:
        from docx import Document
    except ImportError:
        raise ToolFailure("unavailable", "Word files need python-docx on Vision (pip install python-docx).")
    d = Document()
    d.add_heading(title, 0)
    for kind, text in _blocks(content):
        if kind.startswith("h"):
            d.add_heading(_plain(text), min(4, int(kind[1])))
        elif kind == "li":
            d.add_paragraph(_plain(text), style="List Bullet")
        elif kind == "ol":
            d.add_paragraph(_plain(text), style="List Number")
        else:
            p = d.add_paragraph()
            for part in re.split(r"(\*\*.+?\*\*)", text):
                if part.startswith("**") and part.endswith("**"):
                    p.add_run(part[2:-2]).bold = True
                elif part:
                    p.add_run(_plain(part))
    buf = io.BytesIO()
    d.save(buf)
    return buf.getvalue()


def _pdf(title, content):
    try:
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import getSampleStyleSheet
        from reportlab.platypus import ListFlowable, ListItem, Paragraph, SimpleDocTemplate, Spacer
    except ImportError:
        return _pdf_minimal(title, content)
    styles = getSampleStyleSheet()
    buf = io.BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=A4, title=title)
    story = [Paragraph(_esc(title), styles["Title"]), Spacer(1, 8)]
    for kind, text in _blocks(content):
        t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", _esc(text))
        if kind.startswith("h"):
            story.append(Paragraph(t, styles["Heading%d" % min(3, int(kind[1]))]))
        elif kind in ("li", "ol"):
            story.append(ListFlowable([ListItem(Paragraph(t, styles["BodyText"]))], bulletType="bullet" if kind == "li" else "1"))
        else:
            story.append(Paragraph(t, styles["BodyText"]))
            story.append(Spacer(1, 4))
    doc.build(story)
    return buf.getvalue()


def _esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _pdf_minimal(title, content):
    """A plain but valid PDF without extra libraries (used when reportlab is not installed)."""
    lines = [title, ""]
    for kind, text in _blocks(content):
        prefix = "• " if kind == "li" else "" if kind == "p" else ""
        text = _plain(text)
        while len(text) > 90:
            cut = text.rfind(" ", 0, 90)
            cut = cut if cut > 40 else 90
            lines.append(prefix + text[:cut])
            text, prefix = text[cut:].lstrip(), "  " if prefix else ""
        lines.append(prefix + text)
        if kind in ("p",) or kind.startswith("h"):
            lines.append("")
    pages = [lines[i:i + 50] for i in range(0, len(lines), 50)] or [[""]]
    objs = []
    font_obj = 3
    page_ids = []
    content_objs = []
    for pi, page in enumerate(pages):
        ops = ["BT", "/F1 11 Tf", "50 800 Td", "14 TL"]
        for j, l in enumerate(page):
            safe = l.encode("latin-1", "replace").decode("latin-1").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            if pi == 0 and j == 0:
                ops.append("/F1 16 Tf")
            ops.append(f"({safe}) Tj T*")
            if pi == 0 and j == 0:
                ops.append("/F1 11 Tf")
        ops.append("ET")
        content_objs.append("\n".join(ops).encode("latin-1"))
    n = 4
    for _ in pages:
        page_ids.append(n)
        n += 2
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n")
    offsets = {}

    def obj(i, body: bytes):
        offsets[i] = out.tell()
        out.write(f"{i} 0 obj\n".encode() + body + b"\nendobj\n")
    obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    obj(2, f"<< /Type /Pages /Kids [{' '.join(f'{p} 0 R' for p in page_ids)}] /Count {len(page_ids)} >>".encode())
    obj(3, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    for pid, stream in zip(page_ids, content_objs):
        obj(pid, f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents {pid + 1} 0 R >>".encode())
        obj(pid + 1, f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"\nendstream")
    xref = out.tell()
    total = max(offsets) + 1
    out.write(f"xref\n0 {total}\n0000000000 65535 f \n".encode())
    for i in range(1, total):
        out.write(f"{offsets[i]:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {total} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()


def _xlsx(title, rows):
    try:
        from openpyxl import Workbook
        from openpyxl.styles import Font
    except ImportError:
        raise ToolFailure("unavailable", "Spreadsheets need openpyxl on Vision (pip install openpyxl). A CSV file works without it.")
    wb = Workbook()
    ws = wb.active
    ws.title = re.sub(r"[\[\]:*?/\\]", "", title)[:31] or "Sheet1"
    for r in rows or [[title]]:
        ws.append([_number(c) for c in r])
    for c in ws[1]:
        c.font = Font(bold=True)
    for col in ws.columns:
        ws.column_dimensions[col[0].column_letter].width = min(60, max(10, max(len(str(c.value or "")) for c in col) + 2))
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _number(v):
    s = str(v).strip()
    try:
        return int(s) if re.fullmatch(r"-?\d+", s) else float(s) if re.fullmatch(r"-?\d+\.\d+", s) else s
    except Exception:
        return s


def _pptx(title, content):
    try:
        from pptx import Presentation
    except ImportError:
        raise ToolFailure("unavailable", "Slides need python-pptx on Vision (pip install python-pptx).")
    prs = Presentation()
    s = prs.slides.add_slide(prs.slide_layouts[0])
    s.shapes.title.text = title
    slides, cur = [], None
    for kind, text in _blocks(content):
        if kind in ("h1", "h2"):
            cur = [text, []]
            slides.append(cur)
        else:
            if cur is None:
                cur = [title, []]
                slides.append(cur)
            cur[1].append(_plain(text))
    for head, items in slides:
        sl = prs.slides.add_slide(prs.slide_layouts[1])
        sl.shapes.title.text = head
        tf = sl.placeholders[1].text_frame
        for i, it in enumerate(items[:12]):
            (tf.paragraphs[0] if i == 0 else tf.add_paragraph()).text = it
    buf = io.BytesIO()
    prs.save(buf)
    return buf.getvalue()


# ------------------------------------------------------------- workspace

def _ws(args):
    wid = args.get("workspace")
    if not wid:
        raise ToolFailure("invalid_request", "Say which workspace (its id), or create one first with workspace_open.")
    return wid


def workspace_open(args, ctx):
    """From an artifact (zip or single file) Glen uploaded or Gemma made, or empty."""
    src = args.get("artifact_id")
    name = args.get("name") or "project"
    if src:
        meta = artifacts.get(src)
        if not meta:
            raise ToolFailure("not_found", "There is no file with that id.")
        data = artifacts.read(src)
        if meta["filename"].lower().endswith(".zip"):
            ws = workspace.create(meta["filename"][:-4], conversation_id=ctx.get("cid"), archive=data, source=f"artifact {src}")
        else:
            ws = workspace.create(name, conversation_id=ctx.get("cid"), files={meta["filename"]: data}, source=f"artifact {src}")
    else:
        ws = workspace.create(name, conversation_id=ctx.get("cid"), files={}, source="empty")
    ws["files_list"] = [f["path"] for f in workspace.list_files(ws["id"])[:200]]
    g = workspace.godot_version(ws["id"])
    if g.get("found"):
        ws["godot"] = g
    return ws


def ws_files(args, ctx):
    return {"files": workspace.list_files(_ws(args))[:600]}


def ws_read(args, ctx):
    return {"path": args.get("path"), "content": workspace.read(_ws(args), args.get("path", ""))}


def ws_write(args, ctx):
    return workspace.write(_ws(args), args.get("path", ""), str(args.get("content", "")), actor="gemma", note=args.get("why", ""))


def ws_search(args, ctx):
    wid, q = _ws(args), str(args.get("text") or "")
    if not q:
        raise ToolFailure("invalid_request", "text to search for is required")
    base = workspace.work_dir(wid)
    hits = []
    rx = re.compile(re.escape(q), re.I) if not args.get("regex") else re.compile(q)
    for f in workspace.list_files(wid):
        p = base / f["path"]
        try:
            data = p.read_bytes()
            if b"\x00" in data[:4096]:
                continue
            for i, line in enumerate(data.decode("utf-8", "replace").splitlines(), 1):
                if rx.search(line):
                    hits.append({"path": f["path"], "line": i, "text": line.strip()[:200]})
                    if len(hits) >= 120:
                        return {"matches": hits, "truncated": True}
        except Exception:
            continue
    return {"matches": hits}


def ws_run(args, ctx):
    return workspace.run(_ws(args), args.get("action", ""), args.get("target", ""), args.get("args") or [])


def ws_diff(args, ctx):
    d = workspace.diff(_ws(args))
    ctx.setdefault("reviews", []).append(d["workspace"])
    return d


def ws_package(args, ctx):
    art = workspace.package(_ws(args), args.get("filename") or "project-fixed.zip", conversation_id=ctx.get("cid"))
    return _remember(ctx, art)


def ws_export(args, ctx):
    wid, rel = _ws(args), args.get("path", "")
    p = workspace.resolve(wid, rel)
    if not p.is_file():
        raise ToolFailure("not_found", f"{rel} is not in the workspace")
    name = args.get("filename") or p.name
    art = artifacts.save(p.read_bytes(), name, created_by="gemma", conversation_id=ctx.get("cid"),
                         provenance={"workspace": wid, "path": rel, "changes": [c for c in workspace.changes(wid) if c["path"] == rel]})
    return _remember(ctx, art)


def ws_revert(args, ctx):
    return workspace.revert(_ws(args), args.get("path") or None)


class ArtifactsPlugin(Plugin):
    id, name, version, auth = "artifacts", "Files Gemma makes", "1.0", "local"
    description = "Gemma writes real files you can open, download and share: code, text, PDF, Word, Excel, PowerPoint, CSV, ZIP."
    permissions = ("create files in Gemma's own file store",)

    def register(self):
        register(Tool("create_file", "artifacts",
                      "Create a real file for Glen (code, text, Markdown, JSON, config, a .py or .gd script...). Use this whenever he "
                      "asks for a file or for code he will save, instead of pasting long code into chat. The file appears as a card "
                      "he can open and download.",
                      {"filename": {"type": "string", "description": "With extension, e.g. player.gd"},
                       "content": {"type": "string", "description": "The complete file content"},
                       "description": {"type": "string"}}, create_file, ("filename", "content"),
                      keywords=FILE_WORDS, always=True, label="Writing a file"))
        register(Tool("create_archive", "artifacts",
                      "Create a ZIP of several files keeping their folder structure (for a small project, e.g. app.py, config.py, "
                      "requirements.txt, README.md).",
                      {"filename": {"type": "string"}, "files": {"type": "array", "items": {"type": "object", "properties": {
                          "path": {"type": "string"}, "content": {"type": "string"}}}}, "description": {"type": "string"}},
                      create_archive, ("files",), keywords=FILE_WORDS, label="Packaging files"))
        register(Tool("make_document", "artifacts",
                      "Make a document file: pdf, docx (Word), xlsx (spreadsheet), pptx (slides), csv or md. Give the content as Markdown "
                      "(headings with #, bullet lists); for xlsx/csv give a Markdown table or rows.",
                      {"format": {"type": "string", "enum": ["pdf", "docx", "xlsx", "pptx", "csv", "md", "txt"]},
                       "title": {"type": "string"}, "content": {"type": "string"},
                       "rows": {"type": "array", "items": {"type": "array", "items": {"type": "string"}}},
                       "filename": {"type": "string"}}, make_document, ("format", "title"),
                      keywords=("pdf", "docx", "word", "excel", "xlsx", "spreadsheet", "pptx", "slides", "powerpoint", "csv", "report", "document"),
                      label="Making a document"))


class WorkspacePlugin(Plugin):
    id, name, version, auth = "workspace", "Development workspace", "1.0", "local"
    description = ("An isolated copy of a project for Gemma (and Claude Code) to inspect, change, run, test, diff and "
                   "package. The original is never touched.")
    permissions = ("read and change files inside Gemma's workspaces", "run Python, pytest, linters and Godot checks there, "
                   "with time and memory limits and no network where the system allows")

    def health(self):
        return {"status": "ok", "message": "", "toolchain": workspace.toolchain()}

    def register(self):
        W = {"workspace": {"type": "string", "description": "workspace id"}}
        register(Tool("workspace_open", "workspace",
                      "Open a development workspace from a file or ZIP Glen gave (artifact_id), or an empty one. Do this before "
                      "inspecting, fixing or testing code he uploaded.",
                      {"artifact_id": {"type": "string"}, "name": {"type": "string"}}, workspace_open, (),
                      keywords=DEV_WORDS, label="Opening the project"))
        register(Tool("workspace_files", "workspace", "List the files in a workspace.", W, ws_files, ("workspace",), keywords=DEV_WORDS, label="Looking at the files"))
        register(Tool("workspace_read", "workspace", "Read a file in a workspace.", dict(W, path={"type": "string"}), ws_read, ("workspace", "path"), keywords=DEV_WORDS, label="Reading code"))
        register(Tool("workspace_search", "workspace", "Search the workspace's files for text or a symbol name.",
                      dict(W, text={"type": "string"}, regex={"type": "boolean"}), ws_search, ("workspace", "text"), keywords=DEV_WORDS, label="Searching the code"))
        register(Tool("workspace_write", "workspace",
                      "Create or replace a file in the workspace with its complete new content. Only inside the workspace; the "
                      "original stays untouched until Glen accepts the changes.",
                      dict(W, path={"type": "string"}, content={"type": "string"}, why={"type": "string"}), ws_write,
                      ("workspace", "path", "content"), keywords=DEV_WORDS, label="Editing code"))
        register(Tool("workspace_run", "workspace",
                      "Run a check in the workspace and get the real output: " + "; ".join(f"{k}: {v}" for k, v in workspace.ACTIONS.items()) +
                      ". Never say code was tested or passed unless this ran and said so.",
                      dict(W, action={"type": "string", "enum": list(workspace.ACTIONS)}, target={"type": "string"},
                           args={"type": "array", "items": {"type": "string"}}), ws_run, ("workspace", "action"),
                      keywords=DEV_WORDS, label="Running checks"))
        register(Tool("workspace_diff", "workspace", "Show what has changed in the workspace compared with the original (a review for Glen).",
                      W, ws_diff, ("workspace",), keywords=DEV_WORDS, label="Preparing the diff"))
        register(Tool("workspace_revert", "workspace", "Undo changes to one file (path) or the whole workspace.",
                      dict(W, path={"type": "string"}), ws_revert, ("workspace",), keywords=DEV_WORDS, label="Reverting"))
        register(Tool("workspace_package", "workspace", "Package the workspace as a ZIP for Glen to download.",
                      dict(W, filename={"type": "string"}), ws_package, ("workspace",), keywords=DEV_WORDS, label="Packaging"))
        register(Tool("workspace_export_file", "workspace", "Give Glen one file from the workspace as a downloadable file.",
                      dict(W, path={"type": "string"}, filename={"type": "string"}), ws_export, ("workspace", "path"),
                      keywords=DEV_WORDS, label="Preparing the file"))


add(ArtifactsPlugin())
add(WorkspacePlugin())
