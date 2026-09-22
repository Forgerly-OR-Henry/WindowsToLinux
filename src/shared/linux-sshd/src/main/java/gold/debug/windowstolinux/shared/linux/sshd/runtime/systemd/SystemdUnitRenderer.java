package gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd;

import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * Builds systemd unit text from reviewed runtime, identity and storage contracts.
 * <p>根据已审阅的运行、身份及存储契约构建 systemd 单元文本。
 */
public final class SystemdUnitRenderer {
    /**
     * JAVA BINARY.
     * <p>Java二进制。
     */
    private static final String JAVA_BINARY = ManagedHelperBundle.JAVA_RUNTIME_PATH;

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SystemdUnitRenderer() {
    }

    /**
     * Renders systemd unit as text without executing the rendered command.
     * <p>渲染Systemd单元为文本，不执行所渲染命令。
     *
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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
                """.formatted(application.id(), username, application.releaseRoot(), JAVA_BINARY,
                application.releaseRoot());
    }
}
