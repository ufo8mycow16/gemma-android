#!/usr/bin/env python3
"""
Measure how quickly Gemma answers, from the outside, the way the apps see it.

    python3 tools/bench.py http://127.0.0.1:7860 --key <access key> [--rounds 3]

Works against any Gemma that has the /api/v1 streaming endpoint, old or new,
so the same command gives a fair before/after comparison on Vision. Each
scenario is sent in a fresh conversation (so history does not differ between
runs) and timed: first status, first word of the reply, and the whole reply.
Nothing is changed except that a few small test conversations are created.
"""
import argparse, json, statistics, sys, time, urllib.request, uuid

SCENARIOS = [
    ("greeting", "Morning Gemma!"),
    ("casual", "I had a really nice walk this afternoon, the weather was perfect."),
    ("question", "What's the difference between a list and a tuple in Python?"),
]


def call(base, key, method, path, body=None, stream=False, timeout=600):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Authorization", "Bearer " + key)
    return urllib.request.urlopen(req, timeout=timeout)


def one(base, key, text):
    with call(base, key, "POST", "/api/v1/conversations", {"title": "bench " + time.strftime("%H:%M:%S")}) as r:
        cid = json.loads(r.read())["id"]
    started = time.time()
    first_status = first_word = None
    body = {"message": text, "turn_id": "bench" + uuid.uuid4().hex[:16]}
    with call(base, key, "POST", f"/api/v1/conversations/{cid}/turns", body, stream=True) as r:
        for line in r:
            if not line.strip():
                continue
            ev = json.loads(line)
            t = time.time() - started
            if ev.get("type") == "status" and first_status is None:
                first_status = t
            if ev.get("type") == "delta" and first_word is None:
                first_word = t
            if ev.get("type") in ("done", "error"):
                break
    return {"first_status": first_status, "first_word": first_word, "total": time.time() - started}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("--key", default="")
    ap.add_argument("--rounds", type=int, default=3)
    a = ap.parse_args()
    base = a.base.rstrip("/")
    print(f"Benchmarking {base}  ({a.rounds} rounds per scenario)\n")
    print(f"{'scenario':<10} {'first word (median)':>20} {'whole reply (median)':>22}")
    for name, text in SCENARIOS:
        runs = [one(base, a.key, text) for _ in range(a.rounds)]
        fw = [x["first_word"] for x in runs if x["first_word"] is not None]
        tot = [x["total"] for x in runs]
        print(f"{name:<10} {statistics.median(fw) if fw else float('nan'):>19.2f}s {statistics.median(tot):>21.2f}s")
    print("\n(First word is what you feel: the time until Gemma starts answering.)")


if __name__ == "__main__":
    sys.exit(main())
