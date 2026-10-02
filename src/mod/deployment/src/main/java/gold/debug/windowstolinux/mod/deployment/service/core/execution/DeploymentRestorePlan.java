package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.RemoteFacts;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Complete original archive inputs, without looking up the historical source server. / 完整原归档输入，不查历史来源服务器。 */
final class DeploymentRestorePlan {
    /** One expected local file, its original full-graph binding and fixed remote member. / 一项预期本地文件、原全图绑定及固定远端成员。
     * @param file caller-reviewed file identity / 调用方已审阅文件身份
     * @param artifact original collection binding / 原采集绑定
     * @param member fixed candidate member / 固定候选成员 */
    record FileInput(RestoreFile file, DeploymentCollectionPlan.ArtifactPlan artifact, String member) {
    }

    /** Complete immutable caller request. / 完整不可变调用方请求。 */
    final DeploymentRestoreRequest request;

    /** Strictly decoded original graph, configuration and secret metadata. / 严格解码的原图、配置与秘密元数据。 */
    final DeploymentArchivedRuntimeCodec.Archive archive;

    /** Complete publication rebound only to the actual target server. / 仅重绑定实际目标服务器的完整发布。 */
    final DeploymentPublicationProposal proposal;

    /** Exact complete file coverage of the original reviewed bindings. / 原已审阅绑定的精确完整文件覆盖。 */
    final List<FileInput> files;

    /** Validates the archive and complete file metadata without reading the historical source. / 不读取历史来源地校验归档及完整文件元数据。
     * @param request complete immutable request / 完整不可变请求
     * @param releases existing canonical release validator / 既有规范发布校验器
     * @throws Exception if archive identity, coverage or bounds differ / 归档身份、覆盖或界限不符时 */
    DeploymentRestorePlan(DeploymentRestoreRequest request, DeploymentReleaseRepository releases) throws Exception {
        this.request = Objects.requireNonNull(request);
        requireDigest(request.archiveSha256());
        requireDigest(request.reviewedTargetSha256());
        archive = DeploymentArchivedRuntimeCodec.restore(request.archivedApplication(), releases);
        var original = archive.publication();
        var components = original.graph().components().stream()
            .map(value -> new DeploymentApplicationGraph.Component(value.componentId(),
                target(value.unit(), request.target()), value.runtime(), value.dependencies(),
                value.reviewedRuntime(), value.dataPaths(), value.resources()))
            .toList();
        var graph = new DeploymentApplicationGraph(original.graph().applicationId(),
            original.graph().healthComponentId(), original.graph().healthCheck(), components);
        var commits = original.units().stream()
            .map(value -> new DeploymentUnitCommit(target(value.unit(), request.target()), value.runtime(),
                value.release(), value.configuration(), value.secrets()))
            .toList();
        proposal = new DeploymentPublicationProposal(graph, commits);
        var release = DeploymentReleaseSnapshot.capture(original.graph(), original.units());
        var data = new DeploymentApplicationReader.ApplicationFacts(request.archivedApplication(), original.graph(),
            release);
        // Typed environment configuration is in the opaque publication. CONFIG file bindings retain their original PAX contract.
        var selected = request.files().stream().map(RestoreFile::bindingId)
            .filter(value -> !value.startsWith("release:") && !value.startsWith("image:")).toList();
        var plans = new DeploymentCollectionPlan().build(data, selected);
        for (var component : graph.components())
            if (plans.stream()
                .filter(value -> value.componentId().equals(component.componentId()) && value.kind().equals("file"))
                .count() > 48)
                throw new IOException("Restore component exceeds fixed helper argument bound");
        if (request.files().size() != plans.size() || selected.stream().distinct().count() != selected.size())
            throw new IOException("Restore files must exactly cover the reviewed artifact selection");
        var supplied = new HashMap<String, RestoreFile>();
        for (var file : request.files())
            if (supplied.put(file.bindingId(), file) != null)
                throw new IOException("Repeated restore file binding");
        // Full restoration includes every original file and database binding.
        for (var binding : request.archivedApplication().bindings())
            if (!supplied.containsKey(binding.id()))
                throw new IOException("Restore lacks a required data binding");
        var inputs = new ArrayList<FileInput>();
        for (var artifact : plans) {
            var file = supplied.get(artifact.bindingId());
            if (file == null)
                throw new IOException("Restore lacks a component release or image");
            requireDigest(file.sha256());
            if (file.byteCount() < 1 || file.byteCount() > Long.parseLong(artifact.maximum()))
                throw new IOException("Restore file exceeds the original artifact bound");
            String member = artifact.kind().equals("file")
                ? artifact.resource() + ".pax"
                : artifact.kind().equals("database")
                ? "__database-" + artifact.resource() + ".sql"
                : artifact.kind().equals("oci") ? "__image.oci" : "__release.pax";
            inputs.add(new FileInput(file, artifact, member));
        }
        files = List.copyOf(inputs);
    }

    /** Binds the historical archive to current endpoint, trust and platform facts. / 将历史归档绑定当前端点、信任与平台事实。
     * @param archived original archived description / 原归档描述
     * @param archiveSha caller-verified complete archive digest / 调用方核实的完整归档摘要
     * @param server actual saved target / 实际已保存目标
     * @param facts authenticated remote facts / 已认证远端事实
     * @return canonical target review base / 规范目标审阅基值 */
    static String review(DeploymentApplicationSnapshot archived, String archiveSha, ServerInfo server,
                         RemoteFacts facts) {
        requireDigest(archiveSha);
        if (facts.fingerprint().isBlank() || server.trustedFingerprint().isEmpty()
            || !server.trustedFingerprint().orElseThrow().equals(facts.fingerprint()))
            throw new IllegalArgumentException("Target authenticated fingerprint must match current trust");
        var values = List.of("deployment-restore-target-v1", archived.application().id(), archived.revisionSha256(),
            archived.ownershipSha256(), archiveSha, server.ref().id(), server.host(),
            Integer.toString(server.port()), server.username(), facts.fingerprint(), facts.operatingSystem(),
            facts.architecture());
        StringBuilder canonical = new StringBuilder();
        for (String value : values)
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value).append('\n');
        return DeploymentApplicationReader.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Verifies original local identity, size and content before an upload attempt. / 上传尝试前核实原本地身份、大小与内容。
     * @param file reviewed original file / 已审阅原文件
     * @throws Exception if its path, identity, size or digest differs / 路径、身份、大小或摘要不符时 */
    static void verifyLocal(RestoreFile file) throws Exception {
        Path path = file.verifiedLocalFile();
        if (!path.isAbsolute() || !path.normalize().equals(path) || Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)
            || Files.size(path) != file.byteCount())
            throw new IOException("Restore local file identity differs");
        var digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                size += count;
                if (size > file.byteCount())
                    throw new IOException("Restore file grew");
                digest.update(buffer, 0, count);
            }
        }
        if (size != file.byteCount() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))
            throw new IOException("Restore local file content differs");
    }

    /** Reconstructs the exact original request from private durable handoff facts. / 从私有持久交接事实重建精确原请求。
     * @param entry authorized original row / 已授权原行
     * @return complete immutable original request / 完整不可变原请求 */
    static DeploymentRestoreRequest read(DeploymentHandoffLedger.Entry entry) {
        JsonNode request = entry.request(), archived = request.path("archivedSnapshot");
        var bindings = new ArrayList<DeploymentBinding>();
        for (var binding : archived.path("bindings"))
            bindings.add(new DeploymentBinding(binding.path("id").asText(), binding.path("componentId").asText(),
                binding.path("kind").asText(), binding.path("path").asText(), binding.path("engine").asText(),
                binding.path("needsQuiescence").asBoolean(),
                binding.path("secretReferenceId").isNull()
                    ? Optional.empty()
                    : Optional.of(binding.path("secretReferenceId").asText())));
        var snapshot = new DeploymentApplicationSnapshot(entry.publicValue().application(),
            archived.path("displayName").asText(), archived.path("revisionSha256").asText(),
            archived.path("ownershipSha256").asText(), ApplicationRunState.valueOf(archived.path("state").asText()),
            Instant.parse(archived.path("observedAt").asText()),
            new VersionedRuntime(archived.path("runtimeVersion").asInt(),
                Base64.getDecoder().decode(archived.path("runtimeBase64").asText())),
            bindings, archived.path("healthPolicySha256").asText());
        var files = new ArrayList<RestoreFile>();
        for (var file : request.path("files"))
            files.add(new RestoreFile(file.path("bindingId").asText(), Path.of(file.path("path").asText()),
                file.path("size").asLong(), file.path("sha256").asText()));
        return new DeploymentRestoreRequest(snapshot, entry.target().orElseThrow(), files,
            request.path("archiveSha256").asText(), request.path("targetReviewSha256").asText());
    }

    /** Derives the stable main candidate of one original handoff component. / 推导原交接单组件的稳定主候选。
     * @param handoff original handoff ID / 原交接ID
     * @param component original component ID / 原组件ID
     * @return stable original main candidate / 稳定原主候选 */
    String candidate(UUID handoff, String component) {
        String unit = proposal.graph().components().stream().filter(value -> value.componentId().equals(component))
            .findFirst().orElseThrow().unit().id();
        return unit + "-" + UUID.nameUUIDFromBytes((handoff + ":" + component).getBytes(StandardCharsets.UTF_8))
            .toString().replace("-", "").substring(0, 16);
    }

    /** Selects the exact original unit commit by validated component mapping. / 通过已校验组件映射选择精确原单元提交。
     * @param component original component ID / 原组件ID
     * @return exact immutable original unit commit / 精确不可变原单元提交 */
    DeploymentUnitCommit commit(String component) {
        String unit = proposal.graph().components().stream().filter(value -> value.componentId().equals(component))
            .findFirst().orElseThrow().unit().id();
        return proposal.units().stream().filter(value -> value.unit().id().equals(unit)).findFirst().orElseThrow();
    }

    /** Changes only server scope while preserving original managed unit identity. / 仅改变服务器范围并保留原受管单元身份。
     * @param original immutable original unit / 不可变原单元
     * @param target actual restored server / 实际恢复服务器
     * @return target-bound original identity / 绑定目标的原身份 */
    private static DeploymentUnitIdentity target(DeploymentUnitIdentity original, ServerRef target) {
        return new DeploymentUnitIdentity(original.id(), target, original.systemdUnit(), original.releaseRoot(),
            original.ownershipManifestSha256());
    }

    /** Rejects absent or noncanonical reviewed digests before effects. / 副作用前拒绝缺失或非规范已审阅摘要。
     * @param value original reviewed digest / 原已审阅摘要 */
    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact digest required");
    }
}
