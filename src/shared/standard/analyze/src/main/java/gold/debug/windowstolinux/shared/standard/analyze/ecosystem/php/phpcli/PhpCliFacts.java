package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.php.phpcli;

import java.util.List;

/**
 * Fixed dependency-free PHP CLI architecture facts. / 固定的无依赖 PHP CLI 架构事实。
 *
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param documentRoot document root / 文档根目录
 * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
 * @param missingItems missing items / 缺失项目集合
 */
public record PhpCliFacts(String version, String documentRoot, String entrypoint, List<String> missingItems) {
    /**
     * Makes missing facts immutable. / 使缺失事实不可变。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param documentRoot document root / 文档根目录
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param missingItems missing items / 缺失项目集合
     */
    public PhpCliFacts {
        missingItems = List.copyOf(missingItems);
    }
}
