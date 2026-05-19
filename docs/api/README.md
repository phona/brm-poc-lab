# flowlong-app API

OpenAPI 3.0 规格，**从生产 pod 运行时导出**，反映当前 deployed 状态。

## 文件

| 文件 | 用途 |
|---|---|
| `openapi.json` | OpenAPI 3.0 JSON 规格（导入 Postman / Insomnia / 代码生成器）|
| `openapi.yaml` | 同上 YAML 格式（人类可读）|

## 在线 Swagger UI

部署到 vm04 ttpos-arch-lab 后：

```bash
# port-forward
kubectl -n ttpos-arch-lab port-forward svc/flowlong-flowlong-app 8082:8082

# 浏览器打开
http://localhost:8082/swagger-ui.html
```

集群内访问（ttpos main 等同 namespace 服务）：

```
http://flowlong-flowlong-app.ttpos-arch-lab.svc.cluster.local:8082/swagger-ui.html
http://flowlong-flowlong-app.ttpos-arch-lab.svc.cluster.local:8082/v3/api-docs
http://flowlong-flowlong-app.ttpos-arch-lab.svc.cluster.local:8082/v3/api-docs.yaml
```

## 接口概览

| Method | Path | 用途 | Caller |
|---|---|---|---|
| POST | `/flow/start` | 启动流程实例 | ttpos main（用户提交单据时） |
| POST | `/task/skip` | 审批通过任务 | ttpos main（用户点"通过"时） |
| GET | `/task/todo` | 查某人待办 | ttpos main（审批中心后端）|
| GET | `/instance/actors` | 查实例所有任务参与者 | 调试 / 审计 |
| GET | `/health` | Liveness 探针 | K8s |
| GET | `/actuator/health` | Spring Boot 健康检查（含 liveness/readiness 子探针）| K8s 探针 / 监控 |
| GET | `/actuator/prometheus` | Prometheus 指标 | GCP Managed Prometheus / 自建 Prom |

## 关键约定

### 1. 雪花 long ID 字符串化

所有 ID 字段（`instanceId`, `taskId`, `companyUuid` 等）**Java long → JSON string**，避免 JS 大整数精度问题。

```json
{"instanceId": "2056368596446793729"}    // string, 19 位
```

不要：
```json
{"instanceId": 2056368596446793729}       // number, JS 解析会精度丢失
```

### 2. Caller 预解析审批人 list

`/flow/start` 必须传 `approverIds`（简单形式）或 `approvers`（结构化）二选一。

**Java 引擎不连 ttpos DB、不查 RBAC**。Caller (ttpos main) 在调用前自己调
`GetStaffsByAccessPath(companyUuid, accessPath)` 算好审批人 list。

详见 `docs/decision-flowlong.md` 第五节"集成架构"。

### 3. 多审批人会签

`/flow/start` 传 N 个 approverIds，引擎按 N 个生成 `flw_task_actor` 行。
默认 `examineMode=2`（会签：全员通过才结束）。Alice 通过后实例仍是"审批中"，
Bob 通过后实例置"通过"。

## 用法示例

### Postman / Insomnia

```bash
# 导入 openapi.json，自动生成所有接口的请求模板
```

### curl

```bash
# 1. 启动流程
curl -sS -X POST http://localhost:8082/flow/start \
  -H "Content-Type: application/json" \
  -d '{"userId":"10011","userName":"Alice","companyUuid":1001,"businessId":"TR-001","approverIds":["10011","10012"]}'
# → {"instanceId":"...","currentNodeKey":"start","currentNodeName":"发起人"}

# 2. 查 Alice 待办
curl -sS "http://localhost:8082/task/todo?userId=10011"

# 3. Alice 审批通过
curl -sS -X POST "http://localhost:8082/task/skip?taskId=...&userId=10011&userName=Alice"
# → {"success":true}
```

### 代码生成

```bash
# Go client
openapi-generator-cli generate -i openapi.yaml -g go -o ./flowlong-client-go

# TypeScript / Dart 同理
openapi-generator-cli generate -i openapi.yaml -g typescript-axios -o ./flowlong-client-ts
openapi-generator-cli generate -i openapi.yaml -g dart-dio -o ./flowlong-client-dart
```

## 更新规格文件

代码改了之后 spec 不会自动更新本 docs 目录下的文件。手动重导：

```bash
kubectl -n ttpos-arch-lab port-forward svc/flowlong-flowlong-app 8082:8082 &
curl -sS http://localhost:8082/v3/api-docs      > docs/api/openapi.json
curl -sS http://localhost:8082/v3/api-docs.yaml > docs/api/openapi.yaml
kill %1
```

或者用 CI 在每次 push 后自动重导（待办）。
