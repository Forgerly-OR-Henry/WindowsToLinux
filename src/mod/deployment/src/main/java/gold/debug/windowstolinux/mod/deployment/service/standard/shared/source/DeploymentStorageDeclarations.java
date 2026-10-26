package gold.debug.windowstolinux.mod.deployment.service.standard.shared.source;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.Answers;

/** Source-declared file trees become real reviewed bindings before final publication consent. */
public final class DeploymentStorageDeclarations {
    private static final String FILE = "windowstolinux-storage.properties";
    private static final Set<String> FIELDS = Set.of("kind", "access", "location", "hostPath", "mode", "seed", "schema", "reversible");
    private DeploymentStorageDeclarations() { }

    /** Rejects a present malformed declaration instead of deploying without its persistence scope. */
    public static final class InvalidDeclaration extends IllegalArgumentException {
        InvalidDeclaration(String message, Exception cause) { super(message, cause); }
    }

    public static Answers apply(DeploymentSourceMaterial material, DiscoveredProjectPart component, Answers original) {
        Path snapshot = material.snapshot().directory().toAbsolutePath().normalize();
        Path root = snapshot.resolve(component.relativeRoot()).normalize();
        if (!root.startsWith(snapshot)) throw new InvalidDeclaration("Storage source escapes its frozen component", null);
        var declared = read(root);
        if (declared.isEmpty()) return original;
        var bindings = new LinkedHashMap<String, DeploymentFileBinding>();
        declared.forEach(binding -> bindings.put(binding.bindingId(), binding));
        var reviewed = new HashSet<String>();
        for (var binding : original.files()) {
            if (!reviewed.add(binding.bindingId())) throw new InvalidDeclaration("Duplicate reviewed file binding", null);
            // Explicit reviewer choices retain priority over the source's defaults.
            bindings.put(binding.bindingId(), binding);
        }
        if (bindings.size() > 32) throw new InvalidDeclaration("Combined file bindings exceed the helper limit", null);
        return new Answers(original.values(), List.copyOf(bindings.values()), original.limits(), original.containerRisk(),
            original.adapterRisk(), original.workspaceRequest(), original.workspaceSelection());
    }

    private static List<DeploymentFileBinding> read(Path root) {
        Path declaration = root.resolve(FILE);
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Storage component root is unavailable");
            java.nio.file.attribute.BasicFileAttributes attributes;
            try { attributes = Files.readAttributes(declaration, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException absent) { return List.of(); }
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() > 65_536)
                throw new IllegalArgumentException("Storage declaration is not a bounded regular source file");
            var values = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    if (containsKey(key)) throw new IllegalArgumentException("Duplicate storage declaration field");
                    return super.put(key, value);
                }
            };
            String text = Files.readString(declaration);
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536)
                throw new IllegalArgumentException("Storage declaration exceeds its byte limit");
            values.load(new StringReader(text));
            var ids = Arrays.stream(values.getProperty("storage", "").split(",", -1)).map(String::trim).toList();
            if (ids.isEmpty() || ids.size() > 32 || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> !id.matches("[a-z0-9][a-z0-9-]{0,62}")))
                throw new IllegalArgumentException("Storage declarations require 1..32 unique bounded identifiers");
            var allowed = new HashSet<String>();
            allowed.add("storage");
            for (String id : ids) for (String field : FIELDS) allowed.add("storage." + id + "." + field);
            if (!allowed.containsAll(values.stringPropertyNames())) throw new IllegalArgumentException("Unknown storage declaration field");
            var result = new ArrayList<DeploymentFileBinding>();
            for (String id : ids) {
                String prefix = "storage." + id + ".";
                if (!values.getProperty(prefix + "kind", "").trim().equals("FILE"))
                    throw new IllegalArgumentException("Only explicit FILE declarations are supported here");
                String mode = values.getProperty(prefix + "mode", "").trim();
                if (!Set.of("rw", "ro").contains(mode)) throw new IllegalArgumentException("Storage access mode must be rw or ro");
                var access = mode.equals("rw") ? DeploymentStoragePath.AccessMode.READ_WRITE : DeploymentStoragePath.AccessMode.READ_ONLY;
                String reversible = values.getProperty(prefix + "reversible", mode.equals("ro") ? "true" : "false").trim();
                if (!Set.of("true", "false").contains(reversible)) throw new IllegalArgumentException("Invalid storage reversibility");
                var path = new DeploymentStoragePath(values.getProperty(prefix + "access", "").trim(), access,
                    values.getProperty(prefix + "schema", "files").trim(), Boolean.parseBoolean(reversible));
                var location = new DeploymentStorageLocation(DeploymentStorageLocation.StorageLocationType.valueOf(
                    values.getProperty(prefix + "location", "").trim()), values.getProperty(prefix + "hostPath", "").trim());
                if (location.type() == DeploymentStorageLocation.StorageLocationType.UNRESOLVED)
                    throw new IllegalArgumentException("Storage location must be explicit");
                String seed = gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentSqliteRequirement.relativeSourceFile(
                    values.getProperty(prefix + "seed", "").trim());
                if (!seed.isEmpty()) {
                    Path source = root.resolve(seed).normalize();
                    if (!source.startsWith(root) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
                        throw new IllegalArgumentException("File seed must be a directory inside the frozen component");
                    Path ancestor = root;
                    for (Path part : root.relativize(source)) {
                        ancestor = ancestor.resolve(part);
                        if (Files.isSymbolicLink(ancestor)) throw new IllegalArgumentException("Storage seed cannot cross a source symlink");
                    }
                }
                result.add(new DeploymentFileBinding(id, path, location, DeploymentStorageLocation.StorageResourceType.FILE, seed, ""));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new InvalidDeclaration("Invalid frozen file-storage declaration", invalid);
        }
    }
}
