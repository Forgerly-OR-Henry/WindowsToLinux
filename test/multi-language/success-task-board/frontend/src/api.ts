let apiBase = "",
  pending = 0,
  requestTimeoutMs = 15000;
export const node = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
export function message(e: unknown) {
  node("message").textContent = e instanceof Error ? e.message : String(e);
}
const fields = (v: any, shape: Record<string, string>) =>
  v !== null &&
  typeof v === "object" &&
  Object.entries(shape).every(([k, t]) => (t === "array" ? Array.isArray(v[k]) : typeof v[k] === t));
function contract(path: string, v: any) {
  if (path.startsWith("/api/projects") || path.startsWith("/api/members"))
    return Array.isArray(v)
      ? v.every((x) => fields(x, { id: "number", name: "string" }))
      : fields(v, { id: "number", name: "string" });
  if (path.startsWith("/api/stats"))
    return fields(v, {
      total: "number",
      statuses: "array",
      owners: "array",
      overdue: "number",
    });
  if (Array.isArray(v?.items))
    return (
      fields(v, { items: "array", total: "number", page: "number" }) &&
      v.items.every((x: any) =>
        fields(x, {
          id: "number",
          title: "string",
          status: "string",
          version: "number",
          labels: "array",
        }),
      )
    );
  return fields(v, {
    id: "number",
    projectId: "number",
    title: "string",
    ownerId: "number",
    status: "string",
    version: "number",
    dependencies: "array",
    comments: "array",
    events: "array",
  });
}
export async function configure() {
  const r = await fetch("/runtime-config.json");
  const v = await r.json();
  if (!r.ok || typeof v.apiBase !== "string") throw new Error("API 运行配置无效");
  apiBase = v.apiBase;
  if (v.requestTimeoutMs !== undefined) {
    if (!Number.isInteger(v.requestTimeoutMs) || v.requestTimeoutMs < 100 || v.requestTimeoutMs > 60000)
      throw new Error("请求超时配置须为 100..60000 毫秒");
    requestTimeoutMs = v.requestTimeoutMs;
  }
}
export async function api<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  pending++;
  node("loading").textContent = "加载中…";
  const controller = new AbortController(),
    timer = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const r = await fetch(apiBase + path, {
      ...options,
      signal: controller.signal,
      headers: { "Content-Type": "application/json", ...options.headers },
    });
    if (r.headers.get("X-Sample-Protocol") !== "2") throw new Error("服务协议版本不匹配");
    let body;
    try {
      body = await r.json();
    } catch {
      throw new Error("服务返回了无效 JSON");
    }
    if (!r.ok) throw new Error(body.error || `请求失败 ${r.status}`);
    if (!contract(path, body)) throw new Error("服务响应缺少必要字段");
    return body;
  } catch (e) {
    if (e instanceof Error && e.name === "AbortError") throw new Error("服务请求超时");
    if (e instanceof TypeError) throw new Error("服务不可用或连接中断");
    throw e;
  } finally {
    clearTimeout(timer);
    pending--;
    node("loading").textContent = pending ? "加载中…" : "";
  }
}
export function cell(v: unknown) {
  const td = document.createElement("td");
  td.textContent = String(v ?? "");
  return td;
}
export function button(text: string, fn: () => Promise<void>) {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = text;
  b.onclick = async () => {
    b.disabled = true;
    message("");
    try {
      await fn();
    } catch (e) {
      message(e);
    } finally {
      b.disabled = false;
    }
  };
  return b;
}
export function form(id: string, fn: () => Promise<void>) {
  node<HTMLFormElement>(id).onsubmit = async (e) => {
    e.preventDefault();
    message("");
    const b = e.submitter as HTMLButtonElement | null;
    if (b) b.disabled = true;
    try {
      await fn();
    } catch (e) {
      message(e);
    } finally {
      if (b) b.disabled = false;
    }
  };
}
