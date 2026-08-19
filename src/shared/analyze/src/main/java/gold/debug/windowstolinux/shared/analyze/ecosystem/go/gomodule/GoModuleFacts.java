package gold.debug.windowstolinux.shared.analyze.ecosystem.go.gomodule;

import java.util.List;

/** Fixed Go module metadata used by service analysis. / 服务分析使用的固定 Go Module 元数据。 */
record GoModuleFacts(String version, List<String> missingFiles) {
    GoModuleFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
