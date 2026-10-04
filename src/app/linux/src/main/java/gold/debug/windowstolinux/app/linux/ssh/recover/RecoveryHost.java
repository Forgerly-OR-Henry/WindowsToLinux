package gold.debug.windowstolinux.app.linux.ssh.recover;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import gold.debug.windowstolinux.app.linux.ssh.recover.browser.TaskBrowserSession;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Injects existing platform authority and storage into the SSH-owned rescue. / 向 SSH 救援注入既有平台授权及存储。 */
public interface RecoveryHost {
    /** Checks interactive authorization. / 检查交互授权。 */
    void requireUnlocked();

    /** Runs a short control under the original lock generation. / 在原锁定代次下运行短控制操作。
     * @param action original control / 原控制
     * @param <T> result type / 结果类型
     * @return original result / 原结果 */
    <T> T whileUnlocked(Supplier<T> action);

    /** Registers browser privacy. / 登记浏览器隐私。
     * @param listener original lock observer / 原锁定观察者
     * @return retained registration / 保留登记 */
    AutoCloseable observeLock(Consumer<Boolean> listener);

    /** Binds the original platform task owner. / 绑定原平台任务所有者。
     * @param version exact owner version / 精确所有者版本
     * @param recovery original-task mode / 原任务模式
     * @return authenticated task service / 已认证任务服务 */
    TaskService tasks(String version, boolean recovery);

    /** Lists the original central tasks. / 列出原中央任务。
     * @return current task observations / 当前任务观察 */
    List<TaskSnapshot> snapshots();

    /** Reads the current saved selection. / 读取当前已保存选择。
     * @param id original server identity / 原服务器身份
     * @return complete non-secret selection / 完整非秘密选择
     * @throws Exception if storage is unavailable / 存储不可用时 */
    SshRecovery.Server server(String id) throws Exception;

    /** Checks original retained server ownership. / 检查原保留服务器占用。
     * @param task original task / 原任务
     * @param server exact server / 精确服务器
     * @return whether ownership remains / 占用是否保留
     * @throws java.sql.SQLException if storage is unavailable / 存储不可用时 */
    boolean holdsServer(UUID task, String server) throws java.sql.SQLException;

    /** Reconciles the existing task without submitting another one. / 核对既有任务，不提交另一任务。
     * @param id original task / 原任务
     * @return original completion / 原完成结果
     * @throws ApiException if authority is unavailable / 授权不可用时 */
    CompletionStage<TaskSnapshot> reconcile(UUID id) throws ApiException;

    /** Releases only recorded previous-process resources. / 仅释放记录的前进程资源。
     * @param previous original task / 原任务
     * @throws Exception if release is unverified / 释放未核实时 */
    void releasePrevious(TaskSnapshot previous) throws Exception;

    /** Supplies original browser resource receipts. / 提供原浏览器资源凭据。
     * @return task-bound browser authority / 任务绑定浏览器授权 */
    TaskBrowserSession.Host browser();

    /** Executes the guarded fixed SSH verification. / 执行受保护固定 SSH 复核。
     * @param server admitted server / 已准入服务器
     * @param task original task / 原任务
     * @return fixed observation / 固定观察
     * @throws ApiException if authority or cleanup fails / 授权或清理失败时 */
    OperationResult<Void> verify(ServerId server, TaskContext task) throws ApiException;

    /** Calls the configured model with its existing limits. / 按既有上限调用配置模型。
     * @param request bounded rescue request / 有界救援请求
     * @param task original task / 原任务
     * @return original model outcome / 原模型结果
     * @throws ApiException if authority or cleanup fails / 授权或清理失败时 */
    OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException;

    /** Closes original transport, interaction and model resources. / 关闭原传输、交互及模型资源。
     * @param task original task / 原任务
     * @throws ApiException if resources remain / 资源仍保留时 */
    void closeResources(TaskContext task) throws ApiException;

    /** Stores only bounded rescue metadata under the original task. / 以原任务保存有界救援元数据。
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param state rescue lifecycle / 救援生命周期
     * @param digest command hash / 命令摘要
     * @param event safe event / 安全事件
     * @throws Exception if the transaction fails / 事务失败时 */
    void journal(TaskContext task, String server, String state, String digest, String event) throws Exception;
}
