package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.php.composer;

import java.util.List;

/**
 * Fixed Composer metadata used by PHP service analysis. / PHP 服务分析使用的固定 Composer 元数据。
 *
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param missingFiles missing files / 缺失文件集合
 */
record PhpComposerFacts(String version, List<String> missingFiles) {
    /**
     * Binds the supplied dependencies and state for php composer facts.
     * <p>为PHPComposer事实绑定传入的依赖及状态。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param missingFiles missing files / 缺失文件集合
     */
    PhpComposerFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
