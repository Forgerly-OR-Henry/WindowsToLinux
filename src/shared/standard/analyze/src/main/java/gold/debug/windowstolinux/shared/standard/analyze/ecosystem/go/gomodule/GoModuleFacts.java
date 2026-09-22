package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.go.gomodule;

import java.util.List;

/**
 * Fixed Go module metadata used by service analysis. / 服务分析使用的固定 Go Module 元数据。
 *
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param missingFiles missing files / 缺失文件集合
 */
record GoModuleFacts(String version, List<String> missingFiles) {
    /**
     * Binds the supplied dependencies and state for go module facts.
     * <p>为Go模块事实绑定传入的依赖及状态。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param missingFiles missing files / 缺失文件集合
     */
    GoModuleFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
