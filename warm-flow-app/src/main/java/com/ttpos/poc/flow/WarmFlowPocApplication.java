package com.ttpos.poc.flow;

import com.ttpos.poc.flow.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class WarmFlowPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(WarmFlowPocApplication.class, args);
    }
}
