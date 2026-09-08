package gold.debug.windowstolinux.shared.linux.sshd.ecosystem.db;

import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Native DB adapter using one fixed root-owned helper, with sensitive fields carried over stdin. */
public final class SshdNativeDatabasePort implements NativeDatabasePort {
    private final SshCommandExecutor commands;
    public SshdNativeDatabasePort(SshCommandExecutor commands) { this.commands = Objects.requireNonNull(commands); }

    @Override public Inventory inspectDatabase(DatabaseEngineType engine) throws LinuxOperationException {
        return inventory(invoke("inspect", Map.of("engine", engine.name())));
    }
    @Override public Inventory installDatabase(DatabaseEngineType engine, PackageCandidate target, Optional<Replacement> replacement) throws LinuxOperationException {
        Map<String, String> input = new LinkedHashMap<>(); input.put("engine", engine.name()); packageFields(input, target);
        replacement.ifPresent(approval -> {
            if (!target.equals(approval.target())) throw new IllegalArgumentException("replacement target changed");
            instanceFields(input, approval.previous()); input.put("approvedServer", approval.serverId());
            input.put("operation", approval.operation().toString()); input.put("replacementApproved", "true");
        });
        return inventory(invoke("install", input));
    }
    @Override public Instance startDatabase(Instance instance) throws LinuxOperationException {
        Map<String, String> input = new LinkedHashMap<>(); instanceFields(input, instance);
        return instance(invoke("start", input), "instance.");
    }
    @Override public Instance confirmDatabaseRestored(Instance instance, char[] administratorPassword) throws LinuxOperationException {
        Map<String, String> input = new LinkedHashMap<>(); instanceFields(input, instance);
        input.put("adminPassword", new String(administratorPassword));
        return instance(invoke("resume", input), "instance.");
    }
    @Override public Target inspectDatabaseTarget(Instance instance, String applicationId, String database, String username, char[] administratorPassword) throws LinuxOperationException {
        Map<String, String> input = targetInput(instance, applicationId, database, username); input.put("adminPassword", new String(administratorPassword));
        return target(invoke("target", input));
    }
    @Override public Target prepareDatabaseTarget(Instance instance, String applicationId, String database, String username,
            String secretIdentifier, long secretRevision, char[] applicationPassword, char[] administratorPassword) throws LinuxOperationException {
        Map<String, String> input = targetInput(instance, applicationId, database, username);
        input.put("secretIdentifier", secretIdentifier); input.put("secretRevision", Long.toString(secretRevision));
        input.put("applicationPassword", new String(applicationPassword)); input.put("adminPassword", new String(administratorPassword));
        return target(invoke("prepare", input));
    }
    @Override public Target initializeDatabase(Target target, String sourceSha256, byte[] sql, char[] applicationPassword,
            boolean existingSchemaChangeApproved) throws LinuxOperationException {
        if (sql.length > 2 * 1024 * 1024 || !sourceSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid DB initialization payload");
        Map<String, String> input = targetInput(target.instance(), target.applicationId(), target.database(), target.username());
        input.put("ownershipToken", target.ownershipToken()); input.put("sourceSha256", sourceSha256);
        input.put("sql", new String(sql, StandardCharsets.UTF_8)); input.put("applicationPassword", new String(applicationPassword));
        input.put("existingApproved", Boolean.toString(existingSchemaChangeApproved));
        return target(invoke("initialize", input));
    }

    private Map<String, String> invoke(String operation, Map<String, String> input) throws LinuxOperationException {
        StringBuilder encoded = new StringBuilder();
        input.forEach((key, value) -> encoded.append(key).append('=').append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))).append('\n'));
        byte[] payload = encoded.toString().getBytes(StandardCharsets.US_ASCII);
        try {
            var response = commands.execProtocolWithInput("sudo -n " + SshCommandExecutor.quote(ManagedHelperBundle.PATH)
                    + " native-db " + operation, payload, Duration.ofMinutes(operation.equals("install") ? 30 : 10));
            if (!response.succeeded()) throw new DatabaseFailure(FailureType.ACTION_FAILED);
            Map<String, String> values = decode(response.output());
            String status = required(values, "status");
            if (!status.equals("OK")) {
                FailureType failure;
                try { failure = FailureType.valueOf(status); } catch (IllegalArgumentException unknown) { failure = FailureType.ACTION_FAILED; }
                throw new DatabaseFailure(failure);
            }
            return values;
        } finally { Arrays.fill(payload, (byte) 0); }
    }

    static Map<String, String> decode(String output) {
        if (output.length() > 128 * 1024) throw new DatabaseFailure(FailureType.ACTION_FAILED);
        Map<String, String> result = new LinkedHashMap<>();
        try {
            for (String line : output.lines().toList()) {
                int delimiter = line.indexOf('=');
                if (delimiter < 1) throw new IllegalArgumentException();
                String key = line.substring(0, delimiter);
                String value = new String(Base64.getDecoder().decode(line.substring(delimiter + 1)), StandardCharsets.UTF_8);
                if (!key.matches("[a-zA-Z0-9.]{1,64}") || result.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) { throw new DatabaseFailure(FailureType.ACTION_FAILED); }
        return result;
    }
    private static Map<String, String> targetInput(Instance instance, String app, String database, String username) {
        Map<String, String> input = new LinkedHashMap<>(); instanceFields(input, instance);
        input.put("applicationId", app); input.put("database", database); input.put("username", username); return input;
    }
    private static void instanceFields(Map<String, String> values, Instance instance) {
        values.put("engine", instance.engine().name()); values.put("instanceId", instance.id()); values.put("fingerprint", instance.fingerprint());
    }
    private static void packageFields(Map<String, String> values, PackageCandidate candidate) {
        values.put("package", candidate.name()); values.put("packageVersion", candidate.packageVersion()); values.put("engineVersion", candidate.engineVersion());
    }
    private static Inventory inventory(Map<String, String> values) {
        DatabaseEngineType engine = DatabaseEngineType.valueOf(required(values, "engine"));
        List<Instance> instances = new ArrayList<>(); List<String> conflicts = new ArrayList<>();
        int count = count(values, "instanceCount"), issues = count(values, "conflictCount");
        for (int index = 0; index < count; index++) instances.add(instance(values, "instance." + index + "."));
        for (int index = 0; index < issues; index++) conflicts.add(required(values, "conflict." + index));
        Optional<PackageCandidate> candidate = values.containsKey("package") ? Optional.of(new PackageCandidate(
                required(values, "package"), required(values, "packageVersion"), required(values, "engineVersion"))) : Optional.empty();
        return new Inventory(engine, instances, candidate, conflicts);
    }
    private static int count(Map<String, String> values, String key) {
        int value = Integer.parseInt(required(values, key));
        if (value < 0 || value > 64) throw new DatabaseFailure(FailureType.ACTION_FAILED); return value;
    }
    private static Instance instance(Map<String, String> values, String prefix) {
        return new Instance(DatabaseEngineType.valueOf(required(values, prefix + "engine")), required(values, prefix + "id"),
                required(values, prefix + "version"), Integer.parseInt(required(values, prefix + "port")), required(values, prefix + "service"),
                required(values, prefix + "dataDirectory"), required(values, prefix + "fingerprint"), Boolean.parseBoolean(required(values, prefix + "running")));
    }
    private static Target target(Map<String, String> values) {
        return new Target(instance(values, "instance."), required(values, "applicationId"), required(values, "database"), required(values, "username"),
                Boolean.parseBoolean(required(values, "exists")), Boolean.parseBoolean(required(values, "empty")), required(values, "ownershipToken"),
                InitializationState.valueOf(required(values, "initialization")), required(values, "initializedSourceSha256"));
    }
    private static String required(Map<String, String> values, String key) {
        if (!values.containsKey(key)) throw new DatabaseFailure(FailureType.ACTION_FAILED); return values.get(key);
    }
}
