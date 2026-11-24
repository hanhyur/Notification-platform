package me.hanhyur.notify.api.common.database;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseRoleRouterTest {

    @Test
    void anActiveReadOnlyTransactionUsesReplica() {
        assertThat(DatabaseRoleRouter.selectRole(true, true))
                .isEqualTo(DatabaseRole.REPLICA);
    }

    @Test
    void writesAndCallsOutsideTransactionsUsePrimary() {
        assertThat(DatabaseRoleRouter.selectRole(true, false))
                .isEqualTo(DatabaseRole.PRIMARY);
        assertThat(DatabaseRoleRouter.selectRole(false, false))
                .isEqualTo(DatabaseRole.PRIMARY);
        assertThat(DatabaseRoleRouter.selectRole(false, true)).isEqualTo(DatabaseRole.PRIMARY);
    }
}
