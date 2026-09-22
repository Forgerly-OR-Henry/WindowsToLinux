package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.lifecycle.*;

/**
 * Runs a fixed discovery/lifecycle program without installing or rewriting services. / 执行固定发现和生命周期程序，不安装或重写服务。
 */
public final class SshdExternalApplicationPort implements ExternalApplicationPort {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Binds to the existing authenticated command channel. / 绑定既有已认证命令通道。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdExternalApplicationPort(SshCommandExecutor commands) {
        this.commands = java.util.Objects.requireNonNull(commands);
    }

    /**
     * Scans external application scan.
     * <p>扫描外部应用扫描。
     *
     * @return constructed or resolved external application scan / 构造或解析得到的外部应用扫描
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public ExternalApplicationScan scan() throws LinuxOperationException {
        return invoke(List.of("SCAN"));
    }

    /**
     * Executes discovered application.
     * <p>执行已发现应用。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved discovered application / 构造或解析得到的已发现应用
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public DiscoveredApplication execute(ExternalApplicationTarget target, LifecycleAction action)
            throws LinuxOperationException {
        if (action == LifecycleAction.ENABLE_AUTOSTART || action == LifecycleAction.DISABLE_AUTOSTART)
            throw new IllegalArgumentException("external configuration changes are not supported");
        var result = invoke(List.of(target.kind().name(), target.identity(), target.fingerprint(), action.name()));
        if (result.applications().size() != 1 || !result.applications().getFirst().target().equals(target))
            throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED,
                    "External target differs from the scanned identity");
        return result.applications().getFirst();
    }

    /**
     * Invokes external application scan.
     * <p>调用外部应用扫描。
     *
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @return constructed or resolved external application scan / 构造或解析得到的外部应用扫描
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private ExternalApplicationScan invoke(List<String> arguments) throws LinuxOperationException {
        try (var resource = getClass().getResourceAsStream("external-applications.py")) {
            if (resource == null)
                throw new java.io.IOException("missing external application program");
            String command = "exec python3 - "
                    + arguments.stream().map(gold.debug.windowstolinux.shared.linux.command.CommandText::quote)
                            .collect(java.util.stream.Collectors.joining(" "));
            var result = commands.execProtocolWithInput(command, resource.readAllBytes(), Duration.ofSeconds(110),
                    1048576);
            if (result.output().contains("ERROR\tIDENTITY_CHANGED"))
                throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED,
                        "External unit or container changed; scan again");
            if (result.output().contains("ERROR\tMANAGED_OWNERSHIP_REQUIRED"))
                throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OWNERSHIP_REQUIRED,
                        "Managed ownership validation remains required");
            if (!result.succeeded())
                throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OPERATION_FAILED,
                        "External application operation failed or requires additional runtime permissions");
            return parse(result.output());
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OPERATION_FAILED,
                    "External application protocol is unavailable or invalid", failure);
        }
    }

    /**
     * Parses external application scan.
     * <p>解析外部应用扫描。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return external application scan / 外部应用扫描
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    static ExternalApplicationScan parse(String output) {
        List<DiscoveredApplication> applications = new ArrayList<>();
        List<ExternalScanIssueType> issues = new ArrayList<>();
        boolean ended = false;
        for (String line : output.lines().toList()) {
            if (ended)
                throw new IllegalArgumentException("trailing scan output");
            String[] fields = line.split("\t", -1);
            if (fields.length == 9 && fields[0].equals("APP")) {
                applications.add(new DiscoveredApplication(
                        new ExternalApplicationTarget(ExternalApplicationKind.valueOf(fields[1]), decode(fields[2]),
                                fields[3]),
                        decode(fields[4]), RuntimeState.valueOf(fields[5]), bit(fields[6]), bit(fields[7]),
                        bit(fields[8])));
            } else if (fields.length == 2 && fields[0].equals("ISSUE"))
                issues.add(ExternalScanIssueType.valueOf(fields[1]));
            else if (fields.length == 2 && fields[0].equals("END")
                    && Integer.parseInt(fields[1]) == applications.size())
                ended = true;
            else
                throw new IllegalArgumentException("invalid scan output");
        }
        if (!ended)
            throw new IllegalArgumentException("incomplete scan output");
        return new ExternalApplicationScan(applications, issues);
    }

    /**
     * Decodes sshd external application.
     * <p>解码Sshd外部应用。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return sshd external application / Sshd外部应用
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String decode(String text) {
        if (text.length() > 2048)
            throw new IllegalArgumentException("scan field too long");
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    /**
     * Decodes a protocol bit and rejects values other than zero or one.
     * <p>解码协议位，并拒绝零或一之外的值。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return true when decodes a protocol bit and rejects values other than zero or one, false otherwise / 解码协议位，并拒绝零或一之外的值时为 true，否则为 false
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static boolean bit(String value) {
        if (!value.equals("0") && !value.equals("1"))
            throw new IllegalArgumentException("invalid protocol flag");
        return value.equals("1");
    }
}
