package gold.debug.windowstolinux.app.mod.installation;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.mod.development.DevelopmentDescriptor;

/** Runs build-owned CLASS description generation without loading author code. / 执行构建所属 CLASS 描述生成，不加载作者代码。 */
public final class ModPackageTool {
    /** Required build envelope fields, including packaging paths not consumed by describe. / 构建封套必需字段，包含 describe 不读取的打包路径。 */
    private static final Set<String> REQUIRED = Set.of("formatVersion", "workspace", "manifest", "mainJar",
            "authorJars", "libraries", "readme", "license", "mainOutput", "authorOutputs", "buildInputs");

    /** Strict JSON codec shared by input and result serialization. / 输入及结果序列化使用的严格 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Maximum build input bytes. / 构建输入最大字节数。 */
    private static final int MAX_INPUT = 1024 * 1024;

    /** Prevents construction of the build command entry. / 禁止构造构建命令入口。 */
    private ModPackageTool() {
    }

    /** Executes the supported build command in a separate Java process. / 在独立 Java 进程执行支持的构建命令。
     * @param args exact command and input arguments / 精确命令及输入参数 */
    public static void main(String[] args) {
        int status = execute(args, System.out, System.err);
        if (status != 0)
            System.exit(status);
    }

    /** Emits one UTF-8 result and a safe failure diagnostic. / 输出一条 UTF-8 结果及安全失败诊断。
     * @param args command arguments / 命令参数
     * @param output result stream / 结果流
     * @param errors diagnostic stream / 诊断流
     * @return zero on success, two for input errors, three for I/O or validation failures / 成功为零、输入错误为二、I/O 或校验失败为三 */
    public static int execute(String[] args, PrintStream output, PrintStream errors) {
        int status = 0;
        String event = null;
        Path artifact = null;
        String digest = null;
        try {
            if (args.length != 3 || !"describe".equals(args[0]) || !"--input".equals(args[1]))
                throw new IllegalArgumentException("Expected describe --input");
            artifact = describe(Path.of(args[2]));
            digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        } catch (IllegalArgumentException | JsonProcessingException invalid) {
            status = 2;
            event = "BUILD_INPUT_INVALID";
        } catch (IOException | NoSuchAlgorithmException invalid) {
            status = 3;
            event = "BUILD_DESCRIPTION_UNCONFIRMED";
        }
        var result = JSON.createObjectNode().put("command", "describe").put("success", status == 0);
        if (status == 0) {
            result.put("artifact", key(artifact)).put("contentSha256", digest);
        } else {
            result.putNull("artifact").putNull("contentSha256");
            errors.println(event);
        }
        var problems = result.putArray("problems");
        if (event != null)
            problems.add(event);
        try {
            output.write(JSON.writeValueAsBytes(result));
            output.write('\n');
            output.flush();
            return output.checkError() ? 3 : status;
        } catch (IOException failed) {
            return 3;
        }
    }

    /** Reads one bounded build envelope and delegates all output and digest checks to the runtime codec. / 读取有界构建封套，并将全部输出及摘要检查交由运行时共用编解码器。
     * @param input generated absolute input file / 已生成的绝对输入文件
     * @return generated descriptor / 已生成描述
     * @throws IOException if input bytes or compiled evidence are unavailable / 输入字节或编译证据不可用时 */
    public static Path describe(Path input) throws IOException {
        if (!input.isAbsolute() || !input.equals(input.normalize()))
            throw new IllegalArgumentException("Input path must be absolute and normalized");
        byte[] bytes;
        try (var stream = Files.newInputStream(input)) {
            bytes = stream.readNBytes(MAX_INPUT + 1);
        }
        if (bytes.length > MAX_INPUT)
            throw new IllegalArgumentException("Input size exceeded");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF"))
            throw new IllegalArgumentException("Input BOM is not supported");
        var node = JSON.readTree(text);
        if (node == null || !node.isObject() || !node.has("formatVersion") || !node.get("formatVersion").isInt()
                || node.get("formatVersion").intValue() != 1)
            throw new IllegalArgumentException("Unknown build format");
        for (String field : REQUIRED)
            if (!node.hasNonNull(field))
                throw new IllegalArgumentException("Missing build field");
        for (var fields = node.fields(); fields.hasNext();) {
            var field = fields.next();
            if ((!REQUIRED.contains(field.getKey()) && !field.getKey().equals("changelog"))
                    || field.getValue().isNull())
                throw new IllegalArgumentException("Unknown build field");
        }
        for (String field : List.of("mainJar", "readme", "license"))
            path(node.get(field));
        paths(node.get("authorJars"));
        if (node.has("changelog"))
            path(node.get("changelog"));
        return DevelopmentDescriptor.generate(path(node.get("workspace")), path(node.get("manifest")),
                path(node.get("mainOutput")), paths(node.get("authorOutputs")), paths(node.get("buildInputs")),
                paths(node.get("libraries")));
    }

    /** Parses a bounded ordered path list without interpreting Maven. / 解析有界有序路径列表，不解释 Maven。
     * @param node explicit resolved paths / 明示已解析路径
     * @return immutable ordered paths / 不可变有序路径 */
    private static List<Path> paths(JsonNode node) {
        if (!node.isArray() || node.size() > 4096)
            throw new IllegalArgumentException("Invalid path list");
        var result = new ArrayList<Path>();
        node.forEach(value -> result.add(path(value)));
        return List.copyOf(result);
    }

    /** Requires an explicit normalized local path. / 要求明示且规范化的本机路径。
     * @param node path string / 路径字符串
     * @return validated path syntax / 已校验语法的路径 */
    private static Path path(JsonNode node) {
        if (!node.isTextual() || node.textValue().isBlank())
            throw new IllegalArgumentException("Expected path text");
        var result = Path.of(node.textValue());
        if (!result.isAbsolute() || !result.equals(result.normalize()))
            throw new IllegalArgumentException("Expected absolute normalized path");
        return result;
    }

    /** Uses the descriptor's portable path spelling. / 使用描述中的可移植路径拼写。
     * @param path absolute path / 绝对路径
     * @return forward-slash spelling / 正斜线拼写 */
    private static String key(Path path) {
        return path.toString().replace('\\', '/');
    }
}
