package me.hanhyur.notify.api.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.util.UUID;

public final class ReplicatedPostgres implements AutoCloseable {

    private static final String IMAGE = "postgres:18.6";
    private static final String SETUP = "/opt/replication/prepare-primary.sh";
    private static final String STANDBY = "/opt/replication/start-standby.sh";
    private final Network network = Network.newNetwork();
    private final String replicationPassword = UUID.randomUUID().toString();
    private final PostgreSQLContainer primary = new PostgreSQLContainer(IMAGE)
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withPassword(UUID.randomUUID().toString())
            .withEnv("REPLICATION_PASSWORD", replicationPassword)
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-replication/prepare-primary.sh"), SETUP);
    private final GenericContainer<?> replica = new GenericContainer<>(IMAGE)
            .withNetwork(network)
            .withExposedPorts(5432)
            .withEnv("REPLICATION_PASSWORD", replicationPassword)
            .withCopyFileToContainer(MountableFile.forClasspathResource("postgres-replication/start-standby.sh"), STANDBY)
            .withCreateContainerCmdModifier(command -> command.withUser("postgres").withEntrypoint("/bin/bash", STANDBY))
            .waitingFor(Wait.forListeningPort());

    public synchronized void start() {
        if (replica.isRunning()) {
            return;
        }
        try {
            primary.start();
            var setup = primary.execInContainer("/bin/bash", SETUP);
            if (setup.getExitCode() != 0) {
                throw new IllegalStateException("Primary replication setup failed: " + setup.getStderr());
            }
            replica.start();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException(interrupted);
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IllegalStateException(failure);
        }
    }

    public String primaryUrl() {
        return primary.getJdbcUrl();
    }

    public String replicaUrl() {
        return "jdbc:postgresql://" + replica.getHost() + ":" + replica.getMappedPort(5432) + "/" + primary.getDatabaseName();
    }

    public String username() {
        return primary.getUsername();
    }

    public String password() {
        return primary.getPassword();
    }

    @Override
    public void close() {
        try {
            replica.close();
        } finally {
            try {
                primary.close();
            } finally {
                network.close();
            }
        }
    }
}
