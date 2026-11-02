#!/usr/bin/env bash
# Checks the real pack server (Firebase) from CI: anonymous sign-in works and the database rules
# let riders do exactly what pack mode needs, and nothing more. Prints PASS / FAIL per check.
set -u
J=app/google-services.json
[ -f "$J" ] || { echo "pack server: no google-services.json, skipped"; exit 0; }
KEY=$(python3 -c "import json;d=json.load(open('$J'));print(d['client'][0]['api_key'][0]['current_key'])")
DB=$(python3 -c "import json;d=json.load(open('$J'));print(d['project_info'].get('firebase_url',''))")
[ -n "$DB" ] || { echo "FAIL no database URL in google-services.json (create the Realtime Database first)"; exit 0; }
# The key may be restricted to the Android app; send the package so such a key still answers.
H=(-H "X-Android-Package: com.rodeo.hud")
signup() { curl -s -m 20 "${H[@]}" -X POST "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$KEY" -H 'Content-Type: application/json' -d '{"returnSecureToken":true}'; }
field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }
A=$(signup); TA=$(echo "$A" | field idToken); UA=$(echo "$A" | field localId)
B=$(signup); TB=$(echo "$B" | field idToken); UB=$(echo "$B" | field localId)
if [ -z "$TA" ] || [ -z "$TB" ]; then echo "FAIL anonymous sign-in: $(echo "$A" | head -c 300)"; exit 0; fi
echo "PASS anonymous sign-in"
FAILS=0
code() { curl -s -m 20 -o /dev/null -w '%{http_code}' "$@"; }
check() { # name expected-ok(1/0) http-code
  local ok=0; [ "$3" = 200 ] && ok=1
  if [ "$ok" = "$2" ]; then echo "PASS $1 ($3)"; else echo "FAIL $1 ($3)"; FAILS=$((FAILS+1)); fi
}
P="CI$(python3 -c "import random;print(''.join(random.choice('ABCDEFGHJKLMNPQRSTUVWXYZ23456789') for _ in range(4)))")"
check "read without sign-in is refused" 0 "$(code "$DB/packs/$P/members.json")"
check "lead creates the pack" 1 "$(code -X PUT "$DB/packs/$P/meta.json?auth=$TA" -d "{\"lead\":\"$UA\",\"created\":{\".sv\":\"timestamp\"}}")"
check "another rider can't take over the pack" 0 "$(code -X PUT "$DB/packs/$P/meta.json?auth=$TB" -d "{\"lead\":\"$UB\"}")"
check "rider writes own position" 1 "$(code -X PATCH "$DB/packs/$P/members/$UA.json?auth=$TA" -d '{"name":"CI rider A","lat":12.97,"lng":77.59,"speed":40,"brg":90,"ts":{".sv":"timestamp"},"still":0,"sos":false,"online":true}')"
check "online flag alone (reconnect) is allowed" 1 "$(code -X PUT "$DB/packs/$P/members/$UB/online.json?auth=$TB" -d 'true')"
check "rider can't write someone else's position" 0 "$(code -X PATCH "$DB/packs/$P/members/$UA.json?auth=$TB" -d '{"lat":0}')"
check "name longer than 24 letters is refused" 0 "$(code -X PATCH "$DB/packs/$P/members/$UB.json?auth=$TB" -d '{"name":"abcdefghijklmnopqrstuvwxyz"}')"
check "other rider reads the pack" 1 "$(code "$DB/packs/$P/members.json?auth=$TB")"
check "bad pack code is refused" 0 "$(code -X PATCH "$DB/packs/bad/members/$UA.json?auth=$TA" -d '{"lat":1,"lng":1}')"
check "writing the database root is refused" 0 "$(code -X PATCH "$DB/.json?auth=$TA" -d '{"x":1}')"
check "joining voice" 1 "$(code -X PUT "$DB/packs/$P/voice/$UA.json?auth=$TA" -d 'true')"
check "voice message to another rider" 1 "$(code -X POST "$DB/packs/$P/signal/$UB.json?auth=$TA" -d "{\"from\":\"$UA\",\"type\":\"ice\",\"sid\":\"x\"}")"
check "voice message pretending to be someone else is refused" 0 "$(code -X POST "$DB/packs/$P/signal/$UB.json?auth=$TA" -d "{\"from\":\"$UB\",\"type\":\"ice\"}")"
check "reading another rider's voice messages is refused" 0 "$(code "$DB/packs/$P/signal/$UB.json?auth=$TA")"
check "rider reads and clears own voice messages" 1 "$(code -X DELETE "$DB/packs/$P/signal/$UB.json?auth=$TB")"
check "pothole shared" 1 "$(code -X PUT "$DB/hazards/1_1/ci-$P.json?auth=$TA" -d '{"lat":0.02,"lng":0.02,"hits":2}')"
# Leave no trace: riders leave, the pack and test pothole go, the test accounts are deleted.
code -X DELETE "$DB/packs/$P/members/$UA.json?auth=$TA" >/dev/null
code -X DELETE "$DB/packs/$P/members/$UB.json?auth=$TB" >/dev/null
code -X DELETE "$DB/packs/$P/voice/$UA.json?auth=$TA" >/dev/null
code -X DELETE "$DB/hazards/1_1/ci-$P.json?auth=$TA" >/dev/null
code -X DELETE "$DB/packs/$P/meta.json?auth=$TA" >/dev/null
for T in "$TA" "$TB"; do curl -s -m 20 "${H[@]}" -X POST "https://identitytoolkit.googleapis.com/v1/accounts:delete?key=$KEY" -H 'Content-Type: application/json' -d "{\"idToken\":\"$T\"}" >/dev/null; done
echo "pack server: $FAILS failed"
