package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
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
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorRequirements;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewAnswers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewBuilder;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;

/** Renders the standard Directory Diff build up to the offline command review boundary. */
public final class DirectoryDiffBuildReviewFixture {
    private static final String APPLICATION = "wtl-assisted-directory-diff-geue6fdpw";
    private static final String ARCHIVE_SHA256 = "7a7a7148838dc23be934c81d8b7caa6416bf8ad4ffe8c7b4a71877cff55a72a2";
    private static final String BINDING_SHA256 = "4f8b2109a5dfcc9eb65e62b265142142e445de17ea72cc100b645d3ebe03c6bc";
    private static final String PYTHON_ARCHIVE_SHA256 = "710c3e75dac8b2f258d9cd0f76e5c4c2e9175fac9e249c98901552f7cf33b7dd";
    private static final String PYTHON_CACHE_KEY = "1c911f339cb693069b67272b9c505925b17eb614c565fb1b4b3d27a2baa4a90f";

    private DirectoryDiffBuildReviewFixture() { }

    public static MirroredInstallerTestFixture mirrorFixture() throws Exception {
        var constructor = MirroredInstallerTestFixture.class.getDeclaredConstructor(DownloadPoint[].class);
        constructor.setAccessible(true);
        var points = DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.PYTHON_SERVICE));
        return constructor.newInstance((Object) points.toArray(DownloadPoint[]::new));
    }

    public record Rendered(CommandRequest request, ActionIntent intent, Map<String, Object> facts,
                           int buildBodyBytes, String buildBodySha256, int bindingBytes, String bindingSha256)
        implements AutoCloseable {
        @Override
        public void close() {
            request.close();
        }
    }

    public static Rendered render(MirroredInstallerTestFixture fixture, DeploymentSourceMaterial material,
                                  DeploymentSourceArchive archive, DeploymentSourceArchiveReview.Evidence captured)
        throws Exception {
        assertEquals(ARCHIVE_SHA256, archive.sha256());
        assertEquals(ARCHIVE_SHA256, captured.archiveSha256());
        assertEquals("", archive.relativeRoot());
        var assessment = new DeploymentStandardAnalyzer().analyzeComponent(material, Path.of(""), APPLICATION,
            DeploymentProjectKind.PYTHON_SERVICE, fixture.task).value().orElseThrow();
        var limits = new DeploymentBuildLimits(1800, 1024, 2048, 4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, false);
        var consent = new DeploymentReviewConsent(APPLICATION, fixture.server.id(), material.snapshot().sha256(),
            archive.sha256(), Instant.EPOCH);
        var answers = new DeploymentStandardReviewAnswers(Map.of("timeout", "60", "databaseMode", "NONE"),
            List.of(new DeploymentFileBinding("content",
                new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_WRITE, "data-v1", true),
                DeploymentStorageLocation.defaults(), DeploymentStorageLocation.StorageResourceType.FILE, "", "")),
            1, limits, consent, false, true);
        var planner = new DeploymentStandardPlanner();
        var plan = new DeploymentStandardReviewBuilder(fixture.context.servers(), planner, fixture.layout)
            .plan(fixture.server, material, archive, assessment, answers, fixture.task).value().orElseThrow();
        var rendered = planner.render(plan, fixture.task, fixture.layout).value().orElseThrow();
        var reviewed = plan.request();
        var tools = pythonBinding(reviewed, fixture.layout);
        byte[] binding = DeploymentToolchainBindingCodec.encode(tools).getBytes(StandardCharsets.UTF_8);
        String bindingSha256 = DeploymentToolchainBindingCodec.identity(tools);
        String bindingBase64 = Base64.getEncoder().encodeToString(binding);
        assertEquals(368, binding.length);
        assertEquals(492, bindingBase64.length());
        assertEquals(BINDING_SHA256, bindingSha256);

        String body = DeploymentStandardBuildRunner.script(rendered.buildScript(), reviewed,
            new DeploymentBuildEnvironment(APPLICATION, Map.of()), tools);
        assertTrue(body.contains("PYTHON") || body.contains("python"));
        assertTrue(body.contains("run cmake --build .w2l/cmake-build --target 'scan-worker' --parallel 1"));
        String engine = DeploymentStandardBuildRunner.buildEngine(reviewed.facts().buildTool(), reviewed.runtime());
        assertEquals("ordinary-offline", engine);

        var command = captureCommand(fixture, archive, rendered, limits, engine, body, bindingBase64);
        try {
            var facts = new LinkedHashMap<>(new DeploymentFixedCommandProtocol(fixture.context)
                .inspect(command.intent(), command.request(), fixture.layout, fixture.task));
            var association = archiveAssociation(fixture, material, archive, command);
            assertEquals(ARCHIVE_SHA256, association.get("expectedArchiveSha256"));
            facts.put("currentCommandEvidence", Map.of("currentBuildArchive", association,
                "currentBuildArchiveSource", captured));
            return new Rendered(command.request(), command.intent(), Map.copyOf(facts), body.getBytes(StandardCharsets.UTF_8).length,
                MirroredInstallerTestFixture.sha(body), binding.length, bindingSha256);
        } catch (Exception | AssertionError failure) {
            command.request().close();
            throw failure;
        }
    }

    private static DeploymentResolvedToolchains pythonBinding(ReviewedDeploymentRequest reviewed, DeploymentRemoteLayout layout) {
        var requirement = DeploymentProjectToolchainRequirements.from(reviewed.facts(), reviewed.runtime()).stream()
            .filter(value -> value.ecosystem() == DeploymentToolchainType.PYTHON).findFirst().orElseThrow();
        var selection = new DeploymentResolvedToolchains.Selection(requirement,
            DeploymentToolchainVersion.parse(DeploymentToolchainType.PYTHON, "3.12.16").orElseThrow(),
            layout.toolchains() + "/versions/python-" + PYTHON_CACHE_KEY, DeploymentResolvedToolchains.OriginType.MANAGED,
            "https://www.python.org/ftp/python/3.12.16/Python-3.12.16.tgz", PYTHON_ARCHIVE_SHA256);
        return new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of(selection));
    }

    private record CapturedCommand(CommandRequest request, ActionIntent intent) { }

    private static CapturedCommand captureCommand(MirroredInstallerTestFixture fixture, DeploymentSourceArchive archive,
            DeploymentStandardPlanner.RenderedPlan rendered, DeploymentBuildLimits limits, String engine, String body,
            String bindingBase64) throws Exception {
        var intercepted = new AtomicReference<CapturedCommand>();
        var loader = DirectoryDiffBuildReviewFixture.class.getClassLoader();
        var review = (DeploymentCommandReview) Proxy.newProxyInstance(loader, new Class<?>[]{DeploymentCommandReview.class},
            (proxy, method, args) -> {
                assertEquals("approveCommand", method.getName());
                assertSame(fixture.task, args[2]);
                assertTrue(intercepted.compareAndSet(null, new CapturedCommand((CommandRequest) args[1], (ActionIntent) args[0])));
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.ACCESS_DENIED,
                    "OFFLINE_BUILD_FIXTURE", "Build intercepted before host grant", Map.of(), Optional.empty())));
            });
        var remote = (RemoteSession) Proxy.newProxyInstance(loader, new Class<?>[]{RemoteSession.class},
            (proxy, method, args) -> { throw new AssertionError("Unexpected remote call: " + method.getName()); });
        var budget = new DeploymentBuildBudget(limits.timeoutSeconds());
        var channel = new DeploymentBuildCommandChannel(remote, review, fixture.server, fixture.task, rendered.reviewedPlanSha256(),
            budget, fixture.layout);
        var workspace = new DeploymentRemoteWorkspace(APPLICATION, archive.sha256(), fixture.layout);
        // The fixture has no prior remote elapsed time. The original total limit still bounds this command.
        var result = channel.build(workspace, limits, budget.remainingSeconds() - 15, engine, body, bindingBase64, BuildMatchPolicy.groups());
        assertEquals(TaskResult.FAILED, result.outcome());
        assertEquals("OFFLINE_BUILD_FIXTURE", result.problem().orElseThrow().eventCode());
        return Optional.ofNullable(intercepted.get()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> archiveAssociation(MirroredInstallerTestFixture fixture, DeploymentSourceMaterial material,
            DeploymentSourceArchive archive, CapturedCommand command) throws Exception {
        var observation = Map.<String, Object>of(
            "originSnapshotId", archive.originSnapshotId().toString(), "sourceManifestSha256", archive.sourceManifestSha256(),
            "archiveSha256", archive.sha256(), "archiveSnapshotId", archive.snapshot().id().toString(),
            "localArchivePath", archive.path().toString(), "archiveBytes", archive.byteCount(), "relativeRoot", archive.relativeRoot(),
            "fileCount", material.includedFiles().size(), "fileNames", material.includedFiles().stream().sorted().toList(),
            "fileNamesTruncated", false);
        var reviewed = new LinkedHashMap<>(observation);
        reviewed.put("application", APPLICATION);
        reviewed.put("server", fixture.server.id());
        reviewed.put("remoteLayoutSha256", fixture.layout.digest());
        var method = DeploymentAssistedScope.class.getDeclaredMethod("buildArchiveFacts", List.class, Map.class,
            ActionIntent.class, CommandRequest.class, DeploymentRemoteLayout.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(null, List.of(observation), Map.of(APPLICATION, reviewed),
            command.intent(), command.request(), fixture.layout);
    }
}
