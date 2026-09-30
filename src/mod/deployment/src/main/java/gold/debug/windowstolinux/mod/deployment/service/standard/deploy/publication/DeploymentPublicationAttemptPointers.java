package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;

/** Strict small pointers into full durable publication consent, never copied configuration or a new task state. / 指向完整持久发布同意的严格小指针，绝非复制配置或新任务状态。 */
final class DeploymentPublicationAttemptPointers {
    /** Exact operation pointer fields understood by the author checkpoint. / 作者检查点理解的精确操作指针字段。 */
    private static final Set<String> FIELDS = Set.of("operation", "attemptState", "attemptRetry", "attemptPreviousOperation", "attemptNextOperation",
            "attemptRootPrevious", "attemptRootNext", "attemptIntent", "attemptEnvironment", "attemptTools", "attemptReview", "expectedDigest", "expectedKind", "expectedRunning");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentPublicationAttemptPointers() {}

    /** Rejects coerced operation IDs, incomplete consent and unconfirmed tool receipts. / 拒绝强制转换操作ID、不完整同意及未确认工具回执。
     * @param facts exact retained child checkpoint / 精确留存子检查点 */
    static void requireChild(JsonNode facts) {
        if (facts.path("version").intValue() != 4) {
            if (FIELDS.stream().anyMatch(facts::has)) throw new IllegalArgumentException("Original checkpoint version has no publication attempt pointers");
            return;
        }
        if (!facts.path("attemptState").isTextual() || !Set.of("PENDING", "CONFIRMED").contains(facts.path("attemptState").textValue())
                || !facts.path("attemptRetry").isInt() || facts.path("attemptRetry").intValue() < 1 || facts.path("attemptRetry").intValue() > 2)
            throw new IllegalArgumentException("Original publication attempt pointer is incomplete");
        for (String field : List.of("attemptPreviousOperation", "attemptNextOperation")) canonicalUuid(facts, field);
        for (String field : List.of("attemptRootPrevious", "attemptRootNext", "attemptIntent")) hash(facts, field);
        if (text(facts, "attemptPreviousOperation").equals(text(facts, "attemptNextOperation")))
            throw new IllegalArgumentException("Original publication operation was replayed");
        if (facts.has("operation")) canonicalUuid(facts, "operation");
        if (text(facts, "attemptState").equals("PENDING")) {
            String original = facts.has("operation") ? text(facts, "operation") : text(facts, "task");
            if (!original.equals(text(facts, "attemptPreviousOperation")) || !text(facts, "phase").equals("SESSION_CLOSED")
                    || !facts.path("attemptEnvironment").isTextual() || !Set.of("MAY_HAVE_RUN", "VERIFIED").contains(text(facts, "attemptEnvironment"))
                    || text(facts, "attemptEnvironment").equals("VERIFIED") != facts.has("attemptTools"))
                throw new IllegalArgumentException("Old operation cannot prove the pending environment boundary");
            if (facts.has("attemptTools")) hash(facts, "attemptTools");
        } else {
            if (!text(facts, "operation").equals(text(facts, "attemptNextOperation")) || !facts.path("expectedRunning").isBoolean()
                    || !facts.path("expectedDigest").isTextual() || !facts.path("expectedKind").isTextual()
                    || !text(facts, "expectedDigest").isEmpty() && !text(facts, "expectedDigest").matches("[0-9a-f]{64}")
                    || !Set.of("none", "ordinary", "deployment", "container").contains(text(facts, "expectedKind")))
                throw new IllegalArgumentException("Next operation lacks its exact old current gate");
            hash(facts, "attemptReview");
        }
    }

    /** Adds a pending next-operation pointer only to its exact old closed checkpoint. / 仅向精确旧关闭检查点增加待定下一操作指针。
     * @param facts actual original mutable author facts / 实际原可变作者事实
     * @param intent complete formally stored consent / 完整正式已存同意
     * @return whether old root, task and component remain exact / 旧根、任务及组件是否精确保持 */
    static boolean pendingChild(ObjectNode facts, DeploymentPublicationAttemptIntent intent) {
        try {
            String operation = facts.has("operation") ? text(facts, "operation") : text(facts, "task");
            if (!operation.equals(intent.previousOperation().toString()) || !text(facts, "phase").equals("SESSION_CLOSED")) return false;
            if (text(facts, "application").equals(intent.unitId()) && !text(facts, "planSha256")
                    .equals(DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan()).reviewedRevision())) return false;
            facts.put("version", 4).put("attemptState", "PENDING").put("attemptRetry", intent.retry())
                    .put("attemptPreviousOperation", intent.previousOperation().toString()).put("attemptNextOperation", intent.nextOperation().toString())
                    .put("attemptRootPrevious", intent.previousRoot()).put("attemptRootNext", intent.nextRoot()).put("attemptIntent", intentBinding(intent))
                    .put("attemptEnvironment", "MAY_HAVE_RUN");
            facts.remove(List.of("attemptTools", "attemptReview", "expectedDigest", "expectedKind", "expectedRunning"));
            requireChild(facts); return true;
        } catch (SQLException | IllegalArgumentException invalid) { return false; }
    }

    /** Rebuilds a small current checkpoint only after exact final review confirmation. / 精确最终审阅确认后才重建小当前检查点。
     * @param facts exact original checkpoint / 精确原检查点
     * @param review full actually persisted review / 完整实际持久审阅
     * @param plan exact next component SHA / 精确下一组件SHA
     * @param runtime reviewed fixed backend / 已审阅固定后端
     * @return whether exact pointers were consumed / 精确指针是否已消费 */
    static boolean confirmChild(ObjectNode facts, DeploymentPublicationAttemptReview review, String plan, DeploymentRuntimeDefinition runtime) {
        if (!facts.path("attemptState").asText().equals("PENDING") || !facts.path("attemptEnvironment").asText().equals("VERIFIED")
                || facts.path("attemptRetry").asInt() != review.retry() || !facts.path("attemptRootPrevious").asText().equals(review.previousRoot())
                || !facts.path("attemptRootNext").asText().equals(review.nextRoot()) || !facts.path("attemptPreviousOperation").asText().equals(review.previousOperation().toString())
                || !facts.path("attemptNextOperation").asText().equals(review.nextOperation().toString()) || !plan.matches("[0-9a-f]{64}")) return false;
        try {
            if (!facts.path("attemptIntent").asText().equals(reviewBinding(review))) return false;
            if (text(facts, "application").equals(review.unitId()) && (!facts.path("attemptTools").asText().equals(digest(review.nextTools()))
                    || !plan.equals(DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan()).reviewedRevision()))) return false;
            String expected = text(facts, "previousDigest"); String kind = text(facts, "previousKind"); boolean running = facts.path("previousRunning").booleanValue();
            String backend = runtime instanceof DeploymentRuntimeDefinition.Container ? "container" : "native";
            String engine = runtime instanceof DeploymentRuntimeDefinition.Container container ? container.engine().name().toLowerCase(Locale.ROOT) : "none";
            if (!text(facts, "backend").equals(backend) || !text(facts, "engine").equals(engine)) return false;
            var preserved = new HashSet<>(Set.of("version", "task", "server", "application", "ownerManifestSha256", "releaseRoot", "archiveSha256", "backend", "engine",
                    "attemptRetry", "attemptPreviousOperation", "attemptNextOperation", "attemptRootPrevious", "attemptRootNext", "attemptIntent"));
            var remove = new ArrayList<String>(); facts.fieldNames().forEachRemaining(name -> { if (!preserved.contains(name)) remove.add(name); }); facts.remove(remove);
            facts.put("operation", review.nextOperation().toString()).put("attemptState", "CONFIRMED").put("attemptReview", digest(review.encode()))
                    .put("planSha256", plan).put("candidate", new DeploymentRemoteWorkspace(text(facts, "application"), text(facts, "archiveSha256"), Optional.of(review.nextOperation())).candidateId())
                    .put("expectedDigest", expected).put("expectedKind", kind).put("expectedRunning", running).put("phase", "CANDIDATE_MAY_EXIST");
            requireChild(facts);
            return facts.toString().getBytes(StandardCharsets.UTF_8).length <= 12000;
        } catch (SQLException | IllegalArgumentException invalid) { return false; }
    }

    /** Binds full prior consent using fields preserved by the exact final review. / 使用精确最终审阅保留的字段绑定完整原同意。
     * @param intent full original consent / 完整原同意
     * @return stable exact pointer / 稳定精确指针 */
    static String intentBinding(DeploymentPublicationAttemptIntent intent) {
        return binding(List.of("deployment-publication-attempt-consent-v1", intent.previousRoot(), intent.nextRoot(), intent.previousOperation().toString(),
                intent.nextOperation().toString(), intent.originalGraph(), intent.unitId(), intent.previousPlan(), intent.nextPlan(), intent.proofSha256(), intent.approvalAction(), intent.modelRevision()));
    }

    /** Recomputes the same consumed consent pointer from its complete final review. / 从完整最终审阅重新计算同一已消费同意指针。
     * @param review full formally confirmed review / 完整正式确认审阅
     * @return stable exact consumed pointer / 稳定精确已消费指针 */
    static String reviewBinding(DeploymentPublicationAttemptReview review) {
        return binding(List.of("deployment-publication-attempt-consent-v1", review.previousRoot(), review.nextRoot(), review.previousOperation().toString(),
                review.nextOperation().toString(), review.originalGraph(), review.unitId(), review.previousPlan(), review.nextPlan(), review.proofSha256(), review.recoveryAction(), review.modelRevision()));
    }

    /** Adds the next graph pointer only after every old unit and its original session closed. / 所有旧单元及原会话关闭后才增加下一图指针。
     * @param facts exact original root facts / 精确原根事实
     * @param intent full formally stored consent / 完整正式已存同意
     * @return whether the original graph predecessor was consumed / 原图前驱是否已消费 */
    static boolean pendingRoot(ObjectNode facts, DeploymentPublicationAttemptIntent intent) {
        try {
            String operation = facts.has("operation") ? text(facts, "operation") : text(facts, "task");
            if (!text(facts, "phase").equals("SESSION_CLOSED") || !text(facts, "verifiedOutcome").equals("FAILED")
                    || !text(facts, "planSha256").equals(intent.previousRoot()) || !operation.equals(intent.previousOperation().toString()) || intent.originalGraph().isEmpty()) return false;
            var original = DeploymentAssistedBuildReviewCodec.decodeGraph(intent.originalGraph());
            if (!original.applicationId().equals(text(facts, "application")) || facts.path("components").size() != original.order().size()) return false;
            for (var entry : facts.path("components")) if (!text(entry, "unitId").equals(original.namespaces().get(text(entry, "componentId")))
                    || !text(entry, "planSha256").equals(original.identities().get(text(entry, "componentId")))) return false;
            facts.put("version", 3).put("attemptState", "PENDING").put("attemptRetry", intent.retry()).put("attemptUnit", intent.unitId())
                    .put("attemptPreviousOperation", intent.previousOperation().toString()).put("attemptNextOperation", intent.nextOperation().toString())
                    .put("attemptRootPrevious", intent.previousRoot()).put("attemptRootNext", intent.nextRoot()).put("attemptIntent", intentBinding(intent));
            facts.remove("attemptReview"); requireRoot(facts); return true;
        } catch (SQLException | IllegalArgumentException invalid) { return false; }
    }

    /** Resets only the current complete root after the formal CAS and every child confirmation. / 正式CAS及所有子确认后仅重置当前完整根。
     * @param facts exact original root / 精确原根
     * @param review full actually persisted final review / 完整实际持久最终审阅
     * @return whether the complete current graph pointer was confirmed / 完整当前图指针是否已确认 */
    static boolean confirmRoot(ObjectNode facts, DeploymentPublicationAttemptReview review) {
        try {
            if (!text(facts, "attemptState").equals("PENDING") || facts.path("attemptRetry").intValue() != review.retry()
                    || !text(facts, "attemptRootPrevious").equals(review.previousRoot()) || !text(facts, "attemptRootNext").equals(review.nextRoot())
                    || !text(facts, "attemptUnit").equals(review.unitId()) || !text(facts, "attemptIntent").equals(reviewBinding(review))) return false;
            var after = DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan()); boolean changed = false;
            for (var entry : facts.path("components")) if (text(entry, "unitId").equals(review.unitId())) {
                ((ObjectNode) entry).put("planSha256", after.reviewedRevision()); changed = true;
            }
            if (!changed) return false;
            facts.put("operation", review.nextOperation().toString()).put("planSha256", review.nextRoot()).put("attemptState", "CONFIRMED")
                    .put("attemptReview", digest(review.encode())).put("phase", "PREPARING");
            facts.remove(List.of("verifiedOutcome", "failureCode", "failureEvent", "buildReviewState", "buildReviewAttempt", "buildReviewUnit", "buildReviewPrevious", "buildReviewNext", "buildReviewIntent"));
            requireRoot(facts); return facts.toString().getBytes(StandardCharsets.UTF_8).length <= 12000;
        } catch (SQLException | IllegalArgumentException invalid) { return false; }
    }

    /** Requires exact root and operation identifiers with no implicit missing state. / 要求精确根及操作标识，不隐式补缺失状态。
     * @param facts retained root checkpoint / 留存根检查点 */
    static void requireRoot(JsonNode facts) {
        if (facts.path("version").intValue() != 3) {
            if (facts.has("attemptState") || facts.has("operation")) throw new IllegalArgumentException("Original graph version has no publication attempts");
            return;
        }
        canonicalUuid(facts, "attemptPreviousOperation"); canonicalUuid(facts, "attemptNextOperation");
        for (String field : List.of("attemptRootPrevious", "attemptRootNext", "attemptIntent")) hash(facts, field);
        if (!facts.path("attemptRetry").isInt() || facts.path("attemptRetry").intValue() < 1 || facts.path("attemptRetry").intValue() > 2
                || !facts.path("attemptUnit").isTextual() || !text(facts, "attemptUnit").matches("[a-z0-9][a-z0-9-]{0,62}")
                || !facts.path("attemptState").isTextual() || !Set.of("PENDING", "CONFIRMED").contains(text(facts, "attemptState")))
            throw new IllegalArgumentException("Original graph attempt is incomplete");
        if (text(facts, "attemptState").equals("PENDING")) {
            if (!(facts.has("operation") ? text(facts, "operation") : text(facts, "task")).equals(text(facts, "attemptPreviousOperation"))
                    || !text(facts, "planSha256").equals(text(facts, "attemptRootPrevious")) || !text(facts, "phase").equals("SESSION_CLOSED"))
                throw new IllegalArgumentException("Original closed graph changed before its final review");
        } else {
            canonicalUuid(facts, "operation"); hash(facts, "attemptReview");
            if (!text(facts, "operation").equals(text(facts, "attemptNextOperation")) || !text(facts, "planSha256").equals(text(facts, "attemptRootNext")))
                throw new IllegalArgumentException("New graph operation is not formally confirmed");
        }
    }

    /** Requires a strictly textual original field. / 要求严格文本原字段。
     * @param facts original checkpoint / 原检查点
     * @param field exact field / 精确字段
     * @return original value / 原值 */
    private static String text(JsonNode facts, String field) {
        if (!facts.path(field).isTextual()) throw new IllegalArgumentException("Original attempt field is absent");
        return facts.path(field).textValue();
    }

    /** Requires an exact canonical original UUID. / 要求精确规范原UUID。
     * @param facts original checkpoint / 原检查点
     * @param field exact UUID field / 精确UUID字段 */
    private static void canonicalUuid(JsonNode facts, String field) {
        String value = text(facts, field);
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Original attempt UUID is noncanonical");
    }

    /** Requires a real lowercase SHA without textual coercion. / 要求真实小写SHA，不进行文本强制转换。
     * @param facts original checkpoint / 原检查点
     * @param field exact digest field / 精确摘要字段 */
    private static void hash(JsonNode facts, String field) {
        if (!text(facts, field).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Original attempt SHA is malformed");
    }

    /** Hashes full durable consent rather than copying it into the small checkpoint. / 哈希完整持久同意，不将其复制进小检查点。
     * @param text exact canonical consent / 精确规范同意
     * @return lowercase SHA / 小写SHA */
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("JDK SHA-256 unavailable", unavailable); }
    }

    /** Frames full consent fields by UTF-8 byte length rather than ambiguous separators. / 按UTF-8字节长度分界完整同意字段，不使用有歧义分隔符。
     * @param fields exact ordered durable fields / 精确有序持久字段
     * @return lowercase consent SHA / 小写同意SHA */
    private static String binding(List<String> fields) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("JDK SHA-256 unavailable", unavailable); }
    }
}
