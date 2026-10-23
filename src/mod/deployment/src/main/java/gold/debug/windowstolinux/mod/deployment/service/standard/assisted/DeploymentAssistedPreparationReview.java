package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentToolchainPreparationEvidence;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;

/** ASSISTED-only current preparation contract; failed binding never falls back to source expansion. */
public final class DeploymentAssistedPreparationReview {
    private DeploymentAssistedPreparationReview() {
    }

    public static Map<String, Object> facts(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout layout) {
        if (intent.serverIds().size() != 1 || intent.semantics().isEmpty())
            throw new IllegalArgumentException("Preparation server or semantics missing");
        var server = new ServerId(intent.serverIds().getFirst());
        var semantics = intent.semantics().orElseThrow();
        if (!semantics.operation().equals("deployment.toolchain.prepare.v1")
            || !semantics.targets().equals(Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains())))
            || !RemoteActions.command(server, intent.revision(), request).withSemantics(semantics).equals(intent))
            throw new IllegalArgumentException("Preparation request or target changed");
        String invocation = DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout);
        var legacy = DeploymentToolchainPreparationEvidence.decode(invocation, layout);
        gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.Request diagnosticRequest = null;
        if (legacy.isEmpty()) {
            diagnosticRequest = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.decode(request.stdin());
            gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.reviewFacts("prepare-diagnosed",
                invocation, request.stdin(), server.id(), semantics.targets(), layout);
            if (!diagnosticRequest.planRevision().equals(intent.revision())) throw new IllegalArgumentException("Diagnostic plan changed");
        }
        var arguments = legacy.isPresent() ? legacy.orElseThrow() : diagnosticRequest.preparationArguments();
        var ecosystem = DeploymentToolchainType.valueOf(arguments.get("ecosystem").toString());
        if (ecosystem == DeploymentToolchainType.C || ecosystem == DeploymentToolchainType.CPP
            || DeploymentToolchainCatalog.defaults().branches(ecosystem).stream()
            .noneMatch(branch -> branch.version().equals(arguments.get("catalogBranch"))))
            throw new IllegalArgumentException("Preparation branch has no official installation adapter");
        String javaHome = arguments.get("javaHome").toString();
        if (!javaHome.equals("-") && !javaHome.matches(java.util.regex.Pattern.quote(layout.toolchains() + "/versions/java-") + "[a-f0-9]{64}"))
            throw new IllegalArgumentException("Java selection is outside the managed layout");
        byte[] input = request.stdin();
        try {
            if (diagnosticRequest == null && input.length != 0 || !request.timeout().equals(java.time.Duration.ofSeconds(((Number) arguments.get("preparationTimeoutSeconds")).longValue() + 15))
                || request.outputLimitBytes() != 65536)
                throw new IllegalArgumentException("Preparation input or limits changed");
        } finally {
            java.util.Arrays.fill(input, (byte) 0);
        }
        var facts = new LinkedHashMap<String, Object>();
        facts.put("contractRevision", DeploymentHelperReviewContract.REVISION);
        facts.put("helperPath", layout.helperPath());
        facts.put("helperSha256", DeploymentManagedHelperBundle.helperSha256(layout));
        facts.put("remoteLayout", layout.canonical());
        facts.put("remoteLayoutSha256", layout.digest());
        facts.put("server", server.id());
        facts.put("arguments", arguments);
        if (diagnosticRequest != null) facts.put("diagnostic", Map.of("operationId", diagnosticRequest.operationId().toString(),
            "requestSha256", diagnosticRequest.sha256(), "target", diagnosticRequest.target(),
            "meaning", "Original-operation attachment with actual command exit, stage and separately redacted stdout/stderr tails of at most 16 KiB each. Reading it requires a new independent approval and grant. Preparation success protocol is unchanged."));
        facts.put("meaning", "Complete guarded request and literal arguments matched the independently source-bound SYSTEM helper contract for this layout. No project-supplied code, URLs or scripts select the preparation implementation. Not live installation evidence, a safety verdict or permission; original workflow authority and each selected effect still require independent review.");
        return Map.copyOf(facts);
    }

    /** Static product-owned effect text selected only after the exact current request has passed binding checks. */
    public static String instructions(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout layout) {
        var facts = facts(intent, request, layout);
        var arguments = (Map<?, ?>) facts.get("arguments");
        var ecosystem = DeploymentToolchainType.valueOf(arguments.get("ecosystem").toString());
        String selected = switch (ecosystem) {
            case JAVA ->
                "Adoptium feature-release metadata selects Linux/x64/JDK/HotSpot/Eclipse and its package SHA256; extract the checked JDK archive.";
            case NODE ->
                "Node nodejs.org/dist/index.json selects the stable branch/exact release; official SHASUMS256.txt supplies SHA256 for the Linux-x64 archive; extract the checked archive.";
            case GO -> "Go go.dev/dl metadata selects stable Linux/amd64 and its SHA256; extract the checked archive.";
            case DOTNET ->
                "Microsoft .NET release metadata selects Linux-x64 SDK and SHA512; extract the checked flat archive.";
            case KOTLIN ->
                "JetBrains/kotlin official GitHub tags/releases select the compiler ZIP and unambiguous official SHA256 metadata; extract the checked archive. A supplied managed Java directory is ownership/marker/ecosystem checked before JAVA_HOME is set.";
            case RUST ->
                "rust-lang/rust official releases select stable version; static.rust-lang.org supplies archive and SHA256 sidecar. Extract and run its checked install.sh with the managed prefix and --disable-ldconfig.";
            case PYTHON ->
                "python.org official release listing/page supplies source version and SHA256, or the SHA256 message digest from its official Sigstore bundle (no signature-verification claim). Extract checked source; fixed apt/dnf build dependencies and configure/make -j2/make install use the managed prefix with ensurepip.";
            case PHP ->
                "php.net official releases supply source version and SHA256. Extract checked source; fixed apt/dnf dependencies and the product CLI configure options, make -j2/make install use the managed prefix.";
            case RUBY ->
                "ruby-lang.org release listing/announcement supplies source version and SHA256 for cache.ruby-lang.org. Extract checked source; fixed apt/dnf dependencies and configure/make -j2/make install use the managed prefix. Ruby 3.0 also downloads the product-pinned openssl-3.0.3.gem from rubygems.org, verifies SHA256 and installs it locally without dependencies.";
            default -> throw new IllegalArgumentException("No preparation adapter");
        };
        return "Current ASSISTED preparation (source-bound helper contract, selected ecosystem " + ecosystem + "): " + selected
            + " Resolver URL, exact version, digest and MANAGED/SYSTEM origin are future results, not already verified facts. HTTPS host/redirect allowlists, byte/deadline bounds and official digest checks precede extraction; archive guards reject traversal, unsafe links, special types, duplicates and overflow. Under this layout's toolchains root, guarded directories/lock, scratch download/extraction and version/digest installations/markers are created. Exact-version SYSTEM reuse probes executables, pins/rehashes originals and creates protected wrappers/markers; it does not mean the existing host tool is already trusted. Successful installation probes and seals the result. Failures can leave initialized roots/locks, package-manager dependencies and other partial effects; failed destination cleanup is not transaction rollback. Review original goal authority, arguments, deadlines, Java selection and targets. This describes enforced product behavior if executed, not completed checks or an approval.";
    }
}
