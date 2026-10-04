package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentManagedHelperTest {
    @TempDir
    Path temporary;

    @Test
    void packagedBundlePreservesTheCompleteOldByteContractAndBashSyntax() throws Exception {
        byte[] bytes = DeploymentManagedHelperBundle.renderScript().getBytes(StandardCharsets.UTF_8);
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        String script = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(script.contains("# @compat:"));
        assertTrue(script.contains("prepare-toolchains)"));
        assertTrue(script.contains("backup-create)"));
        assertTrue(script.contains("agent-open)"));
        assertTrue(script.contains("database-restore-candidate)"));
        assertTrue(script.contains("printf 'HELPER=1\\nPROTOCOL=%s\\n'"));
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assertTrue(Files.isExecutable(Path.of(bash)), "A real Bash binary is required for this contract test");
        Path file = temporary.resolve("managed-helper");
        Files.write(file, bytes);
        assertBashSyntax(bash, file);
        Path installer = temporary.resolve("managed-installer");
        Files.writeString(installer, DeploymentHelperInstallerScript.render());
        assertBashSyntax(bash, installer);
    }

    @Test
    void trackedInstallerPersistsOriginalIdentityBeforePackagesAndQueriesWithoutHelper() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        var task = UUID.fromString("12345678-1234-1234-1234-123456789012");
        var operation = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        String serverDigest = "a".repeat(64), bodyDigest = "b".repeat(64);
        String script = DeploymentHelperInstallerScript.renderTracked(task, operation, serverDigest, bodyDigest);
        String status = DeploymentHelperInstallerScript.renderStatus(operation);
        assertTrue(script.indexOf("PHASE=STARTED") < script.indexOf("apt-get"));
        assertTrue(script.indexOf("PHASE=SUCCEEDED") < script.lastIndexOf("HELPER_INSTALL=1"));
        assertTrue(script.contains("bootstrap_trusted_directory /var/lib"));
        assertTrue(script.contains("set -C; printf"));
        assertTrue(status.contains("/usr/bin/cat -- \"$bootstrap_receipt\""));
        assertFalse(status.contains("python"));
        assertFalse(status.contains("install -d"));
        assertFalse(status.contains("managed-helper'"));
        assertTrue(DeploymentHelperInstallerScript.receipt(task, operation, serverDigest, bodyDigest, "SUCCEEDED")
            .contains("HELPER_SHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256));
        Path installer = temporary.resolve("tracked-installer");
        Path query = temporary.resolve("tracked-status");
        Files.writeString(installer, script);
        Files.writeString(query, status);
        assertBashSyntax(bash, installer);
        assertBashSyntax(bash, query);
    }

    private static void assertBashSyntax(String bash, Path file) throws Exception {
        Process process = new ProcessBuilder(bash, "--noprofile", "--norc", "-n", file.toString()).start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void exactApprovedInstallThenProbeAcceptsOnlyThePackagedIdentity() throws Exception {
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok("package log\n" + installTail(), "Created symlink\n"));
        replies.add(ok(probeOutput(), ""));
        var requests = new ArrayList<CommandRequest>();
        var intents = new ArrayList<ActionIntent>();
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(intents),
            new ServerId("Server-Exact"), task(), "reviewed-plan-1");
        var result = helper.install();
        assertEquals(TaskResult.SUCCEEDED, result.outcome());
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, result.value().orElseThrow().sha256());
        assertEquals(2, requests.size());
        assertEquals(List.of("deployment.helper.install.v1", "deployment.helper.probe.v1"),
            intents.stream().map(i -> i.semantics().orElseThrow().operation()).toList());
        assertEquals("Server-Exact", intents.getFirst().serverIds().getFirst());
        String installer = new String(requests.getFirst().stdin(), StandardCharsets.UTF_8);
        assertTrue(installer.contains("[ \"$(id -u)\" -eq 0 ] || exit 64"));
        assertTrue(installer.contains("/usr/bin/sha256sum --check --status"));
        assertTrue(installer.contains("PREPARE_REJECT=recovery-unit-conflict"));
        assertTrue(installer.endsWith("printf 'HELPER_INSTALL=1\\nPROTOCOL=14\\nSHA256="
            + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n"));
    }

    @Test
    void malformedProbeAndUnconfirmedInstallNeverCauseReplayOrSuccess() throws Exception {
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok("FORGED=1\n" + probeOutput(), ""));
        var requests = new ArrayList<CommandRequest>();
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
            new ServerId("exact"), task(), "revision");
        assertEquals(TaskResult.FAILED, helper.probe().outcome());
        assertEquals(1, requests.size());

        replies.add(
            new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                "SSH_COMMAND_EXIT_UNKNOWN", "Unconfirmed", Map.of(), Optional.empty()))));
        assertEquals(TaskResult.UNKNOWN, helper.install().outcome());
        assertEquals(2, requests.size());
    }

    @Test
    void approvalCancellationAndConfirmedPreDispatchCancellationDoNotInstall() throws Exception {
        var requests = new ArrayList<CommandRequest>();
        var denied = new InteractionService() {
            @Override
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) {
                return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            }

            @Override
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task) {
                throw new AssertionError("No text input expected");
            }
        };
        var helper = new DeploymentManagedHelper(session(new ArrayDeque<>(), requests), denied, new ServerId("exact"),
            task(), "revision");
        assertEquals(TaskResult.CANCELLED, helper.install().outcome());
        assertTrue(requests.isEmpty());

        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty()));
        helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
            new ServerId("exact"), task(), "revision");
        assertEquals(TaskResult.CANCELLED, helper.install().outcome());
        assertEquals(1, requests.size());
    }

    @Test
    void incompleteInstallAndPostInstallProbeNeverCauseASecondInstall() throws Exception {
        var badInstall = List.of(new CommandResult(Optional.of(0), installTail(), "", true),
            new CommandResult(Optional.empty(), installTail(), "", false),
            new CommandResult(Optional.of(0), "incomplete", "", false));
        for (var result : badInstall) {
            var replies = new ArrayDeque<OperationResult<CommandResult>>();
            replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(result), Optional.empty()));
            var requests = new ArrayList<CommandRequest>();
            var helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
                new ServerId("exact"), task(), "revision");
            assertEquals(TaskResult.UNKNOWN, helper.install().outcome());
            assertEquals(1, requests.size());
        }
        for (var later : List.of(
            new OperationResult<CommandResult>(TaskResult.CANCELLED, Optional.empty(), Optional.empty()),
            ok("PROTOCOL=9\n", ""))) {
            var replies = new ArrayDeque<OperationResult<CommandResult>>();
            replies.add(ok(installTail(), "Package warning\n"));
            replies.add(later);
            var requests = new ArrayList<CommandRequest>();
            var helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
                new ServerId("exact"), task(), "revision");
            assertEquals(TaskResult.UNKNOWN, helper.install().outcome());
            assertEquals(2, requests.size());
        }
    }

    @Test
    void hostAdmissionDenialKeepsItsOriginalProblemAndNeverProbes() {
        var denied = new Problem(ProblemCode.ACCESS_DENIED, "REMOTE_GRANT_REJECTED", "Grant rejected", Map.of(),
            Optional.empty());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute")) {
                    calls.incrementAndGet();
                    throw new ApiException(denied);
                }
                throw new AssertionError(method.getName());
            });
        var helper = new DeploymentManagedHelper(session, reviews(new ArrayList<>()), new ServerId("exact"), task(),
            "revision");
        ApiException failure = assertThrows(ApiException.class, helper::install);
        assertSame(denied, failure.problem());
        assertEquals(1, calls.get());
    }

    private static TaskContext task() {
        return (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("id"))
                    return UUID.fromString("12345678-1234-1234-1234-123456789012");
                throw new AssertionError(method.getName());
            });
    }

    private static InteractionService reviews(List<ActionIntent> intents) {
        return new InteractionService() {
            @Override
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) {
                intents.add(intent);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())),
                    Optional.empty());
            }

            @Override
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task) {
                throw new AssertionError("No input expected");
            }
        };
    }

    private static RemoteSession session(ArrayDeque<OperationResult<CommandResult>> replies,
                                         List<CommandRequest> requests) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute")) {
                    requests.add(((CommandRequest) args[0]).snapshot());
                    return replies.removeFirst();
                }
                if (method.getName().equals("close"))
                    throw new AssertionError("Borrowed session must remain caller-owned");
                throw new AssertionError(method.getName());
            });
    }

    private static OperationResult<CommandResult> ok(String out, String err) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), out, err, false)),
            Optional.empty());
    }

    private static String installTail() {
        return "HELPER_INSTALL=1\nPROTOCOL=14\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
    }

    private static String probeOutput() {
        return "HELPER=1\nPROTOCOL=14\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
    }
}
