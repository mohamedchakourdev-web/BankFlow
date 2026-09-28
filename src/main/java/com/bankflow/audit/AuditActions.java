package com.bankflow.audit;

public final class AuditActions {

    public static final String USER_REGISTERED = "USER_REGISTERED";
    public static final String USER_LOGIN_SUCCESS = "USER_LOGIN_SUCCESS";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String TRANSFER_COMPLETED = "TRANSFER_COMPLETED";

    public static final String USER = "USER";
    public static final String AUTHENTICATION = "AUTHENTICATION";
    public static final String ACCOUNT = "ACCOUNT";
    public static final String TRANSFER = "TRANSFER";

    public static final String CONSUMER = "audit";

    private AuditActions() {
    }
}
