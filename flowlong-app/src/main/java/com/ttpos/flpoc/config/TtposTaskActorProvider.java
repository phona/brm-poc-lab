package com.ttpos.flpoc.config;

import com.aizuda.bpm.engine.TaskActorProvider;
import com.aizuda.bpm.engine.core.Execution;
import com.aizuda.bpm.engine.entity.FlwTaskActor;
import com.aizuda.bpm.engine.model.NodeModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产架构原则：Java 引擎对 ttpos 业务零认知。
 *
 * 审批人解析由 caller（ttpos main，Go）在启动流程前完成：
 *   - Go 调 ttpos 自己的 GetStaffsByAccessPath(companyUuid, accessPath) 算出 staff 列表
 *   - 作为 FlowParams.variable["approverIds"] 传过来
 *   - 这里只负责把它转成 FlwTaskActor 列表
 *
 * 不做：
 *   - 不连 ttpos shop{n} DB
 *   - 不调 ttpos main HTTP API（如果未来有需要再考虑，但每节点创建都打 HTTP 是延迟+故障耦合）
 *   - 不理解 access path / role / company 等业务概念
 *
 * variable 约定（caller 必须提供其一）：
 *   - approverIds: List<String>  →  推荐
 *   - approverIds: String        →  逗号分隔，兼容简单 client
 *   - approvers: List<Map<id,name>>  →  带显示名，方便审计可读
 */
@Component
public class TtposTaskActorProvider implements TaskActorProvider {

    private static final Logger log = LoggerFactory.getLogger(TtposTaskActorProvider.class);

    @Override
    public List<FlwTaskActor> getTaskActors(NodeModel nodeModel, Execution execution) {
        Map<String, Object> vars = execution.getArgs();
        if (vars == null || vars.isEmpty()) {
            log.warn("getTaskActors: no flow args; returning empty actor list");
            return new ArrayList<>();
        }

        // 优先：approvers (含显示名)
        Object structured = vars.get("approvers");
        if (structured instanceof List<?> list && !list.isEmpty()) {
            List<FlwTaskActor> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String id = String.valueOf(m.get("id"));
                    String name = m.get("name") == null ? "" : String.valueOf(m.get("name"));
                    if (!id.isBlank() && !"null".equals(id)) {
                        out.add(FlwTaskActor.ofUser(null, id, name));
                    }
                }
            }
            if (!out.isEmpty()) {
                log.info("getTaskActors: resolved {} actors from 'approvers' variable", out.size());
                return out;
            }
        }

        // 兼容：approverIds (单字符串或字符串列表)
        Object ids = vars.get("approverIds");
        List<String> idList = parseIds(ids);
        if (idList.isEmpty()) {
            log.warn("getTaskActors: 'approverIds' / 'approvers' empty or missing; returning empty actor list");
            return new ArrayList<>();
        }
        // 去重保持顺序
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String id : idList) {
            if (!id.isBlank()) seen.putIfAbsent(id, true);
        }
        List<FlwTaskActor> out = new ArrayList<>(seen.size());
        for (String id : seen.keySet()) {
            out.add(FlwTaskActor.ofUser(null, id, ""));
        }
        log.info("getTaskActors: resolved {} actors from 'approverIds' variable", out.size());
        return out;
    }

    @Override
    public Integer getActorType(NodeModel nodeModel) {
        return 0; // user
    }

    private List<String> parseIds(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
