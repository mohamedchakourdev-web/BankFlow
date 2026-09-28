package com.bankflow.account;

import com.bankflow.common.Currency;
import com.bankflow.user.User;
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
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 34)
    @Column(name = "account_number", nullable = false, unique = true, length = 34)
    private String accountNumber;

    @Setter(AccessLevel.NONE)
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Setter(AccessLevel.NONE)
    @NotNull
    @DecimalMin(value = "0.00")
    @Digits(integer = 15, fraction = 4)
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Account open(User owner, String accountNumber, Currency currency) {
        Account account = new Account();
        account.user = owner;
        account.accountNumber = accountNumber;
        account.balance = BigDecimal.ZERO.setScale(4);
        account.currency = currency;
        account.status = AccountStatus.ACTIVE;
        return account;
    }

    public void debit(BigDecimal amount) {
        BigDecimal updated = this.balance.subtract(amount);
        if (updated.signum() < 0) {
            throw new IllegalStateException("Balance cannot be negative");
        }
        this.balance = updated;
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }
}
