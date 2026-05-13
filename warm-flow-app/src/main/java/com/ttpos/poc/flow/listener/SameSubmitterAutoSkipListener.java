package com.ttpos.poc.flow.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.entity.User;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.listener.Listener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PoC: ⑩「审批人=提交人自动跳过」——在节点任务派生后检测并自动 PASS（飞书同类规则的极简版）。
 */
@Component
public class SameSubmitterAutoSkipListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(SameSubmitterAutoSkipListener.class);
    private static final String SHIP = "ship_approve";

    private final TaskService taskService;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SameSubmitterAutoSkipListener(TaskService taskService, UserService userService) {
        this.taskService = taskService;
        this.userService = userService;
    }

    @Override
    public void notify(ListenerVariable listenerVariable) {
        Task task = listenerVariable.getTask();
        if (task == null && listenerVariable.getNextTasks() != null) {
            for (Task t : listenerVariable.getNextTasks()) {
                if (t != null && t.getNodeCode() != null && SHIP.equals(t.getNodeCode())) {
                    task = t;
                    break;
                }
            }
        }
        if (task == null || task.getNodeCode() == null || !SHIP.equals(task.getNodeCode())) {
            return;
        }
        Map<String, Object> vars = listenerVariable.getVariable();
        if (vars == null) {
            vars = new HashMap<>();
        } else {
            vars = new HashMap<>(vars);
        }
        if (!vars.containsKey("createBy") && listenerVariable.getInstance() != null) {
            Instance ins = listenerVariable.getInstance();
            String json = ins.getVariable();
            if (json != null && !json.isBlank()) {
                try {
                    Map<?, ?> m = objectMapper.readValue(json, Map.class);
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        vars.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        FlowParams flowParams = listenerVariable.getFlowParams();
        if (flowParams != null) {
            if (flowParams.getHandler() != null && !flowParams.getHandler().isBlank()) {
                vars.putIfAbsent("createBy", flowParams.getHandler());
            }
            Object rawFv = flowParams.getVariable();
            if (rawFv instanceof Map) {
                Map<?, ?> fpVars = (Map<?, ?>) rawFv;
                for (Map.Entry<?, ?> e : fpVars.entrySet()) {
                    vars.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
                }
            }
        }
        Object createByObj = vars.get("createBy");
        if (createByObj == null) {
            return;
        }
        String createBy = String.valueOf(createByObj).trim();
        if (createBy.isEmpty()) {
            return;
        }
        List<User> users = task.getUserList();
        if (users == null || users.isEmpty()) {
            users = userService.listByAssociatedAndTypes(task.getId(), new String[0]);
        }
        boolean match = users != null && users.stream().anyMatch(u -> createBy.equals(u.getProcessedBy()));
        // create 阶段（及少数情况下）flow_user 可能仍不可用；用任务权限列表 / 节点权签兜底
        if (!match) {
            match = permissionListContains(task.getPermissionList(), createBy);
        }
        if (!match) {
            return;
        }
        if (task.getId() == null) {
            log.warn("auto-skip ship: task id null, cannot skip");
            return;
        }
        FlowParams fp = FlowParams.build()
                .handler(createBy)
                .permissionFlag(Collections.singletonList(createBy))
                .skipType(SkipType.PASS.getKey())
                .message("auto skip: approver equals submitter (PoC)");
        final Long taskId = task.getId();
        Runnable runSkip = () -> {
            try {
                taskService.skip(taskId, fp);
                log.info("auto-skipped ship_approve for submitter {}", createBy);
            } catch (Exception e) {
                log.warn("auto-skip ship failed for {}: {}", createBy, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runSkip.run();
                }
            });
        } else {
            runSkip.run();
        }
    }

    private static boolean permissionListContains(List<?> permissionList, String handler) {
        if (permissionList == null) {
            return false;
        }
        for (Object o : permissionList) {
            if (o != null && handler.equals(String.valueOf(o).trim())) {
                return true;
            }
        }
        return false;
    }

}
