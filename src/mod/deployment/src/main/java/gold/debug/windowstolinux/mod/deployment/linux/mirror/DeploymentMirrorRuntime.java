package gold.debug.windowstolinux.mod.deployment.linux.mirror;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;

/** Renders task-owned download policy and protocol adapters before executable review. */
public final class DeploymentMirrorRuntime {
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/linux/mirror/";
    private static final String PUBLICATION_RECOVERY_MIRROR_SHA256 =
        "0ef501b4eea1fff29bef1da73b089816d135901bb73cef8997bee3c592a74089";

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
        return sha256(runtimeSource());
    }

    /** Complete runtime executed by Cargo's separate, data-only prefetch phase. */
    public static String cargoRuntimeSource() {
        return resource("10-transfer.py") + "\nimport ssl\n" + resource("locked/20-credentials.py");
    }

    public static String cargoRuntimeSha256() {
        return sha256(cargoRuntimeSource());
    }

    /** Toolchain URL roots and redirect trust come from the Mod catalog, including older tasks. */
    public static String renderToolchainSources(String source) {
        return renderToolchainSources(source, false);
    }

    /** Reproduces the original recovery program, which verifies bindings without preparing toolchains. */
    public static String renderPublicationRecoveryToolchainSources(String source) {
        return renderToolchainSources(source, true);
    }

    private static String renderToolchainSources(String source, boolean publicationRecovery) {
        var catalog = MirrorCatalog.load();
        if (!source.contains("__WTL_MIRROR_DEFAULT_SOURCE_ORIGINS__")
            && !source.contains("__WTL_MIRROR_DEFAULT_ALLOWED_HOSTS__")
            && !source.contains("__WTL_MIRROR_RUNTIME_SHA256__"))
            return source;
        var origins = new java.util.TreeMap<String, java.util.List<String>>();
        catalog.points().forEach((point, configuration) -> {
            if (!publicationRecovery || point != DownloadPoint.GNU_MAKE_SOURCE)
                origins.put(point.name(), configuration.sourceOrigins());
        });
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
                .replace("__WTL_MIRROR_RUNTIME_SHA256__",
                    publicationRecovery ? PUBLICATION_RECOVERY_MIRROR_SHA256 : runtimeSha256());
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
        return bootstrap(policyJson).prefix();
    }

    /** Keeps the exact source used both for the staged file and in the command itself. */
    public record Bootstrap(String prefix, String shell) { }

    public static Bootstrap bootstrap(String policyJson) {
        if (policyJson == null || policyJson.isBlank()) return new Bootstrap("", "");
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
        String prefix = "# WTL_MIRROR_TASK=" + task + "\n" + """
            # Root validates the fixed host temporary parent before creating any task files.
            # The restricted build keeps the temporary directory supplied by its quota volume.
            wtl_mirror_trusted_parent() {
              local directory="$1" allow_sticky="$2" mode
              [ ! -L "$directory" ] && [ -d "$directory" ] && [ "$(/usr/bin/stat -c '%%u' -- "$directory")" = 0 ] || return 65
              mode="$(/usr/bin/stat -c '%%a' -- "$directory")" || return 65
              [[ "$mode" =~ ^[0-7]{1,4}$ ]] || return 65
              (( (8#$mode & 022) == 0 || (allow_sticky == 1 && (8#$mode & 01000) != 0) ))
            }
            unset WTL_MIRROR_SYSTEM_PYTHON
            if [ "$EUID" = 0 ]; then
              wtl_mirror_trusted_parent / 0 && wtl_mirror_trusted_parent /tmp 1 || {
                printf 'MIRROR_REJECT=privileged-temporary-parent\\n' >&2; exit 65;
              }
              wtl_mirror_base=/tmp
            else
              wtl_mirror_base="${TMPDIR:-/tmp}"
            fi
            wtl_mirror_root="$(/usr/bin/mktemp -d -- "$wtl_mirror_base/wtl-mirrors.XXXXXXXX")" || exit 65
            trap '/usr/bin/rm -rf -- "$wtl_mirror_root"' EXIT
            /usr/bin/chmod 700 "$wtl_mirror_root" || exit 65
            if [ "$EUID" = 0 ]; then
              export TMPDIR="$wtl_mirror_root" TMP="$wtl_mirror_root" TEMP="$wtl_mirror_root"
            fi
            if [ -n "${WTL_MIRROR_BUDGET_SECONDS:-}" ] && [ -z "${WTL_MIRROR_STARTED_AT:-}" ]; then export WTL_MIRROR_STARTED_AT="$(/usr/bin/date +%%s)"; fi
            export WTL_MIRROR_POLICY="$wtl_mirror_root/policy.json"
            export WTL_MIRROR_RUNTIME="$wtl_mirror_root/runtime.py"
            export WTL_MIRROR_SHELL="$wtl_mirror_root/bootstrap.sh"
            wtl_mirror_runtime_sha256=%s
            printf '%%s' %s >"$WTL_MIRROR_POLICY" || exit 65
            {
              printf '%%s' %s >"$WTL_MIRROR_RUNTIME"
            } || exit 65
            printf '%%s' %s >"$WTL_MIRROR_SHELL" || exit 65
            /usr/bin/chmod 400 "$WTL_MIRROR_POLICY" "$WTL_MIRROR_RUNTIME" "$WTL_MIRROR_SHELL" || exit 65
            wtl_download() { wtl_run_mirror_runtime /usr/bin/python3 download "$@"; }
            wtl_dependency() {
              if declare -F verify_build_command >/dev/null && ! verify_build_command "$2"; then report_build_tool_failure "$2"; return 65; fi
              if [ -n "${WTL_MIRROR_POLICY:-}" ]; then wtl_run_mirror_runtime /usr/bin/python3 dependency "$@";
              else shift; "$@"; fi
            }
            wtl_oci() { wtl_run_mirror_runtime /usr/bin/python3 oci "$@"; }
            wtl_wrapper() { wtl_run_mirror_runtime /usr/bin/python3 wrapper "$@"; }
            wtl_source_url() { wtl_run_mirror_runtime /usr/bin/python3 source-url "$@"; }
            """.formatted(runtimeSha256(), quote(policyJson), quote(runtimeSource()), quote(shell)) + credentialSetup + shell;
        return new Bootstrap(prefix, shell);
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
