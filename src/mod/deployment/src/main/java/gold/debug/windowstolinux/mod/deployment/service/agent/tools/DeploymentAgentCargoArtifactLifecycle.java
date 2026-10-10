package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;

/** Finishes only the Agent's selected fixed Cargo recipe before its independent artifact seal. / 仅在独立制品封存前收尾 Agent 所选固定 Cargo 配方。 */
final class DeploymentAgentCargoArtifactLifecycle {
    private final String directory;

    private final String artifact;

    private DeploymentAgentCargoArtifactLifecycle(String directory, String artifact) {
        this.directory = directory;
        this.artifact = artifact;
    }

    static Optional<DeploymentAgentCargoArtifactLifecycle> forRecipe(DeploymentProjectFacts facts, DeploymentRuntimeDefinition runtime) {
        if (facts.projectType() != DeploymentProjectKind.RUST_SERVICE || facts.buildTool() != DeploymentBuildToolType.CARGO_LOCKED)
            return Optional.empty();
        if (!(runtime instanceof DeploymentRuntimeDefinition.RustService rust)) throw rejected();
        var lifecycle = new DeploymentAgentCargoArtifactLifecycle(facts.buildDirectory(), rust.artifactName());
        var workload = runtime.workload();
        lifecycle.checkPath(workload.workingDirectory(), "");
        lifecycle.checkCommand(workload.command(), workload.workingDirectory());
        workload.verification().ifPresent(command -> lifecycle.checkCommand(command, workload.workingDirectory()));
        workload.client().ifPresent(command -> lifecycle.checkCommand(command, workload.workingDirectory()));
        workload.workers().forEach(worker -> lifecycle.checkCommand(worker.command(), workload.workingDirectory()));
        workload.companions().forEach(companion -> lifecycle.checkPath(companion.artifactPath(), ""));
        if (runtime.healthCheck() instanceof DeploymentHealthCheck.Command health)
            lifecycle.checkCommand(health.command(), workload.workingDirectory());
        return Optional.of(lifecycle);
    }

    void validateSource(Path snapshot, String sourceRoot) throws IOException {
        Path root = snapshot.toAbsolutePath().normalize();
        Path component = root.resolve(sourceRoot).normalize();
        Path build = component.resolve(directory).normalize();
        if (!component.startsWith(root) || !build.startsWith(component)) throw rejected();
        requireDirectories(root, build);
        for (Path target : List.of(component.resolve("target"), build.resolve("target")))
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw rejected();
    }

    void validateReferences(DeploymentAgentPlan.Part part) {
        // Logical managed-data destinations are independent storage; source seed files still need artifact content.
        part.resources().fileBindings().forEach(binding -> checkPath(binding.seedFile(), ""));
        for (var config : part.configuration())
            if (config.value() instanceof DeploymentConfigValue.Text text && text.value().contains("/"))
                checkPath(text.value(), part.runtime().workload().workingDirectory());
    }

    Map<String, Object> semantics() {
        return Map.of("semanticsOnly", true, "sealScope", "ENTIRE_WTL_OUTPUT",
            "runtimeArtifactRelativePath", relative(".w2l/bin/" + artifact),
            "temporaryBuildTreeRelativePath", relative("target"),
            "cleanupPolicy", "Only this recipe target is removed after successful build, private executable copy and matching SHA-256; this describes the recipe, not observed artifact existence or sealing.");
    }

    String before(DeploymentRemoteWorkspace workspace, String sourceRoot) {
        String source = workspace.candidateRoot() + "/mutable/input-source";
        String output = workspace.candidateRoot() + "/mutable/source";
        return "\nset -euo pipefail\n"
            + "[ \"$WTL_SOURCE\" = " + quote(source) + " ] && [ \"$WTL_OUTPUT\" = " + quote(output) + " ] || exit 64\n"
            + directoryGuard()
            + "agent_cargo_component=\"$WTL_SOURCE\"" + suffix(sourceRoot.equals(".") ? "" : sourceRoot) + "\n"
            + "agent_cargo_input=\"$agent_cargo_component\"" + suffix(directory) + "\n"
            + "agent_cargo_directory_chain \"$WTL_SOURCE\" \"$agent_cargo_input\" || exit 64\n"
            + "[ ! -e \"$agent_cargo_component/target\" ] && [ ! -L \"$agent_cargo_component/target\" ] || exit 64\n"
            + "[ ! -e \"$agent_cargo_input/target\" ] && [ ! -L \"$agent_cargo_input/target\" ] || exit 64\n";
    }

    String after(DeploymentRemoteWorkspace workspace) {
        String output = workspace.candidateRoot() + "/mutable/source";
        return "\n# Cargo target belongs only to this successful fixed recipe; runtime uses its private copied artifact.\n"
            + "[ \"$WTL_OUTPUT\" = " + quote(output) + " ] && [ \"$source\" = \"$WTL_OUTPUT\" ] || exit 64\n"
            + "agent_cargo_build=\"$WTL_OUTPUT\"" + suffix(directory) + "\n"
            + "agent_cargo_target=\"$agent_cargo_build/target\"\n"
            + "agent_cargo_original=\"$agent_cargo_target/release/\"" + quote(artifact) + "\n"
            + "agent_cargo_retained=\"$agent_cargo_build/.w2l/bin/\"" + quote(artifact) + "\n"
            + "agent_cargo_directory_chain \"$WTL_OUTPUT\" \"$agent_cargo_build/.w2l/bin\" || exit 64\n"
            + "agent_cargo_directory_chain \"$WTL_OUTPUT\" \"$agent_cargo_target/release\" || exit 64\n"
            + """
            for agent_cargo_tool in /usr/bin/stat /usr/bin/sha256sum /usr/bin/find /usr/bin/rm; do
              verify_build_command "$agent_cargo_tool" || exit 64
            done
            [ -f "$agent_cargo_original" ] && [ ! -L "$agent_cargo_original" ] && [ -x "$agent_cargo_original" ] || exit 64
            [ -f "$agent_cargo_retained" ] && [ ! -L "$agent_cargo_retained" ] && [ -x "$agent_cargo_retained" ] || exit 64
            [ "$(/usr/bin/stat -c '%h' -- "$agent_cargo_retained")" = 1 ] || exit 64
            agent_cargo_original_sha="$(/usr/bin/sha256sum -- "$agent_cargo_original")"
            agent_cargo_original_sha="${agent_cargo_original_sha%% *}"
            agent_cargo_retained_sha="$(/usr/bin/sha256sum -- "$agent_cargo_retained")"
            [ "$agent_cargo_original_sha" = "${agent_cargo_retained_sha%% *}" ] || exit 64
            agent_cargo_invalid="$(/usr/bin/find "$agent_cargo_target" -xdev ! -type f ! -type d -print -quit)"
            [ -z "$agent_cargo_invalid" ] || exit 64
            # Recheck the exact directory chain immediately before bounded removal; never clean elsewhere in WTL_OUTPUT.
            agent_cargo_directory_chain "$WTL_OUTPUT" "$agent_cargo_target" || exit 64
            /usr/bin/rm -rf --one-file-system -- "$agent_cargo_target"
            [ ! -e "$agent_cargo_target" ] && [ ! -L "$agent_cargo_target" ] || exit 64
            [ -f "$agent_cargo_retained" ] && [ ! -L "$agent_cargo_retained" ] && [ -x "$agent_cargo_retained" ] || exit 64
            [ "$(/usr/bin/stat -c '%h' -- "$agent_cargo_retained")" = 1 ] || exit 64
            agent_cargo_retained_sha="$(/usr/bin/sha256sum -- "$agent_cargo_retained")"
            [ "$agent_cargo_original_sha" = "${agent_cargo_retained_sha%% *}" ] || exit 64
            printf 'AGENT_CARGO_TARGET_REMOVED=1\\n'
            """;
    }

    private static String directoryGuard() {
        return """
            agent_cargo_directory_chain() {
              local root="$1" path="$2" parent
              [ -d "$root" ] && [ ! -L "$root" ] || return 1
              [ "$(/usr/bin/readlink -f -- "$root")" = "$root" ] || return 1
              case "$path" in "$root"|"$root"/*) ;; *) return 1 ;; esac
              parent="$path"
              while true; do
                [ -d "$parent" ] && [ ! -L "$parent" ] || return 1
                [ "$parent" != "$root" ] || break
                parent="${parent%/*}"
              done
            }
            """;
    }

    private void checkCommand(DeploymentCommand command, String workingDirectory) {
        checkPath(command.entrypoint(), "");
        for (String argument : command.arguments()) {
            for (String value : argument.split("[=\\s]+")) checkPath(value, workingDirectory);
        }
    }

    private void checkPath(String value, String workingDirectory) {
        if (value.isEmpty()) return;
        String path = value.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        String target = relative("target");
        var parts = new ArrayDeque<String>();
        for (String part : (workingDirectory + "/" + path).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else parts.addLast(part);
        }
        String resolved = String.join("/", parts);
        if (path.equals(target) || path.startsWith(target + "/") || resolved.equals(target) || resolved.startsWith(target + "/"))
            throw rejected();
    }

    private static void requireDirectories(Path root, Path directory) throws IOException {
        Path current = root;
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) throw rejected();
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current))
                throw rejected();
        }
    }

    private String relative(String path) {
        return directory.isEmpty() ? path : directory + "/" + path;
    }

    private static String suffix(String path) {
        return path.isEmpty() ? "" : "/" + quote(path);
    }

    private static String quote(String value) {
        return SafeBuildScriptEnvelope.shellQuote(value);
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("CAPABILITY_NOT_APPLICABLE: shared Agent Cargo recipe reserves target as a temporary build tree; frozen source target or runtime/health references require an independently reviewed custom command");
    }
}
