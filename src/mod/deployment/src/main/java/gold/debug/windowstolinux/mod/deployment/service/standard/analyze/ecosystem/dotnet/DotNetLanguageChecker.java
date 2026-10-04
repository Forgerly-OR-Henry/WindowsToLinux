package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.dotnet;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceCheckFacts;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceLanguageEvidence;

/**
 * Detects .NET/C# markers independently of a build architecture. / 独立于构建架构识别 .NET/C# 语言标记。
 */
public final class DotNetLanguageChecker {
    /**
     * Returns language evidence without parsing build definitions. / 返回语言证据，不解析构建定义。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return language evidence without parsing build definitions / 语言证据，不解析构建定义
     */
    public ProjectLanguageFacts inspect(SourceCheckFacts source) {
        return SourceLanguageEvidence.collect(source, LanguageEcosystemType.DOTNET,
            name -> name.endsWith(".cs") || name.endsWith(".csproj") ? SourceLanguageType.CSHARP : null);
    }
}
