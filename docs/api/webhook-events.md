# flowlong-app Webhook 事件规格

flowlong-app 把 FlowLong 引擎内部的 TaskEvent / InstanceEvent 桥接成 HTTP POST，**主动**推给 ttpos main（或其他 caller）。

不在 OpenAPI spec 里，因为 OpenAPI 只描述 inbound endpoint。Webhook 是 outbound。

---

## 一、启用方式

flowlong-app 端 `application.yml`（或 Helm chart values）：

```yaml
flowlong:
  eventing:
    task: true        # 启用任务事件
    instance: true    # 启用实例事件
  webhook:
    enabled: true
    callback-url: http://ttpos-main.ttpos.svc.cluster.local:8080/internal/flow/webhook
    timeout-seconds: 5
    retry-max: 3
    retry-base-delay-ms: 500
    signature-header: X-Webhook-Signature  # 可选，签名 header 名
    signature-value: <preshared-secret>     # 可选，固定 token（PoC）；生产建议 HMAC
```

**默认 `enabled=false`**——caller 没准备接收时 webhook 不发出。

---

## 二、事件投递保证

| 维度 | 保证 |
|---|---|
| **传输** | HTTP POST，Content-Type: application/json |
| **可靠性** | 至少一次（at-least-once）：失败时按指数退避重试 ≤ retryMax 次（默认 3） |
| **重试间隔** | 500ms → 1000ms → 2000ms（base × 2^(attempt-1)） |
| **去重** | Caller 负责，按 `eventId`（UUID）做幂等 |
| **顺序** | **不保证**——异步线程池发送，同一 instance 的事件可能乱序到达 |
| **超时** | 每次请求 5 秒（可配） |
| **失败处理** | 重试耗尽后 log.error 丢弃；**不存 outbox**（PoC 简化，生产建议加） |
| **认证** | 可选签名 header；PoC 阶段无 |

**重要**：Caller 必须在 5 秒内返回 2xx，否则会被认为失败开始重试。处理慢就用 202 Accepted 立即 ack + 异步落表。

---

## 三、Payload 通用字段

所有事件 payload 都是 JSON 对象，包含通用字段：

| 字段 | 类型 | 必有 | 说明 |
|---|---|---|---|
| `eventId` | string (UUID) | ✅ | 唯一 ID，**Caller 用来去重** |
| `eventType` | string | ✅ | 格式 `task.<EventType>` 或 `instance.<EventType>`，详见下表 |
| `timestamp` | string (ISO 8601) | ✅ | flowlong-app 发出时间 |
| `creator` | object `{id, name}` | 通常有 | 触发该事件的操作人 |
| `tenantId` | string | 视事件 | 租户 ID（caller 启动时传的 companyUuid） |

---

## 四、Task 类事件

**eventType 格式**：`task.<TaskEventType>`，例如 `task.create` / `task.complete` / `task.reject`

完整 payload schema：

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "task.create",
  "timestamp": "2026-05-19T07:30:00.123Z",
  "taskId": "2056368596446793733",
  "instanceId": "2056368596446793729",
  "taskKey": "approve",
  "taskName": "RBAC 审批 (会签)",
  "taskType": 1,
  "performType": 2,
  "createTimeMs": 1747625400123,
  "nodeKey": "approve",
  "nodeName": "RBAC 审批 (会签)",
  "creator": {"id": "10011", "name": "Alice"},
  "actors": [
    {"id": "10011", "name": "Alice", "type": 0},
    {"id": "10012", "name": "Bob",   "type": 0}
  ]
}
```

### TaskEventType 完整枚举（来自 FlowLong 1.2.4 源码）

按业务场景分组，**MVP 通常只关心粗体的几个**：

| EventType | 含义 | MVP 用途 |
|---|---|---|
| **`create`** | 任务创建（节点激活） | **N1/N2 触发：通知新审批人有待办** |
| **`complete`** | 任务正常完成（PASS） | **N3 触发：通知发起人某节点已通过** |
| `recreate` | 任务重建（多人会签全部 reset） | 一般不直接用 |
| `update` | 任务字段更新 | 调试 |
| `delete` | 任务被删除 | 一般不直接用 |
| **`reject`** | 任务被驳回 | **N3 触发：通知发起人单据被驳回** |
| `claimRole` / `claimDepartment` | 角色 / 部门认领 | 高级用法 |
| `transfer` | 转办 | N6 通知 |
| `delegate` | 委派 | N6 通知 |
| `delegateResolve` | 委派归还 | N6 通知 |
| `addCountersign` | 加签 | N6 通知 |
| `addTaskActor` | 加办理人 | N6 通知 |
| `removeTaskActor` | 减办理人 | N6 通知 |
| `reclaim` | 拿回任务 | 高级 |
| `withdraw` | 撤销待审 | — |
| `resume` | 恢复 | — |
| `revoke` | 撤销审批 | N3 通知 |
| **`terminate`** | 任务终止（实例被强制终止时） | **N3 触发：单据被强制结束** |
| `agent` | 代理任务 | 高级 |
| `cc` / `createCc` | 抄送任务 | **N5 抄送通知** |
| `callProcess` | 调用外部子流程 | 子流程场景 |
| `timeout` | 超时 | N4 催办（MVP 不做） |
| `start` / `startAsDraft` / `restart` | 流程起点节点 | — |
| `jump` / `autoJump` / `rejectJump` / `routeJump` / `triggerJump` / `reApproveJump` | 各种跳转 | 看复杂场景 |
| `reApproveCreate` | 重新审批 | — |
| `autoComplete` / `autoReject` | 自动通过/拒绝 | — |
| `trigger` | 触发器节点 | 高级 |
| `end` | 终止节点完成 | 流程结束 |

---

## 五、Instance 类事件

**eventType 格式**：`instance.<InstanceEventType>`

完整 payload schema：

```json
{
  "eventId": "...",
  "eventType": "instance.end",
  "timestamp": "2026-05-19T07:35:00.456Z",
  "instanceId": "2056368596446793729",
  "businessKey": "TR-20260519-001",
  "currentNodeKey": "end",
  "currentNodeName": "结束",
  "tenantId": "1001",
  "nodeKey": "end",
  "nodeName": "结束",
  "creator": {"id": "10012", "name": "Bob"}
}
```

### InstanceEventType 枚举

按 PoC 验证 + 源码：

| EventType | 含义 | MVP 用途 |
|---|---|---|
| **`start`** | 实例创建 | 业务侧记录"流程开始" |
| **`end`** | 实例正常结束（全部通过）| **N3 触发：通知发起人单据通过、状态机更新单据 status=approved** |
| **`reject`** | 实例被驳回结束 | **N3 触发：单据被驳回到底，更新 status=rejected** |
| `revoke` | 撤销 | 业务侧 status=cancelled |
| **`terminate`** | 强制终止 | **N3 触发：管理员手动终止，发起人通知** |
| `timeout` | 超时结束 | MVP 不做 |
| `update` | 实例字段更新 | 调试 |
| `suspend` / `resume` | 挂起 / 恢复 | 高级 |

---

## 六、Caller 接收端实现建议

### 接口 shape

```go
// ttpos-server-go/main/app/api/flow/webhook.go
func (h *FlowWebhookHandler) Handle(c *gin.Context) {
    var ev FlowEvent
    if err := c.ShouldBindJSON(&ev); err != nil {
        c.AbortWithStatus(400)
        return
    }
    // 1. 幂等检查（按 eventId）
    if h.dedup.Seen(ev.EventID) {
        c.Status(202)
        return
    }
    // 2. 放队列，立即 ack
    h.queue.Push(ev)
    c.Status(202)
}
```

### 必做的三件事

| # | 做什么 | 为什么 |
|---|---|---|
| 1 | **按 `eventId` 去重** | flowlong-app 是 at-least-once 投递，同事件可能重复推 |
| 2 | **5 秒内返回 2xx** | 超时会触发 flowlong-app 重试，加重负担 |
| 3 | **不做 inline 业务逻辑**——立刻入队、异步处理 | webhook 接收要稳定，业务慢逻辑放后台 |

### 反例（不要这样写）

```go
// ❌ 在 webhook handler 里直接做业务、调 N1 推消息、写多张表
func Handle(c *gin.Context) {
    // ... 同步发钉钉、写 timeline、跑 SQL 事务（10+ 秒）
}
// 结果：flowlong-app 等 5 秒超时，重试 3 次，event 收到 4 遍
```

---

## 七、调试 / 演示

### 用 ttl.sh 临时接收点

```bash
# 注册一个临时 webhook 接收 URL（30 天有效）
TMP_URL="https://webhook.site/$(uuidgen | tr A-Z a-z)"
echo "Webhook test URL: $TMP_URL"
# 浏览器打开看到推送来的事件
```

### vm04 lab 启用 webhook 临时调试

```bash
kubectl -n ttpos-arch-lab set env deploy/flowlong-flowlong-app \
    WEBHOOK_ENABLED=true WEBHOOK_URL="$TMP_URL"
# 触发一个 /flow/start，浏览器 webhook.site 会立刻看到事件
```

### 看哪些事件被推过去

flowlong-app 自己的日志：

```bash
kubectl -n ttpos-arch-lab logs deploy/flowlong-flowlong-app | grep -E "webhook delivered|webhook give up"
```

---

## 八、PoC 阶段的简化决策

| 项 | PoC | 生产 |
|---|---|---|
| Outbox 持久化 | ❌ 失败丢弃 | ✅ 必须，DB outbox + 后台扫描重投 |
| 签名校验 | ❌ 可选 header | ✅ HMAC-SHA256 + 时间戳 |
| 重试策略 | 内存重试 3 次 | 持久化重试 + 最终死信告警 |
| 顺序保证 | ❌ 异步乱序 | 看场景，按 instance 分区保序 |
| 接收端鉴权 | ❌ | ✅ 内网信任 + NetworkPolicy 或 mTLS |

PoC 这套实现是**给 MVP 集成调试用**的最薄壳。真上生产前 outbox 必须加。

---

## 九、相关源码

| 文件 | 作用 |
|---|---|
| `flowlong-app/src/main/java/com/ttpos/flpoc/webhook/EventBridge.java` | `@EventListener` 监听 TaskEvent + InstanceEvent，转 payload |
| `flowlong-app/src/main/java/com/ttpos/flpoc/webhook/WebhookSender.java` | 异步发送 + 重试 + 签名 |
| `flowlong-app/src/main/resources/application.yml` | `flowlong.webhook.*` 配置 |
| `flowlong-app/charts/flowlong-app/values.yaml` | Helm 同步 |

源 EventType 枚举：FlowLong 1.2.4 `com.aizuda.bpm.engine.core.enums.TaskEventType` / `InstanceEventType`
