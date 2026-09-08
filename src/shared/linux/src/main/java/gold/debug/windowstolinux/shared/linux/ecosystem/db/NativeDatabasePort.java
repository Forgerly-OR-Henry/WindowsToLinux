package gold.debug.windowstolinux.shared.linux.ecosystem.db;

import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import java.util.*;

/** Typed native database operations; passwords and SQL travel only through sensitive input streams. */
public interface NativeDatabasePort {
    enum FailureType { AUTH_REQUIRED, STATE_CHANGED, INITIALIZATION_FAILED, MANUAL_RESTORE_REQUIRED, VERSION_UNSUPPORTED, ACTION_FAILED }
    /** A bounded protocol failure that contains no command output, SQL or password. */
    final class DatabaseFailure extends RuntimeException {
        private final FailureType reason;
        public DatabaseFailure(FailureType reason) { super("native DB: " + reason.name()); this.reason = reason; }
        public FailureType reason() { return reason; }
    }
    Inventory inspectDatabase(DatabaseEngineType engine) throws LinuxOperationException;
    Inventory installDatabase(DatabaseEngineType engine, PackageCandidate target, Optional<Replacement> replacement) throws LinuxOperationException;
    Instance startDatabase(Instance instance) throws LinuxOperationException;
    Instance confirmDatabaseRestored(Instance instance, char[] administratorPassword) throws LinuxOperationException;
    Target inspectDatabaseTarget(Instance instance, String applicationId, String database, String username, char[] administratorPassword) throws LinuxOperationException;
    Target prepareDatabaseTarget(Instance instance, String applicationId, String database, String username,
                                 String secretIdentifier, long secretRevision, char[] applicationPassword, char[] administratorPassword) throws LinuxOperationException;
    Target initializeDatabase(Target target, String sourceSha256, byte[] sql, char[] applicationPassword,
                              boolean existingSchemaChangeApproved) throws LinuxOperationException;

    record PackageCandidate(String name, String packageVersion, String engineVersion) {
        public PackageCandidate { name = token(name); packageVersion = token(packageVersion); engineVersion = token(engineVersion); }
    }
    record Instance(DatabaseEngineType engine, String id, String version, int port, String service, String dataDirectory,
                    String fingerprint, boolean running) {
        public Instance {
            Objects.requireNonNull(engine); id = text(id); version = text(version); service = token(service);
            dataDirectory = text(dataDirectory); fingerprint = digest(fingerprint);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid DB port");
        }
    }
    record Inventory(DatabaseEngineType engine, List<Instance> instances, Optional<PackageCandidate> candidate, List<String> conflicts) {
        public Inventory {
            Objects.requireNonNull(engine); instances = List.copyOf(instances); candidate = Objects.requireNonNull(candidate); conflicts = List.copyOf(conflicts);
            if (instances.size() > 64 || conflicts.size() > 64 || instances.stream().anyMatch(instance -> instance.engine() != engine)) throw new IllegalArgumentException("invalid DB inventory");
        }
    }
    /** One operation's exact instance/version approval; data recovery remains a separate user action. */
    record Replacement(String serverId, Instance previous, PackageCandidate target, UUID operation, boolean backupConfirmed, boolean downtimeConfirmed) {
        public Replacement {
            serverId = token(serverId); Objects.requireNonNull(previous); Objects.requireNonNull(target); Objects.requireNonNull(operation);
            if (!backupConfirmed || !downtimeConfirmed) throw new IllegalArgumentException("DB replacement requires backup and downtime confirmation");
        }
    }
    enum InitializationState { UNOWNED, EMPTY, STARTED, COMPLETE, FAILED, WAITING_FOR_RESTORE }
    record Target(Instance instance, String applicationId, String database, String username, boolean exists, boolean empty,
                  String ownershipToken, InitializationState initialization, String initializedSourceSha256) {
        public Target {
            Objects.requireNonNull(instance); applicationId = token(applicationId); database = token(database); username = token(username);
            Objects.requireNonNull(ownershipToken); Objects.requireNonNull(initialization); Objects.requireNonNull(initializedSourceSha256);
            if (!ownershipToken.isEmpty()) ownershipToken = digest(ownershipToken);
            if (!initializedSourceSha256.isEmpty()) initializedSourceSha256 = digest(initializedSourceSha256);
            if (!exists && !empty) throw new IllegalArgumentException("absent DB cannot contain data");
        }
        public boolean owned() { return !ownershipToken.isEmpty(); }
    }
    private static String token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:+~@%=-]{0,255}")) throw new IllegalArgumentException("invalid DB token");
        return value;
    }
    private static String text(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid DB text");
        return value;
    }
    private static String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid DB fingerprint");
        return value;
    }
}
