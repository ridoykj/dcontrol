package com.dcontrol.controller.common.persistence;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.ActorContext;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work as a given actor in a new transaction. The actor must be bound before the transaction starts
 * because Hibernate resolves the tenant when the session opens.
 */
@Component
public class ActorTx {

    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;

    public ActorTx(PlatformTransactionManager transactionManager) {
        this.readWrite = new TransactionTemplate(transactionManager);
        this.readWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    public <T> T write(Actor actor, Supplier<T> work) {
        return ActorContext.callAs(actor, () -> readWrite.execute(status -> work.get()));
    }

    public void writeVoid(Actor actor, Runnable work) {
        write(actor, () -> {
            work.run();
            return null;
        });
    }

    public <T> T read(Actor actor, Supplier<T> work) {
        return ActorContext.callAs(actor, () -> readOnly.execute(status -> work.get()));
    }
}
