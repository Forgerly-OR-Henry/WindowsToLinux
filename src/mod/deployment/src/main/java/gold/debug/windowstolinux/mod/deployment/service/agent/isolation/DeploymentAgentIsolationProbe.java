package gold.debug.windowstolinux.mod.deployment.service.agent.isolation;

import static gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** Fixed, bounded, read-only metadata probe; it neither installs nor grants authority. */
public final class DeploymentAgentIsolationProbe {
    public static final String OPERATION="deployment.agent.isolation.preflight.v1";
    public static final String FORMAT="WTL_AGENT_ISOLATION_PREFLIGHT_1";
    public static final String FRAME="AGENT_ISOLATION_PREFLIGHT=";
    private static final Duration TIMEOUT=Duration.ofSeconds(30);
    private static final long LIMIT=32768;
    public enum Mode {ADMISSION,PROTECTED_RECHECK}
    private static final String SUFFIX="\nset -fu\nLC_ALL=C\nbuiltin export LC_ALL\nif [[ ${BASH_ENV+x} != x || ${ENV+x} != x || \"$BASH_ENV\" != /dev/null || \"$ENV\" != /dev/null || ${LD_PRELOAD+x} || ${LD_LIBRARY_PATH+x} ]]; then exit 65; fi\nbuiltin unset BASH_ENV ENV || exit 65\nexec /usr/bin/python3 -I -S - \"${wtl_isolation:?}\"\n";
    private final DeploymentAgentIsolationPolicy policy;
    public record Request(CommandRequest command,Set<ActionTarget> resourceTargets,DeploymentAgentIsolationPolicy policy,
        PlannedScope scope,UUID action,String bindingSha256,Mode mode,UUID queryTask)implements AutoCloseable {
        public Request {resourceTargets=Set.copyOf(resourceTargets);Objects.requireNonNull(command);Objects.requireNonNull(policy);Objects.requireNonNull(scope);Objects.requireNonNull(action);digest(bindingSha256);Objects.requireNonNull(mode);Objects.requireNonNull(queryTask);}
        @Override public void close(){command.close();}
    }
    public record Receipt(String canonicalJson){public Receipt {Objects.requireNonNull(canonicalJson);}}
    public DeploymentAgentIsolationProbe(DeploymentAgentIsolationPolicy policy){this.policy=Objects.requireNonNull(policy);}
    public String source(){return SOURCE;}
    public String sourceVersion(){return sha(SOURCE);}
    public Request request(PlannedScope scope,UUID action){return request(scope,action,Mode.ADMISSION,scope.task());}
    public Request requestProtectedRecheck(PlannedScope originalScope,UUID queryTask,UUID action){return request(originalScope,action,Mode.PROTECTED_RECHECK,queryTask);}
    private Request request(PlannedScope scope,UUID action,Mode mode,UUID queryTask){
        policy.validate(scope);Objects.requireNonNull(action);
        if(mode==Mode.ADMISSION&&!queryTask.equals(scope.task()))throw invalid();
        var binding=JSON.createObjectNode();binding.set("policy",policy.document());binding.set("scope",scope(scope));binding.put("mode",mode.name()).put("queryTask",queryTask.toString());
        String bindingSha=sha(binding.toString());var envelope=JSON.createObjectNode();envelope.set("binding",binding);envelope.put("action",action.toString()).put("sourceVersion",sourceVersion()).put("bindingSha256",bindingSha);
        String argument=envelope.toString();if(argument.getBytes(StandardCharsets.UTF_8).length>49152||argument.indexOf('\'')>=0)throw invalid();
        var targets=new HashSet<ActionTarget>();targets.add(new ActionTarget(scope.server(),"task",queryTask.toString()));targets.add(new ActionTarget(scope.server(),"agent-isolation-policy",sha(policy.document().toString())));
        targets.add(new ActionTarget(scope.server(),"host-inspection","paths-ports-filesystems-memory-cpu"));
        for(var component:scope.components())targets.add(new ActionTarget(scope.server(),"application",component.unit()));
        return new Request(new CommandRequest("wtl_isolation='"+argument+"'"+SUFFIX,SOURCE.getBytes(StandardCharsets.UTF_8),TIMEOUT,LIMIT),targets,policy,scope,action,bindingSha,mode,queryTask);
    }
    /** Pure review reconstruction, never a replacement for the caller's trusted policy or a grant. */
    public static Request decode(CommandRequest command,DeploymentRemoteLayout layout){
        String script=command.script(),prefix="wtl_isolation='";int at=script.indexOf("'\n",prefix.length());
        if(!script.startsWith(prefix)||at<0||!script.substring(at+1).equals(SUFFIX))throw invalid();
        var envelope=read(script.substring(prefix.length(),at));exact(envelope,Set.of("binding","action","sourceVersion","bindingSha256"));exact(envelope.path("binding"),Set.of("policy","scope","mode","queryTask"));
        var policy=DeploymentAgentIsolationPolicy.decode(envelope.path("binding").path("policy"));
        if(!policy.layout().equals(layout))throw invalid();var scope=decodeScope(envelope.path("binding").path("scope"));UUID action=uuid(text(envelope,"action"));
        var program=new DeploymentAgentIsolationProbe(policy);var expected=program.request(scope,action,Mode.valueOf(text(envelope.path("binding"),"mode")),uuid(text(envelope.path("binding"),"queryTask")));
        if(!text(envelope,"sourceVersion").equals(program.sourceVersion())||!text(envelope,"bindingSha256").equals(expected.bindingSha256())
            ||!script.equals(expected.command().script())||!Arrays.equals(command.stdin(),expected.command().stdin())||!command.timeout().equals(TIMEOUT)||command.outputLimitBytes()!=LIMIT){expected.close();throw invalid();}
        return expected;
    }
    public Receipt parse(Request request,OperationResult<CommandResult> actual){
        if(actual.outcome()!=TaskResult.SUCCEEDED||actual.value().isEmpty())throw invalid();var value=actual.value().orElseThrow();String text=value.stdout();
        if(value.truncated()||value.exitCode().isEmpty()||value.exitCode().orElseThrow()!=0||!value.stderr().isEmpty()||!text.startsWith(FRAME)||!text.endsWith("\n")
            ||text.indexOf('\n')!=text.length()-1||text.indexOf('\r')>=0||text.getBytes(StandardCharsets.UTF_8).length>LIMIT)throw invalid();
        return validateReceipt(request,text.substring(FRAME.length(),text.length()-1));
    }
    public Receipt validateReceipt(Request request,String raw){
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length+FRAME.length()+1>LIMIT)throw invalid();
        try(var expected=decode(request.command(),policy.layout())){
            if(!expected.bindingSha256().equals(request.bindingSha256())||!expected.resourceTargets().equals(request.resourceTargets())||!expected.action().equals(request.action())
                ||!scope(expected.scope()).equals(scope(request.scope()))||!policy.document().equals(expected.policy().document()))throw invalid();
        }
        var node=read(raw);exact(node,Set.of("format","sourceVersion","action","bindingSha256","policySha256","scopeSha256","mode","queryTask","status","reason","pathsChecked","unitsChecked","portsChecked","allocatedCandidates","filesystems","memoryAvailableBytes","cpuCount","stable","protectedMetadata"));
        if(!node.toString().equals(raw)||!text(node,"format").equals(FORMAT)||!text(node,"sourceVersion").equals(sourceVersion())||!uuid(text(node,"action")).equals(request.action())
            ||!text(node,"bindingSha256").equals(request.bindingSha256())||!text(node,"policySha256").equals(sha(policy.document().toString()))||!text(node,"scopeSha256").equals(sha(scope(request.scope()).toString()))
            ||!text(node,"mode").equals(request.mode().name())||!uuid(text(node,"queryTask")).equals(request.queryTask())
            ||!Set.of("READY","OBSERVED","REFUSED","UNKNOWN").contains(text(node,"status"))||!Set.of("READY","OBSERVED","ENVIRONMENT","PATH_UNSAFE","PATH_EXISTS","FOREIGN_OWNER","UNIT_EXISTS","UNITS_UNCONFIRMED","PORT_BUSY","PORTS_UNCONFIRMED","DISK_SPACE","MEMORY","CPU","DRIFT","LIMIT","UNCONFIRMED").contains(text(node,"reason"))
            ||!node.path("stable").isBoolean()||!node.path("filesystems").isArray()||node.path("filesystems").size()>64)throw invalid();
        int paths=integer(node,"pathsChecked"),units=integer(node,"unitsChecked"),ports=integer(node,"portsChecked");var demands=new HashSet<String>();
        if(!node.path("allocatedCandidates").isArray()||node.path("allocatedCandidates").size()>request.scope().components().size())throw invalid();
        var allocated=new HashSet<String>();for(var candidate:node.path("allocatedCandidates")){if(!candidate.isTextual()||!allocated.add(candidate.textValue())||request.scope().components().stream().noneMatch(c->c.candidate().equals(candidate.textValue())))throw invalid();}
        if(!node.path("protectedMetadata").isArray()||node.path("protectedMetadata").size()>160)throw invalid();String previous="";var protectedPaths=new HashSet<String>();
        for(var row:node.path("protectedMetadata")){exact(row,Set.of("path","kind","identitySha256"));String path=text(row,"path");DeploymentAgentIsolationPolicy.path(path);digest(text(row,"identitySha256"));if(path.compareTo(previous)<=0||!policy.protects(path)||!Set.of("ABSENT","DIRECTORY","REGULAR","SYMLINK","OTHER").contains(text(row,"kind")))throw invalid();previous=path;protectedPaths.add(path);}
        for(var fs:node.path("filesystems")){exact(fs,Set.of("device","availableBytes","requiredBytes","reserveBytes"));String device=text(fs,"device");if(!device.matches("[0-9]{1,20}")||!demands.add(device))throw invalid();number(fs,"availableBytes");number(fs,"requiredBytes");number(fs,"reserveBytes");}
        if(!node.path("memoryAvailableBytes").isNull())number(node,"memoryAvailableBytes");if(!node.path("cpuCount").isNull())integer(node,"cpuCount");
        boolean ready=text(node,"status").equals("READY");
        if(request.mode()==Mode.PROTECTED_RECHECK){
            if(ready||paths!=0||units!=0||ports!=0||!allocated.isEmpty()||!demands.isEmpty()||!node.path("memoryAvailableBytes").isNull()||!node.path("cpuCount").isNull())throw invalid();
            if(text(node,"status").equals("OBSERVED")){if(!text(node,"reason").equals("OBSERVED")||!node.path("stable").booleanValue()||!protectedPaths.containsAll(policy.protectedMandatoryPaths()))throw invalid();}
            else if(text(node,"reason").equals("READY")||text(node,"reason").equals("OBSERVED")||node.path("stable").booleanValue())throw invalid();
            return new Receipt(raw);
        }
        if(text(node,"status").equals("OBSERVED"))throw invalid();
        if(ready){
            int expectedPaths=request.scope().components().stream().flatMap(c->c.paths().stream()).collect(java.util.stream.Collectors.toSet()).size();int expectedPorts=request.scope().components().stream().flatMap(c->c.ports().stream()).collect(java.util.stream.Collectors.toSet()).size();
            if(!text(node,"reason").equals("READY")||!node.path("stable").booleanValue()||paths!=expectedPaths||units!=request.scope().components().size()*2||ports!=expectedPorts||demands.isEmpty()||!protectedPaths.containsAll(policy.protectedMandatoryPaths())
                ||node.path("memoryAvailableBytes").isNull()||number(node,"memoryAvailableBytes")<request.scope().minimumMemAvailableBytes()||node.path("cpuCount").isNull()||integer(node,"cpuCount")<request.scope().minimumCpuCount())throw invalid();
            for(var fs:node.path("filesystems"))if(number(fs,"reserveBytes")<1024L*1024*1024||number(fs,"availableBytes")<Math.addExact(number(fs,"requiredBytes"),number(fs,"reserveBytes")))throw invalid();
            long required=0,observed=0;for(var component:request.scope().components())required=Math.addExact(required,Math.multiplyExact(component.workspaceBytes(),allocated.contains(component.candidate())?1:2));
            for(var demand:request.scope().additionalDiskDemands())required=Math.addExact(required,demand.requiredBytes());
            for(var fs:node.path("filesystems"))observed=Math.addExact(observed,number(fs,"requiredBytes"));if(required!=observed)throw invalid();
        }else if(text(node,"reason").equals("READY")||node.path("stable").booleanValue())throw invalid();
        return new Receipt(raw);
    }
    public static Map<String,Object> reviewFacts(CommandRequest command,DeploymentRemoteLayout layout){try(var request=decode(command,layout)){return Map.of("operation",OPERATION,"trustedPolicy",request.policy().document(),"acceptedScope",scope(request.scope()),"action",request.action().toString(),"bindingSha256",request.bindingSha256(),"expectedSourceVersion",sha(SOURCE),"executableInputReference","commandEvidence.completeExecutableInput","meaning","Read-only metadata and ownership/port/capacity lower-bound admission. No install, truncate, lock acquisition, old content, secret row, release, cleanup or grant. READY is point-in-time evidence, not a reservation or future OS sandbox. Existing helper readiness must independently match the packaged source. Shared toolchain modifications remain outside this proof.");}}
    public static Set<ActionTarget> reviewedResourceTargets(CommandRequest command,DeploymentRemoteLayout layout,ServerId server){try(var request=decode(command,layout)){if(!request.scope().server().equals(server.id()))throw invalid();return request.resourceTargets();}}
    private static UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}catch(RuntimeException malformed){throw invalid();}}

    private static final String SOURCE="""
        import os,sys,stat,json,re,hashlib,subprocess,time
        # Cooperative budget; blocking filesystem calls are not preempted.
        deadline=time.monotonic()+20.0
        def pairs(rows):
            d={}
            for k,v in rows:
                if k in d: raise ValueError()
                d[k]=v
            return d
        def canonical(x): return json.dumps(x,ensure_ascii=False,separators=(',',':'))
        def digest(x): return hashlib.sha256(canonical(x).encode()).hexdigest()
        e=json.loads(sys.argv[1],object_pairs_hook=pairs);b=e['binding'];p=b['policy'];s=b['scope']
        out=dict(format='WTL_AGENT_ISOLATION_PREFLIGHT_1',sourceVersion=e['sourceVersion'],action=e['action'],bindingSha256=e['bindingSha256'],policySha256=digest(p),scopeSha256=digest(s),mode=b['mode'],queryTask=b['queryTask'],status='UNKNOWN',reason='UNCONFIRMED',pathsChecked=0,unitsChecked=0,portsChecked=0,allocatedCandidates=[],filesystems=[],memoryAvailableBytes=None,cpuCount=None,stable=False,protectedMetadata=[])
        class Refused(Exception): pass
        class Observed(Exception): pass
        def refuse(code): raise Refused(code)
        def remaining_budget():
            remaining=deadline-time.monotonic()
            if remaining<=0: refuse('UNCONFIRMED')
            return remaining
        fds={};missing=set();markers={};tables={}
        def identity(st): return (st.st_dev,st.st_ino,st.st_mode,st.st_uid,st.st_gid)
        def open_path(path):
            remaining_budget()
            if path in fds: return fds[path]
            if len(fds)>=512: refuse('LIMIT')
            if path=='/':
                fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW|os.O_CLOEXEC)
            else:
                parent,name=path.rsplit('/',1);parent=parent or '/';base=open_path(parent)
                if base is None: missing.add(path);return None
                try: fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC|os.O_NONBLOCK,dir_fd=base[0])
                except FileNotFoundError: missing.add(path);return None
            st=os.fstat(fd)
            if st.st_uid!=0 or st.st_gid!=0 or st.st_mode&0o022 or not (stat.S_ISREG(st.st_mode) or stat.S_ISDIR(st.st_mode)):
                os.close(fd);refuse('PATH_UNSAFE')
            fds[path]=(fd,identity(st));return fds[path]
        def bounded(path,limit):
            fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC|os.O_NONBLOCK)
            try:
                if not stat.S_ISREG(os.fstat(fd).st_mode): refuse('UNCONFIRMED')
                data=bytearray()
                while len(data)<=limit:
                    remaining_budget()
                    chunk=os.read(fd,min(65536,limit+1-len(data)))
                    if not chunk: return bytes(data)
                    data.extend(chunk)
                refuse('LIMIT')
            finally: os.close(fd)
        def owner(root):
            row=open_path(root+'/.agent-task')
            if row is None: refuse('FOREIGN_OWNER')
            fd=row[0];before=os.fstat(fd);data=os.read(fd,82);after=os.fstat(fd)
            if len(data)>81 or data!=(s['task']+'\\n').encode() or before.st_size!=len(data) or (before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns): refuse('FOREIGN_OWNER')
            markers[root+'/.agent-task']=(after.st_size,after.st_mtime_ns,after.st_ctime_ns)
        def anchor(path):
            while True:
                row=open_path(path)
                if row is not None:
                    if not stat.S_ISDIR(os.fstat(row[0]).st_mode): refuse('PATH_UNSAFE')
                    return row[0]
                path=path.rsplit('/',1)[0] or '/'
        def port_scan():
            used=set()
            for name,protocol in [('tcp','TCP'),('tcp6','TCP'),('udp','UDP'),('udp6','UDP')]:
                lines=bounded('/proc/net/'+name,4194304).decode('ascii').splitlines()
                if not lines or 'local_address' not in lines[0] or len(lines)>65537: refuse('PORTS_UNCONFIRMED')
                for line in lines[1:]:
                    remaining_budget()
                    fields=line.split()
                    if len(fields)<10 or not re.fullmatch('[0-9A-Fa-f]{8,32}:[0-9A-Fa-f]{4}',fields[1]) or not re.fullmatch('[0-9A-Fa-f]{2}',fields[3]): refuse('PORTS_UNCONFIRMED')
                    port=int(fields[1].split(':')[1],16)
                    if port and (protocol=='UDP' or fields[3].upper()=='0A'): used.add((protocol,port))
            return used
        def directory_entries(directory):
            row=open_path(directory)
            if row is None: return []
            if not stat.S_ISDIR(os.fstat(row[0]).st_mode): refuse('PATH_UNSAFE')
            names=[]
            with os.scandir(row[0]) as entries:
                for entry in entries:
                    remaining_budget()
                    names.append(entry.name)
                    if len(names)>4096: refuse('LIMIT')
            return names
        def matching(directory,prefix,names=None):
            result=[]
            for name in directory_entries(directory) if names is None else names:
                remaining_budget()
                if name.startswith(prefix):
                    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._-]{0,127}',name): refuse('PATH_UNSAFE')
                    result.append(name)
                    if len(result)>16: refuse('LIMIT')
            return sorted(result)
        def protected():
            roots=p['layout']['remote'];paths=set();work=roots['stateRoot']+'/work'
            work_names=directory_entries(work)
            for old in p['protectedApplications']:
                remaining_budget()
                for unit in old['units']:
                    root=roots['applicationsRoot']+'/'+unit;lock=roots['stateRoot']+'/application-locks/'+unit
                    paths.update((root,root+'/current',roots['configurationRoot']+'/'+unit,roots['dataRoot']+'/'+unit,lock,lock+'/maintenance',roots['stateRoot']+'/snapshots/'+unit+'-'+old['task'],'/etc/systemd/system/windowstolinux-'+unit+'.service'))
                    for release in matching(root+'/releases',''):
                        if not re.fullmatch('[0-9a-f]{64}',release): refuse('PATH_UNSAFE')
                        paths.update((root+'/releases/'+release,root+'/releases/'+release+'/.windowstolinux-owner'))
                candidates=set(old['candidates'])|set(matching(work,old['application']+'-',work_names))
                for unit in old['units']: candidates.update(matching(work,unit+'-',work_names))
                if len(candidates)>16: refuse('LIMIT')
                for candidate in candidates: paths.update((work+'/'+candidate,work+'/'+candidate+'/.agent-task','/etc/systemd/system/windowstolinux-build-'+candidate+'.service'))
            if not paths or len(paths)>160: refuse('LIMIT')
            unit_states=unit_metadata(sorted(path.rsplit('/',1)[1] for path in paths if path.startswith('/etc/systemd/system/windowstolinux-') and path.endswith('.service')))
            result=[]
            for path in sorted(paths):
                remaining_budget()
                parent,name=path.rsplit('/',1);base=open_path(parent)
                try: st=None if base is None else os.stat(name,dir_fd=base[0],follow_symlinks=False)
                except FileNotFoundError: st=None
                kind='ABSENT';data=dict(exists=False)
                if st is not None:
                    kind='DIRECTORY' if stat.S_ISDIR(st.st_mode) else 'REGULAR' if stat.S_ISREG(st.st_mode) else 'SYMLINK' if stat.S_ISLNK(st.st_mode) else 'OTHER'
                    data=dict(exists=True,device=st.st_dev,inode=st.st_ino,mode=st.st_mode,uid=st.st_uid,gid=st.st_gid,size=st.st_size,mtime=st.st_mtime_ns,ctime=st.st_ctime_ns)
                    if kind=='SYMLINK':
                        target=os.readlink(name,dir_fd=base[0]);data['linkSha256']=hashlib.sha256(os.fsencode(target)).hexdigest()
                    if name in ('.agent-task','.windowstolinux-owner'):
                        if kind!='REGULAR' or st.st_uid!=0 or st.st_gid!=0 or st.st_mode&0o022 or st.st_size>256: refuse('PATH_UNSAFE')
                        fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC|os.O_NONBLOCK,dir_fd=base[0])
                        try:
                            actual=os.fstat(fd)
                            if identity(actual)!=identity(st): refuse('DRIFT')
                            raw=os.read(fd,257)
                            if len(raw)>256 or len(raw)!=actual.st_size: refuse('LIMIT')
                            data['metadataContentSha256']=hashlib.sha256(raw).hexdigest()
                        finally: os.close(fd)
                if path.startswith('/etc/systemd/system/windowstolinux-') and path.endswith('.service'):
                    data['unitMetadata']=unit_states[name]
                result.append(dict(path=path,kind=kind,identitySha256=digest(data)))
            return result
        def stable_paths():
            for path,(fd,before) in fds.items():
                remaining_budget()
                if identity(os.fstat(fd))!=before or identity(os.stat(path,follow_symlinks=False))!=before: refuse('DRIFT')
            for path in missing:
                remaining_budget()
                if os.path.lexists(path): refuse('DRIFT')
        def units_absent(units):
            for unit in units:
                for root in ('/etc/systemd/system','/run/systemd/system','/usr/local/lib/systemd/system','/usr/lib/systemd/system','/etc/systemd/system.control','/run/systemd/system.control'):
                    if open_path(root+'/'+unit+'.d') is not None: refuse('UNIT_EXISTS')
            if any(row['LoadState']!='not-found' for row in unit_metadata(units).values()): refuse('UNIT_EXISTS')
        def unit_metadata(units):
            if not units or len(units)>96 or len(set(units))!=len(units): refuse('UNITS_UNCONFIRMED')
            if open_path('/usr/bin/systemctl') is None: refuse('UNITS_UNCONFIRMED')
            keys={'LoadState','ActiveState','SubState','UnitFileState'}
            try:
                observed=subprocess.run(['/usr/bin/systemctl','show',*sorted(units),'--property=Id,LoadState,ActiveState,SubState,UnitFileState'],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=min(2.0,remaining_budget()),env={'PATH':'/usr/bin:/usr/sbin','LC_ALL':'C'},check=False)
            except subprocess.TimeoutExpired: refuse('UNCONFIRMED')
            remaining_budget()
            if observed.returncode!=0 or observed.stderr or len(observed.stdout)>1024*len(units): refuse('UNITS_UNCONFIRMED')
            result={};row={};expected=set(units)
            for line in observed.stdout.decode('ascii').splitlines()+['']:
                remaining_budget()
                if not line:
                    if row:
                        if set(row)!=keys|{'Id'} or row['Id'] not in expected or row['Id'] in result: refuse('UNITS_UNCONFIRMED')
                        result[row['Id']]={key:row[key] for key in sorted(keys)};row={}
                    continue
                key,separator,value=line.partition('=')
                if not separator or key not in keys|{'Id'} or key in row or (key!='Id' and not re.fullmatch('[A-Za-z0-9_-]{0,64}',value)): refuse('UNITS_UNCONFIRMED')
                row[key]=value
            if set(result)!=expected: refuse('UNITS_UNCONFIRMED')
            return result
        def memory_available():
            selected=[x for x in bounded('/proc/meminfo',65536).decode('ascii').splitlines() if x.startswith('MemAvailable:')]
            if len(selected)!=1 or not re.fullmatch(r'MemAvailable:\\s+[0-9]+ kB',selected[0]): refuse('UNCONFIRMED')
            return int(selected[0].split()[1])*1024
        try:
            remaining_budget()
            env=list(os.environ.items())
            if len(env)>1024 or sum(len(k)+len(v) for k,v in env)>262144 or any(k in ('BASH_ENV','ENV','LD_PRELOAD','LD_LIBRARY_PATH') or k.startswith('BASH_FUNC_') for k,v in env): refuse('ENVIRONMENT')
            if os.geteuid()!=0 or digest(b)!=e['bindingSha256']: refuse('UNCONFIRMED')
            out['protectedMetadata']=protected()
            if b['mode']=='PROTECTED_RECHECK':
                if protected()!=out['protectedMetadata']: refuse('DRIFT')
                stable_paths()
                remaining_budget()
                out.update(status='OBSERVED',reason='OBSERVED',stable=True)
                raise Observed()
            if b['mode']!='ADMISSION' or b['queryTask']!=s['task']: refuse('UNCONFIRMED')
            roots=p['layout']['remote'];work=roots['stateRoot']+'/work';apps=roots['applicationsRoot']
            allpaths=sorted(set(x for c in s['components'] for x in c['paths']));ports=set((v['transport'],v['port']) for c in s['components'] for v in c['ports'])
            own={work+'/'+c['candidate']:False for c in s['components']}
            for candidate in own:
                row=open_path(candidate)
                if row is not None:
                    if not stat.S_ISDIR(os.fstat(row[0]).st_mode): refuse('PATH_UNSAFE')
                    owner(candidate);own[candidate]=True
            out['allocatedCandidates']=sorted(root.rsplit('/',1)[1] for root,allocated in own.items() if allocated)
            for path in allpaths:
                row=open_path(path)
                if row is not None and not any(owned and (path==root or path.startswith(root+'/')) for root,owned in own.items()): refuse('PATH_EXISTS')
                out['pathsChecked']+=1
            new_units=sorted(unit for component in s['components'] for unit in ('windowstolinux-'+component['unit']+'.service','windowstolinux-build-'+component['candidate']+'.service'))
            units_absent(new_units);out['unitsChecked']=len(new_units)
            used=port_scan()
            if ports & used: refuse('PORT_BUSY')
            out['portsChecked']=len(ports)
            budgets={};budgetfds={}
            def budget(path,amount,reserve):
                remaining_budget()
                fd=anchor(path);device=str(os.fstat(fd).st_dev);v=os.fstatvfs(fd);available=v.f_bavail*v.f_frsize
                if amount<0 or reserve<1073741824: refuse('UNCONFIRMED')
                row=budgets.setdefault(device,dict(device=device,availableBytes=available,requiredBytes=0,reserveBytes=0))
                budgetfds[device]=fd
                row['availableBytes']=min(row['availableBytes'],available);row['requiredBytes']+=amount;row['reserveBytes']=max(row['reserveBytes'],reserve)
            for c in s['components']:
                candidate=work+'/'+c['candidate'];allocated=own[candidate]
                budget(candidate,0 if allocated else c['workspaceBytes'],c['reserveBytes'])
                budget(apps+'/'+c['unit'],c['workspaceBytes'],c['reserveBytes'])
                for path in c['paths']: budget(path,0,c['reserveBytes'])
            for d in s['additionalDiskDemands']: budget(d['path'],d['requiredBytes'],d['reserveBytes'])
            out['filesystems']=sorted(budgets.values(),key=lambda r:int(r['device']))
            if len(budgets)>64: refuse('LIMIT')
            if any(v['availableBytes']<v['requiredBytes']+v['reserveBytes'] for v in budgets.values()): refuse('DISK_SPACE')
            out['memoryAvailableBytes']=memory_available()
            if out['memoryAvailableBytes']<s['minimumMemAvailableBytes']: refuse('MEMORY')
            cpus=os.cpu_count()
            if not isinstance(cpus,int) or cpus<1: refuse('UNCONFIRMED')
            out['cpuCount']=cpus
            if cpus<s['minimumCpuCount']: refuse('CPU')
            if ports & port_scan(): refuse('DRIFT')
            units_absent(new_units)
            stable_paths()
            for path,before in markers.items():
                st=os.fstat(fds[path][0])
                if (st.st_size,st.st_mtime_ns,st.st_ctime_ns)!=before: refuse('DRIFT')
            if protected()!=out['protectedMetadata']: refuse('DRIFT')
            for row in budgets.values():
                current=os.fstatvfs(budgetfds[row['device']]);row['availableBytes']=min(row['availableBytes'],current.f_bavail*current.f_frsize)
                if row['availableBytes']<row['requiredBytes']+row['reserveBytes']: refuse('DISK_SPACE')
            out['memoryAvailableBytes']=min(out['memoryAvailableBytes'],memory_available())
            if out['memoryAvailableBytes']<s['minimumMemAvailableBytes']: refuse('MEMORY')
            current_cpu=os.cpu_count()
            if not isinstance(current_cpu,int) or current_cpu<1: refuse('UNCONFIRMED')
            out['cpuCount']=min(out['cpuCount'],current_cpu)
            if out['cpuCount']<s['minimumCpuCount']: refuse('CPU')
            remaining_budget()
            out.update(status='READY',reason='READY',stable=True)
        except Observed:
            pass
        except Refused as problem:
            out.update(status='UNKNOWN' if str(problem) in ('UNCONFIRMED','UNITS_UNCONFIRMED','PORTS_UNCONFIRMED','LIMIT','DRIFT') else 'REFUSED',reason=str(problem),stable=False)
        except Exception:
            out.update(status='UNKNOWN',reason='UNCONFIRMED',stable=False)
        finally:
            for fd,stamp in fds.values():
                try: os.close(fd)
                except Exception: out.update(status='UNKNOWN',reason='UNCONFIRMED',stable=False)
        result='AGENT_ISOLATION_PREFLIGHT='+canonical(out)+'\\n'
        if len(result.encode())>32768: sys.exit(65)
        sys.stdout.write(result)
        """;
}
