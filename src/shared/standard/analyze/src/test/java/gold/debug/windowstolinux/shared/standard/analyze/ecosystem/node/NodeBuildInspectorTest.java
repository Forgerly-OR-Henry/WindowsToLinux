package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeBuildInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsEachNamedPackageManagerArchitectureFromItsLockfile() throws Exception {
        Map<String, Architecture> architectures = new LinkedHashMap<>();
        architectures.put("package-lock.json", new Architecture(DeploymentBuildToolType.NPM, null,
                "{\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"demo\"}}}"));
        architectures.put("pnpm-lock.yaml", new Architecture(DeploymentBuildToolType.PNPM, "pnpm@9.15.0",
                "lockfileVersion: '9.0'\nsettings: {}\nimporters:\n  .: {}\n"));
        architectures.put("yarn.lock", new Architecture(DeploymentBuildToolType.YARN, "yarn@4.6.0",
                "__metadata:\n  version: 8\n  cacheKey: 10c0\n"));

        for (Map.Entry<String, Architecture> architecture : architectures.entrySet()) {
            String packageManager = architecture.getValue().packageManager() == null
                    ? ""
                    : ",\"packageManager\":\"" + architecture.getValue().packageManager() + "\"";
            Files.writeString(temporaryDirectory.resolve("package.json"),
                    "{\"name\":\"demo\"" + packageManager + ",\"scripts\":{\"build\":\"build\",\"start\":\"start\"}}");
            Path lockFile = temporaryDirectory.resolve(architecture.getKey());
            Files.writeString(lockFile, architecture.getValue().lockContent());
            NodeBuildFacts facts = new NodeBuildInspector().inspect(temporaryDirectory).orElseThrow();
            assertEquals(architecture.getValue().tool(), facts.buildTool());
            assertEquals(java.util.List.of(architecture.getKey()), facts.lockFiles());
            Files.delete(lockFile);
        }
    }

    @Test
    void refusesMalformedOrUnsupportedPackageManagerLocksBeforePlanning() throws Exception {
        Files.writeString(temporaryDirectory.resolve("package.json"),
                "{\"name\":\"demo\",\"packageManager\":\"yarn@1.22.22\","
                        + "\"scripts\":{\"build\":\"build\",\"start\":\"start\"}}");
        Files.writeString(temporaryDirectory.resolve("yarn.lock"), "# yarn lockfile v1\n");

        assertTrue(new NodeBuildInspector().inspect(temporaryDirectory).orElseThrow().lockFiles().isEmpty());
    }

    private record Architecture(DeploymentBuildToolType tool, String packageManager, String lockContent) {
    }
}
