package gold.debug.windowstolinux.mod.deployment.file;

import java.util.*;

import gold.debug.windowstolinux.appapi.file.FileSnapshot;
import gold.debug.windowstolinux.appapi.task.ApiException;

/** Source-only frozen material shared by subsequent analysis and deployment steps. / 供后续分析及部署步骤共用的纯源码冻结素材。
 * @param displayName original selected source name / 原选定来源名称
 * @param snapshot original platform-owned immutable handle / 原平台持有的不可变句柄
 * @param includedFiles exact sorted copied file names / 精确有序复制文件名
 * @param excludedEntries exact sorted omitted files and directories / 精确有序排除文件和目录 */
public record DeploymentSourceMaterial(String displayName, FileSnapshot snapshot, List<String> includedFiles,
                                       List<String> excludedEntries) implements AutoCloseable {
    /** Retains the actual platform lifetime without duplicating its files or ledger. / 保留实际平台寿命，不复制文件或账本。
     * @param displayName original source name / 原来源名称
     * @param snapshot original owned handle / 原所属句柄
     * @param includedFiles original copied names / 原复制名称
     * @param excludedEntries original excluded names / 原排除名称 */
    public DeploymentSourceMaterial {
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(snapshot);
        includedFiles = List.copyOf(includedFiles);
        excludedEntries = List.copyOf(excludedEntries);
    }

    /** Releases actual source work through its original platform handle. / 经原平台句柄释放实际来源工作。
     * @throws ApiException if physical or durable cleanup remains uncertain / 物理或持久清理仍不确定时 */
    @Override
    public void close() throws ApiException {
        snapshot.close();
    }
}
