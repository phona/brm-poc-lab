package com.ttpos.flpoc.web;

import com.aizuda.bpm.engine.FlowLongEngine;
import com.aizuda.bpm.engine.core.FlowCreator;
import com.aizuda.bpm.engine.entity.FlwInstance;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "flowlong-app", description = "ttpos 审批引擎（FlowLong 1.2.4 包装）。Caller 负责预解析审批人 list，引擎对 ttpos 业务零认知。")
public class FlowController {

    private final FlowLongEngine engine;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;

    public FlowController(FlowLongEngine engine, JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.engine = engine;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
    }

    @Operation(summary = "Liveness 探针", description = "返回 'ok' 字符串；K8s livenessProbe 用。生产建议改用 /actuator/health/liveness")
    @ApiResponse(responseCode = "200", description = "ok",
            content = @Content(mediaType = "text/plain", examples = @ExampleObject(value = "ok")))
    @GetMapping("/health")
    public String health() { return "ok"; }

    @Operation(
            summary = "启动流程实例",
            description = "ttpos main 在用户提交单据时调用。caller 必须把审批人 list 预解析后通过 approverIds 或 approvers 传过来，引擎不连 ttpos DB。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = Dtos.StartFlowRequest.class),
                            examples = {
                                    @ExampleObject(name = "简单（仅 ID 列表）", value = "{\"userId\":\"10011\",\"userName\":\"Alice\",\"companyUuid\":1001,\"businessId\":\"TR-001\",\"approverIds\":[\"10011\",\"10012\"]}"),
                                    @ExampleObject(name = "结构化（带显示名）", value = "{\"userId\":\"10011\",\"userName\":\"Alice\",\"companyUuid\":1001,\"businessId\":\"TR-002\",\"approvers\":[{\"id\":\"10011\",\"name\":\"Alice\"},{\"id\":\"10012\",\"name\":\"Bob\"}]}")
                            }
                    )
            )
    )
    @ApiResponse(responseCode = "200", description = "成功",
            content = @Content(schema = @Schema(implementation = Dtos.StartFlowResponse.class)))
    @PostMapping("/flow/start")
    public Map<String, Object> start(@RequestBody Map<String, Object> body) {
        String tenant = String.valueOf(body.getOrDefault("companyUuid", "unknown"));
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            FlowCreator creator = FlowCreator.of(
                    String.valueOf(body.getOrDefault("userId", "u1")),
                    String.valueOf(body.getOrDefault("userName", "anon")));
            Map<String, Object> args = new HashMap<>(body);
            FlwInstance inst = engine.startInstanceByProcessKey("ttpos_transfer_test", null, creator, args)
                    .orElseThrow(() -> new IllegalStateException("start returned empty"));
            Map<String, Object> out = new HashMap<>();
            out.put("instanceId", String.valueOf(inst.getId()));
            out.put("currentNodeKey", inst.getCurrentNodeKey());
            out.put("currentNodeName", inst.getCurrentNodeName());
            return out;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("flow_start_duration_seconds", Tags.of("tenant", tenant, "outcome", outcome)));
            meterRegistry.counter("flow_start_total", "tenant", tenant, "outcome", outcome).increment();
        }
    }

    @Operation(summary = "审批通过任务", description = "caller 传当前操作人 userId；引擎按 task.actor_id 校验权限，不匹配会失败")
    @ApiResponse(responseCode = "200", description = "返回 {success: true/false}",
            content = @Content(schema = @Schema(implementation = Dtos.SkipResponse.class)))
    @PostMapping("/task/skip")
    public Map<String, Object> skip(
            @io.swagger.v3.oas.annotations.Parameter(description = "任务 ID（FlowLong 雪花 long）", required = true, example = "2056368596446793733")
            @RequestParam Long taskId,
            @io.swagger.v3.oas.annotations.Parameter(description = "操作人 staff_uuid", required = true, example = "10011")
            @RequestParam String userId,
            @io.swagger.v3.oas.annotations.Parameter(description = "操作人显示名", example = "Alice")
            @RequestParam(defaultValue = "approver") String userName) {
        String outcome = "success";
        try {
            boolean ok = engine.executeTask(taskId,
                    FlowCreator.of(userId, userName),
                    new HashMap<>());
            if (!ok) outcome = "rejected";
            return Map.of("success", ok);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            meterRegistry.counter("task_skip_total", "outcome", outcome).increment();
        }
    }

    @Operation(summary = "查实例下所有任务的参与者", description = "调试/审计用：列出实例当前所有任务以及每个任务被分配到的 actor")
    @ApiResponse(responseCode = "200", description = "任务参与者列表（一人一行）",
            content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = Dtos.InstanceActorRow.class))))
    @GetMapping("/instance/actors")
    public List<Map<String, Object>> actorsOfInstance(
            @io.swagger.v3.oas.annotations.Parameter(description = "流程实例 ID", required = true, example = "2056368596446793729")
            @RequestParam Long instanceId) {
        return jdbc.queryForList(
                "SELECT t.id AS task_id, t.task_name, t.task_key, a.actor_id, a.actor_name, a.actor_type " +
                        "FROM flw_task t JOIN flw_task_actor a ON a.task_id = t.id " +
                        "WHERE t.instance_id = ? ORDER BY t.id, a.id", instanceId);
    }

    @Operation(summary = "查某人的待办任务", description = "ttpos main 的审批中心 API 后端")
    @ApiResponse(responseCode = "200", description = "待办列表，按创建时间倒序",
            content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = Dtos.TodoRow.class))))
    @GetMapping("/task/todo")
    public List<Map<String, Object>> todo(
            @io.swagger.v3.oas.annotations.Parameter(description = "操作人 staff_uuid", required = true, example = "10011")
            @RequestParam String userId) {
        return jdbc.queryForList(
                "SELECT t.id AS task_id, t.instance_id, t.task_name, t.task_key, t.create_time " +
                        "FROM flw_task t JOIN flw_task_actor a ON a.task_id = t.id " +
                        "WHERE a.actor_id = ? ORDER BY t.create_time DESC", userId);
    }
}
