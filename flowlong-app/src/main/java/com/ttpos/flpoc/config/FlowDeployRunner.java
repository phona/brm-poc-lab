package com.ttpos.flpoc.config;

import com.aizuda.bpm.engine.FlowLongEngine;
import com.aizuda.bpm.engine.core.FlowCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowDeployRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowDeployRunner.class);
    public static final FlowCreator SYSTEM = FlowCreator.of("system", "系统");

    @Bean
    public ApplicationRunner deploy(FlowLongEngine engine) {
        return args -> {
            try {
                // repeat=true so model changes during PoC iteration get re-published as a new version
                Long id = engine.processService()
                        .deployByResource("process-ttpos.json", SYSTEM, true);
                log.info("[FlowLong] deployed ttpos_transfer_test processId={}", id);
            } catch (Exception e) {
                log.error("[FlowLong] deploy failed", e);
            }
        };
    }
}
