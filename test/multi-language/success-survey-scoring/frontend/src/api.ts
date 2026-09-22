let base = "",
  pending = 0,
  timeout = 15000;
export const node = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
export const url = (path: string) => base + path;
export function message(error: unknown) {
  node("message").textContent = error instanceof Error ? error.message : String(error);
}
const shape = (v: any, fields: Record<string, string>) =>
  v !== null &&
  typeof v === "object" &&
  Object.entries(fields).every(([key, type]) => (type === "array" ? Array.isArray(v[key]) : typeof v[key] === type));
function valid(path: string, v: any) {
  if (path === "/api/actors") return Array.isArray(v) && v.every((x) => typeof x === "string");
  if (path === "/api/surveys" && Array.isArray(v))
    return v.every((x) =>
      shape(x, {
        id: "number",
        name: "string",
        versions: "number",
        submissions: "number",
      }),
    );
  if (path.endsWith("/stats")) return shape(v, { total: "number", revisions: "array" });
  if (path.startsWith("/api/submissions?")) return shape(v, { items: "array", total: "number" });
  if (path.startsWith("/api/submissions"))
    return (
      shape(v, {
        id: "number",
        revisionId: "number",
        respondent: "string",
        answers: "object",
        result: "object",
      }) &&
      shape(v.result, {
        score: "number",
        parts: "array",
        dimensions: "object",
        hidden: "array",
        revisionId: "number",
      })
    );
  if (Array.isArray(v?.revisions))
    return shape(v, {
      id: "number",
      name: "string",
      revisions: "array",
      events: "array",
    });
  return (
    shape(v, {
      id: "number",
      surveyId: "number",
      number: "number",
      status: "string",
      editVersion: "number",
      content: "object",
    }) && Array.isArray(v.content.questions)
  );
}
export async function configure() {
  const r = await fetch("/runtime-config.json"),
    c = await r.json();
  if (!r.ok || typeof c.apiBase !== "string") throw new Error("运行配置无效");
  base = c.apiBase;
  if (c.requestTimeoutMs !== undefined) {
    if (!Number.isInteger(c.requestTimeoutMs) || c.requestTimeoutMs < 100 || c.requestTimeoutMs > 60000)
      throw new Error("超时配置无效");
    timeout = c.requestTimeoutMs;
  }
}
export async function api<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  pending++;
  node("loading").textContent = "加载中…";
  const control = new AbortController(),
    timer = setTimeout(() => control.abort(), timeout);
  try {
    const response = await fetch(url(path), {
      ...options,
      signal: control.signal,
      headers: { "Content-Type": "application/json", ...options.headers },
    });
    if (response.headers.get("X-Sample-Protocol") !== "2") throw new Error("服务协议版本不匹配");
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || `请求失败 ${response.status}`);
    if (!valid(path, body)) throw new Error("服务响应缺少必要字段");
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
export const post = (path: string, body: unknown) => api(path, { method: "POST", body: JSON.stringify(body) });
export function cell(v: unknown) {
  const e = document.createElement("td");
  e.textContent = String(v ?? "");
  return e;
}
export function button(label: string, fn: () => Promise<void>) {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
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
