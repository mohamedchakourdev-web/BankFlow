package com.bankflow.notification;

import com.bankflow.account.Account;
import com.bankflow.audit.MissingTransferException;
import com.bankflow.common.Currency;
import com.bankflow.event.IdempotentEventProcessor;
import com.bankflow.outbox.TransferCompletedEvent;
import com.bankflow.transfer.Transfer;
import com.bankflow.transfer.repository.TransferRepository;
import com.bankflow.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public static final String CONSUMER = "notification";
    public static final String TRANSFER = "TRANSFER";

    private final NotificationRepository notificationRepository;
    private final TransferRepository transferRepository;
    private final IdempotentEventProcessor idempotentEventProcessor;

    public NotificationService(
            NotificationRepository notificationRepository,
            TransferRepository transferRepository,
            IdempotentEventProcessor idempotentEventProcessor
    ) {
        this.notificationRepository = notificationRepository;
        this.transferRepository = transferRepository;
        this.idempotentEventProcessor = idempotentEventProcessor;
    }

    public void transferCompleted(TransferCompletedEvent event) {
        idempotentEventProcessor.process(event.eventId(), CONSUMER, () -> {
            Transfer transfer = transferRepository.findById(event.transferId())
                    .orElseThrow(() -> new MissingTransferException(event.eventId(), event.transferId()));
            Account source = transfer.getSourceAccount();
            Account destination = transfer.getDestinationAccount();
            User sender = source.getUser();
            User receiver = destination.getUser();
            String amount = displayAmount(transfer.getAmount(), transfer.getCurrency());
            if (sender.getId().equals(receiver.getId())) {
                save(sender, NotificationType.TRANSFER_SENT, "Transfer completed",
                        "Transfer of " + amount + " completed.", transfer.getId());
                return;
            }
            save(sender, NotificationType.TRANSFER_SENT, "Transfer sent",
                    "Transfer of " + amount + " completed.", transfer.getId());
            save(receiver, NotificationType.TRANSFER_RECEIVED, "Transfer received",
                    "You received " + amount + ".", transfer.getId());
        });
    }

    private void save(User user, NotificationType type, String title, String message, java.util.UUID transferId) {
        notificationRepository.saveAndFlush(Notification.create(user, type, title, message, TRANSFER, transferId));
        log.info("Created notification userId={} type={} transferId={}", user.getId(), type, transferId);
    }

    static String displayAmount(BigDecimal amount, Currency currency) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency.name();
    }
}
