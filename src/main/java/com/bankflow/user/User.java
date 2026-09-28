package com.bankflow.user;

import com.bankflow.account.Account;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Email
    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String password;

    @NotBlank
    @Size(max = 100)
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Setter(AccessLevel.NONE)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "user")
    private List<Account> accounts = new ArrayList<>();

    public static User createCustomer(String email, String passwordHash, String firstName, String lastName) {
        return create(email, passwordHash, firstName, lastName, Role.CUSTOMER);
    }

    public static User createAdmin(String email, String passwordHash, String firstName, String lastName) {
        return create(email, passwordHash, firstName, lastName, Role.ADMIN);
    }

    private static User create(String email, String passwordHash, String firstName, String lastName, Role role) {
        User user = new User();
        user.email = email;
        user.password = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.role = role;
        return user;
    }
}
