package com.ttpos.poc.flow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "poc.gateway")
public record GatewayProperties(String baseUrl, String apiKey) {}
