package com.ttpos.poc.flow.ext;

import com.ttpos.poc.flow.config.GatewayProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class FlowGatewayNotifier {

    private final RestTemplate restTemplate;
    private final GatewayProperties gateway;

    public FlowGatewayNotifier(RestTemplate restTemplate, GatewayProperties gateway) {
        this.restTemplate = restTemplate;
        this.gateway = gateway;
    }

    public void notifyFinished(String businessId, String orderStatus) {
        if (businessId == null || businessId.isBlank()) {
            return;
        }
        String url = trimSlash(gateway.baseUrl()) + "/internal/poc/warm-flow/flow-finished";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-KEY", gateway.apiKey());
        Map<String, Object> body = Map.of(
                "businessId", businessId,
                "status", orderStatus
        );
        restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    private static String trimSlash(String u) {
        if (u.endsWith("/")) {
            return u.substring(0, u.length() - 1);
        }
        return u;
    }
}
