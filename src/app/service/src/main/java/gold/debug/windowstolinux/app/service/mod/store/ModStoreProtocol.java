package gold.debug.windowstolinux.app.service.mod.store;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;

/** Strict version-one catalog wire contract; author parsing stays with ManifestReader. / 严格版本一目录协议，作者解析仍归 ManifestReader。 */
final class ModStoreProtocol {
    /** Whole catalog bytes, independent of package size. / 完整目录字节，与包大小分开。 */
    static final int MAX_CATALOG_BYTES = 8 * 1024 * 1024;

    /** Duplicate and trailing-value rejecting existing JSON codec. / 拒绝重复及尾随值的既有 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Prevents stateful protocol construction. / 禁止有状态协议构造。 */
    private ModStoreProtocol() {
    }

    /** Reads exactly one bounded complete catalog. / 读取精确一份有界完整目录。
     * @param bytes actual HTTP bytes / 实际 HTTP 字节
     * @return strict complete catalog / 严格完整目录
     * @throws IOException if any protocol declaration is invalid / 任一协议声明无效时 */
    static ModStoreGateway.Catalog read(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_CATALOG_BYTES) throw new IOException("Invalid catalog size");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.charAt(0) == '\ufeff') throw new IOException("Catalog BOM is forbidden");
        try {
            var root = JSON.readTree(text);
            object(root, Set.of("formatVersion", "releases"));
            if (!root.path("formatVersion").isIntegralNumber() || !root.path("formatVersion").canConvertToInt() || root.path("formatVersion").intValue() != 1
                || !root.path("releases").isArray() || root.path("releases").size() > 1000)
                throw new IOException("Unsupported catalog format or bound");
            var releases = new ArrayList<ModStoreGateway.Release>();
            var identities = new HashSet<ModStoreSelection.ReleaseKey>();
            for (var node : root.path("releases")) {
                var release = release(node);
                if (!identities.add(new ModStoreSelection.ReleaseKey(release.owner(), release.version())))
                    throw new IOException("Duplicate exact catalog release");
                releases.add(release);
            }
            return new ModStoreGateway.Catalog(hash(bytes), releases);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid store declaration", invalid);
        }
    }

    /** Reads a complete release with no independent author schema. / 读取完整发布，不另造作者 schema。
     * @param node actual release object / 实际发布对象
     * @return strict release / 严格发布
     * @throws IOException if fields or author declaration fail / 字段或作者声明失败时 */
    private static ModStoreGateway.Release release(JsonNode node) throws IOException {
        object(node, Set.of("manifest", "packageUri", "bytes", "sha256", "purposes"));
        if (!node.path("bytes").isIntegralNumber() || !node.path("bytes").canConvertToLong()
            || !node.path("purposes").isObject()) throw new IOException("Invalid release fields");
        var purposes = new LinkedHashMap<String, String>();
        var fields = node.path("purposes").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            purposes.put(entry.getKey(), text(entry.getValue(), 4096));
        }
        return new ModStoreGateway.Release(ManifestReader.read(JSON.writeValueAsBytes(node.path("manifest"))),
            URI.create(text(node.path("packageUri"), 4096)), node.path("bytes").longValue(),
            text(node.path("sha256"), 64), purposes);
    }

    /** Rejects missing, unknown, null or non-object fields. / 拒绝缺失、未知、空值或非对象字段。
     * @param node actual object / 实际对象
     * @param fields exact protocol fields / 精确协议字段
     * @throws IOException if the object differs / 对象不符时 */
    private static void object(JsonNode node, Set<String> fields) throws IOException {
        if (node == null || !node.isObject() || node.size() != fields.size())
            throw new IOException("Incomplete catalog object");
        var names = node.fieldNames();
        while (names.hasNext()) {
            var key = names.next();
            if (!fields.contains(key) || node.path(key).isNull())
                throw new IOException("Unknown or null catalog field");
        }
    }

    /** Reads exact text without coercion. / 不转换类型地读取精确文本。
     * @param node actual field / 实际字段
     * @param maximum maximum text length / 文本长度上限
     * @return actual plain text / 实际纯文本
     * @throws IOException if text is invalid / 文本无效时 */
    private static String text(JsonNode node, int maximum) throws IOException {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maximum)
            throw new IOException("Invalid catalog text");
        return node.textValue();
    }

    /** Rejects package paths outside the same root before networking. / 网络请求前拒绝同根之外的包路径。
     * @param relative relative package URI / 相对包 URI */
    static void relative(URI relative) {
        if (relative == null || relative.isAbsolute() || relative.getRawAuthority() != null
            || relative.getRawQuery() != null || relative.getRawFragment() != null || relative.getPath() == null
            || relative.getRawPath().contains("%") || relative.getPath().chars().anyMatch(c -> c < 32 || c == 127)
            || relative.getPath().startsWith("/") || relative.getPath().contains("\\")
            || !relative.getPath().endsWith(".wtlmod"))
            throw new IllegalArgumentException("Package URI must be a relative .wtlmod path");
        for (var part : relative.getPath().split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Package URI traversal is forbidden");
    }

    /** Hashes exact received bytes. / 对精确收到字节计算摘要。
     * @param bytes actual bytes / 实际字节
     * @return lowercase SHA256 / 小写 SHA256 */
    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
