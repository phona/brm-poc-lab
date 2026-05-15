package com.ttpos.poc.flow.bootstrap;

import com.ttpos.poc.flow.listener.FlowFinishedListener;
import com.ttpos.poc.flow.listener.SameSubmitterAutoSkipListener;
import com.ttpos.poc.flow.listener.WebhookPermissionListener;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.entity.Skip;
import org.dromara.warm.flow.core.enums.NodeType;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.NodeService;
import org.dromara.warm.flow.core.service.SkipService;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.entity.FlowNode;
import org.dromara.warm.flow.orm.entity.FlowSkip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class FlowBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowBootstrap.class);

    private final DefService defService;
    private final NodeService nodeService;
    private final SkipService skipService;

    public FlowBootstrap(DefService defService, NodeService nodeService, SkipService skipService) {
        this.defService = defService;
        this.nodeService = nodeService;
        this.skipService = skipService;
    }

    @Override
    public void run(ApplicationArguments args) {
        install();
    }

    public void install() {
        installTransferTest();
        installMultiTest();
        installCountersignTest();
        installVoteTest();
        installTtposIntegrationTest();
    }

    private void installTtposIntegrationTest() {
        try {
            if (!nodeService.getByFlowCode("ttpos_transfer_test").isEmpty()) {
                log.info("flow ttpos_transfer_test already installed");
                return;
            }
            FlowDefinition def = buildTtposIntegrationDefinition();
            saveAndPublish(def, "ttpos_transfer_test");
        } catch (Exception e) {
            log.error("FlowBootstrap installTtposIntegrationTest failed", e);
        }
    }

    /**
     * Flow used to verify ttpos integration end-to-end.
     * Approver node uses WebhookPermissionListener to call the gateway at runtime,
     * which then calls ttpos RBAC (GetStaffsByAccessPath) scoped to companyUuid
     * read from flow variables. The result is written back to permissionList so the
     * engine enforces it for skip(PASS).
     */
    private FlowDefinition buildTtposIntegrationDefinition() {
        FlowDefinition def = new FlowDefinition();
        def.setFlowCode("ttpos_transfer_test");
        def.setFlowName("PoC ttpos integration");
        def.setVersion("1");
        def.setIsPublish(0);

        FlowNode start = new FlowNode();
        start.setNodeCode("start");
        start.setNodeName("开始");
        start.setNodeType(NodeType.START.getKey());
        start.setVersion("1");
        start.setSkipList(List.of(skip("start", NodeType.START.getKey(), "ttpos_approve", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode approve = new FlowNode();
        approve.setNodeCode("ttpos_approve");
        approve.setNodeName("ttpos RBAC 审批");
        approve.setNodeType(NodeType.BETWEEN.getKey());
        approve.setVersion("1");
        // Engine sees WEBHOOK_RESOLVE: prefix and the listener resolves it at runtime.
        // Use 'create' type: fires when the engine creates the next node's task, BEFORE
        // flow_user records are persisted from permissionFlag (the 'assignment' type
        // existed in earlier versions but is unreliable in 1.3.8).
        approve.setPermissionFlag("WEBHOOK_RESOLVE:ACCESS:transfer_order_approve");
        approve.setListenerType("create");
        approve.setListenerPath(WebhookPermissionListener.class.getName());
        approve.setSkipList(List.of(skip("ttpos_approve", NodeType.BETWEEN.getKey(), "end", NodeType.END.getKey(), SkipType.PASS.getKey())));

        FlowNode end = new FlowNode();
        end.setNodeCode("end");
        end.setNodeName("结束");
        end.setNodeType(NodeType.END.getKey());
        end.setVersion("1");

        List<Node> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(approve);
        nodes.add(end);
        def.setNodeList(nodes);
        return def;
    }

    private void installTransferTest() {
        try {
            if (!nodeService.getByFlowCode("transfer_test").isEmpty()) {
                log.info("flow transfer_test already installed");
                return;
            }
            FlowDefinition def = buildTransferDefinition();
            List<Node> nodes = new ArrayList<>(def.getNodeList());
            List<Skip> allSkips = new ArrayList<>();
            for (Node n : nodes) {
                if (n.getSkipList() != null) {
                    allSkips.addAll(n.getSkipList());
                }
                n.setSkipList(null);
            }
            def.setNodeList(null);
            def.setIsPublish(0);
            def.setVersion("1");
            for (Node n : nodes) {
                n.setVersion("1");
            }
            if (!defService.save(def)) {
                log.error("defService.save returned false");
                return;
            }
            Long defId = def.getId();
            if (defId == null) {
                log.error("definition id null after save");
                return;
            }
            for (Node n : nodes) {
                n.setDefinitionId(defId);
            }
            for (Skip s : allSkips) {
                s.setDefinitionId(defId);
            }
            nodeService.saveBatch(nodes);
            skipService.saveBatch(allSkips);
            defService.publish(defId);
            log.info("published transfer_test defId={}", defId);
        } catch (Exception e) {
            log.error("FlowBootstrap failed", e);
        }
    }

    private void installMultiTest() {
        try {
            if (!nodeService.getByFlowCode("transfer_multi_test").isEmpty()) {
                log.info("flow transfer_multi_test already installed");
                return;
            }
            FlowDefinition def = buildMultiDefinition();
            List<Node> nodes = new ArrayList<>(def.getNodeList());
            List<Skip> allSkips = new ArrayList<>();
            for (Node n : nodes) {
                if (n.getSkipList() != null) {
                    allSkips.addAll(n.getSkipList());
                }
                n.setSkipList(null);
            }
            def.setNodeList(null);
            def.setIsPublish(0);
            def.setVersion("1");
            for (Node n : nodes) {
                n.setVersion("1");
            }
            if (!defService.save(def)) {
                log.error("defService.save returned false for transfer_multi_test");
                return;
            }
            Long defId = def.getId();
            if (defId == null) {
                log.error("definition id null after save for transfer_multi_test");
                return;
            }
            for (Node n : nodes) {
                n.setDefinitionId(defId);
            }
            for (Skip s : allSkips) {
                s.setDefinitionId(defId);
            }
            nodeService.saveBatch(nodes);
            skipService.saveBatch(allSkips);
            defService.publish(defId);
            log.info("published transfer_multi_test defId={}", defId);
        } catch (Exception e) {
            log.error("FlowBootstrap installMultiTest failed", e);
        }
    }

    private FlowDefinition buildTransferDefinition() {
        FlowDefinition def = new FlowDefinition();
        def.setFlowCode("transfer_test");
        def.setFlowName("PoC transfer");
        def.setVersion("1");
        def.setIsPublish(0);

        FlowNode start = new FlowNode();
        start.setNodeCode("start");
        start.setNodeName("开始");
        start.setNodeType(NodeType.START.getKey());
        start.setVersion("1");
        start.setSkipList(List.of(skip("start", NodeType.START.getKey(), "ship_approve", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode ship = new FlowNode();
        ship.setNodeCode("ship_approve");
        ship.setNodeName("发货店审批");
        ship.setNodeType(NodeType.BETWEEN.getKey());
        ship.setVersion("1");
        ship.setPermissionFlag("user_01");
        ship.setListenerType("create");
        ship.setListenerPath(SameSubmitterAutoSkipListener.class.getName());
        ship.setSkipList(List.of(skip("ship_approve", NodeType.BETWEEN.getKey(), "recv_mgr", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode recv = new FlowNode();
        recv.setNodeCode("recv_mgr");
        recv.setNodeName("收货店上级审批");
        recv.setNodeType(NodeType.BETWEEN.getKey());
        recv.setVersion("1");
        // Warm-Flow 1.3.8 removed the 'permission' listener; use SpEL variable expression instead.
        // Gateway pre-resolves recvApprover and passes it in FlowParams.variable.
        recv.setPermissionFlag("#{#recvApprover}");
        // Warm-Flow runs listener "finish" on the current task node during termination (not on the END node).
        recv.setListenerType("finish");
        recv.setListenerPath(FlowFinishedListener.class.getName());
        recv.setSkipList(List.of(skip("recv_mgr", NodeType.BETWEEN.getKey(), "end", NodeType.END.getKey(), SkipType.PASS.getKey())));

        FlowNode end = new FlowNode();
        end.setNodeCode("end");
        end.setNodeName("结束");
        end.setNodeType(NodeType.END.getKey());
        end.setVersion("1");

        List<Node> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(ship);
        nodes.add(recv);
        nodes.add(end);
        def.setNodeList(nodes);
        return def;
    }

    private Skip skip(String nowCode, Integer nowType, String nextCode, Integer nextType, String skipTypeKey) {
        FlowSkip s = new FlowSkip();
        s.setNowNodeCode(nowCode);
        s.setNowNodeType(nowType);
        s.setNextNodeCode(nextCode);
        s.setNextNodeType(nextType);
        s.setSkipType(skipTypeKey);
        return s;
    }

    private FlowDefinition buildMultiDefinition() {
        FlowDefinition def = new FlowDefinition();
        def.setFlowCode("transfer_multi_test");
        def.setFlowName("PoC multi-approver");
        def.setVersion("1");
        def.setIsPublish(0);

        FlowNode start = new FlowNode();
        start.setNodeCode("start");
        start.setNodeName("开始");
        start.setNodeType(NodeType.START.getKey());
        start.setVersion("1");
        start.setSkipList(List.of(skip("start", NodeType.START.getKey(), "multi_approve", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode multi = new FlowNode();
        multi.setNodeCode("multi_approve");
        multi.setNodeName("多人审批");
        multi.setNodeType(NodeType.BETWEEN.getKey());
        multi.setVersion("1");
        multi.setPermissionFlag("user_a@@user_b@@user_c");
        multi.setSkipList(List.of(skip("multi_approve", NodeType.BETWEEN.getKey(), "end", NodeType.END.getKey(), SkipType.PASS.getKey())));

        FlowNode end = new FlowNode();
        end.setNodeCode("end");
        end.setNodeName("结束");
        end.setNodeType(NodeType.END.getKey());
        end.setVersion("1");

        List<Node> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(multi);
        nodes.add(end);
        def.setNodeList(nodes);
        return def;
    }

    private void installCountersignTest() {
        try {
            if (!nodeService.getByFlowCode("transfer_countersign_test").isEmpty()) {
                log.info("flow transfer_countersign_test already installed");
                return;
            }
            FlowDefinition def = buildCountersignDefinition();
            saveAndPublish(def, "transfer_countersign_test");
        } catch (Exception e) {
            log.error("FlowBootstrap installCountersignTest failed", e);
        }
    }

    private void installVoteTest() {
        try {
            if (!nodeService.getByFlowCode("transfer_vote_test").isEmpty()) {
                log.info("flow transfer_vote_test already installed");
                return;
            }
            FlowDefinition def = buildVoteDefinition();
            saveAndPublish(def, "transfer_vote_test");
        } catch (Exception e) {
            log.error("FlowBootstrap installVoteTest failed", e);
        }
    }

    private void saveAndPublish(FlowDefinition def, String flowCode) {
        List<Node> nodes = new ArrayList<>(def.getNodeList());
        List<Skip> allSkips = new ArrayList<>();
        for (Node n : nodes) {
            if (n.getSkipList() != null) {
                allSkips.addAll(n.getSkipList());
            }
            n.setSkipList(null);
        }
        def.setNodeList(null);
        def.setIsPublish(0);
        def.setVersion("1");
        for (Node n : nodes) {
            n.setVersion("1");
        }
        if (!defService.save(def)) {
            log.error("defService.save returned false for {}", flowCode);
            return;
        }
        Long defId = def.getId();
        if (defId == null) {
            log.error("definition id null after save for {}", flowCode);
            return;
        }
        for (Node n : nodes) {
            n.setDefinitionId(defId);
        }
        for (Skip s : allSkips) {
            s.setDefinitionId(defId);
        }
        nodeService.saveBatch(nodes);
        skipService.saveBatch(allSkips);
        defService.publish(defId);
        log.info("published {} defId={}", flowCode, defId);
    }

    private FlowDefinition buildCountersignDefinition() {
        FlowDefinition def = new FlowDefinition();
        def.setFlowCode("transfer_countersign_test");
        def.setFlowName("PoC countersign");
        def.setVersion("1");
        def.setIsPublish(0);

        FlowNode start = new FlowNode();
        start.setNodeCode("start");
        start.setNodeName("开始");
        start.setNodeType(NodeType.START.getKey());
        start.setVersion("1");
        start.setSkipList(List.of(skip("start", NodeType.START.getKey(), "submit", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode submit = new FlowNode();
        submit.setNodeCode("submit");
        submit.setNodeName("提交申请");
        submit.setNodeType(NodeType.BETWEEN.getKey());
        submit.setVersion("1");
        submit.setPermissionFlag("admin");
        submit.setSkipList(List.of(skip("submit", NodeType.BETWEEN.getKey(), "countersign_approve", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode cs = new FlowNode();
        cs.setNodeCode("countersign_approve");
        cs.setNodeName("会签审批");
        cs.setNodeType(NodeType.BETWEEN.getKey());
        cs.setVersion("1");
        cs.setPermissionFlag("user_a@@user_b@@user_c");
        cs.setNodeRatio(BigDecimal.valueOf(100)); // 会签：必须100%通过
        cs.setSkipList(List.of(
                skip("countersign_approve", NodeType.BETWEEN.getKey(), "end", NodeType.END.getKey(), SkipType.PASS.getKey()),
                skip("countersign_approve", NodeType.BETWEEN.getKey(), "submit", NodeType.BETWEEN.getKey(), SkipType.REJECT.getKey())
        ));

        FlowNode end = new FlowNode();
        end.setNodeCode("end");
        end.setNodeName("结束");
        end.setNodeType(NodeType.END.getKey());
        end.setVersion("1");

        List<Node> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(submit);
        nodes.add(cs);
        nodes.add(end);
        def.setNodeList(nodes);
        return def;
    }

    private FlowDefinition buildVoteDefinition() {
        FlowDefinition def = new FlowDefinition();
        def.setFlowCode("transfer_vote_test");
        def.setFlowName("PoC vote");
        def.setVersion("1");
        def.setIsPublish(0);

        FlowNode start = new FlowNode();
        start.setNodeCode("start");
        start.setNodeName("开始");
        start.setNodeType(NodeType.START.getKey());
        start.setVersion("1");
        start.setSkipList(List.of(skip("start", NodeType.START.getKey(), "submit", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode submit = new FlowNode();
        submit.setNodeCode("submit");
        submit.setNodeName("提交申请");
        submit.setNodeType(NodeType.BETWEEN.getKey());
        submit.setVersion("1");
        submit.setPermissionFlag("admin");
        submit.setSkipList(List.of(skip("submit", NodeType.BETWEEN.getKey(), "vote_approve", NodeType.BETWEEN.getKey(), SkipType.PASS.getKey())));

        FlowNode vt = new FlowNode();
        vt.setNodeCode("vote_approve");
        vt.setNodeName("票签审批");
        vt.setNodeType(NodeType.BETWEEN.getKey());
        vt.setVersion("1");
        vt.setPermissionFlag("user_a@@user_b@@user_c");
        vt.setNodeRatio(BigDecimal.valueOf(50)); // 票签：50%通过即可
        vt.setSkipList(List.of(
                skip("vote_approve", NodeType.BETWEEN.getKey(), "end", NodeType.END.getKey(), SkipType.PASS.getKey()),
                skip("vote_approve", NodeType.BETWEEN.getKey(), "submit", NodeType.BETWEEN.getKey(), SkipType.REJECT.getKey())
        ));

        FlowNode end = new FlowNode();
        end.setNodeCode("end");
        end.setNodeName("结束");
        end.setNodeType(NodeType.END.getKey());
        end.setVersion("1");

        List<Node> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(submit);
        nodes.add(vt);
        nodes.add(end);
        def.setNodeList(nodes);
        return def;
    }
}
