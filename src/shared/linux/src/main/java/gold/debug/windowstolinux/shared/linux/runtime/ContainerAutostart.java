package gold.debug.windowstolinux.shared.linux.runtime;

import java.util.Objects;

/**
 * Engine-specific autostart intent that intentionally cannot be translated between Docker and Quadlet.
 *
 * <p>刻意不能在 Docker 和 Quadlet 间互译的引擎专属自启意图。
 */
public sealed interface ContainerAutostart permits ContainerAutostart.DockerRestartPolicy, ContainerAutostart.PodmanQuadletInstall {
    /** Docker restart-policy update that must not start or stop the container. / 不得启动或停止容器的 Docker 重启策略更新。 */
    record DockerRestartPolicy(Policy policy) implements ContainerAutostart {
        /** Creates a {@code DockerRestartPolicy} instance. / 创建 {@code DockerRestartPolicy} 实例。 */
        public DockerRestartPolicy { policy = Objects.requireNonNull(policy, "policy"); }
    }

    /** Podman Quadlet installation target; generated transient services are never enabled directly. / Podman Quadlet 安装目标；绝不直接启用生成的临时服务。 */
    record PodmanQuadletInstall(String wantedBy) implements ContainerAutostart {
        /** Creates a {@code PodmanQuadletInstall} instance. / 创建 {@code PodmanQuadletInstall} 实例。 */
        public PodmanQuadletInstall {
            wantedBy = Objects.requireNonNull(wantedBy, "wantedBy").trim();
            if (!wantedBy.matches("[a-zA-Z0-9_.@-]{1,128}")) {
                throw new IllegalArgumentException("wantedBy must be a bounded Quadlet install target");
            }
        }
    }

    /** Docker restart policies permitted for managed containers. / 受管容器允许的 Docker 重启策略。 */
    enum Policy { /** Disabled. / 禁用。 */ NO, /** Always restart. / 总是重启。 */ ALWAYS,
        /** Restart unless explicitly stopped. / 除非显式停止否则重启。 */ UNLESS_STOPPED,
        /** Restart after failure only. / 仅失败后重启。 */ ON_FAILURE }
}
