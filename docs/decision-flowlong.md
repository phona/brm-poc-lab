# 技术决策：审批流引擎选型 FlowLong

> 状态：**已决** · 日期：2026-05-18 · 决策者：weifashi  
> 关联 PoC：`brm-poc-lab` 仓库（warm-flow-app/ + flowlong-app/）

## 一、决策

ttpos 业务审批流（调拨/采购/盘点等）采用 **FlowLong 1.2.4** 作为底层 BPM 引擎，**Warm-Flow 不再继续**。

## 二、上下文

ttpos 需要一套支撑业务单据审批的工作流能力，覆盖：
- 串行/或签/会签/票签
- 转办/委派/加签/减签
- 退回到指定节点、终止、抄送
- 动态审批人（按角色/权限点解析人员）
- 多租户隔离（每个公司一个 `shop{companyUuid}` 库）
- 业务规模：万店级、单店日均审批 5–20 单（峰值 QPS 个位到两位数）
- 商业形态：**自营 SaaS**，不做 OEM 白标转售

## 三、候选与淘汰理由

| 候选 | 类型 | 淘汰 / 选中 | 理由 |
|---|---|---|---|
| **easy-flow / task-flow** | BPM | ❌ | 停更 2+ 年，无人响应 issue |
| **Temporal** | workflow-as-code | ❌ | 错频道——没有"人工任务/待办"概念，做审批等于自己造 BPM |
| **LiteFlow** | 规则编排 | ❌ | 错频道——是给开发者编排代码用的，跟审批不是同一类 |
| **Camunda / Flowable** | 企业 BPM | ❌ | 太重，外文社区，运维复杂度不匹配业务规模 |
| **Warm-Flow 1.3.8** | BPM | 备胎 | PoC 已验 14/14，但有动态多审批人 quirk |
| **FlowLong 1.2.4** | BPM | ✅ **采用** | 见下节 |

## 四、为什么选 FlowLong

### 4.1 核心决策因素

| 维度 | Warm-Flow | FlowLong | 影响 |
|---|---|---|---|
| **动态多审批人 SPI** | SpEL `@@` 不拆 `flow_user`，要写 50 行 hack | `TaskActorProvider` 返 List 自动多行 | **质变，决定性** |
| **扩展性**（节点类型/子流程/定时器/触发器） | 5 种节点 | 13+ 种内建节点 | 6–12 个月内会用到 |
| **设计器 UX** | warm-flow-vue（BPMN 风） | flowlong-designer（钉钉/飞书风） | 业务/产品自助改流程 |
| **API 风格** | 老派 Activiti 系 | 现代，更多 SPI | 长期维护成本低 |

### 4.2 接受的代价

| 代价 | 程度 | 评估 |
|---|---|---|
| 性能 | FlowLong 比 Warm-Flow 慢 1.5–2 倍（实测 vm08：51 vs 88 TPS @ 50 并发） | **可接受**——审批是人驱动，真实峰值个位数 QPS，两者都过剩 |
| License AGPL 触发条款 | OEM 白标转售场景下需买商业授权（¥7k–¥20k） | **可接受**——ttpos 是自营 SaaS，不触发 |
| 学习成本 | 团队第一次用，要熟悉 SPI/节点模型 | **可接受**——PoC 已踩过几个坑（process JSON 必填字段、deploy repeat=true），有文档沉淀 |
| 重做一次 PoC 验证 | ~4–6 人天 | **已支付**——PoC 12/12 通过 |

### 4.3 PoC 实测证据（vm08）

| 命题 | 验证结果 |
|---|---|
| 多审批人 SPI 一人一行 actor | ✅ Alice + Bob 各一行 `flw_task_actor` |
| 多租户隔离 | ✅ shop1001/shop1002 互不串 |
| 会签语义（全员通过才结束） | ✅ Alice 一人通过剩 1 待办，Bob 通过后流程结束 |
| 性能足以支撑业务 | ✅ 100 并发 13ms，远超业务峰值 |

详细见 `docs/flowlong-vs-warmflow.md`。

## 五、集成架构

### 5.1 服务边界（生产形态）

```
Flutter (ttpos-flutter)
    ↓ JWT
ttpos main (Go, ttpos-server-go/main)
    ├─ middleware/auth.go (JWT 验证，已有，零改动)
    ├─ service/notification_helper/permission.go (RBAC，已有，零改动)
    └─ service/flow/ (新增，~200-400 行)
        ↓ HTTP
flowlong-app (Java + Spring Boot 3 + FlowLong 1.2.4)
    └─ TtposTaskActorProvider (~50 行，调 ttpos RBAC 接口)
        ↓ JDBC
MySQL (shop{companyUuid}.ttpos_* + flowlong.flw_*)
```

### 5.2 关键设计原则

1. **胶水放 ttpos main，不开 bmp-flow 微服务**——避免微服务税；按 `service/flow/` 包结构隔离边界，将来要拆 1 天内能拆
2. **FlowLong 只做引擎，零业务逻辑**——它不知道"调拨单"是什么，只认 staff_uuid 字符串和流程定义 JSON
3. **零账号映射**——ttpos 是身份唯一真源，FlowLong 只在解析审批人时回调 ttpos RBAC（`TaskActorProvider`），不维护任何用户/角色同步表
4. **流程定义存 DB**（FlowLong 原生支持），让业务/产品通过设计器自助修改

### 5.3 不在 ttpos 写"胶水翻译代码"

ttpos main 那边需要写的代码全是**业务接入代码**（任何审批引擎方案都要写）：
- 业务发起调用（提交单据时调 FlowLong start）
- 业务回调接收（流程结束时回写业务表状态）
- 审批 UI 数据源（待办列表/审批通过/驳回）
- access 权限点定义（已有 `ttpos_access` 表，加几行配置）

**不存在"为对接 FlowLong 才多写的翻译代码"**。

## 六、后续行动

| 时机 | 工作 | 负责 |
|---|---|---|
| 立刻 | 把本决策周知团队，关闭引擎选型讨论 | — |
| 下周 | `brm-poc-lab` 仓库归档 warm-flow-app（保留作历史参考），主推 flowlong-app | — |
| 2 周内 | ttpos main 新建 `service/flow/` 模块，实现 JWT 透传 + RBAC 调用 + FlowLong HTTP client | 后端 |
| 2 周内 | 调拨单一条流程跑通端到端（含 Flutter 端审批中心 UI） | 前后端协作 |
| 3 个月后 | 复盘：FlowLong 的扩展性优势（子流程/超时催办/复杂条件）是否真用上 | — |

## 七、退出条件（什么情况下推翻这个决策）

以下任一发生，重新评估：

1. **ttpos 商业方向转向 OEM 白标**——AGPL 触发，必须切回 Warm-Flow 或买 FlowLong 商业版
2. **FlowLong 出现长期未修的严重 bug** 且 aizuda 团队明显失维（连续 6 个月无 release）
3. **业务量爆发到单机 50 TPS 都扛不住**（远低于审批场景实际峰值，几乎不可能）
4. **节点扩展性需求归零**（永远只用串行单审批），Warm-Flow 反而更轻量——但已经接入了就没动力切回

## 八、关联资料

- PoC 仓库：`brm-poc-lab`
- FlowLong PoC 验证脚本：`scripts/verify-flowlong.sh`（12/12 通过）
- Warm-Flow PoC 验证脚本：`scripts/verify-ttpos-integration.sh`（14/14 通过，作历史归档）
- 引擎对比报告：`docs/flowlong-vs-warmflow.md`
- 性能压测脚本：`scripts/perf-flowlong.sh` / `scripts/perf-test-v2.sh`
- 集成架构详细：`docs/ttpos-integration-report.md`（基于 warm-flow，迁移到 FlowLong 时同一架构，只换引擎调用层）
- FlowLong 官方：https://flowlong.aizuda.com
