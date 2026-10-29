package gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** Controlled synthetic transport receipt; no field claims an actual Linux/kernel observation. */
public final class DependencyReceiptFixture {
    private DependencyReceiptFixture() {}

    public static Map<String, Object> lifecycle(String profile, String operation, long seconds, ServerId server, String task, DeploymentRemoteLayout layout) {
        var ownership = new TreeMap<String, Object>();
        ownership.put("formatVersion", 2);ownership.put("operation", operation);ownership.put("profile", profile);
        ownership.put("unit", DeploymentToolchainDependencies.unit(operation));ownership.put("invocationId", "b".repeat(32));
        ownership.put("workerSourceSha256", DeploymentToolchainDependencies.workerDigest(layout));
        ownership.put("description", "WTL_DEPENDENCY_2:" + operation + ":" + ownership.get("workerSourceSha256"));
        ownership.put("server", server.id());ownership.put("task", task);ownership.put("exitType", "cgroup");
        ownership.put("totalSeconds", seconds);ownership.put("runtimeSeconds", seconds - 30);
        ownership.put("stopSeconds", 5);ownership.put("reserveSeconds", 25);
        var ack = new TreeMap<String, Object>(ownership);
        for (String field : List.of("description", "stopSeconds", "reserveSeconds", "exitType")) ack.remove(field);
        ack.put("groupIdentity", List.of(29, 1001));ack.put("eventsIdentity", List.of(29, 1002));
        ack.put("coreEventsFilesystemMagic", 1667723888);ack.put("initialPopulated", 1);
        ack.put("freshReadMethod", "PREAD_OFFSET_ZERO");ack.put("cleanupOwnershipSha256", digest(ownership));
        var value = new LinkedHashMap<String, Object>(ownership);
        for (String field : List.of("operation", "profile", "description")) value.remove(field);
        for (String field : List.of("loadedCompletionObserved", "ackVerified", "stopCompleted", "launcherWaitCompleted", "coreEventsBound")) value.put(field, true);
        for (String field : List.of("groupIdentity", "eventsIdentity", "coreEventsFilesystemMagic", "initialPopulated", "freshReadMethod", "cleanupOwnershipSha256")) value.put(field, ack.get(field));
        value.put("proofKind", "KERNEL_POPULATED_ZERO");value.put("finalPopulated", 0);value.put("readErrno", 0);
        value.put("launcherExitCode", 0);value.put("ackSha256", digest(ack));return value;
    }

    private static String digest(Map<String, Object> value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(new ObjectMapper().writeValueAsBytes(new TreeMap<>(value))));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    public static String receipt(CommandRequest request, String manager, DeploymentRemoteLayout layout) {
        var args = DeploymentToolchainDependencies.arguments(request.script());
        var packages = new LinkedHashMap<String, String>();
        DeploymentToolchainDependencies.packages(manager, args.getFirst()).forEach(name -> packages.put(name, "1.0"));
        try {
            return "TOOLCHAIN_DEPENDENCIES=" + new ObjectMapper().writeValueAsString(Map.of("operation", args.get(1), "ecosystem", args.getFirst(),
                "manager", manager, "packages", packages, "lifecycle", lifecycle(args.getFirst(), args.get(1), Long.parseLong(args.get(2)), new ServerId(args.get(3)), args.get(4), layout))) + "\n";
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
