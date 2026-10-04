package gold.debug.windowstolinux.app.service.server;

import java.sql.SQLException;

import gold.debug.windowstolinux.app.service.server.ServerProfile;

/** Saves platform connection settings without invoking deployment or remote preparation. / 保存平台连接设置，不调用部署或远端准备。 */
@FunctionalInterface
public interface ServerProfileEditor {
    /**
     * Atomically saves metadata and an optional replacement password. / 原子保存元数据及可选替换密码。
     * @param profile exact connection metadata / 精确连接元数据
     * @param password temporary password, cleared by the implementation / 实现负责清零的临时密码
     * @throws SQLException if metadata cannot be saved / 无法保存元数据时
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if the credential cannot be saved / 无法保存凭据时
     */
    void saveServerProfile(ServerProfile profile, char[] password) throws SQLException, gold.debug.windowstolinux.appapi.task.result.ApiException;
}
