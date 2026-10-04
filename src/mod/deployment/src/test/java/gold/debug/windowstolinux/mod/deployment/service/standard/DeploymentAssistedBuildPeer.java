package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Reads actual fixed command bytes and retains candidate/source/tool identities across real workflow calls. / 读取实际固定命令字节，跨真实流程调用保留候选、来源及工具身份。 */
final class DeploymentAssistedBuildPeer {
    final Map<String, String> archives = new LinkedHashMap<>();

    final Map<String, Long> sizes = new LinkedHashMap<>();

    final Map<String, Integer> creates = new LinkedHashMap<>(), uploads = new LinkedHashMap<>(),
        builds = new LinkedHashMap<>();

    final Map<String, List<String>> scripts = new LinkedHashMap<>();

    final Map<String, List<Long>> deadlines = new LinkedHashMap<>();

    final List<String> commands = new CopyOnWriteArrayList<>();

    final Set<UUID> tasks = new HashSet<>();

    final Set<String> present = new HashSet<>();

    final Set<String> publications = new HashSet<>();

    final Map<String, String> publicationDigests = new LinkedHashMap<>();

    final Map<String, Integer> cleanups = new LinkedHashMap<>();

    final List<String> toolBranches = new ArrayList<>();

    private final boolean failedAgain;

    boolean unknownBuild, active;

    /** Enables distinct source-preserving publication attempts only in the new health fixture. / 仅新健康夹具启用保留来源的独立发布尝试。 */
    boolean publicationAttempts;

    int healthFailuresRemaining, healthChecks, rollbackCalls, statusReads, sessionsOpened, sessionsClosed;

    boolean unknownHealth;

    final Map<String, String> current = new LinkedHashMap<>();

    final List<String> sentPublications = new ArrayList<>();

    final Map<String, String> snapshotOwners = new LinkedHashMap<>();

    final Map<String, UUID> snapshotOperations = new LinkedHashMap<>();

    final Set<String> discardedSnapshots = new HashSet<>();

    DeploymentAssistedBuildPeer(boolean failedAgain) {
        this.failedAgain = failedAgain;
    }

    RemoteSession session(TaskContext task) throws ApiException {
        tasks.add(task.id());
        sessionsOpened++;
        var lease = task.retain("controlled_ssh", "assisted-build-" + UUID.randomUUID(),
            "Controlled fixed build receipts");
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("facts"))
                    return new RemoteFacts("linux", "x86_64", "SHA256:assisted-build");
                if (method.getName().equals("close")) {
                    lease.close();
                    sessionsClosed++;
                    return null;
                }
                assertNotNull(args[1], "The exact original host grant must reach every send");
                if (method.getName().equals("upload")) {
                    var transfer = (TransferRequest) args[0];
                    var matcher = Pattern.compile("/work/([^/]+)/").matcher(transfer.remoteAbsolutePath());
                    assertTrue(matcher.find(), transfer.remoteAbsolutePath());
                    String candidate = matcher.group(1);
                    assertTrue(present.contains(candidate));
                    byte[] bytes = Files.readAllBytes(transfer.localPath());
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!publicationAttempts)
                        assertTrue(candidate.endsWith(sha.substring(0, 16)));
                    archives.put(candidate, sha);
                    sizes.put(candidate, (long) bytes.length);
                    uploads.merge(candidate, 1, Integer::sum);
                    Arrays.fill(bytes, (byte) 0);
                    return success(new TransferResult(sizes.get(candidate), sha, transfer.localPath(),
                        transfer.remoteAbsolutePath()));
                }
                assertEquals("execute", method.getName());
                return command((CommandRequest) args[0]);
            });
    }

    private OperationResult<CommandResult> command(CommandRequest request) throws Exception {
        String script = request.script();
        commands.add(script);
        if (script.contains("managed-helper' probe"))
            return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
        if (script.contains("DISTRO_ID="))
            return reply(
                "DISTRO_ID=ubuntu\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\nCPU_LEVEL=x86-64-v3\nSECURITY_MODULE=apparmor\nSECURITY_STATE=enabled\nFIREWALL=ufw\nFIREWALL_STATE=active\n");
        if (script.contains("stat -f -c"))
            return reply("1000000000 4096\n");
        if (script.contains("build-preflight"))
            return reply("");
        if (script.contains("prepare-toolchains 'prepare'")) {
            String version = script.contains("'1.25'") ? "1.25.1" : "1.24.1";
            toolBranches.add(version.startsWith("1.25") ? "1.25" : "1.24");
            String sha = version.startsWith("1.25") ? "d".repeat(64) : "b".repeat(64);
            return reply("TOOLCHAIN=GO|" + version + "|/usr/local/lib/windowstolinux/toolchains/versions/go-" + sha
                + "|MANAGED|https://official.example/go|" + sha + "\n");
        }
        if (script.contains("prepare-toolchains 'bind'")) {
            String encoded = script.substring(script.indexOf("'bind' '") + 8);
            encoded = encoded.substring(0, encoded.indexOf('\''));
            var tools = DeploymentToolchainBindingCodec
                .decode(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            return reply("TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(tools) + "\n");
        }
        if (script.contains("candidate-create-task")) {
            String id = candidate(script);
            assertTrue(present.add(id), "Original candidate cannot be recreated");
            creates.merge(id, 1, Integer::sum);
            return reply("CANDIDATE=/var/lib/windowstolinux/work/" + id + "\n");
        }
        if (script.contains("stat -c '%s'")) {
            String id = candidate(script);
            assertTrue(present.contains(id));
            return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + sizes.get(id) + "\nSHA256="
                + archives.get(id) + "\nOWNER=0\n");
        }
        if (script.contains("candidate-query-task"))
            return reply("CANDIDATE_STATE=" + (present.contains(candidate(script)) ? "owned" : "absent")
                + "\nBUILD_ACTIVE=no\n");
        if (script.contains("publication-original-status")) {
            statusReads++;
            if (publicationAttempts)
                assertFalse(current.containsKey(arguments(script).get(2)),
                    "Fresh proof must observe an actually rolled back empty target");
            return reply(
                "PUBLICATION_STATUS=1\nCURRENT=none\nCURRENT_KIND=none\nCURRENT_MODE=none\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE="
                    + (present.contains(candidate(script)) ? "owned" : "absent") + "\nBUILD_ACTIVE="
                    + (active ? "yes" : "no")
                    + "\nUNIT_PRESENT=no\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=0\n");
        }
        if (script.startsWith("exec /usr/bin/python3 -I -c ")) {
            String id = candidate(script);
            int round = builds.merge(id, 1, Integer::sum);
            assertEquals(1, uploads.get(id));
            assertTrue(present.contains(id));
            scripts.computeIfAbsent(id, ignored -> new ArrayList<>())
                .add(new String(request.stdin(), StandardCharsets.UTF_8));
            deadlines.computeIfAbsent(id, ignored -> new ArrayList<>()).add(request.timeout().toSeconds());
            if (unknownBuild) {
                active = true;
                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_BUILD_EXIT_UNKNOWN",
                        "Original physical build exit was not acknowledged", Map.of(), Optional.empty())));
            }
            int exit = publicationAttempts ? 0 : round == 1 || failedAgain ? 1 : 0;
            return reply("WTL_BUILD_REPORT=1\nEXIT=" + exit + "\nBYTES=0\nSHA256=" + "c".repeat(64)
                + "\nUID=1001\nENGINE=ordinary\nTOOL=GO_MODULE\nCOMPAT=0\nLIMIT=-\nCONFLICT=0\nHEAD=\nTAIL=\n");
        }
        if (script.contains("stage-config"))
            return reply("INPUT_STAGED=1\n");
        if (publicationAttempts && script.contains("snapshot-discard-")) {
            var values = arguments(script);
            assertTrue(Set.of("snapshot-discard-task", "snapshot-discard-attempt").contains(values.get(1)), script);
            boolean attempt = values.get(1).equals("snapshot-discard-attempt");
            assertEquals(attempt ? 6 : 5, values.size());
            String unit = values.get(2);
            assertEquals(snapshotOwners.get(unit), values.get(3));
            assertEquals(Set.of(UUID.fromString(values.get(4))), tasks);
            String operation = values.get(attempt ? 5 : 4);
            assertEquals(snapshotOperations.get(unit), UUID.fromString(operation));
            assertFalse(current.containsKey(unit), "Discard may only settle an actually unpublished empty snapshot");
            assertTrue(discardedSnapshots.add(unit + ":" + operation));
            return reply("SNAPSHOT_DISCARDED=1\n");
        }
        if (script.contains("snapshot-deployment")) {
            if (publicationAttempts) {
                var values = arguments(script);
                String unit = values.get(2);
                assertTrue(Set.of("snapshot-deployment", "snapshot-deployment-attempt").contains(values.get(1)),
                    script);
                assertFalse(current.containsKey(unit),
                    "Actual previous target must be empty before this reviewed snapshot");
                UUID task = UUID.fromString(values.get(4));
                assertEquals(Set.of(task), tasks);
                String previousOwner = snapshotOwners.putIfAbsent(unit, values.get(3));
                if (previousOwner != null)
                    assertEquals(previousOwner, values.get(3));
                UUID operation = task;
                if (values.get(1).equals("snapshot-deployment-attempt")) {
                    assertEquals(7, values.size());
                    operation = UUID.fromString(values.get(5));
                    assertNotEquals(task, operation);
                    assertEquals("empty", values.get(6));
                } else
                    assertEquals(5, values.size());
                snapshotOperations.put(unit, operation);
            }
            return reply("PREVIOUS=0\n");
        }
        if (script.contains("publish-deployment")) {
            var actual = arguments(script);
            if (!publicationAttempts)
                assertTrue(publications.add(actual.get(2)));
            else {
                publications.add(actual.get(2));
                sentPublications.add(actual.get(2));
                current.put(actual.get(2), actual.get(4));
            }
            assertTrue(present.contains(actual.get(3)));
            publicationDigests.put(actual.get(2), actual.get(4));
            return reply("PUBLISHED=1\n");
        }
        if (script.contains("application-health")) {
            healthChecks++;
            if (unknownHealth)
                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_HEALTH_UNKNOWN",
                        "Health observation lost", Map.of(), Optional.empty())));
            if (healthFailuresRemaining > 0) {
                healthFailuresRemaining--;
                return new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(new CommandResult(Optional.of(1), "HEALTHY=0\n", "", false)), Optional.empty());
            }
            return reply("HEALTHY=1\n");
        }
        if (publicationAttempts && script.contains("rollback-deployment")) {
            rollbackCalls++;
            current.remove(arguments(script).get(2));
            return reply("ROLLED_BACK=1\n");
        }
        if (script.contains("inspect-runtime"))
            return reply("KIND=deployment\nMODE=DAEMON\n");
        if (script.contains("observe-deployment"))
            return reply(
                "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\nSubState=running\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=123\n");
        if (script.contains("application-maintenance"))
            return reply("MAINTENANCE=end\n");
        if (script.contains("candidate-cleanup-task")) {
            String id = candidate(script);
            assertEquals(1, cleanups.merge(id, 1, Integer::sum));
            present.remove(id);
            return reply("CANDIDATE_CLEANED=1\n");
        }
        if (script.contains("'retain'"))
            return reply("RETAINED=1\n");
        throw new AssertionError("Unrecognized actual fixed build command: " + script);
    }

    private static String candidate(String script) {
        var matcher = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}").matcher(script);
        String result = null;
        while (matcher.find()) {
            String next = matcher.group();
            if (next.endsWith("-" + "b".repeat(16)) || next.endsWith("-" + "d".repeat(16)))
                continue;
            if (result == null)
                result = next;
            else
                assertEquals(result, next);
        }
        assertNotNull(result, script);
        return result;
    }

    private static List<String> arguments(String script) {
        var values = new ArrayList<String>();
        var matcher = Pattern.compile("'([^']*)'").matcher(script);
        while (matcher.find())
            values.add(matcher.group(1));
        return values;
    }

    private static OperationResult<CommandResult> reply(String value) {
        return success(new CommandResult(Optional.of(0), value, "", false));
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
