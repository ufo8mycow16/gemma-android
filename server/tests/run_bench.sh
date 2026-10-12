#!/bin/bash
# Run an app.py against the stand-in model and benchmark it.
#   tests/run_bench.sh <path to app.py> <label> [rounds]
# Prints the benchmark and, per scenario, the model calls the turn caused.
set -u
APP="$1"; LABEL="$2"; ROUNDS="${3:-2}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LIVE="/tmp/gemma-bench-$LABEL"; rm -rf "$LIVE"; mkdir -p "$LIVE"
cp "$APP" "$LIVE/app.py"; [ -d "$(dirname "$APP")/gemma_core" ] && cp -r "$(dirname "$APP")/gemma_core" "$LIVE/"; [ -d "$(dirname "$APP")/web" ] && cp -r "$(dirname "$APP")/web" "$LIVE/"; [ -d "$(dirname "$APP")/skills" ] && cp -r "$(dirname "$APP")/skills" "$LIVE/"
for p in /tmp/gemma-bench-*.pids; do [ -f "$p" ] && kill $(cat "$p") 2>/dev/null; rm -f "$p"; done; sleep 0.5
FAKE_SCALE=${FAKE_SCALE:-1.0} FAKE_LOG="$LIVE/fake.jsonl" python3 "$HERE/tests/fake_ollama.py" > "$LIVE/fake.out" 2>&1 & F=$!
sleep 0.7
(cd "$LIVE" && GEMMA_PORT=7871 GEMMA_TOKEN=bench-key-0123456789abcdef GEMMA_GEOCODER_URL=http://127.0.0.1:8899/reverse GEMMA_TTS_URL=http://127.0.0.1:9 exec python3 -u app.py > boot.log 2>&1) & A=$!
echo "$F $A" > /tmp/gemma-bench-$LABEL.pids
for i in $(seq 1 40); do curl -s -o /dev/null http://127.0.0.1:7871/api/v1/health && break; sleep 0.25; done
python3 "$HERE/tools/bench.py" http://127.0.0.1:7871 --key bench-key-0123456789abcdef --rounds "$ROUNDS"
echo; echo "Model calls per scenario (from the stand-in's log):"
python3 - "$LIVE/fake.jsonl" <<'PY'
import json, sys, collections
rows = [json.loads(l) for l in open(sys.argv[1])]
groups = collections.OrderedDict()
for r in rows:
    key = next((k for k in ("Morning Gemma", "nice walk", "list and a tuple") if k in r["user_tail"]), "other")
    groups.setdefault(key, []).append(r)
for k, rs in groups.items():
    by = collections.Counter(r["purpose"] for r in rs)
    print(f"  {k:<16} calls={len(rs):<3} reloads={sum(r['reload'] for r in rs):<3} ctx={sorted(set(r['num_ctx'] for r in rs))} "
          f"chat_prompt_chars={max([r['prompt_chars'] for r in rs if r['purpose']=='chat'] or [0])} purposes={dict(by)}")
PY
kill $A $F 2>/dev/null; wait 2>/dev/null
