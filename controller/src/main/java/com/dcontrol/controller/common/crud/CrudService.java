package com.dcontrol.controller.common.crud;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * List / get / logical delete / restore for an account-scoped entity. Delete sets {@code is_active = false};
 * subclasses hook into {@link #beforeDelete} and {@link #beforeRestore} for FSM moves and side effects.
 */
public abstract class CrudService<E extends AccountScopedEntity, R extends ScopedRepository<E>> {

    protected final R repository;
    private final String entityName;

    protected CrudService(R repository, String entityName) {
        this.repository = repository;
        this.entityName = entityName;
    }

    @Transactional(readOnly = true)
    public List<E> list(boolean deleted) {
        return repository.findByActiveOrderByCreatedAtDesc(!deleted);
    }

    @Transactional(readOnly = true)
    public E get(UUID id) {
        return find(id, true);
    }

    @Transactional
    public void delete(UUID id) {
        E entity = find(id, true);
        beforeDelete(entity);
        entity.setActive(false);
        repository.saveAndFlush(entity);
        afterDelete(entity);
    }

    @Transactional
    public E restore(UUID id) {
        E entity = find(id, false);
        beforeRestore(entity);
        entity.setActive(true);
        E saved = repository.saveAndFlush(entity);
        afterRestore(saved);
        return saved;
    }

    protected E find(UUID id, boolean active) {
        return repository.findByIdAndActive(id, active).orElseThrow(() -> ApiException.notFound(entityName));
    }

    protected void beforeDelete(E entity) {
    }

    protected void afterDelete(E entity) {
    }

    protected void beforeRestore(E entity) {
    }

    protected void afterRestore(E entity) {
    }
}
