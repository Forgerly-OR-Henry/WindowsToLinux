package gold.debug.windowstolinux.mod.deployment.service.core;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitMessages;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRef;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitSetup;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;

/** Bounded original-task Git facts, atomically advanced on a host-owned author checkpoint. / 在宿主所属作者检查点上原子推进的有界原任务 Git 依据。 */
final class DeploymentGitSourceActionLog implements DeploymentGitSetup.Observer {
    /** Fixed task-local checkpoint key. / 固定任务内检查点键。 */
    static final String KEY = "deployment-git-source";

    /** Strict independent parser for persisted author facts. / 严格的独立持久作者依据解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Original platform task and checkpoint identity. / 原平台任务和检查点身份。 */
    private final UUID task;

    /** Original host-controlled durable handle. / 原宿主控制的持久句柄。 */
    private final TaskCheckpoint checkpoint;

    /** Last exact CAS snapshot and decoded immutable facts. / 最近精确 CAS 快照及已解码不可变依据。 */
    private TaskCheckpoint.Snapshot snapshot;

    /** Decoded current author facts. / 已解码的当前作者依据。 */
    private Facts facts;

    /** Persisted state transitions; none permits Git replay during recovery. / 持久状态迁移；恢复均不允许重放 Git。 */
    enum PhaseState {
        /** No Git request was sent. / 尚未发送 Git 请求。 */
        PLANNED,
        /** The original request may have been sent. / 原请求可能已发送。 */
        REQUESTED,
        /** Original physical receipt was confirmed. / 原物理回执已确认。 */
        RECEIPT,
        /** Complete project row was proposed. / 完整项目行已拟定。 */
        PROPOSED,
        /** Project write began, with acknowledgement possibly lost. / 项目写入开始，确认可能丢失。 */
        WRITE_STARTED,
        /** Exact project state was confirmed. / 精确项目状态已确认。 */
        SAVED,
        /** Known failure was durably recorded. / 已知失败已持久记录。 */
        KNOWN_FAILURE
    }

    /** Complete bounded original facts. / 完整有界的原依据。
     * @param task original task / 原任务
     * @param plan original physical IDs / 原物理身份
     * @param project target project ID / 目标项目身份
     * @param displayName reviewed name / 已审阅名称
     * @param observedAt reviewed millisecond observation / 已审阅毫秒观测
     * @param expected reviewed complete previous row / 已审阅完整旧行
     * @param request complete reviewed Git request / 完整已审阅 Git 请求
     * @param maximumBytes reviewed source byte budget / 已审阅来源字节预算
     * @param maximumEntries reviewed entry budget / 已审阅条目预算
     * @param phase persisted phase / 持久阶段
     * @param receipt verified physical result / 已核实物理结果
     * @param next proposed complete row / 拟保存完整新行
     * @param failure known business failure / 已知业务失败 */
    record Facts(UUID task, DeploymentGitSetup.Planned plan, UUID project, String displayName, Instant observedAt,
                 Optional<DeploymentProjectRecord> expected, DeploymentGitRequest request, long maximumBytes,
                 int maximumEntries, PhaseState phase, Optional<DeploymentGitSetup.Receipt> receipt,
                 Optional<DeploymentProjectRecord> next, Optional<Problem> failure) {
        /** Requires immutable, internally consistent facts. / 要求不可变且内部一致的依据。
         * @param task original task / 原任务
         * @param plan original physical IDs / 原物理身份
         * @param project target project ID / 目标项目身份
         * @param displayName reviewed name / 已审阅名称
         * @param observedAt reviewed millisecond observation / 已审阅毫秒观测
         * @param expected reviewed complete previous row / 已审阅完整旧行
         * @param request complete reviewed Git request / 完整已审阅 Git 请求
         * @param maximumBytes reviewed source byte budget / 已审阅来源字节预算
         * @param maximumEntries reviewed entry budget / 已审阅条目预算
         * @param phase persisted phase / 持久阶段
         * @param receipt verified physical result / 已核实物理结果
         * @param next proposed complete row / 拟保存完整新行
         * @param failure known business failure / 已知业务失败 */
        Facts {
            Objects.requireNonNull(task);
            Objects.requireNonNull(plan);
            Objects.requireNonNull(project);
            Objects.requireNonNull(observedAt);
            Objects.requireNonNull(expected);
            Objects.requireNonNull(request);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(receipt);
            Objects.requireNonNull(next);
            Objects.requireNonNull(failure);
            if (displayName == null || displayName.isBlank() || displayName.length() > 256
                || observedAt.getNano() % 1_000_000 != 0 || maximumBytes < 0
                || maximumBytes > 2L * 1024 * 1024 * 1024 || maximumEntries < 1 || maximumEntries > 100000
                || expected.stream().anyMatch(old -> !old.id().equals(project))
                || expected.stream().anyMatch(old -> !observedAt.isAfter(old.updatedAt()))
                || next.stream().anyMatch(row -> !row.id().equals(project)))
                throw new IllegalArgumentException("Original Git project facts differ");
            if (next.isPresent()) {
                var row = next.orElseThrow();
                if (!row.displayName().equals(displayName) || !row.updatedAt().equals(observedAt)
                    || !row.createdAt().equals(expected.map(DeploymentProjectRecord::createdAt).orElse(observedAt))
                    || row.source().kind() != DeploymentProjectSource.KindType.GIT
                    || !row.source().location().equals(request.remote().location())
                    || !row.source().gitRef().equals(Optional.of(reference(request.reference())))
                    || receipt.isEmpty() || receipt.orElseThrow().outcome() != TaskResult.SUCCEEDED
                    || !row.source().gitCommit().equals(receipt.orElseThrow().commit()))
                    throw new IllegalArgumentException("Proposed project differs from original Git intent");
            }
        }
    }

    /** Binds an exact decoded original row. / 绑定一条精确解码的原记录。
     * @param task original task / 原任务
     * @param checkpoint original handle / 原句柄
     * @param snapshot exact persisted snapshot / 精确持久快照 */
    private DeploymentGitSourceActionLog(UUID task, TaskCheckpoint checkpoint, TaskCheckpoint.Snapshot snapshot) {
        this.task = task;
        this.checkpoint = checkpoint;
        this.snapshot = snapshot;
        this.facts = decode(snapshot.payload(), task);
    }

    /** Creates the complete physical plan before any workspace or process exists. / 在任何工作目录或进程存在前创建完整物理计划。
     * @param tasks original task service / 原任务服务
     * @param task original context / 原上下文
     * @param project target identity / 目标身份
     * @param name reviewed display name / 已审阅名称
     * @param expected complete reviewed old row / 完整已审阅旧行
     * @param observedAt reviewed observation / 已审阅观测
     * @param request complete reviewed Git request / 完整已审阅 Git 请求
     * @param maximumBytes reviewed source byte budget / 已审阅来源字节预算
     * @param maximumEntries reviewed entry budget / 已审阅条目预算
     * @return durable original journal / 持久原日志
     * @throws ApiException if first creation is unconfirmed / 首次创建未确认时 */
    static DeploymentGitSourceActionLog begin(TaskService tasks, TaskContext task, UUID project, String name,
                                              Optional<DeploymentProjectRecord> expected, Instant observedAt, DeploymentGitRequest request,
                                              long maximumBytes, int maximumEntries) throws ApiException {
        var plan = new DeploymentGitSetup.Planned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID());
        var initial = new Facts(task.id(), plan, project, name, observedAt, expected, request, maximumBytes,
            maximumEntries, PhaseState.PLANNED, Optional.empty(), Optional.empty(), Optional.empty());
        var handle = tasks.beginCheckpoint(task, KEY, encode(initial));
        return new DeploymentGitSourceActionLog(task.id(), handle, handle.snapshot());
    }

    /** Validates the complete bounded journal shape before task admission. / 在任务准入前校验完整有界日志形态。
     * @param project reviewed target / 已审阅目标
     * @param name reviewed display name / 已审阅名称
     * @param expected complete old row / 完整旧行
     * @param observedAt reviewed observation / 已审阅观测
     * @param request complete Git request / 完整 Git 请求
     * @param maximumBytes source budget / 来源预算
     * @param maximumEntries entry budget / 条目预算 */
    static void validateInput(UUID project, String name, Optional<DeploymentProjectRecord> expected, Instant observedAt,
                              DeploymentGitRequest request, long maximumBytes, int maximumEntries) {
        var plan = new DeploymentGitSetup.Planned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID());
        var probe = new Facts(UUID.randomUUID(), plan, project, name, observedAt, expected, request, maximumBytes,
            maximumEntries, PhaseState.PLANNED, Optional.empty(), Optional.empty(), Optional.empty());
        if (encode(probe).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12000)
            throw new IllegalArgumentException("Original Git request exceeds checkpoint budget");
    }

    /** Finds the original row, including a released row before task archival. / 查找原记录，包括任务归档前已释放的记录。
     * @param tasks original service / 原服务
     * @param task original recovery context / 原恢复上下文
     * @return checked journal or absence / 已核对日志或不存在
     * @throws ApiException if durable evidence cannot be read / 持久依据无法读取时 */
    static Optional<DeploymentGitSourceActionLog> find(TaskService tasks, TaskContext task) throws ApiException {
        var original = tasks.findCheckpoint(task, KEY);
        if (original.isEmpty())
            return Optional.empty();
        var handle = original.orElseThrow();
        return Optional.of(new DeploymentGitSourceActionLog(task.id(), handle, handle.snapshot()));
    }

    /** Returns checked current immutable facts. / 返回已核对的当前不可变依据。
     * @return current facts / 当前依据 */
    Facts facts() {
        return facts;
    }

    /** Returns original physical IDs. / 返回原物理身份。
     * @return original plan / 原计划 */
    @Override
    public DeploymentGitSetup.Planned planned() {
        return facts.plan();
    }

    /** Marks request delivery possible before sending the one request. / 单次发送前标记请求可能投递。
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    @Override
    public void beforeRequest() throws ApiException {
        if (facts.phase() != PhaseState.PLANNED)
            throw invalid();
        advance(copy(PhaseState.REQUESTED, facts.receipt(), facts.next(), facts.failure()));
    }

    /** Records only a complete verified physical result before control deletion. / 删除控制目录前仅记录完整已核实物理结果。
     * @param value original verified receipt / 原已核实回执
     * @throws ApiException if CAS or result shape cannot be confirmed / CAS 或结果形态无法确认时 */
    @Override
    public void receipt(DeploymentGitSetup.Receipt value) throws ApiException {
        if (facts.phase() != PhaseState.REQUESTED)
            throw invalid();
        advance(copy(PhaseState.RECEIPT, Optional.of(value), facts.next(), facts.failure()));
    }

    /** Persists the full proposed project row before its save boundary. / 在保存边界前持久化完整拟保存项目行。
     * @param row exact proposed next row / 精确拟保存新行
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void propose(DeploymentProjectRecord row) throws ApiException {
        if (facts.phase() != PhaseState.RECEIPT || facts.receipt().orElseThrow().outcome() != TaskResult.SUCCEEDED
            || !row.id().equals(facts.project()))
            throw invalid();
        advance(copy(PhaseState.PROPOSED, facts.receipt(), Optional.of(row), facts.failure()));
    }

    /** Records the only project write boundary. / 记录唯一项目写入边界。
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void writeStarted() throws ApiException {
        if (facts.phase() != PhaseState.PROPOSED)
            throw invalid();
        advance(copy(PhaseState.WRITE_STARTED, facts.receipt(), facts.next(), facts.failure()));
    }

    /** Confirms the original project save returned. / 确认原项目保存已返回。
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void saved() throws ApiException {
        if (facts.phase() != PhaseState.WRITE_STARTED)
            throw invalid();
        advance(copy(PhaseState.SAVED, facts.receipt(), facts.next(), facts.failure()));
    }

    /** Keeps a known failure ahead of later coincidentally equal project rows. / 将已知失败置于后来偶然相同的项目行之前。
     * @param problem bounded actual failure / 有界实际失败
     * @throws ApiException if CAS is unconfirmed / CAS 未确认时 */
    void knownFailure(Problem problem) throws ApiException {
        if (facts.phase() == PhaseState.SAVED || facts.phase() == PhaseState.KNOWN_FAILURE)
            throw invalid();
        var bounded = new Problem(problem.code(), problem.eventCode(), "Original Git project operation failed",
            Map.of(), Optional.empty());
        if (bounded.code() == ProblemCode.UNKNOWN || !bounded.eventCode().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
            throw invalid();
        advance(copy(PhaseState.KNOWN_FAILURE, facts.receipt(), facts.next(), Optional.of(bounded)));
    }

    /** Releases only after all original physical and business evidence is settled. / 仅在原物理与业务依据全部确定后释放。
     * @throws ApiException if release cannot be confirmed / 释放无法确认时 */
    void close() throws ApiException {
        checkpoint.close();
    }

    /** Rechecks the durable value after an uncertain CAS confirmation. / CAS 确认不确定后重读持久值。
     * @return current checked facts / 当前已核对依据
     * @throws ApiException if original evidence cannot be read / 原依据无法读取时 */
    Facts refresh() throws ApiException {
        snapshot = checkpoint.snapshot();
        facts = decode(snapshot.payload(), task);
        return facts;
    }

    /** Constructs a transition retaining immutable original intent. / 构造保留不可变原意图的迁移。
     * @param phase next phase / 下一阶段
     * @param receipt verified result / 已核实结果
     * @param next proposed row / 拟保存行
     * @param failure known failure / 已知失败
     * @return complete next facts / 完整下一依据 */
    private Facts copy(PhaseState phase, Optional<DeploymentGitSetup.Receipt> receipt,
                       Optional<DeploymentProjectRecord> next, Optional<Problem> failure) {
        return new Facts(task, facts.plan(), facts.project(), facts.displayName(), facts.observedAt(), facts.expected(),
            facts.request(), facts.maximumBytes(), facts.maximumEntries(), phase, receipt, next, failure);
    }

    /** Advances one exact revision and decodes the confirmed result. / 推进一个精确修订并解码已确认结果。
     * @param next complete next facts / 完整下一依据
     * @throws ApiException if original CAS is unconfirmed / 原 CAS 未确认时 */
    private void advance(Facts next) throws ApiException {
        snapshot = checkpoint.advance(snapshot, encode(next));
        facts = decode(snapshot.payload(), task);
    }

    /** Encodes only bounded allowlisted fields. / 仅编码有界白名单字段。
     * @param value complete facts / 完整依据
     * @return bounded JSON / 有界 JSON */
    private static String encode(Facts value) {
        var root = JSON.createObjectNode().put("formatVersion", 1).put("taskId", value.task().toString())
            .put("jobId", value.plan().job().toString()).put("checkoutId", value.plan().checkout().toString())
            .put("controlId", value.plan().control().toString()).put("processId", value.plan().process().toString())
            .put("projectId", value.project().toString()).put("displayName", value.displayName())
            .put("observedAt", value.observedAt().toEpochMilli()).put("phase", value.phase().name())
            .put("request", DeploymentGitMessages.encode(value.request())).put("maximumBytes", value.maximumBytes())
            .put("maximumEntries", value.maximumEntries());
        value.expected().ifPresentOrElse(row -> root.set("expected", project(row)), () -> root.putNull("expected"));
        value.next().ifPresentOrElse(row -> root.set("next", project(row)), () -> root.putNull("next"));
        value.receipt().ifPresentOrElse(receipt -> {
            var node = root.putObject("receipt").put("outcome", receipt.outcome().name());
            receipt.commit().ifPresentOrElse(commit -> node.put("commit", commit), () -> node.putNull("commit"));
            receipt.problemCode().ifPresentOrElse(code -> node.put("code", code.name()), () -> node.putNull("code"));
            receipt.eventCode().ifPresentOrElse(event -> node.put("event", event), () -> node.putNull("event"));
        }, () -> root.putNull("receipt"));
        value.failure().ifPresentOrElse(problem -> root.putObject("failure").put("code", problem.code().name())
            .put("event", problem.eventCode()), () -> root.putNull("failure"));
        return root.toString();
    }

    /** Decodes exact identities and all optional evidence without type coercion. / 不做类型强转地解码精确身份及全部可选依据。
     * @param value original JSON / 原 JSON
     * @param task expected task ID / 预期任务身份
     * @return checked facts / 已核对依据 */
    private static Facts decode(String value, UUID task) {
        try {
            var root = JSON.readTree(value);
            fields(root,
                Set.of("formatVersion", "taskId", "jobId", "checkoutId", "controlId", "processId", "projectId",
                    "displayName", "observedAt", "phase", "request", "maximumBytes", "maximumEntries",
                    "expected", "next", "receipt", "failure"));
            if (!root.get("formatVersion").isInt() || root.get("formatVersion").intValue() != 1
                || !root.get("observedAt").isIntegralNumber() || !root.get("observedAt").canConvertToLong()
                || !root.get("maximumBytes").isIntegralNumber() || !root.get("maximumBytes").canConvertToLong()
                || !root.get("maximumEntries").isInt())
                throw new IllegalArgumentException();
            var plan = new DeploymentGitSetup.Planned(uuid(root, "jobId"), uuid(root, "checkoutId"),
                uuid(root, "controlId"), uuid(root, "processId"));
            var expected = root.get("expected").isNull()
                ? Optional.<DeploymentProjectRecord>empty()
                : Optional.of(project(root.get("expected")));
            var next = root.get("next").isNull()
                ? Optional.<DeploymentProjectRecord>empty()
                : Optional.of(project(root.get("next")));
            var receipt = root.get("receipt").isNull()
                ? Optional.<DeploymentGitSetup.Receipt>empty()
                : Optional.of(receipt(root.get("receipt")));
            var failure = root.get("failure").isNull()
                ? Optional.<Problem>empty()
                : Optional.of(failure(root.get("failure")));
            var facts = new Facts(uuid(root, "taskId"), plan, uuid(root, "projectId"), text(root, "displayName"),
                Instant.ofEpochMilli(root.get("observedAt").longValue()), expected,
                DeploymentGitMessages.decode(text(root, "request")), root.get("maximumBytes").longValue(),
                root.get("maximumEntries").intValue(), PhaseState.valueOf(text(root, "phase")), receipt, next,
                failure);
            if (!facts.task().equals(task) || !encode(facts).equals(root.toString()) || !validPhase(facts))
                throw new IllegalArgumentException();
            return facts;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Original Git journal facts are invalid", invalid);
        }
    }

    /** Validates each phase's required and forbidden evidence. / 校验各阶段必需与禁止的依据。
     * @param facts decoded original facts / 已解码原依据
     * @return whether the combination is possible / 组合是否可能 */
    private static boolean validPhase(Facts facts) {
        boolean receipt = facts.receipt().isPresent(), next = facts.next().isPresent(),
            failure = facts.failure().isPresent();
        return switch (facts.phase()) {
            case PLANNED, REQUESTED -> !receipt && !next && !failure;
            case RECEIPT -> receipt && !next && !failure;
            case PROPOSED, WRITE_STARTED, SAVED ->
                receipt && next && !failure && facts.receipt().orElseThrow().outcome() == TaskResult.SUCCEEDED;
            case KNOWN_FAILURE -> failure && facts.failure().orElseThrow().code() != ProblemCode.UNKNOWN
                && facts.failure().orElseThrow().eventCode().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                && (!next || receipt && facts.receipt().orElseThrow().outcome() == TaskResult.SUCCEEDED);
        };
    }

    /** Encodes one complete project row. / 编码一条完整项目行。
     * @param row exact row / 精确行
     * @return JSON object / JSON 对象 */
    private static ObjectNode project(DeploymentProjectRecord row) {
        var node = JSON.createObjectNode().put("id", row.id().toString()).put("name", row.displayName())
            .put("kind", row.source().kind().name()).put("location", row.source().location().toString())
            .put("sha256", row.source().sha256()).put("createdAt", row.createdAt().toEpochMilli())
            .put("updatedAt", row.updatedAt().toEpochMilli());
        row.source().gitRef().ifPresentOrElse(ref -> node.put("gitRef", ref), () -> node.putNull("gitRef"));
        row.source().gitCommit().ifPresentOrElse(commit -> node.put("gitCommit", commit),
            () -> node.putNull("gitCommit"));
        return node;
    }

    /** Decodes one complete project row. / 解码一条完整项目行。
     * @param node source object / 来源对象
     * @return checked row / 已核对行 */
    private static DeploymentProjectRecord project(JsonNode node) {
        fields(node,
            Set.of("id", "name", "kind", "location", "sha256", "createdAt", "updatedAt", "gitRef", "gitCommit"));
        if (!node.get("createdAt").isIntegralNumber() || !node.get("updatedAt").isIntegralNumber())
            throw new IllegalArgumentException();
        var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.valueOf(text(node, "kind")),
            URI.create(text(node, "location")), optionalText(node, "gitRef"), optionalText(node, "gitCommit"),
            text(node, "sha256"));
        return new DeploymentProjectRecord(uuid(node, "id"), text(node, "name"), source,
            Instant.ofEpochMilli(node.get("createdAt").longValue()),
            Instant.ofEpochMilli(node.get("updatedAt").longValue()));
    }

    /** Encodes the selected reference without mixing branch, tag and commit identities. / 编码所选引用，不混淆分支、标签与提交身份。
     * @param ref reviewed reference / 已审阅引用
     * @return exact persisted reference / 精确持久引用 */
    private static String reference(DeploymentGitRef ref) {
        return switch (ref) {
            case DeploymentGitRef.DefaultBranch ignored -> "DEFAULT:HEAD";
            case DeploymentGitRef.Branch branch -> "BRANCH:" + branch.value();
            case DeploymentGitRef.Tag tag -> "TAG:" + tag.value();
            case DeploymentGitRef.Commit commit -> "COMMIT:" + commit.value();
        };
    }

    /** Decodes a complete safe physical result. / 解码完整安全物理结果。
     * @param node original receipt / 原回执
     * @return checked receipt / 已核对回执 */
    private static DeploymentGitSetup.Receipt receipt(JsonNode node) {
        fields(node, Set.of("outcome", "commit", "code", "event"));
        return new DeploymentGitSetup.Receipt(TaskResult.valueOf(text(node, "outcome")), optionalText(node, "commit"),
            optionalText(node, "code").map(ProblemCode::valueOf), optionalText(node, "event"));
    }

    /** Decodes only a bounded known failure classification. / 仅解码有界已知失败分类。
     * @param node original failure / 原失败
     * @return safe problem / 安全问题 */
    private static Problem failure(JsonNode node) {
        fields(node, Set.of("code", "event"));
        return new Problem(ProblemCode.valueOf(text(node, "code")), text(node, "event"),
            "Original Git project operation failed", Map.of(), Optional.empty());
    }

    /** Requires an exact object field set. / 要求精确对象字段集合。
     * @param node JSON object / JSON 对象
     * @param required complete keys / 完整键 */
    private static void fields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject() || node.size() != required.size())
            throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(key -> {
            if (!required.contains(key))
                throw new IllegalArgumentException();
        });
    }

    /** Reads one exact textual field. / 读取一个精确文本字段。
     * @param node object / 对象
     * @param key key / 键
     * @return text / 文本 */
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual())
            throw new IllegalArgumentException();
        return node.get(key).textValue();
    }

    /** Reads an exact nullable textual field. / 读取精确可空文本字段。
     * @param node object / 对象
     * @param key key / 键
     * @return optional text / 可选文本 */
    private static Optional<String> optionalText(JsonNode node, String key) {
        return node.path(key).isNull() ? Optional.empty() : Optional.of(text(node, key));
    }

    /** Reads an exact canonical UUID. / 读取精确规范 UUID。
     * @param node object / 对象
     * @param key key / 键
     * @return UUID / 身份 */
    private static UUID uuid(JsonNode node, String key) {
        var raw = text(node, key);
        var value = UUID.fromString(raw);
        if (!value.toString().equals(raw))
            throw new IllegalArgumentException();
        return value;
    }

    /** Returns a stable contract error for impossible local state changes. / 为不可能的本地状态迁移返回稳定契约错误。
     * @return error / 错误 */
    private static ApiException invalid() {
        return new ApiException(new Problem(ProblemCode.CONTRACT_VIOLATION, "DEPLOYMENT_GIT_JOURNAL_PHASE",
            "Original Git journal phase differs", Map.of(), Optional.empty()));
    }
}
