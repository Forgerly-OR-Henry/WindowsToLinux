package gold.debug.windowstolinux.mod.deployment.db.execution;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Retains one fully restored publication operation without ending its original formal task. / 留存一次完整恢复的发布操作，不结束原正式任务。
 * @param task authentic original task / 真实原任务
 * @param operation exact retired publication operation / 精确已退出发布操作
 * @param reviewedRevision complete retired root SHA / 完整已退出根SHA
 * @param templateSha256 complete immutable retired template SHA / 完整不可变已退出模板SHA
 * @param failureCode actual known failure category / 实际已知失败类别
 * @param failureEvent actual known failure event / 实际已知失败事件
 * @param retriesSpent actual cumulative corrections including original catalog fallbacks / 含原目录回退的实际累计纠正
 * @param children exact unit cleanup evidence / 精确单元清理依据
 * @param root optional complete graph checkpoint / 可选完整图检查点 */
public record DeploymentPublishAttemptProof(UUID task, UUID operation, String reviewedRevision,
                                            String templateSha256,
                                            ProblemCode failureCode, String failureEvent, int retriesSpent,
                                            List<Child> children, Optional<JsonNode> root) {
    /** Sixteen bounded tool bindings with escaping plus seventeen original twelve-KiB checkpoints. / 十六份含转义的有界工具绑定加十七份原十二KiB检查点。 */
    static final int LIMIT = 7 * 1_048_576;

    /** Requires complete known cleanup and copies every retained mutable value. / 要求完整已知清理并复制所有留存可变值。
     * @param task original task / 原任务
     * @param operation retired operation / 已退出操作
     * @param reviewedRevision original root / 原根
     * @param templateSha256 exact complete template / 精确完整模板
     * @param failureCode known category / 已知类别
     * @param failureEvent known event / 已知事件
     * @param retriesSpent actual shared corrections consumed / 实际共用已消费纠正
     * @param children complete original units / 完整原单元
     * @param root original graph or explicit absence / 原图或明确不存在 */
    public DeploymentPublishAttemptProof {
        Objects.requireNonNull(task);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(failureCode);
        children = List.copyOf(children);
        root = root.map(JsonNode::deepCopy);
        if (!reviewedRevision.matches("[0-9a-f]{64}") || !templateSha256.matches("[0-9a-f]{64}")
            || failureCode == ProblemCode.UNKNOWN || failureCode == ProblemCode.CANCELLED
            || retriesSpent < 0 || retriesSpent > 2 || !failureEvent.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}") || children.isEmpty() || children.size() > 16
            || children.stream().map(Child::unitId).distinct().count() != children.size() || root.isPresent() != (children.size() > 1))
            throw new IllegalArgumentException("Incomplete restored publication operation");
        for (var child : children) child.requireTask(task, operation);
        if (root.isPresent()) {
            var facts = root.orElseThrow();
            requireCheckpoint(facts);
            if (!task.toString().equals(text(facts, "task")) || !reviewedRevision.equals(text(facts, "planSha256"))
                || !"FAILED".equals(text(facts, "verifiedOutcome")) || !failureCode.name().equals(text(facts, "failureCode"))
                || !failureEvent.equals(text(facts, "failureEvent")) || !facts.path("components").isArray()
                || facts.path("components").size() != children.size())
                throw new IllegalArgumentException("Restored graph differs from its original failure");
            var indexed = new HashSet<String>();
            for (var entry : facts.path("components")) {
                String unit = text(entry, "unitId");
                var child = children.stream().filter(value -> value.unitId().equals(unit)).findFirst().orElseThrow();
                if (!indexed.add(unit) || !text(entry, "planSha256").equals(text(child.checkpoint(), "planSha256")))
                    throw new IllegalArgumentException("Restored graph child binding changed");
            }
        } else {
            var facts = children.getFirst().checkpoint();
            if (!reviewedRevision.equals(text(facts, "planSha256")) || !"FAILED".equals(text(facts, "verifiedOutcome"))
                || !failureCode.name().equals(text(facts, "verifiedCode")) || !failureEvent.equals(text(facts, "verifiedEvent")))
                throw new IllegalArgumentException("Restored single unit differs from its original failure");
        }
    }

    /** Holds exact original tools and observations only after snapshot, marker and candidate cleanup. / 仅在快照、标记及候选清理后持有精确原工具及观察。
     * @param unitId exact original unit / 精确原单元
     * @param checkpoint complete original twelve-KiB author facts / 完整原十二KiB作者事实
     * @param tools complete physically observed original binding / 完整物理观察原绑定
     * @param current old current content or explicit empty / 旧当前内容或明确为空
     * @param kind old current runtime kind / 旧当前运行类别
     * @param running actual restored running state / 实际恢复运行状态
     * @param limitSeconds original admitted cumulative execution limit / 原准入累计执行上限
     * @param remainingSeconds actual remaining original command budget / 原命令预算实际剩余 */
    public record Child(String unitId, JsonNode checkpoint, String tools, Optional<String> current, String kind,
                        boolean running,
                        int limitSeconds, long remainingSeconds) {
        /** Copies and checks a fully closed original component. / 复制并核验完整关闭的原组件。
         * @param unitId original unit / 原单元
         * @param checkpoint original author checkpoint / 原作者检查点
         * @param tools original tool binding / 原工具绑定
         * @param current restored old current / 已恢复旧当前
         * @param kind restored old kind / 已恢复旧类别
         * @param running restored old activity / 已恢复旧活动
         * @param limitSeconds original admitted execution limit / 原准入执行上限
         * @param remainingSeconds actual unchanged remaining budget / 实际不变剩余预算 */
        public Child {
            checkpoint = Objects.requireNonNull(checkpoint).deepCopy();
            current = Objects.requireNonNull(current);
            requireCheckpoint(checkpoint);
            if (!unitId.matches("[a-z0-9][a-z0-9-]{0,62}") || !unitId.equals(text(checkpoint, "application"))
                || !text(checkpoint, "archiveSha256").matches("[0-9a-f]{64}") || !text(checkpoint, "planSha256").matches("[0-9a-f]{64}")
                || !checkpoint.path("previousRunning").isBoolean() || checkpoint.path("previousRunning").booleanValue() != running
                || !text(checkpoint, "previousDigest").equals(current.orElse("")) || !text(checkpoint, "previousKind").equals(kind)
                || current.isPresent() && !current.orElseThrow().matches("[0-9a-f]{64}")
                || current.isEmpty() && (!kind.equals("none") || running) || current.isPresent() && !Set.of("deployment", "container").contains(kind)
                || limitSeconds < 60 || limitSeconds > 7200 || remainingSeconds < 0 || remainingSeconds > limitSeconds)
                throw new IllegalArgumentException("Restored unit differs from its acknowledged old snapshot");
            try {
                DeploymentToolchainBindingCodec.decode(tools);
                if (!text(checkpoint, "buildTools").equals(digest(tools)))
                    throw new IllegalArgumentException("Restored unit tools differ from the original build");
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Original restored tools are incomplete", invalid);
            }
        }

        /** Returns copied original component facts. / 返回复制的原组件事实。
         * @return detached original evidence / 分离原依据 */
        @Override
        public JsonNode checkpoint() {
            return checkpoint.deepCopy();
        }

        /** Proves the source-derived or explicit-operation candidate and snapshot ownership. / 证明来源派生或明确操作候选及快照归属。
         * @param task authentic original root / 真实原根
         * @param operation retired original operation / 已退出原操作 */
        private void requireTask(UUID task, UUID operation) {
            Optional<UUID> explicit = checkpoint.has("operation") ? Optional.of(UUID.fromString(text(checkpoint, "operation"))) : Optional.empty();
            String token = text(checkpoint, "previousToken");
            if (!task.toString().equals(text(checkpoint, "task")) || !explicit.orElse(task).equals(operation)
                || !token.isEmpty() && !token.equals(operation.toString())
                || !text(checkpoint, "candidate").equals(new DeploymentRemoteWorkspace(unitId, text(checkpoint, "archiveSha256"), explicit).candidateId()))
                throw new IllegalArgumentException("Restored candidate belongs to another task or operation");
        }
    }

    /** Returns a copied original graph checkpoint. / 返回复制的原图检查点。
     * @return detached graph evidence or absence / 分离图依据或空值 */
    @Override
    public Optional<JsonNode> root() {
        return root.map(JsonNode::deepCopy);
    }

    /** Encodes bounded nested checkpoint objects without escaping an entire checkpoint again. / 编码有界嵌套检查点对象，不再次转义整份检查点。
     * @return complete canonical original cleanup proof / 完整规范原清理证明
     * @throws SQLException if a formal byte bound rejects / 正式字节边界拒绝时 */
    public String encode() throws SQLException {
        var node = object().put("formatVersion", 1).put("task", task.toString()).put("operation", operation.toString())
            .put("reviewedRevision", reviewedRevision).put("templateSha256", templateSha256)
            .put("failureCode", failureCode.name()).put("failureEvent", failureEvent).put("retriesSpent", retriesSpent);
        var entries = node.putArray("children");
        for (var child : children) {
            var entry = entries.addObject().put("unitId", child.unitId()).put("tools", child.tools()).put("current", child.current().orElse(""))
                .put("kind", child.kind()).put("running", child.running()).put("limitSeconds", child.limitSeconds()).put("remainingSeconds", child.remainingSeconds());
            entry.set("checkpoint", child.checkpoint());
        }
        root.ifPresentOrElse(value -> node.set("root", value.deepCopy()), () -> node.putNull("root"));
        return DeploymentJsonDocument.encode(node, LIMIT);
    }

    /** Reads complete proof without treating absence or a known business failure as cleanup evidence. / 读取完整证明，不把不存在或已知业务失败当作清理依据。
     * @param node stored bounded proof / 已存有界证明
     * @return exact immutable proof / 精确不可变证明
     * @throws SQLException if strict fields or canonical data conflict / 严格字段或规范数据冲突时 */
    static DeploymentPublishAttemptProof decode(JsonNode node) throws SQLException {
        fields(node, Set.of("formatVersion", "task", "operation", "reviewedRevision", "templateSha256", "failureCode", "failureEvent", "retriesSpent", "children", "root"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1 || !node.path("retriesSpent").isInt())
            throw new SQLException("Unsupported retired publication proof");
        try {
            var children = new ArrayList<Child>();
            for (var entry : array(node, "children", 16)) {
                fields(entry, Set.of("unitId", "tools", "current", "kind", "running", "checkpoint", "limitSeconds", "remainingSeconds"));
                if (!entry.path("running").isBoolean() || !entry.path("limitSeconds").isInt()
                    || !entry.path("remainingSeconds").isIntegralNumber() || !entry.path("remainingSeconds").canConvertToLong())
                    throw new SQLException("Retired activity or execution budget has invalid types");
                String current = string(entry, "current");
                children.add(new Child(string(entry, "unitId"), entry.path("checkpoint"), string(entry, "tools"),
                    current.isEmpty() ? Optional.empty() : Optional.of(current), string(entry, "kind"), entry.path("running").booleanValue(),
                    entry.path("limitSeconds").intValue(), entry.path("remainingSeconds").longValue()));
            }
            var proof = new DeploymentPublishAttemptProof(uuid(node, "task"), uuid(node, "operation"), string(node, "reviewedRevision"), string(node, "templateSha256"),
                ProblemCode.valueOf(string(node, "failureCode")), string(node, "failureEvent"), node.path("retriesSpent").intValue(), children,
                node.path("root").isNull() ? Optional.empty() : Optional.of(node.path("root")));
            if (!proof.encode().equals(node.toString()))
                throw new SQLException("Retired publication proof is not canonical");
            return proof;
        } catch (IllegalArgumentException | NoSuchElementException invalid) {
            throw new SQLException("Retired publication proof is incomplete", invalid);
        }
    }

    /** Rejects unsettled effects and unfinished local resources before retaining an old operation. / 留存旧操作前拒绝未结副作用及未完成本地资源。
     * @param node exact original checkpoint / 精确原检查点 */
    private static void requireCheckpoint(JsonNode node) {
        if (!node.isObject() || node.toString().getBytes(StandardCharsets.UTF_8).length > 12000 || !node.path("version").isInt()
            || !"SESSION_CLOSED".equals(text(node, "phase")) || node.has("environmentRecovery") && !"VERIFIED".equals(text(node, "environmentRecovery"))
            || node.has("buildReviewState") && !"CONFIRMED".equals(text(node, "buildReviewState")))
            throw new IllegalArgumentException("Original publication resources are not confirmed closed");
    }

    /** Requires real textual proof fields without coercion. / 要求真实文本证明字段，不进行强制转换。
     * @param node original object / 原对象
     * @param field exact original field / 精确原字段
     * @return original text / 原文本 */
    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException("Original cleanup proof field is absent");
        return node.path(field).textValue();
    }

    /** Hashes exact bounded original tools, proof or proposal bytes. / 哈希精确有界原工具、证明或提案字节。
     * @param text complete canonical value / 完整规范值
     * @return lowercase SHA-256 / 小写SHA-256 */
    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }
}
