package com.bankflow.transfer.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransferReferenceGenerator {

    public String next() {
        return "TRF" + UUID.randomUUID().toString().replace("-", "");
    }
}
