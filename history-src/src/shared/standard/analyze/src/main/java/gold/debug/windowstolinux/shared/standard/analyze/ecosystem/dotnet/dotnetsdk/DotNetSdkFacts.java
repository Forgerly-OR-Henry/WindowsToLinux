package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.dotnet.dotnetsdk;

import java.util.List;

/**
 * Fixed .NET SDK project metadata used by service analysis. / 服务分析使用的固定 .NET SDK 项目元数据。
 *
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
 * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
 * @param missingFiles missing files / 缺失文件集合
 */
record DotNetSdkFacts(String version, String artifactName, String entrypoint, List<String> missingFiles) {
    /**
     * Binds the supplied dependencies and state for dot net sdk facts.
     * <p>为DotNetSdk事实绑定传入的依赖及状态。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifactName reviewed relative name of the expected build artifact / 预期构建制品的已审阅相对名称
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param missingFiles missing files / 缺失文件集合
     */
    DotNetSdkFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
