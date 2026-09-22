export function element<T extends HTMLElement = HTMLElement>(id: string): T {
  return document.getElementById(id) as T;
}
let pending = 0;
export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  pending++;
  element("loading").textContent = "加载中…";
  try {
    const r = await fetch(path, {
      ...options,
      headers: { "Content-Type": "application/json", ...options.headers },
    });
    const b = await r.json();
    if (!r.ok) throw new Error(b.error || `请求失败 ${r.status}`);
    return b as T;
  } finally {
    pending--;
    element("loading").textContent = pending ? "加载中…" : "";
  }
}
export function showError(error: unknown) {
  element("message").textContent = error instanceof Error ? error.message : String(error);
}
export function cell(value: unknown) {
  const c = document.createElement("td");
  c.textContent = String(value ?? "");
  return c;
}
export function action(label: string, fn: () => Promise<void>) {
  const b = document.createElement("button");
  b.textContent = label;
  b.onclick = async () => {
    b.disabled = true;
    showError("");
    try {
      await fn();
    } catch (e) {
      showError(e);
    } finally {
      b.disabled = false;
    }
  };
  return b;
}
