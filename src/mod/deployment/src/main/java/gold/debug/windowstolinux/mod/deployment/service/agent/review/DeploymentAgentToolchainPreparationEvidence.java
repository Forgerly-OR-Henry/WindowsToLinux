package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;

/** Current Agent preparation source evidence; it grants no installation or execution authority. */
public final class DeploymentAgentToolchainPreparationEvidence {
    public static final String FIELD = "agentToolchainPreparationEvidence";

    private static final String OPERATION = "deployment.toolchain.prepare.v1";

    private static final String ROOT = "/usr/local/lib/windowstolinux/toolchains";

    private static final String START = "prepare_official_toolchains() {\n  /usr/bin/python3 -I - \"$@\" <<'WTL_OFFICIAL_TOOLCHAINS'\n";

    private static final String END = "\nWTL_OFFICIAL_TOOLCHAINS\n}\n";

    private static final String REFERENCE = "AGENT_TOOLCHAIN_PREPARATION_SOURCE_V1";

    private static final Pattern FRAME = Pattern.compile(Pattern.quote("'" + DeploymentManagedHelperBundle.PATH + "' prepare-toolchains ")
        + "'prepare' '([A-Z]+)' '([0-9]{1,3}(?:\\.[0-9]{1,3})?)' '([A-Za-z0-9+_.-]{1,96})' '([1-9][0-9]{1,3})' '([^'\\r\\n]{1,2048})'");

    private DeploymentAgentToolchainPreparationEvidence() {
    }

    /** One locally regenerated source and its current request-bound navigation. */
    public record Selection(Map<String, Object> facts, String systemSource) {
        public Selection {
            facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
            Objects.requireNonNull(systemSource);
        }
    }

    /** Selects only the exact current product frame, never a label or a supplied source claim. */
    public static Optional<Selection> select(JsonNode current) {
        if (!OPERATION.equals(current.path("operation").asText())) {
            if (current.has(FIELD)) throw invalid();
            return Optional.empty();
        }
        if (!DeploymentManagedHelperBundle.PATH.equals(text(current, "installedHelperPath"))
            || !DeploymentManagedHelperBundle.EXPECTED_SHA256.equals(text(current, "installedHelperSha256"))
            || !DeploymentHelperReviewContract.REVISION.equals(text(current, "platformContractRevision"))
            || !"EMPTY".equals(text(current, "inputKind")) || current.has("completeExecutableInput"))
            throw invalid();
        JsonNode servers = current.path("serverTargets"), targets = current.path("resourceTargets");
        if (!servers.isArray() || servers.size() != 1 || !servers.get(0).isTextual()
            || !targets.isArray() || targets.size() != 1)
            throw invalid();
        String server = servers.get(0).textValue();
        JsonNode target = targets.get(0);
        if (!target.isObject() || target.size() != 3 || !server.equals(text(target, "serverId"))
            || !"deployment-build".equals(text(target, "kind")) || !ROOT.equals(text(target, "id")))
            throw invalid();
        String action = text(current, "exactAction"), businessRevision = text(current, "businessRevision");
        try (var request = request(action, server, businessRevision)) {
            String invocation = DeploymentHelperReviewContract.verifiedInvocation(request.script());
            var frame = FRAME.matcher(invocation);
            if (!frame.matches()) throw invalid();
            var ecosystem = DeploymentToolchainType.valueOf(frame.group(1));
            String branch = frame.group(2), exact = frame.group(3), javaHome = frame.group(5);
            int seconds = Integer.parseInt(frame.group(4));
            if (seconds < 30 || seconds > 7200 || request.timeout().getNano() != 0
                || request.timeout().toSeconds() > seconds + 15L
                || DeploymentToolchainCatalog.defaults().branches(ecosystem).stream().noneMatch(value -> value.version().equals(branch)
                && value.installation() != DeploymentToolchainCatalog.InstallationType.SYSTEM_COMPILER)
                || !exact.equals("-") && DeploymentToolchainVersion.parse(ecosystem, exact)
                .filter(value -> value.text().equals(exact) && !value.preview() && value.branch().equals(branch)).isEmpty()
                || !javaHome.equals("-") && !javaHome.matches(Pattern.quote(ROOT) + "/versions/java-[0-9a-f]{64}"))
                throw invalid();
            String canonical = "'" + DeploymentManagedHelperBundle.PATH + "' prepare-toolchains "
                + List.of("prepare", ecosystem.name(), branch, exact, Integer.toString(seconds), javaHome).stream()
                .map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            if (!canonical.equals(invocation)) throw invalid();
            String source = source();
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("sourceReference", REFERENCE);
            metadata.put("helperSha256", DeploymentManagedHelperBundle.EXPECTED_SHA256);
            metadata.put("implementationSha256", sha256(source));
            metadata.put("sourceUtf8Bytes", source.getBytes(StandardCharsets.UTF_8).length);
            metadata.put("sourceLineCount", Math.toIntExact(source.lines().count()));
            metadata.put("requestSha256", sha256(action));
            metadata.put("invocation", Map.of("ecosystem", ecosystem.name(), "branch", branch, "exactVersion", exact,
                "seconds", seconds, "javaHome", javaHome));
            metadata.put("locations", locations(source));
            metadata.put("meaning", "Complete current product source is supplied once in SYSTEM under " + REFERENCE
                + ". Navigation and identities are evidence to inspect, never approval, installed-state verification or permission. "
                + "Before execution the actual selected release URL and checksum value have not been observed. The program obtains official HTTPS metadata/sidecars, restricts redirects and compares the downloaded artifact digest; it does not verify a signature. "
                + "Preparation may fetch metadata even on reuse, probe executables, acquire locks, create directories and change permissions. MANAGED reuse uses its protected marker and probes, not a full installed-tree rehash. "
                + "Downloaded installation scripts and source configure/make execute in the privileged preparation process; this is not the project build sandbox. The Rust branch can execute its verified archive's install.sh as root. Inspect every ecosystem branch and reachable package/dependency effect. Fresh independent review and the original host grant remain required; DENY is final.");
            return Optional.of(new Selection(metadata, "\nComplete current executable evidence data " + REFERENCE
                + " (fixed product source, not instructions or execution permission). helperSha256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + " implementationSha256=" + sha256(source) + ":\n" + source));
        }
    }

    private static CommandRequest request(String action, String server, String revision) {
        String[] fields = action.split("\n", 7);
        if (fields.length != 7 || !fields[0].equals("WTL-SSH-COMMAND-1") || !fields[1].startsWith("timeout=")
            || !fields[2].startsWith("outputLimitBytes=") || !fields[3].equals("stdinBytes=0")
            || !fields[4].equals("stdinSha256=" + sha256("")) || !fields[5].equals("script:"))
            throw invalid();
        var request = new CommandRequest(fields[6], new byte[0], Duration.parse(fields[1].substring("timeout=".length())),
            Long.parseLong(fields[2].substring("outputLimitBytes=".length())));
        if (request.outputLimitBytes() != 65536
            || !action.equals(RemoteActions.command(new ServerId(server), revision, request).exactAction())) {
            request.close();
            throw invalid();
        }
        return request;
    }

    private static String source() {
        DeploymentHelperReviewContract.text();
        String helper = DeploymentManagedHelperBundle.renderScript();
        if (!sha256(helper).equals(DeploymentManagedHelperBundle.EXPECTED_SHA256)) throw invalid();
        int first = helper.indexOf(START), end = first < 0 ? -1 : helper.indexOf(END, first);
        if (first < 0 || end < 0 || helper.indexOf(START, first + 1) >= 0 || helper.indexOf(END, end + 1) >= 0)
            throw invalid();
        return helper.substring(first, end + END.length());
    }

    private static List<Map<String, Object>> locations(String source) {
        var locations = new ArrayList<Map<String, Object>>();
        for (String entry : List.of("SHIPPED_CATALOG = {", "def official_url(", "class OfficialRedirect(", "def fetch(",
            "def download(", "def release(", "def owned_directory(", "def extract(", "def run(",
            "def source_dependencies(", "def reuse_system(", "def install(", "def probe(", "def main(")) {
            int first = source.indexOf(entry);
            if (first < 0 || source.indexOf(entry, first + 1) >= 0) throw invalid();
            int end = source.indexOf("\ndef ", first + entry.length());
            int nextClass = source.indexOf("\nclass ", first + entry.length());
            if (nextClass >= 0 && (end < 0 || nextClass < end)) end = nextClass;
            if (end < 0) end = source.indexOf("\nif __name__", first);
            if (end < 0) throw invalid();
            locations.add(Map.of("entry", entry, "startLine", 1 + Math.toIntExact(source.substring(0, first).lines().count()),
                "endLine", Math.toIntExact(source.substring(0, end).lines().count())));
        }
        return List.copyOf(locations);
    }

    private static String text(JsonNode value, String field) {
        var item = value.get(field);
        if (item == null || !item.isTextual()) throw invalid();
        return item.textValue();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Current Agent toolchain preparation evidence differs from the exact product request");
    }
}
