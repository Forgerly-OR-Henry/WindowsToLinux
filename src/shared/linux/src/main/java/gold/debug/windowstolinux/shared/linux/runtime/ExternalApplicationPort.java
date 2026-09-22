package gold.debug.windowstolinux.shared.linux.runtime;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.lifecycle.*;

/**
 * Bounded discovery and identity-checked lifecycle of existing applications. / 既有应用的有界发现与身份复核生命周期。
 */
public interface ExternalApplicationPort {
    /**
     * Scans external application scan.
     * <p>扫描外部应用扫描。
     *
     * @return constructed or resolved external application scan / 构造或解析得到的外部应用扫描
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    ExternalApplicationScan scan() throws LinuxOperationException;

    /**
     * Executes discovered application.
     * <p>执行已发现应用。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved discovered application / 构造或解析得到的已发现应用
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    DiscoveredApplication execute(ExternalApplicationTarget target, LifecycleAction action)
            throws LinuxOperationException;
}
