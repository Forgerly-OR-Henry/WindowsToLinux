package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Bounded facts collected without executing any project-controlled content.
 *
 *  <p>在不执行任何项目可控内容的前提下采集的有界事实。
 *
 * @param scannedFiles scanned files / 已扫描文件集合
 * @param relativeFiles the bounded relative source paths / 有界相对源码路径
 * @param scannedText scanned text / 已扫描文本
 * @param reviewedRootName original reviewed name for metadata-free identity fallback / 无元数据时身份回退所用原审阅名称
 */
public record SourceInspectionFacts(int scannedFiles, List<Path> relativeFiles, String scannedText,
                                    Optional<String> reviewedRootName) {
    /** Preserves legacy Path-based analysis without an explicit reviewed name. / 保留未传明确审阅名称的旧Path分析。
     * @param scannedFiles scanned file count / 已扫描文件数
     * @param relativeFiles bounded relative paths / 有界相对路径
     * @param scannedText bounded scanned text / 有界扫描文本 */
    public SourceInspectionFacts(int scannedFiles, List<Path> relativeFiles, String scannedText) {
        this(scannedFiles, relativeFiles, scannedText, Optional.empty());
    }

    /**
     * Creates immutable bounded source facts. / 创建不可变的有界源码事实。
     *
     * @param scannedFiles scanned files / 已扫描文件集合
     * @param relativeFiles the bounded relative source paths / 有界相对源码路径
     * @param scannedText scanned text / 已扫描文本
     * @param reviewedRootName reviewed fallback name / 已审阅回退名称
     */
    public SourceInspectionFacts {
        relativeFiles = List.copyOf(relativeFiles);
        reviewedRootName = java.util.Objects.requireNonNull(reviewedRootName);
    }

    /** Binds one explicitly reviewed name to this analysis observation. / 为此次分析观察绑定一个明确审阅名称。
     * @param name original reviewed name / 原审阅名称
     * @return immutable facts with that fallback / 带该回退名称的不可变事实 */
    public SourceInspectionFacts withReviewedRootName(String name) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("reviewed source name is required");
        return new SourceInspectionFacts(scannedFiles, relativeFiles, scannedText, Optional.of(name));
    }

    /** Uses the selected directory name only when no explicit reviewed name exists. / 仅在没有明确审阅名称时使用所选目录名。
     * @param root current source root / 当前源码根
     * @return original name before identifier normalization / 标识规范化前原名 */
    public String logicalRootName(Path root) {
        return reviewedRootName.orElseGet(() -> ProjectIdentityResolver.rootName(root));
    }
}
