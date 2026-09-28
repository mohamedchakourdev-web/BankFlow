package com.bankflow.idempotency;

import com.bankflow.common.exception.BankFlowException;
import com.bankflow.transfer.dto.CreateTransferRequest;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TransferRequestFingerprint {

    private TransferRequestFingerprint() {
    }

    public static String of(CreateTransferRequest request) {
        String canonical = request.sourceAccountId()
                + "\n"
                + request.destinationAccountId()
                + "\n"
                + canonicalAmount(request.amount())
                + "\n"
                + (request.currency() == null ? "" : request.currency());
        return HexFormat.of().formatHex(sha256(canonical));
    }

    private static String canonicalAmount(BigDecimal amount) {
        try {
            return amount.setScale(4, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException ex) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Amount must be a valid decimal");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
