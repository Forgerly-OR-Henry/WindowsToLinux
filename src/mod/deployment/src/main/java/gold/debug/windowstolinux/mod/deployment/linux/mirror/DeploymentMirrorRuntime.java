package gold.debug.windowstolinux.mod.deployment.linux.mirror;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;

/** Renders task-owned download policy and protocol adapters before executable review. */
public final class DeploymentMirrorRuntime {
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/linux/mirror/";

    private DeploymentMirrorRuntime() { }

    public static String runtimeSource() {
        String bootstrap;
        try {
            bootstrap = new ObjectMapper().writeValueAsString(resource("40-bootstrap.sh") + "\n" + resource("41-apt.sh"));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return resource("10-transfer.py") + "\n" + resource("20-dependencies.py") + "\n"
            + resource("locked/10-locked-transport.py") + "\n"
            + resource("locked/20-credentials.py") + "\n"
            + resource("25-wrapper.py") + "\nBOOTSTRAP_SHELL = " + bootstrap + "\n"
            + resource("35-system.py") + "\n" + resource("36-dnf.py") + "\n" + resource("30-oci.py");
    }

    public static String runtimeSha256() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(runtimeSource().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Toolchain URL roots and redirect trust come from the Mod catalog, including older tasks. */
    public static String renderToolchainSources(String source) {
        var catalog = MirrorCatalog.load();
        var origins = new java.util.TreeMap<String, java.util.List<String>>();
        catalog.points().forEach((point, configuration) -> origins.put(point.name(), configuration.sourceOrigins()));
        var hosts = new java.util.TreeSet<String>();
        var toolchains = java.util.Set.of("JAVA_JDK", "JAVA_METADATA", "NODE_DISTRIBUTION", "NODE_METADATA",
            "GO_DISTRIBUTION", "GO_METADATA", "DOTNET_SDK", "DOTNET_METADATA", "KOTLIN_DISTRIBUTION",
            "KOTLIN_METADATA", "RUST_TOOLCHAIN", "RUST_METADATA", "PYTHON_DISTRIBUTION", "PYTHON_METADATA",
            "PHP_DISTRIBUTION", "PHP_METADATA", "RUBY_DISTRIBUTION", "RUBY_METADATA", "RUBYGEMS");
        catalog.providers().stream().filter(provider -> provider.official()).forEach(provider ->
            provider.sources().forEach((point, endpoint) -> {
                if (toolchains.contains(point.name())) hosts.addAll(endpoint.allowedHosts());
            }));
        try {
            var json = new ObjectMapper();
            return source.replace("__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__", json.writeValueAsString(origins))
                .replace("__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__", json.writeValueAsString(hosts))
                .replace("__WTL_MIRROR_RUNTIME_SHA256__", runtimeSha256());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A missing snapshot is an older task and keeps its existing execution semantics. */
    public static String wrapBuild(String script, String policyJson) {
        return wrap(script, policyJson);
    }

    public static String wrapBuild(String script, String policyJson, long timeoutSeconds) {
        if (policyJson == null || policyJson.isBlank()) return script;
        if (timeoutSeconds < 1) throw new IllegalArgumentException("Build mirror budget must be positive");
        return wrap("export WTL_MIRROR_BUDGET_SECONDS=" + timeoutSeconds
            + " WTL_MIRROR_STARTED_AT=\"$(/usr/bin/date +%s)\"\n" + script, policyJson);
    }

    public static String wrapPreparation(String script, String policyJson) {
        return wrap(script, policyJson);
    }

    private static String wrap(String script, String policyJson) {
        Objects.requireNonNull(script, "script");
        if (policyJson == null || policyJson.isBlank()) return script;
        return bootstrapPrefix(policyJson) + "trap '/usr/bin/rm -rf -- \"$wtl_mirror_root\"' EXIT\n(\n"
            + script + "\n)\n";
    }

    /** Pure shell setup remains usable before the first Python/system dependency bootstrap. */
    public static String bootstrapPrefix(String policyJson) {
        if (policyJson == null || policyJson.isBlank()) return "";
        JsonNode policy;
        try {
            policy = new ObjectMapper().readTree(policyJson);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid frozen mirror policy", failure);
        }
        if (policy.path("formatVersion").asInt() != 1 || !policy.path("points").isObject())
            throw new IllegalArgumentException("Unsupported frozen mirror policy");
        String task = policy.path("task").asText();
        if (!task.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Frozen mirror policy task must be a UUID");
        StringBuilder providers = new StringBuilder("wtl_source_providers() {\n  case \"$1\" in\n");
        StringBuilder routes = new StringBuilder("wtl_source_routes() {\n  case \"$1:$2\" in\n");
        StringBuilder repositories = new StringBuilder("wtl_source_repo_templates() {\n  case \"$1:$2\" in\n");
        StringBuilder credentials = new StringBuilder("wtl_source_credential() {\n  wtl_credential_file= wtl_credential_scope= wtl_credential_ref=\n  case \"$1:$2\" in\n");
        StringBuilder nativeDnf = new StringBuilder("wtl_dnf_native_allowed() { case \"$1\" in\n");
        policy.path("points").fields().forEachRemaining(point -> {
            if (!point.getKey().matches("[A-Z][A-Z0-9_]+") || !point.getValue().isArray()
                || point.getValue().size() > 256)
                throw new IllegalArgumentException("Invalid frozen mirror point");
            providers.append("    ").append(point.getKey()).append(") providers=(");
            for (JsonNode endpoint : point.getValue()) {
                String provider = endpoint.path("providerId").asText();
                if (!provider.matches("[A-Za-z0-9_.-]{1,96}")) throw new IllegalArgumentException("Invalid source id");
                providers.append(quote(provider)).append(' ');
                routes.append("    ").append(point.getKey()).append(':').append(provider).append(") origins=(");
                for (JsonNode route : endpoint.path("routes"))
                    if (route.path("pattern").asText().isEmpty()) routes.append(quote(route.path("origin").asText())).append(' ');
                routes.append("); prefixes=(");
                for (JsonNode route : endpoint.path("routes"))
                    if (route.path("pattern").asText().isEmpty()) routes.append(quote(route.path("prefix").asText())).append(' ');
                routes.append(");;\n");
                repositories.append("    ").append(point.getKey()).append(':').append(provider).append(") repo_templates=(");
                endpoint.path("repoTemplates").fields().forEachRemaining(entry -> repositories.append('[')
                    .append(quote(entry.getKey())).append("]=").append(quote(entry.getValue().asText())).append(' '));
                repositories.append(");;\n");
                credentials.append("    ").append(point.getKey()).append(':').append(provider).append(") ");
                String reference = endpoint.path("secretRef").asText("");
                if (!reference.isBlank()) {
                    credentials.append("wtl_credential_ref=").append(quote(reference))
                        .append("; wtl_credential_scope=").append(quote(endpoint.path("baseUrl").asText()))
                        .append("; wtl_credential_file=\"${WTL_MIRROR_CREDENTIALS_DIR:?}/")
                        .append(sha256(reference)).append("\";");
                } else credentials.append(":;");
                credentials.append(";\n");
            }
            providers.append(");;\n");
            if (point.getKey().startsWith("DNF_") && point.getValue().size() == 1) {
                JsonNode endpoint = point.getValue().get(0);
                if (endpoint.path("official").asBoolean() && !endpoint.path("custom").asBoolean()
                    && !endpoint.path("explicit").asBoolean() && endpoint.path("secretRef").asText("").isBlank())
                    nativeDnf.append(point.getKey()).append(") return 0;;\n");
            }
        });
        providers.append("    *) return 65;;\n  esac\n}\n");
        routes.append("    *) return 65;;\n  esac\n}\n");
        repositories.append("    *) return 65;;\n  esac\n}\n");
        credentials.append("    *) return 65;;\n  esac\n}\n");
        nativeDnf.append("*) return 65;; esac; }\n");
        String shell = providers.toString() + routes + repositories + credentials + nativeDnf + resource("40-bootstrap.sh")
            + "\n" + resource("41-apt.sh") + "\n";
        boolean hasSecrets = policy.path("points").findValues("secretRef").stream().anyMatch(value -> !value.asText().isBlank());
        String policySha = sha256(policyJson);
        JsonNode timeouts = policy.path("timeouts");
        int connect = Math.max(1, Math.min(16_777_216, timeouts.path("connectSeconds").asInt(10)));
        int idle = Math.max(1, Math.min(16_777_216, Math.min(timeouts.path("noProgressSeconds").asInt(30), timeouts.path("readSeconds").asInt(60))));
        int lowWindow = Math.max(1, Math.min(16_777_216, timeouts.path("lowSpeedWindowSeconds").asInt(60)));
        int lowRate = Math.max(1, Math.min(16_777_216, timeouts.path("lowSpeedBytesPerSecond").asInt(65536)));
        int attempts = Math.max(1, Math.min(2, timeouts.path("attemptsPerEndpoint").asInt(2)));
        String credentialSetup = "export WTL_MIRROR_POLICY_SHA256=" + policySha + "\n"
            + "wtl_mirror_connect_seconds=" + connect + "\nwtl_mirror_idle_seconds=" + idle
            + "\nwtl_mirror_low_window_seconds=" + lowWindow + "\nwtl_mirror_low_rate=" + lowRate
            + "\nwtl_mirror_attempts=" + attempts + "\n";
        if (hasSecrets) {
            String suffix = "/" + task + "/" + sha256(policy.path("server").asText()) + "/" + policySha;
            credentialSetup += "case \"${WTL_MIRROR_CREDENTIALS_SOURCE:-}\" in *" + quote(suffix)
                + ") ;; *) printf 'MIRROR_REJECT=credential-source-binding\\n' >&2; exit 65;; esac\n"
                + "if [ \"$(/usr/bin/id -u)\" = 0 ]; then export WTL_MIRROR_CREDENTIALS_DIR=\"$WTL_MIRROR_CREDENTIALS_SOURCE\"; else "
                + "export WTL_MIRROR_CREDENTIALS_DIR=\"${TMPDIR:-/tmp}/wtl-mirror-material" + suffix + "\"; fi\n"
                + "[ -d \"$WTL_MIRROR_CREDENTIALS_DIR\" ] && [ ! -L \"$WTL_MIRROR_CREDENTIALS_DIR\" ] || exit 65\n";
        }
        return "# WTL_MIRROR_TASK=" + task + "\n" + """
            # Frozen task policy; all generated files live on the current task temporary volume.
            wtl_mirror_root="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/wtl-mirrors.XXXXXXXX")"
            /usr/bin/chmod 700 "$wtl_mirror_root"
            if [ -n "${WTL_MIRROR_BUDGET_SECONDS:-}" ] && [ -z "${WTL_MIRROR_STARTED_AT:-}" ]; then export WTL_MIRROR_STARTED_AT="$(/usr/bin/date +%%s)"; fi
            export WTL_MIRROR_POLICY="$wtl_mirror_root/policy.json"
            export WTL_MIRROR_RUNTIME="$wtl_mirror_root/runtime.py"
            export WTL_MIRROR_SHELL="$wtl_mirror_root/bootstrap.sh"
            printf '%%s' %s >"$WTL_MIRROR_POLICY"
            printf '%%s' %s >"$WTL_MIRROR_RUNTIME"
            printf '%%s' %s >"$WTL_MIRROR_SHELL"
            /usr/bin/chmod 400 "$WTL_MIRROR_POLICY" "$WTL_MIRROR_RUNTIME" "$WTL_MIRROR_SHELL"
            wtl_download() { /usr/bin/python3 -I "$WTL_MIRROR_RUNTIME" download "$@"; }
            wtl_dependency() {
              if declare -F verify_build_command >/dev/null && ! verify_build_command "$2"; then report_build_tool_failure "$2"; return 65; fi
              if [ -n "${WTL_MIRROR_POLICY:-}" ]; then /usr/bin/python3 -I "$WTL_MIRROR_RUNTIME" dependency "$@";
              else shift; "$@"; fi
            }
            wtl_oci() { /usr/bin/python3 -I "$WTL_MIRROR_RUNTIME" oci "$@"; }
            wtl_wrapper() { /usr/bin/python3 -I "$WTL_MIRROR_RUNTIME" wrapper "$@"; }
            wtl_source_url() { /usr/bin/python3 -I "$WTL_MIRROR_RUNTIME" source-url "$@"; }
            """.formatted(quote(policyJson), quote(runtimeSource()), quote(shell)) + credentialSetup + shell;
    }

    /** Writers call this to retain old-task behavior while routing new-task dependency requests. */
    public static String dependency(String point, String command) {
        return "if [ -n \"${WTL_MIRROR_POLICY:-}\" ]; then wtl_dependency " + point + " " + command
            + "; else run " + command + "; fi";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String resource(String name) {
        try (var stream = DeploymentMirrorRuntime.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) throw new IllegalStateException("Mirror runtime resource unavailable: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Mirror runtime resource unreadable: " + name, failure);
        }
    }
}
