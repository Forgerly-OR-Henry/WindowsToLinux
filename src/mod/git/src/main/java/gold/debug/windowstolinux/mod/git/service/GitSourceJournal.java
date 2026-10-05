package gold.debug.windowstolinux.mod.git.service;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.file.material.MaterialRef;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.git.api.GitSourceRequest;
import gold.debug.windowstolinux.mod.git.file.GitMessages;
import gold.debug.windowstolinux.mod.git.file.GitPreparation;

/** Provider-owned original-task checkpoint; no phase authorizes replay. / 提供方所属原任务检查点；任何阶段均不授权重放。 */
final class GitSourceJournal implements GitPreparation.Observer {
    /** Strict bounded original-facts parser. / 严格有界原依据解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(8)
            .maxStringLength(12000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Exact host-owned original checkpoint. / 精确宿主持有原检查点。 */
    private final TaskCheckpoint checkpoint;

    /** Original task UUID. / 原任务 UUID。 */
    private final UUID task;

    /** Last durable CAS revision. / 最近持久 CAS 修订。 */
    private TaskCheckpoint.Snapshot snapshot;

    /** Validated current facts. / 已校验当前依据。 */
    private Facts facts;

    /** Durable effect boundaries. / 持久效果边界。 */
    enum PhaseState {
        /** Original identities recorded before creation. / 创建前已记录原身份。 */
        PLANNED,
        /** Request may have been delivered. / 请求可能已投递。 */
        REQUESTED,
        /** Native exit and original result proved. / 原生退出及原结果已证明。 */
        RECEIPT,
        /** Original platform publication proved. / 原平台发布已证明。 */
        PUBLISHED
    }

    /** Exact bounded intent and its proved effect boundaries. / 精确有界意图及已证明效果边界。
     * @param task authentic original task identity / 真实原任务身份
     * @param request immutable source intent / 不可变来源意图
     * @param plan original physical identities / 原物理身份
     * @param phase durable effect phase / 持久效果阶段
     * @param receipt original verified native result / 原已验证原生结果
     * @param manifest original published manifest evidence / 原已发布 manifest 依据 */
    record Facts(UUID task, GitSourceRequest request, GitPreparation.Planned plan, PhaseState phase,
                 Optional<GitPreparation.Receipt> receipt, Optional<ManifestEvidence> manifest) {
        /** Rejects incomplete or conflicting original facts. / 拒绝不完整或冲突原依据。
         * @param task original task / 原任务
         * @param request original intent / 原意图
         * @param plan original identities / 原身份
         * @param phase original phase / 原阶段
         * @param receipt original receipt / 原回执
         * @param manifest original manifest evidence / 原 manifest 依据 */
        Facts {
            Objects.requireNonNull(task);
            Objects.requireNonNull(request);
            Objects.requireNonNull(plan);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(receipt);
            Objects.requireNonNull(manifest);
            GitMessages.request(request);
            if (!plan.job().equals(request.operationId())
                || Set.of(plan.job(), plan.checkout(), plan.control(), plan.process(), request.materialId()).size() != 5
                || (phase == PhaseState.RECEIPT || phase == PhaseState.PUBLISHED) != receipt.isPresent()
                || (phase == PhaseState.PUBLISHED) != manifest.isPresent()
                || phase == PhaseState.PUBLISHED && receipt.orElseThrow().outcome() != TaskResult.SUCCEEDED)
                throw new IllegalArgumentException("Original Git facts are inconsistent");
        }
    }

    /** Small persistent publication proof. / 小型持久发布证明。
     * @param digest exact manifest digest / 精确 manifest 摘要
     * @param bytes original material bytes / 原素材字节
     * @param files original material files / 原素材文件 */
    record ManifestEvidence(String digest, long bytes, int files) {
        /** Requires exact nonnegative counters and digest. / 要求精确非负计数及摘要。
         * @param digest original digest / 原摘要
         * @param bytes original bytes / 原字节
         * @param files original files / 原文件 */
        ManifestEvidence {
            if (digest == null || !digest.matches("[0-9a-f]{64}") || bytes < 0 || files < 0)
                throw new IllegalArgumentException("Invalid publication proof");
        }
    }

    /** Reopens only an authenticated original checkpoint. / 仅重开已认证原检查点。
     * @param task original task / 原任务
     * @param checkpoint original host handle / 原宿主句柄
     * @throws ApiException if original facts are unavailable / 原依据不可用时 */
    private GitSourceJournal(UUID task, TaskCheckpoint checkpoint) throws ApiException {
        this.task = task;
        this.checkpoint = checkpoint;
        refresh();
    }

    /** Records unique identities before first filesystem or process creation. / 首次文件系统或进程创建前记录唯一身份。
     * @param service provider-bound task capability / 提供方绑定任务能力
     * @param task authentic original task / 真实原任务
     * @param request validated immutable intent / 已验证不可变意图
     * @return original durable journal / 原持久日志
     * @throws ApiException if first creation or durable acceptance fails / 首次创建或持久接纳失败时 */
    static GitSourceJournal begin(TaskService service, TaskContext task, GitSourceRequest request) throws ApiException {
        var plan = new GitPreparation.Planned(request.operationId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var facts = new Facts(task.id(), request, plan, PhaseState.PLANNED, Optional.empty(), Optional.empty());
        String payload = encode(facts);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 12000) throw invalid();
        return new GitSourceJournal(task.id(), service.beginCheckpoint(task, key(request.operationId()), payload));
    }

    /** Finds retained original evidence, including released rows before archive. / 查找留存原依据，包括归档前已释放行。
     * @param service provider-bound task capability / 提供方绑定任务能力
     * @param task authentic original task / 真实原任务
     * @param operation original operation / 原操作
     * @return exact original journal or absence / 精确原日志或不存在
     * @throws ApiException if authority or stored evidence is unconfirmed / 授权或存储依据未确认时 */
    static Optional<GitSourceJournal> find(TaskService service, TaskContext task, UUID operation) throws ApiException {
        var original = service.findCheckpoint(task, key(operation));
        if (original.isEmpty()) return Optional.empty();
        var result = new GitSourceJournal(task.id(), original.orElseThrow());
        if (!result.facts.request().operationId().equals(operation)) throw invalid();
        return Optional.of(result);
    }

    /** Returns the original owner-local checkpoint key. / 返回原所有者内部检查点键。
     * @param operation original operation / 原操作
     * @return canonical key / 规范键 */
    private static String key(UUID operation) {
        return "git-source-" + Objects.requireNonNull(operation);
    }

    /** Returns the last strictly decoded facts. / 返回最近严格解码依据。
     * @return exact facts / 精确依据 */
    Facts facts() {
        return facts;
    }

    /** Returns durable physical identities. / 返回持久物理身份。
     * @return original plan / 原计划 */
    @Override
    public GitPreparation.Planned planned() {
        return facts.plan();
    }

    /** Records possible delivery before sending the one original request. / 发送唯一原请求前记录可能投递。
     * @throws ApiException if phase or CAS is unconfirmed / 阶段或 CAS 未确认时 */
    @Override
    public void beforeRequest() throws ApiException {
        if (facts.phase() != PhaseState.PLANNED) throw invalid();
        advance(new Facts(task, facts.request(), facts.plan(), PhaseState.REQUESTED, Optional.empty(), Optional.empty()));
    }

    /** Records only original positive native cleanup evidence, idempotently. / 幂等地仅记录原肯定原生清理依据。
     * @param receipt verified original receipt / 已验证原回执
     * @throws ApiException if phase or original receipt differs / 阶段或原回执不同 */
    @Override
    public void receipt(GitPreparation.Receipt receipt) throws ApiException {
        refresh();
        if ((facts.phase() == PhaseState.RECEIPT || facts.phase() == PhaseState.PUBLISHED)
            && facts.receipt().equals(Optional.of(receipt))) return;
        if (facts.phase() != PhaseState.REQUESTED) throw invalid();
        advance(new Facts(task, facts.request(), facts.plan(), PhaseState.RECEIPT, Optional.of(receipt), Optional.empty()));
    }

    /** Records the already published original platform reference. / 记录已经发布的原平台引用。
     * @param reference exact original reference / 精确原引用
     * @throws ApiException if reference or CAS is unconfirmed / 引用或 CAS 未确认时 */
    void published(MaterialRef reference) throws ApiException {
        refresh();
        if (!reference.id().equals(facts.request().materialId()) || !reference.operationId().equals(facts.plan().job())
            || !reference.taskId().equals(task) || facts.receipt().isEmpty()
            || !reference.revision().equals(facts.receipt().orElseThrow().commit().orElse(""))) throw invalid();
        var evidence = new ManifestEvidence(reference.manifestSha256(), reference.byteCount(), reference.fileCount());
        if (facts.phase() == PhaseState.PUBLISHED && facts.manifest().equals(Optional.of(evidence))) return;
        if (facts.phase() != PhaseState.RECEIPT || facts.receipt().orElseThrow().outcome() != TaskResult.SUCCEEDED)
            throw invalid();
        advance(new Facts(task, facts.request(), facts.plan(), PhaseState.PUBLISHED, facts.receipt(), Optional.of(evidence)));
    }

    /** Releases only this provider's proved original checkpoint occupancy. / 仅释放此提供方已证明原检查点占用。
     * @throws ApiException if original durable release fails / 原持久释放失败时 */
    void close() throws ApiException {
        checkpoint.close();
    }

    /** Rereads original facts after a possibly lost acknowledgement. / 确认可能丢失后重读原依据。
     * @throws ApiException if original durable facts are unconfirmed / 原持久依据未确认时 */
    void refresh() throws ApiException {
        snapshot = checkpoint.snapshot();
        facts = decode(snapshot.payload(), task);
    }

    /** Advances only the exact original revision. / 仅推进精确原修订。
     * @param next next complete facts / 下个完整依据
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    private void advance(Facts next) throws ApiException {
        snapshot = checkpoint.advance(snapshot, encode(next));
        facts = next;
    }

    /** Encodes complete bounded original facts without credentials or paths to source payload. / 编码完整有界原依据，不含凭据或来源载荷路径。
     * @param facts original facts / 原依据
     * @return exact versioned JSON / 精确带版本 JSON */
    private static String encode(Facts facts) {
        try {
            var request = facts.request();
            var root = JSON.createObjectNode().put("formatVersion", 1).put("taskId", facts.task().toString())
                .put("operationId", request.operationId().toString()).put("materialId", request.materialId().toString())
                .put("executable", request.gitExecutable()).put("checkoutId", facts.plan().checkout().toString())
                .put("controlId", facts.plan().control().toString()).put("processId", facts.plan().process().toString())
                .put("phase", facts.phase().name());
            root.set("request", JSON.readTree(GitMessages.encode(GitMessages.request(request))));
            facts.receipt().ifPresentOrElse(receipt -> {
                var node = root.putObject("receipt").put("outcome", receipt.outcome().name());
                receipt.commit().ifPresentOrElse(value -> node.put("commit", value), () -> node.putNull("commit"));
                receipt.problemCode().ifPresentOrElse(value -> node.put("code", value.name()), () -> node.putNull("code"));
                receipt.eventCode().ifPresentOrElse(value -> node.put("event", value), () -> node.putNull("event"));
            }, () -> root.putNull("receipt"));
            facts.manifest().ifPresentOrElse(value -> root.putObject("manifest").put("digest", value.digest())
                .put("bytes", value.bytes()).put("files", value.files()), () -> root.putNull("manifest"));
            return root.toString();
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Cannot encode original Git facts");
        }
    }

    /** Decodes only strict original identities and phase evidence. / 仅解码严格原身份及阶段依据。
     * @param payload original bounded payload / 原有界载荷
     * @param task expected original task / 预期原任务
     * @return checked facts / 已核验依据
     * @throws ApiException if stored facts are invalid / 存储依据无效时 */
    private static Facts decode(String payload, UUID task) throws ApiException {
        try {
            var root = JSON.readTree(payload);
            fields(root, Set.of("formatVersion", "taskId", "operationId", "materialId", "executable", "checkoutId",
                "controlId", "processId", "phase", "request", "receipt", "manifest"));
            if (!root.get("formatVersion").isInt() || root.get("formatVersion").intValue() != 1)
                throw new IllegalArgumentException();
            var request = GitMessages.source(uuid(root, "operationId"), uuid(root, "materialId"), text(root, "executable"),
                GitMessages.decode(root.get("request").toString()));
            var plan = new GitPreparation.Planned(request.operationId(), uuid(root, "checkoutId"), uuid(root, "controlId"),
                uuid(root, "processId"));
            Optional<GitPreparation.Receipt> receipt = Optional.empty();
            if (!root.get("receipt").isNull()) {
                var node = root.get("receipt");
                fields(node, Set.of("outcome", "commit", "code", "event"));
                receipt = Optional.of(new GitPreparation.Receipt(TaskResult.valueOf(text(node, "outcome")), optional(node, "commit"),
                    optional(node, "code").map(ProblemCode::valueOf), optional(node, "event")));
            }
            Optional<ManifestEvidence> manifest = Optional.empty();
            if (!root.get("manifest").isNull()) {
                var node = root.get("manifest");
                fields(node, Set.of("digest", "bytes", "files"));
                if (!node.get("bytes").isIntegralNumber() || !node.get("bytes").canConvertToLong()
                    || !node.get("files").isInt()) throw new IllegalArgumentException();
                manifest = Optional.of(new ManifestEvidence(text(node, "digest"), node.get("bytes").longValue(), node.get("files").intValue()));
            }
            var result = new Facts(uuid(root, "taskId"), request, plan, PhaseState.valueOf(text(root, "phase")), receipt, manifest);
            if (!result.task().equals(task)) throw new IllegalArgumentException();
            return result;
        } catch (java.io.IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /** Requires the complete exact field set. / 要求完整精确字段集合。
     * @param node original object / 原对象
     * @param keys exact keys / 精确键 */
    private static void fields(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || node.size() != keys.size()) throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(key -> {
            if (!keys.contains(key)) throw new IllegalArgumentException();
        });
    }

    /** Reads exact text without coercion. / 不强转地读取精确文本。
     * @param node original object / 原对象
     * @param key original field / 原字段
     * @return exact text / 精确文本 */
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw new IllegalArgumentException();
        return node.get(key).textValue();
    }

    /** Reads exact nullable text. / 读取精确可空文本。
     * @param node original object / 原对象
     * @param key original field / 原字段
     * @return original optional text / 原可选文本 */
    private static Optional<String> optional(JsonNode node, String key) {
        return node.path(key).isNull() ? Optional.empty() : Optional.of(text(node, key));
    }

    /** Requires canonical original UUID text. / 要求规范原 UUID 文本。
     * @param node original object / 原对象
     * @param key original field / 原字段
     * @return exact UUID / 精确 UUID */
    private static UUID uuid(JsonNode node, String key) {
        String raw = text(node, key);
        UUID value = UUID.fromString(raw);
        if (!value.toString().equals(raw)) throw new IllegalArgumentException();
        return value;
    }

    /** Produces a bounded contract diagnostic. / 生成有界契约诊断。
     * @return safe failure / 安全失败 */
    private static ApiException invalid() {
        return new ApiException(new Problem(ProblemCode.CONTRACT_VIOLATION, "GIT_SOURCE_JOURNAL_INVALID",
            "Original Git source evidence could not be verified", Map.of(), Optional.empty()));
    }
}
