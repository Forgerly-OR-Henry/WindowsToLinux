package gold.debug.windowstolinux.app.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Windows Credential Manager adapter implemented with fixed P/Invoke calls. The PowerShell source is fixed and encoded without secret values. Target and credential data are passed only in the spawned process environment, rather than on a process command line. Callers cannot supply shell syntax, a command name, or a credential target outside this app.
 *
 *  <p>通过固定 P/Invoke 调用实现的 Windows Credential Manager 适配器。PowerShell 源码固定编码且不包含秘密值。目标和凭据数据只通过派生进程环境传递，不会出现在进程命令行中。调用方无法提供 Shell 语法、命令名称或本应用之外的凭据目标。
 */
public final class WindowsCredentialManagerSecretStore implements SecretStore {
    /**
     * TARGET PREFIX.
     * <p>目标前缀。
     */
    private static final String TARGET_PREFIX = "WindowsToLinux/";
    /**
     * TARGET FILTER.
     * <p>目标筛选。
     */
    private static final String TARGET_FILTER = "WindowsToLinux/*";
    /**
     * PROCESS TIMEOUT.
     * <p>进程超时。
     */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Fixed CREDENTIAL INTEROP text used by the enclosing renderer or protocol.
     * <p>外层渲染器或协议使用的固定凭据INTEROP文本。
     */
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
              [DllImport("Advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
              public static extern bool CredDelete(string target, int type, int flags);
              [DllImport("Advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
              public static extern bool CredEnumerate(string filter, int flags, out int count, out IntPtr credentials);
              [DllImport("Advapi32.dll", SetLastError=true)]
              public static extern void CredFree(IntPtr credential);
            }
            """;
    /**
     * Fixed CREDENTIAL SCRIPT text used by the enclosing renderer or protocol.
     * <p>外层渲染器或协议使用的固定凭据脚本文本。
     */
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
            } elseif ($mode -eq 'read') {
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
            } elseif ($mode -eq 'delete') {
              if ([WtlCredential]::CredDelete($target,1,0)) { Write-Output 'OK'; exit 0 }
              if ([Runtime.InteropServices.Marshal]::GetLastWin32Error() -eq 1168) { Write-Output 'NOT_FOUND'; exit 0 }
              exit 25
            } elseif ($mode -eq 'delete-namespace') {
              $count=0; $pointer=[IntPtr]::Zero
              if (-not [WtlCredential]::CredEnumerate('WindowsToLinux/*',0,[ref]$count,[ref]$pointer)) {
                if ([Runtime.InteropServices.Marshal]::GetLastWin32Error() -eq 1168) { Write-Output 'EMPTY'; exit 0 }
                exit 26
              }
              $targets=New-Object System.Collections.Generic.List[string]
              try {
                for ($index=0; $index -lt $count; $index++) {
                  $item=[Runtime.InteropServices.Marshal]::ReadIntPtr($pointer,$index * [IntPtr]::Size)
                  $credential=[Runtime.InteropServices.Marshal]::PtrToStructure($item,[type][WtlCredential+CREDENTIAL])
                  $targets.Add($credential.TargetName)
                }
              } finally { [WtlCredential]::CredFree($pointer) }
              foreach ($item in $targets) {
                $encodedTarget=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($item))
                if ($item -cnotmatch '^WindowsToLinux/[a-z0-9][a-z0-9/_-]{0,127}$') {
                  Write-Output ('RESIDUAL:' + $encodedTarget); continue
                }
                if ([WtlCredential]::CredDelete($item,1,0)) { Write-Output ('DELETED:' + $encodedTarget) }
                elseif ([Runtime.InteropServices.Marshal]::GetLastWin32Error() -eq 1168) {
                  Write-Output ('DELETED:' + $encodedTarget)
                } else { Write-Output ('RESIDUAL:' + $encodedTarget) }
              }
            } else { exit 27 }
            """.formatted(CREDENTIAL_INTEROP);

    /**
     * Binds the supplied dependencies and state for windows credential manager secret store.
     * <p>为Windows凭据管理器秘密存储绑定传入的依赖及状态。
     *
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public WindowsCredentialManagerSecretStore() throws SecretStoreException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw failure(SecretStoreFailureType.WINDOWS_ONLY, "Windows Credential Manager is available only on Windows");
        }
    }

    /**
     * Persists windows credential manager secret store.
     * <p>持久化Windows凭据管理器秘密存储。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
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

    /**
     * Reads optional.
     * <p>读取可选。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
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

    /**
     * Deletes one exact application-owned Credential Manager target. / 删除一个精确的应用持有凭据管理器目标。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return true when deletes one exact application-owned Credential Manager target, false otherwise / 删除一个精确的应用持有凭据管理器目标时为 true，否则为 false
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    @Override
    public boolean delete(String key) throws SecretStoreException {
        validateKey(key);
        String response = responseLine(invoke("delete", target(key), ""));
        if ("OK".equals(response)) return true;
        if ("NOT_FOUND".equals(response)) return false;
        throw failure(SecretStoreFailureType.WINDOWS_DELETE_FAILED,
                "Windows Credential Manager rejected the delete operation");
    }

    /**
     * Deletes valid targets in the fixed application namespace and reports exact residuals. / 删除固定应用命名空间内的合法目标并报告精确残留。
     *
     * @return constructed or resolved credential namespace deletion result / 构造或解析得到的凭据命名空间Deletion结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public CredentialNamespaceDeletionResult deleteApplicationNamespace() throws SecretStoreException {
        return parseNamespaceDeletion(invoke("delete-namespace", TARGET_FILTER, ""));
    }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public void close() {
        // Credential Manager owns its OS-protected persistence lifecycle. / Credential Manager 持有其受操作系统保护的持久化生命周期。
    }

    /**
     * Runs the fixed Windows credential helper with bounded input and output and translates its exit status.
     * <p>使用有界输入输出运行固定 Windows 凭据 helper，并转换其退出状态。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param encodedValue encoded value / 已编码内容
     * @return invoke text / 调用文本
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
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

    /**
     * Prefixes the logical secret key with the application's Credential Manager namespace.
     * <p>为逻辑秘密键添加应用的 Credential Manager 命名空间前缀。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return target text / 目标文本
     */
    private static String target(String key) {
        return TARGET_PREFIX + key;
    }

    /**
     * Extracts the recognized status or Base64 payload from the credential helper response.
     * <p>从凭据 helper 响应中提取可识别的状态或 Base64 载荷。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return the recognized status or Base64 payload from the credential helper response / 从凭据 helper 响应中提取可识别的状态或 Base64 载荷
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    private static String responseLine(String output) throws SecretStoreException {
        return output.lines()
                .map(String::trim)
                .filter(line -> line.equals("OK") || line.equals("NOT_FOUND")
                        || (line.length() >= 4 && line.length() % 4 == 0 && line.matches("[A-Za-z0-9+/]+={0,2}")))
                .findFirst()
                .orElseThrow(() -> failure(SecretStoreFailureType.WINDOWS_UNCONTROLLED_RESPONSE,
                        "Windows Credential Manager did not return a controlled response"));
    }

    /**
     * Parses namespace deletion.
     * <p>解析命名空间Deletion。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return namespace deletion / 命名空间Deletion
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    static CredentialNamespaceDeletionResult parseNamespaceDeletion(String output) throws SecretStoreException {
        List<String> deleted = new ArrayList<>();
        List<String> residual = new ArrayList<>();
        boolean empty = false;
        for (String raw : output.lines().toList()) {
            String line = raw.trim();
            if (line.equals("EMPTY")) {
                empty = true;
            } else if (line.startsWith("DELETED:")) {
                deleted.add(decodedTarget(line.substring("DELETED:".length()), true));
            } else if (line.startsWith("RESIDUAL:")) {
                residual.add(decodedTarget(line.substring("RESIDUAL:".length()), false));
            }
        }
        if ((!empty && deleted.isEmpty() && residual.isEmpty())
                || empty && (!deleted.isEmpty() || !residual.isEmpty())) {
            throw failure(SecretStoreFailureType.WINDOWS_UNCONTROLLED_RESPONSE,
                    "Windows Credential Manager namespace deletion returned no controlled result");
        }
        try {
            return new CredentialNamespaceDeletionResult(deleted, residual);
        } catch (IllegalArgumentException exception) {
            throw failure(SecretStoreFailureType.WINDOWS_UNCONTROLLED_RESPONSE,
                    "Windows Credential Manager namespace deletion returned inconsistent targets", exception);
        }
    }

    /**
     * Checks decoded target syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查已解码目标语法及边界。
     *
     * @param encoded encoded / 已编码
     * @param generatedTarget generated target / 已生成目标
     * @return decoded target text / 已解码目标文本
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String decodedTarget(String encoded, boolean generatedTarget) throws SecretStoreException {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            String target = new String(bytes, StandardCharsets.UTF_8);
            if (!Arrays.equals(bytes, target.getBytes(StandardCharsets.UTF_8))
                    || target.length() > 256 || target.chars().anyMatch(Character::isISOControl)
                    || !target.startsWith(TARGET_PREFIX)
                    || generatedTarget && !target.substring(TARGET_PREFIX.length())
                    .matches("[a-z0-9][a-z0-9/_-]{0,127}")) {
                throw new IllegalArgumentException("credential target is invalid");
            }
            return target;
        } catch (IllegalArgumentException exception) {
            throw failure(SecretStoreFailureType.WINDOWS_UNCONTROLLED_RESPONSE,
                    "Windows Credential Manager returned an invalid namespace target", exception);
        }
    }

    /**
     * Exact result of deleting the fixed application credential namespace. / 删除固定应用凭据命名空间的精确结果。
     *
     * @param deletedTargets deleted targets / 已删除目标集合
     * @param residualTargets residual targets / 残留目标集合
     */
    public record CredentialNamespaceDeletionResult(List<String> deletedTargets, List<String> residualTargets) {
        /**
         * Freezes sorted, unique and non-overlapping target sets. / 冻结排序、唯一且互不重叠的目标集合。
         *
         * @param deletedTargets deleted targets / 已删除目标集合
         * @param residualTargets residual targets / 残留目标集合
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public CredentialNamespaceDeletionResult {
            deletedTargets = sortedTargets(deletedTargets, "deletedTargets");
            residualTargets = sortedTargets(residualTargets, "residualTargets");
            Set<String> overlap = new HashSet<>(deletedTargets);
            overlap.retainAll(residualTargets);
            if (!overlap.isEmpty()) throw new IllegalArgumentException("credential deletion result overlaps");
        }

        /**
         * Validates and produces sorted targets for the next contract boundary.
         * <p>校验并生成供下一契约边界使用的已排序目标集合。
         *
         * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
         * @param field field name or input definition being validated / 正在校验的字段名或输入定义
         * @return constructed or resolved list / 构造或解析得到的列表
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        private static List<String> sortedTargets(List<String> values, String field) {
            List<String> result = List.copyOf(values).stream().sorted().toList();
            if (result.stream().distinct().count() != result.size()) {
                throw new IllegalArgumentException(field + " contains duplicates");
            }
            return result;
        }
    }

    /**
     * Converts the current contract to utf 8.
     * <p>将当前契约转换为Utf8。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private static byte[] toUtf8(char[] value) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    /**
     * Validates lookup key within the current contract and rejects inputs outside the declared constraints.
     * <p>校验当前契约内的查找键并拒绝超出已声明约束的输入。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9/_-]{0,127}")) {
            throw new IllegalArgumentException("secret key is invalid");
        }
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static SecretStoreException failure(SecretStoreFailureType type, String diagnostic) {
        return SecretStoreException.create(type, diagnostic);
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static SecretStoreException failure(SecretStoreFailureType type, String diagnostic, Throwable cause) {
        return SecretStoreException.create(type, diagnostic, cause);
    }
}
