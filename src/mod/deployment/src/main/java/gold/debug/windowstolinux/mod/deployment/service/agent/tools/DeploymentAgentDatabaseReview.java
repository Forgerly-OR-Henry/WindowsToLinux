package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseReview;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentNativeDatabaseFacts.*;

/** Agent decisions choose observed targets; business review and passwords stay in host capabilities. / Agent 决策选择已观察目标；业务审阅和密码仍由宿主能力管理。 */
final class DeploymentAgentDatabaseReview implements DeploymentNativeDatabaseReview {
    /** Original platform capabilities / 原平台能力。
     */
    private final ModContext context;

    /** Returns the current immutable snapshot identity / 返回当前不可变快照身份。
     */
    private final String source;

    /** Model selections from observed inventory / 模型从已观察库存中选择。
     */
    private final Map<String, String> selections;

    /** Binds original task capabilities / 绑定原任务能力。
     * @param context Original platform capabilities / 原平台能力
     * @param source current source identity / 当前来源身份
     * @param selections Model selections from observed inventory / 模型从已观察库存中选择
     */
    DeploymentAgentDatabaseReview(ModContext context, String source, Map<String, String> selections) {
        this.context = context;
        this.source = source;
        this.selections = Map.copyOf(selections);
    }

    /** Selects only an instance in the actual inventory / 只选择实际库存中的实例。
     * @param server Exact original server / 精确原服务器
     * @param componentId Component identity / 组件身份
     * @param database Source-bound database requirement / 来源绑定数据库需求
     * @param inventory Actual host inventory / 实际宿主库存
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     */
    @Override
    public OperationResult<String> choose(ServerId server, String componentId, DatabaseRequirement database,
                                          InstanceList inventory, TaskContext task) {
        String id = selections.get(database.id());
        if (id == null || !id.isEmpty() && inventory.instances().stream().noneMatch(v -> v.id().equals(id)))
            throw new IllegalArgumentException("Selected database instance is not in the current inventory");
        return success(id);
    }

    /** Requests original installation business consent / 请求原安装业务确认。
     * @param server Exact original server / 精确原服务器
     * @param database Source-bound database requirement / 来源绑定数据库需求
     * @param candidate Original protected candidate / 原受保护候选
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    @Override
    public OperationResult<Boolean> install(ServerId server, DatabaseRequirement database, Candidate candidate, TaskContext task) throws Exception {
        return confirm(context, server, "db.install", database.id(), source, database.engine() + " " + candidate, task);
    }

    /** Requires backup, downtime and exact replacement consent / 要求备份、停机及精确替换确认。
     * @param server Exact original server / 精确原服务器
     * @param previous Original database instance / 原数据库实例
     * @param candidate Original protected candidate / 原受保护候选
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    @Override
    public OperationResult<Boolean> replacement(ServerId server, Instance previous, Candidate candidate, TaskContext task) throws Exception {
        var backup = confirm(context, server, "db.backup", previous.id(), source, "确认已完成可恢复备份: " + previous, task);
        if (backup.outcome() != TaskResult.SUCCEEDED) return backup;
        var downtime = confirm(context, server, "db.downtime", previous.id(), source, "确认允许本次停机: " + previous, task);
        if (downtime.outcome() != TaskResult.SUCCEEDED) return downtime;
        return confirm(context, server, "db.replacement", previous.id(), source,
            "确认精确版本替换: " + previous + " -> " + candidate, task);
    }

    /** Confirms original manual restoration / 确认原人工恢复。
     * @param server Exact original server / 精确原服务器
     * @param instance Exact observed database instance / 精确已观察数据库实例
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    @Override
    public OperationResult<Boolean> restored(ServerId server, Instance instance, TaskContext task) throws Exception {
        return confirm(context, server, "db.restored", instance.id(), source, "Confirm completed manual restoration: " + instance, task);
    }

    /** Reads administrator bytes through secure host input / 经安全宿主输入读取管理员字节。
     * @param server Exact original server / 精确原服务器
     * @param instance Exact observed database instance / 精确已观察数据库实例
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    @Override
    public OperationResult<byte[]> administratorPassword(ServerId server, Instance instance, TaskContext task) throws Exception {
        return password("Database administrator credential", server.id() + " / " + instance.id(), task);
    }

    /** Reads application bytes through secure host input / 经安全宿主输入读取应用字节。
     * @param server Exact original server / 精确原服务器
     * @param database Source-bound database requirement / 来源绑定数据库需求
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    @Override
    public OperationResult<byte[]> applicationPassword(ServerId server, DatabaseRequirement database, TaskContext task) throws Exception {
        return password("Existing database credential", server.id() + " / " + database.id(), task);
    }

    /** Requests source-bound SQL business consent / 请求来源绑定SQL业务确认。
     * @param server Exact original server / 精确原服务器
     * @param componentId Component identity / 组件身份
     * @param database Source-bound database requirement / 来源绑定数据库需求
     * @param sourceDigest sourceDigest contract input / sourceDigest合同输入
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    @Override
    public OperationResult<Boolean> existingSchema(ServerId server, String componentId, DatabaseRequirement database,
                                                   String sourceDigest, TaskContext task) throws Exception {
        return confirm(context, server, "db.existing-schema", componentId + ":" + database.id(), sourceDigest,
            "Apply reviewed SQL to an existing schema: " + database.initializationFiles(), task);
    }

    /** Preserves the independent business confirmation in every command approval mode. / 在所有命令审批模式保留独立业务确认。
     * @param context Original platform capabilities / 原平台能力
     * @param server Exact original server / 精确原服务器
     * @param operation Original business operation / 原业务操作
     * @param target Exact resource target / 精确资源目标
     * @param source current source identity / 当前来源身份
     * @param summary Nonsecret public summary / 非秘密公开摘要
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    static OperationResult<Boolean> confirm(ModContext context, ServerId server, String operation, String target,
                                            String source, String summary, TaskContext task) throws Exception {
        var intent = new ActionIntent(operation, List.of(server.id()), target, source, operation + ":" + target + ":" + source, summary)
            .withSemantics(new ActionSemantics(operation, Set.of(new ActionTarget(server.id(), "deployment-database", target))));
        var result = context.interaction().approveRequired(intent, task);
        if (result.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
        result.value().orElseThrow();
        return success(true);
    }

    /** Obtains short-lived bytes without exporting them to the model / 获取短期字节而不导出给模型。
     * @param title title contract input / title合同输入
     * @param target Exact resource target / 精确资源目标
     * @param task Original admitted task / 原已准入任务
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private OperationResult<byte[]> password(String title, String target, TaskContext task) throws Exception {
        var result = context.interaction().requestText(title, target, true, task);
        if (result.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
        return success(result.value().orElseThrow().getBytes(StandardCharsets.UTF_8));
    }

    /** Preserves a known typed success / 保留确知类型化成功。
     * @param <T> typed payload / 类型化载荷
     * @param value Current typed input / 当前类型化输入
     * @return checked current result / 已校验当前结果
     */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
