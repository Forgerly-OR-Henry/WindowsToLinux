package gold.debug.windowstolinux.mod.deployment.file.git;

import java.nio.file.Path;

import gold.debug.windowstolinux.appapi.file.material.MaterialRef;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.git.api.GitReferenceKind;
import gold.debug.windowstolinux.mod.git.api.GitSourceRequest;
import gold.debug.windowstolinux.mod.git.api.GitSourceService;

/** Optional-type adapter constructed only inside a Git source operation. / 仅在 Git 来源操作内构造的可选类型适配器。 */
public final class DeploymentGitAccess {
    /** Exact declared source provider. / 精确声明来源提供方。 */
    public static final String PROVIDER = "gold.debug.windowstolinux.mod.git";

    /** Original consumer activation. / 原消费方启用。 */
    private final ModContext context;

    /** Binds consumer context without providing a fallback implementation. / 绑定消费方上下文，不提供替代实现。
     * @param context original consumer context / 原消费方上下文 */
    public DeploymentGitAccess(ModContext context) {
        this.context = java.util.Objects.requireNonNull(context);
    }

    /** Calls the real declared service in the original task. / 在原任务内调用真实声明服务。
     * @param request reviewed deployment source choice / 已审阅部署来源选择
     * @param executable explicit native Git / 明确原生 Git
     * @param plan pre-recorded operation and material / 预记录操作及素材
     * @param task authentic original task / 真实原任务
     * @return small platform-owned reference / 小型平台持有引用
     * @throws ApiException if original service authority or result fails / 原服务授权或结果失败时 */
    public MaterialRef prepare(DeploymentGitRequest request, Path executable, DeploymentGitSetup.Planned plan,
                               TaskContext task) throws ApiException {
        var kind = switch (request.reference()) {
            case DeploymentGitRef.DefaultBranch ignored -> GitReferenceKind.DEFAULT_BRANCH;
            case DeploymentGitRef.Branch ignored -> GitReferenceKind.BRANCH;
            case DeploymentGitRef.Tag ignored -> GitReferenceKind.TAG;
            case DeploymentGitRef.Commit ignored -> GitReferenceKind.COMMIT;
        };
        var input = new GitSourceRequest(plan.job(), plan.material(), request.remote().location().toString(), kind,
            request.reference().value(), request.allowedHosts(), request.permitLocalFileRemote(), executable.toString());
        try (var service = context.services().require(PROVIDER, GitSourceService.class)) {
            var result = service.call(task, provider -> provider.prepare(input, task));
            return result.material();
        }
    }

    /** Observes original provider facts without sending a new request. / 不发送新请求，仅观察原提供方依据。
     * @param plan original recorded identities / 原记录身份
     * @param task authentic original execution or recovery task / 真实原执行或恢复任务
     * @return original outcome and small reference / 原结果及小型引用
     * @throws ApiException if exact original provider authority fails / 精确原提供方授权失败时 */
    public OperationResult<MaterialRef> reconcile(DeploymentGitSetup.Planned plan, TaskContext task) throws ApiException {
        try (var service = context.services().require(PROVIDER, GitSourceService.class)) {
            var result = service.call(task, provider -> provider.reconcile(plan.job(), plan.material(), task));
            return new OperationResult<>(result.outcome(), result.value().map(value -> value.material()), result.problem());
        }
    }
}
