package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiver;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceSetup;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercises the original CLASS/H2 source, approval and task with a controlled remote session. / 用受控远端会话验证原 CLASS/H2 来源、授权与任务。 */
class DeploymentStandardCandidateRunnerTest extends DeploymentRuntimeFixture {
    /** One actual pipeline boundary. / 一项真实编排边界。 */
    private enum ScenarioType {
        SUCCESS, HOST_DRIFT, HOST_RECEIPT_MISSING, CAPACITY_RECEIPT_MISSING, CREATE_UNKNOWN, UPLOAD_UNKNOWN, WRONG_ENDPOINT, VERIFY_DRIFT, QUERY_FOREIGN, SOURCE_CLOSED, CANCELLED_BEFORE_REMOTE
    }

    /** Exact host-approved actions in order. / 按顺序记录经宿主审核的精确动作。 */
    private final List<ActionIntent> reviewed = new CopyOnWriteArrayList<>();

    /** Enables real host Interaction but keeps the SSH peer controlled. / 启用真实宿主Interaction，SSH端保持受控。
     * @return actual CLASS/H2 platform / 实际CLASS/H2平台
     * @throws Exception if setup fails / 设置失败时 */
    @Override
    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server,
                                                           TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("Candidate target must be exclusively absent");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task,
                                                       CancelToken cancellation) {
                throw new AssertionError("Controlled transfer cannot stall");
            }

            public String transferProgress(TransferProgress progress) {
                return "Transfer";
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                reviewed.add(intent);
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancellation) {
                throw new AssertionError("No text input expected");
            }
        });
        runtime.setSourcePicker((title, task, cancellation) -> selectedSourceDirectory());
        runtime.started();
        return runtime;
    }

    /** Returns the actual selected input root. / 返回实际选择的输入根。
     * @return source path / 来源路径 */
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Confirms exact upload and build order, including no replay after ambiguous effects. / 确认精确上传和构建顺序，包括副作用不明后不重放。
     * @param scenario one controlled boundary / 一项受控边界
     * @throws Exception if actual runtime setup fails / 实际运行时设置失败时 */
    @ParameterizedTest
    @EnumSource(ScenarioType.class)
    void originalSourceTaskUsesOneCandidateAndExactTransfer(ScenarioType scenario) throws Exception {
        candidateScenario(scenario, null);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INSTALL", "READY", "NON_NATIVE", "COMPILER", "UNKNOWN", "CANCELLED",
        "NO_VALUE", "NO_EXIT", "TRUNCATED", "EXIT", "RECEIPT", "OBSOLETE", "POST_UNKNOWN", "DENY", "CANCEL_INCOMPLETE", "CANCEL_COMPLETE"})
    void fixedNativePrerequisitesGateTheOriginalCandidate(String nativeCase) throws Exception {
        candidateScenario(ScenarioType.SUCCESS, nativeCase);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UNSUPPORTED", "SDK_INVALID", "RECOVERY", "RECOVERY_UNKNOWN"})
    void nativePreparationPreservesAdmissionAndRecoveredSelection(String nativeCase) throws Exception {
        candidateScenario(ScenarioType.SUCCESS, nativeCase);
    }

    private void candidateScenario(ScenarioType scenario, String nativeCase) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/service\n\ngo 1.24\n");
        Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        boolean rust = nativeCase != null && !nativeCase.equals("NON_NATIVE");
        if (rust) {
            Files.createDirectories(selectedSourceDirectory().resolve("src"));
            Files.writeString(selectedSourceDirectory().resolve("Cargo.toml"), "[package]\nname=\"native-fixture\"\nversion=\"0.1.0\"\nedition=\"2021\"\nrust-version=\"1.95\"\n");
            Files.writeString(selectedSourceDirectory().resolve("Cargo.lock"), "version = 4\n");
            Files.writeString(selectedSourceDirectory().resolve("rust-toolchain.toml"), "[toolchain]\nchannel=\"1.95.0\"\nprofile=\"minimal\"\n");
            Files.writeString(selectedSourceDirectory().resolve("src/main.rs"), "fn main() {}\n");
            Files.createDirectories(selectedSourceDirectory().resolve("native"));
            Files.writeString(selectedSourceDirectory().resolve("native/CMakeLists.txt"), "cmake_minimum_required(VERSION 3.25)\nproject(worker LANGUAGES C)\nset(CMAKE_C_STANDARD 17)\nadd_executable(worker worker.c)\n");
            Files.writeString(selectedSourceDirectory().resolve("native/worker.c"), "int main(void) { return 0; }\n");
            Files.writeString(selectedSourceDirectory().resolve("windowstolinux-application.properties"), "companions=worker\ncompanion.worker.type=CMAKE_SERVICE\ncompanion.worker.sourcePath=native\ncompanion.worker.artifactPath=.w2l/bin/worker\ncompanion.worker.environment=NATIVE_HELPER\n");
            Files.writeString(selectedSourceDirectory().resolve("native/CMakePresets.json"), "{\"version\":3,\"configurePresets\":[{\"name\":\"w2l-release\",\"generator\":\"Ninja\",\"binaryDir\":\"${sourceDir}/.w2l/cmake-build\",\"cacheVariables\":{\"CMAKE_BUILD_TYPE\":\"Release\"}}],\"buildPresets\":[{\"name\":\"w2l-release-build\",\"configurePreset\":\"w2l-release\"}]}");
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("server-candidate");
            runtime.servers().saveServerProfile(
                ServerProfile.fromStored(
                    new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var archiveIdentity = new AtomicReference<String>();
            var archiveBytes = new AtomicLong();
            var archivePath = new AtomicReference<Path>();
            var workspaceIdentity = new AtomicReference<DeploymentRemoteWorkspace>();
            var actions = new CopyOnWriteArrayList<String>();
            var hostReads = new AtomicInteger();
            var nativeSends = new AtomicInteger();
            var originalHandle = new AtomicReference<gold.debug.windowstolinux.appapi.task.run.TaskHandle<?>>();
            var candidateCreates = new AtomicInteger();
            var uploads = new AtomicInteger();
            var grants = new ConcurrentHashMap<ApprovalGrant, ActionIntent>();
            var readyToCancel = new CountDownLatch(1);
            var releaseCancellation = new CountDownLatch(1);
            RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("facts"))
                        return new RemoteFacts("", "", "SHA256:fixture-host");
                    if (method.getName().equals("upload")) {
                        assertTrue(grants.containsKey(args[1]), "Original host grant must be passed unchanged");
                        actions.add("upload");
                        uploads.incrementAndGet();
                        TransferRequest transfer = (TransferRequest) args[0];
                        assertEquals(archivePath.get(), transfer.localPath());
                        assertEquals(archiveBytes.get(), transfer.maxBytes());
                        assertEquals(workspaceIdentity.get().candidateRoot() + "/mutable/source.tar.gz",
                            transfer.remoteAbsolutePath());
                        if (scenario == ScenarioType.UPLOAD_UNKNOWN)
                            return unknownTransfer();
                        return new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(new TransferResult(archiveBytes.get(), archiveIdentity.get(),
                                transfer.localPath(),
                                scenario == ScenarioType.WRONG_ENDPOINT
                                    ? transfer.remoteAbsolutePath() + ".other"
                                    : transfer.remoteAbsolutePath())),
                            Optional.empty());
                    }
                    if (!method.getName().equals("execute"))
                        throw new AssertionError("Caller owns the original session: " + method.getName());
                    CommandRequest command = (CommandRequest) args[0];
                    assertTrue(grants.containsKey(args[1]), "Original host grant must be passed unchanged");
                    String script = command.script();
                    if (script.contains(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PATH))
                        script = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedBody(script);
                    if (script.contains("managed-helper' probe")) {
                        actions.add("helper-probe");
                        return reply("HELPER=1\nPROTOCOL="
                            + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.PROTOCOL_VERSION
                            + "\nSHA256="
                            + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.EXPECTED_SHA256
                            + "\n");
                    }
                    if (script.startsWith("/usr/bin/python3 -I -S -c ")) {
                        actions.add("native-install"); nativeSends.incrementAndGet();
                        var approved = grants.get(args[1]);
                        assertEquals(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.OPERATION,
                            approved.semantics().orElseThrow().operation());
                        var bound = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.inspect(
                            approved, command, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
                        assertEquals("NATIVE_BUILD", bound.get("profile"));
                        assertTrue(actions.indexOf("bind-tools") < actions.indexOf("native-install"));
                        assertEquals(nativeCase.startsWith("RECOVERY") ? 3 : 2, hostReads.get());
                        assertEquals(nativeCase.startsWith("RECOVERY") ? 1 : 0, uploads.get());
                        if (nativeCase.startsWith("CANCEL_")) originalHandle.get().requestCancel();
                        if (nativeCase.equals("CANCEL_INCOMPLETE")) return new OperationResult<CommandResult>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                        if (nativeCase.equals("UNKNOWN") || nativeCase.equals("RECOVERY_UNKNOWN")) return unknownCommand();
                        if (nativeCase.equals("CANCELLED")) return new OperationResult<CommandResult>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                        if (nativeCase.equals("NO_VALUE")) return new OperationResult<CommandResult>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                        if (nativeCase.equals("NO_EXIT")) return new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(new CommandResult(Optional.empty(), "", "", false)), Optional.empty());
                        if (nativeCase.equals("TRUNCATED")) return new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(new CommandResult(Optional.of(0), "", "", true)), Optional.empty());
                        if (nativeCase.equals("EXIT")) return new OperationResult<>(TaskResult.SUCCEEDED,
                            Optional.of(new CommandResult(Optional.of(4), "", "", false)), Optional.empty());
                        if (nativeCase.equals("RECEIPT")) return reply("TOOLCHAIN_DEPENDENCIES={}\n");
                        var packages = new LinkedHashMap<String, String>();
                        gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.packages("APT", "NATIVE_BUILD")
                            .forEach(name -> packages.put(name, "1.0"));
                        return reply(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DependencyReceiptFixture.receipt(command, "APT",
                            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()));
                    }
                    if (script.contains("DISTRO_ID=")) {
                        actions.add("host-facts");
                        int read = hostReads.incrementAndGet();
                        if (scenario == ScenarioType.HOST_RECEIPT_MISSING)
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                        if (nativeCase != null && nativeCase.equals("POST_UNKNOWN") && read == 3) return unknownCommand();
                        String nativeTools = "";
                        if (rust) {
                            boolean ready = nativeCase.startsWith("RECOVERY") ? read <= 2 || read >= 4 : nativeCase.equals("READY") || read >= 3;
                            nativeTools = "TOOL_C_COMPILER=" + (nativeCase.equals("COMPILER") && !ready ? "" : "13.3.0") + "\n"
                                + "TOOL_CMAKE=" + (ready ? nativeCase.equals("OBSOLETE") ? "3.24.4" : "3.28.3" : nativeCase.equals("COMPILER") ? "3.28.3" : "") + "\n"
                                + "TOOL_NINJA=" + (ready || nativeCase.equals("COMPILER") ? "1.11.1" : "") + "\n";
                        }
                        return reply("DISTRO_ID=ubuntu\nVERSION=24.04\nARCH="
                            + ("UNSUPPORTED".equals(nativeCase) || scenario == ScenarioType.HOST_DRIFT && read == 2 ? "aarch64" : "x86_64")
                            + "\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\nCPU_LEVEL=x86-64-v3\n"
                            + "SECURITY_MODULE=apparmor\nSECURITY_STATE=enabled\nFIREWALL=ufw\n"
                            + "FIREWALL_STATE=active\n" + nativeTools);
                    }
                    if (script.contains("stat -f -c")) {
                        actions.add("capacity");
                        if (scenario == ScenarioType.CAPACITY_RECEIPT_MISSING)
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                        return reply("1000000000 4096\n");
                    }
                    if (script.contains("build-preflight")) {
                        actions.add("build-preflight");
                        return reply("");
                    }
                    if (script.contains("prepare-toolchains 'prepare-diagnosed'")) {
                        var diagnostic = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics.decode(command.stdin());
                        assertEquals(rust ? "RUST" : "GO", diagnostic.preparationArguments().get("ecosystem"));
                        actions.add("prepare-tools");
                        if ("SDK_INVALID".equals(nativeCase)) return reply("TOOLCHAIN=RUST|9.99.1|/opt/unconfirmed|MANAGED|https://official.example/rust|" + "b".repeat(64) + "\n");
                        return reply("TOOLCHAIN=" + (rust ? "RUST|1.95.0|/usr/local/lib/windowstolinux/toolchains/versions/rust-" : "GO|1.24.1|/usr/local/lib/windowstolinux/toolchains/versions/go-")
                            + "b".repeat(64) + "|MANAGED|https://official.example/go|" + "b".repeat(64) + "\n");
                    }
                    if (script.contains("prepare-toolchains 'bind'")) {
                        actions.add("bind-tools");
                        String encoded = script.substring(script.indexOf("'bind' '") + 8);
                        encoded = encoded.substring(0, encoded.indexOf('\''));
                        var tools = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec
                            .decode(new String(Base64.getDecoder().decode(encoded),
                                java.nio.charset.StandardCharsets.UTF_8));
                        return reply("TOOLCHAIN_BINDING="
                            + gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec
                            .identity(tools)
                            + "\n");
                    }
                    if (script.contains("candidate-create-task")) {
                        actions.add("create-candidate");
                        candidateCreates.incrementAndGet();
                        if (scenario == ScenarioType.CREATE_UNKNOWN)
                            return unknownCommand();
                        return reply("CANDIDATE=" + workspaceIdentity.get().candidateRoot() + "\n");
                    }
                    if (script.contains("stat -c '%s'")) {
                        actions.add("verify-archive");
                        return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archiveBytes.get()
                            + "\nSHA256="
                            + (scenario == ScenarioType.VERIFY_DRIFT ? "f".repeat(64) : archiveIdentity.get())
                            + "\nOWNER=0\n");
                    }
                    if (script.contains("candidate-query-task")) {
                        actions.add("query-candidate");
                        return reply(scenario == ScenarioType.QUERY_FOREIGN
                            ? "CANDIDATE_STATE=absent\nBUILD_ACTIVE=no\n"
                            : "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n");
                    }
                    assertTrue(script.startsWith("exec /usr/bin/python3 -I -c "), script);
                    actions.add("build");
                    return reply("WTL_BUILD_REPORT=1\nEXIT=0\nBYTES=0\nSHA256=" + "c".repeat(64)
                        + "\nUID=1001\nENGINE=ordinary\nTOOL=" + (rust ? "CARGO_LOCKED" : "GO_MODULE") + "\nCOMPAT=0\nLIMIT=-\n"
                        + "CONFLICT=0\nHEAD=\nTAIL=\n");
                });
            var context = runtime.modContexts().get(OWNER).context();
            InteractionService hostInteraction = new InteractionService() {
                public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task)
                    throws ApiException {
                    if ("DENY".equals(nativeCase) && intent.semantics().orElseThrow().operation().equals(
                        gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.OPERATION))
                        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.ACCESS_DENIED,
                            "CONTROLLED_DENY", "Original native review refused", Map.of(), Optional.empty())));
                    var result = context.interaction().approve(intent, task);
                    result.value().ifPresent(value -> grants.put(value, intent));
                    return result;
                }

                public OperationResult<String> requestText(String title, String prompt, boolean secret,
                                                           TaskContext task) throws ApiException {
                    return context.interaction().requestText(title, prompt, secret, task);
                }
            };
            var failure = new AtomicReference<Throwable>();
            var observed = new AtomicReference<OperationResult<DeploymentVerifiedBuildResult>>();
            var callbackFinished = new CountDownLatch(1);
            var handle = context.tasks().submit(
                new TaskRequest("deployment-standard-candidate", "Candidate source", List.of(server.id()), true),
                task -> {
                    try {
                        var selection = context.file().sources().selectDirectory("Source", task);
                        try (var material = new DeploymentSourceSetup(context.file()).prepare(selection, 65536,
                            100, task);
                             var archive = new DeploymentSourceArchiver(context.file()).prepare(material,
                                 65536, task)) {
                            archiveIdentity.set(archive.sha256());
                            archiveBytes.set(archive.byteCount());
                            archivePath.set(archive.path());
                            var analysis = new DeploymentStandardAnalyzer().analyze(material, "Reviewed Go",
                                rust ? DeploymentProjectKind.RUST_SERVICE : DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                            assertEquals(TaskResult.SUCCEEDED, analysis.outcome(), analysis.problem().toString());
                            assertTrue(analysis.value().orElseThrow().assessment().facts().isPresent(), analysis.value().orElseThrow().assessment().toString());
                            var facts = analysis.value().orElseThrow().assessment().facts().orElseThrow();
                            workspaceIdentity
                                .set(new DeploymentRemoteWorkspace(facts.applicationId(), archive.sha256()));
                            DeploymentRuntimeDefinition runtimeDefinition = rust
                                ? new DeploymentRuntimeDefinition.RustService("1.95", facts.applicationId(), "src/main.rs", new DeploymentHealthCheck.Tcp(8080, 10, 2))
                                : new DeploymentRuntimeDefinition.GoService("1.24", facts.applicationId(), "main.go", new DeploymentHealthCheck.Tcp(8080, 10, 2));
                            if (rust) {
                                var workload = runtimeDefinition.workload();
                                runtimeDefinition = runtimeDefinition.withWorkload(new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload(
                                    workload.mode(), workload.reviewed(), workload.command(), workload.workingDirectory(), workload.endpoints(), workload.verification(),
                                    workload.expectedOutput(), workload.client(), workload.inputs(), List.of(new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion(
                                        "worker", "native", gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion.BuildType.CMAKE_SERVICE,
                                        ".w2l/bin/worker", "NATIVE_HELPER")), workload.buildDirectory(), workload.workers()));
                            }
                            var request = new ReviewedDeploymentRequest(server, facts, material, archive,
                                DeploymentConfigSnapshot.create(facts.applicationId(), 1, "v1",
                                    Instant.now(), List.of()),
                                List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())),
                                runtimeDefinition,
                                Optional.empty(), gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(),
                                new DeploymentReviewConsent(facts.applicationId(), server.id(),
                                    material.snapshot().sha256(), archive.sha256(), Instant.now()),
                                false, true);
                            var plan = new DeploymentStandardPlanner().plan(material,
                                analysis.value().orElseThrow(), request, task);
                            if (scenario == ScenarioType.SOURCE_CLOSED)
                                archive.close();
                            if (scenario == ScenarioType.CANCELLED_BEFORE_REMOTE) {
                                readyToCancel.countDown();
                                try {
                                    assertTrue(releaseCancellation.await(10, TimeUnit.SECONDS));
                                } catch (InterruptedException cancelled) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            var runner = new DeploymentStandardCandidateRunner();
                            OperationResult<DeploymentVerifiedBuildResult> result;
                            if (nativeCase != null && nativeCase.startsWith("RECOVERY")) {
                                var prepared = runner.prepare(plan.value().orElseThrow(), session, hostInteraction, task);
                                assertEquals(TaskResult.SUCCEEDED, prepared.outcome(), prepared.toString());
                                var original = prepared.value().orElseThrow();
                                var built = runner.build(original, session, task);
                                assertEquals(TaskResult.SUCCEEDED, built.outcome(), built.toString());
                                var recovered = runner.recoverSelection(original, plan.value().orElseThrow(), 0, session, hostInteraction, task);
                                assertEquals(1, uploads.get(), "Formal correction cannot upload the existing candidate again");
                                assertEquals(1, actions.stream().filter("build"::equals).count());
                                if (recovered.outcome() == TaskResult.SUCCEEDED) {
                                    var field = DeploymentStandardCandidateRunner.Prepared.class.getDeclaredField("selection"); field.setAccessible(true);
                                    var before = (DeploymentStandardBuildRunner.Prepared) field.get(original);
                                    var after = (DeploymentStandardBuildRunner.Prepared) field.get(recovered.value().orElseThrow());
                                    assertSame(before.budget, after.budget); assertSame(before.task, after.task); assertSame(before.session, after.session);
                                    assertEquals(before.workspace, after.workspace);
                                    result = built;
                                } else {
                                    assertTrue(recovered.value().isEmpty());
                                    result = new OperationResult<>(recovered.outcome(), Optional.empty(), recovered.problem());
                                }
                            } else result = runner.execute(plan.value().orElseThrow(), session, hostInteraction, task);
                            observed.set(result);
                            callbackFinished.countDown();
                            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                        }
                    } catch (Throwable caught) {
                        failure.set(caught);
                        callbackFinished.countDown();
                        throw caught;
                    }
                });
            originalHandle.set(handle);
            if (scenario == ScenarioType.CANCELLED_BEFORE_REMOTE) {
                assertTrue(readyToCancel.await(10, TimeUnit.SECONDS));
                handle.requestCancel();
                releaseCancellation.countDown();
            }
            assertTrue(callbackFinished.await(20, TimeUnit.SECONDS), "callback did not return");
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            var completed = handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (scenario != ScenarioType.CANCELLED_BEFORE_REMOTE && (nativeCase == null || !nativeCase.startsWith("CANCEL_")))
                assertEquals(TaskResult.SUCCEEDED, completed.outcome());
            if (nativeCase != null) {
                boolean success = Set.of("INSTALL", "COMPILER", "READY", "NON_NATIVE", "RECOVERY").contains(nativeCase);
                TaskResult expected = success ? TaskResult.SUCCEEDED : Set.of("CANCELLED", "CANCEL_COMPLETE").contains(nativeCase) ? TaskResult.CANCELLED
                    : Set.of("EXIT", "OBSOLETE", "DENY", "UNSUPPORTED").contains(nativeCase) ? TaskResult.FAILED : TaskResult.UNKNOWN;
                assertEquals(expected, observed.get().outcome(), observed.get().toString());
                assertEquals(Set.of("READY", "NON_NATIVE", "DENY", "UNSUPPORTED", "SDK_INVALID").contains(nativeCase) ? 0 : 1, nativeSends.get());
                boolean originalBuild = success || nativeCase.startsWith("RECOVERY");
                assertEquals(originalBuild ? 1 : 0, uploads.get());
                assertEquals(originalBuild ? 1 : 0, candidateCreates.get());
                assertEquals(originalBuild, actions.contains("build"));
                if (nativeCase.equals("OBSOLETE")) assertEquals("DEPLOYMENT_NATIVE_BUILD_PREREQUISITES_UNSATISFIED", observed.get().problem().orElseThrow().eventCode());
                if (nativeCase.startsWith("CANCEL_")) assertEquals(2, hostReads.get(), "Cancellation cannot start a fresh probe");
                if (success && nativeSends.get() == 1) assertTrue(actions.indexOf("native-install") < actions.lastIndexOf("host-facts"));
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                return;
            }
            if (scenario == ScenarioType.SUCCESS) {
                assertEquals(TaskResult.SUCCEEDED, observed.get().outcome(), observed.get().toString());
                assertEquals(archiveIdentity.get(), observed.get().value().orElseThrow().archiveSha256());
                assertEquals("1.24.1",
                    observed.get().value().orElseThrow().toolchains().selections().getFirst().version().text());
                assertEquals(List.of("helper-probe", "host-facts", "capacity", "build-preflight", "prepare-tools",
                    "bind-tools", "host-facts", "capacity", "create-candidate", "query-candidate", "upload", "verify-archive",
                    "build"), actions);
                assertEquals(1, reviewed.stream().filter(
                        value -> value.semantics().orElseThrow().operation().equals("deployment.candidate.upload.v1"))
                    .count());
            } else {
                TaskResult expectedOutcome = switch (scenario) {
                    case HOST_DRIFT, HOST_RECEIPT_MISSING, CAPACITY_RECEIPT_MISSING, SOURCE_CLOSED -> TaskResult.FAILED;
                    case CANCELLED_BEFORE_REMOTE -> TaskResult.CANCELLED;
                    default -> TaskResult.UNKNOWN;
                };
                String expectedEvent = switch (scenario) {
                    case HOST_DRIFT -> "DEPLOYMENT_RUNTIME_UNSUPPORTED";
                    case HOST_RECEIPT_MISSING -> "DEPLOYMENT_HOST_PROBE_MISSING";
                    case CAPACITY_RECEIPT_MISSING -> "DEPLOYMENT_CAPACITY_MISSING";
                    case SOURCE_CLOSED -> "DEPLOYMENT_PLAN_SOURCE_UNAVAILABLE";
                    case CANCELLED_BEFORE_REMOTE -> "DEPLOYMENT_CANDIDATE_CANCELLED";
                    case CREATE_UNKNOWN -> "FIXTURE_UNKNOWN";
                    case UPLOAD_UNKNOWN -> "FIXTURE_UPLOAD_UNKNOWN";
                    case WRONG_ENDPOINT -> "DEPLOYMENT_UPLOAD_RECEIPT_CONFLICT";
                    case VERIFY_DRIFT -> "DEPLOYMENT_UPLOAD_REMOTE_CONFLICT";
                    case QUERY_FOREIGN -> "DEPLOYMENT_CANDIDATE_OWNER_UNCONFIRMED";
                    case SUCCESS -> throw new AssertionError();
                };
                assertEquals(expectedOutcome, observed.get().outcome(), observed.get().toString());
                assertEquals(expectedEvent, observed.get().problem().orElseThrow().eventCode());
                assertFalse(actions.contains("build"));
                if (scenario == ScenarioType.HOST_DRIFT)
                    assertFalse(actions.contains("create-candidate"));
                if (scenario == ScenarioType.CREATE_UNKNOWN)
                    assertFalse(actions.contains("upload"));
                if (scenario == ScenarioType.UPLOAD_UNKNOWN || scenario == ScenarioType.WRONG_ENDPOINT)
                    assertFalse(actions.contains("verify-archive"));
            }
            assertEquals(
                scenario == ScenarioType.SUCCESS || scenario == ScenarioType.CREATE_UNKNOWN
                    || scenario == ScenarioType.UPLOAD_UNKNOWN || scenario == ScenarioType.WRONG_ENDPOINT
                    || scenario == ScenarioType.VERIFY_DRIFT || scenario == ScenarioType.QUERY_FOREIGN ? 1 : 0,
                candidateCreates.get());
            assertEquals(
                scenario == ScenarioType.SUCCESS || scenario == ScenarioType.UPLOAD_UNKNOWN
                    || scenario == ScenarioType.WRONG_ENDPOINT || scenario == ScenarioType.VERIFY_DRIFT ? 1 : 0,
                uploads.get());
            assertEquals(
                scenario == ScenarioType.HOST_RECEIPT_MISSING || scenario == ScenarioType.CAPACITY_RECEIPT_MISSING
                    || scenario == ScenarioType.SOURCE_CLOSED
                    || scenario == ScenarioType.CANCELLED_BEFORE_REMOTE ? 0 : 1,
                actions.stream().filter("prepare-tools"::equals).count());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    /** Returns one complete command reply. / 返回完整命令回执。
     * @param stdout exact output / 精确输出
     * @return known success / 已知成功 */
    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }

    /** Returns an unconfirmed command without a fabricated receipt. / 返回无虚构回执的未知命令。
     * @return unknown command / 未知命令 */
    private static OperationResult<CommandResult> unknownCommand() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "FIXTURE_UNKNOWN", "Controlled reply was lost", Map.of(), Optional.empty())));
    }

    /** Returns an unconfirmed public upload. / 返回未确认公开上传。
     * @return unknown upload / 未知上传 */
    private static OperationResult<TransferResult> unknownTransfer() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "FIXTURE_UPLOAD_UNKNOWN", "Controlled transfer reply was lost", Map.of(), Optional.empty())));
    }
}
