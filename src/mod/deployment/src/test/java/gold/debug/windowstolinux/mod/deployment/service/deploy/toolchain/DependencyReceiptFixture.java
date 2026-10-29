package gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** Fake transport receipt only; these booleans do not claim a Linux/kernel observation. */
public final class DependencyReceiptFixture {
    private DependencyReceiptFixture() {}

    public static Map<String, Object> lifecycle(String operation, long seconds, ServerId server, String task, DeploymentRemoteLayout layout) {
        var value = new LinkedHashMap<String, Object>();
        value.put("formatVersion", 1);value.put("unit", DeploymentToolchainDependencies.unit(operation));
        value.put("invocationId", "b".repeat(32));value.put("workerSourceSha256", DeploymentToolchainDependencies.workerDigest(layout));
        value.put("totalSeconds", seconds);value.put("runtimeSeconds", seconds - 30);value.put("stopSeconds", 5);value.put("reserveSeconds", 25);
        value.put("loadedCompletionObserved", true);value.put("recursiveCgroupEmptyObserved", true);value.put("stopCompleted", true);
        value.put("server", server.id());value.put("task", task);return value;
    }

    public static String receipt(CommandRequest request, String manager, DeploymentRemoteLayout layout) {
        var args = DeploymentToolchainDependencies.arguments(request.script());
        var packages = new LinkedHashMap<String, String>();
        DeploymentToolchainDependencies.packages(manager, args.getFirst()).forEach(name -> packages.put(name, "1.0"));
        try {
            return "TOOLCHAIN_DEPENDENCIES=" + new ObjectMapper().writeValueAsString(Map.of("operation", args.get(1), "ecosystem", args.getFirst(),
                "manager", manager, "packages", packages, "lifecycle", lifecycle(args.get(1), Long.parseLong(args.get(2)), new ServerId(args.get(3)), args.get(4), layout))) + "\n";
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
