package com.ttpos.flpoc.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Request / Response DTO 集合，给 OpenAPI 文档用。
 * 真实代码继续走 Map<String, Object> 因为流程变量是动态的，DTO 仅用于文档展示典型 shape。
 */
public class Dtos {

    // ========= 启动 =========

    @Schema(description = "启动流程实例请求体。flowCode 指定流程定义（缺省回退 ttpos_transfer_test）；" +
            "approverIds 或 approvers 二选一（caller 在调用前预解析好审批人列表）。")
    public record StartFlowRequest(
            @Schema(description = "发起人 ID（业务系统的 staff_uuid 字符串）", example = "10011", requiredMode = Schema.RequiredMode.REQUIRED)
            String userId,
            @Schema(description = "发起人显示名", example = "Alice")
            String userName,
            @Schema(description = "租户/公司 ID", example = "1001", requiredMode = Schema.RequiredMode.REQUIRED)
            Long companyUuid,
            @Schema(description = "业务单据号（订单号 / 调拨单号等），便于反查", example = "TR-20260519-001")
            String businessId,
            @Schema(description = "流程定义 key（对应 flw_process.process_key）；缺省回退到 ttpos_transfer_test", example = "ttpos_transfer_test")
            String flowCode,
            @Schema(description = "审批人 ID 列表（简单形式，无显示名）；和 approvers 二选一", example = "[\"10011\",\"10012\"]")
            List<String> approverIds,
            @Schema(description = "审批人结构化列表（含显示名，方便审计），格式 [{id,name}]；和 approverIds 二选一")
            List<Map<String, Object>> approvers
    ) {}

    @Schema(description = "启动流程响应")
    public record StartFlowResponse(
            @Schema(description = "FlowLong 流程实例 ID（雪花 long 转字符串，避免 JS 大整数精度问题）", example = "2056368596446793729")
            String instanceId,
            @Schema(description = "当前所在节点 key", example = "start")
            String currentNodeKey,
            @Schema(description = "当前所在节点显示名", example = "发起人")
            String currentNodeName
    ) {}

    // ========= 审批通过 =========

    @Schema(description = "审批通过请求体。caller 可通过 variables.approverIds 指定下一节点的人")
    public record SkipRequest(
            @Schema(description = "任务 ID（FlowLong 雪花 long）", example = "2056368596446793733", requiredMode = Schema.RequiredMode.REQUIRED)
            Long taskId,
            @Schema(description = "操作人 staff_uuid", example = "10011", requiredMode = Schema.RequiredMode.REQUIRED)
            String userId,
            @Schema(description = "操作人显示名", example = "Alice")
            String userName,
            @Schema(description = "审批意见", example = "同意，符合采购规范")
            String message,
            @Schema(description = "节点流转变量：可用 approverIds 指定下一节点审批人 list（caller 预解析后传入）。" +
                    "传 null 时使用启动时已传的 approverIds。")
            Map<String, Object> variables
    ) {}

    @Schema(description = "审批/通过任务返回")
    public record SkipResponse(
            @Schema(description = "引擎是否成功推进", example = "true")
            boolean success,
            @Schema(description = "操作后实例当前节点 key（流程结束则为 end）", example = "approve")
            String currentNodeKey,
            @Schema(description = "实例状态：0=审批中 1=审批通过 2=审批拒绝 3=撤销 5=终止", example = "0")
            Integer instanceState
    ) {}

    // ========= 驳回 =========

    @Schema(description = "驳回任务请求体")
    public record RejectRequest(
            @Schema(description = "任务 ID", example = "2056368596446793733", requiredMode = Schema.RequiredMode.REQUIRED)
            Long taskId,
            @Schema(description = "操作人 staff_uuid", example = "10011", requiredMode = Schema.RequiredMode.REQUIRED)
            String userId,
            @Schema(description = "操作人显示名", example = "Alice")
            String userName,
            @Schema(description = "驳回原因（必填，业务规则）", example = "金额不符，请修改", requiredMode = Schema.RequiredMode.REQUIRED)
            String reason,
            @Schema(description = "驳回到指定节点 key（null/留空则驳回到上一节点）", example = "submit")
            String rejectToNodeKey,
            @Schema(description = "是否同时终止整个实例（true=终止，业务'驳回'分支用；false=仅退回前一节点，默认 false）", example = "false")
            Boolean terminate
    ) {}

    @Schema(description = "驳回响应")
    public record RejectResponse(
            boolean success,
            @Schema(description = "驳回后激活的任务列表（如果是退回上一节点会有新任务；如果是终止则为空）")
            List<TaskInfo> activatedTasks,
            @Schema(description = "实例状态", example = "0")
            Integer instanceState
    ) {}

    // ========= 终止实例 =========

    @Schema(description = "终止实例请求体（业务侧用于'驳回'分支强制结束流程）")
    public record TerminateRequest(
            @Schema(description = "操作人 staff_uuid", example = "10011", requiredMode = Schema.RequiredMode.REQUIRED)
            String userId,
            @Schema(description = "操作人显示名", example = "Alice")
            String userName,
            @Schema(description = "终止原因（审计用）", example = "提交人主动撤回")
            String reason
    ) {}

    // ========= 实例详情 =========

    @Schema(description = "实例当前状态 + 进行中任务的快照")
    public record InstanceDetail(
            String instanceId,
            String processKey,
            @Schema(description = "业务单据号（启动时 caller 通过 businessId 字段传入）", example = "TR-20260519-001")
            String businessKey,
            String currentNodeKey,
            String currentNodeName,
            @Schema(description = "0=审批中 1=通过 2=拒绝 3=撤销 4=超时 5=强制终止 6=自动通过 7=自动拒绝")
            Integer instanceState,
            @Schema(description = "创建时间（Unix 毫秒）")
            Long createTimeMs,
            @Schema(description = "进行中的任务（已分配 actor）")
            List<TaskInfo> activeTasks,
            @Schema(description = "租户/公司 ID（caller 启动时传入的 companyUuid）", example = "1001")
            String tenantId
    ) {}

    @Schema(description = "任务及其参与者")
    public record TaskInfo(
            String taskId,
            String taskKey,
            String taskName,
            @Schema(description = "0=主办 1=审批 2=抄送 3=条件审批 ... 详见 flw_task.task_type")
            Integer taskType,
            @Schema(description = "0=发起 1=顺序 2=会签 3=或签 4=票签")
            Integer performType,
            List<ActorInfo> actors
    ) {}

    public record ActorInfo(
            String actorId,
            String actorName,
            @Schema(description = "0=用户 1=角色 2=部门")
            Integer actorType
    ) {}

    // ========= 历史 =========

    @Schema(description = "实例的历史任务时间线")
    public record InstanceHistory(
            String instanceId,
            List<HistoryEntry> items
    ) {}

    @Schema(description = "历史任务条目")
    public record HistoryEntry(
            String taskId,
            String taskKey,
            String taskName,
            @Schema(description = "0=活动 1=跳转 2=完成 3=拒绝 4=撤销 5=超时 6=终止 ...")
            Integer taskState,
            Integer taskType,
            Long createTimeMs,
            Long finishTimeMs,
            @Schema(description = "处理耗时 ms")
            Long durationMs,
            List<ActorInfo> actors
    ) {}

    // ========= 流程定义部署 =========

    @Schema(description = "部署 / 更新流程定义。流程定义 JSON 模型由 ttpos main 持有，调本接口推送到 FlowLong。")
    public record DeployRequest(
            @Schema(description = "FlowLong 流程模型 JSON 字符串（按 flowlong 模型规范，详见 process-ttpos.json 示例）", requiredMode = Schema.RequiredMode.REQUIRED)
            String modelJson,
            @Schema(description = "true=已存在时建新版本（推荐），false=已存在则跳过", example = "true")
            Boolean repeat,
            @Schema(description = "部署操作人 staff_uuid", example = "system")
            String userId,
            @Schema(description = "部署操作人显示名", example = "system")
            String userName
    ) {}

    @Schema(description = "部署响应")
    public record DeployResponse(
            @Schema(description = "FlowLong 内部 process ID（用于实例化时引用）", example = "2056368515614023681")
            String processId,
            @Schema(description = "流程 key（业务唯一标识，从 modelJson 的 key 字段提取）", example = "ttpos_transfer_test")
            String processKey,
            @Schema(description = "流程版本（每次 repeat=true 部署自动 +1）", example = "1")
            Integer processVersion
    ) {}

    @Schema(description = "流程定义摘要")
    public record ProcessSummary(
            String processId,
            String processKey,
            String processName,
            Integer processVersion,
            @Schema(description = "0=不可用 1=可用 2=历史版本")
            Integer processState
    ) {}

    // ========= 兼容旧字段 =========

    @Schema(description = "审批/通过任务返回（兼容旧 query 调用形式）")
    public record SkipResponseLegacy(
            boolean success
    ) {}

    @Schema(description = "待办任务")
    public record TodoRow(
            Long taskId,
            Long instanceId,
            String taskName,
            String taskKey,
            String createTime
    ) {}

    @Schema(description = "实例下任务及其参与者（debug 接口返回行）")
    public record InstanceActorRow(
            Long taskId,
            String taskName,
            String taskKey,
            String actorId,
            String actorName,
            Integer actorType
    ) {}
}
