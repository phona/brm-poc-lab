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

### 3.1 速判表

| 候选 | 类型 | 淘汰 / 选中 | 理由（一句话） |
|---|---|---|---|
| **easy-flow / task-flow** | BPM | ❌ | 停更 2+ 年，无人响应 issue |
| **Snakerflow** | 轻量 BPM | ❌ | 国产老引擎，停更 10 年+，无 SB3 适配 |
| **Temporal** | workflow-as-code | ❌ | 错频道——没有"人工任务/待办"概念，做审批等于自己造 BPM |
| **LiteFlow** | 规则编排 | ❌ | 错频道——是给开发者编排代码用的，跟审批不是同一类 |
| **Camunda 7 CE** | 企业 BPM | ❌ | **2025-10-14 官方宣布 CE 版 EOL**；BPMN 思维不贴中国式审批 |
| **Camunda 8 / Zeebe** | 云原生 BPM | ❌ | License 改为 Camunda License v1.0（非 OSI），自营 SaaS 商用受限 |
| **Flowable 7** | 企业 BPM | ❌ | 60+ 张表过重；中国式审批要二开；高级功能商业 gating |
| **Activiti 7/8** | 企业 BPM | ❌ | 社区下滑明显，已被 Flowable 取代 |
| **Bonita BPM** | 法系 BPM | ❌ | GPL v2 + 商业双 License；国内零社区 |
| **JeecgBoot / RuoYi-Flowable** | 低代码全家桶 | ❌ | 侵入性太强，捆绑整套后台脚手架 |
| **Warm-Flow 1.3.8** | 国产轻量 BPM | 备胎 | PoC 已验 14/14，但有动态多审批人 quirk |
| **FlowLong 1.2.4** | 国产轻量 BPM | ✅ **采用** | 见下节 |

### 3.2 企业 BPM 三巨头详细盘点

针对 Camunda / Flowable / Activiti（一开始最容易被列为"行业标准"的候选），按维度细看：

| 维度 | Camunda 7.22+ CE | Flowable 7 | Activiti 7/8 |
|---|---|---|---|
| 表数量 | ~48 张 `ACT_*`+`CAM_*` | **60+ 张**（含 BPMN/CMMN/DMN/Form/Content/IDM） | ~40 张；Cloud 版加 Keycloak/RabbitMQ/ES |
| 部署 | 嵌入式 / Run 独立 / 集群 | 嵌入式 JAR / Flowable UI App | Core 嵌入；**Cloud 必须 K8s 多 Pod** |
| 空载内存 | 350–500 MB 嵌入 / 700M-1G 独立 | 300–450 MB 嵌入 / 生产 500+ | Core 同 Flowable；**Cloud 全家桶 4–6 GB 起** |
| License | Apache 2.0 但 **CE 2025-10 EOL**，仅 EE 续到 2030 | Apache 2.0；高级 Form/Case/Audit 商业 gating | Apache 2.0；个人主导，提交量下滑 |
| 中文社区 | 老文章多，EOL 后基本断流 | **国内最活跃**，RuoYi-Flowable 衍生项目多 | 7/8 中文资料稀缺 |
| Designer | Camunda Modeler 桌面免费，Cockpit EE 强 | Modeler CE 包含但 UI 老旧，多用三方 | **官方 Modeler 停止维护** |
| 中国式审批适配 | **差**（BPMN 西方思维，加签/退回/转办都要二开） | **较差**（同上，社区有补丁但不够开箱） | **差** + 无中国化生态 |
| 多租户 | 内置 tenant_id 列 | 内置 tenant_id 列 | Core 内置；Cloud 走 Keycloak realm，复杂 |
| SB3 / Java 17 | 7.22+ 支持，**但 EOL 一年后无人维护** | 7.0 起以 JDK 17 + SB 3 为基线，**最干净** | 8.x 才官方支持 SB 3 |

### 3.3 为什么三巨头都不选

**Camunda 7 CE**：硬伤是 **2025-10-14 EOL**（[官方公告](https://forum.camunda.io/t/important-update-camunda-7-community-edition-end-of-life-announced/50921)）。选了就要 2026 年立刻规划迁 Camunda 8（完全不同架构）或转 CIB seven 社区分叉。新项目压上去 = 主动给自己挖坑。

**Flowable 7**：技术上最稳的"国际派"。但 ① 60+ 张表对 5–20 单/店的轻量审批是工程过载；② 中国式审批（加签/退回/抄送）要自行二开，开发量等同重写一个轻引擎；③ 高级 Form/Case/Audit 被商业版 gating。**对万店 SaaS 自营场景过重**。

**Activiti 7/8**：社区下滑明显（Salaboy 个人主导）、Cloud 版组件过多、中国化生态弱于 Flowable。**没有任何选它而不选 Flowable 的理由**。

### 3.4 选 FlowLong 的核心命中点

ttpos 场景的真实约束：**单店审批量极轻（5–20/天）× 店铺数极重（万店级）× 中国式审批形态（钉钉/飞书风）× 自营 SaaS 商业可控 × 团队 Java 经验有限**。

FlowLong 在这五个维度同时命中：
1. ✅ **中国式审批开箱即用**——examineMode/setType 直接对应"会签/票签/或签/指定人/上级"等钉钉概念
2. ✅ **JSON 模型轻量**（8 张表）——对比 Camunda/Flowable 30–60+ 张表，运维心智成本低一个数量级
3. ✅ **Apache 2.0 自营商用无忧**（不做 OEM 不触发 AGPL）
4. ✅ **SB3 / JDK17 原生**——零适配
5. ✅ **仿钉钉/飞书设计器免费**——业务/产品自助改流程的入口

Camunda/Flowable/Activiti 三巨头分别在"未来生命周期"、"工程过载"、"社区/中国化适配"三个不同维度卡住。其它候选（Snakerflow/Bonita/Jeecg）在 License、维护性、侵入性上各有硬伤。

**FlowLong 是当前唯一同时满足这五条的现成方案，与 PoC 实测一致。**

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
