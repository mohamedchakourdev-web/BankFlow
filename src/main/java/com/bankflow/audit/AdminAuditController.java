package com.bankflow.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-logs")
@Tag(name = "Admin audit")
public class AdminAuditController {

    private final AuditQueryService auditQueryService;

    public AdminAuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @Operation(summary = "List audit logs", description = "ADMIN only. Newest first. Default size 20, maximum 100. Append-only.")
    public Page<AuditLogResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return auditQueryService.list(page, size);
    }
}
