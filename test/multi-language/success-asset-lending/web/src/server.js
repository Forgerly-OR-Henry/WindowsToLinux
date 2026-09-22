import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { validResponse } from "./contracts.js";
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "public");
const upstream = new URL(process.env.API_URL || "http://127.0.0.1:18121");
if (upstream.protocol !== "http:") throw new Error("API_URL must use HTTP");
const server = http.createServer((req, res) => {
  const url = new URL(req.url || "/", "http://localhost");
  if (url.pathname === "/healthz") {
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ status: "ok", component: "node-gateway", version: 2 }));
    return;
  }
  if (url.pathname.startsWith("/api/") || url.pathname === "/readyz") {
    const target = new URL(url.pathname === "/readyz" ? "/healthz" : url.pathname + url.search, upstream);
    const headers = { ...req.headers, host: target.host };
    delete headers.connection;
    const proxy = http.request(
      target,
      {
        method: req.method,
        headers,
        timeout: Number(process.env.API_TIMEOUT_MS || 30000),
      },
      (response) => {
        const protocolFailure = () => {
          response.destroy();
          if (!res.headersSent) {
            res.writeHead(502, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "下游协议或响应字段无效" }));
          }
        };
        if (response.headers["x-sample-protocol"] !== "2") {
          protocolFailure();
          return;
        }
        let size = 0;
        const chunks = [];
        const maximum = Number(process.env.MAX_API_BYTES || 8388608);
        response.on("data", (chunk) => {
          size += chunk.length;
          if (size > maximum) {
            protocolFailure();
            return;
          }
          chunks.push(chunk);
        });
        response.on("end", () => {
          if (res.writableEnded) return;
          try {
            const body = Buffer.concat(chunks);
            const data = JSON.parse(body.toString("utf8"));
            if (!validResponse(url.pathname, req.method || "GET", response.statusCode || 502, data)) {
              protocolFailure();
              return;
            }
            res.writeHead(response.statusCode || 502, {
              "Content-Type": "application/json; charset=utf-8",
              "Content-Length": body.length,
            });
            res.end(body);
          } catch {
            protocolFailure();
          }
        });
        response.on("error", protocolFailure);
      },
    );
    proxy.on("timeout", () => proxy.destroy(new Error("upstream timeout")));
    proxy.on("error", () => {
      if (!res.headersSent) {
        res.writeHead(502, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "后端服务不可用" }));
      } else res.destroy();
    });
    req.on("aborted", () => proxy.destroy());
    res.on("close", () => {
      if (!res.writableFinished) proxy.destroy();
    });
    req.pipe(proxy);
    return;
  }
  let name;
  try {
    name = decodeURIComponent(url.pathname);
  } catch {
    res.writeHead(400);
    res.end();
    return;
  }
  const file = path.resolve(root, "." + (name === "/" ? "/index.html" : name));
  if (!file.startsWith(root + path.sep)) {
    res.writeHead(403);
    res.end();
    return;
  }
  fs.stat(file, (error, stat) => {
    if (error || !stat.isFile()) {
      res.writeHead(404);
      res.end();
      return;
    }
    const types = Object.fromEntries([
      [".html", "text/html; charset=utf-8"],
      [".css", "text/css"],
      [".js", "text/javascript"],
      [".json", "application/json"],
    ]);
    res.setHeader("Content-Type", types[path.extname(file)] || "application/octet-stream");
    const stream = fs.createReadStream(file);
    stream.on("error", () => res.destroy());
    stream.pipe(res);
  });
});
server.listen(Number(process.env.PORT || 18120), process.env.HOST || "127.0.0.1");
