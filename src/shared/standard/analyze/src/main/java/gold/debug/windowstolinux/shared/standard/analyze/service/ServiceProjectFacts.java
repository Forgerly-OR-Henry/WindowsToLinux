package gold.debug.windowstolinux.shared.standard.analyze.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Common bounded service metadata returned by one language inspector. / 单个语言检查器返回的公共有界服务元数据。
 *
 * @param primaryMetadata primary metadata / 主元数据
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
 * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
 * @param missingFiles missing files / 缺失文件集合
 */
public record ServiceProjectFacts(String primaryMetadata, String version, String artifactName, String entrypoint,
        List<String> missingFiles) {
    /**
     * Pattern recognizing SAFE NAME.
     * <p>用于识别安全名称的匹配模式。
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /**
     * Preserves immutable missing-file evidence and rejects unsafe artifact names. / 保留不可变缺失文件证据并拒绝不安全的制品名称。
     *
     * @param primaryMetadata primary metadata / 主元数据
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param missingFiles missing files / 缺失文件集合
     */
    public ServiceProjectFacts {
        if (artifactName != null && !SAFE_NAME.matcher(artifactName).matches()) {
            artifactName = null;
        }
        missingFiles = List.copyOf(missingFiles);
    }
}
