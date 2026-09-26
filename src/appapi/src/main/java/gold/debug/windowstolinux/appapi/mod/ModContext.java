package gold.debug.windowstolinux.appapi.mod;

import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.source.SourceService;
import gold.debug.windowstolinux.appapi.task.InteractionService;
import gold.debug.windowstolinux.appapi.task.LogService;
import gold.debug.windowstolinux.appapi.task.ProcessService;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.ui.UiService;

/**
 * Platform capabilities bound to the actual starting Mod. / 绑定真实启动插件身份的平台能力集合。
 */
public interface ModContext {

    /**
     * Returns the actual bound identity, which caller input cannot replace. / 返回实际绑定身份，调用方输入不能替换该身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    ModIdentity identity();

    /**
     * Returns normal startup or restricted recovery mode. / 返回正常启动或受限恢复模式。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    ModLaunchMode launchMode();

    /**
     * Returns owner-bound page registration and opening. / 返回绑定所有者的页面登记与打开能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UiService ui();

    /**
     * Returns owner-bound task admission and reconciliation. / 返回绑定所有者的任务接纳与核对能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    TaskService tasks();

    /**
     * Returns host-owned review and input interactions. / 返回宿主持有的审核与输入交互能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    InteractionService interaction();

    /**
     * Returns platform server lookup and reference reservations. / 返回平台服务器查询与引用预留能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    ServerService servers();

    /**
     * Returns task-authorized SSH and remote transfer access. / 返回经过任务授权的 SSH 与远端传输能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    LinuxService linux();

    /**
     * Returns task-authorized model invocation. / 返回经过任务授权的模型调用能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    AiService ai();

    /**
     * Returns connections bound to the owner's active Schema generation. / 返回绑定所有者活动数据代次的连接能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    ModDatabase db();

    /**
     * Returns authorized short-lived secret access. / 返回经授权的短期秘密访问能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    SecretService secrets();

    /**
     * Returns authorized source reads and snapshots. / 返回经授权的来源读取与快照能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    SourceService source();

    /**
     * Returns structured diagnostic storage for this owner. / 返回此所有者的结构化诊断存储能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    LogService logs();

    /**
     * Returns owned protocol-process startup and tracking. / 返回所属协议进程的启动与跟踪能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    ProcessService processes();

    /**
     * Returns explicitly declared cross-Mod services. / 返回显式声明的跨插件服务能力。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    ModServices services();
}
