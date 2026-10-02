package gold.debug.windowstolinux.app.mod.config;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.mod.version.VersionRange;

/** Parsed author metadata, independent of installation intent or runtime state. / 已解析的作者元数据，独立于安装意图及运行状态。
 * @param id stable exact Mod identity / 稳定且精确的插件身份
 * @param name author display name / 作者声明的显示名
 * @param version canonical release version / 规范发布版本
 * @param platformApiRange supported platform API versions / 支持的平台 API 版本
 * @param minJavaVersion minimum Java major version / 最低 Java 主版本
 * @param mainJar declared main JAR path / 声明的主 JAR 路径
 * @param entryClass fully qualified entry class / 入口类全限定名
 * @param description optional author description / 可选作者说明
 * @param authors copied author declarations / 作者声明副本
 * @param homepage optional absolute HTTPS homepage / 可选的绝对 HTTPS 主页
 * @param dependencies copied required dependency declarations / 必需依赖声明副本
 * @param exports copied public package declarations / 公开包声明副本
 * @param schemaVersion optional canonical data version / 可选规范数据版本
 * @param migrations copied explicit migration edges / 显式迁移边副本
 * @param json original validated JSON text for durable registration / 供持久登记使用的原始已验证 JSON 文本
 */
public record ModManifest(String id, String name, SemanticVersion version, VersionRange platformApiRange,
                          int minJavaVersion, String mainJar, String entryClass, Optional<String> description,
                          List<String> authors,
                          Optional<URI> homepage, List<Dependency> dependencies, List<String> exports,
                          Optional<String> schemaVersion,
                          List<Migration> migrations, String json) {
    /**
     * Copies author collections after strict reader validation. / 严格读取器验证后复制作者集合。
     * @param id stable exact Mod identity / 稳定且精确的插件身份
     * @param name author display name / 作者声明的显示名
     * @param version canonical release version / 规范发布版本
     * @param platformApiRange supported platform API versions / 支持的平台 API 版本
     * @param minJavaVersion minimum Java major version / 最低 Java 主版本
     * @param mainJar declared main JAR path / 声明的主 JAR 路径
     * @param entryClass fully qualified entry class / 入口类全限定名
     * @param description optional author description / 可选作者说明
     * @param authors author declarations / 作者声明
     * @param homepage optional absolute HTTPS homepage / 可选的绝对 HTTPS 主页
     * @param dependencies required dependency declarations / 必需依赖声明
     * @param exports public package declarations / 公开包声明
     * @param schemaVersion optional canonical data version / 可选规范数据版本
     * @param migrations explicit migration edges / 显式迁移边
     * @param json original validated JSON text / 原始已验证 JSON 文本
     */
    public ModManifest {
        authors = List.copyOf(authors);
        dependencies = List.copyOf(dependencies);
        exports = List.copyOf(exports);
        migrations = List.copyOf(migrations);
    }

    /** Returns the current Java package containing the explicit entry class. / 返回显式入口类当前所属的 Java 包。
     * @return entry package, independent of a historical stable ID / 入口包，独立于历史稳定 ID */
    public String rootPackage() {
        return entryClass.substring(0, entryClass.lastIndexOf('.'));
    }

    /** One required provider and its supported release range. / 一个必需提供方及其受支持发布范围。
     * @param id exact required Mod identity / 精确的必需插件身份
     * @param versionRange accepted provider releases / 接受的提供方发布版本 */
    public record Dependency(String id, VersionRange versionRange) {
    }

    /** One explicit increasing data migration edge. / 一条显式递增的数据迁移边。
     * @param from canonical source data version / 规范来源数据版本
     * @param to canonical target data version / 规范目标数据版本
     * @param className fully qualified author migration class / 作者迁移类全限定名 */
    public record Migration(String from, String to, String className) {
    }
}
