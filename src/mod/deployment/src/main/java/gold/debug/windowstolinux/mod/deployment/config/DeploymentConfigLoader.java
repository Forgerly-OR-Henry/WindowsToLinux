package gold.debug.windowstolinux.mod.deployment.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Loads this Mod's strict bundled YAML without accessing APP configuration. / 加载本 Mod 的严格随包 YAML，不访问 APP 配置。
 */
public final class DeploymentConfigLoader {
    /** Mod-owned classpath resource. / Mod 所属 classpath 资源。 */
    public static final String RESOURCE = "/gold/debug/windowstolinux/mod/deployment/config/config.yaml";

    /** One immutable resource snapshot per selected Mod classloader. / 每个选中 Mod 类加载器的一份不可变资源快照。 */
    private static DeploymentConfig bundled;

    /** Prevents construction. / 禁止构造。 */
    private DeploymentConfigLoader() {
    }

    /** Loads once before startup registration and shares the same immutable values. / 启动登记前加载一次并共享同一不可变值。
     * @return bundled configuration / 随包配置
     * @throws IllegalStateException if the bundled resource is unavailable or invalid / 随包资源缺失或非法时 */
    public static synchronized DeploymentConfig current() {
        if (bundled == null) {
            try (var input = DeploymentConfigLoader.class.getResourceAsStream(RESOURCE)) {
                bundled = load(input);
            } catch (IOException invalid) {
                throw new IllegalStateException("Deployment configuration is unavailable: " + RESOURCE, invalid);
            }
        }
        return bundled;
    }

    /** Parses one bounded YAML; the caller owns the stream. / 解析一份有界 YAML；调用方持有流。
     * @param input module configuration stream / 模块配置流
     * @return validated immutable settings / 已校验不可变配置
     * @throws IOException if fields, types, ranges or syntax are invalid / 字段、类型、范围或语法非法时 */
    public static DeploymentConfig load(InputStream input) throws IOException {
        if (input == null) throw new IOException("Deployment configuration resource is missing");
        byte[] bytes = input.readNBytes(65_537);
        if (bytes.length > 65_536) throw new IOException("Deployment configuration exceeds 64 KiB");
        var factory = YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        var mapper = new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var root = mapper.readTree(bytes);
        fields(root, Set.of("source", "build", "environment", "view", "ai", "paths"));
        var ai = root.path("ai");
        fields(ai, Set.of("deployment-max-output-tokens", "extended-deployment-max-output-tokens", "approval-max-output-tokens",
            "requests", "execution", "history"));
        var requests = ai.path("requests");
        fields(requests, Set.of("deployment-timeout-seconds", "approval-timeout-seconds", "extended-timeout-seconds", "generation-recovery-attempts"));
        var execution = ai.path("execution");
        fields(execution, Set.of("shared-tool-attempts", "standalone-tool-attempts", "local-tool-turns", "agent-model-responses", "retained-review-sessions", "max-observations"));
        var history = ai.path("history");
        fields(history, Set.of("max-batches", "characters", "payload-bytes", "initial-batches", "initial-payload-bytes"));
        var source = root.path("source");
        fields(source, Set.of("bytes", "entries", "archive-bytes"));
        var build = root.path("build");
        fields(build, Set.of("timeout-seconds", "processes", "memory-mib", "output-bytes", "workspace-bytes", "workspace-max-bytes", "workspace-host-reserve-bytes"));
        var environment = root.path("environment");
        fields(environment, Set.of("install-seconds", "check-seconds", "reboot-seconds", "poll-seconds"));
        var view = root.path("view");
        fields(view, Set.of("refresh-ms", "page-size"));
        var paths = root.path("paths");
        fields(paths, Set.of("helper-directory", "applications-root", "state-root", "configuration-root", "data-root"));
        try {
            return new DeploymentConfig(
                new DeploymentConfig.Source(number(source, "bytes"), integer(source, "entries"), number(source, "archive-bytes")),
                new DeploymentConfig.Build(integer(build, "timeout-seconds"), integer(build, "processes"),
                    integer(build, "memory-mib"), number(build, "output-bytes"), number(build, "workspace-bytes"),
                    number(build, "workspace-max-bytes"), number(build, "workspace-host-reserve-bytes")),
                new DeploymentConfig.Environment(integer(environment, "install-seconds"), integer(environment, "check-seconds"),
                    integer(environment, "reboot-seconds"), integer(environment, "poll-seconds")),
                new DeploymentConfig.View(integer(view, "refresh-ms"), integer(view, "page-size")),
                new DeploymentConfig.Ai(integer(ai, "deployment-max-output-tokens"), integer(ai, "extended-deployment-max-output-tokens"),
                    integer(ai, "approval-max-output-tokens"),
                    new DeploymentConfig.Requests(integer(requests, "deployment-timeout-seconds"), integer(requests, "approval-timeout-seconds"),
                        integer(requests, "extended-timeout-seconds"), integer(requests, "generation-recovery-attempts")),
                    new DeploymentConfig.Execution(integer(execution, "shared-tool-attempts"), integer(execution, "standalone-tool-attempts"),
                        integer(execution, "local-tool-turns"), integer(execution, "agent-model-responses"), integer(execution, "retained-review-sessions"), integer(execution, "max-observations")),
                    new DeploymentConfig.History(integer(history, "max-batches"), integer(history, "characters"), integer(history, "payload-bytes"),
                        integer(history, "initial-batches"), integer(history, "initial-payload-bytes"))),
                new DeploymentConfig.RemotePaths(text(paths, "helper-directory"),
                    text(paths, "applications-root"), text(paths, "state-root"), text(paths, "configuration-root"),
                    text(paths, "data-root")));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Deployment configuration value is outside its supported range", invalid);
        }
    }

    /** Rejects missing, unknown and null fields without supplying hidden defaults. / 拒绝缺失、未知及 null 字段，不提供隐藏默认值。
     * @param node configuration group / 配置分组
     * @param expected exact required fields / 精确必需字段
     * @throws IOException if the group differs / 分组不符时 */
    private static void fields(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("Deployment configuration group must be a mapping");
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected) || expected.stream().anyMatch(name -> node.path(name).isNull()))
            throw new IOException("Deployment configuration fields differ from the required contract");
    }

    /** Reads an exact integer without YAML/string coercion or overflow. / 不进行 YAML/字符串转换或溢出地读取精确整数。
     * @param group configuration group / 配置分组
     * @param name field name / 字段名
     * @return exact long value / 精确 long 值
     * @throws IOException if the field is not a representable integer / 字段不是可表示整数时 */
    private static long number(JsonNode group, String name) throws IOException {
        var value = group.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new IOException("Deployment configuration field must be an integer: " + name);
        return value.longValue();
    }

    /** Reads a bounded Java int. / 读取有界 Java int。
     * @param group configuration group / 配置分组
     * @param name field name / 字段名
     * @return exact int value / 精确 int 值
     * @throws IOException if the field overflows / 字段溢出时 */
    private static int integer(JsonNode group, String name) throws IOException {
        long value = number(group, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IOException("Deployment configuration integer is out of range: " + name);
        return (int) value;
    }

    /** Reads literal text without YAML number, boolean or collection coercion. / 不转换 YAML 数字、布尔或集合地读取字面文本。
     * @param group configuration group / 配置分组
     * @param name field name / 字段名
     * @return exact string / 精确字符串
     * @throws IOException if the field is not text / 字段不是文本时 */
    private static String text(JsonNode group, String name) throws IOException {
        var value = group.path(name);
        if (!value.isTextual())
            throw new IOException("Deployment configuration field must be a string: " + name);
        return value.textValue();
    }
}
