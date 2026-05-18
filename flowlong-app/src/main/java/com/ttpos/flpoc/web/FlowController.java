package com.ttpos.flpoc.web;

import com.aizuda.bpm.engine.FlowLongEngine;
import com.aizuda.bpm.engine.core.FlowCreator;
import com.aizuda.bpm.engine.entity.FlwInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class FlowController {

    private final FlowLongEngine engine;
    private final JdbcTemplate jdbc;

    public FlowController(FlowLongEngine engine, JdbcTemplate jdbc) {
        this.engine = engine;
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public String health() { return "ok"; }

    /** body: {"userId":"10011","userName":"Alice","companyUuid":1001,"businessId":"FL-T1"} */
    @PostMapping("/flow/start")
    public Map<String, Object> start(@RequestBody Map<String, Object> body) {
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
    }

    /** Approve current task on behalf of caller. */
    @PostMapping("/task/skip")
    public Map<String, Object> skip(@RequestParam Long taskId,
                                    @RequestParam String userId,
                                    @RequestParam(defaultValue = "approver") String userName) {
        boolean ok = engine.executeTask(taskId,
                FlowCreator.of(userId, userName),
                new HashMap<>());
        return Map.of("success", ok);
    }

    @GetMapping("/instance/actors")
    public List<Map<String, Object>> actorsOfInstance(@RequestParam Long instanceId) {
        return jdbc.queryForList(
                "SELECT t.id AS task_id, t.task_name, t.task_key, a.actor_id, a.actor_name, a.actor_type " +
                        "FROM flw_task t JOIN flw_task_actor a ON a.task_id = t.id " +
                        "WHERE t.instance_id = ? ORDER BY t.id, a.id", instanceId);
    }

    @GetMapping("/task/todo")
    public List<Map<String, Object>> todo(@RequestParam String userId) {
        return jdbc.queryForList(
                "SELECT t.id AS task_id, t.instance_id, t.task_name, t.task_key, t.create_time " +
                        "FROM flw_task t JOIN flw_task_actor a ON a.task_id = t.id " +
                        "WHERE a.actor_id = ? ORDER BY t.create_time DESC", userId);
    }
}
