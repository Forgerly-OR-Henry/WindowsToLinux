import type http from "node:http";

export const uploadPolicy = { body: 240000, idle: 30000, connect: 10000, overall: 290000 };

// Local end/finish is not a Go receipt. Only the absolute watchdog covers ACK waiting.
export function protectUpload(req: http.IncomingMessage, res: http.ServerResponse, policy = uploadPolicy) {
  let proxy: http.ClientRequest | undefined, socket: import("node:net").Socket | undefined;
  let ended = false, bodyEnded = false, idleRemaining = policy.idle, idleStarted = performance.now();
  let idle: ReturnType<typeof setTimeout> | undefined;
  let body: ReturnType<typeof setTimeout> | undefined, connect: ReturnType<typeof setTimeout> | undefined;
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const clear = (timer: ReturnType<typeof setTimeout> | undefined) => { if (timer) { clearTimeout(timer); timers.delete(timer); } };
  const later = (millis: number, action: () => void) => {
    const timer = setTimeout(() => { timers.delete(timer); action(); }, millis); timers.add(timer); timer.unref(); return timer;
  };
  const detachBody = () => { req.off("data", progress); req.off("pause", pause); req.off("resume", resume); req.off("end", bodyEnd); };
  const cleanup = () => {
    if (ended) return; ended = true;
    for (const timer of timers) clearTimeout(timer); timers.clear(); detachBody();
    req.off("aborted", aborted); req.off("error", aborted); req.off("close", requestClose);
    res.off("finish", finished); res.off("close", closed);
    proxy?.off("socket", assigned); socket?.off("connect", connected);
  };
  const fail = () => {
    if (ended) return;
    cleanup(); if (proxy) req.unpipe(proxy); proxy?.destroy();
    if (!res.headersSent) {
      const payload=JSON.stringify({error:"后端服务不可用"});
      res.writeHead(502, {"Content-Type":"application/json", "Content-Length":Buffer.byteLength(payload), "Connection":"close"}); res.end(payload);
    }
    else if (!res.writableEnded) res.destroy();
  };
  const armIdle = () => { idleStarted = performance.now(); idle = later(Math.max(1,idleRemaining),fail); };
  function progress(chunk: Buffer) {
    if (chunk.length === 0 || bodyEnded || ended) return;
    clear(idle); idle=undefined; idleRemaining = policy.idle;
    if (!req.isPaused()) { try {armIdle()} catch {fail()} }
  }
  // Pause the local idle clock under downstream backpressure; D/N never pause.
  function pause() { if (bodyEnded || ended) return; if (idle) idleRemaining -= performance.now()-idleStarted; clear(idle); idle=undefined; }
  function resume() { if (!bodyEnded && !ended && !idle) {try {armIdle()} catch {fail()}} }
  function bodyEnd() { bodyEnded=true; clear(body); clear(idle); detachBody(); }
  function aborted() { cleanup(); proxy?.destroy(); }
  function requestClose() { if (!req.complete) aborted(); }
  function finished() { cleanup(); proxy?.destroy(); }
  function closed() { cleanup(); proxy?.destroy(); }
  function connected() { clear(connect); socket?.off("connect",connected); }
  function assigned(value: import("node:net").Socket) { socket=value; if(value.connecting) value.once("connect",connected); else connected(); }
  try {
    later(policy.overall,fail); body=later(policy.body,fail); connect=later(policy.connect,fail); armIdle();
    req.on("data",progress); req.on("pause",pause); req.on("resume",resume); req.once("end",bodyEnd);
    req.once("aborted",aborted); req.once("error",aborted); req.once("close",requestClose); res.once("finish",finished); res.once("close",closed);
  } catch { fail(); return null; }
  return { attach(value: http.ClientRequest) { proxy=value; value.once("socket",assigned); }, fail };
}
