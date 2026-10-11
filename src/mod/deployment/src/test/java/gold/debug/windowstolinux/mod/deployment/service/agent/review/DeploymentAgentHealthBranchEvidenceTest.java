package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentHealthBranchEvidenceTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"DAEMON_HTTP", "OBSERVED_DAEMON_EXECUTION_ON_DEMAND_HTTP", "HTTP_STALE_CUSTOM",
        "DAEMON_COMMAND", "ON_DEMAND_COMMAND", "UDP_CUSTOM"})
    void actualAssembledHealthBranchHonorsCurrentPayloadAndExecutionMode(String scenario) throws Exception {
        boolean command = scenario.endsWith("_COMMAND");
        boolean udp = scenario.equals("UDP_CUSTOM");
        String mode = scenario.contains("ON_DEMAND") ? "ON_DEMAND" : "DAEMON";
        DeploymentHealthCheck check = command
            ? new DeploymentHealthCheck.Command(new DeploymentCommand("verify.sh", List.of("literal")), "CONTROLLED_PROGRAM", 3)
            : udp ? new DeploymentHealthCheck.Udp(18101, "", "", Optional.of(new DeploymentCommand("probe.sh", List.of("literal-probe"))), 3)
            : new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18101/healthz"), 200, 3);
        var result = executeBranch(mode, scenario.equals("HTTP_STALE_CUSTOM"), check);
        assertTrue(result.trace().contains("OBSERVED_MODE=DAEMON\n"));
        assertTrue(result.trace().contains("FIXTURE:ADMISSION\n"));
        assertTrue(result.trace().contains("FIXTURE:LOAD_CURRENT:" + mode + "\n"));
        assertTrue(result.trace().contains("FIXTURE:INPUTS\n"));
        boolean reject = scenario.equals("OBSERVED_DAEMON_EXECUTION_ON_DEMAND_HTTP");
        assertEquals(reject ? 64 : 0, result.exit(), result.output() + result.trace());
        assertEquals(!reject && !command && !udp, result.trace().contains("FIXTURE:HTTP_CURL\n"));
        assertEquals(command, result.trace().contains("PROGRAM:verify\n"));
        assertEquals(udp, result.trace().contains("PROGRAM:probe\n"));
        if (reject) {
            assertTrue(result.output().contains("MANAGED_HELPER_REJECT=application-installation-verification-mismatch\n"));
            assertFalse(result.output().contains("HEALTHY=1\n"));
            assertFalse(result.trace().contains("FIXTURE:PROCESS_IDENTITY\n"));
        } else {
            assertTrue(result.output().contains("HEALTHY=1\n"));
            assertTrue(result.trace().contains("AFTER_HEALTH\n"));
        }
        if (scenario.equals("HTTP_STALE_CUSTOM"))
            assertTrue(result.trace().contains("FINAL_KIND=HTTP;CUSTOM=1\n"));
    }

    @SuppressWarnings("unchecked")
    private static String assembledBranchFunctions() {
        var spans = (List<DeploymentAgentHelperSourceEvidence.Span>) DeploymentAgentHelperSourceEvidence.facts().get("spans");
        var source = new StringBuilder();
        for (String symbol : List.of("reject", "application_field", "application_number", "application_relative",
            "application_command_fields", "parse_application_health", "parse_application_health_payload",
            "application_health", "application_health_loaded")) {
            var span = spans.stream().filter(value -> value.symbol().equals(symbol)).findFirst().orElseThrow();
            assertEquals("COMPLETE_FUNCTION", span.scope());
            assertEquals(DeploymentAgentInputs.hash(span.text()), span.textSha256());
            source.append(span.text());
        }
        return source.toString();
    }

    private BranchResult executeBranch(String mode, boolean staleCustom, DeploymentHealthCheck check) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assertTrue(Files.isExecutable(Path.of(bash)), "A real Bash binary is required for the controlled branch test");
        // Only the selected assembled control flow is real; admission/load/inputs, OS probes and program execution are observable fixtures.
        String fixture = """
            trace=branch-trace.txt
            printf 'OBSERVED_MODE=DAEMON\\n' >"$trace"
            fixture_mode=%s
            fixture_stale_custom=%s
            application_admit_run() {
                [ "$1" = controlled-backend ] || reject fixture-application
                printf 'FIXTURE:ADMISSION\\n' >>"$trace"
            }
            application_load_current() {
                [ "$1" = controlled-backend ] && [ "$2" = controlled-manifest ] || reject fixture-binding
                printf 'FIXTURE:LOAD_CURRENT:%%s\\n' "$fixture_mode" >>"$trace"
                application_mode="$fixture_mode"
                application_backend=native
                current_application="$1"
                application_verify_entry=verify.sh
                application_verify_args=(literal)
                application_expected_output=CONTROLLED_PROGRAM
                application_health_kind=COMMAND
                application_health_custom="$fixture_stale_custom"
            }
            application_assert_inputs() {
                printf 'FIXTURE:INPUTS\\n' >>"$trace"
            }
            application_process_identity() {
                printf 'FIXTURE:PROCESS_IDENTITY\\n' >>"$trace"
                printf 'controlled-pid:controlled-start'
            }
            application_udp_owned() {
                printf 'FIXTURE:LISTENER_OWNERSHIP\\n' >>"$trace"
            }
            application_tcp_response() { reject fixture-unexpected-tcp; }
            application_udp_response() { reject fixture-unexpected-default-udp; }
            python3() {
                # These explicitly substituted OS validations are not asserted as real parser/network evidence.
                case "${3:-}" in
                    *urllib.parse*)
                        printf 'FIXTURE:URL_PARSER\\n' >>"$trace"
                        printf '18101\\n'
                        ;;
                    *p=sys.argv*) printf 'FIXTURE:RELATIVE_VALIDATION\\n' >>"$trace" ;;
                    *) reject fixture-unexpected-python ;;
                esac
            }
            curl() {
                printf 'FIXTURE:HTTP_CURL\\n' >>"$trace"
                printf '200'
            }
            run_application_native() {
                [ "$1" = controlled-backend ] || reject fixture-program-application
                case "$2" in
                    verify)
                        [ "$application_verify_entry" = verify.sh ] && [ "${application_verify_args[*]}" = literal ] || reject fixture-verification
                        ;;
                    probe)
                        [ "$application_health_entry" = probe.sh ] && [ "${application_health_args[*]}" = literal-probe ] || reject fixture-probe
                        ;;
                    *) reject fixture-unexpected-program ;;
                esac
                printf 'PROGRAM:%%s\\n' "$2" >>"$trace"
                printf 'CONTROLLED_PROGRAM\\n'
            }
            run_application_container() { reject fixture-unexpected-container; }
            application_container_candidate_probe() { reject fixture-unexpected-container-probe; }
            application_health controlled-backend controlled-manifest '%s'
            printf 'FINAL_KIND=%%s;CUSTOM=%%s\\n' "$application_health_kind" "$application_health_custom" >>"$trace"
            printf 'AFTER_HEALTH\\n' >>"$trace"
            """.formatted(mode, staleCustom ? "1" : "0", DeploymentWorkloadArguments.healthPayload(check));
        String script = "set -euo pipefail\n" + assembledBranchFunctions() + fixture;
        var builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").directory(temporary.toFile()).redirectErrorStream(true);
        builder.environment().remove("BASH_ENV");
        builder.environment().remove("ENV");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Controlled Bash branch did not exit");
            return new BranchResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                Files.readString(temporary.resolve("branch-trace.txt"), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private record BranchResult(int exit, String output, String trace) {
    }
}
