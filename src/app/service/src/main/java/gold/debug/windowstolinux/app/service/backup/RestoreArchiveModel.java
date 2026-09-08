package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationCodec;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationDocument;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Locally decoded exact schema-v4 state used before any target mutation. / 任何目标修改前在本地解码的精确 schema-v4 状态。 */
record RestoreArchiveModel(
        PreparedBackupActivation activation,
        Map<String, BackupConfigurationDocument> configurations,
        Optional<DatabaseMaterial> database
) {
    static RestoreArchiveModel load(PreparedBackupActivation activation) throws IOException {
        BackupConfigurationCodec codec = new BackupConfigurationCodec();
        LinkedHashMap<String, BackupConfigurationDocument> configurations = new LinkedHashMap<>();
        List<DatabaseOwner> databases = new ArrayList<>();
        for (BackupComponent component : activation.validation().manifest().inventory().components()) {
            BackupMember member = exactMember(activation, component.configurationSnapshotPath(),
                    BackupMemberKind.CONFIGURATION);
            BackupConfigurationDocument document = codec.readActivation(readMember(activation, member));
            if (!document.configuration().applicationId().equals(component.managedApplicationId())
                    || !document.runtimeConfiguration().healthCheck().equals(
                    component.runtime().healthCheck().toHealthCheck())) {
                throw new IOException("backup component configuration identity or runtime contract differs");
            }
            for (var file : document.resourceBindings().fileBindings()) {
                exactMember(activation, "data/" + component.componentId() + "/files/"
                        + file.bindingId() + ".pax", BackupMemberKind.PERSISTENT_CONTENT);
            }
            document.resourceBindings().databaseBindings().orElseThrow().forEach(binding ->
                    databases.add(new DatabaseOwner(component, binding)));
            configurations.put(component.componentId(), document);
        }
        Optional<DatabaseMaterial> database = database(activation, databases);
        return new RestoreArchiveModel(activation, Map.copyOf(configurations), database);
    }

    private static Optional<DatabaseMaterial> database(
            PreparedBackupActivation activation, List<DatabaseOwner> databases) throws IOException {
        BackupDatabaseType type = activation.validation().manifest().inventory().database().type();
        List<BackupMember> members = activation.validation().manifest().members().stream()
                .filter(member -> member.kind() == BackupMemberKind.DATABASE).toList();
        if (type == BackupDatabaseType.NONE) {
            if (!databases.isEmpty() || !members.isEmpty()) {
                throw new IOException("database-free manifest contains database bindings or material");
            }
            return Optional.empty();
        }
        if (type == BackupDatabaseType.SQLITE) {
            throw BackupException.create(BackupFailureType.RESTORE_PREFLIGHT_FAILED,
                    "SQLite physical activation is not supported by the current managed release mapping");
        }
        if (databases.size() != 1 || members.size() != 1) {
            throw new IOException("automatic restore requires exactly one reviewed database binding and artifact");
        }
        DatabaseOwner owner = databases.getFirst(); BackupMember member = members.getFirst();
        if (!member.path().equals("database/" + owner.binding().databaseId() + ".dump")
                || type(owner.binding().connection().engine()) != type
                || owner.binding().connection() instanceof ManagedDatabaseConnection.Server server
                && !owner.component().secretReferences().orElseThrow().contains(server.passwordReference())) {
            throw new IOException("database artifact, binding, type, or secret identity differs from the manifest");
        }
        DatabaseBackupArtifact artifact = new DatabaseBackupArtifact(
                "db-" + member.sha256().substring(0, 32), member.size(), member.sha256(),
                activation.validation().manifest().inventory().database(),
                List.of("database artifact identity reconstructed from the validated archive member"));
        DatabaseRestoreRequest request = new DatabaseRestoreRequest(
                activation.validation().manifest().applicationId(), owner.component().managedApplicationId(),
                activation.localCandidate().inspection().applicationId() + "-"
                        + activation.validation().archiveSha256().substring(0, 16),
                profile(owner.binding().connection()), artifact);
        return Optional.of(new DatabaseMaterial(request,
                activation.restoreCandidate().root().resolve(member.path()).normalize(), artifact));
    }

    private static BackupMember exactMember(
            PreparedBackupActivation activation, String path, BackupMemberKind kind) throws IOException {
        List<BackupMember> matches = activation.validation().manifest().members().stream()
                .filter(member -> member.path().equals(path) && member.kind() == kind).toList();
        if (matches.size() != 1) throw new IOException("backup lacks one exact required member: " + path);
        return matches.getFirst();
    }

    private static byte[] readMember(PreparedBackupActivation activation, BackupMember member) throws IOException {
        Path path = activation.restoreCandidate().root().resolve(member.path()).normalize();
        if (!path.startsWith(activation.restoreCandidate().root())
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != member.size()
                || member.size() > 4L * 1024 * 1024) {
            throw new IOException("extracted backup member changed before activation decoding");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != member.size()) throw new IOException("extracted backup member read was incomplete");
        if (!sha256(bytes).equals(member.sha256())) {
            throw new IOException("extracted backup member changed after archive validation");
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static DatabaseConnectionProfile profile(ManagedDatabaseConnection connection) {
        if (connection instanceof ManagedDatabaseConnection.Sqlite sqlite) {
            return new DatabaseConnectionProfile.Sqlite(sqlite.fileName());
        }
        ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) connection;
        return new DatabaseConnectionProfile.Server(type(server.engine()), server.host(), server.port(),
                server.database(), server.username(), server.passwordReference(), server.tlsRequired());
    }

    private static BackupDatabaseType type(ManagedDatabaseEngineType type) {
        return switch (type) {
            case SQLITE -> BackupDatabaseType.SQLITE;
            case POSTGRESQL -> BackupDatabaseType.POSTGRESQL;
            case MYSQL -> BackupDatabaseType.MYSQL;
            case MARIADB -> BackupDatabaseType.MARIADB;
            case REDIS -> throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE, "complete Redis backup is unsupported");
        };
    }

    record DatabaseMaterial(DatabaseRestoreRequest request, Path localArtifact, DatabaseBackupArtifact artifact) { }
    private record DatabaseOwner(BackupComponent component, ManagedDatabaseBinding binding) { }
}
