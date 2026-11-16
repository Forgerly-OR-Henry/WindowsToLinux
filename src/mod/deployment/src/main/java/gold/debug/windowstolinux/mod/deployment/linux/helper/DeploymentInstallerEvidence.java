package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.SshCommandFrameProtocol;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;

/** Lossless ordered installer evidence. No executable bytes are omitted or executed here. */
public final class DeploymentInstallerEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLAIN_FORMAT = "ORDERED_SHELL_LITERAL_V1";
    private static final String LEGACY_MIRROR_FORMAT = "ORDERED_SHELL_LITERAL_MIRROR_V2";
    private static final String LEGACY_CUSTOM_MIRROR_FORMAT = "ORDERED_CUSTOM_DEPENDENCY_WORKER_MIRROR_V1";
    public static final String MIRROR_FORMAT = "ORDERED_SHELL_LITERAL_MIRROR_V3";
    public static final String CUSTOM_FORMAT = "ORDERED_CUSTOM_DEPENDENCY_WORKER_V1";
    public static final String CUSTOM_MIRROR_FORMAT = "ORDERED_CUSTOM_DEPENDENCY_WORKER_MIRROR_V2";
    public static final String MIRROR_SOURCE_POINTER = "/completeExecutableInput/sources/mirrorRuntime";
    private static final String WORKER_ASSIGNMENT = "DEPENDENCY_WORKER_SOURCE = ";
    private static final String MIRROR_BOOTSTRAP = "mirrorBootstrapShell";
    private static final String BOOTSTRAP_TARGET = " >\"$WTL_MIRROR_SHELL\" || exit 65\n";
    private static final Set<String> MIRRORED_FORMATS = Set.of(MIRROR_FORMAT, CUSTOM_MIRROR_FORMAT,
        LEGACY_MIRROR_FORMAT, LEGACY_CUSTOM_MIRROR_FORMAT);

    private DeploymentInstallerEvidence() {
    }

    /** Describes package effects only after exact native installer, request and task/server matching. */
    public static Map<String, Object> helperPackageImpact(
            gold.debug.windowstolinux.appapi.task.action.ActionIntent intent,
            gold.debug.windowstolinux.appapi.linux.command.CommandRequest request, UUID task,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws java.io.IOException {
        if (intent.serverIds().size() != 1)
            throw new IllegalArgumentException("Native helper package evidence requires its original server");
        var server = new gold.debug.windowstolinux.appapi.server.model.ServerId(intent.serverIds().getFirst());
        var semantics = intent.semantics().orElseThrow();
        var actual = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, intent.revision(), request)
            .withSemantics(semantics);
        if (!actual.equals(intent)) throw new IllegalArgumentException("Native helper request binding changed");
        byte[] input = request.stdin();
        try {
            String executable = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(input)).toString();
            var mirror = DeploymentMirrorTasks.buildInput(executable);
            String original = mirror.map(DeploymentMirrorTasks.Preparation::original).orElse(executable);
            Optional<DownloadPoint> point = Optional.empty();
            boolean ready = false;
            String expected;
            Set<gold.debug.windowstolinux.appapi.task.action.ActionTarget> targets;
            switch (semantics.operation()) {
                case "deployment.helper.install.v1" -> {
                    expected = DeploymentHelperInstallerScript.render(layout);
                    targets = DeploymentManagedHelper.installationTargets(server, layout);
                }
                case "deployment.helper.install.files.v1" -> {
                    expected = DeploymentHelperInstallerScript.renderFiles(layout);
                    targets = DeploymentManagedHelper.installationTargets(server, layout);
                }
                case "deployment.helper.install.tracked.v1" -> {
                    String policy = DeploymentMirrorTasks.policy(task);
                    point = DeploymentHelperInstallerScript.packagePoint(policy);
                    var tracked = DeploymentHelperInstallerScript.trackedRequest(original, task, layout, point);
                    ready = tracked.ready();
                    if (ready) {
                        if (mirror.isPresent()) throw new IllegalArgumentException("Package-free installer cannot stage a mirror runtime");
                        var aptPoint = point.orElseThrow();
                        expected = DeploymentHelperInstallerScript.renderTrackedReady(task, tracked.operation(), sha(server.id()),
                            DeploymentHelperInstallerScript.readyBodyDigest(layout, aptPoint), layout, aptPoint);
                    } else {
                        if (!mirror.map(DeploymentMirrorTasks.Preparation::policy).orElse("").equals(policy))
                            throw new IllegalArgumentException("Tracked installer differs from the frozen task mirror policy");
                        expected = DeploymentHelperInstallerScript.renderTracked(task, tracked.operation(), sha(server.id()),
                            DeploymentHelperInstallerScript.bodyDigest(layout, point), layout, point);
                    }
                    targets = DeploymentManagedHelper.trackedInstallationTargets(server, layout, tracked.operation(), point, ready);
                }
                default -> throw new IllegalArgumentException("Not a native helper package installer");
            }
            if (!request.script().equals(DeploymentHelperInstallerScript.trackedLauncher(ready)))
                throw new IllegalArgumentException("Native helper installer launcher differs from its exact form");
            if (mirror.isPresent()) {
                var bound = mirror.orElseThrow();
                if (!bound.task().equals(task) || !JSON.readTree(bound.policy()).path("server").asText().equals(server.id())
                    || bound.budgetSeconds() != request.timeout().toSeconds())
                    throw new IllegalArgumentException("Native helper mirror task, server or deadline changed");
                expected = DeploymentHelperInstallerScript.withMirrors(expected, bound.policy(), bound.budgetSeconds());
            }
            if (!semantics.targets().equals(targets) || !Arrays.equals(input, expected.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Native helper installer source or targets changed");
            var facts = new LinkedHashMap<String, Object>();
            facts.put("comparison", "EXACT_CURRENT_NATIVE_INSTALLER_LAUNCHER_STDIN_TASK_SERVER_AND_TARGETS");
            facts.put("task", task.toString());
            facts.put("server", server.id());
            facts.put("businessRevision", intent.revision());
            facts.put("workflowRequestPointer", "/currentCommandEvidence/workflowAuthorization/frozenWorkflowRequest");
            facts.put("executableEvidencePointer", "/completeExecutableInput");
            facts.put("stdinSha256", sha(executable));
            if (ready) {
                facts.put("packageMode", "VERIFIED_EXISTING_APT_PREREQUISITES");
                facts.put("expectedRepositoryPoint", point.orElseThrow().name());
                facts.put("observedPackageSet", List.of(DeploymentHelperInstallerScript.APT_PACKAGES.split(" ")));
                facts.put("metadataToolGuard", DeploymentManagedHelper.prerequisiteMetadataToolFacts());
                facts.put("runtimeGuard", "Inspect the protected OS package database and fixed runtime paths before taking the helper directory lock, again under the lock before the original STARTED receipt, and before success. A failed check stops this command. The helper directory lock does not lock the dpkg database.");
                facts.put("nativeTransactions", Map.of("targets", List.of(), "effects",
                    "The exact request has no package-manager transaction, package index update, package download or maintainer-script execution."));
                facts.put("limits", "The local OS package database is a bootstrap trust assumption. Review the protected package and runtime checks, normal helper file and systemd effects, exact post-install probe and original receipt.");
                return Collections.unmodifiableMap(facts);
            }
            boolean apt = point.map(DeploymentHelperInstallerScript::usesApt).orElse(true);
            boolean dnf = point.map(value -> !DeploymentHelperInstallerScript.usesApt(value)).orElse(true);
            point.ifPresent(value -> facts.put("platformPackageScope", Map.of(
                "frozenRepositoryPoint", value.name(), "packageManager", apt ? "apt-get" : "dnf",
                "policyPointer", "/mirrorTransport/frozenPolicy/points/" + value.name(),
                "runtimeGuard", "Before helper admission, receipt or package effects, wtl_system_point reads the live os-release ID as data and must equal this frozen point. A mismatch rejects with exit 64.",
                "installerSource", "The fixed tracked body contains one native package stage for this point. The separately represented generic mirror source contains both reusable manager definitions.")));
            if (apt) {
                facts.put("aptRootPackages", List.of(DeploymentHelperInstallerScript.APT_PACKAGES.split(" ")));
                facts.put("aptEffects", "Missing root packages may be installed. --no-upgrade retains already installed command-line packages; necessary dependencies may still be installed or upgraded. --no-remove aborts required removals; --no-install-recommends remains set.");
            }
            if (dnf) {
                facts.put("dnfRootPackages", List.of(DeploymentHelperInstallerScript.DNF_PACKAGES.split(" ")));
                facts.put("dnfEffects", "DNF requests install -y without --allowerasing. Dependency resolution may upgrade or obsolete packages; there is no install-only transaction guard or RPM scriptlet and trigger suppression.");
            }
            facts.put("nativeTransactions", Map.of(
                "targets", targets.stream().filter(target -> target.kind().equals("system-package-transaction"))
                    .map(gold.debug.windowstolinux.appapi.task.action.ActionTarget::id).sorted().toList(),
                "effects", "Root package maintenance for the fixed helper prerequisites includes resolver-selected dependencies, package installation and configuration, distribution maintainer scripts, triggers and registered package-manager hooks. Branch-specific upgrade and removal behavior is stated above.",
                "limits", "Check source authentication and package configuration guards in the exact current branch. Only effects inherent in the approved native dependency transaction are in scope. The installer has no general authority to alter unrelated services."));
            facts.put("sudoPackagePurpose", "The product publishes sudo-prefixed managed application usage for authorized host operators. The fixed shell body does not invoke sudo or directly edit sudoers. Review sudo package and maintainer-script effects with the other listed root packages.");
            if (mirror.isPresent())
                facts.put("mirrorShellFunctions", "The exact wrapper inlines wtl_apt and wtl_dnf definitions into the parent shell before the installer subshell inherits them. The written bootstrap file is not the only copy of those definitions.");
            if (apt) facts.put("aptDownloads", mirror.isPresent()
                ? "The task-local APT source copy checks every active URI against the frozen route before metadata update, including rejection of file: and cdrom:. The fixed apt-helper and ancestors are checked before downloads. The runtime freezes URI/name/size/SHA256, authenticates package sources and installs with --no-download. Package effects still require independent review."
                : "Native configured APT sources; mirror object freezing is not asserted for this unwrapped request.");
            facts.put("limits", "Package maintainer scripts and service effects remain possible; NEEDRESTART_MODE=l is not a blanket service-change ban. Existing readiness checks still must pass; no retry without these protections is introduced. No live state, transaction outcome or permission is asserted. Compare complete source and effects against the referenced actual frozen workflow request.");
            return Collections.unmodifiableMap(facts);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    public static Map<String, Object> describe(String executable) {
        return describe(executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    /** Recognizes the body digest after the caller verifies the actual installer bytes and digest scopes. */
    public static boolean hasReadyBodyDigest(JsonNode executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return readyPoint(executable.at("/digestScopes/bodyDigest/value").asText(), layout).isPresent();
    }

    private static Optional<DownloadPoint> readyPoint(String bodyDigest,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return List.of(DownloadPoint.APT_UBUNTU, DownloadPoint.APT_DEBIAN).stream()
            .filter(point -> bodyDigest.equals(DeploymentHelperInstallerScript.readyBodyDigest(layout, point))).findFirst();
    }

    public static Map<String, Object> describe(String executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return describe(executable, DeploymentHelperInstallerScript.renderHelperInstallation(layout), layout);
    }

    /** Binds the protected launcher to the byte-verified package-free installer form. */
    public static Map<String, Object> describeTracked(String executable, String launcher,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var evidence = describe(executable, layout);
        var scopes = (Map<?, ?>) evidence.get("digestScopes");
        boolean ready = scopes.get("bodyDigest") instanceof Map<?, ?> body && body.get("value") instanceof String digest
            && readyPoint(digest, layout).isPresent();
        if ((ready || launcher.startsWith(SshCommandFrameProtocol.HELPER_PERL_STARTUP_HEADER))
            && !launcher.equals(DeploymentHelperInstallerScript.trackedLauncher(ready)))
            throw new IllegalArgumentException("Tracked helper installer launcher differs from its exact form");
        return evidence;
    }

    public static Map<String, Object> describeFiles(String executable) {
        return describeFiles(executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> describeFiles(String executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return describe(executable, DeploymentHelperInstallerScript.renderFiles(layout), layout);
    }

    /** Projects complete ordered bytes after the caller verifies its own fixed installer implementation. */
    public static Map<String, Object> describe(String executable, String requiredInstallation) {
        return describe(executable, requiredInstallation, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> describe(String executable, String requiredInstallation,
                                               gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return describe(executable, requiredInstallation, null, layout);
    }

    /** The caller must first validate its complete canonical CUSTOM installer and dependency program. */
    public static Map<String, Object> describeCustom(String executable, String requiredInstallation, String dependencyProgram,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        Objects.requireNonNull(dependencyProgram);
        return describe(executable, requiredInstallation, dependencyProgram, layout);
    }

    private static Map<String, Object> describe(String executable, String requiredInstallation, String dependencyProgram,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        String helper = DeploymentManagedHelperBundle.renderScript(layout);
        String entry = DeploymentManagedHelperBundle.renderBuildEntry(layout);
        String quotedHelper = DeploymentHelperInstallerScript.quote(helper);
        String quotedEntry = DeploymentHelperInstallerScript.quote(entry);
        int first = executable.indexOf(quotedHelper);
        int second = executable.indexOf(quotedEntry, first + quotedHelper.length());
        if (first < 0 || second < 0 || executable.indexOf(quotedHelper, first + quotedHelper.length()) >= 0
            || executable.indexOf(quotedEntry, second + quotedEntry.length()) >= 0
            || !executable.contains(requiredInstallation))
            throw new IllegalArgumentException("Installer must contain the complete unchanged helper installation");
        String runtime = DeploymentMirrorRuntime.runtimeSource();
        String quotedRuntime = DeploymentHelperInstallerScript.quote(runtime);
        String runtimeLine = "printf '%s' " + quotedRuntime + " >\"$WTL_MIRROR_RUNTIME\"\n";
        int runtimeLineStart = executable.indexOf(runtimeLine);
        boolean mirrored = runtimeLineStart >= 0;
        int runtimeStart = runtimeLineStart + "printf '%s' ".length();
        if (mirrored && (runtimeStart >= first || executable.indexOf(runtimeLine, runtimeLineStart + 1) >= 0
            || executable.indexOf(quotedRuntime) != runtimeStart || executable.indexOf(quotedRuntime, runtimeStart + quotedRuntime.length()) >= 0))
            throw new IllegalArgumentException("Installer mirror runtime insertion is not unique and ordered");
        var sources = new LinkedHashMap<String, String>();
        var segments = new ArrayList<Map<String, String>>();
        if (mirrored) {
            sources.put("mirrorRuntime", runtime);
            segments.add(literal(executable.substring(0, runtimeStart)));
            segments.add(quote("mirrorRuntime"));
        }
        int afterRuntime = mirrored ? runtimeStart + quotedRuntime.length() : 0;
        if (mirrored) afterRuntime = projectMirrorBootstrap(executable, afterRuntime, first, sources, segments);
        boolean sharedBootstrap = sources.containsKey(MIRROR_BOOTSTRAP);
        if (dependencyProgram != null) {
            int newline = dependencyProgram.indexOf('\n');
            if (newline < 0) throw new IllegalArgumentException("Dependency program assignment missing");
            String worker = dependencyProgram.substring(newline + 1);
            String encodedWorker = jsonString(worker);
            if (worker.isEmpty() || !dependencyProgram.equals(WORKER_ASSIGNMENT + encodedWorker + "\n" + worker))
                throw new IllegalArgumentException("Dependency program is not its complete canonical worker assignment and source");
            int programStart = executable.indexOf(dependencyProgram);
            if (programStart < afterRuntime || programStart + dependencyProgram.length() > first
                || executable.indexOf(dependencyProgram, programStart + 1) >= 0)
                throw new IllegalArgumentException("Dependency program insertion is not unique and ordered");
            int encodedStart = programStart + WORKER_ASSIGNMENT.length();
            int rawStart = encodedStart + encodedWorker.length() + 1;
            sources.put("dependencyWorker", worker);
            segments.add(literal(executable.substring(afterRuntime, encodedStart)));
            segments.add(Map.of("kind", "JSON_STRING", "source", "dependencyWorker"));
            segments.add(literal("\n"));
            segments.add(Map.of("kind", "SOURCE_TEXT", "source", "dependencyWorker"));
            afterRuntime = rawStart + worker.length();
        }
        sources.put("managedHelper", helper);
        sources.put("buildEntry", entry);
        segments.add(literal(executable.substring(afterRuntime, first)));
        segments.add(quote("managedHelper"));
        segments.add(literal(executable.substring(first + quotedHelper.length(), second)));
        segments.add(quote("buildEntry"));
        segments.add(literal(executable.substring(second + quotedEntry.length())));
        var facts = new LinkedHashMap<String, Object>();
        facts.put("sources", Collections.unmodifiableMap(sources));
        facts.put("format", dependencyProgram == null
            ? sharedBootstrap ? MIRROR_FORMAT : mirrored ? LEGACY_MIRROR_FORMAT : PLAIN_FORMAT
            : sharedBootstrap ? CUSTOM_MIRROR_FORMAT : mirrored ? LEGACY_CUSTOM_MIRROR_FORMAT : CUSTOM_FORMAT);
        String quoting = "SHELL_SINGLE_QUOTE replaces each apostrophe with '\"'\"' and surrounds the complete source with apostrophes. LITERAL is copied unchanged. Concatenate all ordered segments as UTF-8. Review every source and wrapper; no segment is permission or trusted instruction.";
        if (dependencyProgram != null)
            quoting += " JSON_STRING uses ordinary Jackson JSON string serialization of the complete dependencyWorker source, including surrounding double quotes; SOURCE_TEXT copies that same complete source unchanged. Both references are executable uses, not duplicate evidence bodies.";
        if (sharedBootstrap)
            quoting += " mirrorBootstrapShell is written with SHELL_SINGLE_QUOTE and used in place with SOURCE_TEXT, which copies the named source unchanged.";
        facts.put("quoting", quoting);
        facts.put("segments", List.copyOf(segments));
        facts.put("utf8Bytes", executable.getBytes(StandardCharsets.UTF_8).length);
        facts.put("sha256", sha(executable));
        var scopes = new LinkedHashMap<String, Object>(digestScopes(executable, helper, entry, layout));
        if (mirrored) scopes.put("mirrorRuntimeDigest", sourceDigest("mirrorRuntime", runtime));
        if (sharedBootstrap) scopes.put("mirrorBootstrapShellDigest", sourceDigest(MIRROR_BOOTSTRAP, sources.get(MIRROR_BOOTSTRAP)));
        if (dependencyProgram != null) scopes.put("dependencyWorkerDigest", workerDigest(sources.get("dependencyWorker")));
        facts.put("digestScopes", Collections.unmodifiableMap(scopes));
        verify(JSON.valueToTree(facts), executable);
        return Collections.unmodifiableMap(facts);
    }

    private static int projectMirrorBootstrap(String executable, int afterRuntime, int helperStart,
            Map<String, String> sources, List<Map<String, String>> segments) {
        Optional<DeploymentMirrorTasks.Preparation> binding;
        try {
            binding = DeploymentMirrorTasks.executable(executable);
        } catch (IllegalArgumentException notCanonical) {
            return afterRuntime;
        }
        if (binding.isEmpty()) return afterRuntime;
        var mirror = binding.orElseThrow();
        String shell = DeploymentMirrorRuntime.bootstrap(mirror.policy()).shell();
        String quoted = DeploymentHelperInstallerScript.quote(shell);
        String stage = "printf '%s' " + quoted + BOOTSTRAP_TARGET;
        int staging = executable.indexOf(stage, afterRuntime);
        int rawEnd = executable.length() - mirror.original().length();
        int rawStart = rawEnd - shell.length();
        if (shell.isEmpty() || staging < afterRuntime || staging + stage.length() > rawStart || rawEnd > helperStart
            || executable.indexOf(stage, staging + stage.length()) >= 0 || !executable.startsWith(shell, rawStart))
            return afterRuntime;
        int quotedStart = staging + "printf '%s' ".length();
        sources.put(MIRROR_BOOTSTRAP, shell);
        segments.add(literal(executable.substring(afterRuntime, quotedStart)));
        segments.add(quote(MIRROR_BOOTSTRAP));
        segments.add(literal(executable.substring(quotedStart + quoted.length(), rawStart)));
        segments.add(Map.of("kind", "SOURCE_TEXT", "source", MIRROR_BOOTSTRAP));
        return rawEnd;
    }

    private static Map<String, String> literal(String text) {
        return Map.of("kind", "LITERAL", "text", text);
    }

    private static Map<String, String> quote(String source) {
        return Map.of("kind", "SHELL_SINGLE_QUOTE", "source", source);
    }

    public static String reconstruct(JsonNode evidence) {
        List<SourceReference> references = sourceReferences(evidence);
        List<String> names = references.stream().map(SourceReference::source).distinct().toList();
        if (!evidence.path("sources").isObject() || evidence.path("sources").size() != names.size()
            || !evidence.path("segments").isArray() || evidence.path("segments").size() != references.size() * 2 + 1)
            throw new IllegalArgumentException("Incomplete installer evidence");
        for (String name : names) {
            if (!evidence.path("sources").path(name).isTextual()) throw new IllegalArgumentException("Installer source changed");
            requireUnicode(evidence.path("sources").path(name).textValue());
        }
        var result = new StringBuilder();
        for (int i = 0; i < evidence.path("segments").size(); i++) {
            var segment = evidence.path("segments").get(i);
            String field = i % 2 == 0 ? "text" : "source";
            String kind = i % 2 == 0 ? "LITERAL" : references.get(i / 2).kind();
            if (segment.size() != 2 || !segment.path("kind").asText().equals(kind) || !segment.path(field).isTextual())
                throw new IllegalArgumentException("Installer segment changed");
            if (i % 2 == 0) {
                requireUnicode(segment.path("text").textValue());
                result.append(segment.path("text").textValue());
            }
            else {
                String name = references.get(i / 2).source();
                if (!segment.path("source").asText().equals(name) || !evidence.path("sources").path(name).isTextual())
                    throw new IllegalArgumentException("Installer source changed");
                result.append(encodedSource(kind, evidence.path("sources").path(name).textValue()));
            }
        }
        if (isCustom(evidence)) {
            int jsonSegment = dependencyWorkerJsonSegment(references);
            String worker = evidence.at("/sources/dependencyWorker").textValue();
            String program = WORKER_ASSIGNMENT + jsonString(worker) + "\n" + worker;
            String executable = result.toString();
            int start = executable.indexOf(program);
            if (worker.isEmpty() || !evidence.at("/segments/" + (jsonSegment - 1) + "/text").textValue().endsWith(WORKER_ASSIGNMENT)
                || !evidence.at("/segments/" + (jsonSegment + 1) + "/text").textValue().equals("\n")
                || start < 0 || executable.indexOf(program, start + 1) >= 0)
                throw new IllegalArgumentException("Dependency worker assignment or ordered insertion changed");
        }
        if (names.contains("mirrorRuntime")) {
            String runtime = evidence.at("/sources/mirrorRuntime").textValue();
            String quoted = DeploymentHelperInstallerScript.quote(runtime);
            String executable = result.toString();
            String line = "printf '%s' " + quoted + " >\"$WTL_MIRROR_RUNTIME\"\n";
            int position = executable.indexOf(quoted);
            if (!runtime.equals(DeploymentMirrorRuntime.runtimeSource())
                || position < 0 || executable.indexOf(quoted, position + quoted.length()) >= 0
                || !executable.contains(line) || !evidence.at("/segments/0/text").textValue().endsWith("printf '%s' ")
                || !evidence.at("/segments/2/text").textValue().startsWith(" >\"$WTL_MIRROR_RUNTIME\"\n"))
                throw new IllegalArgumentException("Installer mirror runtime source or insertion changed");
        }
        if (usesSharedMirrorBootstrap(evidence) && (!evidence.at("/segments/2/text").asText().endsWith("printf '%s' ")
            || !evidence.at("/segments/4/text").asText().startsWith(BOOTSTRAP_TARGET)
            || evidence.at("/sources/" + MIRROR_BOOTSTRAP).asText().isBlank()))
            throw new IllegalArgumentException("Installer mirror bootstrap insertion changed");
        return result.toString();
    }

    private record SourceReference(String source, String kind) {}

    private static List<SourceReference> sourceReferences(JsonNode evidence) {
        if (!isMirrored(evidence) && !isCustom(evidence) && !PLAIN_FORMAT.equals(evidence.path("format").asText()))
            throw new IllegalArgumentException("Incomplete installer evidence");
        var references = new ArrayList<SourceReference>();
        if (isMirrored(evidence)) references.add(new SourceReference("mirrorRuntime", "SHELL_SINGLE_QUOTE"));
        if (usesSharedMirrorBootstrap(evidence)) {
            references.add(new SourceReference(MIRROR_BOOTSTRAP, "SHELL_SINGLE_QUOTE"));
            references.add(new SourceReference(MIRROR_BOOTSTRAP, "SOURCE_TEXT"));
        }
        if (isCustom(evidence)) {
            references.add(new SourceReference("dependencyWorker", "JSON_STRING"));
            references.add(new SourceReference("dependencyWorker", "SOURCE_TEXT"));
        }
        references.add(new SourceReference("managedHelper", "SHELL_SINGLE_QUOTE"));
        references.add(new SourceReference("buildEntry", "SHELL_SINGLE_QUOTE"));
        return List.copyOf(references);
    }

    private static boolean isCustom(JsonNode evidence) {
        return Set.of(CUSTOM_FORMAT, CUSTOM_MIRROR_FORMAT, LEGACY_CUSTOM_MIRROR_FORMAT).contains(evidence.path("format").asText());
    }

    public static boolean usesSharedMirrorBootstrap(JsonNode evidence) {
        return Set.of(MIRROR_FORMAT, CUSTOM_MIRROR_FORMAT).contains(evidence.path("format").asText());
    }

    public static boolean isMirrored(JsonNode evidence) {
        return isMirroredFormat(evidence.path("format").asText());
    }

    public static boolean isMirroredFormat(Object format) {
        return format instanceof String name && MIRRORED_FORMATS.contains(name);
    }

    public static int dependencyWorkerJsonSegment(JsonNode evidence) {
        return dependencyWorkerJsonSegment(sourceReferences(evidence));
    }

    private static int dependencyWorkerJsonSegment(List<SourceReference> references) {
        int position = references.indexOf(new SourceReference("dependencyWorker", "JSON_STRING"));
        if (position < 0) throw new IllegalArgumentException("Installer dependency worker reference missing");
        return position * 2 + 1;
    }

    private static String encodedSource(String kind, String source) {
        return switch (kind) {
            case "SHELL_SINGLE_QUOTE" -> DeploymentHelperInstallerScript.quote(source);
            case "JSON_STRING" -> jsonString(source);
            case "SOURCE_TEXT" -> source;
            default -> throw new IllegalArgumentException("Installer source encoding changed");
        };
    }

    private static String jsonString(String source) {
        requireUnicode(source);
        try {
            return JSON.writeValueAsString(source);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Dependency worker JSON string cannot be rendered", failure);
        }
    }

    private static void requireUnicode(String source) {
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(source));
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Installer evidence contains invalid Unicode", invalid);
        }
    }

    /** All required source and literal leaves of the same validated representation. */
    public static List<String> readableBodies(JsonNode evidence) {
        reconstruct(evidence);
        var pointers = new ArrayList<String>();
        sourceReferences(evidence).stream().map(SourceReference::source).distinct().forEach(name -> pointers.add("/sources/" + name));
        for (int index = 0; index < evidence.path("segments").size(); index += 2) pointers.add("/segments/" + index + "/text");
        return List.copyOf(pointers);
    }

    public static String prefixPointer(JsonNode evidence, String source) {
        reconstruct(evidence);
        int index = -1;
        var references = sourceReferences(evidence);
        for (int position = 0; position < references.size(); position++) {
            if (!references.get(position).source().equals(source)) continue;
            if (index >= 0) throw new IllegalArgumentException("Installer source has multiple encoded references");
            index = position;
        }
        if (index < 0) throw new IllegalArgumentException("Installer source missing");
        return "/segments/" + (index * 2) + "/text";
    }

    public static String tailPointer(JsonNode evidence) {
        reconstruct(evidence);
        return "/segments/" + (evidence.path("segments").size() - 1) + "/text";
    }

    /** A source pointer is valid only beside the exact complete source and its digest. */
    public static void verifySourceReferences(JsonNode current) {
        var evidence = current.path("completeExecutableInput");
        var transport = current.path("mirrorTransport");
        boolean mirrored = isMirrored(evidence);
        if (!mirrored && !transport.has("runtimeSourcePointer")) return;
        if (!mirrored || !transport.isObject() || transport.has("runtimeSource")
            || !transport.path("runtimeSourcePointer").asText().equals(MIRROR_SOURCE_POINTER))
            throw new IllegalArgumentException("Mirror runtime review reference changed");
        verify(evidence, reconstruct(evidence));
        if (!transport.path("runtimeSha256").asText().equals(sha(current.at(MIRROR_SOURCE_POINTER).textValue())))
            throw new IllegalArgumentException("Mirror runtime review reference digest changed");
    }

    public static void verify(JsonNode evidence, String executable) {
        requireUnicode(executable);
        String restored = reconstruct(evidence);
        if (!Arrays.equals(restored.getBytes(StandardCharsets.UTF_8), executable.getBytes(StandardCharsets.UTF_8))
            || !evidence.path("sha256").asText().equals(sha(executable))
            || !evidence.path("utf8Bytes").canConvertToInt()
            || evidence.path("utf8Bytes").asInt() != executable.getBytes(StandardCharsets.UTF_8).length)
            throw new IllegalArgumentException("Installer evidence does not reproduce its exact executable bytes");
        JsonNode scopes = evidence.path("digestScopes");
        boolean mirrored = isMirrored(evidence);
        boolean custom = isCustom(evidence);
        boolean sharedBootstrap = usesSharedMirrorBootstrap(evidence);
        if (!scopes.isObject() || !scopes.path("algorithm").asText().equals("SHA-256")
            || !scopes.path("requestDigest").equals(JSON.valueToTree(requestDigest(executable)))
            || !scopes.path("helperDigest").equals(JSON.valueToTree(sourceDigest("managedHelper", evidence.at("/sources/managedHelper").asText())))
            || !scopes.path("buildEntryDigest").equals(JSON.valueToTree(sourceDigest("buildEntry", evidence.at("/sources/buildEntry").asText())))
            || mirrored && !scopes.path("mirrorRuntimeDigest").equals(JSON.valueToTree(sourceDigest("mirrorRuntime", evidence.at("/sources/mirrorRuntime").asText())))
            || sharedBootstrap && !scopes.path("mirrorBootstrapShellDigest").equals(JSON.valueToTree(
                sourceDigest(MIRROR_BOOTSTRAP, evidence.at("/sources/" + MIRROR_BOOTSTRAP).asText())))
            || custom && !scopes.path("dependencyWorkerDigest").equals(JSON.valueToTree(workerDigest(evidence.at("/sources/dependencyWorker").asText())))
            || scopes.size() != (scopes.has("bodyDigest") ? 5 : 4) + (mirrored ? 1 : 0) + (custom ? 1 : 0) + (sharedBootstrap ? 1 : 0))
            throw new IllegalArgumentException("Installer digest scopes differ from executable bytes");
        if (scopes.has("bodyDigest")) verifyBodyDigest(scopes.path("bodyDigest"), executable);
    }

    private static Map<String, Object> digestScopes(String executable, String helper, String entry,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var scopes = new LinkedHashMap<String, Object>();
        scopes.put("algorithm", "SHA-256");
        scopes.put("requestDigest", requestDigest(executable));
        scopes.put("helperDigest", sourceDigest("managedHelper", helper));
        scopes.put("buildEntryDigest", sourceDigest("buildEntry", entry));
        var receipt = java.util.regex.Pattern.compile("FORMAT=1\\nTASK=([0-9a-f-]{36})\\nOPERATION=([0-9a-f-]{36})\\nSERVER_SHA256=([0-9a-f]{64})\\nBODY_SHA256=([0-9a-f]{64})\\nHELPER_SHA256=([0-9a-f]{64})\\nPHASE=STARTED\\n").matcher(executable);
        if (receipt.find()) {
            var mirror = DeploymentMirrorTasks.executable(executable);
            var point = mirror
                .flatMap(binding -> DeploymentHelperInstallerScript.packagePoint(binding.policy()));
            var ready = mirror.isEmpty() ? readyPoint(receipt.group(4), layout) : Optional.<DownloadPoint>empty();
            String body = ready.isPresent() ? DeploymentHelperInstallerScript.renderReady(layout, ready.orElseThrow())
                : DeploymentHelperInstallerScript.render(layout, point);
            if (!receipt.group(4).equals(sha(body)) || !receipt.group(5).equals(sha(helper)))
                throw new IllegalArgumentException("Installer receipt body/helper digest differs from its actual byte scope");
            UUID task = UUID.fromString(receipt.group(1));
            UUID operation = UUID.fromString(receipt.group(2));
            String tracked = ready.isPresent() ? DeploymentHelperInstallerScript.renderTrackedReady(task, operation,
                receipt.group(3), receipt.group(4), layout, ready.orElseThrow())
                : DeploymentHelperInstallerScript.renderTracked(task, operation, receipt.group(3), receipt.group(4), layout, point);
            int start = executable.indexOf(tracked);
            if (start < 0 || executable.indexOf(tracked, start + tracked.length()) >= 0
                || ready.isPresent() && !executable.equals(tracked))
                throw new IllegalArgumentException("Tracked installer receipt wrapper differs from the complete current implementation");
            int begin = tracked.indexOf("prepare_stage=installer-receipt\n");
            int beginEnd = tracked.indexOf(DeploymentHelperInstallerScript.BODY_AFTER_RECEIPT, begin);
            int end = tracked.indexOf("prepare_stage=installer-receipt-terminal\n", beginEnd);
            int endEnd = tracked.indexOf("printf 'HELPER_INSTALL=1\\nPROTOCOL=", end);
            if (begin < 0 || beginEnd < 0 || end < 0 || endEnd < 0
                || tracked.indexOf(DeploymentHelperInstallerScript.BODY_AFTER_RECEIPT,
                    beginEnd + DeploymentHelperInstallerScript.BODY_AFTER_RECEIPT.length()) >= 0
                || !body.equals(tracked.substring(0, begin) + tracked.substring(beginEnd, end) + tracked.substring(endEnd)))
                throw new IllegalArgumentException("Installer body byte ranges are not its exact fixed body");
            scopes.put("bodyDigest", bodyDigest(body, List.of(byteRange(executable, start, start + begin),
                byteRange(executable, start + beginEnd, start + end),
                byteRange(executable, start + endEnd, start + tracked.length()))));
        } else {
            String body = DeploymentHelperInstallerScript.render(layout);
            int start = executable.indexOf(body);
            if (start >= 0)
                scopes.put("bodyDigest", bodyDigest(body, List.of(byteRange(executable, start, start + body.length()))));
        }
        return Collections.unmodifiableMap(scopes);
    }

    private static Map<String, Object> requestDigest(String executable) {
        return Map.of("value", sha(executable), "utf8Bytes", executable.getBytes(StandardCharsets.UTF_8).length,
            "bytes", "COMPLETE_STDIN_UTF8_INCLUDING_OPERATION_RECEIPT_WRAPPERS");
    }

    private static Map<String, Object> sourceDigest(String name, String source) {
        return Map.of("value", sha(source), "utf8Bytes", source.getBytes(StandardCharsets.UTF_8).length,
            "sourcePointer", "/completeExecutableInput/sources/" + name,
            "bytes", "COMPLETE_UNQUOTED_SOURCE_UTF8_BEFORE_SHELL_LITERAL_ENCODING");
    }

    private static Map<String, Object> workerDigest(String source) {
        return Map.of("value", sha(source), "utf8Bytes", source.getBytes(StandardCharsets.UTF_8).length,
            "sourcePointer", "/completeExecutableInput/sources/dependencyWorker",
            "bytes", "COMPLETE_DEPENDENCY_WORKER_SOURCE_UTF8_BEFORE_JSON_STRING_OR_SOURCE_TEXT_TRANSFORMATION");
    }

    private static Map<String, Object> bodyDigest(String body, List<Map<String, Integer>> ranges) {
        return Map.of("value", sha(body), "utf8Bytes", body.getBytes(StandardCharsets.UTF_8).length,
            "bytes", "FIXED_INSTALLER_BODY_UTF8_EXCLUDING_OPERATION_RECEIPT_WRAPPERS",
            "receiptField", "BODY_SHA256", "requestByteRanges", ranges);
    }

    private static Map<String, Integer> byteRange(String executable, int start, int end) {
        return Map.of("startInclusive", executable.substring(0, start).getBytes(StandardCharsets.UTF_8).length,
            "endExclusive", executable.substring(0, end).getBytes(StandardCharsets.UTF_8).length);
    }

    private static void verifyBodyDigest(JsonNode scope, String executable) {
        if (scope.size() != 5 || !scope.path("bytes").asText().equals("FIXED_INSTALLER_BODY_UTF8_EXCLUDING_OPERATION_RECEIPT_WRAPPERS")
            || !scope.path("receiptField").asText().equals("BODY_SHA256") || !scope.path("requestByteRanges").isArray()
            || scope.path("requestByteRanges").isEmpty())
            throw new IllegalArgumentException("Installer body digest scope changed");
        byte[] request = executable.getBytes(StandardCharsets.UTF_8);
        var body = new java.io.ByteArrayOutputStream();
        int previous = 0;
        for (JsonNode range : scope.path("requestByteRanges")) {
            if (range.size() != 2 || !range.path("startInclusive").canConvertToInt() || !range.path("endExclusive").canConvertToInt())
                throw new IllegalArgumentException("Installer body byte range changed");
            int start = range.path("startInclusive").asInt(), end = range.path("endExclusive").asInt();
            if (start < previous || start > end || end > request.length)
                throw new IllegalArgumentException("Installer body byte range is outside stdin");
            body.write(request, start, end - start);
            previous = end;
        }
        String restored = new String(body.toByteArray(), StandardCharsets.UTF_8);
        if (!scope.equals(JSON.valueToTree(bodyDigest(restored,
                JSON.convertValue(scope.path("requestByteRanges"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Integer>>>() {})))))
            throw new IllegalArgumentException("Installer body digest differs from its byte ranges");
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
