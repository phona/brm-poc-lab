#!/usr/bin/env bash
# Verify the ttpos integration data path.
#
# Three assertions:
#   1. JWT issued by gateway (ttpos-compatible HS256) is accepted on /api/v1/ttpos/*.
#   2. Dynamic approver resolution: starting a flow with WEBHOOK_RESOLVE:ACCESS:transfer_order_approve
#      causes the gateway to query shop{companyUuid}.ttpos_* and the resolved staff_uuids
#      are written back to the task's permissionList.
#   3. Multi-tenant isolation: companyUuid=1001 resolves to shop1001 staff; 1002 to shop1002.
#
# Requires: docker compose up to be running (mysql + poc-gateway + warm-flow).
set -euo pipefail

GW="${GW:-http://localhost:8080}"
RED=$'\e[31m'; GRN=$'\e[32m'; YLW=$'\e[33m'; RST=$'\e[0m'
pass=0; fail=0

ok()   { echo "${GRN}✓${RST} $1"; pass=$((pass+1)); }
ng()   { echo "${RED}✗${RST} $1"; fail=$((fail+1)); }
info() { echo "${YLW}»${RST} $1"; }

require() {
  command -v "$1" >/dev/null || { echo "missing $1"; exit 2; }
}
require curl
require jq

# Java long IDs (snowflake ~2e18) exceed JS MAX_SAFE_INTEGER, so jq loses precision.
# Extract them as raw digit strings straight from the JSON text instead of via jq.
extract_id() {
  local field="$1" body="$2"
  echo "$body" | grep -oE "\"$field\"[[:space:]]*:[[:space:]]*[0-9]+" | head -1 | grep -oE '[0-9]+$'
}

info "wait for gateway"
for i in $(seq 1 30); do
  if curl -sf "$GW/health" >/dev/null; then break; fi
  sleep 1
done
curl -sf "$GW/health" >/dev/null || { ng "gateway not up"; exit 1; }
ok "gateway healthy"

# ------- Token generation (dev helper) -------
gen_token() {
  local company="$1" staff="$2"
  curl -s -X POST "$GW/dev/gen-token" \
    -H "Content-Type: application/json" \
    -d "{\"companyUuid\":$company,\"staffUuid\":$staff,\"source\":\"shop\"}" \
    | jq -r '.data.token'
}

TOKEN_ALICE=$(gen_token 1001 10011)   # Alice 持有 transfer_order_approve
TOKEN_BOB=$(gen_token   1001 10012)   # Bob is_super=1
TOKEN_EVE=$(gen_token   1001 10013)   # Eve 无审批权限
TOKEN_CAROL=$(gen_token 1002 10021)   # Carol shop1002 区域经理
TOKEN_DAVE=$(gen_token  1002 10022)   # Dave shop1002 超管

[ -n "$TOKEN_ALICE" ] && [ "$TOKEN_ALICE" != "null" ] && ok "minted token for Alice" || ng "token mint failed"

# ------- Assertion 1: JWT auth works (rejects bad token, accepts good) -------
info "Assertion 1: JWT auth"

code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GW/api/v1/ttpos/flow/start" \
  -H "Content-Type: application/json" \
  -d '{"businessId":"X","flowCode":"ttpos_transfer_test"}')
[ "$code" = "401" ] && ok "no-token → 401" || ng "no-token expected 401, got $code"

code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GW/api/v1/ttpos/flow/start" \
  -H "Authorization: Bearer not-a-jwt" \
  -H "Content-Type: application/json" \
  -d '{"businessId":"X","flowCode":"ttpos_transfer_test"}')
[ "$code" = "401" ] && ok "bad-token → 401" || ng "bad-token expected 401, got $code"

# ------- Assertion 2: Start flow with Alice's JWT, verify dynamic approver resolution -------
info "Assertion 2: dynamic approver resolution via ttpos RBAC"

BIZ_ID="TTPOS-$(date +%s)-A"
start_resp=$(curl -s -X POST "$GW/api/v1/ttpos/flow/start" \
  -H "Authorization: Bearer $TOKEN_ALICE" \
  -H "Content-Type: application/json" \
  -d "{\"businessId\":\"$BIZ_ID\",\"flowCode\":\"ttpos_transfer_test\"}")
echo "$start_resp" | jq .

inst_id=$(extract_id instanceId "$start_resp")
if [ -z "$inst_id" ]; then
  ng "flow.start failed: $start_resp"
  exit 1
fi
ok "started instance $inst_id for biz $BIZ_ID (company 1001)"

# The approve node is dynamic. Eligible handlers from shop1001:
#   - Alice (10011) — holds transfer_order_approve via role
#   - Bob   (10012) — is_super=1
# Eve (10013) should NOT be in the permissionList.

# Fetch tasks of this instance and inspect the approve task.
tasks_resp=$(curl -sf "$GW/internal/poc/warm-flow/instance/tasks?instanceId=$inst_id" \
  -H "X-API-KEY: dev-poc-key")
echo "$tasks_resp"
# Pull the taskId associated with nodeCode ttpos_approve via grep block-by-block (precision-safe).
task_id=$(echo "$tasks_resp" | tr '}' '\n' | grep '"nodeCode":"ttpos_approve"' | grep -oE '"taskId":[0-9]+' | head -1 | grep -oE '[0-9]+')
if [ -z "$task_id" ]; then
  ng "no ttpos_approve task found"
  exit 1
fi
ok "ttpos_approve task = $task_id"

# Read its permissionList via /api/task/detail. handlers[] are uint64 strings (gateway converts).
detail=$(curl -sf "$GW/internal/poc/warm-flow/task/detail?taskId=$task_id" \
  -H "X-API-KEY: dev-poc-key")
echo "$detail" | jq .
handlers=$(echo "$detail" | jq -r '.data.handlers[]?' | tr '\n' ',' | sed 's/,$//')
info "resolved handlers: [$handlers]"

contains() { echo ",$1," | grep -q ",$2,"; }
contains "$handlers" "10011" && ok "Alice (10011) in handlers"          || ng "Alice missing from handlers"
contains "$handlers" "10012" && ok "Bob/superadmin (10012) in handlers"  || ng "Bob missing from handlers"
contains "$handlers" "10013" && ng "Eve (10013) leaked into handlers"   || ok "Eve correctly excluded"

# ------- Assertion 3: Engine enforces handler binding (Eve cannot approve, Alice can) -------
info "Assertion 3: engine enforces resolved permissionList"

eve_resp=$(curl -s -X POST "$GW/api/v1/ttpos/flow/approve" \
  -H "Authorization: Bearer $TOKEN_EVE" \
  -H "Content-Type: application/json" \
  -d "{\"taskId\":$task_id,\"message\":\"trying as Eve\"}")
# Engine returns non-zero code/error on permission mismatch.
if echo "$eve_resp" | jq -e '.code != 0' >/dev/null; then
  ok "Eve approve rejected (engine refused unauthorized handler)"
else
  ng "Eve approve unexpectedly succeeded: $eve_resp"
fi

alice_resp=$(curl -s -X POST "$GW/api/v1/ttpos/flow/approve" \
  -H "Authorization: Bearer $TOKEN_ALICE" \
  -H "Content-Type: application/json" \
  -d "{\"taskId\":$task_id,\"message\":\"approving as Alice\"}")
echo "$alice_resp" | jq .
if echo "$alice_resp" | jq -e '.data.flowStatus != null' >/dev/null; then
  ok "Alice approve succeeded"
else
  ng "Alice approve failed: $alice_resp"
fi

# ------- Assertion 4: Multi-tenant isolation -------
info "Assertion 4: multi-tenant isolation (shop1002)"

BIZ_ID2="TTPOS-$(date +%s)-B"
start2=$(curl -s -X POST "$GW/api/v1/ttpos/flow/start" \
  -H "Authorization: Bearer $TOKEN_CAROL" \
  -H "Content-Type: application/json" \
  -d "{\"businessId\":\"$BIZ_ID2\",\"flowCode\":\"ttpos_transfer_test\"}")
inst2=$(extract_id instanceId "$start2")
[ -n "$inst2" ] && ok "started instance $inst2 for company 1002" || { ng "start2 failed: $start2"; exit 1; }

tasks_resp2=$(curl -sf "$GW/internal/poc/warm-flow/instance/tasks?instanceId=$inst2" \
  -H "X-API-KEY: dev-poc-key")
task_id2=$(echo "$tasks_resp2" | tr '}' '\n' | grep '"nodeCode":"ttpos_approve"' | grep -oE '"taskId":[0-9]+' | head -1 | grep -oE '[0-9]+')
detail2=$(curl -sf "$GW/internal/poc/warm-flow/task/detail?taskId=$task_id2" \
  -H "X-API-KEY: dev-poc-key")
handlers2=$(echo "$detail2" | jq -r '.data.handlers[]?' | tr '\n' ',' | sed 's/,$//')
info "company 1002 handlers: [$handlers2]"

contains "$handlers2" "10021" && ok "Carol (10021, shop1002) in handlers" || ng "Carol missing"
contains "$handlers2" "10022" && ok "Dave (10022, shop1002 super) in handlers" || ng "Dave missing"
contains "$handlers2" "10011" && ng "shop1001 Alice leaked into shop1002!" || ok "shop1001 Alice NOT in shop1002 handlers"
contains "$handlers2" "10012" && ng "shop1001 Bob leaked into shop1002!"  || ok "shop1001 Bob NOT in shop1002 handlers"

echo
echo "===================="
echo "passed: $pass   failed: $fail"
echo "===================="
[ $fail -eq 0 ]
