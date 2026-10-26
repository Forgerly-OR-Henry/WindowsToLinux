import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once } from "node:events";
import fs from "node:fs/promises";
import http from "node:http";
import { stripTypeScriptTypes } from "node:module";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

function assertBackpressureObservations(records) {
  assert.ok(records.length > 0 && records.length <= 12);
  for (const [index, entry] of records.entries()) {
    const previous = records[index - 1];
    assert.equal(entry.transferObservationMeaning, "NODE_BUFFERS_NOT_CLIENT_RECEIPT");
    assert.ok(Number.isFinite(Date.parse(entry.requestStartedAtUtc)));
    assert.equal(entry.requestStartedAtUtc, records[0].requestStartedAtUtc);
    for (const key of ["upstreamPauseCount", "upstreamResumeCount", "downstreamDrainCount"]) {
      assert.ok(Number.isSafeInteger(entry[key]) && entry[key] >= (previous?.[key] || 0));
    }
    assert.ok(Number.isSafeInteger(entry.downstreamWritableLength) && entry.downstreamWritableLength >= 0);
    assert.ok(Number.isFinite(entry.upstreamPausedMillis) && entry.upstreamPausedMillis >= (previous?.upstreamPausedMillis || 0));
    assert.ok(entry.upstreamPausedMillis <= entry.elapsedMillis + 1e-6);
    for (const [timeKey, ageKey] of [
      ["upstreamLastDataElapsedMillis", "upstreamLastDataAgeMillis"],
      ["downstreamLastDrainElapsedMillis", "downstreamLastDrainAgeMillis"],
      ["upstreamPauseStartedElapsedMillis", "upstreamCurrentPauseMillis"],
    ]) {
      if (entry[timeKey] === null) assert.equal(entry[ageKey], null);
      else {
        assert.ok(Number.isFinite(entry[timeKey]) && entry[timeKey] >= 0 && entry[timeKey] <= entry.elapsedMillis);
        assert.ok(Number.isFinite(entry[ageKey]) && entry[ageKey] >= 0);
        assert.ok(Math.abs(entry[timeKey] + entry[ageKey] - entry.elapsedMillis) < 1e-6);
      }
      if (timeKey !== "upstreamPauseStartedElapsedMillis" && previous?.[timeKey] != null) {
        assert.ok(entry[timeKey] !== null && entry[timeKey] >= previous[timeKey]);
      }
    }
    assert.ok(Array.isArray(entry.upstreamRecentLongPauses) && entry.upstreamRecentLongPauses.length <= 4);
    let previousPauseEnd = 0;
    for (const interval of entry.upstreamRecentLongPauses) {
      assert.ok(Number.isFinite(interval.startElapsedMillis) && interval.startElapsedMillis >= previousPauseEnd);
      assert.ok(Number.isFinite(interval.durationMillis) && interval.durationMillis >= 1000);
      previousPauseEnd = interval.startElapsedMillis + interval.durationMillis;
      assert.ok(previousPauseEnd <= entry.elapsedMillis + 1e-6);
    }
  }
}

test("64 MiB download remains complete under controlled downstream backpressure", { timeout: 10000 }, async (t) => {
  const bytes = 64 * 1024 * 1024;
  const idleMillis = 30000;
  const tempRoot = await fs.realpath(os.tmpdir());
  const temporary = await fs.mkdtemp(path.join(tempRoot, "wtl-download-proxy-"));
  const sourceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/server");
  const source = await fs.readFile(path.join(sourceRoot, "server.ts"), "utf8");
  const contracts = await fs.readFile(path.join(sourceRoot, "contracts.ts"), "utf8");
  const importText = 'from "./contracts.js"';
  assert.equal(source.split(importText).length, 2);
  await fs.writeFile(path.join(temporary, "server.mjs"), stripTypeScriptTypes(source.replace(importText, 'from "./contracts.mjs"')));
  await fs.writeFile(path.join(temporary, "contracts.mjs"), stripTypeScriptTypes(contracts));

  const trace = { proxyRequests: 0, upstreamTimeouts: 0, upstreamPauseEvents: 0, downloadTimeouts: [], bodyBytes: 0, aborted: false, ended: false, errorType: "", producerBytes: 0 };
  const object = Buffer.alloc(bytes, 0x5a);
  const expectedDigest = createHash("sha256").update(object).digest("hex");
  const receivedDigest = createHash("sha256");
  const upstream = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Length": bytes, "X-Sample-Protocol": "2" });
    function produce() {
      while (trace.producerBytes < bytes && !res.destroyed) {
        const length = Math.min(32 * 1024, bytes - trace.producerBytes);
        const start = trace.producerBytes;
        trace.producerBytes += length;
        if (!res.write(object.subarray(start, start + length))) {
          res.once("drain", produce);
          return;
        }
      }
      if (trace.producerBytes === bytes) res.end();
    }
    produce();
  });
  const listen = once(upstream, "listening");
  upstream.listen(0, "127.0.0.1");
  await listen;
  const oldEnv = Object.fromEntries(["HOST", "PORT", "API_URL", "API_TIMEOUT_MS"].map((key) => [key, process.env[key]]));
  Object.assign(process.env, { HOST: "127.0.0.1", PORT: "0", API_URL: `http://127.0.0.1:${upstream.address().port}`, API_TIMEOUT_MS: String(idleMillis) });
  const originalCreateServer = http.createServer;
  const originalRequest = http.request;
  const originalInfo = console.info;
  const observations = [];
  let gateway;
  let request;
  let response;
  let observationDeadline;
  let release;
  let listenerClosed = false;
  const clientAgent = new http.Agent({ keepAlive: true });
  let downloadSocket;
  let downloadClosed = false;
  try {
    const gatewayReady = new Promise((resolve, reject) => {
      http.createServer = function (...args) {
        gateway = originalCreateServer.apply(this, args);
        gateway.once("listening", resolve);
        gateway.once("error", reject);
        return gateway;
      };
    });
    http.request = function (...args) {
      assert.equal(args[1].timeout, idleMillis);
      const proxy = originalRequest.apply(this, args);
      const setTimeout = proxy.setTimeout;
      proxy.setTimeout = function (millis, ...timeoutArgs) {
        trace.downloadTimeouts.push(millis);
        return setTimeout.call(this, millis, ...timeoutArgs);
      };
      trace.proxyRequests++;
      proxy.on("response", (incoming) => incoming.on("pause", () => { trace.upstreamPauseEvents++; }));
      proxy.on("timeout", () => { trace.upstreamTimeouts++; });
      return proxy;
    };
    console.info = function (...args) {
      if (typeof args[0] === "string" && args[0].startsWith("FILE_TRANSFER_DOWNLOAD_DIAGNOSTIC ")) {
        observations.push(JSON.parse(args[0].slice("FILE_TRANSFER_DOWNLOAD_DIAGNOSTIC ".length)));
      }
      originalInfo.apply(this, args);
    };
    await import(pathToFileURL(path.join(temporary, "server.mjs")).href);
    http.createServer = originalCreateServer;
    await gatewayReady;

    const result = await new Promise((resolve, reject) => {
      observationDeadline = setTimeout(() => reject(new Error("LOCAL_TEST_OBSERVATION_DEADLINE")), 5000);
      request = originalRequest(`http://127.0.0.1:${gateway.address().port}/api/versions/0123456789abcdef0123456789abcdef/download`, { agent: clientAgent }, (incoming) => {
        response = incoming;
        downloadSocket = incoming.socket;
        downloadSocket.once("close", () => { downloadClosed = true; });
        response.on("data", (chunk) => {
          trace.bodyBytes += chunk.length;
          receivedDigest.update(chunk);
        });
        response.on("aborted", () => { trace.aborted = true; });
        response.on("error", (error) => { trace.errorType = error.constructor.name; resolve(incoming); });
        response.on("end", () => { trace.ended = true; resolve(incoming); });
        response.on("close", () => resolve(incoming));
        response.pause();
        release = setTimeout(() => response.resume(), 50);
      });
      request.on("error", reject);
      request.end();
    });
    assert.equal(result.statusCode, 200);
    assert.equal(Number(result.headers["content-length"]), bytes);
    assert.ok(trace.upstreamPauseEvents > 0, "the proxy must experience backpressure");
    assert.ok(trace.bodyBytes === bytes && trace.ended && result.complete && !trace.aborted && !trace.errorType, "a truncated response must never count as a complete 64 MiB transfer");
    assert.equal(receivedDigest.digest("hex"), expectedDigest);
    assert.equal(result.headers.connection, "close", "a completed file must not enter the client's keep-alive cache");
    assert.equal(result.headers["keep-alive"], undefined);
    const closeDeadline = performance.now() + 1000;
    while (!downloadClosed) {
      assert.ok(performance.now() < closeDeadline, "the completed download socket must close without waiting for keep-alive expiry");
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    async function ordinaryRequest() {
      return new Promise((resolve, reject) => {
        const client = originalRequest(`http://127.0.0.1:${gateway.address().port}/healthz`, { agent: clientAgent }, (incoming) => {
          const socket = incoming.socket;
          const chunks = [];
          incoming.on("data", (chunk) => chunks.push(chunk));
          incoming.on("error", reject);
          incoming.on("end", () => resolve({ socket, headers: incoming.headers, body: JSON.parse(Buffer.concat(chunks).toString("utf8")) }));
        });
        client.on("error", reject); client.end();
      });
    }
    const ordinaryFirst = await ordinaryRequest();
    const ordinarySecond = await ordinaryRequest();
    assert.equal(ordinaryFirst.body.component, "node-gateway");
    assert.equal(ordinaryFirst.headers.connection, "keep-alive");
    assert.notEqual(ordinaryFirst.socket, downloadSocket);
    assert.equal(ordinaryFirst.socket, ordinarySecond.socket, "ordinary API keep-alive must remain enabled");
    assert.deepEqual(trace.downloadTimeouts, [0]);
    assertBackpressureObservations(observations);
    assert.ok(observations.every((entry) => entry.firstObservedTerminationEvent === null));
    const finish = observations.find((entry) => entry.event === "DOWNSTREAM_FINISH");
    assert.ok(finish.upstreamComplete && finish.downstreamWritableFinished);
    assert.equal(finish.upstreamDataBytes, bytes);
    assert.ok(finish.upstreamLastDataElapsedMillis !== null && finish.upstreamPauseCount > 0);
    t.diagnostic(JSON.stringify({ marker: "LOCAL_NODE_64M_BACKPRESSURE_VERIFIED", expectedBytes: object.length, idleMillis, sourceSha256: createHash("sha256").update(source).digest("hex"), sha256: expectedDigest, ...trace }));
  } finally {
    clearTimeout(observationDeadline);
    clearTimeout(release);
    t.diagnostic(JSON.stringify({ marker: "LOCAL_NODE_FINAL_OBSERVATION", ...trace }));
    response?.destroy();
    request?.destroy();
    clientAgent.destroy();
    http.createServer = originalCreateServer;
    http.request = originalRequest;
    console.info = originalInfo;
    for (const [key, value] of Object.entries(oldEnv)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    async function close(server) {
      if (!server?.listening) return;
      const done = new Promise((resolve) => server.close(resolve));
      server.closeAllConnections();
      await done;
    }
    await Promise.all([close(gateway), close(upstream)]);
    listenerClosed = !gateway?.listening && !upstream.listening;
    const directory = await fs.lstat(temporary);
    const resolved = await fs.realpath(temporary);
    assert.ok(directory.isDirectory() && !directory.isSymbolicLink());
    assert.ok(path.isAbsolute(resolved));
    assert.equal(resolved, temporary);
    assert.equal(path.dirname(resolved), tempRoot);
    assert.ok(path.basename(resolved).startsWith("wtl-download-proxy-"));
    await fs.rm(resolved, { recursive: true, force: true });
    assert.equal(await fs.lstat(resolved).then(() => true, (error) => { if (error.code === "ENOENT") return false; throw error; }), false);
    t.diagnostic(`LOCAL_NODE_LISTENERS_CLOSED=${listenerClosed}`);
    t.diagnostic("LOCAL_NODE_TEMP_DIRECTORY_REMOVED=true");
  }
  assert.ok(listenerClosed);
});

test("download diagnostics preserve the first observed termination through disconnect cascades", { timeout: 10000 }, async (t) => {
  const versions = ["11111111111111111111111111111111", "22222222222222222222222222222222"];
  const declaredBytes = 1024 * 1024;
  const prefix = Buffer.alloc(32 * 1024, 0x5a);
  const tempRoot = await fs.realpath(os.tmpdir());
  const temporary = await fs.mkdtemp(path.join(tempRoot, "wtl-download-proxy-"));
  const sourceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/server");
  const source = await fs.readFile(path.join(sourceRoot, "server.ts"), "utf8");
  const contracts = await fs.readFile(path.join(sourceRoot, "contracts.ts"), "utf8");
  const importText = 'from "./contracts.js"';
  assert.equal(source.split(importText).length, 2);
  await fs.writeFile(path.join(temporary, "server.mjs"), stripTypeScriptTypes(source.replace(importText, 'from "./contracts.mjs"')));
  await fs.writeFile(path.join(temporary, "contracts.mjs"), stripTypeScriptTypes(contracts));
  const timers = new Set();
  const clients = [];
  const observations = [];
  const proxyRequests = new Map();
  const proxyDestroys = new Map();
  const downloadTimeouts = new Map();
  let upstreamDisconnects = 0;
  let clientAborts = 0;
  let notifyObservation = () => {};
  const upstream = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Length": declaredBytes, "X-Sample-Protocol": "2" });
    if (req.url.includes(versions[0])) {
      res.write(prefix, () => {
        const timer = setTimeout(() => { upstreamDisconnects++; res.socket?.destroy(); }, 20);
        timers.add(timer);
      });
    } else {
      res.write(prefix);
      const timer = setInterval(() => { if (!res.destroyed) res.write(prefix); }, 10);
      timers.add(timer);
      res.once("close", () => clearInterval(timer));
    }
  });
  const listen = once(upstream, "listening");
  upstream.listen(0, "127.0.0.1");
  await listen;
  const oldEnv = Object.fromEntries(["HOST", "PORT", "API_URL", "API_TIMEOUT_MS"].map((key) => [key, process.env[key]]));
  Object.assign(process.env, { HOST: "127.0.0.1", PORT: "0", API_URL: `http://127.0.0.1:${upstream.address().port}`, API_TIMEOUT_MS: "30000" });
  const originalCreateServer = http.createServer;
  const originalRequest = http.request;
  const originalInfo = console.info;
  let gateway;
  try {
    const gatewayReady = new Promise((resolve, reject) => {
      http.createServer = function (...args) {
        gateway = originalCreateServer.apply(this, args);
        gateway.once("listening", resolve);
        gateway.once("error", reject);
        return gateway;
      };
    });
    http.request = function (...args) {
      const version = versions.find((value) => String(args[0]).includes(value));
      assert.ok(version);
      assert.equal(args[1].timeout, 30000);
      proxyRequests.set(version, (proxyRequests.get(version) || 0) + 1);
      const proxy = originalRequest.apply(this, args);
      const setTimeout = proxy.setTimeout;
      proxy.setTimeout = function (millis, ...timeoutArgs) {
        downloadTimeouts.set(version, [...(downloadTimeouts.get(version) || []), millis]);
        return setTimeout.call(this, millis, ...timeoutArgs);
      };
      const destroy = proxy.destroy;
      proxy.destroy = function (...destroyArgs) {
        proxyDestroys.set(version, (proxyDestroys.get(version) || 0) + 1);
        return destroy.apply(this, destroyArgs);
      };
      return proxy;
    };
    console.info = function (...args) {
      if (typeof args[0] === "string" && args[0].startsWith("FILE_TRANSFER_DOWNLOAD_DIAGNOSTIC ")) {
        observations.push(JSON.parse(args[0].slice("FILE_TRANSFER_DOWNLOAD_DIAGNOSTIC ".length)));
        notifyObservation();
      } else originalInfo.apply(this, args);
    };
    await import(pathToFileURL(path.join(temporary, "server.mjs")).href);
    http.createServer = originalCreateServer;
    await gatewayReady;
    for (const [index, version] of versions.entries()) {
      const trace = { bytes: 0, ended: false, aborted: false, errorType: "" };
      const response = await new Promise((resolve, reject) => {
        let receivedResponse = false;
        const timer = setTimeout(() => reject(new Error("LOCAL_TEST_OBSERVATION_DEADLINE")), 2000);
        timers.add(timer);
        const request = originalRequest(`http://127.0.0.1:${gateway.address().port}/api/versions/${version}/download`, {
          agent: false, headers: { Authorization: "Bearer LOCAL_DIAGNOSTIC_CANARY" },
        }, (incoming) => {
          receivedResponse = true;
          clients.push(incoming);
          incoming.on("data", (chunk) => {
            trace.bytes += chunk.length;
            if (index === 1 && clientAborts === 0) {
              clientAborts++;
              incoming.destroy(new Error("LOCAL_CLIENT_ABORT"));
            }
          });
          incoming.on("aborted", () => { trace.aborted = true; });
          incoming.on("error", (error) => { trace.errorType = error.name; });
          incoming.on("end", () => { trace.ended = true; });
          incoming.once("close", () => { clearTimeout(timer); resolve(incoming); });
        });
        clients.push(request);
        request.on("error", (error) => { if (!receivedResponse) reject(error); });
        request.end();
      });
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("LOCAL_DIAGNOSTIC_CASCADE_DEADLINE")), 2000);
        timers.add(timer);
        notifyObservation = () => {
          const events = observations.filter((entry) => entry.version === version).map((entry) => entry.event);
          if (events.includes("DOWNSTREAM_CLOSE_INCOMPLETE") && events.includes("RESPONSE_CLOSE_INCOMPLETE")) {
            clearTimeout(timer);
            resolve();
          }
        };
        notifyObservation();
      });
      assert.equal(response.statusCode, 200);
      assert.equal(Number(response.headers["content-length"]), declaredBytes);
      assert.ok(trace.bytes > 0 && trace.bytes < declaredBytes && !trace.ended && !response.complete && trace.aborted);
      const records = observations.filter((entry) => entry.version === version);
      assert.ok(records.length > 2 && records.length <= 12);
      assertBackpressureObservations(records);
      const first = records.find((entry) => entry.firstObservedTerminationEvent !== null).event;
      assert.ok(["UPSTREAM_TIMEOUT", "PROXY_ERROR", "RESPONSE_ERROR", "RESPONSE_ABORTED",
        "RESPONSE_CLOSE_INCOMPLETE", "DOWNSTREAM_CLOSE_INCOMPLETE", "REQUEST_ABORTED"].includes(first));
      let firstSeen = false;
      for (const [position, entry] of records.entries()) {
        assert.equal(entry.sequence, position + 1);
        assert.ok(Number.isFinite(entry.elapsedMillis) && entry.elapsedMillis >= (records[position - 1]?.elapsedMillis || 0));
        assert.ok(entry.upstreamDataBytes >= (records[position - 1]?.upstreamDataBytes || 0));
        assert.equal(entry.eventOrderMeaning, "NODE_OBSERVATION_NOT_NETWORK_CAUSE");
        assert.equal(entry.requestId, records[0].requestId);
        if (entry.firstObservedTerminationEvent !== null) firstSeen = true;
        assert.equal(entry.firstObservedTerminationEvent, firstSeen ? first : null);
        for (const [key, value] of Object.entries(entry.error)) {
          assert.ok(["name", "code", "syscall"].includes(key));
          assert.match(value, /^[A-Za-z0-9_.:-]{1,64}$/);
        }
      }
      assert.ok(records.some((entry) => entry.event === "RESPONSE_ERROR"));
      assert.ok(records.some((entry) => entry.upstreamAborted && !entry.upstreamComplete));
      assert.ok(records.some((entry) => entry.downstreamDestroyed && !entry.downstreamWritableFinished));
      assert.equal(proxyRequests.get(version), 1, "diagnostics must not retry the proxy request");
      assert.equal(proxyDestroys.get(version), 1, "diagnostics must not add another proxy abort");
      assert.deepEqual(downloadTimeouts.get(version), [0]);
      t.diagnostic(JSON.stringify({ marker: "LOCAL_NODE_TERMINATION_ORDER_VERIFIED", version, firstObservedTerminationEvent: first, proxyRequests: proxyRequests.get(version), proxyDestroys: proxyDestroys.get(version), declaredBytes, ...trace, observations: records }));
    }
    assert.equal(upstreamDisconnects, 1);
    assert.equal(clientAborts, 1);
    const serialized = JSON.stringify(observations);
    assert.ok(!serialized.includes("LOCAL_DIAGNOSTIC_CANARY") && !serialized.includes("LOCAL_CLIENT_ABORT"));
    assert.ok(!serialized.includes('"message"') && !serialized.includes('"headers"') && !serialized.includes('"body"'));
  } finally {
    for (const timer of timers) clearTimeout(timer);
    for (const client of clients) if (!client.destroyed) client.destroy();
    http.createServer = originalCreateServer;
    http.request = originalRequest;
    console.info = originalInfo;
    for (const [key, value] of Object.entries(oldEnv)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    async function close(server) {
      if (!server?.listening) return;
      const done = new Promise((resolve) => server.close(resolve));
      server.closeAllConnections();
      await done;
    }
    await Promise.all([close(gateway), close(upstream)]);
    assert.ok(!gateway?.listening && !upstream.listening);
    const directory = await fs.lstat(temporary);
    const resolved = await fs.realpath(temporary);
    assert.ok(directory.isDirectory() && !directory.isSymbolicLink() && path.isAbsolute(resolved));
    assert.equal(resolved, temporary);
    assert.equal(path.dirname(resolved), tempRoot);
    assert.ok(path.basename(resolved).startsWith("wtl-download-proxy-"));
    await fs.rm(resolved, { recursive: true, force: true });
    assert.equal(await fs.lstat(resolved).then(() => true, (error) => { if (error.code === "ENOENT") return false; throw error; }), false);
    t.diagnostic("LOCAL_NODE_TERMINATION_LISTENERS_CLOSED=true");
    t.diagnostic("LOCAL_NODE_TERMINATION_TEMP_DIRECTORY_REMOVED=true");
  }
});
