package me.hanhyur.notify.api.common.database;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Map;

final class DatabaseRoleRouter extends AbstractRoutingDataSource {

    DatabaseRoleRouter(DataSource primary, DataSource replica) {
        setTargetDataSources(Map.of(DatabaseRole.PRIMARY, primary, DatabaseRole.REPLICA, replica));
        setLenientFallback(false);
        initialize();
    }

    @Override
    protected DatabaseRole determineCurrentLookupKey() {
        return selectRole(TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    static DatabaseRole selectRole(boolean transactionActive, boolean readOnly) {
        return transactionActive && readOnly ? DatabaseRole.REPLICA : DatabaseRole.PRIMARY;
    }
}
