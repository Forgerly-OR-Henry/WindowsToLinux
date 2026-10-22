package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class DeploymentManagedHelperBundleTest {
    @Test
    void publicationRecoveryUsesFixedOriginalFragmentsWithoutChangingGlobalHelper() throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        String original = DeploymentManagedHelperBundle.renderScript();
        String recovery = DeploymentManagedHelperBundle.renderPublicationRecovery(layout);
        assertEquals(16, DeploymentManagedHelperBundle.PROTOCOL_VERSION);
        assertEquals("bb1ecc2ae54d46263ef1eab31b40fd8967a7acf0a7a86c463b80eeaf7b217efd",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))));
        assertEquals(original, DeploymentManagedHelperBundle.renderScript());
        assertFalse(recovery.contains("rollback-deployment-first)"));
        assertFalse(recovery.contains("application_assert_native_job_stopped()"));
        assertFalse(recovery.contains("stop_candidate_build()"));
        int last = recovery.lastIndexOf("application_assert_no_orphan() {");
        int next = recovery.indexOf("recovery_existing_regular()", last);
        String noJob = recovery.substring(last, next);
        assertTrue(noJob.contains("! -e"));
        assertTrue(noJob.contains("! -L"));
        assertTrue(noJob.contains("reject recovery-job-present"));
        assertFalse(noJob.contains("rm "));
        assertFalse(noJob.contains("systemctl"));
        String entry = recovery.substring(recovery.indexOf("recovery_first_rollback()"));
        assertTrue(entry.indexOf("application_maintenance ") < entry.indexOf("assert_deployment_current_or_empty "));
        assertTrue(entry.indexOf("assert_deployment_current_or_empty ") < entry.indexOf("rollback_deployment_first "));
        assertTrue(entry.indexOf("recovery-maintenance-remains") > entry.indexOf("rollback_deployment_first "));
        for (var pin : java.util.Map.of("agent-controller-loader.py", "d04eca875afd038c58b44cd4699470a9f384136ad7cec5c122942d6e0bc204be",
            "agent-controller-install.py", "4c25923129cf686edcd50edab7ed947cd966c92ea590a68f2d79aeb20364ec6a",
            "agent-controller-program-manifest.json", "da40a8654e8fe1c7949f5f20a1412960d15a2d4521e69152ffe7206d6092f6b7").entrySet()) {
            try (var stream = getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/service/agent/toolchain/" + pin.getKey())) {
                assertEquals(pin.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
            }
        }
        // These are artifact/dispatch assertions; real shell guard behavior is not executed by this test.
    }

    @Test
    void extendsTheSameCatalogIntoHelperAndBuildScriptsWithoutVersionSpecificCode() {
        var catalog = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog
            .defaults();
        var branches = new java.util.ArrayList<>(catalog.branches());
        branches.add(
            new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.Branch(
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.JAVA, "29",
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.ReleaseType.LTS,
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.MaintenanceStatus.MAINTAINED,
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.InstallationType.TEMURIN));
        var future = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog(
            "TEST_ONLY", branches);
        String helper = new String(DeploymentManagedHelperBundle.assemble(future),
            java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(helper.contains("'JAVA': ['8', '11', '17', '21', '25', '29']"));
        var requirement = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement
            .declared(gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.JAVA,
                "28", "fixture",
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.PurposeType.BUILD);
        var version = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainSelectionPolicy(
            future)
            .resolve(requirement, future.candidates(requirement).getFirst(),
                java.util.List.of(
                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion
                        .parse(requirement.ecosystem(), "29.0.1").orElseThrow()));
        var set = new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains(
            future.revision(),
            java.util.List.of(
                new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.Selection(
                    requirement, version,
                    "/usr/local/lib/windowstolinux/toolchains/versions/java-" + "a".repeat(64),
                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.OriginType.MANAGED,
                    "https://api.adoptium.net/test-fixture", "a".repeat(64))));
        assertEquals("28", set.selections().getFirst().requirement().declaration());
        assertFalse(catalog.permits(version));
    }

    @Test
    void assemblesTheAllowlistedProtocolByteForByte() throws Exception {
        byte[] bytes = DeploymentManagedHelperBundle.renderScript().getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, sha256);
        assertFalse(DeploymentManagedHelperBundle.renderScript().contains("# @compat:"));
        assertTrue(DeploymentManagedHelperBundle.renderScript().startsWith("#!/usr/bin/env bash\n"));
        assertTrue(DeploymentManagedHelperBundle.renderScript().endsWith("esac\n"));
        assertEquals("/usr/local/lib/windowstolinux/managed-helper", DeploymentManagedHelperBundle.PATH);
        assertTrue(DeploymentManagedHelperBundle.renderScript().contains("/usr/local/lib/windowstolinux/java-21"));
        assertFalse(DeploymentManagedHelperBundle.renderScript().contains("/usr/bin/java"));
    }

    @Test
    void exposesOnlyCurrentTypedOperations() {
        String helper = DeploymentManagedHelperBundle.renderScript();

        assertEquals(16, DeploymentManagedHelperBundle.PROTOCOL_VERSION);
        assertTrue(helper.contains("snapshot-deployment-attempt) snapshot_deployment_attempt \"$@\""));
        assertTrue(helper.contains("snapshot-container-attempt) snapshot_container_attempt \"$@\""));
        assertTrue(helper.contains("publication-original-status-attempt) publication_original_status_attempt \"$@\""));
        assertTrue(helper.contains("snapshot-discard-attempt) discard_original_snapshot_attempt \"$@\""));
        assertTrue(helper.contains("printf 'HELPER=1\\nPROTOCOL=%s\\n' \"$helper_protocol\""));
        assertFalse(helper.contains("gradle)"));
        assertFalse(helper.contains("LEGACY_UNSPECIFIED"));
        assertFalse(helper.contains("legacy_runtime_user"));
        assertFalse(helper.contains("expected_unit_digest()"));
        assertFalse(helper.contains("lifecycle) lifecycle \"$@\""));
        assertTrue(helper.contains("go | rust)"));
        assertTrue(helper.contains("java | javasource)"));
        assertTrue(helper.contains("PIP_LOCKED | PIPENV_LOCKED | POETRY_LOCKED | UV_LOCKED"));
        assertTrue(helper.contains("GRADLE_KOTLIN_WRAPPER | KOTLINC"));
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
        assertTrue(helper.contains(
            "assert_root_owned_regular \"$(restore_activation_root \"$database_activation_candidate\")/.application-quiesced\""));
        assertTrue(helper.contains("database_commit_sqlite()"));
        assertTrue(helper.contains("database_recover_sqlite()"));
        assertTrue(helper.contains("--lock-all-tables --routines --events --triggers"));
        assertTrue(helper.contains("[ \"${#database_name}\" -le 63 ] || reject database-postgresql-name-too-long"));
        assertTrue(helper.contains("PREVIOUS_VERIFIED=1\\nCANDIDATE_REMOVED=1"));
        assertTrue(helper.contains("backup-create) backup_create_artifact \"$@\""));
        assertTrue(helper.contains("backup-read) backup_read_artifact \"$@\""));
        assertTrue(helper.contains("backup-discard) backup_discard_operation \"$@\""));
        assertTrue(helper.contains("handoff-backup-status) handoff_backup_status \"$@\""));
        assertTrue(helper.contains("handoff-maintenance-status) handoff_maintenance_status \"$@\""));
        assertTrue(helper.contains("handoff-database-export) handoff_database_export \"$@\""));
        assertTrue(helper.contains("handoff-database-status) handoff_database_status \"$@\""));
        assertTrue(helper.contains("handoff-database-discard) handoff_database_discard \"$@\""));
        assertTrue(helper.contains("activate-retained) activate_retained \"$@\""));
        assertTrue(helper.contains("rollback-retained) rollback_retained \"$@\""));
        assertTrue(helper.contains("finish-retained) finish_retained \"$@\""));
        assertTrue(helper.contains("retained-status) retained_status \"$@\""));
        assertTrue(helper.contains("data_root=/var/opt/windowstolinux/apps"));
        assertTrue(helper.matches("(?s).*rm -f -- \"\\$pgpass\"\\s+reject database-restore-failed.*"));
        assertTrue(helper.contains("DROP DATABASE IF EXISTS"));
        assertTrue(helper.contains("dropdb --if-exists --force --no-password"));
        assertTrue(helper.contains("rm -f -- \"$credentials\""));
        assertTrue(helper.contains("NPM) command=\"/usr/bin/env PATH=${node_manager_path:-$toolchain_path} npm"));
        assertTrue(helper.contains("PNPM) command=\"/usr/bin/env PATH=${node_manager_path:-$toolchain_path} pnpm"));
        assertTrue(helper.contains("YARN) command=\"/usr/bin/env $node_yarn_environment yarn"));
        assertTrue(helper
            .contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=$toolchain_path $toolchain_php -S"));
        assertTrue(helper
            .contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=$toolchain_path $toolchain_php -n -S"));
        assertTrue(helper
            .contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=$root/current/source/.w2l/bundler/bin:$toolchain_path GEM_HOME=$root/current/source/.w2l/bundler"));
        assertTrue(helper.contains("GEM_PATH=$root/current/source/.w2l/bundler: bundle exec rackup"));
        assertTrue(helper.contains(
            "ecosystem_runtime_command_result=\"/usr/bin/env PATH=$toolchain_path PORT=$rubycli_port $toolchain_ruby"));
        assertTrue(helper.contains("restore-preflight) restore_preflight \"$@\""));
        assertTrue(helper.contains("restore-start-candidate) restore_start_candidate \"$@\""));
        assertTrue(helper.contains("restore-start-formal) restore_start_formal \"$@\""));
        assertTrue(helper.contains("restore-mark-quiesced) restore_mark_quiesced \"$@\""));
        assertTrue(helper.contains("restore-quiesce-recovery) restore_quiesce_recovery \"$@\""));
        assertTrue(helper.contains("restore-recover) restore_recover_component \"$@\""));
        assertFalse(helper.contains("previous_kind=ordinary"));
        assertTrue(helper.contains("[ \"$previous_kind\" = deployment ] || reject snapshot-kind"));
        assertTrue(helper.contains("printf 'deployment\\n' >\"$snapshot/kind\""));
        assertTrue(helper.contains("tr -d '\\r' <\"$manifest/META-INF/MANIFEST.MF\" | grep -Eq"));
        assertTrue(helper.contains(
            "if [ ! -e \"$candidate/.windowstolinux-owner\" ] && [ ! -L \"$candidate/.windowstolinux-owner\" ]; then"));
        assertTrue(helper.contains(
            "[ ! -e \"$release/.windowstolinux-owner\" ] && [ ! -L \"$release/.windowstolinux-owner\" ] || reject release-exists"));
        assertTrue(helper.contains("[ \"$previous_path\" = \"$release\" ]; then"));
        assertTrue(helper.contains("reject release-parameters-changed"));
        assertTrue(helper.contains("ln -sfnT -- \"$previous\" \"$root/current\""));
        assertTrue(helper.contains("install -o root -g root -m 644 -- \"$snapshot/unit\" \"$unit\""));
        assertTrue(helper.contains("if [ \"$previous_runtime\" = active ]; then systemctl start"));
        assertFalse(helper.contains("  snapshot)"));
        assertFalse(helper.contains("  publish)"));
        assertFalse(helper.contains("  rollback)"));
        assertFalse(helper.contains("  install-unit)"));
        assertFalse(helper.contains("  daemon-reload)"));
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temporaryDirectory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"valid", "podman", "podman-unqualified",
        "podman-extra-tags", "root", "tag", "link", "oversized", "foreign"})
    void validatesRootlessImageInputWithoutExecutingItsContents(String scenario) throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String marker = "<<'WTL_IMAGE_INPUT'\n";
        int start = helper.indexOf(marker) + marker.length();
        String validator = helper.substring(start, helper.indexOf("\nWTL_IMAGE_INPUT", start));
        var archive = temporaryDirectory.resolve("image.tar");
        var validation = temporaryDirectory.resolve("validate.py");
        java.nio.file.Files.writeString(validation, validator);
        java.nio.file.Files.writeString(temporaryDirectory.resolve("json.py"),
            "raise RuntimeError('untrusted import')");
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
        String repository = scenario.startsWith("podman")
            ? "localhost/windowstolinux-candidate"
            : "windowstolinux-candidate";
        assertEquals(0, python("-c", fixture, scenario, archive.toString(), repository));
        int exit = python(validation.toString(), archive.toString(), "demo", "demo-0123456789abcdef",
            repository + ":demo-0123456789abcdef");
        if (scenario.equals("valid") || scenario.equals("podman"))
            assertEquals(0, exit);
        else
            org.junit.jupiter.api.Assertions.assertNotEquals(0, exit);
    }

    private int python(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(System.getProperty("managed.test.python", "python"));
        command.add("-I");
        command.addAll(java.util.List.of(arguments));
        var log = temporaryDirectory.resolve("python-output");
        Process process = new ProcessBuilder(command).directory(temporaryDirectory.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
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

    @Test
    void sqlClientsUsePrivateCredentialsAndDisableLocalFileCommands() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        String fragment;
        try (var input = DeploymentManagedHelperBundle.class.getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/linux/helper/database/64-database-client.sh")) {
            fragment = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(("set -eu\nbase_root=/controlled\n"
                    + "assert_root_owned_regular() { test \"$1\" = /controlled/client; }\n"
                    + "cat() { printf '01234567-89ab-cdef-0123-456789abcdef'; }\n"
                    + "systemd-run() { printf '%s\\n' \"$@\"; }\n" + fragment
                    + "\nrun_mysql_client /usr/bin/mysql /controlled/client --database=fixture\n")
                    .getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String arguments = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), arguments);
            for (String flag : java.util.List.of("--batch", "--binary-mode", "--local-infile=0",
                "--defaults-file=/run/credentials/windowstolinux-database-01234567-89ab-cdef-0123-456789abcdef.service/client",
                "--property=DynamicUser=yes", "--property=LoadCredential=client:/controlled/client"))
                assertTrue(arguments.lines().anyMatch(flag::equals), arguments);
            assertFalse(arguments.contains("--password"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    /** Executes the protocol-eleven status verb against exact task evidence and systemd observations. / 对精确任务依据和systemd观测真实执行第十一版状态命令。 */
    @Test
    void publicationObservationReportsFirstDeploymentAndChecksSystemdPresence() throws Exception {
        String prefix = "/gold/debug/windowstolinux/mod/deployment/linux/helper/";
        String foundation;
        String observation;
        try (var input = java.util.Objects.requireNonNull(
            DeploymentManagedHelperBundle.class.getResourceAsStream(prefix + "00-protocol-foundation.sh"))) {
            foundation = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var input = java.util.Objects.requireNonNull(DeploymentManagedHelperBundle.class
            .getResourceAsStream(prefix + "release/53-publication-observation.sh"))) {
            observation = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String setup = "\nbase_root='" + temporaryDirectory.toString().replace('\\', '/')
            + "/controlled'\napplications_root=\"$base_root/apps\"\nwork_root=\"$base_root/work\"\n"
            + "snapshots_root=\"$base_root/snapshots\"\n" + "unit_path() { printf '%s/unit' \"$base_root\"; }\n"
            + "podman_quadlet_path() { printf '%s/quadlet' \"$base_root\"; }\n"
            + "build_unit_name() { printf 'windowstolinux-build-%s.service' \"$1\"; }\n"
            + "assert_root_owned_directory() { test -d \"$1\"; }\n"
            + "assert_root_owned_regular() { test -f \"$1\" && test ! -L \"$1\"; }\n";
        String call = "publication_original_status demo " + "b".repeat(64)
            + " 11111111-1111-4111-8111-111111111111 demo-" + "a".repeat(16) + " none native none\n";
        String inactive = "systemctl() { case \"$*\" in *LoadState*) printf 'not-found\\n';; *ActiveState*) printf 'inactive\\n';; *) printf 'inactive\\n';; esac; }\n";
        var first = publicationScript(foundation + setup + inactive + observation + "\n" + call);
        assertEquals(0, first.exit(), first.output());
        assertTrue(first.output().startsWith(
                "PUBLICATION_STATUS=1\nCURRENT=none\nCURRENT_KIND=none\n" + "CURRENT_MODE=none\nSNAPSHOT=none\n"),
            first.output());
        assertTrue(first.output().contains("BUILD_ACTIVE=no\nUNIT_PRESENT=no\nCONTAINER_PRESENT=not-applicable\n"),
            first.output());
        String loaded = "systemctl() { case \"$*\" in *LoadState*) printf 'loaded\\n';; *ActiveState*) printf 'active\\n';; *) printf 'inactive\\n';; esac; }\n";
        var active = publicationScript(foundation + setup + loaded + observation + "\n" + call);
        assertEquals(0, active.exit(), active.output());
        assertTrue(active.output().contains("UNIT_PRESENT=yes\n"), active.output());
        String unavailable = "systemctl() { case \"$*\" in *LoadState*) return 1;; *) printf 'inactive\\n';; esac; }\n";
        var failed = publicationScript(foundation + setup + unavailable + observation + "\n" + call);
        assertNotEquals(0, failed.exit(), failed.output());
        assertTrue(failed.output().contains("publication-unit-load-query"), failed.output());

        var old = temporaryDirectory.resolve("controlled/apps/demo/releases").resolve("d".repeat(64));
        java.nio.file.Files.createDirectories(old);
        java.nio.file.Files.writeString(old.resolve(".windowstolinux-owner"), "b".repeat(64));
        var snapshot = temporaryDirectory.resolve("controlled/snapshots/demo-11111111-1111-4111-8111-111111111111");
        java.nio.file.Files.createDirectories(snapshot);
        java.nio.file.Files.writeString(snapshot.resolve(".deployment-task"), "11111111-1111-4111-8111-111111111111");
        java.nio.file.Files.writeString(snapshot.resolve("current-path"), old.toString().replace('\\', '/'));
        java.nio.file.Files.writeString(snapshot.resolve("runtime"), "inactive");
        java.nio.file.Files.writeString(snapshot.resolve("kind"), "deployment");
        java.nio.file.Files.writeString(snapshot.resolve(".deployment-old-owner"), "b".repeat(64));
        var original = publicationScript(foundation + setup + inactive + observation + "\n" + call);
        assertEquals(0, original.exit(), original.output());
        assertTrue(
            original.output().contains(
                "SNAPSHOT=11111111-1111-4111-8111-111111111111\nSNAPSHOT_OLD=" + "d".repeat(64) + "\n"),
            original.output());
        java.nio.file.Files.delete(snapshot.resolve(".deployment-old-owner"));
        var missingOwner = publicationScript(foundation + setup + inactive + observation + "\n" + call);
        assertNotEquals(0, missingOwner.exit(), missingOwner.output());
        java.nio.file.Files.writeString(snapshot.resolve(".deployment-old-owner"), "b".repeat(64));
        java.nio.file.Files.delete(old.resolve(".windowstolinux-owner"));
        java.nio.file.Files.delete(old);
        var retained = publicationScript(foundation + setup + inactive + observation + "\n" + call);
        assertEquals(0, retained.exit(), retained.output());
        assertTrue(retained.output().contains("SNAPSHOT_OLD=" + "d".repeat(64) + "\n"), retained.output());
        var discard = publicationScript(foundation + setup + inactive + observation
            + "\napplication_maintenance() { :; }\n" + "discard_original_snapshot demo " + "b".repeat(64)
            + " 11111111-1111-4111-8111-111111111111\n");
        assertNotEquals(0, discard.exit(), discard.output());
        java.nio.file.Files.writeString(snapshot.resolve(".deployment-old-owner"), "c".repeat(64));
        var wrongOwner = publicationScript(foundation + setup + inactive + observation + "\n" + call);
        assertNotEquals(0, wrongOwner.exit(), wrongOwner.output());
        assertTrue(wrongOwner.output().contains("publication-snapshot-owner"), wrongOwner.output());
        java.nio.file.Files.writeString(snapshot.resolve(".deployment-old-owner"), "b".repeat(64));
        java.nio.file.Files.writeString(snapshot.resolve(".deployment-task"), "22222222-2222-4222-8222-222222222222");
        var foreign = publicationScript(foundation + setup + inactive + observation + "\n" + call);
        assertNotEquals(0, foreign.exit(), foreign.output());
        assertTrue(foreign.output().contains("publication-snapshot-foreign"), foreign.output());
    }

    @Test
    void refusesLegacyRuntimeIdentityAndUnboundToolchainBeforeEffects() throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String functions = helper.substring(0, helper.lastIndexOf("\nrequire_root\n"));
        String parser = functions + "\nparse_application_payload() { application_payload=\"$1\"; }\n";
        String digest = "a".repeat(64);
        for (String arguments : java.util.List.of("old-format SYSTEMD_STATIC reviewed " + digest + " 0",
            "identity-v2 LEGACY_UNSPECIFIED reviewed " + digest + " 0")) {
            var rejected = publicationScript(parser + "parse_deployment_inputs " + arguments + "\nprintf 'EFFECT=1\\n'\n");
            assertNotEquals(0, rejected.exit(), rejected.output());
            assertFalse(rejected.output().contains("EFFECT=1"), rejected.output());
        }
        for (String policy : java.util.List.of("SYSTEMD_STATIC", "CONTAINER_NON_ROOT")) {
            var accepted = publicationScript(parser + "parse_deployment_inputs identity-v2 " + policy
                + " reviewed " + digest + " 0\nprintf '%s:%s\\n' \"$runtime_identity_policy\" \"$deployment_configuration_digest\"\n");
            assertEquals(0, accepted.exit(), accepted.output());
            assertEquals(policy + ":" + digest + "\n", accepted.output());
        }
        var unbound = publicationScript(functions + "\ntoolchain_binding=''\nrequire_bound_version JAVA 21\nprintf 'EFFECT=1\\n'\n");
        assertNotEquals(0, unbound.exit(), unbound.output());
        assertTrue(unbound.output().contains("runtime-toolchain-missing"), unbound.output());
        assertFalse(unbound.output().contains("EFFECT=1"), unbound.output());
    }

    @Test
    void refusesWritableBuildEntryBeforeCandidateOrServiceEffects() throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String functions = helper.substring(0, helper.lastIndexOf("\nrequire_root\n"));
        String setup = """
            require_app() { :; }
            require_candidate() { :; }
            candidate_root() { printf '/fixture/candidate'; }
            build_unit_name() { printf 'fixture-build'; }
            build_identity_name() { printf 'fixture-user'; }
            assert_root_owned_regular() { :; }
            sha256sum() { printf '%s\\n' "$build_entry_sha256"; }
            find() { if [ "$writable" = yes ]; then printf '%s\\n' "$helper_directory/build-entry"; fi; }
            assert_candidate_for_deployer() { printf 'CANDIDATE_CHECK=1\\n'; exit 0; }
            """;
        for (String writable : java.util.List.of("no", "yes")) {
            var result = publicationScript(functions + "\n" + setup + "writable=" + writable
                + "\nrun_restricted_build demo fixture 60 16 256 8192 ordinary " + "a".repeat(64) + " -\n");
            if (writable.equals("yes")) {
                assertNotEquals(0, result.exit(), result.output());
                assertTrue(result.output().contains("build-entry-writable"), result.output());
                assertFalse(result.output().contains("CANDIDATE_CHECK=1"), result.output());
            } else {
                assertEquals(0, result.exit(), result.output());
                assertTrue(result.output().contains("CANDIDATE_CHECK=1"), result.output());
            }
        }
    }

    @Test
    void verifiesTheSelectedBindingBeforeCandidateOrServiceEffects() throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String functions = helper.substring(0, helper.lastIndexOf("\nrequire_root\n"));
        String payload = "WTL-TOOLS-1\tfixture\n";
        String encoded = java.util.Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        String setup = """
            require_app() { :; }
            require_candidate() { :; }
            parse_toolchain_binding() {
                [ "$#" = 3 ] && [ "$1" = tools-v1 ] && [ "$2" = '%s' ] && [ "$3" = build ] || reject wrong-build-binding;
                printf 'TOOLCHAIN_CHECK=1\\n'
                [ "$marker" = present ] || reject toolchain-binding-changed
            }
            candidate_root() { printf '/fixture/candidate'; }
            build_unit_name() { printf 'fixture-build'; }
            build_identity_name() { printf 'fixture-user'; }
            assert_root_owned_regular() { :; }
            sha256sum() { if [ "$#" = 0 ]; then command sha256sum; else printf '%%s\\n' "$build_entry_sha256"; fi; }
            find() { :; }
            assert_candidate_for_deployer() { printf 'CANDIDATE_CHECK=1\\n'; exit 0; }
            """.formatted(digest);
        for (String marker : java.util.List.of("present", "missing")) {
            var result = publicationScript(functions + "\n" + setup + "marker=" + marker
                + "\nrun_restricted_build demo fixture 60 16 256 8192 ordinary " + "a".repeat(64) + " '" + encoded + "'\n");
            assertTrue(result.output().contains("TOOLCHAIN_CHECK=1"), result.output());
            if (marker.equals("missing")) {
                assertNotEquals(0, result.exit(), result.output());
                assertTrue(result.output().contains("toolchain-binding-changed"), result.output());
                assertFalse(result.output().contains("CANDIDATE_CHECK=1"), result.output());
            } else {
                assertEquals(0, result.exit(), result.output());
                assertTrue(result.output().contains("CANDIDATE_CHECK=1"), result.output());
            }
        }
    }

    @Test
    void rejectsChangedBuildScriptBeforeMountOrService(@org.junit.jupiter.api.io.TempDir java.nio.file.Path fixture) throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String functions = helper.substring(0, helper.lastIndexOf("\nrequire_root\n"));
        java.nio.file.Files.writeString(fixture.resolve("build-entry"), DeploymentManagedHelperBundle.renderBuildEntry());
        String setup = """
            helper_directory='%s'
            require_app() { :; }
            require_candidate() { :; }
            candidate_root() { printf '%%s' "$helper_directory"; }
            build_unit_name() { printf 'fixture-build'; }
            build_identity_name() { printf 'fixture-user'; }
            assert_root_owned_regular() { :; }
            assert_candidate_for_deployer() { :; }
            assert_workspace_volume() { :; }
            find() { :; }
            systemctl() { printf not-found; }
            mount() { printf 'MOUNT_EFFECT=1\\n'; exit 91; }
            monitor_build_output() { printf 'SERVICE_EFFECT=1\\n'; exit 92; }
            run_restricted_build demo fixture 60 16 256 8192 ordinary %s - <<'PAYLOAD'
            changed-script
            PAYLOAD
            """.formatted(fixture.toString().replace('\\', '/'), "0".repeat(64));
        var rejected = publicationScript(functions + "\n" + setup);
        assertNotEquals(0, rejected.exit(), rejected.output());
        assertTrue(rejected.output().contains("build-script-identity"), rejected.output());
        assertFalse(rejected.output().contains("MOUNT_EFFECT=1"), rejected.output());
        assertFalse(rejected.output().contains("SERVICE_EFFECT=1"), rejected.output());
    }

    @Test
    void refusesLegacyGradleRuntimeBeforeFilesystemOrServiceEffects() throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String functions = helper.substring(0, helper.lastIndexOf("\nrequire_root\n"));
        String setup = "\nparse_deployment_inputs() { deployment_remaining_arguments=(\"$@\"); }\n"
            + "parse_managed_data_bindings() { managed_data_remaining_arguments=(\"$@\"); }\n"
            + "parse_toolchain_binding() { toolchain_remaining_arguments=(\"$@\"); }\n";
        var rejected = publicationScript(functions + setup + "render_deployment_unit demo gradle\nprintf 'EFFECT=1\\n'\n");
        assertNotEquals(0, rejected.exit(), rejected.output());
        assertTrue(rejected.output().contains("runtime-kind"), rejected.output());
        assertFalse(rejected.output().contains("EFFECT=1"), rejected.output());
    }

    @Test
    void restoresOnlyAnUnlinkedOrdinarySourceArchiveBeforeFreezing() throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String functions = helper.substring(0, helper.lastIndexOf("\nrequire_root\n"));
        String setup = """
            require_app() { :; }
            require_candidate() { :; }
            candidate_root() { printf '/fixture/candidate'; }
            assert_candidate_for_deployer() { :; }
            assert_workspace_volume() { :; }
            findmnt() { printf 'rw'; }
            test() {
              case "$1" in '!') shift; ! test "$@"; return ;; esac
              case "$*" in
                '-f /fixture/candidate/.build-script') return 0 ;;
                '-f /fixture/candidate/mutable/source.tar.gz') return 0 ;;
                '-L /fixture/candidate/mutable/source.tar.gz') [ "$scenario" = symlink ]; return ;;
                '-e /fixture/candidate/.agent-mode') [ "$scenario" = agent ]; return ;;
                *) builtin test "$@" ;;
              esac
            }
            # Bash bracket syntax has its own command, so route it to the same controlled filesystem.
            function [() { test "${@:1:$#-1}"; }
            stat() { if [ "$scenario" = hardlink ]; then printf '2'; else printf '1'; fi; }
            chown() { printf 'CHOWN=%s\\n' "$*"; }
            chmod() { printf 'CHMOD=%s\\n' "$*"; }
            mount() { printf 'FROZEN=1\\n'; }
            """;
        for (String scenario : java.util.List.of("ordinary", "agent", "symlink", "hardlink")) {
            var observed = publicationScript(functions + "\n" + setup + "scenario=" + scenario
                + "\nfreeze_candidate_build demo fixture\n");
            assertEquals(0, observed.exit(), observed.output());
            boolean restored = observed.output().contains("CHOWN=root:root -- /fixture/candidate/mutable/source.tar.gz\n");
            assertEquals(scenario.equals("ordinary"), restored, observed.output());
            if (restored) {
                assertTrue(observed.output().contains("CHMOD=400 -- /fixture/candidate/mutable/source.tar.gz\n"), observed.output());
                assertTrue(observed.output().indexOf("/mutable/source.tar.gz") < observed.output().indexOf("FROZEN=1"), observed.output());
            }
        }
    }

    @Test
    void publicationRecoveryGuardRejectsObservedIdentityDriftBeforeRollbackAndDelete(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        for (String scenario : java.util.List.of("current", "owner", "task", "unit")) {
            var fixture = temporary.resolve(scenario);
            prepareRecoveryGuardFixture(fixture, scenario);
            var result = publicationScript(recoveryGuardScript(fixture));
            assertNotEquals(0, result.exit(), result.output());
            String rejection = switch (scenario) {
                case "current" -> "recovery-current-drift";
                case "owner" -> "current-owner";
                case "task" -> "recovery-candidate-task";
                default -> "unit-digest";
            };
            assertTrue(result.output().contains(rejection), result.output());
            String trace = java.nio.file.Files.readString(fixture.resolve("trace"));
            assertEquals(0, trace.lines().filter(line -> line.matches("\\++ rollback_deployment_first .*")).count(), trace);
            assertEquals("", java.nio.file.Files.readString(fixture.resolve("effects")));
            assertTrue(java.nio.file.Files.exists(fixture.resolve("apps/backend/current")));
            assertTrue(java.nio.file.Files.exists(fixture.resolve("units/backend.service")));
            assertTrue(java.nio.file.Files.exists(fixture.resolve("apps/backend/releases/" + "b".repeat(64))));
            assertTrue(java.nio.file.Files.exists(fixture.resolve("state/application-locks/backend/maintenance")));
        }
    }

    @Test
    void publicationRecoveryGuardRunsOriginalRollbackAndRemovesOnlyOwnedMarkerUnderHeldDescriptors(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path fixture) throws Exception {
        prepareRecoveryGuardFixture(fixture, "expected");
        var result = publicationScript(recoveryGuardScript(fixture));
        assertEquals(0, result.exit(), result.output());
        assertEquals("ROLLED_BACK=1\n", result.output());
        String trace = java.nio.file.Files.readString(fixture.resolve("trace"));
        assertEquals(1, trace.lines().filter(line -> line.matches("\\++ rollback_deployment_first .*")).count(), trace);
        var effects = java.nio.file.Files.readAllLines(fixture.resolve("effects"));
        String root = fixture.toString().replace('\\', '/');
        var deletes = effects.stream().filter(line -> line.startsWith("DELETE=")).toList();
        assertEquals(java.util.List.of("DELETE=" + root + "/units/backend.service", "DELETE=" + root + "/apps/backend/current",
            "DELETE=" + root + "/apps/backend/releases/" + "b".repeat(64), "DELETE=" + root + "/state/application-locks/backend/maintenance"), deletes);
        assertEquals(1, effects.stream().filter(line -> line.equals("LOCK_HELD_AT_STOP=1")).count());
        assertEquals(4, effects.stream().filter(line -> line.equals("LOCK_HELD_AT_DELETE=1")).count());
        assertFalse(java.nio.file.Files.exists(fixture.resolve("apps/backend/current")));
        assertFalse(java.nio.file.Files.exists(fixture.resolve("units/backend.service")));
        assertFalse(java.nio.file.Files.exists(fixture.resolve("apps/backend/releases/" + "b".repeat(64))));
        assertFalse(java.nio.file.Files.exists(fixture.resolve("state/application-locks/backend/maintenance")));
        assertEquals("foreign-marker", java.nio.file.Files.readString(fixture.resolve("state/application-locks/backend/foreign-maintenance")));
        assertEquals("retained-data", java.nio.file.Files.readString(fixture.resolve("apps/backend/persistent-data")));
        assertTrue(java.nio.file.Files.exists(fixture.resolve("state/work/backend-0123456789abcdef/.agent-task")));
    }

    private static void prepareRecoveryGuardFixture(java.nio.file.Path fixture, String scenario) throws Exception {
        String task = "072bc074-4bce-4c14-b425-f2ff965c7d40", release = "b".repeat(64);
        for (String directory : java.util.List.of("apps/backend/releases/" + release, "state/work/backend-0123456789abcdef",
            "state/application-locks/backend", "state/snapshots", "units"))
            java.nio.file.Files.createDirectories(fixture.resolve(directory));
        String root = fixture.toString().replace('\\', '/');
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/current"), root + "/apps/backend/releases/" + (scenario.equals("current") ? "d".repeat(64) : release));
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/releases/" + release + "/.windowstolinux-owner"), scenario.equals("owner") ? "d".repeat(64) : "a".repeat(64));
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/releases/" + release + "/.windowstolinux-deployment-parameters"), "fixture-native-daemon\n");
        java.nio.file.Files.writeString(fixture.resolve("state/work/backend-0123456789abcdef/.agent-task"), scenario.equals("task") ? "11111111-1111-4111-8111-111111111111" : task);
        java.nio.file.Files.writeString(fixture.resolve("state/work/backend-0123456789abcdef/.deployer"), "root");
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/admission"), "");
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/run"), "");
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/maintenance"), "deployment-" + release);
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/foreign-maintenance"), "foreign-marker");
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/persistent-data"), "retained-data");
        java.nio.file.Files.writeString(fixture.resolve("units/backend.service"), scenario.equals("unit") ? "changed-unit\n" : "expected-unit\n");
        java.nio.file.Files.writeString(fixture.resolve("expected-unit"), "expected-unit\n");
        java.nio.file.Files.writeString(fixture.resolve("effects"), "");
    }

    /** Executes the complete original guard with real old current/owner/unit checks and old first rollback.
     * Root/owner/stat, systemd/flock and Windows CURRENT symlink representation are explicit test boundaries. */
    private static String recoveryGuardScript(java.nio.file.Path fixture) throws Exception {
        String resources = "/gold/debug/windowstolinux/mod/deployment/linux/helper/";
        StringBuilder script = new StringBuilder();
        for (String name : java.util.List.of("00-protocol-foundation.sh", "runtime/41-application-admission.sh",
            "runtime/40-typed-runtime.sh", "runtime/systemd/61-service-identity.sh")) {
            try (var input = DeploymentManagedHelperBundleTest.class.getResourceAsStream(resources + name)) {
                script.append(new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")).append('\n');
            }
        }
        script.append("""
            fixture='@FIXTURE@'
            base_root="$fixture/state"
            work_root="$base_root/work"
            snapshots_root="$base_root/snapshots"
            applications_root="$fixture/apps"
            lock_directory="$base_root/application-locks/backend"
            lock_calls=0
            require_root() { :; }
            require_deployer() { deployer=root; }
            assert_storage_parent() { :; }
            assert_root_owned_directory() { [[ -d "$1" && ! -L "$1" ]] || reject fixture-directory; }
            assert_root_owned_regular() { [[ -f "$1" && ! -L "$1" ]] || reject fixture-regular; }
            unit_path() { printf '%s/units/backend.service' "$fixture"; }
            load_deployment_parameters() { assert_root_owned_regular "$1"; application_mode=DAEMON; deployment_runtime_parameters=(fixture-native-daemon); }
            expected_deployment_unit_digest() { command sha256sum -- "$fixture/expected-unit" | command awk '{print $1}'; }
            assert_managed_data_links() { :; }
            # The Windows runner needs no symlink privilege: only CURRENT's representation is virtualized.
            function [() {
                if [[ "$#" = 3 && "$1" = -L && "$2" = "$applications_root/backend/current" ]]; then
                    builtin [ -f "$2" ]; return
                fi
                builtin [ "$@"
            }
            readlink() {
                local target="${!#}"
                [[ "$target" = "$applications_root/backend/current" ]] || return 91
                command cat -- "$target"
            }
            stat() {
                case "$2" in
                    %h) printf '1' ;;
                    %a) if [[ "${!#}" = "$lock_directory" ]]; then printf '700'; else printf '600'; fi ;;
                    *) command stat "$@" ;;
                esac
            }
            install() {
                [[ "$*" = "-d -o root -g root -m 700 -- $lock_directory" && -d "$lock_directory" ]] || return 92
            }
            flock() {
                [[ "$1" = -w && "$3" = -x ]] || return 93
                lock_calls=$((lock_calls + 1))
            }
            fixture_locked() {
                [[ "$lock_calls" = 2 ]] || exit 94
                : >&"$application_admission_fd"
                : >&"$application_run_fd"
            }
            systemctl() {
                case "$*" in
                    'show --value --property FragmentPath windowstolinux-backend.service') unit_path backend ;;
                    'show --value --property DropInPaths windowstolinux-backend.service') : ;;
                    'show --value --property LoadState windowstolinux-backend.service') printf 'loaded' ;;
                    'show --value --property ActiveState '* ) printf 'inactive' ;;
                    'show --value --property MainPID '* ) printf '0' ;;
                    'show --value --property ControlGroup '* ) : ;;
                    'is-active --quiet windowstolinux-backend.service') return 1 ;;
                    'stop windowstolinux-backend.service') fixture_locked; printf 'LOCK_HELD_AT_STOP=1\n' >>"$fixture/effects" ;;
                    daemon-reload) fixture_locked; printf 'RELOAD=1\n' >>"$fixture/effects" ;;
                    *) printf 'UNEXPECTED_SYSTEMCTL=%s\n' "$*" >&2; return 95 ;;
                esac
            }
            rm() {
                local target="${!#}"
                case "$target" in
                    "$fixture/units/backend.service" | "$fixture/apps/backend/current" | "$fixture/apps/backend/releases/@RELEASE@" | "$lock_directory/maintenance") ;;
                    *) printf 'UNEXPECTED_DELETE=%s\n' "$target" >&2; exit 96 ;;
                esac
                fixture_locked
                printf 'LOCK_HELD_AT_DELETE=1\nDELETE=%s\n' "$target" >>"$fixture/effects"
                command rm "$@"
            }
            exec 9>"$fixture/trace"
            BASH_XTRACEFD=9
            PS4='+ '
            set -x
            set -- guarded-first-rollback 072bc074-4bce-4c14-b425-f2ff965c7d40 aaaaaaaabbbb4ccc8dddeeeeeeeeeeee 45e3a9a4-a6b8-403a-89f5-605e11eeeca0 backend @OWNER@ backend-0123456789abcdef @RELEASE@ @PLAN@
            """.replace("@FIXTURE@", fixture.toString().replace('\\', '/').replace("'", "'\\''"))
            .replace("@OWNER@", "a".repeat(64)).replace("@RELEASE@", "b".repeat(64)).replace("@PLAN@", "c".repeat(64)));
        try (var input = DeploymentManagedHelperBundleTest.class.getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/publication-recovery-guard.sh")) {
            script.append(new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        return script.toString();
    }

    /** Runs one actual bundled Bash function without allowing a test process to outlive the assertion. / 实际运行一项打包Bash函数，不让测试进程越过断言。
     * @param script complete local fixture / 完整本机夹具
     * @return process exit and complete output / 进程退出码与完整输出
     * @throws Exception if the local interpreter cannot start / 本机解释器无法启动时 */
    private static PublishScriptResult publicationScript(String script) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            return new PublishScriptResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    /** Complete local Bash observation. / 完整本机Bash观测。
     * @param exit actual process exit / 实际进程退出
     * @param output complete output / 完整输出 */
    private record PublishScriptResult(int exit, String output) {
    }

}
