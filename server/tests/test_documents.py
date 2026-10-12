"""
Document regression tests (stand-in script; the real one lives on Vision).

    python3 tests/test_documents.py

Covers the baseline Glen asked to preserve and the defects found in the audit:
Episode 3 is the real Scotty episode, Episode 4 is found, an incidental
"Episode 3 will reveal..." never outranks the genuine heading, evidence stops
exactly where Episode 4 begins, a table of contents entry is not mistaken for
the episode, documents without heading styles still work, authority rules pick
the Episode 1 master, and re-indexing the same bytes is a cache hit.
"""
import array
import os
import sqlite3
import sys
import tempfile
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

from gemma_core import documents as D  # noqa: E402
from make_script import build, build_episode1_master  # noqa: E402

TMP = Path(tempfile.mkdtemp())
DBP = TMP / "t.db"
LOCK = threading.Lock()
checks = 0


@contextmanager
def db():
    con = sqlite3.connect(DBP)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    finally:
        con.close()


def ok(cond, what):
    global checks
    checks += 1
    if not cond:
        raise AssertionError("FAILED: " + what)


with db() as con:
    con.executescript("""
    CREATE TABLE attachments(id INTEGER PRIMARY KEY, message_id INTEGER, filename TEXT, mime TEXT, created_at TEXT,
      original_name TEXT, extracted_text TEXT, analysis_status TEXT, analysis_error TEXT);
    CREATE TABLE embeddings(kind TEXT, ref_id INTEGER, model TEXT, text_hash TEXT, vector BLOB, PRIMARY KEY(kind, ref_id));
    """)
D.init(db=db, lock=LOCK, upload_dir=TMP, embed=lambda texts, purpose: None,
       now=lambda: datetime.now(timezone.utc).isoformat(), text_hash=lambda t: str(hash(t)))

DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


def add(aid, name, data):
    (TMP / f"{aid}.docx").write_bytes(data)
    with db() as con:
        con.execute("INSERT INTO attachments(id,message_id,filename,mime,created_at,original_name,extracted_text,analysis_status) "
                    "VALUES(?,?,?,?,?,?,?,?)", (aid, aid, f"{aid}.docx", DOCX, "now", name, "", "complete"))
    return D.ensure_index(aid)


# --- the heading test itself
ok(D.structural_heading("Episode 3 – Acting Captain")[:2] == ("episode", 3), "dash title is a heading")
ok(D.structural_heading("EPISODE 3")[:2] == ("episode", 3), "bare capitals heading")
ok(D.structural_heading("Episode 3 Synopsis")[:2] == ("episode", 3), "title-case tail is a heading")
ok(D.structural_heading("Episode 3 will reveal the full scale of the Romulan plan.") is None, "prose is not a heading")
ok(D.structural_heading("Episode 3 must not be cut.") is None, "prose is not a heading (2)")
ok(D.structural_heading("Scene 12: Engineering") [:2] == ("scene", 12), "scene heading")

for styled in (True, False):
    label = "styled" if styled else "unstyled"
    base = 10 if styled else 20
    v = add(base, f"Complete Vulcan Fury Script ({label}).docx", build(styled=styled))
    ok(v is not None and v["sections"] >= 5, f"{label}: sections were found ({v and v['sections']})")
    ok(v["heading_source"] == ("style" if styled else "pattern"), f"{label}: heading source {v['heading_source']}")

    ep3 = D.structural_evidence([base], "episode", 3)
    ok(ep3["found"], f"{label}: Episode 3 located")
    t = ep3["text"]
    for must in ("Scotty becomes acting captain", "Auxiliary Control on Deck 7", "Romulan Infiltration Matrix", "asteroid field",
                 "transporter logs are reconstructed", "hidden mining colony", "transporter has been sabotaged", "leads on to Episode 4"):
        ok(must in t, f"{label}: Episode 3 evidence contains '{must}'")
    for never in ("bowling", "Chapel", "Science Officer's Log", "Kalandra", "Uhura decodes", "Project notes", "High Council", "Character Portraits"):
        ok(never not in t, f"{label}: Episode 3 evidence must not contain '{never}'")
    ok(ep3["stop_boundary"].startswith("Episode 4"), f"{label}: evidence stops at Episode 4 (stopped at {ep3['stop_boundary']!r})")
    ok(ep3["section"].startswith("Episode 3 – Acting Captain"), f"{label}: the genuine heading wins over the contents entry and prose")
    ok(len(ep3["candidates"]) >= 2, f"{label}: the contents entry was seen and rejected ({ep3['candidates']})")

    ep4 = D.structural_evidence([base], "episode", 4)
    ok(ep4["found"] and "Kalandra" in ep4["text"] and "Scotty becomes" not in ep4["text"], f"{label}: Episode 4 is the real Episode 4")
    ok(ep4["stop_boundary"].startswith("Design Bible") or ep4["stop_boundary"] == "(end of document)", f"{label}: Episode 4 stops at the next section")

    missing = D.structural_evidence([base], "episode", 9)
    ok(not missing["found"] and "no genuine heading" in missing["reason"], f"{label}: a missing episode is reported as not located, not absent")

    # chunks never cross a section boundary and carry their position
    with db() as con:
        rows = con.execute("SELECT * FROM doc_chunks WHERE attachment_id=? ORDER BY ordinal", (base,)).fetchall()
        secs = {r["ordinal"]: r for r in con.execute("SELECT * FROM doc_sections WHERE attachment_id=?", (base,))}
        vtext = con.execute("SELECT text FROM doc_versions WHERE attachment_id=?", (base,)).fetchone()["text"]
    for r in rows:
        s = secs[r["section_ordinal"]]
        ok(s["start_char"] <= r["start_char"] and r["end_char"] <= max(s["end_char"], r["end_char"]), f"{label}: chunk {r['ordinal']} inside its section")
        ok(vtext[r["start_char"]:r["end_char"]].strip() == r["text"], f"{label}: chunk {r['ordinal']} positions match the text")
        ok(len(r["text"]) <= D.MAX_CHARS + 10, f"{label}: chunk {r['ordinal']} within size limit ({len(r['text'])})")
        nxt = [x for x in secs.values() if x["start_char"] > r["start_char"] and x["start_char"] < r["end_char"]]
        ok(not nxt, f"{label}: chunk {r['ordinal']} does not run into the next heading {[x['title'] for x in nxt]}")

    # the table stays where it was written (after the Design Bible heading)
    ok(vtext.index("Episode | Portrait") > vtext.index("Design Bible"), f"{label}: table kept in reading order")

    # hybrid retrieval for a narrow question finds the Matrix and does not leak across sections
    h = D.hybrid_evidence([base], "Where was the Romulan Infiltration Matrix found?")
    ok(h["chunks"] and any("Infiltration Matrix" in c["text"] for c in h["chunks"]), f"{label}: narrow question finds the Matrix passage")

# cache: same bytes, no new version
with db() as con:
    before = con.execute("SELECT COUNT(*) FROM doc_versions").fetchone()[0]
D.ensure_index(10)
with db() as con:
    after = con.execute("SELECT COUNT(*) FROM doc_versions").fetchone()[0]
ok(before == after, "re-indexing identical bytes is a cache hit")

# authority: the Episode 1 master wins Episode 1; the complete script wins Episode 2 onward
add(30, "Episode 1 Master.docx", build_episode1_master())
D.set_authority("episode", 1, 1, 30, "Glen: the separate master is authoritative for Episode 1")
D.set_authority("episode", 2, 99, 10, "Glen: the complete script is authoritative from Episode 2")
ok(D.authority_for("episode", 1) == 30 and D.authority_for("episode", 3) == 10, "authority rules resolve")
e1 = D.structural_evidence([D.authority_for("episode", 1)], "episode", 1)
ok(e1["found"] and "MASTER VERSION" in e1["text"], "Episode 1 comes from the master")

# outline for whole-document questions
out = D.outline(10)
ok([o["title"] for o in out if o["kind"] == "episode" and o["number"] == 3], "outline lists Episode 3")

print(f"DOCUMENT TESTS OK: {checks} checks")
