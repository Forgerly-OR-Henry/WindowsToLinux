package gold.debug.windowstolinux.appapi.file;

import java.nio.file.Path;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.ApiException;

/** Task-owned writable staging files retained until actual cleanup. / 保留至实际清理的任务所属可写暂存文件。 */
public interface FileWorkspace extends AutoCloseable {
    /** Returns the original resource identity. / 返回原资源身份。
     * @return immutable resource identity / 不可变资源身份 */
    UUID id();

    /** Returns the writable directory during the original executing callback. / 在原执行回调期间返回可写目录。
     * @return task-owned payload directory / 任务所属载荷目录
     * @throws ApiException if the task, activation or physical identity is unavailable / 任务、启用或物理身份不可用时 */
    Path directory() throws ApiException;

    /** Returns an original-task source grant for generated files. / 返回生成文件的原任务来源授权。
     * @return opaque original selection, revoked on close / 关闭时撤销的不透明原选择
     * @throws ApiException if the workspace is no longer accessible / 工作目录不再可访问时 */
    FileSelection selection() throws ApiException;

    /** Deletes actual staging files before releasing task occupancy; stop all writers first. / 释放任务占用前删除实际暂存文件，须先停止所有写入者。
     * @throws ApiException if physical or durable cleanup needs retry / 物理或持久清理需要重试时 */
    @Override
    void close() throws ApiException;
}
