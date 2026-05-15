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

## 六、PoC 沙箱状态说明

本次实施在沙箱环境中**未能跑通端到端测试**——沙箱无 docker / go。已交付：

- ✅ 全部代码改动（5 个新/改文件）
- ✅ 种子数据 SQL
- ✅ 流程定义
- ✅ 一键验证脚本
- ⏳ 端到端跑通：需在有 docker 的开发机执行 `docker compose up -d --build && ./scripts/verify-ttpos-integration.sh`

跑完把 `passed/failed` 行贴回来，如果有 fail 我按上面表格定位。
