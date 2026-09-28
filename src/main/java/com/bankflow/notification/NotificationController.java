package com.bankflow.notification;

import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.exception.BankFlowException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    @GetMapping
    @Operation(summary = "List my notifications", description = "Newest first. Default size 20, maximum 100. Ownership is enforced.")
    public Page<NotificationResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return notificationQueryService.list(requireUser(principal), page, size);
    }

    @PatchMapping("/read-all")
    public MarkAllReadResponse markAllRead(@AuthenticationPrincipal AuthenticatedUser principal) {
        return notificationQueryService.markAllRead(requireUser(principal));
    }

    @GetMapping("/{id}")
    public NotificationResponse get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id
    ) {
        return notificationQueryService.get(requireUser(principal), id);
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id
    ) {
        return notificationQueryService.markRead(requireUser(principal), id);
    }

    private AuthenticatedUser requireUser(AuthenticatedUser principal) {
        if (principal == null) {
            throw new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return principal;
    }
}
