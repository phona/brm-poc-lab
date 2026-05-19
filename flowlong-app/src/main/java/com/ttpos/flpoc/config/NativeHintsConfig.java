package com.ttpos.flpoc.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * GraalVM Native Image runtime hints.
 *
 * FlowLong 在运行时把 flw_process.model_content 的 JSON 反序列化成 ProcessModel/NodeModel
 * 树（Jackson + 大量反射），AOT 分析器看不到这些类。需要显式注册。
 *
 * MyBatis-Plus 走动态代理生成 Mapper，spring-boot3-starter 3.5.x+ 自带 hints，
 * 但 FlowLong 的 dao 接口也要单独 register-for-reflection。
 */
@Configuration
@ImportRuntimeHints(NativeHintsConfig.FlowLongHints.class)
public class NativeHintsConfig {

    static class FlowLongHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // FlowLong 模型类（Jackson 反序列化 process.json 需要）
            for (String cls : new String[]{
                    "com.aizuda.bpm.engine.model.ProcessModel",
                    "com.aizuda.bpm.engine.model.NodeModel",
                    "com.aizuda.bpm.engine.model.NodeAssignee",
                    "com.aizuda.bpm.engine.model.ConditionNode",
                    "com.aizuda.bpm.engine.model.NodeExpression",
            }) {
                hints.reflection().registerTypeIfPresent(classLoader, cls,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.DECLARED_FIELDS);
            }

            // FlowLong entity 类（MyBatis 反射 setter）
            for (String cls : new String[]{
                    "com.aizuda.bpm.engine.entity.FlwProcess",
                    "com.aizuda.bpm.engine.entity.FlwInstance",
                    "com.aizuda.bpm.engine.entity.FlwHisInstance",
                    "com.aizuda.bpm.engine.entity.FlwTask",
                    "com.aizuda.bpm.engine.entity.FlwHisTask",
                    "com.aizuda.bpm.engine.entity.FlwTaskActor",
                    "com.aizuda.bpm.engine.entity.FlwHisTaskActor",
                    "com.aizuda.bpm.engine.entity.FlwExtInstance",
            }) {
                hints.reflection().registerTypeIfPresent(classLoader, cls,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.DECLARED_FIELDS);
            }

            // FlowLong DAO/Mapper 接口（MyBatis-Plus 动态代理）
            for (String cls : new String[]{
                    "com.aizuda.bpm.engine.dao.FlwProcessDao",
                    "com.aizuda.bpm.engine.dao.FlwInstanceDao",
                    "com.aizuda.bpm.engine.dao.FlwHisInstanceDao",
                    "com.aizuda.bpm.engine.dao.FlwTaskDao",
                    "com.aizuda.bpm.engine.dao.FlwHisTaskDao",
                    "com.aizuda.bpm.engine.dao.FlwTaskActorDao",
                    "com.aizuda.bpm.engine.dao.FlwHisTaskActorDao",
                    "com.aizuda.bpm.engine.dao.FlwExtInstanceDao",
            }) {
                hints.reflection().registerTypeIfPresent(classLoader, cls,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
                // JDK proxy 需要 TypeReference（String -> TypeReference 工厂方法）
                hints.proxies().registerJdkProxy(TypeReference.of(cls));
            }

            // resources：process JSON 文件（classpath:flows/*.json，flowlong 启动时读）
            hints.resources().registerPattern("flows/.*\\.json");
            hints.resources().registerPattern("process-.*\\.json"); // 历史命名兼容

            // FlowLong 自带的 mybatis mapper xml
            hints.resources().registerPattern("mapper/.*\\.xml");
            hints.resources().registerPattern("com/aizuda/bpm/.*/mapper/.*\\.xml");
        }
    }
}
