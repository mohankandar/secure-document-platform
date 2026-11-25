package com.securedoc.platform.api.notifications;

import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/**
 * Task 0059: gives the platform's notification stores the connection of the transaction that is running, when there is one, so
 * a decision, its messages and the case's own change are saved or undone together. The stores end their own transactions; here
 * those calls do nothing and Spring's transaction decides (an error a store throws still reaches the caller, and the
 * transaction can only be undone then). Outside a transaction (the sender, the tick) it is the plain connection.
 */
final class JoiningDataSource extends AbstractDataSource {

    private static final Set<String> LEFT_TO_THE_TRANSACTION = Set.of("commit", "rollback", "setAutoCommit",
            "setTransactionIsolation", "close");

    private final DataSource target;

    JoiningDataSource(DataSource target) {
        this.target = Objects.requireNonNull(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(target)) {
            return target.getConnection();
        }
        Connection connection = DataSourceUtils.doGetConnection(target);
        DataSourceUtils.doReleaseConnection(connection, target);
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (LEFT_TO_THE_TRANSACTION.contains(method.getName())) return null;
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }
}
