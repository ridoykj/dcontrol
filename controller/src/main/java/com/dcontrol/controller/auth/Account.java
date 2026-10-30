package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.persistence.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** The tenant. Every business row references one through {@code id_account_key}. */
@Entity
@Table(name = "t_account")
@AttributeOverride(name = "id", column = @Column(name = "id_account_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_account_ver", nullable = false))
public class Account extends BaseEntity {

    @Column(name = "tx_name", nullable = false, length = 120)
    private String name;

    protected Account() {
    }

    public Account(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
