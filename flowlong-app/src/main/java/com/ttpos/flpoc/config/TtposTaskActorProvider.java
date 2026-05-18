package com.ttpos.flpoc.config;

import com.aizuda.bpm.engine.TaskActorProvider;
import com.aizuda.bpm.engine.core.Execution;
import com.aizuda.bpm.engine.entity.FlwTaskActor;
import com.aizuda.bpm.engine.model.NodeModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟 ttpos 的 GetStaffsByAccessPath 解析：
 *   根据 instance 启动时传入的 companyUuid，去 shop{companyUuid} 库查 RBAC 表，
 *   返回持有 transfer_order_approve 权限的 staff_uuid 列表（含超管 union）。
 *
 * 复用 PoC seed 的 shop1001 / shop1002 库（warm-flow 那边已经建好）。
 */
@Component
public class TtposTaskActorProvider implements TaskActorProvider {

    private static final Logger log = LoggerFactory.getLogger(TtposTaskActorProvider.class);

    private final JdbcTemplate jdbc;

    public TtposTaskActorProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FlwTaskActor> getTaskActors(NodeModel nodeModel, Execution execution) {
        Map<String, Object> vars = execution.getArgs();
        Object cu = vars == null ? null : vars.get("companyUuid");
        if (cu == null) {
            log.warn("companyUuid missing from flow args; returning empty actor list");
            return new ArrayList<>();
        }
        long companyUuid = Long.parseLong(cu.toString());

        // accessPath -> uuid, role_access -> role_uuids, staff_role -> staff_uuids ∪ is_super=1
        String accessSql = "SELECT uuid FROM shop" + companyUuid + ".ttpos_access WHERE path=? AND delete_time=0";
        List<Long> accessIds = jdbc.queryForList(accessSql, Long.class, "transfer_order_approve");

        Map<Long, String> staffs = new HashMap<>();
        if (!accessIds.isEmpty()) {
            String roleSql = "SELECT role_uuid FROM shop" + companyUuid +
                    ".ttpos_role_access WHERE access_uuid IN (" +
                    accessIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") +
                    ") AND delete_time=0";
            List<Long> roleIds = jdbc.queryForList(roleSql, Long.class);
            if (!roleIds.isEmpty()) {
                String sql = "SELECT s.uuid,s.real_name FROM shop" + companyUuid + ".ttpos_staff s " +
                        "JOIN shop" + companyUuid + ".ttpos_staff_role sr ON sr.staff_uuid=s.uuid " +
                        "WHERE sr.role_uuid IN (" +
                        roleIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") +
                        ") AND s.delete_time=0 AND sr.delete_time=0";
                jdbc.query(sql, rs -> {
                    staffs.put(rs.getLong("uuid"), rs.getString("real_name"));
                });
            }
        }
        // is_super=1 union
        String superSql = "SELECT uuid,real_name FROM shop" + companyUuid +
                ".ttpos_staff WHERE is_super=1 AND is_disable=0 AND delete_time=0";
        jdbc.query(superSql, rs -> {
            staffs.put(rs.getLong("uuid"), rs.getString("real_name"));
        });

        List<FlwTaskActor> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : staffs.entrySet()) {
            out.add(FlwTaskActor.ofUser(null, String.valueOf(e.getKey()), e.getValue()));
        }
        log.info("ttpos RBAC resolved company={} -> {} actors {}", companyUuid, out.size(), staffs.keySet());
        return out;
    }

    @Override
    public Integer getActorType(NodeModel nodeModel) {
        return 0; // user
    }
}
