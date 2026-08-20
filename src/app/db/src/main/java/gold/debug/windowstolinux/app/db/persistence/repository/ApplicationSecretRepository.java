package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Stores immutable application-secret metadata and release bindings, never secret values. / 保存不可变应用秘密元数据与发布绑定，绝不保存秘密值。 */
public final class ApplicationSecretRepository {
    private final DesktopConnectionFactory connections;

    /** Creates the repository. / 创建仓库。 */
    public ApplicationSecretRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Saves immutable secret-revision metadata. / 保存不可变秘密修订元数据。 */
    public void saveRevision(StoredApplicationSecretRevision revision) throws SQLException {
        Objects.requireNonNull(revision, "revision");
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                Optional<StoredApplicationSecretRevision> existing = findRevision(connection, revision.reference());
                if (existing.isPresent()) {
                    if (!existing.orElseThrow().equals(revision)) {
                        throw new SQLException("application secret revisions are immutable");
                    }
                    return;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO application_secret_revision (
                            secret_identifier, revision, credential_key, credential_mode, created_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, revision.reference().identifier());
                    statement.setLong(2, revision.reference().revision());
                    statement.setString(3, revision.credentialKey());
                    statement.setString(4, revision.credentialMode().name());
                    statement.setLong(5, revision.createdAt().toEpochMilli());
                    statement.executeUpdate();
                }
            });
        }
    }

    /** Finds immutable secret-revision metadata. / 查找不可变秘密修订元数据。 */
    public Optional<StoredApplicationSecretRevision> findRevision(SecretReference reference) throws SQLException {
        try (Connection connection = connections.open()) {
            return findRevision(connection, reference);
        }
    }

    /** Binds one exact secret-revision set to a release. / 将一个精确秘密修订集合绑定到发布。 */
    public void bindRelease(String applicationId, String releaseIdentity, List<SecretReference> references) throws SQLException {
        applicationId = identity(applicationId, "applicationId");
        releaseIdentity = identity(releaseIdentity, "releaseIdentity");
        Set<SecretReference> expected = Set.copyOf(Objects.requireNonNull(references, "references"));
        if (expected.size() != references.size()) {
            throw new IllegalArgumentException("release secret references must be unique");
        }
        String application = applicationId;
        String release = releaseIdentity;
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> bindRelease(connection, application, release, expected));
        }
    }

    static void bindRelease(Connection connection, String application, String release,
                            Set<SecretReference> expected) throws SQLException {
        for (SecretReference reference : expected) {
            if (findRevision(connection, reference).isEmpty()) {
                throw new SQLException("release secret reference has not been registered");
            }
        }
        boolean exists = bindingExists(connection, application, release);
        Set<SecretReference> stored = findReleaseReferences(connection, application, release);
        if (exists) {
            if (!stored.equals(expected)) {
                throw new SQLException("application release secret references are immutable");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_release_secret_binding (application_id, release_identity) VALUES (?, ?)
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_release_secret_reference (
                    application_id, release_identity, secret_identifier, secret_revision
                ) VALUES (?, ?, ?, ?)
                """)) {
            for (SecretReference reference : expected) {
                statement.setString(1, application);
                statement.setString(2, release);
                statement.setString(3, reference.identifier());
                statement.setLong(4, reference.revision());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Reports whether a secret revision is retained by a release. / 报告秘密修订是否由发布保留。 */
    public boolean isReferenced(SecretReference reference) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM application_release_secret_reference
                     WHERE secret_identifier=? AND secret_revision=? LIMIT 1
                     """)) {
            statement.setString(1, reference.identifier());
            statement.setLong(2, reference.revision());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Optional<StoredApplicationSecretRevision> findRevision(Connection connection, SecretReference reference)
            throws SQLException {
        Objects.requireNonNull(reference, "reference");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT credential_key, credential_mode, created_at FROM application_secret_revision
                WHERE secret_identifier=? AND revision=?
                """)) {
            statement.setString(1, reference.identifier());
            statement.setLong(2, reference.revision());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(new StoredApplicationSecretRevision(reference, result.getString("credential_key"),
                            CredentialStorageMode.valueOf(result.getString("credential_mode")),
                            Instant.ofEpochMilli(result.getLong("created_at"))));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("saved application secret revision violates current validation rules", exception);
                }
            }
        }
    }

    private static Set<SecretReference> findReleaseReferences(Connection connection, String application, String release)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT secret_identifier, secret_revision FROM application_release_secret_reference
                WHERE application_id=? AND release_identity=? ORDER BY secret_identifier, secret_revision
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            try (ResultSet result = statement.executeQuery()) {
                Set<SecretReference> references = new LinkedHashSet<>();
                while (result.next()) {
                    references.add(new SecretReference(result.getString("secret_identifier"),
                            result.getLong("secret_revision")));
                }
                return Set.copyOf(references);
            }
        }
    }

    private static boolean bindingExists(Connection connection, String application, String release) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM application_release_secret_binding WHERE application_id=? AND release_identity=?
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String identity(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a bounded release identity");
        }
        return value;
    }
}
