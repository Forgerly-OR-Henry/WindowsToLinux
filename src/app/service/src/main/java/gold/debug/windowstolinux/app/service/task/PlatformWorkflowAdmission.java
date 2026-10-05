package gold.debug.windowstolinux.app.service.task;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Reads the complete original root server admission for host delegation. / 读取宿主委托所需的完整原根服务器准入。 */
final class PlatformWorkflowAdmission {
    /** Prevents construction. / 禁止构造。 */
    private PlatformWorkflowAdmission() {
    }

    /** Shares exact original server evidence while keeping ordinary and recovery admission distinct. / 共享精确原服务器依据并区分普通与恢复准入。
     * @param storage original storage / 原存储
     * @param tasks actual runtime / 实际运行时
     * @param task original context / 原上下文
     * @param owner bound root / 绑定根
     * @param version exact root release / 精确根发布
     * @param recovering whether explicit root reconciliation is required / 是否要求明确根核对
     * @return complete original server set / 完整原服务器集合
     * @throws ApiException if callback kind or original facts differ / 回调种类或原依据不符时 */
    static Set<String> servers(PlatformStorage storage, PlatformTaskRuntime tasks, TaskContext task, String owner, String version, boolean recovering) throws ApiException {
        var original = recovering ? tasks.requireCleanup(task, owner, version) : tasks.callbacks().requireOrdinaryExecuting(task, owner, version);
        if (recovering && !tasks.reconciling(task))
            throw failure("WORKFLOW_RECOVERY_REQUIRED", "Fresh recovery scope requires actual root reconciliation");
        if (!owner.equals(original.ownerId()) || !version.equals(original.ownerVersion()))
            throw failure("WORKFLOW_ROOT_REQUIRED", "Only the original root can delegate its workflow");
        var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (var connection = storage.database().openConnection();
             var statement = connection.prepareStatement("SELECT request_json,recovery_json FROM platform_task WHERE task_id=? AND owner_id=? AND owner_version=?")) {
            statement.setObject(1, task.id());
            statement.setString(2, owner);
            statement.setString(3, version);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLException("Original workflow request is absent");
                var document = mapper.readTree(rows.getString(1));
                if (document == null || !document.isObject())
                    throw new java.sql.SQLException("Original request is invalid");
                var values = document.required("serverIds");
                if (!values.isArray() || values.isEmpty())
                    throw new java.sql.SQLException("Original server set is invalid");
                var servers = new HashSet<String>();
                for (var value : values) {
                    if (!value.isTextual() || value.asText().isBlank() || !servers.add(value.asText()))
                        throw new java.sql.SQLException("Original server identity is invalid");
                    tasks.requireServer(task, owner, version, value.asText());
                }
                var claims = mapper.readTree(rows.getString(2)).required("initialClaims");
                if (!claims.isArray()) throw new java.sql.SQLException("Original claims are invalid");
                var claimed = new HashSet<String>();
                for (var claim : claims) {
                    if (!"SERVER".equals(claim.path("kind").asText())) continue;
                    if (!claim.path("id").isTextual() || !owner.equals(claim.path("ownerId").asText())
                        || !claim.path("exclusive").isBoolean() || !claim.path("exclusive").booleanValue()
                        || !claimed.add(claim.path("id").asText()))
                        throw new java.sql.SQLException("Original server claim is invalid");
                }
                if (!servers.equals(claimed)) throw new java.sql.SQLException("Original request and claims differ");
                return Set.copyOf(servers);
            }
        } catch (java.sql.SQLException | java.io.IOException | IllegalArgumentException failure) {
            throw failure("WORKFLOW_ORIGINAL_SERVERS", "Original workflow servers cannot be verified");
        }
    }

    /** Builds a bounded denial without original request content. / 创建不含原请求内容的有界拒绝。
     * @param event stable event / 稳定事件
     * @param message safe explanation / 安全说明
     * @return public denial / 公开拒绝 */
    private static ApiException failure(String event, String message) {
        return new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(ProblemCode.ACCESS_DENIED,
            event, message, java.util.Map.of(), java.util.Optional.empty()));
    }
}
