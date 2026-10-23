package gold.debug.windowstolinux.mod.deployment.service.agent.isolation;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperReadiness;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;

class DeploymentAgentIsolationTest {
    private final DeploymentRemoteLayout layout=DeploymentRemoteLayout.original();
    private final UUID task=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID oldTask=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final String oldApp="isolated-old",application="isolated-new";
    private final long workspace=64L*1024*1024,reserve=1024L*1024*1024,memory=128L*1024*1024;
    private final Set<Port> ports=Set.of(new Port("TCP",49280),new Port("TCP",49281));

    @Test void trustedPolicyRejectsProtectedNamespacesPortsAndUnresolvedBudgets(){
        var policy=policy();var scope=scope(policy,"a");policy.validate(scope);
        assertThrows(IllegalArgumentException.class,()->DeploymentAgentIsolationPolicy.trusted("run",oldApp,layout,policy.protectedApplications()));
        var part=scope.components().getFirst();var changed=new HashSet<>(part.paths());changed.add(layout.dataRoot(oldApp+"-backend")+"/uploads");
        var foreign=new ResolvedComponent(part.id(),part.unit(),part.candidate(),changed,part.ports(),workspace,reserve,part.runtimeSha256(),part.resourcesSha256());
        assertThrows(IllegalArgumentException.class,()->policy.validate(withParts(scope,List.of(foreign))));
        for(String outside:List.of("/root/new-unowned",layout.dataRoot(application+"-backend")+"/cross-component")){
            var extra=new HashSet<>(part.paths());extra.add(outside);
            var unowned=new ResolvedComponent(part.id(),part.unit(),part.candidate(),extra,part.ports(),workspace,reserve,part.runtimeSha256(),part.resourcesSha256());
            assertThrows(IllegalArgumentException.class,()->policy.validate(withParts(scope,List.of(unowned))));
        }
        var collision=new ResolvedComponent(part.id(),part.unit(),part.candidate(),part.paths(),Set.of(new Port("TCP",48936)),workspace,reserve,part.runtimeSha256(),part.resourcesSha256());
        assertThrows(IllegalArgumentException.class,()->policy.validate(withParts(scope,List.of(collision))));
        assertThrows(IllegalArgumentException.class,()->new PlannedScope(task,"test-server","1".repeat(64),"2".repeat(64),"3".repeat(64),layout.digest(),"4".repeat(64),scope.components(),List.of(),0,1));
        assertThrows(IllegalArgumentException.class,()->new Port("tcp",49280));
    }

    @Test void actualProbeBindsReadyTransportHelperAndExactAcceptedScope(){
        var policy=policy();var scope=scope(policy,"a");var probe=new DeploymentAgentIsolationProbe(policy);var gate=new DeploymentAgentIsolationGate(policy);
        try(var request=probe.request(scope,UUID.randomUUID())){
            var ready=receipt(request,true);gate.accept(request,actual(ready),helper(),"b".repeat(64));assertTrue(gate.matches(scope));gate.requireCurrent(scope);
            assertFalse(gate.matches(scope(policy,"c")));assertThrows(SecurityException.class,()->gate.requireCurrent(scope(policy,"c")));
            try(var decoded=DeploymentAgentIsolationProbe.decode(request.command(),layout)){assertEquals(request.bindingSha256(),decoded.bindingSha256());assertEquals(request.scope(),decoded.scope());assertArrayEquals(probe.source().getBytes(StandardCharsets.UTF_8),decoded.command().stdin());}
            assertThrows(SecurityException.class,()->gate.accept(request,actual(ready),new DeploymentAgentHelperReadiness.Observation(DeploymentAgentHelperReadiness.Status.MISSING,DeploymentAgentHelperReadiness.Reason.HELPER_MISSING),"b".repeat(64)));
            assertFalse(gate.matches(scope));
            var partial=new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new CommandResult(Optional.of(0),DeploymentAgentIsolationProbe.FRAME+ready+"\n","",true)),Optional.<Problem>empty());
            assertThrows(IllegalArgumentException.class,()->probe.parse(request,partial));
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,ready.deepCopy().put("action",UUID.randomUUID().toString()).toString()));
        }
    }

    @Test void protectedOnlyQueryCannotAdmitAndNeverReplacesOriginalMetadataBaseline(){
        var policy=policy();var scope=scope(policy,"a");var probe=new DeploymentAgentIsolationProbe(policy);var gate=new DeploymentAgentIsolationGate(policy);
        try(var admission=probe.request(scope,UUID.randomUUID())){gate.accept(admission,actual(receipt(admission,true)),helper(),"b".repeat(64));}
        UUID query=UUID.randomUUID();
        try(var request=probe.requestProtectedRecheck(scope,query,UUID.randomUUID())){
            assertEquals(task,request.scope().task());assertEquals(query,request.queryTask());
            var row=receipt(request,false);var post=probe.parse(request,actual(row));assertTrue(gate.protectedUnchanged(post));
            var changed=row.deepCopy();((ObjectNode)changed.path("protectedMetadata").get(0)).put("identitySha256","e".repeat(64));
            assertFalse(gate.protectedUnchanged(probe.parse(request,actual(changed))));
            assertThrows(SecurityException.class,()->gate.accept(request,actual(row),helper(),"b".repeat(64)));
            assertFalse(gate.matches(scope));
        }
        try(var replacement=probe.request(scope(policy,"c"),UUID.randomUUID())){
            var changed=receipt(replacement,true);((ObjectNode)changed.path("protectedMetadata").get(0)).put("identitySha256","e".repeat(64));
            assertThrows(SecurityException.class,()->gate.accept(replacement,actual(changed),helper(),"b".repeat(64)));
            gate.accept(replacement,actual(receipt(replacement,true)),helper(),"b".repeat(64));assertTrue(gate.matches(replacement.scope()));
        }
    }

    @Test void completeFixedInputAndPerFilesystemBudgetCannotBeWeakened(){
        var policy=policy();var probe=new DeploymentAgentIsolationProbe(policy);
        try(var request=probe.request(scope(policy,"a"),UUID.randomUUID())){
            var original=request.command();
            try(var changed=new CommandRequest(original.script(),(probe.source()+"\n# changed").getBytes(StandardCharsets.UTF_8),original.timeout(),original.outputLimitBytes())){assertThrows(IllegalArgumentException.class,()->DeploymentAgentIsolationProbe.decode(changed,layout));}
            var ready=receipt(request,true);var undercharged=ready.deepCopy();((ObjectNode)undercharged.path("filesystems").get(0)).put("requiredBytes",0);
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,undercharged.toString()));
            var split=ready.deepCopy();var filesystems=split.putArray("filesystems");filesystems.addObject().put("device","7").put("availableBytes",10*reserve).put("requiredBytes",workspace).put("reserveBytes",reserve);
            filesystems.addObject().put("device","8").put("availableBytes",workspace).put("requiredBytes",workspace).put("reserveBytes",reserve);
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,split.toString()),"Free bytes on another filesystem cannot cover this filesystem's reserve");
            var missing=ready.deepCopy();missing.putArray("protectedMetadata");assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,missing.toString()));
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,ready.deepCopy().put("permissionGranted",true).toString()));
            assertTrue(probe.source().contains("os.O_RDONLY"));assertTrue(probe.source().contains("['/usr/bin/systemctl','show'"));assertFalse(probe.source().contains("os.unlink"));assertFalse(probe.source().contains("os.system"));
        }
    }

    @Test void isolationShellRejectsMissingOrEmptyBindingBeforeInterpreter()throws Exception{
        var policy=sevenRetainedNamespaces();var probe=new DeploymentAgentIsolationProbe(policy);
        try(var request=probe.request(scope(policy,"a"),UUID.randomUUID())){
            String script=request.command().script();int newline=script.indexOf('\n');assertTrue(newline>0);
            String suffix=script.substring(newline);
            for(String initialization:List.of("builtin unset wtl_isolation\n","wtl_isolation=''\n")){
                var observed=runIsolationShell(initialization+suffix,new byte[0]);
                assertNotEquals(0,observed.exitCode(),observed.output());
                assertFalse(observed.output().contains("ISOLATION_TEST_INTERPRETER"),observed.output());
                assertTrue(observed.output().contains("wtl_isolation"),observed.output());
            }
        }
    }

    @Test void isolationShellPassesCompleteFixedBindingAndStdin()throws Exception{
        var policy=sevenRetainedNamespaces();var probe=new DeploymentAgentIsolationProbe(policy);
        try(var request=probe.request(scope(policy,"a"),UUID.randomUUID())){
            var command=request.command();String script=command.script();int newline=script.indexOf('\n');
            assertTrue(script.startsWith("wtl_isolation='"));assertEquals('\'',script.charAt(newline-1));
            String originalArgument=script.substring("wtl_isolation='".length(),newline-1);
            var observed=runIsolationShell(script,command.stdin());assertEquals(0,observed.exitCode(),observed.output());
            String[] captured=observed.output().split(String.valueOf((char)0),7);assertEquals(7,captured.length);
            assertEquals(List.of("ISOLATION_TEST_INTERPRETER","/usr/bin/python3","-I","-S","-"),Arrays.asList(captured).subList(0,5));
            assertEquals(originalArgument,captured[5]);assertArrayEquals(command.stdin(),captured[6].getBytes(StandardCharsets.UTF_8));
            var envelope=JSON.readTree(captured[5]);var binding=envelope.path("binding");
            assertEquals(JSON.readTree(policy.document().toString()),binding.path("policy"));assertEquals(JSON.readTree(DeploymentAgentIsolationPolicy.scope(request.scope()).toString()),binding.path("scope"));
            assertEquals(request.mode().name(),binding.path("mode").textValue());assertEquals(request.queryTask().toString(),binding.path("queryTask").textValue());
            assertEquals(request.action().toString(),envelope.path("action").textValue());assertEquals(probe.sourceVersion(),envelope.path("sourceVersion").textValue());
            assertEquals(request.bindingSha256(),envelope.path("bindingSha256").textValue());
            try(var decoded=DeploymentAgentIsolationProbe.decode(command,layout)){
                assertEquals(request.scope(),decoded.scope());assertEquals(request.action(),decoded.action());assertEquals(request.bindingSha256(),decoded.bindingSha256());
                assertEquals(script,decoded.command().script());assertArrayEquals(command.stdin(),decoded.command().stdin());
            }
            String changedDigest=(request.bindingSha256().startsWith("0")?"1":"0")+request.bindingSha256().substring(1);
            for(String changed:List.of(script.replace(request.bindingSha256(),changedDigest),script.replace("${wtl_isolation:?}","$wtl_isolation"))){
                assertNotEquals(script,changed);
                try(var tampered=new CommandRequest(changed,command.stdin(),command.timeout(),command.outputLimitBytes())){
                    assertThrows(IllegalArgumentException.class,()->DeploymentAgentIsolationProbe.decode(tampered,layout));
                }
            }
        }
    }

    @Test void completeSevenRetainedNamespacesStayWithinBoundedReceipt(){
        var policy=sevenRetainedNamespaces();var probe=new DeploymentAgentIsolationProbe(policy);
        try(var request=probe.request(scope(policy,"a"),UUID.randomUUID())){
            assertEquals(32768,request.command().outputLimitBytes());
            try(var oldLimit=new CommandRequest(request.command().script(),request.command().stdin(),request.command().timeout(),24576)){
                assertThrows(IllegalArgumentException.class,()->DeploymentAgentIsolationProbe.decode(oldLimit,layout));
            }
            var ready=receipt(request,true);var rows=ready.withArray("protectedMetadata");
            for(String path:discoveredRetainedPaths())rows.addObject().put("path",path).put("kind","DIRECTORY").put("identitySha256","d".repeat(64));
            sortProtectedRows(ready);assertEquals(135,ready.path("protectedMetadata").size());
            assertTrue((DeploymentAgentIsolationProbe.FRAME+ready+"\n").getBytes(StandardCharsets.UTF_8).length<32768);
            assertEquals(ready.toString(),probe.parse(request,actual(ready)).canonicalJson());
            var missing=ready.deepCopy();missing.withArray("protectedMetadata").remove(0);
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,missing.toString()));
            var shortPolicy=policy();var shortProbe=new DeploymentAgentIsolationProbe(shortPolicy);
            try(var shortRequest=shortProbe.request(scope(shortPolicy,"a"),UUID.randomUUID())){
                var excess=receipt(shortRequest,true);assertEquals(19,excess.path("protectedMetadata").size());
                for(int i=0;i<142;i++)excess.withArray("protectedMetadata").addObject().put("path",layout.applicationRoot(oldApp+"-backend")+"/extra-"+i).put("kind","ABSENT").put("identitySha256","d".repeat(64));
                sortProtectedRows(excess);assertEquals(161,excess.path("protectedMetadata").size());
                for(var row:excess.path("protectedMetadata")){String path=row.path("path").textValue();DeploymentAgentIsolationPolicy.path(path);assertTrue(shortPolicy.protects(path));}
                assertTrue((DeploymentAgentIsolationProbe.FRAME+excess+"\n").getBytes(StandardCharsets.UTF_8).length<32768);
                assertThrows(IllegalArgumentException.class,()->shortProbe.validateReceipt(shortRequest,excess.toString()));
                assertThrows(IllegalArgumentException.class,()->shortProbe.parse(shortRequest,actual(excess)));
            }
            var foreign=ready.deepCopy();((ObjectNode)foreign.path("protectedMetadata").get(0)).put("path","/foreign/namespace");sortProtectedRows(foreign);
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,foreign.toString()));
            var unsorted=ready.deepCopy();var first=unsorted.withArray("protectedMetadata").remove(0);unsorted.withArray("protectedMetadata").add(first);
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,unsorted.toString()));
            var oversized=ready.deepCopy();var splitFilesystems=oversized.putArray("filesystems");
            for(int i=0;i<64;i++)splitFilesystems.addObject().put("device","100000000000000000"+String.format(Locale.ROOT,"%02d",i)).put("availableBytes",10*reserve).put("requiredBytes",2*workspace/64).put("reserveBytes",reserve);
            assertEquals(135,oversized.path("protectedMetadata").size());
            for(var row:oversized.path("protectedMetadata")){String path=row.path("path").textValue();DeploymentAgentIsolationPolicy.path(path);assertTrue(policy.protects(path));}
            assertTrue(oversized.toString().getBytes(StandardCharsets.UTF_8).length>32768);
            assertThrows(IllegalArgumentException.class,()->probe.validateReceipt(request,oversized.toString()));
            assertThrows(IllegalArgumentException.class,()->probe.parse(request,actual(oversized)));
            var truncated=new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new CommandResult(Optional.of(0),DeploymentAgentIsolationProbe.FRAME+ready+"\n","",true)),Optional.<Problem>empty());
            assertThrows(IllegalArgumentException.class,()->probe.parse(request,truncated));
        }
    }

    @Test void fixedPythonProtectedProbeUsesSameFiniteMetadataBound()throws Exception{
        var policy=sevenRetainedNamespaces();var probe=new DeploymentAgentIsolationProbe(policy);
        String fixture="""
            import ast,sys,json,hashlib,re
            data=json.load(sys.stdin)
            tree=ast.parse(data['source'])
            selected=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in ('canonical','digest','protected','matching')]
            assert len(selected)==4
            class Refused(Exception): pass
            def refuse(code): raise Refused(code)
            extra_candidate=False
            reads=0
            scans=0
            roots=data['policy']['layout']['remote']
            def directory_entries(root):
                global scans
                if root==roots['stateRoot']+'/work':
                    scans+=1
                    return ['wtl-agent-file-transfer-8pqtgvbwc-web-525f7bab44afa87d']+(['wtl-agent-file-transfer-46xyx53kc-web-525f7bab44afa87d','wtl-agent-file-transfer-61fy7ik8g-web-525f7bab44afa87d'] if extra_candidate else [])
                if root==roots['applicationsRoot']+'/wtl-agent-file-transfer-8pqtgvbwc-backend/releases':
                    return ['39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597']+([format(i,'064x') for i in range(10)] if extra_candidate else [])
                return []
            def open_path(path):
                global reads
                reads+=1
                return None
            states={'LoadState':'not-found','ActiveState':'inactive','SubState':'dead','UnitFileState':''}
            namespace=dict(p=data['policy'],json=json,hashlib=hashlib,re=re,refuse=refuse,directory_entries=directory_entries,remaining_budget=lambda:20,open_path=open_path,unit_metadata=lambda units:{unit:states for unit in units})
            exec(compile(ast.Module(body=selected,type_ignores=[]),'<fixed-protected-probe>','exec'),namespace)
            rows=namespace['protected']()
            assert len(rows)==135 and [row['path'] for row in rows]==sorted(data['paths']) and scans==1
            before=reads
            extra_candidate=True
            try: namespace['protected']()
            except Refused as error: assert str(error)=='LIMIT'
            else: raise AssertionError('161 protected rows were accepted')
            assert reads==before and scans==2, 'over-limit enumeration must refuse before metadata reads and refresh its directory scan'
            print(json.dumps(rows,separators=(',',':')))
            """;
        var paths=new TreeSet<>(policy.protectedMandatoryPaths());paths.addAll(discoveredRetainedPaths());
        var input=JSON.createObjectNode().put("source",probe.source());input.set("policy",policy.document());input.set("paths",JSON.valueToTree(paths));
        var process=new ProcessBuilder(System.getProperty("managed.test.python","python"),"-I","-S","-c",fixture).redirectErrorStream(true).start();
        var reader=java.util.concurrent.Executors.newSingleThreadExecutor();var outputBytes=reader.submit(()->process.getInputStream().readNBytes(32769));
        try{
            try(var out=process.getOutputStream()){out.write(JSON.writeValueAsBytes(input));}
            assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"controlled Python protected probe did not exit");
            String output=new String(outputBytes.get(5,java.util.concurrent.TimeUnit.SECONDS),StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),output);
            try(var request=probe.requestProtectedRecheck(scope(policy,"a"),UUID.randomUUID(),UUID.randomUUID())){
                var observed=receipt(request,false);observed.set("protectedMetadata",JSON.readTree(output));
                assertEquals(135,observed.path("protectedMetadata").size());assertEquals(observed.toString(),probe.parse(request,actual(observed)).canonicalJson());
            }
        }finally{
            if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
            assertFalse(process.isAlive());reader.shutdownNow();assertTrue(reader.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void fixedPythonUnitBatchesRequireCompleteExactIdentityMaps()throws Exception{
        String fixture="""
            import ast,json,sys,re,types,subprocess
            data=json.load(sys.stdin);tree=ast.parse(data['source'])
            selected=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in ('unit_metadata','remaining_budget')]
            assert len(selected)==2
            class Refused(Exception): pass
            def refuse(code): raise Refused(code)
            units=['windowstolinux-old-backend.service','windowstolinux-build-old-backend-source.service']
            def block(unit): return chr(10).join(['Id='+unit,'LoadState=not-found','ActiveState=inactive','SubState=dead','UnitFileState='])
            valid=(chr(10)*2).join(block(unit) for unit in reversed(units))
            output=valid;code=0;errors=b'';calls=0
            def run(argv,**options):
                global calls
                calls+=1
                assert argv==['/usr/bin/systemctl','show',*sorted(units),'--property=Id,LoadState,ActiveState,SubState,UnitFileState']
                assert options==dict(stdin=-3,stdout=-1,stderr=-1,timeout=2.0,env={'PATH':'/usr/bin:/usr/sbin','LC_ALL':'C'},check=False)
                return types.SimpleNamespace(returncode=code,stdout=output.encode('ascii'),stderr=errors)
            namespace=dict(re=re,deadline=20,time=types.SimpleNamespace(monotonic=lambda:0),refuse=refuse,open_path=lambda path:(1,None),subprocess=types.SimpleNamespace(run=run,DEVNULL=-3,PIPE=-1,TimeoutExpired=subprocess.TimeoutExpired))
            exec(compile(ast.Module(body=selected,type_ignores=[]),'<fixed-unit-batch>','exec'),namespace)
            observed=namespace['unit_metadata'](units)
            assert set(observed)==set(units)
            assert all(set(row)=={'LoadState','ActiveState','SubState','UnitFileState'} and 'Id' not in row for row in observed.values())
            malformed=[block(units[0]),valid+chr(10)*2+block(units[0]),valid.replace(units[0],'foreign.service'),valid.replace('ActiveState=inactive',''),valid.replace('SubState=dead','SubState=dead'+chr(10)+'SubState=dead'),valid+chr(10)+'Foreign=value',valid.replace('Id='+units[0],'Id='),valid+'x'*2049]
            for output in malformed:
                try: namespace['unit_metadata'](units)
                except Refused as error: assert str(error)=='UNITS_UNCONFIRMED'
                else: raise AssertionError('incomplete or foreign unit batch accepted')
            output=valid
            for code,errors in [(1,b''),(0,b'failure')]:
                try: namespace['unit_metadata'](units)
                except Refused as error: assert str(error)=='UNITS_UNCONFIRMED'
                else: raise AssertionError('failed unit command accepted')
            before=calls
            try: namespace['unit_metadata']([units[0],units[0]])
            except Refused as error: assert str(error)=='UNITS_UNCONFIRMED'
            else: raise AssertionError('duplicate requested identity accepted')
            assert calls==before
            print(json.dumps(dict(valid=True,rejected=len(malformed)+3,calls=calls)))
            """;
        var input=JSON.createObjectNode().put("source",new DeploymentAgentIsolationProbe(fiveRetainedNamespaces()).source());
        var result=JSON.readTree(runFixedPython(fixture,input));assertTrue(result.path("valid").booleanValue());assertEquals(11,result.path("rejected").intValue());assertEquals(11,result.path("calls").intValue());
    }

    @Test void fixedPythonProbeKeepsFreshBatchesWithinAggregateBudget()throws Exception{
        var policy=fiveRetainedNamespaces();var probe=new DeploymentAgentIsolationProbe(policy);
        String fixture="""
            import ast,json,sys,re,types,subprocess,hashlib,io
            data=json.load(sys.stdin);tree=ast.parse(data['source'])
            names={'canonical','digest','refuse','remaining_budget','matching','protected','unit_metadata','units_absent','stable_paths'}
            selected=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in names or isinstance(node,ast.ClassDef) and node.name in ('Refused','Observed')]
            assert len(selected)==len(names)+2
            main=[node for node in tree.body if isinstance(node,ast.Try)]
            assert len(main)==1
            def run_case(mode):
                clock={'now':0.0};calls=[];timeouts=[];scans=0;output=io.StringIO()
                roots=data['policy']['layout']['remote']
                def directory_entries(root):
                    nonlocal scans
                    if root==roots['stateRoot']+'/work':
                        scans+=1
                        if mode=='budget': clock['now']+=18.5
                        return ['wtl-agent-file-transfer-8pqtgvbwc-web-525f7bab44afa87d']
                    if root==roots['applicationsRoot']+'/wtl-agent-file-transfer-8pqtgvbwc-backend/releases':
                        return ['39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597']
                    return []
                def run(argv,**options):
                    units=argv[2:-1];calls.append(list(units));timeouts.append(options['timeout'])
                    assert len(set(units))==len(units) and units==sorted(units)
                    assert argv[:2]==['/usr/bin/systemctl','show'] and argv[-1]=='--property=Id,LoadState,ActiveState,SubState,UnitFileState'
                    assert 0<options['timeout']<=min(2.0,20.0-clock['now'])
                    if mode=='budget':
                        duration=min(1.0,options['timeout']);clock['now']+=duration
                        if options['timeout']<1.0: raise subprocess.TimeoutExpired(argv,options['timeout'])
                    blocks=[]
                    for index,unit in enumerate(reversed(units)):
                        state='active' if mode=='drift' and len(calls)==4 and index==0 else 'inactive'
                        blocks.append(chr(10).join(['Id='+unit,'LoadState=not-found','ActiveState='+state,'SubState=dead','UnitFileState=']))
                    return types.SimpleNamespace(returncode=0,stdout=(chr(10)*2).join(blocks).encode(),stderr=b'')
                operating=types.SimpleNamespace(environ={},geteuid=lambda:0,cpu_count=lambda:2,fstat=lambda fd:types.SimpleNamespace(st_dev=7),fstatvfs=lambda fd:types.SimpleNamespace(f_bavail=10737418240,f_frsize=1))
                namespace=dict(json=json,hashlib=hashlib,re=re,deadline=20.0,time=types.SimpleNamespace(monotonic=lambda:clock['now']),os=operating,sys=types.SimpleNamespace(stdout=output,exit=lambda code:(_ for _ in ()).throw(AssertionError('unexpected output cap exit'))),subprocess=types.SimpleNamespace(run=run,DEVNULL=-3,PIPE=-1,TimeoutExpired=subprocess.TimeoutExpired),fds={},missing=set(),markers={},tables={},directory_entries=directory_entries,open_path=lambda path:(1,None) if path=='/usr/bin/systemctl' else None,port_scan=lambda:set(),anchor=lambda path:1,memory_available=lambda:10737418240)
                exec(compile(ast.Module(body=selected,type_ignores=[]),'<fixed-isolation-functions>','exec'),namespace)
                namespace.update(p=data['policy'],s=data['scope'],b=dict(policy=data['policy'],scope=data['scope'],mode='ADMISSION',queryTask=data['scope']['task']),out=json.loads(json.dumps(data['initial'])))
                namespace['e']={'bindingSha256':namespace['digest'](namespace['b'])}
                exec(compile(ast.Module(body=main+tree.body[-3:],type_ignores=[]),'<fixed-isolation-main>','exec'),namespace)
                frame=output.getvalue()
                assert frame.startswith('AGENT_ISOLATION_PREFLIGHT=') and frame.endswith(chr(10)) and len(frame.encode())<=32768
                receipt=json.loads(frame[len('AGENT_ISOLATION_PREFLIGHT='):])
                assert all('Id' not in row for row in receipt['protectedMetadata'])
                if mode in ('ready','drift'):
                    assert len(calls)==4 and scans==2 and calls[0]==calls[3] and calls[1]==calls[2]
                    assert len(calls[0])==14 and len(calls[1])==2
                else: assert len(calls)==2 and scans==1 and timeouts==[1.5,0.5] and clock['now']==20.0
                return dict(receipt=receipt,calls=len(calls),scans=scans,time=clock['now'],timeouts=timeouts)
            result={mode:run_case(mode) for mode in ('ready','drift','budget')}
            assert result['ready']['receipt']['status']=='READY'
            assert result['drift']['receipt']['status']=='UNKNOWN' and result['drift']['receipt']['reason']=='DRIFT'
            assert result['budget']['receipt']['status']=='UNKNOWN' and result['budget']['receipt']['reason']=='UNCONFIRMED'
            print(json.dumps(result,separators=(',',':')))
            """;
        try(var request=probe.request(scope(policy,"a"),UUID.randomUUID())){
            var initial=receipt(request,true).put("status","UNKNOWN").put("reason","UNCONFIRMED").put("stable",false).put("pathsChecked",0).put("unitsChecked",0).put("portsChecked",0).putNull("memoryAvailableBytes").putNull("cpuCount");
            initial.putArray("allocatedCandidates");initial.putArray("filesystems");initial.putArray("protectedMetadata");
            var input=JSON.createObjectNode().put("source",probe.source());input.set("policy",policy.document());input.set("scope",DeploymentAgentIsolationPolicy.scope(request.scope()));input.set("initial",initial);
            var result=JSON.readTree(runFixedPython(fixture,input));
            for(String mode:List.of("ready","drift","budget")){
                var row=(ObjectNode)result.path(mode).path("receipt");assertEquals(row.toString(),probe.parse(request,actual(row)).canonicalJson());
                assertEquals(mode.equals("ready")?"READY":"UNKNOWN",row.path("status").textValue());
            }
            assertEquals(4,result.path("ready").path("calls").intValue());assertEquals(2,result.path("ready").path("scans").intValue());
            assertEquals("DRIFT",result.path("drift").path("receipt").path("reason").textValue());
            assertEquals(20.0,result.path("budget").path("time").doubleValue());assertFalse(result.path("budget").path("receipt").path("stable").booleanValue());
        }
    }

    private record IsolationShellObservation(int exitCode,String output){}
    private IsolationShellObservation runIsolationShell(String script,byte[] stdin)throws Exception{
        String capture="""
            builtin enable -n exec
            exec() {
                builtin printf '%s\\0' 'ISOLATION_TEST_INTERPRETER' "$@"
                local line=''
                while IFS= builtin read -r line || [[ -n "$line" ]]; do builtin printf '%s\\n' "$line"; done
            }
            """;
        var scriptFile=java.nio.file.Files.createTempFile("iso-",".sh");
        try{
            java.nio.file.Files.write(scriptFile,(capture+script).getBytes(StandardCharsets.UTF_8));
            var builder=new ProcessBuilder(System.getProperty("managed.test.bash","/bin/bash"),"--noprofile","--norc",scriptFile.toAbsolutePath().toString().replace('\\','/')).redirectErrorStream(true);
            builder.environment().keySet().removeIf(key->key.startsWith("BASH_FUNC_")||Set.of("SHELLOPTS","BASHOPTS","LD_PRELOAD","LD_LIBRARY_PATH").contains(key));
            builder.environment().put("BASH_ENV","/dev/null");builder.environment().put("ENV","/dev/null");
            var process=builder.start();var reader=java.util.concurrent.Executors.newSingleThreadExecutor();
            var output=reader.submit(()->process.getInputStream().readNBytes(65537));
            try{
                try(var out=process.getOutputStream()){out.write(stdin);}
                assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"controlled isolation shell did not exit");
                byte[] bytes=output.get(5,java.util.concurrent.TimeUnit.SECONDS);assertTrue(bytes.length<=65536);
                return new IsolationShellObservation(process.exitValue(),new String(bytes,StandardCharsets.UTF_8));
            }finally{
                try{
                    if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
                }finally{
                    reader.shutdownNow();assertTrue(reader.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));assertFalse(process.isAlive());
                }
            }
        }finally{
            java.nio.file.Files.deleteIfExists(scriptFile);
        }
    }

    private String runFixedPython(String fixture,ObjectNode input)throws Exception{
        var process=new ProcessBuilder(System.getProperty("managed.test.python","python"),"-I","-S","-c",fixture).redirectErrorStream(true).start();
        var reader=java.util.concurrent.Executors.newSingleThreadExecutor();var bytes=reader.submit(()->process.getInputStream().readNBytes(131073));
        try{
            try(var out=process.getOutputStream()){out.write(JSON.writeValueAsBytes(input));}
            assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"controlled fixed Python did not exit");
            byte[] result=bytes.get(5,java.util.concurrent.TimeUnit.SECONDS);String output=new String(result,StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);assertTrue(result.length<=131072);return output;
        }finally{
            if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
            reader.shutdownNow();assertTrue(reader.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));assertFalse(process.isAlive());
        }
    }

    private DeploymentAgentIsolationPolicy sevenRetainedNamespaces(){
        var applications=new ArrayList<>(sixRetainedNamespaces().protectedApplications());String name="wtl-agent-file-transfer-61fy7ik8g";
        applications.add(new ProtectedApplication(UUID.fromString("7abadc73-0817-4159-80e8-945341a0bbb1"),name,layout.digest(),Set.of(name+"-backend",name+"-web"),Set.of(name+"-backend-525f7bab44afa87d"),Map.of("planSha256","025fa88d05de24e1f9600e4b81e658bddb919df44dd407ab8bd6037d42e33d9d","sourceSha256","296967551e9c7a93b61165be8d03198192c94f4b1544f41e5d16ae75e8672fd3"),Set.of(new Port("TCP",49292),new Port("TCP",49293))));
        return DeploymentAgentIsolationPolicy.trusted("seven-retained-namespaces",application,layout,applications).requiringPorts(ports).minimumResources(memory,1);
    }
    private DeploymentAgentIsolationPolicy sixRetainedNamespaces(){
        var applications=new ArrayList<>(fiveRetainedNamespaces().protectedApplications());String name="wtl-agent-file-transfer-4kt0vqj3s";
        applications.add(new ProtectedApplication(UUID.fromString("eb71a6de-07cb-4574-b538-15df729a88d5"),name,layout.digest(),Set.of(name+"-backend",name+"-web"),Set.of(name+"-backend-525f7bab44afa87d",name+"-web-525f7bab44afa87d"),Map.of(),Set.of()));
        return DeploymentAgentIsolationPolicy.trusted("six-retained-namespaces",application,layout,applications).requiringPorts(ports).minimumResources(memory,1);
    }
    private DeploymentAgentIsolationPolicy fiveRetainedNamespaces(){
        var applications=new ArrayList<ProtectedApplication>();
        String[] names={"wtl-agent-file-transfer-8pqtgvbwc","wtl-agent-file-transfer-ufjfoeitg","wtl-agent-file-transfer-v0wjwq48s","wtl-agent-file-transfer-3g6o4vqic","wtl-agent-file-transfer-46xyx53kc"};
        String[] tasks={"072bc074-4bce-4c14-b425-f2ff965c7d40","4255f545-7acb-4b47-a331-45c977d086dd","c236a665-56e5-44b3-8acb-bfe167d97b97","d8de6c5a-2b8c-42bd-8628-4bba4f88216b","af2f6e5b-08c1-4977-beae-533e7fa679f9"};
        for(int i=0;i<names.length;i++)applications.add(new ProtectedApplication(UUID.fromString(tasks[i]),names[i],layout.digest(),Set.of(names[i]+"-backend",names[i]+"-web"),i==1||i==4?Set.of():Set.of(names[i]+"-backend-525f7bab44afa87d"),Map.of(),Set.of()));
        return DeploymentAgentIsolationPolicy.trusted("five-retained-namespaces",application,layout,applications).requiringPorts(ports).minimumResources(memory,1);
    }
    private Set<String> discoveredRetainedPaths(){
        String unit="wtl-agent-file-transfer-8pqtgvbwc-web",candidate=unit+"-525f7bab44afa87d",release=layout.applicationRoot("wtl-agent-file-transfer-8pqtgvbwc-backend")+"/releases/39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597";
        return Set.of(layout.candidateRoot(candidate),layout.candidateRoot(candidate)+"/.agent-task","/etc/systemd/system/windowstolinux-build-"+candidate+".service",release,release+"/.windowstolinux-owner");
    }
    private void sortProtectedRows(ObjectNode receipt){
        var sorted=new TreeMap<String,com.fasterxml.jackson.databind.JsonNode>();for(var row:receipt.path("protectedMetadata"))sorted.put(row.path("path").textValue(),row);
        var rows=receipt.putArray("protectedMetadata");sorted.values().forEach(rows::add);
    }

    private DeploymentAgentIsolationPolicy policy(){
        var old=new ProtectedApplication(oldTask,oldApp,layout.digest(),Set.of(oldApp+"-backend",oldApp+"-web"),Set.of(oldApp+"-backend-0123456789abcdef"),Map.of("backendOwner","f".repeat(64)),Set.of(new Port("TCP",48936)));
        return DeploymentAgentIsolationPolicy.trusted("unit-test",application,layout,List.of(old)).requiringPorts(ports).minimumResources(memory,1);
    }
    private PlannedScope scope(DeploymentAgentIsolationPolicy policy,String source){
        String unit=DeploymentManagedUnitResolver.managedId(application,"web"),candidate=unit+"-0123456789abcdef";
        var part=new ResolvedComponent("web",unit,candidate,Set.of(layout.candidateRoot(candidate)),ports,workspace,reserve,"5".repeat(64),"6".repeat(64));
        part=new ResolvedComponent(part.id(),unit,candidate,policy.requiredPaths(part,task),ports,workspace,reserve,part.runtimeSha256(),part.resourcesSha256());
        return new PlannedScope(task,"test-server","1".repeat(64),source.repeat(64),"3".repeat(64),layout.digest(),"4".repeat(64),List.of(part),List.of(),memory,1);
    }
    private PlannedScope withParts(PlannedScope original,List<ResolvedComponent> parts){return new PlannedScope(original.task(),original.server(),original.targetSha256(),original.sourceSha256(),original.configurationSha256(),original.layoutSha256(),original.planSha256(),parts,List.of(),memory,1);}
    private DeploymentAgentHelperReadiness.Observation helper(){return new DeploymentAgentHelperReadiness.Observation(DeploymentAgentHelperReadiness.Status.READY,DeploymentAgentHelperReadiness.Reason.CURRENT_TASK_READY);}
    private ObjectNode receipt(DeploymentAgentIsolationProbe.Request request,boolean admission){
        var probe=new DeploymentAgentIsolationProbe(request.policy());var node=JSON.createObjectNode().put("format",DeploymentAgentIsolationProbe.FORMAT).put("sourceVersion",probe.sourceVersion())
            .put("action",request.action().toString()).put("bindingSha256",request.bindingSha256()).put("policySha256",sha(request.policy().document().toString())).put("scopeSha256",sha(DeploymentAgentIsolationPolicy.scope(request.scope()).toString()))
            .put("mode",request.mode().name()).put("queryTask",request.queryTask().toString()).put("status",admission?"READY":"OBSERVED").put("reason",admission?"READY":"OBSERVED")
            .put("pathsChecked",admission?request.scope().components().stream().flatMap(c->c.paths().stream()).distinct().count():0).put("unitsChecked",admission?2*request.scope().components().size():0).put("portsChecked",admission?ports.size():0);
        node.putArray("allocatedCandidates");var fs=node.putArray("filesystems");if(admission)fs.addObject().put("device","7").put("availableBytes",10*reserve).put("requiredBytes",2*workspace).put("reserveBytes",reserve);
        if(admission)node.put("memoryAvailableBytes",4*memory).put("cpuCount",2);else node.putNull("memoryAvailableBytes").putNull("cpuCount");node.put("stable",true);
        var old=node.putArray("protectedMetadata");for(String path:new TreeSet<>(request.policy().protectedMandatoryPaths()))old.addObject().put("path",path).put("kind","ABSENT").put("identitySha256","d".repeat(64));
        return node;
    }
    private OperationResult<CommandResult> actual(ObjectNode node){return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new CommandResult(Optional.of(0),DeploymentAgentIsolationProbe.FRAME+node+"\n","",false)),Optional.empty());}
}
