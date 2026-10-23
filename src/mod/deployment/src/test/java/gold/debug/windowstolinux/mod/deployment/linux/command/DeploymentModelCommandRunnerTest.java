package gold.debug.windowstolinux.mod.deployment.linux.command;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentModelCommandRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REVISION = "a".repeat(64);

    private String currentRevision = REVISION;

    private final UUID taskId = UUID.randomUUID();

    private final ServerId server = new ServerId("controlled-host");

    private final AtomicInteger sends = new AtomicInteger(), queries = new AtomicInteger();

    private final AtomicInteger connections = new AtomicInteger();

    private final Map<UUID, String> exactGrants = new HashMap<>();

    private MemoryCheckpoint checkpoint;

    private MemoryCheckpoint layoutCheckpoint;

    private gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout commandLayout =
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();

    private String receipt;

    private boolean deny, loseReply, receiptReady = true;

    private boolean truncatedReply, truncatedReceipt, unknownExit, queryException;

    private String changedReceiptField;

    private boolean denyStatus;

    private Runnable afterStatusApproval = () -> {
    };

    private Runnable afterStatusExecute = () -> {
    };

    private final AtomicInteger statusApprovals = new AtomicInteger();

    private final List<String> commandSequence = new ArrayList<>();

    private final TaskContext task = proxy(TaskContext.class, (name, args) -> switch (name) {
        case "id" -> taskId;
        case "cancelToken" -> (CancelToken) () -> false;
        case "progress" -> null;
        default -> throw new AssertionError(name);
    });

    @Test
    void readableBodyAndRationaleRequireTheExactProductEnvelope() throws Exception {
        UUID operation = UUID.randomUUID();
        String script = "builtin printf 'model body\\n'";
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(script.getBytes(StandardCharsets.UTF_8)));
        String executable = DeploymentModelCommandIdentity.envelope(taskId, operation, digest, script, "Observed host gap\nnot a grant");
        var facts = DeploymentModelCommandIdentity.executableFacts(executable);
        assertEquals(script, facts.get("modelScript"));
        assertEquals("Observed host gap\nnot a grant", facts.get("rationale"));
        assertEquals(taskId.toString(), facts.get("task"));
        assertEquals(digest, facts.get("bodySha256"));
        assertFalse(script.contains("model-command-operations"));
        for (String changed : List.of(executable.replace("model body", "changed body"),
            executable.replace("set -C", "set +C"), executable + "\nbuiltin printf altered", "builtin printf no-envelope"))
            assertThrows(IllegalArgumentException.class, () -> DeploymentModelCommandIdentity.executableFacts(changed));
    }

    @Test
    void exactReviewPrecedesDurableDispatchAndNonzeroExitIsRealFeedback() throws Exception {
        var context = context();
        String output = DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION, "exit 7", 10, "Controlled failure");
        assertTrue(output.contains("EXIT=7"));
        assertEquals(receipt + "\nactual output\n", output);
        assertEquals("ENDED", facts().path("commands").get(0).path("phase").asText());
        assertEquals("7", facts().path("commands").get(0).path("exit").asText());
        assertEquals(1, sends.get());
        assertEquals(1, queries.get());
        DeploymentModelCommandRunner.releaseKnown(context, task);
        assertTrue(checkpoint.snapshot().released());
    }

    @Test
    void truncatedOutputStillQueriesExactReceiptWithoutTreatingPartialDataAsBusinessProof() throws Exception {
        truncatedReply = true;
        var context = context();
        String output = DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION, "exit 7", 10, "Controlled partial output");
        var item = facts().path("commands").get(0);
        assertTrue(output.startsWith(DeploymentModelCommandIdentity.header(taskId, UUID.fromString(item.path("operation").asText()), item.path("body").asText())
            + "EXIT=7\n\nOUTPUT_TRUNCATED=1\n"));
        assertTrue(output.contains("not proof of publication, health, sealed artifacts or acceptance"));
        assertTrue(output.endsWith("actual output\n"));
        assertEquals("ENDED", item.path("phase").asText());
        assertEquals("7", item.path("exit").asText());
        assertEquals(List.of("EXECUTE", "QUERY"), commandSequence);
        assertEquals(1, sends.get());
        assertEquals(1, queries.get());
        assertFalse(DeploymentModelCommandRunner.unconfirmed(context, task));
        DeploymentModelCommandRunner.releaseKnown(context, task);
        assertTrue(checkpoint.snapshot().released());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MISSING", "TASK", "OPERATION", "BODY_SHA256", "FORMAT", "TRUNCATED", "EXCEPTION"})
    void truncatedOutputKeepsDispatchedIdentityWhenReceiptIsNotExactAndComplete(String scenario) throws Exception {
        truncatedReply = true;
        receiptReady = !scenario.equals("MISSING");
        truncatedReceipt = scenario.equals("TRUNCATED");
        queryException = scenario.equals("EXCEPTION");
        changedReceiptField = Set.of("TASK", "OPERATION", "BODY_SHA256", "FORMAT").contains(scenario) ? scenario : null;
        var context = context();
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION,
            "exit 7", 10, "Controlled unconfirmed receipt"));
        var original = facts().path("commands").get(0).deepCopy();
        assertEquals("DISPATCHED", original.path("phase").asText());
        assertFalse(original.has("exit"));
        assertTrue(DeploymentModelCommandRunner.unconfirmed(context, task));
        assertThrows(IllegalStateException.class, () -> DeploymentModelCommandRunner.releaseKnown(context, task));
        assertEquals(original, facts().path("commands").get(0));
        assertFalse(checkpoint.snapshot().released());
        assertEquals(List.of("EXECUTE", "QUERY"), commandSequence);
        assertEquals(1, sends.get());
        assertEquals(1, queries.get());
    }

    @Test
    void truncatedOutputUsesCapturedLayoutReceiptAndRetainsChangedLayoutAsUnknown() throws Exception {
        for (boolean changed : List.of(false, true)) {
            var frame = new DeploymentModelCommandRunnerTest();
            frame.truncatedReply = true;
            frame.commandLayout = new gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout(
                new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths("/usr/local/lib/controlled-model",
                    "/opt/controlled-model/apps", "/var/lib/controlled-model", "/etc/opt/controlled-model", "/var/opt/controlled-model"));
            var context = frame.context();
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), frame.task, frame.commandLayout);
            frame.changedReceiptField = changed ? "LAYOUT_SHA256" : null;
            if (changed) {
                assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, frame.session(), frame.server, frame.task,
                    REVISION, "exit 7", 10, "Controlled changed layout receipt"));
                assertEquals("DISPATCHED", frame.facts().path("commands").get(0).path("phase").asText());
                assertTrue(DeploymentModelCommandRunner.unconfirmed(context, frame.task));
                assertFalse(frame.checkpoint.snapshot().released());
            } else {
                String output = DeploymentModelCommandRunner.execute(context, frame.session(), frame.server, frame.task,
                    REVISION, "exit 7", 10, "Controlled captured layout receipt");
                assertTrue(output.startsWith("FORMAT=2\n"));
                assertTrue(output.contains("LAYOUT_SHA256=" + frame.commandLayout.digest() + "\nEXIT=7\n\nOUTPUT_TRUNCATED=1"));
                assertEquals("ENDED", frame.facts().path("commands").get(0).path("phase").asText());
                DeploymentModelCommandRunner.releaseKnown(context, frame.task);
                assertTrue(frame.checkpoint.snapshot().released());
            }
            assertEquals(List.of("EXECUTE", "QUERY"), frame.commandSequence);
            assertEquals(1, frame.sends.get());
            assertEquals(1, frame.queries.get());
        }
    }

    @Test
    void truncatedOutputWithUnobservedExitDoesNotQueryOrReleaseOriginalOperation() throws Exception {
        truncatedReply = true;
        unknownExit = true;
        var context = context();
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION,
            "exit 7", 10, "Controlled missing exit"));
        assertEquals(List.of("EXECUTE"), commandSequence);
        assertEquals(1, sends.get());
        assertEquals(0, queries.get());
        assertTrue(DeploymentModelCommandRunner.unconfirmed(context, task));
        assertFalse(checkpoint.snapshot().released());
    }

    @Test
    void refusedCommandNeverDispatchesAndReleasesUnsentIntent() throws Exception {
        deny = true;
        var context = context();
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION,
            "exit 7", 10, "Controlled refusal"));
        assertEquals("INTENT", facts().path("commands").get(0).path("phase").asText());
        assertEquals(0, sends.get());
        assertEquals(0, queries.get());
        DeploymentModelCommandRunner.releaseKnown(context, task);
        assertTrue(checkpoint.snapshot().released());
    }

    @Test
    void lostReplyIsRecoveredFromOriginalReceiptWithoutResending() throws Exception {
        loseReply = true;
        receiptReady = false;
        var context = context();
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION,
            "exit 7", 10, "Controlled lost reply"));
        assertEquals("DISPATCHED", facts().path("commands").get(0).path("phase").asText());
        assertTrue(DeploymentModelCommandRunner.unconfirmed(context, task));
        currentRevision = "b".repeat(64);
        assertThrows(IllegalStateException.class, () -> DeploymentModelCommandRunner.replaceConfiguration(context, task, REVISION, currentRevision));
        assertEquals(REVISION, facts().path("modelRevision").asText());
        currentRevision = REVISION;
        assertThrows(IllegalStateException.class, () -> DeploymentModelCommandRunner.releaseKnown(context, task));
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.reconcile(context, task));
        assertFalse(checkpoint.snapshot().released());
        receiptReady = true;
        assertTrue(DeploymentModelCommandRunner.reconcile(context, task));
        assertFalse(DeploymentModelCommandRunner.unconfirmed(context, task));
        assertEquals(1, sends.get());
        assertEquals(2, queries.get());
        assertTrue(checkpoint.snapshot().released());
    }

    @Test
    void recoveryReviewerPreservesOriginalCommandAndUnknownReceipt() throws Exception {
        loseReply = true;
        var context = context();
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION,
            "exit 7", 10, "Original lost reply"));
        var original = facts().path("commands").get(0).deepCopy();
        currentRevision = "b".repeat(64);
        DeploymentModelCommandRunner.requireRecoveryIdentity(context, task, server, REVISION);
        receiptReady = false;
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.reconcile(context, task, currentRevision));
        assertEquals(original, facts().path("commands").get(0));
        assertFalse(checkpoint.snapshot().released());
        receiptReady = true;
        assertTrue(DeploymentModelCommandRunner.reconcile(context, task, currentRevision));
        assertEquals(REVISION, facts().path("modelRevision").asText());
        var settled = facts().path("commands").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) settled).put("phase", "DISPATCHED").remove("exit");
        assertEquals(original, settled);
        assertEquals(1, sends.get(), "Recovery must not dispatch the old script again");
        assertTrue(checkpoint.snapshot().released());
    }

    @Test
    void changedRecoveryReviewerBlocksBeforeConnectionAndRetainsOriginal() throws Exception {
        loseReply = true;
        var context = context();
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION,
            "exit 7", 10, "Original lost reply"));
        var original = checkpoint.snapshot().payload();
        currentRevision = "c".repeat(64);
        assertThrows(ApiException.class, () -> DeploymentModelCommandRunner.reconcile(context, task, "b".repeat(64)));
        assertEquals(0, connections.get());
        assertEquals(original, checkpoint.snapshot().payload());
        assertThrows(SecurityException.class, () -> DeploymentModelCommandRunner.requireRecoveryIdentity(context, task, server, currentRevision));
        assertFalse(checkpoint.snapshot().released());
    }

    @Test
    void changedReceiptIdentityCannotReleaseUnknownCommand() throws Exception {
        loseReply = true;
        var context = context();
        assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION,
            "exit 7", 10, "Controlled identity mismatch"));
        receipt = receipt.replace(taskId.toString(), UUID.randomUUID().toString());
        assertFalse(DeploymentModelCommandRunner.reconcile(context, task));
        assertEquals(1, sends.get());
        assertFalse(checkpoint.snapshot().released());
    }

    @Test
    void explicitSettledModelReplacementPreservesOriginalOperationsAndBudgets() throws Exception {
        var context = context();
        DeploymentModelCommandRunner.execute(context, session(), server, task, REVISION, "exit 7", 10, "First model command");
        var original = facts().path("commands").get(0).deepCopy();
        currentRevision = "b".repeat(64);
        assertThrows(SecurityException.class, () -> DeploymentModelCommandRunner.execute(context, session(), server, task,
            currentRevision, "exit 7", 10, "Silent replacement is refused"));
        assertEquals(1, sends.get());
        DeploymentModelCommandRunner.replaceConfiguration(context, task, REVISION, currentRevision);
        DeploymentModelCommandRunner.execute(context, session(), server, task, currentRevision, "exit 7", 20, "Reviewed replacement command");
        assertEquals(original, facts().path("commands").get(0));
        assertEquals(currentRevision, facts().path("modelRevision").asText());
        assertEquals(2, facts().path("commands").size());
        assertEquals(20, facts().path("commands").get(1).path("seconds").asInt());
        assertEquals(2, sends.get());
        assertEquals(2, queries.get());
        DeploymentModelCommandRunner.releaseKnown(context, task);
        assertTrue(checkpoint.snapshot().released());
    }

    @Test
    void receiptStatusRequiresDispatchedCheckpointBeforeEveryFreshGrant() throws Exception {
        for (String change : List.of("DENY", "APPROVAL_CHANGE", "RESULT_CHANGE")) {
            boolean mutate = !change.equals("DENY");
            var f = new DeploymentModelCommandRunnerTest();
            var context = f.context();
            f.loseReply = true;
            assertThrows(Exception.class, () -> DeploymentModelCommandRunner.execute(context, f.session(), f.server, f.task,
                REVISION, "exit 7", 10, "Original lost reply"));
            String original = f.checkpoint.snapshot().payload();
            f.loseReply = false;
            Runnable mutation = () -> {
                try {
                    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) f.facts();
                    changed.put("fingerprint", "changed-target");
                    f.checkpoint.advance(f.checkpoint.snapshot(), changed.toString());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            };
            if (change.equals("APPROVAL_CHANGE")) f.afterStatusApproval = mutation;
            else if (change.equals("RESULT_CHANGE")) f.afterStatusExecute = mutation;
            else f.denyStatus = true;
            assertThrows(Exception.class, () -> DeploymentModelCommandRunner.reconcile(context, f.task));
            assertEquals(1, f.sends.get());
            assertEquals(change.equals("RESULT_CHANGE") ? 1 : 0, f.queries.get());
            assertEquals(1, f.statusApprovals.get());
            assertFalse(f.checkpoint.snapshot().released());
            assertEquals("DISPATCHED", f.facts().path("commands").get(0).path("phase").asText());
            if (!mutate) {
                assertEquals(original, f.checkpoint.snapshot().payload());
                f.denyStatus = false;
                assertTrue(DeploymentModelCommandRunner.reconcile(context, f.task));
                assertEquals(2, f.statusApprovals.get(), "A refusal does not authorize the later status request");
                assertEquals(1, f.queries.get());
                assertEquals(1, f.sends.get(), "The original author body is not replayed");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualBashReceiptStatusProjectsOnlyCanonicalBoundBytes(boolean customLayout, @TempDir Path directory) throws Exception {
        Path bash = Path.of("E:/Program/Git/bin/bash.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(bash));
        var layout = customLayout ? new gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout(
            new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths("/srv/receipt/lib", "/srv/receipt/apps",
                "/srv/receipt/state", "/srv/receipt/config", "/srv/receipt/data"))
            : gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        String root = "/" + Character.toLowerCase(directory.toString().charAt(0)) + directory.toString().substring(2).replace('\\', '/');
        Path receiptFile = directory.resolve("receipt");
        Path metadataFile = directory.resolve("metadata");
        String fixedMetadata = "8180:0:0:600:1:";
        Path stat = directory.resolve("stat-fixture.sh");
        // Only ownership/type/inode metadata and protected ancestors are emulated on Windows; od and Bash consume the real bytes.
        Files.writeString(stat, """
            #!/bin/bash
            if [ "$2" = '%u:%g' ]; then printf '0:0\n'
            elif [ "$2" = '%a' ]; then printf '700\n'
            elif [ "$2" = '%u:%g:%a' ]; then printf '0:0:700\n'
            else
              line=$(/usr/bin/cat -- '@ROOT@/metadata')
              count=0
              if [ -f '@ROOT@/stat-count' ]; then read -r count < '@ROOT@/stat-count'; fi
              count=$((count + 1)); printf '%s\n' "$count" > '@ROOT@/stat-count'
              if [ -f '@ROOT@/race' ] && [ "$count" -eq 3 ]; then line=${line/:1:2:/:1:3:}; fi
              printf '%s\n' "$line"
            fi
            """.replace("@ROOT@", root));
        UUID operation = UUID.randomUUID();
        String header = DeploymentModelCommandIdentity.header(taskId, operation, "b".repeat(64), layout);
        String canonical = DeploymentModelCommandIdentity.receiptStatus(taskId, operation, "b".repeat(64), layout);
        String prefix = "exec " + DeploymentModelCommandIdentity.ENVIRONMENT + " /bin/bash --noprofile --norc -c ";
        String literal = canonical.substring(prefix.length(), canonical.length() - " 2>/dev/null".length());
        String reader = literal.substring(1, literal.length() - 1).replace("'\\''", "'");
        assertEquals(canonical, prefix + DeploymentModelCommandIdentity.quote(reader) + " 2>/dev/null");
        reader = reader.replace("[ ! -L \"$p\" ] && [ -d \"$p\" ]", ":")
            .replace(layout.modelCommandOperationsRoot() + "/" + operation + ".receipt", root + "/receipt")
            .replace("/usr/bin/stat", "/bin/bash " + DeploymentModelCommandIdentity.quote(root + "/stat-fixture.sh"));
        Path script = directory.resolve("reader.sh");
        Files.writeString(script, prefix + DeploymentModelCommandIdentity.quote(reader) + " 2>/dev/null");
        String secret = "CREDENTIAL_SENTINEL_DO_NOT_OUTPUT";
        var inputs = new LinkedHashMap<String, Boolean>();
        for (String status : List.of("STARTED=1\n", "EXIT=0\n", "EXIT=7\n", "EXIT=255\n"))
            inputs.put(header + status, true);
        for (String invalid : List.of(header + "EXIT=00\n", header + "EXIT=256\n", header + "EXIT=-1\n",
            header + "EXIT=7", header + "EXIT=7\r\n", header + "EXIT=7\n\n", header + "EXIT=7\n" + secret,
            header + "EXIT=7\0" + secret + "\n", header.replace(taskId.toString(), UUID.randomUUID().toString()) + "EXIT=7\n",
            header.replace("b".repeat(64), "c".repeat(64)) + "EXIT=7\n",
            header.replace(operation.toString(), UUID.randomUUID().toString()) + "EXIT=7\n",
            header.replaceFirst("FORMAT=[12]", "FORMAT=9") + "EXIT=7\n", header + header + "EXIT=7\n",
            header + "LAYOUT_SHA256=" + "c".repeat(64) + "\nEXIT=7\n", secret.repeat(40)))
            inputs.put(invalid, false);
        if (customLayout) inputs.put(header.replace(layout.digest(), "c".repeat(64)) + "EXIT=7\n", false);
        for (var entry : inputs.entrySet()) {
            byte[] bytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            Files.write(receiptFile, bytes);
            Files.writeString(metadataFile, fixedMetadata + bytes.length + ":1:2:stable:stable\n");
            assertReceiptReader(bash, script, entry.getValue() ? entry.getKey() : null, secret);
        }
        Files.writeString(receiptFile, header + "EXIT=7\n");
        long size = Files.size(receiptFile);
        for (String changed : List.of("8180:1:0:600:1:", "8180:0:0:640:1:", "8180:0:0:600:2:", "1180:0:0:600:1:")) {
            Files.writeString(metadataFile, changed + size + ":1:2:stable:stable\n");
            assertReceiptReader(bash, script, null, secret);
        }
        Files.writeString(metadataFile, fixedMetadata + size + ":1:2:stable:stable\n");
        Files.writeString(directory.resolve("race"), "public controlled after-read identity change");
        Files.deleteIfExists(directory.resolve("stat-count"));
        assertReceiptReader(bash, script, null, secret);
        Files.delete(directory.resolve("race"));
        Files.delete(receiptFile);
        assertReceiptReader(bash, script, null, secret);
    }

    private static void assertReceiptReader(Path bash, Path script, String expected, String secret) throws Exception {
        var process = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", script.toString()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("", stderr);
            assertFalse(stdout.contains(secret));
            assertFalse(stderr.contains(secret));
            if (expected != null) {
                assertEquals(0, process.exitValue());
                assertEquals(expected, stdout);
            } else {
                assertNotEquals(0, process.exitValue());
                assertEquals("", stdout);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void actualBashRecordsFailedChildAndRefusesDuplicateExecution(@TempDir Path directory) throws Exception {
        Path bash = Path.of("E:/Program/Git/bin/bash.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(bash));
        String root = "/" + Character.toLowerCase(directory.toString().charAt(0)) + directory.toString().substring(2).replace('\\', '/');
        Files.createDirectories(directory.resolve("var/lib"));
        Path stat = directory.resolve("stat-fixture.sh");
        Files.writeString(stat, "#!/bin/bash\ncase \"$2\" in '%u:%g') printf '0:0\\n';; '%u:%g:%a') printf '0:0:700\\n';; '%u') printf '0\\n';; '%a') printf '700\\n';; *) exit 64;; esac\n");
        UUID operation = UUID.randomUUID();
        Path inheritedStartup = directory.resolve("inherited-startup.sh");
        Files.writeString(inheritedStartup, "printf 'inherited startup\\n' >> '" + root + "/startup-effects'\n");
        String body = "builtin printf '%s|%s|%s\\n' \"${BASH_ENV-unset}\" \"$PWD\" \"$HOME\" > '" + root + "/environment'\n"
            + "builtin printf 'executed\\n' >> '" + root + "/effects'\nexit 7";
        // Windows emulates the already-admitted product layout, root ownership and fsync; Bash child/receipt/duplicate control flow is real.
        String executable = envelope(taskId, operation, "b".repeat(64), body)
            .replace(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.initialize(
                gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()), ": # controlled local layout admission\n")
            .replace("/var", root + "/var")
            .replace("/usr/bin/stat", "/bin/bash '" + root + "/stat-fixture.sh'")
            .replace("/usr/bin/install -d -o root -g root -m 700 --", "/usr/bin/mkdir -p --")
            .replace("/usr/bin/sync", ":");
        Path script = directory.resolve("command.sh");
        Files.writeString(script, "export BASH_ENV='" + root + "/inherited-startup.sh'\nexport HOME='" + root + "'\n"
            + DeploymentModelCommandIdentity.LAUNCHER + " <<'WTL_TEST_MODEL_WRAPPER'\n" + executable + "\nWTL_TEST_MODEL_WRAPPER\n");
        assertEquals(0, bash(bash, script));
        assertTrue(Files.readString(directory.resolve("var/lib/windowstolinux/model-command-operations/" + operation + ".receipt")).endsWith("EXIT=7\n"));
        assertEquals(64, bash(bash, script));
        assertEquals("executed\n", Files.readString(directory.resolve("effects")));
        assertEquals("unset|/|/dev/null\n", Files.readString(directory.resolve("environment")));
        assertFalse(Files.exists(directory.resolve("startup-effects")), "Model child cannot execute inherited BASH_ENV");
    }

    private static String envelope(UUID task, UUID operation, String digest, String script) {
        return DeploymentModelCommandIdentity.envelope(task, operation, digest, script, "Controlled Bash receipt test");
    }

    private static int bash(Path bash, Path script) throws Exception {
        var process = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", script.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private ModContext context() {
        var interaction = proxy(DeploymentCommandReview.class, (name, args) -> {
            assertEquals("approveCommand", name);
            var intent = (ActionIntent) args[0];
            var request = (CommandRequest) args[1];
            assertSame(task, args[2]);
            assertEquals(List.of(server.id()), intent.serverIds());
            assertEquals(request.stdin().length > 0 ? currentRevision : facts().path("modelRevision").asText(), intent.revision());
            if (intent.semantics().orElseThrow().operation().equals("deployment.model.command.v1")) {
                assertEquals(DeploymentModelCommandIdentity.LAUNCHER, request.script());
                var item = facts().path("commands").get(facts().path("commands").size() - 1);
                assertEquals("INTENT", item.path("phase").asText());
                String executable = new String(request.stdin(), StandardCharsets.UTF_8);
                assertEquals("exit 7", DeploymentModelCommandIdentity.executableFacts(executable, commandLayout).get("modelScript"));
                assertTrue(executable.contains("exit 7"));
                assertTrue(executable.contains("set -C"));
                assertTrue(executable.contains("OPERATION=" + item.path("operation").asText()));
                if (deny)
                    return new OperationResult<ApprovalGrant>(TaskResult.FAILED, Optional.empty(), Optional.empty());
            } else {
                assertEquals("deployment.model.command.status.v1", intent.semantics().orElseThrow().operation());
                statusApprovals.incrementAndGet();
                assertEquals("DISPATCHED", DeploymentModelCommandRunner.receiptStatusEvidence(context(), task, intent, request,
                    commandLayout).get("localPhase"));
                if (denyStatus)
                    return new OperationResult<ApprovalGrant>(TaskResult.FAILED, Optional.empty(), Optional.empty());
                afterStatusApproval.run();
            }
            var grant = new ApprovalGrant(UUID.randomUUID());
            exactGrants.put(grant.id(), intent.exactAction());
            return success(grant);
        });
        var tasks = proxy(TaskService.class, (name, args) -> switch (name) {
            case "findCheckpoint" -> "deployment.config.remote-layout.v1".equals(args[1])
                ? Optional.ofNullable(layoutCheckpoint) : Optional.ofNullable(checkpoint);
            case "beginCheckpoint" -> "deployment.config.remote-layout.v1".equals(args[1])
                ? layoutCheckpoint = new MemoryCheckpoint((String) args[2]) : (checkpoint = new MemoryCheckpoint((String) args[2]));
            default -> throw new AssertionError(name);
        });
        var ai = proxy(AiService.class, (name, args) -> {
            assertEquals("configurationRevision", name);
            return currentRevision;
        });
        var linux = proxy(LinuxService.class, (name, args) -> {
            assertEquals("connect", name);
            assertEquals(server, args[0]);
            connections.incrementAndGet();
            return session();
        });
        return proxy(ModContext.class, (name, args) -> switch (name) {
            case "tasks" -> tasks;
            case "ai" -> ai;
            case "interaction" -> interaction;
            case "linux" -> linux;
            default -> throw new AssertionError(name);
        });
    }

    private RemoteSession session() {
        return proxy(RemoteSession.class, (name, args) -> switch (name) {
            case "facts" -> new RemoteFacts("linux", "x86_64", "SHA256:controlled");
            case "close" -> null;
            case "execute" -> {
                var request = (CommandRequest) args[0];
                var grant = (ApprovalGrant) args[1];
                String businessRevision = request.stdin().length > 0 ? currentRevision : facts().path("modelRevision").asText();
                assertEquals(gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, businessRevision, request).exactAction(), exactGrants.remove(grant.id()));
                var item = facts().path("commands").get(facts().path("commands").size() - 1);
                assertEquals("DISPATCHED", item.path("phase").asText(), "Dispatch is persisted before the actual send");
                if (request.stdin().length > 0) {
                    sends.incrementAndGet();
                    commandSequence.add("EXECUTE");
                    receipt = DeploymentModelCommandIdentity.header(taskId, UUID.fromString(item.path("operation").asText()), item.path("body").asText(), commandLayout)
                        + "EXIT=7\n";
                    yield loseReply ? new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty()) : success(new CommandResult(unknownExit ? Optional.empty() : Optional.of(0), "actual output", "", truncatedReply));
                }
                queries.incrementAndGet();
                afterStatusExecute.run();
                commandSequence.add("QUERY");
                assertTrue(request.script().contains(item.path("operation").asText()), "Query must target only the original operation");
                if (queryException) throw new IllegalStateException("Controlled receipt query exception");
                String returned = changedReceiptField == null ? receipt
                    : receipt.replaceFirst(changedReceiptField + "=[^\n]*", changedReceiptField + "=changed");
                yield receiptReady ? success(new CommandResult(Optional.of(0), returned, "", truncatedReceipt))
                    : new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty());
            }
            default -> throw new AssertionError(name);
        });
    }

    private JsonNode facts() throws Exception {
        return JSON.readTree(checkpoint.snapshot().payload());
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    @FunctionalInterface
    private interface Call {
        Object apply(String name, Object[] args) throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> call.apply(m.getName(), a));
    }

    private static final class MemoryCheckpoint implements TaskCheckpoint {
        private Snapshot value;

        MemoryCheckpoint(String payload) {
            value = new Snapshot(0, payload, false);
        }

        public Snapshot snapshot() {
            return value;
        }

        public Snapshot advance(Snapshot expected, String next) {
            assertEquals(value, expected);
            assertFalse(value.released());
            return value = new Snapshot(value.revision() + 1, next, false);
        }

        public void close() {
            value = new Snapshot(value.revision() + 1, value.payload(), true);
        }
    }
}
