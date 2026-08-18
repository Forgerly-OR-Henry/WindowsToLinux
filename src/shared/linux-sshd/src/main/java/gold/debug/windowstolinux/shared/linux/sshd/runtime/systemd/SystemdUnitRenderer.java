package gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.helper.ManagedHelperBundle;

import java.util.Objects;

/**
 * Provides the {@code SystemdUnitRenderer} implementation.
 *
 * <p>提供 {@code SystemdUnitRenderer} 实现。
 */
public final class SystemdUnitRenderer {
    private static final String JAVA_BINARY = ManagedHelperBundle.JAVA_RUNTIME_PATH;

    private SystemdUnitRenderer() {
    }

    /**
     * Performs the {@code render} operation.
     *
     * <p>执行 {@code render} 操作。
     *
     * @param username the {@code username} value / {@code username} 值
     * @param application the {@code application} value / {@code application} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static String render(String username, ManagedApplication application) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(application, "application");
        return """
                [Unit]
                Description=WindowsToLinux managed %s
                After=network.target

                [Service]
                Type=simple
                User=%s
                WorkingDirectory=%s/current
                ExecStart=%s -jar %s/current/app.jar
                Restart=on-failure
                RestartSec=5
                SuccessExitStatus=143

                [Install]
                WantedBy=multi-user.target
                """.formatted(application.id(), username, application.releaseRoot(), JAVA_BINARY, application.releaseRoot());
    }
}
