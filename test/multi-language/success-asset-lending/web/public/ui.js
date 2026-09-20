export const el = (id) => document.getElementById(id);
let pending = 0;
export async function api(path, options = {}) {
  pending++;
  el("loading").textContent = "加载中…";
  try {
    const r = await fetch(path, {
      ...options,
      headers: { "Content-Type": "application/json", ...options.headers },
    });
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || `请求失败 ${r.status}`);
    return data;
  } finally {
    pending--;
    el("loading").textContent = pending ? "加载中…" : "";
  }
}
export const post = (path, data) =>
  api(path, { method: "POST", body: JSON.stringify(data) });
export function error(e) {
  el("message").textContent = e instanceof Error ? e.message : String(e);
}
export function cell(value) {
  const c = document.createElement("td");
  c.textContent = String(value ?? "");
  return c;
}
export function action(label, fn) {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.onclick = async () => {
    b.disabled = true;
    error("");
    try {
      await fn();
    } catch (e) {
      error(e);
    } finally {
      b.disabled = false;
    }
  };
  return b;
}
export function form(id, fn) {
  el(id).onsubmit = async (e) => {
    e.preventDefault();
    error("");
    const b = e.submitter;
    if (b) b.disabled = true;
    try {
      await fn();
    } catch (e) {
      error(e);
    } finally {
      if (b) b.disabled = false;
    }
  };
}
