package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.net.URI;
import java.sql.SQLException;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;

import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationRef;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentAccessUrl;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentWorkload;

/** Edits local presentation only, preserving reviewed runtime and release identity. / 仅编辑本地展示信息，保留已审阅运行与发布身份。 */
public final class DeploymentApplicationPresentation {
    /** Provider capabilities used within the original local edit. / 在原本地编辑内使用的提供方能力。 */
    private final ModContext context;

    /** Complete formal application evidence for revision validation. / 用于修订校验的完整正式应用依据。 */
    private final DeploymentApplicationReader reader;

    /** Binds the original deployment generation. / 绑定原部署代次。
     * @param context active deployment context / 活动部署上下文 */
    public DeploymentApplicationPresentation(ModContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        this.reader = new DeploymentApplicationReader(context.db());
    }

    /** Current local editor values. / 当前本地编辑器值。
     * @param category original runtime category / 原运行类别
     * @param accessUrl saved optional website URL / 保存的可选网站链接 */
    public record Values(String category, Optional<String> accessUrl) {
    }

    /** Reads original runtime category and saved website override. / 读取原运行类别和已存网站覆盖。
     * @param application selected application / 所选应用
     * @param revision selected release / 所选发布
     * @param task original query / 原查询
     * @return original editor values / 原编辑器值
     * @throws ApiException if the selected identity differs / 所选身份不符时 */
    public Values values(DeploymentApplicationRef application, String revision, TaskContext task) throws ApiException {
        var data = reader.find(application, task);
        if (!data.snapshot().revisionSha256().equals(revision))
            throw unknown();
        var health = data.graph().components().stream()
            .filter(value -> value.componentId().equals(data.graph().healthComponentId())).findFirst()
            .orElseThrow();
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT access_url FROM deployment_unit WHERE deployment_unit_id=? AND server_profile_id=?")) {
            query.setString(1, health.unit().id());
            query.setString(2, application.server().id());
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    throw unknown();
                return new Values(health.runtime().workload().category().name(),
                    Optional.ofNullable(rows.getString(1)));
            }
        } catch (SQLException invalid) {
            throw unknown();
        }
    }

    /** Preserves original category and stored access override when changing a name. / 更改名称时保留原类别和已存访问覆盖。
     * @param application selected application / 所选应用
     * @param revision visible revision / 可见修订
     * @param name selected name / 所选名称
     * @param task original task / 原任务
     * @return saved name / 已存名称
     * @throws ApiException if evidence is unavailable / 依据不可用时 */
    public OperationResult<String> editName(DeploymentApplicationRef application, String revision, String name,
                                            TaskContext task) throws ApiException {
        var data = reader.find(application, task);
        var owner = data.graph().components().stream()
            .filter(value -> value.componentId().equals(data.graph().healthComponentId())).findFirst()
            .orElseThrow();
        Optional<String> url;
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT access_url FROM deployment_unit WHERE deployment_unit_id=? AND server_profile_id=?")) {
            query.setString(1, owner.unit().id());
            query.setString(2, application.server().id());
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return failure("DEPLOYMENT_PRESENTATION_IDENTITY_CHANGED");
                url = Optional.ofNullable(rows.getString(1));
            }
        } catch (SQLException invalid) {
            throw unknown();
        }
        return edit(application, revision, name, owner.runtime().workload().category().name(), url, task);
    }

    /** Replaces one verified display override without modifying a remote service. / 不修改远端服务地替换一份已验证展示覆盖。
     * @param application exact application / 精确应用
     * @param expectedRevisionSha256 release observed by the editor / 编辑器观察的发布
     * @param name bounded display name / 有界展示名称
     * @param category reviewed WEBSITE or APP category / 已审阅网站或应用类别
     * @param accessUrl optional user URL / 可选用户URL
     * @param task original accepted task / 原已接纳任务
     * @return saved display name / 已保存展示名称
     * @throws ApiException if identity or transaction cannot be confirmed / 身份或事务无法确认时 */
    public OperationResult<String> edit(DeploymentApplicationRef application, String expectedRevisionSha256,
                                        String name, String category, Optional<String> accessUrl, TaskContext task) throws ApiException {
        var data = reader.find(application, task);
        if (!data.snapshot().revisionSha256().equals(expectedRevisionSha256))
            return failure("DEPLOYMENT_PRESENTATION_REVISION_CHANGED");
        if (name == null || name.isBlank() || name.length() > 160 || name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0
            || name.indexOf('\r') >= 0)
            return failure("DEPLOYMENT_PRESENTATION_NAME_INVALID");
        var health = data.graph().components().stream()
            .filter(component -> component.componentId().equals(data.graph().healthComponentId())).findFirst()
            .orElseThrow();
        String actualCategory = health.runtime().workload().category().name();
        if (!actualCategory.equals(category))
            return failure("DEPLOYMENT_PRESENTATION_CATEGORY_CHANGED");
        String url = accessUrl.orElse("");
        if (!url.isEmpty()) {
            if (category.equals(DeploymentWorkload.CategoryType.APP.name()))
                return failure("DEPLOYMENT_PRESENTATION_URL_NOT_APPLICABLE");
            try {
                new DeploymentAccessUrl(URI.create(url));
            } catch (IllegalArgumentException invalid) {
                return failure("DEPLOYMENT_PRESENTATION_URL_INVALID");
            }
        }
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                try (var query = connection.prepareStatement(
                    "SELECT release_sha256 FROM deployment_release WHERE application_id=? AND state='ACTIVE' FOR UPDATE")) {
                    query.setString(1, application.id());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !expectedRevisionSha256.equals(rows.getString(1)) || rows.next()) {
                            connection.rollback();
                            return failure("DEPLOYMENT_PRESENTATION_REVISION_CHANGED");
                        }
                    }
                }
                try (var update = connection.prepareStatement(
                    "UPDATE deployment_unit SET has_display_override=1,display_name=?,application_category=?,access_url=? WHERE deployment_unit_id=? AND server_profile_id=?")) {
                    update.setString(1, name.trim());
                    update.setString(2, category);
                    update.setString(3, url.isEmpty() ? null : url);
                    update.setString(4, health.unit().id());
                    update.setString(5, application.server().id());
                    if (update.executeUpdate() != 1)
                        throw new SQLException("Application identity changed");
                }
                connection.commit();
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    throw unknown();
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw unknown();
        }
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(name.trim()), Optional.empty());
    }

    /** Returns an exact known local validation rejection. / 返回精确的已知本地校验拒绝。
     * @param event stable known refusal / 稳定已知拒绝
     * @return known local failure / 已知本地失败 */
    private static OperationResult<String> failure(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE,
            event, "Presentation differs from the reviewed application", java.util.Map.of(), Optional.empty())));
    }

    /** Retains commit uncertainty without claiming the edit failed. / 保留提交未知而不声称编辑失败。
     * @return bounded original commit uncertainty / 有界原提交未知 */
    private static ApiException unknown() {
        return new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_PRESENTATION_COMMIT_UNKNOWN",
            "Display transaction requires reconciliation", java.util.Map.of(), Optional.empty()));
    }
}
