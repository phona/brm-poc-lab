package com.ttpos.poc.flow.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttpos.poc.flow.config.GatewayProperties;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.listener.Listener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class WebhookPermissionListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(WebhookPermissionListener.class);
    private static final String PREFIX = "WEBHOOK_RESOLVE:";

    private final RestTemplate restTemplate;
    private final GatewayProperties gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebhookPermissionListener(RestTemplate restTemplate, GatewayProperties gateway) {
        this.restTemplate = restTemplate;
        this.gateway = gateway;
    }

    @Override
    public void notify(ListenerVariable listenerVariable) {
        if (listenerVariable.getNextNodes() == null) {
            return;
        }
        for (Node node : listenerVariable.getNextNodes()) {
            String pf = node.getPermissionFlag();
            if (pf == null || !pf.startsWith(PREFIX)) {
                continue;
            }
            String roleCode = pf.substring(PREFIX.length()).trim();
            Instance ins = listenerVariable.getInstance();
            String businessId = ins == null ? null : ins.getBusinessId();
            Map<String, String> vars = flatten(listenerVariable.getVariable());

            Map<String, Object> body = new HashMap<>();
            body.put("businessId", businessId);
            body.put("roleCode", roleCode);
            body.put("variables", vars);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", gateway.apiKey());

            String url = trimSlash(gateway.baseUrl()) + "/internal/poc/warm-flow/resolve-approver";
            try {
                ResponseEntity<String> res = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
                JsonNode root = objectMapper.readTree(res.getBody());
                int code = root.path("code").asInt(-1);
                if (code != 0) {
                    log.warn("gateway resolve error: {}", res.getBody());
                    continue;
                }
                JsonNode data = root.path("data");
                List<String> ids = objectMapper.convertValue(data.path("userIds"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                if (ids != null && !ids.isEmpty()) {
                    node.setPermissionFlag(String.join(",", ids));
                    // Warm-Flow 1.3.8: assignment listener runs after addTask, so we must sync task.permissionList too
                    if (listenerVariable.getNextTasks() != null) {
                        for (Task task : listenerVariable.getNextTasks()) {
                            if (task != null && node.getNodeCode().equals(task.getNodeCode())) {
                                task.setPermissionList(new ArrayList<>(ids));
                                break;
                            }
                        }
                    }
                    log.info("WEBHOOK resolved role {} -> {} for {}", roleCode, ids, businessId);
                }
                if ("recv_mgr".equals(node.getNodeCode())) {
                    postCcRecord(ins, listenerVariable.getVariable());
                }
            } catch (Exception e) {
                log.error("WEBHOOK resolve failed", e);
            }
        }
    }

    private void postCcRecord(Instance ins, Map<String, Object> vars) {
        if (ins == null || ins.getBusinessId() == null) {
            return;
        }
        List<String> ccIds = extractCcUserIds(vars);
        if (ccIds.isEmpty() && ins.getVariable() != null && !ins.getVariable().isBlank()) {
            try {
                Map<?, ?> m = objectMapper.readValue(ins.getVariable(), Map.class);
                Map<String, Object> vm = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    vm.put(String.valueOf(e.getKey()), e.getValue());
                }
                ccIds = extractCcUserIds(vm);
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (ccIds.isEmpty()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("businessId", ins.getBusinessId());
        body.put("nodeCode", "recv_mgr");
        body.put("timing", "before_recv_assigned");
        body.put("ccUserIds", ccIds);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-KEY", gateway.apiKey());
        String url = trimSlash(gateway.baseUrl()) + "/internal/poc/warm-flow/cc-record";
        try {
            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            log.info("cc-record (webhook phase) for {} count {}", ins.getBusinessId(), ccIds.size());
        } catch (Exception e) {
            log.warn("cc-record failed: {}", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCcUserIds(Map<String, Object> vars) {
        List<String> out = new ArrayList<>();
        if (vars == null) {
            return out;
        }
        Object raw = vars.get("ccUserIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                List<?> parsed = objectMapper.readValue(s, List.class);
                for (Object o : parsed) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
            } catch (Exception ignored) {
                for (String part : s.split(",")) {
                    if (!part.isBlank()) {
                        out.add(part.trim());
                    }
                }
            }
        }
        return out;
    }

    private static String trimSlash(String u) {
        if (u.endsWith("/")) {
            return u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static Map<String, String> flatten(Map<String, Object> variable) {
        Map<String, String> out = new HashMap<>();
        if (variable == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : variable.entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out;
    }
}
