package gold.debug.windowstolinux.shared.analyze.ecosystem.dotnet.dotnetsdk;

import gold.debug.windowstolinux.shared.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Inspects one locked .NET Web SDK service without executing dotnet. / 在不执行 dotnet 的情况下检查一个锁定的 .NET Web SDK 服务。 */
public final class DotNetSdkDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern DOTNET_VERSION = Pattern.compile(
            "[\"']version[\"']\\s*:\\s*[\"']([0-9]+(?:\\.[0-9]+){0,2}(?:[-+][A-Za-z0-9._-]+)?)[\"']");
    private static final Pattern DOTNET_WEB_SDK = Pattern.compile(
            "<Project\\s+Sdk\\s*=\\s*[\"']Microsoft\\.NET\\.Sdk\\.Web[\"']", Pattern.CASE_INSENSITIVE);

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.DOTNET_SERVICE;
    }

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
                ? projects.getFirst().getFileName().toString().replaceFirst("\\.csproj$", "") : null;
        if (projects.size() == 1
                && !DOTNET_WEB_SDK.matcher(ServiceMetadataInspector.readIfPresent(projects.getFirst())).find()) {
            missing = ServiceMetadataInspector.append(missing, "Microsoft.NET.Sdk.Web");
        }
        if (!ServiceMetadataInspector.present(root, "Program.cs")) {
            missing = ServiceMetadataInspector.append(missing, "Program.cs");
        }
        DotNetSdkFacts facts = new DotNetSdkFacts(
                ServiceMetadataInspector.match(ServiceMetadataInspector.readIfPresent(root.resolve("global.json")),
                        DOTNET_VERSION), artifact,
                artifact == null || !ServiceMetadataInspector.present(root, "Program.cs")
                        ? null : artifact + ".dll", missing);
        ServiceProjectFacts shape = new ServiceProjectFacts("global.json", facts.version(), facts.artifactName(),
                facts.entrypoint(), facts.missingFiles());
        return ServiceInspectionAssembler.assemble(root, projectType(), DeploymentBuildToolType.DOTNET_LOCKED,
                languageFacts, shape, false);
    }
}
