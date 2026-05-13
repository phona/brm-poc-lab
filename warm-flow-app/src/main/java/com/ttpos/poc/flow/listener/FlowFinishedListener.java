package com.ttpos.poc.flow.listener;

import com.ttpos.poc.flow.ext.FlowGatewayNotifier;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.listener.Listener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FlowFinishedListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(FlowFinishedListener.class);

    private final FlowGatewayNotifier flowGatewayNotifier;

    public FlowFinishedListener(FlowGatewayNotifier flowGatewayNotifier) {
        this.flowGatewayNotifier = flowGatewayNotifier;
    }

    @Override
    public void notify(ListenerVariable listenerVariable) {
        Instance ins = listenerVariable.getInstance();
        String businessId = ins == null ? null : ins.getBusinessId();
        if (businessId == null) {
            return;
        }
        try {
            flowGatewayNotifier.notifyFinished(businessId, "approved");
            log.info("flow-finished notified for {}", businessId);
        } catch (Exception e) {
            log.error("flow-finished callback failed", e);
        }
    }
}
