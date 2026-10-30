package com.dcontrol.controller.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

/**
 * Maps the standard header. Subclasses rename {@code id} and {@code version} with
 * {@code @AttributeOverride} to their {@code id_<t>_key} / {@code id_<t>_ver} columns.
 * Modification and creation columns are filled by the TR_<t>_evt trigger and read back after each write.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(name = "ver", nullable = false)
    private Long version;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "dtt_mod", insertable = false, updatable = false)
    private Instant modifiedAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "id_usr_mod", insertable = false, updatable = false)
    private UUID modifiedBy;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "id_evt_key", insertable = false, updatable = false)
    private UUID eventKey;

    @Generated(event = EventType.INSERT)
    @Column(name = "dtt_created", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "id_usr_created", insertable = false, updatable = false)
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public UUID getModifiedBy() {
        return modifiedBy;
    }

    public UUID getEventKey() {
        return eventKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
