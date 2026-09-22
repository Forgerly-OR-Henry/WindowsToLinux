package gold.debug.windowstolinux.shared.model.managed;

import java.util.List;

import gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload;

/**
 * Projects shared desktop/Web delivery information; classification never comes from an edited URL.
 * <p>投影桌面及 Web 共享交付信息；分类绝不来自编辑后的 URL。
 *
 * @param category category / 类别
 * @param mode selected operating or storage mode / 所选运行或存储模式
 * @param reviewed reviewed / 已审阅
 * @param lifecycle lifecycle / 生命周期
 * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
 * @param endpoints endpoints / 端点集合
 */
public record ApplicationUsage(String category, ApplicationWorkload.ExecutionMode mode, boolean reviewed,
        boolean lifecycle, String command, List<String> endpoints) {
    /**
     * Reconstructs this typed contract from the supplied source representation.
     * <p>根据所提供的源表示重建当前类型化契约。
     *
     * @param app app / 应用
     * @param workload reviewed application execution and resource contract / 已审阅应用执行及资源契约
     * @return constructed or resolved application usage / 构造或解析得到的应用用法
     */
    public static ApplicationUsage from(ManagedApplication app, ApplicationWorkload workload) {
        if (!workload.reviewed())
            return new ApplicationUsage("UNKNOWN", workload.mode(), false, false, "", List.of());
        boolean daemon = workload.mode() == ApplicationWorkload.ExecutionMode.DAEMON;
        String helper = "sudo /usr/local/lib/windowstolinux/managed-helper ";
        String command = daemon && workload.client().isEmpty()
                ? helper + "lifecycle " + app.id() + " start " + app.ownershipManifestSha256()
                : helper + (daemon ? "app-client " : "app-run ") + app.id() + " " + app.ownershipManifestSha256()
                        + " --";
        String host = app.server().host().contains(":") ? "[" + app.server().host() + "]" : app.server().host();
        var endpoints = workload.endpoints().stream().filter(endpoint -> endpoint
                .exposure() == gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ExposureType.EXTERNAL)
                .map(endpoint -> endpoint.accessUrl().isEmpty()
                        ? endpoint.protocol().name().toLowerCase(java.util.Locale.ROOT) + "://" + host + ":"
                                + endpoint.hostPort()
                        : endpoint.accessUrl())
                .toList();
        return new ApplicationUsage(workload.category().name(), workload.mode(), true, daemon, command, endpoints);
    }
}
