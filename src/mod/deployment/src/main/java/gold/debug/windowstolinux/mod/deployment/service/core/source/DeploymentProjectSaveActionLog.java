package gold.debug.windowstolinux.mod.deployment.service.core.source;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectStore;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectSource;

/** Records one complete local project CAS before writing, without retaining a source grant. / 写入前记录一次完整本地项目 CAS，不保留来源授权。 */
public final class DeploymentProjectSaveActionLog {
    /** Stable original-task evidence address. / 稳定原任务依据地址。 */
    private static final String KEY = "deployment-local-project-save";

    /** Strict bounded JSON codec. / 严格有界 JSON 编码器。 */
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Exact durable original handle. / 精确持久原句柄。 */
    private final TaskCheckpoint checkpoint;

    /** Current exact CAS value. / 当前精确 CAS 值。 */
    private TaskCheckpoint.Snapshot snapshot;

    /** Original task identity. / 原任务身份。 */
    private final UUID taskId;

    /** Binds and validates one original checkpoint. / 绑定并校验一个原检查点。
     * @param taskId original task / 原任务
     * @param checkpoint original handle / 原句柄
     * @throws ApiException if evidence cannot be read / 无法读取依据时 */
    private DeploymentProjectSaveActionLog(UUID taskId, TaskCheckpoint checkpoint) throws ApiException {
        this.taskId = taskId;
        this.checkpoint = checkpoint;
        snapshot = checkpoint.snapshot();
        facts();
    }

    /** Writes one user-confirmed complete row, recording uncertainty before the CAS. / 写入一条用户确认完整行，在 CAS 前记录不确定边界。
     * @param context original capabilities / 原能力
     * @param task authentic original task / 真实原任务
     * @param expected complete reviewed old row / 完整已审阅旧行
     * @param next actual frozen source row / 实际冻结来源行
     * @return confirmed saved row / 已确认保存行
     * @throws ApiException if checkpoint or commit is uncertain / 检查点或提交未知时
     * @throws SQLException if the complete old row conflicts / 完整旧行冲突时 */
    public static DeploymentProjectRecord save(ModContext context, TaskContext task,
                                               Optional<DeploymentProjectRecord> expected, DeploymentProjectRecord next) throws ApiException, SQLException {
        validate(expected, next);
        String payload = encode(task.id(), expected, next, "PREPARED");
        var log = new DeploymentProjectSaveActionLog(task.id(), context.tasks().beginCheckpoint(task, KEY, payload));
        if (!log.snapshot.payload().equals(payload) || log.snapshot.released())
            throw new IllegalArgumentException("Original project save was already started");
        log.advance("WRITE_STARTED");
        var projects = new DeploymentProjectStore(context.db());
        try {
            if (expected.isPresent()) projects.update(task, expected.orElseThrow(), next);
            else projects.create(task, next);
        } catch (SQLException rollback) {
            log.advance("FAILED");
            log.checkpoint.close();
            throw rollback;
        }
        log.advance("SAVED");
        log.checkpoint.close();
        return next;
    }

    /** Observes only the original complete row; never retries source preparation or a write. / 仅观察原完整行，不重试来源准备或写入。
     * @param context original capabilities / 原能力
     * @param task original recovery task / 原恢复任务
     * @return absent evidence or the exact business outcome / 无依据或精确业务结果
     * @throws ApiException if original evidence cannot be read / 原依据无法读取时
     * @throws SQLException if the original schema is unavailable / 原结构不可用时 */
    public static Optional<TaskResult> reconcile(ModContext context, TaskContext task) throws ApiException, SQLException {
        var handle = context.tasks().findCheckpoint(task, KEY);
        if (handle.isEmpty()) return Optional.empty();
        var log = new DeploymentProjectSaveActionLog(task.id(), handle.orElseThrow());
        var facts = log.facts();
        String state = text(facts, "state");
        if (state.equals("WRITE_STARTED")) {
            var next = project(facts.get("next"));
            var expected = optionalProject(facts.get("expected"));
            var actual = new DeploymentProjectStore(context.db()).find(task, next.id());
            if (actual.equals(Optional.of(next))) state = "SAVED";
            else if (actual.equals(expected)) state = "UNCHANGED";
            else return Optional.of(TaskResult.UNKNOWN);
            log.advance(state);
        } else if (state.equals("PREPARED")) {
            state = "UNCHANGED";
            log.advance(state);
        }
        log.checkpoint.close();
        return Optional.of(state.equals("SAVED") ? TaskResult.SUCCEEDED : TaskResult.FAILED);
    }

    /** Validates immutable project identity and a later exact observation. / 校验不可变项目身份及更晚精确观测。
     * @param expected complete old row / 完整旧行
     * @param next complete observed row / 完整观测行 */
    private static void validate(Optional<DeploymentProjectRecord> expected, DeploymentProjectRecord next) {
        if (next.source().kind() != DeploymentProjectSource.KindType.LOCAL || expected.isPresent()
            && (!expected.orElseThrow().id().equals(next.id())
            || !expected.orElseThrow().createdAt().equals(next.createdAt())
            || !next.updatedAt().isAfter(expected.orElseThrow().updatedAt())))
            throw new IllegalArgumentException("Project save differs from original identity or observation");
    }

    /** Decodes strict original facts. / 解码严格原事实。
     * @return checked JSON / 已核对 JSON */
    private JsonNode facts() {
        try {
            var value = JSON.readTree(snapshot.payload());
            exact(value, Set.of("format", "taskId", "expected", "next", "state"));
            if (!value.get("format").isInt() || value.get("format").intValue() != 1
                || !UUID.fromString(text(value, "taskId")).equals(taskId)
                || !Set.of("PREPARED", "WRITE_STARTED", "SAVED", "UNCHANGED", "FAILED").contains(text(value, "state")))
                throw new IllegalArgumentException("Invalid original project evidence");
            validate(optionalProject(value.get("expected")), project(value.get("next")));
            if (snapshot.released() && Set.of("PREPARED", "WRITE_STARTED").contains(text(value, "state")))
                throw new IllegalArgumentException("Unsettled project evidence was released");
            return value;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid original project evidence", invalid);
        }
    }

    /** Advances the original value without replacing unconfirmed state. / 推进原值，不覆盖未确认状态。
     * @param state next confirmed boundary / 下个确认边界
     * @throws ApiException if original CAS fails / 原 CAS 失败时 */
    private void advance(String state) throws ApiException {
        var old = facts();
        snapshot = checkpoint.advance(snapshot,
            encode(taskId, optionalProject(old.get("expected")), project(old.get("next")), state));
    }

    /** Encodes all identities before any business write. / 任何业务写入前编码全部身份。
     * @param task original task / 原任务
     * @param expected original row / 原行
     * @param next proposed row / 拟写行
     * @param state exact boundary / 精确边界
     * @return bounded payload / 有界载荷 */
    private static String encode(UUID task, Optional<DeploymentProjectRecord> expected, DeploymentProjectRecord next, String state) {
        var value = JSON.createObjectNode().put("format", 1).put("taskId", task.toString()).put("state", state);
        value.set("expected", expected.map(DeploymentProjectSaveActionLog::project).orElseGet(() -> JSON.getNodeFactory().nullNode()));
        value.set("next", project(next));
        String payload = value.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > 12_000)
            throw new IllegalArgumentException("Complete project evidence exceeds checkpoint budget");
        return payload;
    }

    /** Encodes a complete project row including the old source kind. / 编码包含旧来源种类的完整项目行。
     * @param row complete row / 完整行
     * @return complete JSON / 完整 JSON */
    private static JsonNode project(DeploymentProjectRecord row) {
        ObjectNode value = JSON.createObjectNode().put("id", row.id().toString()).put("name", row.displayName())
            .put("kind", row.source().kind().name()).put("location", row.source().location().toString())
            .put("sha256", row.source().sha256()).put("createdAt", row.createdAt().toString())
            .put("updatedAt", row.updatedAt().toString());
        value.put("gitRef", row.source().gitRef().orElse(null));
        value.put("gitCommit", row.source().gitCommit().orElse(null));
        return value;
    }

    /** Decodes an optional complete original row. / 解码可选原完整行。
     * @param value exact nullable JSON / 精确可空 JSON
     * @return original row or absence / 原行或不存在 */
    private static Optional<DeploymentProjectRecord> optionalProject(JsonNode value) {
        return value.isNull() ? Optional.empty() : Optional.of(project(value));
    }

    /** Decodes all stored project columns. / 解码全部保存项目列。
     * @param value complete JSON / 完整 JSON
     * @return checked complete row / 已核对完整行 */
    private static DeploymentProjectRecord project(JsonNode value) {
        exact(value, Set.of("id", "name", "kind", "location", "sha256", "createdAt", "updatedAt", "gitRef", "gitCommit"));
        var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.valueOf(text(value, "kind")),
            URI.create(text(value, "location")), optionalText(value, "gitRef"), optionalText(value, "gitCommit"), text(value, "sha256"));
        return new DeploymentProjectRecord(UUID.fromString(text(value, "id")), text(value, "name"), source,
            Instant.parse(text(value, "createdAt")), Instant.parse(text(value, "updatedAt")));
    }

    /** Requires exact JSON fields without extensions. / 要求精确 JSON 字段，不接受扩展。
     * @param value original node / 原节点
     * @param fields exact names / 精确名称 */
    private static void exact(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size())
            throw new IllegalArgumentException("Invalid project evidence fields");
        value.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) throw new IllegalArgumentException("Unknown project evidence field");
        });
    }

    /** Requires a genuine text value. / 要求真实文本值。
     * @param value original object / 原对象
     * @param field exact field / 精确字段
     * @return original text / 原文本 */
    private static String text(JsonNode value, String field) {
        if (!value.path(field).isTextual()) throw new IllegalArgumentException("Project evidence requires text");
        return value.get(field).textValue();
    }

    /** Requires genuine nullable text. / 要求真实可空文本。
     * @param value original object / 原对象
     * @param field exact field / 精确字段
     * @return original text or absence / 原文本或不存在 */
    private static Optional<String> optionalText(JsonNode value, String field) {
        return value.get(field).isNull() ? Optional.empty() : Optional.of(text(value, field));
    }
}
