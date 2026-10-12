"""
End-to-end turn tests against a real server and the stand-in model.
    python3 tests/test_turns_v2.py
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import Live, doc_payload            # noqa: E402
from make_script import build                    # noqa: E402

checks = 0


def ok(cond, what):
    global checks
    checks += 1
    if not cond:
        raise AssertionError("FAILED: " + what)


def last_chat(live):
    reqs = [r for r in live.chat_requests() if r["purpose"] == "chat"]
    return reqs[-1] if reqs else None


def user_content(req):
    return next(m["content"] for m in reversed(req["messages"]) if m["role"] == "user")


def purposes(live):
    return [c["purpose"] for c in live.calls()]


live = Live()
try:
    cid = live.new_chat("doc tests")

    # 1. fast path
    live.clear_calls()
    ev = live.turn(cid, "Morning Gemma!")
    done = [e for e in ev if e["type"] == "done"]
    ok(done and done[0]["reply"], "greeting answered")
    ok(ev[0]["type"] == "start" and ev[0].get("request_id", "").startswith("r"), "start event carries a request id")
    ok(purposes(live) == ["chat"], f"greeting makes exactly one model call (got {purposes(live)})")
    ok("attached_document_material" not in user_content(last_chat(live)), "greeting carries no document material")
    ok("ATTACHMENT AND DOCUMENT TRUTH" not in last_chat(live)["messages"][0]["content"], "greeting prompt has no document rules")

    # 2. upload the (large) script
    live.clear_calls()
    script = build(styled=True, filler_paras=60)
    ev = live.turn(cid, "Here's the complete script.", documents=[doc_payload("Complete Vulcan Fury Script.docx", script)])
    stages = [e.get("doc_stage") for e in ev if e["type"] == "status" and e.get("doc_stage")]
    ok("reading" in stages and "chunking" in stages and "indexing" in stages, f"upload reports real reading/chunking/indexing stages ({stages})")
    analysing = [e for e in ev if e["type"] == "status" and e.get("doc_stage") == "analysing"]
    ok(analysing and all(isinstance(e.get("total"), int) for e in analysing), "analysis progress carries done/total from the backend")
    ok(any(e.get("doc_stage") == "complete" and e.get("percent") == 100 for e in ev if e["type"] == "status"), "analysis reports completion")
    ok([e for e in ev if e["type"] == "done"], "upload turn answered")
    ok("research_planner" not in purposes(live), "an upload turn never consults the web planner")

    # 3. explicit Episode 4 first (so it sits in history), then Episode 3
    live.clear_calls()
    live.turn(cid, "What happens in Episode 4?")
    c4 = user_content(last_chat(live))
    ok("Kalandra" in c4 and "Scotty becomes acting captain" not in c4, "Episode 4 evidence is Episode 4")
    live.clear_calls()
    live.turn(cid, "Summarise Episode 3.")
    req = last_chat(live)
    c3 = user_content(req)
    ok('section="Episode 3 – Acting Captain"' in c3, "Episode 3 evidence is the genuine section")
    for must in ("Scotty becomes acting captain", "Auxiliary Control on Deck 7", "Romulan Infiltration Matrix", "asteroid field",
                 "transporter has been sabotaged"):
        ok(must in c3, f"Episode 3 evidence contains {must!r}")
    for never in ("bowling", "Chapel", "Science Officer's Log", "Kalandra"):
        ok(never not in c3, f"Episode 3 evidence has no {never!r}")
    ok(not [m for m in req["messages"] if m["role"] == "assistant"], "document turn leaves Gemma's earlier replies out")
    ok("research_planner" not in purposes(live), "a document question never consults the web planner")

    # 4. the v9 failure: whole-script opinion right after Episode questions
    live.clear_calls()
    live.turn(cid, "What do you think of the whole script as a story?")
    cw = user_content(last_chat(live))
    # Since story ranges (index v3): every episode from its own range, plus the document map, instead of a v9 overview and outline.
    ok("<document_map" in cw and cw.count("<story_part ") >= 4, "whole-script question gets every episode from its own range")
    ok("Episode 3 – Acting Captain" in cw, "whole-script question names the real episodes")
    ok('target="episode 4"' not in cw and 'target="episode 3"' not in cw, "no structural lookup for a whole-script question")
    ok("asking for YOUR opinion" in cw, "an opinion request is marked as one")

    # 5. acknowledgement: no unsolicited document task
    live.clear_calls()
    live.turn(cid, "I finally fixed your logic. You should now have access to the whole script.")
    ca = user_content(last_chat(live))
    ok("attached_document_material" not in ca, "acknowledgement carries no document material")
    ok(not {"research_planner", "provenance_classifier"} & set(purposes(live)), f"acknowledgement is a fast-path turn ({purposes(live)})")

    # 6. upload announced, nothing attached
    live.clear_calls()
    live.turn(cid, "Would you like to read the script in its entirety? Let me upload it.")
    cu = user_content(last_chat(live))
    ok("attached_document_material" not in cu, "announced upload: no old script retrieved")
    ok("nothing has arrived yet" in cu, "announced upload: attachment truth says nothing has arrived")

    # 7. topic change
    live.clear_calls()
    live.turn(cid, "What should I cook for dinner tonight?")
    cd = user_content(last_chat(live))
    ok("attached_document_material" not in cd, "dinner question carries no document material")
    sysd = last_chat(live)["messages"][0]["content"]
    ok("Vulcan Fury" not in sysd, "personality prompt no longer names projects")

    print(f"TURN TESTS OK: {checks} checks")
finally:
    live.stop()
