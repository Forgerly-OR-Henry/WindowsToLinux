package gold.debug.windowstolinux.shared.config.persistence.serialization;

import gold.debug.windowstolinux.shared.model.project.application.*;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import java.io.*;
import java.util.*;

/** Canonical bounded workload payload shared by persistence and release identity. / 持久化与发布身份共用的有界执行载荷。 */
public final class ApplicationWorkloadCodec {
    private ApplicationWorkloadCodec() { }

    public static void write(DataOutput output, ApplicationWorkload value) throws IOException {
        output.writeUTF(value.mode().name()); output.writeBoolean(value.reviewed());
        writeCommand(output, value.command()); output.writeUTF(value.workingDirectory()); output.writeUTF(value.buildDirectory());
        output.writeInt(value.endpoints().size());
        for (var endpoint : value.endpoints()) {
            output.writeUTF(endpoint.id()); output.writeUTF(endpoint.protocol().name()); output.writeUTF(endpoint.bindAddress());
            output.writeInt(endpoint.hostPort()); output.writeInt(endpoint.targetPort());
            output.writeUTF(endpoint.exposure().name()); output.writeUTF(endpoint.accessUrl());
        }
        writeOptional(output, value.verification()); output.writeUTF(value.expectedOutput()); writeOptional(output, value.client());
        output.writeInt(value.inputs().size());
        for (var input : value.inputs()) { output.writeUTF(input.id()); output.writeUTF(input.hostPath()); output.writeUTF(input.accessPath()); }
        output.writeInt(value.companions().size());
        for (var item : value.companions()) {
            output.writeUTF(item.id()); output.writeUTF(item.sourcePath()); output.writeUTF(item.projectType().name());
            output.writeUTF(item.artifactPath()); output.writeUTF(item.environment());
        }
        if (!value.workers().isEmpty()) {
            output.writeInt(value.workers().size());
            for (var worker : value.workers()) { output.writeUTF(worker.id()); writeCommand(output, worker.command()); }
        }
    }

    public static ApplicationWorkload read(DataInput input, boolean hasWorkers) throws IOException {
        var mode = ApplicationWorkload.ExecutionMode.valueOf(input.readUTF()); boolean reviewed = bool(input);
        var command = readCommand(input); String directory = input.readUTF(), buildDirectory = input.readUTF();
        var endpoints = new ArrayList<ApplicationEndpoint>();
        for (int n = count(input, 32); n > 0; n--) endpoints.add(new ApplicationEndpoint(input.readUTF(),
                ApplicationEndpoint.ProtocolType.valueOf(input.readUTF()), input.readUTF(), input.readInt(), input.readInt(),
                ApplicationEndpoint.ExposureType.valueOf(input.readUTF()), input.readUTF()));
        var verification = readOptional(input); String expected = input.readUTF(); var client = readOptional(input);
        var inputs = new ArrayList<ApplicationInput>();
        for (int n = count(input, 32); n > 0; n--) inputs.add(new ApplicationInput(input.readUTF(), input.readUTF(), input.readUTF()));
        var companions = new ArrayList<ApplicationCompanion>();
        for (int n = count(input, 32); n > 0; n--) companions.add(new ApplicationCompanion(input.readUTF(), input.readUTF(),
                ApplicationCompanion.BuildType.valueOf(input.readUTF()), input.readUTF(), input.readUTF()));
        var workers = new ArrayList<ApplicationWorker>();
        if (hasWorkers) {
            int size = count(input, 8);
            if (size == 0) throw new IOException("worker runtime must declare workers");
            for (int n = size; n > 0; n--) workers.add(new ApplicationWorker(input.readUTF(), readCommand(input)));
        }
        return new ApplicationWorkload(mode, reviewed, command, directory, endpoints, verification, expected, client, inputs, companions, buildDirectory, workers);
    }

    public static void writeCommand(DataOutput output, ApplicationCommand value) throws IOException {
        output.writeUTF(value.entrypoint()); output.writeInt(value.arguments().size());
        for (String arg : value.arguments()) output.writeUTF(arg);
    }
    public static ApplicationCommand readCommand(DataInput input) throws IOException {
        String entrypoint = input.readUTF(); var arguments = new ArrayList<String>();
        for (int n = count(input, 64); n > 0; n--) arguments.add(input.readUTF());
        return new ApplicationCommand(entrypoint, arguments);
    }
    public static void writeOptional(DataOutput output, Optional<ApplicationCommand> command) throws IOException {
        output.writeBoolean(command.isPresent()); if (command.isPresent()) writeCommand(output, command.orElseThrow());
    }
    public static Optional<ApplicationCommand> readOptional(DataInput input) throws IOException {
        return bool(input) ? Optional.of(readCommand(input)) : Optional.empty();
    }
    private static int count(DataInput input, int max) throws IOException {
        int count = input.readInt(); if (count < 0 || count > max) throw new IOException("invalid workload collection size");
        return count;
    }
    private static boolean bool(DataInput input) throws IOException {
        return switch (input.readUnsignedByte()) { case 0 -> false; case 1 -> true; default -> throw new IOException("invalid workload boolean"); };
    }
}
