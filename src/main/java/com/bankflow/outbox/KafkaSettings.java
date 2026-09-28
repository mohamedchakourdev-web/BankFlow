package com.bankflow.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bankflow.kafka")
public class KafkaSettings {

    private String transferTopic = "bankflow.transfer-events";

    public String getTransferTopic() {
        return transferTopic;
    }

    public void setTransferTopic(String transferTopic) {
        this.transferTopic = transferTopic;
    }
}
