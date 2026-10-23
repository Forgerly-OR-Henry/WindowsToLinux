package gold.debug.windowstolinux.mod.deployment.secret;

import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefKey;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;

/** Bounded original-task facts for one newly created credential and immutable business revision. / 一项新建凭据与不可变业务修订的有界原任务依据。 */
final class DeploymentSecretProvisionActionLog {
    /** One owner-local checkpoint per original task. / 每个原任务一个所有者内部检查点。 */
    static final String KEY = "deployment-secret-revision";

    /** Strict parser rejecting duplicate fields and trailing input. / 拒绝重复字段与尾随输入的严格解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Exact host checkpoint. / 精确宿主检查点。 */
    private final TaskCheckpoint checkpoint;

    /** Last confirmed CAS value. / 最近已确认的 CAS 值。 */
    private TaskCheckpoint.Snapshot snapshot;

    /** Decoded original facts. / 已解码的原依据。 */
    private Facts facts;

    /** Irreversible boundaries of the original attempt. / 原尝试不可逆边界。 */
    enum PhaseType {
        /** No secret creation began. / 尚未开始秘密创建。 */
        PLANNED,
        /** Creation may have happened without an observed ID. / 可能已创建但未观察到 ID。 */
        CREATE_STARTED,
        /** The exact platform ID is durable. / 精确平台 ID 已持久化。 */
        CREATED,
        /** Original reference reservation may have happened. / 原引用预留可能已发生。 */
        RESERVE_STARTED,
        /** Original reference UUID is durable. / 原引用 UUID 已持久化。 */
        RESERVED,
        /** Business SQL may have committed. / 业务 SQL 可能已提交。 */
        WRITE_STARTED,
        /** Exact immutable business row was confirmed. / 精确不可变业务行已确认。 */
        SAVED,
        /** A definite failure was recorded before business success. / 业务成功前已记录确定失败。 */
        KNOWN_FAILURE
    }

    /** Complete non-secret original intent and result. / 完整不含秘密的原意图与结果。
     * @param task original platform task / 原平台任务
     * @param server original admitted server / 原准入服务器
     * @param revision immutable business revision / 不可变业务修订
     * @param purpose exact credential purpose / 精确凭据用途
     * @param createdAt reviewed millisecond timestamp / 已审阅毫秒时间
     * @param phase durable attempt phase / 持久尝试阶段
     * @param credentialId observed opaque ID / 已观察不透明 ID
     * @param referenceId original platform reference UUID / 原平台引用 UUID
     * @param failure definite bounded failure / 确定的有界失败 */
    record Facts(UUID task, ServerId server, DeploymentSecretRef revision, String purpose, Instant createdAt,
                 PhaseType phase, Optional<String> credentialId, Optional<UUID> referenceId,
                 Optional<Problem> failure) {
        /** Validates one immutable, possible phase. / 校验一个不可变且可能的阶段。
         * @param task original platform task / 原平台任务
         * @param server original admitted server / 原准入服务器
         * @param revision immutable business revision / 不可变业务修订
         * @param purpose exact credential purpose / 精确凭据用途
         * @param createdAt reviewed millisecond timestamp / 已审阅毫秒时间
         * @param phase durable attempt phase / 持久尝试阶段
         * @param credentialId observed opaque ID / 已观察不透明 ID
         * @param referenceId original platform reference UUID / 原平台引用 UUID
         * @param failure definite bounded failure / 确定的有界失败 */
        Facts {
            Objects.requireNonNull(task);
            Objects.requireNonNull(server);
            Objects.requireNonNull(revision);
            Objects.requireNonNull(createdAt);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(credentialId);
            Objects.requireNonNull(referenceId);
            Objects.requireNonNull(failure);
            if (server.id() == null || server.id().isBlank() || server.id().length() > 512
                || server.id().indexOf(0) >= 0 || purpose == null || purpose.isBlank() || purpose.length() > 128
                || purpose.indexOf(0) >= 0 || createdAt.getNano() % 1_000_000 != 0
                || revision.revision() > Integer.MAX_VALUE
                || credentialId.filter(id -> id.isBlank() || id.length() > 512 || id.indexOf(0) >= 0).isPresent()
                || referenceId.isPresent() && credentialId.isEmpty()
                || failure.filter(p -> p.code() == ProblemCode.UNKNOWN
                || !p.eventCode().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")).isPresent()
                || !valid(phase, credentialId.isPresent(), referenceId.isPresent(), failure.isPresent()))
                throw new IllegalArgumentException("Original secret facts are invalid");
        }
    }

    /** Binds an already created host checkpoint. / 绑定已有宿主检查点。
     * @param checkpoint original handle / 原句柄
     * @param snapshot exact persisted value / 精确持久值
     * @param task expected original task / 预期原任务 */
    private DeploymentSecretProvisionActionLog(TaskCheckpoint checkpoint, TaskCheckpoint.Snapshot snapshot, UUID task) {
        this.checkpoint = checkpoint;
        this.snapshot = snapshot;
        this.facts = decode(snapshot.payload(), task);
    }

    /** Validates pure admission inputs before submitting a platform task. / 提交平台任务前校验纯准入输入。
     * @param server original server / 原服务器
     * @param revision original revision / 原修订
     * @param purpose exact purpose / 精确用途
     * @param createdAt reviewed time / 已审阅时间 */
    static void validateInput(ServerId server, DeploymentSecretRef revision, String purpose, Instant createdAt) {
        new Facts(new UUID(0, 0), server, revision, purpose, createdAt, PhaseType.PLANNED, Optional.empty(),
            Optional.empty(), Optional.empty());
    }

    /** Persists the original non-secret intent before creation. / 创建前持久化原不含秘密的意图。
     * @param tasks bound task capability / 绑定任务能力
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param revision original revision / 原修订
     * @param purpose exact purpose / 精确用途
     * @param createdAt reviewed time / 已审阅时间
     * @return original journal / 原日志
     * @throws ApiException if persistence is unconfirmed / 持久化未确认时 */
    static DeploymentSecretProvisionActionLog begin(TaskService tasks, TaskContext task, ServerId server,
                                                    DeploymentSecretRef revision, String purpose, Instant createdAt) throws ApiException {
        return begin(tasks, task, server, revision, purpose, createdAt, KEY);
    }

    /** Begins one pre-indexed child under the original publication task. / 在原发布任务中开始一项预索引子项。
     * @param tasks original task service / 原任务服务
     * @param task original task / 原任务
     * @param server admitted server / 已准入服务器
     * @param revision exact immutable business revision / 精确不可变业务修订
     * @param purpose exact secret purpose / 精确秘密用途
     * @param createdAt reviewed time / 已审阅时间
     * @param key planned child checkpoint key / 已计划子项检查点键
     * @return durable child journal / 持久子日志
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    static DeploymentSecretProvisionActionLog begin(TaskService tasks, TaskContext task, ServerId server,
                                                    DeploymentSecretRef revision, String purpose, Instant createdAt, String key) throws ApiException {
        checkedKey(key);
        var initial = new Facts(task.id(), server, revision, purpose, createdAt, PhaseType.PLANNED, Optional.empty(),
            Optional.empty(), Optional.empty());
        var handle = tasks.beginCheckpoint(task, key, encode(initial));
        return new DeploymentSecretProvisionActionLog(handle, handle.snapshot(), task.id());
    }

    /** Finds only the original retained checkpoint. / 仅查找留存的原检查点。
     * @param tasks bound task capability / 绑定任务能力
     * @param task original context / 原上下文
     * @return original journal or absence / 原日志或不存在
     * @throws ApiException if original evidence cannot be read / 原依据无法读取时 */
    static Optional<DeploymentSecretProvisionActionLog> find(TaskService tasks, TaskContext task) throws ApiException {
        return find(tasks, task, KEY);
    }

    /** Finds an indexed child without treating damaged evidence as absence. / 查找已索引子项，不把损坏依据当作不存在。
     * @param tasks original task service / 原任务服务
     * @param task original task / 原任务
     * @param key planned child checkpoint key / 已计划子项检查点键
     * @return original child or genuine absence / 原子项或真实不存在
     * @throws ApiException if lookup fails / 查找失败时 */
    static Optional<DeploymentSecretProvisionActionLog> find(TaskService tasks, TaskContext task, String key)
        throws ApiException {
        checkedKey(key);
        var original = tasks.findCheckpoint(task, key);
        if (original.isEmpty())
            return Optional.empty();
        var handle = original.orElseThrow();
        return Optional.of(new DeploymentSecretProvisionActionLog(handle, handle.snapshot(), task.id()));
    }

    /** Restricts child addresses to the prior durable database plan. / 将子项地址限制为先前持久数据库计划格式。
     * @param key candidate checkpoint key / 候选检查点键 */
    private static void checkedKey(String key) {
        if (!KEY.equals(key) && (key == null || !key.matches("deployment-secret-revision\\.[0-9a-f]{32}")))
            throw new IllegalArgumentException("Invalid secret child checkpoint key");
    }

    /** Returns the verified current facts. / 返回已核实当前依据。
     * @return immutable facts / 不可变依据 */
    Facts facts() {
        return facts;
    }

    /** Marks the last point before an opaque ID can be lost. / 标记不透明 ID 可能丢失前的最后边界。
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void createStarted() throws ApiException {
        require(PhaseType.PLANNED);
        advance(PhaseType.CREATE_STARTED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Durably captures the platform-issued ID immediately after return. / 平台返回后立即持久捕获其签发 ID。
     * @param secret exact created identity / 精确已创建身份
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void created(SecretId secret) throws ApiException {
        require(PhaseType.CREATE_STARTED);
        if (!facts.purpose().equals(secret.purpose()))
            throw invalid();
        advance(PhaseType.CREATED, Optional.of(secret.id()), Optional.empty(), Optional.empty());
    }

    /** Marks the reservation effect boundary. / 标记预留效果边界。
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void reserveStarted() throws ApiException {
        require(PhaseType.CREATED);
        advance(PhaseType.RESERVE_STARTED, facts.credentialId(), Optional.empty(), Optional.empty());
    }

    /** Records only the original matching reference UUID. / 仅记录原匹配引用 UUID。
     * @param id original platform reference / 原平台引用
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void reserved(UUID id) throws ApiException {
        require(PhaseType.RESERVE_STARTED);
        advance(PhaseType.RESERVED, facts.credentialId(), Optional.of(id), Optional.empty());
    }

    /** Marks the business commit boundary. / 标记业务提交边界。
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void writeStarted() throws ApiException {
        require(PhaseType.RESERVED);
        advance(PhaseType.WRITE_STARTED, facts.credentialId(), facts.referenceId(), Optional.empty());
    }

    /** Records confirmed exact business state. / 记录已确认的精确业务状态。
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void saved() throws ApiException {
        require(PhaseType.WRITE_STARTED);
        advance(PhaseType.SAVED, facts.credentialId(), facts.referenceId(), Optional.empty());
    }

    /** Preserves a known failure instead of reinterpreting later matching rows. / 保留已知失败，不由后来相同行改判。
     * @param problem bounded failure / 有界失败
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void knownFailure(Problem problem) throws ApiException {
        if (facts.phase() == PhaseType.SAVED || facts.phase() == PhaseType.KNOWN_FAILURE)
            throw invalid();
        var safe = new Problem(problem.code(), problem.eventCode(), "Original secret provision failed", Map.of(),
            Optional.empty());
        advance(PhaseType.KNOWN_FAILURE, facts.credentialId(), facts.referenceId(), Optional.of(safe));
    }

    /** Re-reads after uncertain acknowledgement without replacing the original facts. / 确认不明后重读而不覆盖原依据。
     * @return original current facts / 原当前依据
     * @throws ApiException if the original read fails / 原读取失败时 */
    Facts refresh() throws ApiException {
        snapshot = checkpoint.snapshot();
        facts = decode(snapshot.payload(), facts.task());
        return facts;
    }

    /** Releases only this original occupancy. / 仅释放此原占用。
     * @throws ApiException if release is unconfirmed / 释放未确认时 */
    void close() throws ApiException {
        checkpoint.close();
    }

    /** Advances one exact CAS revision. / 推进一次精确 CAS 修订。
     * @param phase next phase / 下一阶段
     * @param credentialId original credential / 原凭据
     * @param referenceId original reference / 原引用
     * @param failure known failure / 已知失败
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    private void advance(PhaseType phase, Optional<String> credentialId, Optional<UUID> referenceId,
                         Optional<Problem> failure) throws ApiException {
        var next = new Facts(facts.task(), facts.server(), facts.revision(), facts.purpose(), facts.createdAt(), phase,
            credentialId, referenceId, failure);
        snapshot = checkpoint.advance(snapshot, encode(next));
        facts = decode(snapshot.payload(), facts.task());
    }

    /** Requires the exact prior phase. / 要求精确前一阶段。
     * @param expected required phase / 所需阶段
     * @throws ApiException if the persisted phase differs / 持久阶段不符时 */
    private void require(PhaseType expected) throws ApiException {
        if (facts.phase() != expected)
            throw invalid();
    }

    /** Validates possible evidence for each phase. / 校验每个阶段可能的依据。
     * @param phase original phase / 原阶段
     * @param credential whether an ID is known / 是否已知 ID
     * @param reference whether reference UUID is known / 是否已知引用 UUID
     * @param failure whether a known failure is recorded / 是否记录已知失败
     * @return possible shape / 可能的形态 */
    private static boolean valid(PhaseType phase, boolean credential, boolean reference, boolean failure) {
        return switch (phase) {
            case PLANNED, CREATE_STARTED -> !credential && !reference && !failure;
            case CREATED, RESERVE_STARTED -> credential && !reference && !failure;
            case RESERVED, WRITE_STARTED, SAVED -> credential && reference && !failure;
            case KNOWN_FAILURE -> failure && (!reference || credential);
        };
    }

    /** Encodes only bounded, non-secret facts. / 仅编码有界且不含秘密的依据。
     * @param value original facts / 原依据
     * @return canonical JSON / 规范 JSON */
    private static String encode(Facts value) {
        var node = JSON.createObjectNode().put("formatVersion", 1).put("taskId", value.task().toString())
            .put("serverId", value.server().id()).put("secretId", value.revision().identifier())
            .put("revision", value.revision().revision()).put("purpose", value.purpose())
            .put("createdAt", value.createdAt().toEpochMilli()).put("phase", value.phase().name());
        value.credentialId().ifPresentOrElse(id -> node.put("credentialId", id), () -> node.putNull("credentialId"));
        value.referenceId().ifPresentOrElse(id -> node.put("referenceId", id.toString()),
            () -> node.putNull("referenceId"));
        value.failure().ifPresentOrElse(problem -> node.putObject("failure").put("code", problem.code().name())
            .put("event", problem.eventCode()), () -> node.putNull("failure"));
        return node.toString();
    }

    /** Parses exact canonical evidence without guessing missing fields. / 解析精确规范依据，不猜测缺失字段。
     * @param value persisted JSON / 持久 JSON
     * @param task expected original task / 预期原任务
     * @return decoded facts / 已解码依据 */
    private static Facts decode(String value, UUID task) {
        try {
            var node = JSON.readTree(value);
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!node.isObject()
                || !fields.equals(Set.of("formatVersion", "taskId", "serverId", "secretId", "revision", "purpose",
                "createdAt", "phase", "credentialId", "referenceId", "failure"))
                || !node.get("formatVersion").isInt() || node.get("formatVersion").intValue() != 1
                || !node.get("revision").isIntegralNumber() || !node.get("revision").canConvertToLong()
                || !node.get("createdAt").isIntegralNumber() || !node.get("createdAt").canConvertToLong())
                throw new IllegalArgumentException();
            var failure = node.get("failure").isNull()
                ? Optional.<Problem>empty()
                : Optional.of(problem(node.get("failure")));
            var facts = new Facts(uuid(node, "taskId"), new ServerId(text(node, "serverId")),
                new DeploymentSecretRef(text(node, "secretId"), node.get("revision").longValue()),
                text(node, "purpose"), Instant.ofEpochMilli(node.get("createdAt").longValue()),
                PhaseType.valueOf(text(node, "phase")), nullableText(node, "credentialId"),
                node.get("referenceId").isNull() ? Optional.empty() : Optional.of(uuid(node, "referenceId")),
                failure);
            if (!facts.task().equals(task) || !encode(facts).equals(node.toString()))
                throw new IllegalArgumentException();
            return facts;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Original secret checkpoint is invalid", invalid);
        }
    }

    /** Reads a bounded persisted failure. / 读取有界持久失败。
     * @param node original failure object / 原失败对象
     * @return safe problem / 安全问题 */
    private static Problem problem(JsonNode node) {
        var fields = new HashSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!node.isObject() || !fields.equals(Set.of("code", "event")))
            throw new IllegalArgumentException();
        return new Problem(ProblemCode.valueOf(text(node, "code")), text(node, "event"),
            "Original secret provision failed", Map.of(), Optional.empty());
    }

    /** Reads exact persisted text. / 读取精确持久文本。
     * @param node source object / 来源对象
     * @param key exact field / 精确字段
     * @return text / 文本 */
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual())
            throw new IllegalArgumentException();
        return node.get(key).textValue();
    }

    /** Reads optional exact text. / 读取可选精确文本。
     * @param node source object / 来源对象
     * @param key exact field / 精确字段
     * @return text or absence / 文本或不存在 */
    private static Optional<String> nullableText(JsonNode node, String key) {
        return node.path(key).isNull() ? Optional.empty() : Optional.of(text(node, key));
    }

    /** Parses only canonical original UUIDs. / 仅解析规范原 UUID。
     * @param node source object / 来源对象
     * @param key exact field / 精确字段
     * @return original UUID / 原 UUID */
    private static UUID uuid(JsonNode node, String key) {
        String value = text(node, key);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value))
            throw new IllegalArgumentException();
        return id;
    }

    /** Reports a stable invalid transition. / 报告稳定的无效迁移。
     * @return safe API exception / 安全 API 异常 */
    private static ApiException invalid() {
        return new ApiException(new Problem(ProblemCode.CONTRACT_VIOLATION, "DEPLOYMENT_SECRET_CHECKPOINT",
            "Original secret checkpoint differs", Map.of(), Optional.empty()));
    }

    /** Provides the exact reference key for this durable original ID. / 为原持久 ID 提供精确引用键。
     * @return original reference intent / 原引用意图 */
    gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent intent() {
        return DeploymentRefKey.secretRevision(facts.server(),
            new SecretId(facts.credentialId().orElseThrow(), facts.purpose()), facts.revision());
    }
}
