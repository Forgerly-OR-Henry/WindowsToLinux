export const statuses = {
  queued: "排队中",
  running: "检查中",
  completed: "已完成",
  failed: "失败",
  cancelled: "已取消",
  interrupted: "已中断，可重试",
};
export function text(value) {
  return String(value ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}
export async function api(path, options = {}) {
  const { json, ...init } = options;
  if (json !== undefined)
    Object.assign(init, {
      method: "POST",
      body: JSON.stringify(json),
      headers: { "Content-Type": "application/json" },
    });
  const response = await fetch(path, {
    ...init,
    signal: AbortSignal.timeout(30000),
  });
  const data = await response.json();
  if (!response.ok) throw Error(data.error || "请求失败");
  if (response.headers.get("X-Sample-Protocol") !== "2") throw Error("服务协议版本错误");
  return data;
}
let pending = 0;
export async function run(fn) {
  document.querySelector("#message").textContent = "";
  pending++;
  document.querySelector("#loading").hidden = false;
  try {
    await fn();
  } catch (error) {
    document.querySelector("#message").textContent = error.message;
  } finally {
    pending--;
    document.querySelector("#loading").hidden = pending === 0;
  }
}
export function table(headings, rows) {
  return `<table><thead><tr>${headings.map((h) => `<th>${text(h)}</th>`).join("")}</tr></thead><tbody>${rows.map((row) => `<tr>${row.map((v) => `<td>${text(v)}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
}
export function pager(data, load) {
  document.querySelector("#pages").innerHTML =
    `<button id="previous" ${data.offset === 0 ? "disabled" : ""}>上一页</button><span>共 ${data.total} 条 · ${data.total ? data.offset + 1 : 0}–${Math.min(data.offset + data.limit, data.total)}</span><button id="next" ${data.offset + data.limit >= data.total ? "disabled" : ""}>下一页</button>`;
  document.querySelector("#previous").onclick = () => load(Math.max(0, data.offset - data.limit));
  document.querySelector("#next").onclick = () => load(data.offset + data.limit);
}
