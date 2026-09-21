package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.source.SourceLanguageEvidence;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

/**
 * Detects Kotlin markers independently of a build architecture. / 独立于构建架构识别 Kotlin 语言标记。
 */
public final class KotlinLanguageInspector {
    /**
     * Returns language evidence without parsing build definitions. / 返回语言证据，不解析构建定义。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return language evidence without parsing build definitions / 语言证据，不解析构建定义
     */
    public ProjectLanguageFacts inspect(SourceInspectionFacts source) {
        return SourceLanguageEvidence.collect(source, LanguageEcosystemType.KOTLIN,
                name -> name.endsWith(".kt") || name.endsWith(".kts")
                        ? SourceLanguageType.KOTLIN : null);
    }
}
