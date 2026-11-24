package com.securedoc.platform.api.licensing;

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
 * Task 0057: gives the usage ledger the connection of the transaction that is running, so its entry is saved or undone with
 * the caller's own rows. The ledger ends its own transactions; here those calls do nothing, and Spring's transaction
 * decides. An error the ledger throws reaches the caller and undoes everything. Outside a transaction it refuses.
 */
final class TransactionConnections extends AbstractDataSource {

    private static final Set<String> LEFT_TO_THE_TRANSACTION = Set.of("commit", "rollback", "setAutoCommit",
            "setTransactionIsolation", "close");

    private final DataSource target;

    TransactionConnections(DataSource target) {
        this.target = Objects.requireNonNull(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!TransactionSynchronizationManager.hasResource(target)) {
            throw new SQLException("Usage is recorded only inside a database transaction");
        }
        // The transaction's own connection; Spring gives it back when the transaction ends.
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
