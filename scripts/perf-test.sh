#!/bin/bash
# Warm-Flow 1.3.8 性能与稳定性测试脚本

BASE_URL="http://localhost:8081"
RESULT_DIR="/tmp/warm-flow-perf"
mkdir -p $RESULT_DIR

echo "=========================================="
echo "Warm-Flow 1.3.8 性能与稳定性测试"
echo "=========================================="
echo ""

# 预热
http_code=$(curl -s -o /dev/null -w "%{http_code}" $BASE_URL/actuator/health 2>/dev/null || echo "000")
if [ "$http_code" != "200" ]; then
    echo "引擎健康检查: 跳过 (无 actuator)"
fi

# ===== 测试 1: 单线程串行基准 =====
echo "[测试 1] 单线程串行启动流程 × 50"
START_TIME=$(date +%s%N)
for i in $(seq 1 50); do
    curl -s -X POST $BASE_URL/api/flow/start \
        -H "Content-Type: application/json" \
        -d "{\"businessId\":\"PERF-START-$i\",\"flowCode\":\"transfer_test\",\"handler\":\"admin\",\"variable\":{}}" > /dev/null
done
END_TIME=$(date +%s%N)
DURATION_MS=$(( (END_TIME - START_TIME) / 1000000 ))
AVG_MS=$(( DURATION_MS / 50 ))
echo "  总耗时: ${DURATION_MS}ms | 平均: ${AVG_MS}ms/次 | TPS: $(echo "scale=1; 50000 / $DURATION_MS" | bc)"
echo ""

# ===== 测试 2: 单线程串行审批 × 50 =====
echo "[测试 2] 单线程串行审批任务 × 50"
# 先启动一个流程
TASK_RESP=$(curl -s -X POST $BASE_URL/api/flow/start \
    -H "Content-Type: application/json" \
    -d '{"businessId":"PERF-TASK-BASE","flowCode":"transfer_test","handler":"admin","variable":{}}')
INS_ID=$(echo $TASK_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['instanceId'])" 2>/dev/null)
sleep 0.5
TASK_ID=$(curl -s "$BASE_URL/api/instance/tasks?instanceId=$INS_ID" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['list'][0]['taskId'] if d.get('list') else '')" 2>/dev/null)

if [ -n "$TASK_ID" ]; then
    START_TIME=$(date +%s%N)
    for i in $(seq 1 50); do
        # 每次审批后流程结束，需要重新启动
        NEW_RESP=$(curl -s -X POST $BASE_URL/api/flow/start \
            -H "Content-Type: application/json" \
            -d "{\"businessId\":\"PERF-SKIP-$i\",\"flowCode\":\"transfer_test\",\"handler\":\"admin\",\"variable\":{}}")
        NEW_INS=$(echo $NEW_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['instanceId'])" 2>/dev/null)
        sleep 0.1
        NEW_TASK=$(curl -s "$BASE_URL/api/instance/tasks?instanceId=$NEW_INS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['list'][0]['taskId'] if d.get('list') else '')" 2>/dev/null)
        if [ -n "$NEW_TASK" ]; then
            curl -s -X POST $BASE_URL/api/task/skip \
                -H "Content-Type: application/json" \
                -d "{\"taskId\":$NEW_TASK,\"handler\":\"user_01\",\"message\":\"perf\",\"skipType\":\"PASS\"}" > /dev/null
        fi
    done
    END_TIME=$(date +%s%N)
    DURATION_MS=$(( (END_TIME - START_TIME) / 1000000 ))
    AVG_MS=$(( DURATION_MS / 50 ))
    echo "  总耗时: ${DURATION_MS}ms | 平均: ${AVG_MS}ms/次 (含启动+审批)"
else
    echo "  跳过: 无法获取任务ID"
fi
echo ""

# ===== 测试 3: 并发启动流程 =====
echo "[测试 3] 并发启动流程 (50并发 × 10轮 = 500次)"
START_TIME=$(date +%s%N)
for round in $(seq 1 10); do
    for i in $(seq 1 50); do
        idx=$(( (round - 1) * 50 + i ))
        curl -s -X POST $BASE_URL/api/flow/start \
            -H "Content-Type: application/json" \
            -d "{\"businessId\":\"PERF-CON-$idx\",\"flowCode\":\"transfer_test\",\"handler\":\"admin\",\"variable\":{}}" > /dev/null &
    done
    wait
done
END_TIME=$(date +%s%N)
DURATION_MS=$(( (END_TIME - START_TIME) / 1000000 ))
AVG_MS=$(( DURATION_MS / 500 ))
echo "  总耗时: ${DURATION_MS}ms | 平均: ${AVG_MS}ms/次 | TPS: $(echo "scale=1; 500000 / $DURATION_MS" | bc)"
echo ""

# ===== 测试 4: 会签并发审批 (3人同时审批同一任务) =====
echo "[测试 4] 会签节点并发审批 (3人同时skip同一task) — 锁竞争测试"
CS_RESP=$(curl -s -X POST $BASE_URL/api/flow/start \
    -H "Content-Type: application/json" \
    -d '{"businessId":"PERF-CS-LOCK","flowCode":"transfer_countersign_test","handler":"admin","variable":{}}')
CS_INS=$(echo $CS_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['instanceId'])" 2>/dev/null)
# admin 先过 submit 节点
sleep 0.5
CS_TASK1=$(curl -s "$BASE_URL/api/instance/tasks?instanceId=$CS_INS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['list'][0]['taskId'] if d.get('list') else '')" 2>/dev/null)
if [ -n "$CS_TASK1" ]; then
    curl -s -X POST $BASE_URL/api/task/skip \
        -H "Content-Type: application/json" \
        -d "{\"taskId\":$CS_TASK1,\"handler\":\"admin\",\"message\":\"submit\",\"skipType\":\"PASS\"}" > /dev/null
    sleep 0.5
    CS_TASK2=$(curl -s "$BASE_URL/api/instance/tasks?instanceId=$CS_INS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['list'][0]['taskId'] if d.get('list') else '')" 2>/dev/null)
    if [ -n "$CS_TASK2" ]; then
        echo "  会签任务ID: $CS_TASK2"
        START_TIME=$(date +%s%N)
        curl -s -X POST $BASE_URL/api/task/skip \
            -H "Content-Type: application/json" \
            -d "{\"taskId\":$CS_TASK2,\"handler\":\"user_a\",\"message\":\"a\",\"skipType\":\"PASS\"}" > $RESULT_DIR/cs_a.json &
        curl -s -X POST $BASE_URL/api/task/skip \
            -H "Content-Type: application/json" \
            -d "{\"taskId\":$CS_TASK2,\"handler\":\"user_b\",\"message\":\"b\",\"skipType\":\"PASS\"}" > $RESULT_DIR/cs_b.json &
        curl -s -X POST $BASE_URL/api/task/skip \
            -H "Content-Type: application/json" \
            -d "{\"taskId\":$CS_TASK2,\"handler\":\"user_c\",\"message\":\"c\",\"skipType\":\"PASS\"}" > $RESULT_DIR/cs_c.json &
        wait
        END_TIME=$(date +%s%N)
        DURATION_MS=$(( (END_TIME - START_TIME) / 1000000 ))
        echo "  3人并发审批耗时: ${DURATION_MS}ms"
        echo "  user_a 结果: $(cat $RESULT_DIR/cs_a.json)"
        echo "  user_b 结果: $(cat $RESULT_DIR/cs_b.json)"
        echo "  user_c 结果: $(cat $RESULT_DIR/cs_c.json)"
        # 检查是否有 500 错误
        if grep -q "error" $RESULT_DIR/cs_*.json 2>/dev/null; then
            echo "  ⚠️  发现错误响应！"
        else
            echo "  ✅ 全部成功（无并发异常）"
        fi
        # 检查最终状态
        sleep 0.5
        CS_STATUS=$(curl -s "$BASE_URL/api/instance/tasks?instanceId=$CS_INS" | python3 -c "import sys,json; d=json.load(sys.stdin); print('done' if not d.get('list') else 'pending')" 2>/dev/null)
        echo "  最终状态: $CS_STATUS"
    fi
fi
echo ""

# ===== 测试 5: 数据库连接与实例规模 =====
echo "[测试 5] 压测后数据库状态"
docker exec brm-poc-lab-mysql-1 mysql -uroot -ppocroot -e "
SELECT 'flow_instance' as tbl, COUNT(*) as cnt FROM ttpos_poc_flow.flow_instance
UNION ALL
SELECT 'flow_task', COUNT(*) FROM ttpos_poc_flow.flow_task
UNION ALL
SELECT 'flow_his_task', COUNT(*) FROM ttpos_poc_flow.flow_his_task
UNION ALL
SELECT 'flow_user', COUNT(*) FROM ttpos_poc_flow.flow_user;
" 2>&1 | grep -v "Warning"
echo ""

# ===== 测试 6: JVM 内存与 GC =====
echo "[测试 6] JVM 内存快照"
docker exec brm-poc-lab-warm-flow-1 sh -c "
PID=\$(ps -o pid,comm | grep java | awk '{print \$1}');
if [ -n \"\$PID\" ]; then
    jcmd \$PID VM.heap_info 2>/dev/null || echo 'jcmd not available';
else
    echo 'java process not found';
fi
"
echo ""

# ===== 测试 7: 错误日志扫描 =====
echo "[测试 7] 引擎异常日志扫描 (最近 100 行)"
ERRORS=$(docker logs brm-poc-lab-warm-flow-1 --since 5m 2>&1 | grep -c "Exception\|ERROR\|FlowException" || echo "0")
echo "  最近5分钟异常数: $ERRORS"
if [ "$ERRORS" -gt 0 ]; then
    docker logs brm-poc-lab-warm-flow-1 --since 5m 2>&1 | grep "Exception\|ERROR\|FlowException" | tail -5
fi
echo ""

echo "=========================================="
echo "性能测试完成"
echo "=========================================="
