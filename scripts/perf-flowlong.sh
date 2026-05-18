#!/bin/bash
# FlowLong 1.2.4 性能压测——跟 perf-test-v2.sh (warm-flow) 同手法
BASE_URL="${BASE_URL:-http://localhost:8082}"

start_flow() {
    local idx=$1
    curl -s -X POST $BASE_URL/flow/start \
        -H "Content-Type: application/json" \
        -d "{\"userId\":\"10011\",\"userName\":\"Alice\",\"companyUuid\":1001,\"businessId\":\"FL-PERF-$idx\"}" > /dev/null
}

echo "=========================================="
echo "FlowLong 1.2.4 性能测试"
echo "=========================================="

# ===== T1: 单线程串行启动 × 100 =====
echo "[T1] 单线程串行启动 × 100"
START=$(date +%s%N)
for i in $(seq 1 100); do start_flow "T1-$i"; done
END=$(date +%s%N)
MS=$(( (END - START) / 1000000 ))
echo "  总耗时: ${MS}ms | 平均: $((MS/100))ms/次 | TPS: $(echo "scale=1; 100000/$MS" | bc)"

# ===== T2: 串行完整流转 × 50（启动 + 两人审批结束）=====
echo "[T2] 串行完整流转 × 50（启动 → Alice + Bob 都审 → 结束）"
START=$(date +%s%N)
for i in $(seq 1 50); do
    RESP=$(curl -s -X POST $BASE_URL/flow/start \
        -H "Content-Type: application/json" \
        -d "{\"userId\":\"10011\",\"userName\":\"Alice\",\"companyUuid\":1001,\"businessId\":\"FL-T2-$i\"}")
    INS=$(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['instanceId'])" 2>/dev/null)
    [ -z "$INS" ] && continue
    # 拿任意一个 task 给 Alice 和 Bob 审
    for U in 10011 10012; do
        TID=$(curl -s "$BASE_URL/instance/actors?instanceId=$INS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['task_id'] if d else '')" 2>/dev/null)
        [ -z "$TID" ] && break
        curl -s -X POST "$BASE_URL/task/skip?taskId=$TID&userId=$U&userName=$U" > /dev/null
    done
done
END=$(date +%s%N)
MS=$(( (END - START) / 1000000 ))
echo "  总耗时: ${MS}ms | 平均: $((MS/50))ms/次完整流转"

# ===== T3: 并发启动阶梯加压 =====
for conc in 10 20 50; do
    total=$((conc * 5))
    echo "[T3.$conc] 并发启动 (${conc}并发 × 5轮 = ${total}次)"
    START=$(date +%s%N)
    for round in $(seq 1 5); do
        for i in $(seq 1 $conc); do
            idx=$(( (round - 1) * conc + i ))
            start_flow "T3-${conc}-${idx}" &
        done
        wait
        sleep 0.2
    done
    END=$(date +%s%N)
    MS=$(( (END - START) / 1000000 ))
    echo "  总耗时: ${MS}ms | 平均: $((MS/total))ms/次 | TPS: $(echo "scale=1; ${total}*1000/$MS" | bc)"
done

# ===== T4: 100 并发瞬时 =====
echo "[T4] 100 并发 × 1 轮"
START=$(date +%s%N)
for i in $(seq 1 100); do start_flow "T4-$i" & done
wait
END=$(date +%s%N)
MS=$(( (END - START) / 1000000 ))
echo "  总耗时: ${MS}ms | 平均: $((MS/100))ms/次 | TPS: $(echo "scale=1; 100000/$MS" | bc)"

# ===== 容器内存 =====
echo ""
echo "[内存] flowlong 容器 RSS:"
docker stats --no-stream --format "  {{.Container}}: {{.MemUsage}} (CPU {{.CPUPerc}})" | grep flowlong
