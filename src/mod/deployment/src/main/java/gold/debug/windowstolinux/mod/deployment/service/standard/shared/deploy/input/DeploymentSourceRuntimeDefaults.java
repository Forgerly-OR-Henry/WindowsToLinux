package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;

/** Reads bounded literal network defaults without executing source. / 不执行源码地读取有界字面网络默认值。 */
final class DeploymentSourceRuntimeDefaults {
    private DeploymentSourceRuntimeDefaults() {
    }

    private static final List<Pattern> PORTS = List.of(
        Pattern.compile("(?:getOrDefault|getenv|environ\\.get|ENV\\.fetch|envInt|env)\\(\\s*[\"']PORT[\"']\\s*,\\s*[\"']?([0-9]{1,5})"),
        Pattern.compile("(?:getenv|GetEnvironmentVariable)\\(\\s*[\"']PORT[\"']\\s*\\)\\s*(?:\\?\\?|\\?:)\\s*[\"']?([0-9]{1,5})"),
        Pattern.compile("process\\.env\\.PORT\\s*(?:\\|\\||\\?\\?)\\s*[\"']?([0-9]{1,5})"),
        Pattern.compile("(?s)port\\s*:=\\s*os\\.Getenv\\(\"PORT\"\\)\\s*if\\s+port\\s*==\\s*\"\"\\s*\\{\\s*port\\s*=\\s*\"([0-9]{1,5})\""));

    static OptionalInt fill(DeploymentProjectKind type, Path root, Map<String, String> values) {
        if (type == DeploymentProjectKind.STATIC_SITE) {
            values.putIfAbsent("port", "8080");
            if (!Files.exists(root.resolve("package.json")) && Files.isRegularFile(root.resolve("index.html")))
                values.putIfAbsent("primary", ".");
            return OptionalInt.empty();
        }
        var ports = new TreeSet<Integer>();
        var httpPorts = new TreeSet<Integer>();
        boolean host = false;
        boolean webOrigin = false;
        try (var paths = Files.walk(root, 8)) {
            var candidates = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    return !Pattern.compile("(^|/)(target|build|dist|node_modules|\\.git|test|tests|vendor|bin|obj)(/|$)")
                        .matcher(relative).find() && relative.matches(".*\\.(java|kt|js|ts|mjs|cjs|py|cs|go|rb|php|properties|yml|yaml)$");
                }).sorted().limit(257).toList();
            if (candidates.size() > 256) return OptionalInt.empty();
            long bytes = 0;
            for (Path path : candidates) {
                long length = Files.size(path);
                if (length > 65536 || (bytes += length) > 2 * 1024 * 1024) continue;
                String source = Files.readString(path);
                if (type == DeploymentProjectKind.NODE_SERVICE && path.getFileName().toString().matches(".*\\.(?:[mc]?js|ts)$"))
                    httpPorts.addAll(nodeHttpPorts(source));
                host |= source.contains("\"HOST\"") || source.contains("'HOST'") || source.contains("process.env.HOST");
                webOrigin |= source.contains("\"WEB_ORIGIN\"") || source.contains("'WEB_ORIGIN'") || source.contains("process.env.WEB_ORIGIN");
                for (var pattern : PORTS) {
                    var match = pattern.matcher(source);
                    while (match.find()) {
                        int port = Integer.parseInt(match.group(1));
                        if (port >= 1 && port <= 65535) ports.add(port);
                    }
                }
                var property = Pattern.compile("(?m)^\\s*server\\.port\\s*[:=]\\s*([0-9]{1,5})\\s*$").matcher(source);
                while (property.find()) ports.add(Integer.parseInt(property.group(1)));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot inspect runtime network defaults", failure);
        }
        if (ports.isEmpty() && httpPorts.size() == 1) ports.add(httpPorts.first());
        if (ports.size() == 1) values.putIfAbsent("port", ports.first().toString());
        if (type == DeploymentProjectKind.SPRING_BOOT && ports.isEmpty()) values.putIfAbsent("port", "8080");
        if (webOrigin) values.put("automatic.webOrigin", "true");
        if (values.containsKey("port")) {
            values.put("healthMode", "TCP");
            var configuration = new java.util.ArrayList<String>();
            configuration.add("PORT=" + values.get("port"));
            if (host) configuration.add("HOST=0.0.0.0");
            if (type == DeploymentProjectKind.SPRING_BOOT) {
                configuration.add("SERVER_PORT=" + values.get("port"));
                configuration.add("SERVER_ADDRESS=0.0.0.0");
            }
            values.put("configuration", String.join("\n", configuration));
        }
        return ports.size() == 1 && httpPorts.size() == 1 && ports.first().equals(httpPorts.first())
            ? OptionalInt.of(httpPorts.first()) : OptionalInt.empty();
    }

    /** Adds protocol evidence only after explicit source declarations have had priority. Health remains independent. */
    static void httpEndpoint(OptionalInt sourcePort, Map<String, String> values) {
        if (sourcePort.isEmpty() || values.containsKey("application.mode") || values.containsKey("application.endpoints")
            || !Integer.toString(sourcePort.orElseThrow()).equals(values.get("port"))) return;
        String exposure = values.getOrDefault("exposure", "INTERNAL");
        values.put("application.endpoints", "service");
        values.put("application.endpoint.service.protocol", "HTTP");
        values.put("application.endpoint.service.bind", values.getOrDefault("bindAddress",
            exposure.equals("EXTERNAL") ? "0.0.0.0" : "127.0.0.1"));
        values.put("application.endpoint.service.port", values.get("port"));
        values.put("application.endpoint.service.targetPort", values.get("port"));
        values.put("application.endpoint.service.exposure", exposure);
    }

    private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final String HTTP_MODULE = "[\"'](?:node:)?http[\"']";
    private static final String LISTEN_PORT = "(?:Number\\s*\\(\\s*)?(?:process\\s*\\.\\s*env\\s*\\.\\s*PORT\\s*(?:\\|\\||\\?\\?)\\s*)?"
        + "[\"']?([0-9]{1,5})[\"']?\\s*\\)?\\s*(?=,|\\))";

    /** Recognizes a directly imported HTTP factory, one immutable server and its literal/default PORT listener. */
    private static Set<Integer> nodeHttpPorts(String source) {
        String code = codeOnly(source);
        var factories = new java.util.ArrayList<String>();
        var namespaces = Pattern.compile("(?m)^[ \\t]*import\\s+(?:\\*\\s+as\\s+)?(" + IDENTIFIER + ")\\s+from\\s+" + HTTP_MODULE).matcher(source);
        while (namespaces.find())
            if (code.substring(namespaces.start(), namespaces.end()).stripLeading().startsWith("import"))
                factories.add(Pattern.quote(namespaces.group(1)) + "\\s*\\.\\s*createServer");
        var named = Pattern.compile("(?m)^[ \\t]*import\\s*\\{([^}\\n]+)}\\s*from\\s*" + HTTP_MODULE).matcher(source);
        while (named.find()) {
            if (!code.substring(named.start(), named.end()).stripLeading().startsWith("import")) continue;
            for (String entry : named.group(1).split(",")) {
                var imported = Pattern.compile("\\s*createServer(?:\\s+as\\s+(" + IDENTIFIER + "))?\\s*").matcher(entry);
                if (imported.matches()) factories.add(Pattern.quote(imported.group(1) == null ? "createServer" : imported.group(1)));
            }
        }
        var required = Pattern.compile("(?m)^[ \\t]*const\\s+(" + IDENTIFIER + ")\\s*=\\s*require\\s*\\(\\s*" + HTTP_MODULE + "\\s*\\)").matcher(source);
        while (required.find())
            if (code.substring(required.start(), required.end()).stripLeading().startsWith("const"))
                factories.add(Pattern.quote(required.group(1)) + "\\s*\\.\\s*createServer");
        var result = new TreeSet<Integer>();
        for (String factory : factories) {
            var servers = Pattern.compile("(?m)^[ \\t]*const\\s+(" + IDENTIFIER + ")\\s*=\\s*" + factory + "\\s*\\(").matcher(code);
            while (servers.find()) {
                String server = servers.group(1);
                var listeners = Pattern.compile("(?<![A-Za-z0-9_$.])" + Pattern.quote(server) + "\\s*\\.\\s*listen\\s*\\(\\s*" + LISTEN_PORT).matcher(source);
                while (listeners.find())
                    if (code.regionMatches(listeners.start(), server, 0, server.length())) {
                        int port = Integer.parseInt(listeners.group(1));
                        if (port >= 1 && port <= 65535) result.add(port);
                    }
            }
        }
        return result;
    }

    /** Retains positions while masking comments and quoted/template text in positive syntax evidence. */
    private static String codeOnly(String source) {
        char[] code = source.toCharArray();
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                char quote = current;
                code[index] = ' ';
                for (index++; index < source.length(); index++) {
                    current = source.charAt(index);
                    if (current != '\n' && current != '\r') code[index] = ' ';
                    if (current == '\\') {
                        if (++index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') code[index] = ' ';
                    } else if (current == quote) break;
                }
            } else if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') code[index++] = ' ';
                index--;
            } else if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                code[index++] = ' '; code[index] = ' ';
                for (index++; index < source.length(); index++) {
                    if (source.charAt(index) == '*' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                        code[index++] = ' '; code[index] = ' '; break;
                    }
                    if (source.charAt(index) != '\n' && source.charAt(index) != '\r') code[index] = ' ';
                }
            }
        }
        return new String(code);
    }
}
