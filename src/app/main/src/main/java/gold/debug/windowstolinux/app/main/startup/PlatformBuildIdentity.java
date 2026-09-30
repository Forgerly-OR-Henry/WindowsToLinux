package gold.debug.windowstolinux.app.main.startup;

import javax.swing.SwingUtilities;

/** Reads compiled boot identities and enforces the blocking lifecycle boundary. / 读取编译后的启动身份并落实阻塞生命周期边界。 */
final class PlatformBuildIdentity {
    /** Prevents creation of a stateless identity reader. / 阻止创建无状态身份读取器。 */
    private PlatformBuildIdentity() {
    }

    /** Reads the actual compiled appapi identity for both management and startup. / 为管理与启动读取实际编译 appapi 身份。
     * @return exact public API release / 精确公开 API 版本
     * @throws java.io.IOException if identity is unavailable / 身份不可用时 */
    static gold.debug.windowstolinux.app.mod.version.SemanticVersion readApiVersion()
            throws java.io.IOException {
        var properties = new java.util.Properties();
        try (var input = gold.debug.windowstolinux.appapi.mod.ModContext.class
                .getResourceAsStream("/gold/debug/windowstolinux/appapi/build.properties")) {
            if (input == null)
                throw new java.io.IOException("Public API build identity is missing");
            properties.load(input);
        }
        return gold.debug.windowstolinux.app.mod.version.SemanticVersion
                .parse(properties.getProperty("platform.api.version"));
    }

    /**
     * Reads build-generated identity independently of working directory or JAR manifest layout. / 独立于工作目录及 JAR 清单布局读取构建生成身份。
     * @return actual non-secret artifact version / 实际不含秘密的产物版本
     * @throws java.io.IOException if metadata is absent or not generated / 元数据缺失或未生成时
     */
    static String readVersion() throws java.io.IOException {
        var properties = new java.util.Properties();
        try (var input = PlatformRuntime.class
                .getResourceAsStream("/gold/debug/windowstolinux/app/main/build.properties")) {
            if (input == null)
                throw new java.io.IOException("Application build identity is missing");
            properties.load(input);
        }
        String value = properties.getProperty("application.version", "");
        if (value.isBlank() || value.length() > 512 || value.contains("${"))
            throw new java.io.IOException("Application build identity is invalid");
        return value;
    }

    /** Rejects blocking startup and shutdown on the Swing event thread. / 拒绝在 Swing 事件线程执行阻塞启动和关闭。 */
    static void requireWorker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Platform lifecycle requires a worker thread");
    }
}
