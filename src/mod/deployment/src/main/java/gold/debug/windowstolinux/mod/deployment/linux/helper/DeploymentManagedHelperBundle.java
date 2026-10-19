package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;

/**
 * Assembles the root-owned managed helper from fixed responsibility fragments and rejects protocol drift. / 从固定职责片段拼装 root 持有的受管 helper 并拒绝协议漂移。
 */
public final class DeploymentManagedHelperBundle {
    /**
     * Protocol version printed by this exact helper bundle. / 此精确 helper 包输出的协议版本。
     */
    public static final int PROTOCOL_VERSION = 16;

    /**
     * Platform-owned helper installation directory. / 平台持有的 helper 安装目录。
     */
    public static final String DIRECTORY = "/usr/local/lib/windowstolinux";

    /**
     * Fixed root management entrypoint. / 固定的 root 管理入口。
     */
    public static final String PATH = DIRECTORY + "/managed-helper";

    /** Installed non-privileged build entry path. / 安装后的非特权构建入口路径。 */
    public static final String BUILD_ENTRY_PATH = DIRECTORY + "/build-entry";

    /**
     * Expected byte-for-byte helper bundle identity. / 预期的 helper 逐字节身份。
     */
    public static final String EXPECTED_SHA256 = "9a8faa704f43955e7f13ee69cf2f8ad665b744d856790b0ef92a13e838b90aba";

    /**
     * ROOT.
     * <p>根目录。
     */
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/linux/helper/";

    /**
     * RESOURCE INSERTS.
     * <p>资源INSERTS。
     */
    private static final Map<String, String> RESOURCE_INSERTS = Map.of("# @compat:agent-source@\n",
        "workspace/source-operations.py", "# @compat:apparmor@\n",
        "workspace/apparmor-namespace.sh", "# @compat:systemd-isolation@\n",
        "runtime/systemd/systemd-manager-isolation.sh", "# @compat:selinux-entry@\n",
        "runtime/systemd/selinux-command-entry.sh", "# @compat:centos-repositories@\n",
        "toolchain/centos-source-repositories.py", "# @compat:toolchain-archives@\n", "toolchain/archive-boundaries.py");

    /**
     * Ordered packaged helper fragments.
     * <p>有序打包 helper 片段。
     */
    private static final List<String> FRAGMENTS = List.of(
        "00-protocol-foundation.sh",
        "release/10-typed-release.sh",
        "release/12-container-image-input.sh",
        "input/15-deployment-input.sh",
        "input/16-application-input.sh",
        "input/17-managed-content.sh",
        "release/18-container-storage.sh",
        "workspace/20-candidate-workspace.sh",
        "workspace/21-workspace-volume.sh",
        "workspace/22-restricted-build.sh",
        "workspace/23-container-builder.sh",
        "workspace/25-workspace-recovery.sh",
        "workspace/26-build-output.sh",
        "workspace/27-agent-workspace.sh",
        "ecosystem/35-ecosystem-dispatch.sh",
        "toolchain/10-release-metadata.py", "toolchain/20-installation-boundaries.py",
        "toolchain/30-managed-installation.py", "toolchain/40-binding-protocol.py",
        "runtime/40-typed-runtime.sh",
        "runtime/41-application-admission.sh",
        "runtime/41-application-runtime.sh",
        "runtime/42-application-job.sh",
        "runtime/43-application-container.sh",
        "runtime/44-application-health.sh",
        "runtime/45-application-container-contract.sh",
        "release/50-container-release.sh",
        "release/52-container-recovery.sh",
        "release/53-publication-observation.sh",
        "release/55-publication-snapshot.sh",
        "restore/54-restore-candidate.sh",
        "restore/56-restore-recovery.sh",
        "restore/56-restore-commit.sh",
        "restore/57-application-restore.sh",
        "release/58-retained-activation.sh",
        "restore/59-handoff-restore.sh",
        "restore/59-handoff-database-restore.sh",
        "restore/59-handoff-restore-cleanup.sh",
        "runtime/container/55-podman-quadlet.sh", "runtime/systemd/60-lifecycle.sh",
        "runtime/systemd/61-service-identity.sh",
        "database/64-database-client.sh",
        "database/63-database-inspection.sh",
        "database/65-database-backup.sh",
        "database/66-database-activation.sh",
        "backup/67-managed-backup.sh",
        "database/68-handoff-database.sh",
        "backup/69-handoff-backup-status.sh",
        "database/10-native-instances.sh",
        "database/20-native-targets.sh",
        "database/21-native-operations.sh",
        "70-command-dispatch.sh");

    /**
     * Installed non-privileged build entrypoint. / 安装后的非特权构建入口。
     *
     * @return render build entry text / 渲染构建条目文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static String renderBuildEntry() {
        return renderBuildEntry(DeploymentRemoteLayout.original());
    }

    /** Renders the entry for an explicitly captured layout. */
    public static String renderBuildEntry(DeploymentRemoteLayout layout) {
        try (InputStream stream = DeploymentManagedHelperBundle.class
            .getResourceAsStream(ROOT + "workspace/24-build-entry.sh")) {
            if (stream == null)
                throw new IllegalStateException("Build entry resource unavailable");
            return DeploymentRemoteLayoutTemplate.render(
                new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), layout);
        } catch (IOException failure) {
            throw new IllegalStateException("Build entry resource unreadable", failure);
        }
    }

    /** Returns the exact build entry identity embedded in the helper. / 返回嵌入helper的精确构建入口身份。
     * @return SHA256 of the complete entry / 完整入口的SHA256 */
    public static String buildEntrySha256() {
        return buildEntrySha256(DeploymentRemoteLayout.original());
    }

    public static String buildEntrySha256(DeploymentRemoteLayout layout) {
        return sha256(renderBuildEntry(layout).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentManagedHelperBundle() {
    }

    /** Binds each installed-helper invocation to the reviewed bytes before any helper code runs. / 在任何helper代码运行前将每次调用绑定到已审阅字节。
     * @param script complete implementation-owned invocation / 完整实现所属调用
     * @return complete guarded executable command / 完整受保护可执行命令 */
    public static String verifiedCommand(String script) {
        return verifiedCommand(script, DeploymentRemoteLayout.original());
    }

    /** Binds the complete invocation to the original layout and exact rendered helper. */
    public static String verifiedCommand(String script, DeploymentRemoteLayout layout) {
        String path = layout.helperPath();
        String guard = executionGuard(layout);
        if (script == null || script.isBlank() || !script.contains(path) || script.startsWith(guard))
            throw new IllegalArgumentException("Invalid installed helper invocation");
        return guard + script;
    }

    /** Extracts the invocation only after matching the complete current integrity guard. / 仅匹配完整当前完整性守卫后提取调用。
     * @param script complete actual executable command / 完整实际可执行命令
     * @return guarded invocation for typed argument decoding / 用于类型化参数解码的受保护调用 */
    public static String verifiedBody(String script) {
        return verifiedBody(script, DeploymentRemoteLayout.original());
    }

    public static String verifiedBody(String script, DeploymentRemoteLayout layout) {
        String guard = executionGuard(layout);
        if (script == null || !script.startsWith(guard))
            throw new IllegalArgumentException("Installed helper integrity guard missing or changed");
        String body = script.substring(guard.length());
        if (body.isBlank() || !body.contains(layout.helperPath()) || body.startsWith(guard))
            throw new IllegalArgumentException("Invalid guarded helper invocation");
        return body;
    }

    /** Returns the fixed read-only prerequisite checks shared by all helper callers. / 返回全部helper调用方共用的固定只读前置核验。
     * @return exact fail-before-execution guard / 精确执行前失败守卫 */
    private static String executionGuard() {
        return """
            set -eu
            PATH=/usr/sbin:/usr/bin:/sbin:/bin
            export PATH
            unset BASH_ENV ENV
            for wtl_helper_directory in / /usr /usr/local /usr/local/lib %s; do
                [ -d "$wtl_helper_directory" ] && [ ! -L "$wtl_helper_directory" ] || exit 64
                [ "$(/usr/bin/stat -c '%%u:%%g' -- "$wtl_helper_directory")" = 0:0 ] || exit 64
                wtl_helper_writable=$(/usr/bin/find -P "$wtl_helper_directory" -maxdepth 0 -perm /022 -print -quit) || exit 64
                [ -z "$wtl_helper_writable" ] || exit 64
            done
            [ -f '%s' ] && [ -x '%s' ] && [ ! -L '%s' ] || exit 64
            [ "$(/usr/bin/stat -c '%%u:%%g' -- '%s')" = 0:0 ] || exit 64
            wtl_helper_writable=$(/usr/bin/find -P '%s' -maxdepth 0 -perm /022 -print -quit) || exit 64
            [ -z "$wtl_helper_writable" ] || exit 64
            printf '%%s  %%s\\n' '%s' '%s' | /usr/bin/sha256sum --check --status || exit 64
            """.formatted(DIRECTORY, PATH, PATH, PATH, PATH, PATH, EXPECTED_SHA256, PATH);
    }

    private static String executionGuard(DeploymentRemoteLayout layout) {
        if (DeploymentRemoteLayout.original().equals(layout))
            return executionGuard();
        String directory = layout.paths().helperDirectory();
        var ancestors = new java.util.ArrayList<String>();
        ancestors.add("/");
        String current = "";
        for (String component : directory.substring(1).split("/")) {
            current += "/" + component;
            ancestors.add(current);
        }
        String path = layout.helperPath();
        return """
            set -eu
            PATH=/usr/sbin:/usr/bin:/sbin:/bin
            export PATH
            unset BASH_ENV ENV
            for wtl_helper_directory in %s; do
                [ -d "$wtl_helper_directory" ] && [ ! -L "$wtl_helper_directory" ] || exit 64
                [ "$(/usr/bin/stat -c '%%u:%%g' -- "$wtl_helper_directory")" = 0:0 ] || exit 64
                wtl_helper_writable=$(/usr/bin/find -P "$wtl_helper_directory" -maxdepth 0 -perm /022 -print -quit) || exit 64
                [ -z "$wtl_helper_writable" ] || exit 64
            done
            [ -f '%s' ] && [ -x '%s' ] && [ ! -L '%s' ] || exit 64
            [ "$(/usr/bin/stat -c '%%u:%%g' -- '%s')" = 0:0 ] || exit 64
            wtl_helper_writable=$(/usr/bin/find -P '%s' -maxdepth 0 -perm /022 -print -quit) || exit 64
            [ -z "$wtl_helper_writable" ] || exit 64
            printf '%%s  %%s\\n' '%s' '%s' | /usr/bin/sha256sum --check --status || exit 64
            """.formatted(String.join(" ", ancestors), path, path, path, path, path, helperSha256(layout), path);
    }

    /**
     * Assembles and verifies the immutable helper protocol script. / 拼装并验证不可变 helper 协议脚本。
     *
     * @return render script text / 渲染脚本文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static String renderScript() {
        byte[] bytes = assemble();
        String actual = sha256(bytes);
        if (!EXPECTED_SHA256.equals(actual)) {
            throw new IllegalStateException("managed-deployment privilege helper bundle identity changed: " + actual);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Verifies the default template identity before rendering another immutable layout. */
    public static String renderScript(DeploymentRemoteLayout layout) {
        java.util.Objects.requireNonNull(layout);
        String original = renderScript();
        return DeploymentRemoteLayout.original().equals(layout) ? original
            : new String(assemble(DeploymentToolchainCatalog.defaults(), layout), StandardCharsets.UTF_8);
    }

    public static String helperSha256(DeploymentRemoteLayout layout) {
        return sha256(renderScript(layout).getBytes(StandardCharsets.UTF_8));
    }

    /** Supplies the actual fixed opaque-data receiver; protected payload bytes are never included. */
    public static Map<String, Object> configurationStagingImplementationFacts(DeploymentRemoteLayout layout) {
        String complete = renderScript(layout);
        var sources = new java.util.ArrayList<Map<String, Object>>();
        int characters = 0;
        for (String fragment : List.of("00-protocol-foundation.sh", "input/15-deployment-input.sh", "input/17-managed-content.sh",
            "workspace/20-candidate-workspace.sh", "70-command-dispatch.sh")) {
            String source = new String(assemble(DeploymentToolchainCatalog.defaults(), layout, List.of(fragment)), StandardCharsets.UTF_8);
            characters += source.length();
            if (characters > 131072 || !complete.contains(source))
                throw new IllegalStateException("Configuration staging source differs from pinned helper");
            sources.add(Map.of("fragment", fragment, "sha256", sha256(source.getBytes(StandardCharsets.UTF_8)), "source", source));
        }
        return Map.of("helperSha256", sha256(complete.getBytes(StandardCharsets.UTF_8)), "sources", List.copyOf(sources),
            "sourceBinding", "EXACT_RENDERED_SUBSTRINGS_OF_COMPLETE_LOCALLY_HASH_PINNED_HELPER",
            "meaning", "Actual fixed stage_configuration/stage_secret, receive_immutable_payload, controlled-root setup and public dispatch. Review selected arguments, declared target, hash/length, root initialization and immutable file creation/reuse. Opaque stdin is copied as bounded bytes, never evaluated, sourced or run by staging; configuration/secret identity differs from rendered payload SHA. Protected values intentionally remain undisclosed. Runtime consumption, activation and application behavior are separate operations needing their own review. Source bytes and exact payload bindings do not prove live ownership, desired content, safe future consumption or authority.");
    }

    /** Supplies the pinned build controller and non-root entry source without invoking or modifying either. */
    public static Map<String, Object> buildIsolationImplementationFacts(DeploymentRemoteLayout layout) {
        String complete = renderScript(layout), entry = renderBuildEntry(layout);
        var groups = List.of(List.of("00-protocol-foundation.sh"),
            List.of("workspace/20-candidate-workspace.sh", "workspace/21-workspace-volume.sh", "workspace/22-restricted-build.sh", "workspace/23-container-builder.sh"),
            List.of("workspace/26-build-output.sh"), List.of("ecosystem/35-ecosystem-dispatch.sh"),
            List.of("runtime/systemd/61-service-identity.sh"), List.of("70-command-dispatch.sh"));
        var sources = new java.util.ArrayList<Map<String, Object>>();
        int characters = entry.length();
        for (var fragments : groups) {
            String source = new String(assemble(DeploymentToolchainCatalog.defaults(), layout, fragments), StandardCharsets.UTF_8);
            characters += source.length();
            if (characters > 131072 || !complete.contains(source))
                throw new IllegalStateException("Build review source differs from pinned helper");
            sources.add(Map.of("fragments", fragments, "sha256", sha256(source.getBytes(StandardCharsets.UTF_8)), "source", source));
        }
        String entryDigest = sha256(entry.getBytes(StandardCharsets.UTF_8));
        if (!complete.contains(entryDigest)) throw new IllegalStateException("Build entry differs from helper pin");
        return Map.of("helperSha256", sha256(complete.getBytes(StandardCharsets.UTF_8)), "sources", List.copyOf(sources),
            "buildEntrySha256", entryDigest, "buildEntrySource", entry,
            "sourceBinding", "EXACT_RENDERED_HELPER_SUBSTRINGS_AND_HASH_BOUND_COMPLETE_BUILD_ENTRY",
            "meaning", "Actual pinned controller/volume/identity/output/binding-dispatch/systemd isolation sources and complete build-entry; these are implementation evidence, not a live isolation receipt or permission. Inspect this current engine branch, arguments, full executable stdin and limits. Project source and dependency lifecycle hooks remain untrusted executable input intentionally run inside that build boundary; source snapshots are identity evidence, not code approval. Ordinary dynamic-user systemd isolation, rootless-container exceptions, network access, writable candidate paths and root-owned controller setup/cleanup effects must be assessed from the complete supplied code; no claim of a network sandbox or safe hooks is made. Other helper functions remain governed by the separately pinned interface contract; this is not a reproduction of the whole helper or installer.");
    }

    /**
     * Combines fixed helper fragments with resource inserts and the shipped toolchain catalog into UTF-8 bytes.
     * <p>将固定 helper 片段、资源插入内容及随附工具链目录组合为 UTF-8 字节。
     *
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    static byte[] assemble() {
        return assemble(DeploymentToolchainCatalog.defaults());
    }

    /**
     * Combines fixed helper fragments with resource inserts and the shipped toolchain catalog into UTF-8 bytes.
     * <p>将固定 helper 片段、资源插入内容及随附工具链目录组合为 UTF-8 字节。
     *
     * @param catalog catalog / 目录
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    static byte[] assemble(DeploymentToolchainCatalog catalog) {
        return assemble(catalog, DeploymentRemoteLayout.original());
    }

    /** Renders only the fixed native-first Agent recovery program; the global helper remains unchanged. */
    public static String renderPublicationRecovery(DeploymentRemoteLayout layout) {
        renderScript(); // Verify every original helper resource against the unchanged protocol pin.
        var fragments = List.of("00-protocol-foundation.sh", "input/15-deployment-input.sh",
            "input/16-application-input.sh", "input/17-managed-content.sh", "ecosystem/35-ecosystem-dispatch.sh",
            "toolchain/10-release-metadata.py", "toolchain/20-installation-boundaries.py",
            "toolchain/30-managed-installation.py", "toolchain/40-binding-protocol.py",
            "runtime/40-typed-runtime.sh", "runtime/41-application-admission.sh", "runtime/41-application-runtime.sh",
            "runtime/systemd/61-service-identity.sh", "database/10-native-instances.sh");
        String base = new String(assemble(DeploymentToolchainCatalog.defaults(), java.util.Objects.requireNonNull(layout), fragments),
            StandardCharsets.UTF_8);
        try (var stream = DeploymentManagedHelperBundle.class.getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/publication-recovery-guard.sh")) {
            if (stream == null) throw new IllegalStateException("Publication recovery guard unavailable");
            return base + "\n" + DeploymentRemoteLayoutTemplate.render(
                new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), layout);
        } catch (IOException failure) {
            throw new IllegalStateException("Publication recovery guard unreadable", failure);
        }
    }

    private static byte[] assemble(DeploymentToolchainCatalog catalog, DeploymentRemoteLayout layout) {
        return assemble(catalog, layout, FRAGMENTS);
    }

    private static byte[] assemble(DeploymentToolchainCatalog catalog, DeploymentRemoteLayout layout, List<String> fragments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String fragment : fragments) {
            try (InputStream stream = DeploymentManagedHelperBundle.class.getResourceAsStream(ROOT + fragment)) {
                if (stream == null) {
                    throw new IllegalStateException(
                        "managed-deployment privilege helper fragment is unavailable: " + fragment);
                }
                String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                content = DeploymentRemoteLayoutTemplate.render(expandResourceInserts(content), layout);
                if (fragment.startsWith("toolchain/")) {
                    if (fragment.endsWith("10-release-metadata.py"))
                        output.write(
                            ("prepare_official_toolchains() {\n  /usr/bin/python3 -I - \"$@\" <<'WTL_OFFICIAL_TOOLCHAINS'\n"
                                + catalogLiteral(catalog)).getBytes(StandardCharsets.UTF_8));
                    output.write((content + "\n").getBytes(StandardCharsets.UTF_8));
                    if (fragment.endsWith("40-binding-protocol.py"))
                        output.write("WTL_OFFICIAL_TOOLCHAINS\n}\n".getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                if (fragment.endsWith("00-protocol-foundation.sh")) {
                    content += "build_entry_sha256=" + buildEntrySha256(layout)
                        + "\n";
                }
                // Embedded Python is literal; packaged shell fragments use their declared backslash encoding. / 嵌入的 Python 保持字面内容，打包 Shell 片段按声明的反斜杠编码输出。
                output.write(((fragment.endsWith("27-agent-workspace.sh") || fragment.endsWith("10-native-instances.sh")
                    || fragment.endsWith("20-native-targets.sh") || fragment.endsWith("21-native-operations.sh") || fragment.endsWith("23-container-builder.sh")
                    || fragment.endsWith("12-container-image-input.sh")
                    || fragment.endsWith("18-container-storage.sh") || fragment.contains("application-"))
                    ? content
                    : content.replace("\\\\", "\\"))
                    .getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "managed-deployment privilege helper fragment cannot be read: " + fragment, exception);
            }
        }
        return output.toByteArray();
    }

    /**
     * Replaces known helper insertion markers with their required packaged resource contents.
     * <p>使用必需的打包资源内容替换已知 helper 插入标记。
     *
     * @param script build script path / 构建脚本路径
     * @return expand resource inserts text / expand资源Inserts文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String expandResourceInserts(String script) throws IOException {
        for (var insert : RESOURCE_INSERTS.entrySet()) {
            if (!script.contains(insert.getKey()))
                continue;
            try (InputStream input = DeploymentManagedHelperBundle.class
                .getResourceAsStream(ROOT + insert.getValue())) {
                if (input == null) {
                    throw new IllegalStateException(
                        "managed-deployment privilege helper insert is unavailable: " + insert.getValue());
                }
                script = script.replaceAll("(?m)^[ \t]*" + java.util.regex.Pattern.quote(insert.getKey()),
                    java.util.regex.Matcher.quoteReplacement(
                        new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")));
            }
        }
        return script;
    }

    /**
     * Renders the supported toolchain catalog as the helper's fixed Python data literal.
     * <p>将受支持工具链目录渲染为 helper 的固定 Python 数据字面量。
     *
     * @param catalog catalog / 目录
     * @return catalog literal text / 目录Literal文本
     */
    static String catalogLiteral(DeploymentToolchainCatalog catalog) {
        StringBuilder data = new StringBuilder("SHIPPED_CATALOG = {\n");
        for (var ecosystem : DeploymentToolchainType.values()) {
            data.append("    '").append(ecosystem.name()).append("': [");
            data.append(catalog.branches(ecosystem).stream().map(b -> "'" + b.version() + "'")
                .collect(java.util.stream.Collectors.joining(", "))).append("],\n");
        }
        return data.append("}\n").toString();
    }

    /**
     * Computes the SHA-256 content identity used for independent integrity checks.
     * <p>计算独立完整性检查使用的 SHA-256 内容身份。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return computed SHA-256 content digest / 已计算的 SHA-256 内容摘要
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
