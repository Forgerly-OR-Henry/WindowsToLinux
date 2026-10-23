package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.rust;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceLanguageEvidence;

/**
 * Detects Rust markers independently of a build architecture. / 独立于构建架构识别 Rust 语言标记。
 */
public final class RustLanguageChecker {
    /**
     * Returns language evidence without parsing build definitions. / 返回语言证据，不解析构建定义。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return language evidence without parsing build definitions / 语言证据，不解析构建定义
     */
    public ProjectLanguageFacts inspect(SourceCheckFacts source) {
        return SourceLanguageEvidence.collect(source, LanguageEcosystemType.RUST,
            name -> name.endsWith(".rs") || name.equals("cargo.toml") ? SourceLanguageType.RUST : null);
    }
}
