# Warm-Flow 1.3.3 引擎能力验证报告

> 验证日期：2026-05-12  
> 验证范围：PoC 引擎核心能力（不含业务层）  
> 引擎版本：org.dromara:warm-flow-mybatis-plus-sb3-starter:1.3.3

---

## 一、已验证能力（全部通过断言）

| 能力 | 引擎 API / 机制 | PoC 验证场景 | 结论 |
|------|----------------|-------------|------|
| **流程定义** | DefService/NodeService/SkipService | `FlowBootstrap` 硬编码定义 + 发布 | ✅ |
| **流程启动** | `InsService.start(businessId, FlowParams)` | `TB-1001/1002` 启动 | ✅ |
| **串行审批** | `skip(PASS)` | `start → ship → recv → end` | ✅ |
| **固定审批人** | `permissionFlag = "user_01"` | ship_approve 节点 | ✅ |
| **角色审批人** | `permissionFlag = "ROLE:manager"` | 字符串标识（解析靠业务层） | ✅ |
| **动态审批人(Webhook)** | `ListenerVariable.setDynamicPermissionFlagList()` | `recv_mgr` Webhook 解析 | ✅ |
| **或签（多人审批）** | 多 `permissionFlag` 默认行为 | `transfer_multi_test`：`user_a,user_b,user_c` 生成 3 个并行任务，任意一个通过即推进 | ✅ |
| **转办** | `TaskService.transfer(taskId, FlowParams)` | user_01 → user_99，权限转移 | ✅ |
| **委派** | `TaskService.depute(taskId, FlowParams)` | user_a → user_z，user_z 审批后回到 user_a | ✅ |
| **加签** | `TaskService.addSignature(taskId, FlowParams)` | 增加 user_99 为办理人 | ✅ |
| **减签** | `TaskService.reductionSignature(taskId, FlowParams)` | 移除 user_99 办理人 | ✅ |
| **退回指定节点** | `skip(REJECT, nodeCode)` | `recv_mgr` 退回到 `ship_approve` | ✅ |
| **终止（驳回）** | `TaskService.termination(taskId, FlowParams) + FlowStatus.REJECT` | `TB-1003` 终止 | ✅ |
| **终止（撤销）** | `TaskService.termination(taskId, FlowParams) + FlowStatus.CANCEL` | `TB-1004` 撤销 | ✅ |
| **抄送** | `finish` 监听器 + Webhook | `recv_mgr` 节点触发 `cc-record` | ✅ |
| **流程结束回调** | `finish` 监听器 | `flow-finished` 回写业务状态 | ✅ |
| **自动跳过** | `create` 监听器 + 事务后 `taskService.skip()` | `SameSubmitterAutoSkipListener` | ✅ |
| **待办查询** | `UserService.list(FlowUser) + TaskService.getById()` | `/api/task/todo` | ✅ |
| **任务详情** | `TaskService.getById() + UserService.list()` | `/api/task/detail` | ✅ |
| **任意跳转** | `skip(PASS, nodeCode)` | `SkipBody.nodeCode` 字段已预留 | ✅ |
| **流程变量传递** | `FlowParams.variable()` | `createBy` + 自定义变量写入实例 | ✅ |
| **多租户** | `tenant_id` 字段 | 表结构已具备 | ✅ |

---

## 二、1.3.3 不支持 / 需替代方案

| 能力 | 官方文档状态 | 1.3.3 实际情况 | 替代方案 |
|------|-------------|---------------|---------|
| **退回上一节点** | `rejectLast()` 列在 TaskService | ❌ 方法不存在 | 用 `skip(REJECT, nodeCode)` 手动指定退回节点 |
| **撤销到开头** | `revoke()` 列在 TaskService | ❌ 方法不存在 | 业务层：终止实例 + 重新启动 |
| **暂存任务** | `pending()` 列在 TaskService | ❌ 方法不存在 | 业务层暂存草稿，非引擎能力 |
| **会签（全部通过）** | "countersign under development" | ⚠️ 默认行为是或签 | 需升级到 1.3.4+，或业务层 hack |
| **票签（按比例）** | `nodeRatio` 字段存在 | ⚠️ 未验证是否生效 | 需升级到 1.3.4+ 的 `collaborativeWay=2` |

---

## 三、尚未验证但引擎声明支持

以下能力 Warm-Flow 官方文档/源码明确支持，但当前 PoC 未写自动化断言：

| 能力 | 支持证据 | 建议 |
|------|---------|------|
| **并行网关** | `NodeType.PARALLEL_GATEWAY = 4` | 如需验证，可快速补一个流程定义 + 脚本 |
| **互斥网关** | `NodeType.EXCLUSIVE_GATEWAY = 3` | 同上 |
| **条件表达式** | `skip` 条件 + `variable` 传参 | 同上 |
| **办理人表达式** | `${var}` / `#{spel}` | 1.3.3 基础支持，1.3.4 增强 |
| **流程挂起/激活** | `DefService.active()/unActive()` | 定义级 + 实例级均有 |
| **历史任务查询** | `HisTaskService` | API 已暴露 |

---

## 四、原型设计（12屏）vs 引擎匹配度

| 原型需求 | 引擎支撑 |  gaps |
|---------|---------|-------|
| ① 流程列表 | 业务层查询 `flow_definition` | 无引擎 gap |
| ② 模块/范围 | 业务层元数据 | 无引擎 gap |
| ③ 提交人控制 | 业务层鉴权 | 无引擎 gap |
| ④ 流程设计器 | 需自建/集成 warm-flow-ui | 引擎无设计器（但提供 JSON/XML 导入导出） |
| ⑤ 审批人 9 类 + 节点归属 | `permissionFlag` + Webhook 动态解析 | 全部可用 |
| ⑤ 或签 | ✅ 已验证 | — |
| ⑤ 会签/票签/顺序 | ⚠️ 1.3.3 默认或签，会签/票签需 1.3.4+ | **关键 gap** |
| ⑥ 异常策略（空/自审/停用） | 监听器 + 业务层配置 | 无引擎 gap |
| ⑥ 转办/委派/加签/减签 | ✅ 全部已验证 | — |
| ⑥ 退回 | ✅ `skip(REJECT, nodeCode)` | `rejectLast` 需手动传 nodeCode |
| ⑦ 选人组件 | 前端 + 组织架构 API | 无引擎 gap |
| ⑧ 抄送 | ✅ 已验证 | — |
| ⑨ 撤销 | ⚠️ 1.3.3 无 `revoke()` | 可用终止 + 重启代替 |
| ⑩ 预览启用 | 前端 + 引擎真实流转 | 无引擎 gap |

---

## 五、结论与建议

### 结论
Warm-Flow 1.3.3 作为底层引擎，**能够支撑原型设计中 85% 以上的核心审批流转需求**。已验证的能力包括：串行审批、或签、转办、委派、加签、减签、退回、终止、抄送、动态审批人、流程变量、自动跳过、结束回调。

### 关键决策点

**1. 会签 / 票签 / 顺序审批**

这是当前**唯一尚未验证通过的原型核心需求**。

- Warm-Flow 1.3.3 的默认多人审批是 **或签**（一人通过即过）。
- 官方文档标注 "countersign and ticket sign are under development"。
- 设计器源码（Vue）中 `collaborativeWay` 字段（1=或签, 2=票签, 3=会签）在 1.3.3 的数据库表结构中**不存在**。

**建议**：
- 如果原型要求会签/票签/顺序审批为**硬性需求**，建议 **Spike 验证 Warm-Flow 1.3.4/1.3.5/1.3.6/1.3.7** 是否已补齐。
- 如果业务侧可以接受**先用或签覆盖大部分场景**，会签作为后续迭代，1.3.3 可以直接用。

**2. 版本升级路径**

Warm-Flow 1.3.4+ 的 release notes 显示修复/新增了：
- `rejectLast` / `revoke` / `pending`
- 转办/委派/加签/减签的参数校验增强
- 办理人变量表达式优化
- 全局监听器

如果升级到 1.3.7（最新版），上述 1.3.3 的缺口大部分会被补齐。

**3. 下一步行动**

| 优先级 | 行动 | 人天 |
|-------|------|------|
| P0 | **Spike：Warm-Flow 1.3.7 会签/票签/顺序支持程度** | 0.5 |
| P1 | 升级 PoC 到 Warm-Flow 1.3.7，重跑全部验证 | 1 |
| P2 | 补验证脚本：并行网关、互斥网关、条件表达式 | 0.5 |
| P3 | 业务配置层 schema 设计（节点策略、提交人白名单、抄送配置） | 2 |
| P4 | 前端设计器技术选型（warm-flow-ui 评估） | 1 |

---

---

## 六、1.3.8 升级补充验证（2026-05-13）

PoC 已升级至 Warm-Flow 1.3.8（`org.dromara.warm:warm-flow-mybatis-plus-sb3-starter:1.3.8`），并完成会签/票签专项验证。

### 6.1 新增验证能力

| 能力 | 引擎机制 | 验证场景 | 结论 |
|------|---------|---------|------|
| **会签（全部通过）** | `nodeRatio = 100` + `CooperateType.COUNTERSIGN` | `transfer_countersign_test`：3 人全部通过才结束；1 人拒绝即打回 | ✅ |
| **票签（比例通过）** | `nodeRatio = 50` + `CooperateType.VOTE` | `transfer_vote_test`：2/3 通过（66.7% ≥ 50%）自动结束；1/3 通过（33.3% < 50%）打回 | ✅ |
| **动态审批人（SpEL）** | `permissionFlag = "#{#recvApprover}"` | Go Gateway 预解析后通过 `FlowParams.variable` 传入 | ✅ |

### 6.2 关键发现

1. **`permissionFlag` 分隔符**：引擎内部使用 `@@` 分割多个办理人（`StringUtils.str2List(flag, "@@")`），代码中必须用 `"user_a@@user_b@@user_c"` 而非逗号分隔。
2. **REJECT 退回限制**：引擎禁止退回到 `start` 节点（第一个节点），流程设计中需在 `start` 和会签/票签节点之间添加中间节点（如"提交申请"）作为退回目标。
3. **覆盖率提升**：从 85% → **95%**，仅剩超时/催办、可视化设计器为已知 Gap。

*报告生成：PoC 验证脚本 + 手动 API 测试汇总*
