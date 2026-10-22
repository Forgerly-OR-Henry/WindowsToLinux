package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentObservationBranchEvidenceTest {
    private static final int PINNED_PROTOCOL = 16;

    private static final String PINNED_SHA256 = "bb1ecc2ae54d46263ef1eab31b40fd8967a7acf0a7a86c463b80eeaf7b217efd";

    @TempDir
    Path temporary;

    @Test
    void nativeLoadingSourceReachesVerifyWithAnExistingReadOnlySharedPreparationLock() {
        String script = pinnedScript();
        String loader = selected(script, "load_deployment_parameters").text();
        String renderer = selected(script, "render_deployment_unit").text();
        String binding = function(script, "parse_toolchain_binding", "require_bound_version").text();
        String toolchains = between(script, "prepare_official_toolchains() {", "WTL_OFFICIAL_TOOLCHAINS");
        assertTrue(loader.contains("render_deployment_unit \"$current_application\" \"${deployment_runtime_parameters[@]}\" >/dev/null"));
        assertTrue(renderer.contains("parse_toolchain_binding \"${managed_data_remaining_arguments[@]}\""));
        assertTrue(binding.contains("prepare_official_toolchains verify \"$toolchain_binding\" || reject toolchain-binding-changed"));
        assertTrue(toolchains.contains("verifying = bool(args and args[0] == 'verify')"));
        assertTrue(toolchains.contains("lock_path = ROOT / '.prepare.lock'"));
        assertTrue(toolchains.contains("fail('ownership', 'preparation lock must already exist and be root-owned')"));
        assertTrue(toolchains.contains("with lock_path.open('r' if verifying else 'a') as lock:"));
        assertTrue(toolchains.contains("fcntl.flock(lock, (fcntl.LOCK_SH if verifying else fcntl.LOCK_EX) | fcntl.LOCK_NB)"));
        assertTrue(toolchains.contains("hashlib.sha256(payload).hexdigest() != args[1]"));
        assertTrue(toolchains.contains("toolchain completion marker differs from the immutable binding"));
        assertTrue(toolchains.contains("verify_system(record)"));
        assertTrue(toolchains.contains("hashlib.sha256(file.read_bytes()).hexdigest() != f['sha256']"));
        // Source identity and call-path evidence do not execute Linux fcntl or the complete binding parser.
    }

    @ParameterizedTest
    @ValueSource(strings = {"publication-original-status", "inspect-runtime", "observe-deployment"})
    void nativeObservationQueriesProduceReceiptsWithoutHealthAdmissionMaintenanceOrCleanup(String verb) throws Exception {
        var result = executeBranch(verb, "VALID");
        assertEquals(0, result.exit(), result.output() + result.trace());
        assertTrue(result.trace().contains("BEFORE_DISPATCH:OUTPUT_EMPTY\n"));
        assertTrue(result.trace().contains("FIXTURE:TOOLCHAIN_VERIFY:verify:"));
        assertFalse(result.trace().contains("FORBIDDEN:"), result.trace());
        assertFalse(result.output().contains("HEALTHY="));
        if (verb.equals("publication-original-status")) {
            assertTrue(result.output().contains("PUBLICATION_STATUS=1\nCURRENT=" + "a".repeat(64) + "\n"));
            assertTrue(result.output().contains("MAINTENANCE=deployment-" + "b".repeat(64) + "\n"));
            assertFalse(result.output().contains("CURRENT=" + "b".repeat(64) + "\n"));
            assertTrue(result.output().contains("CURRENT_KIND=deployment\nCURRENT_MODE=DAEMON\n"));
            assertFalse(result.trace().contains("--property FragmentPath"));
            assertFalse(result.trace().contains("--property DropInPaths"));
            assertFalse(result.trace().contains("FIXTURE:DATA_LINKS\n"));
        } else {
            assertTrue(result.trace().contains("--property FragmentPath"));
            assertTrue(result.trace().contains("--property DropInPaths"));
            assertTrue(result.trace().contains("FIXTURE:DATA_LINKS\n"));
            if (verb.equals("inspect-runtime")) {
                assertEquals("KIND=deployment\nMODE=DAEMON\n", result.output());
            } else {
                assertTrue(result.output().startsWith("OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\n"));
                assertTrue(result.output().contains("ActiveState=active\n"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "publication-original-status,MISSING_OWNER,controlled-file,false",
        "inspect-runtime,MISSING_CURRENT,lifecycle-unmanaged,false",
        "observe-deployment,MISSING_UNIT,controlled-file,true",
        "observe-deployment,UNIT_DIGEST,unit-digest,true",
        "inspect-runtime,FOREIGN_DROPIN,unit-dropins,true",
        "observe-deployment,VERIFY_FAIL,toolchain-binding-changed,true",
        "inspect-runtime,PARAMETER_DRIFT,fixture-renderer-parameters,false"
    })
    void missingOrChangedExecutionFactsRejectBeforeObservationReceipts(String verb, String defect, String reason,
                                                                       boolean reachesVerify) throws Exception {
        var result = executeBranch(verb, defect);
        assertEquals(64, result.exit(), result.output() + result.trace());
        assertTrue(result.output().contains("MANAGED_HELPER_REJECT=" + reason + "\n"), result.output());
        assertTrue(result.trace().contains("BEFORE_DISPATCH:OUTPUT_EMPTY\n"));
        assertEquals(reachesVerify, result.trace().contains("FIXTURE:TOOLCHAIN_VERIFY:verify:"), result.trace());
        assertFalse(result.trace().contains("FORBIDDEN:"), result.trace());
        for (String receipt : List.of("PUBLICATION_STATUS=1", "KIND=deployment", "OWNER=1", "QUERY_OK=1", "HEALTHY=1"))
            assertFalse(result.output().contains(receipt), result.output());
    }

    private static String pinnedScript() {
        String script = DeploymentManagedHelperBundle.renderScript();
        assertEquals(PINNED_PROTOCOL, DeploymentManagedHelperBundle.PROTOCOL_VERSION);
        assertEquals(PINNED_SHA256, DeploymentManagedHelperBundle.EXPECTED_SHA256);
        assertEquals(PINNED_SHA256, DeploymentAgentInputs.hash(script));
        assertTrue(script.contains("\nhelper_protocol=" + PINNED_PROTOCOL + "\n"));
        return script;
    }

    @SuppressWarnings("unchecked")
    private static DeploymentAgentHelperSourceEvidence.Span selected(String script, String symbol) {
        var spans = (List<DeploymentAgentHelperSourceEvidence.Span>) DeploymentAgentHelperSourceEvidence.fromScript(script).get("spans");
        var span = spans.stream().filter(value -> value.symbol().equals(symbol)).findFirst().orElseThrow();
        assertEquals("COMPLETE_FUNCTION", span.scope());
        assertEquals(DeploymentAgentInputs.hash(span.text()), span.textSha256());
        return span;
    }

    private static SourceRange function(String script, String symbol, String next) {
        String text = between(script, symbol + "() {", next + "() {");
        assertTrue(text.stripTrailing().endsWith("}"), symbol);
        return new SourceRange(text, DeploymentAgentInputs.hash(text));
    }

    private static String between(String script, String startAnchor, String endAnchor) {
        String[] lines = script.split("\n", -1);
        int start = uniqueLine(lines, startAnchor);
        int end = uniqueLine(lines, endAnchor);
        assertTrue(start < end, startAnchor);
        return String.join("\n", Arrays.copyOfRange(lines, start, end)) + "\n";
    }

    private static int uniqueLine(String[] lines, String anchor) {
        int found = -1;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].equals(anchor)) continue;
            assertEquals(-1, found, "Duplicate assembled source anchor: " + anchor);
            found = index;
        }
        assertTrue(found >= 0, "Missing assembled source anchor: " + anchor);
        return found;
    }

    private static String assembledBranchFunctions(String script) {
        var source = new StringBuilder();
        var spans = DeploymentAgentHelperSourceEvidence.extractRanges(script);
        for (String symbol : List.of("reject", "require_app", "require_candidate", "require_digest", "require_snapshot_token",
            "app_root", "unit_name", "candidate_root", "snapshot_root", "assert_not_writable_by_others",
            "assert_root_owned_directory", "assert_root_owned_regular", "assert_candidate_for_deployer",
            "expected_deployment_unit_digest", "load_deployment_parameters", "assert_deployment_current_or_empty",
            "publication_original_status_owned", "build_unit_name"))
            source.append(spans.stream().filter(span -> span.symbol().equals(symbol)).findFirst().orElseThrow().text());
        for (var entry : List.of(
            Map.entry("publication_original_status", "publication_original_status_attempt"),
            Map.entry("observe_deployment", "retain_releases"),
            Map.entry("inspect_managed_runtime", "render_systemd_manager_isolation"),
            Map.entry("observe_application_unit", "stop_requested_application_unit"))) {
            var range = function(script, entry.getKey(), entry.getValue());
            assertEquals(DeploymentAgentInputs.hash(range.text()), range.sha256());
            source.append(range.text());
        }
        return source.toString();
    }

    private BranchResult executeBranch(String verb, String scenario) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assertTrue(Files.isExecutable(Path.of(bash)), "A real Bash binary is required for controlled observation branches");
        String source = pinnedScript();
        String[] lines = source.split("\n", -1);
        int dispatchStart = uniqueLine(lines, "require_root");
        String dispatch = String.join("\n", Arrays.copyOfRange(lines, dispatchStart, lines.length));
        String script = "set -euo pipefail\nPATH=/usr/bin:/bin\n" + assembledBranchFunctions(source) + fixture() + dispatch;
        Path output = temporary.resolve("execution-output.txt");
        var builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").directory(temporary.toFile())
            .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("BASH_ENV");
        builder.environment().remove("ENV");
        builder.environment().put("MSYS", "winsymlinks:lnk");
        builder.environment().put("FIXTURE_VERB", verb);
        builder.environment().put("FIXTURE_SCENARIO", scenario);
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Controlled observation branch did not exit");
            return new BranchResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                Files.readString(temporary.resolve("branch-trace.txt"), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Controlled observation process did not stop");
            }
            // Git Bash's real MSYS symlink is a read-only .lnk file; permit JUnit to remove this fixture after execution.
            Path link = temporary.resolve("fixture/apps/controlled-backend/current.lnk");
            if (Files.exists(link)) {
                var attributes = Files.getFileAttributeView(link, DosFileAttributeView.class);
                if (attributes != null) attributes.setReadOnly(false);
            }
        }
    }

    private static String fixture() {
        // Explicit substitutions: root/deployer checks, stat/find ownership-mode facts, unit/quadlet paths,
        // native renderer/payload/toolchain environment, managed-data link checks and systemd replies.
        // Actual selected functions, disk existence/symlink/owner-content checks, loader, unit digest and dispatch run unchanged.
        return """
            trace=branch-trace.txt
            : >"$trace"
            fixture_app=controlled-backend
            fixture_manifest=$(printf 'c%.0s' {1..64})
            fixture_current=$(printf 'a%.0s' {1..64})
            fixture_expected=$(printf 'b%.0s' {1..64})
            fixture_binding=$(printf 'd%.0s' {1..64})
            fixture_task=00000000-0000-0000-0000-000000000001
            fixture_candidate="$fixture_app-0000000000000001"
            fixture_root="$PWD/fixture"
            applications_root="$fixture_root/apps"
            base_root="$fixture_root/state"
            work_root="$base_root/work"
            snapshots_root="$base_root/snapshots"
            fixture_unit="$fixture_root/units/$fixture_app.service"
            fixture_unit_name="windowstolinux-$fixture_app.service"
            fixture_release="$applications_root/$fixture_app/releases/$fixture_current"
            fixture_parameters=(identity-v2 SYSTEMD_STATIC CONTROLLED_PAYLOAD "$fixture_manifest" 0 "$fixture_app" "$fixture_app" 0 tools-v1 "$fixture_binding" process)
            mkdir -p -- "$fixture_release/source" "${fixture_unit%/*}" "$base_root/application-locks/$fixture_app"
            printf '%s\\n' "$fixture_manifest" >"$fixture_release/.windowstolinux-owner"
            printf '%s\\n' "${fixture_parameters[@]}" >"$fixture_release/.windowstolinux-deployment-parameters"
            printf 'FIXTURE_NATIVE_UNIT\\n' >"$fixture_unit"
            printf 'deployment-%s\\n' "$fixture_expected" >"$base_root/application-locks/$fixture_app/maintenance"
            ln -s -- "$fixture_release" "$applications_root/$fixture_app/current"
            [ -L "$applications_root/$fixture_app/current" ] || reject fixture-real-symlink-required
            case "$FIXTURE_SCENARIO" in
                MISSING_OWNER) rm -- "$fixture_release/.windowstolinux-owner" ;;
                MISSING_CURRENT) rm -- "$applications_root/$fixture_app/current" ;;
                MISSING_UNIT) rm -- "$fixture_unit" ;;
                UNIT_DIGEST) printf 'FOREIGN_NATIVE_UNIT\\n' >"$fixture_unit" ;;
                PARAMETER_DRIFT)
                    altered=("${fixture_parameters[@]}")
                    altered[9]="$fixture_current"
                    printf '%s\\n' "${altered[@]}" >"$fixture_release/.windowstolinux-deployment-parameters"
                    ;;
                VALID | FOREIGN_DROPIN | VERIFY_FAIL) ;;
                *) reject fixture-scenario ;;
            esac
            forbidden() {
                printf 'FORBIDDEN:%s\\n' "$1" >>"$trace"
                reject "fixture-forbidden-$1"
            }
            require_root() { printf 'FIXTURE:ROOT_BOUNDARY\\n' >>"$trace"; }
            require_deployer() { deployer=root; printf 'FIXTURE:DEPLOYER_BOUNDARY\\n' >>"$trace"; }
            unit_path() { [ "$1" = "$fixture_app" ] || reject fixture-app; printf '%s' "$fixture_unit"; }
            podman_quadlet_path() { [ "$1" = "$fixture_app" ] || reject fixture-app; printf '%s.container' "$fixture_unit"; }
            stat() {
                [ "$#" = 4 ] && [ "$1" = -c ] && [ "$2" = '%u:%g' ] && [ "$3" = -- ] || reject fixture-stat
                case "$4" in "$fixture_root"/*) ;; *) reject fixture-stat-path ;; esac
                printf '0:0\\n'
            }
            find() {
                [ "$#" = 8 ] && [ "$1" = -P ] && [ "$3" = -maxdepth ] && [ "$4" = 0 ] && [ "$5" = -perm ] \
                    && [ "$6" = /022 ] && [ "$7" = -print ] && [ "$8" = -quit ] || reject fixture-find
                case "$2" in "$fixture_root"/*) ;; *) reject fixture-find-path ;; esac
            }
            render_deployment_unit() {
                [ "$#" = 12 ] && [ "$1" = "$fixture_app" ] || reject fixture-renderer-arguments
                shift
                local index=0 argument
                for argument in "$@"; do
                    [ "$argument" = "${fixture_parameters[$index]}" ] || reject fixture-renderer-parameters
                    index=$((index + 1))
                done
                [ "$9" = tools-v1 ] && [ "${10}" = "$fixture_binding" ] || reject fixture-renderer-binding
                printf 'FIXTURE:RENDER_NATIVE_PARAMETERS\\n' >>"$trace"
                prepare_official_toolchains verify "${10}" || reject toolchain-binding-changed
                application_mode=DAEMON
                printf 'FIXTURE_NATIVE_UNIT\\n'
            }
            prepare_official_toolchains() {
                [ "$#" = 2 ] && [ "$1" = verify ] && [ "$2" = "$fixture_binding" ] || forbidden toolchain-prepare-or-drift
                printf 'FIXTURE:TOOLCHAIN_VERIFY:%s:%s\\n' "$1" "$2" >>"$trace"
                [ "$FIXTURE_SCENARIO" != VERIFY_FAIL ] || return 65
            }
            assert_managed_data_links() {
                [ "$1" = "$fixture_release/source" ] || reject fixture-data-links
                printf 'FIXTURE:DATA_LINKS\\n' >>"$trace"
            }
            systemctl() {
                printf 'FIXTURE:SYSTEMCTL:%s\\n' "$*" >>"$trace"
                case "$*" in
                    "show --value --property=ActiveState windowstolinux-build-$fixture_candidate.service") printf 'inactive\\n' ;;
                    "show --value --property=LoadState $fixture_unit_name") printf 'loaded\\n' ;;
                    "show --value --property=ActiveState $fixture_unit_name") printf 'active\\n' ;;
                    "show --value --property FragmentPath $fixture_unit_name") printf '%s\\n' "$fixture_unit" ;;
                    "show --value --property DropInPaths $fixture_unit_name")
                        if [ "$FIXTURE_SCENARIO" = FOREIGN_DROPIN ]; then printf '/fixture/foreign.conf\\n'; fi
                        ;;
                    "is-active --quiet $fixture_unit_name") return 0 ;;
                    "is-enabled $fixture_unit_name") printf 'enabled\\n' ;;
                    "show --property=ActiveState,SubState,Result,ExecMainCode,ExecMainStatus,MainPID $fixture_unit_name")
                        printf 'ActiveState=active\\nSubState=running\\nResult=success\\nExecMainCode=0\\nExecMainStatus=0\\nMainPID=17\\n'
                        ;;
                    "cat $fixture_unit_name") return 1 ;;
                    *) forbidden systemctl-mutation-or-unexpected-query ;;
                esac
            }
            application_health() { forbidden health; }
            application_admit_run() { forbidden admission; }
            application_maintenance() { forbidden maintenance; }
            application_lock_directory() { forbidden health-lock-directory; }
            application_assert_no_orphan() { forbidden orphan-cleanup; }
            application_assert_native_job_stopped() { forbidden native-job-cleanup; }
            application_container_cleanup() { forbidden container-job-cleanup; }
            application_job() { forbidden job-execution; }
            install() { forbidden install; }
            flock() { forbidden flock; }
            chown() { forbidden chown; }
            chmod() { forbidden chmod; }
            rm() { forbidden removal; }
            mkdir() { forbidden mkdir; }
            ln() { forbidden relink; }
            [ ! -s execution-output.txt ] || reject fixture-premature-receipt
            printf 'BEFORE_DISPATCH:OUTPUT_EMPTY\\n' >>"$trace"
            if [ "$FIXTURE_VERB" = publication-original-status ]; then
                set -- "$FIXTURE_VERB" "$fixture_app" "$fixture_manifest" "$fixture_task" "$fixture_candidate" "$fixture_expected" native none
            else
                set -- "$FIXTURE_VERB" "$fixture_app" "$fixture_manifest"
            fi
            """;
    }

    private record SourceRange(String text, String sha256) {
    }

    private record BranchResult(int exit, String output, String trace) {
    }
}
