package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;

/** Fixed Agent bootstrap prerequisites inside the unchanged shared files-installation envelope. */
public final class DeploymentAgentHelperInstallerScript {
    public static final String LAUNCHER = "exec /usr/bin/env -i LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --noprofile --norc -p -s";

    private static final String DELIMITER = "WTL_AGENT_HELPER_DEPENDENCIES";

    private static final Pattern FRAME = Pattern.compile("(?m)^  /usr/bin/python3 -I -S - 'HELPER_RUNTIME' '([a-f0-9]{32})' '7200' '([A-Za-z0-9][A-Za-z0-9._:-]{0,127})' '([a-f0-9-]{36})' <<'"
        + DELIMITER + "'\\n");

    private DeploymentAgentHelperInstallerScript() {
    }

    /** Renders only current fixed prerequisite bytes for one original operation, never model input. */
    public static String prerequisites(String operation, DeploymentRemoteLayout layout, ServerId server, String task) {
        requireOperation(operation);
        var frame = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.frame("HELPER_RUNTIME", operation, 7200, server, task);
        return "  files_trusted_runtime_path /usr/bin/python3 || { printf 'PREPARE_REJECT=agent-bootstrap-python-required\\n'; exit 64; }\n"
            + "  /usr/bin/python3 -I -S - " + frame.stream().map(value -> "'" + value + "'").collect(java.util.stream.Collectors.joining(" ")) + " <<'" + DELIMITER + "'\n"
            + DeploymentAgentToolchainPrograms.dependencies(layout) + "\n" + DELIMITER + "\n";
    }

    /** Reuses the complete shared installation and checks with fixed guarded Agent prerequisites. */
    public static String render(String operation, DeploymentRemoteLayout layout, ServerId server, String task) {
        return DeploymentHelperInstallerScript.renderFiles(prerequisites(operation, layout, server, task), layout);
    }

    /** Accepts an operation only when every supplied installer byte matches its canonical rendering. */
    public static String parseOperation(String script) {
        return parseOperation(script, DeploymentRemoteLayout.original());
    }

    public static String parseOperation(String script, DeploymentRemoteLayout layout) {
        return parseFrame(script, layout).get(1);
    }

    public static java.util.List<String> parseFrame(String script, DeploymentRemoteLayout layout) {
        if (script == null || script.length() > 2 * 1024 * 1024)
            throw new IllegalArgumentException("Agent helper installer frame changed");
        var matcher = FRAME.matcher(script);
        if (!matcher.find())
            throw new IllegalArgumentException("Agent helper installer operation missing");
        String operation = matcher.group(1);
        var frame = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.frame("HELPER_RUNTIME", operation, 7200, new ServerId(matcher.group(2)), matcher.group(3));
        if (matcher.find() || !render(operation, layout, new ServerId(frame.get(3)), frame.get(4)).equals(script))
            throw new IllegalArgumentException("Noncanonical Agent helper installer");
        return frame;
    }

    private static void requireOperation(String operation) {
        if (operation == null || !operation.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("Invalid Agent helper installer operation");
    }
}
