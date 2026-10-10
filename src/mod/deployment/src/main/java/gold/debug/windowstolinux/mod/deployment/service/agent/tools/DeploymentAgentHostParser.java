package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType;

/** Parses exact bounded observation receipts without defaulting missing observations. / 解析精确有界观测回执，不为缺失观测提供缺省值。 */
final class DeploymentAgentHostParser {
    static final int OUTPUT_LIMIT = 4096;

    private DeploymentAgentHostParser() {
    }

    static Map<String, String> baseline(String output) {
        var values = fields(output, Set.of("FORMAT", "DISTRO_ID", "DISTRO_VARIANT", "VERSION", "ARCH", "PACKAGE_MANAGER", "PACKAGE_ARCH", "SYSTEMD"));
        require(values.get("FORMAT").equals("WTL_AGENT_BASELINE_1"));
        for (String key : Set.of("DISTRO_ID", "VERSION", "ARCH", "PACKAGE_ARCH"))
            require(values.get(key).matches("[A-Za-z0-9._-]{1,64}"));
        require(values.get("DISTRO_VARIANT").matches("[A-Za-z0-9._-]{0,64}"));
        require(Set.of("apt", "dnf", "unknown").contains(values.get("PACKAGE_MANAGER")));
        require(binary(values.get("SYSTEMD")));
        values.remove("FORMAT");
        return Map.copyOf(values);
    }

    static Map<String, String> container(String output, ContainerEngineType engine) {
        var values = fields(output, Set.of("FORMAT", "ENGINE", "CLIENT", "OPERATIONAL", "QUADLET"));
        require(values.get("FORMAT").equals("WTL_AGENT_CONTAINER_1") && values.get("ENGINE").equals(engine.name()));
        require(binary(values.get("CLIENT")) && binary(values.get("OPERATIONAL")));
        require(engine == ContainerEngineType.DOCKER ? values.get("QUADLET").equals("NOT_APPLICABLE") : binary(values.get("QUADLET")));
        if (values.get("CLIENT").equals("0"))
            require(values.get("OPERATIONAL").equals("0") && !values.get("QUADLET").equals("1"));
        return Map.of("engine", values.get("ENGINE"), "clientAvailable", values.get("CLIENT"),
            "operational", values.get("OPERATIONAL"), "quadletAvailable", values.get("QUADLET"),
            "initializationImpact", "The selected engine info/quadlet query may initialize native storage or configuration; this observation does not establish runtime admission or authorize later operations.");
    }

    private static LinkedHashMap<String, String> fields(String output, Set<String> required) {
        require(output != null && output.getBytes(StandardCharsets.UTF_8).length <= OUTPUT_LIMIT && output.endsWith("\n")
            && output.indexOf('\r') < 0 && output.indexOf('\u0000') < 0);
        var values = new LinkedHashMap<String, String>();
        String[] lines = output.split("\n", -1);
        require(lines.length == required.size() + 1);
        for (int index = 0; index < lines.length - 1; index++) {
            String line = lines[index];
            int equals = line.indexOf('=');
            require(equals > 0);
            String key = line.substring(0, equals);
            require(required.contains(key) && values.putIfAbsent(key, line.substring(equals + 1)) == null);
        }
        require(values.keySet().equals(required));
        return values;
    }

    private static boolean binary(String value) {
        return value.equals("0") || value.equals("1");
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Agent host observation receipt is incomplete or malformed");
    }
}
