# ttpos × Warm-Flow 数据链路 PoC（接入层验证）

> 范围：仅验证 gateway 接入层与 ttpos 体系的三条数据链路。
> 不验证：完整 ttpos 后端部署、Flutter 端、生产化运维。

## 一、要验证的三条链路

| 链路 | 命题 | 通过判据 |
|---|---|---|
| ① **JWT 身份透传** | 用 ttpos 同款 HS256 + 同款 Claims 结构签发的 token，gateway 能解开并取出 `CompanyUuid` / `StaffUuid` | 无 token / 坏 token 返回 401；好 token 拿到上下文 |
| ② **动态审批人解析** | 引擎运行时通过 webhook 回调到 gateway，gateway 用真实 ttpos RBAC（`ttpos_access` → `role_access` → `staff_role` + 超管 union）解析出 staff_uuid 列表写回任务 | `task.permissionList` 包含持有该 access 的 staff + 超管，且不包含无权限的 staff |
| ③ **多租户隔离** | 不同 `companyUuid` 启动的流程，webhook 解析时定位到不同的 `shop{n}` 库，staff 互不串 | shop1001 流程的 handlers 不出现 shop1002 的人 |
| ④ **引擎强制鉴权** | 引擎按 `permissionList` 强制校验 skip handler，调用 `flow/approve` 时未授权 staff 被拒 | Eve（无权限）approve 返回非 0；Alice 成功 |

## 二、最小种子数据（`shop1001` / `shop1002` 两个公司库）

文件：`docker/mysql/init/04-ttpos-rbac-seed.sql`

| company | staff_uuid | 角色 | 期望解析结果 |
|---|---|---|---|
| 1001 | 10011 Alice | 店长（持 `transfer_order_approve`） | ✅ 在 handlers |
| 1001 | 10012 Bob | is_super=1 | ✅ 在 handlers（超管兜底） |
| 1001 | 10013 Eve | 无角色 | ❌ 不在 handlers |
| 1002 | 10021 Carol | 区域经理（持 `transfer_order_approve`） | ✅ 在 1002 handlers，❌ 不出现在 1001 |
| 1002 | 10022 Dave | is_super=1 | ✅ 在 1002 handlers |

表结构 / 字段名 / 软删除约定 / 超管 union 逻辑 **与 ttpos main 完全一致**，
直接对照 `main/app/service/notification_helper/permission.go::GetStaffsByAccessPath`。

## 三、接入层代码改动（仅 gateway，零侵入 ttpos）

| 文件 | 作用 |
|---|---|
| `gateway/ttpos_jwt.go` | 复刻 `main/pkg/auth/jwt.go` 的 `Claims` 结构和 HS256 签发/验证；提供 `requireTtposJWT` 中间件，注入 `companyUuid` / `staffUuid` 到 gin.Context |
| `gateway/ttpos_rbac.go` | 复刻 `GetStaffsByAccessPath` 语义，按 `companyUuid` 选 `shop{n}` 库；进程内缓存 `*gorm.DB` |
| `gateway/server.go` | `PostResolveApprover` 扩展 `ACCESS:<path>` 前缀分支；新增 `/api/v1/ttpos/{flow/start, flow/todo, flow/approve}` 三个 JWT-only 业务接口；`/dev/gen-token` 仅 PoC 用 |
| `warm-flow-app/.../FlowBootstrap.java` | 新增流程定义 `ttpos_transfer_test`：`start → ttpos_approve → end`，approve 节点 `permissionFlag = "WEBHOOK_RESOLVE:ACCESS:transfer_order_approve"`，挂 `assignment` 监听器 `WebhookPermissionListener` |

**关键设计：把 `companyUuid` 写进 `FlowParams.variable`**

`PostTtposFlowStart` 从 JWT 取出 `companyUuid` 后塞进 `variable["companyUuid"]`。
引擎在分配 approve 节点时通过 `WebhookPermissionListener` 把 `variables` flatten 进 webhook 请求体，
gateway 的 `resolve-approver` 从中拿到 `companyUuid` → 选 `shop{n}` 库 → 跑 RBAC 查询 → 返回 staff_uuid 列表。

这就是 ttpos 多租户与 Warm-Flow 引擎之间的桥。

## 四、运行验证（你的本机）

PoC 沙箱无 docker/go，需要在你自己的开发机执行：

```bash
cd /home/weifashi/hwt/brm-poc-lab
docker compose up -d --build           # 拉起 mysql + warm-flow + gateway
./scripts/verify-ttpos-integration.sh  # 跑 4 条断言
```

期望输出末尾：

```
passed: 12+   failed: 0
```

如果任何一条 fail，对照下面表格定位：

| 失败断言 | 大概率原因 |
|---|---|
| `gateway not up` | gateway 容器没起来，看 `docker compose logs poc-gateway` 是否构建失败（最可能是 `go.sum` 缺 jwt v5 → Dockerfile 已改为 `go mod tidy` 自动拉，重试 `--no-cache` build） |
| `no-token → 401` 失败 | JWT 中间件没生效或注册到错的 group |
| `Alice missing from handlers` | WebhookPermissionListener 没触发，看 warm-flow 日志 `WEBHOOK resolved role ...`；或 ACCESS: 分支没进，看 gateway 日志 |
| `Eve leaked into handlers` | RBAC 查询逻辑错（最可能 `delete_time = 0` 漏了或 union 错） |
| `shop1001 Alice leaked into shop1002` | `companyUuid` 没正确从 variables 取出，或缓存了错的 DB handle |
| `Eve approve unexpectedly succeeded` | 引擎没把 webhook 解析后的 ids 写进 `task.permissionList`（看 listener 的 `task.setPermissionList` 那段） |

## 五、对接生产化所需的最小改动（PoC 不做）

1. **gen-token 移除**：生产里 token 由 ttpos main 颁发，gateway 只验证。
2. **`pkg/auth.ParseToken` 共享**：把 ttpos-server-go 作为 Go module 真接入（go.mod replace），不要再 PoC 这样复刻一份。
3. **`GetStaffsByAccessPath` 真共享**：同上，直接调 `notification_helper.GetStaffsByAccessPath`。
4. **DB 连接走 ttpos `DBManager`**：用 ttpos 已有的多租户连接池，不要自己维护 `ttposDBManager`。
5. **流程定义入库化**：`ttpos_transfer_test` 这种硬编码改成从 ttpos 业务配置表加载，配合 warm-flow-ui 设计器。
6. **审计与监控**：webhook 解析失败、JWT 校验失败、`shop{n}` 库不可达，都要打到 ttpos 现有 OpenTelemetry 链路（`pkg/otel`）。

每一项都是"加 1–2 天工"的事，不是架构变更。

## 六、vm08 端到端跑通记录（2026-05-15）

在 vm-node08（43.239.84.28）clone 仓库执行 `docker compose up -d --build`，
跑 `./scripts/verify-ttpos-integration.sh` 结果：

```
passed: 14   failed: 0
```

涵盖：JWT 透传 / 动态审批人解析 / 引擎强制鉴权 / 多租户隔离。

### 跑通过程踩到的三个真实坑（已修）

| # | 现象 | 根因 | 修法 | 提交 |
|---|------|------|------|------|
| 1 | `/api/flow/start` 500：`Unknown column 'any_node_skip'` | seed schema (`03-warm-flow-all.sql`) 是 1.3.3 时代的，1.3.8 SELECT 多查了一列 | seed SQL 补列 | `1d4eac5` |
| 2 | gateway 返回的 instanceId 末 2 位被截断（`...02` → `...00`），下游用 ID 查不到任何记录 | Go `encoding/json` 解 `map[string]any` 数字默认走 float64，Java long snowflake（~2×10¹⁸）超过双精度安全整数范围 | wfClient 改 `json.Decoder.UseNumber()` 保原始数字串 | `1d4eac5` |
| 3 | 多审批人 SpEL（`#{#approvers}` = `"10011@@10012"`）解析后引擎对所有 caller 拒绝 | 1.3.8 只对**定义时字面量**的 `@@` permissionFlag 做拆分入 `flow_user` 多行；SpEL 运行时解析值不拆分，落成单行 `processed_by="10011@@10012"`，checkAuth 不命中 | 单审批人 SpEL `#{#approver}`（PoC 范围够用）；多审批人需要 custom Handler bean | `c13e8ef` |

第 2 坑是**通用的 Java/JS 大整数互操作问题**——Flutter / Web 前端调 Java 引擎也会踩，与本 PoC 无关。建议生产化时统一约定：所有 long ID 在 Java 控制器序列化为 string。

第 3 坑是当前 PoC 的**已知局限**：动态多审批人（含会签/票签）走 SpEL 时被 1.3.8 该 quirk 卡住。
解决方案二选一：
- **a. 自写 Handler**：在 Warm-Flow 加个 Spring bean 实现 `org.dromara.warm.flow.core.handler.PermissionHandler`，从 variables 拿 list 返回。约 50 行代码。
- **b. 定义时拼接 + Pre-resolve list 长度**：业务上能枚举的角色用定义时 `@@`，动态人单独节点 + addSignature。复杂度高。

推荐 a。

### 最终链路证据

```
JWT(company=1001, staff=10011)
  → gateway 解 JWT
  → ttposStaffsByAccessPath(1001, "transfer_order_approve") → [10011, 10012]   ← 真 ttpos RBAC 查询
  → pick caller eligible → 10011
  → /api/flow/start variable.approver=10011
  → engine 把 SpEL 解析为 10011 → flow_user.processed_by=10011
  → Eve (10013) skip   → checkAuth 拒绝 ✗
  → Alice (10011) skip → 引擎放行 ✓，流程结束（flowStatus=8）

JWT(company=1002, staff=10021)
  → ttposStaffsByAccessPath(1002, ...) → [10021, 10022]   ← shop1002 库，不串
  → flow_user.processed_by=10021                          ← 0 个 shop1001 staff 出现
```
