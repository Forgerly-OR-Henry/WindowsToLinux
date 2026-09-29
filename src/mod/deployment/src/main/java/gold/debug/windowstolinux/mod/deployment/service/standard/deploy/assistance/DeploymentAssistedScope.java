package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.source.SourceService;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.UiService;

/** Freezes one original assisted task without replacing platform authority. / 冻结一项原辅助任务而不替换平台权限。
 * @param original actual owner capabilities / 实际所有者能力
 * @param task original platform context / 原平台上下文
 * @param revision frozen whole AI routing / 冻结完整 AI 路由 */
public record DeploymentAssistedScope(ModContext original, TaskContext task, String revision) implements ModContext {
    /** Captures the usable language route once before any assisted work. / 辅助工作前一次捕获可用语言路由。
     * @param original actual capabilities / 实际能力
     * @param task original task / 原任务
     * @return task-bound frozen capabilities / 任务绑定冻结能力
     * @throws ApiException if configuration is unavailable / 配置不可用时 */
    public static DeploymentAssistedScope freeze(ModContext original, TaskContext task) throws ApiException {
        return new DeploymentAssistedScope(original, task,
                original.ai().configurationRevision(AiPurpose.LANGUAGE, task));
    }

    /** Verifies the original binding at actual dispatch time. / 在真实发送时验证原绑定。
     * @throws ApiException when the binding changed / 绑定变化时 */
    private void verify() throws ApiException {
        if (!revision.equals(original.ai().configurationRevision(AiPurpose.LANGUAGE, task)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                    "Assisted configuration changed", Map.of(), Optional.empty()));
    }

    /** Preserves the exact task at every scoped capability call. / 每次限定能力调用均保留精确任务。
     * @param supplied supplied task / 提供任务 */
    private void sameTask(TaskContext supplied) {
        if (supplied != task)
            throw new IllegalArgumentException("Assisted capability belongs to another task");
    }

    /** Returns real SSH with a guard before each actual send. / 返回每次真实发送前带校验的真实 SSH。
     * @return guarded original Linux access / 带校验原 Linux 访问 */
    @Override
    public LinuxService linux() {
        return new LinuxService() {
            /** Connects only under the frozen original task. / 仅按冻结原任务连接。
             * @param server original target / 原目标
             * @param supplied original task / 原任务
             * @return actual guarded connection / 实际带校验连接
             * @throws ApiException on drift or failed connection / 漂移或连接失败时 */
            public RemoteSession connect(ServerRef server, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                return new DeploymentAssistedSession(original.linux().connect(server, supplied),
                        DeploymentAssistedScope.this::verify);
            }

            /** Reads only original pending transfers. / 仅读取原待结传输。
             * @param supplied original task / 原任务
             * @return actual pending transfers / 实际待结传输
             * @throws ApiException on unavailable evidence / 依据不可用时 */
            public List<TransferCheckpoint> pendingTransfers(TaskContext supplied) throws ApiException {
                sameTask(supplied);
                return original.linux().pendingTransfers(supplied);
            }
        };
    }

    /** Returns AI that cannot silently adopt a later configuration. / 返回不能默默采用后续配置的 AI。
     * @return frozen original AI capability / 冻结原 AI 能力 */
    @Override
    public AiService ai() {
        return new AiService() {
            /** Verifies the usable purpose and frozen whole route. / 验证可用用途和冻结整路由。
             * @param purpose required purpose / 必需用途
             * @param supplied original task / 原任务
             * @return frozen revision / 冻结修订
             * @throws ApiException on drift or missing purpose / 漂移或用途缺失时 */
            public String configurationRevision(AiPurpose purpose, TaskContext supplied) throws ApiException {
                sameTask(supplied);
                verify();
                if (!revision.equals(original.ai().configurationRevision(purpose, supplied)))
                    throw new IllegalStateException("Whole AI route differs by purpose");
                return revision;
            }

            /** Calls only the frozen route. / 仅调用冻结路由。
             * @param request bounded request / 有界请求
             * @param supplied original task / 原任务
             * @return actual provider feedback / 实际提供方反馈
             * @throws ApiException on drift or failed invocation / 漂移或调用失败时 */
            public OperationResult<AiResponse> call(AiRequest request, TaskContext supplied) throws ApiException {
                return call(request, revision, supplied);
            }

            /** Rejects any caller revision differing from the task. / 拒绝与任务不同的调用方修订。
             * @param request bounded request / 有界请求
             * @param expected caller binding / 调用方绑定
             * @param supplied original task / 原任务
             * @return actual provider feedback / 实际提供方反馈
             * @throws ApiException on drift or failed invocation / 漂移或调用失败时 */
            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext supplied)
                    throws ApiException {
                sameTask(supplied);
                verify();
                if (!revision.equals(expected))
                    throw new IllegalArgumentException("Assisted caller revision differs");
                return original.ai().call(request, revision, supplied);
            }
        };
    }

    /** Returns bound owner identity. / 返回绑定所有者身份。
     * @return bound owner identity / 绑定所有者身份 */
    @Override
    public ModIdentity identity() {
        return original.identity();
    }

    /** Returns original launch mode. / 返回原启动模式。
     * @return original launch mode / 原启动模式 */
    @Override
    public ModLaunchMode launchMode() {
        return original.launchMode();
    }

    /** Returns owner page capability. / 返回所属页面能力。
     * @return owner page capability / 所属页面能力 */
    @Override
    public UiService ui() {
        return original.ui();
    }

    /** Returns original task capability. / 返回原任务能力。
     * @return original task capability / 原任务能力 */
    @Override
    public TaskService tasks() {
        return original.tasks();
    }

    /** Returns host review capability. / 返回宿主审核能力。
     * @return host review capability / 宿主审核能力 */
    @Override
    public InteractionService interaction() {
        return original.interaction();
    }

    /** Returns original server references. / 返回原服务器引用。
     * @return original server references / 原服务器引用 */
    @Override
    public ServerService servers() {
        return original.servers();
    }

    /** Returns original Schema access. / 返回原 Schema 访问。
     * @return original Schema access / 原 Schema 访问 */
    @Override
    public ModDatabase db() {
        return original.db();
    }

    /** Returns original opaque secret access. / 返回原不透明秘密访问。
     * @return original opaque secret access / 原不透明秘密访问 */
    @Override
    public SecretService secrets() {
        return original.secrets();
    }

    /** Returns original source ownership. / 返回原来源归属。
     * @return original source ownership / 原来源归属 */
    @Override
    public SourceService source() {
        return original.source();
    }

    /** Returns original structured diagnostics. / 返回原结构化诊断。
     * @return original structured diagnostics / 原结构化诊断 */
    @Override
    public LogService logs() {
        return original.logs();
    }

    /** Returns original process ownership. / 返回原进程归属。
     * @return original process ownership / 原进程归属 */
    @Override
    public ProcessService processes() {
        return original.processes();
    }

    /** Returns declared service capability. / 返回已声明服务能力。
     * @return declared service capability / 已声明服务能力 */
    @Override
    public ModServices services() {
        return original.services();
    }
}
