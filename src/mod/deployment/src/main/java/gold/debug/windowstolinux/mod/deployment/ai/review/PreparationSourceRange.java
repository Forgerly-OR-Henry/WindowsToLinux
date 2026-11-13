package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Navigation into the exact currently pinned receiver. Unselected source remains readable. */
record PreparationSourceRange(String section, int offset, int next) {
    private static final Pattern PYTHON_SECTION = Pattern.compile("(?m)^(?:@|def |class |if __name__ == )");

    static List<PreparationSourceRange> helper(String helper, String ecosystem, boolean diagnosed) throws IOException {
        var ranges = new ArrayList<PreparationSourceRange>();
        ranges.add(between(helper, "shellStartup", "#!/bin/bash -p\n", "\nhelper_path="));
        ranges.add(between(helper, "startupOwnership", "require_root() {\n", "\nrequire_app() {"));
        ranges.add(between(helper, "prepareDispatch", "    prepare-toolchains)\n", "    retain)"));
        ranges.add(between(helper, "pythonLauncher", "prepare_official_toolchains() {\n", "\nSHIPPED_CATALOG = "));
        int release = offset(helper, "def release(ecosystem, branch, exact):");
        ranges.add(between(helper, "releaseDownloadPoint", "def release(ecosystem, branch, exact):",
            "\n    if ecosystem == '", release));
        ranges.add(between(helper, "selectedRelease", "    if ecosystem == '" + ecosystem + "':\n",
            "\n    if ecosystem == '", release));
        for (String function : List.of("task_mirrors", "source_origin", "source_url", "toolchain_point", "fetch", "json_at", "download",
            "open_preparation_lock", "prepare", "owned_directory", "reuse_system", "official_url")) {
            ranges.add(python(helper, "def " + function + "("));
        }
        boolean archive = ecosystem.equals("GO") || ecosystem.equals("DOTNET");
        if (archive) {
            ranges.addAll(archiveInstallation(helper));
            ranges.add(between(helper, "archiveVersionProbe", "def probe(ecosystem, directory, expected):",
                "\n    if ecosystem == 'JAVA':\n        run("));
            ranges.add(python(helper, "def extract(archive, destination):"));
            if (ecosystem.equals("DOTNET")) ranges.add(between(helper, "dotnetSystemReuse",
                "def discover_system(ecosystem, identity):", "\n    binaries = "));
            else ranges.add(python(helper, "def discover_system("));
        } else if (ecosystem.equals("RUST") && helper.contains("\ndef install_rust_distribution(")) {
            ranges.add(python(helper, "def install_rust_distribution("));
            ranges.add(python(helper, "def install_with_rust_distribution("));
            ranges.add(python(helper, "def probe_managed_rust("));
            ranges.add(python(helper, "def probe_with_rust_distribution("));
        } else {
            ranges.add(python(helper, "def install(ecosystem, identity, url, algorithm, expected, layout):"));
            ranges.add(python(helper, "def probe(ecosystem, directory, expected):"));
        }
        if (!archive) ranges.add(python(helper, "def preparation_lock("));
        if (diagnosed) {
            if (archive) {
                ranges.add(between(helper, "preparationDiagnosticRun", "def installation_diagnostic_main(args, raw=None):",
                    "\n    owned_directory(ROOT)\n"));
            } else ranges.add(python(helper, "def installation_diagnostic_main("));
            ranges.add(between(helper, "diagnosticDispatch", "def main(args):\n",
                "    if args and args[0] in ('prepare-tracked'"));
        }
        if (buildsFromSource(ecosystem))
            ranges.add(python(helper, "def source_dependencies("));
        if (ecosystem.equals("RUBY")) {
            for (String function : List.of("ruby_unit", "ruby_probe", "ruby_publish", "ruby_source_install"))
                ranges.add(python(helper, "def " + function + "("));
        }
        return List.copyOf(ranges);
    }

    private static List<PreparationSourceRange> archiveInstallation(String helper) throws IOException {
        var install = python(helper, "def install(ecosystem, identity, url, algorithm, expected, layout):");
        int alternate = helper.indexOf("\n            else:\n", install.offset());
        int finish = helper.indexOf("\n            probe(ecosystem, destination, identity)", alternate);
        if (alternate < install.offset() || finish <= alternate || finish >= install.next() ||
            !helper.substring(install.offset(), alternate).contains("if layout in ('flat', 'archive'):"))
            throw new IOException("Fixed archive installation branch unavailable");
        return List.of(new PreparationSourceRange("archiveInstall", install.offset(), alternate),
            new PreparationSourceRange("archiveReceipt", finish, install.next()));
    }

    static List<PreparationSourceRange> transfer(String runtime, String ecosystem, boolean credentials,
                                                  boolean packageManager) throws IOException {
        var ranges = new ArrayList<PreparationSourceRange>();
        boolean fullTransport = ecosystem.equals("DOTNET");
        boolean routeExcerpt = fullTransport || ecosystem.equals("GO");
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
            ranges.add(between(runtime, "mirrorInitialRoute", "            selected = policy.validate_url(endpoint, policy.maps(endpoint, original))",
                "\n        while record['attempts']"));
            ranges.add(between(runtime, "mirrorTlsRedirectAndCommit", "                        def connect(self):\n",
                "\n            except (OSError, ValueError, MirrorFailure, urllib.error.URLError, http.client.HTTPException)",
                offset(runtime, "def _mirror_transfer(")));
            ranges.add(python(runtime, "def mirror_bytes("));
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
        if (end < 0 && section.equals("selectedRelease")) end = text.indexOf("\n    fail('request', 'unsupported installation adapter')", start);
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
