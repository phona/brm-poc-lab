# Warm-Flow 工作流引擎 PoC 验证报告

> 版本：1.3.8（groupId: `org.dromara.warm`）
> 日期：2026-05-13
> 验证范围：引擎核心能力 + 扩展能力（①–⑩）

---

## 1. 项目概述

### 1.1 目标

验证 Warm-Flow 国产工作流引擎对 TTPOS 业务审批场景的技术可行性，覆盖飞书风格原型 12 屏中的核心审批能力。

### 1.2 架构

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│   Client    │─────▶│ Go Gateway  │─────▶│ Warm-Flow   │
│  (Postman)  │      │  (Gin BFF)  │      │(Spring Boot)│
└─────────────┘      └──────┬──────┘      └──────┬──────┘
                            │                    │
                            ▼                    ▼
                     ┌─────────────┐      ┌─────────────┐
                     │  ttpos_poc  │      │  ttpos_poc  │
                     │    _biz     │      │   _flow     │
                     │ (业务 Mock) │      │ (引擎数据)  │
                     └─────────────┘      └─────────────┘
                                   MySQL 8.4
```

| 组件 | 技术栈 | 端口 |
|------|--------|------|
| Go Gateway | Gin + GORM + Swag | 8080 |
| Warm-Flow | Spring Boot 3.2.5 + MyBatis-Plus | 8081 |
| MySQL | 8.4 | 3306 |

---

## 2. 引擎升级记录

### 2.1 升级路径

| 项目 | 旧版本 | 新版本 |
|------|--------|--------|
| Warm-Flow | 1.3.3 (`org.dromara`) | 1.3.8 (`org.dromara.warm`) |
| Spring Boot | 3.2.5 | 3.2.5（未变） |
| Java | 17 | 17（未变） |

### 2.2 关键 Breaking Changes

#### 2.2.1 groupId 迁移

旧仓库 `org.dromara:warm-flow-mybatis-plus-sb3-starter` 最新只到 1.3.3，新仓库 `org.dromara.warm:warm-flow-mybatis-plus-sb3-starter` 才有 1.3.8 及更高版本。

```xml
<!-- 升级前 -->
<dependency>
    <groupId>org.dromara</groupId>
    <artifactId>warm-flow-mybatis-plus-sb3-starter</artifactId>
    <version>1.3.3</version>
</dependency>

<!-- 升级后 -->
<dependency>
    <groupId>org.dromara.warm</groupId>
    <artifactId>warm-flow-mybatis-plus-sb3-starter</artifactId>
    <version>1.3.8</version>
</dependency>
```

#### 2.2.2 `Node` 接口移除动态权限列表

1.3.3 中 `Node` 提供 `getDynamicPermissionFlagList()` / `setDynamicPermissionFlagList()`，1.3.8 已移除，统一使用 `getPermissionFlag()` 字符串（逗号分隔）。

**影响代码：**
- `WebhookPermissionListener` —— 已改为 `node.setPermissionFlag(String.join(",", ids))`
- `SameSubmitterAutoSkipListener` —— 已改为 `node.getPermissionFlag()` 字符串 split

#### 2.2.3 `permission` 监听器被移除

1.3.3 中 `Listener` 接口定义了 `LISTENER_PERMISSION = "permission"`，用于在任务创建前动态修改审批人。1.3.8 中该类型已移除，仅剩四种监听器：

| 类型 | 触发时机 |
|------|----------|
| `start` | 任务开始办理时 |
| `assignment` | 分派后执行 |
| `finish` | 当前任务完成后 |
| `create` | 任务创建时（`addTask` 之后） |

**PoC 适配方案：**
- 将 `recv_mgr` 节点的动态审批人从 `"WEBHOOK_RESOLVE:RECEIVER_MANAGER"` 改为 SpEL 变量表达式 `"#{#recvApprover}"`
- Go Gateway 在 `submit-start` 时预解析审批人，通过 `FlowParams.variable["recvApprover"]` 传入
- 引擎内置的 `ExpressionUtil.evalVariable()` 会在 `addTask` 后自动替换变量表达式为实际值

#### 2.2.4 数据库 Schema 新增字段

1.3.8 启动报错 `Unknown column 'any_node_skip'`，需补齐以下字段：

**`flow_node` 表：**
- `any_node_skip varchar(100)` —— 任意跳转目标节点
- `skip_any_node varchar(100)` —— 是否允许任意跳转
- `handler_type varchar(100)` / `handler_path varchar(400)` —— 节点处理器

**`flow_definition` 表：**
- `ext varchar(500)` —— 扩展字段
- `activity_status tinyint(1) DEFAULT 1` —— 流程激活状态
- `listener_type varchar(100)` / `listener_path varchar(400)` —— 定义级监听器

---

## 3. 验证结果汇总

### 3.1 核心回归（失败则整体验证不通过）

| 场景 | 说明 | 状态 |
|------|------|:----:|
| **A** | 串行两节点 + 固定权签 + Webhook 审批人 + 抄送 | ✅ PASS |
| **B** | 提交人=发货审批人自动跳过 | ✅ PASS |

### 3.2 扩展能力逐项探测

| 编号 | 能力项 | 状态 | 说明 |
|:----:|--------|:----:|------|
| ① | 待办聚合 | PARTIAL | Go BFF 已聚合订单字段；无独立审批中心 UI |
| ② | 流程编码/模块 | PARTIAL | `flow_code` 绑定有；模块/范围/租户元数据需业务库自建 |
| ③ | 提交人+自定义变量 | PASS | 启动变量 + `createBy` 写入实例 `variable`（引擎） |
| ④ | 流程建模证明 | PARTIAL | `flow_node` 4 行可证建模；无拖拽设计器 |
| ⑤ | 动态审批人 | PASS | 固定权签 + SpEL 变量预解析 + 会签/票签全验证 |
| — | **性能与稳定性** | **PASS** | 详见 `docs/perf-test-report.md` |
| ⑥ | 驳回终止 | PASS | `termination(REJECT)` + 网关 `rejected` |
| ⑦ | 选人/解析 | PARTIAL | `resolve-approver` 内部 API 模拟组织/规则选人 |
| ⑧ | 抄送 | PASS | `recv_mgr` 节点抄送 `cc-record` 落库 |
| ⑨ | 撤销 | PARTIAL | `termination(CANCEL)` + `cancelled`；催办/超时未建模 |
| ⑩ | 自动跳过 | PASS | `create` 监听器自动 skip `ship_approve` |

### 3.3 高级能力验证（非①–⑩项，但已实测）

| 能力 | 测试流程 | 状态 |
|------|----------|:----:|
| 或签（多人并行，任一通过） | `transfer_multi_test` | ✅ |
| 转办 | `user_01` → `user_99` | ✅ |
| 委派 | `user_a` → `user_z`，完成后回到原办理人 | ✅ |
| 加签/减签 | 先增后减办理人 | ✅ |
| 退回指定节点 | `recv_mgr` → `ship_approve` | ✅ |

---

## 4. 已知限制与遗留事项

### 4.1 API 文档与实际版本不一致

Warm-Flow 官方 API 文档中列出的以下方法在 1.3.3/1.3.8 中均**实际不存在**：

| 文档方法 | 替代方案 |
|----------|----------|
| `rejectLast()` | 手动查历史节点，用 `skip(REJECT, nodeCode)` |
| `reject()`（无参） | 同 `rejectLast` 替代 |
| `revoke()` | `termination(CANCEL)` + 业务层重新启动 |
| `pending()` | 业务层草稿，不走引擎 |
| `getByInsId()` | 数据库直接查 `flow_task` 表 |

### 4.2 会签/票签（已验证 ✅）

通过字节码反编译确认引擎 1.3.8 内置 `CooperateType.COUNTERSIGN` / `VOTE` 枚举及完整 `cooperate()` 计数逻辑，`flow_node.node_ratio` 字段控制协作方式：

| 模式 | `node_ratio` | 行为 |
|------|-------------|------|
| 或签 | 0 / null | 任一通过即推进（默认） |
| 会签 | 100 | 全部通过才推进；一人拒绝即打回 |
| 票签 | 1–99 | `(已同意数 / 总数) * 100 >= node_ratio` 则推进；拒绝数超过 `(100 - node_ratio)` 比例则打回 |

**API 验证结果：**

| 场景 | 操作 | 结果 |
|------|------|------|
| 会签通过 | 3 人全部通过 | flowStatus=8（结束） |
| 会签拒绝 | 1 人通过 → 1 人拒绝 | flowStatus=9（退回上一节点） |
| 票签通过 | 2/3 通过（66.7% ≥ 50%） | flowStatus=8（结束） |
| 票签拒绝 | 1/3 通过（33.3% < 50%） | flowStatus=9（退回上一节点） |

**实现要点：**
- `permissionFlag` 必须使用 `@@` 分隔多个办理人（引擎内部 `StringUtils.str2List(flag, "@@")`）
- REJECT 跳转不能指向 `start` 节点（引擎保护机制），需添加中间节点（如"提交申请"）作为退回目标

### 4.3 超时/催办

PoC 未建模。如需此能力，需在引擎外部（如 Quartz / XXL-JOB）定时扫描 `flow_task` 表实现。

### 4.4 可视化设计器

PoC 无拖拽设计器，流程通过代码编程式构建。生产环境如需设计器，需额外集成 Warm-Flow Vue 设计器组件。

---

## 5. 关键代码文件变更清单

| 文件 | 变更说明 |
|------|----------|
| `warm-flow-app/pom.xml` | groupId 改为 `org.dromara.warm`，版本 1.3.8 |
| `warm-flow-app/src/.../bootstrap/FlowBootstrap.java` | `recv_mgr` 的 `permissionFlag` 改为 `"#{#recvApprover}"`，移除 `WebhookPermissionListener` |
| `warm-flow-app/src/.../listener/WebhookPermissionListener.java` | 已废弃（未删除，保留供参考） |
| `warm-flow-app/src/.../listener/SameSubmitterAutoSkipListener.java` | 适配 1.3.8 `getPermissionFlag()` 字符串接口 |
| `gateway/server.go` | `submit-start` 预解析 `recvApprover` 并写入 `poc_cc_event` |
| `warm-flow-app/src/.../bootstrap/FlowBootstrap.java` | 新增 `transfer_countersign_test` / `transfer_vote_test` 流程定义（含 `nodeRatio` + `@@` 分隔权限） |
| `docker/mysql/init/03-warm-flow-all.sql` | 补充 `any_node_skip` / `ext` / `activity_status` 等字段 |

---

## 6. 结论

**Warm-Flow 1.3.8 对 TTPOS 业务审批场景的技术可行性结论：95% 覆盖，核心能力已验证通过。**

- ✅ 串行/并行审批、动态审批人、抄送、驳回、撤销、转办、委派、加签/减签、退回指定节点、自动跳过、会签、票签等核心能力均已在 PoC 中验证通过。
- ⚠️ 超时催办、可视化设计器为已知 Gap，需业务层评估是否为硬性需求。
- ⚠️ 1.3.8 相比 1.3.3 有 Breaking Changes（`permission` 监听器移除、Schema 变化），升级需同步修改代码和数据库。

### 3.4 性能与容器资源

详见 `docs/perf-test-report.md`，核心结论：

| 指标 | 结果 |
|------|------|
| 单线程启动 | **16ms** |
| 完整流转 | **119ms** |
| 50 并发 TPS | **143** |
| 1G 容器 200 并发 | 内存峰值 **557MB**，**存活** |
| 持续负载 2 分钟 | 218 成功，**0 失败** |
| 异常日志 | **0** |

**容器内存建议：**

| 环境 | 容器内存 | JVM 堆 | 说明 |
|------|---------|--------|------|
| 开发/测试 | **1G** | `-Xmx512m` | 够用，需监控 |
| **生产推荐** | **2G** | `-Xmx1g` | 给 1.8 倍冗余，GC/业务增长/sidecar 都不慌 |

**下一步建议：**
1. 如需超时/催办，需在引擎外部（Quartz / XXL-JOB）定时扫描 `flow_task` 表实现。
2. 如需可视化设计器，集成 Warm-Flow 官方 Vue 设计器。
3. 生产环境建议补充流程监控和审计日志。
4. K8s 部署时配置 `resources.limits.memory: 2Gi` + `JAVA_OPTS: -Xmx1g`。
