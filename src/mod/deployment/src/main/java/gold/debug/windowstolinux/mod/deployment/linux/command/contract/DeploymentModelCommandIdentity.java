package gold.debug.windowstolinux.mod.deployment.linux.command.contract;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe;

/** Pure model command identity and executable evidence envelope. / 纯模型命令身份及可执行依据封套。 */
public final class DeploymentModelCommandIdentity {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentModelCommandIdentity() {
    }

    /** Strict duplicate and trailing-token rejecting JSON codec. / 拒绝重复字段及尾随内容的严格 JSON 编解码器。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Fixed isolated environment for model command execution. / 模型命令执行的固定隔离环境。 */
    public static final String ENVIRONMENT = "/usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LC_ALL=C HOME=/dev/null";

    /** Fixed launcher accepting the reviewed executable body. / 接收已审阅可执行正文的固定启动器。 */
    public static final String LAUNCHER = "exec " + ENVIRONMENT + " /bin/bash --noprofile --norc -s";

    /** Canonical model command evidence metadata prefix. / 规范模型命令依据元数据前缀。 */
    public static final String METADATA = "# WTL_MODEL_COMMAND ";

    /** Verifies the full model executable envelope before exposing command evidence. / 在暴露命令依据前核实完整模型可执行封套。
     * @param executable complete model command envelope / 完整模型命令封套
     * @return verified exact executable evidence / 已核实精确可执行依据
     * @throws java.io.IOException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static Map<String, Object> executableFacts(String executable) throws java.io.IOException {
        return executableFacts(executable, DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> executableFacts(String executable, DeploymentRemoteLayout layout) throws java.io.IOException {
        int firstLine = executable.indexOf('\n');
        if (!executable.startsWith(METADATA) || firstLine < 0 || firstLine > 20000)
            throw new IllegalArgumentException("Model command metadata is absent");
        String encoded = executable.substring(METADATA.length(), firstLine);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try {
            if (!Base64.getEncoder().encodeToString(bytes).equals(encoded))
                throw new IllegalArgumentException("Model command metadata is not canonical");
            var metadata = JSON.readTree(bytes);
            var fields = new HashSet<>(Set.of("task", "operation", "bodySha256", "rationale"));
            if (!layout.equals(DeploymentRemoteLayout.original())) fields.add("remoteLayout");
            DeploymentJsonFields.exact(metadata, fields);
            if (!layout.equals(DeploymentRemoteLayout.original())
                && !layout.canonical().equals(DeploymentJsonFields.text(metadata, "remoteLayout", 4096)))
                throw new IllegalArgumentException("Model command original layout changed");
            UUID task = UUID.fromString(DeploymentJsonFields.text(metadata, "task", 36));
            UUID operation = UUID.fromString(DeploymentJsonFields.text(metadata, "operation", 36));
            String body = DeploymentJsonFields.text(metadata, "bodySha256", 64);
            String rationale = DeploymentJsonFields.text(metadata, "rationale", 2000);
            String delimiter = "WTL_MODEL_" + operation.toString().replace("-", "");
            String begin = " /bin/bash --noprofile --norc -se <<'" + delimiter + "'\n"
                + "cd -- /\n" + DeploymentPlatformCapabilityProbe.probeGuards();
            int start = executable.indexOf(begin);
            if (start < 0) throw new IllegalArgumentException("Model command body is absent");
            start += begin.length();
            int end = executable.indexOf("\n" + delimiter + "\n", start);
            if (end < 0) throw new IllegalArgumentException("Model command body is incomplete");
            String script = executable.substring(start, end);
            if (!body.equals(digest(script)) || !executable.equals(envelope(task, operation, body, script, rationale, layout)))
                throw new IllegalArgumentException("Model command executable binding changed");
            return Map.of("task", task.toString(), "operation", operation.toString(), "bodySha256", body,
                "rationale", rationale, "modelScript", script, "productReceiptDirectory", layout.modelCommandOperationsRoot());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Computes the canonical SHA256 identity of the original content. / 计算原内容的规范 SHA256 身份。
     * @param s original content included in the digest or shell literal / 摘要或 Shell 字面量包含的原内容
     * @return canonical lowercase SHA256 / 规范小写 SHA256 */
    public static String digest(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Original durable model operation receipt directory. / 原持久模型操作回执目录。 */
    public static final String DIRECTORY = "/var/lib/windowstolinux/model-command-operations";

    /** Renders the original isolated model command and durable receipt protocol. / 生成原隔离模型命令及持久回执协议。
     * @param task original admitted task / 原已准入任务
     * @param operation original operation receipt identity / 原操作回执身份
     * @param digest exact reviewed executable content digest / 精确已审阅可执行内容摘要
     * @param script complete original reviewed executable script / 完整原已审阅可执行脚本
     * @param rationale bounded reason bound to the exact model body / 绑定精确模型正文的有界理由
     * @return complete original guarded executable body / 完整原受保护可执行正文 */
    public static String envelope(UUID task, UUID operation, String digest, String script, String rationale) {
        return envelope(task, operation, digest, script, rationale, DeploymentRemoteLayout.original());
    }

    public static String envelope(UUID task, UUID operation, String digest, String script, String rationale,
                                  DeploymentRemoteLayout layout) {
        String delimiter = "WTL_MODEL_" + operation.toString().replace("-", "");
        if (script.contains(delimiter)) throw new IllegalArgumentException("Model command delimiter conflict");
        if (rationale.isBlank() || rationale.length() > 2000)
            throw new IllegalArgumentException("Model command rationale invalid");
        var data = JSON.createObjectNode().put("task", task.toString()).put("operation", operation.toString())
            .put("bodySha256", digest).put("rationale", rationale);
        if (!layout.equals(DeploymentRemoteLayout.original())) data.put("remoteLayout", layout.canonical());
        String metadata = data.toString();
        return METADATA + Base64.getEncoder().encodeToString(metadata.getBytes(StandardCharsets.UTF_8))
            + "\nset -euo pipefail\nexport LC_ALL=C\n"
            + DeploymentRemoteLayoutAdmission.initialize(layout)
            + "for p in " + String.join(" ", DeploymentRemoteLayoutAdmission.ancestors(layout.paths().stateRoot()).stream()
            .map(DeploymentModelCommandIdentity::quote).toList())
            + "; do if [ ! -e \"$p\" ] && [ ! -L \"$p\" ]; then /usr/bin/install -d -o root -g root -m 700 -- \"$p\"; fi; [ ! -L \"$p\" ] && [ -d \"$p\" ] && [ \"$(/usr/bin/stat -c '%u:%g' \"$p\")\" = 0:0 ] || exit 64; m=$(/usr/bin/stat -c '%a' \"$p\"); (( (8#$m & 022) == 0 )) || exit 64; done\n"
            + "d=" + quote(layout.modelCommandOperationsRoot()) + "; if [ -e \"$d\" ] || [ -L \"$d\" ]; then [ ! -L \"$d\" ] && [ \"$(/usr/bin/stat -c '%u:%g:%a' \"$d\")\" = 0:0:700 ] || exit 64; else /usr/bin/install -d -o root -g root -m 700 -- \"$d\"; fi\n"
            + "umask 077\nr=\"$d/" + operation + ".receipt\"\n(set -C; printf '%s' " + quote(header(task, operation, digest, layout) + "STARTED=1\n") + " >\"$r\") || exit 64\n/usr/bin/sync -f \"$r\"\n/usr/bin/sync -f \"$d\"\n"
            + "set +e\n" + ENVIRONMENT + " /bin/bash --noprofile --norc -se <<'" + delimiter + "'\n"
            + "cd -- /\n" + DeploymentPlatformCapabilityProbe.probeGuards() + script + "\n" + delimiter
            + "\ne=$?\nset -e\nt=$(/usr/bin/mktemp \"$d/.terminal.XXXXXX\")\nprintf '%sEXIT=%s\\n' "
            + quote(header(task, operation, digest, layout)) + " \"$e\" >\"$t\"\n/usr/bin/sync -f \"$t\"\n/usr/bin/mv -fT -- \"$t\" \"$r\"\n/usr/bin/sync -f \"$d\"\n";
    }

    /** Renders the original task, operation and body digest receipt header. / 生成原任务、操作及正文摘要回执头。
     * @param task original admitted task / 原已准入任务
     * @param operation original operation receipt identity / 原操作回执身份
     * @param body original executable body digest / 原可执行正文摘要
     * @return exact original durable receipt header / 精确原持久回执头 */
    public static String header(UUID task, UUID operation, String body) {
        return header(task, operation, body, DeploymentRemoteLayout.original());
    }

    public static String header(UUID task, UUID operation, String body, DeploymentRemoteLayout layout) {
        return "FORMAT=" + (layout.equals(DeploymentRemoteLayout.original()) ? "1" : "2") + "\nTASK=" + task
            + "\nOPERATION=" + operation + "\nBODY_SHA256=" + body + "\n"
            + (layout.equals(DeploymentRemoteLayout.original()) ? "" : "LAYOUT_SHA256=" + layout.digest() + "\n");
    }

    /** Reads only a finite, original-identity receipt protocol; arbitrary file bytes never become output. / 仅读取有限原身份回执协议，任意文件字节不成为输出。
     * @param task original task / 原任务
     * @param operation original operation / 原操作
     * @param body original executable digest / 原可执行摘要
     * @param layout captured original layout / 捕获的原目录
     * @return complete independently reviewable reader / 完整可独立审阅的读取器 */
    public static String receiptStatus(UUID task, UUID operation, String body, DeploymentRemoteLayout layout) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(layout);
        if (body == null || !body.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Original receipt identity is invalid");
        String header = header(task, operation, body, layout);
        String reader = """
            set -euo pipefail
            export LC_ALL=C
            for p in @PARENTS@ @DIRECTORY@; do
              [ ! -L "$p" ] && [ -d "$p" ] || exit 3
              [ "$(/usr/bin/stat -c '%u:%g' -- "$p")" = '0:0' ] || exit 3
              m=$(/usr/bin/stat -c '%a' -- "$p")
              [[ "$m" =~ ^[0-7]{3,4}$ ]] && (( (8#$m & 022) == 0 )) || exit 3
            done
            [ "$(/usr/bin/stat -c '%u:%g:%a' -- @DIRECTORY@)" = '0:0:700' ] || exit 3
            r=@RECEIPT@
            [ ! -L "$r" ] && [ -f "$r" ] || exit 3
            format='%f:%u:%g:%a:%h:%s:%d:%i:%y:%z'
            before=$(/usr/bin/stat -c "$format" -- "$r")
            exec 9< "$r"
            opened=$(/usr/bin/stat -Lc "$format" -- /proc/self/fd/9)
            [ "$before" = "$opened" ] || exit 3
            IFS=: read -r kind uid gid mode links bytes device inode times <<< "$opened"
            [[ "$kind" =~ ^[0-9a-f]+$ && "$bytes" =~ ^(0|[1-9][0-9]{0,3})$ ]] || exit 3
            (( (16#$kind & 0170000) == 0100000 )) || exit 3
            [ "$uid:$gid:$mode:$links" = '0:0:600:1' ] && (( bytes <= 1024 )) || exit 3
            hex=$(/usr/bin/od -An -v -tx1 -N1025 <&9)
            after=$(/usr/bin/stat -Lc "$format" -- /proc/self/fd/9)
            [ ! -L "$r" ] && [ -f "$r" ] || exit 3
            [ "$opened" = "$after" ] && [ "$opened" = "$(/usr/bin/stat -c "$format" -- "$r")" ] || exit 3
            hex=${hex// /}
            hex=${hex//$'\\n'/}
            [[ "$hex" =~ ^[0-9a-f]+$ ]] && (( ${#hex} == bytes * 2 )) || exit 3
            prefix=@HEADER_HEX@
            [[ "$hex" == "$prefix"* ]] || exit 3
            tail=${hex:${#prefix}}
            if [ "$tail" = '535441525445443d310a' ]; then
              builtin printf '%sSTARTED=1\\n' @HEADER@
            elif [[ "$tail" =~ ^455849543d(3[0-9]){1,3}0a$ ]]; then
              digits=${tail:10:${#tail}-12}
              code=''
              while [ -n "$digits" ]; do code+=${digits:1:1}; digits=${digits:2}; done
              [[ "$code" =~ ^(0|[1-9][0-9]{0,2})$ ]] && (( 10#$code <= 255 )) || exit 3
              builtin printf '%sEXIT=%s\\n' @HEADER@ "$code"
            else
              exit 3
            fi
            """.replace("@PARENTS@", String.join(" ", DeploymentRemoteLayoutAdmission.ancestors(layout.paths().stateRoot()).stream()
                .map(DeploymentModelCommandIdentity::quote).toList()))
            .replace("@DIRECTORY@", quote(layout.modelCommandOperationsRoot()))
            .replace("@RECEIPT@", quote(layout.modelCommandOperationsRoot() + "/" + operation + ".receipt"))
            .replace("@HEADER_HEX@", quote(HexFormat.of().formatHex(header.getBytes(StandardCharsets.UTF_8))))
            .replace("@HEADER@", quote(header));
        // A clean nested shell also prevents inherited xtrace/BASH_ENV from exposing internal hex. / 干净的内层Shell同时防止继承跟踪或启动脚本暴露内部hex。
        return "exec " + ENVIRONMENT + " /bin/bash --noprofile --norc -c " + quote(reader) + " 2>/dev/null";
    }

    /** Quotes one literal for the fixed Bash protocol without evaluating it. / 将一个字面量引用到固定 Bash 协议中，不求值。
     * @param s original content included in the digest or shell literal / 摘要或 Shell 字面量包含的原内容
     * @return quoted literal without shell expansion / 不含 Shell 展开的引用字面量 */
    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
