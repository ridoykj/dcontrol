package com.dcontrol.controller.common.persistence;

import java.util.UUID;

/** Fixed keys seeded by V2__account_user.sql. */
public final class SystemKeys {

    public static final UUID SYSTEM_ACCOUNT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    public static final UUID SYSTEM_USER = UUID.fromString("01900000-0000-7000-8000-000000000002");

    /** Tenant of anonymous callers. No row carries it, so tenant-filtered queries return nothing. */
    public static final UUID NO_ACCOUNT = new UUID(0L, 0L);

    private SystemKeys() {
    }
}
