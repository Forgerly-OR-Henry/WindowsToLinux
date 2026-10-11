package gold.debug.windowstolinux.mod.deployment.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentServerIdentity;

/** Pure layout identity derived only from explicitly supplied validated roots. / 仅由显式传入的已校验根派生的纯布局身份。
 * @param paths immutable roots validated by the configuration contract / 配置合同已校验的不可变根 */
public record DeploymentRemoteLayout(DeploymentConfig.RemotePaths paths) {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_ENCODED_BYTES = 4096;
    private static final Set<String> ROOT_FIELDS = Set.of("helperDirectory", "applicationsRoot", "stateRoot",
        "configurationRoot", "dataRoot");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public DeploymentRemoteLayout {
        Objects.requireNonNull(paths, "paths");
    }

    /** Explicit original-layout choice for a caller's known legacy branch. / 供调用方已知旧版分支显式选择原布局。 */
    public static DeploymentRemoteLayout original() {
        return new DeploymentRemoteLayout(DeploymentConfig.RemotePaths.original());
    }

    public int version() {
        return FORMAT_VERSION;
    }

    /** Fixed field order and literal UTF-8 JSON without a trailing newline. / 固定字段顺序且无末尾换行的字面 UTF-8 JSON。 */
    public String canonical() {
        var node = JSON.createObjectNode().put("formatVersion", FORMAT_VERSION);
        var remote = node.putObject("remote");
        remote.put("helperDirectory", paths.helperDirectory());
        remote.put("applicationsRoot", paths.applicationsRoot());
        remote.put("stateRoot", paths.stateRoot());
        remote.put("configurationRoot", paths.configurationRoot());
        remote.put("dataRoot", paths.dataRoot());
        return node.toString();
    }

    /** SHA-256 of exactly the canonical UTF-8 bytes. / 精确规范 UTF-8 字节的 SHA-256。 */
    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Requires the complete canonical v1 value; never supplies current or historical roots. / 要求完整规范 v1 值，绝不补当前或历史根。 */
    public static DeploymentRemoteLayout decode(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED_BYTES
            || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Remote layout evidence is missing or exceeds limits");
        try {
            var node = JSON.readTree(encoded);
            fields(node, Set.of("formatVersion", "remote"));
            var version = node.path("formatVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != FORMAT_VERSION)
                throw new IllegalArgumentException("Remote layout version is unsupported");
            var remote = node.path("remote");
            fields(remote, ROOT_FIELDS);
            for (String field : ROOT_FIELDS) {
                if (!remote.path(field).isTextual())
                    throw new IllegalArgumentException("Remote layout root must be text: " + field);
            }
            var layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(remote.path("helperDirectory").textValue(),
                remote.path("applicationsRoot").textValue(), remote.path("stateRoot").textValue(),
                remote.path("configurationRoot").textValue(), remote.path("dataRoot").textValue()));
            if (!encoded.equals(layout.canonical()))
                throw new IllegalArgumentException("Remote layout encoding is not canonical");
            return layout;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Remote layout evidence is invalid", invalid);
        }
    }

    public String helperPath() {
        return paths.helperDirectory() + "/managed-helper";
    }

    public String buildEntry() {
        return paths.helperDirectory() + "/build-entry";
    }

    public String toolchains() {
        return paths.helperDirectory() + "/toolchains";
    }

    public String workRoot() {
        return paths.stateRoot() + "/work";
    }

    public String candidateRoot(String candidate) {
        Objects.requireNonNull(candidate, "candidate");
        int separator = candidate.lastIndexOf('-');
        if (separator < 1 || !candidate.substring(separator + 1).matches("[0-9a-f]{16}"))
            throw new IllegalArgumentException("Invalid candidate identity");
        application(candidate.substring(0, separator));
        return workRoot() + "/" + candidate;
    }

    public String applicationRoot(String application) {
        return paths.applicationsRoot() + "/" + application(application);
    }

    public String configurationRoot(String application) {
        return paths.configurationRoot() + "/" + application(application);
    }

    public String dataRoot(String application) {
        return paths.dataRoot() + "/" + application(application);
    }

    public String snapshotsRoot() {
        return paths.stateRoot() + "/snapshots";
    }

    public String snapshotRoot(String application, UUID originalTask) {
        return snapshotsRoot() + "/" + application(application) + "-" + Objects.requireNonNull(originalTask, "originalTask");
    }

    public String backupsRoot() {
        return paths.stateRoot() + "/backups";
    }

    public String applicationLockRoot(String application) {
        return paths.stateRoot() + "/application-locks/" + application(application);
    }

    public String helperInstallOperationsRoot() {
        return paths.stateRoot() + "/helper-install-operations";
    }

    public String modelCommandOperationsRoot() {
        return paths.stateRoot() + "/model-command-operations";
    }

    public String environmentOperationsRoot() {
        return paths.stateRoot() + "/environment-operations";
    }

    private static String application(String value) {
        if (!Objects.equals(value, DeploymentServerIdentity.requireIdentifier(value, "application")))
            throw new IllegalArgumentException("Application identity must already be canonical");
        return value;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Remote layout evidence must be an object");
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected))
            throw new IllegalArgumentException("Remote layout fields differ from the required contract");
    }
}
