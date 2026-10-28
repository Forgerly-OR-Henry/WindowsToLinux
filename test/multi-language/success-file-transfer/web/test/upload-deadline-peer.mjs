// Owned loopback fixture: real Go Routes/Store + unchanged Node gateway source + paced ingress.
import assert from "node:assert/strict";
import http from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { stripTypeScriptTypes } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";

const temporary = await fs.mkdtemp(path.join(await fs.realpath(os.tmpdir()), "wtl-upload-deadline-"));
const owned = new Set(), timers = new Set(); let go, gateway, paced, putCount = 0;
const originalCreate = http.createServer;
try {
  go = spawn(process.env.UPLOAD_DEADLINE_GO_BINARY, ["-test.run=^TestUploadDeadlineLocalPeer$", "-test.v"], {
    env: { ...process.env, UPLOAD_DEADLINE_LOCAL_PEER: "1" }, stdio: ["pipe", "pipe", "pipe"], windowsHide: true,
  });
  const goOrigin = await new Promise((resolve, reject) => {
    let pending = "";
    go.once("error", reject); go.once("exit", (code) => reject(new Error(`GO_EARLY_EXIT_${code}`)));
    go.stdout.on("data", (value) => {
      pending += value.toString(); let end;
      while ((end = pending.indexOf("\n")) >= 0) {
        const line = pending.slice(0, end); pending = pending.slice(end + 1);
        if (line.startsWith("UPLOAD_DEADLINE_GO_READY ")) resolve(JSON.parse(line.slice(25)).origin);
        else process.stderr.write(line + "\n");
      }
    });
    go.stderr.pipe(process.stderr);
  });
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/server");
  await fs.writeFile(path.join(temporary, "contracts.mjs"), stripTypeScriptTypes(await fs.readFile(path.join(root, "contracts.ts"), "utf8")));
  await fs.writeFile(path.join(temporary, "upload-budget.mjs"), stripTypeScriptTypes(await fs.readFile(path.join(root,"upload-budget.ts"),"utf8")));
  const source = await fs.readFile(path.join(root, "server.ts"), "utf8");
  await fs.writeFile(path.join(temporary, "server.mjs"), stripTypeScriptTypes(source.replace('from "./contracts.js"', 'from "./contracts.mjs"').replace('from "./upload-budget.js"','from "./upload-budget.mjs"')));
  Object.assign(process.env, { API_URL: goOrigin, HOST:"127.0.0.1", PORT:"0" });
  const ready = new Promise((resolve) => {
    http.createServer = (...args) => { gateway = originalCreate(...args); gateway.once("listening", resolve); return gateway; };
  });
  await import(pathToFileURL(path.join(temporary, "server.mjs")).href); http.createServer = originalCreate; await ready;
  const origin = `http://127.0.0.1:${gateway.address().port}`;
  paced = http.createServer((req, res) => {
    putCount++; assert.equal(req.method, "PUT"); assert.match(req.url, /^\/api\/uploads\/[0-9a-f]{32}\/chunks\/0$/);
    const buffers = []; req.on("error", () => {}); req.on("data", (b) => buffers.push(b));
    req.on("end", () => {
      const body = Buffer.concat(buffers); assert.equal(body.length, 1 << 20);
      const upstream = http.request(origin + req.url, {method:"PUT", headers:{"Content-Length":body.length}, agent:false}, (answer) => {
        res.writeHead(answer.statusCode, answer.headers); answer.pipe(res);
      }); owned.add(upstream);
      upstream.on("error", () => { if (!res.headersSent) res.writeHead(502); res.end(); });
      upstream.on("close", () => owned.delete(upstream)); upstream.flushHeaders();
      let offset = 0, timer;
      const write = () => {
        if (timer) timers.delete(timer);
        if (upstream.destroyed || res.destroyed) return;
        const end = Math.min(offset + 65536, body.length); upstream.write(body.subarray(offset,end)); offset=end;
        if (offset === body.length) upstream.end();
        else { timer = setTimeout(write, 3000); timers.add(timer); }
      };
      res.once("close", () => { if (timer) { clearTimeout(timer); timers.delete(timer); } upstream.destroy(); });
      write();
    });
  });
  paced.listen(0,"127.0.0.1"); await once(paced,"listening");
  console.log("UPLOAD_DEADLINE_PEER_READY " + JSON.stringify({origin, pacedOrigin:`http://127.0.0.1:${paced.address().port}`, node:process.version,
    requestTimeout:gateway.requestTimeout, headersTimeout:gateway.headersTimeout, goPid:go.pid}));
  await new Promise((resolve) => { process.stdin.once("data",resolve); process.stdin.once("end",resolve); });
} finally {
  http.createServer = originalCreate;
  for (const timer of timers) clearTimeout(timer); timers.clear(); for (const req of owned) req.destroy();
  for (const server of [paced,gateway]) if(server?.listening) { const stopped=once(server,"close"); server.close(); server.closeAllConnections(); await stopped; }
  if(go) { const exited = go.exitCode === null ? once(go,"exit") : Promise.resolve([go.exitCode]); go.stdin.end("stop\n");
    const timer=setTimeout(()=>go.kill(),5000); try { const [code]=await exited; assert.equal(code,0); } finally {clearTimeout(timer);} }
  await fs.rm(temporary,{recursive:true});
  console.log("UPLOAD_DEADLINE_PEER_STOPPED " + JSON.stringify({putCount, timers:timers.size, upstreams:owned.size, listenersClosed:true}));
}
