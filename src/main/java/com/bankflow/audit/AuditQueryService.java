package com.bankflow.audit;

import com.bankflow.common.exception.BankFlowException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditQueryService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> list(int page, int size) {
        if (page < 0) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Page size must be between 1 and 100");
        }
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return auditLogRepository.findAll(pageRequest).map(log -> AuditLogResponse.from(log, objectMapper));
    }
}
