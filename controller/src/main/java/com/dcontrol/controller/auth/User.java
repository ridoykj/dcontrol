package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_user")
@AttributeOverride(name = "id", column = @Column(name = "id_usr_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_usr_ver", nullable = false))
public class User extends AccountScopedEntity {

    @Column(name = "tx_email", nullable = false, length = 254)
    private String email;

    @Column(name = "tx_password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "tx_display_name", nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_role", nullable = false, length = 16)
    private UserRole role;

    @Column(name = "dtt_email_verified")
    private Instant emailVerifiedAt;

    protected User() {
    }

    /** Creates the owner of a new account. Must run as the system actor. */
    public static User owner(UUID accountKey, String email, String passwordHash, String displayName) {
        User user = new User();
        user.assignAccount(accountKey);
        user.email = email;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.role = UserRole.OWNER;
        return user;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public void markEmailVerified(Instant at) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = at;
        }
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }
}
