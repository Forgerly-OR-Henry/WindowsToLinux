package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Registers authenticated restored revisions without overwriting a different platform secret. / 注册已认证恢复修订且不覆盖不同的平台秘密。 */
final class RestoredSecretRegistrar {
    private final ApplicationSecretRepository metadata;
    private final DesktopSecretStoreService stores;

    RestoredSecretRegistrar(ApplicationSecretRepository metadata, DesktopSecretStoreService stores) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    void register(String applicationId, List<ResolvedSecretRevision> revisions, CredentialStorageMode mode,
                  char[] masterPassword, Instant createdAt) throws SQLException, SecretStoreException {
        try {
            for (ResolvedSecretRevision revision : revisions) {
                var existing = metadata.findRevision(revision.reference());
                CredentialStorageMode selected = existing.map(StoredApplicationSecretRevision::credentialMode)
                        .orElse(mode);
                try (SecretStore store = stores.open(selected, masterPassword)) {
                    register(applicationId, revision, selected, createdAt, store, existing.orElse(null));
                }
            }
        } finally {
            if (masterPassword != null) Arrays.fill(masterPassword, '\0');
        }
    }

    private void register(String applicationId, ResolvedSecretRevision revision, CredentialStorageMode mode,
                          Instant createdAt, SecretStore store, StoredApplicationSecretRevision existing)
            throws SQLException, SecretStoreException {
        String key = existing == null ? "application/" + applicationId + "/" + key(revision)
                : existing.credentialKey();
        StoredApplicationSecretRevision expected = new StoredApplicationSecretRevision(
                revision.reference(), key, mode, createdAt);
        char[] stored = store.read(key).orElse(null);
        try {
            if (stored != null) {
                try (ResolvedSecretRevision comparison = new ResolvedSecretRevision(revision.reference(), stored)) {
                    if (!comparison.digest().equals(revision.digest())) {
                        throw new SQLException("restored secret revision conflicts with an existing platform value");
                    }
                }
            } else {
                char[] value = revision.copyCharacters();
                try { store.save(key, value); } finally { Arrays.fill(value, '\0'); }
            }
            if (existing == null) {
                try {
                    metadata.saveRevision(expected);
                } catch (SQLException | RuntimeException exception) {
                    try { store.delete(key); } catch (SecretStoreException cleanup) { exception.addSuppressed(cleanup); }
                    throw exception;
                }
            }
        } finally {
            if (stored != null) Arrays.fill(stored, '\0');
        }
    }

    private static String key(ResolvedSecretRevision revision) {
        try {
            String identity = revision.reference().identifier() + "\0" + revision.reference().revision();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
