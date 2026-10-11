package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;

/** Selected exact assembled helper ranges for Agent review; not an execution dependency closure. */
public final class DeploymentAgentHelperSourceEvidence {
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;

    private static final int MAX_RANGE_BYTES = 32768;

    private static final int MAX_SELECTED_BYTES = 131072;

    private record Range(String symbol, String startAnchor, String endAnchor, boolean completeFunction,
                         boolean exactStartLine) {
        Range(String symbol, String startAnchor, String endAnchor, boolean completeFunction) {
            this(symbol, startAnchor, endAnchor, completeFunction, false);
        }
    }

    public record Span(String symbol, int assembledStartLine, int assembledEndLine, String text,
                       String textSha256, String scope) {
    }

    private static final List<Range> RANGES = List.of(
        new Range("helper_protocol_and_paths", "#!/usr/bin/env bash", "reject() {", false),
        function("reject", "require_root"), function("require_root", "require_deployer"),
        function("require_deployer", "require_app"), function("require_app", "require_candidate"),
        function("require_candidate", "require_digest"), function("require_digest", "require_secret_identifier"),
        function("require_secret_identifier", "require_revision"), function("require_revision", "require_snapshot_token"),
        function("require_snapshot_token", "app_root"), function("app_root", "unit_name"),
        function("unit_name", "unit_path"), function("unit_path", "candidate_root"),
        function("candidate_root", "snapshot_root"), function("snapshot_root", "assert_not_writable_by_others"),
        function("assert_not_writable_by_others", "assert_root_owned_directory"),
        function("assert_root_owned_directory", "assert_root_owned_regular"),
        function("assert_root_owned_regular", "assert_candidate_for_deployer"),
        function("assert_candidate_for_deployer", "require_relative_path"),
        function("require_count", "render_deployment_unit"),
        function("render_deployment_unit", "assert_deployment_current_or_empty"),
        new Range("assert_deployment_current_or_empty", "assert_deployment_current_or_empty() {", "build_entry_sha256=", true),
        function("configuration_directory", "configuration_path"), function("configuration_path", "secret_revision_directory"),
        function("secret_revision_directory", "secret_revision_path"), function("secret_revision_path", "require_payload_size"),
        function("parse_deployment_inputs", "assert_deployment_inputs"),
        function("application_field", "application_number"), function("application_number", "application_relative"),
        function("application_relative", "application_absolute"), function("application_absolute", "application_command_fields"),
        function("application_command_fields", "parse_application_payload"),
        function("parse_application_payload", "parse_application_health"),
        function("parse_application_health", "parse_application_health_payload"),
        function("parse_application_health_payload", "managed_binding_parts"),
        function("assert_storage_parent", "parse_managed_data_bindings"),
        function("build_unit_name", "build_identity_name"),
        function("expected_deployment_unit_digest", "save_deployment_parameters"),
        function("load_deployment_parameters", "prepare_official_toolchains"),
        function("application_lock_directory", "application_maintenance"),
        function("application_admit_run", "application_assert_inputs"),
        function("application_assert_inputs", "application_systemd_escape"),
        function("application_load_current", "application_job"),
        function("application_assert_no_orphan", "application_maintenance_control"),
        function("application_assert_native_job_stopped", "application_container_cleanup"),
        function("application_process_identity", "application_udp_owned"),
        function("application_udp_owned", "application_udp_response"),
        function("application_udp_response", "application_health"),
        function("application_health", "application_health_loaded"),
        function("application_health_loaded", "application_tcp_response"),
        function("application_tcp_response", "assert_container_ports"),
        function("container_name", "container_image"),
        function("publication_original_status_owned", "discard_original_snapshot"),
        function("podman_quadlet_path", "podman_quadlet_autostart_path"),
        new Range("health_dispatch_prefix", "require_root", "    app-run)", false, true));

    // Manually selected relevant definitions; declaration presence is checked, not a parsed call graph.
    private static final List<String> UNEXPANDED_DEPENDENCIES = List.of(
        "require_relative_path", "require_java_main", "require_safe_name", "require_service_port", "require_safe_argument",
        "normalize_secret_environment_name", "parse_managed_data_bindings", "parse_toolchain_binding", "require_bound_version",
        "render_ecosystem_runtime_command", "prepare_application_primary_argv", "render_application_command", "render_application_workers",
        "wrap_database_runtime", "render_service_runtime_identity", "render_application_bindings", "assert_managed_data_links",
        "service_identity_name", "container_nonroot_user", "container_image", "managed_binding_parts", "managed_data_binding_root",
        "load_container_parameters", "container_current_release", "assert_container_ports",
        "run_application_native", "run_application_container", "application_container_candidate_probe", "restore_application_health");

    private DeploymentAgentHelperSourceEvidence() {
    }

    public static Map<String, Object> facts() {
        return fromScript(DeploymentManagedHelperBundle.renderScript());
    }

    static Map<String, Object> fromScript(String script) {
        if (!DeploymentManagedHelperBundle.EXPECTED_SHA256.equals(DeploymentAgentInputs.hash(Objects.requireNonNull(script))))
            throw new IllegalStateException("Assembled helper source identity drifted");
        var spans = extractRanges(script);
        var lines = script.split("\n", -1);
        for (String dependency : UNEXPANDED_DEPENDENCIES) uniqueLine(lines, dependency + "() ");
        return Map.ofEntries(
            Map.entry("schema", "agent-assembled-helper-source-evidence.v1"),
            Map.entry("helperSha256", DeploymentManagedHelperBundle.EXPECTED_SHA256),
            Map.entry("helperProtocol", DeploymentManagedHelperBundle.PROTOCOL_VERSION),
            Map.entry("source", "Exact DeploymentManagedHelperBundle.renderScript() text after inserts and escaping; local source, not a remote observation"),
            Map.entry("spans", spans),
            Map.entry("includedFunctionSymbols", RANGES.stream().filter(Range::completeFunction).map(Range::symbol).toList()),
            Map.entry("unexpandedRelevantDefinitions", UNEXPANDED_DEPENDENCIES),
            Map.entry("dependencyBoundary", "Selected complete functions plus the protocol/path header and top-level dispatch prefix through application-health. Remaining dispatch branches and the listed relevant dependency definitions are not expanded. This is not a complete execution dependency closure or a computed call graph; bundled interpreter/OS tools, mutable host state, credentials/configuration contents and unrelated helper definitions are not supplied."),
            Map.entry("maintenanceAdmission", "application_health sets local application_validation=1. application_admit_run skips only this application's persistent maintenance-marker existence rejection, still takes this application's admission/run locks and performs orphan checks. It does not read another application's maintenance marker, end maintenance, or grant unconditional permission."),
            Map.entry("sideEffects", "Health admission can install its root-owned lock directory and create/open/truncate admission/run lock files through shell redirection. Orphan checks can remove a verified inactive job record or a container already observed not running. COMMAND/on-demand/custom-probe branches invoke original reviewed programs through unexpanded execution helpers. Review these effects; the health entry is not strictly read-only and this source does not authorize execution."),
            Map.entry("listenerLimits", "The native listener guard finds a same-port listener PID in the selected systemd cgroup; the external-endpoint branch additionally classifies owned listener addresses. HTTP then curls its loopback URL and rechecks process identity. It does not bind the particular HTTP connection/response socket to that PID, prove that a process loaded the exact CURRENT release, or provide a currently observed PID/listener/health result."),
            Map.entry("optionalOverrides", "The original application_health_unit/application_health_container overrides remain verbatim. The ordinary canonical application_health entry and shown dispatch prefix do not assign them; the unexpanded restore_application_health definition is a separate path that can assign overrides. No override is removed or normalized by this evidence."));
    }

    static List<Span> extractRanges(String script) {
        Objects.requireNonNull(script);
        if (script.indexOf('\r') >= 0 || !script.endsWith("\n") || script.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES)
            throw new IllegalStateException("Assembled source encoding or bounds are invalid");
        String[] lines = script.split("\n", -1);
        var spans = new ArrayList<Span>();
        int selectedBytes = 0;
        for (var range : RANGES) {
            int start = uniqueLine(lines, range.startAnchor(), range.exactStartLine());
            int end = uniqueLine(lines, range.endAnchor());
            if (start >= end) throw new IllegalStateException("Helper source anchors are reversed: " + range.symbol());
            String text = String.join("\n", Arrays.copyOfRange(lines, start, end)) + "\n";
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            selectedBytes += bytes;
            if (bytes > MAX_RANGE_BYTES || selectedBytes > MAX_SELECTED_BYTES
                || range.completeFunction() && !text.stripTrailing().endsWith("}"))
                throw new IllegalStateException("Helper source range is incomplete or out of bounds: " + range.symbol());
            spans.add(new Span(range.symbol(), start + 1, end, text, DeploymentAgentInputs.hash(text),
                range.completeFunction() ? "COMPLETE_FUNCTION" : "EXACT_HEADER_OR_DISPATCH_PREFIX_NOT_COMPLETE_FUNCTION"));
        }
        return List.copyOf(spans);
    }

    private static Range function(String symbol, String next) {
        return new Range(symbol, symbol + "() {", next + "() {", true);
    }

    private static int uniqueLine(String[] lines, String anchor) {
        return uniqueLine(lines, anchor, false);
    }

    private static int uniqueLine(String[] lines, String anchor, boolean exact) {
        int found = -1;
        for (int index = 0; index < lines.length; index++) {
            if (exact ? lines[index].equals(anchor) : lines[index].startsWith(anchor)) {
                if (found != -1) throw new IllegalStateException("Duplicate helper source anchor: " + anchor);
                found = index;
            }
        }
        if (found == -1) throw new IllegalStateException("Missing helper source anchor: " + anchor);
        return found;
    }
}
