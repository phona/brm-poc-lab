package com.ttpos.flpoc.web;

import com.aizuda.bpm.engine.FlowLongEngine;
import com.aizuda.bpm.engine.RuntimeService;
import com.aizuda.bpm.engine.QueryService;
import com.aizuda.bpm.engine.TaskService;
import com.aizuda.bpm.engine.core.FlowCreator;
import com.aizuda.bpm.engine.entity.FlwHisTask;
import com.aizuda.bpm.engine.entity.FlwHisTaskActor;
import com.aizuda.bpm.engine.entity.FlwInstance;
import com.aizuda.bpm.engine.entity.FlwTask;
import com.aizuda.bpm.engine.entity.FlwTaskActor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@Tag(name = "flowlong-app", description = "ttpos 审批引擎（FlowLong 1.2.4 包装）。Caller 负责预解析审批人 list，引擎对 ttpos 业务零认知。")
public class FlowController {

    /** gap-1: caller 未指定 flowCode 时回退的 PoC 默认流程 key */
    private static final String DEFAULT_FLOW_CODE = "ttpos_transfer_test";

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

    // ============== 启动流程 ==============

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

            // gap-1: 流程 key 由 caller 通过 flowCode 指定（缺省回退到 PoC 默认流程）
            String flowCode = String.valueOf(body.getOrDefault("flowCode", DEFAULT_FLOW_CODE));
            // gap-8: companyUuid 作为 tenantId 传进引擎（webhook→dispatcher 切库依赖）
            // 引擎 startInstanceByProcessKey 第 2 参数是 Integer，JSON 数字可能反序列化为 Integer/Long/String
            Object companyUuid = body.get("companyUuid");
            Integer tenantId = companyUuid == null ? null
                    : (companyUuid instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(companyUuid)));
            // gap-7: caller 传的 businessId 映射到 FlowLong 的 businessKey
            if (body.get("businessId") != null) {
                args.put("businessKey", String.valueOf(body.get("businessId")));
            }

            FlwInstance inst = engine.startInstanceByProcessKey(flowCode, tenantId, creator, args)
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

    // ============== 审批通过（新版 JSON body） ==============

    @Operation(
            summary = "审批通过任务",
            description = "caller 用 JSON body 传操作人 + variables。variables.approverIds 可指定下一节点审批人 list（caller 预解析）。" +
                    "引擎按 task.actor_id 校验权限，不匹配会失败。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = Dtos.SkipRequest.class),
                            examples = @ExampleObject(value = "{\"taskId\":2056368596446793733,\"userId\":\"10011\",\"userName\":\"Alice\",\"message\":\"同意\",\"variables\":{\"approverIds\":[\"10013\",\"10014\"]}}")
                    )
            )
    )
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.SkipResponse.class)))
    @PostMapping("/task/skip")
    public Dtos.SkipResponse skip(@RequestBody Dtos.SkipRequest req) {
        String outcome = "success";
        try {
            FlowCreator creator = FlowCreator.of(req.userId(), req.userName() == null ? "approver" : req.userName());
            Map<String, Object> args = req.variables() == null ? new HashMap<>() : new HashMap<>(req.variables());
            if (req.message() != null) args.put("message", req.message());
            boolean ok = engine.executeTask(req.taskId(), creator, args);

            // 查实例后状态
            FlwTask task = engine.queryService().getTask(req.taskId());
            Long instanceId = task != null ? task.getInstanceId() : null;
            FlwInstance inst = instanceId == null ? null : engine.queryService().getInstance(instanceId);
            return new Dtos.SkipResponse(ok,
                    inst == null ? null : inst.getCurrentNodeKey(),
                    null); // FlwInstance 上没有 instanceState 字段；要查 his_instance；PoC 略
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            meterRegistry.counter("task_skip_total", "outcome", outcome).increment();
        }
    }

    // ============== 驳回 ==============

    @Operation(
            summary = "驳回任务",
            description = "默认退回上一节点；指定 rejectToNodeKey 退回到指定节点；terminate=true 时直接终止整个实例（业务'驳回'分支用）",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(implementation = Dtos.RejectRequest.class))
            )
    )
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.RejectResponse.class)))
    @PostMapping("/task/reject")
    public Dtos.RejectResponse reject(@RequestBody Dtos.RejectRequest req) {
        String outcome = "success";
        try {
            FlwTask task = engine.queryService().getTask(req.taskId());
            if (task == null) throw new IllegalArgumentException("task not found: " + req.taskId());

            FlowCreator creator = FlowCreator.of(req.userId(), req.userName() == null ? "rejector" : req.userName());
            Map<String, Object> args = new HashMap<>();
            if (req.reason() != null) args.put("reason", req.reason());

            boolean terminate = Boolean.TRUE.equals(req.terminate());
            var resultOpt = engine.executeRejectTask(task, req.rejectToNodeKey(), creator, args, terminate);

            List<FlwTask> activated = resultOpt.orElse(new ArrayList<>());
            FlwInstance inst = engine.queryService().getInstance(task.getInstanceId());

            return new Dtos.RejectResponse(
                    true,
                    activated.stream().map(this::toTaskInfo).collect(Collectors.toList()),
                    null  // 同 skip：PoC 不查 his_instance
            );
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            meterRegistry.counter("task_reject_total", "outcome", outcome).increment();
        }
    }

    // ============== 终止实例 ==============

    @Operation(summary = "强制终止实例", description = "业务'驳回'分支或提交人主动撤回时调用。一旦终止不可恢复，老任务全部置完成。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.SkipResponseLegacy.class)))
    @PostMapping("/instance/{instanceId}/terminate")
    public Map<String, Object> terminate(
            @Parameter(description = "流程实例 ID", example = "2056368596446793729") @PathVariable Long instanceId,
            @RequestBody Dtos.TerminateRequest req) {
        FlowCreator creator = FlowCreator.of(req.userId(), req.userName() == null ? "terminator" : req.userName());
        boolean ok = engine.runtimeService().terminate(instanceId, creator);
        meterRegistry.counter("instance_terminate_total", "outcome", ok ? "success" : "error").increment();
        return Map.of("success", ok);
    }

    // ============== 实例详情 ==============

    @Operation(summary = "查实例详情", description = "返回实例当前状态 + 所有进行中的任务 + 任务的 actor")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.InstanceDetail.class)))
    @GetMapping("/instance/{instanceId}")
    public Dtos.InstanceDetail getInstance(@PathVariable Long instanceId) {
        FlwInstance inst = engine.queryService().getInstance(instanceId);
        if (inst == null) throw new IllegalArgumentException("instance not found: " + instanceId);

        // 直查 flw_process 拿 processKey（避免再 query）
        String processKey = jdbc.queryForObject(
                "SELECT process_key FROM flw_process WHERE id = ?",
                String.class, inst.getProcessId());

        List<FlwTask> tasks = engine.queryService().getTasksByInstanceId(instanceId);
        List<Dtos.TaskInfo> taskInfos = tasks.stream().map(this::toTaskInfo).collect(Collectors.toList());

        return new Dtos.InstanceDetail(
                String.valueOf(inst.getId()),
                processKey,
                inst.getBusinessKey(),
                inst.getCurrentNodeKey(),
                inst.getCurrentNodeName(),
                null,  // 当前 instance 表无 state，只 his_instance 有；PoC 略
                inst.getCreateTime() == null ? null : inst.getCreateTime().getTime(),
                taskInfos,
                inst.getTenantId()
        );
    }

    // ============== 实例历史 ==============

    @Operation(summary = "查实例历史 timeline", description = "ttpos Timeline 渲染数据源")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.InstanceHistory.class)))
    @GetMapping("/instance/{instanceId}/history")
    public Dtos.InstanceHistory getHistory(@PathVariable Long instanceId) {
        List<FlwHisTask> hisTasks = engine.queryService().getHisTasksByInstanceId(instanceId)
                .orElse(new ArrayList<>());

        // 一次性把所有 his_task_actor 拉回来再聚合
        List<FlwHisTaskActor> hisActors = engine.queryService()
                .getHisTaskActorsByInstanceId(instanceId)
                .orElse(new ArrayList<>());
        Map<Long, List<Dtos.ActorInfo>> actorsByTask = new HashMap<>();
        for (FlwHisTaskActor a : hisActors) {
            actorsByTask.computeIfAbsent(a.getTaskId(), k -> new ArrayList<>())
                    .add(new Dtos.ActorInfo(a.getActorId(), a.getActorName(), a.getActorType()));
        }

        List<Dtos.HistoryEntry> entries = hisTasks.stream().map(t -> new Dtos.HistoryEntry(
                String.valueOf(t.getId()),
                t.getTaskKey(),
                t.getTaskName(),
                t.getTaskState() == null ? null : t.getTaskState().intValue(),
                t.getTaskType() == null ? null : t.getTaskType().intValue(),
                t.getCreateTime() == null ? null : t.getCreateTime().getTime(),
                t.getFinishTime() == null ? null : t.getFinishTime().getTime(),
                t.getDuration(),
                actorsByTask.getOrDefault(t.getId(), new ArrayList<>())
        )).collect(Collectors.toList());

        return new Dtos.InstanceHistory(String.valueOf(instanceId), entries);
    }

    // ============== 调试 / 兼容旧接口 ==============

    @Operation(summary = "查实例下所有任务的参与者（调试用）",
            description = "推荐用 /instance/{id} 替代；本接口保留用于 debug 直查")
    @GetMapping("/instance/actors")
    public List<Map<String, Object>> actorsOfInstance(
            @Parameter(description = "流程实例 ID", required = true, example = "2056368596446793729")
            @RequestParam Long instanceId) {
        return jdbc.queryForList(
                "SELECT t.id AS task_id, t.task_name, t.task_key, a.actor_id, a.actor_name, a.actor_type " +
                        "FROM flw_task t JOIN flw_task_actor a ON a.task_id = t.id " +
                        "WHERE t.instance_id = ? ORDER BY t.id, a.id", instanceId);
    }

    @Operation(summary = "查某人的待办任务", description = "ttpos main 的审批中心 API 后端")
    @GetMapping("/task/todo")
    public List<Map<String, Object>> todo(
            @Parameter(description = "操作人 staff_uuid", required = true, example = "10011")
            @RequestParam String userId) {
        return jdbc.queryForList(
                "SELECT t.id AS task_id, t.instance_id, t.task_name, t.task_key, t.create_time " +
                        "FROM flw_task t JOIN flw_task_actor a ON a.task_id = t.id " +
                        "WHERE a.actor_id = ? ORDER BY t.create_time DESC", userId);
    }

    // ============== helpers ==============

    private Dtos.TaskInfo toTaskInfo(FlwTask t) {
        // 走 JDBC 避免 FlowLong API 不同版本差异
        List<Dtos.ActorInfo> actorInfos = jdbc.query(
                "SELECT actor_id, actor_name, actor_type FROM flw_task_actor WHERE task_id = ? ORDER BY id",
                (rs, n) -> new Dtos.ActorInfo(
                        rs.getString("actor_id"),
                        rs.getString("actor_name"),
                        (Integer) rs.getObject("actor_type")
                ),
                t.getId());
        return new Dtos.TaskInfo(
                String.valueOf(t.getId()),
                t.getTaskKey(),
                t.getTaskName(),
                t.getTaskType() == null ? null : t.getTaskType().intValue(),
                t.getPerformType() == null ? null : t.getPerformType().intValue(),
                actorInfos
        );
    }
}
