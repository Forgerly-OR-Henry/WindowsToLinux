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

test("download client enforces real 30 second progress and integrity", { timeout: 50000, concurrency: true }, async (t) => {
  const tempRoot = await fs.realpath(os.tmpdir());
  const temporary = await fs.mkdtemp(path.join(tempRoot, "wtl-download-client-"));
  const sourceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/client");
  const source = await fs.readFile(path.join(sourceRoot, "download.ts"), "utf8");
  const shaSource = await fs.readFile(path.join(sourceRoot, "sha256.ts"), "utf8");
  const importText = 'from "./sha256.js"';
  assert.equal(source.split(importText).length, 2);
  await fs.writeFile(path.join(temporary, "download.mjs"), stripTypeScriptTypes(source.replace(importText, 'from "./sha256.mjs"')));
  await fs.writeFile(path.join(temporary, "sha256.mjs"), stripTypeScriptTypes(shaSource));
  const { downloadVersion } = await import(pathToFileURL(path.join(temporary, "download.mjs")).href);
  const timers = new Set();
  const states = new Map();
  const digest = (body) => createHash("sha256").update(body).digest("hex");
  const chunk = Buffer.alloc(1024 * 1024, 0x5a);
  const longHash = createHash("sha256");
  for (let n = 0; n < 64; n++) longHash.update(chunk);
  const longSha256 = longHash.digest("hex");
  const versions = Object.fromEntries(["progress", "stall", "headers", "first", "short", "sha", "long", "empty", "network"].map((name, index) => [name, (index + 1).toString(16).padStart(32, "0")]));
  function later(callback, millis) {
    const timer = setTimeout(() => { timers.delete(timer); callback(); }, millis);
    timers.add(timer);
    return timer;
  }
  const upstream = http.createServer((req, res) => {
    const name = Object.keys(versions).find((key) => req.url === `/api/versions/${versions[key]}/download`);
    assert.ok(name);
    const state = { requests: (states.get(name)?.requests || 0) + 1, sentBytes: 0, firstBodyAt: null, closedAt: null, cancelled: false };
    state.closed = new Promise((resolve) => res.once("close", () => {
      state.closedAt = performance.now();
      state.cancelled = !res.writableFinished;
      resolve();
    }));
    states.set(name, state);
    function write(body) {
      state.firstBodyAt ??= performance.now();
      state.sentBytes += body.length;
      return res.write(body);
    }
    if (name === "headers") return;
    if (name === "first") {
      later(() => { if (!res.destroyed) { res.writeHead(200); res.flushHeaders(); } }, 15000);
      return;
    }
    res.writeHead(200, { "Content-Type": "application/octet-stream" });
    if (name === "progress") {
      let chunks = 0;
      function produce() {
        if (res.destroyed) return;
        write(chunk);
        chunks++;
        if (chunks === 64) res.end();
        else later(produce, 550);
      }
      produce();
    } else if (name === "stall") write(Buffer.from("a"));
    else if (name === "short") res.end(Buffer.from("short"));
    else if (name === "sha") res.end(Buffer.from("same-size"));
    else if (name === "empty") res.end();
    else if (name === "network") {
      write(Buffer.from("a"));
      later(() => res.socket?.destroy(), 20);
    } else if (name === "long") {
      function produce() {
        if (res.destroyed) return;
        write(chunk.subarray(0, 32 * 1024));
        later(produce, 10);
      }
      produce();
    }
  });
  const listening = once(upstream, "listening");
  upstream.listen(0, "127.0.0.1");
  await listening;
  const origin = `http://127.0.0.1:${upstream.address().port}`;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (resource, options) => originalFetch(new URL(resource, origin), options);
  async function download(name, size, sha256) {
    const started = performance.now();
    let deadline;
    try {
      const blob = await Promise.race([
        downloadVersion({ id: versions[name], name: `${name}.bin`, size, sha256 }),
        new Promise((_, reject) => { deadline = later(() => reject(new Error("LOCAL_CLIENT_TEST_DEADLINE")), 42000); }),
      ]);
      return { blob, elapsedMillis: performance.now() - started };
    } finally { clearTimeout(deadline); timers.delete(deadline); }
  }
  async function closed(name) {
    let deadline;
    try {
      await Promise.race([states.get(name).closed, new Promise((_, reject) => { deadline = later(() => reject(new Error("LOCAL_UPSTREAM_CANCEL_DEADLINE")), 2000); })]);
    } finally { clearTimeout(deadline); timers.delete(deadline); }
    assert.equal(states.get(name).requests, 1);
  }
  try {
    await Promise.all([
      t.test("64 MiB continues beyond 30 seconds with real body progress", async (st) => {
        const result = await download("progress", 64 * chunk.length, longSha256);
        assert.ok(result.elapsedMillis > 30000 && result.elapsedMillis < 42000);
        assert.equal(result.blob.size, 67108864);
        assert.equal(digest(Buffer.from(await result.blob.arrayBuffer())), longSha256);
        await closed("progress");
        assert.equal(states.get("progress").cancelled, false);
        st.diagnostic(JSON.stringify({ marker: "LOCAL_CLIENT_64M_REAL_PROGRESS_VERIFIED", bytes: result.blob.size, sha256: longSha256, idleMillis: 30000, elapsedMillis: result.elapsedMillis, requests: states.get("progress").requests }));
      }),
      ...["stall", "headers", "first"].map((name) => t.test(`${name} aborts after the real 30 second idle and cancels upstream`, async (st) => {
        const started = performance.now();
        await assert.rejects(download(name, 2, digest(Buffer.from("ab"))), (error) => {
          assert.equal(error.name, "DownloadIdleError");
          return true;
        });
        const elapsedMillis = performance.now() - started;
        assert.ok(elapsedMillis >= 30000 && elapsedMillis < 38000);
        await closed(name);
        const state = states.get(name);
        assert.equal(state.cancelled, true);
        if (name === "stall") assert.ok(state.closedAt - state.firstBodyAt >= 30000);
        st.diagnostic(JSON.stringify({ marker: "LOCAL_CLIENT_REAL_IDLE_CANCEL_VERIFIED", scenario: name, idleMillis: 30000, elapsedMillis, requests: state.requests, upstreamCancelled: state.cancelled }));
      })),
      ...[
        ["short", 6, digest(Buffer.from("short!")), /长度/],
        ["sha", 9, "0".repeat(64), /SHA-256/],
        ["long", 16 * 1024, digest(Buffer.alloc(16 * 1024)), /超过/],
      ].map(([name, size, sha256, expected]) => t.test(`${name} refuses a Blob on integrity failure`, async () => {
        await assert.rejects(download(name, size, sha256), expected);
        await closed(name);
        if (name === "long") assert.equal(states.get(name).cancelled, true);
      })),
      t.test("zero bytes validates the empty digest", async () => {
        const result = await download("empty", 0, digest(Buffer.alloc(0)));
        assert.equal(result.blob.size, 0);
        assert.equal(digest(Buffer.from(await result.blob.arrayBuffer())), digest(Buffer.alloc(0)));
        await closed("empty");
      }),
      t.test("a network failure is not replaced with an idle error", async () => {
        await assert.rejects(download("network", 2, digest(Buffer.from("ab"))), (error) => {
          assert.equal(error.name, "TypeError");
          return true;
        });
        await closed("network");
      }),
    ]);
    t.diagnostic(JSON.stringify({ marker: "LOCAL_CLIENT_HELPER_SOURCE_VERIFIED", sha256: createHash("sha256").update(source).digest("hex"), runtime: "NODE_WEB_STREAMS_NOT_BROWSER_UI" }));
  } finally {
    globalThis.fetch = originalFetch;
    for (const timer of timers) clearTimeout(timer);
    const stopped = new Promise((resolve) => upstream.close(resolve));
    upstream.closeAllConnections();
    await stopped;
    assert.equal(upstream.listening, false);
    const directory = await fs.lstat(temporary);
    const resolved = await fs.realpath(temporary);
    assert.ok(directory.isDirectory() && !directory.isSymbolicLink() && path.isAbsolute(resolved));
    assert.equal(resolved, temporary);
    assert.equal(path.dirname(resolved), tempRoot);
    assert.ok(path.basename(resolved).startsWith("wtl-download-client-"));
    await fs.rm(resolved, { recursive: true, force: true });
    assert.equal(await fs.lstat(resolved).then(() => true, (error) => { if (error.code === "ENOENT") return false; throw error; }), false);
    t.diagnostic("LOCAL_CLIENT_LISTENER_CLOSED=true");
    t.diagnostic("LOCAL_CLIENT_TIMERS_CLEARED=true");
    t.diagnostic("LOCAL_CLIENT_TEMP_DIRECTORY_REMOVED=true");
  }
});
