package gold.debug.windowstolinux.shared.analyze.ecosystem.node;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeBuildInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void selectsEachNamedPackageManagerArchitectureFromItsLockfile() throws Exception {
        Files.writeString(temporaryDirectory.resolve("package.json"),
                "{\"name\":\"demo\",\"scripts\":{\"build\":\"build\",\"start\":\"start\"}}");
        Map<String, DeploymentBuildToolType> architectures = new LinkedHashMap<>();
        architectures.put("package-lock.json", DeploymentBuildToolType.NPM);
        architectures.put("pnpm-lock.yaml", DeploymentBuildToolType.PNPM);
        architectures.put("yarn.lock", DeploymentBuildToolType.YARN);

        for (Map.Entry<String, DeploymentBuildToolType> architecture : architectures.entrySet()) {
            Path lockFile = temporaryDirectory.resolve(architecture.getKey());
            Files.writeString(lockFile, "lock");
            NodeBuildFacts facts = new NodeBuildInspector().inspect(temporaryDirectory).orElseThrow();
            assertEquals(architecture.getValue(), facts.buildTool());
            assertEquals(java.util.List.of(architecture.getKey()), facts.lockFiles());
            Files.delete(lockFile);
        }
    }
}
