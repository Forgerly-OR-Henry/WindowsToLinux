package gold.debug.windowstolinux.app.service.file.check;

import java.io.IOException;

import gold.debug.windowstolinux.app.file.model.FileBackend;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Host-only access to task-bound local or already managed SSH file channels. / 平台专有的任务绑定本机或既有受管 SSH 文件通道访问。 */
public interface FileBackendAccess {
    /** Runs with one owned backend and closes the channel before returning. / 使用一个所属后端执行，返回前关闭通道。
     * @param host host-qualified registry scope / 带主机登记作用域
     * @param task authentic original executing task / 真实原执行任务
     * @param work bounded admitted host callback / 有界准入宿主回调
     * @param <T> admitted callback result type / 准入回调结果类型
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    <T> T withBackend(FilePath host, TaskContext task, Work<T> work) throws IOException, ApiException;

    /** Backend work; raw clients never cross appapi. / 后端工作，原始客户端不跨 appapi。
     * @param <T> admitted callback result type / 准入回调结果类型
     */
    @FunctionalInterface
    interface Work<T> {
        /** Runs actual account-level I/O. / 执行实际账户 I/O。
         * @param backend account-level host primitives / 账户级主机原语
         * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
         * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
         */
        T run(FileBackend backend) throws IOException, ApiException;
    }
}
