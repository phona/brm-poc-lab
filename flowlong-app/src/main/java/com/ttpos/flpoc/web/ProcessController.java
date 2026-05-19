package com.ttpos.flpoc.web;

import com.aizuda.bpm.engine.FlowLongEngine;
import com.aizuda.bpm.engine.core.FlowCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "flowlong-app-process", description = "流程定义管理接口。ttpos main 持有定义真源，通过 /process/deploy 同步到 FlowLong。")
public class ProcessController {

    private final FlowLongEngine engine;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProcessController(FlowLongEngine engine, JdbcTemplate jdbc) {
        this.engine = engine;
        this.jdbc = jdbc;
    }

    @Operation(
            summary = "部署 / 更新流程定义",
            description = "ttpos main 改完流程定义后调本接口推过来。repeat=true 时已存在则建新版本（老实例继续走老版本），" +
                    "repeat=false 时已存在则跳过。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = Dtos.DeployRequest.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "modelJson": "{\\"key\\":\\"my_flow\\",\\"name\\":\\"我的流程\\",\\"type\\":\\"AUDIT\\",\\"nodeConfig\\":{...}}",
                                      "repeat": true,
                                      "userId": "system",
                                      "userName": "system"
                                    }""")
                    )
            )
    )
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.DeployResponse.class)))
    @PostMapping("/process/deploy")
    public Dtos.DeployResponse deploy(@RequestBody Dtos.DeployRequest req) throws Exception {
        if (req.modelJson() == null || req.modelJson().isBlank()) {
            throw new IllegalArgumentException("modelJson required");
        }
        FlowCreator creator = FlowCreator.of(
                req.userId() == null ? "system" : req.userId(),
                req.userName() == null ? "system" : req.userName());
        boolean repeat = req.repeat() == null || req.repeat();

        // FlowLong: deploy(Long processId, String jsonString, FlowCreator, boolean repeat, Consumer<FlwProcess>)
        // processId=null 表示新建（FlowLong 内部判断同 key 是否存在）
        Long processId = engine.processService().deploy(null, req.modelJson(), creator, repeat, null);

        // 从 DB 反查返回
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT process_key, process_version FROM flw_process WHERE id = ?", processId);

        return new Dtos.DeployResponse(
                String.valueOf(processId),
                (String) row.get("process_key"),
                (Integer) row.get("process_version")
        );
    }

    @Operation(summary = "列出所有流程定义")
    @ApiResponse(responseCode = "200",
            content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = Dtos.ProcessSummary.class))))
    @GetMapping("/process/list")
    public List<Dtos.ProcessSummary> list() {
        return jdbc.query(
                "SELECT id, process_key, process_name, process_version, process_state " +
                        "FROM flw_process ORDER BY id DESC",
                (rs, n) -> new Dtos.ProcessSummary(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("process_key"),
                        rs.getString("process_name"),
                        (Integer) rs.getObject("process_version"),
                        (Integer) rs.getObject("process_state")
                ));
    }

    @Operation(summary = "查指定 processKey 的最新版本定义", description = "返回 model JSON 内容")
    @GetMapping("/process/{processKey}")
    public Map<String, Object> getByKey(@org.springframework.web.bind.annotation.PathVariable String processKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, process_key, process_name, process_version, process_state, model_content " +
                        "FROM flw_process WHERE process_key = ? AND process_state = 1 " +
                        "ORDER BY process_version DESC LIMIT 1",
                processKey);
        if (rows.isEmpty()) throw new IllegalArgumentException("processKey not found: " + processKey);
        Map<String, Object> row = rows.get(0);
        // 把 model_content 解析成对象方便客户端读
        try {
            Object content = row.get("model_content");
            if (content != null) {
                JsonNode node = objectMapper.readTree(String.valueOf(content));
                row.put("model", node);
            }
        } catch (Exception ignored) {}
        row.remove("model_content"); // 已经放到 model 字段
        return row;
    }
}
