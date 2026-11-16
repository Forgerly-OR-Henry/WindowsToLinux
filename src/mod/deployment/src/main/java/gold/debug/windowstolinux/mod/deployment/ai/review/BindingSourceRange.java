package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Exact excerpts from the pinned installed helper for the literal bind dispatch. */
record BindingSourceRange(String section, int offset, int next, String text) {
    private static final String PYTHON_OPEN = "\nprepare_official_toolchains() {\n";
    private static final String PYTHON_CLOSE = "\nWTL_OFFICIAL_TOOLCHAINS\n  )\n}\n";

    static List<BindingSourceRange> select(String helper, boolean managedDotnet, boolean localNode) throws IOException {
        int start = anchor(helper, PYTHON_OPEN) + 1;
        int end = anchor(helper, PYTHON_CLOSE) + PYTHON_CLOSE.length();
        if (end <= start) throw new IOException("Pinned binding receiver boundaries changed");
        String python = helper.substring(start, end);
        var ranges = new ArrayList<>(List.of(
            range(helper, 0, "bindDispatch", "\n    prepare-toolchains)\n", "\n    retain)"),
            range(helper, 0, "helperDispatchAdmission", "\nrequire_root\n[ \"$#\" -ge 1 ]", "\ncase \"$verb\" in\n    publish-deployment"),
            range(helper, 0, "rootAndDeployerChecks", "\nrequire_root() {\n", "\nrequire_app() {"),
            range(helper, 0, "ownerAndModeChecks", "\nassert_not_writable_by_others() {\n", "\nassert_candidate_for_deployer() {"),
            range(python, start, "pythonEntry", PYTHON_OPEN.substring(1), "\nSHIPPED_CATALOG = "),
            range(python, start, "versionComparison", "\ndef version_key(version):\n", "\ndef choose("),
            range(python, start, "lockAndOwnership", "\ndef preparation_process_options():\n", "\ndef tracked_process_started("),
            range(python, start, "processProbe", "\ndef run(command, cwd=None, environment=None):\n", "\ndef managed_dotnet_probe_output("),
            range(python, start, "ownedDirectory", "\ndef owned_directory(path):\n", "\ndef extract(archive, destination):"),
            range(python, start, "rehashSystemBinaries", "\ndef verify_system(record):\n", "\ndef installation_key("),
            range(python, start, "installedToolProbes", "\ndef probe(ecosystem, directory, expected):\n", "\ndef admitted("),
            range(python, start, "validateAndSeal", "\ndef admitted(", "\ndef existing_installation("),
            range(python, start, "sharedRootAndLock", "\ndef prepare_shared_directories():\n", "\nTRACKED_OPERATION ="),
            range(python, start, "mainAdmissionAndLock", "\ndef main(args):\n", "\n            binding = ROOT / 'bindings' / args[1]"),
            range(python, start, "literalBindBranch", "\n        if len(args) == 2 and args[0] == 'bind':\n",
                "\n        if len(args) == 2 and args[0] == 'restore':\n")
        ));
        if (localNode) {
            ranges.add(range(python, start, "shippedCatalogAndRoot", "\nSHIPPED_CATALOG = {\n", "\nMAX_METADATA = "));
        }
        if (managedDotnet) {
            for (var range : PreparationSourceRange.dotnetProbe(helper)) {
                ranges.add(new BindingSourceRange(range.section(), range.offset(), range.next(),
                    helper.substring(range.offset(), range.next())));
            }
        }
        return List.copyOf(ranges);
    }

    private static BindingSourceRange range(String body, int base, String section, String first, String after) throws IOException {
        int start = anchor(body, first);
        int end = anchor(body, after);
        if (end <= start) throw new IOException("Pinned binding source range changed: " + section);
        return new BindingSourceRange(section, base + start, base + end, body.substring(start, end));
    }

    private static int anchor(String body, String text) throws IOException {
        int start = body.indexOf(text);
        if (start < 0 || body.indexOf(text, start + 1) >= 0)
            throw new IOException("Pinned binding source anchor changed");
        return start;
    }
}
