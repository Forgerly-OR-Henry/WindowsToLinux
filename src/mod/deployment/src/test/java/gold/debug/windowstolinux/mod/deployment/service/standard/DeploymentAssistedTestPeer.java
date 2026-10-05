package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Controlled standard helper receipts consumed by actual publication channels. / 由真实发布频道消费的受控标准 helper 回执。 */
final class DeploymentAssistedTestPeer {
    final AtomicInteger publications = new AtomicInteger();

    private String archive;

    private long archiveBytes;

    private String candidate;

    private boolean present;

    RemoteSession session(TaskContext task, Runnable closed) throws ApiException {
        var lease = task.retain("controlled_ssh", "controlled-assisted-ssh-" + UUID.randomUUID(), "Controlled SSH");
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("facts"))
                    return new RemoteFacts("linux", "x86_64", "SHA256:" + "A".repeat(43));
                if (method.getName().equals("close")) {
                    lease.close();
                    closed.run();
                    return null;
                }
                assertNotNull(args[1], "Actual platform grant must reach the controlled SSH peer");
                if (method.getName().equals("upload")) {
                    var transfer = (TransferRequest) args[0];
                    byte[] bytes = Files.readAllBytes(transfer.localPath());
                    archive = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    archiveBytes = bytes.length;
                    Arrays.fill(bytes, (byte) 0);
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new TransferResult(archiveBytes,
                        archive, transfer.localPath(), transfer.remoteAbsolutePath())), Optional.empty());
                }
                assertEquals("execute", method.getName());
                return command((CommandRequest) args[0]);
            });
    }

    private OperationResult<CommandResult> command(CommandRequest request) throws Exception {
        String script = request.script();
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
        if (script.contains("prepare-toolchains 'prepare'"))
            return reply("TOOLCHAIN=GO|1.24.1|/usr/local/lib/windowstolinux/toolchains/versions/go-" + "b".repeat(64)
                + "|MANAGED|https://official.example/go|" + "b".repeat(64) + "\n");
        if (script.contains("prepare-toolchains 'bind'")) {
            String encoded = script.substring(script.indexOf("'bind' '") + 8);
            encoded = encoded.substring(0, encoded.indexOf('\''));
            var tools = DeploymentToolchainBindingCodec
                .decode(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            return reply("TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(tools) + "\n");
        }
        if (script.contains("candidate-create-task")) {
            var matcher = Pattern.compile("candidate-create-task '[^']+' '([^']+)'").matcher(script);
            assertTrue(matcher.find(), script);
            candidate = matcher.group(1);
            present = true;
            return reply("CANDIDATE=/var/lib/windowstolinux/work/" + candidate + "\n");
        }
        if (script.contains("stat -c '%s'"))
            return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archiveBytes + "\nSHA256=" + archive
                + "\nOWNER=0\n");
        if (script.contains("candidate-query-task"))
            return reply("CANDIDATE_STATE=" + (present ? "owned" : "absent") + "\nBUILD_ACTIVE=no\n");
        if (script.startsWith("exec /usr/bin/python3 -I -c "))
            return reply("WTL_BUILD_REPORT=1\nEXIT=0\nBYTES=0\nSHA256=" + "c".repeat(64)
                + "\nUID=1001\nENGINE=ordinary\nTOOL=GO_MODULE\nCOMPAT=0\nLIMIT=-\nCONFLICT=0\nHEAD=\nTAIL=\n");
        if (script.contains("stage-config"))
            return reply("INPUT_STAGED=1\n");
        if (script.contains("snapshot-deployment"))
            return reply("PREVIOUS=0\n");
        if (script.contains("publish-deployment")) {
            publications.incrementAndGet();
            return reply("PUBLISHED=1\n");
        }
        if (script.contains("application-health"))
            return reply("HEALTHY=1\n");
        if (script.contains("inspect-runtime"))
            return reply("KIND=deployment\nMODE=DAEMON\n");
        if (script.contains("observe-deployment"))
            return reply(
                "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\nSubState=running\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=123\n");
        if (script.contains("application-maintenance"))
            return reply("MAINTENANCE=end\n");
        if (script.contains("candidate-cleanup-task")) {
            present = false;
            return reply("CANDIDATE_CLEANED=1\n");
        }
        if (script.contains("'retain'"))
            return reply("RETAINED=1\n");
        throw new AssertionError("Unexpected actual assisted helper script: " + script);
    }

    private static OperationResult<CommandResult> reply(String output) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
    }
}
