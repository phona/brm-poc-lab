#!/bin/bash
# Warm-Flow 1.3.8 性能与稳定性测试脚本 v2（逐步加压）

BASE_URL="http://localhost:8081"
RESULT_DIR="/tmp/warm-flow-perf"
mkdir -p $RESULT_DIR

echo "=========================================="
echo "Warm-Flow 1.3.8 性能与稳定性测试 v2"
echo "=========================================="
echo ""

# 辅助函数：计时启动流程
start_flow() {
    local bid=$1
    curl -s -X POST $BASE_URL/api/flow/start \
        -H "Content-Type: application/json" \
        -d "{\"businessId\":\"$bid\",\"flowCode\":\"transfer_test\",\"handler\":\"admin\",\"variable\":{}}" > /dev/null
}

# 辅助函数：获取任务ID
get_task_id() {
    local ins=$1
    curl -s "$BASE_URL/api/instance/tasks?instanceId=$ins" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['list'][0]['taskId'] if d.get('list') else '')" 2>/dev/null
}

# 辅助函数：审批任务
skip_task() {
    local tid=$1
    local handler=$2
    curl -s -X POST $BASE_URL/api/task/skip \
        -H "Content-Type: application/json" \
        -d "{\"taskId\":$tid,\"handler\":\"$handler\",\"message\":\"perf\",\"skipType\":\"PASS\"}" > /dev/null
}

# ===== 测试 1: 单线程串行基准 =====
echo "[测试 1] 单线程串行启动流程 × 100"
START=$(date +%s%N)
for i in $(seq 1 100); do
    start_flow "PERF1-$i"
done
END=$(date +%s%N)
MS=$(( (END - START) / 1000000 ))
echo "  总耗时: ${MS}ms | 平均: $((MS/100))ms/次 | TPS: $(echo "scale=1; 100000/$MS" | bc)"
echo ""

# ===== 测试 2: 串行完整流转（启动+审批）=====
echo "[测试 2] 串行完整流转 × 50（启动→审批→结束）"
START=$(date +%s%N)
for i in $(seq 1 50); do
    RESP=$(curl -s -X POST $BASE_URL/api/flow/start -H "Content-Type: application/json" -d "{\"businessId\":\"PERF2-$i\",\"flowCode\":\"transfer_test\",\"handler\":\"admin\",\"variable\":{}}")
    INS=$(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['instanceId'])" 2>/dev/null)
    sleep 0.05
    TID=$(get_task_id $INS)
    if [ -n "$TID" ]; then
        skip_task $TID "user_01"
    fi
done
END=$(date +%s%N)
MS=$(( (END - START) / 1000000 ))
echo "  总耗时: ${MS}ms | 平均: $((MS/50))ms/次完整流转"
echo ""

# ===== 测试 3: 并发启动（阶梯加压）=====
for conc in 10 20 50; do
    total=$((conc * 5))
    echo "[测试 3.$conc] 并发启动流程 (${conc}并发 × 5轮 = ${total}次)"
    START=$(date +%s%N)
    for round in $(seq 1 5); do
        for i in $(seq 1 $conc); do
            idx=$(( (round - 1) * conc + i ))
            start_flow "PERF3-${conc}-${idx}" &
        done
        wait
        sleep 0.2
    done
    END=$(date +%s%N)
    MS=$(( (END - START) / 1000000 ))
    echo "  总耗时: ${MS}ms | 平均: $((MS/total))ms/次 | TPS: $(echo "scale=1; ${total}*1000/$MS" | bc)"
    # 检查容器是否还活着
    if ! docker ps | grep -q warm-flow; then
        echo "  ⚠️  容器已崩溃！并发上限: ~$((conc * (round - 1))) 并发"
        break
    fi
done
echo ""

# ===== 测试 4: 会签并发审批（逐步）=====
echo "[测试 4] 会签并发审批 — 锁竞争测试"
CS_RESP=$(curl -s -X POST $BASE_URL/api/flow/start -H "Content-Type: application/json" -d '{"businessId":"PERF4","flowCode":"transfer_countersign_test","handler":"admin","variable":{}}')
CS_INS=$(echo $CS_RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['instanceId'])" 2>/dev/null)
sleep 0.3
CS_T1=$(get_task_id $CS_INS)
if [ -n "$CS_T1" ]; then
    skip_task $CS_T1 "admin"
    sleep 0.3
    CS_T2=$(get_task_id $CS_INS)
    if [ -n "$CS_T2" ]; then
        echo "  会签任务ID: $CS_T2"
        START=$(date +%s%N)
        # 先 user_a 和 user_b 同时审批
        curl -s -X POST $BASE_URL/api/task/skip -H "Content-Type: application/json" -d "{\"taskId\":$CS_T2,\"handler\":\"user_a\",\"message\":\"a\",\"skipType\":\"PASS\"}" > $RESULT_DIR/lock_a.json &
        curl -s -X POST $BASE_URL/api/task/skip -H "Content-Type: application/json" -d "{\"taskId\":$CS_T2,\"handler\":\"user_b\",\"message\":\"b\",\"skipType\":\"PASS\"}" > $RESULT_DIR/lock_b.json &
        wait
        END=$(date +%s%N)
        MS=$(( (END - START) / 1000000 ))
        echo "  2人并发耗时: ${MS}ms"
        # 再 user_c 完成会签
        sleep 0.2
        START=$(date +%s%N)
        curl -s -X POST $BASE_URL/api/task/skip -H "Content-Type: application/json" -d "{\"taskId\":$CS_T2,\"handler\":\"user_c\",\"message\":\"c\",\"skipType\":\"PASS\"}" > $RESULT_DIR/lock_c.json
        END=$(date +%s%N)
        MS2=$(( (END - START) / 1000000 ))
        echo "  user_c 最终审批: ${MS2}ms"
        # 检查结果
        ERR_A=$(grep -c "error" $RESULT_DIR/lock_a.json 2>/dev/null | head -1 || echo 0)
        ERR_B=$(grep -c "error" $RESULT_DIR/lock_b.json 2>/dev/null | head -1 || echo 0)
        ERR_C=$(grep -c "error" $RESULT_DIR/lock_c.json 2>/dev/null | head -1 || echo 0)
        TOTAL_ERR=$((ERR_A + ERR_B + ERR_C))
        if [ "$TOTAL_ERR" -gt 0 ]; then
            echo "  ⚠️  发现 $TOTAL_ERR 个错误响应"
            cat $RESULT_DIR/lock_a.json; cat $RESULT_DIR/lock_b.json; cat $RESULT_DIR/lock_c.json
        else
            echo "  ✅ 全部成功（无并发异常）"
        fi
        # 最终状态
        sleep 0.3
        CS_FINAL=$(curl -s "$BASE_URL/api/instance/tasks?instanceId=$CS_INS")
        echo "  最终任务状态: $(echo $CS_FINAL | python3 -c 'import sys,json; d=json.load(sys.stdin); print("ended" if not d.get("list") else d["list"][0]["nodeCode"])' 2>/dev/null)"
    fi
fi
echo ""

# ===== 测试 5: 稳定性 — 持续低负载 2 分钟 =====
echo "[测试 5] 持续低负载测试 (2分钟，约 2 QPS)"
START=$(date +%s)
SUCCESS=0
FAILED=0
while [ $(($(date +%s) - START)) -lt 120 ]; do
    RESP=$(curl -s -X POST $BASE_URL/api/flow/start -H "Content-Type: application/json" -d "{\"businessId\":\"PERF5-${SUCCESS}\",\"flowCode\":\"transfer_test\",\"handler\":\"admin\",\"variable\":{}}")
    if echo "$RESP" | grep -q "instanceId"; then
        SUCCESS=$((SUCCESS + 1))
    else
        FAILED=$((FAILED + 1))
    fi
    sleep 0.5
done
END=$(date +%s)
DURATION=$((END - START))
echo "  持续时间: ${DURATION}s | 成功: $SUCCESS | 失败: $FAILED | 平均 QPS: $(echo "scale=1; $SUCCESS/$DURATION" | bc)"
# 检查内存
if docker ps | grep -q warm-flow; then
    MEM=$(docker stats --no-stream --format "{{.MemUsage}}" brm-poc-lab-warm-flow-1 2>/dev/null)
    echo "  容器内存: $MEM"
else
    echo "  ⚠️  容器已崩溃"
fi
echo ""

# ===== 测试 6: 数据库规模与连接 =====
echo "[测试 6] 压测后数据库状态"
if docker ps | grep -q mysql; then
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
    echo "  活跃连接数:"
    docker exec brm-poc-lab-mysql-1 mysql -uroot -ppocroot -e "SHOW STATUS LIKE 'Threads_connected';" 2>&1 | grep -v "Warning"
else
    echo "  MySQL 容器未运行"
fi
echo ""

# ===== 测试 7: 异常日志 =====
echo "[测试 7] 引擎异常日志扫描"
ERRORS=$(docker logs brm-poc-lab-warm-flow-1 --since 3m 2>&1 | grep -c "Exception\|ERROR\|FlowException" | head -1 || echo 0)
echo "  最近3分钟异常数: $ERRORS"
if [ "$ERRORS" -gt 0 ]; then
    docker logs brm-poc-lab-warm-flow-1 --since 3m 2>&1 | grep "Exception\|ERROR\|FlowException" | tail -3
fi
echo ""

# ===== 测试 8: 极限并发探测 =====
echo "[测试 8] 极限并发探测 (100并发 × 1轮)"
START=$(date +%s%N)
for i in $(seq 1 100); do
    start_flow "PERF8-$i" &
done
wait
END=$(date +%s%N)
MS=$(( (END - START) / 1000000 ))
echo "  100并发启动耗时: ${MS}ms | 平均: $((MS/100))ms/次"
if docker ps | grep -q warm-flow; then
    echo "  ✅ 容器存活"
else
    echo "  ⚠️  容器崩溃"
fi
echo ""

echo "=========================================="
echo "性能测试完成"
echo "=========================================="
