package gold.debug.windowstolinux.mod.deployment.ai;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.mod.service.ModServices;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.log.LogService;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentCommandReview;

/** Adds original-task command review while borrowing every actual owner capability unchanged. / 增补原任务命令审核，原样借用所有真实所有者能力。 */
public final class DeploymentCommandReviewScope implements ModContext {
    /** Actual original owner capabilities. / 实际原所有者能力。 */
    private final ModContext original;

    /** Complete command review before the original host grant. / 原宿主许可前的完整命令审核。 */
    private final DeploymentCommandReview interaction;

    /** Requires the original two model roles before any remote work. / 任何远端工作前要求两个原模型角色。
     * @param original actual original owner / 实际原所有者
     * @param host original real host review / 原真实宿主审核
     * @param task authentic original task / 真实原任务
     * @param policy original immutable policy / 原不可变策略
     * @param authorization original immutable question repetition / 原不可变问题重复规则
     * @param revision original or explicitly adopted route / 原路由或明确采用的路由
     * @throws ApiException if the original model route is unavailable / 原模型路由不可用时 */
    public DeploymentCommandReviewScope(ModContext original, InteractionService host, TaskContext task,
                                        String policy, String authorization, Supplier<String> revision) throws ApiException {
        this.original = Objects.requireNonNull(original);
        DeploymentAuthorizationPolicy.requireAllowed(policy, authorization);
        Objects.requireNonNull(host);
        Objects.requireNonNull(task);
        Objects.requireNonNull(revision);
        DeploymentFixedCommandApproval.requireRoute(original, task, revision.get());
        interaction = new DeploymentCommandReview() {
            /** Checks and reviews the complete actual request. / 检查并审核完整实际请求。
             * @param intent exact original intent / 精确原意图
             * @param request immutable actual request / 不可变实际请求
             * @param supplied authentic original task / 真实原任务
             * @return real single-use host grant or refusal / 真实单次宿主许可或拒绝
             * @throws ApiException if original review cannot complete / 原审核无法完成时 */
            public OperationResult<ApprovalGrant> approveCommand(ActionIntent intent, CommandRequest request,
                                                                 TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return DeploymentFixedCommandApproval.approve(original, host, intent, request, supplied, revision.get(), policy, authorization);
            }

            /** Retains original host authority for non-command interactions. / 非命令交互保留原宿主权限。
             * @param intent exact non-command intent / 精确非命令意图
             * @param supplied authentic original task / 真实原任务
             * @return original host decision / 原宿主决定
             * @throws ApiException if original authority rejects / 原权限拒绝时 */
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return host.approve(intent, supplied);
            }

            /** Applies the original host repetition rule to an actual required question. / 对实际必需问题应用原宿主重复规则。
             * @param intent exact original question / 精确原问题
             * @param supplied authentic original task / 真实原任务
             * @return real original grant or refusal / 真实原许可或拒绝
             * @throws ApiException if original authority rejects / 原权限拒绝时 */
            public OperationResult<ApprovalGrant> approveRequired(ActionIntent intent, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return host.approveRequired(intent, supplied);
            }

            /** Borrows original classified human input. / 借用原分类人工输入。
             * @param title original title / 原标题
             * @param prompt original question / 原问题
             * @param secret original privacy classification / 原隐私分类
             * @param supplied authentic original task / 真实原任务
             * @return original answer / 原回答
             * @throws ApiException if original input rejects / 原输入拒绝时 */
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return host.requestText(title, prompt, secret, supplied);
            }

            /** Rejects replacement task objects without changing capability authority. / 拒绝替代任务对象，不改变能力权限。
             * @param supplied original task object / 原任务对象 */
            private void sameTask(TaskContext supplied) {
                if (supplied != task)
                    throw new IllegalArgumentException("Command review differs from its original task");
            }
        };
    }

    /** Returns original owner identity. / 返回原所有者身份。
     * @return original identity / 原身份 */
    public ModIdentity identity() {
        return original.identity();
    }

    /** Returns original launch mode. / 返回原启动模式。
     * @return original mode / 原模式 */
    public ModLaunchMode launchMode() {
        return original.launchMode();
    }

    /** Returns original page authority. / 返回原页面权限。
     * @return original UI / 原UI */
    public UiService ui() {
        return original.ui();
    }

    /** Returns original task admission. / 返回原任务准入。
     * @return original tasks / 原任务 */
    public TaskService tasks() {
        return original.tasks();
    }

    /** Returns original-task command review. / 返回原任务命令审核。
     * @return borrowed real host review with command policy / 带命令策略的借用真实宿主审核 */
    public InteractionService interaction() {
        return interaction;
    }

    /** Returns original server authority. / 返回原服务器权限。
     * @return original servers / 原服务器 */
    public ServerService servers() {
        return original.servers();
    }

    /** Returns original SSH and transfer authority. / 返回原SSH与传输权限。
     * @return original Linux service / 原Linux服务 */
    public LinuxService linux() {
        return original.linux();
    }

    /** Returns original model authority. / 返回原模型权限。
     * @return original AI service / 原AI服务 */
    public AiService ai() {
        return original.ai();
    }

    /** Returns original Schema authority. / 返回原Schema权限。
     * @return original database / 原数据库 */
    public ModDatabase db() {
        return original.db();
    }

    /** Returns original opaque secret authority. / 返回原不透明秘密权限。
     * @return original secrets / 原秘密 */
    public SecretService secrets() {
        return original.secrets();
    }

    /** Returns original managed files. / 返回原受管文件。
     * @return original files / 原文件 */
    public FileService file() {
        return original.file();
    }

    /** Returns original owned logs. / 返回原所属日志。
     * @return original logs / 原日志 */
    public LogService logs() {
        return original.logs();
    }

    /** Returns original protocol processes. / 返回原协议进程。
     * @return original processes / 原进程 */
    public ProcessService processes() {
        return original.processes();
    }

    /** Returns original declared cross-Mod services. / 返回原声明跨Mod服务。
     * @return original services / 原服务 */
    public ModServices services() {
        return original.services();
    }
}
