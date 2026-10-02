package gold.debug.windowstolinux.app.service.file;

import java.io.IOException;
import gold.debug.windowstolinux.app.file.FileBackend;
import gold.debug.windowstolinux.appapi.file.FilePath;
import gold.debug.windowstolinux.appapi.task.*;

/** Host-only access to task-bound local or already managed SSH file channels. / 平台专有的任务绑定本机或既有受管 SSH 文件通道访问。 */
public interface FileBackendAccess {
    /** Runs with one owned backend and closes the channel before returning. / 使用一个所属后端执行，返回前关闭通道。 */
    <T> T withBackend(FilePath host, TaskContext task, Work<T> work) throws IOException, ApiException;
    /** Backend work; raw clients never cross appapi. / 后端工作，原始客户端不跨 appapi。 */
    @FunctionalInterface interface Work<T> { /** Runs actual account-level I/O. / 执行实际账户 I/O。 */ T run(FileBackend backend) throws IOException, ApiException; }
}
