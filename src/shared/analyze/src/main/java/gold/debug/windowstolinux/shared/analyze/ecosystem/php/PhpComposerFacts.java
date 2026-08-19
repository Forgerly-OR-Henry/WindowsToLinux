package gold.debug.windowstolinux.shared.analyze.ecosystem.php;

import java.util.List;

/** Fixed Composer metadata used by PHP service analysis. / PHP 服务分析使用的固定 Composer 元数据。 */
record PhpComposerFacts(String version, List<String> missingFiles) {
    PhpComposerFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
