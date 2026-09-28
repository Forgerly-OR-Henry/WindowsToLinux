package gold.debug.windowstolinux.mod.deployment.linux;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;

/** Streams up to the reviewed build-output limit into a fixed, short host receipt. / 将不超过已审阅构建输出限制的流转换成固定短回执。 */
public final class DeploymentBuildOutputProtocol {
    /** Protocol observation after the original helper has physically exited. / 原helper实际退出后的协议观测。
     * @param exit original helper exit code / 原helper退出码
     * @param bytes original full output bytes / 原完整输出字节数
     * @param digest SHA-256 of original full output / 原完整输出SHA-256
     * @param uid original helper user identity / 原helper用户身份
     * @param engine original fixed engine / 原固定引擎
     * @param tool original build tool / 原构建工具
     * @param compatible full-stream compatibility marker / 全流兼容标记
     * @param limit observed output or workspace limit / 已观察的输出或工作区限制
     * @param conflicting duplicate or malformed control evidence / 重复或损坏的控制证据
     * @param head bounded redacted initial diagnostic / 有界遮蔽的开头诊断
     * @param tail bounded redacted final diagnostic / 有界遮蔽的结尾诊断 */
    public record Receipt(int exit, long bytes, String digest, String uid, String engine, String tool,
            boolean compatible, String limit, boolean conflicting, String head, String tail) {
    }

    /** Prevents utility construction. / 防止构造工具类。 */
    private DeploymentBuildOutputProtocol() {
    }

    /** Builds the fixed remote streaming command without changing the helper bundle. / 构造固定远端流式命令，不改变 helper 包。
     * @param workspace original verified candidate / 原经核实候选
     * @param seconds remaining total deadline / 剩余总期限秒数
     * @param processes reviewed process ceiling / 已审阅进程上限
     * @param memory reviewed MiB ceiling / 已审阅内存上限
     * @param output reviewed remote output ceiling / 已审阅远端输出上限
     * @param engine fixed reviewed engine / 固定已审阅引擎
     * @param compatibilityGroups reviewed Java compatibility phrases / 已审阅Java兼容性短语
     * @return exact script sent through the public remote command / 经公开远端命令发送的精确脚本 */
    public static String script(DeploymentRemoteWorkspace workspace, long seconds, int processes, int memory,
            long output, String engine, List<List<String>> compatibilityGroups) {
        String groups = groups(compatibilityGroups);
        String python = """
                import base64, hashlib, os, re, subprocess, sys, threading
                sys.stdout.reconfigure(newline='\\n')
                groups = %s
                markers = [base64.b64decode(item) for group in groups for item in group]
                overlap = max(map(len, markers)) - 1
                payload = sys.stdin.buffer.read(1048577)
                if len(payload) > 1048576:
                    raise SystemExit(64)
                proc = subprocess.Popen(sys.argv[1:], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)
                def feed():
                    try:
                        proc.stdin.write(payload)
                        proc.stdin.close()
                    except BrokenPipeError:
                        pass
                sender = threading.Thread(target=feed)
                sender.start()
                head = bytearray()
                tail = bytearray()
                recent = b''
                seen = set()
                digest = hashlib.sha256()
                count = 0
                output_limit = None
                controls = {}
                conflict = False
                line = bytearray()
                oversized = False
                private_key = False
                private_line = False
                pending_secret = False
                marker_recent = b''
                assignment = re.compile(r'(?i)(password|secret|token|api[_-]?key)\\s*[:=]\\s*\\S+')
                url_password = re.compile(r'(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@')
                def accept_line(data):
                    global conflict
                    for key, pattern in ((b'BUILD_UID', rb'[0-9]{1,10}'),
                                         (b'BUILD_ENGINE', rb'(?:ordinary|docker|podman)'),
                                         (b'BUILD_TOOL', rb'[A-Z0-9_]{1,64}')):
                        if data.startswith(key + b'='):
                            value = data[len(key)+1:]
                            if key in controls or not re.fullmatch(pattern, value):
                                conflict = True
                            else:
                                controls[key] = value.decode('ascii')
                def safe_line(data, long_line, hidden):
                    text = data.decode('utf-8', 'replace')
                    if hidden:
                        return b'<redacted-key>\\n'
                    if long_line:
                        return b'[... long line omitted ...]\\n'
                    text = assignment.sub(r'\\1=<redacted>', text)
                    text = url_password.sub(r'\\1<redacted>@', text)
                    return (text + '\\n').encode('utf-8')
                def add_diagnostic(data):
                    if len(head) < 2048:
                        head.extend(data[:2048-len(head)])
                    tail.extend(data)
                    if len(tail) > 4096:
                        del tail[:-4096]
                def append_line(part, complete):
                    global oversized, private_key, private_line, marker_recent, pending_secret
                    if private_key:
                        private_line = True
                    marker_window = marker_recent + part
                    markers_at = sorted([(match.start(), True) for match in re.finditer(b'-----BEGIN ', marker_window)]
                                        + [(match.start(), False) for match in
                                           re.finditer(rb'-----END [A-Z ]+-----', marker_window)])
                    for _, begins in markers_at:
                        private_key = begins
                        private_line = True
                    marker_recent = marker_window[-128:]
                    if len(line) + len(part) > 512:
                        oversized = True
                    if not oversized:
                        line.extend(part)
                    if complete:
                        if not oversized:
                            accept_line(bytes(line))
                        complete_line = bytes(line)
                        secret_prefix = bool(re.search(rb'(?i)(?:password|secret|token|api[_-]?key)\\s*(?:[:=]\\s*)?$', complete_line))
                        continuation = pending_secret and not complete_line.strip()
                        continuation = continuation or (pending_secret and bool(re.fullmatch(rb'\\s*[:=]\\s*', complete_line)))
                        add_diagnostic(safe_line(complete_line, oversized,
                                                 private_line or pending_secret or secret_prefix))
                        pending_secret = secret_prefix or continuation or oversized
                        line.clear()
                        oversized = False
                        private_line = False
                        marker_recent = b''
                for block in iter(lambda: os.read(proc.stdout.fileno(), 8192), b''):
                    digest.update(block)
                    count += len(block)
                    window = recent + block
                    for index, marker in enumerate(markers):
                        if marker in window:
                            seen.add(index)
                    if b'BUILD_LIMIT=output' in window:
                        output_limit = 'output'
                    if b'BUILD_LIMIT=workspace' in window:
                        output_limit = 'workspace'
                    recent = window[-overlap:] if overlap else b''
                    parts = block.split(b'\\n')
                    for part in parts[:-1]:
                        append_line(part, True)
                    append_line(parts[-1], False)
                sender.join()
                exit_code = proc.wait()
                if line or oversized:
                    append_line(b'', True)
                compatible = any(all(markers.index(base64.b64decode(marker)) in seen for marker in group)
                                 for group in groups)
                print('WTL_BUILD_REPORT=1')
                print('EXIT=' + str(exit_code))
                print('BYTES=' + str(count))
                print('SHA256=' + digest.hexdigest())
                print('UID=' + controls.get(b'BUILD_UID', '-'))
                print('ENGINE=' + controls.get(b'BUILD_ENGINE', '-'))
                print('TOOL=' + controls.get(b'BUILD_TOOL', '-'))
                print('COMPAT=' + ('1' if compatible else '0'))
                print('LIMIT=' + (output_limit or '-'))
                print('CONFLICT=' + ('1' if conflict else '0'))
                print('HEAD=' + base64.b64encode(head).decode('ascii'))
                print('TAIL=' + base64.b64encode(tail).decode('ascii'))
                """
                .formatted(groups);
        return "exec /usr/bin/python3 -I -c " + shellQuote(python) + " "
                + shellQuote(DeploymentManagedHelperBundle.PATH) + " build-run " + shellQuote(workspace.applicationId())
                + " " + shellQuote(workspace.candidateId()) + " " + seconds + " " + processes + " " + memory + " "
                + output + " " + shellQuote(engine);
    }

    /** Parses only a complete fixed wrapper receipt; arbitrary build output never becomes a control field. / 仅解析完整固定包装器回执；任意构建输出不成为控制字段。
     * @param output bounded public stdout / 有界公开标准输出
     * @return verified short receipt / 经核实的短回执 */
    public static Receipt parse(String output) {
        Objects.requireNonNull(output);
        String[] lines = output.split("\n", -1);
        if (lines.length != 13 || !lines[12].isEmpty() || !lines[0].equals("WTL_BUILD_REPORT=1"))
            throw new IllegalArgumentException("Incomplete build report");
        if (!lines[1].matches("EXIT=-?[0-9]{1,4}") || !lines[2].matches("BYTES=[0-9]{1,12}")
                || !lines[3].matches("SHA256=[0-9a-f]{64}") || !lines[4].matches("UID=(?:-|[0-9]{1,10})")
                || !lines[5].matches("ENGINE=(?:-|ordinary|docker|podman)")
                || !lines[6].matches("TOOL=(?:-|[A-Z0-9_]{1,64})") || !lines[7].matches("COMPAT=[01]")
                || !lines[8].matches("LIMIT=(?:-|output|workspace)") || !lines[9].matches("CONFLICT=[01]")
                || !lines[10].matches("HEAD=[A-Za-z0-9+/=]{0,3000}")
                || !lines[11].matches("TAIL=[A-Za-z0-9+/=]{0,6000}"))
            throw new IllegalArgumentException("Invalid build report");
        return new Receipt(Integer.parseInt(lines[1].substring(5)), Long.parseLong(lines[2].substring(6)),
                lines[3].substring(7), lines[4].substring(4), lines[5].substring(7), lines[6].substring(5),
                lines[7].equals("COMPAT=1"), lines[8].substring(6), lines[9].equals("CONFLICT=1"),
                new String(Base64.getDecoder().decode(lines[10].substring(5)), StandardCharsets.UTF_8),
                new String(Base64.getDecoder().decode(lines[11].substring(5)), StandardCharsets.UTF_8));
    }

    /** Encodes the one Java compatibility policy as bounded Python literals. / 将唯一 Java 兼容策略编码为有界 Python 字面量。
     * @param groups reviewed disjunction of conjunctions / 已审阅合取组析取
     * @return immutable marker groups / 不可变标记组 */
    private static String groups(List<List<String>> groups) {
        Objects.requireNonNull(groups);
        StringBuilder result = new StringBuilder("[");
        for (List<String> group : groups) {
            if (result.length() > 1)
                result.append(',');
            result.append('[');
            for (String marker : group) {
                if (result.charAt(result.length() - 1) != '[')
                    result.append(',');
                result.append("b'").append(Base64.getEncoder().encodeToString(marker.getBytes(StandardCharsets.UTF_8)))
                        .append("'");
            }
            result.append(']');
        }
        return result.append(']').toString();
    }

    /** Quotes one fixed literal for the remote shell. / 为远端shell引用一个固定字面量。
     * @param value exact argument / 精确参数
     * @return quoted literal / 已引用字面量 */
    private static String shellQuote(String value) {
        return "'" + Objects.requireNonNull(value).replace("'", "'\"'\"'") + "'";
    }
}
