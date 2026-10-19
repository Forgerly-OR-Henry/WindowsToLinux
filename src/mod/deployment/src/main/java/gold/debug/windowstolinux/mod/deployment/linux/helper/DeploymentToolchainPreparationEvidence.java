package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.util.Map;

/** Single literal decoder for preparation evidence; never a grant or a live installation check. */
public final class DeploymentToolchainPreparationEvidence {
    private DeploymentToolchainPreparationEvidence() {
    }

    /** Labels exact literal arguments without asserting resolver results, installation trust or authority. */
    public static java.util.Optional<Map<String, Object>> decode(String script, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + layout.helperPath() + "' prepare-toolchains ")
            + "'prepare' '([A-Z]+)' '([0-9]{1,3}(?:\\.[0-9]{1,3})?)' '([^'\\r\\n]+)' '([1-9][0-9]{1,3})' '(-|/[^'\\r\\n]*)'\\z").matcher(script);
        if (!match.matches()) return java.util.Optional.empty();
        gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType ecosystem;
        try {
            ecosystem = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.valueOf(match.group(1));
        } catch (IllegalArgumentException invalid) {
            return java.util.Optional.empty();
        }
        String branch = match.group(2), exact = match.group(3);
        int seconds = Integer.parseInt(match.group(4));
        if (seconds < 30 || seconds > 7200 || match.group(5).length() > 2048
            || !(exact.equals("-") || gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion.parse(ecosystem, exact)
            .filter(version -> version.text().equals(exact) && !version.preview() && version.branch().equals(branch)).isPresent()))
            return java.util.Optional.empty();
        return java.util.Optional.of(Map.of("subcommand", "prepare", "ecosystem", ecosystem.name(), "catalogBranch", branch,
            "requestedExact", exact, "preparationTimeoutSeconds", seconds, "javaHome", match.group(5),
            "literalArgumentOrder", java.util.List.of("prepare", "ecosystem", "catalogBranch", "requestedExact", "preparationTimeoutSeconds", "javaHome"),
            "meaning", "Exact literal argument decoding only; branch and timeout are separate arguments. Official version/source/digest are resolved inside the pinned helper. Not live installation evidence, a safety verdict or a command grant."));
    }

}
