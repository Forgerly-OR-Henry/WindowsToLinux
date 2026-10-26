import { Sha256 } from "./sha256.js";

export type DownloadVersion = { id: string; name: string; size: number; sha256: string };

export async function downloadVersion(version: DownloadVersion): Promise<Blob> {
  if (!/^[0-9a-f]{32}$/.test(version.id) || !Number.isSafeInteger(version.size) || version.size < 0 ||
      !/^[0-9a-f]{64}$/.test(version.sha256)) throw new Error("下载版本信息无效");
  const controller = new AbortController();
  const idleMillis = 30000;
  const idleError = new Error("下载连续30秒未收到数据，已停止");
  idleError.name = "DownloadIdleError";
  let lastProgress = performance.now();
  let timer: ReturnType<typeof setTimeout>;
  let response: Response | undefined;
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  const checkIdle = () => {
    if (performance.now() - lastProgress >= idleMillis) {
      controller.abort(idleError);
      throw idleError;
    }
  };
  const watchIdle = () => {
    const remaining = idleMillis - (performance.now() - lastProgress);
    if (remaining <= 0) controller.abort(idleError);
    else timer = setTimeout(watchIdle, remaining);
  };
  timer = setTimeout(watchIdle, idleMillis);
  try {
    response = await fetch(`/api/versions/${version.id}/download`, { signal: controller.signal });
    checkIdle();
    if (!response.ok) throw new Error(`下载失败 ${response.status}`);
    if (!response.body) throw new Error("下载响应为空");
    reader = response.body.getReader();
    const hash = new Sha256();
    const parts: ArrayBuffer[] = [];
    let received = 0;
    while (true) {
      const { done, value } = await reader.read();
      checkIdle();
      if (done) break;
      if (value.byteLength === 0) continue;
      if (value.byteLength > version.size - received) throw new Error("下载长度超过版本记录");
      lastProgress = performance.now();
      received += value.byteLength;
      hash.update(value);
      parts.push(new Uint8Array(value).buffer);
    }
    if (received !== version.size) throw new Error("下载未完成，长度与版本记录不符");
    if (hash.digestHex() !== version.sha256) throw new Error("下载校验失败，SHA-256与版本记录不符");
    return new Blob(parts, { type: "application/octet-stream" });
  } finally {
    clearTimeout(timer);
    controller.abort();
    try {
      if (reader) await reader.cancel();
      else if (response?.body) await response.body.cancel();
    } catch {}
    try { reader?.releaseLock(); } catch {}
  }
}
