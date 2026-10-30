package com.dcontrol.controller.common.crud;

import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Repository for account-scoped entities. Use these derived queries instead of {@code findById}:
 * they go through JPQL and therefore always carry the tenant restriction.
 */
@NoRepositoryBean
public interface ScopedRepository<E extends AccountScopedEntity> extends JpaRepository<E, UUID> {

    Optional<E> findByIdAndActive(UUID id, boolean active);

    List<E> findByActiveOrderByCreatedAtDesc(boolean active);
}
