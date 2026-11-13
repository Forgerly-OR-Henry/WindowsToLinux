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
        ranges.add(between(helper, "startupAndOwnership", "#!/bin/bash -p\n", "\nrequire_app() {"));
        ranges.add(between(helper, "prepareDispatch", "    prepare-toolchains)\n", "    retain)"));
        ranges.add(between(helper, "pythonLauncher", "prepare_official_toolchains() {\n", "\nSHIPPED_CATALOG = "));
        ranges.add(between(helper, "selectedRelease", "    if ecosystem == '" + ecosystem + "':\n",
            "\n    if ecosystem == '", offset(helper, "def release(ecosystem, branch, exact):")));
        for (String function : List.of("task_mirrors", "fetch", "download", "open_preparation_lock", "preparation_lock",
            "prepare", "probe", "owned_directory", "reuse_system", "official_url")) {
            ranges.add(python(helper, "def " + function + "("));
        }
        ranges.add(python(helper, "def install(ecosystem, identity, url, algorithm, expected, layout):"));
        if (diagnosed) {
            ranges.add(python(helper, "def installation_diagnostic_main("));
            ranges.add(between(helper, "diagnosticDispatch", "def main(args):\n",
                "    if args and args[0] in ('prepare-tracked'"));
        }
        if (buildsFromSource(ecosystem))
            ranges.add(python(helper, "def source_dependencies("));
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
