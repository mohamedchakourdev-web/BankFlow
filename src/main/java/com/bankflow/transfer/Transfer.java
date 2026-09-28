package com.bankflow.transfer;

import com.bankflow.account.Account;
import com.bankflow.common.Currency;
import com.bankflow.transaction.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 64)
    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Setter(AccessLevel.NONE)
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false)
    private Account sourceAccount;

    @Setter(AccessLevel.NONE)
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false)
    private Account destinationAccount;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Positive
    @Digits(integer = 15, fraction = 4)
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transfer")
    private List<Transaction> transactions = new ArrayList<>();

    public static Transfer completed(
            Account source,
            Account destination,
            BigDecimal amount,
            Currency currency,
            String reference
    ) {
        Transfer transfer = new Transfer();
        transfer.reference = reference;
        transfer.sourceAccount = source;
        transfer.destinationAccount = destination;
        transfer.amount = amount;
        transfer.currency = currency;
        transfer.status = TransferStatus.COMPLETED;
        return transfer;
    }
}
