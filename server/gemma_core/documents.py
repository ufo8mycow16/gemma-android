"""
Structure-aware document index (index version 2).

Audit of the v9 pipeline (Tiffany's), and what this replaces:

  * Headings were guessed by a regex on every line ("^Episode 3\\b.*"), so prose
    such as "Episode 3 will reveal..." became the stored heading of a chunk. v9
    worked around this at query time by re-scanning chunk text and scoring
    heading-like lines with document-specific words ("synopsis", "hud",
    "design bible"...). Here the cause is fixed at ingestion: DOCX heading styles
    are read from the file, and the fallback pattern only accepts a line that
    *is* a heading (short, standalone, the words after the number are a title,
    not a sentence).
  * DOCX tables were appended after all paragraphs, out of reading order. Here
    the body is read in order.
  * A chunk was labelled with the heading in force when it was *flushed*, so the
    text just before a new heading carried the new heading (preceding-boundary
    contamination), and chunks ran straight across section boundaries. Here
    chunks never cross a section boundary.
  * The 600-character overlap was copied from the end of one chunk into the next
    even across sections (next-section leakage, duplicated evidence). Here there
    is no cross-section overlap; inside a section the last paragraph is carried
    over only when it is short.
  * Chunks had no source positions. Here every section and chunk keeps its
    character range in the version text, and each version records the SHA-256 of
    the original file, so a cached index can never be paired with other bytes.
  * Structural questions are answered from the section table: the genuine
    "Episode 3" section is the longest section with that heading (a table of
    contents entry or a passing mention has almost no body), and the evidence is
    exactly that section's text, in order, ending where Episode 4 begins.
  * Chunk size is now ~800 tokens (3,200 characters, cut at paragraph
    boundaries, never above 4,800): small enough to rank precisely, large enough
    to carry a scene. Sizes are measured in estimated tokens (chars / 4).

What is kept: the original files, the extracted text, v9's chunks and its
completed hierarchy (section coverage and whole-document overview). The
hierarchy is built from v9's chunks and stays valid as a derived summary of the
same document, so a finished 34-section synthesis is never regenerated.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
import re
import threading
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

from . import observe
from .routing import number_token

INDEX_VERSION = 3          # 3: story ranges (episodes from heading to the next episode, whatever the Word styles say)
TARGET_CHARS = 3200
MAX_CHARS = 4800
CARRY_CHARS = 500
STRUCTURAL_BUDGET = 60000        # characters of original text for one structural answer (~15k tokens)
HYBRID_BUDGET = 24000

_CTX: dict = {}

_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
_STRUCT_KINDS = ("episode", "chapter", "act", "scene", "part", "section", "prologue", "epilogue", "interlude",
                 "intermission", "appendix", "book")
_STRUCT_LEVEL = {"book": 0, "part": 1, "episode": 1, "chapter": 2, "act": 2, "section": 3, "scene": 3,
                 "prologue": 1, "epilogue": 1, "interlude": 2, "intermission": 2, "appendix": 1}
_HEAD_RE = re.compile(
    r"^(?P<kind>episode|chapter|act|scene|part|section|book|appendix)\s+(?P<num>\d{1,3}|[ivxlc]{1,6}|one|two|three|four|five|six|"
    r"seven|eight|nine|ten|eleven|twelve)\b(?P<tail>.*)$", re.I)
_NAMED_RE = re.compile(r"^(?P<kind>prologue|epilogue|interlude|intermission)\b(?P<tail>.*)$", re.I)
_MINOR = {"a", "an", "the", "of", "and", "or", "in", "on", "at", "to", "for", "with", "from", "by", "vs", "&"}


def init(*, db, lock, upload_dir: Path, embed, now, text_hash, embed_model: str = "doc"):
    _CTX.update(db=db, lock=lock, upload_dir=upload_dir, embed=embed, now=now, text_hash=text_hash, embed_model=embed_model)
    with lock, db() as con:
        con.executescript("""
        CREATE TABLE IF NOT EXISTS doc_versions(
          id INTEGER PRIMARY KEY AUTOINCREMENT, attachment_id INTEGER NOT NULL, sha256 TEXT NOT NULL,
          index_version INTEGER NOT NULL, text TEXT NOT NULL, chars INTEGER NOT NULL,
          heading_source TEXT, sections INTEGER NOT NULL DEFAULT 0, chunks INTEGER NOT NULL DEFAULT 0,
          embedded INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL,
          UNIQUE(attachment_id, sha256, index_version)
        );
        CREATE TABLE IF NOT EXISTS doc_sections(
          id INTEGER PRIMARY KEY AUTOINCREMENT, version_id INTEGER NOT NULL, attachment_id INTEGER NOT NULL,
          ordinal INTEGER NOT NULL, level INTEGER NOT NULL, kind TEXT, number INTEGER, title TEXT NOT NULL,
          path TEXT NOT NULL, parent_ordinal INTEGER, start_char INTEGER NOT NULL, end_char INTEGER NOT NULL,
          body_chars INTEGER NOT NULL, source TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS doc_sections_lookup ON doc_sections(attachment_id, kind, number);
        CREATE TABLE IF NOT EXISTS doc_chunks(
          id INTEGER PRIMARY KEY AUTOINCREMENT, version_id INTEGER NOT NULL, attachment_id INTEGER NOT NULL,
          section_ordinal INTEGER, ordinal INTEGER NOT NULL, start_char INTEGER NOT NULL, end_char INTEGER NOT NULL,
          heading_path TEXT NOT NULL, text TEXT NOT NULL, tokens INTEGER NOT NULL, carried INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS doc_chunks_by_doc ON doc_chunks(attachment_id, ordinal);
        -- A story part's whole range ("Episode 4" from its first heading to where Episode 5 begins), independent
        -- of Word heading levels. source: "headings" (found by its own headings) or "inferred" (no heading of its own;
        -- placed between its neighbours and confirmed by the text naming itself).
        CREATE TABLE IF NOT EXISTS doc_ranges(
          id INTEGER PRIMARY KEY AUTOINCREMENT, version_id INTEGER NOT NULL, attachment_id INTEGER NOT NULL,
          ordinal INTEGER NOT NULL, kind TEXT NOT NULL, number INTEGER, title TEXT NOT NULL,
          start_char INTEGER NOT NULL, end_char INTEGER NOT NULL, source TEXT NOT NULL, headings TEXT, note TEXT
        );
        CREATE INDEX IF NOT EXISTS doc_ranges_lookup ON doc_ranges(attachment_id, kind, number);
        CREATE TABLE IF NOT EXISTS doc_section_summaries(
          version_id INTEGER NOT NULL, section_ordinal INTEGER NOT NULL, summary TEXT NOT NULL,
          mode TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(version_id, section_ordinal)
        );
        -- Which document is the authority for which part of a story ("the Episode 1 master
        -- is authoritative for Episode 1; the complete script for Episode 2 onward").
        CREATE TABLE IF NOT EXISTS doc_authority(
          id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, number_from INTEGER NOT NULL,
          number_to INTEGER NOT NULL, attachment_id INTEGER NOT NULL, note TEXT, created_at TEXT NOT NULL
        );
        """)


# ---------------------------------------------------------------- extraction

@dataclass
class Block:
    kind: str                 # "heading", "para", "table", "page"
    text: str
    level: int | None = None  # heading level from the document's own styles
    source: str = ""          # "style", "pattern", "markdown", "page"


def _docx_style_levels(z: zipfile.ZipFile) -> dict:
    """style id -> heading level, from the document's styles (names like 'Heading 2', 'Title', or an outline level)."""
    levels = {}
    try:
        root = ET.fromstring(z.read("word/styles.xml"))
    except Exception:
        return levels
    for st in root.iter(_W + "style"):
        sid = st.get(_W + "styleId") or ""
        name_el = st.find(_W + "name")
        name = (name_el.get(_W + "val") if name_el is not None else "") or sid
        low = name.lower()
        outline = st.find(f"{_W}pPr/{_W}outlineLvl")
        if low == "title":
            levels[sid] = 0
        elif re.match(r"^heading\s*(\d)$", low):
            levels[sid] = int(re.match(r"^heading\s*(\d)$", low).group(1))
        elif outline is not None and (outline.get(_W + "val") or "").isdigit() and int(outline.get(_W + "val")) < 9:
            levels[sid] = int(outline.get(_W + "val")) + 1
    return levels


def _docx_blocks(data: bytes) -> list[Block]:
    """Paragraphs and tables in reading order, with heading levels taken from the file's own styles."""
    z = zipfile.ZipFile(io.BytesIO(data))
    levels = _docx_style_levels(z)
    root = ET.fromstring(z.read("word/document.xml"))
    body = root.find(_W + "body")
    out: list[Block] = []

    def para_text(p) -> str:
        parts = []
        for node in p.iter():
            tag = node.tag
            if tag == _W + "t" and node.text:
                parts.append(node.text)
            elif tag == _W + "tab":
                parts.append("\t")
            elif tag in (_W + "br", _W + "cr"):
                parts.append("\n")
        return "".join(parts)

    for el in list(body) if body is not None else []:
        if el.tag == _W + "p":
            text = " ".join(para_text(el).replace("\n", " \n ").split(" ")).strip()
            text = re.sub(r"[ \t]+", " ", text).strip()
            if not text:
                continue
            level = None
            ppr = el.find(_W + "pPr")
            if ppr is not None:
                st = ppr.find(_W + "pStyle")
                if st is not None and st.get(_W + "val") in levels:
                    level = levels[st.get(_W + "val")]
                ol = ppr.find(_W + "outlineLvl")
                if level is None and ol is not None and (ol.get(_W + "val") or "").isdigit() and int(ol.get(_W + "val")) < 9:
                    level = int(ol.get(_W + "val")) + 1
            if level is not None and len(text) <= 200:
                out.append(Block("heading", text, level, "style"))
            else:
                out.append(Block("para", text))
        elif el.tag == _W + "tbl":
            rows = []
            for tr in el.iter(_W + "tr"):
                cells = [" ".join(para_text(tc).split()) for tc in tr.iter(_W + "tc")]
                if any(cells):
                    rows.append(" | ".join(cells))
            if rows:
                out.append(Block("table", "\n".join(rows)))
    return out


def _pdf_blocks(data: bytes) -> list[Block]:
    from pypdf import PdfReader
    reader = PdfReader(io.BytesIO(data))
    out: list[Block] = []
    for i, page in enumerate(reader.pages, 1):
        text = (page.extract_text() or "").strip()
        if not text:
            continue
        out.append(Block("page", f"[Page {i}]", None, "page"))
        for para in re.split(r"\n\s*\n", text):
            for line in para.split("\n"):
                line = " ".join(line.split())
                if line:
                    out.append(Block("para", line))
    return out


def _text_blocks(text: str) -> list[Block]:
    out = []
    for line in str(text).replace("\r\n", "\n").split("\n"):
        line = line.rstrip()
        if not line.strip():
            continue
        m = re.match(r"^(#{1,6})\s+(.+?)\s*#*$", line)
        if m:
            out.append(Block("heading", m.group(2).strip(), len(m.group(1)), "markdown"))
        else:
            out.append(Block("para", " ".join(line.split())))
    return out


def extract_blocks(mime: str, data: bytes) -> list[Block]:
    if mime == "application/pdf":
        return _pdf_blocks(data)
    if mime.endswith("wordprocessingml.document"):
        return _docx_blocks(data)
    return _text_blocks(data.decode("utf-8-sig", errors="replace"))


# ------------------------------------------------------------ heading logic

def _title_like(tail: str) -> bool:
    """Is the text after 'Episode 3' a title (or nothing) rather than the rest of a sentence?"""
    t = tail.strip()
    if not t:
        return True
    if t[0] in ":-–—.|)(,[":
        return True
    words = re.findall(r"[A-Za-z0-9'’]+", t)
    if not words or len(words) > 10:
        return False
    capitalised = sum(1 for w in words if w[0].isupper() or w[0].isdigit() or w.lower() in _MINOR)
    return capitalised == len(words) and not t.rstrip().endswith((".", "?", "!")) or t.isupper()


def structural_heading(text: str):
    """(kind, number or None, title) if this line *is* a structural heading, else None."""
    s = " ".join(str(text or "").split()).strip().strip("*_#").strip()
    if not s or len(s) > 120:
        return None
    m = _HEAD_RE.match(s)
    if m:
        if not _title_like(m.group("tail")):
            return None
        return m.group("kind").lower(), number_token(m.group("num")) or _roman_big(m.group("num")), s
    m = _NAMED_RE.match(s)
    if m and _title_like(m.group("tail")):
        return m.group("kind").lower(), None, s
    return None


def _roman_big(raw: str):
    vals = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100}
    s = str(raw).lower()
    if not s or any(ch not in vals for ch in s):
        return None
    total = 0
    for i, ch in enumerate(s):
        v = vals[ch]
        total += -v if i + 1 < len(s) and vals[s[i + 1]] > v else v
    return total or None


def _generic_heading(text: str) -> bool:
    """An unstyled line that looks like a heading: short, all capitals, no sentence ending."""
    s = text.strip()
    words = s.split()
    return 1 <= len(words) <= 8 and len(s) <= 70 and s.isupper() and any(c.isalpha() for c in s) and not s.endswith((".", ",", ";"))


@dataclass
class Section:
    ordinal: int
    level: int
    kind: str | None
    number: int | None
    title: str
    start: int
    end: int = 0
    body_chars: int = 0
    parent: int | None = None
    source: str = ""
    path: str = ""


@dataclass
class Index:
    text: str
    sections: list[Section] = field(default_factory=list)
    chunks: list[dict] = field(default_factory=list)
    heading_source: str = "none"
    ranges: list[dict] = field(default_factory=list)


def build_index(blocks: list[Block]) -> Index:
    """Canonical text, the section tree and section-bounded chunks, all with character positions."""
    styled = any(b.kind == "heading" and b.source == "style" for b in blocks)
    pieces: list[str] = []
    pos = 0
    marks = []          # (start, end, block, heading tuple or None)
    for b in blocks:
        head = None
        if b.kind == "heading":
            st = structural_heading(b.text)
            head = (st[0], st[1], b.text, b.level if b.level is not None else _STRUCT_LEVEL.get(st[0], 2), b.source) if st else \
                (None, None, b.text, b.level if b.level is not None else 2, b.source)
        elif b.kind == "para" and not styled:
            st = structural_heading(b.text)
            if st:
                head = (st[0], st[1], b.text, _STRUCT_LEVEL.get(st[0], 2), "pattern")
            elif _generic_heading(b.text):
                head = (None, None, b.text, 4, "pattern")
        elif b.kind == "para" and styled:
            # Even in a styled document, an unstyled line that is plainly "Episode 4" is still a boundary.
            st = structural_heading(b.text)
            if st and st[1] is not None:
                head = (st[0], st[1], b.text, _STRUCT_LEVEL.get(st[0], 2), "pattern")
        start = pos
        pieces.append(b.text)
        pos += len(b.text)
        marks.append((start, pos, b, head))
        pieces.append("\n")
        pos += 1
    text = "".join(pieces).rstrip("\n")

    idx = Index(text=text, heading_source="style" if styled else ("pattern" if any(m[3] for m in marks) else "none"))
    root = Section(0, -1, None, None, "(start)", 0, source="document")
    sections = [root]
    stack = [root]
    for start, end, b, head in marks:
        if head is None:
            continue
        kind, number, title, level, source = head
        while len(stack) > 1 and stack[-1].level >= level:
            stack.pop()
        sec = Section(len(sections), level, kind, number, title, start, parent=stack[-1].ordinal, source=source)
        sections.append(sec)
        stack.append(sec)
    # Each section runs until the next heading at the same or a higher level.
    for i, sec in enumerate(sections):
        sec.end = len(text)
        for later in sections[i + 1:]:
            if later.level <= sec.level:
                sec.end = later.start
                break
        # Own body: until the very next heading of any level.
        nxt = next((s.start for s in sections[i + 1:]), len(text))
        sec.body_chars = max(0, min(sec.end, nxt) - sec.start - len(sec.title))
    by_ord = {s.ordinal: s for s in sections}
    for sec in sections:
        names = []
        cur = sec
        while cur is not None and cur.ordinal != 0:
            names.append(cur.title[:80])
            cur = by_ord.get(cur.parent) if cur.parent is not None else None
        sec.path = " › ".join(reversed(names)) or "(start)"
    idx.sections = sections

    # Chunks: walk the leaf spans (from one heading to the next) and cut each at paragraph boundaries.
    boundaries = sorted({s.start for s in sections[1:]} | {0, len(text)})
    owner = {}
    for s in sections[1:]:
        owner[s.start] = s          # when headings share a start, the deepest one owns the text
    chunks = []
    for a, b in zip(boundaries, boundaries[1:]):
        if b <= a:
            continue
        sec = owner.get(a, root)
        paras = [(m.start(), m.end()) for m in re.finditer(r"[^\n]+", text[a:b])]
        cur_start = None
        cur_end = None
        carried = False
        for ps, pe in paras:
            ps += a
            pe += a
            if cur_start is None:
                cur_start, cur_end = ps, pe
                continue
            if pe - cur_start > TARGET_CHARS and cur_end - cur_start >= TARGET_CHARS // 3:
                chunks.append((sec, cur_start, cur_end, carried))
                last_para = text.rfind("\n", cur_start, cur_end)
                last_para = cur_start if last_para < 0 else last_para + 1
                if cur_end - last_para <= CARRY_CHARS and last_para > cur_start:
                    cur_start, carried = last_para, True
                else:
                    cur_start, carried = ps, False
            cur_end = pe
            while cur_end - cur_start > MAX_CHARS:          # one enormous paragraph
                cut = text.rfind(". ", cur_start + TARGET_CHARS // 2, cur_start + MAX_CHARS)
                cut = cut + 1 if cut > 0 else cur_start + MAX_CHARS
                chunks.append((sec, cur_start, cut, carried))
                cur_start, carried = cut, False
        if cur_start is not None and cur_end > cur_start:
            chunks.append((sec, cur_start, cur_end, carried))
    for i, (sec, s, e, carried) in enumerate(chunks):
        body = text[s:e].strip()
        if not body:
            continue
        idx.chunks.append({"ordinal": i, "section": sec.ordinal, "start": s, "end": e, "path": sec.path,
                           "text": body, "tokens": max(1, len(body) // 4), "carried": carried})
    idx.ranges = story_ranges(idx)
    return idx


# ----------------------------------------------------------------- story ranges

_NUMWORD = r"(\d{1,3}|[ivxlc]{1,6}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
_END_RE = re.compile(rf"^(?:end of (?P<k1>episode|chapter|part)\s+(?P<n1>{_NUMWORD})|(?P<k2>episode|chapter|part)\s+(?P<n2>{_NUMWORD})\s+"
                     r"(?:complete|completed|ends|ended|end|concludes))\W*$", re.I)
_SIDE_KINDS = {"prologue": 0, "epilogue": 10_000}
MIN_RANGE_HEADING_SPAN = 200     # an unstyled "Episode 3" line with almost nothing under it is a list entry, not a start


def _num(raw):
    return number_token(raw) or _roman_big(raw)


def story_ranges(idx: Index) -> list[dict]:
    """
    Where each episode (or chapter, or part) really runs: from its first genuine heading to where the next one
    begins or its own end marker ("EPISODE 1 COMPLETE", "END OF EPISODE 4."), whatever heading levels the
    document's styles use. Contents lists and variable tables that merely name the episodes are not starts.
    An episode with no heading of its own (the next one jumps from 1 to 3) is inferred from the gap between
    its neighbours, but only when that text keeps naming itself ("Episode 2 is Spock's episode").
    """
    text, secs = idx.text, idx.sections[1:]
    starts_sorted = sorted(s.start for s in secs)

    def own_span(s):
        later = next((x for x in starts_sorted if x > s.start), len(text))
        return later - s.start

    # Lines naming several parts close together ("Episode 1 / Episode 2 / … / Episode 5" in a variables table or a
    # contents list) are a list, even when the last of them happens to be followed by a paragraph.
    listed = set()
    named = sorted((s for s in secs if s.kind in ("episode", "part", "chapter") and s.number and s.source != "style"), key=lambda s: s.start)
    run = []
    for s in named + [None]:
        if s is not None and run and s.start - run[-1].start < MIN_RANGE_HEADING_SPAN and s.kind == run[-1].kind:
            run.append(s)
            continue
        if len(run) >= 2:
            listed.update(x.ordinal for x in run)
        run = [s] if s is not None else []

    def qualifies(s):
        if s.ordinal in listed:
            return False
        return s.source == "style" or own_span(s) >= MIN_RANGE_HEADING_SPAN

    primary = None
    for kind in ("episode", "part", "chapter"):
        if len({s.number for s in secs if s.kind == kind and s.number and qualifies(s)}) >= 2:
            primary = kind
            break
    if primary is None:
        return []
    events = []          # (pos, order, type, kind, number, title, end_pos)
    for s in secs:
        if not qualifies(s):
            continue
        line = " ".join(s.title.split())
        m = _END_RE.match(line)
        if m and (m.group("k1") or m.group("k2")).lower() == primary:
            events.append((s.start, 1, "end", primary, _num(m.group("n1") or m.group("n2")), line, s.end))
            continue
        if s.kind == primary and s.number:
            events.append((s.start, 0, "start", primary, s.number, s.title, None))
        elif s.kind in _SIDE_KINDS:
            events.append((s.start, 0, "start", s.kind, _SIDE_KINDS[s.kind], s.title, None))
    heading_starts = {e[0] for e in events}
    for m in re.finditer(r"[^\n]{4,60}", text):
        if m.start() in heading_starts:
            continue
        e = _END_RE.match(m.group(0).strip())
        if e and (e.group("k1") or e.group("k2")).lower() == primary:
            events.append((m.start(), 1, "end", primary, _num(e.group("n1") or e.group("n2")), m.group(0).strip(), m.end()))
    events.sort(key=lambda e: (e[0], e[1]))

    ranges, cur, done = [], None, set()
    order_of = lambda kind, n: n if kind == primary else _SIDE_KINDS[kind] if kind in _SIDE_KINDS else n
    last_order = -1
    for pos, _, typ, kind, number, title, end_pos in events:
        key = (kind, number)
        if typ == "start":
            if cur and cur["key"] == key:
                cur["headings"].append(title)
                continue
            # A later heading naming an earlier part (a recap, a reference) is not that part starting again.
            if key in done or order_of(kind, number) < last_order:
                continue
            if cur:
                cur["end"] = pos
                ranges.append(cur)
                done.add(cur["key"])
            cur = {"key": key, "kind": kind, "number": number if kind == primary else None, "title": title,
                   "start": pos, "end": len(text), "source": "headings", "headings": [title], "note": ""}
            last_order = order_of(kind, number)
        elif cur and cur["key"] == key:
            cur["end"] = end_pos
            cur["note"] = f"ends at “{title}”"
            ranges.append(cur)
            done.add(key)
            cur = None
        elif cur is None and ranges and ranges[-1]["key"] == key and end_pos > ranges[-1]["end"]:
            # A second end marker (END OF EPISODE 1, then the EPISODE 1 COMPLETE screen): the part runs to the last one.
            ranges[-1]["end"] = end_pos
            ranges[-1]["note"] = f"ends at “{title}”"
    if cur:
        ranges.append(cur)

    # Fill one missing number between neighbours when the text in the gap names itself.
    filled = []
    for a, b in zip(ranges, ranges[1:] + [None]):
        filled.append(a)
        if b is None or a["kind"] != primary or b["kind"] != primary or b["number"] != a["number"] + 2:
            continue
        gap = text[a["end"]:b["start"]]
        n = a["number"] + 1
        naming = len(re.findall(rf"\b{primary}\s+({n}|{_WORD_FOR.get(n, '#')})\b", gap[:60000], re.I))
        if len(gap.strip()) >= 2000 and naming >= 3:
            first = next((x for x in gap.splitlines() if x.strip()), "").strip()[:80]
            filled.append({"key": (primary, n), "kind": primary, "number": n, "title": f"{primary.title()} {n}",
                           "start": a["end"], "end": b["start"], "source": "inferred", "headings": [first] if first else [],
                           "note": f"no heading of its own; placed between “{a['title'][:60]}” and “{b['title'][:60]}”, "
                                   f"and the text names itself {primary.title()} {n} {naming} times"})
    for r in filled:
        r.pop("key", None)
    return filled


_WORD_FOR = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven", 8: "eight", 9: "nine", 10: "ten",
             11: "eleven", 12: "twelve"}


# ----------------------------------------------------------------- storage

def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def version_for(attachment_id: int):
    db = _CTX["db"]
    with db() as con:
        return con.execute("SELECT * FROM doc_versions WHERE attachment_id=? AND index_version=? ORDER BY id DESC LIMIT 1",
                           (attachment_id, INDEX_VERSION)).fetchone()


def ensure_index(attachment_id: int, data: bytes | None = None, progress=None):
    """Index (or re-index) one document from its original bytes. Cheap: no model calls.
    Returns the doc_versions row. Embeddings are added in the background."""
    db, lock = _CTX["db"], _CTX["lock"]
    progress = progress or (lambda *a, **k: None)
    with db() as con:
        att = con.execute("SELECT id, filename, mime, original_name, extracted_text FROM attachments WHERE id=?", (attachment_id,)).fetchone()
    if att is None:
        return None
    if data is None:
        path = _CTX["upload_dir"] / att["filename"]
        data = path.read_bytes() if path.exists() else (att["extracted_text"] or "").encode("utf-8")
        mime = att["mime"] if path.exists() else "text/plain"
    else:
        mime = att["mime"]
    sha = _sha(data)
    with db() as con:
        existing = con.execute("SELECT * FROM doc_versions WHERE attachment_id=? AND sha256=? AND index_version=?",
                               (attachment_id, sha, INDEX_VERSION)).fetchone()
    if existing:
        observe.debug("DOCUMENT", "index cache hit", doc=attachment_id, version=existing["id"], sha=sha[:12])
        return existing
    with observe.span("DOCUMENT", "document indexed", doc=attachment_id, name=att["original_name"]) as info:
        progress("reading", f"Reading {att['original_name'] or 'document'}…", doc_stage="reading")
        try:
            blocks = extract_blocks(mime, data)
        except Exception as e:
            observe.warn("DOCUMENT", "structured extraction failed; indexing the stored text instead", doc=attachment_id, error=str(e))
            blocks = _text_blocks(att["extracted_text"] or "")
        progress("chunking", f"Chunking {att['original_name'] or 'document'}…", doc_stage="chunking")
        idx = build_index(blocks)
        with lock, db() as con:
            vid = con.execute(
                "INSERT INTO doc_versions(attachment_id,sha256,index_version,text,chars,heading_source,sections,chunks,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (attachment_id, sha, INDEX_VERSION, idx.text, len(idx.text), idx.heading_source,
                 len(idx.sections) - 1, len(idx.chunks), _CTX["now"]())).lastrowid
            for s in idx.sections:
                con.execute(
                    "INSERT INTO doc_sections(version_id,attachment_id,ordinal,level,kind,number,title,path,parent_ordinal,"
                    "start_char,end_char,body_chars,source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (vid, attachment_id, s.ordinal, s.level, s.kind, s.number, s.title, s.path, s.parent,
                     s.start, s.end, s.body_chars, s.source))
            for i, r in enumerate(idx.ranges):
                con.execute(
                    "INSERT INTO doc_ranges(version_id,attachment_id,ordinal,kind,number,title,start_char,end_char,source,headings,note) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    (vid, attachment_id, i, r["kind"], r["number"], r["title"], r["start"], r["end"], r["source"],
                     json.dumps(r["headings"][:12], ensure_ascii=False), r["note"]))
            for c in idx.chunks:
                con.execute(
                    "INSERT INTO doc_chunks(version_id,attachment_id,section_ordinal,ordinal,start_char,end_char,heading_path,text,tokens,carried) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (vid, attachment_id, c["section"], c["ordinal"], c["start"], c["end"], c["path"], c["text"], c["tokens"], int(c["carried"])))
        info.update(version=vid, sha=sha[:12], blocks=len(blocks), headings_from=idx.heading_source,
                    sections=len(idx.sections) - 1, chunks=len(idx.chunks), chars=len(idx.text),
                    structural=[f"{s.kind} {s.number}" for s in idx.sections if s.kind and s.number][:40],
                    story_ranges=[{"part": r["title"][:60], "range": [r["start"], r["end"]], "source": r["source"]} for r in idx.ranges],
                    carried_overlaps=sum(1 for c in idx.chunks if c["carried"]))
    progress("indexing", f"Indexing {att['original_name'] or 'document'}…", doc_stage="indexing")
    threading.Thread(target=observe.run_in_context(_embed_version, vid), daemon=True, name=f"embed-doc-{attachment_id}").start()
    return version_for(attachment_id)


def _embed_version(version_id: int):
    db, lock, embed = _CTX["db"], _CTX["lock"], _CTX["embed"]
    with db() as con:
        rows = con.execute("SELECT id, heading_path, text FROM doc_chunks WHERE version_id=? ORDER BY ordinal", (version_id,)).fetchall()
    done = 0
    for i in range(0, len(rows), 24):
        batch = rows[i:i + 24]
        texts = [f"{r['heading_path']}\n{r['text']}" for r in batch]
        vectors = embed(texts, "document")
        if vectors is None:
            observe.event("DOCUMENT", "embeddings unavailable; word matching will be used", version=version_id, embedded=done)
            return
        with lock, db() as con:
            for r, t, v in zip(batch, texts, vectors):
                con.execute("INSERT OR REPLACE INTO embeddings(kind,ref_id,model,text_hash,vector) VALUES(?,?,?,?,?)",
                            ("doc_chunk", r["id"], _CTX.get("embed_model", "doc"), _CTX["text_hash"](t), v.tobytes()))
        done += len(batch)
    with lock, db() as con:
        con.execute("UPDATE doc_versions SET embedded=1 WHERE id=?", (version_id,))
    observe.event("DOCUMENT", "embeddings stored", version=version_id, chunks=done)


# --------------------------------------------------------------- authority

def set_authority(kind: str, number_from: int, number_to: int, attachment_id: int, note: str = "") -> int:
    db, lock = _CTX["db"], _CTX["lock"]
    with lock, db() as con:
        con.execute("DELETE FROM doc_authority WHERE kind=? AND number_from=? AND number_to=?", (kind, number_from, number_to))
        rid = con.execute("INSERT INTO doc_authority(kind,number_from,number_to,attachment_id,note,created_at) VALUES(?,?,?,?,?,?)",
                          (kind, number_from, number_to, attachment_id, note, _CTX["now"]())).lastrowid
    observe.event("DOCUMENT", "authority rule set", kind=kind, range=[number_from, number_to], doc=attachment_id)
    return rid


def authority_for(kind: str, number: int):
    db = _CTX["db"]
    with db() as con:
        row = con.execute("SELECT attachment_id FROM doc_authority WHERE kind=? AND ? BETWEEN number_from AND number_to "
                          "ORDER BY (number_to - number_from), id DESC LIMIT 1", (kind, number)).fetchone()
    return int(row["attachment_id"]) if row else None


def authorities() -> list[dict]:
    db = _CTX["db"]
    with db() as con:
        return [dict(r) for r in con.execute("SELECT * FROM doc_authority ORDER BY kind, number_from")]


# ---------------------------------------------------------------- retrieval

RANGE_ORDINAL_BASE = 1_000_000      # summary-cache keys for story ranges, apart from section ordinals


def story_map(attachment_id: int) -> list[dict]:
    """The story parts of one document, in order, with their real character ranges."""
    db = _CTX["db"]
    v = version_for(attachment_id)
    if not v:
        return []
    with db() as con:
        return [dict(r, version_id=v["id"], ordinal=RANGE_ORDINAL_BASE + r["ordinal"], start_char=r["start_char"],
                     span_chars=r["end_char"] - r["start_char"], body_chars=r["end_char"] - r["start_char"],
                     path=r["title"], range=True)
                for r in con.execute("SELECT * FROM doc_ranges WHERE version_id=? ORDER BY start_char", (v["id"],))]


def front_matter(attachment_id: int, limit: int = 4) -> list[str]:
    """Headings before the first story part (title pages, design standards, contents)."""
    parts = story_map(attachment_id)
    if not parts:
        return []
    db = _CTX["db"]
    with db() as con:
        return [r["title"] for r in con.execute(
            "SELECT title FROM doc_sections WHERE version_id=? AND ordinal>0 AND start_char<? AND level<=2 ORDER BY start_char LIMIT ?",
            (parts[0]["version_id"], parts[0]["start_char"], limit))]


def find_sections(attachment_ids, kind: str, number: int) -> list[dict]:
    """The story range for kind+number if the document has one (heading levels don't matter), otherwise every
    genuine heading for it, best (longest body) first."""
    if not attachment_ids:
        return []
    db = _CTX["db"]
    out = []
    for aid in attachment_ids:
        for r in story_map(aid):
            if r["kind"] == kind and (r["number"] == number or (number is None and r["number"] is None)):
                out.append(r)
    ranges = out
    out = []
    with db() as con:
        for aid in attachment_ids:
            v = con.execute("SELECT id FROM doc_versions WHERE attachment_id=? AND index_version=? ORDER BY id DESC LIMIT 1",
                            (aid, INDEX_VERSION)).fetchone()
            if not v:
                continue
            for s in con.execute("SELECT * FROM doc_sections WHERE version_id=? AND kind=? AND number=?", (v["id"], kind, number)):
                out.append(dict(s, span_chars=s["end_char"] - s["start_char"]))
    out.sort(key=lambda s: (s["span_chars"], s["body_chars"]), reverse=True)
    # The story range wins; the individual headings follow, so Diagnostics shows what was passed over.
    return ranges + out


def section_text(version_id: int, start: int, end: int) -> str:
    db = _CTX["db"]
    with db() as con:
        row = con.execute("SELECT substr(text, ?, ?) AS t FROM doc_versions WHERE id=?", (start + 1, end - start, version_id)).fetchone()
    return row["t"] if row else ""


def structural_evidence(attachment_ids, kind: str, number: int, budget: int = STRUCTURAL_BUDGET, summarise=None, progress=None):
    """The original text of one structural section, exactly from its heading to where the next peer begins.
    Returns dict(found, attachment_id, section, text, truncated, reason, candidates)."""
    candidates = find_sections(attachment_ids, kind, number)
    result = {"found": False, "target": f"{kind} {number}", "candidates": [
        {"doc": c["attachment_id"], "title": c["title"], "chars": c["span_chars"], "source": c["source"]} for c in candidates[:6]]}
    if not candidates:
        result["reason"] = "no genuine heading for this section in the indexed documents"
        observe.event("RETRIEVAL", "structural target not located", target=result["target"], docs=list(attachment_ids))
        return result
    best = candidates[0]
    text = section_text(best["version_id"], best["start_char"], best["end_char"]).strip()
    if best.get("range"):
        text = _mark_recaps(best, text, kind, number)
    db = _CTX["db"]
    with db() as con:
        nxt = con.execute("SELECT title FROM doc_sections WHERE version_id=? AND start_char>=? ORDER BY start_char LIMIT 1",
                          (best["version_id"], best["end_char"])).fetchone()
    if best.get("range"):
        nxt = None
        later = [r for r in story_map(best["attachment_id"]) if r["start_char"] >= best["end_char"]]
        nxt = {"title": later[0]["title"]} if later else None
    result.update(found=True, attachment_id=best["attachment_id"], version_id=best["version_id"], section=best["title"],
                  path=best["path"], start=best["start_char"], end=best["end_char"], stop_boundary=nxt["title"] if nxt else "(end of document)",
                  truncated=False, mode="original")
    if len(text) > budget:
        summary = None
        if summarise is not None:
            summary = range_summary(best, summarise, progress) if best.get("range") else section_summary(best, summarise, progress)
        if summary:
            result.update(text=summary, mode="section_summary_of_original", truncated=False,
                          original_chars=len(text))
        else:
            cut = text.rfind("\n", 0, budget)
            result.update(text=text[:cut if cut > budget // 2 else budget], truncated=True, original_chars=len(text))
    else:
        result["text"] = text
    if best.get("range"):
        result.update(range_source=best["source"], range_note=best.get("note") or "")
    observe.event("RETRIEVAL", "structural section selected", doc=best["attachment_id"], target=result["target"],
                  story_range=bool(best.get("range")), range_source=best.get("source"),
                  heading=best["title"], heading_source=best["source"], range=[best["start_char"], best["end_char"]],
                  stop_boundary=result["stop_boundary"], evidence_chars=len(result.get("text") or ""), mode=result["mode"],
                  rejected=[{"title": c["title"], "chars": c["span_chars"]} for c in candidates[1:5]])
    return result


def _mark_recaps(part: dict, text: str, kind: str, number) -> str:
    """Inside Episode 4, a heading such as "Opening Recap — The End of Episode 3" introduces Episode 3's events.
    Say so in the evidence, so they are never told as Episode 4's."""
    db = _CTX["db"]
    with db() as con:
        heads = con.execute("SELECT title, start_char FROM doc_sections WHERE version_id=? AND start_char>? AND start_char<? ORDER BY start_char",
                            (part["version_id"], part["start_char"], part["end_char"])).fetchall()
    pat = re.compile(rf"\b{kind}\s+{_NUMWORD}\b", re.I)
    out, last = [], 0
    base = part["start_char"]
    lead = len(section_text(part["version_id"], part["start_char"], part["end_char"])) - len(section_text(part["version_id"], part["start_char"], part["end_char"]).lstrip())
    for h in heads:
        m = pat.search(h["title"])
        if not m or _num(m.group(1)) == number:
            continue
        at = h["start_char"] - base - lead
        if 0 <= at <= len(text):
            other = _num(m.group(1))
            out.append(text[last:at])
            out.append(f"[Note from Gemma's document index: the part below, headed “{h['title'][:80]}”, recaps or refers to "
                       f"{kind.title()} {other}. Those are {kind.title()} {other}'s events, not new {kind.title()} {number} events.]\n")
            last = at
    out.append(text[last:])
    return "".join(out)


def range_summary(part: dict, summarise, progress=None) -> str | None:
    """A faithful summary of one whole story part (episode), built from its exact text range in pieces, and cached."""
    db, lock = _CTX["db"], _CTX["lock"]
    vid, key = part["version_id"], part["ordinal"]
    with db() as con:
        cached = con.execute("SELECT summary FROM doc_section_summaries WHERE version_id=? AND section_ordinal=?", (vid, key)).fetchone()
    if cached:
        observe.event("DOCUMENT", "story part summary cache hit", part=part["title"])
        return cached["summary"]
    text = section_text(vid, part["start_char"], part["end_char"])
    parts, pos = [], 0
    while pos < len(text):
        end = min(len(text), pos + 20000)
        if end < len(text):
            cut = text.rfind("\n", pos + 10000, end)
            end = cut if cut > pos else end
        parts.append(text[pos:end])
        pos = end
    label = part["title"] if part["source"] == "headings" else f"{part['kind'].title()} {part['number']}"
    notes = []
    for i, piece in enumerate(parts, 1):
        if progress:
            pct = max(1, min(99, round(i / len(parts) * 99)))
            progress("analysing", f"Reading {label} closely — part {i}/{len(parts)} · {pct}%", done=i - 1, total=len(parts),
                     percent=pct, doc_stage="analysing", doc_name=label)
        try:
            notes.append(summarise(f"{label} (part {i} of {len(parts)})\n\n{piece}\n\n"
                                   "Write a dense, faithful account of the story events in this part, in order: who does what, where, "
                                   "and what is discovered. Keep every named character, place and event. Production notes (file names, "
                                   "variables, recording lists) only in a line if they matter. If a passage is a recap of an earlier "
                                   "episode, say so. Add nothing that is not in the text."))
        except Exception as e:
            observe.warn("DOCUMENT", "story part summary failed", part=label, piece=i, error=str(e))
            return None
    summary = "\n\n".join(notes)
    with lock, db() as con:
        con.execute("INSERT OR REPLACE INTO doc_section_summaries(version_id,section_ordinal,summary,mode,created_at) VALUES(?,?,?,?,?)",
                    (vid, key, summary, "generated", _CTX["now"]()))
    return summary


def section_summary(section_row: dict, summarise, progress=None) -> str | None:
    """A faithful summary of one section too long to give whole, built from its original chunks and cached."""
    db, lock = _CTX["db"], _CTX["lock"]
    vid, ordn = section_row["version_id"], section_row["ordinal"]
    with db() as con:
        cached = con.execute("SELECT summary FROM doc_section_summaries WHERE version_id=? AND section_ordinal=?", (vid, ordn)).fetchone()
        if cached:
            observe.event("DOCUMENT", "section summary cache hit", section=section_row["title"])
            return cached["summary"]
        rows = con.execute("SELECT text FROM doc_chunks WHERE version_id=? AND start_char>=? AND end_char<=? ORDER BY ordinal",
                           (vid, section_row["start_char"], section_row["end_char"])).fetchall()
    parts, group, size = [], [], 0
    for r in rows:
        if group and size + len(r["text"]) > 20000:
            parts.append("\n\n".join(group))
            group, size = [], 0
        group.append(r["text"])
        size += len(r["text"])
    if group:
        parts.append("\n\n".join(group))
    notes = []
    for i, part in enumerate(parts, 1):
        if progress:
            progress("analysing", f"Reading {section_row['title']} closely — part {i}/{len(parts)}", done=i - 1, total=len(parts))
        try:
            notes.append(summarise(f"Section: {section_row['title']} (part {i} of {len(parts)})\n\n{part}\n\n"
                                   "Write a dense, faithful account of everything that happens in this part, in order. "
                                   "Keep every named character, place and event. Add nothing that is not in the text."))
        except Exception as e:
            observe.warn("DOCUMENT", "section summary part failed", section=section_row["title"], part=i, error=str(e))
            return None
    summary = "\n\n".join(notes)
    with lock, db() as con:
        con.execute("INSERT OR REPLACE INTO doc_section_summaries(version_id,section_ordinal,summary,mode,created_at) VALUES(?,?,?,?,?)",
                    (vid, ordn, summary, "generated", _CTX["now"]()))
    return summary


_WORD = re.compile(r"[a-z0-9']{2,}")
_STOP = set("the a an and or of to in on at for with from by is are was were be been it its this that these those what which who "
            "how why when where do does did can could would should will i you he she they we me my your his her their our about "
            "tell please into than then there here just also so as if not no yes".split())


def _terms(text: str) -> list[str]:
    return [w for w in _WORD.findall(text.lower()) if w not in _STOP]


def hybrid_evidence(attachment_ids, query: str, semantic=None, budget: int = HYBRID_BUDGET, limit: int = 8):
    """Ranked original passages for a narrower question: words, meaning and section titles, then
    neighbouring passages from the same section only."""
    db = _CTX["db"]
    rows = []
    with db() as con:
        for aid in attachment_ids:
            v = con.execute("SELECT id FROM doc_versions WHERE attachment_id=? AND index_version=? ORDER BY id DESC LIMIT 1",
                            (aid, INDEX_VERSION)).fetchone()
            if v:
                rows += [dict(r) for r in con.execute("SELECT * FROM doc_chunks WHERE version_id=? ORDER BY ordinal", (v["id"],))]
    if not rows:
        return {"chunks": [], "reason": "no indexed text"}
    q = _terms(query)
    if not q:
        return {"chunks": [], "reason": "no searchable words in the question"}
    n = len(rows)
    df = {}
    docs_terms = []
    for r in rows:
        ts = _terms(r["text"])
        docs_terms.append(ts)
        for t in set(ts):
            df[t] = df.get(t, 0) + 1
    avg = sum(len(t) for t in docs_terms) / max(1, n)
    meaning = semantic(query) if semantic else {}
    scored = []
    for r, ts in zip(rows, docs_terms):
        tf = {}
        for t in ts:
            tf[t] = tf.get(t, 0) + 1
        bm25 = 0.0
        for t in set(q):
            if t in tf:
                idf = math.log(1 + (n - df[t] + 0.5) / (df[t] + 0.5))
                bm25 += idf * tf[t] * 2.2 / (tf[t] + 1.2 * (0.25 + 0.75 * len(ts) / max(1.0, avg)))
        head = len(set(q) & set(_terms(r["heading_path"]))) * 1.5
        sem = max(0.0, meaning.get(r["id"], 0.0)) * 6.0
        scored.append((bm25 + head + sem, bm25, head, sem, r))
    scored.sort(key=lambda x: x[0], reverse=True)
    seeds = [x for x in scored[:limit] if x[0] > 0.5]
    picked = {}
    by_key = {(r["attachment_id"], r["ordinal"]): r for r in rows}
    for score, *_rest, r in seeds:
        for j in (r["ordinal"] - 1, r["ordinal"], r["ordinal"] + 1):
            nb = by_key.get((r["attachment_id"], j))
            if nb and nb["section_ordinal"] == r["section_ordinal"]:      # never leak into the next section
                picked.setdefault((nb["attachment_id"], j), nb)
    chosen, used = [], 0
    for key in sorted(picked, key=lambda k: (k[0], k[1])):
        c = picked[key]
        if chosen and used + len(c["text"]) > budget:
            continue
        chosen.append(c)
        used += len(c["text"])
    observe.event("RETRIEVAL", "hybrid passages selected", query_terms=q[:12], candidates=len(rows),
                  top=[{"chunk": x[4]["ordinal"], "path": x[4]["heading_path"][:60], "score": round(x[0], 2), "lexical": round(x[1], 2),
                        "heading": x[2], "semantic": round(x[3], 2)} for x in scored[:6]],
                  selected=[c["ordinal"] for c in chosen], evidence_chars=used)
    return {"chunks": chosen, "reason": "" if chosen else "nothing in the indexed text matched the question"}


def outline(attachment_id: int, limit: int = 80) -> list[dict]:
    db = _CTX["db"]
    v = version_for(attachment_id)
    if not v:
        return []
    with db() as con:
        return [dict(r) for r in con.execute(
            "SELECT ordinal, level, kind, number, title, body_chars, end_char - start_char AS span FROM doc_sections "
            "WHERE version_id=? AND ordinal>0 AND level<=3 ORDER BY ordinal LIMIT ?", (v["id"], limit))]


def documents_with_index() -> list[dict]:
    db = _CTX["db"]
    with db() as con:
        return [dict(r) for r in con.execute(
            "SELECT a.id, a.original_name, a.mime, a.analysis_status, v.sections, v.chunks, v.chars, v.heading_source, v.embedded, v.created_at "
            "FROM attachments a LEFT JOIN doc_versions v ON v.attachment_id=a.id AND v.index_version=? "
            "WHERE a.mime IN ('application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','text/plain') "
            "ORDER BY a.id DESC", (INDEX_VERSION,))]
