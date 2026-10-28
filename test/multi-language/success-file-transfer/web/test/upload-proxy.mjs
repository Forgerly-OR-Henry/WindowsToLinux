import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once, EventEmitter } from "node:events";
import fs from "node:fs/promises";
import http from "node:http";
import net from "node:net";
import { stripTypeScriptTypes } from "node:module";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

const prefix = "FILE_TRANSFER_UPLOAD_PROXY_DIAGNOSTIC ";
const flowPrefix = "FILE_TRANSFER_UPLOAD_FLOW_DIAGNOSTIC ";
async function waitFor(predicate) {
  const until = performance.now() + 3000;
  while (!predicate()) {
    if (performance.now() >= until) throw new Error("LOCAL_UPLOAD_OBSERVATION_DEADLINE");
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

async function gateway(origin, options = {}) {
  assert.match(origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/);
  const tempRoot = await fs.realpath(os.tmpdir());
  const temporary = await fs.mkdtemp(path.join(tempRoot, "wtl-upload-proxy-"));
  const sourceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/server");
  const source = await fs.readFile(path.join(sourceRoot, "server.ts"), "utf8");
  const contracts = await fs.readFile(path.join(sourceRoot, "contracts.ts"), "utf8");
  assert.equal(source.split('from "./contracts.js"').length, 2);
  let budgetSource=await fs.readFile(path.join(sourceRoot,"upload-budget.ts"),"utf8");
  if(options.policy) { assert.match(budgetSource,/export const uploadPolicy = \{[^\n]+\};/); budgetSource=budgetSource.replace(/export const uploadPolicy = \{[^\n]+\};/,"export const uploadPolicy = "+JSON.stringify(options.policy)+";"); }
  await fs.writeFile(path.join(temporary, "upload-budget.mjs"), stripTypeScriptTypes(budgetSource));
  await fs.writeFile(path.join(temporary, "server.mjs"), stripTypeScriptTypes(source.replace('from "./contracts.js"', 'from "./contracts.mjs"').replace('from "./upload-budget.js"','from "./upload-budget.mjs"')));
  await fs.writeFile(path.join(temporary, "contracts.mjs"), stripTypeScriptTypes(contracts));
  const original = { createServer: http.createServer, request: http.request, info: console.info,
    setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval, setTimeout:globalThis.setTimeout, clearTimeout:globalThis.clearTimeout, hrtime: process.hrtime.bigint };
  const env = Object.fromEntries(["HOST", "PORT", "API_URL", "API_TIMEOUT_MS"].map((key) => [key, process.env[key]]));
  Object.assign(process.env, { HOST: "127.0.0.1", PORT: "0", API_URL: origin, API_TIMEOUT_MS: "30000" });
  const trace = { records: [], flowRecords: [], requests: [], responses:[], proxies: [], unpipeDataListenerCounts: [], intervals: [], policyTimers:[], lifecycle:[],clientEvents:[] };
  const recordLife=(event,res,proxy,upstream,reason)=>{if(trace.lifecycle.length<128)trace.lifecycle.push({sequence:trace.lifecycle.length+1,event,reason,
    downstreamFinished:res?.writableFinished,downstreamEnded:res?.writableEnded,downstreamDestroyed:res?.destroyed,proxyDestroyed:proxy?.destroyed,upstreamComplete:upstream?.complete})};
  globalThis.setTimeout=(callback,millis,...args)=>{
    if(!new Error().stack.includes("upload-budget.mjs")) return original.setTimeout(callback,millis,...args);
    if(options.failPolicyTimer) throw new Error("CONTROLLED_POLICY_SETUP_FAILURE");
    const record={active:true,millis}; const handle=original.setTimeout(()=>{record.active=false;record.fired=true;callback(...args)},millis);
    record.handle=handle;trace.policyTimers.push(record);return handle;
  };
  globalThis.clearTimeout=(handle)=>{const entry=trace.policyTimers.find(r=>r.handle===handle);if(entry)entry.active=false;return original.clearTimeout(handle)};
  globalThis.setInterval = (callback, millis, ...args) => {
    if (options.failTimer) throw new Error("TEST_SECRET_CANARY");
    const entry = { callback, millis, active: true, fires: 0 };
    const handle = original.setInterval(() => { entry.fires++; callback(...args); }, millis);
    entry.handle = handle; trace.intervals.push(entry); return handle;
  };
  globalThis.clearInterval = (handle) => {
    const observed = trace.intervals.find((entry) => entry.handle === handle); if (observed) observed.active = false;
    return original.clearInterval(handle);
  };
  const backendAgent = new http.Agent({ keepAlive: true });
  let server;
  const ready = new Promise((resolve, reject) => {
    http.createServer = function (listener, ...args) {
      server = original.createServer.call(this, (req, res) => {
        trace.requests.push(req);trace.responses.push(res);
        res.on("finish",()=>recordLife("DOWNSTREAM_FINISH",res));res.on("close",()=>recordLife("DOWNSTREAM_CLOSE",res));
        const destroy=res.destroy;res.destroy=function(...values){recordLife("DOWNSTREAM_DESTROY",res);return destroy.apply(this,values)};
        listener(req, res);
      }, ...args);
      server.once("listening", resolve); server.once("error", reject); return server;
    };
  });
  http.request = function (...args) {
    assert.equal(args[1].timeout, /^\/api\/uploads\/[0-9a-f]{32}\/chunks\//.test(args[0].pathname) && args[1].method === "PUT" ? 0 : 30000);
    args[1] = { ...args[1], agent: backendAgent };
    const proxy = original.request.apply(this, args);
    const record = { destroyCalls: 0, timeouts: [], stream: proxy }; trace.proxies.push(record);
    const downstream=trace.responses.at(-1);let upstream;
    proxy.on("response",response=>{upstream=response;recordLife("UPSTREAM_HEADERS",downstream,proxy,upstream);
      response.on("end",()=>recordLife("UPSTREAM_END",downstream,proxy,upstream));response.on("aborted",()=>recordLife("UPSTREAM_ABORTED",downstream,proxy,upstream));response.on("error",()=>recordLife("UPSTREAM_ERROR",downstream,proxy,upstream));});
    proxy.on("error",()=>recordLife("PROXY_ERROR",downstream,proxy,upstream));
    if (options.corkProxy) proxy.once("socket", (socket) => {
      const hold = () => { proxy.cork(); record.corked = true; };
      if (socket.connecting) socket.once("connect", hold); else hold();
    });
    const destroy = proxy.destroy;
    proxy.destroy = function (...values) {
      const stack=new Error().stack;const reason=["finished","closed","aborted","fail"].find(name=>new RegExp("\\b"+name+"\\b").test(stack))||"OTHER";
      recordLife("PROXY_DESTROY",downstream,proxy,upstream,reason);record.destroyCalls++;return destroy.apply(this,values);
    };
    const setTimeout = proxy.setTimeout;
    proxy.setTimeout = function (millis, ...values) { record.timeouts.push(millis); return setTimeout.call(this, millis, ...values); };
    proxy.on("unpipe", (source) => queueMicrotask(() => trace.unpipeDataListenerCounts.push(source.listeners("data").filter(listener=>listener.name!=="progress").length)));
    return proxy;
  };
  console.info = function (...args) {
    if (options.failLog) throw new Error("TEST_SECRET_CANARY");
    if (typeof args[0] === "string" && args[0].startsWith(prefix)) trace.records.push(JSON.parse(args[0].slice(prefix.length)));
    else if (typeof args[0] === "string" && args[0].startsWith(flowPrefix)) trace.flowRecords.push(JSON.parse(args[0].slice(flowPrefix.length)));
    else original.info.apply(this, args);
  };
  async function close() {
    http.createServer = original.createServer; http.request = original.request; backendAgent.destroy();
    if (server?.listening) { const stopped = new Promise((resolve) => server.close(resolve)); server.closeAllConnections(); await stopped; }
    console.info = original.info;
    globalThis.setInterval = original.setInterval; globalThis.clearInterval = original.clearInterval; process.hrtime.bigint = original.hrtime;
    globalThis.setTimeout=original.setTimeout;globalThis.clearTimeout=original.clearTimeout;
    assert.ok(trace.policyTimers.every(entry=>!entry.active),"all protection timers stop after response/abort, independently of observation");
    assert.ok(trace.policyTimers.every(entry=>!entry.handle.hasRef()),"policy timers are unref'ed");
    const outstanding = trace.intervals.filter((entry) => entry.active);
    for (const entry of outstanding) original.clearInterval(entry.handle);
    for (const [key, value] of Object.entries(env)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; }
    assert.ok(!server?.listening);
    for (const req of trace.requests) assert.equal(req.listenerCount("data"), 0, "upload observer must detach when its original pipe ends");
    for (const req of trace.requests) { assert.equal(req.listenerCount("pause"), 0); assert.equal(req.listenerCount("resume"), 0); }
    for (const entry of trace.proxies) assert.equal(entry.stream.listenerCount("drain"), 0);
    assert.equal(outstanding.length, 0, "all upload sampling timers must be cleared before harness cleanup");
    const stat = await fs.lstat(temporary); const resolved = await fs.realpath(temporary);
    assert.ok(stat.isDirectory() && !stat.isSymbolicLink() && path.isAbsolute(resolved));
    assert.equal(resolved, temporary); assert.equal(path.dirname(resolved), tempRoot); assert.ok(path.basename(resolved).startsWith("wtl-upload-proxy-"));
    await fs.rm(resolved, { recursive: true, force: true });
    assert.equal(await fs.lstat(resolved).then(() => true, (error) => { if (error.code === "ENOENT") return false; throw error; }), false);
  }
  try { await import(pathToFileURL(path.join(temporary, "server.mjs")).href); http.createServer = original.createServer; await ready; }
  catch (error) { await close(); throw error; }
  return { ...trace, url: `http://127.0.0.1:${server.address().port}`, request: original.request, close,
    tick: (fail = false) => {
      try {
        if (fail) process.hrtime.bigint = () => { throw new Error("TEST_SECRET_CANARY"); };
        for (const entry of trace.intervals) if (entry.active) entry.callback();
      } finally { process.hrtime.bigint = original.hrtime; }
    },
    sourceSha256: createHash("sha256").update(source).digest("hex") };
}

function verifyRecords(records) {
  const fields = ["uploadId", "chunkIndex", "requestId", "startedAtUtc", "sequence", "elapsedMillis", "event",
    "firstObservedTerminationEvent", "requestBodyBytes", "lastNonemptyBodyDataElapsedMillis", "lastBodyDataAgeMillis",
    "requestComplete", "requestAborted", "proxyWritableFinished", "upstreamStatusCode", "upstreamComplete",
    "downstreamWritableFinished", "downstreamDestroyed", "transferObservationMeaning", "error"];
  const groups = Map.groupBy(records, (entry) => entry.requestId);
  for (const entries of groups.values()) {
    assert.ok(entries.length <= 12);
    for (const [index, entry] of entries.entries()) {
      assert.deepEqual(Object.keys(entry).sort(), [...fields].sort());
      assert.equal(entry.sequence, index + 1);
      assert.ok(entry.elapsedMillis >= (entries[index - 1]?.elapsedMillis || 0));
      assert.ok(entry.requestBodyBytes >= (entries[index - 1]?.requestBodyBytes || 0));
      assert.equal(entry.transferObservationMeaning, "NODE_BUFFER_EVENTS_NOT_UPSTREAM_RECEIPT");
      for (const [key, value] of Object.entries(entry.error)) { assert.ok(["name", "code", "syscall"].includes(key)); assert.match(value, /^[A-Za-z0-9_.:-]{1,64}$/); }
    }
  }
  assert.ok(!JSON.stringify(records).includes("TEST_SECRET_CANARY"));
}

function verifyFlowRecords(records) {
  const fields = ["uploadId", "chunkIndex", "requestId", "startedAtUtc", "stopEvent", "sampleIntervalMillis", "sampleLimit",
    "sampleCount", "droppedSamples", "requestBodyBytes", "pauseCount", "resumeCount", "drainCount", "observationFailures", "samples", "final", "queueMeaning", "tickGapMeaning"];
  const sampleFields = ["elapsedMillis", "tickGapMillis", "requestBodyBytes", "deltaBodyBytes", "requestPaused", "requestReadableFlowing",
    "requestReadableLength", "proxyWritableLength", "proxyWritableNeedDrain", "pauseCount", "resumeCount", "drainCount", "currentPauseMillis", "pausedMillis", "lastDrainAgeMillis", "lastBodyDataAgeMillis"];
  for (const entry of records) {
    assert.deepEqual(Object.keys(entry).sort(), [...fields].sort());
    assert.equal(entry.sampleIntervalMillis, 1000); assert.equal(entry.sampleLimit, 16);
    assert.equal(entry.samples.length, Math.min(16, entry.sampleCount)); assert.equal(entry.droppedSamples, Math.max(0, entry.sampleCount - 16));
    assert.equal(entry.queueMeaning, "NODE_STREAM_BUFFERS_NOT_OS_OR_UPSTREAM_RECEIPT");
    assert.equal(entry.tickGapMeaning, "MONOTONIC_SAMPLING_INTERVAL_NOT_A_SINGLE_CAUSE");
    assert.ok(Buffer.byteLength(JSON.stringify(entry)) < 16384);
    for (const sample of [...entry.samples, ...(entry.final ? [entry.final] : [])]) {
      assert.deepEqual(Object.keys(sample).sort(), [...sampleFields].sort());
      assert.ok(sample.elapsedMillis >= 0 && sample.tickGapMillis >= 0 && sample.deltaBodyBytes >= 0);
    }
  }
  for (const entries of Map.groupBy(records, (entry) => entry.requestId).values()) assert.equal(entries.length, 1);
  assert.ok(!JSON.stringify(records).includes("TEST_SECRET_CANARY"));
}

const uploadId = "0123456789abcdef0123456789abcdef";
const uploadBody = Buffer.alloc(1 << 20, 0x5a);
uploadBody.write("TEST_SECRET_CANARY");
function startUpload(proxy, write = (request) => request.end(uploadBody), body = uploadBody) {
  let request,observedResponse,responseBytes=0;
  const result = new Promise((resolve, reject) => {
    const deadline = setTimeout(() => reject(new Error("LOCAL_UPLOAD_HTTP_DEADLINE")), 3000);
    request = proxy.request(`${proxy.url}/api/uploads/${uploadId}/chunks/0?secret=TEST_SECRET_CANARY`, {
      method: "PUT", agent: false, headers: { "Content-Length": body.length, Authorization: "Bearer TEST_SECRET_CANARY" },
    }, (response) => {
      observedResponse=response;
      proxy.clientEvents.push({event:"HEADERS",status:response.statusCode});
      const parts = []; response.on("data", (part) => {responseBytes+=part.length;parts.push(part)}); response.on("error", error=>{proxy.clientEvents.push({event:"ERROR",bytes:responseBytes,complete:response.complete,code:error.code});reject(error)});
      response.on("end", () => { proxy.clientEvents.push({event:"END",bytes:responseBytes,complete:response.complete});clearTimeout(deadline); resolve({ status: response.statusCode, body: JSON.parse(Buffer.concat(parts).toString()) }); });
    });
    request.on("error", reject); request.once("close", () => clearTimeout(deadline)); write(request);
  }).then((response) => ({ response }), (error) => ({ errorCode: error.code || error.name,responseStatus:observedResponse?.statusCode,
    responseBytes,responseComplete:observedResponse?.complete,declaredResponseLength:observedResponse?.headers["content-length"] }));
  return { request, result };
}

for (const mode of ["idle","total","watchdog","finish-is-not-receipt","abort","observation-detached","setup-failure","backpressure","paused-total"]) test(`upload deadline ${mode} stays bounded and cleans independent guards`, {timeout:7000}, async(t)=>{
  const heldRead=mode.startsWith("finish-");
  let requests=0,received=0,fixtureCommits=0,releaseBody;
  const sentBody=heldRead?uploadBody.subarray(0,65536):uploadBody;
  const upstream=http.createServer((req,res)=>{
    requests++;req.on("error",()=>{});req.on("data",chunk=>{received+=chunk.length});
    req.on("end",()=>{if(heldRead || mode==="backpressure") setTimeout(()=>{fixtureCommits++;res.writeHead(200,{"X-Sample-Protocol":"2"});res.end(JSON.stringify({number:0,sha256:createHash("sha256").update(sentBody).digest("hex"),duplicate:false}))},mode==="backpressure"?180:0)});
    if(heldRead){req.pause();releaseBody=()=>req.resume();}
  });
  upstream.listen(0,"127.0.0.1");await once(upstream,"listening");let proxy,client,tick;
  try{
    const policy={body:["total","observation-detached","paused-total"].includes(mode)?180:heldRead?2000:900,idle:heldRead?500:70,connect:300,overall:mode==="watchdog"?200:heldRead?4000:1200};
    proxy=await gateway(`http://127.0.0.1:${upstream.address().port}`,{policy,failPolicyTimer:mode==="setup-failure",corkProxy:mode==="backpressure"||mode==="paused-total"});
    client=startUpload(proxy,(req)=>{
      if(heldRead || ["watchdog","setup-failure","backpressure","paused-total"].includes(mode))req.end(sentBody);
      else {req.write(uploadBody.subarray(0,32768));if(mode==="total"||mode==="observation-detached")tick=setInterval(()=>req.write(Buffer.from([1])),20)}
    },sentBody);
    client.request.once("response",()=>{if(tick){clearInterval(tick);tick=undefined}});
    if(mode==="abort"){await waitFor(()=>proxy.requests[0]?.listenerCount("data")===3);client.request.destroy();}
    if(mode==="observation-detached"){await waitFor(()=>proxy.proxies.length===1);proxy.requests[0].unpipe(proxy.proxies[0].stream);}
    if(mode==="backpressure") {await waitFor(()=>proxy.proxies[0]?.corked && proxy.requests[0].isPaused());await new Promise(r=>setTimeout(r,130));proxy.proxies[0].stream.uncork();}
    if(heldRead) {await waitFor(()=>releaseBody && proxy.records.some(r=>r.event==="PROXY_FINISH_LOCAL_OS_DELIVERY_ONLY"));assert.equal(received,0,"local finish precedes actual upstream body consumption");await new Promise(r=>setTimeout(r,700));releaseBody();}
    const began=performance.now();const result=await client.result;
    if(mode==="abort") assert.equal(result.errorCode,"ECONNRESET");
    else if(["total","paused-total","setup-failure"].includes(mode) && !result.response) assert.equal(result.errorCode,"ECONNRESET","only explicit connection failure, never an ACK");
    else assert.equal(result.response?.status,heldRead || mode==="backpressure"?200:502,JSON.stringify(result));
    if(heldRead) {assert.equal(received,sentBody.length);assert.ok(proxy.records.some(r=>r.event==="PROXY_FINISH_LOCAL_OS_DELIVERY_ONLY"));}
    assert.equal(proxy.proxies.length,mode==="setup-failure"?0:1);assert.equal(requests,mode==="setup-failure"?0:1);
    assert.equal(fixtureCommits,heldRead || mode==="backpressure"?1:0,"no successful effect in the controlled upstream after failure");
    await waitFor(()=>proxy.policyTimers.every(r=>!r.active));
    await waitFor(()=>client.request.destroyed && proxy.proxies.every(r=>r.stream.destroyed));
    t.diagnostic(JSON.stringify({mode,requests,received,fixtureCommits,requestTerminated:client.request.destroyed,policyTimers:proxy.policyTimers.length,active:proxy.policyTimers.filter(r=>r.active).length}));
    if(heldRead)t.diagnostic(JSON.stringify({lifecycle:proxy.lifecycle,client:proxy.clientEvents}));
  } catch(error){t.diagnostic(error.stack);t.diagnostic(JSON.stringify({requests,received,fixtureCommits,events:proxy?.records.map(({event,upstreamStatusCode,error})=>({event,upstreamStatusCode,error})),policy:proxy?.policyTimers.map(({millis,active,fired})=>({millis,active,fired})),lifecycle:proxy?.lifecycle,client:proxy?.clientEvents,flow:proxy?.flowRecords}));throw error;}
  finally {releaseBody?.();if(tick)clearInterval(tick);client?.request.destroy();try{await proxy?.close()}finally{const stopped=new Promise(r=>upstream.close(r));upstream.closeAllConnections();await stopped;}}
});

test("upload deadline bounds blocked actual Go sync",{timeout:7000},async(t)=>{
  const origin=process.env.UPLOAD_TEST_GO_ORIGIN,id=process.env.UPLOAD_TEST_GO_ID;
  if(!origin||!id){t.skip("explicit Go owned sync fixture only");return}
  const proxy=await gateway(origin,{policy:{body:5000,idle:2000,connect:1000,overall:1500}});let request;
  try{
    const result=await new Promise((resolve,reject)=>{
      request=proxy.request(`${proxy.url}/api/uploads/${id}/chunks/0`,{method:"PUT",agent:false,headers:{"Content-Length":65536}},res=>{res.resume();res.on("end",()=>resolve(res.statusCode));res.on("error",reject)});
      request.on("error",reject);request.end(Buffer.alloc(65536,0x5a));
    });
    assert.equal(result,502);assert.equal(proxy.proxies.length,1);assert.ok(proxy.records.some(r=>r.event==="PROXY_FINISH_LOCAL_OS_DELIVERY_ONLY"));
    await waitFor(()=>proxy.policyTimers.every(r=>!r.active));t.diagnostic("NODE_WATCHDOG_ACTUAL_GO_BLOCKED_SYNC_FINITE_FAILURE=true");
  }finally{request?.destroy();await proxy.close()}
});

test("browser chunk fixed timer clears on success error timeout and early abort",async()=>{
  const source=await fs.readFile(path.resolve(path.dirname(fileURLToPath(import.meta.url)),"../src/client/client.ts"),"utf8");
  const block=/controller = new AbortController\(\);[\s\S]*?controller = undefined;\n      }/.exec(source)?.[0];assert.ok(block);
  const execute=new (Object.getPrototypeOf(async function(){}).constructor)("api","file","u","n","paused","setTimeout","clearTimeout","AbortController","let controller;"+block+";return controller;");
  for(const mode of ["success","error","timeout","abort"]){
    let timer,clears=0,controller,signal;class Controller extends AbortController{constructor(){super();controller=this}}
    const action=execute(async(_url,options)=>{signal=options.signal;if(mode==="success")return {};if(mode==="error")throw new Error("CONTROLLED_FAILURE");return new Promise((_,reject)=>{signal.addEventListener("abort",()=>reject(new DOMException("cancelled","AbortError")),{once:true});queueMicrotask(()=>mode==="timeout"?timer():controller.abort())})},
      {slice:()=>new Blob([uploadBody])},{id:uploadId,chunkSize:1<<20},0,false,(callback,millis)=>{assert.equal(millis,300000);timer=callback;return 1},(handle)=>{assert.equal(handle,1);clears++},Controller);
    if(mode==="success")assert.equal(await action,undefined);else await assert.rejects(action,mode==="error"?{message:"CONTROLLED_FAILURE"}:{name:"AbortError"});
    assert.equal(clears,1);assert.equal(signal.aborted,mode==="timeout"||mode==="abort");
  }
});

test("upload connection guard refuses stalled socket and detaches before dispatch",async()=>{
  const source=await fs.readFile(path.resolve(path.dirname(fileURLToPath(import.meta.url)),"../src/server/upload-budget.ts"),"utf8");
  const {protectUpload}=await import("data:text/javascript,"+encodeURIComponent(stripTypeScriptTypes(source)));
  const req=new EventEmitter();req.isPaused=()=>false;req.unpipe=()=>{};req.complete=false;
  const res=new EventEmitter();res.headersSent=false;res.writableEnded=false;let status;
  res.writeHead=(value)=>{status=value;res.headersSent=true};res.end=()=>{res.writableEnded=true;res.emit("finish")};res.destroy=()=>{};
  const proxy=new EventEmitter();proxy.destroyed=false;proxy.destroy=()=>{proxy.destroyed=true};
  const socket=new EventEmitter();socket.connecting=true;
  const guard=protectUpload(req,res,{body:300,idle:200,connect:40,overall:500});assert.ok(guard);guard.attach(proxy);proxy.emit("socket",socket);
  const began=performance.now();await waitFor(()=>res.writableEnded);
  assert.equal(status,502);assert.ok(performance.now()-began>=30);assert.ok(proxy.destroyed);
  assert.deepEqual(req.eventNames(),[]);assert.deepEqual(res.eventNames(),[]);assert.deepEqual(socket.eventNames(),[]);assert.deepEqual(proxy.eventNames(),[]);
});

test("upload flow samples actual Node backpressure and bounded tick gaps", { timeout: 8000 }, async (t) => {
  let received = 0;
  const upstream = http.createServer((req, res) => {
    req.on("data", (chunk) => { received += chunk.length; });
    req.on("end", () => { res.writeHead(200, { "X-Sample-Protocol": "2" }); res.end(JSON.stringify({ number: 0, sha256: createHash("sha256").update(uploadBody).digest("hex"), duplicate: false })); });
  });
  upstream.listen(0, "127.0.0.1"); await once(upstream, "listening"); let proxy, client;
  try {
    proxy = await gateway(`http://127.0.0.1:${upstream.address().port}`, { corkProxy: true }); client = startUpload(proxy);
    await waitFor(() => proxy.proxies[0]?.corked && proxy.requests[0]?.isPaused() && proxy.proxies[0]?.stream.writableNeedDrain && proxy.requests[0].listenerCount("data") === 3);
    assert.equal(proxy.intervals.length, 1); assert.equal(proxy.intervals[0].millis, 1000); assert.equal(proxy.intervals[0].handle.hasRef(), false);
    const heldUntil = performance.now() + 1150;
    while (performance.now() < heldUntil) {} // A controlled local scheduling stall, not a network diagnosis.
    await waitFor(() => proxy.intervals[0].fires === 1);
    for (let index = 0; index < 19; index++) proxy.tick();
    assert.equal(proxy.records.length, 1, "progress does not consume the original event quota"); assert.equal(proxy.flowRecords.length, 0);
    const receivedWhileHeld = received; assert.ok(receivedWhileHeld < uploadBody.length);
    proxy.proxies[0].stream.uncork(); const result = await client.result;
    if (result.response?.status !== 200) t.diagnostic(JSON.stringify({ received, result, events: proxy.records.map((entry) => ({ event: entry.event, bytes: entry.requestBodyBytes, error: entry.error })), flow: proxy.flowRecords }));
    assert.equal(result.response?.status, 200, JSON.stringify(result)); assert.equal(received, uploadBody.length);
    await waitFor(() => proxy.flowRecords.length === 1 && proxy.intervals.every((entry) => !entry.active));
    const flow = proxy.flowRecords[0]; verifyFlowRecords(proxy.flowRecords); verifyRecords(proxy.records);
    assert.equal(flow.sampleCount, 20); assert.equal(flow.droppedSamples, 4); assert.ok(flow.samples[0].elapsedMillis < flow.samples[1].elapsedMillis);
    assert.ok(flow.samples.some((sample) => sample.requestPaused && sample.proxyWritableNeedDrain && sample.proxyWritableLength > 0));
    assert.ok(flow.samples[0].requestBodyBytes > receivedWhileHeld, "the observed Node queue is not an upstream body receipt");
    assert.ok(flow.samples[0].tickGapMillis >= 1100, "native monotonic time records the deliberately delayed first timer tick");
    assert.ok(flow.pauseCount > 0 && flow.resumeCount > 0 && flow.drainCount > 0); assert.equal(flow.final.requestBodyBytes, uploadBody.length);
    assert.ok(proxy.records.some((entry) => entry.event === "REQUEST_END" && entry.requestBodyBytes === uploadBody.length));
    assert.deepEqual(proxy.proxies[0].timeouts, []); t.diagnostic("NODE_BACKPRESSURE_PASSIVE_RING_AND_FINAL_COUNTERS_VERIFIED=true");
  } finally {
    client?.request.destroy(); await proxy?.close(); const stopped = new Promise((resolve) => upstream.close(resolve)); upstream.closeAllConnections(); await stopped;
  }
});

for (const ending of ["abort", "error", "timeout", "unpipe", "response-aborted"]) test(`upload flow cleans observers on ${ending} without spending terminal quota`, { timeout: 8000 }, async () => {
  let heldResponse;
  const upstream = http.createServer((req, res) => {
    req.on("error", () => {});
    if (ending === "response-aborted") {
      req.resume(); heldResponse = res;
      res.writeHead(200, { "X-Sample-Protocol": "2", "Content-Type": "application/json", "Content-Length": 128 }); res.write("{");
    }
  });
  upstream.listen(0, "127.0.0.1"); await once(upstream, "listening"); let proxy, client;
  try {
    proxy = await gateway(`http://127.0.0.1:${upstream.address().port}`); client = startUpload(proxy, (request) => request.write(uploadBody.subarray(0, 32768)));
    await waitFor(() => proxy.requests[0]?.listenerCount("data") === 3 && proxy.proxies.length === 1);
    if (ending === "response-aborted") await waitFor(() => proxy.records.some((entry) => entry.event === "UPSTREAM_RESPONSE_HEADERS"));
    for (let index = 0; index < 40; index++) proxy.tick();
    assert.equal(proxy.records.length, ending === "response-aborted" ? 2 : 1); const stream = proxy.proxies[0].stream;
    if (ending === "abort") client.request.destroy();
    else if (ending === "error") stream.destroy(Object.assign(new Error("TEST_SECRET_CANARY"), { code: "ECONNRESET" }));
    else if (ending === "timeout") stream.emit("timeout");
    else if (ending === "response-aborted") heldResponse.destroy();
    else { proxy.requests[0].unpipe(stream); stream.destroy(Object.assign(new Error("TEST_SECRET_CANARY"), { code: "ECONNRESET" })); }
    await client.result; await waitFor(() => proxy.flowRecords.length === 1 && proxy.intervals.every((entry) => !entry.active));
    const first = proxy.records.find((entry) => entry.firstObservedTerminationEvent !== null);
    const nativeTermination=ending==="response-aborted"?proxy.lifecycle.find(entry=>["PROXY_ERROR","UPSTREAM_ABORTED"].includes(entry.event))?.event:null;
    if(ending==="response-aborted")assert.ok(nativeTermination,"observe the actual transport termination order independently");
    assert.equal(first.firstObservedTerminationEvent, ending === "abort" ? "REQUEST_ABORTED" : ending === "timeout" ? "UPSTREAM_TIMEOUT" : ending === "response-aborted" ? nativeTermination==="UPSTREAM_ABORTED"?"RESPONSE_ABORTED":"PROXY_ERROR" : "PROXY_ERROR");
    if (ending === "error") { for (let index = 0; index < 20; index++) stream.emit("error", Object.assign(new Error("TEST_SECRET_CANARY"), { code: "EPIPE" })); assert.equal(proxy.records.length, 12); }
    assert.equal(proxy.flowRecords.length, 1); assert.equal(proxy.flowRecords[0].sampleCount, 40); assert.equal(proxy.flowRecords[0].droppedSamples, 24);
    verifyFlowRecords(proxy.flowRecords); verifyRecords(proxy.records); assert.deepEqual(proxy.proxies[0].timeouts, []);
    assert.equal(proxy.requests[0].listenerCount("pause"), 0); assert.equal(proxy.requests[0].listenerCount("resume"), 0);
  } finally {
    client?.request.destroy(); await proxy?.close(); const stopped = new Promise((resolve) => upstream.close(resolve)); upstream.closeAllConnections(); await stopped;
  }
});

for (const fault of ["clock", "log", "setup"]) test(`upload flow observation ${fault} failure preserves the response and timeout`, { timeout: 8000 }, async () => {
  let received = 0;
  const upstream = http.createServer((req, res) => {
    req.on("data", (chunk) => { received += chunk.length; });
    req.on("end", () => { res.writeHead(200, { "X-Sample-Protocol": "2" }); res.end(JSON.stringify({ number: 0, sha256: createHash("sha256").update(uploadBody).digest("hex"), duplicate: false })); });
  });
  upstream.listen(0, "127.0.0.1"); await once(upstream, "listening"); let proxy, client;
  try {
    proxy = await gateway(`http://127.0.0.1:${upstream.address().port}`, { failLog: fault === "log", failTimer: fault === "setup" });
    client = startUpload(proxy, (request) => request.write(uploadBody.subarray(0, 32768)));
    await waitFor(() => proxy.proxies.length === 1 && (fault === "setup" || proxy.intervals.length === 1));
    if (fault === "clock") proxy.tick(true);
    client.request.end(uploadBody.subarray(32768)); const result = await client.result;
    assert.equal(result.response?.status, 200, JSON.stringify(result)); assert.equal(received, uploadBody.length); assert.deepEqual(proxy.proxies[0].timeouts, []);
    await waitFor(() => proxy.intervals.every((entry) => !entry.active));
    if (fault !== "log") { verifyFlowRecords(proxy.flowRecords); assert.ok(proxy.flowRecords[0].observationFailures > 0); }
  } finally {
    client?.request.destroy(); await proxy?.close(); const stopped = new Promise((resolve) => upstream.close(resolve)); upstream.closeAllConnections(); await stopped;
  }
});

test("upload flow does not schedule timers outside its upload guard", { timeout: 8000 }, async () => {
  let upstreamRequests = 0;
  const upstream = http.createServer((req, res) => { upstreamRequests++; res.end(); });
  upstream.listen(0, "127.0.0.1"); await once(upstream, "listening"); let proxy;
  try {
    proxy = await gateway(`http://127.0.0.1:${upstream.address().port}`);
    const source = await fs.readFile(path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/server/server.ts"), "utf8");
    const guard = source.indexOf("    if (observeUpload && !uploadObservationDetached) {");
    const guarded = source.slice(guard, source.indexOf("\n    return;", guard));
    assert.equal(source.match(/setInterval\(/g).length, 1); assert.ok(guard >= 0 && guarded.includes("setInterval(") && guarded.includes("uploadTimer.unref()"));
    assert.ok(source.includes('const uploadMatch = req.method === "PUT" ? /^\\/api\\/uploads\\/'));
    assert.ok(source.includes('const observeUpload = upload ?')); assert.ok(source.includes('if (downloadVersion) proxy.setTimeout(0);'));
    await new Promise((resolve, reject) => {
      const request = proxy.request(proxy.url + "/healthz", { agent: false }, (response) => {
        assert.equal(response.statusCode, 200); response.resume(); response.on("end", resolve); response.on("error", reject);
      }); request.on("error", reject); request.end();
    });
    assert.equal(proxy.intervals.length, 0); assert.equal(proxy.flowRecords.length, 0); assert.equal(proxy.records.length, 0);
    assert.equal(proxy.proxies.length, 0); assert.equal(upstreamRequests, 0);
  } finally {
    await proxy?.close(); const stopped = new Promise((resolve) => upstream.close(resolve)); upstream.closeAllConnections(); await stopped;
  }
});

test("upload proxy distinguishes body transfer from processing and detaches observers", { timeout: 8000 }, async (t) => {
  const body = Buffer.alloc(1 << 20, 0x5a); const id = "0123456789abcdef0123456789abcdef";
  let receivedProcessingBody; const processingBody = new Promise((resolve) => { receivedProcessingBody = resolve; });
  let releaseProcessing; const processingRelease = new Promise((resolve) => { releaseProcessing = resolve; });
  const counts = new Map();
  const upstream = http.createServer((req, res) => {
    const index = Number(new URL(req.url, "http://localhost").pathname.split("/").at(-1));
    counts.set(index, 0);
    if (index === 2) { req.once("data", () => req.socket.destroy()); return; }
    req.on("data", (chunk) => counts.set(index, counts.get(index) + chunk.length));
    req.on("end", async () => {
      if (index === 1) { receivedProcessingBody(); await processingRelease; }
      res.writeHead(200, { "X-Sample-Protocol": "2" });
      res.end(JSON.stringify({ number: index, sha256: createHash("sha256").update(body).digest("hex"), duplicate: false }));
    });
  });
  const listening = once(upstream, "listening"); upstream.listen(0, "127.0.0.1"); await listening;
  let proxy; const clients = []; const timers = [];
  try {
    proxy = await gateway(`http://127.0.0.1:${upstream.address().port}`);
    function send(index, mode) {
      return new Promise((resolve, reject) => {
        let received = false;
        const deadline = setTimeout(() => reject(new Error("LOCAL_UPLOAD_HTTP_DEADLINE")), 3000); timers.push(deadline);
        const req = proxy.request(`${proxy.url}/api/uploads/${id}/chunks/${index}?secret=TEST_SECRET_CANARY`, {
          method: "PUT", agent: false, headers: { "Content-Length": body.length, Authorization: "Bearer TEST_SECRET_CANARY" },
        }, (res) => {
          received = true; clients.push(res); const chunks = [];
          res.on("data", (value) => chunks.push(value)); res.on("error", reject);
          res.on("end", () => { clearTimeout(deadline); resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString("utf8")) }); });
        });
        clients.push(req); req.on("error", (error) => { if (!received) reject(error); });
        if (mode === "slow") { req.write(body.subarray(0, 32768)); const timer = setTimeout(() => req.end(body.subarray(32768)), 120); timers.push(timer); }
        else if (mode === "partial") req.write(body.subarray(0, 32768));
        else req.end(body);
      });
    }
    const slow = send(0, "slow");
    await waitFor(() => proxy.requests[0]?.listenerCount("data") === 3);
    assert.ok(!proxy.records.some((entry) => entry.chunkIndex === 0 && entry.event === "REQUEST_END"));
    const first = await slow; assert.equal(first.status, 200); assert.equal(first.body.duplicate, false); assert.equal(counts.get(0), body.length);
    const processing = send(1, "full"); await processingBody;
    await waitFor(() => proxy.records.some((entry) => entry.chunkIndex === 1 && entry.event === "PROXY_FINISH_LOCAL_OS_DELIVERY_ONLY"));
    const held = proxy.records.filter((entry) => entry.chunkIndex === 1);
    assert.ok(held.some((entry) => entry.event === "REQUEST_END" && entry.requestBodyBytes === body.length));
    assert.ok(!held.some((entry) => entry.event === "UPSTREAM_RESPONSE_HEADERS"));
    releaseProcessing(); assert.equal((await processing).status, 200); assert.equal(counts.get(1), body.length);
    const disconnected = await send(2, "partial").then((response) => response, (error) => ({ errorCode: error.code }));
    assert.ok(disconnected.status === 502 || disconnected.errorCode === "ECONNRESET");
    await waitFor(() => proxy.unpipeDataListenerCounts.length >= 3);
    assert.ok(proxy.unpipeDataListenerCounts.every((count) => count === 0));
    assert.ok(proxy.proxies.every((record) => record.timeouts.length === 0), "chunk protection replaces the old socket timeout without a finish-based ACK timer");
    verifyRecords(proxy.records);
    verifyFlowRecords(proxy.flowRecords);
    t.diagnostic(JSON.stringify({ marker: "LOCAL_UPLOAD_PROXY_BODY_VS_PROCESSING_VERIFIED", bytes: body.length, sourceSha256: proxy.sourceSha256,
      unpipeDataListenerCounts: proxy.unpipeDataListenerCounts, proxyDestroyCalls: proxy.proxies.map((entry) => entry.destroyCalls),
      requests: proxy.proxies.length, events: proxy.records.map((entry) => ({ chunkIndex: entry.chunkIndex, event: entry.event, bytes: entry.requestBodyBytes })) }));
  } finally {
    releaseProcessing(); for (const timer of timers) clearTimeout(timer); for (const client of clients) if (!client.destroyed) client.destroy();
    await proxy?.close(); const stopped = new Promise((resolve) => upstream.close(resolve)); upstream.closeAllConnections(); await stopped;
    assert.equal(upstream.listening, false); t.diagnostic("LOCAL_UPLOAD_PROXY_LISTENERS_CLOSED=true TEMP_REMOVED=true DATA_OBSERVERS_REMOVED=true");
  }
});

test("upload cancellation reaches actual Go handler", { timeout: 7000 }, async (t) => {
  const origin = process.env.UPLOAD_TEST_GO_ORIGIN; const id = process.env.UPLOAD_TEST_GO_ID;
  if (!origin || !id) { t.skip("Run through the focused Go cancellation test with its real Store and Routes"); return; }
  assert.match(id, /^[0-9a-f]{32}$/);
  const proxy = await gateway(origin); let request;
  const cancelReady = once(process.stdin, "data");
  try {
    await new Promise((resolve, reject) => {
      request = proxy.request(`${proxy.url}/api/uploads/${id}/chunks/0`, { method: "PUT", agent: false, headers: { "Content-Length": 1 << 20 } });
      request.on("error", () => {}); request.once("close", resolve);
      request.write(Buffer.alloc(32768, 0x5a), () => { cancelReady.then(() => request.destroy(), reject); });
      const deadline = setTimeout(() => reject(new Error("LOCAL_CLIENT_CANCEL_DEADLINE")), 3000);
      request.once("close", () => clearTimeout(deadline));
    });
    await waitFor(() => proxy.records.some((entry) => entry.event === "REQUEST_ABORTED") && proxy.unpipeDataListenerCounts.length > 0);
    assert.ok(proxy.unpipeDataListenerCounts.every((count) => count === 0));
    assert.equal(proxy.proxies.length, 1); assert.ok(proxy.proxies[0].stream.destroyed, "the sole owned upstream must be terminated");
    assert.deepEqual(proxy.proxies[0].timeouts, []);
    verifyRecords(proxy.records);
    t.diagnostic(JSON.stringify({ marker: "LOCAL_UPLOAD_NODE_REAL_GO_CANCEL_SENT", sourceSha256: proxy.sourceSha256,
      proxyRequests: proxy.proxies.length, proxyDestroyCalls: proxy.proxies[0].destroyCalls, unpipeDataListenerCounts: proxy.unpipeDataListenerCounts,
      events: proxy.records.map((entry) => ({ event: entry.event, bytes: entry.requestBodyBytes, first: entry.firstObservedTerminationEvent })) }));
  } finally {
    if (request && !request.destroyed) request.destroy(); await proxy.close();
    t.diagnostic("LOCAL_UPLOAD_NODE_CANCEL_LISTENER_CLOSED=true TEMP_REMOVED=true DATA_OBSERVERS_REMOVED=true");
  }
});

test("early rejection drains request before next keepalive request", { timeout: 7000 }, async (t) => {
  const id = "0123456789abcdef0123456789abcdef";
  const seen = [];
  const upstream = http.createServer((req,res) => {
    seen.push(req.url);
    req.on("error", () => {}); req.resume();
    res.writeHead(409, {"X-Sample-Protocol":"2", "Content-Type":"application/json", "Connection":"keep-alive"});
    res.end(JSON.stringify({error:"cancelled"}));
  });
  upstream.listen(0,"127.0.0.1"); await once(upstream,"listening");
  let proxy, client;
  const responses=[]; let pending=Buffer.alloc(0); let parseError;
  try {
    proxy=await gateway(`http://127.0.0.1:${upstream.address().port}`);
    client=net.connect(new URL(proxy.url).port,"127.0.0.1");
    client.on("error",()=>{});
    client.on("data",chunk=>{
      pending=Buffer.concat([pending,chunk]);
      while(true) {
        const split=pending.indexOf("\r\n\r\n"); if(split<0) return;
        const head=pending.subarray(0,split).toString();
        const length=Number(/Content-Length: (\d+)/i.exec(head)?.[1]);
        if(!Number.isSafeInteger(length)) { parseError = new Error("EXPECTED_CONTENT_LENGTH: " + head); return; }
        if(pending.length<split+4+length) return;
        responses.push({status:Number(/^HTTP\/1\.1 (\d+)/.exec(head)[1]),body:pending.subarray(split+4,split+4+length).toString()});
        pending=pending.subarray(split+4+length);
      }
    });
    await once(client,"connect");
    client.write(`PUT /api/uploads/${id}/chunks/1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\nConnection: keep-alive\r\n\r\n`);
    client.write(Buffer.alloc(5792,0x5a));
    await waitFor(()=>responses.length===1 || parseError);
    if (parseError) throw parseError;
    assert.equal(responses[0].status,409);
    assert.equal(proxy.requests[0].complete,false);
    client.write(Buffer.alloc(1048576-5792,0x5a));
    client.write(`POST /api/uploads/${id}/complete HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n`);
    await waitFor(()=>responses.length===2 || parseError);
    if (parseError) throw parseError;
    assert.equal(responses[1].status,409);
    assert.equal(proxy.requests[0].complete,true);
    assert.equal(seen.length,2);
    t.diagnostic("EARLY_409_NEXT_REQUEST_VERIFIED=true");
  } finally {
    t.diagnostic(JSON.stringify({responses,seen,requests:proxy?.requests.map(req=>({url:req.url,complete:req.complete,paused:req.isPaused(),readableLength:req.readableLength})),records:proxy?.records}));
    client?.destroy(); await proxy?.close();
    const stopped=new Promise(resolve=>upstream.close(resolve)); upstream.closeAllConnections(); await stopped;
    t.diagnostic("LOCAL_LISTENERS_CLOSED=true TEMP_REMOVED=true");
  }
});
