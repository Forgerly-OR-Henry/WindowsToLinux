package gold.debug.windowstolinux.shared.model.managed;

import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.util.Objects;

/**
 * Immutable identity of an application owned by WindowsToLinux.
 *
 *  <p>由 WindowsToLinux 拥有的应用不可变身份。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
 * @param systemdUnit systemd unit / systemd单元
 * @param releaseRoot release root / 发布根目录
 * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
 */
public record ManagedApplication(
        String id,
        ServerIdentity server,
        String systemdUnit,
        String releaseRoot,
        String ownershipManifestSha256
) {
    /**
     * Validates and binds the inputs required by managed application.
     * <p>校验并绑定受管应用所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param systemdUnit systemd unit / systemd单元
     * @param releaseRoot release root / 发布根目录
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedApplication {
        id = ServerIdentity.requireIdentifier(id, "id");
        server = Objects.requireNonNull(server, "server");
        systemdUnit = requireUnit(systemdUnit);
        releaseRoot = Objects.requireNonNull(releaseRoot, "releaseRoot");
        if (!releaseRoot.equals(ManagedStorageLocation.installationRoot(id))) {
            throw new IllegalArgumentException("releaseRoot must stay under the managed application root");
        }
        ownershipManifestSha256 = Objects.requireNonNull(ownershipManifestSha256, "ownershipManifestSha256");
        if (!ownershipManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ownershipManifestSha256 must be lowercase SHA-256");
        }
    }

    /**
     * Builds managed application from the supplied for managed inputs.
     * <p>根据所提供对应受管输入构建受管应用。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @return the operation result / 操作结果
     */
    public static ManagedApplication forManaged(String id, ServerIdentity server, String ownershipManifestSha256) {
        String normalizedId = ServerIdentity.requireIdentifier(id, "id");
        return new ManagedApplication(
                normalizedId,
                server,
                "windowstolinux-" + normalizedId + ".service",
                ManagedStorageLocation.installationRoot(normalizedId),
                ownershipManifestSha256
        );
    }

    /**
     * Validates and returns unit and rejects inputs outside the declared constraints.
     * <p>校验并返回单元并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return require unit text / 要求单元文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireUnit(String value) {
        value = Objects.requireNonNull(value, "systemdUnit");
        if (!value.matches("windowstolinux-[a-z0-9][a-z0-9-]{0,62}\\.service")) {
            throw new IllegalArgumentException("systemdUnit must be a WindowsToLinux managed service");
        }
        return value;
    }
}
