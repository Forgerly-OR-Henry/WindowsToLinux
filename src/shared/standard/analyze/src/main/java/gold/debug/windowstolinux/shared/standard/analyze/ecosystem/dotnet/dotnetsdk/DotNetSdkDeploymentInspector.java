package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.dotnet.dotnetsdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Inspects one locked .NET Web SDK service without executing dotnet. / 在不执行 dotnet 的情况下检查一个锁定的 .NET Web SDK 服务。
 */
public final class DotNetSdkDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Pattern recognizing DOTNET VERSION.
     * <p>用于识别DOTNET版本的匹配模式。
     */
    private static final Pattern DOTNET_VERSION = Pattern
            .compile("[\"']version[\"']\\s*:\\s*[\"']([0-9]+(?:\\.[0-9]+){0,2}(?:[-+][A-Za-z0-9._-]+)?)[\"']");

    /**
     * Pattern recognizing .NET Web SDK declaration.
     * <p>用于识别.NET Web SDK 声明的匹配模式。
     */
    private static final Pattern DOTNET_WEB_SDK = Pattern
            .compile("<Project\\s+Sdk\\s*=\\s*[\"']Microsoft\\.NET\\.Sdk\\.Web[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.DOTNET_SERVICE;
    }

    /**
     * Checks .NET project and SDK declarations against the supported service architecture and records rejected or unresolved inputs.
     * <p>根据受支持服务架构检查 .NET 项目及 SDK 声明，并记录被拒绝或未解决输入。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections) throws IOException {
        List<String> missing = ServiceMetadataInspector.missing(root, "global.json", "packages.lock.json");
        List<Path> projects;
        try (var stream = Files.list(root)) {
            projects = stream.filter(path -> path.getFileName().toString().endsWith(".csproj"))
                    .filter(BoundedMetadataInspector::regular).limit(2).toList();
        }
        if (projects.size() != 1) {
            missing = ServiceMetadataInspector.append(missing, "exactly-one-root-csproj");
        }
        String artifact = projects.size() == 1
                ? projects.getFirst().getFileName().toString().replaceFirst("\\.csproj$", "")
                : null;
        if (projects.size() == 1) {
            String project = ServiceMetadataInspector.readIfPresent(projects.getFirst());
            boolean console = Pattern
                    .compile("<Project\\s+Sdk\\s*=\\s*[\"']Microsoft\\.NET\\.Sdk[\"']", Pattern.CASE_INSENSITIVE)
                    .matcher(project).find()
                    && Pattern.compile("<OutputType>\\s*Exe\\s*</OutputType>", Pattern.CASE_INSENSITIVE)
                            .matcher(project).find();
            boolean worker = project.contains("Microsoft.NET.Sdk.Worker");
            if (!DOTNET_WEB_SDK.matcher(project).find() && !console && !worker)
                missing = ServiceMetadataInspector.append(missing,
                        "executable Microsoft.NET.Sdk, Web or Worker project");
            if (Pattern.compile("(?i)(?:net[0-9.]+-windows|<(?:UseWPF|UseWindowsForms)>\\s*true)").matcher(project)
                    .find())
                missing = ServiceMetadataInspector.append(missing, "Linux-compatible non-GUI project");
        }
        if (!ServiceMetadataInspector.present(root, "Program.cs")) {
            missing = ServiceMetadataInspector.append(missing, "Program.cs");
        }
        DotNetSdkFacts facts = new DotNetSdkFacts(
                ServiceMetadataInspector.match(ServiceMetadataInspector.readIfPresent(root.resolve("global.json")),
                        DOTNET_VERSION),
                artifact,
                artifact == null || !ServiceMetadataInspector.present(root, "Program.cs") ? null : artifact + ".dll",
                missing);
        ServiceProjectFacts shape = new ServiceProjectFacts("global.json", facts.version(), facts.artifactName(),
                facts.entrypoint(), facts.missingFiles());
        return ServiceInspectionAssembler.assemble(root, projectType(), DeploymentBuildToolType.DOTNET_LOCKED,
                languageFacts, shape, false);
    }
}
