package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Archives the complete canonical publication plus nonsecret credential metadata. / 归档完整规范发布及非秘密凭据元数据。 */
public final class DeploymentArchivedRuntimeCodec {
    /** Fixed discriminator of the complete unpublished archive envelope. / 完整未发行归档封套的固定标识。 */
    private static final byte[] MAGIC = "W2L-RELEASE-3\n".getBytes(StandardCharsets.US_ASCII);

    /** Aggregate public runtime byte limit, without truncation. / 不截断的公开运行时总字节上限。 */
    private static final int LIMIT = 16 * 1_048_576;

    /** Strict private metadata reader that rejects duplicate fields. / 拒绝重复字段的严格私有元数据读取器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    /** Prevents utility instances from carrying archive state. / 防止工具实例携带归档状态。 */
    private DeploymentArchivedRuntimeCodec() {
    }

    /** Complete private restoration input; archive references still require target authorization. / 完整私有恢复输入；归档引用仍须目标授权。
     * @param publication original graph, configurations and release bindings / 原图、配置与发布绑定
     * @param secrets nonsecret original credential identities and purposes / 非秘密原凭据身份与用途 */
    public record Archive(DeploymentPublicationProposal publication, List<DeploymentSecretRevision> secrets) {
        /** Copies nonsecret metadata. / 复制非秘密元数据。
         * @param publication complete publication / 完整发布
         * @param secrets credential metadata / 凭据元数据 */
        public Archive {
            Objects.requireNonNull(publication);
            secrets = List.copyOf(secrets);
        }
    }

    /** Encodes every original configuration through the canonical publication codec. / 经规范发布编解码器编码每份原配置。
     * @param proposal original complete publication / 原完整发布
     * @param secrets complete nonsecret credential metadata / 完整非秘密凭据元数据
     * @param releases existing canonical codec / 既有规范编解码器
     * @return bounded opaque archive bytes / 有界不透明归档字节
     * @throws IOException if complete content is invalid or exceeds the bound / 完整内容无效或超过界限时 */
    public static VersionedRuntime encode(DeploymentPublicationProposal proposal,
            List<DeploymentSecretRevision> secrets, DeploymentReleaseRepository releases) throws IOException {
        try {
            requireSecrets(proposal, secrets);
            byte[] template = releases.archiveTemplate(proposal).getBytes(StandardCharsets.UTF_8);
            var metadata = JSON.createObjectNode().put("formatVersion", 1);
            var values = metadata.putArray("secrets");
            for (var secret : secrets.stream()
                    .sorted(Comparator
                            .comparing(value -> value.reference().identifier() + ":" + value.reference().revision()))
                    .toList())
                values.addObject().put("identifier", secret.reference().identifier())
                        .put("revision", secret.reference().revision()).put("credentialId", secret.credentialId())
                        .put("purpose", secret.purpose().orElseThrow())
                        .put("createdAt", secret.createdAt().toEpochMilli());
            byte[] credentials = JSON.writeValueAsBytes(metadata);
            long size = MAGIC.length + 8L + template.length + credentials.length;
            if (size > LIMIT)
                throw new IOException("Complete archived publication exceeds public bound");
            return new VersionedRuntime(3, ByteBuffer.allocate((int) size).put(MAGIC).putInt(template.length)
                    .put(template).putInt(credentials.length).put(credentials).array());
        } catch (SQLException | IllegalArgumentException invalid) {
            throw new IOException("Invalid complete archived publication", invalid);
        }
    }

    /** Decodes canonical release documents independently of source rows. / 不依赖来源行地解码规范发布文档。
     * @param applicationId original application / 原应用
     * @param revision exact original digest / 精确原摘要
     * @param value complete versioned bytes / 完整版本化字节
     * @return record for the existing full release validator / 供既有完整发布校验器使用的记录
     * @throws IOException if bounds or content differ / 界限或内容不符时 */
    public static DeploymentReleaseRecord decode(String applicationId, String revision, VersionedRuntime value)
            throws IOException {
        var envelope = envelope(value);
        var template = parse(envelope.template());
        if (!template.path("applicationId").asText().equals(applicationId) || !template.path("runtime").isObject()
                || !template.path("health").isObject())
            throw new IOException("Archived release identity differs");
        UUID id = UUID.nameUUIDFromBytes((applicationId + "\n" + revision).getBytes(StandardCharsets.UTF_8));
        return new DeploymentReleaseRecord(applicationId, new DeploymentReleaseIdentity(id, revision), id,
                DeploymentReleaseRecord.DeploymentReleaseState.RETIRED, template.path("runtime").toString(),
                template.path("health").toString(), Instant.EPOCH);
    }

    /** Verifies all configuration content and nonsecret reference identities. / 校验全部配置内容与非秘密引用身份。
     * @param archived untrusted public archive / 不可信公开归档
     * @param releases existing full validator / 既有完整校验器
     * @return exact complete private restoration input / 精确完整私有恢复输入
     * @throws IOException if any archive binding or document differs / 任一归档绑定或文档不符时 */
    public static Archive restore(DeploymentApplicationSnapshot archived, DeploymentReleaseRepository releases)
            throws IOException {
        try {
            var envelope = envelope(archived.runtime());
            var proposal = releases.archivedTemplate(new String(envelope.template(), StandardCharsets.UTF_8),
                    archived.revisionSha256());
            var node = parse(envelope.credentials());
            if (!fields(node).equals(Set.of("formatVersion", "secrets"))
                    || !node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                    || node.path("formatVersion").intValue() != 1 || !node.path("secrets").isArray()
                    || node.path("secrets").size() > 1024)
                throw new IOException("Invalid archived secret metadata");
            var secrets = new ArrayList<DeploymentSecretRevision>();
            for (var secret : node.path("secrets")) {
                if (!fields(secret).equals(Set.of("identifier", "revision", "credentialId", "purpose", "createdAt"))
                        || !secret.path("revision").isIntegralNumber() || !secret.path("revision").canConvertToLong()
                        || !secret.path("createdAt").isIntegralNumber() || !secret.path("createdAt").canConvertToLong()
                        || !secret.path("identifier").isTextual() || !secret.path("credentialId").isTextual()
                        || !secret.path("purpose").isTextual())
                    throw new IOException("Invalid archived secret identity");
                secrets.add(new DeploymentSecretRevision(
                        new DeploymentSecretReference(secret.path("identifier").asText(),
                                secret.path("revision").longValue()),
                        secret.path("credentialId").asText(),
                        Instant.ofEpochMilli(secret.path("createdAt").longValue()),
                        Optional.of(secret.path("purpose").asText())));
            }
            requireSecrets(proposal, secrets);
            verifyIdentity(archived, proposal.graph());
            return new Archive(proposal, secrets);
        } catch (SQLException | IllegalArgumentException invalid) {
            throw new IOException("Archived publication is invalid", invalid);
        }
    }

    /** Validates full archived graph and configuration before target preparation. / 目标准备前校验完整归档图与配置。
     * @param archived archived public facts / 归档公开事实
     * @param releases existing validator / 既有校验器
     * @return complete original graph and bindings / 完整原图与绑定
     * @throws IOException if archive content differs / 归档内容不符时 */
    public static DeploymentReleaseSnapshot verify(DeploymentApplicationSnapshot archived,
            DeploymentReleaseRepository releases) throws IOException {
        var archive = restore(archived, releases);
        return DeploymentReleaseSnapshot.capture(archive.publication().graph(), archive.publication().units());
    }

    /** Matches all public archive identities to the canonical complete graph. / 将全部公开归档身份匹配规范完整图。
     * @param archived public original archive / 公开原归档
     * @param graph canonical complete original graph / 规范完整原图
     * @throws IOException if any application, server, binding or policy differs / 应用、服务器、绑定或策略不符时 */
    private static void verifyIdentity(DeploymentApplicationSnapshot archived, DeploymentApplicationGraph graph)
            throws IOException {
        if (!graph.applicationId().equals(archived.application().id())
                || graph.components().stream()
                        .anyMatch(value -> !value.unit().server().equals(archived.application().server()))
                || !DeploymentApplicationReader.ownership(graph).equals(archived.ownershipSha256())
                || !DeploymentApplicationReader.bindings(graph).equals(archived.bindings())
                || !DeploymentApplicationReader.sha256(new DeploymentHealthCodec().write(graph.healthCheck()))
                        .equals(archived.healthPolicySha256()))
            throw new IOException("Archived graph identity differs");
    }

    /** Requires exact complete credential metadata coverage without plaintext. / 要求无明文的精确完整凭据元数据覆盖。
     * @param proposal canonical original publication / 规范原发布
     * @param secrets original nonsecret credential metadata / 原非秘密凭据元数据 */
    private static void requireSecrets(DeploymentPublicationProposal proposal, List<DeploymentSecretRevision> secrets) {
        var required = new HashSet<DeploymentSecretReference>();
        proposal.units().forEach(value -> required.addAll(value.secrets()));
        var supplied = new HashSet<DeploymentSecretReference>();
        for (var value : secrets)
            if (value.purpose().isEmpty() || !supplied.add(value.reference()))
                throw new IllegalArgumentException("Archived credential metadata is incomplete or repeated");
        if (!required.equals(supplied))
            throw new IllegalArgumentException("Archived credentials do not cover original bindings");
    }
    /** Private bounded parts of one complete archive. / 完整归档的私有有界组成。
     * @param template canonical complete publication / 规范完整发布
     * @param credentials strict nonsecret credential metadata / 严格非秘密凭据元数据 */
    private record Envelope(byte[] template, byte[] credentials) {
    }
    /** Splits only a complete bounded format-three envelope. / 仅拆分完整有界格式三封套。
     * @param value original opaque public runtime / 原不透明公开运行时
     * @return complete private envelope parts / 完整私有封套组成
     * @throws IOException if format, length or completeness differs / 格式、长度或完整性不符时 */
    private static Envelope envelope(VersionedRuntime value) throws IOException {
        byte[] bytes = value.payload();
        if (value.formatVersion() != 3 || bytes.length > LIMIT || bytes.length < MAGIC.length + 10)
            throw new IOException("Unsupported archived runtime format");
        var buffer = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC))
            throw new IOException("Unknown archived runtime envelope");
        int length = buffer.getInt();
        if (length < 1 || length > buffer.remaining() - 5)
            throw new IOException("Invalid archived template bound");
        byte[] template = new byte[length];
        buffer.get(template);
        length = buffer.getInt();
        if (length < 1 || length != buffer.remaining())
            throw new IOException("Invalid archived credential bound");
        byte[] credentials = new byte[length];
        buffer.get(credentials);
        return new Envelope(template, credentials);
    }

    /** Reads one strict metadata object without trailing or duplicate fields. / 读取无尾随或重复字段的严格元数据对象。
     * @param bytes bounded original metadata / 有界原元数据
     * @return strict original metadata object / 严格原元数据对象
     * @throws IOException if the object is malformed or incomplete / 对象畸形或不完整时 */
    private static JsonNode parse(byte[] bytes) throws IOException {
        try (var parser = JSON.createParser(bytes)) {
            JsonNode value = JSON.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null)
                throw new IOException("Invalid archived JSON");
            return value;
        }
    }

    /** Captures the exact field set for strict metadata shape checks. / 为严格元数据结构核对捕获精确字段集合。
     * @param node original metadata object / 原元数据对象
     * @return independently captured field identities / 独立捕获的字段身份 */
    private static Set<String> fields(JsonNode node) {
        var result = new HashSet<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }
}
