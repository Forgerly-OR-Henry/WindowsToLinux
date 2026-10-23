package gold.debug.windowstolinux.mod.deployment.service.agent.isolation;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;

/** Trusted caller policy. Model tools may supply an accepted plan, never replace this protected scope. */
public final class DeploymentAgentIsolationPolicy {
    static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public record Port(String transport,int port) {
        public Port {if(!Set.of("TCP","UDP").contains(transport)||port<1||port>65535)throw invalid();}
    }
    public record ProtectedApplication(UUID task,String application,String layoutSha256,Set<String> units,
        Set<String> candidates,Map<String,String> knownMetadata,Set<Port> ports) {
        public ProtectedApplication {
            Objects.requireNonNull(task);id(application);digest(layoutSha256);
            units=Set.copyOf(units);candidates=Set.copyOf(candidates);knownMetadata=Map.copyOf(knownMetadata);ports=Set.copyOf(ports);
            if(units.isEmpty()||units.size()>16||candidates.size()>16||knownMetadata.size()>32||ports.size()>64)throw invalid();
            units.forEach(DeploymentAgentIsolationPolicy::id);candidates.forEach(DeploymentAgentIsolationPolicy::id);
            knownMetadata.forEach((key,value)->{id(key);digest(value);});
        }
    }
    public record ResolvedComponent(String id,String unit,String candidate,Set<String> paths,Set<Port> ports,
        long workspaceBytes,long reserveBytes,String runtimeSha256,String resourcesSha256) {
        public ResolvedComponent {
            DeploymentAgentIsolationPolicy.id(id);DeploymentAgentIsolationPolicy.id(unit);DeploymentAgentIsolationPolicy.id(candidate);
            paths=Set.copyOf(paths);ports=Set.copyOf(ports);paths.forEach(DeploymentAgentIsolationPolicy::path);
            if(paths.isEmpty()||paths.size()>64||ports.size()>64||workspaceBytes<64L*1024*1024||workspaceBytes>128L*1024*1024*1024
                ||reserveBytes<1024L*1024*1024||reserveBytes>128L*1024*1024*1024)throw invalid();
            digest(runtimeSha256);digest(resourcesSha256);
        }
    }
    public record DiskDemand(String path,long requiredBytes,long reserveBytes) {
        public DiskDemand {DeploymentAgentIsolationPolicy.path(path);if(requiredBytes<0||requiredBytes>1024L*1024*1024*1024||reserveBytes<1024L*1024*1024||reserveBytes>128L*1024*1024*1024)throw invalid();}
    }
    /** Constructed by the real accepted-plan/selected-quota adapter, not by a parent-generated deployment plan. */
    public record PlannedScope(UUID task,String server,String targetSha256,String sourceSha256,String configurationSha256,
        String layoutSha256,String planSha256,List<ResolvedComponent> components,List<DiskDemand> additionalDiskDemands,
        long minimumMemAvailableBytes,int minimumCpuCount) {
        public PlannedScope {
            Objects.requireNonNull(task);id(server);for(String value:List.of(targetSha256,sourceSha256,configurationSha256,layoutSha256,planSha256))digest(value);
            components=List.copyOf(components);additionalDiskDemands=List.copyOf(additionalDiskDemands);
            if(components.isEmpty()||components.size()>16||additionalDiskDemands.size()>32||minimumMemAvailableBytes<=0||minimumCpuCount<1||minimumCpuCount>65536)throw invalid();
            if(components.stream().map(ResolvedComponent::id).distinct().count()!=components.size()
                ||components.stream().map(ResolvedComponent::unit).distinct().count()!=components.size()
                ||components.stream().map(ResolvedComponent::candidate).distinct().count()!=components.size())throw invalid();
        }
    }

    private final String run,newApplication;
    private final DeploymentRemoteLayout layout;
    private final List<ProtectedApplication> protectedApplications;
    private final Set<Port> requiredPorts;
    private final long minimumMemAvailableBytes;
    private final int minimumCpuCount;
    private DeploymentAgentIsolationPolicy(String run,String newApplication,DeploymentRemoteLayout layout,List<ProtectedApplication> protectedApplications,
        Set<Port> requiredPorts,long memory,int cpu) {
        id(run);id(newApplication);this.run=run;this.newApplication=newApplication;this.layout=Objects.requireNonNull(layout);
        this.protectedApplications=List.copyOf(protectedApplications);this.requiredPorts=Set.copyOf(requiredPorts);
        if(protectedApplications.isEmpty()||protectedApplications.size()>16||requiredPorts.size()>64||memory<0||cpu<1||cpu>65536)throw invalid();
        if(protectedApplications.stream().map(ProtectedApplication::application).distinct().count()!=protectedApplications.size())throw invalid();
        if(protectedApplications.stream().mapToInt(v->v.units().size()).sum()>16||protectedApplications.stream().mapToInt(v->v.candidates().size()).sum()>16)throw invalid();
        for(var old:protectedApplications)if(newApplication.equals(old.application())||newApplication.startsWith(old.application()+"-")
            ||old.application().startsWith(newApplication+"-")||!old.layoutSha256().equals(layout.digest()))throw invalid();
        this.minimumMemAvailableBytes=memory;this.minimumCpuCount=cpu;
    }
    public static DeploymentAgentIsolationPolicy trusted(String run,String newApplication,DeploymentRemoteLayout layout,List<ProtectedApplication> protectedApplications){return new DeploymentAgentIsolationPolicy(run,newApplication,layout,protectedApplications,Set.of(),0,1);}
    public DeploymentAgentIsolationPolicy requiringPorts(Set<Port> ports){return new DeploymentAgentIsolationPolicy(run,newApplication,layout,protectedApplications,ports,minimumMemAvailableBytes,minimumCpuCount);}
    public DeploymentAgentIsolationPolicy minimumResources(long bytes,int cpus){if(bytes<=0)throw invalid();return new DeploymentAgentIsolationPolicy(run,newApplication,layout,protectedApplications,requiredPorts,bytes,cpus);}
    public String run(){return run;}
    public String newApplication(){return newApplication;}
    public DeploymentRemoteLayout layout(){return layout;}
    public List<ProtectedApplication> protectedApplications(){return protectedApplications;}
    public Set<Port> requiredPorts(){return requiredPorts;}
    public long minimumMemAvailableBytes(){return minimumMemAvailableBytes;}
    public int minimumCpuCount(){return minimumCpuCount;}

    public void validate(PlannedScope scope) {
        if(minimumMemAvailableBytes<=0||!scope.layoutSha256().equals(layout.digest())||scope.minimumMemAvailableBytes()<minimumMemAvailableBytes||scope.minimumCpuCount()<minimumCpuCount)throw invalid();
        var units=new HashSet<String>();var candidates=new HashSet<String>();var ports=new HashSet<Port>();var paths=new HashSet<String>();
        for(var component:scope.components()){
            if(!component.unit().equals(DeploymentManagedUnitResolver.managedId(newApplication,component.id()))||!component.candidate().startsWith(component.unit()+"-")
                ||!units.add(component.unit())||!candidates.add(component.candidate()))throw invalid();
            layout.candidateRoot(component.candidate());
            if(!component.paths().containsAll(requiredPaths(component,scope.task())))throw invalid();
            var roots=Set.of(layout.applicationRoot(component.unit()),layout.configurationRoot(component.unit()),layout.dataRoot(component.unit()),
                layout.candidateRoot(component.candidate()),layout.snapshotRoot(component.unit(),scope.task()),layout.applicationLockRoot(component.unit()));
            var exact=requiredPaths(component,scope.task());
            for(String path:component.paths())if(!exact.contains(path)&&roots.stream().noneMatch(root->path.startsWith(root+"/")))throw invalid();
            paths.addAll(component.paths());ports.addAll(component.ports());
        }
        if(paths.size()>256||ports.size()>64||!ports.containsAll(requiredPorts))throw invalid();
        for(var old:protectedApplications){
            if(old.task().equals(scope.task())||!Collections.disjoint(ports,old.ports()))throw invalid();
            for(var unit:units)if(old.units().contains(unit))throw invalid();
            for(var candidate:candidates)if(old.candidates().contains(candidate))throw invalid();
        }
        for(String candidatePath:paths)for(String prefix:protectedPrefixes())
            if(candidatePath.startsWith(prefix)||prefix.startsWith(candidatePath+"/")||candidatePath.equals(prefix.substring(0,prefix.length()-1)))throw invalid();
    }
    public Set<String> requiredPaths(ResolvedComponent component,UUID task){return Set.of(layout.applicationRoot(component.unit()),layout.configurationRoot(component.unit()),layout.dataRoot(component.unit()),
        layout.candidateRoot(component.candidate()),layout.snapshotRoot(component.unit(),task),layout.applicationLockRoot(component.unit()),
        "/etc/systemd/system/windowstolinux-"+component.unit()+".service","/etc/systemd/system/windowstolinux-build-"+component.candidate()+".service");}
    public Set<String> protectedPrefixes(){
        var values=new TreeSet<String>();
        for(var old:protectedApplications)for(String root:List.of(layout.paths().applicationsRoot(),layout.paths().configurationRoot(),layout.paths().dataRoot(),layout.workRoot(),layout.snapshotsRoot(),layout.backupsRoot(),layout.paths().stateRoot()+"/application-locks",layout.paths().stateRoot()+"/secrets",layout.toolchains()+"/bindings"))values.add(root+"/"+old.application()+"-");
        for(var old:protectedApplications){values.add("/etc/systemd/system/windowstolinux-"+old.application()+"-");values.add("/etc/systemd/system/windowstolinux-build-"+old.application()+"-");}
        for(var old:protectedApplications){
            for(String unit:old.units()){
                for(String exact:List.of(layout.applicationRoot(unit),layout.configurationRoot(unit),layout.dataRoot(unit),layout.applicationLockRoot(unit),"/etc/systemd/system/windowstolinux-"+unit+".service"))values.add(exact+"/");
                values.add(layout.workRoot()+"/"+unit+"-");values.add(layout.snapshotsRoot()+"/"+unit+"-");values.add(layout.backupsRoot()+"/"+unit+"-");
            }
            for(String candidate:old.candidates()){values.add(layout.candidateRoot(candidate)+"/");values.add("/etc/systemd/system/windowstolinux-build-"+candidate+".service/");}
        }
        return Collections.unmodifiableSet(values);
    }
    Set<String> protectedMandatoryPaths(){
        var paths=new TreeSet<String>();
        for(var old:protectedApplications){
            for(String unit:old.units()){
                String root=layout.applicationRoot(unit),lock=layout.applicationLockRoot(unit);
                paths.addAll(List.of(root,root+"/current",layout.configurationRoot(unit),layout.dataRoot(unit),lock,lock+"/maintenance",layout.snapshotRoot(unit,old.task()),"/etc/systemd/system/windowstolinux-"+unit+".service"));
            }
            for(String candidate:old.candidates())paths.addAll(List.of(layout.candidateRoot(candidate),layout.candidateRoot(candidate)+"/.agent-task","/etc/systemd/system/windowstolinux-build-"+candidate+".service"));
        }
        return Set.copyOf(paths);
    }
    boolean protects(String path){return protectedPrefixes().stream().anyMatch(prefix->path.startsWith(prefix)||path.equals(prefix.substring(0,prefix.length()-1)));}
    public Map<String,Object> facts(){
        return Map.of("policy",document(),"policySha256",sha(document().toString()),"meaning","Trusted protected identities and required bounds; no old state is released or adopted. Resource minima are lower bounds, not a workload-performance guarantee. A probe is point-in-time admission, not an OS sandbox or future reservation.");
    }
    ObjectNode document(){
        var node=JSON.createObjectNode().put("run",run).put("newApplication",newApplication);node.set("layout",read(layout.canonical()));
        var old=node.putArray("protectedApplications");
        for(var item:protectedApplications.stream().sorted(Comparator.comparing(ProtectedApplication::application)).toList()){
            var row=old.addObject().put("task",item.task().toString()).put("application",item.application()).put("layoutSha256",item.layoutSha256());
            row.set("units",JSON.valueToTree(new TreeSet<>(item.units())));row.set("candidates",JSON.valueToTree(new TreeSet<>(item.candidates())));row.set("knownMetadata",JSON.valueToTree(new TreeMap<>(item.knownMetadata())));row.set("ports",ports(item.ports()));
        }
        node.set("requiredPorts",ports(requiredPorts));node.put("minimumMemAvailableBytes",minimumMemAvailableBytes).put("minimumCpuCount",minimumCpuCount);return node;
    }
    static ObjectNode scope(PlannedScope scope){
        var node=JSON.createObjectNode().put("task",scope.task().toString()).put("server",scope.server()).put("targetSha256",scope.targetSha256()).put("sourceSha256",scope.sourceSha256())
            .put("configurationSha256",scope.configurationSha256()).put("layoutSha256",scope.layoutSha256()).put("planSha256",scope.planSha256());
        var parts=node.putArray("components");for(var part:scope.components().stream().sorted(Comparator.comparing(ResolvedComponent::id)).toList()){
            var row=parts.addObject().put("id",part.id()).put("unit",part.unit()).put("candidate",part.candidate());row.set("paths",JSON.valueToTree(new TreeSet<>(part.paths())));row.set("ports",ports(part.ports()));
            row.put("workspaceBytes",part.workspaceBytes()).put("reserveBytes",part.reserveBytes()).put("runtimeSha256",part.runtimeSha256()).put("resourcesSha256",part.resourcesSha256());
        }
        var demands=node.putArray("additionalDiskDemands");for(var demand:scope.additionalDiskDemands().stream().sorted(Comparator.comparing(DiskDemand::path).thenComparingLong(DiskDemand::requiredBytes).thenComparingLong(DiskDemand::reserveBytes)).toList())demands.addObject().put("path",demand.path()).put("requiredBytes",demand.requiredBytes()).put("reserveBytes",demand.reserveBytes());
        node.put("minimumMemAvailableBytes",scope.minimumMemAvailableBytes()).put("minimumCpuCount",scope.minimumCpuCount());return node;
    }
    static DeploymentAgentIsolationPolicy decode(JsonNode node){
        exact(node,Set.of("run","newApplication","layout","protectedApplications","requiredPorts","minimumMemAvailableBytes","minimumCpuCount"));
        var layout=DeploymentRemoteLayout.decode(node.path("layout").toString());var old=new ArrayList<ProtectedApplication>();
        try{if(!node.path("protectedApplications").isArray()||node.path("protectedApplications").size()>16)throw invalid();for(var row:node.path("protectedApplications"))old.add(JSON.treeToValue(row,ProtectedApplication.class));
            var ports=JSON.treeToValue(node.path("requiredPorts"),Port[].class);
            return trusted(text(node,"run"),text(node,"newApplication"),layout,old).requiringPorts(Set.copyOf(Arrays.asList(ports))).minimumResources(number(node,"minimumMemAvailableBytes"),integer(node,"minimumCpuCount"));
        }catch(Exception malformed){throw invalid();}
    }
    static PlannedScope decodeScope(JsonNode node){try{var result=JSON.treeToValue(node,PlannedScope.class);if(!read(scope(result).toString()).equals(read(node.toString())))throw invalid();return result;}catch(Exception malformed){throw invalid();}}
    private static JsonNode ports(Set<Port> ports){return JSON.valueToTree(ports.stream().sorted(Comparator.comparing(Port::transport).thenComparingInt(Port::port)).toList());}
    static JsonNode read(String text){try{return JSON.readTree(text);}catch(Exception malformed){throw invalid();}}
    static void exact(JsonNode node,Set<String> fields){var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);if(!node.isObject()||!actual.equals(fields))throw invalid();}
    static String text(JsonNode node,String name){if(!node.path(name).isTextual())throw invalid();return node.path(name).textValue();}
    static long number(JsonNode node,String name){if(!node.path(name).isIntegralNumber()||!node.path(name).canConvertToLong()||node.path(name).longValue()<0)throw invalid();return node.path(name).longValue();}
    static int integer(JsonNode node,String name){if(!node.path(name).isIntegralNumber()||!node.path(name).canConvertToInt()||node.path(name).intValue()<0)throw invalid();return node.path(name).intValue();}
    static void id(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw invalid();}
    static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    static void path(String value){if(value==null||value.length()>512||!value.matches("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+")||Arrays.asList(value.split("/")).contains("..")||Arrays.asList(value.split("/")).contains("."))throw invalid();}
    static String sha(String value){return DeploymentAgentInputs.hash(value);}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Agent isolation policy or accepted scope is invalid");}
}
