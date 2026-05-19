package com.ttpos.flpoc.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 发送器：把 TaskEvent / InstanceEvent 转成 HTTP POST 推给 ttpos main。
 * 至少一次投递（at-least-once）：失败时按指数退避重试，要求 caller 用 eventId 去重。
 * 异步发送，不阻塞引擎主流程。
 */
@Component
@EnableAsync
@EnableConfigurationProperties(WebhookSender.Properties.class)
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final Properties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WebhookSender(Properties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(props.timeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(props.timeoutSeconds).toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    @Bean(name = "webhookExecutor")
    public TaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("webhook-");
        exec.initialize();
        return exec;
    }

    @Async("webhookExecutor")
    public void send(Map<String, Object> payload) {
        if (!props.enabled || props.callbackUrl == null || props.callbackUrl.isBlank()) {
            return;
        }
        // 注入元字段
        if (!payload.containsKey("eventId")) {
            payload.put("eventId", UUID.randomUUID().toString());
        }
        payload.put("timestamp", java.time.Instant.now().toString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (props.signatureHeader != null && props.signatureValue != null) {
            headers.set(props.signatureHeader, props.signatureValue);
        }

        Exception last = null;
        for (int attempt = 1; attempt <= props.retryMax; attempt++) {
            try {
                String body = objectMapper.writeValueAsString(payload);
                ResponseEntity<String> resp = restTemplate.postForEntity(
                        props.callbackUrl,
                        new HttpEntity<>(body, headers),
                        String.class);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    log.info("webhook delivered eventType={} eventId={} attempt={}",
                            payload.get("eventType"), payload.get("eventId"), attempt);
                    return;
                }
                log.warn("webhook non-2xx eventType={} status={} attempt={}",
                        payload.get("eventType"), resp.getStatusCode(), attempt);
            } catch (Exception e) {
                last = e;
                log.warn("webhook attempt {} failed: {}", attempt, e.getMessage());
            }
            try {
                Thread.sleep((long) (props.retryBaseDelayMs * Math.pow(2, attempt - 1)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.error("webhook give up after {} attempts eventType={} lastErr={}",
                props.retryMax, payload.get("eventType"), last == null ? "non-2xx" : last.getMessage());
    }

    @ConfigurationProperties(prefix = "flowlong.webhook")
    public static class Properties {
        public boolean enabled = false;
        public String callbackUrl;
        public int timeoutSeconds = 5;
        public int retryMax = 3;
        public long retryBaseDelayMs = 500;
        /** 可选签名 header（如 X-Webhook-Signature） */
        public String signatureHeader;
        public String signatureValue;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCallbackUrl() { return callbackUrl; }
        public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getRetryMax() { return retryMax; }
        public void setRetryMax(int retryMax) { this.retryMax = retryMax; }
        public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
        public void setRetryBaseDelayMs(long retryBaseDelayMs) { this.retryBaseDelayMs = retryBaseDelayMs; }
        public String getSignatureHeader() { return signatureHeader; }
        public void setSignatureHeader(String signatureHeader) { this.signatureHeader = signatureHeader; }
        public String getSignatureValue() { return signatureValue; }
        public void setSignatureValue(String signatureValue) { this.signatureValue = signatureValue; }
    }
}
