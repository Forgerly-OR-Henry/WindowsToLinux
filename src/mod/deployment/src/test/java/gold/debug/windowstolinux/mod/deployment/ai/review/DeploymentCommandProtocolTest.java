package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import org.junit.jupiter.api.Test;

class DeploymentCommandProtocolTest {
    private static final String DEPENDENCY_TASK = "11111111-2222-3333-4444-555555555555";
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final ServerId server = new ServerId("protocol-fixture");
    private final ModContext owner = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{ModContext.class}, (proxy, method, args) -> { throw new AssertionError("No capability access: " + method.getName()); });

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"APPROVE", "DENY", "INPUT", "ARGV", "COLLECTOR", "TARGET", "REQUEST", "HELPER_GUARD"})
    void standardBuildInvocationFactsKeepIndependentDecisionsAndExactGrants(String scenario) throws Exception {
        var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        var context = reviewContext(fixture);
        var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("demo", "a".repeat(64));
        byte[] body = "printf approved\n".getBytes(StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        String script = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol.script(
            workspace, 60, 16, 256, 8192, "ordinary", digest, "-", List.of(List.of("marker")));
        if (scenario.equals("ARGV")) script = script.substring(0, script.length() - " '-'".length());
        if (scenario.equals("COLLECTOR")) script = script.replace("subprocess.Popen(sys.argv[1:]", "subprocess.Popen(sys.argv[2:]");
        if (!scenario.equals("HELPER_GUARD")) script = DeploymentManagedHelperBundle.verifiedCommand(script, layout);
        var grants = new AtomicInteger();
        var reviews = new AtomicInteger();
        var exactRequest = new java.util.concurrent.atomic.AtomicReference<CommandRequest>();
        var exactIntent = new java.util.concurrent.atomic.AtomicReference<ActionIntent>();
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
            assertEquals("approveReviewedAction", method.getName());
            assertSame(exactIntent.get(), args[0]); assertSame(exactRequest.get(), ((java.util.Optional<?>) args[1]).orElseThrow());
            assertSame(fixture.task, args[4]); grants.incrementAndGet();
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), java.util.Optional.empty());
        });
        DeploymentCommandReviewModel model = (instructions, facts, intent, request, revision, supplied) -> {
            reviews.incrementAndGet();
            var current = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
            var invocation = current.at("/buildCollector/helperInvocation");
            assertEquals("build-run", invocation.path("verb").asText()); assertEquals(9, invocation.path("argumentCount").asInt());
            assertEquals("seconds", invocation.at("/arguments/2/name").asText());
            assertEquals("60", invocation.at("/arguments/2/value").asText());
            assertEquals("maxProcesses", invocation.at("/arguments/3/name").asText());
            assertEquals(digest, invocation.at("/executableStdin/sha256").asText());
            assertEquals("printf approved\n", current.path("completeExecutableInput").asText());
            assertEquals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.REVISION,
                current.path("platformContractRevision").asText());
            return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision(scenario.equals("DENY") ? "DENY" : "APPROVE", false, "Controlled independent verdict"), Map.of());
        };
        try (var request = new CommandRequest(script, scenario.equals("INPUT") ? "changed".getBytes(StandardCharsets.UTF_8) : body, Duration.ofSeconds(75), 65536);
             var replacement = new CommandRequest(script, "new request".getBytes(StandardCharsets.UTF_8), request.timeout(), request.outputLimitBytes())) {
            var intent = intent("deployment.build.run.v1", request, Set.of(new ActionTarget(server.id(), "deployment-build",
                scenario.equals("TARGET") ? workspace.candidateRoot() + "-other" : workspace.candidateRoot())));
            exactIntent.set(intent); exactRequest.set(request);
            if (scenario.equals("APPROVE")) {
                var result = DeploymentFixedCommandApproval.approve(context, host, intent, request, fixture.task,
                    "fixture-route", "AUTOMATIC", false, model, DeploymentCurrentCommandEvidence.NONE, true);
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, result.outcome()); assertTrue(result.value().isPresent());
            } else {
                var refused = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, host, intent,
                    scenario.equals("REQUEST") ? replacement : request, fixture.task, "fixture-route", "AUTOMATIC", false,
                    model, DeploymentCurrentCommandEvidence.NONE, true));
                assertEquals(scenario.equals("DENY") ? "DEPLOYMENT_COMMAND_REVIEW_DENIED" : scenario.equals("REQUEST")
                    ? "DEPLOYMENT_COMMAND_REQUEST_CHANGED" : "DEPLOYMENT_COMMAND_REVIEW_INVALID", refused.problem().eventCode());
            }
        }
        assertEquals(scenario.equals("APPROVE") || scenario.equals("DENY") ? 1 : 0, reviews.get());
        assertEquals(scenario.equals("APPROVE") ? 1 : 0, grants.get());
        assertTrue(fixture.requests.isEmpty(), "No provider call is needed for a fixed request/protocol boundary test");
    }

    @Test
    void sharedDependencyBytesAndStrictReceiptsPreserveOriginalProfiles() throws Exception {
        String source = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencies(layout);
        assertEquals("e288e3c73a34dfe01f67ba9fab5ee4984e542fa80802c1882f66a1948fa3e170", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))));
        assertEquals(source, DeploymentAgentToolchainPrograms.dependencies(layout));
        assertEquals(DeploymentAgentToolchainPrograms.BOOTSTRAP, gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.BOOTSTRAP);
        assertEquals(DeploymentAgentToolchainPrograms.command(List.of("NATIVE_BUILD", "a".repeat(32))),
            gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.command(List.of("NATIVE_BUILD", "a".repeat(32))));
        for (String manager : List.of("APT", "DNF")) for (String profile : List.of("NATIVE_BUILD", "PYTHON", "PHP", "RUBY", "HELPER_RUNTIME")) {
            var names = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.packages(manager, profile);
            assertEquals(names, DeploymentAgentToolchainPrograms.packages(manager, profile));
            var packages = new LinkedHashMap<String, String>(); names.forEach(name -> packages.put(name, "1.0"));
            var value = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("operation", "a".repeat(32), "ecosystem", profile, "manager", manager, "packages", packages, "lifecycle", gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DependencyReceiptFixture.lifecycle(profile, "a".repeat(32), 60, server, DEPENDENCY_TASK, layout)));
            String line = "TOOLCHAIN_DEPENDENCIES=" + value + "\n";
            var numeric = (com.fasterxml.jackson.databind.node.ObjectNode) value.deepCopy();
            numeric.put("operation", new java.math.BigInteger("1".repeat(32)));
            assertFalse(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyReceipt("TOOLCHAIN_DEPENDENCIES=" + numeric + "\n", profile, "1".repeat(32), 60, server, DEPENDENCY_TASK, layout), "Numeric operation cannot borrow textual identity");
            assertTrue(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyReceipt(line, profile, "a".repeat(32), 60, server, DEPENDENCY_TASK, layout));
            for (String mutation : List.of("DUPLICATE", "EXTRA", "OPERATION", "PROFILE", "MANAGER", "PACKAGES", "EMPTY_VERSION", "CONTROL_VERSION", "TAIL")) {
                var changed = (com.fasterxml.jackson.databind.node.ObjectNode) value.deepCopy();
                switch (mutation) {
                    case "EXTRA" -> changed.put("extra", "data");
                    case "OPERATION" -> changed.put("operation", "b".repeat(32));
                    case "PROFILE" -> changed.put("ecosystem", "OTHER");
                    case "MANAGER" -> changed.put("manager", "NONE");
                    case "PACKAGES" -> ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("packages")).remove(names.getFirst());
                    case "EMPTY_VERSION" -> ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("packages")).put(names.getFirst(), " ");
                    case "CONTROL_VERSION" -> ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("packages")).put(names.getFirst(), "1\n2");
                }
                String raw = changed.toString();
                if (mutation.equals("DUPLICATE")) raw = raw.replaceFirst("\\{", "{\"manager\":\"" + manager + "\",");
                if (mutation.equals("TAIL")) raw += "{}";
                assertFalse(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyReceipt("TOOLCHAIN_DEPENDENCIES=" + raw + "\n", profile, "a".repeat(32), 60, server, DEPENDENCY_TASK, layout), manager + profile + mutation);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"VALID", "SOURCE", "PROFILE", "HELPER", "ARGV", "ID", "LAUNCHER", "MISSING_TARGET", "EXTRA_TARGET", "SERVER", "OUTPUT", "REQUEST"})
    void nativeDependencyCommonProtocolBindsEveryCurrentByteAndTarget(String mutation) throws Exception {
        String profile = mutation.equals("PROFILE") ? "PYTHON" : mutation.equals("HELPER") ? "HELPER_RUNTIME" : "NATIVE_BUILD";
        String operation = mutation.equals("ID") ? "A".repeat(32) : "a".repeat(32);
        var args = new ArrayList<>(List.of(profile, operation, "60", server.id(), DEPENDENCY_TASK));
        if (mutation.equals("ARGV")) args.add("extra");
        String script = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.command(args);
        if (mutation.equals("LAUNCHER")) script = script.replace("/usr/bin/python3", "/bin/python3");
        String source = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencies(layout)
            + (mutation.equals("SOURCE") ? " " : "");
        var targets = new java.util.HashSet<>(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyTargets(server, "NATIVE_BUILD", "a".repeat(32), layout));
        if (mutation.equals("MISSING_TARGET")) targets.remove(targets.iterator().next());
        if (mutation.equals("EXTRA_TARGET")) targets.add(new ActionTarget(server.id(), "system-package", "curl"));
        if (mutation.equals("SERVER")) { targets.clear(); targets.addAll(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyTargets(new ServerId("other"), "NATIVE_BUILD", "a".repeat(32), layout)); }
        try (var request = new CommandRequest(script, source.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), mutation.equals("OUTPUT") ? 4096 : 65536);
             var changed = new CommandRequest(request.script(), "different".getBytes(StandardCharsets.UTF_8), request.timeout(), request.outputLimitBytes())) {
            var intent = intent(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.OPERATION, request, Set.copyOf(targets));
            var common = new DeploymentFixedCommandProtocol(owner);
            if (mutation.equals("VALID")) {
                var facts = common.inspect(intent, request, layout, task(UUID.fromString(DEPENDENCY_TASK)));
                assertEquals(source, facts.get("completeExecutableInput"));
                assertEquals("NATIVE_BUILD", ((Map<?, ?>) facts.get("toolchainDependencies")).get("profile"));
            } else assertThrows(IllegalArgumentException.class, () -> common.inspect(intent, mutation.equals("REQUEST") ? changed : request, layout, task(UUID.fromString(DEPENDENCY_TASK))));
        }
    }

    @Test
    void pythonProducerRecordBytesMatchTheJavaReceiptConsumer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        String operation = "a".repeat(32);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.valueToTree(Map.of("operation", operation, "ecosystem", "NATIVE_BUILD", "manager", "APT",
            "packages", gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.packages("APT", "NATIVE_BUILD")
                .stream().collect(java.util.stream.Collectors.toMap(name -> name, name -> "1.0")),
            "lifecycle", gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DependencyReceiptFixture.lifecycle("NATIVE_BUILD", operation, 60, server, DEPENDENCY_TASK, layout)));
        String checks = """
            import sys,json,hashlib
            payload=json.load(sys.stdin)
            g={'__name__':'pure_record_fixture'}
            exec(compile(payload['source'],'<current-canonical-dependency-source>','exec'),g)
            root=payload['receipt']; life=root['lifecycle']
            m={k:life[k] for k in ('unit','workerSourceSha256','server','task','totalSeconds','runtimeSeconds')}
            m.update(operation=root['operation'],profile=root['ecosystem'])
            m['description']='WTL_DEPENDENCY_2:'+m['operation']+':'+m['workerSourceSha256']
            ownership=g['dependency_ownership_record'](m,life['invocationId'])
            ownership_sha=hashlib.sha256(g['dependency_encode'](ownership)).hexdigest()
            ready={'invocationId':life['invocationId'],'groupIdentity':life['groupIdentity']}
            ack=g['dependency_ack_record'](m,ready,{'eventsIdentity':life['eventsIdentity']},ownership_sha)
            life['cleanupOwnershipSha256']=ownership_sha
            life['ackSha256']=hashlib.sha256(g['dependency_encode'](ack)).hexdigest()
            print('TOOLCHAIN_DEPENDENCIES='+json.dumps(root,sort_keys=True,separators=(',',':')))
            """;
        var python = java.nio.file.Path.of(System.getProperty("managed.test.python",
            java.nio.file.Files.isRegularFile(java.nio.file.Path.of("E:/Program/Python/Python3.12/python.exe"))
                ? "E:/Program/Python/Python3.12/python.exe" : "/usr/bin/python3"));
        assertTrue(java.nio.file.Files.isExecutable(python), "A real local Python interpreter is required");
        var log = temporary.resolve("record-interoperability.log");
        var process = new ProcessBuilder(python.toString(), "-I", "-B", "-S", "-c", checks)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(json.writeValueAsBytes(Map.of("source",
                    gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencies(layout), "receipt", root)));
            }
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
            String result = java.nio.file.Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), result);
            assertTrue(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyReceipt(
                result, "NATIVE_BUILD", operation, 60, server, DEPENDENCY_TASK, layout), "Actual Python canonical record bytes must bind the Java consumer");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); }
        }
    }

    @Test
    void dependencyLifecycleReceiptRejectsMissingStaleOrUnprovenTermination() throws Exception {
        String operation = "a".repeat(32);
        var frame = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.frame("NATIVE_BUILD", operation, 60, server, DEPENDENCY_TASK);
        try (var request = new CommandRequest(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.command(frame), new byte[0], Duration.ofSeconds(60), 65536)) {
            String valid = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DependencyReceiptFixture.receipt(request, "APT", layout);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            assertTrue(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyReceipt(
                valid, "NATIVE_BUILD", operation, 60, server, DEPENDENCY_TASK, layout));
            var retired = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(valid.substring("TOOLCHAIN_DEPENDENCIES=".length()));
            var retiredLife = (com.fasterxml.jackson.databind.node.ObjectNode) retired.get("lifecycle");
            retiredLife.put("proofKind", "KERNEL_CORE_EVENTS_RETIRED");retiredLife.putNull("finalPopulated");retiredLife.put("readErrno", 19);
            assertTrue(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyReceipt(
                "TOOLCHAIN_DEPENDENCIES=" + retired + "\n", "NATIVE_BUILD", operation, 60, server, DEPENDENCY_TASK, layout));
            for (String fault : List.of("MISSING", "UNIT", "SOURCE", "TIME", "OVERFLOW", "TASK", "EMPTY_INVOCATION", "NUMERIC_INVOCATION",
                "NONEMPTY", "LEGACY_FIELD", "STRING_BOOLEAN", "FORMAT_FLOAT", "NO_ACK", "NO_CORE_FD", "ACK_SHA", "OWNERSHIP_SHA",
                "NO_WAIT", "NONZERO_EXIT", "FLOAT_EXIT", "MAGIC", "READ_METHOD", "IDENTITY_BOOLEAN", "IDENTITY_OVERFLOW",
                "EVENTS_DEVICE", "SAME_INODE", "RETIRED_VALUE", "RETIRED_ERRNO", "MISSING_FINAL", "UNKNOWN_PROOF")) {
                var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(valid.substring("TOOLCHAIN_DEPENDENCIES=".length()));
                var life = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("lifecycle");
                switch (fault) {
                    case "MISSING" -> root.remove("lifecycle");
                    case "UNIT" -> life.put("unit", "windowstolinux-dependency-" + "c".repeat(32) + ".service");
                    case "SOURCE" -> life.put("workerSourceSha256", "c".repeat(64));
                    case "TIME" -> life.put("totalSeconds", 61);
                    case "OVERFLOW" -> life.put("totalSeconds", java.math.BigInteger.ONE.shiftLeft(64).add(java.math.BigInteger.valueOf(60)));
                    case "TASK" -> life.put("task", "22222222-2222-2222-2222-222222222222");
                    case "EMPTY_INVOCATION" -> life.put("invocationId", "0".repeat(32));
                    case "NUMERIC_INVOCATION" -> life.put("invocationId", new java.math.BigInteger("1".repeat(32)));
                    case "NONEMPTY" -> life.put("finalPopulated", 1);
                    case "LEGACY_FIELD" -> life.put("recursiveCgroupEmptyObserved", true);
                    case "STRING_BOOLEAN" -> life.put("stopCompleted", "true");
                    case "FORMAT_FLOAT" -> life.put("formatVersion", 2.0);
                    case "NO_ACK" -> life.put("ackVerified", false);
                    case "NO_CORE_FD" -> life.put("coreEventsBound", false);
                    case "ACK_SHA" -> life.put("ackSha256", "a".repeat(64));
                    case "OWNERSHIP_SHA" -> life.put("cleanupOwnershipSha256", "a".repeat(64));
                    case "NO_WAIT" -> life.put("launcherWaitCompleted", false);
                    case "NONZERO_EXIT" -> life.put("launcherExitCode", 1);
                    case "FLOAT_EXIT" -> life.put("launcherExitCode", 0.0);
                    case "MAGIC" -> life.put("coreEventsFilesystemMagic", 0);
                    case "READ_METHOD" -> life.put("freshReadMethod", "SEEK_THEN_READ");
                    case "IDENTITY_BOOLEAN" -> life.putArray("groupIdentity").add(true).add(1001);
                    case "IDENTITY_OVERFLOW" -> life.putArray("eventsIdentity").add(29).add(java.math.BigInteger.ONE.shiftLeft(64).add(java.math.BigInteger.valueOf(1002)));
                    case "EVENTS_DEVICE" -> life.putArray("eventsIdentity").add(30).add(1002);
                    case "SAME_INODE" -> life.set("eventsIdentity", life.get("groupIdentity").deepCopy());
                    case "RETIRED_VALUE" -> { life.put("proofKind", "KERNEL_CORE_EVENTS_RETIRED");life.put("readErrno", 19); }
                    case "RETIRED_ERRNO" -> { life.put("proofKind", "KERNEL_CORE_EVENTS_RETIRED");life.putNull("finalPopulated");life.put("readErrno", 5); }
                    case "MISSING_FINAL" -> life.remove("finalPopulated");
                    case "UNKNOWN_PROOF" -> life.put("proofKind", "PATH_MISSING");
                }
                assertFalse(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.dependencyReceipt(
                    "TOOLCHAIN_DEPENDENCIES=" + root + "\n", "NATIVE_BUILD", operation, 60, server, DEPENDENCY_TASK, layout), fault);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void trackedToolchainOperationsRequireTheSameExactOriginalInputInBothModes(boolean custom) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/tracked/helper", "/srv/tracked/apps", "/srv/tracked/state", "/srv/tracked/config", "/srv/tracked/data")) : this.layout;
        UUID original = UUID.randomUUID();
        var identity = new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.Identity(
            UUID.randomUUID().toString(), "sample-api", 1, "a".repeat(64), "b".repeat(64));
        var prepare = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.prepare(UUID.randomUUID(),
            original.toString(), identity, server.id(), layout,
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.NODE, "22", "-", 120, "-");
        var empty = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains("fixture", List.of());
        String encoded = java.util.Base64.getEncoder().encodeToString(
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.encode(empty).getBytes(StandardCharsets.UTF_8));
        var binding = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.bind(UUID.randomUUID(),
            original.toString(), identity, server.id(), layout, encoded);
        var targets = Set.of(new ActionTarget(server.id(), "task", original.toString()),
            new ActionTarget(server.id(), "deployment-build", layout.toolchains()));
        var common = new DeploymentFixedCommandProtocol(owner);
        var agent = new DeploymentAgentCommandProtocol(owner, Map::of);
        for (String verb : List.of("prepare-tracked", "bind-tracked", "observe-tracked", "cleanup-tracked")) {
            var originalRequest = verb.equals("bind-tracked") ? binding : prepare;
            String operation = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.operation(verb);
            String invocation = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.command(verb, originalRequest);
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(invocation, layout),
                    originalRequest.bytes(), Duration.ofSeconds(60), 32768)) {
                var intent = intent(operation, request, targets);
                assertEquals(common.inspect(intent, request, layout, task(original)), agent.inspect(intent, request, layout, task(original)));
                assertEquals("TRACKED_TOOLCHAIN_REQUEST", common.inspect(intent, request, layout, task(original)).get("inputKind"));
                assertThrows(IllegalArgumentException.class, () -> common.inspect(intent, request, layout, task(UUID.randomUUID())));
                var changed = intent.withSemantics(new ActionSemantics(operation,
                    Set.of(new ActionTarget(server.id(), "task", original.toString()))));
                assertThrows(IllegalArgumentException.class, () -> common.inspect(changed, request, layout, task(original)));
                assertThrows(IllegalArgumentException.class, () -> agent.inspect(changed, request, layout, task(original)));
                String changedBytes = new String(originalRequest.bytes(), StandardCharsets.UTF_8).replace("sample-api", "other-api");
                try (var wrong = new CommandRequest(request.script(), changedBytes.getBytes(StandardCharsets.UTF_8), request.timeout(), request.outputLimitBytes())) {
                    var wrongIntent = intent(operation, wrong, targets);
                    assertThrows(IllegalArgumentException.class, () -> common.inspect(wrongIntent, wrong, layout, task(original)));
                    assertThrows(IllegalArgumentException.class, () -> agent.inspect(wrongIntent, wrong, layout, task(original)));
                }
            }
        }
    }

    @Test
    void actualHostProbeProducesIdenticalCompleteProtocolEvidenceInBothModes() throws Exception {
        var original = task(UUID.randomUUID());
        String script = gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe.render();
        try (var request = new CommandRequest(script, new byte[0], Duration.ofMinutes(2), 65536)) {
            var intent = intent("deployment.candidate.host-probe.v1", request, Set.of(new ActionTarget(server.id(), "deployment-host", server.id())));
            var common = new DeploymentFixedCommandProtocol(owner).inspect(intent, request, layout, original);
            var agent = new DeploymentAgentCommandProtocol(owner, Map::of).inspect(intent, request, layout, original);
            assertEquals(common, agent);
            assertEquals("HOST", ((Map<?, ?>) common.get("platformProbe")).get("variant"));
            assertTrue(gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.facts(common, 1048576).contains(script.replace("\n", "\\n").substring(0, 30)));
        }
    }

    @Test
    void identicalCommonInputHasIdenticalEvidenceInBothModesAndChangedTargetsFail() throws Exception {
        var task = task(UUID.randomUUID());
        var common = new DeploymentFixedCommandProtocol(owner);
        var agent = new DeploymentAgentCommandProtocol(owner, () -> { throw new AssertionError("No Agent receipts for common input"); });
        try (var request = new CommandRequest(DeploymentCandidateChannel.capacityScript(layout), new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.candidate.capacity.v1", request, Set.of(new ActionTarget(server.id(),
                "deployment-workspace", DeploymentCandidateChannel.capacityTarget(layout))));
            assertEquals(common.inspect(intent, request, layout, task), agent.inspect(intent, request, layout, task));
            var changed = intent.withSemantics(new ActionSemantics("deployment.candidate.capacity.v1",
                Set.of(new ActionTarget(server.id(), "deployment-workspace", "/changed"))));
            assertThrows(IllegalArgumentException.class, () -> common.inspect(changed, request, layout, task));
            assertThrows(IllegalArgumentException.class, () -> agent.inspect(changed, request, layout, task));
        }
    }

    @Test
    void originalSnapshotCannotBeReviewedUnderAnotherTask() throws Exception {
        UUID original = UUID.randomUUID();
        String script = DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' 'snapshot-deployment' 'demo' '" + "a".repeat(64) + "' '" + original + "'", layout);
        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.publication.snapshot.v1", request, Set.of(
                new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo")),
                new ActionTarget(server.id(), "deployment-snapshot", layout.snapshotRoot("demo", original))));
            var common = new DeploymentFixedCommandProtocol(owner);
            assertTrue(common.inspect(intent, request, layout, task(original)).containsKey("snapshotTaskMatchesOriginalContext"));
            assertThrows(IllegalArgumentException.class, () -> common.inspect(intent, request, layout, task(UUID.randomUUID())));
        }
    }

    @Test
    void optionalObservationsCannotAdmitUnknownOperationsOrChangedExecutableInput() throws Exception {
        var task = task(UUID.randomUUID());
        var observed = new AtomicInteger();
        DeploymentCurrentCommandEvidence observations = (intent, request, supplied) -> { observed.incrementAndGet(); return Map.of("approved", true); };
        InteractionService host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{InteractionService.class}, (proxy, method, args) -> { throw new AssertionError("No host grant"); });
        DeploymentCommandReviewModel model = (instructions, facts, intent, request, revision, supplied) -> { throw new AssertionError("No model call"); };
        try (var request = new CommandRequest("printf original", "original".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30), 4096);
             var changed = new CommandRequest(request.script(), "changed".getBytes(StandardCharsets.UTF_8), request.timeout(), request.outputLimitBytes())) {
            var unknown = intent("deployment.unknown.v1", request, Set.of(new ActionTarget(server.id(), "task", task.id().toString())));
            var refused = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(owner, host, unknown, request, task,
                "fixture", "AUTOMATIC", false, model, observations, true));
            assertEquals("DEPLOYMENT_COMMAND_UNREGISTERED", refused.problem().eventCode());
            var original = intent("deployment.build.preflight.v1", request, unknown.semantics().orElseThrow().targets());
            var drift = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(owner, host, original, changed, task,
                "fixture", "AUTOMATIC", false, model, observations, true));
            assertEquals("DEPLOYMENT_COMMAND_REQUEST_CHANGED", drift.problem().eventCode());
            assertEquals(0, observed.get());
        }
    }

    @Test
    void agentOperationIsRejectedByCommonProtocolAndMalformedPrivateInputCannotBecomeOpaque() throws Exception {
        var common = new DeploymentFixedCommandProtocol(owner);
        var agent = new DeploymentAgentCommandProtocol(owner, Map::of);
        byte[] source = DeploymentAgentToolchainPrograms.dependencies(layout).getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest("/usr/bin/python3 -I -S -c 'unrecognized launcher'", source, Duration.ofSeconds(30), 65536)) {
            var intent = intent("deployment.agent.toolchain.dependencies.v1", request,
                Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains())));
            assertFalse(common.registered(intent)); assertTrue(agent.registered(intent));
            assertThrows(IllegalArgumentException.class, () -> common.inspect(intent, request, layout, task(UUID.randomUUID())));
            assertThrows(IllegalArgumentException.class, () -> agent.inspect(intent, request, layout, task(UUID.randomUUID())));
        }
    }

    @Test
    void agentRunRequiresTheAuthenticTaskWhileOriginalCandidateQueriesRemainSupported() throws Exception {
        UUID original = UUID.randomUUID();
        var current = task(original);
        var other = task(UUID.randomUUID());
        var agent = new DeploymentAgentCommandProtocol(owner, Map::of);
        byte[] body = "printf fixture\n".getBytes(StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        String invocation = "'" + layout.helperPath() + "' 'agent-run' 'demo' 'demo-aaaaaaaaaaaaaaaa' '" + original
            + "' '" + "a".repeat(64) + "' '10' '8192' 'ordinary' '" + sha + "' '-'";
        var targets = Set.of(new ActionTarget(server.id(), "deployment-build", layout.candidateRoot("demo-aaaaaaaaaaaaaaaa")));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(invocation, layout), body, Duration.ofSeconds(30), 8192)) {
            var intent = intent("deployment.agent.helper.v1", request, targets);
            assertTrue(agent.inspect(intent, request, layout, current).containsKey("agentExecutableInputBinding"));
            assertThrows(IllegalArgumentException.class, () -> agent.inspect(intent, request, layout, other));
        }
        String query = "'" + layout.helperPath() + "' 'candidate-query-task' 'demo' 'demo-aaaaaaaaaaaaaaaa' '" + original + "'";
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(query, layout), new byte[0], Duration.ofSeconds(30), 8192)) {
            var intent = intent("deployment.agent.helper.v1", request, targets);
            assertEquals("EMPTY", agent.inspect(intent, request, layout, other).get("inputKind"));
        }
    }

    @Test
    void taskLayoutDriftAfterModelOrHostCannotReturnAnExecutableGrant() throws Exception {
        var changedLayout = new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/review-protocol/helper", "/srv/review-protocol/apps", "/srv/review-protocol/state", "/srv/review-protocol/config", "/srv/review-protocol/data"));
        for (String phase : List.of("model", "host")) {
            var supplied = task(UUID.randomUUID());
            var captured = new java.util.concurrent.atomic.AtomicReference<>(layout);
            var grants = new AtomicInteger();
            var checkpoint = (gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("snapshot")) throw new AssertionError(method.getName());
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var node = json.createObjectNode().put("formatVersion", 1).put("task", supplied.id().toString()).put("layoutSha256", captured.get().digest());
                    node.set("layout", json.readTree(captured.get().canonical()));
                    return new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(0, node.toString(), false);
                });
            var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findCheckpoint") && args[0] == supplied) return java.util.Optional.of(checkpoint);
                    throw new AssertionError(method.getName());
                });
            var ai = (gold.debug.windowstolinux.appapi.ai.AiService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("configurationRevision")) return "fixture";
                    throw new AssertionError("No provider: " + method.getName());
                });
            var ui = (gold.debug.windowstolinux.appapi.ui.UiService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("environment")) return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false);
                    throw new AssertionError(method.getName());
                });
            var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> switch (method.getName()) { case "tasks" -> tasks; case "ai" -> ai; case "ui" -> ui; default -> throw new AssertionError(method.getName()); });
            var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
                assertEquals("approveReviewedAction", method.getName()); grants.incrementAndGet(); captured.set(changedLayout);
                return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                    java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), java.util.Optional.empty());
            });
            DeploymentCommandReviewModel model = (instruction, facts, intent, request, revision, actualTask) -> {
                if (phase.equals("model")) captured.set(changedLayout);
                return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("APPROVE", false, "controlled"), Map.of());
            };
            try (var request = new CommandRequest("printf fixture", new byte[0], Duration.ofSeconds(30), 4096)) {
                var intent = intent("deployment.build.preflight.v1", request, Set.of(new ActionTarget(server.id(), "task", supplied.id().toString())));
                var refusal = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, host, intent, request, supplied,
                    "fixture", "AUTOMATIC", false, model, DeploymentCurrentCommandEvidence.NONE, true));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", refusal.problem().eventCode());
                assertEquals(phase.equals("model") ? 0 : 1, grants.get());
            }
        }
    }

    @Test
    void completeProtocolSnapshotRejectsRemovedKeysAndNestedMutationAfterModelOrHost() throws Exception {
        for (String phase : List.of("model", "host")) {
            for (String change : List.of("removed-key", "nested-value")) {
                var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
                var context = reviewContext(fixture);
                var nested = new LinkedHashMap<String, Object>(); nested.put("value", "original");
                var protocolFacts = new LinkedHashMap<String, Object>();
                protocolFacts.put("operation", "deployment.build.preflight.v1");
                protocolFacts.put("requiredBinding", "present"); protocolFacts.put("nested", nested);
                Runnable drift = () -> { if (change.equals("removed-key")) protocolFacts.remove("requiredBinding"); else nested.put("value", "changed"); };
                var protocol = new DeploymentCommandProtocol() {
                    public boolean registered(ActionIntent intent) { return true; }
                    public Map<String, Object> inspect(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout selected, TaskContext supplied) {
                        assertSame(fixture.task, supplied); assertEquals(layout, selected); return protocolFacts;
                    }
                };
                var grants = new AtomicInteger();
                var reviews = new AtomicInteger();
                var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
                    assertEquals("approveReviewedAction", method.getName()); grants.incrementAndGet(); drift.run();
                    return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                        java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), java.util.Optional.empty());
                });
                DeploymentCommandReviewModel model = (instructions, facts, intent, request, revision, supplied) -> {
                    reviews.incrementAndGet();
                    @SuppressWarnings("unchecked") var frozenNested = (Map<String, Object>) facts.get("nested");
                    assertNotSame(nested, frozenNested);
                    assertEquals("original", frozenNested.get("value"));
                    assertThrows(UnsupportedOperationException.class, () -> frozenNested.put("value", "model-change"));
                    if (phase.equals("model")) drift.run();
                    assertEquals("original", frozenNested.get("value"), "Review bytes cannot alias subsequent protocol mutation");
                    return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("APPROVE", false, "controlled"), Map.of());
                };
                try (var request = new CommandRequest("printf fixture", new byte[0], Duration.ofSeconds(30), 4096)) {
                    var intent = intent("deployment.build.preflight.v1", request, Set.of(new ActionTarget(server.id(), "task", fixture.task.id().toString())));
                    var refusal = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context, host, intent, request, fixture.task,
                        "fixture-route", "AUTOMATIC", false, model, DeploymentCurrentCommandEvidence.NONE, true, protocol), phase + ":" + change);
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", refusal.problem().eventCode());
                    assertEquals(phase.equals("model") ? 0 : 1, grants.get()); assertEquals(1, reviews.get());
                    assertTrue(fixture.requests.isEmpty(), "No real provider call is needed for this boundary test");
                }
            }
        }
    }

    @Test
    void unchangedCompleteProtocolFactsReceiveFreshGrantsInBothModes() throws Exception {
        var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        var context = reviewContext(fixture);
        var grants = new AtomicInteger();
        var reviews = new AtomicInteger();
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
            assertEquals("approveReviewedAction", method.getName()); grants.incrementAndGet();
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())), java.util.Optional.empty());
        });
        DeploymentCommandReviewModel model = (instructions, facts, intent, request, revision, supplied) -> {
            assertEquals("deployment.candidate.capacity.v1", facts.get("operation")); reviews.incrementAndGet();
            return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("APPROVE", false, "controlled"), Map.of());
        };
        try (var request = new CommandRequest(DeploymentCandidateChannel.capacityScript(layout), new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.candidate.capacity.v1", request, Set.of(new ActionTarget(server.id(), "deployment-workspace", DeploymentCandidateChannel.capacityTarget(layout))));
            for (DeploymentCommandProtocol protocol : List.of(new DeploymentFixedCommandProtocol(context), new DeploymentAgentCommandProtocol(context, Map::of))) {
                var grant = DeploymentFixedCommandApproval.approve(context, host, intent, request, fixture.task, "fixture-route", "AUTOMATIC", false,
                    model, DeploymentCurrentCommandEvidence.NONE, true, protocol);
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, grant.outcome());
                assertTrue(grant.value().isPresent());
            }
        }
        assertEquals(2, reviews.get()); assertEquals(2, grants.get()); assertTrue(fixture.requests.isEmpty());
    }

    @Test
    void retainedEvidenceForwardsBothInstructionFormsWorkflowAndLifecycleInOrderAndRejectsNullKeyCollision() throws Exception {
        var events = new ArrayList<String>();
        WorkflowGrant workflow = () -> {};
        var first = observer("retained", events, workflow);
        var second = observer("current", events, workflow);
        var composition = DeploymentCommandReviewScope.class.getDeclaredMethod("retain", DeploymentCurrentCommandEvidence.class, DeploymentCurrentCommandEvidence.class);
        composition.setAccessible(true);
        var combined = (DeploymentCurrentCommandEvidence) composition.invoke(null, first, second);
        var task = task(UUID.randomUUID());
        try (var request = new CommandRequest("printf fixture", new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = intent("deployment.build.preflight.v1", request, Set.of(new ActionTarget(server.id(), "task", task.id().toString())));
            assertEquals(Map.of("retained", "facts", "current", "facts"), combined.facts(intent, request, task));
            assertEquals("retained-default\ncurrent-default", combined.reviewInstructions());
            assertEquals("retained-request\ncurrent-request", combined.reviewInstructions(intent, request, task));
            combined.workflowOpened(workflow); combined.verify(intent, request, task);
            combined.reviewed(intent, request, task, new DeploymentReviewDecision("APPROVE", false, "fixture"));
            assertEquals(List.of("retained-workflow", "current-workflow", "retained-verify", "current-verify", "retained-reviewed", "current-reviewed"), events);
            var missing = new LinkedHashMap<String, Object>(); missing.put("same", null);
            var collision = (DeploymentCurrentCommandEvidence) composition.invoke(null,
                (DeploymentCurrentCommandEvidence) (i, r, t) -> missing,
                (DeploymentCurrentCommandEvidence) (i, r, t) -> Map.of("same", "replacement"));
            assertThrows(IllegalArgumentException.class, () -> collision.facts(intent, request, task));
        }
    }

    private ModContext reviewContext(gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture fixture) {
        var ui = (gold.debug.windowstolinux.appapi.ui.UiService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (proxy, method, args) -> {
                if (method.getName().equals("environment")) return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false);
                throw new AssertionError(method.getName());
            });
        return (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("ui")) return ui;
            return method.invoke(fixture.context, args);
        });
    }

    private DeploymentCurrentCommandEvidence observer(String name, List<String> events, WorkflowGrant expected) {
        return new DeploymentCurrentCommandEvidence() {
            public Map<String, ?> facts(ActionIntent i, CommandRequest r, TaskContext t) { return Map.of(name, "facts"); }
            public String reviewInstructions() { return name + "-default"; }
            public String reviewInstructions(ActionIntent i, CommandRequest r, TaskContext t) { return name + "-request"; }
            public void workflowOpened(WorkflowGrant workflow) { assertSame(expected, workflow); events.add(name + "-workflow"); }
            public void verify(ActionIntent i, CommandRequest r, TaskContext t) { events.add(name + "-verify"); }
            public void reviewed(ActionIntent i, CommandRequest r, TaskContext t, DeploymentReviewDecision verdict) { events.add(name + "-reviewed"); }
        };
    }
    private ActionIntent intent(String operation, CommandRequest request, Set<ActionTarget> targets) {
        return RemoteActions.command(server, "a".repeat(64), request).withSemantics(new ActionSemantics(operation, targets));
    }
    private TaskContext task(UUID id) {
        return (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> { if (method.getName().equals("id")) return id; throw new AssertionError(method.getName()); });
    }
}
