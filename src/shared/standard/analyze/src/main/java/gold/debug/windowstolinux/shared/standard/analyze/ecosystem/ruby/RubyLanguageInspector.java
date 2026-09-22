package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.ruby;

import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceLanguageEvidence;

/**
 * Detects Ruby markers independently of a build architecture. / 独立于构建架构识别 Ruby 语言标记。
 */
public final class RubyLanguageInspector {
    /**
     * Returns language evidence without parsing build definitions. / 返回语言证据，不解析构建定义。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return language evidence without parsing build definitions / 语言证据，不解析构建定义
     */
    public ProjectLanguageFacts inspect(SourceInspectionFacts source) {
        return SourceLanguageEvidence.collect(source, LanguageEcosystemType.RUBY,
                name -> name.endsWith(".rb") || name.equals("gemfile") ? SourceLanguageType.RUBY : null);
    }
}
