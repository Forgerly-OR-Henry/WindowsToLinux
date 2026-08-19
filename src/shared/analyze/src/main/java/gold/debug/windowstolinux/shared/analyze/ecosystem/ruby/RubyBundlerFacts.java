package gold.debug.windowstolinux.shared.analyze.ecosystem.ruby;

import java.util.List;

/** Fixed Bundler metadata used by Ruby service analysis. / Ruby 服务分析使用的固定 Bundler 元数据。 */
record RubyBundlerFacts(String version, List<String> missingFiles) {
    RubyBundlerFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
