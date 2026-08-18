package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Derives the immutable release identity from every input that can change a built or running release. / 从可改变构建或运行发布的每项输入推导不可变发布身份。 */
public final class ReviewedReleaseIdentity {
    private ReviewedReleaseIdentity() { }

    /** Returns a deterministic release SHA-256 covering source, configuration, secrets, and runtime. / 返回覆盖源码、配置、秘密与运行时的确定性发布 SHA-256。 */
    public static String from(ReviewedDeploymentRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "reviewed-release-v2");
            update(digest, request.sourceRevision().sourceSha256());
            update(digest, request.facts().buildTool().name());
            update(digest, request.configuration().sha256());
            request.secretReferences().stream()
                    .sorted(Comparator.comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision))
                    .forEach(reference -> {
                        update(digest, reference.identifier());
                        update(digest, Long.toString(reference.revision()));
                    });
            runtime(digest, request.runtime());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }

    private static void runtime(MessageDigest digest, DeploymentRuntimeSpecification runtime) {
        update(digest, runtime.projectType().name());
        switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot ignored -> { }
            case DeploymentRuntimeSpecification.JavaJar javaJar -> {
                update(digest, javaJar.jarRelativePath());
                update(digest, javaJar.mainClass());
                update(digest, javaJar.javaVersion());
                javaJar.jvmArguments().forEach(value -> update(digest, value));
                update(digest, "application-arguments");
                javaJar.applicationArguments().forEach(value -> update(digest, value));
            }
            case DeploymentRuntimeSpecification.NodeService node ->
                    update(digest, Integer.toString(node.nodeMajorVersion()));
            case DeploymentRuntimeSpecification.PythonService python -> {
                update(digest, python.pythonVersion());
                update(digest, python.entrypoint());
            }
            case DeploymentRuntimeSpecification.StaticSite site -> {
                update(digest, site.outputDirectory());
                update(digest, site.nodeMajorVersion().isPresent()
                        ? Integer.toString(site.nodeMajorVersion().getAsInt()) : "no-node");
            }
            case DeploymentRuntimeSpecification.Container container -> {
                update(digest, container.engine().name());
                container.publishedPorts().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(entry -> update(digest, entry.getKey() + ":" + entry.getValue()));
                container.volumes().stream().sorted(Comparator.comparing(
                                DeploymentRuntimeSpecification.ManagedVolume::name)
                                .thenComparing(DeploymentRuntimeSpecification.ManagedVolume::containerPath))
                        .forEach(volume -> update(digest, volume.name() + ":" + volume.containerPath()
                                + ":" + volume.readOnly()));
            }
            case DeploymentRuntimeSpecification.GoService service -> service(digest, "go", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.RustService service -> service(digest, "rust", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.DotNetService service -> service(digest, "dotnet", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.KotlinService service -> service(digest, "kotlin", service.version(), service.artifactName(), service.entrypoint(), null);
            case DeploymentRuntimeSpecification.PhpService service -> service(digest, "php", service.version(), service.artifactName(), service.entrypoint(), service.servicePort());
            case DeploymentRuntimeSpecification.RubyService service -> service(digest, "ruby", service.version(), service.artifactName(), service.entrypoint(), service.servicePort());
        }
        health(digest, runtime.healthCheck());
    }

    private static void service(MessageDigest digest, String ecosystem, String version, String artifact, String entrypoint,
                                Integer port) {
        update(digest, ecosystem);
        update(digest, version);
        update(digest, artifact);
        update(digest, entrypoint);
        update(digest, port == null ? "no-service-port" : Integer.toString(port));
    }

    private static void health(MessageDigest digest, HealthCheck healthCheck) {
        switch (healthCheck) {
            case HealthCheck.Http http -> {
                update(digest, "http");
                update(digest, http.endpoint().toASCIIString());
                update(digest, Integer.toString(http.expectedStatus()));
                update(digest, Integer.toString(http.timeoutSeconds()));
            }
            case HealthCheck.Tcp tcp -> {
                update(digest, "tcp");
                update(digest, Integer.toString(tcp.port()));
                update(digest, Integer.toString(tcp.timeoutSeconds()));
                update(digest, Integer.toString(tcp.stabilitySeconds()));
            }
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
