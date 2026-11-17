package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.ai.review.MirroredInstallerTestFixture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiveReview;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewAnswers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewBuilder;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;

/** Regenerates the frozen Asset Lending archive and typed build without a host or model. */
public final class AssetLendingBuildReviewFixture {
    private static final String APPLICATION = "wtl-assisted-asset-lending-enxoeniyw-backend";
    private static final String ARCHIVE_SHA256 = "f8596fdc60ad98185acef03a88f1762e8665afbf2fd3aeb625d5fbdc2e0a70c9";
    private static final String BINDING_SHA256 = "4c4bcad78d46b12e90ee4eeea9fbeb724757030951929829fb1855ab0bda484a";
    private static final String TOOLCHAIN_KEY = "8bf15bbfa4082cda2d5ab5a797fe32d0c10380135fd1d19cd2c27ea81a6eb026";
    private static final String TOOLCHAIN_SHA256 = "4e7e7fb86ecbb8da3347c6218f99490474597a964c0ca43499863466f3be0375";

    private AssetLendingBuildReviewFixture() { }

    private record Snapshot(UUID id, String sha256, Path directory) implements FileSnapshot {
        @Override public void close() { }
    }

    public record Captured(CommandRequest request, ActionIntent intent, Map<String, Object> facts) implements AutoCloseable {
        @Override public void close() { request.close(); }
    }

    @FunctionalInterface
    public interface Command {
        OperationResult<?> run(DeploymentBuildCommandChannel channel) throws ApiException;
    }

    public static Captured capture(MirroredInstallerTestFixture fixture, String revision, DeploymentBuildBudget budget,
                                   Command command) throws Exception {
        return capture(fixture, revision, budget, Optional.empty(), command);
    }

    public static Captured capture(MirroredInstallerTestFixture fixture, String revision, DeploymentBuildBudget budget,
                                   Optional<String> javaReceipt, Command command) throws Exception {
        var intercepted = new AtomicReference<Captured>();
        var preflightPending = new AtomicBoolean(javaReceipt.isPresent());
        var javaGrant = new ApprovalGrant(UUID.randomUUID());
        var loader = AssetLendingBuildReviewFixture.class.getClassLoader();
        var review = (DeploymentCommandReview) Proxy.newProxyInstance(loader, new Class<?>[]{DeploymentCommandReview.class},
            (proxy, method, args) -> {
                assertEquals("approveCommand", method.getName());
                assertSame(fixture.task, args[2]);
                if (preflightPending.get()) {
                    assertEquals("deployment.toolchain.prepare.v1", ((ActionIntent) args[0]).semantics().orElseThrow().operation());
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(javaGrant), Optional.empty());
                }
                assertTrue(intercepted.compareAndSet(null,
                    new Captured(((CommandRequest) args[1]).snapshot(), (ActionIntent) args[0], Map.of())));
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.ACCESS_DENIED,
                    "OFFLINE_BUDGET_REPLAY", "Captured before any host grant", Map.of(), Optional.empty())));
            });
        var remote = (RemoteSession) Proxy.newProxyInstance(loader, new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
            if (method.getName().equals("facts")) return new RemoteFacts("linux", "x86_64", "SHA256:fixture");
            if (method.getName().equals("execute") && preflightPending.compareAndSet(true, false)) {
                assertSame(javaGrant, args[1]);
                return new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(new CommandResult(Optional.of(0), javaReceipt.orElseThrow(), "", false)), Optional.empty());
            }
            throw new AssertionError("Unexpected remote call " + method.getName());
        });
        var channel = new DeploymentBuildCommandChannel(remote, review, fixture.server, fixture.task, revision, budget, fixture.layout);
        if (javaReceipt.isPresent()) assertEquals(TaskResult.SUCCEEDED,
            channel.prepare(DeploymentToolchainType.JAVA, "21", "-", 120, "-").outcome());
        var result = command.run(channel);
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals("OFFLINE_BUDGET_REPLAY", result.problem().orElseThrow().eventCode());
        return Optional.ofNullable(intercepted.get()).orElseThrow();
    }

    public static Captured build(MirroredInstallerTestFixture fixture, Path source, Path archiveDirectory) throws Exception {
        Path root = source.toRealPath();
        List<String> names;
        try (var files = Files.list(root.resolve("backend"))) {
            names = files.filter(Files::isRegularFile).map(file -> "backend/" + file.getFileName()).sorted().toList();
        }
        var material = new DeploymentSourceMaterial("Asset Lending",
            new Snapshot(UUID.randomUUID(), "a".repeat(64), root), names, List.of());
        var archive = archive(material, fixture.task, archiveDirectory);
        var sources = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
        assertEquals("COMPLETE", sources.coverage());
        assertEquals(names.size(), sources.memberCount());

        var assessment = new DeploymentStandardAnalyzer().analyzeComponent(material, Path.of("backend"), APPLICATION,
            DeploymentProjectKind.DOTNET_SERVICE, fixture.task).value().orElseThrow();
        var limits = new DeploymentBuildLimits(1800, 1024, 2048, 4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, false);
        var consent = new DeploymentReviewConsent(APPLICATION, fixture.server.id(), material.snapshot().sha256(),
            archive.sha256(), Instant.EPOCH);
        var answers = new DeploymentStandardReviewAnswers(Map.of("timeout", "10", "databaseMode", "NONE",
            "version", "10.0.300", "primary", "AssetLending", "secondary", "AssetLending.dll"), List.of(), 1, limits, consent, false, true);
        var planner = new DeploymentStandardPlanner();
        var plan = new DeploymentStandardReviewBuilder(fixture.context.servers(), planner, fixture.layout)
            .plan(fixture.server, material, archive, assessment, answers, fixture.task).value().orElseThrow();
        var rendered = planner.render(plan, fixture.task, fixture.layout).value().orElseThrow();
        var reviewed = plan.request();
        var requirement = DeploymentProjectToolchainRequirements.from(reviewed.facts(), reviewed.runtime()).stream()
            .filter(value -> value.ecosystem() == DeploymentToolchainType.DOTNET).findFirst().orElseThrow();
        var selection = new DeploymentResolvedToolchains.Selection(requirement,
            DeploymentToolchainVersion.parse(DeploymentToolchainType.DOTNET, "10.0.300").orElseThrow(),
            fixture.layout.toolchains() + "/versions/dotnet-" + TOOLCHAIN_KEY, DeploymentResolvedToolchains.OriginType.MANAGED,
            "https://builds.dotnet.microsoft.com/dotnet/Sdk/10.0.300/dotnet-sdk-10.0.300-linux-x64.tar.gz", TOOLCHAIN_SHA256);
        var tools = new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of(selection));
        byte[] binding = DeploymentToolchainBindingCodec.encode(tools).getBytes(StandardCharsets.UTF_8);
        assertEquals(412, binding.length);
        assertEquals(BINDING_SHA256, DeploymentToolchainBindingCodec.identity(tools));
        String build = DeploymentStandardBuildRunner.script(rendered.buildScript(), reviewed,
            new DeploymentBuildEnvironment(APPLICATION, Map.of("HOST", "127.0.0.1", "PORT", "46121")), tools);
        assertTrue(build.contains("dotnet") && build.contains("--locked-mode"));
        String engine = DeploymentStandardBuildRunner.buildEngine(reviewed.facts().buildTool(), reviewed.runtime());
        var budget = new DeploymentBuildBudget(limits.timeoutSeconds());
        var workspace = new DeploymentRemoteWorkspace(APPLICATION, archive.sha256(), fixture.layout);
        var captured = capture(fixture, rendered.reviewedPlanSha256(), budget, channel -> channel.build(workspace, limits,
            budget.remainingSeconds() - 15, engine, build, Base64.getEncoder().encodeToString(binding), BuildMatchPolicy.groups()));
        try {
            var facts = new LinkedHashMap<>(new DeploymentFixedCommandProtocol(fixture.context)
                .inspect(captured.intent(), captured.request(), fixture.layout, fixture.task));
            facts.put("currentCommandEvidence", Map.of("currentBuildArchive", association(fixture, material, archive, captured),
                "currentBuildArchiveSource", sources));
            return new Captured(captured.request(), captured.intent(), Map.copyOf(facts));
        } catch (Exception | AssertionError failure) {
            captured.close();
            throw failure;
        }
    }

    private static DeploymentSourceArchive archive(DeploymentSourceMaterial source, TaskContext task, Path directory) throws Exception {
        var writer = Class.forName("gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceTar")
            .getDeclaredMethod("write", DeploymentSourceMaterial.class, String.class, OutputStream.class, TaskContext.class);
        writer.setAccessible(true);
        Path file = directory.resolve("source.tar.gz");
        long total;
        try (var stream = Files.newOutputStream(file)) {
            total = (long) writer.invoke(null, source, "backend", stream, task);
        }
        byte[] bytes = Files.readAllBytes(file);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(7271, bytes.length);
        assertEquals(ARCHIVE_SHA256, hash);
        return new DeploymentSourceArchive(new Snapshot(UUID.randomUUID(), "b".repeat(64), directory.toRealPath()),
            hash, bytes.length, total, source.snapshot().id(), source.snapshot().sha256(), "backend");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> association(MirroredInstallerTestFixture fixture, DeploymentSourceMaterial source,
                                                    DeploymentSourceArchive archive, Captured captured) throws Exception {
        var observation = Map.<String, Object>of(
            "originSnapshotId", archive.originSnapshotId().toString(), "sourceManifestSha256", archive.sourceManifestSha256(),
            "archiveSha256", archive.sha256(), "archiveSnapshotId", archive.snapshot().id().toString(),
            "localArchivePath", archive.path().toString(), "archiveBytes", archive.byteCount(), "relativeRoot", archive.relativeRoot(),
            "fileCount", source.includedFiles().size(),
            "fileNames", source.includedFiles().stream().map(name -> name.substring("backend/".length())).sorted().toList(),
            "fileNamesTruncated", false);
        var reviewed = new LinkedHashMap<>(observation);
        reviewed.put("application", APPLICATION);
        reviewed.put("server", fixture.server.id());
        reviewed.put("remoteLayoutSha256", fixture.layout.digest());
        var method = DeploymentAssistedScope.class.getDeclaredMethod("buildArchiveFacts", List.class, Map.class,
            ActionIntent.class, CommandRequest.class, DeploymentRemoteLayout.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(null, List.of(observation), Map.of(APPLICATION, reviewed),
            captured.intent(), captured.request(), fixture.layout);
    }
}
