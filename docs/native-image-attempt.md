# flowlong-app GraalVM Native Image 尝试报告

> 状态：**主动放弃** · 日期：2026-05-19 · 决策：暂时不上 native，保留 JVM 镜像

## 一、结论先行

**Native 镜像在 ttpos-lab 当前阶段不可行**。

根因：**MyBatis-Plus + Spring AOT 在 FactoryBean 注入上有上游已知不兼容**（[mybatis-plus#5594](https://github.com/baomidou/mybatis-plus/issues/5594)），FlowLong 用了 MyBatis-Plus，所以连带受影响。

绕过这个需要写自定义 AOT BeanPostProcessor 或手动注册每个 Mapper，**短期投入远超 native 化带来的运行时收益**。

## 二、6 次 CI 迭代过程

| iter | commit | 问题 | 解法 |
|---|---|---|---|
| 1 | `80682ea` | `proxiedInterfaces(String)` API 用错 | 改用 `TypeReference.of(String)` |
| 2 | `ffccede` | Dockerfile 里再跑一遍 mvn 浪费 + 失败 | runtime-only Dockerfile，binary 由 CI workflow 编 |
| 3 | `669cf7e` | distroless/base 缺 libz | 换 distroless/cc |
| 4 | `aff0d55` | distroless/cc 也缺 libz | 换 debian:12-slim 显式装 zlib1g |
| 5 | `2b83ac3` | MyBatis logger 反射探测 NPE | 显式配 `mybatis-plus.configuration.log-impl` + RuntimeHints 注册 logger 类 |
| **6** | `3be3d61` | **Spring AOT 处理 MapperFactoryBean 失败，所有 8 个 FlowLong DAO 注入失败** | **未解，主动停止** |

## 三、客观数据

虽然没跑通，但中间产物证明了一些事实：

| 指标 | JVM 版本（当前生产用）| Native 版本（iter 4 镜像能起但 5/6 业务起不来）|
|---|---|---|
| 镜像大小 | 480 MB（含 JRE）| 177 MB（debian-slim + 95MB binary） |
| binary 大小 | jar 30 MB + jre ~180 MB | binary 95 MB |
| **理论运行时内存** | 240 MB | ~80 MB（推算，未实测）|
| **理论启动时间** | 48 秒 | ~1-3 秒（推算，未实测）|
| CI 构建时间 | 1-2 分钟 | 3-4 分钟 |
| GraalVM build 内存峰值 | — | ~3 GB（ubuntu-latest 7GB 够）|

## 四、什么时候可以重试

满足任一条件时值得重试：

| 触发条件 | 来源 |
|---|---|
| MyBatis-Plus 官方修复 Spring AOT 兼容 | watch [#5594](https://github.com/baomidou/mybatis-plus/issues/5594) |
| FlowLong 切到 MyBatis Native 友好的 DAO 写法（如 @Mapper + @MapperScan 显式 factoryBean） | watch [FlowLong releases](https://github.com/aizuda/flowlong/releases) |
| Spring Boot 升级到 4.x（AOT 支持持续改进）| 2026 Q4 ETA |
| 私有化客户硬性要求小镜像（< 100MB）| 业务驱动，可以投入 1-2 周专人优化 |
| 团队招到熟悉 GraalVM Native 的人 | — |

## 五、当前选择：继续用 JVM 镜像

| 维度 | 现状（JVM）| 影响 |
|---|---|---|
| 镜像大小 | 480 MB | 私有化交付时一次性多传 300MB，可接受 |
| 启动时间 | 48 秒 | 滚动升级窗口长一点，业务可接受 |
| 运行时内存 | 240 MB | vm04 lab 略紧，生产 2Gi 容器充裕 |
| 维护负担 | **极低**——团队不需要懂 GraalVM | 收益最大的一项 |

## 六、留下的资产（万一上游修了能复用）

| 文件 | 路径 | 用途 |
|---|---|---|
| Native pom profile | `flowlong-app/pom.xml` | 激活 native build |
| RuntimeHints 注册 | `flowlong-app/src/main/java/com/ttpos/flpoc/config/NativeHintsConfig.java` | FlowLong + MyBatis logger 反射注册（部分完成） |
| Native Dockerfile | `flowlong-app/Dockerfile.native` | debian-slim runtime |
| GitHub Actions | `.github/workflows/build-flowlong-native.yml` | 公开仓库免费 CI，触发即跑 |

未来一个 commit 解 MyBatis-Plus 问题，整套流水线立刻能继续。

## 七、给后人的提醒

如果以后有人想再试 native，**别从头来**：

1. 先看 [mybatis-plus#5594](https://github.com/baomidou/mybatis-plus/issues/5594) 是否有进展
2. 如果有，直接 `git checkout 3be3d61` 然后 push 触发 CI，可能就过了
3. 如果 still broken，估算自己写 BeanPostProcessor 的成本——通常需要 3-5 天熟悉 Spring AOT 内部
4. 评估的真问题不是"能不能做"，是"做完后维护成本"——MyBatis-Plus / FlowLong 每升级一次，custom AOT 都可能要重做

## 八、CI 流水线状态

GitHub Actions workflow `Build flowlong-app native image` 保留：

- 触发：`flowlong-app/**` 或自身改动 push 到 main
- 当前会跑通 native 构建步骤 + 镜像 push（iter 6 后镜像启动 fail 但 CI 报 success——因为 smoke test 故意 `set +e`）
- 镜像 tag：`ghcr.io/phona/flowlong-app:native-<commit_sha>`

**注意**：当前推出去的镜像**不能用于生产**（业务起不来），仅作 artifact 留底。生产继续用 JVM 镜像 `flowlong-app:0.1.1`。
