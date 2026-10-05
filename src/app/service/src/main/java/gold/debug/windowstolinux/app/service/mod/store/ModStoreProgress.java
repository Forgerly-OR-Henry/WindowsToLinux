package gold.debug.windowstolinux.app.service.mod.store;

import java.util.UUID;

import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;

/** Actual stage and byte observations for the original batch. / 原批次的实际阶段及字节观察。
 * @param batchId original batch identity / 原批次身份
 * @param planRevision confirmed revision / 已确认修订
 * @param key current exact package / 当前精确包
 * @param completedPackages packages with returned actual results / 已返回实际结果的包数
 * @param totalPackages confirmed package count / 已确认包数
 * @param stage current actual stage / 当前实际阶段
 * @param completedBytes actually received bytes / 实际收到字节
 * @param totalBytes catalog-declared exact byte count / 目录声明精确字节数
 */
public record ModStoreProgress(UUID batchId, long planRevision, ReleaseKey key, int completedPackages,
                               int totalPackages, Kind stage, long completedBytes, long totalBytes) {
    /** Actual transfer, verification and installer stages. / 实际传输、核验及安装阶段。 */
    public enum Kind {
        /** Rechecking original pending operations and installed reuse evidence. / 复核原待核操作及已装复用依据。 */
        REVALIDATING,
        /** Rechecking actual installed bytes before recording reuse. / 记录复用前重新核验实际已装字节。 */
        REUSING,
        /** Receiving actual package bytes into the owned temporary file. / 将实际包字节接收到所属临时文件。 */
        DOWNLOADING,
        /** Verifying the complete downloaded package size and digest. / 核验完整已下载包的长度及摘要。 */
        VERIFYING,
        /** Reviewing the actual package declaration against the complete catalog manifest. / 按完整目录清单审阅实际包声明。 */
        REVIEWING,
        /** Passing the exact original review to the original installer. / 将精确原审阅交给原安装器。 */
        INSTALLING,
        /** The actual package report has been saved; this stage does not imply success. / 实际包报告已保存，此阶段不表示成功。 */
        FINISHED
    }
}
