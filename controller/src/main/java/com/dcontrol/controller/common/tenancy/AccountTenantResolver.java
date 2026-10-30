package com.dcontrol.controller.common.tenancy;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.ActorContext;
import com.dcontrol.controller.common.persistence.SystemKeys;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

@Component
public class AccountTenantResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        Actor actor = ActorContext.current();
        return switch (actor.type()) {
            case SYSTEM -> SystemKeys.SYSTEM_ACCOUNT;
            case ANONYMOUS -> SystemKeys.NO_ACCOUNT;
            case USER, DEVICE -> actor.accountKey();
        };
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return SystemKeys.SYSTEM_ACCOUNT.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
