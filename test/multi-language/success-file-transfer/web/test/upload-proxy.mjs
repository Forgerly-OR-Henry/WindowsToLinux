import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once } from "node:events";
import fs from "node:fs/promises";
import http from "node:http";
import net from "node:net";
import { stripTypeScriptTypes } from "node:module";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

const prefix = "FILE_TRANSFER_UPLOAD_PROXY_DIAGNOSTIC ";
async function waitFor(predicate) {
  const until = performance.now() + 3000;
  while (!predicate()) {
    if (performance.now() >= until) throw new Error("LOCAL_UPLOAD_OBSERVATION_DEADLINE");
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

async function gateway(origin) {
  assert.match(origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/);
  const tempRoot = await fs.realpath(os.tmpdir());
  const temporary = await fs.mkdtemp(path.join(tempRoot, "wtl-upload-proxy-"));
  const sourceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/server");
  const source = await fs.readFile(path.join(sourceRoot, "server.ts"), "utf8");
  const contracts = await fs.readFile(path.join(sourceRoot, "contracts.ts"), "utf8");
  assert.equal(source.split('from "./contracts.js"').length, 2);
  await fs.writeFile(path.join(temporary, "server.mjs"), stripTypeScriptTypes(source.replace('from "./contracts.js"', 'from "./contracts.mjs"')));
  await fs.writeFile(path.join(temporary, "contracts.mjs"), stripTypeScriptTypes(contracts));
  const original = { createServer: http.createServer, request: http.request, info: console.info };
  const env = Object.fromEntries(["HOST", "PORT", "API_URL", "API_TIMEOUT_MS"].map((key) => [key, process.env[key]]));
  Object.assign(process.env, { HOST: "127.0.0.1", PORT: "0", API_URL: origin, API_TIMEOUT_MS: "30000" });
  const trace = { records: [], requests: [], proxies: [], unpipeDataListenerCounts: [] };
  const backendAgent = new http.Agent({ keepAlive: true });
  let server;
  const ready = new Promise((resolve, reject) => {
    http.createServer = function (listener, ...args) {
      server = original.createServer.call(this, (req, res) => { trace.requests.push(req); listener(req, res); }, ...args);
      server.once("listening", resolve); server.once("error", reject); return server;
    };
  });
  http.request = function (...args) {
    assert.equal(args[1].timeout, 30000);
    args[1] = { ...args[1], agent: backendAgent };
    const proxy = original.request.apply(this, args);
    const record = { destroyCalls: 0, timeouts: [] }; trace.proxies.push(record);
    const destroy = proxy.destroy;
    proxy.destroy = function (...values) { record.destroyCalls++; return destroy.apply(this, values); };
    const setTimeout = proxy.setTimeout;
    proxy.setTimeout = function (millis, ...values) { record.timeouts.push(millis); return setTimeout.call(this, millis, ...values); };
    proxy.on("unpipe", (source) => queueMicrotask(() => trace.unpipeDataListenerCounts.push(source.listenerCount("data"))));
    return proxy;
  };
  console.info = function (...args) {
    if (typeof args[0] === "string" && args[0].startsWith(prefix)) trace.records.push(JSON.parse(args[0].slice(prefix.length)));
    else original.info.apply(this, args);
  };
  async function close() {
    http.createServer = original.createServer; http.request = original.request; backendAgent.destroy();
    if (server?.listening) { const stopped = new Promise((resolve) => server.close(resolve)); server.closeAllConnections(); await stopped; }
    console.info = original.info;
    for (const [key, value] of Object.entries(env)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; }
    assert.ok(!server?.listening);
    for (const req of trace.requests) assert.equal(req.listenerCount("data"), 0, "upload observer must detach when its original pipe ends");
    const stat = await fs.lstat(temporary); const resolved = await fs.realpath(temporary);
    assert.ok(stat.isDirectory() && !stat.isSymbolicLink() && path.isAbsolute(resolved));
    assert.equal(resolved, temporary); assert.equal(path.dirname(resolved), tempRoot); assert.ok(path.basename(resolved).startsWith("wtl-upload-proxy-"));
    await fs.rm(resolved, { recursive: true, force: true });
    assert.equal(await fs.lstat(resolved).then(() => true, (error) => { if (error.code === "ENOENT") return false; throw error; }), false);
  }
  try { await import(pathToFileURL(path.join(temporary, "server.mjs")).href); http.createServer = original.createServer; await ready; }
  catch (error) { await close(); throw error; }
  return { ...trace, url: `http://127.0.0.1:${server.address().port}`, request: original.request, close,
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
    await waitFor(() => proxy.requests[0]?.listenerCount("data") === 2);
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
    assert.ok(proxy.proxies.every((record) => record.timeouts.length === 0), "upload must retain its original 30000ms timeout");
    verifyRecords(proxy.records);
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
    assert.equal(proxy.proxies.length, 1); assert.equal(proxy.proxies[0].destroyCalls, 2, "retain the existing req.aborted and incomplete res.close destroy calls");
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
    res.writeHead(409, {"X-Sample-Protocol":"2", "Content-Type":"application/json", "Connection":"close"});
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
