package com.ttpos.flpoc.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * RocketMQ event sender：把审批流事件以 JSON 消息发到 broker。
 *
 * 引擎事件投递的**唯一**通道（vm04 联调起改造；系统未上线，无 webhook 后向兼容）。
 * 多租户隔离用 tag：每个 review env 配自己的 tag（如 pr448），同一个 topic
 * 不同 ttpos consumer group + 不同 tag 过滤即可隔离消费。
 *
 * 不依赖 rocketmq-spring-boot-starter（对 Spring Boot 3 支持不稳），直接用
 * 4.x 原生 client + 一个 bean 起 producer。
 */
@Component
@EnableConfigurationProperties(RocketMQEventSender.Properties.class)
public class RocketMQEventSender {

    private static final Logger log = LoggerFactory.getLogger(RocketMQEventSender.class);

    private final Properties props;
    private final ObjectMapper objectMapper;
    private DefaultMQProducer producer;

    public RocketMQEventSender(Properties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() throws Exception {
        if (!props.enabled) {
            log.info("RocketMQEventSender disabled, skip producer init");
            return;
        }
        if (props.nameServer == null || props.nameServer.isBlank()) {
            log.warn("RocketMQEventSender enabled but nameServer blank, skip producer init");
            return;
        }

        AclClientRPCHook aclHook = null;
        if (props.accessKey != null && !props.accessKey.isBlank()
                && props.secretKey != null && !props.secretKey.isBlank()) {
            aclHook = new AclClientRPCHook(new SessionCredentials(props.accessKey, props.secretKey));
        }

        producer = new DefaultMQProducer(props.producerGroup, aclHook);
        producer.setNamesrvAddr(props.nameServer);
        producer.setSendMsgTimeout(props.sendTimeoutMs);
        producer.setRetryTimesWhenSendFailed(props.retryMax);
        producer.start();
        log.info("RocketMQEventSender started nameServer={} producerGroup={} topic={} tag={}",
                props.nameServer, props.producerGroup, props.topic, props.tag);

        if (props.autoCreateTopic) {
            ensureTopic();
        }
    }

    /**
     * 启动期建 topic（idempotent，已存在等价于刷新配置）。
     *
     * 走 producer.createTopic 借助 broker 默认 topic TBW102 模板：
     *   - broker autoCreateTopicEnable=true 时 即可工作（vm04 联调环境默认值）
     *   - prod broker autoCreateTopicEnable=false 时 此调用会失败：建议改用 K8s
     *     pre-install Job + mqadmin updateTopic 显式建 topic（见仓库 README）
     *
     * 任何异常 log warn 不阻塞 service 启动 — producer 自己第一次 send 时也会
     * 兜底 autoCreate（同样依赖 broker 默认值），双重保险。
     */
    private void ensureTopic() {
        try {
            producer.createTopic(MixAll.DEFAULT_TOPIC, props.topic, props.queueNums, 0);
            log.info("RocketMQEventSender ensured topic={} queueNums={}", props.topic, props.queueNums);
        } catch (Exception e) {
            log.warn("RocketMQEventSender ensureTopic failed topic={} err={} (broker autoCreate may still kick in on first send)",
                    props.topic, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (producer != null) {
            producer.shutdown();
            log.info("RocketMQEventSender stopped");
        }
    }

    /**
     * 发送审批流事件。线程安全，同步发送。
     * 失败由 client 自身重试 retryMax 次；最终失败抛异常给调用方决定是否吞掉。
     */
    public void send(Map<String, Object> payload) {
        if (producer == null) {
            return; // disabled / init skipped
        }
        // payload schema 与下游 ttpos engine.TranslateEvent 对齐
        payload.putIfAbsent("eventId", UUID.randomUUID().toString());
        payload.putIfAbsent("timestamp", java.time.Instant.now().toString());

        String eventId = String.valueOf(payload.get("eventId"));
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            Message msg = new Message(props.topic, props.tag, eventId, body);
            // 显式 set keys 给 broker 索引（运维 `mqadmin queryMsgByKey` 用）
            msg.setKeys(eventId);
            SendResult result = producer.send(msg);
            log.info("rocketmq delivered eventType={} eventId={} msgId={} status={}",
                    payload.get("eventType"), eventId, result.getMsgId(), result.getSendStatus());
        } catch (Exception e) {
            // client 已经重试过，到这里就是确认失败 - 记录后由 FlowLong autoconfigure
            // 外层 try/catch 兜底，不让单条事件失败阻塞引擎主流程。
            log.error("rocketmq send failed eventType={} eventId={} err={}",
                    payload.get("eventType"), eventId, e.getMessage());
        }
    }

    public boolean isEnabled() {
        return props.enabled && producer != null;
    }

    @ConfigurationProperties(prefix = "flowlong.rocketmq")
    public static class Properties {
        /** 是否启用 RocketMQ 投递。默认 true：这是唯一通道，禁用 = 引擎事件不会推出。 */
        public boolean enabled = true;
        public String nameServer;
        public String producerGroup = "flowlong-app-producer";
        public String topic = "approval.flow.event";
        /** 多租户路由 tag，例如 pr448 / prod；空 = 不打 tag，下游订阅全量。 */
        public String tag = "";
        public String accessKey;
        public String secretKey;
        public int sendTimeoutMs = 3000;
        public int retryMax = 2;
        /** 启动期是否调 createTopic 确保 topic 存在；prod 关闭+用 K8s Job 显式建。 */
        public boolean autoCreateTopic = true;
        /** createTopic 时 read/write queue 数；改大可提高消费并行度（须 ≥ consumer 实例数）。 */
        public int queueNums = 4;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getNameServer() { return nameServer; }
        public void setNameServer(String nameServer) { this.nameServer = nameServer; }
        public String getProducerGroup() { return producerGroup; }
        public void setProducerGroup(String producerGroup) { this.producerGroup = producerGroup; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public int getSendTimeoutMs() { return sendTimeoutMs; }
        public void setSendTimeoutMs(int sendTimeoutMs) { this.sendTimeoutMs = sendTimeoutMs; }
        public int getRetryMax() { return retryMax; }
        public void setRetryMax(int retryMax) { this.retryMax = retryMax; }
        public boolean isAutoCreateTopic() { return autoCreateTopic; }
        public void setAutoCreateTopic(boolean autoCreateTopic) { this.autoCreateTopic = autoCreateTopic; }
        public int getQueueNums() { return queueNums; }
        public void setQueueNums(int queueNums) { this.queueNums = queueNums; }
    }
}
