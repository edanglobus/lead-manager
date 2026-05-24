package com.leadmanager.api.user;

import com.leadmanager.api.common.audit.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A provider in the marketplace — the only kind of user in v1
 * (customers are embedded data on Jobs, not authenticated principals).
 * <p>
 * Mutable fields ({@code displayName}, {@code phone}, {@code passwordHash})
 * carry setters because the registration / profile-update flows need them;
 * {@code email} is intentionally setter-less because changing the email of
 * an existing identity is a separate, audited flow that will live in a later
 * slice (and must coexist with the unique index on {@code LOWER(email)}).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(nullable = false, length = 254)
    private String email;

    @Setter
    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Setter
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Setter
    @Column(length = 32)
    private String phone;

    @Builder
    private User(String email, String passwordHash, String displayName, String phone) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.phone = phone;
    }
}
