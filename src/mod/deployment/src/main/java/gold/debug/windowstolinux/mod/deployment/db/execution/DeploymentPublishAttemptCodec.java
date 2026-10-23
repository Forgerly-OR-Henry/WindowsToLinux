package gold.debug.windowstolinux.mod.deployment.db.execution;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec;

/** Explicit bounded request formats for at most three immutable operations of one original execution. / 一个原执行至多三个不可变操作的明确有界请求格式。 */
final class DeploymentPublishAttemptCodec {
    /** Original 144 MiB, two 36 MiB reviews, two 18 MiB compact proposals, two 7 MiB proofs and bounded framing. / 原144MiB、两份36MiB审阅、两份18MiB紧凑提案、两份7MiB证明及有界分界。 */
    static final int LIMIT = 272 * 1_048_576;

    /** Mandatory current correction-chain fields. / 当前纠正链必需字段。 */
    static final Set<String> FIELDS = Set.of("attemptProofs", "attemptReviews", "attemptBuildReviews",
        "attemptTemplates", "attemptOperation", "attemptTemplate", "attemptIntent", "attemptPendingProof",
        "attemptFallbacks");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentPublishAttemptCodec() {
    }

    /** Returns the current original operation without deriving it from task state or a candidate path. / 返回当前原操作，不从任务状态或候选路径推导。
     * @param request complete validated request / 完整已校验请求
     * @return current publication operation / 当前发布操作
     * @throws SQLException if an explicit operation is malformed / 明确操作无效时 */
    static UUID operation(JsonNode request) throws SQLException {
        return uuid(request, "attemptOperation");
    }

    /** Counts the same original corrections across build and publication boundaries. / 跨构建与发布边界统计同一原纠正次数。
     * @param request complete original request / 完整原请求
     * @return actual formally consumed corrections / 实际正式已消费纠正
     * @throws SQLException if a bounded history is malformed / 有界历史无效时 */
    static int retries(JsonNode request) throws SQLException {
        int total = array(request, "buildReviews", 2).size();
        if (!request.path("attemptFallbacks").isInt() || request.path("attemptFallbacks").intValue() < 0
            || request.path("attemptFallbacks").intValue() > 2)
            throw new SQLException("Original fixed catalog fallback count is malformed");
        total += request.path("attemptFallbacks").intValue();
        total += array(request, "attemptReviews", 2).size();
        for (var group : array(request, "attemptBuildReviews", 2)) {
            if (!group.isArray() || group.size() > 2)
                throw new SQLException("Invalid retired build history");
            total += group.size();
        }
        if (total > 2)
            throw new SQLException("Original cumulative retry allowance was exceeded");
        return total;
    }

    /** Returns the exact current complete template, using only first durable fixed configurations. / 仅使用首份持久固定配置返回精确当前完整模板。
     * @param request complete later request / 完整后续请求
     * @param execution original formal identity / 原正式身份
     * @return current immutable proposal or absence before publish / 当前不可变提案或发布前空值
     * @throws SQLException if canonical hydration or identity conflicts / 规范重建或身份冲突时 */
    static Optional<DeploymentPublishProposal> template(JsonNode request, UUID execution) throws SQLException {
        if (request.path("publicationTemplate").isNull()) return Optional.empty();
        var first = first(request, execution);
        if (array(request, "attemptReviews", 2).isEmpty()) return Optional.of(first);
        return request.path("attemptTemplate").isNull()
            ? Optional.empty()
            : Optional.of(DeploymentAttemptTemplateCodec.decodeCompact(first,
            request.path("attemptTemplate").toString(), execution));
    }

    /** Reads the original full proposal which remains immutable across every later operation. / 读取跨所有后续操作保持不可变的原完整提案。
     * @param request complete original request / 完整原请求
     * @param execution authentic execution identity / 真实执行身份
     * @return complete original proposal / 完整原提案
     * @throws SQLException if the original proposal is absent or malformed / 原提案缺失或无效时 */
    private static DeploymentPublishProposal first(JsonNode request, UUID execution) throws SQLException {
        if (!request.path("publicationTemplate").isObject())
            throw new SQLException("Later operation lacks the complete original template");
        return DeploymentPublishTemplateCodec.decodeTemplate(request.path("publicationTemplate").toString(),
            execution);
    }

    /** Reads one retired complete proposal by its original operation index. / 按原操作索引读取一份已退出完整提案。
     * @param request complete request / 完整请求
     * @param index zero-based retired operation / 从零起已退出操作
     * @param execution original execution / 原执行
     * @return exact original proposal / 精确原提案
     * @throws SQLException if a retired pointer or proposal conflicts / 已退出指针或提案冲突时 */
    static DeploymentPublishProposal retired(JsonNode request, int index, UUID execution) throws SQLException {
        var first = first(request, execution);
        if (index == 0)
            return first;
        var templates = array(request, "attemptTemplates", 1);
        if (index != 1 || templates.size() != 1)
            throw new SQLException("Original retired template pointer is missing");
        return DeploymentAttemptTemplateCodec.decodeCompact(first, templates.get(0).toString(), execution);
    }

    /** Checks the full formal chain while refusing any interpretation as permission to retry across boot. / 核对完整正式链，拒绝将其解释为跨启动重试权限。
     * @param record authentic stored execution / 真实已存执行
     * @param request complete explicitly versioned request / 完整明确版本化请求
     * @throws SQLException if root, operation, proof, template or retry bindings differ / 根、操作、证明、模板或重试绑定不符时 */
    static void known(DeploymentExecutionRecord record, JsonNode request) throws SQLException {
        if (record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED
            || !string(request, "operationType").equals("deployment-publication"))
            throw new SQLException("Later operation belongs only to original assisted publication");
        var original = first(request, record.identity().executionId());
        var proofs = array(request, "attemptProofs", 2);
        var reviews = array(request, "attemptReviews", 2);
        var groups = array(request, "attemptBuildReviews", 2);
        var templates = array(request, "attemptTemplates", 1);
        if (proofs.size() != reviews.size() || groups.size() != reviews.size()
            || templates.size() != Math.max(0, reviews.size() - 1) || !request.has("attemptTemplate")
            || !request.has("attemptIntent") || !request.has("attemptPendingProof"))
            throw new SQLException("Incomplete original attempt chain");
        if (reviews.isEmpty() && (!request.path("attemptTemplate").isNull()
            || request.path("attemptFallbacks").intValue() != 0 && request.path("attemptIntent").isNull()))
            throw new SQLException("Initial publication contains unconfirmed later evidence");
        UUID operation = record.identity().taskId();
        String root = null;
        if (!request.path("attemptFallbacks").isInt())
            throw new SQLException("Original fallback budget is absent");
        int consumed = request.path("attemptFallbacks").intValue();
        var failures = new HashSet<String>();
        for (int index = 0; index < reviews.size(); index++) {
            var proof = DeploymentPublishAttemptProof.decode(proofs.get(index));
            var review = DeploymentPublishAttemptReview.decode(reviews.get(index));
            var prior = builds(groups.get(index));
            consumed += prior.size();
            if (root != null && !prior.isEmpty() && !prior.getFirst().previousRoot().equals(root))
                throw new SQLException("Retired build changed the confirmed publication root");
            if (!prior.isEmpty())
                root = prior.getLast().nextRoot();
            if (!proof.task().equals(record.identity().taskId()) || !proof.operation().equals(operation)
                || !review.previousOperation().equals(operation)
                || root != null && !root.equals(review.previousRoot()) || proof.retriesSpent() != consumed
                || review.retry() != ++consumed || !failures.add(failureBinding(proof, review.unitId())))
                throw new SQLException("Publish retry does not consume its exact original operation");
            var actualPrevious = retired(request, index, record.identity().executionId());
            requireProof(proof, actualPrevious);
            review.requirePrevious(proof, actualPrevious);
            root = review.nextRoot();
            operation = review.nextOperation();
        }
        var current = builds(request.path("buildReviews"));
        if (!current.isEmpty()) {
            if (root != null && !current.getFirst().previousRoot().equals(root))
                throw new SQLException("Current build changed its last confirmed publication root");
            root = current.getLast().nextRoot();
        }
        if (!uuid(request, "attemptOperation").equals(operation)
            || root != null && !root.equals(record.identity().reviewedRevision())
            || retries(request) != consumed + current.size())
            throw new SQLException("Current operation or reviewed root is not formally confirmed");
        var actual = template(request, record.identity().executionId());
        if (!reviews.isEmpty() && actual.isPresent())
            requireReviewTemplate(request, actual.orElseThrow());
        pending(record, request);
    }

    /** Reads original bounded build reviews with their unchanged strict codec. / 使用未变更严格codec读取原有界构建审阅。
     * @param node retained review array / 留存审阅数组
     * @return complete ordered build reviews / 完整有序构建审阅
     * @throws SQLException if local predecessor bindings conflict / 局部前驱绑定冲突时 */
    private static List<DeploymentAssistedBuildReview> builds(JsonNode node) throws SQLException {
        if (!node.isArray() || node.size() > 2)
            throw new SQLException("Invalid original build review group");
        var values = new ArrayList<DeploymentAssistedBuildReview>();
        for (var entry : node) {
            var value = DeploymentAssistedBuildReview.decode(entry);
            DeploymentAssistedBuildReview.requireContinuation(values, value.unitId(), value.previousPlan(),
                value.previousScript(), value.previousTools());
            if (value.attempt() != values.size() + 1
                || !values.isEmpty() && !value.previousRoot().equals(values.getLast().nextRoot()))
                throw new SQLException("Retired original build review chain changed");
            values.add(value);
        }
        return List.copyOf(values);
    }

    /** Checks full pending consent without consuming it or sending a recovery action. / 核对完整待定同意，不消费或发送恢复动作。
     * @param record authentic original execution / 真实原执行
     * @param request actual later request / 实际后续请求
     * @throws SQLException if a pending predecessor or allowance differs / 待定前驱或额度不符时 */
    private static void pending(DeploymentExecutionRecord record, JsonNode request) throws SQLException {
        if (request.path("attemptIntent").isNull() != request.path("attemptPendingProof").isNull())
            throw new SQLException("Pending operation lacks its complete original proof");
        if (request.path("attemptIntent").isNull())
            return;
        var intent = DeploymentPublishAttemptIntent.decode(request.path("attemptIntent"));
        var proof = DeploymentPublishAttemptProof.decode(request.path("attemptPendingProof"));
        if (!request.path("buildIntent").isNull() || !proof.task().equals(record.identity().taskId())
            || !proof.operation().equals(uuid(request, "attemptOperation"))
            || !proof.reviewedRevision().equals(record.identity().reviewedRevision())
            || !intent.previousOperation().equals(proof.operation())
            || !intent.previousRoot().equals(proof.reviewedRevision()) || intent.retry() != retries(request) + 1
            || proof.retriesSpent() != retries(request)
            || !intent.proofSha256().equals(DeploymentPublishAttemptProof.digest(proof.encode())))
            throw new SQLException("Pending publication consent changed the original root");
        var actual = template(request, record.identity().executionId())
            .orElseThrow(() -> new SQLException("Pending publication recovery lacks the actual previous template"));
        requireProof(proof, actual);
        intent.requirePrevious(proof, actual);
        var reviews = array(request, "attemptReviews", 2);
        for (int index = 0; index < reviews.size(); index++) {
            var review = DeploymentPublishAttemptReview.decode(reviews.get(index));
            var originalProof = DeploymentPublishAttemptProof.decode(array(request, "attemptProofs", 2).get(index));
            if (failureBinding(originalProof, review.unitId()).equals(failureBinding(proof, intent.unitId())))
                throw new SQLException("Repeated unchanged publication failure cannot authorize another operation");
        }
    }

    /** Uses actual executed source, script, tools and failure rather than new operation or evidence timestamps. / 使用实际执行来源、脚本、工具及失败，排除新操作及依据时刻。
     * @param proof complete original physical predecessor / 完整原物理前驱
     * @param unit exact corrected unit / 精确纠正单元
     * @return stable original failure binding / 稳定原失败绑定
     * @throws SQLException if the executed child binding is absent / 已执行子绑定缺失时 */
    private static String failureBinding(DeploymentPublishAttemptProof proof, String unit) throws SQLException {
        var child = proof.children().stream().filter(value -> value.unitId().equals(unit)).findFirst()
            .orElseThrow(() -> new SQLException("Original failed unit is absent"));
        return DeploymentPublishAttemptProof
            .digest(object().put("unit", unit).put("archive", string(child.checkpoint(), "archiveSha256"))
                .put("script", string(child.checkpoint(), "buildScript"))
                .put("tools", string(child.checkpoint(), "buildTools")).put("code", proof.failureCode().name())
                .put("event", proof.failureEvent()).toString());
    }

    /** Requires all original units and complete previous template before beginning new consent. / 新同意开始前要求全部原单元及完整旧模板。
     * @param proof original completed cleanup / 原已完成清理
     * @param original actual immutable proposal / 实际不可变提案
     * @throws SQLException if complete graph or template changed / 完整图或模板改变时 */
    private static void requireProof(DeploymentPublishAttemptProof proof, DeploymentPublishProposal original)
        throws SQLException {
        if (!proof.templateSha256().equals(
            DeploymentPublishAttemptProof.digest(DeploymentPublishTemplateCodec.encodeTemplate(original)))
            || !new HashSet<>(original.units().stream().map(unit -> unit.unit().id()).toList())
            .equals(new HashSet<>(proof.children().stream()
                .map(DeploymentPublishAttemptProof.Child::unitId).toList())))
            throw new SQLException("Publish recovery omitted or replaced an original unit");
        for (var unit : original.units()) {
            var child = proof.children().stream().filter(value -> value.unitId().equals(unit.unit().id())).findFirst()
                .orElseThrow();
            if (!string(child.checkpoint(), "ownerManifestSha256").equals(unit.unit().ownershipManifestSha256())
                || !string(child.checkpoint(), "server").equals(unit.unit().server().id())
                || !string(child.checkpoint(), "releaseRoot").equals(unit.unit().releaseRoot())
                || child.checkpoint().has("releaseSha256")
                && !string(child.checkpoint(), "releaseSha256").equals(unit.release().sha256()))
                throw new SQLException("Retired proof changed an original release owner");
        }
    }

    /** Adds full typed consent only after the old operation and its session are actually closed. / 仅在旧操作及会话实际关闭后增加完整类型化同意。
     * @param record original executing row / 原执行行
     * @param intent full explicit human consent / 完整明确人工同意
     * @param proof full original cleanup proof / 完整原清理证明
     * @return complete new explicitly versioned request / 完整新明确版本化请求
     * @throws SQLException if any actual previous binding conflicts / 任一实际原绑定冲突时 */
    static String withIntent(DeploymentExecutionRecord record, DeploymentPublishAttemptIntent intent,
                             DeploymentPublishAttemptProof proof) throws SQLException {
        var request = parse(record.requestJson(), LIMIT);
        if (array(request, "attemptReviews", 2).isEmpty()) {
            int fallbacks = proof.retriesSpent() - array(request, "buildReviews", 2).size();
            if (fallbacks < 0 || fallbacks > 2 || request.path("attemptFallbacks").intValue() != 0
                && request.path("attemptFallbacks").intValue() != fallbacks)
                throw new SQLException("Original observed fallback budget conflicts");
            request.put("attemptFallbacks", fallbacks);
        }
        if (!request.path("attemptIntent").isNull() || !request.path("buildIntent").isNull())
            throw new SQLException("Original publication already has pending consent");
        request.set("attemptIntent", parse(intent.encode(), DeploymentPublishAttemptIntent.LIMIT));
        request.set("attemptPendingProof", parse(proof.encode(), DeploymentPublishAttemptProof.LIMIT));
        pending(record, request);
        return encodeBounded(request);
    }

    /** Consumes exact prior consent after actual tools and final approval, retaining the same formal execution. / 实际工具及最终审批后消费精确原同意，保留同一正式执行。
     * @param record original executing row / 原执行行
     * @param review complete actual final review / 完整实际最终审阅
     * @return new formal root and current operation request / 新正式根及当前操作请求
     * @throws SQLException if any original predecessor or proof differs / 任一原前驱或证明不符时 */
    static String withReview(DeploymentExecutionRecord record, DeploymentPublishAttemptReview review)
        throws SQLException {
        var request = parse(record.requestJson(), LIMIT);
        if (request.path("attemptIntent").isNull())
            throw new SQLException("Original publication has no durable new operation consent");
        var intent = DeploymentPublishAttemptIntent.decode(request.path("attemptIntent"));
        var proof = DeploymentPublishAttemptProof.decode(request.path("attemptPendingProof"));
        if (!intent.matches(review) || review.retry() != retries(request) + 1)
            throw new SQLException("Final publication review changed its explicit prior consent");
        var old = template(request, record.identity().executionId())
            .orElseThrow(() -> new SQLException("Original previous template is absent"));
        review.requirePrevious(proof, old);
        if (!request.path("attemptReviews").isEmpty()) {
            if (!request.path("attemptTemplate").isObject())
                throw new SQLException("Original later operation lacks its immutable proposal");
            request.withArray("attemptTemplates").add(request.path("attemptTemplate").deepCopy());
        }
        request.withArray("attemptProofs").add(request.path("attemptPendingProof").deepCopy());
        request.withArray("attemptReviews").add(parse(review.encode(), DeploymentPublishAttemptReview.LIMIT));
        request.withArray("attemptBuildReviews").add(request.path("buildReviews").deepCopy());
        request.putArray("buildReviews");
        request.put("reviewedRevision", review.nextRoot()).put("attemptOperation", review.nextOperation().toString());
        request.putNull("attemptTemplate").putNull("attemptIntent").putNull("attemptPendingProof");
        return encodeBounded(request);
    }

    /** Records only the exact later immutable template after formal review confirmation. / 正式审阅确认后仅记录精确后续不可变模板。
     * @param record authentic original executing row / 真实原执行行
     * @param proposal complete actual later proposal / 完整实际后续提案
     * @return complete bounded request / 完整有界请求
     * @throws SQLException if fixed configuration, confirmed review or prior template differs / 固定配置、已确认审阅或原模板不符时 */
    static String withTemplate(DeploymentExecutionRecord record, DeploymentPublishProposal proposal)
        throws SQLException {
        var request = parse(record.requestJson(), LIMIT);
        if (array(request, "attemptReviews", 2).isEmpty() || !request.path("attemptIntent").isNull())
            throw new SQLException("Original later operation is not formally confirmed");
        String compact = DeploymentAttemptTemplateCodec.encodeCompact(first(request, record.identity().executionId()),
            proposal);
        if (!request.path("attemptTemplate").isNull() && !compact.equals(request.path("attemptTemplate").toString()))
            throw new SQLException("Original later publication template is immutable");
        requireReviewTemplate(request, proposal);
        request.set("attemptTemplate", parse(compact, DeploymentAttemptTemplateCodec.LIMIT));
        return encodeBounded(request);
    }

    /** Checks every latest publication correction against the complete actual later proposal. / 按完整实际后续提案核对各最近发布纠正。
     * @param request complete request / 完整请求
     * @param proposal actual later proposal / 实际后续提案
     * @throws SQLException if a corrected unit or configuration changed / 纠正单元或配置改变时 */
    private static void requireReviewTemplate(JsonNode request, DeploymentPublishProposal proposal)
        throws SQLException {
        var latest = new LinkedHashMap<String, DeploymentAssistedBuildReviewCodec.Description>();
        for (var entry : array(request, "attemptReviews", 2)) {
            var review = DeploymentPublishAttemptReview.decode(entry);
            latest.put(review.unitId(), DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan()));
        }
        for (var review : builds(request.path("buildReviews")))
            latest.put(review.unitId(), DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan()));
        for (var description : latest.values()) {
            var component = proposal.graph().components().stream()
                .filter(value -> value.unit().id().equals(description.applicationId())).findFirst()
                .orElseThrow(() -> new SQLException("Reviewed unit is absent from the actual later template"));
            var unit = proposal.units().stream().filter(value -> value.unit().id().equals(description.applicationId()))
                .findFirst().orElseThrow();
            var expectedConfiguration = object();
            var actualConfiguration = object();
            DeploymentPublishTemplateCodec.configuration(expectedConfiguration, description.configuration());
            DeploymentPublishTemplateCodec.configuration(actualConfiguration, unit.configuration());
            if (!component.reviewedRuntime().equals(description.runtime())
                || !component.resources().equals(description.resources())
                || !component.unit().server().id().equals(description.serverId())
                || !actualConfiguration.equals(expectedConfiguration)
                || !new HashSet<>(unit.secrets()).equals(new HashSet<>(description.secrets())))
                throw new SQLException("Later template changed its complete confirmed review");
        }
    }

    /** Allows only a confirmed operation which has not created its immutable publication template. / 仅允许尚未创建不可变发布模板的已确认操作。
     * @param request current later request / 当前后续请求
     * @return whether current build consent can be stored / 是否可存储当前构建同意 */
    static boolean buildBoundary(JsonNode request) {
        return request.path("attemptIntent").isNull() && (request.path("attemptReviews").isEmpty()
            ? request.path("publicationTemplate").isNull() : request.path("attemptTemplate").isNull());
    }

    /** Enforces per-field bounds before the complete independent later-format byte cap. / 完整独立后续格式字节上限前实施各字段边界。
     * @param request complete actual request / 完整实际请求
     * @return canonical bounded request / 规范有界请求
     * @throws SQLException if any admitted field or total byte bound rejects / 任一已准入字段或总字节边界拒绝时 */
    static String encodeBounded(ObjectNode request) throws SQLException {
        retries(request);
        if (!request.path("attemptIntent").isNull() && retries(request) >= 2
            || !request.path("buildIntent").isNull() && retries(request) >= 2)
            throw new SQLException("Original retry allowance cannot admit another pending consent");
        var metadata = request.deepCopy();
        metadata.remove(List.of("publicationTemplate", "buildReviews", "buildIntent", "attemptProofs", "attemptReviews",
            "attemptBuildReviews", "attemptTemplates", "attemptTemplate", "attemptIntent", "attemptPendingProof"));
        encode(metadata, 1_048_576);
        return encode(request, DeploymentExecutionCodec.requestLimit(request));
    }
}
