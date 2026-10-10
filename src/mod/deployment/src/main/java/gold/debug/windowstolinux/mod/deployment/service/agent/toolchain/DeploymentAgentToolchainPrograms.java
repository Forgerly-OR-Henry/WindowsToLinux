package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentToolchainSources;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;

/** Renders complete fixed Agent programs from shared pure sources and an independent execution boundary. / 从共享纯来源及独立执行边界渲染完整 Agent 固定程序。 */
public final class DeploymentAgentToolchainPrograms {
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/service/agent/toolchain/";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> PROFILES = List.of("PYTHON", "PHP", "RUBY", "NATIVE_BUILD");

    private static final String PACKAGES_TEXT = resource("dependency-packages.json");

    private static final JsonNode PACKAGES = packages();

    /** Reads the original bytes once and gives the fixed controller its own source identity. / 仅读取一次原字节，并向固定控制器提供其自身来源身份。 */
    public static final String BOOTSTRAP = "import sys;code=sys.stdin.buffer.read();exec(compile(code,'<wtl-agent-toolchain>','exec'),{'__name__':'__main__','PROGRAM_SOURCE':code})";

    private static final String COMMAND_PREFIX = "/usr/bin/python3 -I -S -c " + quote(BOOTSTRAP) + " ";

    private DeploymentAgentToolchainPrograms() {
    }

    /** Includes every current executable controller byte. / 包含当前控制器的全部可执行字节。 */
    public static String controller() {
        return DeploymentToolchainSources.metadata() + DeploymentToolchainSources.archives() + "\n"
            + resource("agent-toolchain-controller.py");
    }

    /** Includes the exact dependency data and fixed authenticated package-manager implementation. / 包含精确依赖数据及固定认证包管理器实现。 */
    public static String dependencies() {
        try {
            return "import json\nDEPENDENCY_PACKAGES = json.loads(" + JSON.writeValueAsString(PACKAGES_TEXT)
                + ")\n" + resource("agent-toolchain-dependencies.py");
        } catch (IOException failure) {
            throw new IllegalStateException("Dependency source cannot be rendered", failure);
        }
    }

    /** Returns exact potential packages, without accepting model-controlled package names. / 返回精确潜在包名，不接受模型控制的包名。 */
    public static List<String> packages(String manager, DeploymentToolchainType ecosystem) {
        return packages(manager, ecosystem.name());
    }

    /** Returns only one shipped dependency profile. / 仅返回一个随产品发布的依赖类别。 */
    public static List<String> packages(String manager, String profile) {
        JsonNode names = PACKAGES.path(manager).path(profile);
        if (!names.isArray())
            throw new IllegalArgumentException("Unsupported dependency manager or ecosystem");
        var result = new java.util.ArrayList<String>();
        names.forEach(name -> result.add(name.textValue()));
        return List.copyOf(result);
    }

    /** Uses one canonical launcher for dispatch and evidence validation. / 发送和证据核验使用同一规范启动器。 */
    public static String command(List<String> arguments) {
        return COMMAND_PREFIX + List.copyOf(arguments).stream().map(DeploymentAgentToolchainPrograms::quote)
            .collect(java.util.stream.Collectors.joining(" "));
    }

    static List<String> arguments(String command) {
        if (command == null || !command.startsWith(COMMAND_PREFIX))
            throw new IllegalArgumentException("Agent program launcher changed");
        String tail = command.substring(COMMAND_PREFIX.length());
        var matcher = java.util.regex.Pattern.compile("'([^']*)'(?: |$)").matcher(tail);
        var result = new java.util.ArrayList<String>();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor)
                throw new IllegalArgumentException("Noncanonical Agent program arguments");
            result.add(matcher.group(1));
            cursor = matcher.end();
        }
        if (result.isEmpty() || cursor != tail.length() || !command(result).equals(command))
            throw new IllegalArgumentException("Agent program command frame changed");
        return List.copyOf(result);
    }

    private static String quote(String value) {
        return "'" + Objects.requireNonNull(value).replace("'", "'\\''") + "'";
    }

    private static JsonNode packages() {
        try {
            JsonNode result = JSON.readTree(PACKAGES_TEXT);
            if (result.size() != 2 || !result.has("APT") || !result.has("DNF"))
                throw new IllegalStateException("Unexpected dependency managers");
            for (String manager : List.of("APT", "DNF")) {
                if (result.path(manager).size() != PROFILES.size())
                    throw new IllegalStateException("Unexpected dependency ecosystems");
                for (String ecosystem : PROFILES) {
                    var names = result.path(manager).path(ecosystem);
                    var unique = new java.util.HashSet<String>();
                    if (!names.isArray() || names.isEmpty() || names.size() > 24)
                        throw new IllegalStateException("Invalid dependency package collection");
                    for (var name : names)
                        if (!name.isTextual() || !name.textValue().matches("[a-z][a-z0-9+.-]{0,63}")
                            || !unique.add(name.textValue()))
                            throw new IllegalStateException("Invalid dependency package name");
                }
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Dependency manifest unreadable", failure);
        }
    }

    private static String resource(String name) {
        try (var input = DeploymentAgentToolchainPrograms.class.getResourceAsStream(ROOT + name)) {
            if (input == null)
                throw new IllegalStateException("Agent toolchain program unavailable: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Agent toolchain program unreadable: " + name, failure);
        }
    }
}
