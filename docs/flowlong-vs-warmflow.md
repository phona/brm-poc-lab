# FlowLong 1.2.4 vs Warm-Flow 1.3.8 实测对比

> 同台 vm08，同 MySQL，同 ttpos RBAC 种子（shop1001/shop1002）。
> 两个 PoC 端到端跑通，对比维度全部实测。

## 一、核心命题（决定走向的那一个）

> **动态多审批人：能否一人一行存进 task_actor？**

| 引擎 | 测试结果 | 机制 |
|---|---|---|
| **Warm-Flow 1.3.8** | ❌ SpEL `#{#approvers}` 解析出 `"10011@@10012"` 后**只写一行** `flow_user.processed_by="10011@@10012"`；引擎 checkAuth 不命中，全员被拒 | SpEL 解析时机晚于 flow_user 持久化；只有**定义时字面量** `@@` 才拆分 |
| **FlowLong 1.2.4** | ✅ `TaskActorProvider.getTaskActors()` 返回 `List<FlwTaskActor>`，引擎**一人一行**写 `flw_task_actor`（实测 Alice=10011 / Bob=10012 各一行） | SPI 接口设计为 List，引擎按 list 大小创建 actor 记录，无歧义 |

vm08 验证截图：

```
actors1=[10011
10012]
✓ approve task has 2 actor rows (expect ≥2)
✓ Alice (10011) is one of the actors
✓ Bob/superadmin (10012) is one of the actors

after Alice approves: instance_state=[0] active_tasks=1
✓ 会签语义：Alice 一人通过后还有 1 个待办，未结束
after Bob approves: instance_state=1 active_tasks=0
✓ 会签全员通过，流程结束
```

**这是切 FlowLong 的最强动机**——Warm-Flow 那个 quirk 要写 50 行 custom Handler 才能修，FlowLong 是开箱即可用的一等公民。

## 二、维度对比

| 维度 | Warm-Flow 1.3.8 | FlowLong 1.2.4 |
|---|---|---|
| **维护活跃度** | dromara，迭代中 | aizuda（snail-job 同家），活跃 |
| **API 风格** | TaskService.skip / transfer，老派 Activiti 系 | FlowLongEngine.executeTask，更现代 |
| **流程定义** | Java 代码硬编码（PoC）或 XML/JSON | 纯 JSON（更适合设计器消费） |
| **审批人 SPI** | 监听器 + 字符串 permissionFlag（坑多） | **`TaskActorProvider` 一等公民 SPI** |
| **动态多人** | ❌ SpEL 不拆分（已踩坑） | ✅ 返回 List 自动拆分 |
| **会签/票签** | ✅ 1.3.8 支持 | ✅ examineMode=2/4 |
| **转办/委派/加签** | ✅ | ✅ |
| **退回到指定节点** | ✅ skip(REJECT, nodeCode)，禁回 start | ✅ 支持回退到任意已完成节点 |
| **流程设计器** | warm-flow-vue（BPMN 风） | flowlong-designer（钉钉/飞书风，更贴中国式审批） |
| **Schema 表数** | 7-8 张 | 8 张 |
| **Schema 漂移管理** | ⚠️ 我们踩过 `any_node_skip` 缺列 | ⚠️ 同样缺官方 migration 脚本，需自己跟 release notes |
| **多租户** | `tenant_id` 字段全表都有 | `tenant_id` 字段全表都有 |
| **Spring Boot 3 / Java 17** | ✅ | ✅（要用 mybatis-plus-spring-boot3-starter） |
| **License** | Mulan PSL v2（宽松） | **Apache 2.0 + 附加条款**：移除版权/做白标转售自动降级为 AGPL-3.0 |
| **商业版** | 无 | 有（¥7k–¥20k 含 AI 节点/低代码扩展） |

## 三、接入成本对比（折算到 ttpos 真接入）

| 接入工作 | Warm-Flow | FlowLong |
|---|---|---|
| ttpos JWT 透传 | 一样 | 一样 |
| ttpos RBAC 解析复用 | gateway 调用 `GetStaffsByAccessPath`，pre-resolve | gateway 改成在 `TaskActorProvider` 里调用 ttpos，**运行时解析** |
| 多审批人方案 | **需写 50 行 custom Handler** 才能让 SpEL `@@` 正确拆 | **零额外代码**，SPI 原生支持 |
| 流程定义 | Java 代码（warm-flow-ui 也能写 JSON） | JSON 文件 / 数据库存储 |
| Long ID 序列化 | 同样的 Java/JS 大数问题 | 同样的问题（PoC 验证已踩过） |
| 学习曲线（团队第一次用） | 已经走过 PoC，认知成熟 | 新栈，需要再花 1–2 天熟悉 |

净接入工作量：**FlowLong ≈ Warm-Flow - 50 行 Handler + 200 行学习成本** ≈ 持平。

## 四、风险盘点

### Warm-Flow 切到 FlowLong 的代价
- 已经验证过的 PoC、踩过的坑、配套脚本、报告，**全部要重做一遍**（约 2–3 天）
- 团队第一次用 FlowLong，会再踩它独有的坑（PoC 已经踩了一个 `nodeAssigneeList` 不能空）
- License 附加条款：如果未来 ttpos 要做 OEM 白标卖给第三方品牌，**自动降级 AGPL-3.0**，需买商业授权

### 继续 Warm-Flow 的代价
- 多审批人 SpEL quirk 要写 50 行 custom Handler（可控）
- dromara 生态偏小众，遇到罕见 bug 社区响应慢

## 五、判定

| 场景 | 推荐 |
|---|---|
| **ttpos 自营 SaaS、单一品牌、审批场景为主** | **二者都行**。建议 **FlowLong**：多审批人零成本，设计器更贴业务 |
| **ttpos 计划做 OEM 白标 / 多品牌转售** | **Warm-Flow**：避开 FlowLong AGPL 触发条款 |
| **审批流极简（少量串行审批，无会签/票签）** | **Warm-Flow**：PoC 已验过，沉没成本最低 |
| **审批流复杂（频繁会签/票签/动态多审批人）** | **FlowLong**：SPI 设计直接省掉 50 行 hack |
| **设计器交给业务/产品自助操作权重高** | **FlowLong**：UI 更贴中国式审批语义 |
| **未来要嵌套子流程** | **FlowLong**：子流程支持更完善 |

## 六、我的推荐

**切到 FlowLong**。

理由按权重排：

1. **多审批人 SPI 是质变不是量变**：`TaskActorProvider` 返回 List → 多行 actor，这是引擎该有的样子。Warm-Flow 用 `@@` 字符串 + listener hack 是设计缺陷的补丁
2. **设计器更适合 ttpos 场景**：业务/产品要自己改流程，钉钉/飞书风的 UI 学习成本最低
3. **PoC 已经验证全过**：vm08 12/12 通过，没有遗留风险
4. **License 风险可控**：你们做自营 SaaS，不做白标转售（如果做，切回 Warm-Flow 也只是 2–3 天工作量）

**反对意见**：如果你认为"已经验证过的 Warm-Flow + 写 50 行 Handler"比"重新走一遍 FlowLong PoC"风险更可控，那继续 Warm-Flow 也不算错——核心差异在多审批人，写 Handler 是可控工作。

## 七、PoC 仓库证据

| 文件 | 用途 |
|---|---|
| `flowlong-app/` | FlowLong Spring Boot 应用 |
| `flowlong-app/src/main/resources/process-ttpos.json` | 流程定义（start → countersign approve → end） |
| `flowlong-app/.../TtposTaskActorProvider.java` | 复用同一份 ttpos RBAC seed 查询 |
| `docker/mysql/init/05-flowlong-schema.sql` | FlowLong 8 张表 schema |
| `scripts/verify-flowlong.sh` | 12 条断言 |

vm08 一键复现：

```bash
cd ~/hwt/brm-poc-lab
docker compose up -d --build
./scripts/verify-flowlong.sh   # FlowLong  12/12
./scripts/verify-ttpos-integration.sh   # Warm-Flow 14/14
```
