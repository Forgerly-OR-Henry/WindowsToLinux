package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Navigation into the exact currently pinned receiver. Unselected source remains readable. */
record PreparationSourceRange(String section, int offset, int next) {
    private static final Pattern PYTHON_SECTION = Pattern.compile("(?m)^(?:@|def |class |if __name__ == )");
    private static final Set<String> NODE_HELPER_INITIAL = Set.of("selectedRelease", "pinnedMirrorRuntime", "def checksum_text(");
    private static final Set<String> NODE_TRANSFER_INITIAL = Set.of("mirrorRouteAdmission", "mirrorTLS", "mirrorDigest");
    private static final Set<String> NODE_HELPER_SUPPLEMENT = Set.of("prepareDispatch", "pythonLauncher",
        "nodeMirroredMetadata", "nodeMirroredArchive", "archiveTarValidation", "nodeArchiveDigest",
        "nodeManagedArchive", "archiveVersionProbe", "nodePreparationLock");
    private static final Set<String> NODE_TRANSFER_SUPPLEMENT = Set.of("class MirrorRedirect(", "mirrorDownload");
    private static final Set<String> PYTHON_PREPARATION_INITIAL = Set.of(
        "shellStartup", "startupOwnership", "prepareDispatch", "pythonLauncher", "selectedRelease",
        "sourceResourceLimits", "sourceIsolationPreflight", "def source_install(", "def source_unit(", "pythonSourceWorker",
        "pythonMakeSourceIdentity", "gnuMakeSourceIdentity", "pythonMakeBootstrapExecution",
        "def source_dependencies(", "pythonLegacyReceiverGuard",
        "aptDispatch", "aptSourceSafety", "aptCredentialAndTransportPolicy", "aptSecurityAndFrozenTransaction",
        "mirrorPolicyValidation", "mirrorRouteAdmission", "mirrorTLS", "mirrorDigest",
        "def mirror_system_command(");
    private static final Set<String> RUBY_MAKE_REQUIRED = Set.of(
        "gnuMakeSourceIdentity", "gnuMakeBootstrap", "rubySourceWorker",
        "def gnu_make_worker(", "def gnu_make_dependency(", "def prepare_gnu_make_source(",
        "def gnu_make_origin(", "def download(", "def source_unit(", "def source_install(",
        "def ruby_unit(", "def ruby_source_install(", "def install_with_ruby_isolation(", "rubyIsolatedInstallDispatch",
        "rubySourceRecipe", "def install_key_with_ruby_recipe(", "def ruby_recipe_key(", "def require_ruby_source_record(");
    private static final Set<String> RUBY_PREPARATION_INITIAL = Set.of(
        "shellStartup", "startupOwnership", "prepareDispatch", "pythonLauncher", "selectedRelease",
        "sourceResourceLimits", "sourceIsolationPreflight", "def prepare(", "def source_dependencies(",
        "aptDispatch", "aptSourceSafety", "aptCredentialAndTransportPolicy", "aptSecurityAndFrozenTransaction",
        "mirrorPolicyValidation", "mirrorRouteAdmission", "mirrorTLS", "mirrorBoundedRead", "mirrorDigest",
        "def mirror_system_command(");
    private static final Set<String> PHP_BUILD_REQUIRED = Set.of(
        "phpArchiveSourceBinding", "phpSourceWorker", "phpSourceAdapter", "def extract(archive, destination):",
        "def source_unit(", "def source_install(", "def source_probe(", "def ruby_publish(",
        "sourceIsolationPreflight", "gnuMakeSourceIdentity", "gnuMakeBootstrap", "def prepare_gnu_make_source(",
        "def discover_system(", "def verify_system(", "phpLegacyReceiverGuard", "phpLegacyProbeGuard");
    private static final Set<String> APT_DEPENDENCY_WORKER_INITIAL = Set.of(
        "def dependency_manifest(", "def dependency_open_core_events(", "def dependency_worker(",
        "def dependency_supervise(", "def dependency_stop_owned(", "def dependency_validate_apt(",
        "def dependency_transaction(", "def dependency_main(", "def dependency_mirror_environment(",
        "def dependency_static_identity(", "def dependency_validate_ownership(", "def dependency_events_read(",
        "def dependency_ack_record(", "def dependency_result(", "dependencyAptBinaryPaths", "def dependency_protected(",
        "dependencyAptSourceIdentity");
    private static final Set<String> APT_DEPENDENCY_SHELL_INITIAL = Set.of(
        "aptGuardedRepositoryAdmission", "aptSignedUpdateToFrozenInstall", "aptDispatch",
        "aptSecurityAndFrozenTransaction", "def mirror_system_command(", "aptFrozenPolicyAdmission",
        "aptRepositoryUriAdmission");

    static List<PreparationSourceRange> helper(String helper, String ecosystem, boolean diagnosed) throws IOException {
        var ranges = new ArrayList<PreparationSourceRange>();
        boolean rust = ecosystem.equals("RUST");
        boolean node = ecosystem.equals("NODE");
        boolean pythonSource = ecosystem.equals("PYTHON");
        boolean phpSource = ecosystem.equals("PHP");
        ranges.add(between(helper, "shellStartup", "#!/bin/bash -p\n", "\nhelper_path="));
        ranges.add(between(helper, "startupOwnership", "require_root() {\n", "\nrequire_app() {"));
        ranges.add(between(helper, "prepareDispatch", "    prepare-toolchains)\n", "    retain)"));
        ranges.add(between(helper, "pythonLauncher", "prepare_official_toolchains() {\n", "\nSHIPPED_CATALOG = "));
        int release = offset(helper, "def release(ecosystem, branch, exact):");
        ranges.add(between(helper, "releaseDownloadPoint", "def release(ecosystem, branch, exact):",
            "\n    if ecosystem == '", release));
        ranges.add(selectedRelease(helper, ecosystem, release));
        var common = pythonSource
            ? List.of("task_mirrors", "fetch", "download", "prepare")
            : List.of("task_mirrors", "source_origin", "source_url", "toolchain_point", "fetch", "json_at", "download",
                "open_preparation_lock", "prepare", "owned_directory");
        for (String function : common) {
            ranges.add(python(helper, "def " + function + "("));
        }
        if (!rust) ranges.add(python(helper, "def reuse_system("));
        ranges.add(python(helper, "def official_url("));
        boolean java = ecosystem.equals("JAVA");
        boolean archive = java || ecosystem.equals("GO") || ecosystem.equals("DOTNET") || node;
        if (archive) {
            ranges.addAll(archiveInstallation(helper, ecosystem.equals("DOTNET")));
            ranges.add(between(helper, "archiveVersionProbe", "def probe(ecosystem, directory, expected):",
                java ? "\n    if ecosystem == 'PYTHON':\n        run(" : "\n    if ecosystem == 'JAVA':\n        run("));
            ranges.add(python(helper, "def extract(archive, destination):"));
            if (ecosystem.equals("DOTNET")) {
                ranges.add(between(helper, "dotnetSystemReuse", "def discover_system(ecosystem, identity):", "\n    binaries = "));
                ranges.addAll(dotnetProbe(helper));
            } else ranges.add(python(helper, "def discover_system("));
            if (node) {
                for (String function : List.of("checksum_text", "saved_installation_marker", "verify_system"))
                    ranges.add(python(helper, "def " + function + "("));
            }
            if (java || node) ranges.add(python(helper, "def preparation_lock("));
        } else if (rust) {
            ranges.addAll(rustPreparation(helper, diagnosed));
        } else if (pythonSource) {
            ranges.addAll(pythonSourcePreparation(helper));
        } else if (phpSource) {
            ranges.addAll(phpSourcePreparation(helper));
        } else {
            ranges.add(python(helper, "def install(ecosystem, identity, url, algorithm, expected, layout):"));
            ranges.add(python(helper, "def probe(ecosystem, directory, expected):"));
        }
        if (!archive) ranges.add(python(helper, "def preparation_lock("));
        if (diagnosed) {
            if (archive) {
                ranges.add(between(helper, "preparationDiagnosticRun", "def installation_diagnostic_main(args, raw=None):",
                    "\n    owned_directory(ROOT)\n"));
            } else if (pythonSource) {
                ranges.add(between(helper, "sourceDiagnosticInvocation", "def installation_diagnostic_main(args, raw=None):",
                    "\n    owned_directory(ROOT)\n"));
            } else ranges.add(python(helper, "def installation_diagnostic_main("));
            ranges.add(between(helper, "diagnosticDispatch", "def main(args):\n",
                "    if args and args[0] in ('prepare-tracked'"));
        }
        if (buildsFromSource(ecosystem))
            ranges.add(python(helper, "def source_dependencies("));
        if (ecosystem.equals("RUBY")) {
            ranges.addAll(sourceIsolation(helper));
            ranges.addAll(gnuMakeSource(helper));
            ranges.add(python(helper, "def ruby_mount_path("));
            ranges.add(python(helper, "def ruby_publish("));
            ranges.add(python(helper, "def ruby_link_target("));
            ranges.add(between(helper, "rubySourceWorker", "\nRUBY_WORKER = ",
                "\n\ndef ruby_system_command("));
            for (String function : List.of("ruby_unit", "ruby_probe", "ruby_source_install",
                    "install_with_ruby_isolation", "install_key_with_ruby_recipe", "ruby_recipe_key", "require_ruby_source_record"))
                ranges.add(python(helper, "def " + function + "("));
            ranges.add(between(helper, "rubySourceRecipe", "RUBY_LEGACY_SOURCE_RECIPE = ", "\nRUBY_DYNAMIC_MIN, "));
            ranges.add(between(helper, "rubyIsolatedInstallDispatch", "\ninstallation_key = install_key_with_ruby_recipe",
                "\nprobe = probe_with_ruby_isolation"));
        }
        return List.copyOf(ranges);
    }

    private static List<PreparationSourceRange> pythonSourcePreparation(String helper) throws IOException {
        var ranges = new ArrayList<>(sourceIsolation(helper));
        int worker = offset(helper, "\nPYTHON_SOURCE_WORKER = ");
        ranges.add(between(helper, "pythonMakeSourceIdentity", "PYTHON_LEGACY_SOURCE_RECIPE = ",
            "\nPYTHON_MAKE_FAILURES = "));
        ranges.addAll(gnuMakeSource(helper));
        ranges.add(between(helper, "pythonMakeBootstrapExecution", "def bootstrap_gnu_make(",
            "\n'''"));
        ranges.add(between(helper, "pythonMakeInvocation", "def make_python(",
            "\n\nfailure = 90", worker));
        ranges.add(between(helper, "pythonSourceWorker", "\nfailure = 90\n", "\n\ndef python_source_marker_recipe_matches(", worker));
        ranges.add(between(helper, "pythonSourceAdapter", "def python_source_marker_recipe_matches(", "\n\nPHP_ARCHIVE_BOUNDARIES = "));
        ranges.add(between(helper, "pythonLegacyReceiverGuard",
            "    if ecosystem == 'PYTHON' or layout == 'python':\n", "\n    MIRROR_POINT = toolchain_point(ecosystem)"));
        ranges.add(between(helper, "pythonSystemProfile", "def discover_system(ecosystem, identity):", "\n    binaries = "));
        ranges.add(python(helper, "def verify_system("));
        return ranges;
    }

    private static List<PreparationSourceRange> phpSourcePreparation(String helper) throws IOException {
        var ranges = new ArrayList<>(sourceIsolation(helper));
        ranges.addAll(gnuMakeSource(helper));
        ranges.add(between(helper, "phpArchiveSourceBinding", "\nPHP_ARCHIVE_BOUNDARIES = ", "\nPHP_SOURCE_RECIPE = "));
        ranges.add(between(helper, "phpSourceWorker", "\nPHP_SOURCE_RECIPE = ", "\n\ndef php_protected_executable("));
        ranges.add(between(helper, "phpSourceAdapter", "def php_protected_executable(", "\n\ndef main(args):"));
        ranges.add(between(helper, "phpLegacyReceiverGuard",
            "    if ecosystem == 'PHP' or layout == 'php':\n", "\n    if ecosystem == 'PYTHON' or layout == 'python':"));
        ranges.add(between(helper, "phpLegacyProbeGuard", "def probe(ecosystem, directory, expected):", "\n    if ecosystem == 'DOTNET':"));
        for (String function : List.of("extract(archive, destination):", "discover_system(", "verify_system(",
                "ruby_publish(", "ruby_link_target("))
            ranges.add(python(helper, "def " + function));
        return ranges;
    }

    private static List<PreparationSourceRange> gnuMakeSource(String helper) throws IOException {
        return List.of(
            between(helper, "gnuMakeSourceIdentity", "\nGNU_MAKE_SOURCE_VERSION = ", "\nGNU_MAKE_FAILURES = "),
            between(helper, "gnuMakeBootstrap", "GNU_MAKE_BOOTSTRAP = r'''", "\ndef gnu_make_worker("),
            python(helper, "def gnu_make_worker("), python(helper, "def gnu_make_dependency("),
            between(helper, "def prepare_gnu_make_source(", "def prepare_gnu_make_source(", "\n\n"),
            python(helper, "def gnu_make_origin("));
    }

    private static List<PreparationSourceRange> sourceIsolation(String helper) throws IOException {
        return List.of(
            between(helper, "sourceResourceLimits", "\nRUBY_SCRATCH_BYTES = ", "\nRUBY_OPENSSL_DIGEST = "),
            between(helper, "sourceIsolationPreflight", "SOURCE_WORKER = r'''\n", "\nRUBY_WORKER = "),
            python(helper, "def source_install("), python(helper, "def source_unit("),
            python(helper, "def ruby_scratch_mount("), python(helper, "def ruby_create_scratch("),
            python(helper, "def ruby_remove_scratch("),
            python(helper, "def source_probe("), python(helper, "def ruby_require_settled("),
            python(helper, "def source_scratch_mount_entry("),
            between(helper, "sourceProbeTmpfs", "def source_probe_mount_identity(", "\ndef ruby_manager_terminal("),
            between(helper, "sourceProbeRecovery", "def tracked_require_quiescent_with_ruby(",
                "\ninstallation_key = install_key_with_ruby_recipe"));
    }

    private static List<PreparationSourceRange> rustPreparation(String helper, boolean diagnosed) throws IOException {
        var ranges = new ArrayList<>(List.of(
            between(helper, "rustShellAdmission", "assert_not_writable_by_others() {\n", "\nassert_candidate_for_deployer() {"),
            between(helper, "rustShellDispatch", "\nrequire_root\n[ \"$#\" -ge 1 ]", "\ncase \"$verb\" in\n    publish-deployment"),
            between(helper, "rustSourceLimits", "\nMAX_METADATA = ", "\n\n\nclass PreparationFailure("),
            between(helper, "rustReleaseSelection", "def version_key(version):", "\ndef html_hash("),
            python(helper, "def extract(archive, destination):"),
            between(helper, "rustSystemReuse", "def discover_system(ecosystem, identity):", "\ndef ruby_legacy_tls("),
            between(helper, "rustSystemProbe", "def probe(ecosystem, directory, expected):", "\n    if ecosystem == 'JAVA':\n        run("),
            between(helper, "rustProbeProcess", "def probe_output(command):", "\n@contextlib.contextmanager\ndef open_preparation_lock("),
            between(helper, "rustProbeDeadline", "def tracked_process_started(process):", "\ndef source_dependencies("),
            python(helper, "def admitted("),
            python(helper, "def prepare_shared_directories("),
            python(helper, "def prepare_root("),
            between(helper, "rustMarkerPersistence", "def tracked_regular(path, limit):", "\ndef installation_diagnostic_request("),
            between(helper, "rustPredecessorDispatch", "_previous_installation_key = installation_key", "\ndef tracked_require_quiescent_with_ruby("),
            between(helper, "rustDispatchPredecessors", "\ninstallation_key = install_key_with_ruby_recipe\n", "\nRUST_DISTRIBUTION_RECIPE = "),
            between(helper, "rustComponentReceiver", "RUST_DISTRIBUTION_RECIPE = ", "\nPYTHON_SOURCE_RECIPE = "),
            between(helper, "rustPythonEntry", "\nif __name__ == '__main__':\n", "\nWTL_OFFICIAL_TOOLCHAINS\n",
                offset(helper, "\ndef prepare("))
        ));
        if (diagnosed) {
            ranges.add(between(helper, "rustDiagnosticRequest", "TRACKED_OPERATION = None\nINSTALLATION_DIAGNOSTIC = None",
                "\ndef tracked_request("));
            ranges.add(between(helper, "rustDiagnosticArguments", "def installation_diagnostic_request(",
                "\ndef installation_diagnostic_main("));
            ranges.add(installationDiagnosticWorker(helper));
        } else {
            ranges.add(between(helper, "rustDirectDispatch", "def main(args):\n", "\n        if verifying:\n            binding = "));
            ranges.add(between(helper, "rustDirectPrepare", "\n        prepare(args)\n", "\ndef prepare("));
        }
        return ranges;
    }

    private static PreparationSourceRange selectedRelease(String helper, String ecosystem, int release) throws IOException {
        String selector = "    if ecosystem == '" + ecosystem + "':\n";
        int start = helper.indexOf(selector, release);
        int end = helper.indexOf("\n    fail('request', 'unsupported installation adapter')", release);
        if (start < 0 || end <= start) throw new IOException("Fixed release branch unavailable");
        int next = helper.indexOf("\n    if ecosystem == '", start + selector.length());
        return new PreparationSourceRange("selectedRelease", start, next < 0 ? end : Math.min(next, end));
    }

    private static List<PreparationSourceRange> archiveInstallation(String helper, boolean dotnet) throws IOException {
        var install = python(helper, "def install(ecosystem, identity, url, algorithm, expected, layout):");
        int alternate = helper.indexOf("\n            else:\n", install.offset());
        int finish = helper.indexOf("\n            if not dotnet:\n                probe(ecosystem, destination, identity)", alternate);
        if (alternate < install.offset() || finish <= alternate || finish >= install.next() ||
            !helper.substring(install.offset(), alternate).contains("if layout in ('flat', 'archive'):"))
            throw new IOException("Fixed archive installation branch unavailable");
        if (dotnet) return List.of(new PreparationSourceRange("archiveInstall", install.offset(), alternate),
            new PreparationSourceRange("archiveReceipt", finish, install.next()));
        var admission = between(helper, "archiveInstall", "def install(ecosystem, identity, url, algorithm, expected, layout):",
            "\n        previous_dotnet = load_managed_dotnet_marker(", install.offset());
        var download = between(helper, "archiveDownload", "\n    work_parent = ROOT / 'work'",
            "\n        dotnet_probe_files = managed_dotnet_probe_identity(", admission.next());
        var copy = between(helper, "archiveCopy", "\n        with create_installation_directory(destination, tracked):",
            "\n            else:\n", download.next());
        var receipt = between(helper, "archiveReceipt", "\n            if not dotnet:\n                probe(ecosystem, destination, identity)",
            "\n            if dotnet:\n", finish);
        int seal = helper.indexOf("\n            elif tracked is None:\n                marker.write_text(", receipt.next());
        if (seal < receipt.next() || seal >= install.next()) throw new IOException("Fixed archive completion branch unavailable");
        return List.of(admission, download, copy, receipt, new PreparationSourceRange("archiveSeal", seal, install.next()));
    }

    static List<PreparationSourceRange> dotnetProbe(String helper) throws IOException {
        return List.of(python(helper, "def managed_dotnet_probe_output("),
            between(helper, "managedDotnetProbeIdentityAndExecution", "\ndef managed_dotnet_file_digest(",
                "\ndef install(ecosystem, identity, url, algorithm, expected, layout):"));
    }

    static List<PreparationSourceRange> nodeHelper(String helper, boolean diagnosed) throws IOException {
        int release = offset(helper, "def release(ecosystem, branch, exact):");
        var extract = python(helper, "def extract(archive, destination):");
        var tar = between(helper, "archiveTarValidation", "        for m in members:\n            target = root / m.name",
            "\n        data.extractall(root, members=members)", extract.offset());
        var ranges = new ArrayList<>(List.of(
            between(helper, "prepareDispatch", "    prepare-toolchains)\n", "    retain)"),
            between(helper, "pythonLauncher", "prepare_official_toolchains() {\n", "\nSHIPPED_CATALOG = "),
            selectedRelease(helper, "NODE", release),
            between(helper, "pinnedMirrorRuntime", "        for path in (runtime, policy_path):",
                "\n    return MIRROR_MODULE, policy", offset(helper, "def task_mirrors(")),
            python(helper, "def checksum_text("),
            between(helper, "nodeMirroredMetadata", "def fetch(url,", "\n    request = urllib.request.Request("),
            between(helper, "nodeMirroredArchive", "def download(url,", "\n    official = hashlib.new("),
            new PreparationSourceRange(tar.section(), tar.offset(), extract.next()),
            between(helper, "nodeSystemOwnership", "        owned_directory(home)", "\n    except (PreparationFailure,",
                offset(helper, "def discover_system(")),
            python(helper, "def verify_system(")));
        int install = offset(helper, "def install(ecosystem, identity, url, algorithm, expected, layout):");
        ranges.add(between(helper, "nodeArchiveDigest", "    if algorithm not in ('sha256', 'sha512') or not re.fullmatch(",
            "\n    key = installation_key(",
            install));
        ranges.add(between(helper, "nodeManagedArchive", "        archive = work / 'release.archive'",
            "\n            else:\n", install));
        ranges.add(between(helper, "archiveVersionProbe", "    command = list(commands[ecosystem])",
            "\n    if ecosystem == 'JAVA':\n        run(", offset(helper, "def probe(ecosystem, directory, expected):")));
        ranges.add(between(helper, "nodePreparationLock", "def require_preparation_lock_file(",
            "\ndef tracked_process_started("));
        if (diagnosed) ranges.add(installationDiagnosticWorker(helper));
        return ranges;
    }

    private static PreparationSourceRange installationDiagnosticWorker(String helper) throws IOException {
        var diagnostic = python(helper, "def start_installation_diagnostic(");
        return new PreparationSourceRange("preparationDiagnosticRun", diagnostic.offset(), diagnostic.next());
    }

    static List<PreparationSourceRange> nodeHelperInitial(List<PreparationSourceRange> ranges) {
        return ranges.stream().filter(range -> NODE_HELPER_INITIAL.contains(range.section())).toList();
    }

    static List<PreparationSourceRange> nodeHelperSupplement(List<PreparationSourceRange> ranges) {
        return ranges.stream().filter(range -> NODE_HELPER_SUPPLEMENT.contains(range.section())).toList();
    }

    static List<PreparationSourceRange> nodeTransfer(String runtime, boolean credentials) throws IOException {
        var ranges = new ArrayList<>(List.of(
            between(runtime, "policyDigest", "    @classmethod\n    def from_environment(", "\n    def remaining("),
            python(runtime, "class MirrorRedirect("),
            between(runtime, "mirrorRouteAdmission", "            selected = policy.validate_url(endpoint, policy.maps(endpoint, original))",
                "\n        while record['attempts']"),
            between(runtime, "mirrorTLS", "                                self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host,",
                "\n                            else:", offset(runtime, "def _mirror_transfer(")),
            between(runtime, "mirrorDownload", "                handlers = [urllib.request.ProxyHandler({}), Http(), Https(), MirrorRedirect(endpoint, policy)]",
                "\n                digest = hashlib.new(algorithm)", offset(runtime, "def _mirror_transfer(")),
            between(runtime, "mirrorBoundedRead", "                digest = hashlib.new(algorithm) if algorithm else None",
                "\n                    if content_length and total != int(content_length):", offset(runtime, "def _mirror_transfer(")),
            between(runtime, "mirrorDigest", "                    if content_length and total != int(content_length):",
                "\n                mirror_log(point, endpoint, 'complete', attempt)")));
        if (credentials) {
            ranges.add(python(runtime, "class MirrorCredentialMaterial:"));
            ranges.add(python(runtime, "def install_mirror_credentials("));
        }
        return ranges;
    }

    static List<PreparationSourceRange> nodeTransferInitial(List<PreparationSourceRange> ranges) {
        return ranges.stream().filter(range -> NODE_TRANSFER_INITIAL.contains(range.section())).toList();
    }

    static List<PreparationSourceRange> nodeTransferSupplement(List<PreparationSourceRange> ranges) {
        return ranges.stream().filter(range -> NODE_TRANSFER_SUPPLEMENT.contains(range.section())).toList();
    }

    static List<PreparationSourceRange> transfer(String runtime, String ecosystem, boolean credentials,
                                                  boolean packageManager) throws IOException {
        var ranges = new ArrayList<PreparationSourceRange>();
        boolean fullTransport = ecosystem.equals("RUST");
        boolean routeExcerpt = fullTransport || ecosystem.equals("DOTNET") || ecosystem.equals("GO")
            || ecosystem.equals("KOTLIN") || packageManager;
        if (fullTransport) {
            ranges.add(python(runtime, "class MirrorTransferLedger:"));
            ranges.add(python(runtime, "class MirrorPolicy:"));
        } else if (routeExcerpt) {
            ranges.add(between(runtime, "mirrorRouteAndRedirectPolicy", "    @staticmethod\n    def validate_url(endpoint, url):",
                "\n\nclass MirrorRedirect("));
        }
        if (routeExcerpt) ranges.add(python(runtime, "class MirrorRedirect("));
        if (fullTransport) {
            for (String function : List.of("mirror_transfer", "_mirror_transfer", "mirror_bytes"))
                ranges.add(python(runtime, "def " + function + "("));
        } else if (routeExcerpt) {
            int transfer = offset(runtime, "def _mirror_transfer(");
            ranges.add(between(runtime, "mirrorInitialRoute", "            selected = policy.validate_url(endpoint, policy.maps(endpoint, original))",
                "\n        while record['attempts']"));
            ranges.add(between(runtime, "mirrorTlsRedirectAndCommit", "                        def connect(self):\n",
                "\n            except (OSError, ValueError, MirrorFailure, urllib.error.URLError, http.client.HTTPException)",
                transfer));
            if (!ecosystem.equals("GO"))
                ranges.add(between(runtime, "mirrorFallbackAndCleanup",
                    "            except (OSError, ValueError, MirrorFailure, urllib.error.URLError, http.client.HTTPException)",
                    "\ndef mirror_bytes(", transfer));
            ranges.add(python(runtime, "def mirror_bytes("));
        }
        if (ecosystem.equals("NODE") || ecosystem.equals("JAVA")) {
            ranges.add(between(runtime, "nodeMirrorRouteAdmission",
                "            selected = policy.validate_url(endpoint, policy.maps(endpoint, original))",
                "\n        while record['attempts']"));
            ranges.add(python(runtime, "class MirrorRedirect("));
            int transfer = offset(runtime, "def _mirror_transfer(");
            ranges.add(between(runtime, "nodeMirrorTLS", "                        def connect(self):\n",
                "\n                class Http(", transfer));
            ranges.add(between(runtime, "nodeMirrorDigest", "                handlers = [urllib.request.ProxyHandler({}), Http(), Https(), MirrorRedirect(endpoint, policy)]",
                "\n                mirror_log(point, endpoint, 'complete', attempt)", transfer));
        }
        if (credentials) {
            ranges.add(python(runtime, "class MirrorCredentialMaterial:"));
            ranges.add(python(runtime, "def install_mirror_credentials("));
        }
        if (packageManager) ranges.add(python(runtime, "def mirror_system_command("));
        return List.copyOf(ranges);
    }

    static List<PreparationSourceRange> apt(String shell) throws IOException {
        int functions = offset(shell, "\nwtl_prepare_system_credential() {\n") + 1;
        return List.of(between(shell, "aptRepositoryConfiguration", "wtl_repository_copy() {\n", "\nwtl_dnf_repository_copy() {"),
            between(shell, "systemPackage", "wtl_system_package() {\n", "\nwtl_dnf() {"),
            new PreparationSourceRange("aptSecurityAndFrozenTransaction", functions, shell.length()));
    }

    static PreparationSourceRange runtimeLauncher(String shell) throws IOException {
        return between(shell, "mirrorRuntimeLauncher", "wtl_run_mirror_runtime() {\n", "\nwtl_system_package() {");
    }

    static List<PreparationSourceRange> buildRuntime(String runtime) throws IOException {
        return List.of(python(runtime, "class MirrorPolicy:"),
            python(runtime, "class MirrorRedirect("),
            python(runtime, "def mirror_wrapper("),
            python(runtime, "def mirror_dependency("));
    }

    static List<PreparationSourceRange> cargoBuildRuntime(String runtime) throws IOException {
        var ranges = new ArrayList<>(buildRuntime(runtime));
        ranges.addAll(List.of(
            between(runtime, "cargoDependencyDispatch", "    if args.operation == 'dependency':",
                "\n    if args.operation == 'system-dnf':"),
            between(runtime, "cargoSparseRegistry", "    elif point == 'RUST_CRATE':", "\n    elif point == 'NUGET':"),
            between(runtime, "cargoRepositoryRequests", "class RepositoryGateway:", "\n    @staticmethod\n    def python_filename("),
            between(runtime, "cargoRepositoryLinks", "    def link(self, point, value,", "\n    def __enter__(self):"),
            between(runtime, "cargoRepositoryLifecycle", "    def __enter__(self):\n        self.thread.start()",
                "\ndef reject_unmapped_lock_urls(", offset(runtime, "class RepositoryGateway:")),
            python(runtime, "def locked_dependency("),
            python(runtime, "def mirror_transfer("), python(runtime, "def _mirror_transfer(")));
        return ranges;
    }

    static List<PreparationSourceRange> goBuildRuntime(String runtime) throws IOException {
        var ranges = new ArrayList<>(List.of(
            python(runtime, "class MirrorPolicy:"),
            between(runtime, "goDependencyDispatch", "    if args.operation == 'dependency':",
                "\n    if args.operation == 'system-dnf':"),
            python(runtime, "def mirror_dependency("),
            between(runtime, "goLoopbackAdmission", "def mirror_loopback_peer_uid(", "\ndef mirror_log("),
            between(runtime, "goRepositoryRequests", "class RepositoryGateway:", "\n    @staticmethod\n    def python_filename("),
            between(runtime, "goRepositoryLifecycle", "    def __enter__(self):\n        self.thread.start()",
                "\ndef reject_unmapped_lock_urls(", offset(runtime, "class RepositoryGateway:")),
            between(runtime, "goRepositoryLockSelection", "def reject_unmapped_lock_urls(", "\n    for name in names:"),
            between(runtime, "goDependencyEnvironment", "    elif point == 'GO_MODULE':", "\n    elif point == 'MAVEN_REPOSITORY':"),
            between(runtime, "goUnsupportedLockedFallback", "def locked_dependency(", "\n    with LockedRepositoryTransport("),
            python(runtime, "class MirrorTransferLedger:"), python(runtime, "def mirror_transfer(")));
        ranges.addAll(transfer(runtime, "GO", false, false).stream()
            .filter(range -> !range.section().equals("mirrorRouteAndRedirectPolicy")).toList());
        return ranges;
    }

    static List<PreparationSourceRange> dependencyRuntime(String runtime) throws IOException {
        return List.of(python(runtime, "def mirror_system_command("),
            python(runtime, "def mirror_dnf("));
    }

    static List<PreparationSourceRange> aptDependencyRuntime(String runtime) throws IOException {
        return List.of(python(runtime, "def mirror_system_command("),
            between(runtime, "aptFrozenPolicyAdmission", "class MirrorPolicy:",
                "\n    def transport_options("),
            between(runtime, "aptRuntimePolicyTransport", "    def transport_options(self, endpoint, url):",
                "\n\nclass MirrorRedirect("));
    }

    static List<PreparationSourceRange> aptDependencyWorker(String worker) throws IOException {
        var ranges = new ArrayList<PreparationSourceRange>();
        for (String function : List.of("dependency_mirror_environment", "dependency_static_identity", "dependency_unit_identity",
            "dependency_manifest", "dependency_observe_startup", "dependency_events_read", "dependency_open_core_events",
            "dependency_validate_ownership", "dependency_retain_ownership", "dependency_ack_record", "dependency_validate_ack",
            "dependency_worker", "dependency_result", "dependency_completion_proof", "dependency_stop_owned",
            "dependency_supervise", "dependency_protected",
            "dependency_profile", "dependency_bytes", "dependency_text", "dependency_registered", "dependency_tool",
            "dependency_task_mirrors", "dependency_run",
            "dependency_packages", "dependency_validate_apt", "dependency_transaction", "dependency_main"))
            ranges.add(python(worker, "def " + function + "("));
        ranges.add(between(worker, "dependencyAptHookOwners", "\nHOOK_OWNERS = frozenset(", "\n\n\nclass DependencyFailure("));
        ranges.add(between(worker, "dependencyAptBinaryPaths", "APT_BINARY_PATHS = {", "\n\n\ndef dependency_"));
        ranges.add(between(worker, "dependencyAptSourceIdentity", "def dependency_url(", "def dependency_apt_hook_owners("));
        ranges.add(between(worker, "dependencyAptAuthentication", "def dependency_apt_hook_owners(",
            "\ndef dependency_validate_apt("));
        if (worker.contains("\nDEPENDENCY_SCOPED_PROFILE = ")) {
            ranges.add(between(worker, "scopedNativeAptPackagePolicy", "\nDEPENDENCY_PACKAGES = ",
                "\n\"\"\"Operation-owned dependency service"));
            ranges.add(python(worker, "def dependency_frame("));
        }
        return ranges;
    }

    static List<PreparationSourceRange> aptDependencyShell(String shell, boolean credentials, boolean nativeApt) throws IOException {
        var ranges = new ArrayList<>(aptSystemShell(shell, credentials));
        ranges.add(between(shell, "aptRepositoryUriAdmission", "wtl_apt_source_security() {\n",
            "\nwtl_apt_sandbox_identity() {"));
        if (nativeApt) {
            ranges.add(between(shell, "aptBoundedSubprocess", "wtl_stop_package_tree() {\n", "\nwtl_run_mirror_runtime() {"));
            ranges.add(between(shell, "aptResultAndCleanup", "\n      wtl_package_log \"$log\"", "\nwtl_apt() ("));
            ranges.add(between(shell, "aptTransactionLog", "wtl_package_log() {\n", "\nwtl_apt_source_security() {"));
        }
        return ranges;
    }

    private static List<PreparationSourceRange> aptSystemShell(String shell, boolean credentials) throws IOException {
        return List.of(
            between(shell, "aptRepositorySelection", "wtl_system_point() {\n", "\nwtl_dnf_repository_copy() {"),
            between(shell, "aptGuardedRepositoryAdmission", "wtl_system_package() {\n",
                "\n    else\n      for file in /etc/yum.repos.d/"),
            between(shell, "aptSignedUpdateToFrozenInstall", "    for ((attempt=1; attempt<=wtl_mirror_attempts; attempt++)); do",
                "\n      else\n        printf '[main]\\nplugins=0", offset(shell, "wtl_system_package() {\n")),
            between(shell, "aptCredentialAndTransportPolicy", "wtl_prepare_system_credential() {\n",
                credentials ? "\nwtl_package_log() {" : "\n  [[ \"$wtl_credential_scope\" = https://* ]]"),
            between(shell, "aptDispatch", "wtl_apt() (\n", "\nwtl_dnf() {"),
            between(shell, "aptRootDownloadSandbox", "wtl_apt_sandbox_identity() {\n", "\nwtl_apt_update() {"),
            between(shell, "aptUpdateAndAuthentication", "wtl_apt_update() {\n", "\nwtl_apt_resolution_missing() {"),
            between(shell, "aptResolveFrozen", "wtl_apt_resolve_frozen() {\n", "\nwtl_apt_configure_query() {"),
            new PreparationSourceRange("aptSecurityAndFrozenTransaction", offset(shell, "wtl_apt_trusted_path() {\n"), shell.length()));
    }

    static boolean aptDependencyInitial(PreparationSourceRange range) {
        return APT_DEPENDENCY_WORKER_INITIAL.contains(range.section())
            || APT_DEPENDENCY_SHELL_INITIAL.contains(range.section())
            || range.section().equals("scopedNativeAptPackagePolicy");
    }

    static List<PreparationSourceRange> sourceBuildPreparationShell(String shell, boolean credentials) throws IOException {
        var ranges = new ArrayList<>(aptSystemShell(shell, credentials));
        ranges.add(runtimeLauncher(shell));
        ranges.add(between(shell, "aptSourceSafety", "wtl_apt_source_security() {\n", "\nwtl_apt_repository_uris() {"));
        return ranges;
    }

    static List<PreparationSourceRange> sourceBuildPreparationTransport(String runtime, String ecosystem, boolean credentials) throws IOException {
        var ranges = new ArrayList<>(nodeTransfer(runtime, credentials));
        ranges.add(between(runtime, "mirrorPolicyValidation", "    @staticmethod\n    def validate_url(endpoint, url):",
            "\n    @staticmethod\n    def validate_redirect("));
        ranges.add(between(runtime, "mirrorRedirectValidation", "    @staticmethod\n    def validate_redirect(endpoint, original, url):",
            "\n    @staticmethod\n    def redirect_path("));
        ranges.addAll(transfer(runtime, ecosystem, credentials, true));
        return ranges.stream().distinct().toList();
    }

    static boolean sourceBuildPreparationInitial(String ecosystem, PreparationSourceRange range) {
        if (ecosystem.equals("PHP")) return Set.of("phpSourceWorker", "phpSourceAdapter").contains(range.section())
            || RUBY_PREPARATION_INITIAL.contains(range.section());
        if (!ecosystem.equals("RUBY")) return PYTHON_PREPARATION_INITIAL.contains(range.section());
        return RUBY_PREPARATION_INITIAL.contains(range.section()) ||
            rubyMakeRequired(range) && !range.section().equals("def gnu_make_origin(");
    }

    static boolean rubyMakeRequired(PreparationSourceRange range) {
        return RUBY_MAKE_REQUIRED.contains(range.section());
    }

    static boolean phpBuildRequired(PreparationSourceRange range) {
        return PHP_BUILD_REQUIRED.contains(range.section());
    }

    static boolean buildsFromSource(String ecosystem) {
        return Set.of("PYTHON", "PHP", "RUBY").contains(ecosystem);
    }

    static PreparationSourceRange python(String body, String anchor) throws IOException {
        int start = offset(body, "\n" + anchor) + 1;
        String section = body.substring(0, start - 1);
        int previous = section.lastIndexOf('\n') + 1;
        if (section.startsWith("@", previous)) start = previous;
        var next = PYTHON_SECTION.matcher(body);
        int declaration = body.indexOf(anchor, start);
        if (next.find(declaration + anchor.length())) return new PreparationSourceRange(anchor, start, next.start());
        if (declaration == body.lastIndexOf(anchor)) return new PreparationSourceRange(anchor, start, body.length());
        throw new IOException("Fixed preparation section boundary unavailable: " + anchor);
    }

    private static PreparationSourceRange between(String text, String section, String from, String until) throws IOException {
        return between(text, section, from, until, 0);
    }

    private static PreparationSourceRange between(String text, String section, String from, String until, int after) throws IOException {
        int start = after == 0 ? offset(text, from) : text.indexOf(from, after);
        if (start < 0) throw new IOException("Fixed preparation section unavailable: " + section);
        int end = text.indexOf(until, start + from.length());
        if (end < 0) throw new IOException("Fixed preparation section boundary unavailable: " + section);
        return new PreparationSourceRange(section, start, end);
    }

    private static int offset(String text, String anchor) throws IOException {
        int start = text.indexOf(anchor);
        if (start < 0 || text.indexOf(anchor, start + 1) >= 0)
            throw new IOException("Fixed preparation anchor unavailable: " + anchor);
        return start;
    }
}
