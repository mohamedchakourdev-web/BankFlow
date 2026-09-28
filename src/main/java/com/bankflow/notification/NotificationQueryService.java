package com.bankflow.notification;

import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.exception.BankFlowException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationQueryService {

    static final int MAX_PAGE_SIZE = 100;
    static final String NOT_FOUND = "Notification not found";

    private final NotificationRepository notificationRepository;

    public NotificationQueryService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(AuthenticatedUser principal, int page, int size) {
        return notificationRepository.findByUser_Id(principal.id(), pageRequest(page, size))
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public NotificationResponse get(AuthenticatedUser principal, UUID id) {
        return notificationRepository.findByIdAndUser_Id(id, principal.id())
                .map(NotificationResponse::from)
                .orElseThrow(this::notFound);
    }

    @Transactional
    public NotificationResponse markRead(AuthenticatedUser principal, UUID id) {
        Notification notification = notificationRepository.findByIdAndUser_Id(id, principal.id())
                .orElseThrow(this::notFound);
        notification.markRead();
        return NotificationResponse.from(notification);
    }

    @Transactional
    public MarkAllReadResponse markAllRead(AuthenticatedUser principal) {
        int updated = notificationRepository.markAllRead(principal.id());
        return new MarkAllReadResponse(updated);
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BankFlowException(HttpStatus.BAD_REQUEST, "Page size must be between 1 and 100");
        }
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private BankFlowException notFound() {
        return new BankFlowException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }
}
