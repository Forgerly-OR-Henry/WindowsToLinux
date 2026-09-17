package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedHelperBundleTest {
    @Test void extendsTheSameCatalogIntoHelperAndBuildScriptsWithoutVersionSpecificCode() {
        var catalog = gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.defaults();
        var branches = new java.util.ArrayList<>(catalog.branches());
        branches.add(new gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.Branch(
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA, "29",
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.ReleaseType.LTS,
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.MaintenanceStatus.MAINTAINED,
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.InstallationType.TEMURIN));
        var future = new gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog("TEST_ONLY", branches);
        String helper = new String(ManagedHelperBundle.assemble(future), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helper.contains("'JAVA': ['8', '11', '17', '21', '25', '29']"));
        var requirement = gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.declared(
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA, "28", "fixture",
                gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.PurposeType.BUILD);
        var version = new gold.debug.windowstolinux.shared.model.toolchain.ToolchainSelectionPolicy(future).resolve(
                requirement, future.candidates(requirement).getFirst(), java.util.List.of(
                        gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion.parse(requirement.ecosystem(), "29.0.1").orElseThrow()));
        var set = new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet(future.revision(), java.util.List.of(
                new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet.Selection(requirement, version,
                        "/usr/local/lib/windowstolinux/toolchains/versions/java-" + "a".repeat(64),
                        gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet.OriginType.MANAGED,
                        "https://api.adoptium.net/test-fixture", "a".repeat(64))));
        assertTrue(gold.debug.windowstolinux.shared.linux.sshd.toolchain.ToolchainBuildEnvironment.render(set)
                .contains("WTL_JAVA_BRANCH='29'"));
        assertEquals("28", set.selections().getFirst().requirement().declaration());
        org.junit.jupiter.api.Assertions.assertFalse(catalog.permits(version));
    }
    @Test
    void assemblesTheAllowlistedProtocolByteForByte() throws Exception {
        byte[] bytes = ManagedHelperBundle.renderScript().getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        assertEquals(ManagedHelperBundle.EXPECTED_SHA256, sha256);
        assertFalse(ManagedHelperBundle.renderScript().contains("# @compat:"));
        assertTrue(ManagedHelperBundle.renderScript().startsWith("#!/usr/bin/env bash\n"));
        assertTrue(ManagedHelperBundle.renderScript().endsWith("esac\n"));
        assertEquals("/usr/local/lib/windowstolinux/managed-helper", ManagedHelperBundle.PATH);
        assertTrue(ManagedHelperBundle.renderScript().contains("/usr/local/lib/windowstolinux/java-21"));
        assertFalse(ManagedHelperBundle.renderScript().contains("/usr/bin/java"));
    }

    @Test
    void exposesOnlyVersionedTypedOperationsAndKeepsLegacyGradleReadCompatibility() {
        String helper = ManagedHelperBundle.renderScript();

        assertEquals(7, ManagedHelperBundle.PROTOCOL_VERSION);
        assertTrue(helper.contains("printf 'HELPER=1\\nPROTOCOL=%s\\n' \"$helper_protocol\""));
        assertTrue(helper.contains("gradle)"));
        assertTrue(helper.contains("[ \"$kind\" != gradle ] || reject legacy-gradle-write"));
        assertTrue(helper.contains("go|rust)"));
        assertTrue(helper.contains("java|javasource)"));
        assertTrue(helper.contains("PIP_LOCKED|PIPENV_LOCKED|POETRY_LOCKED|UV_LOCKED"));
        assertTrue(helper.contains("GRADLE_KOTLIN_WRAPPER|KOTLINC"));
        assertTrue(helper.contains("render_ecosystem_runtime_command \"$kind\" \"$root\" \"$@\""));
        assertTrue(helper.contains("phpcli)"));
        assertTrue(helper.contains("rubycli)"));
        assertTrue(helper.contains("cmake)"));
        assertTrue(helper.contains("database-inspect) database_inspect \"$@\""));
        assertTrue(helper.contains("database-export) database_export \"$@\""));
        assertTrue(helper.contains("database-stage-artifact) database_stage_artifact \"$@\""));
        assertTrue(helper.contains("database-restore-candidate) database_restore_candidate \"$@\""));
        assertTrue(helper.contains("database-commit-candidate) database_commit_candidate \"$@\""));
        assertTrue(helper.contains("database-recover-candidate) database_recover_candidate \"$@\""));
        assertTrue(helper.contains("database-discard-candidate) database_discard_candidate \"$@\""));
        assertTrue(helper.contains("assert_root_owned_regular \"$(restore_activation_root \"$database_activation_candidate\")/.application-quiesced\""));
        assertTrue(helper.contains("[ \"$database_activation_type\" != sqlite ] || reject database-sqlite-activation-unsupported"));
        assertTrue(helper.contains("--lock-all-tables --routines --events --triggers"));
        assertTrue(helper.contains("[ \"${#database_name}\" -le 63 ] || reject database-postgresql-name-too-long"));
        assertTrue(helper.contains("PREVIOUS_VERIFIED=1\\nCANDIDATE_REMOVED=1"));
        assertTrue(helper.contains("backup-create) backup_create_artifact \"$@\""));
        assertTrue(helper.contains("backup-read) backup_read_artifact \"$@\""));
        assertTrue(helper.contains("backup-discard) backup_discard_operation \"$@\""));
        assertTrue(helper.contains("data_root=\"$base_root/data\""));
        assertTrue(helper.contains("rm -f -- \"$pgpass\"; reject database-restore-failed"));
        assertTrue(helper.contains("DROP DATABASE IF EXISTS"));
        assertTrue(helper.contains("dropdb --if-exists --force --no-password"));
        assertTrue(helper.contains("rm -f -- \"$credentials\""));
        assertTrue(helper.contains("NPM) command=\"/usr/bin/env PATH=${node_manager_path:-$toolchain_path} npm"));
        assertTrue(helper.contains("PNPM) command=\"/usr/bin/env PATH=${node_manager_path:-$toolchain_path} pnpm"));
        assertTrue(helper.contains("YARN) command=\"/usr/bin/env $node_yarn_environment yarn"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=$toolchain_path $toolchain_php -S"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=$toolchain_path $toolchain_php -n -S"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=$toolchain_path bundle exec rackup"));
        assertTrue(helper.contains("GEM_PATH=$root/current/source/.w2l/bundler: bundle exec rackup"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=$toolchain_path PORT=$rubycli_port $toolchain_ruby"));
        assertTrue(helper.contains("restore-preflight) restore_preflight \"$@\""));
        assertTrue(helper.contains("restore-start-candidate) restore_start_candidate \"$@\""));
        assertTrue(helper.contains("restore-start-formal) restore_start_formal \"$@\""));
        assertTrue(helper.contains("restore-mark-quiesced) restore_mark_quiesced \"$@\""));
        assertTrue(helper.contains("restore-quiesce-recovery) restore_quiesce_recovery \"$@\""));
        assertTrue(helper.contains("restore-recover) restore_recover_component \"$@\""));
        assertTrue(helper.contains("previous_kind=ordinary"));
        assertTrue(helper.contains("[ \"$previous_kind\" = deployment ] || [ \"$previous_kind\" = ordinary ]"));
        assertTrue(helper.contains("printf '%s\\n' \"$previous_kind\" > \"$snapshot/kind\""));
        assertTrue(helper.contains("tr -d '\\r' < \"$manifest/META-INF/MANIFEST.MF\" | grep -Eq"));
        assertTrue(helper.contains("if [ ! -e \"$candidate/.windowstolinux-owner\" ] && [ ! -L \"$candidate/.windowstolinux-owner\" ]; then"));
        assertTrue(helper.contains("[ ! -e \"$release/.windowstolinux-owner\" ] && [ ! -L \"$release/.windowstolinux-owner\" ] || reject release-exists"));
        assertTrue(helper.contains("[ \"$previous_path\" = \"$release\" ] && [ \"$previous_running\" -eq 1 ]"));
        assertTrue(helper.contains("ln -sfnT -- \"$previous\" \"$root/current\""));
        assertTrue(helper.contains("install -o root -g root -m 644 -- \"$snapshot/unit\" \"$unit\""));
        assertTrue(helper.contains("if [ \"$previous_runtime\" = active ]; then systemctl start"));
        assertFalse(helper.contains("  snapshot)"));
        assertFalse(helper.contains("  publish)"));
        assertFalse(helper.contains("  rollback)"));
        assertFalse(helper.contains("  install-unit)"));
        assertFalse(helper.contains("  daemon-reload)"));
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporaryDirectory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"valid", "podman", "podman-unqualified", "podman-extra-tags",
            "root", "tag", "link", "oversized", "foreign"})
    void validatesRootlessImageInputWithoutExecutingItsContents(String scenario) throws Exception {
        String helper = ManagedHelperBundle.renderScript();
        String marker = "<<'WTL_IMAGE_INPUT'\n";
        int start = helper.indexOf(marker) + marker.length();
        String validator = helper.substring(start, helper.indexOf("\nWTL_IMAGE_INPUT", start));
        var archive = temporaryDirectory.resolve("image.tar");
        var validation = temporaryDirectory.resolve("validate.py");
        java.nio.file.Files.writeString(validation, validator);
        java.nio.file.Files.writeString(temporaryDirectory.resolve("json.py"), "raise RuntimeError('untrusted import')");
        String fixture = """
                import hashlib, io, json, sys, tarfile
                scenario, archive, repository = sys.argv[1:]
                config = {'config': {'User': '0' if scenario == 'root' else '65532:65532',
                    'Labels': {'io.windowstolinux.application': 'other' if scenario == 'foreign' else 'demo',
                               'io.windowstolinux.candidate': 'demo-0123456789abcdef'}}}
                raw = json.dumps(config).encode()
                name = hashlib.sha256(raw).hexdigest() + '.json'
                tags = ['foreign:latest' if scenario == 'tag' else
                    ('windowstolinux-candidate' if scenario == 'podman-unqualified' else repository) + ':demo-0123456789abcdef']
                if scenario == 'podman-extra-tags': tags.append('foreign:latest')
                manifest = [{'Config': name, 'RepoTags': tags, 'Layers': []}]
                repositories = json.dumps({repository: {'demo-0123456789abcdef': 'legacy-layer'}}).encode()
                with tarfile.open(archive, 'w') as output:
                    for path, data in [(name, raw), ('manifest.json', b'x' * 524289 if scenario == 'oversized' else json.dumps(manifest).encode()),
                                       ('repositories', repositories)]:
                        info = tarfile.TarInfo(path); info.size = len(data)
                        output.addfile(info, io.BytesIO(data))
                    if scenario == 'link':
                        info = tarfile.TarInfo('escape'); info.type = tarfile.SYMTYPE; info.linkname = '/etc/passwd'
                        output.addfile(info)
                """;
        String repository = scenario.startsWith("podman") ? "localhost/windowstolinux-candidate" : "windowstolinux-candidate";
        assertEquals(0, python("-c", fixture, scenario, archive.toString(), repository));
        int exit = python(validation.toString(), archive.toString(), "demo", "demo-0123456789abcdef",
                repository + ":demo-0123456789abcdef");
        if (scenario.equals("valid") || scenario.equals("podman")) assertEquals(0, exit);
        else org.junit.jupiter.api.Assertions.assertNotEquals(0, exit);
    }

    private int python(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(System.getProperty("managed.test.python", "python")); command.add("-I");
        command.addAll(java.util.List.of(arguments));
        var log = temporaryDirectory.resolve("python-output");
        Process process = new ProcessBuilder(command).directory(temporaryDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Python validation timed out");
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test void sqlClientsUsePrivateCredentialsAndDisableLocalFileCommands() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        String fragment;
        try (var input = ManagedHelperBundle.class.getResourceAsStream(
                "/gold/debug/windowstolinux/shared/linux/sshd/execution/protocol/helper/fragments/database/64-database-client.sh")) {
            fragment = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(("set -eu\nbase_root=/controlled\n"
                        + "assert_root_owned_regular() { test \"$1\" = /controlled/client; }\n"
                        + "cat() { printf '01234567-89ab-cdef-0123-456789abcdef'; }\n"
                        + "systemd-run() { printf '%s\\n' \"$@\"; }\n"
                        + fragment + "\nrun_mysql_client /usr/bin/mysql /controlled/client --database=fixture\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String arguments = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), arguments);
            for (String flag : java.util.List.of("--batch", "--binary-mode", "--local-infile=0", "--defaults-file=/run/credentials/windowstolinux-database-01234567-89ab-cdef-0123-456789abcdef.service/client",
                    "--property=DynamicUser=yes", "--property=LoadCredential=client:/controlled/client"))
                assertTrue(arguments.lines().anyMatch(flag::equals), arguments);
            assertFalse(arguments.contains("--password"));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); }
        }
    }

}
