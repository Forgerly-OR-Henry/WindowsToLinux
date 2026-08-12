package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.git.reference.GitReference;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.AdvancedRuntimeKind;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** Parses bounded desktop runtime, secret-reference, and Git-reference notation. / 解析桌面端有界运行时、秘密引用与 Git 引用记法。 */
final class DeploymentRuntimeParser {
    private DeploymentRuntimeParser() { }

    static List<SecretReference> secrets(String input) {
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

    static Map<Integer, Integer> ports(String input) {
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

    static List<DeploymentRuntimeSpecification.ManagedVolume> volumes(String input) {
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

    static List<String> arguments(String input) {
        return input.isBlank() ? List.of() : List.of(input.trim().split("\\s+"));
    }

    static DeploymentRuntimeSpecification advanced(DeploymentProjectType selected, String version,
                                                    String artifact, String entrypoint, HealthCheck health) {
        AdvancedRuntimeKind kind = AdvancedRuntimeKind.forProjectType(selected);
        OptionalInt port = kind.requiresServicePort() ? OptionalInt.of(healthPort(health)) : OptionalInt.empty();
        return new DeploymentRuntimeSpecification.AdvancedService(kind, version, artifact, entrypoint, port, health);
    }

    private static int healthPort(HealthCheck health) {
        if (health instanceof HealthCheck.Tcp tcp) return tcp.port();
        HealthCheck.Http http = (HealthCheck.Http) health;
        int port = http.endpoint().getPort();
        if (port > 0) return port;
        return "https".equalsIgnoreCase(http.endpoint().getScheme()) ? 443 : 80;
    }

    static GitReference gitReference(int index, String value) {
        return switch (index) {
            case 0 -> new GitReference.Branch(value);
            case 1 -> new GitReference.Tag(value);
            case 2 -> new GitReference.Commit(value);
            default -> throw new IllegalArgumentException("unsupported Git reference kind");
        };
    }
}
