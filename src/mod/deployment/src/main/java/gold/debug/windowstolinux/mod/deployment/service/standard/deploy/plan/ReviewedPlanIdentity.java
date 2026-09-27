package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

import gold.debug.windowstolinux.mod.deployment.db.DeploymentHealthCodec;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentRequest;

/** Stable pre-execution review identity, distinct from the eventual release manifest identity. / 稳定的执行前审阅身份，区别于最终发布清单身份。 */
public final class ReviewedPlanIdentity {
    /** No instances are required. / 无需实例。 */
    private ReviewedPlanIdentity() {
    }

    /** Hashes all reviewed inputs with explicit framing and the new plan domain. / 使用明确分界及新计划域散列全部已审阅输入。
     * @param request complete reviewed input / 完整审阅输入
     * @return lower-case SHA-256 plan identity / 小写SHA-256计划身份 */
    public static String from(ReviewedDeploymentRequest request) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                text(out, "deployment-reviewed-plan-v1");
                text(out, request.server().id());
                text(out, request.material().snapshot().id().toString());
                text(out, request.archive().sourceManifestSha256());
                text(out, request.archive().sha256());
                out.writeLong(request.archive().byteCount());
                out.writeLong(request.archive().uncompressedByteCount());
                facts(out, request);
                text(out, request.configuration().applicationId());
                out.writeLong(request.configuration().revision());
                text(out, request.configuration().schemaVersion());
                text(out, request.configuration().sha256());
                var secrets = request.secretReferences().stream()
                        .sorted(Comparator.comparing(DeploymentSecretReference::identifier)
                                .thenComparingLong(DeploymentSecretReference::revision))
                        .toList();
                out.writeInt(secrets.size());
                for (var secret : secrets) {
                    text(out, secret.identifier());
                    out.writeLong(secret.revision());
                }
                resources(out, request.resources());
                binary(out, new DeploymentRuntimeCodec().write(request.runtime()));
                binary(out, new DeploymentHealthCodec().write(request.runtime().healthCheck()));
                out.writeBoolean(request.userAccessUrl().isPresent());
                if (request.userAccessUrl().isPresent())
                    text(out, request.userAccessUrl().orElseThrow().url().toString());
                limits(out, request.limits());
                text(out, request.review().confirmedAt().toString());
                out.writeBoolean(request.containerDaemonRiskAccepted());
                out.writeBoolean(request.experimentalAdapterRiskAccepted());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Reviewed plan identity cannot be encoded", failure);
        }
    }

    /** Encodes selected architecture and source toolchain declarations without a local source path. / 编码所选架构及源码工具链声明，不包含本机源码路径。
     * @param out framed output / 分界输出
     * @param request reviewed input / 审阅输入
     * @throws IOException when encoding fails / 编码失败时 */
    private static void facts(DataOutputStream out, ReviewedDeploymentRequest request) throws IOException {
        var facts = request.facts();
        text(out, facts.applicationId());
        text(out, facts.projectType().name());
        text(out, facts.buildTool().name());
        text(out, facts.buildDirectory());
        text(out, facts.support().level().name());
        text(out, facts.support().language().name());
        text(out, facts.support().architecture());
        text(out, facts.support().framework());
        out.writeInt(facts.toolchainRequirements().size());
        for (var requirement : facts.toolchainRequirements()) {
            text(out, requirement.ecosystem().name());
            text(out, requirement.declaration());
            text(out, requirement.source());
            text(out, requirement.purpose().name());
            text(out, requirement.constraint().name());
            out.writeBoolean(requirement.version().isPresent());
            if (requirement.version().isPresent())
                text(out, requirement.version().orElseThrow().text());
        }
    }

    /** Encodes exact sorted file and database bindings, preserving unreviewed versus reviewed-empty. / 编码精确排序的文件和数据库绑定，并保留未审阅与审阅为空的区别。
     * @param out framed output / 分界输出
     * @param bindings reviewed bindings / 已审阅绑定
     * @throws IOException when encoding fails / 编码失败时 */
    private static void resources(DataOutputStream out, DeploymentResourceBindings bindings) throws IOException {
        out.writeInt(bindings.fileBindings().size());
        for (var file : bindings.fileBindings()) {
            text(out, file.bindingId());
            text(out, file.dataPath().path());
            text(out, file.dataPath().access().name());
            text(out, file.dataPath().schemaId());
            out.writeBoolean(file.dataPath().reversible());
            text(out, file.location().type().name());
            text(out, file.location().path());
            text(out, file.resourceType().name());
            text(out, file.seedFile());
            text(out, file.contentSha256());
        }
        out.writeBoolean(bindings.databaseBindings().isPresent());
        if (bindings.databaseBindings().isEmpty())
            return;
        var databases = bindings.databaseBindings().orElseThrow();
        out.writeInt(databases.size());
        for (var database : databases) {
            text(out, database.databaseId());
            text(out, database.connection().engine().name());
            switch (database.connection()) {
                case DeploymentDatabaseConnection.Sqlite sqlite -> {
                    text(out, sqlite.fileName());
                    text(out, sqlite.location().type().name());
                    text(out, sqlite.location().path());
                    text(out, sqlite.accessPath());
                    text(out, sqlite.seedFile());
                    out.writeInt(sqlite.initializationFiles().size());
                    for (var file : sqlite.initializationFiles())
                        text(out, file);
                }
                case DeploymentDatabaseConnection.Server server -> {
                    text(out, server.host());
                    out.writeInt(server.port());
                    text(out, server.database());
                    text(out, server.username());
                    text(out, server.passwordReference().identifier());
                    out.writeLong(server.passwordReference().revision());
                    out.writeBoolean(server.tlsRequired());
                }
            }
        }
    }

    /** Encodes all enforced build limits. / 编码全部强制构建限制。
     * @param out framed output / 分界输出
     * @param limits reviewed limits / 已审阅限制
     * @throws IOException when encoding fails / 编码失败时 */
    private static void limits(DataOutputStream out, DeploymentBuildLimits limits) throws IOException {
        out.writeInt(limits.timeoutSeconds());
        out.writeInt(limits.maxProcesses());
        out.writeInt(limits.maxMemoryMiB());
        out.writeLong(limits.maxOutputBytes());
        out.writeLong(limits.maxWorkspaceBytes());
        out.writeBoolean(limits.runAsRoot());
    }

    /** Writes one length-prefixed UTF-8 field. / 写入一个带长度前缀的UTF-8字段。
     * @param out framed output / 分界输出
     * @param value exact field / 精确字段
     * @throws IOException when encoding fails / 编码失败时 */
    private static void text(DataOutputStream out, String value) throws IOException {
        binary(out, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes one length-prefixed binary field. / 写入一个带长度前缀的二进制字段。
     * @param out framed output / 分界输出
     * @param value exact bytes / 精确字节
     * @throws IOException when encoding fails / 编码失败时 */
    private static void binary(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }
}
