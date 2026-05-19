# 踩坑记录

> 本 PoC 期间踩到的所有"实际遇到、找到根因、定了处置"的坑。
> 按层级分类，每个坑给出：**现象 / 根因 / 早期识别 / 处置**。
> 给后人少踩一次是一次。

---

## 一、引擎选型 / 架构层

### 1. 把 Warm-Flow 当成 FlowLong 的等价替代

**现象**：以为两个国产 BPM 引擎"差不多"，准备直接复用 Warm-Flow PoC 的接入层切 FlowLong。

**根因**：表面相似（都是 Java + MyBatis-Plus + JSON 流程定义 + 类似 BPM 概念），但**核心 SPI 设计哲学不同**：
- Warm-Flow 用字符串拼接 `permissionFlag = "user_a@@user_b"`，多人靠 `@@` 分隔
- FlowLong 用 `TaskActorProvider` SPI，返回 `List<FlwTaskActor>` 是一等公民

**早期识别**：看官方文档"动态审批人"那章，FlowLong 直接给 SPI 接口，Warm-Flow 推荐"监听器 + 字符串"——这是设计代际差。

**处置**：选型阶段**真做端到端 PoC**（不只是 happy path），重点测多审批人/会签/票签这种带多元素集合的场景。

---

### 2. Warm-Flow 1.3.8 SpEL 解析的 `@@` 不拆 `flow_user` 行

**现象**：用 SpEL `permissionFlag = "#{#approvers}"`，运行时 `approvers = "10011@@10012"`，结果只写一行 `flow_user.processed_by = "10011@@10012"`，引擎 checkAuth 不命中。

**根因**：Warm-Flow 1.3.8 **只对定义时字面量** `@@` 做拆分入 `flow_user` 多行；SpEL 运行时解析值不拆。

**早期识别**：
1. 启动流程后查 `SELECT processed_by FROM flow_user WHERE associated=<task_id>`，行数 = 1 但内容包含 `@@`
2. 任何 caller 调 skip 都被拒"无权限"

**处置**：
- 短期：单审批人模式（pre-resolve 一个人）
- 长期：写 custom Handler bean 自己拆分 + INSERT 多行

**这个坑也是切到 FlowLong 的决定性原因之一**。

---

### 3. 错把审批引擎当成 LiteFlow / Temporal 同类

**现象**：候选清单里塞了 LiteFlow 和 Temporal。

**根因**：都叫 "workflow" 但**完全不同领域**：
- LiteFlow：规则编排，Java 组件串联，无人工任务
- Temporal：跨服务长事务编排，无 BPM 概念

**早期识别**：找有没有"任务/待办/审批人"这种概念。没有 = 不是 BPM。

**处置**：决策文档明确分类，写清"为什么不选"。见 `docs/decision-flowlong.md`。

---

### 4. Camunda 7 CE 已 EOL（2025-10）

**现象**：差点把 Camunda 7 列为"行业标准"备胎。

**根因**：[Camunda 官方 2025 年宣布 CE 版 EOL](https://forum.camunda.io/t/important-update-camunda-7-community-edition-end-of-life-announced/50921)，仅 EE 续到 2030。中文社区还在用老资料。

**早期识别**：选型时翻官方 release notes 和 forum 公告，不只看"知乎/CSDN 文章是不是多"。

**处置**：决策文档显式标注 EOL 风险。

---

### 5. FlowLong License 附加条款（OEM 触发 AGPL）

**现象**：Apache 2.0 看上去开放，实际有附加条款。

**根因**：[aizuda 官方文档](https://doc.flowlong.com/docs/authorization)：移除版权 / 改名做白标 / 转售给第三方 SaaS，自动降级为 AGPL-3.0，需买商业授权（¥7k-¥20k）。

**早期识别**：选 aizuda 系开源项目（snail-job 同家）都要看附加条款。

**处置**：ttpos 不做 OEM 白标 = 不触发，文档明示退出条件。

---

## 二、ttpos 集成层

### 6. 把 ttpos DB 直连塞进 Java 引擎服务

**现象**：PoC 早期 `TtposTaskActorProvider.java` 90 行 JDBC 代码直接查 `shop1001.ttpos_staff`。

**根因**：图省事——没真跑 ttpos main 时只能这样。但违反了之前定的架构原则"Java 引擎对 ttpos 业务零认知"。

**早期识别**：Code review 时看 Java 代码有没有出现 ttpos 表名（`ttpos_access` / `shop{n}` 等）。

**处置**：refactor 成 `TaskActorProvider` 只从 `execution.getArgs()` 读 `approverIds` List，caller (Go) 负责解析。`cf5c782` 提交。

**教训**：**PoC 的"快速跑通"和"按生产架构跑通"是两件事**。一开始就按生产形态走，省得后面 refactor。

---

### 7. JSON 大整数精度丢失（Java long → JS number）

**现象**：gateway 返回的 instanceId 末 2 位变 0（`...02` → `...00`），下游用这个 ID 查不到任何记录。

**根因**：Go `encoding/json` 解 `map[string]any` 数字默认走 float64，Java snowflake long（~2×10¹⁸）超过 float64 安全整数范围（2^53 ≈ 9×10¹⁵）。

**早期识别**：业务流"启动成功 → 立刻查待办 → 找不到任务"，且数据库里 instance 实际存在但 ID 末尾不同。

**处置**：`json.NewDecoder(...).UseNumber()` 保留原始数字字符串。提交 `1d4eac5`。

**通用提醒**：**Java long ID 跨 JSON 边界全部当字符串处理**（Java 侧 `@JsonSerialize(using=ToStringSerializer.class)`，Flutter 端用 BigInt 解析）。Flutter / 浏览器 / 任何 JS 客户端都有同样问题。

---

### 8. Warm-Flow `flow_node.any_node_skip` 列缺失

**现象**：`/api/flow/start` 返回 500，错误 `Unknown column 'any_node_skip' in 'field list'`。

**根因**：seed schema (`docker/mysql/init/03-warm-flow-all.sql`) 是 1.3.3 时代的，1.3.8 SELECT 多查了一列。

**早期识别**：升级 BPM 引擎大版本时翻 release notes，看 schema 变更。

**处置**：seed SQL 补列。提交 `1d4eac5`。

**教训**：用 Warm-Flow / FlowLong 这类**不提供官方 migration 脚本**的引擎，每次升版要自己 diff schema。

---

## 三、FlowLong 引擎使用

### 9. process JSON `nodeAssigneeList` 不能为空

**现象**：调 `/flow/start` 返回 `FlowLongException: process nodeModel config error`。

**根因**：`ModelHelper.checkNodeModel` 对 `setType=1/3/4`（指定成员/角色/发起人选）**要求 `nodeAssigneeList` 非空**，否则 fail。

**早期识别**：FlowLong 文档没明说，要看源码 `flowlong-core/src/main/java/com/aizuda/bpm/engine/model/ModelHelper.java:398`。

**处置**：用 `TaskActorProvider` 时也塞一个 placeholder：
```json
"nodeAssigneeList": [{"id":"placeholder","name":"runtime resolved"}]
```
Provider 会在运行时覆盖。提交 `37cd7fa`。

---

### 10. FlowLong `deployByResource(..., false)` 已存在不更新

**现象**：改了 process JSON 重启服务，但流程定义还是老的。

**根因**：第三个参数 `repeat=false` 时，**如果已存在则跳过 deploy**。

**早期识别**：日志有 `published xxx defId=...`，但查 `flw_process` 表 `model_content` 是旧内容。

**处置**：开发 / PoC 阶段用 `repeat=true`，每次启动覆盖发新版本（FlowLong 内置版本管理，老实例继续走老版本）。提交 `37cd7fa`。

---

### 11. FlowLong 8 张表，不是 7 张

**现象**：以为 7 张表（`flw_process / flw_instance / flw_his_instance / flw_task / flw_his_task / flw_task_actor / flw_his_task_actor`），漏了 `flw_ext_instance`。

**根因**：`flw_ext_instance` 不是核心 BPM 表，是给定义快照用的，容易漏。

**处置**：完整 schema 文件 `docker/mysql/init/05-flowlong-schema.sql` 含全部 8 张表 + 外键。

---

## 四、K8s / 部署层

### 12. k3s containerd 跟 docker daemon 不共享镜像

**现象**：本地 `docker build` 后 K8s pod 报 `ImagePullBackOff` 或用了旧镜像。

**根因**：k3s 用 containerd，docker daemon 是另一套。`docker images` 看得见的镜像，k3s 看不见。

**处置**：
```bash
docker save flowlong-app:0.1.1 | k3s ctr images import -
```
配合 chart `imagePullPolicy: Never` 让 k3s 走本地。

**注意**：换 image tag 也要重新 import，k3s 不会自动同步。

---

### 13. `imagePullPolicy: Never` + 同 tag 滚动不生效

**现象**：`docker save | k3s ctr images import` 导入了新镜像，但 helm upgrade 后 pod 还是用老的。

**根因**：tag 没变（如 `native-latest`），k3s 认为没变化不重新调度；即使触发 rollout，containerd 也会用 cache 的 image ID。

**处置**：
- 短期 PoC：每次用唯一 tag（如 SHA），别用 `latest`
- 长期生产：推 registry + `pullPolicy: IfNotPresent`，让镜像 digest 驱动

---

### 14. distroless/cc-debian12 不含 libz

**现象**：GraalVM native binary 在 distroless 启动报 `libz.so.1: cannot open shared object file`。

**根因**：误以为 `distroless/cc` = "C runtime 全套"，实际只含 `libc / libstdc++ / libgcc-s1 / libgomp1`，**没 zlib**。

**早期识别**：`docker history` 看 layer 名称（找 `zlib1g` / `libz`），或直接尝试 `ldd binary` 看依赖。

**处置**：换 `debian:12-slim` + 显式 `apt install zlib1g`。提交 `2b83ac3`。

**通用规则**：GraalVM native binary 跑 distroless 之前，先 `ldd` 看依赖，对照 distroless 文档确认 base 包含所有 `.so` 文件。

---

### 15. K3s 单节点用 `podAntiAffinity` 浪费

**现象**：Helm chart values 写了 `podAntiAffinity` preferred，单节点 k3s 多副本时无效。

**根因**：`preferredDuringSchedulingIgnoredDuringExecution` 是 soft，没节点可散就忽略。lab 单节点根本没用。

**处置**：lab values 显式覆盖 `affinity: {}`。提交 `values-vm04.yaml`。

---

### 16. MySQL service 有但无 endpoint（之前部署的没起来）

**现象**：vm04 上 `ttpos-arch-lab-cloud-mysql` service 存在，想复用，但 pod 不在线，endpoint 空。

**根因**：之前的 helm release 部分组件未启动。Service 是声明，不代表后端 pod 在跑。

**早期识别**：`kubectl get endpoints <svc>` 看 ENDPOINTS 列。空 = 没后端。

**处置**：不依赖别人的部署，单独起自己的 MySQL（资源有限就 256Mi）。

---

## 五、GraalVM Native（完整失败案例）

> 详细见 `docs/native-image-attempt.md`。本节只列**真正卡死的根因坑**。

### 17. Spring AOT + MyBatis-Plus FactoryBean 不兼容

**现象**：native binary 启动到 Spring context refresh 时 fail：
```
Error creating bean with name 'flwHisTaskActorMapper':
No qualifying bean of type 'java.lang.Class<?>' available
```
所有 8 个 FlowLong Mapper 注入失败。

**根因**：Spring AOT 处理 MyBatis-Plus 的 `MapperFactoryBean<T>` 时不知道怎么注入构造器的 `Class<T> mapperInterface` 参数。这是**上游已知不兼容**（[mybatis-plus#5594](https://github.com/baomidou/mybatis-plus/issues/5594)），不是我们配置错。

**早期识别**：选型时确认整个栈每一层的 Spring Boot 3 Native 兼容矩阵。**MyBatis-Plus 出现 = 红灯**。

**处置**：**主动放弃**。要修需要：
- 自定义 AOT BeanPostProcessor（3-5 天）
- 或手动 @Bean 注册每个 Mapper（脆弱，FlowLong 升级要重做）
- 或 fork FlowLong 改 DAO 实现方式（绝对不要）

**教训**：**所有 Java 库的 Spring Native 兼容性，不要看"理论上支持"，看真实跑通的同栈案例**。MyBatis-Plus 官方说支持 native，但实际跟 Spring Boot 3 AOT 还是有缝。

---

### 18. MyBatis logger NPE in native

**现象**：native binary 启动到 MyBatis init 时 `NullPointerException` in `LogFactory.getLog`。

**根因**：MyBatis 用反射 try/catch 链探测各种 logger 实现，native 模式没注册就 NPE。

**早期识别**：栈顶有 `org.apache.ibatis.logging.LogFactory.getLog`。

**处置**：两路冗余
1. `application.yml` 显式 `mybatis-plus.configuration.log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl`
2. RuntimeHints 注册全部 logger candidate 类

提交 `3be3d61`。

---

### 19. RuntimeHints API `proxiedInterfaces(String)` 不存在

**现象**：第一次 native build 编译 fail：`no suitable method found for proxiedInterfaces(java.lang.String)`。

**根因**：Spring AOT API 只接受 `Class<?>` 或 `TypeReference`，不直接接 String。

**处置**：`TypeReference.of("com.aizuda....Dao")` 工厂方法包装。提交 `ffccede`。

---

### 20. Dockerfile.native 在容器内重跑 mvn 浪费

**现象**：CI workflow 已经 mvn 编出 binary，Dockerfile.native 又跑一遍 mvn build native——重复 + 失败。

**根因**：Dockerfile 是 multi-stage 自带 build，没考虑 CI 已经准备好 artifact。

**处置**：Dockerfile.native 改成 runtime-only，只 COPY workflow 编好的 `target/flowlong-app`。提交 `669cf7e`。

---

## 六、可观测性

### 21. 不要在应用里耦合云厂商 SDK

**现象**：差点给 flowlong-app 接 Stackdriver Micrometer Registry + GCP Trace Exporter。

**根因**：那样应用代码绑定 GCP，未来切平台 / 私有化客户机房就拆不开。

**处置**：应用只输出标准信号——**JSON 日志到 stdout + Prometheus 标准格式 `/actuator/prometheus`**。谁来消费由部署侧决定。GCP Ops Agent / Loki / Prometheus 都能吃。

提交 `636f2b5`。

---

## 七、总结沉淀的判断原则

| 类型 | 原则 |
|---|---|
| **选型** | 真做 PoC 跑业务必经路径，不是 happy path。多审批人 / 多租户 / 异常路径必须真测 |
| **架构** | 应用对外输出标准信号，平台特定 SDK 全部留在部署/基础设施层 |
| **集成** | 跨服务边界用 ID 字符串，长整数过 JSON 全部强制字符串化 |
| **K8s** | lab 单节点配置 ≠ 生产配置，affinity / probe / PDB 都要看场景；image 同步走 registry + digest，不要靠 docker save |
| **Native / AOT** | 看 Spring Native 兼容性"理论支持" + "真实同栈案例"，缺一票否决 |
| **失败设置上限** | 任何探索性投入预先定上限（次数 / 时间），到了就主动止损，沉淀 lessons learned |
| **PoC vs 生产** | "PoC 快速跑通" 写出来的代码大概率是"生产架构反例"，refactor 成本预先认知 |

---

*持续追加：下次踩到新坑随时往这里加，按"现象 / 根因 / 早期识别 / 处置"四段式写*
