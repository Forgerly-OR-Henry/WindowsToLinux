package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Parses bounded desktop runtime, database, secret-reference, and Git-reference notation. / 解析桌面端有界运行时、数据库、秘密引用与 Git 引用记法。 */
public final class DeploymentRuntimeParser {
    private DeploymentRuntimeParser() { }

    /** Database scopes the user can explicitly review in the current deployment form. / 用户可在当前部署表单中显式审阅的数据库范围。 */
    public enum DatabaseReviewMode {
        UNREVIEWED,
        NONE,
        POSTGRESQL,
        MYSQL,
        MARIADB
    }

    /**
     * Parses zero or one explicitly reviewed server database binding.
     *
     * <p>解析零个或一个显式审阅的服务器数据库绑定。
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
        if (values.length != 7) {
            throw new IllegalArgumentException("server database binding must contain seven fields");
        }
        List<SecretReference> passwordReferences = secrets(values[5]);
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
                Integer.parseInt(values[2].trim()), values[3].trim(), values[4].trim(),
                passwordReferences.getFirst(), tlsRequired);
        return Optional.of(List.of(new ManagedDatabaseBinding(values[0].trim(), connection)));
    }

    public static List<SecretReference> secrets(String input) {
        List<SecretReference> references = new ArrayList<>();
        for (String item : input.split(";")) {
            String value = item.trim();
            if (value.isEmpty()) continue;
            int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("secret reference must contain an identifier and revision");
            }
            references.add(new SecretReference(value.substring(0, separator).trim(),
                    Long.parseLong(value.substring(separator + 1).trim())));
        }
        return List.copyOf(references);
    }

    public static Map<Integer, Integer> ports(String input) {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (String pair : input.split(";")) {
            String value = pair.trim();
            if (value.isEmpty()) continue;
            String[] items = value.split(":", -1);
            if (items.length != 2) throw new IllegalArgumentException("container port must be host:container");
            Integer previous = values.put(Integer.parseInt(items[0].trim()), Integer.parseInt(items[1].trim()));
            if (previous != null) throw new IllegalArgumentException("container host ports must be unique");
        }
        return Map.copyOf(values);
    }

    public static List<DeploymentRuntimeSpecification.ManagedVolume> volumes(String input) {
        if (input.isBlank()) return List.of();
        List<DeploymentRuntimeSpecification.ManagedVolume> values = new ArrayList<>();
        for (String entry : input.split(";")) {
            String[] items = entry.trim().split(":", -1);
            if (items.length < 2 || items.length > 3) throw new IllegalArgumentException("container volume is invalid");
            boolean readOnly = items.length == 3 && items[2].trim().equalsIgnoreCase("ro");
            if (items.length == 3 && !readOnly && !items[2].trim().equalsIgnoreCase("rw")) {
                throw new IllegalArgumentException("container volume mode must be ro or rw");
            }
            values.add(new DeploymentRuntimeSpecification.ManagedVolume(items[0].trim(), items[1].trim(), readOnly));
        }
        return List.copyOf(values);
    }

    public static List<String> arguments(String input) {
        return input.isBlank() ? List.of() : List.of(input.trim().split("\\s+"));
    }

    public static DeploymentRuntimeSpecification service(DeploymentProjectType selected, String version,
                                                   String artifact, String entrypoint, HealthCheck health) {
        return switch (selected) {
            case GO_SERVICE -> new DeploymentRuntimeSpecification.GoService(version, artifact, entrypoint, health);
            case RUST_SERVICE -> new DeploymentRuntimeSpecification.RustService(version, artifact, entrypoint, health);
            case DOTNET_SERVICE -> new DeploymentRuntimeSpecification.DotNetService(version, artifact, entrypoint, health);
            case KOTLIN_SERVICE -> new DeploymentRuntimeSpecification.KotlinService(version, artifact, entrypoint, health);
            case PHP_SERVICE -> new DeploymentRuntimeSpecification.PhpService(version, artifact, entrypoint, healthPort(health), health);
            case RUBY_SERVICE -> new DeploymentRuntimeSpecification.RubyService(version, artifact, entrypoint, healthPort(health), health);
            default -> throw new IllegalArgumentException("selected project type is not an ecosystem service");
        };
    }

    private static int healthPort(HealthCheck health) {
        if (health instanceof HealthCheck.Tcp tcp) return tcp.port();
        HealthCheck.Http http = (HealthCheck.Http) health;
        int port = http.endpoint().getPort();
        if (port > 0) return port;
        return "https".equalsIgnoreCase(http.endpoint().getScheme()) ? 443 : 80;
    }

    public static GitReference gitReference(int index, String value) {
        return switch (index) {
            case 0 -> new GitReference.Branch(value);
            case 1 -> new GitReference.Tag(value);
            case 2 -> new GitReference.Commit(value);
            default -> throw new IllegalArgumentException("unsupported Git reference kind");
        };
    }
}
