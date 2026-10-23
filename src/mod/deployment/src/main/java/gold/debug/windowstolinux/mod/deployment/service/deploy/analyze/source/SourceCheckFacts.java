package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Bounded source facts with an explicit reviewed logical identity. / 带明确审阅逻辑身份的有界源码事实。
 * @param scannedFiles observed file count / 已观察文件数
 * @param relativeFiles bounded relative paths / 有界相对路径
 * @param scannedText bounded source metadata / 有界源码元数据
 * @param reviewedRootName original reviewed source name / 原审阅源码名称 */
public record SourceCheckFacts(int scannedFiles, List<Path> relativeFiles, String scannedText,
                               String reviewedRootName) {
    /** Copies source paths and rejects absent review identity. / 复制源码路径并拒绝缺失审阅身份。
     * @param scannedFiles file count / 文件数
     * @param relativeFiles relative paths / 相对路径
     * @param scannedText metadata / 元数据
     * @param reviewedRootName reviewed name / 审阅名称 */
    public SourceCheckFacts {
        relativeFiles = List.copyOf(relativeFiles);
        Objects.requireNonNull(scannedText);
        if (reviewedRootName == null || reviewedRootName.isBlank())
            throw new IllegalArgumentException("reviewed source name is required");
    }
}
