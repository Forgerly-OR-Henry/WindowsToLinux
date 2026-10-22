package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

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

    private static final List<String> PROFILES = List.of("PYTHON", "PHP", "RUBY", "NATIVE_BUILD", "HELPER_RUNTIME");

    private static final String PACKAGES_TEXT = resource("dependency-packages.json");

    private static final JsonNode PACKAGES = packages();

    /** Reads the original bytes once and gives the fixed controller its own source identity. / 仅读取一次原字节，并向固定控制器提供其自身来源身份。 */
    public static final String BOOTSTRAP = "import sys;sys.dont_write_bytecode=True;code=sys.stdin.buffer.read();exec(compile(code,'<wtl-agent-toolchain>','exec'),{'__name__':'__main__','PROGRAM_SOURCE':code})";

    private static final String COMMAND_PREFIX = "/usr/bin/python3 -I -S -c " + quote(BOOTSTRAP) + " ";

    public static final String BOOTSTRAP_TRACKED = "import sys;sys.dont_write_bytecode=True;n=sys.argv[1];assert n.isdecimal() and str(int(n))==n and 1<=int(n)<=1048576;raw=sys.stdin.buffer.read(int(n)+131073);code=raw[:int(n)];recipe=raw[int(n):];assert len(code)==int(n) and 1<=len(recipe)<=131072;sys.argv=['<wtl-agent-toolchain>',*sys.argv[2:]];exec(compile(code,'<wtl-agent-toolchain>','exec'),{'__name__':'__main__','PROGRAM_SOURCE':code,'AGENT_TRACKED_REQUEST_RAW':recipe})";

    private static final String TRACKED_COMMAND_PREFIX = "/usr/bin/python3 -I -S -c " + quote(BOOTSTRAP_TRACKED) + " ";

    private DeploymentAgentToolchainPrograms() {
    }

    /** Includes every current executable controller byte. / 包含当前控制器的全部可执行字节。 */
    public static String controller() {
        return controller(DeploymentRemoteLayout.original());
    }

    public static String controller(DeploymentRemoteLayout layout) {
        String controller = resource("agent-toolchain-controller.py");
        String marker = "# @WTL_AGENT_TRACKED_OPERATIONS@";
        if (controller.indexOf(marker) < 0 || controller.indexOf(marker) != controller.lastIndexOf(marker))
            throw new IllegalStateException("Tracked controller insertion boundary changed");
        controller = controller.replace(marker, resource("agent-toolchain-tracked.py"));
        return DeploymentToolchainSources.metadata(layout) + resource("agent-toolchain-resolution.py") + "\n"
            + "EXPECTED_RESOLVER_SOURCE_SHA256 = '" + sha256(resolver(layout)) + "'\n"
            + DeploymentToolchainSources.archives() + "\n"
            + gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate.render(
            controller.replace("/etc/opt/windowstolinux/apps", "@wtl:configuration-root@")
                .replace("/var/opt/windowstolinux/apps", "@wtl:data-root@")
                .replace("/opt/windowstolinux/apps", "@wtl:applications-root@"), layout);
    }

    /** Same fixed controller definitions with a closed precreation guard; old installed source/version stays unchanged. */
    public static String existingController(DeploymentRemoteLayout layout) {
        String source = controller(layout);
        String marker = "    selections = [agent_selection_snapshot(record)] if record is not None else []\n";
        if (source.indexOf(marker) < 0 || source.indexOf(marker) != source.lastIndexOf(marker))
            throw new IllegalStateException("Existing-toolchain insertion boundary changed");
        return source.replace(marker, marker + "    if not selections:\n        agent_reject('EXISTING_INSTALLATION_REQUIRED')\n");
    }

    /** Resolves official metadata without entering the installation controller. / 仅解析官方元数据，不进入安装控制器。 */
    public static String resolver() {
        return resolver(DeploymentRemoteLayout.original());
    }

    public static String resolver(DeploymentRemoteLayout layout) {
        return DeploymentToolchainSources.metadata(layout) + resource("agent-toolchain-resolution.py")
            + "\nif __name__ == '__main__':\n    resolution_cli()\n";
    }

    static String sha256(String source) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Includes the exact dependency data and fixed authenticated package-manager implementation. / 包含精确依赖数据及固定认证包管理器实现。 */
    public static String dependencies() {
        return dependencies(DeploymentRemoteLayout.original());
    }

    public static String dependencies(DeploymentRemoteLayout layout) {
        Objects.requireNonNull(layout);
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

    public static String trackedCommand(DeploymentAgentToolchainTracked.Recipe recipe) {
        if (!recipe.invocationKind().equals("EXISTING_INLINE") || !recipe.phase().equals("PREPARE"))
            throw new IllegalArgumentException("Only existing inline preparation is admitted");
        int bytes = existingController(recipe.layout()).getBytes(StandardCharsets.UTF_8).length;
        return TRACKED_COMMAND_PREFIX + quote(Integer.toString(bytes)) + " " + recipe.frame("prepare-tracked").stream()
            .map(DeploymentAgentToolchainPrograms::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    public static byte[] trackedInput(DeploymentAgentToolchainTracked.Recipe recipe) {
        byte[] source = existingController(recipe.layout()).getBytes(StandardCharsets.UTF_8), request = recipe.bytes();
        byte[] result = java.util.Arrays.copyOf(source, source.length + request.length);
        System.arraycopy(request, 0, result, source.length, request.length); return result;
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
