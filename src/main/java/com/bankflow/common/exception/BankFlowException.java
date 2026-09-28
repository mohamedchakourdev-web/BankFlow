package com.bankflow.common.exception;

import org.springframework.http.HttpStatus;

public class BankFlowException extends RuntimeException {

    private final HttpStatus status;

    public BankFlowException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
