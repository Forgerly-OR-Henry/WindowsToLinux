package gold.debug.windowstolinux.shared.model.project;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fixed runtime families for the six advanced experimental adapters.
 *
 * <p>六个高级试验适配器的固定运行时族。
 */
public enum AdvancedRuntimeKind {
    /** Go 1.22 through 1.24 compiled service. / Go 1.22 至 1.24 编译服务。 */
    GO(DeploymentProjectType.GO_SERVICE, "1\\.(?:22|23|24)", false),
    /** Rust stable toolchain identified by a six-week release number. / 由六周发布版本标识的 Rust stable 工具链。 */
    RUST(DeploymentProjectType.RUST_SERVICE, "1\\.(?:7[5-9]|8[0-9]|9[0-9])(?:\\.[0-9]+)?", false),
    /** .NET 8 or 9 SDK service. / .NET 8 或 9 SDK 服务。 */
    DOTNET(DeploymentProjectType.DOTNET_SERVICE, "(?:8|9)\\.0(?:\\.[0-9]+)?", false),
    /** Kotlin/JVM application pinned to Java 21. / 固定到 Java 21 的 Kotlin/JVM 应用。 */
    KOTLIN(DeploymentProjectType.KOTLIN_SERVICE, "21", false),
    /** PHP 8.2 through 8.4 built-in HTTP test service. / PHP 8.2 至 8.4 内置 HTTP 测试服务。 */
    PHP(DeploymentProjectType.PHP_SERVICE, "8\\.(?:2|3|4)", true),
    /** Ruby 3.2 through 3.4 Rack service. / Ruby 3.2 至 3.4 Rack 服务。 */
    RUBY(DeploymentProjectType.RUBY_SERVICE, "3\\.(?:2|3|4)(?:\\.[0-9]+)?", true);

    private final DeploymentProjectType projectType;
    private final Pattern versionPattern;
    private final boolean requiresServicePort;

    AdvancedRuntimeKind(DeploymentProjectType projectType, String versionPattern, boolean requiresServicePort) {
        this.projectType = Objects.requireNonNull(projectType, "projectType");
        this.versionPattern = Pattern.compile(versionPattern);
        this.requiresServicePort = requiresServicePort;
    }

    /** Returns the only matching project type. / 返回唯一匹配的项目类型。 */
    public DeploymentProjectType projectType() {
        return projectType;
    }

    /** Returns whether the reviewed version belongs to this bounded family. / 返回经审阅版本是否属于此有界族。 */
    public boolean acceptsVersion(String version) {
        return version != null && versionPattern.matcher(version).matches();
    }

    /** Returns whether the fixed runtime command requires an explicit port. / 返回固定运行命令是否需要显式端口。 */
    public boolean requiresServicePort() {
        return requiresServicePort;
    }

    /** Returns the kind for exactly one advanced project type. / 返回恰好一个高级项目类型的运行时种类。 */
    public static AdvancedRuntimeKind forProjectType(DeploymentProjectType projectType) {
        for (AdvancedRuntimeKind kind : values()) {
            if (kind.projectType == projectType) {
                return kind;
            }
        }
        throw new IllegalArgumentException("project type does not use an advanced runtime");
    }
}
