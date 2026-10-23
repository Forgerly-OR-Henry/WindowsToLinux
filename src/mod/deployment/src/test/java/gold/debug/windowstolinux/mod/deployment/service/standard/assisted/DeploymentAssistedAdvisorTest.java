package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import org.junit.jupiter.api.Test;

class DeploymentAssistedAdvisorTest {
    @Test
    void exposesOnlyAllowlistedReportedToolNamesWithoutPathsOrProtectedValues() {
        var problem = new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_BUILD_FAILED", "Private compiler output",
            Map.of("exit", "69", "diagnostic", "BUILD_MISSING_TOOL=curl\nBUILD_MISSING_TOOL=curl\n"
                + "BUILD_MISSING_TOOL=/private/php\nBUILD_MISSING_TOOL=protected-fixture-value\n"
                + "BUILD_TOOL_REJECTED=php\nBUILD_TOOL_REJECTED=php protected-fixture-value\n"), Optional.empty());
        assertEquals(Map.of("exitCode", 69, "reportedMissingBuildTools", List.of("curl"), "reportedRejectedBuildTools", List.of("php")),
            DeploymentAssistedAdvisor.failureDetails("BUILD", problem));
    }

    @Test
    void exposesNumericBuildEvidenceWithoutCompilerTextOrProtectedContext() {
        var problem = new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_BUILD_FAILED", "Private compiler output",
            Map.of("exit", "64", "diagnostic", "BUILD_UID=1234\nPRIVATE_VALUE=protected-fixture-value\nBUILD_FAILURE_LINE=214\n",
                "credential", "protected-fixture-value"), Optional.empty());
        assertEquals(Map.of("exitCode", 64, "reportedFailureLines", List.of(214)),
            DeploymentAssistedAdvisor.failureDetails("BUILD", problem));
        assertEquals(Map.of(), DeploymentAssistedAdvisor.failureDetails("HEALTH", problem));
    }

    @Test
    void sharesCrossLanguageToolIdentifiersAndBoundsReportedMissingTools() {
        var problem = new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_BUILD_FAILED", "Untrusted build report",
            Map.of("diagnostic", "BUILD_MISSING_TOOL=cmake\nBUILD_MISSING_TOOL=ninja\nBUILD_MISSING_TOOL=java\n"
                + "BUILD_MISSING_TOOL=python3\nBUILD_MISSING_TOOL=cargo\nBUILD_TOOL_REJECTED=dotnet\n"
                + "BUILD_TOOL_REJECTED=ruby\nBUILD_TOOL_REJECTED=/private/node\n"), Optional.empty());
        assertEquals(Map.of("reportedMissingBuildTools", List.of("cmake", "ninja", "java", "python3"),
            "reportedRejectedBuildTools", List.of("dotnet", "ruby")), DeploymentAssistedAdvisor.failureDetails("BUILD", problem));
    }

    @Test
    void rejectsMalformedNumbersAndBoundsTheDiagnosticList() {
        var problem = new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_BUILD_FAILED", "Private compiler output",
            Map.of("exit", "64\nPRIVATE_VALUE=protected-fixture-value", "diagnostic",
                "BUILD_FAILURE_LINE=protected-fixture-value\nBUILD_FAILURE_LINE=12345678\n"
                    + "BUILD_FAILURE_LINE=1\nBUILD_FAILURE_LINE=2\nBUILD_FAILURE_LINE=3\nBUILD_FAILURE_LINE=4\nBUILD_FAILURE_LINE=5\n"), Optional.empty());
        assertEquals(Map.of("reportedFailureLines", List.of(1, 2, 3, 4)),
            DeploymentAssistedAdvisor.failureDetails("BUILD", problem));
    }
}
