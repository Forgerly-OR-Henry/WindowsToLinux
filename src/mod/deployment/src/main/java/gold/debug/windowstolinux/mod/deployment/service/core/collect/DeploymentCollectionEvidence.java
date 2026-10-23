package gold.debug.windowstolinux.mod.deployment.service.core.collect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentArtifact;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader.ApplicationFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentHandoffChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;

/** Original collection identity, current state and sealed receipt validation. / 原采集身份、当前状态及封印回执校验。 */
final class DeploymentCollectionEvidence {
    /** Prevents instances of the fixed original evidence functions. / 防止固定原依据函数产生实例。 */
    private DeploymentCollectionEvidence() {
    }

    /** Identifies the original reviewed producer selection independently of artifact receipts. / 独立于产物回执地标识原已审阅生产者选择。
     * @param plan original reviewed artifact / 原已审阅产物
     * @return stable original selection key / 稳定原选择键 */
    static String planKey(DeploymentCollectionPlan.ArtifactPlan plan) {
        return plan.componentId() + ":" + plan.kind() + ":" + plan.resource();
    }

    /** Selects the component's own release digest, never the whole graph digest. / 选择组件自身发布摘要，绝不使用整图摘要。
     * @param data complete original release / 完整原发布
     * @param component original component identity / 原组件身份
     * @return exact original unit release digest / 精确原单元发布摘要 */
    static String unitRelease(ApplicationFacts data, DeploymentApplicationGraph.Part component) {
        return data.release().bindings().stream().filter(value -> value.unitId().equals(component.unit().id()))
            .findFirst().orElseThrow().releaseSha256();
    }

    /** Reads only this original operation's maintenance marker ownership. / 仅读取本原操作的维护标记归属。
     * @param channel original helper channel / 原helper频道
     * @param component original component identity / 原组件身份
     * @param operation original producer operation / 原生产者操作
     * @return confirmed presence, absence or uncertainty / 已确证存在、不存在或未知
     * @throws ApiException if provider authority is unavailable / 提供方权限不可用时 */
    static Optional<Boolean> marker(DeploymentHandoffChannel channel, DeploymentApplicationGraph.Part component,
                                    String operation) throws ApiException {
        var result = channel.execute("handoff-maintenance-status",
            List.of(component.unit().id(), component.unit().ownershipManifestSha256(), operation), new byte[0],
            Duration.ofMinutes(2), 1024);
        if (DeploymentHandoffChannel.receipt(result, "HANDOFF_MAINTENANCE=1\nPRESENT=1\n"))
            return Optional.of(true);
        if (DeploymentHandoffChannel.receipt(result, "HANDOFF_MAINTENANCE=1\nPRESENT=0\n"))
            return Optional.of(false);
        return Optional.empty();
    }

    /** Accepts only a complete sealed original artifact status with exact identity and bounds. / 仅接受身份与界限精确的完整封印原产物状态。
     * @param result actual remote status / 实际远端状态
     * @param plan original reviewed artifact / 原已审阅产物
     * @param operation original producer operation / 原生产者操作
     * @param application original application ID / 原应用ID
     * @return verified original artifact or unconfirmed status / 已核实原产物或未确证状态 */
    static Optional<DeploymentArtifact> parseStatus(
        OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result,
        DeploymentCollectionPlan.ArtifactPlan plan, String operation, String application) {
        return parseStatus(result, plan, operation, application,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    static Optional<DeploymentArtifact> parseStatus(
        OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result,
        DeploymentCollectionPlan.ArtifactPlan plan, String operation, String application,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        java.util.Objects.requireNonNull(layout);
        if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty())
            return Optional.empty();
        var reply = result.value().orElseThrow();
        if (reply.truncated() || !reply.exitCode().equals(Optional.of(0)))
            return Optional.empty();
        var fields = new HashMap<String, String>();
        for (String line : reply.stdout().lines().toList()) {
            int split = line.indexOf('=');
            if (split < 1 || fields.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null)
                return Optional.empty();
        }
        boolean database = plan.kind().equals("database");
        if (!"1".equals(fields.get(database ? "HANDOFF_DATABASE" : "HANDOFF_BACKUP"))
            || !"1".equals(fields.get("PRESENT")) || !"1".equals(fields.get("COMPLETE")))
            return Optional.empty();
        if (database && !fields.keySet()
            .equals(Set.of("HANDOFF_DATABASE", "PRESENT", "COMPLETE", "ARTIFACT_ID", "BYTE_COUNT", "SHA256")))
            return Optional.empty();
        if (!database && (!fields.keySet()
            .equals(Set.of("HANDOFF_BACKUP", "PRESENT", "COMPLETE", "ARTIFACT", "KIND", "SIZE", "SHA256"))
            || !plan.kind().equals(fields.get("KIND"))))
            return Optional.empty();
        String id = fields.get(database ? "ARTIFACT_ID" : "ARTIFACT");
        String expected = database
            ? "db-" + digest32(operation, application, plan.componentId(), plan.unitId(), plan.release(),
            plan.resource())
            : "artifact-" + digest32(application, plan.componentId(), plan.unitId(), plan.release(), plan.kind(),
            plan.resource());
        String size = fields.get(database ? "BYTE_COUNT" : "SIZE");
        String digest = fields.get("SHA256");
        if (!expected.equals(id) || !size.matches("[1-9][0-9]{0,11}") || !digest.matches("[0-9a-f]{64}"))
            return Optional.empty();
        long count = Long.parseLong(size);
        if (count > Long.parseLong(plan.maximum()))
            return Optional.empty();
        String path = database
            ? layout.backupsRoot() + "/" + id
            : layout.backupsRoot() + "/" + operation + "/" + id
            + (plan.kind().equals("oci") ? ".oci" : ".pax");
        return Optional.of(new DeploymentArtifact(id, plan.bindingId(), path, count, digest));
    }

    /** Derives the fixed helper artifact identity from exact original fields. / 从精确原字段推导固定helper产物身份。
     * @param parts original immutable identity fields / 原不可变身份字段
     * @return original artifact digest suffix / 原产物摘要后缀 */
    static String digest32(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** Verifies all actual component current releases before any collection or recovery effect. / 采集或恢复副作用前核实全部实际组件当前发布。
     * @param channel original helper channel / 原helper频道
     * @param data complete original release / 完整原发布
     * @return exact current identity conclusion / 精确当前身份结论
     * @throws ApiException if provider authority is unavailable / 提供方权限不可用时 */
    static OperationResult<Void> current(DeploymentHandoffChannel channel, ApplicationFacts data) throws ApiException {
        for (var component : data.graph().components()) {
            var release = data.release().bindings().stream()
                .filter(value -> value.unitId().equals(component.unit().id())).findFirst().orElseThrow();
            var verified = channel.current(component, release.releaseSha256());
            if (verified.outcome() != TaskResult.SUCCEEDED)
                return verified;
        }
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Verifies the completed original producer receipt without accepting mere file presence. / 核实原生产者完成回执而不将文件存在视为成功。
     * @param result completed remote producer result / 已完成远端生产者结果
     * @param plan original reviewed artifact / 原已审阅产物
     * @param operation original producer operation / 原生产者操作
     * @param application original application ID / 原应用ID
     * @return verified complete original artifact or unconfirmed receipt / 已核实完整原产物或未确证回执 */
    static Optional<DeploymentArtifact> parseArtifact(
        OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result,
        DeploymentCollectionPlan.ArtifactPlan plan, String operation, String application) {
        return parseArtifact(result, plan, operation, application,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    static Optional<DeploymentArtifact> parseArtifact(
        OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> result,
        DeploymentCollectionPlan.ArtifactPlan plan, String operation, String application,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        java.util.Objects.requireNonNull(layout);
        var reply = result.value().orElseThrow();
        if (reply.truncated() || !reply.exitCode().equals(Optional.of(0)))
            return Optional.empty();
        var fields = new HashMap<String, String>();
        for (String line : reply.stdout().lines().toList()) {
            int split = line.indexOf('=');
            if (split < 1 || fields.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null)
                return Optional.empty();
        }
        boolean database = plan.kind().equals("database");
        if (database && (!fields.keySet().equals(Set.of("HANDOFF_DATABASE", "ARTIFACT_ID", "BYTE_COUNT", "SHA256"))
            || !fields.get("HANDOFF_DATABASE").equals("1")))
            return Optional.empty();
        if (!database && (!fields.keySet().equals(Set.of("ARTIFACT", "KIND", "SIZE", "SHA256"))
            || !fields.get("KIND").equals(plan.kind())))
            return Optional.empty();
        String id = fields.get(database ? "ARTIFACT_ID" : "ARTIFACT");
        String expected = database
            ? "db-" + digest32(operation, application, plan.componentId(), plan.unitId(), plan.release(),
            plan.resource())
            : "artifact-" + digest32(application, plan.componentId(), plan.unitId(), plan.release(), plan.kind(),
            plan.resource());
        String size = fields.get(database ? "BYTE_COUNT" : "SIZE");
        String digest = fields.get("SHA256");
        if (!expected.equals(id) || !size.matches("[1-9][0-9]{0,11}") || !digest.matches("[0-9a-f]{64}"))
            return Optional.empty();
        long count = Long.parseLong(size);
        if (count > Long.parseLong(plan.maximum()))
            return Optional.empty();
        String path = database
            ? layout.backupsRoot() + "/" + id
            : layout.backupsRoot() + "/" + operation + "/" + id
            + (plan.kind().equals("oci") ? ".oci" : ".pax");
        return Optional.of(new DeploymentArtifact(id, plan.bindingId(), path, count, digest));
    }

    /** Reconstructs only original persisted complete artifact results. / 仅重建原持久完整产物结果。
     * @param facts original private recovery facts / 原私有恢复事实
     * @return immutable original artifact list / 不可变原产物列表 */
    static List<DeploymentArtifact> artifacts(JsonNode facts) {
        var result = new ArrayList<DeploymentArtifact>();
        var values = facts.path("artifacts");
        if (values.isArray())
            for (var value : values)
                result.add(new DeploymentArtifact(value.path("id").asText(), value.path("bindingId").asText(),
                    value.path("remotePath").asText(), value.path("byteCount").asLong(),
                    value.path("sha256").asText()));
        return List.copyOf(result);
    }

    /** Resolves validated dependency order and rejects cycles before effects. / 副作用前解析已校验依赖顺序并拒绝环。
     * @param graph original application graph / 原应用图
     * @return dependency-ordered original components / 按依赖排序的原组件 */
    static List<DeploymentApplicationGraph.Part> order(DeploymentApplicationGraph graph) {
        var result = new ArrayList<DeploymentApplicationGraph.Part>();
        Set<String> done = new HashSet<>();
        while (result.size() < graph.components().size()) {
            int before = result.size();
            for (var component : graph.components())
                if (!done.contains(component.componentId()) && done.containsAll(component.dependencies())) {
                    result.add(component);
                    done.add(component.componentId());
                }
            if (before == result.size())
                throw new IllegalStateException("Reviewed graph contains a cycle");
        }
        return result;
    }

    /** Preserves a non-successful original observation across payload types. / 跨载荷类型保留非成功原观察。
     * @param <T> destination payload type / 目标载荷类型
     * @param result original non-successful result / 原非成功结果
     * @return unchanged conclusion and evidence / 不变结论与依据 */
    static <T> OperationResult<T> copy(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports only a confirmed original collection payload. / 仅报告已确证原采集载荷。
     * @param <T> payload type / 载荷类型
     * @param value confirmed original payload / 已确证原载荷
     * @return known successful result / 已知成功结果 */
    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Rejects known incompatible collection input before effects. / 副作用前拒绝已知不兼容采集输入。
     * @param <T> payload type / 载荷类型
     * @param event original incompatibility event / 原不兼容事件
     * @return known failed result / 已知失败结果 */
    static <T> OperationResult<T> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE,
            event, "Collection differs from the original reviewed release", Map.of(), Optional.empty())));
    }

    /** Retains uncertain original effects without concluding cleanup. / 保留未知原副作用而不结束清理。
     * @param <T> payload type / 载荷类型
     * @param event original uncertainty event / 原未知事件
     * @return retained unknown result / 留存未知结果 */
    static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Collection requires original-task read-only reconciliation", Map.of(), Optional.empty())));
    }

    /** Preserves classified original ownership or recovery evidence. / 保留已分类原归属或恢复依据。
     * @param code classified original cause / 已分类原原因
     * @param event original event / 原事件
     * @return original checked problem / 原受检问题 */
    static ApiException problem(ProblemCode code, String event) {
        return new ApiException(
            new Problem(code, event, "Collection evidence cannot be confirmed", Map.of(), Optional.empty()));
    }
}
