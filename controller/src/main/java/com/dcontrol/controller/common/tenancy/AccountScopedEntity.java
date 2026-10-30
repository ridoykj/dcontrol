package com.dcontrol.controller.common.tenancy;

import com.dcontrol.controller.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base for business rows owned by an account. Hibernate stamps {@code id_account_key} from the current
 * actor on insert and adds it to every query, so a repository never returns another account's rows.
 * Only the system actor may assign the account explicitly.
 */
@MappedSuperclass
public abstract class AccountScopedEntity extends BaseEntity {

    @TenantId
    @Column(name = "id_account_key", nullable = false, updatable = false)
    private UUID accountKey;

    public UUID getAccountKey() {
        return accountKey;
    }

    protected void assignAccount(UUID accountKey) {
        this.accountKey = accountKey;
    }
}
