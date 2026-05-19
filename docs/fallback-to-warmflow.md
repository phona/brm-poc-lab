# 切回 Warm-Flow 应急预案

> 状态：备用预案（未触发） · 日期：2026-05-19
> 关联：`docs/decision-flowlong.md` · `docs/ttpos-integration-report.md`

## 一、适用前提

本预案**仅在生产发版前**有效。一旦生产环境跑起来、有真实在飞流程数据，迁移成本会上一个量级（要写数据迁移脚本 + 双跑验证 + 灰度切流），不在本预案讨论范围内。

**未发版 = 业务侧还没接 ttpos main / Flutter 端 / 没有用户数据**。
**已发版 = 走数据迁移流程，另起预案。**

## 二、触发条件（任一即评估，不必全中）

| # | 触发条件 | 紧迫度 |
|---|---|---|
| 1 | FlowLong 某能力踩到底层 bug，issue 提交 1 周以上无响应 | 高 |
| 2 | 升级 FlowLong 小版本引入 regression，老版本又无法回退 | 高 |
| 3 | 业务提出 FlowLong 明确不支持的能力，自研补丁评估 > 5 天 | 中 |
| 4 | aizuda 团队失维（连续 6 个月无 release / 仓库无更新）| 高 |
| 5 | ttpos 商业方向转向 OEM 白标 | **强制**——AGPL 触发不可逆 |
| 6 | FlowLong 出现安全 CVE 且官方修复 > 2 周未发布 | 中 |

第 5 条是 hard stop，其它都是评估窗口——评估"切回 Warm-Flow"和"在 FlowLong 上写 workaround"哪个划算。

## 三、工作清单

### 3.1 代码层（合计 1-2 人天）

| 项 | 改动量 | 说明 |
|---|---|---|
| `flowlong-app/` 整体停用 / 归档 | 0 | 留作历史参考，不删 |
| `warm-flow-app/` 恢复主线地位 | 0 | PoC 期间一直保留，14/14 验证过 |
| ttpos main `service/flow/` 客户端调用 | ~5 处 API 调整 | `FlowLongEngine.executeTask()` → `TaskService.skip()`；`engine.startInstanceByProcessKey()` → `InsService.start()`；详见两端 controller 对比 |
| 多审批人模式 | 改回单审批人 SpEL `#{#approver}` | Warm-Flow 1.3.8 quirk，PoC 已确认绕过方式 |
| 业务回调（flow-finished）endpoint 名 | 可能微调 | 看具体集成时是否绑死了 FlowLong 命名 |
| Helm chart | 从 flowlong-app/charts 拷贝一份改成 warm-flow-app | 大部分模板复用，改 image / probe 路径 |

### 3.2 数据层（合计 0 天）

| 项 | 处置 |
|---|---|
| MySQL schema | 删 `flowlong` 库；建 `warm-flow` 库，灌 `03-warm-flow-all.sql`（已含 1.3.8 列修复） |
| 业务表 | 没动过——业务回调还没真接，无表结构耦合 |
| 历史流程数据 | **没有**——前提是未发版 |

### 3.3 部署层（合计 0.5 人天）

| 项 | 改动 |
|---|---|
| 镜像构建 | warm-flow-app 已有 Dockerfile，CI workflow 复制一份 |
| vm04 部署 | helm upgrade 指向新 chart + 新 image |
| 监控告警 | Prometheus 指标名可能换几个，dashboard 调整 |
| 日志格式 | logback 配置照搬 |

### 3.4 文档层（合计 0.5 人天）

| 项 | 改动 |
|---|---|
| `decision-flowlong.md` | 加一段"切换记录"，说明触发原因 + 时间 |
| `pitfalls.md` | 把切换过程的新坑追加 |
| 新增 `decision-warmflow.md` | 简版，引用 `decision-flowlong.md` 的候选对比 |
| `ttpos-integration-report.md` | 标注"恢复使用"（这份本来就是基于 warm-flow 写的） |

## 四、合计工时

| 角色 | 工时 |
|---|---|
| 后端（Go + Java） | 1.5-2 人天 |
| DevOps（K8s + CI）| 0.5-1 人天 |
| 文档 | 0.5 人天 |
| **总计** | **2.5-3.5 人天** |

**外加 0.5-1 天 buffer**（联调、回归测试）= 实际窗口 **3-5 个工作日**。

## 五、已有资产盘点（这是预案成立的根基）

| 资产 | 路径 | 状态 |
|---|---|---|
| Warm-Flow 1.3.8 完整 PoC 应用 | `warm-flow-app/` | 端到端 14/14 验证 |
| Schema（含 1.3.8 列修复）| `docker/mysql/init/03-warm-flow-all.sql` | 跑过 |
| RBAC seed | `docker/mysql/init/04-ttpos-rbac-seed.sql` | 跑过 |
| 接入层架构 | gateway/ + `docs/ttpos-integration-report.md` | 跑通 |
| 一键验证脚本 | `scripts/verify-ttpos-integration.sh` | 14/14 通过 |
| 性能基线 | `docs/perf-test-report.md` | 88 TPS @ 50并发实测 |
| 踩过的坑 | `docs/pitfalls.md` 第 2/8 节 | 已知风险清单 |

**这些不是文档纪念品，是切换时直接复用的生产输入**。

## 六、切换验证清单

切换完成判定（全部 ✓ 才算切换完成）：

- [ ] vm04 上 warm-flow-app + warm-flow-mysql 都 Running
- [ ] `verify-ttpos-integration.sh` 14/14 通过
- [ ] 业务流量从 ttpos main 调过来能跑通"提交 → 审批 → 结束"全链路
- [ ] 性能压测 ≥ 50 TPS（vm04 lab 环境的 baseline）
- [ ] Prometheus 业务指标（`flow_start_total`）有数据
- [ ] JSON 日志格式与 GCP Ops Agent 兼容
- [ ] flowlong-app 资源全部下线（pod / svc / pvc / configmap / secret）
- [ ] ghcr.io 上 flowlong-app 镜像归档（不删，标 deprecated tag）

## 七、归档处理（切换完成后）

| 资产 | 处置 |
|---|---|
| `flowlong-app/` 整个目录 | 不删，加 README 说明"deprecated, see decision-flowlong.md切换记录" |
| `docs/decision-flowlong.md` | 改标题为"FlowLong 选型历史 + 切换记录"，加切换决策 |
| `docs/flowlong-vs-warmflow.md` | 保留，仍是有效对比资料 |
| `docs/native-image-attempt.md` | 保留 |
| `flw_*` 数据库 | 删除 |
| GitHub Actions workflow | 禁用（不删，留底） |
| ghcr.io flowlong-app 镜像 | 加 deprecated tag，不删（万一未来再切回去）|

## 八、风险与限制

| 风险 | 说明 |
|---|---|
| **切换窗口拖长** | 业务侧每天积累新代码，切换窗口超 1 周就开始遇到 conflicts |
| **多审批人模式回退** | Warm-Flow SpEL `@@` 不拆分的 quirk 还在，业务如果已经设计了多人会签，要改回单审批人或写 custom Handler |
| **Flutter 端 API contract** | 如果已经按 FlowLong API 命名给前端，要重新对齐字段名 |
| **团队认知** | 团队这段时间投入熟悉了 FlowLong，切回 Warm-Flow 要重新拉一遍知识对齐 |
| **沉没成本偏见** | "在 FlowLong 上已经投了 N 天" 会让人本能拒绝切换——决策时要排除这个 |

## 九、最重要的一条原则

**判断切换时机：看"未来"成本，不看"过去"投入**。

- 在 FlowLong 上已经投了多少时间不重要
- 在 FlowLong 上**继续解决问题**还要投多久才重要
- 如果"继续解决" > "切回 Warm-Flow + 后续维护"，立即切

PoC 阶段保留 Warm-Flow 作为备胎，**这个备胎随时可用**。这是当时不删的核心价值。

## 十、决策入口

如果触发任一条件，按以下顺序处置：

1. 在 issue tracker 上记录触发事件 + 时间
2. 评估 FlowLong 自研 workaround 工作量（**给个上限，比如 3 天**）
3. 评估切回 Warm-Flow 工作量（本文档预估 2.5-3.5 天）
4. 工作量比较 + 长期维护成本比较
5. 决策后 24 小时内启动切换或确认继续
6. 决策记录追加到 `docs/decision-flowlong.md`

**别让"再调一调试试"的拖延变成既成事实**——这是工程决策最容易踩的陷阱。
