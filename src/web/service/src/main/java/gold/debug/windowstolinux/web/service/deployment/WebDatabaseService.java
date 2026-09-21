package gold.debug.windowstolinux.web.service.deployment;

import gold.debug.windowstolinux.web.service.interaction.WebTaskInteractionService;

import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

import gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.execution.environment.NativeDatabasePreparationService;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Adapts browser decisions and scoped credentials to the shared native database policy.
 * <p>将浏览器决策及限定作用域凭据适配到共享原生数据库策略。
 */
public final class WebDatabaseService {
    /**
     * Credential references or scoped secret-access service.
     * <p>凭据引用或限定作用域的秘密访问服务。
     */
    private final WebApplicationSecrets secrets;
    /**
     * Bound web server service collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的Web服务器服务协作对象。
     */
    private final WebServerService servers;
    /**
     * Bound gold debug windowstolinux web service ai web ai service collaborator for the supplied web ai service.
     * <p>处理所提供的WebAI服务的golddebugwindowstolinuxWeb服务AIWebAI服务协作对象。
     */
    private final gold.debug.windowstolinux.web.service.ai.WebAiService ai;
    /**
     * Binds the supplied dependencies and state for web database service.
     * <p>为Web数据库服务绑定传入的依赖及状态。
     *
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param ai the supplied web ai service / 所提供的WebAI服务
     */
    public WebDatabaseService(WebApplicationSecrets secrets, WebServerService servers,gold.debug.windowstolinux.web.service.ai.WebAiService ai) { this.secrets=secrets;this.servers=servers;this.ai=ai; }
    /**
     * Builds assessment from the supplied inputs inputs.
     * <p>根据所提供输入集合输入构建评估。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return assessment from the supplied inputs inputs / 根据所提供输入集合输入构建评估
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DatabaseProjectInspector.Assessment inputs(WebRequestContext context, Path root,String applicationId,Map<String,String> values,TaskInteraction interaction) throws Exception {
        var assessment = new DatabaseProjectInspector().inspect(root);
        if (values.get("type").equals("DOCKERFILE_CONTAINER")) assessment = DatabaseProjectInspector.containerStorage(assessment);
        if (assessment.databases().isEmpty() && !assessment.unknownDatabase()) return assessment;
        if (values.containsKey("databaseMode")) {
            String mode=values.get("databaseMode");
            if (Set.of("NONE","UNREVIEWED").contains(mode) || assessment.databases().size()>1
                    || assessment.databases().stream().anyMatch(db -> !db.engine().name().equals(mode)) || assessment.schemaReviewRequired())
                throw new IllegalArgumentException("Manual DB bindings conflict with declared database/schema requirements");
            return assessment;
        }
        if (values.get("type").equals("DOCKERFILE_CONTAINER") && assessment.databases().stream().anyMatch(database -> database.engine()!=gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType.SQLITE)) throw new IllegalArgumentException("Container databases require an explicitly reachable binding");
        if (assessment.endpointConfirmationRequired()) WebTaskInteractionService.approve(interaction,"db.nativeEndpoint",WebJsonCodec.object());
        return new NativeDatabasePreparationService(secrets.databaseCredentials(context)).completeInputs(root,applicationId,assessment,interaction(context,interaction));
    }
    /**
     * Prepares automatic database preparation.
     * <p>准备自动数据库准备。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param assessment the typed static assessment / 类型化静态评估
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved automatic database preparation / 构造或解析得到的自动数据库准备
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public AutomaticDatabasePreparation prepare(WebRequestContext context,String serverId,Path root,String applicationId,
            DatabaseProjectInspector.Assessment assessment,Map<String,String> values,TaskInteraction interaction) throws Exception {
        if (values.containsKey("databaseMode") || assessment.databases().isEmpty()) return AutomaticDatabasePreparation.empty();
        return servers.withSession(context,serverId,interaction,session -> new NativeDatabasePreparationService(secrets.databaseCredentials(context))
                .prepare(root,applicationId,serverId,session.nativeDatabases(),assessment,interaction(context,interaction),
                        message -> WebTaskInteractionService.progress(interaction,message.key(),WebJsonCodec.object())));
    }
    /**
     * Adapts scoped Web decisions, transient secret requests and cancellation to the shared database-preparation interaction.
     * <p>将限定作用域 Web 决策、临时秘密请求及取消适配到共享数据库准备交互。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param task task / 任务
     * @return constructed or resolved automatic deployment interaction / 构造或解析得到的自动部署交互
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private AutomaticDeploymentInteraction interaction(WebRequestContext context,TaskInteraction task) {
        return new AutomaticDeploymentInteraction() {
            /**
             * Completes database input fields through Web task interaction, preserving interruption as cancellation.
             * <p>通过 Web 任务交互补全数据库输入字段，并将中断保留为取消。
             *
             * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
             * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
             * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
             */
            @Override public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> fields) {
                try { return Optional.of(ai.inputs(context,fields,task)); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                catch (Exception failure) { throw new IllegalStateException("Cannot collect database inputs",failure); }
            }
            /**
             * Confirms anonymous.
             * <p>确认匿名。
             *
             * @param key lookup key within the current contract / 当前契约内的查找键
             * @param details details / 详情
             * @return true when confirms anonymous, false otherwise / 确认匿名时为 true，否则为 false
             */
            @Override public boolean confirm(String key,Map<String,?> details) { return WebTaskInteractionService.confirm(task,key,WebJsonCodec.tree(details)); }
            /**
             * Confirms database replacement.
             * <p>确认数据库替换。
             *
             * @param details details / 详情
             * @return true when confirms database replacement, false otherwise / 确认数据库替换时为 true，否则为 false
             * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
             */
            @Override public boolean confirmDatabaseReplacement(Map<String,?> details) {
                try {
                    var answer=task.decide("DATABASE_REPLACEMENT",WebJsonCodec.tree(details));
                    WebRequestValidator.fields(answer,"backupConfirmed","downtimeConfirmed","replacementConfirmed");
                    return answer.path("backupConfirmed").asBoolean(false) && answer.path("downtimeConfirmed").asBoolean(false) && answer.path("replacementConfirmed").asBoolean(false);
                } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                catch (Exception failure) { throw new IllegalStateException("Cannot confirm database replacement",failure); }
            }
            /**
             * Resolves a database credential through a secret-reference decision, preserving interruption as cancellation.
             * <p>通过秘密引用决策解析数据库凭据，并将中断保留为取消。
             *
             * @param key lookup key within the current contract / 当前契约内的查找键
             * @return a database credential through a secret-reference decision, preserving interruption as cancellation / 通过秘密引用决策解析数据库凭据，并将中断保留为取消
             * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
             */
            @Override public char[] requestSecret(String key) {
                try { return secrets.decisionSecret(context,task,key); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                catch (Exception failure) { throw new IllegalStateException("Cannot resolve database credential",failure); }
            }
        };
    }
}
