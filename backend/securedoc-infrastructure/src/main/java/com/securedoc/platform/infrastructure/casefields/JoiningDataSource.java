package com.securedoc.platform.infrastructure.casefields;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * Task 0061: platform-fields takes a {@link DataSource} and commits its own work. Inside a transaction of the app its work
 * joins that transaction instead (a case and its values are stored together or not at all, over one connection): its
 * commit, rollback and close do nothing there. A failure is thrown to the caller, whose transaction rolls back.
 */
public final class JoiningDataSource extends DelegatingDataSource {

    public JoiningDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
        DataSource target = obtainTargetDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !(TransactionSynchronizationManager.getResource(target) instanceof ConnectionHolder)) {
            return super.getConnection();
        }
        Connection joined = DataSourceUtils.doGetConnection(target);
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "commit", "rollback", "setAutoCommit" -> null;
                    case "close" -> {
                        DataSourceUtils.doReleaseConnection(joined, target);
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> {
                        try {
                            yield method.invoke(joined, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
                });
    }
}
