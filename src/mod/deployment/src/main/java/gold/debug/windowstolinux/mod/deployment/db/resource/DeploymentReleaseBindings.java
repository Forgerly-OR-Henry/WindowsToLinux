package gold.debug.windowstolinux.mod.deployment.db.resource;

import java.sql.*;
import java.util.*;

/** Checks immutable release resource bindings in the caller's connection. / 在调用方连接中核对不可变发布资源绑定。 */
public final class DeploymentReleaseBindings {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentReleaseBindings() {
    }

    /** Checks immutable release bindings using the caller-owned connection. / 使用调用方所属连接核对不可变发布绑定。
     * @param connection caller-owned original transaction connection / 调用方所属原事务连接
     * @param bindings original bindings bound to this operation / 当前操作绑定的原bindings值
     * @throws SQLException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static void require(Connection connection, List<gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentReleaseBinding> bindings) throws SQLException {
        for (var binding : bindings) {
            try (var query = connection.prepareStatement(
                "SELECT configuration_revision,configuration_sha256,is_secret_binding_complete FROM deployment_unit_release_binding WHERE deployment_unit_id=? AND release_sha256=?")) {
                query.setString(1, binding.unitId());
                query.setString(2, binding.releaseSha256());
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != binding.configurationRevision()
                        || !rows.getString(2).equals(binding.configurationSha256()) || !rows.getBoolean(3))
                        throw new SQLException("Release configuration binding differs from original history");
                }
            }
            if (!Set.copyOf(
                    DeploymentSecretStore.findRelease(connection, binding.unitId(), binding.releaseSha256())
                        .orElseThrow(() -> new SQLException("Release secret set is absent")))
                .equals(Set.copyOf(binding.secrets())))
                throw new SQLException("Release secrets differ from original history");
        }
    }
}
