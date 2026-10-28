import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { validResponse } from "./contracts.js";
import { protectUpload } from "./upload-budget.js";
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "public");
const upstream = new URL(process.env.API_URL || "http://127.0.0.1:18111");
if (upstream.protocol !== "http:") throw new Error("API_URL must use HTTP");
const server = http.createServer((req, res) => {
  const url = new URL(req.url || "/", "http://localhost");
  if (url.pathname === "/healthz") {
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ status: "ok", component: "node-gateway", version: 2 }));
    return;
  }
  if (url.pathname.startsWith("/api/") || url.pathname === "/readyz") {
    const uploadMatch = req.method === "PUT" ? /^\/api\/uploads\/([0-9a-f]{32})\/chunks\/(0|[1-9][0-9]{0,15})$/.exec(url.pathname) : null;
    const upload = uploadMatch && Number.isSafeInteger(Number(uploadMatch[2])) ? uploadMatch : null;
    const protection = upload ? protectUpload(req,res) : undefined;
    if (upload && !protection) return;
    const uploadStarted = upload ? process.hrtime.bigint() : 0n;
    const uploadStartedAtUtc = upload ? new Date().toISOString() : undefined;
    let uploadSequence = 0;
    let uploadBodyBytes = 0;
    let uploadLastDataMillis: number | null = null;
    let uploadFirstTermination: string | null = null;
    let uploadProxy: http.ClientRequest | undefined;
    let uploadResponse: http.IncomingMessage | undefined;
    let uploadDataObserver: ((chunk: Buffer) => void) | undefined;
    let uploadPauseObserver: (() => void) | undefined;
    let uploadResumeObserver: (() => void) | undefined;
    let uploadDrainObserver: (() => void) | undefined;
    let uploadTimer: ReturnType<typeof setInterval> | undefined;
    let uploadPauseCount = 0, uploadResumeCount = 0, uploadDrainCount = 0;
    let uploadPauseStarted: number | null = null, uploadPausedMillis = 0, uploadLastDrain: number | null = null;
    let uploadLastTick = 0, uploadLastTickBytes = 0, uploadTickCount = 0, uploadDroppedTicks = 0, uploadObservationFailures = 0;
    const uploadProgress: Record<string, unknown>[] = [];
    let uploadObservationDetached = false;
    const uploadNow = () => Number(process.hrtime.bigint() - uploadStarted) / 1e6;
    const safelyObserveUpload = (action: () => void) => { try { action(); } catch { uploadObservationFailures++; } };
    const uploadSnapshot = (now: number) => ({
      elapsedMillis: now, tickGapMillis: now - uploadLastTick, requestBodyBytes: uploadBodyBytes,
      deltaBodyBytes: uploadBodyBytes - uploadLastTickBytes,
      requestPaused: req.isPaused(), requestReadableFlowing: req.readableFlowing, requestReadableLength: req.readableLength,
      proxyWritableLength: uploadProxy?.writableLength ?? null, proxyWritableNeedDrain: uploadProxy?.writableNeedDrain ?? null,
      pauseCount: uploadPauseCount, resumeCount: uploadResumeCount, drainCount: uploadDrainCount,
      currentPauseMillis: uploadPauseStarted === null ? null : now - uploadPauseStarted,
      pausedMillis: uploadPausedMillis + (uploadPauseStarted === null ? 0 : now - uploadPauseStarted),
      lastDrainAgeMillis: uploadLastDrain === null ? null : now - uploadLastDrain,
      lastBodyDataAgeMillis: uploadLastDataMillis === null ? null : now - uploadLastDataMillis,
    });
    const stopObservingUploadData = (stopEvent = "OBSERVATION_CLOSED") => {
      if (uploadObservationDetached) return;
      uploadObservationDetached = true;
      if (uploadTimer) { clearInterval(uploadTimer); uploadTimer = undefined; }
      if (uploadDataObserver) {
        req.off("data", uploadDataObserver);
        uploadDataObserver = undefined;
      }
      if (uploadPauseObserver) { req.off("pause", uploadPauseObserver); uploadPauseObserver = undefined; }
      if (uploadResumeObserver) { req.off("resume", uploadResumeObserver); uploadResumeObserver = undefined; }
      if (uploadDrainObserver) { uploadProxy?.off("drain", uploadDrainObserver); uploadDrainObserver = undefined; }
      if (upload) {
        let final: Record<string, unknown> | null = null;
        safelyObserveUpload(() => { final = uploadSnapshot(uploadNow()); });
        safelyObserveUpload(() => console.info("FILE_TRANSFER_UPLOAD_FLOW_DIAGNOSTIC " + JSON.stringify({
          uploadId: upload[1], chunkIndex: Number(upload[2]), requestId: uploadStarted.toString(), startedAtUtc: uploadStartedAtUtc,
          stopEvent, sampleIntervalMillis: 1000, sampleLimit: 16, sampleCount: uploadTickCount, droppedSamples: uploadDroppedTicks,
          requestBodyBytes: uploadBodyBytes, pauseCount: uploadPauseCount, resumeCount: uploadResumeCount, drainCount: uploadDrainCount,
          observationFailures: uploadObservationFailures, samples: uploadProgress, final,
          queueMeaning: "NODE_STREAM_BUFFERS_NOT_OS_OR_UPSTREAM_RECEIPT",
          tickGapMeaning: "MONOTONIC_SAMPLING_INTERVAL_NOT_A_SINGLE_CAUSE",
        })));
      }
    };
    const observeUpload = upload ? (event: string, error?: unknown) => {
      try {
        if (uploadFirstTermination === null && ["UPSTREAM_TIMEOUT", "PROXY_ERROR", "REQUEST_ABORTED",
          "DOWNSTREAM_CLOSE_INCOMPLETE", "RESPONSE_ABORTED", "RESPONSE_ERROR", "RESPONSE_CLOSE_INCOMPLETE"].includes(event)) uploadFirstTermination = event;
        if (uploadSequence >= 12) return;
        const elapsedMillis = Number(process.hrtime.bigint() - uploadStarted) / 1e6;
        const safeError: Record<string, string> = {};
        if (error && typeof error === "object") {
          for (const key of ["name", "code", "syscall"] as const) {
            const value = (error as Record<string, unknown>)[key];
            if (typeof value === "string" && /^[A-Za-z0-9_.:-]{1,64}$/.test(value)) safeError[key] = value;
          }
        }
        console.info("FILE_TRANSFER_UPLOAD_PROXY_DIAGNOSTIC " + JSON.stringify({
          uploadId: upload[1], chunkIndex: Number(upload[2]), requestId: uploadStarted.toString(),
          startedAtUtc: uploadStartedAtUtc, sequence: ++uploadSequence, elapsedMillis, event,
          firstObservedTerminationEvent: uploadFirstTermination, requestBodyBytes: uploadBodyBytes,
          lastNonemptyBodyDataElapsedMillis: uploadLastDataMillis,
          lastBodyDataAgeMillis: uploadLastDataMillis === null ? null : elapsedMillis - uploadLastDataMillis,
          requestComplete: req.complete, requestAborted: req.aborted,
          proxyWritableFinished: uploadProxy?.writableFinished ?? null,
          upstreamStatusCode: uploadResponse?.statusCode ?? null, upstreamComplete: uploadResponse?.complete ?? null,
          downstreamWritableFinished: res.writableFinished, downstreamDestroyed: res.destroyed,
          transferObservationMeaning: "NODE_BUFFER_EVENTS_NOT_UPSTREAM_RECEIPT", error: safeError,
        }));
      } catch {}
    } : undefined;
    observeUpload?.("REQUEST_BEGIN");
    if (observeUpload) {
      req.once("end", () => { observeUpload("REQUEST_END"); stopObservingUploadData("REQUEST_END"); });
      req.once("close", () => stopObservingUploadData("REQUEST_CLOSE"));
      res.once("close", () => stopObservingUploadData("DOWNSTREAM_CLOSE"));
      res.once("finish", () => observeUpload("DOWNSTREAM_FINISH"));
    }
    const downloadVersion = /^\/api\/versions\/([0-9a-f]{32})\/download$/.exec(url.pathname)?.[1];
    const started = downloadVersion ? process.hrtime.bigint() : 0n;
    const requestStartedAtUtc = downloadVersion ? new Date().toISOString() : undefined;
    const elapsedMillis = () => Number(process.hrtime.bigint() - started) / 1e6;
    let sequence = 0;
    let firstObservedTerminationEvent: string | null = null;
    let upstreamDataBytes = 0;
    let upstreamLastDataElapsedMillis: number | null = null;
    let upstreamPauseCount = 0;
    let upstreamResumeCount = 0;
    let upstreamPauseStartedElapsedMillis: number | null = null;
    let upstreamCompletedPauseMillis = 0;
    const upstreamRecentLongPauses: { startElapsedMillis: number; durationMillis: number }[] = [];
    let downstreamLastDrainElapsedMillis: number | null = null;
    let downstreamDrainCount = 0;
    let upstreamResponse: http.IncomingMessage | undefined;
    const diagnose = downloadVersion ? (event: string, error?: unknown) => {
      try {
        if (firstObservedTerminationEvent === null && [
          "UPSTREAM_TIMEOUT", "PROXY_ERROR", "RESPONSE_ERROR", "RESPONSE_ABORTED",
          "RESPONSE_CLOSE_INCOMPLETE", "DOWNSTREAM_CLOSE_INCOMPLETE", "REQUEST_ABORTED",
        ].includes(event)) firstObservedTerminationEvent = event;
        if (sequence >= 12) return;
        const now = elapsedMillis();
        const upstreamCurrentPauseMillis = upstreamPauseStartedElapsedMillis === null ? null : now - upstreamPauseStartedElapsedMillis;
        const safeError: Record<string, string> = {};
        if (error && typeof error === "object") {
          for (const key of ["name", "code", "syscall"] as const) {
            const value = (error as Record<string, unknown>)[key];
            if (typeof value === "string" && /^[A-Za-z0-9_.:-]{1,64}$/.test(value)) safeError[key] = value;
          }
        }
        console.info("FILE_TRANSFER_DOWNLOAD_DIAGNOSTIC " + JSON.stringify({
          version: downloadVersion, requestId: started.toString(), sequence: ++sequence,
          requestStartedAtUtc, elapsedMillis: now, event,
          firstObservedTerminationEvent, eventOrderMeaning: "NODE_OBSERVATION_NOT_NETWORK_CAUSE",
          transferObservationMeaning: "NODE_BUFFERS_NOT_CLIENT_RECEIPT",
          upstreamDataBytes, upstreamComplete: upstreamResponse?.complete ?? null,
          upstreamAborted: upstreamResponse?.aborted ?? null,
          upstreamLastDataElapsedMillis,
          upstreamLastDataAgeMillis: upstreamLastDataElapsedMillis === null ? null : now - upstreamLastDataElapsedMillis,
          upstreamPauseCount, upstreamResumeCount, upstreamPauseStartedElapsedMillis, upstreamCurrentPauseMillis,
          upstreamPausedMillis: upstreamCompletedPauseMillis + (upstreamCurrentPauseMillis ?? 0), upstreamRecentLongPauses,
          downstreamLastDrainElapsedMillis,
          downstreamLastDrainAgeMillis: downstreamLastDrainElapsedMillis === null ? null : now - downstreamLastDrainElapsedMillis,
          downstreamDrainCount, downstreamWritableLength: res.writableLength,
          downstreamWritableFinished: res.writableFinished, downstreamDestroyed: res.destroyed,
          downstreamHeadersSent: res.headersSent, error: safeError,
        }));
      } catch {}
    } : undefined;
    if (diagnose) {
      res.once("finish", () => diagnose("DOWNSTREAM_FINISH"));
      res.on("drain", () => {
        downstreamDrainCount++;
        downstreamLastDrainElapsedMillis = elapsedMillis();
      });
    }
    const target = new URL(url.pathname === "/readyz" ? "/healthz" : url.pathname + url.search, upstream);
    const headers = { ...req.headers, host: target.host };
    delete headers.connection;
    let proxy: http.ClientRequest;
    try { proxy = http.request(
      target,
      {
        method: req.method,
        headers,
        timeout: upload ? 0 : Number(process.env.API_TIMEOUT_MS || 30000),
      },
      (response) => {
        uploadResponse = response;
        if (observeUpload) {
          observeUpload("UPSTREAM_RESPONSE_HEADERS");
          response.once("aborted", () => { observeUpload("RESPONSE_ABORTED"); stopObservingUploadData("RESPONSE_ABORTED"); });
          response.once("close", () => {
            if (!response.complete) { observeUpload("RESPONSE_CLOSE_INCOMPLETE"); stopObservingUploadData("RESPONSE_CLOSE_INCOMPLETE"); }
          });
        }
        upstreamResponse = response;
        if (diagnose) {
          diagnose("UPSTREAM_HEADERS");
          response.once("aborted", () => diagnose("RESPONSE_ABORTED"));
          response.once("close", () => {
            if (!response.complete) diagnose("RESPONSE_CLOSE_INCOMPLETE");
          });
          response.once("end", () => diagnose("UPSTREAM_END"));
          response.on("pause", () => {
            upstreamPauseCount++;
            if (upstreamPauseStartedElapsedMillis === null) upstreamPauseStartedElapsedMillis = elapsedMillis();
          });
          response.on("resume", () => {
            upstreamResumeCount++;
            if (upstreamPauseStartedElapsedMillis !== null) {
              const durationMillis = elapsedMillis() - upstreamPauseStartedElapsedMillis;
              upstreamCompletedPauseMillis += durationMillis;
              if (durationMillis >= 1000) {
                upstreamRecentLongPauses.push({ startElapsedMillis: upstreamPauseStartedElapsedMillis, durationMillis });
                if (upstreamRecentLongPauses.length > 4) upstreamRecentLongPauses.shift();
              }
              upstreamPauseStartedElapsedMillis = null;
            }
          });
        }
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
        if (!url.pathname.endsWith("/download") || (response.statusCode || 500) >= 400) {
          let size = 0;
          const chunks: Buffer[] = [];
          const maximum = Number(process.env.MAX_API_BYTES || 8388608);
          response.on("data", (chunk: Buffer) => {
            if (diagnose) {
              upstreamDataBytes += chunk.length;
              upstreamLastDataElapsedMillis = elapsedMillis();
            }
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
              const data: unknown = JSON.parse(body.toString("utf8"));
              if (!validResponse(url.pathname, req.method || "GET", response.statusCode || 502, data)) {
                protocolFailure();
                return;
              }
              if (!req.readableEnded) {
                req.unpipe(proxy);
                req.resume();
                proxy.destroy();
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
          response.on("error", (error) => {
            observeUpload?.("RESPONSE_ERROR", error);
            if (observeUpload) stopObservingUploadData("RESPONSE_ERROR");
            diagnose?.("RESPONSE_ERROR", error);
            protocolFailure();
          });
          return;
        }
        if (downloadVersion) proxy.setTimeout(0);
        // The client can finish draining a file after the server's keep-alive window has expired.
        const downloadHeaders: http.OutgoingHttpHeaders = { ...response.headers, connection: "close" };
        delete downloadHeaders["keep-alive"];
        res.writeHead(response.statusCode || 502, downloadHeaders);
        response.pipe(res);
        if (diagnose) response.on("data", (chunk: Buffer) => {
          upstreamDataBytes += chunk.length;
          upstreamLastDataElapsedMillis = elapsedMillis();
        });
        response.on("error", (error) => {
          diagnose?.("RESPONSE_ERROR", error);
          res.destroy();
        });
      },
    ); } catch {
      if (protection) protection.fail();
      else { res.writeHead(502, {"Content-Type":"application/json"}); res.end(JSON.stringify({error:"后端服务不可用"})); }
      return;
    }
    uploadProxy = proxy;
    if (observeUpload) {
      proxy.once("finish", () => observeUpload("PROXY_FINISH_LOCAL_OS_DELIVERY_ONLY"));
      proxy.on("unpipe", (source) => { if (source === req) stopObservingUploadData("REQUEST_UNPIPE"); });
      proxy.once("close", () => stopObservingUploadData("PROXY_CLOSE"));
    }
    proxy.on("timeout", () => {
      observeUpload?.("UPSTREAM_TIMEOUT");
      if (observeUpload) stopObservingUploadData("UPSTREAM_TIMEOUT");
      diagnose?.("UPSTREAM_TIMEOUT");
      proxy.destroy(new Error("upstream timeout"));
    });
    proxy.on("error", (error) => {
      observeUpload?.("PROXY_ERROR", error);
      if (observeUpload) stopObservingUploadData("PROXY_ERROR");
      diagnose?.("PROXY_ERROR", error);
      if (!res.headersSent) {
        res.writeHead(502, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "后端服务不可用" }));
      } else if (!res.writableEnded) res.destroy();
    });
    req.on("aborted", () => {
      observeUpload?.("REQUEST_ABORTED");
      if (observeUpload) stopObservingUploadData("REQUEST_ABORTED");
      diagnose?.("REQUEST_ABORTED");
      proxy.destroy();
    });
    res.on("close", () => {
      if (!res.writableFinished) {
        observeUpload?.("DOWNSTREAM_CLOSE_INCOMPLETE");
        if (observeUpload) stopObservingUploadData("DOWNSTREAM_CLOSE_INCOMPLETE");
        diagnose?.("DOWNSTREAM_CLOSE_INCOMPLETE");
        proxy.destroy();
      }
    });
    protection?.attach(proxy);
    if (upload) proxy.flushHeaders();
    req.pipe(proxy);
    if (observeUpload && !uploadObservationDetached) {
      try {
        uploadDataObserver = (chunk: Buffer) => safelyObserveUpload(() => {
          if (chunk.length > 0) {
            uploadBodyBytes += chunk.length;
            uploadLastDataMillis = Number(process.hrtime.bigint() - uploadStarted) / 1e6;
          }
        });
        req.on("data", uploadDataObserver);
        uploadPauseObserver = () => safelyObserveUpload(() => { uploadPauseCount++; if (uploadPauseStarted === null) uploadPauseStarted = uploadNow(); });
        uploadResumeObserver = () => safelyObserveUpload(() => {
          uploadResumeCount++;
          if (uploadPauseStarted !== null) { uploadPausedMillis += uploadNow() - uploadPauseStarted; uploadPauseStarted = null; }
        });
        uploadDrainObserver = () => safelyObserveUpload(() => { uploadDrainCount++; uploadLastDrain = uploadNow(); });
        req.on("pause", uploadPauseObserver); req.on("resume", uploadResumeObserver); proxy.on("drain", uploadDrainObserver);
        uploadTimer = setInterval(() => safelyObserveUpload(() => {
          if (uploadObservationDetached) return;
          const now = uploadNow(); const sample = uploadSnapshot(now);
          uploadTickCount++;
          if (uploadProgress.length === 16) { uploadProgress.splice(1, 1); uploadDroppedTicks++; }
          uploadProgress.push(sample); uploadLastTick = now; uploadLastTickBytes = uploadBodyBytes;
        }), 1000);
        uploadTimer.unref();
      } catch { uploadObservationFailures++; stopObservingUploadData("OBSERVATION_SETUP_ERROR"); }
    }
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
server.requestTimeout = 300000;
server.listen(Number(process.env.PORT || 18110), process.env.HOST || "127.0.0.1");
