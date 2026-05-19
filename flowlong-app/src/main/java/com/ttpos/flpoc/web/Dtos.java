package com.ttpos.flpoc.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Request / Response DTO 集合，给 OpenAPI 文档用。
 * 真实代码继续走 Map<String, Object> 因为流程变量是动态的，DTO 仅用于文档展示典型 shape。
 */
public class Dtos {

    @Schema(description = "启动流程实例请求体。flowCode 在 PoC 中硬编码为 ttpos_transfer_test；" +
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

    @Schema(description = "审批/通过任务返回")
    public record SkipResponse(
            @Schema(description = "引擎是否成功推进", example = "true")
            boolean success
    ) {}

    @Schema(description = "实例下任务及其参与者")
    public record InstanceActorRow(
            @Schema(description = "任务 ID（雪花 long）", example = "2056368596446793733")
            Long taskId,
            @Schema(example = "RBAC 审批 (会签)")
            String taskName,
            @Schema(example = "approve")
            String taskKey,
            @Schema(description = "参与者 ID（staff_uuid 字符串）", example = "10011")
            String actorId,
            @Schema(description = "参与者显示名", example = "Alice")
            String actorName,
            @Schema(description = "0=用户 1=角色 2=部门", example = "0")
            Integer actorType
    ) {}

    @Schema(description = "待办任务")
    public record TodoRow(
            @Schema(example = "2056368596446793733")
            Long taskId,
            @Schema(description = "所属流程实例 ID", example = "2056368596446793729")
            Long instanceId,
            @Schema(example = "RBAC 审批 (会签)")
            String taskName,
            @Schema(example = "approve")
            String taskKey,
            @Schema(description = "创建时间（Unix 秒级时间戳的字符串）")
            String createTime
    ) {}
}
