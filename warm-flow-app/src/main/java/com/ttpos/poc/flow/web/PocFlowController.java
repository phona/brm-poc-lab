package com.ttpos.poc.flow.web;

import com.ttpos.poc.flow.ext.FlowGatewayNotifier;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.entity.User;
import org.dromara.warm.flow.core.enums.FlowStatus;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.service.UserService;
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.dromara.warm.flow.orm.entity.FlowUser;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP facade over Warm-Flow services (PoC). Paths are stable for the Go gateway client.
 */
@RestController
public class PocFlowController {

    private final InsService insService;
    private final TaskService taskService;
    private final UserService userService;
    private final FlowGatewayNotifier flowGatewayNotifier;

    public PocFlowController(InsService insService, TaskService taskService, UserService userService,
                             FlowGatewayNotifier flowGatewayNotifier) {
        this.insService = insService;
        this.taskService = taskService;
        this.userService = userService;
        this.flowGatewayNotifier = flowGatewayNotifier;
    }

    // ========== 流程实例 ==========
    public record StartBody(String businessId, String flowCode, String handler, Map<String, Object> variable) {}

    @PostMapping("/api/flow/start")
    public Map<String, Object> start(@RequestBody StartBody body) {
        Map<String, Object> vars = body.variable() == null ? new HashMap<>() : new HashMap<>(body.variable());
        vars.putIfAbsent("createBy", body.handler());
        FlowParams fp = FlowParams.build()
                .flowCode(body.flowCode())
                .handler(body.handler())
                .variable(vars);
        Instance ins = insService.start(body.businessId(), fp);
        return Map.of(
                "instanceId", ins.getId(),
                "businessId", ins.getBusinessId(),
                "flowStatus", ins.getFlowStatus() == null ? "" : ins.getFlowStatus()
        );
    }

    public record RevokeBody(long instanceId, String handler, String message) {}

    // revoke 在 Warm-Flow 1.3.3 中不存在，1.3.4+ 才支持。PoC 跳过此 API 验证。

    // ========== 任务跳转（通过/退回/任意跳转） ==========
    public record SkipBody(long taskId, String handler, String message, String skipType, String nodeCode) {}

    @PostMapping("/api/task/skip")
    public Map<String, Object> skip(@RequestBody SkipBody body) {
        String st = body.skipType();
        if (st == null || st.isBlank()) {
            st = SkipType.PASS.getKey();
        }
        FlowParams fp = FlowParams.build()
                .handler(body.handler())
                .message(body.message())
                .skipType(st);
        if (body.nodeCode() != null && !body.nodeCode().isBlank()) {
            fp = fp.nodeCode(body.nodeCode());
        }
        if (body.handler() != null && !body.handler().isBlank()) {
            fp = fp.permissionFlag(Collections.singletonList(body.handler()));
        }
        Instance ins = taskService.skip(body.taskId(), fp);
        return Map.of(
                "instanceId", ins.getId(),
                "businessId", ins.getBusinessId(),
                "flowStatus", ins.getFlowStatus() == null ? "" : ins.getFlowStatus()
        );
    }

    // ========== 退回 ==========
    public record RejectBody(long taskId, String handler, String message) {}

    // rejectLast 在 Warm-Flow 1.3.3 中不存在，1.3.4+ 才支持。
    // PoC 中用 skip(REJECT, nodeCode) 退回上一节点代替。

    public record RejectAtWillBody(long taskId, String handler, String nodeCode, String message) {}

    @PostMapping("/api/task/reject")
    public Map<String, Object> reject(@RequestBody RejectAtWillBody body) {
        // Warm-Flow 1.3.3 无 reject() 方法，用 skip(REJECT, nodeCode) 实现退回指定节点
        FlowParams fp = FlowParams.build()
                .handler(body.handler())
                .message(body.message())
                .skipType(SkipType.REJECT.getKey())
                .nodeCode(body.nodeCode());
        if (body.handler() != null && !body.handler().isBlank()) {
            fp = fp.permissionFlag(Collections.singletonList(body.handler()));
        }
        Instance ins = taskService.skip(body.taskId(), fp);
        return Map.of(
                "instanceId", ins.getId(),
                "businessId", ins.getBusinessId(),
                "flowStatus", ins.getFlowStatus() == null ? "" : ins.getFlowStatus()
        );
    }

    // ========== 终止/撤销/暂存 ==========
    public record TerminateBody(long taskId, String handler, String message) {}

    @PostMapping("/api/task/terminate")
    public Map<String, Object> terminate(@RequestBody TerminateBody body) {
        return finishByTermination(body, FlowStatus.REJECT, "rejected");
    }

    @PostMapping("/api/task/cancel")
    public Map<String, Object> cancel(@RequestBody TerminateBody body) {
        return finishByTermination(body, FlowStatus.CANCEL, "cancelled");
    }

    private Map<String, Object> finishByTermination(TerminateBody body, FlowStatus flowStatus, String gatewayStatus) {
        String msg = body.message();
        if (msg == null || msg.isBlank()) {
            msg = gatewayStatus;
        }
        FlowParams fp = FlowParams.build()
                .handler(body.handler())
                .message(msg)
                .flowStatus(flowStatus.getKey());
        if (body.handler() != null && !body.handler().isBlank()) {
            fp = fp.permissionFlag(Collections.singletonList(body.handler()));
        }
        Instance ins = taskService.termination(body.taskId(), fp);
        if (ins.getBusinessId() != null) {
            flowGatewayNotifier.notifyFinished(ins.getBusinessId(), gatewayStatus);
        }
        return Map.of(
                "instanceId", ins.getId(),
                "businessId", ins.getBusinessId(),
                "flowStatus", ins.getFlowStatus() == null ? "" : ins.getFlowStatus()
        );
    }

    public record PendingBody(long taskId, String handler, String message) {}

    // pending 在 Warm-Flow 1.3.3 中不存在，1.3.4+ 才支持。PoC 跳过此 API 验证。

    // ========== 转办/委派/加签/减签 ==========
    public record TransferBody(long taskId, String handler, List<String> addHandlers, String message) {}

    @PostMapping("/api/task/transfer")
    public Map<String, Object> transfer(@RequestBody TransferBody body) {
        FlowParams fp = FlowParams.build()
                .handler(body.handler())
                .message(body.message());
        if (body.addHandlers() != null && !body.addHandlers().isEmpty()) {
            fp = fp.addHandlers(body.addHandlers());
        }
        if (body.handler() != null && !body.handler().isBlank()) {
            fp = fp.permissionFlag(Collections.singletonList(body.handler()));
        }
        boolean ok = taskService.transfer(body.taskId(), fp);
        return Map.of("success", ok);
    }

    @PostMapping("/api/task/depute")
    public Map<String, Object> depute(@RequestBody TransferBody body) {
        FlowParams fp = FlowParams.build()
                .handler(body.handler())
                .message(body.message());
        if (body.addHandlers() != null && !body.addHandlers().isEmpty()) {
            fp = fp.addHandlers(body.addHandlers());
        }
        if (body.handler() != null && !body.handler().isBlank()) {
            fp = fp.permissionFlag(Collections.singletonList(body.handler()));
        }
        boolean ok = taskService.depute(body.taskId(), fp);
        return Map.of("success", ok);
    }

    public record SignatureBody(long taskId, String handler, List<String> addHandlers, String message) {}

    @PostMapping("/api/task/add-signature")
    public Map<String, Object> addSignature(@RequestBody SignatureBody body) {
        FlowParams fp = FlowParams.build()
                .handler(body.handler())
                .message(body.message());
        if (body.addHandlers() != null && !body.addHandlers().isEmpty()) {
            fp = fp.addHandlers(body.addHandlers());
        }
        if (body.handler() != null && !body.handler().isBlank()) {
            fp = fp.permissionFlag(Collections.singletonList(body.handler()));
        }
        boolean ok = taskService.addSignature(body.taskId(), fp);
        return Map.of("success", ok);
    }

    public record ReductionBody(long taskId, String handler, List<String> reductionHandlers, String message) {}

    @PostMapping("/api/task/reduction-signature")
    public Map<String, Object> reductionSignature(@RequestBody ReductionBody body) {
        FlowParams fp = FlowParams.build()
                .handler(body.handler())
                .message(body.message());
        if (body.reductionHandlers() != null && !body.reductionHandlers().isEmpty()) {
            fp = fp.reductionHandlers(body.reductionHandlers());
        }
        if (body.handler() != null && !body.handler().isBlank()) {
            fp = fp.permissionFlag(Collections.singletonList(body.handler()));
        }
        boolean ok = taskService.reductionSignature(body.taskId(), fp);
        return Map.of("success", ok);
    }

    // ========== 查询 ==========
    @GetMapping("/api/task/todo")
    public Map<String, Object> todo(@RequestParam("handler") String handler) {
        List<User> users = userService.list(new FlowUser().setProcessedBy(handler));
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : users) {
            Long taskId = u.getAssociated();
            if (taskId == null) {
                continue;
            }
            Task task = taskService.getById(taskId);
            if (task == null) {
                continue;
            }
            String bid = task.getBusinessId();
            if (bid == null || bid.isBlank()) {
                Instance ins = insService.getById(task.getInstanceId());
                if (ins != null && ins.getBusinessId() != null) {
                    bid = ins.getBusinessId();
                } else {
                    bid = "";
                }
            }
            list.add(Map.of(
                    "taskId", task.getId(),
                    "businessId", bid,
                    "nodeName", task.getNodeName() == null ? "" : task.getNodeName(),
                    "nodeCode", task.getNodeCode() == null ? "" : task.getNodeCode()
            ));
        }
        return Map.of("list", list);
    }

    @GetMapping("/api/task/detail")
    public Map<String, Object> taskDetail(@RequestParam("taskId") long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            return Map.of("task", null, "handlers", List.of());
        }
        List<User> users = userService.list(new FlowUser().setAssociated(taskId));
        List<String> handlers = new ArrayList<>();
        for (User u : users) {
            if (u.getProcessedBy() != null && !u.getProcessedBy().isBlank()) {
                handlers.add(u.getProcessedBy());
            }
        }
        Map<String, Object> taskMap = new HashMap<>();
        taskMap.put("taskId", task.getId());
        taskMap.put("nodeCode", task.getNodeCode());
        taskMap.put("nodeName", task.getNodeName());
        taskMap.put("businessId", task.getBusinessId());
        taskMap.put("instanceId", task.getInstanceId());
        return Map.of("task", taskMap, "handlers", handlers);
    }

    @GetMapping("/api/instance/tasks")
    public Map<String, Object> instanceTasks(@RequestParam("instanceId") long instanceId) {
        // Warm-Flow 1.3.3 无 getByInsId()，用 list(FlowTask) 代替
        List<Task> tasks = taskService.list(new FlowTask().setInstanceId(instanceId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Task t : tasks) {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", t.getId());
            m.put("nodeCode", t.getNodeCode());
            m.put("nodeName", t.getNodeName());
            list.add(m);
        }
        return Map.of("list", list);
    }
}
