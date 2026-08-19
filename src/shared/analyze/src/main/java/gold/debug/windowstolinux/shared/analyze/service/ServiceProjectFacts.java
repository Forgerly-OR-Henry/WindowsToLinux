package gold.debug.windowstolinux.shared.analyze.service;

import java.util.List;
import java.util.regex.Pattern;

/** Common bounded service metadata returned by one language inspector. / 单个语言检查器返回的公共有界服务元数据。 */
public record ServiceProjectFacts(
        String primaryMetadata,
        String version,
        String artifactName,
        String entrypoint,
        List<String> missingFiles
) {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** Preserves immutable missing-file evidence and rejects unsafe artifact names. / 保留不可变缺失文件证据并拒绝不安全的制品名称。 */
    public ServiceProjectFacts {
        if (artifactName != null && !SAFE_NAME.matcher(artifactName).matches()) {
            artifactName = null;
        }
        missingFiles = List.copyOf(missingFiles);
    }
}
