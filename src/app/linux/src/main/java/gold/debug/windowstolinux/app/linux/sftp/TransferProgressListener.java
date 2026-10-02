package gold.debug.windowstolinux.app.linux.sftp;

import gold.debug.windowstolinux.appapi.linux.TransferProgress;

/** Host-owned progress publication without exposing the physical worker. / 不暴露物理工作线程的宿主所属进度发布。 */
@FunctionalInterface
public interface TransferProgressListener {
    /** Publishes observed stage bytes; callback time is not effective transfer progress. / 发布已观察阶段字节；回调耗时不是有效传输进展。
     * @param progress current observed progress / 当前已观察进度
     * @throws Exception if host progress publication fails / 宿主进度发布失败时 */
    void progress(TransferProgress progress) throws Exception;
}
