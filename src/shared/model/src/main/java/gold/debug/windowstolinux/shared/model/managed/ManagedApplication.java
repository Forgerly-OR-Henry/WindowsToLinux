package gold.debug.windowstolinux.shared.model.managed;

import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.util.Objects;

/**
 * Immutable identity of an application owned by WindowsToLinux.
 *
 * <p>由 WindowsToLinux 拥有的应用不可变身份。
 *
 * @param id the {@code id} value / {@code id} 值
 * @param server the {@code server} value / {@code server} 值
 * @param systemdUnit the {@code systemdUnit} value / {@code systemdUnit} 值
 * @param releaseRoot the {@code releaseRoot} value / {@code releaseRoot} 值
 * @param ownershipManifestSha256 the {@code ownershipManifestSha256} value / {@code ownershipManifestSha256} 值
 */
public record ManagedApplication(
        String id,
        ServerIdentity server,
        String systemdUnit,
        String releaseRoot,
        String ownershipManifestSha256
) {
    /**
     * Creates a {@code ManagedApplication} instance.
     *
     * <p>创建 {@code ManagedApplication} 实例。
     *
     * @param id the {@code id} value / {@code id} 值
     * @param server the {@code server} value / {@code server} 值
     * @param systemdUnit the {@code systemdUnit} value / {@code systemdUnit} 值
     * @param releaseRoot the {@code releaseRoot} value / {@code releaseRoot} 值
     * @param ownershipManifestSha256 the {@code ownershipManifestSha256} value / {@code ownershipManifestSha256} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ManagedApplication {
        id = ServerIdentity.requireIdentifier(id, "id");
        server = Objects.requireNonNull(server, "server");
        systemdUnit = requireUnit(systemdUnit);
        releaseRoot = Objects.requireNonNull(releaseRoot, "releaseRoot");
        if (!releaseRoot.matches("/var/lib/windowstolinux/apps/[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("releaseRoot must stay under the managed application root");
        }
        ownershipManifestSha256 = Objects.requireNonNull(ownershipManifestSha256, "ownershipManifestSha256");
        if (!ownershipManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ownershipManifestSha256 must be lowercase SHA-256");
        }
    }

    /**
     * Performs the {@code forPhaseOne} operation.
     *
     * <p>执行 {@code forPhaseOne} 操作。
     *
     * @param id the {@code id} value / {@code id} 值
     * @param server the {@code server} value / {@code server} 值
     * @param ownershipManifestSha256 the {@code ownershipManifestSha256} value / {@code ownershipManifestSha256} 值
     * @return the operation result / 操作结果
     */
    public static ManagedApplication forPhaseOne(String id, ServerIdentity server, String ownershipManifestSha256) {
        String normalizedId = ServerIdentity.requireIdentifier(id, "id");
        return new ManagedApplication(
                normalizedId,
                server,
                "windowstolinux-" + normalizedId + ".service",
                "/var/lib/windowstolinux/apps/" + normalizedId,
                ownershipManifestSha256
        );
    }

    private static String requireUnit(String value) {
        value = Objects.requireNonNull(value, "systemdUnit");
        if (!value.matches("windowstolinux-[a-z0-9][a-z0-9-]{0,62}\\.service")) {
            throw new IllegalArgumentException("systemdUnit must be a WindowsToLinux managed service");
        }
        return value;
    }
}
