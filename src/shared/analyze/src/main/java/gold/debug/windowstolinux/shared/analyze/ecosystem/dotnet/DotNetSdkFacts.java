package gold.debug.windowstolinux.shared.analyze.ecosystem.dotnet;

import java.util.List;

/** Fixed .NET SDK project metadata used by service analysis. / 服务分析使用的固定 .NET SDK 项目元数据。 */
record DotNetSdkFacts(String version, String artifactName, String entrypoint, List<String> missingFiles) {
    DotNetSdkFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
