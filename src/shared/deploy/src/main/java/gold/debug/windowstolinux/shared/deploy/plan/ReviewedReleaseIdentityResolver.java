package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Derives the immutable release identity from every input that can change a built or running release. / 从可改变构建或运行发布的每项输入推导不可变发布身份。 */
public final class ReviewedReleaseIdentityResolver {
    private ReviewedReleaseIdentityResolver() { }

    /** Pins the exact prepared tools into a new release without changing legacy identities. / 将精确准备的工具绑定到新发布，不改变旧版身份。 */
    public static String bind(String reviewedIdentity, gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet tools) {
        if (tools.selections().isEmpty()) return reviewedIdentity;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            update(digest, "reviewed-release-tools-v1"); update(digest, reviewedIdentity);
            update(digest, gold.debug.windowstolinux.shared.model.toolchain.ToolchainBindingCodec.identity(tools));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Returns a deterministic release SHA-256 covering source, configuration, database bindings, secrets, and runtime. / 返回覆盖源码、配置、数据库绑定、秘密与运行时的确定性发布 SHA-256。 */
    public static String from(ReviewedDeploymentRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "reviewed-release-v5");
            update(digest, request.sourceRevision().sourceSha256());
            update(digest, request.facts().buildTool().name());
            update(digest, request.configuration().sha256());
            request.secretReferences().stream()
                    .sorted(Comparator.comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision))
                    .forEach(reference -> {
                        update(digest, reference.identifier());
                        update(digest, Long.toString(reference.revision()));
                    });
            request.fileBindings().stream().sorted(Comparator.comparing(gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding::bindingId)).forEach(binding -> {
                update(digest, binding.bindingId()); update(digest, binding.dataPath().path());
                update(digest, binding.dataPath().access().name()); update(digest, binding.dataPath().schemaId());
                update(digest, Boolean.toString(binding.dataPath().reversible())); update(digest, binding.resourceType().name());
                update(digest, binding.location().type().name()); update(digest, binding.location().path()); update(digest, binding.seedFile()); update(digest, binding.contentSha256());
            });
            databaseBindings(digest, request);
            runtime(digest, request.runtime());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }

    private static void runtime(MessageDigest digest, DeploymentRuntimeSpecification runtime) {
        try {
            digest.update(new gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec().write(runtime));
            digest.update(new gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec().write(runtime.healthCheck()));
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("invalid release runtime", failure); }
    }

    private static void databaseBindings(MessageDigest digest, ReviewedDeploymentRequest request) {
        if (request.databaseBindings().isEmpty()) {
            update(digest, "database-bindings-unreviewed");
            return;
        }
        update(digest, "database-bindings-reviewed");
        request.databaseBindings().orElseThrow().forEach(binding -> {
            update(digest, binding.databaseId());
            update(digest, binding.connection().engine().name());
            if (binding.connection() instanceof ManagedDatabaseConnection.Sqlite sqlite) {
                update(digest, sqlite.fileName());
                update(digest, sqlite.location().type().name()); update(digest, sqlite.location().path()); update(digest, sqlite.accessPath());
                update(digest, sqlite.seedFile()); sqlite.initializationFiles().forEach(file -> update(digest,file));
                return;
            }
            ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) binding.connection();
            update(digest, server.host());
            update(digest, Integer.toString(server.port()));
            update(digest, server.database());
            update(digest, server.username());
            update(digest, server.passwordReference().identifier());
            update(digest, Long.toString(server.passwordReference().revision()));
            update(digest, Boolean.toString(server.tlsRequired()));
        });
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
