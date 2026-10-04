package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.linux.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.generation.script.SafeBuildScriptEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the actual Python/Bash streaming receipt with a controlled fake helper, without SSH. / 用受控假 helper 在真实 Python/Bash 上验证流式回执，不访问 SSH。 */
class DeploymentBuildOutputProtocolTest {
    /** Isolated fake command body. / 隔离的假命令体。 */
    @TempDir
    Path temp;

    /** Covers long output, separated compatibility, secret redaction and control fields outside the tail. / 覆盖长输出、分块兼容提示、秘密遮蔽及尾部之外的控制字段。
     * @throws Exception if process or fixture setup fails / 进程或夹具设置失败时 */
    @Test
    void longRemoteOutputKeepsTheOriginalLimitAndOnlyACompactSafeReceipt() throws Exception {
        String python = System.getProperty("managed.test.python", "python").replace('\\', '/');
        String bash = System.getProperty("managed.test.bash", "/bin/bash").replace('\\', '/');
        Path helper = temp.resolve("fake-helper.sh");
        Files.writeString(helper, """
            #!/usr/bin/env bash
            cat >/dev/null
            printf '%%s\\n' 'helper notice' 'BUILD_UID=1001' 'BUILD_ENGINE=ordinary'
            printf '%%s\\n' 'password=CrossBlockSecret' 'https://alice:UrlSecret@example.test/path'
            printf '%%s\\n' '-----BEGIN PRIVATE KEY-----' 'VeryPrivateBody' '-----END PRIVATE KEY-----'
            %s -I -c 'import sys; sys.stdout.buffer.write(b"p" * 700 + b"-----BEGIN PRIVATE KEY-----\\n")'
            printf '%%s\\n' 'HiddenAfterLongPrefix' '-----END PRIVATE' 'StillPrivate' '-----END PRIVATE KEY-----'
            %s -I -c 'import sys; sys.stdout.buffer.write(b"x" * 9000000 + b"invalid target release: 17\\n")'
            printf '%%s\\n' 'BUILD_TOOL=GO_MODULE'
            %s -I -c 'import sys; sys.stdout.buffer.write(b"z" * 100000)'
            printf '\\n'
            printf 'password=\\nSecretAtEOF'
            """.formatted(SafeBuildScriptEnvelope.shellQuote(python), SafeBuildScriptEnvelope.shellQuote(python),
            SafeBuildScriptEnvelope.shellQuote(python)), StandardCharsets.UTF_8);
        String command = DeploymentBuildOutputProtocol.script(new DeploymentRemoteWorkspace("demo", "a".repeat(64)),
            300, 16, 512, 128L * 1024 * 1024, "ordinary", BuildMatchPolicy.groups());
        command = command.replace("exec /usr/bin/python3", "exec " + SafeBuildScriptEnvelope.shellQuote(python))
            .replace("'" + DeploymentManagedHelperBundle.PATH + "' build-run",
                SafeBuildScriptEnvelope.shellQuote(bash) + " "
                    + SafeBuildScriptEnvelope.shellQuote(helper.toString().replace('\\', '/'))
                    + " build-run");
        Path wrapper = temp.resolve("wrapper.sh");
        Files.writeString(wrapper, command + "\n", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(bash, wrapper.toString()).redirectErrorStream(true).start();
        try {
            try {
                process.getOutputStream().write("fixed-build-script\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
            } catch (IOException earlyExit) {
                process.waitFor(10, TimeUnit.SECONDS);
                fail(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), earlyExit);
            }
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "wrapper did not exit");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.length() < 15000, "public captured output should remain below the host cap");
            DeploymentBuildOutputProtocol.Receipt receipt;
            try {
                receipt = DeploymentBuildOutputProtocol.parse(output);
            } catch (IllegalArgumentException invalid) {
                throw new AssertionError(output, invalid);
            }
            assertEquals(0, receipt.exit());
            assertTrue(receipt.bytes() > 8L * 1024 * 1024);
            assertEquals("1001", receipt.uid());
            assertEquals("ordinary", receipt.engine());
            assertEquals("GO_MODULE", receipt.tool());
            assertTrue(receipt.compatible());
            assertFalse(receipt.conflicting());
            String diagnostics = receipt.head() + receipt.tail();
            assertFalse(diagnostics.contains("CrossBlockSecret"));
            assertFalse(diagnostics.contains("UrlSecret"));
            assertFalse(diagnostics.contains("VeryPrivateBody"));
            assertFalse(diagnostics.contains("HiddenAfterLongPrefix"));
            assertFalse(diagnostics.contains("StillPrivate"));
            assertFalse(diagnostics.contains("SecretAtEOF"));
        } finally {
            if (process.isAlive()) {
                process.getOutputStream().close();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "test-owned wrapper did not exit");
            }
        }
    }

    /** A malformed or conflicting fixed report never becomes success. / 损坏或冲突的固定回执绝不能成为成功。
     * @throws Exception if fixture setup fails / 夹具设置失败时 */
    @Test
    void rejectsConflictingOrIncompleteReceipts() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> DeploymentBuildOutputProtocol.parse("WTL_BUILD_REPORT=1\n"));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentBuildOutputProtocol.parse("WTL_BUILD_REPORT=1\nEXIT=0\nBYTES=1\n"));
    }
}
