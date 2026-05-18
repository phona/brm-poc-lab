#!/usr/bin/env bash
# FlowLong PoC verify — 跟 warm-flow PoC 同样的场景，验证关键差异点。
#
# 重点：multi-approver via TaskActorProvider 是否真的生成多行 flw_task_actor
# （Warm-Flow 1.3.8 在 SpEL 路径下不拆分，这是切 FlowLong 的核心动机）。
set -euo pipefail

FL="${FL:-http://localhost:8082}"
MYSQL_EXEC="${MYSQL_EXEC:-docker exec brm-poc-lab-mysql-1 mysql -uroot -ppocroot}"
RED=$'\e[31m'; GRN=$'\e[32m'; YLW=$'\e[33m'; RST=$'\e[0m'
pass=0; fail=0
ok()   { echo "${GRN}✓${RST} $1"; pass=$((pass+1)); }
ng()   { echo "${RED}✗${RST} $1"; fail=$((fail+1)); }
info() { echo "${YLW}»${RST} $1"; }

info "wait for flowlong"
for i in $(seq 1 60); do
  if curl -sf "$FL/health" >/dev/null; then break; fi
  sleep 2
done
curl -sf "$FL/health" >/dev/null || { ng "flowlong not up"; exit 1; }
ok "flowlong healthy"

# Assertion 1: start instance for company 1001
info "Assertion 1: start instance (company 1001, Alice as creator)"
start1=$(curl -s -X POST "$FL/flow/start" \
  -H "Content-Type: application/json" \
  -d '{"userId":"10011","userName":"Alice","companyUuid":1001,"businessId":"FL-1001-A"}')
echo "$start1"
inst1=$(echo "$start1" | grep -oE '"instanceId":"[0-9]+"' | head -1 | grep -oE '[0-9]+')
[ -n "$inst1" ] && ok "instance1 = $inst1" || { ng "start1 failed"; exit 1; }

# Assertion 2: multi-actor rows for the approve task
info "Assertion 2: flw_task_actor 是否一人一行（核心命题）"
actors1=$($MYSQL_EXEC -N -e "USE flowlong; SELECT actor_id FROM flw_task_actor WHERE instance_id=$inst1 ORDER BY actor_id;" 2>&1 | grep -v Warning)
echo "actors1=[$actors1]"
n1=$(echo "$actors1" | grep -c .)
[ "$n1" -ge 2 ] && ok "approve task has $n1 actor rows (expect ≥2)" || ng "approve task only has $n1 rows; FlowLong 也存在合并行的问题？"

echo "$actors1" | grep -qx "10011" && ok "Alice (10011) is one of the actors" || ng "Alice missing"
echo "$actors1" | grep -qx "10012" && ok "Bob/superadmin (10012) is one of the actors" || ng "Bob missing"
echo "$actors1" | grep -qx "10013" && ng "Eve (10013) leaked in" || ok "Eve correctly excluded"

# Assertion 3: cross-tenant
info "Assertion 3: 多租户隔离"
start2=$(curl -s -X POST "$FL/flow/start" \
  -H "Content-Type: application/json" \
  -d '{"userId":"10021","userName":"Carol","companyUuid":1002,"businessId":"FL-1002-B"}')
inst2=$(echo "$start2" | grep -oE '"instanceId":"[0-9]+"' | head -1 | grep -oE '[0-9]+')
[ -n "$inst2" ] && ok "instance2 = $inst2" || { ng "start2 failed"; exit 1; }

actors2=$($MYSQL_EXEC -N -e "USE flowlong; SELECT actor_id FROM flw_task_actor WHERE instance_id=$inst2 ORDER BY actor_id;" 2>&1 | grep -v Warning)
echo "actors2=[$actors2]"
echo "$actors2" | grep -qx "10021" && ok "Carol (10021) in shop1002 handlers" || ng "Carol missing"
echo "$actors2" | grep -qx "10022" && ok "Dave (10022) in shop1002 handlers" || ng "Dave missing"
echo "$actors2" | grep -qx "10011" && ng "shop1001 Alice leaked!" || ok "shop1001 Alice NOT in shop1002 handlers"

# Assertion 4: 会签语义——任意一人 approve 不应结束流程
info "Assertion 4: 会签（examineMode=2）——单人 approve 应继续等待"
task_id1=$($MYSQL_EXEC -N -e "USE flowlong; SELECT id FROM flw_task WHERE instance_id=$inst1 LIMIT 1;" 2>&1 | grep -v Warning | head -1)
[ -n "$task_id1" ] && info "task_id=$task_id1"

ar1=$(curl -s -X POST "$FL/task/skip?taskId=$task_id1&userId=10011&userName=Alice")
echo "$ar1"
state_after1=$($MYSQL_EXEC -N -e "USE flowlong; SELECT instance_state FROM flw_his_instance WHERE id=$inst1;" 2>&1 | grep -v Warning | head -1)
remain1=$($MYSQL_EXEC -N -e "USE flowlong; SELECT COUNT(*) FROM flw_task WHERE instance_id=$inst1;" 2>&1 | grep -v Warning | head -1)
info "after Alice approves: instance_state=[$state_after1] active_tasks=$remain1"
[ "$remain1" -gt 0 ] && ok "会签语义：Alice 一人通过后还有 $remain1 个待办，未结束" || ng "会签 broken：Alice 一人就结束了"

# Assertion 5: 第二人通过后流程结束
info "Assertion 5: 第二人审批，流程结束"
task_id2=$($MYSQL_EXEC -N -e "USE flowlong; SELECT id FROM flw_task WHERE instance_id=$inst1 ORDER BY id LIMIT 1;" 2>&1 | grep -v Warning | head -1)
ar2=$(curl -s -X POST "$FL/task/skip?taskId=$task_id2&userId=10012&userName=Bob")
echo "$ar2"
remain2=$($MYSQL_EXEC -N -e "USE flowlong; SELECT COUNT(*) FROM flw_task WHERE instance_id=$inst1;" 2>&1 | grep -v Warning | head -1)
state2=$($MYSQL_EXEC -N -e "USE flowlong; SELECT instance_state FROM flw_his_instance WHERE id=$inst1;" 2>&1 | grep -v Warning | head -1)
info "after Bob approves: instance_state=$state2 active_tasks=$remain2"
[ "$remain2" -eq 0 ] && ok "会签全员通过，流程结束" || ng "会签：第二人通过后还剩 $remain2 个任务"

echo
echo "===================="
echo "passed: $pass   failed: $fail"
echo "===================="
[ $fail -eq 0 ]
