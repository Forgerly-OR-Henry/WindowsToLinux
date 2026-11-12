package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.SshCommandFrameProtocol;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHostProbe;

/** Navigates exact baseline script bytes, without claiming remote integrity or permission. / 导航精确基线脚本，不宣称远端完整性或许可。 */
public final class DeploymentAgentHostProbeEvidence {
    public static final String FIELD = "agentHostBaselineSourceEvidence";

    public static final String INSTRUCTION = """
        Current canonical host-baseline navigation identifies the exact rendered script in exactAction. The exact first-line marker requires the built-in SSH transport to check launcher paths through SFTP metadata on the same authenticated session before SSH exec. Inspect the fixed bootstrap paths, remote filesystem assumption and subsequent shell guards. apt-get, dnf and systemctl are presence checks only. The digest covers script bytes. Independent review still applies to this request and every reachable effect.
        """;

    private static final Pattern ACTION = Pattern.compile("\\AWTL-SSH-COMMAND-1\\ntimeout=([^\\n]+)\\noutputLimitBytes=([0-9]+)\\nstdinBytes=([0-9]+)\\nstdinSha256=([0-9a-f]{64})\\nscript:\\n([\\s\\S]+)\\z");

    private DeploymentAgentHostProbeEvidence() {
    }

    public static Optional<Map<String, Object>> select(JsonNode current) {
        String script = DeploymentAgentHostProbe.baseline();
        var match = ACTION.matcher(current.path("exactAction").asText());
        if (!"deployment.candidate.host-probe.v1".equals(current.path("operation").asText())
            || !match.matches() || !script.equals(match.group(5))) {
            if (current.has(FIELD))
                throw new IllegalArgumentException("Baseline navigation has no matching current script");
            return Optional.empty();
        }
        var servers = current.path("serverTargets");
        if (!servers.isArray() || servers.size() != 1 || !servers.get(0).isTextual()
            || !"EMPTY".equals(current.path("inputKind").asText()) || current.has("completeExecutableInput"))
            throw new IllegalArgumentException("Baseline observation requires one original server and empty stdin");
        var server = new ServerId(servers.get(0).textValue());
        var targets = current.path("resourceTargets");
        if (!targets.isArray() || targets.size() != 1 || targets.get(0).size() != 3
            || !server.id().equals(targets.get(0).path("serverId").asText())
            || !"deployment-host".equals(targets.get(0).path("kind").asText())
            || !server.id().equals(targets.get(0).path("id").asText()))
            throw new IllegalArgumentException("Baseline observation resource target changed");
        Duration timeout = Duration.parse(match.group(1));
        if (timeout.getNano() != 0 || timeout.getSeconds() < 1 || timeout.getSeconds() > 120
            || !"4096".equals(match.group(2)) || !"0".equals(match.group(3)) || !sha256("").equals(match.group(4)))
            throw new IllegalArgumentException("Baseline observation receipt bounds changed");
        try (var request = new CommandRequest(script, new byte[0], timeout, 4096)) {
            if (!RemoteActions.command(server, current.path("businessRevision").asText(), request).exactAction()
                .equals(current.path("exactAction").asText()))
                throw new IllegalArgumentException("Baseline observation action is not canonical");
        }
        String exact = current.path("exactAction").asText();
        return Optional.of(Map.of(
            "sourcePointer", "/exactAction", "scriptSha256", sha256(script),
            "scriptBytes", script.getBytes(StandardCharsets.UTF_8).length,
            "locations", List.of(location(exact, script),
                location(exact, SshCommandFrameProtocol.PROTECTED_SYSTEM_PROBE_HEADER),
                location(exact, "probe_parents() {"), location(exact, "probe_file() {"),
                location(exact, "probe_command() {"), location(exact, "os_release_value() {"),
                location(exact, "/usr/bin/uname -m || exit 64"),
                location(exact, "/usr/bin/dpkg --print-architecture || exit 64"),
                location(exact, "/usr/bin/rpm --eval '%{_arch}' || exit 64")),
            "presenceOnly", List.of("apt-get", "dnf", "systemctl"),
            "startupTrustAssumptions", List.of("authenticated SSH server, its SFTP subsystem and OS startup", "SFTP and SSH exec share the host filesystem view"),
            "startupAdmission", Map.of("marker", SshCommandFrameProtocol.PROTECTED_SYSTEM_PROBE_HEADER.stripTrailing(),
                "mechanism", "Same authenticated session, SFTP v3 LSTAT and READLINK before any exec. Requires root ownership, protected POSIX mode and fixed executable paths. Supports a protected /bin directory or the root-owned merged-usr link. Missing or unsafe metadata blocks dispatch.",
                "executables", List.of("/usr/bin/env", "/bin/bash", "/usr/bin/stat", "/usr/bin/readlink")),
            "meaning", "Current script byte identity and source navigation. Native query paths are checked by the script after launch-path admission. No remote binary SHA, race-free integrity, protection from malicious root, observed success or authorization is claimed."));
    }

    private static Map<String, Object> location(String source, String fragment) {
        int start = source.indexOf(fragment);
        if (start < 0 || source.indexOf(fragment, start + 1) >= 0)
            throw new IllegalArgumentException("Baseline source location is missing or ambiguous");
        int first = 1 + (int) source.substring(0, start).chars().filter(c -> c == '\n').count();
        int last = first + (int) fragment.stripTrailing().chars().filter(c -> c == '\n').count();
        return Map.of("sourcePointer", "/exactAction", "startLine", first, "endLine", last,
            "startCharacter", start, "endCharacter", start + fragment.length(), "utf8Sha256", sha256(fragment));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
