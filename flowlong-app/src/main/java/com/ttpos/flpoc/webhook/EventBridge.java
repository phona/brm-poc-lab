package com.ttpos.flpoc.webhook;

import com.aizuda.bpm.engine.entity.FlwTask;
import com.aizuda.bpm.engine.entity.FlwTaskActor;
import com.aizuda.bpm.spring.event.InstanceEvent;
import com.aizuda.bpm.spring.event.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 FlowLong 内部的 TaskEvent / InstanceEvent 桥接成 webhook 推给 ttpos main。
 * Spring 自动调度 @EventListener；event 由 FlowLong autoconfigure 在
 * flowlong.eventing.task=true / instance=true 时发布。
 */
@Component
public class EventBridge {

    private static final Logger log = LoggerFactory.getLogger(EventBridge.class);

    private final WebhookSender sender;

    public EventBridge(WebhookSender sender) {
        this.sender = sender;
    }

    @EventListener
    public void onTask(TaskEvent event) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "task." + (event.getEventType() == null ? "unknown" : event.getEventType().name()));

            FlwTask task = event.getFlwTask();
            if (task != null) {
                payload.put("taskId", String.valueOf(task.getId()));
                payload.put("instanceId", task.getInstanceId() == null ? null : String.valueOf(task.getInstanceId()));
                payload.put("taskKey", task.getTaskKey());
                payload.put("taskName", task.getTaskName());
                payload.put("taskType", task.getTaskType());
                payload.put("performType", task.getPerformType());
                payload.put("createTimeMs", task.getCreateTime() == null ? null : task.getCreateTime().getTime());
            }

            String nodeKey = null;
            String nodeName = null;
            if (event.getNodeModel() != null) {
                nodeKey = event.getNodeModel().getNodeKey();
                nodeName = event.getNodeModel().getNodeName();
            }
            // flowlong v1.2.4 TaskServiceImpl#taskNotify 在 COMPLETE 路径 by design 传 nodeModel=null，
            // 节点信息从 flwTask.taskKey 取（flwTask 创建时已复制 nodeModel.nodeKey/nodeName）。
            if (nodeKey == null && task != null) {
                nodeKey = task.getTaskKey();
            }
            if (nodeName == null && task != null) {
                nodeName = task.getTaskName();
            }
            if (nodeKey == null) {
                log.warn("TaskEvent without nodeKey: eventType={} taskId={} instanceId={}",
                        event.getEventType(),
                        task == null ? null : task.getId(),
                        task == null ? null : task.getInstanceId());
            }
            payload.put("nodeKey", nodeKey);
            payload.put("nodeName", nodeName);

            if (event.getFlowCreator() != null) {
                payload.put("creator", Map.of(
                        "id", event.getFlowCreator().getCreateId(),
                        "name", event.getFlowCreator().getCreateBy() == null ? "" : event.getFlowCreator().getCreateBy()
                ));
            }

            payload.put("actors", toActorList(event.getTaskActors()));

            sender.send(payload);
        } catch (Exception e) {
            log.warn("on TaskEvent failed: {}", e.toString());
        }
    }

    @EventListener
    public void onInstance(InstanceEvent event) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "instance." + (event.getEventType() == null ? "unknown" : event.getEventType().name()));

            if (event.getFlwInstance() != null) {
                payload.put("instanceId", String.valueOf(event.getFlwInstance().getId()));
                payload.put("businessKey", event.getFlwInstance().getBusinessKey());
                payload.put("currentNodeKey", event.getFlwInstance().getCurrentNodeKey());
                payload.put("currentNodeName", event.getFlwInstance().getCurrentNodeName());
                payload.put("tenantId", event.getFlwInstance().getTenantId());
            }

            payload.put("nodeKey", event.getNodeModel() == null ? null : event.getNodeModel().getNodeKey());
            payload.put("nodeName", event.getNodeModel() == null ? null : event.getNodeModel().getNodeName());

            if (event.getFlowCreator() != null) {
                payload.put("creator", Map.of(
                        "id", event.getFlowCreator().getCreateId(),
                        "name", event.getFlowCreator().getCreateBy() == null ? "" : event.getFlowCreator().getCreateBy()
                ));
            }

            sender.send(payload);
        } catch (Exception e) {
            log.warn("on InstanceEvent failed: {}", e.toString());
        }
    }

    private List<Map<String, Object>> toActorList(List<FlwTaskActor> actors) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (actors == null) return out;
        for (FlwTaskActor a : actors) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getActorId());
            m.put("name", a.getActorName());
            m.put("type", a.getActorType());
            out.add(m);
        }
        return out;
    }
}
