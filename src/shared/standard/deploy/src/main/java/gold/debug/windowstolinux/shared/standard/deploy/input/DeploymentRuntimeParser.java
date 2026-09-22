package gold.debug.windowstolinux.shared.standard.deploy.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Parses bounded desktop runtime, database, secret-reference, and Git-reference notation. / 解析桌面端有界运行时、数据库、秘密引用与 Git 引用记法。
 */
public final class DeploymentRuntimeParser {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentRuntimeParser() {
    }

    /**
     * Parses zero or one explicitly reviewed server database binding.
     *
     *  <p>解析零个或一个显式审阅的服务器数据库绑定。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static Optional<List<ManagedDatabaseBinding>> databaseBindings(DatabaseReviewMode mode, String input) {
        mode = Objects.requireNonNull(mode, "mode");
        input = Objects.requireNonNull(input, "input").trim();
        if (input.length() > 2048 || input.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("database binding input is invalid");
        }
        if (mode == DatabaseReviewMode.UNREVIEWED) {
            throw new IllegalArgumentException("database scope must be explicitly reviewed");
        }
        if (mode == DatabaseReviewMode.NONE) {
            return Optional.of(List.of());
        }
        String[] values = input.split("\\|", -1);
        if (mode == DatabaseReviewMode.SQLITE) {
            if (values.length != 2)
                throw new IllegalArgumentException(
                        "SQLite binding requires database-id|application-file-path; default paths use the DB declaration with a path environment variable");
            var file = gold.debug.windowstolinux.shared.model.ecosystem.db.SqliteFileRequirement
                    .fromPath(values[1].trim(), "", "");
            if (file.accessPath().isEmpty())
                throw new IllegalArgumentException(
                        "SQLite default paths require a reviewed application path input in windowstolinux-db.properties");
            return Optional.of(List.of(new ManagedDatabaseBinding(values[0].trim(),
                    new ManagedDatabaseConnection.Sqlite(file.fileName(), file.location(), file.accessPath()))));
        }
        if (values.length != 7) {
            throw new IllegalArgumentException("server database binding must contain seven fields");
        }
        List<SecretReference> passwordReferences = gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser
                .secrets(values[5]);
        if (passwordReferences.size() != 1) {
            throw new IllegalArgumentException("server database binding requires one password reference");
        }
        boolean tlsRequired = switch (values[6].trim().toLowerCase(java.util.Locale.ROOT)) {
            case "required" -> true;
            case "optional" -> false;
            default -> throw new IllegalArgumentException("database TLS mode must be required or optional");
        };
        ManagedDatabaseEngineType engine = ManagedDatabaseEngineType.valueOf(mode.name());
        ManagedDatabaseConnection connection = new ManagedDatabaseConnection.Server(engine, values[1].trim(),
                Integer.parseInt(values[2].trim()), values[3].trim(), values[4].trim(), passwordReferences.getFirst(),
                tlsRequired);
        return Optional.of(List.of(new ManagedDatabaseBinding(values[0].trim(), connection)));
    }

    /**
     * Parses semicolon-separated host-to-container port pairs and rejects invalid mappings.
     * <p>解析分号分隔的主机到容器端口对，并拒绝无效映射。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return semicolon-separated host-to-container port pairs and rejects invalid mappings / 分号分隔的主机到容器端口对，并拒绝无效映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static Map<Integer, Integer> ports(String input) {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (String pair : input.split(";")) {
            String value = pair.trim();
            if (value.isEmpty())
                continue;
            String[] items = value.split(":", -1);
            if (items.length != 2)
                throw new IllegalArgumentException("container port must be host:container");
            Integer previous = values.put(Integer.parseInt(items[0].trim()), Integer.parseInt(items[1].trim()));
            if (previous != null)
                throw new IllegalArgumentException("container host ports must be unique");
        }
        return Map.copyOf(values);
    }

    /**
     * Checks volumes syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查卷集合语法及边界。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static List<DeploymentRuntimeSpecification.ManagedVolume> volumes(String input) {
        if (input.isBlank())
            return List.of();
        List<DeploymentRuntimeSpecification.ManagedVolume> values = new ArrayList<>();
        for (String entry : input.split(";")) {
            String[] items = entry.trim().split(":", -1);
            if (items.length < 2 || items.length > 3)
                throw new IllegalArgumentException("container volume is invalid");
            boolean readOnly = items.length == 3 && items[2].trim().equalsIgnoreCase("ro");
            if (items.length == 3 && !readOnly && !items[2].trim().equalsIgnoreCase("rw")) {
                throw new IllegalArgumentException("container volume mode must be ro or rw");
            }
            values.add(new DeploymentRuntimeSpecification.ManagedVolume(items[0].trim(), items[1].trim(), readOnly));
        }
        return List.copyOf(values);
    }

    /**
     * Splits a nonblank argument string on whitespace; blank input produces an empty list.
     * <p>按空白字符拆分非空参数字符串；空白输入生成空列表。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    public static List<String> arguments(String input) {
        return input.isBlank() ? List.of() : List.of(input.trim().split("\\s+"));
    }

    /**
     * Constructs the selected language-service runtime from its reviewed version, artifact, entrypoint and health check.
     * <p>根据已审阅版本、制品、入口及健康检查构建所选语言服务运行规格。
     *
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @param health health / 健康
     * @return the selected language-service runtime from its reviewed version, artifact, entrypoint and health check / 根据已审阅版本、制品、入口及健康检查构建所选语言服务运行规格
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static DeploymentRuntimeSpecification service(DeploymentProjectType selected, String version,
            String artifact, String entrypoint, HealthCheck health) {
        return switch (selected) {
            case GO_SERVICE -> new DeploymentRuntimeSpecification.GoService(version, artifact, entrypoint, health);
            case RUST_SERVICE -> new DeploymentRuntimeSpecification.RustService(version, artifact, entrypoint, health);
            case DOTNET_SERVICE ->
                new DeploymentRuntimeSpecification.DotNetService(version, artifact, entrypoint, health);
            case KOTLIN_SERVICE ->
                new DeploymentRuntimeSpecification.KotlinService(version, artifact, entrypoint, health);
            case PHP_SERVICE -> new DeploymentRuntimeSpecification.PhpService(version, artifact, entrypoint,
                    healthPort(health), health);
            case RUBY_SERVICE -> new DeploymentRuntimeSpecification.RubyService(version, artifact, entrypoint,
                    healthPort(health), health);
            default -> throw new IllegalArgumentException("selected project type is not an ecosystem service");
        };
    }

    /**
     * Returns the health check's port, using zero when that check has no port.
     * <p>返回健康检查端口；该检查没有端口时使用零。
     *
     * @param health health / 健康
     * @return the health check's port, using zero when that check has no port / 健康检查端口；该检查没有端口时使用零
     */
    private static int healthPort(HealthCheck health) {
        return health.portNumber().orElse(0);
    }

    /**
     * Maps the selected index to a Git branch, tag or commit reference and rejects unsupported choices.
     * <p>将所选索引映射为 Git 分支、标签或提交引用，并拒绝不支持的选择。
     *
     * @param index index / 索引
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved git reference / 构造或解析得到的Git引用
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static GitReference gitReference(int index, String value) {
        return switch (index) {
            case 0 -> new GitReference.Branch(value);
            case 1 -> new GitReference.Tag(value);
            case 2 -> new GitReference.Commit(value);
            default -> throw new IllegalArgumentException("unsupported Git reference kind");
        };
    }
}
