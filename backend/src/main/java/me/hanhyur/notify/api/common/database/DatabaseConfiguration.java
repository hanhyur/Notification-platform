package me.hanhyur.notify.api.common.database;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class DatabaseConfiguration {

    @Bean(defaultCandidate = false)
    @Qualifier("primaryPool")
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource primaryPool(DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> details) {
        JdbcConnectionDetails connection = details.getIfAvailable();
        if (connection == null) {
            return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        }
        return DataSourceBuilder.create().type(HikariDataSource.class)
                .driverClassName(connection.getDriverClassName()).url(connection.getJdbcUrl())
                .username(connection.getUsername()).password(connection.getPassword()).build();
    }

    @Bean(defaultCandidate = false)
    @Qualifier("replicaPool")
    @ConfigurationProperties("notification.database.replica.hikari")
    @Nullable
    HikariDataSource replicaPool(@Qualifier("primaryPool") HikariDataSource primary,
            @Value("${notification.database.replica.url:}") String url,
            @Value("${notification.database.replica.username:}") String username,
            @Value("${notification.database.replica.password:}") String password) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        var replica = new HikariDataSource();
        replica.setJdbcUrl(url);
        replica.setUsername(StringUtils.hasText(username) ? username : primary.getUsername());
        replica.setPassword(password.isEmpty() ? primary.getPassword() : password);
        replica.setReadOnly(true);
        return replica;
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("primaryPool") HikariDataSource primary,
            @Qualifier("replicaPool") ObjectProvider<HikariDataSource> optionalReplica, SlowQueryMonitor monitor) {
        DataSource writes = monitor.monitor(primary, DatabaseRole.PRIMARY);
        HikariDataSource replica = optionalReplica.getIfAvailable();
        if (replica == null) {
            return writes;
        }
        DataSource reads = monitor.monitor(replica, DatabaseRole.REPLICA);
        return new LazyConnectionDataSourceProxy(new DatabaseRoleRouter(writes, reads));
    }
}
