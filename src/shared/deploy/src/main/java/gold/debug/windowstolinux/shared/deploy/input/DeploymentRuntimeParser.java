package gold.debug.windowstolinux.shared.deploy.input;

import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;

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
        if (mode == DatabaseReviewMode.SQLITE) {
            if (values.length != 2) throw new IllegalArgumentException("SQLite binding requires database-id|application-file-path; default paths use the DB declaration with a path environment variable");
            var file = gold.debug.windowstolinux.shared.model.ecosystem.db.SqliteFileRequirement.fromPath(values[1].trim(), "", "");
            if (file.accessPath().isEmpty()) throw new IllegalArgumentException("SQLite default paths require a reviewed application path input in windowstolinux-db.properties");
            return Optional.of(List.of(new ManagedDatabaseBinding(values[0].trim(), new ManagedDatabaseConnection.Sqlite(file.fileName(),file.location(),file.accessPath()))));
        }
        if (values.length != 7) {
            throw new IllegalArgumentException("server database binding must contain seven fields");
        }
        List<SecretReference> passwordReferences = gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.secrets(values[5]);
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
        return health.portNumber().orElse(0);
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
