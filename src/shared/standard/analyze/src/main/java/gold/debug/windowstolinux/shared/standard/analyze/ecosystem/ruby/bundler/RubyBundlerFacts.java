package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.ruby.bundler;

import java.util.List;

/**
 * Fixed Bundler metadata used by Ruby service analysis. / Ruby 服务分析使用的固定 Bundler 元数据。
 *
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param missingFiles missing files / 缺失文件集合
 */
record RubyBundlerFacts(String version, List<String> missingFiles) {
    /**
     * Binds the supplied dependencies and state for ruby bundler facts.
     * <p>为RubyBundler事实绑定传入的依赖及状态。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param missingFiles missing files / 缺失文件集合
     */
    RubyBundlerFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
