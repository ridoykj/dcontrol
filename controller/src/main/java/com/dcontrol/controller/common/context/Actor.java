package com.dcontrol.controller.common.context;

import com.dcontrol.controller.common.persistence.SystemKeys;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is acting in the current thread. Drives tenancy (which account's rows are visible) and the
 * event header written by every write transaction.
 */
public record Actor(ActorType type, UUID accountKey, UUID subjectKey, EventChannel channel, String label) {

    public Actor {
        Objects.requireNonNull(type);
        Objects.requireNonNull(accountKey);
        Objects.requireNonNull(subjectKey);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(label);
    }

    public static Actor user(UUID accountKey, UUID userKey, EventChannel channel) {
        return new Actor(ActorType.USER, accountKey, userKey, channel, "user:" + userKey);
    }

    public static Actor device(UUID accountKey, UUID deviceKey) {
        return new Actor(ActorType.DEVICE, accountKey, deviceKey, EventChannel.WS_DEVICE, "device:" + deviceKey);
    }

    /** Cross-account actor for jobs and pre-authentication lookups. Sees every account's rows. */
    public static Actor system(String purpose, EventChannel channel) {
        return new Actor(ActorType.SYSTEM, SystemKeys.SYSTEM_ACCOUNT, SystemKeys.SYSTEM_USER, channel,
                "system:" + purpose);
    }

    public static Actor anonymous() {
        return new Actor(ActorType.ANONYMOUS, SystemKeys.NO_ACCOUNT, SystemKeys.SYSTEM_USER, EventChannel.HTTP,
                "anonymous");
    }

    /** User key recorded in id_usr_mod / id_usr_created. */
    public UUID auditUserKey() {
        return type == ActorType.USER ? subjectKey : SystemKeys.SYSTEM_USER;
    }

    /** Account recorded on T_EVENT. */
    public UUID eventAccountKey() {
        return type == ActorType.USER || type == ActorType.DEVICE ? accountKey : SystemKeys.SYSTEM_ACCOUNT;
    }
}
