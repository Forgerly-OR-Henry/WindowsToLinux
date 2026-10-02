package gold.debug.windowstolinux.mod.deployment.service.agent;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.appapi.linux.*;

import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;

/** Controlled stateful SSH peer; production channels parse every receipt and enforce real task approvals. / 受控有状态 SSH 对等端；生产频道解析每份回执并要求真实任务审批。 */
final class DeploymentAgentTestPeer {
    final Map<String, Boolean> present = new ConcurrentHashMap<>();

    final Map<String, String> current = new ConcurrentHashMap<>();

    final List<String> rollbacks = new CopyOnWriteArrayList<>();

    final AtomicInteger commands = new AtomicInteger();

    final AtomicInteger connections = new AtomicInteger();

    volatile String fingerprint = "SHA256:agent-test";

    volatile boolean active;

    volatile boolean recoveryKnown;

    private final DeploymentAgentWorkflowTest.ScenarioType scenario;

    private final Map<String, String> candidates = new ConcurrentHashMap<>();

    private final Map<String, UUID> originalOwners = new ConcurrentHashMap<>();

    private final AtomicInteger health = new AtomicInteger();

    private String archive;

    private long archiveBytes;

    DeploymentAgentTestPeer(DeploymentAgentWorkflowTest.ScenarioType scenario) {
        this.scenario = scenario;
    }

    RemoteSession session(TaskContext task) throws ApiException {
        connections.incrementAndGet();
        var lease = task.retain("controlled_ssh", "controlled-ssh-" + UUID.randomUUID(), "Controlled SSH session");
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("facts"))
                    return new RemoteFacts("linux", "x86_64", fingerprint);
                if (method.getName().equals("close")) {
                    lease.close();
                    return null;
                }
                if (method.getName().equals("upload")) {
                    var request = (TransferRequest) args[0];
                    assertNotNull(args[1]);
                    byte[] bytes = Files.readAllBytes(request.localPath());
                    archive = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    archiveBytes = bytes.length;
                    Arrays.fill(bytes, (byte) 0);
                    return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new TransferResult(archiveBytes,
                        archive, request.localPath(), request.remoteAbsolutePath())), Optional.empty());
                }
                if (method.getName().equals("execute")) {
                    assertNotNull(args[1], "Every actual helper send needs the platform's exact grant");
                    return command((CommandRequest) args[0], task);
                }
                throw new AssertionError(method.getName());
            });
    }

    private OperationResult<CommandResult> command(CommandRequest request, TaskContext task) {
        String script = request.script();
        String id = script.contains("'demo-api'") ? "demo-api" : "demo-web";
        if (script.startsWith("exec /bin/bash --noprofile --norc -s"))
            return reply("HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
        if (script.contains("/usr/bin/sha256sum --check --status"))
            return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
        if (script.contains("/etc/os-release"))
            return reply(
                "DISTRO_ID=ubuntu\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\nCPU_LEVEL=x86-64-v3\nSECURITY_MODULE=apparmor\nSECURITY_STATE=enabled\nFIREWALL=ufw\nFIREWALL_STATE=active\n");
        if (script.contains("stat -f -c"))
            return reply("1000000000 4096\n");
        if (script.contains("candidate-create-task")) {
            originalOwners.put(id, task.id());
            var values = scalars(script);
            int at = values.indexOf(id);
            candidates.put(id, values.get(at + 1));
            present.put(id, true);
            String root = "/var/lib/windowstolinux/work/" + candidates.get(id);
            // Verify the production layout rather than constructing a successful DTO. / 核验生产布局而非构造成功 DTO。
            return reply("CANDIDATE=" + root + "\n");
        }
        if (script.contains("stat -c '%s'"))
            return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archiveBytes + "\nSHA256=" + archive
                + "\nOWNER=0\n");
        if (script.contains("candidate-query-task")) {
            var values = scalars(script);
            int at = values.indexOf(id);
            assertTrue(at >= 0, "The original application must be present in the exact query");
            assertEquals(id, values.get(at));
            assertEquals(candidates.get(id), values.get(at + 1));
            assertEquals(originalOwners.get(id).toString(), values.get(at + 2),
                "Readonly query must preserve original ownership UUID");
            return reply("CANDIDATE_STATE=" + (present.getOrDefault(id, false) ? "owned" : "absent") + "\nBUILD_ACTIVE="
                + (active ? "yes" : "no") + "\n");
        }
        if (script.contains("'agent-open'"))
            return reply("SOURCE_REVISION=" + "c".repeat(64) + "\n");
        if (script.contains("'agent-run'")) {
            int attempt = commands.incrementAndGet();
            if (scenario == DeploymentAgentWorkflowTest.ScenarioType.COMMAND_UNKNOWN
                || scenario == DeploymentAgentWorkflowTest.ScenarioType.HISTORY_INSPECT) {
                active = true;
                return unknown("CONTROLLED_BUILD_RECEIPT_LOST");
            }
            return attempt == 1 ? reply(1, "actual controlled compile failed\n") : reply("controlled build output\n");
        }
        if (script.contains("'agent-seal'"))
            return reply("DIGEST=" + "d".repeat(64) + "\n");
        if (script.contains("'stage-config'"))
            return reply("INPUT_STAGED=1\n");
        if (script.contains("'snapshot-deployment'"))
            return reply("PREVIOUS=0\n");
        if (script.contains("'publish-deployment'")) {
            var values = scalars(script);
            int at = values.indexOf("publish-deployment");
            assertEquals(id, values.get(at + 1));
            current.put(id, values.get(at + 3));
            if (scenario == DeploymentAgentWorkflowTest.ScenarioType.PUBLISH_UNKNOWN && id.equals("demo-web"))
                return unknown("CONTROLLED_PUBLICATION_RECEIPT_LOST");
            return reply("PUBLISHED=1\n");
        }
        if (script.contains("'application-health'")) {
            if (scenario == DeploymentAgentWorkflowTest.ScenarioType.WHOLE_HEALTH_FAILURE
                && health.incrementAndGet() == 5)
                return reply(1, "HEALTH=failed\n");
            return reply("HEALTHY=1\n");
        }
        if (script.contains("'observe-deployment'"))
            return reply(
                "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\nSubState=running\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=123\n");
        if (script.contains("'rollback-deployment'") || script.contains("'rollback-deployment-first'")) {
            current.remove(id);
            rollbacks.add(id);
            return reply("ROLLED_BACK=1\n");
        }
        if (script.contains("'publication-original-status'")) {
            if (scenario == DeploymentAgentWorkflowTest.ScenarioType.PUBLISH_UNKNOWN && !recoveryKnown)
                return unknown("CONTROLLED_STATUS_NOT_YET_AVAILABLE");
            String value = current.get(id);
            return reply("PUBLICATION_STATUS=1\nCURRENT=" + (value == null ? "none" : value) + "\nCURRENT_KIND="
                + (value == null ? "none" : "deployment") + "\nCURRENT_MODE=" + (value == null ? "none" : "DAEMON")
                + "\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE="
                + (present.getOrDefault(id, false) ? "owned" : "absent") + "\nBUILD_ACTIVE=no\nUNIT_PRESENT="
                + (value == null ? "no" : "yes") + "\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT="
                + (value == null ? 0 : 1) + "\n");
        }
        if (script.contains("candidate-cleanup-task")) {
            present.put(id, false);
            return reply("CANDIDATE_CLEANED=1\n");
        }
        if (script.contains("'application-maintenance'"))
            return reply("MAINTENANCE=end\n");
        if (script.contains("'inspect-runtime'"))
            return reply("KIND=deployment\nMODE=DAEMON\n");
        throw new AssertionError("Unexpected controlled SSH script: " + script);
    }

    private static List<String> scalars(String script) {
        var values = new ArrayList<String>();
        var matcher = Pattern.compile("'([^']*)'").matcher(script);
        while (matcher.find())
            values.add(matcher.group(1));
        return values;
    }

    private static OperationResult<CommandResult> reply(String output) {
        return reply(0, output);
    }

    private static OperationResult<CommandResult> reply(int exit, String output) {
        return new OperationResult<>(Outcome.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(exit), output, "", false)), Optional.empty());
    }

    private static OperationResult<CommandResult> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Controlled original receipt is unavailable", Map.of(), Optional.empty())));
    }
}
