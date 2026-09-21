package gold.debug.windowstolinux.shared.linux.protocol.backup;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;

import java.io.OutputStream;

/**
 * Fixed remote creation, verified streaming and exact cleanup for managed backup artifacts. / 受管备份制品的固定远端创建、校验流式传输与精确清理。
 */
public interface RemoteBackupArtifactPort {
    /**
     * Rejects begin maintenance because this adapter does not provide that capability.
     * <p>拒绝开始维护，因为当前适配器不提供该能力。
     *
     * @param app app / 应用
     * @param token token / 令牌
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws UnsupportedOperationException if the requested capability is not implemented by this adapter / 当前适配器未实现所请求能力时
     */
    default void beginMaintenance(gold.debug.windowstolinux.shared.model.managed.ManagedApplication app, String token)
            throws LinuxOperationException { throw new UnsupportedOperationException("application maintenance is unavailable"); }

    /**
     * Rejects end maintenance because this adapter does not provide that capability.
     * <p>拒绝结束维护，因为当前适配器不提供该能力。
     *
     * @param app app / 应用
     * @param token token / 令牌
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws UnsupportedOperationException if the requested capability is not implemented by this adapter / 当前适配器未实现所请求能力时
     */
    default void endMaintenance(gold.debug.windowstolinux.shared.model.managed.ManagedApplication app, String token)
            throws LinuxOperationException { throw new UnsupportedOperationException("application maintenance is unavailable"); }
    /**
     * Creates exactly one artifact from its reviewed managed identity. / 从经审阅受管身份创建一个精确制品。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return exactly one artifact from its reviewed managed identity / 从经审阅受管身份创建一个精确制品
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteBackupArtifact createBackupArtifact(RemoteBackupArtifactRequest request) throws LinuxOperationException;

    /**
     * Streams one exact artifact while independently verifying size and digest. / 流式回读一个精确制品并独立校验长度和摘要。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void copyBackupArtifact(RemoteBackupArtifact artifact, OutputStream destination) throws LinuxOperationException;

    /**
     * Removes only the exact helper-owned operation directory. / 仅删除 helper 持有的精确操作目录。
     *
     * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteStepResult discardBackupOperation(String operationId) throws LinuxOperationException;
}
