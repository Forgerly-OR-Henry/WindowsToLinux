package gold.debug.windowstolinux.web.service.source;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.file.workspace.*;
import gold.debug.windowstolinux.web.file.upload.SourceArchiveUpload;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchivePreparer;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;
import gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot;
import gold.debug.windowstolinux.shared.git.*;
import gold.debug.windowstolinux.shared.git.snapshot.GitSnapshotPreparer;
import gold.debug.windowstolinux.shared.analyze.component.ProjectComponentDiscovery;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Browser-owned uploads and pinned Git snapshots; static reads and canonical archives only. */
public final class WebSourceService {
    private final WebResourceRepository repository;
    private final WebWorkspace workspace;
    private final java.time.Duration temporaryRetention;
    public WebSourceService(WebResourceRepository repository, WebWorkspace workspace, java.time.Duration temporaryRetention) {
        this.repository = repository; this.workspace = workspace; this.temporaryRetention = temporaryRetention;
    }

    /** Startup-only cleanup after task recovery, before accepting new requests. */
    public void recoverTemporary(WebRequestContext context) throws Exception {
        for(var row:repository.list(scope(context),ResourceType.SOURCE)) {
            var address=address(context,row.id());
            if("READY".equals(row.attributes().get("state"))) {
                if(workspace.exists(address))for(String child:List.of("snapshots","git","verify.tar.gz","upload.archive"))workspace.discardChild(address,child);
            } else if(java.time.Instant.parse(row.updatedAt()).isBefore(java.time.Instant.now().minus(temporaryRetention))) {
                if(workspace.exists(address))workspace.discard(address);
                var fields=new LinkedHashMap<>(row.attributes());fields.put("state","FAILED");
                repository.save(scope(context),ResourceType.SOURCE,row.id(),row.name(),fields,row.document(),row.version());
            }
        }
    }

    public JsonNode list(WebRequestContext context) throws Exception {
        return WebJson.tree(repository.list(scope(context), ResourceType.SOURCE).stream().map(WebSourceService::view).toList());
    }
    public JsonNode begin(WebRequestContext context, String name) throws Exception {
        String id = UUID.randomUUID().toString();
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("Use a plain project name");
        var fields = new LinkedHashMap<String, Object>();
        fields.put("state", "UPLOADING"); fields.put("kind", "UPLOAD"); fields.put("digest", null); fields.put("byte_count", 0);
        var stored = repository.save(scope(context), ResourceType.SOURCE, id, name, fields, "{}", 0);
        workspace.create(address(context, id)); return view(stored);
    }
    public synchronized void upload(WebRequestContext context, String id, String path, InputStream input) throws Exception {
        requireUploading(context, id); workspace.upload(address(context, id), path, input);
    }
    public synchronized void archive(WebRequestContext context, String id, String format, InputStream input) throws Exception {
        var stored = requireUploading(context, id);
        try { new SourceArchiveUpload(workspace).extract(address(context, id), format, input); }
        catch (Exception failure) {
            var fields = new LinkedHashMap<>(stored.attributes()); fields.put("state", "FAILED");
            repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields, stored.document(), stored.version());
            workspace.discard(address(context, id)); throw failure;
        }
    }
    public synchronized JsonNode finish(WebRequestContext context, String id) throws Exception {
        var stored = requireUploading(context, id); var address = address(context, id);
        SourceArchive archive = new SafeSourceArchivePreparer().archive(workspace.source(address), workspace.directory(address).resolve("source.tar.gz"));
        workspace.complete(address);
        var fields = new LinkedHashMap<>(stored.attributes()); fields.put("state", "READY"); fields.put("digest", archive.contentSha256()); fields.put("byte_count", archive.uncompressedByteCount());
        return view(repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields, "{}", stored.version()));
    }

    public PreparedWebOperation git(WebRequestContext context, JsonNode input) throws Exception {
        WebJson.fields(input, "url", "referenceKind", "reference", "name");
        GitRemote remote = GitRemote.parse(WebJson.text(input, "url", 2048));
        if (!remote.location().getScheme().equals("https")) throw new IllegalArgumentException("Web Git sources require credential-free HTTPS");
        String host = remote.host().orElseThrow();
        GitReference reference = switch (input.path("referenceKind").asText("default")) {
            case "default" -> new GitReference.DefaultBranch();
            case "branch" -> new GitReference.Branch(WebJson.text(input, "reference", 255));
            case "tag" -> new GitReference.Tag(WebJson.text(input, "reference", 255));
            case "commit" -> new GitReference.Commit(WebJson.text(input, "reference", 40));
            default -> throw new IllegalArgumentException("Invalid Git reference kind");
        };
        String name = input.path("name").asText(remote.location().getPath().replaceFirst(".*/", "").replaceFirst("\\.git$", ""));
        var created = begin(context, name); String id = created.path("id").asText();
        var request = new GitSourceRequest(remote, reference, Set.of(host), workspace.quota().projectBytes(), false);
        return new PreparedWebOperation("GIT_SNAPSHOT", input, List.of(), List.of(), false, id, null, null, interaction -> {
            var address = address(context, id);
            try {
                interaction.progress("SOURCE_FETCHING", WebJson.object().put("sourceId", id));
                Path gitRoot = Files.createDirectory(workspace.directory(address).resolve("git"));
                var snapshot = prepareGit(request,gitRoot,address);
                try (var archive = Files.newInputStream(snapshot.archive().archivePath())) {
                    new SourceArchiveUpload(workspace).extract(address, "tar.gz", archive);
                }
                workspace.discardChild(address,"git");
                interaction.checkCancelled();
                finish(context, id);
                var stored = require(context, id);
                var fields = new LinkedHashMap<>(stored.attributes()); fields.put("kind", "GIT");
                return view(repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields,
                        WebJson.write(Map.of("remote", remote.location().toString(), "commit", snapshot.commit())), stored.version()));
            } catch (Exception failure) {
                var stored = require(context, id); var fields = new LinkedHashMap<>(stored.attributes()); fields.put("state", "FAILED");
                repository.save(scope(context), ResourceType.SOURCE, id, stored.name(), fields, stored.document(), stored.version());
                workspace.discardChild(address,"git"); workspace.discard(address);
                throw failure;
            }
        });
    }

    private GitSnapshot prepareGit(GitSourceRequest request,Path directory,WorkspaceAddress address) throws Exception {
        Thread owner=Thread.currentThread();
        var exceeded=new java.util.concurrent.atomic.AtomicReference<Exception>();
        var monitor=Thread.ofVirtual().name("web-git-quota").start(() -> {
            try {
                while(!Thread.currentThread().isInterrupted()) { workspace.checkCapacity(address,0); Thread.sleep(250); }
            } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch(Exception failure) { exceeded.set(failure);owner.interrupt(); }
        });
        try { return new GitSnapshotPreparer().prepare(request,directory); }
        finally {
            monitor.interrupt(); boolean interrupted=Thread.interrupted();
            monitor.join();
            if(exceeded.get()!=null) throw exceeded.get();
            if(interrupted)owner.interrupt();
        }
    }

    public PreparedWebOperation analyze(WebRequestContext context, String id) throws Exception {
        requireReady(context, id);
        return new PreparedWebOperation("ANALYZE", WebJson.object().put("sourceId", id), List.of(), List.of(), false, id, null, null,
                interaction -> {
                    interaction.progress("SOURCE_ANALYZING", WebJson.object());
                    try (var snapshot = snapshot(context, id)) {
                        var components = new ArrayList<JsonNode>();
                        for (var component : new ProjectComponentDiscovery().discover(snapshot.directory())) {
                            var value = WebJson.object().put("id", component.id()).put("path", component.relativeRoot().toString().replace('\\', '/'));
                            value.set("types", WebJson.tree(component.types()));
                            if (component.types().size() == 1) {
                                var assessment = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(snapshot.directory().resolve(component.relativeRoot()), component.types().getFirst());
                                value.put("admission", assessment.admission().name());
                                value.set("rejections", WebJson.tree(assessment.rejections().stream().map(rejection -> rejection.code()).toList()));
                                assessment.facts().ifPresent(facts -> value.put("applicationId", facts.applicationId()).put("buildTool", facts.buildTool().name()));
                                assessment.runtimeSuggestion().ifPresent(suggestion -> value.set("suggestion", WebJson.tree(suggestion)));
                            }
                            components.add(value); interaction.checkCancelled();
                        }
                        return WebJson.object().put("sourceId", id).set("components", WebJson.tree(components));
                    }
                });
    }

    public synchronized SourceDirectorySnapshot snapshot(WebRequestContext context, String id) throws Exception {
        var stored = requireReady(context, id); var address = address(context, id);
        Path directory = workspace.directory(address);
        // Recompute from the frozen directory so local tampering cannot silently change a reviewed source.
        var archive = new SafeSourceArchivePreparer().archive(workspace.source(address), directory.resolve("verify.tar.gz"));
        try {
            if (!archive.contentSha256().equals(stored.attributes().get("digest"))) throw new IllegalStateException("Source digest changed; upload again");
            return SourceDirectorySnapshot.create(workspace.source(address), directory.resolve("snapshots"), stored.name());
        } finally { Files.deleteIfExists(directory.resolve("verify.tar.gz")); }
    }
    public Path operationDirectory(WebRequestContext context, String id) throws Exception {
        requireReady(context, id);
        return Files.createTempDirectory(workspace.directory(address(context, id)), "operation-");
    }
    public StoredResource requireReady(WebRequestContext context, String id) throws Exception {
        var stored = require(context, id);
        if (!"READY".equals(stored.attributes().get("state"))) throw new IllegalStateException("Source upload is not complete");
        return stored;
    }
    private StoredResource requireUploading(WebRequestContext context, String id) throws Exception {
        var stored = require(context, id);
        if (!"UPLOADING".equals(stored.attributes().get("state"))) throw new IllegalStateException("Source is not accepting uploads"); return stored;
    }
    private StoredResource require(WebRequestContext context, String id) throws Exception {
        return repository.find(scope(context), ResourceType.SOURCE, id).orElseThrow(() -> new NoSuchElementException("Source not found"));
    }
    private static JsonNode view(StoredResource row) {
        var value = WebJson.object().put("id", row.id()).put("name", row.name()).put("state", (String) row.attributes().get("state"))
                .put("kind", (String) row.attributes().get("kind")).put("digest", (String) row.attributes().get("digest"))
                .put("byteCount", ((Number) row.attributes().get("byte_count")).longValue()).put("createdAt", row.createdAt());
        value.set("provenance", WebJson.read(row.document())); return value;
    }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
    private static WorkspaceAddress address(WebRequestContext context, String id) { return new WorkspaceAddress(context.workspaceId(), id); }
}
