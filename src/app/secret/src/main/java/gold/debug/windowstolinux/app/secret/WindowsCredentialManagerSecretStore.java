package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Windows Credential Manager adapter implemented with fixed P/Invoke calls. The PowerShell source is fixed and encoded without secret values. Target and credential data are passed only in the spawned process environment, rather than on a process command line. Callers cannot supply shell syntax, a command name, or a credential target outside this app.
 *
 * <p>通过固定 P/Invoke 调用实现的 Windows Credential Manager 适配器。PowerShell 源码固定编码且不包含秘密值。目标和凭据数据只通过派生进程环境传递，不会出现在进程命令行中。调用方无法提供 Shell 语法、命令名称或本应用之外的凭据目标。
 */
public final class WindowsCredentialManagerSecretStore implements SecretStore {
    private static final String TARGET_PREFIX = "WindowsToLinux/";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final String CREDENTIAL_INTEROP = """
            using System;
            using System.Runtime.InteropServices;
            public static class WtlCredential {
              [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
              public struct CREDENTIAL {
                public int Flags; public int Type; public string TargetName; public string Comment;
                public long LastWritten; public int CredentialBlobSize; public IntPtr CredentialBlob;
                public int Persist; public int AttributeCount; public IntPtr Attributes;
                public string TargetAlias; public string UserName;
              }
              [DllImport("Advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
              public static extern bool CredWrite(ref CREDENTIAL credential, int flags);
              [DllImport("Advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
              public static extern bool CredRead(string target, int type, int flags, out IntPtr credential);
              [DllImport("Advapi32.dll", SetLastError=true)]
              public static extern void CredFree(IntPtr credential);
            }
            """;
    private static final String CREDENTIAL_SCRIPT = """
            $ErrorActionPreference='Stop'
            $ProgressPreference='SilentlyContinue'
            Add-Type -TypeDefinition @'
            %s
            '@ | Out-Null
            $mode=$env:WTL_CREDENTIAL_MODE
            $target=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:WTL_CREDENTIAL_TARGET))
            $encoded=$env:WTL_CREDENTIAL_DATA
            if ($mode -eq 'write') {
              $bytes=[Convert]::FromBase64String($encoded)
              $memory=[Runtime.InteropServices.Marshal]::AllocHGlobal($bytes.Length)
              try {
                [Runtime.InteropServices.Marshal]::Copy($bytes,0,$memory,$bytes.Length)
                $cred=New-Object WtlCredential+CREDENTIAL
                $cred.Type=1; $cred.TargetName=$target; $cred.CredentialBlobSize=$bytes.Length
                $cred.CredentialBlob=$memory; $cred.Persist=2; $cred.UserName='WindowsToLinux'
                if (-not [WtlCredential]::CredWrite([ref]$cred,0)) { exit 23 }
                Write-Output 'OK'
              } finally { [Array]::Clear($bytes,0,$bytes.Length); [Runtime.InteropServices.Marshal]::FreeHGlobal($memory) }
            } else {
              $pointer=[IntPtr]::Zero
              if (-not [WtlCredential]::CredRead($target,1,0,[ref]$pointer)) {
                if ([Runtime.InteropServices.Marshal]::GetLastWin32Error() -eq 1168) { Write-Output 'NOT_FOUND'; exit 0 }
                exit 24
              }
              try {
                $cred=[Runtime.InteropServices.Marshal]::PtrToStructure($pointer,[type][WtlCredential+CREDENTIAL])
                $bytes=New-Object byte[] $cred.CredentialBlobSize
                try { [Runtime.InteropServices.Marshal]::Copy($cred.CredentialBlob,$bytes,0,$bytes.Length); Write-Output ([Convert]::ToBase64String($bytes)) }
                finally { [Array]::Clear($bytes,0,$bytes.Length) }
              } finally { [WtlCredential]::CredFree($pointer) }
            }
            """.formatted(CREDENTIAL_INTEROP);

    /**
     * Creates a {@code WindowsCredentialManagerSecretStore} instance.
     *
     * <p>创建 {@code WindowsCredentialManagerSecretStore} 实例。
     *
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    public WindowsCredentialManagerSecretStore() throws SecretStoreException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw failure(SecretStoreFailureType.WINDOWS_ONLY, "Windows Credential Manager is available only on Windows");
        }
    }

    /** Performs the {@code save} operation. / 执行 {@code save} 操作。 */
    @Override
    public void save(String key, char[] value) throws SecretStoreException {
        validateKey(key);
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("secret value must not be empty");
        }
        byte[] valueBytes = toUtf8(value);
        try {
            String response = responseLine(invoke("write", target(key), Base64.getEncoder().encodeToString(valueBytes)));
            if (!"OK".equals(response)) {
                throw failure(SecretStoreFailureType.WINDOWS_WRITE_FAILED, "Windows Credential Manager rejected the write operation");
            }
        } finally {
            Arrays.fill(valueBytes, (byte) 0);
        }
    }

    /** Performs the {@code read} operation. / 执行 {@code read} 操作。 */
    @Override
    public Optional<char[]> read(String key) throws SecretStoreException {
        validateKey(key);
        String output = responseLine(invoke("read", target(key), ""));
        if ("NOT_FOUND".equals(output)) {
            return Optional.empty();
        }
        if (output.isBlank()) {
            throw failure(SecretStoreFailureType.WINDOWS_EMPTY_RESPONSE, "Windows Credential Manager returned an empty response");
        }
        try {
            byte[] value = Base64.getDecoder().decode(output);
            try {
                return Optional.of(new String(value, StandardCharsets.UTF_8).toCharArray());
            } finally {
                Arrays.fill(value, (byte) 0);
            }
        } catch (IllegalArgumentException exception) {
            throw failure(SecretStoreFailureType.WINDOWS_INVALID_RESPONSE, "Windows Credential Manager returned invalid data",
                    exception);
        }
    }

    /** Closes this resource. / 关闭此资源。 */
    @Override
    public void close() {
        // Credential Manager owns its OS-protected persistence lifecycle. / Credential Manager 持有其受操作系统保护的持久化生命周期。
    }

    private static String invoke(String mode, String target, String encodedValue) throws SecretStoreException {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(CREDENTIAL_SCRIPT.getBytes(StandardCharsets.UTF_16LE)))
                    .redirectErrorStream(true);
            builder.environment().put("WTL_CREDENTIAL_MODE", mode);
            builder.environment().put("WTL_CREDENTIAL_TARGET", Base64.getEncoder().encodeToString(target.getBytes(StandardCharsets.UTF_8)));
            builder.environment().put("WTL_CREDENTIAL_DATA", encodedValue);
            process = builder.start();
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw failure(SecretStoreFailureType.WINDOWS_TIMEOUT, "Windows Credential Manager operation timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw SecretStoreException.create(SecretStoreFailureType.WINDOWS_OPERATION_FAILED,
                        java.util.Map.of("exitCode", process.exitValue()),
                        "Windows Credential Manager operation failed with a non-zero exit code", null);
            }
            return output;
        } catch (IOException exception) {
            throw failure(SecretStoreFailureType.WINDOWS_START_FAILED, "Failed to start the Windows Credential Manager adapter",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(SecretStoreFailureType.WINDOWS_INTERRUPTED, "Windows Credential Manager operation was interrupted",
                    exception);
        }
    }

    private static String target(String key) {
        return TARGET_PREFIX + key;
    }

    private static String responseLine(String output) throws SecretStoreException {
        return output.lines()
                .map(String::trim)
                .filter(line -> line.equals("OK") || line.equals("NOT_FOUND")
                        || (line.length() >= 4 && line.length() % 4 == 0 && line.matches("[A-Za-z0-9+/]+={0,2}")))
                .findFirst()
                .orElseThrow(() -> failure(SecretStoreFailureType.WINDOWS_UNCONTROLLED_RESPONSE,
                        "Windows Credential Manager did not return a controlled response"));
    }

    private static byte[] toUtf8(char[] value) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9/_-]{0,127}")) {
            throw new IllegalArgumentException("secret key is invalid");
        }
    }

    private static SecretStoreException failure(SecretStoreFailureType type, String diagnostic) {
        return SecretStoreException.create(type, diagnostic);
    }

    private static SecretStoreException failure(SecretStoreFailureType type, String diagnostic, Throwable cause) {
        return SecretStoreException.create(type, diagnostic, cause);
    }
}
