#!/usr/bin/env bash
# 技术选型用：①–⑩ 全覆盖可执行验证（PASS / PARTIAL / GAP）。
# - PASS：本脚本内有断言且当前环境通过。
# - PARTIAL：仅 API/引擎能力部分满足，业务/ UI 仍需自建（脚本通过）。
# - GAP：本 PoC 未实现或探测失败（不判整脚本失败，只在报告中标出）。
#
# 用法：
#   ./scripts/poc-tech-selection-verify.sh           # 完整报告 + 全部场景
#   ./scripts/poc-tech-selection-verify.sh --quick   # 仅核心回归（原 A+B）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

QUICK=false
for a in "$@"; do
  [[ "$a" == "--quick" ]] && QUICK=true
done

API_KEY="${INTERNAL_API_KEY:-dev-poc-key}"
G="http://127.0.0.1:8080/internal/poc/warm-flow"
WF="http://127.0.0.1:8081"

need_cmd() {
  command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }
}
need_cmd docker
need_cmd curl
need_cmd python3

mysql_exec() {
  docker exec brm-poc-lab-mysql-1 mysql -uroot -ppocroot "$@"
}

ensure_cc_table() {
  mysql_exec ttpos_poc_biz -e "
CREATE TABLE IF NOT EXISTS poc_cc_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  business_id VARCHAR(64) NOT NULL,
  node_code VARCHAR(64) NOT NULL,
  timing VARCHAR(64) NOT NULL,
  cc_user_ids TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"
}

reset_all() {
  local order_no="$1"
  ensure_cc_table
  mysql_exec -e "
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM ttpos_poc_flow.flow_user;
DELETE FROM ttpos_poc_flow.flow_his_task;
DELETE FROM ttpos_poc_flow.flow_task;
DELETE FROM ttpos_poc_flow.flow_instance;
DELETE FROM ttpos_poc_flow.flow_skip;
DELETE FROM ttpos_poc_flow.flow_node;
DELETE FROM ttpos_poc_flow.flow_definition;
SET FOREIGN_KEY_CHECKS=1;
"
  mysql_exec ttpos_poc_biz -e "
DELETE FROM poc_cc_event WHERE business_id='${order_no}';
UPDATE poc_mock_transfer_order SET status='draft' WHERE order_no='${order_no}';
INSERT INTO poc_mock_transfer_order (order_no, from_shop, to_shop, amount, status) VALUES
('${order_no}', 'Shop A', 'Shop B',
  CASE '${order_no}' WHEN 'TB-1002' THEN 1200 WHEN 'TB-1001' THEN 5000 WHEN 'TB-1003' THEN 900 WHEN 'TB-1004' THEN 700 ELSE 5000 END, 'draft')
ON DUPLICATE KEY UPDATE from_shop = VALUES(from_shop), to_shop = VALUES(to_shop), amount = VALUES(amount);
"
  docker restart brm-poc-lab-warm-flow-1 >/dev/null
  echo "waiting warm-flow..."
  sleep 12
}

todo_count() {
  local user="$1"
  curl -sS "${WF}/api/task/todo?handler=${user}" | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('list')or[]))"
}

cc_count() {
  local biz="$1"
  curl -sS -H "X-API-KEY: $API_KEY" "${G}/cc-record?businessId=${biz}" | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('data')or[]))"
}

gw_code() {
  python3 -c "import json,sys; print(json.load(sys.stdin).get('code',-999))" <<<"$1"
}

report_row() {
  printf '%-5s %-14s %s\n' "$1" "$2" "$3"
}

echo "========== Warm-Flow + Go 网关｜技术选型 PoC 验证 =========="
echo "说明：GAP 表示本仓库未覆盖或本流程未配置，不单独导致退出码 1；核心回归失败会退出 1。"
echo

if $QUICK; then
  echo "(quick) 仅运行核心回归 A + B"
fi

# ---------- 核心回归 A + B（失败则 exit 1）----------
echo "=== A: 串行两节点 + 固定权签 + Webhook 收货审批人 + 抄送（TB-1002）==="
reset_all "TB-1002"
curl -sS -X POST "$G/transfer/submit-start" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d '{"orderNo":"TB-1002","flowCode":"transfer_test","createBy":"user_02","ccUserIds":["cc_user_a","cc_user_b"]}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"

C1="$(todo_count user_01)"
test "$C1" = "1" || { echo "fail A: expected 1 todo for user_01 got $C1"; exit 1; }

T1="$(curl -sS "${WF}/api/task/todo?handler=user_01" | python3 -c "import json,sys; print(json.load(sys.stdin)['list'][0]['taskId'])")"
curl -sS -X POST "$G/task/skip" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d "{\"taskId\":$T1,\"handler\":\"user_01\",\"message\":\"ok\"}" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"

CC1="$(cc_count TB-1002)"
test "$CC1" -ge 1 || { echo "fail A: cc events >= 1 got $CC1"; exit 1; }

T2="$(curl -sS "${WF}/api/task/todo?handler=user_03" | python3 -c "import json,sys; print(json.load(sys.stdin)['list'][0]['taskId'])")"
curl -sS -X POST "$G/task/skip" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d "{\"taskId\":$T2,\"handler\":\"user_03\",\"message\":\"ok\"}" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"

ST="$(mysql_exec ttpos_poc_biz -N -e "SELECT status FROM poc_mock_transfer_order WHERE order_no='TB-1002'")"
test "$ST" = "approved" || { echo "fail A: order status $ST"; exit 1; }
echo "PASS A"

echo "=== B: 提交人=发货审批人自动跳过 + 抄送（TB-1001）==="
reset_all "TB-1001"
curl -sS -X POST "$G/transfer/submit-start" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d '{"orderNo":"TB-1001","flowCode":"transfer_test","createBy":"user_01","ccUserIds":["observer_1"]}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"

Cship="$(todo_count user_01)"
test "$Cship" = "0" || { echo "fail B: expected user_01 ship todo=0 got $Cship"; exit 1; }

Crecv="$(todo_count user_03)"
test "$Crecv" = "1" || { echo "fail B: expected user_03 todo=1 got $Crecv"; exit 1; }

CC2="$(cc_count TB-1001)"
test "$CC2" -ge 1 || { echo "fail B: cc events TB-1001"; exit 1; }

T3="$(curl -sS "${WF}/api/task/todo?handler=user_03" | python3 -c "import json,sys; print(json.load(sys.stdin)['list'][0]['taskId'])")"
curl -sS -X POST "$G/task/skip" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d "{\"taskId\":$T3,\"handler\":\"user_03\",\"message\":\"ok\"}" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"

ST2="$(mysql_exec ttpos_poc_biz -N -e "SELECT status FROM poc_mock_transfer_order WHERE order_no='TB-1001'")"
test "$ST2" = "approved" || { echo "fail B: order status $ST2"; exit 1; }
echo "PASS B"

if $QUICK; then
  echo "=== --quick 完成，跳过扩展项 ==="
  exit 0
fi

echo
echo "=== 扩展项：①–⑩ 逐项探测（在核心已通过前提下）==="

R01="PARTIAL"; R01d="Go BFF 聚合待办 API 有订单字段；无独立审批中心 UI"
R02="PARTIAL"; R02d="flow_code 绑定有；模块/范围/租户元数据需业务库自建"
R03="PASS";    R03d="启动变量 + createBy 写入实例 variable（引擎）"
R04="PARTIAL"; R04d="无可视化设计器；flow_node 行数可证建模（DB）"
R05="PARTIAL"; R05d="固定权签 + Webhook 解析审批人；会签/复杂规则未测全"
R06="PASS";    R06d="termination(REJECT)+网关 rejected（非 skip REJECT 退回上一节点）"
R07="PARTIAL"; R07d="resolve-approver 内部 API 模拟选人/组织解析"
R08="PASS";    R08d="recv_mgr 权限后 cc-record 落库"
R09="PARTIAL"; R09d="撤销：termination(CANCEL)+cancelled；催办/超时仍未建模"
R10="PASS";    R10d="create 监听器自动 skip ship（场景B）"

# ① 待办聚合
echo "--- ① 待办聚合（BFF）---"
reset_all "TB-1001"
curl -sS -X POST "$G/transfer/submit-start" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d '{"orderNo":"TB-1001","flowCode":"transfer_test","createBy":"user_02","ccUserIds":[]}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"
AGG_JSON="$(curl -sS -H "X-API-KEY: $API_KEY" "${G}/tasks/todo?userId=user_01")"
python3 -c "
import json,sys
j=json.load(sys.stdin)
assert j.get('code')==0, j
d=j.get('data') or []
assert len(d)>=1, d
x=d[0]
assert x.get('orderAmount')==5000, x
assert x.get('nodeCode')=='ship_approve', x
assert x.get('orderNo')=='TB-1001', x
" <<<"$AGG_JSON"
echo "PASS ①（PARTIAL：仅 API 层聚合）"

# ② 流程定义存在
echo "--- ② 流程编码 / 模块类比（DB）---"
DC="$(mysql_exec ttpos_poc_flow -N -e "SELECT COUNT(*) FROM flow_definition WHERE flow_code='transfer_test' AND del_flag='0'")"
test "$DC" -ge 1 || { echo "fail: no transfer_test definition"; exit 1; }
echo "PARTIAL ②: 定义已发布（业务「模块/范围」元数据仍自建）"

# ③ 变量
echo "--- ③ 提交人 + 自定义流程变量 ---"
reset_all "TB-1001"
curl -sS -X POST "$G/transfer/submit-start" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d '{"orderNo":"TB-1001","flowCode":"transfer_test","createBy":"user_02","variables":{"pocTrace":"sel-trace-3"},"ccUserIds":[]}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"
FV="$(mysql_exec ttpos_poc_flow -N -e "SELECT variable FROM flow_instance WHERE business_id='TB-1001' ORDER BY id DESC LIMIT 1")"
python3 -c "
import json,sys
s=sys.argv[1]
m=json.loads(s) if s else {}
assert m.get('createBy')=='user_02', m
assert m.get('pocTrace')=='sel-trace-3', m
" "$FV"
echo "PASS ③"

# ④ 设计时能力：仅用 DB 证明已建模节点（非 UI 设计器）
echo "--- ④ 流程建模证明（flow_node / 无 UI 设计器）---"
NC="$(mysql_exec ttpos_poc_flow -N -e "SELECT COUNT(*) FROM flow_node n INNER JOIN flow_definition d ON n.definition_id = d.id WHERE d.flow_code = 'transfer_test' AND n.del_flag = '0' AND d.del_flag = '0'")"
test "${NC:-0}" -ge 4 || { echo "fail ④: expected >=4 flow_nodes for transfer_test got $NC"; exit 1; }
echo "PARTIAL ④：节点已建模 count=${NC} （无拖拽设计器）"

# ⑦ resolve（⑤ 动态审批人一部分）
echo "--- ⑦ 选人 / 解析（resolve-approver）---"
RES="$(curl -sS -X POST "$G/resolve-approver" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d '{"businessId":"TB-1001","roleCode":"RECEIVER_MANAGER","variables":{}}')"
python3 -c "
import json,sys
j=json.load(sys.stdin)
assert j.get('code')==0, j
uids=(j.get('data') or {}).get('userIds') or []
assert uids==['user_03'], uids
" <<<"$RES"
echo "PASS ⑦（模拟组织/规则选人）"

# ⑥ 驳回终止（termination，Warm-Flow 的 skip REJECT 要求已有历史节点不可用于首审批点）
echo "--- ⑥ 驳回终止 /api/task/terminate（TB-1003）---"
reset_all "TB-1003"
curl -sS -X POST "$G/transfer/submit-start" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d '{"orderNo":"TB-1003","flowCode":"transfer_test","createBy":"user_02","ccUserIds":[]}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"
TR="$(curl -sS "${WF}/api/task/todo?handler=user_01" | python3 -c "import json,sys; print(json.load(sys.stdin)['list'][0]['taskId'])")"
REJ_BODY="$(curl -sS -X POST "$G/task/terminate" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d "{\"taskId\":$TR,\"handler\":\"user_01\",\"message\":\"probe reject\"}")"
RJ="$(gw_code "$REJ_BODY")"
if [[ "$RJ" != "0" ]]; then
  echo "fail ⑥: expected gateway code=0, got $RJ body=$REJ_BODY"
  exit 1
fi
TODO_AFTER="$(todo_count user_01)"
test "$TODO_AFTER" = "0" || { echo "fail ⑥: expected user_01 todo=0 after reject got $TODO_AFTER"; exit 1; }
RST="$(mysql_exec ttpos_poc_biz -N -e "SELECT status FROM poc_mock_transfer_order WHERE order_no='TB-1003'")"
test "$RST" = "rejected" || { echo "fail ⑥: order status expected rejected got $RST"; exit 1; }
echo "PASS ⑥（驳回建模 + 业务状态）"

echo "--- ⑧ 抄送内容校验（TB-1002，场景 A 已写入）---"
CC_LAST="$(curl -sS -H "X-API-KEY: $API_KEY" "${G}/cc-record?businessId=TB-1002")"
python3 -c "
import json,sys
j=json.load(sys.stdin)
rows=j.get('data') or []
assert rows, rows
row=rows[0]
raw=row.get('cc_user_ids') or row.get('CcUserIDs') or row.get('ccUserIds') or '[]'
if isinstance(raw, list):
    a=raw
else:
    a=json.loads(raw)
assert 'cc_user_a' in a, (row, a)
" <<<"$CC_LAST"
echo "PASS ⑧"

# ⑨ 撤销（termination CANCEL）
echo "--- ⑨ 撤销 /api/task/cancel（TB-1004）---"
reset_all "TB-1004"
curl -sS -X POST "$G/transfer/submit-start" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d '{"orderNo":"TB-1004","flowCode":"transfer_test","createBy":"user_02","ccUserIds":[]}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code')==0, d"
TC="$(curl -sS "${WF}/api/task/todo?handler=user_01" | python3 -c "import json,sys; print(json.load(sys.stdin)['list'][0]['taskId'])")"
CAN="$(curl -sS -X POST "$G/task/cancel" -H "Content-Type: application/json" -H "X-API-KEY: $API_KEY" \
  -d "{\"taskId\":$TC,\"handler\":\"user_01\",\"message\":\"发起撤销\"}")"
CJ="$(gw_code "$CAN")"
test "$CJ" = "0" || { echo "fail ⑨: cancel gateway code=$CJ body=$CAN"; exit 1; }
test "$(todo_count user_01)" = "0" || { echo "fail ⑨: todo after cancel"; exit 1; }
CST="$(mysql_exec ttpos_poc_biz -N -e "SELECT status FROM poc_mock_transfer_order WHERE order_no='TB-1004'")"
test "$CST" = "cancelled" || { echo "fail ⑨: order status expected cancelled got $CST"; exit 1; }
echo "PASS ⑨（PARTIAL：撤销已验；催办/超时未建模）"

echo
echo "================== 技术选型摘要（①–⑩）=================="
report_row "①" "$R01" "$R01d"
report_row "②" "$R02" "$R02d"
report_row "③" "$R03" "$R03d"
report_row "④" "$R04" "$R04d"
report_row "⑤" "$R05" "$R05d"
report_row "⑥" "$R06" "$R06d"
report_row "⑦" "$R07" "$R07d"
report_row "⑧" "$R08" "$R08d"
report_row "⑨" "$R09" "$R09d"
report_row "⑩" "$R10" "$R10d"
echo
echo "核心回归 A+B：已通过。"
echo "说明：PASS=本脚本已验证；PARTIAL=能力部分具备需业务补全；GAP=PoC 未覆盖或本流程未建模。"
echo "若多条为 GAP 且为选型硬性要求，需评估更重审批平台或加深流程建模（驳回边、会签、超时等）。"
