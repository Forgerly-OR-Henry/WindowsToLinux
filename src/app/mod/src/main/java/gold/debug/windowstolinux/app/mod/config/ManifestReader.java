package gold.debug.windowstolinux.app.mod.config;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.lang.model.SourceVersion;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.mod.version.VersionRange;

/** Strictly reads bounded UTF-8 author manifests without loading author classes. / 严格读取有界 UTF-8 作者清单，不加载作者类。 */
public final class ManifestReader {
    /** Maximum original manifest bytes. / 原始清单字节上限。 */
    public static final int MAX_BYTES = 256 * 1024;

    /** Strict JSON parser shared without mutable per-read configuration. / 不改变逐次读取配置的共享严格 JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** All author fields accepted by format version one. / 格式版本一接受的所有作者字段。 */
    private static final Set<String> FIELDS = Set.of("formatVersion", "id", "name", "version", "platformApiRange",
        "minJavaVersion", "mainJar", "entryClass", "description", "authors", "homepage", "dependencies", "exports",
        "schemaVersion", "migrations");

    /** Prevents creation of this stateless parser. / 禁止创建此无状态解析器实例。 */
    private ManifestReader() {
    }

    /**
     * Reads exactly one supported manifest and validates structural author contracts. / 读取精确一份受支持清单并验证作者结构契约。
     * @param bytes original manifest bytes / 原始清单字节
     * @return validated immutable author metadata / 已验证的不可变作者元数据
     * @throws IOException if encoding, JSON or any declared contract is invalid / 编码、JSON 或任一声明契约无效时
     */
    public static ModManifest read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IOException("Manifest must contain 1 to 262144 bytes");
        String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (source.charAt(0) == '\ufeff')
            throw new IOException("Manifest UTF-8 BOM is forbidden");
        try {
            var node = JSON.readTree(source);
            object(node, FIELDS);
            if (integer(node, "formatVersion") != 1)
                throw new IllegalArgumentException("Unsupported manifest format");
            var id = javaName(text(node, "id", 512));
            var name = text(node, "name", 128);
            var version = SemanticVersion.parse(text(node, "version", 512));
            var range = VersionRange.parse(text(node, "platformApiRange", 512));
            int java = integer(node, "minJavaVersion");
            if (java < 1)
                throw new IllegalArgumentException("Invalid Java major version");
            var mainJar = text(node, "mainJar", 512);
            if (!mainJar.matches("main/[^/]+\\.jar"))
                throw new IllegalArgumentException("Main JAR must be directly inside main/");
            archiveName(mainJar);
            var entry = javaName(text(node, "entryClass", 512));
            if (!entry.endsWith(".Mod"))
                throw new IllegalArgumentException("Entry simple name must be Mod");
            var root = entry.substring(0, entry.length() - 4);
            Optional<String> description = node.has("description")
                ? Optional.of(text(node, "description", 4096))
                : Optional.empty();
            var authors = strings(node, "authors", 32, 128);
            Optional<URI> homepage = Optional.empty();
            if (node.has("homepage")) {
                var uri = URI.create(text(node, "homepage", 2048));
                if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null)
                    throw new IllegalArgumentException("Homepage must be absolute HTTPS without user information");
                homepage = Optional.of(uri);
            }
            var dependencies = new ArrayList<ModManifest.Dependency>();
            var ids = new HashSet<String>();
            for (var dependency : array(node, "dependencies", 128)) {
                object(dependency, Set.of("id", "versionRange"));
                var provider = javaName(text(dependency, "id", 512));
                if (!ids.add(provider))
                    throw new IllegalArgumentException("Duplicate dependency: " + provider);
                dependencies.add(new ModManifest.Dependency(provider,
                    VersionRange.parse(text(dependency, "versionRange", 512))));
            }
            var exports = strings(node, "exports", 128, 512);
            var packages = new HashSet<String>();
            for (var exported : exports) {
                javaName(exported);
                if (!exported.equals(root) && !exported.startsWith(root + "."))
                    throw new IllegalArgumentException("Export is outside the entry package");
                if (!packages.add(exported))
                    throw new IllegalArgumentException("Duplicate export");
            }
            Optional<String> schema = node.has("schemaVersion")
                ? Optional.of(schema(text(node, "schemaVersion", 64)))
                : Optional.empty();
            if (node.has("migrations") && schema.isEmpty())
                throw new IllegalArgumentException("Migrations require schemaVersion");
            var migrations = new ArrayList<ModManifest.Migration>();
            var edges = new HashSet<String>();
            for (var migration : array(node, "migrations", 256)) {
                object(migration, Set.of("from", "to", "class"));
                var from = schema(text(migration, "from", 64));
                var to = schema(text(migration, "to", 64));
                if (new BigInteger(from).compareTo(new BigInteger(to)) >= 0
                    || new BigInteger(to).compareTo(new BigInteger(schema.orElseThrow())) > 0)
                    throw new IllegalArgumentException("Migration must increase toward the declared schema");
                if (!edges.add(from + ":" + to))
                    throw new IllegalArgumentException("Duplicate migration edge");
                migrations.add(new ModManifest.Migration(from, to, javaName(text(migration, "class", 512))));
            }
            return new ModManifest(id, name, version, range, java, mainJar, entry, description, authors, homepage,
                dependencies, exports, schema, migrations, source);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid Mod manifest: " + failure.getMessage(), failure);
        }
    }

    /**
     * Rejects unknown fields and explicit null values at an object boundary. / 在对象边界拒绝未知字段及显式空值。
     * @param node expected JSON object / 预期 JSON 对象
     * @param allowed allowed field names / 允许的字段名
     */
    private static void object(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Expected JSON object");
        node.fields().forEachRemaining(entry -> {
            if (!allowed.contains(entry.getKey()) || entry.getValue().isNull())
                throw new IllegalArgumentException("Unknown or null field: " + entry.getKey());
        });
    }

    /**
     * Reads one required bounded integer without coercion. / 读取一个必填有界整数，不执行类型转换。
     * @param node containing object / 包含字段的对象
     * @param key required field / 必填字段
     * @return exact integer / 精确整数
     */
    private static int integer(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException("Invalid integer: " + key);
        return value.intValue();
    }

    /**
     * Reads required text using Unicode code-point length. / 按 Unicode 码点长度读取必填文本。
     * @param node containing object / 包含字段的对象
     * @param key required field / 必填字段
     * @param maximum maximum code points / 码点数量上限
     * @return bounded nonblank text / 有界非空白文本
     */
    private static String text(JsonNode node, String key, int maximum) {
        return text(node.get(key), maximum, key);
    }

    /**
     * Validates one textual value without implicit conversions. / 验证一个文本值，不执行隐式转换。
     * @param value expected string node / 预期字符串节点
     * @param maximum maximum code points / 码点数量上限
     * @param label field used in diagnostics / 诊断使用的字段
     * @return bounded nonblank text / 有界非空白文本
     */
    private static String text(JsonNode value, int maximum, String label) {
        if (value == null || !value.isTextual())
            throw new IllegalArgumentException("Invalid string: " + label);
        String text = value.textValue();
        if (text.isBlank() || text.codePointCount(0, text.length()) > maximum)
            throw new IllegalArgumentException("Invalid text length: " + label);
        return text;
    }

    /**
     * Reads an optional bounded array, treating absence as empty. / 读取可选有界数组，将缺省视为空数组。
     * @param node containing object / 包含字段的对象
     * @param key optional field / 可选字段
     * @param maximum maximum elements / 元素数量上限
     * @return immutable element list / 不可变元素列表
     */
    private static List<JsonNode> array(JsonNode node, String key, int maximum) {
        if (!node.has(key))
            return List.of();
        var value = node.get(key);
        if (!value.isArray() || value.size() > maximum)
            throw new IllegalArgumentException("Invalid array: " + key);
        var values = new ArrayList<JsonNode>();
        value.forEach(values::add);
        return List.copyOf(values);
    }

    /**
     * Reads a bounded array of nonblank strings. / 读取由非空白字符串组成的有界数组。
     * @param node containing object / 包含字段的对象
     * @param key optional field / 可选字段
     * @param count maximum elements / 元素数量上限
     * @param length maximum code points per element / 每项码点数量上限
     * @return immutable text list / 不可变文本列表
     */
    private static List<String> strings(JsonNode node, String key, int count, int length) {
        return array(node, key, count).stream().map(value -> text(value, length, key)).toList();
    }

    /**
     * Validates an exact Java package or class name for JDK 21. / 验证精确的 JDK 21 Java 包名或类名。
     * @param name author identity / 作者身份
     * @return unchanged validated name / 未改变的已验证名称
     */
    private static String javaName(String name) {
        if (!SourceVersion.isName(name, SourceVersion.RELEASE_21))
            throw new IllegalArgumentException("Invalid Java name: " + name);
        return name;
    }

    /**
     * Validates a positive canonical decimal data version. / 验证规范的正整数十进制数据版本。
     * @param version author data version / 作者数据版本
     * @return unchanged validated version / 未改变的已验证版本
     */
    private static String schema(String version) {
        if (!version.matches("[1-9][0-9]{0,63}"))
            throw new IllegalArgumentException("Invalid schema version");
        return version;
    }

    /**
     * Rejects unsafe Windows components in the declared main JAR path. / 拒绝声明主 JAR 路径中的不安全 Windows 路径组成。
     * @param path validated two-component main JAR path / 已验证为两段的主 JAR 路径
     */
    private static void archiveName(String path) {
        for (String part : path.split("/"))
            if (part.matches(".*[<>:\"\\\\|?*\\x00-\\x1f].*") || part.endsWith(".") || part.endsWith(" ")
                || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?"))
                throw new IllegalArgumentException("Unsafe main JAR path");
    }
}
