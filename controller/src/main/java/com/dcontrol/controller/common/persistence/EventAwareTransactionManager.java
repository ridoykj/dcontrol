package com.dcontrol.controller.common.persistence;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.ActorContext;
import com.dcontrol.controller.common.web.RequestIdFilter;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.hibernate.Session;
import org.slf4j.MDC;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Opens a T_EVENT row at the start of every read-write transaction and exposes its key and the acting
 * user to the header triggers through transaction-local settings.
 */
public class EventAwareTransactionManager extends JpaTransactionManager {

    private static final String OPEN_EVENT_SQL = """
            WITH e AS (
                INSERT INTO t_event (id_evt_key, id_evt_ver, is_active, dtt_mod, id_usr_mod, dtt_created,
                                     id_usr_created, id_account_key, tx_type, tx_actor, tx_request_id)
                VALUES (uuidv7(), 0, TRUE, now(), ?, now(), ?, ?, ?, ?, ?)
                RETURNING id_evt_key)
            SELECT set_config('dc.evt_key', e.id_evt_key::TEXT, TRUE), set_config('dc.usr_key', ?, TRUE) FROM e
            """;

    public EventAwareTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        if (definition.isReadOnly()) {
            return;
        }
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        Actor actor = ActorContext.current();
        holder.getEntityManager().unwrap(Session.class).doWork(connection -> openEvent(connection, actor));
    }

    private static void openEvent(Connection connection, Actor actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(OPEN_EVENT_SQL)) {
            statement.setObject(1, actor.auditUserKey());
            statement.setObject(2, actor.auditUserKey());
            statement.setObject(3, actor.eventAccountKey());
            statement.setString(4, actor.channel().name());
            statement.setString(5, actor.label());
            statement.setString(6, MDC.get(RequestIdFilter.MDC_KEY));
            statement.setString(7, actor.auditUserKey().toString());
            statement.executeQuery().close();
        }
    }
}
