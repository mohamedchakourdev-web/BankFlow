package com.bankflow.outbox;

import org.springframework.stereotype.Component;

@Component
public class OutboxInsertProbe {

    public void afterInsert() {
    }
}
