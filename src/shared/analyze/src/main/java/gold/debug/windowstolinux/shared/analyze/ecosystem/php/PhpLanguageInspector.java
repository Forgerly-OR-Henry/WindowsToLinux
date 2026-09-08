package gold.debug.windowstolinux.shared.analyze.ecosystem.php;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.source.SourceLanguageEvidence;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

/** Detects PHP markers independently of a build architecture. / 独立于构建架构识别 PHP 语言标记。 */
public final class PhpLanguageInspector {
    /** Returns language evidence without parsing build definitions. / 返回语言证据，不解析构建定义。 */
    public ProjectLanguageFacts inspect(SourceInspectionFacts source) {
        return SourceLanguageEvidence.collect(source, LanguageEcosystemType.PHP,
                name -> name.endsWith(".php") || name.equals("composer.json")
                        ? SourceLanguageType.PHP : null);
    }
}
