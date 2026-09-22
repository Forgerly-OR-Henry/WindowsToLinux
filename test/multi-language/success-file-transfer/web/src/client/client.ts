import { api, element, cell, action, showError } from "./ui.js";
type Version = {
  id: string;
  number: number;
  size: number;
  sha256: string;
  created: string;
};
type FileRow = {
  id: string;
  name: string;
  version: number;
  versionId: string;
  size: number;
  sha256: string;
};
type Session = {
  id: string;
  folderId: number;
  name: string;
  size: number;
  sha256: string;
  chunkSize: number;
  state: string;
  chunks: { number: number }[];
  events: unknown[];
};
let offset = 0,
  paused = false,
  controller: AbortController | undefined;
const folder = () => Number(element<HTMLSelectElement>("folder").value);
const sessionInput = element<HTMLInputElement>("session-id");
sessionInput.value = localStorage.getItem("file-session") || "";
function remember(id: string) {
  sessionInput.value = id;
  localStorage.setItem("file-session", id);
}
function download(id: string) {
  const a = document.createElement("a");
  a.href = `/api/versions/${id}/download`;
  a.textContent = "下载";
  return a;
}
async function folders(selected?: number) {
  const list = await api<{ id: number; name: string }[]>("/api/folders");
  const select = element<HTMLSelectElement>("folder");
  select.replaceChildren(...list.map((x) => new Option(x.name, String(x.id))));
  if (selected) select.value = String(selected);
}
async function files() {
  const result = await api<{ items: FileRow[]; total: number }>(
    "/api/files?" +
      new URLSearchParams({
        folderId: String(folder()),
        q: element<HTMLInputElement>("query").value,
        offset: String(offset),
        limit: "25",
      }),
  );
  const body = element("rows");
  body.replaceChildren();
  for (const f of result.items) {
    const row = document.createElement("tr"),
      buttons = cell("");
    buttons.append(
      download(f.versionId),
      action("版本详情", async () => {
        element("detail-title").textContent = f.name + " / 历史版本";
        const versions = await api<Version[]>(`/api/files/${f.id}/versions`);
        element("versions").replaceChildren(
          ...versions.map((v) => {
            const r = document.createElement("tr"),
              d = cell("");
            d.append(download(v.id));
            r.append(cell(v.number), cell(v.size), cell(v.sha256), cell(v.created), d);
            return r;
          }),
        );
        location.hash = "detail";
      }),
    );
    row.append(cell(f.name), cell(f.version), cell(f.size), cell(f.sha256), buttons);
    body.append(row);
  }
  element("stats").textContent = `共 ${result.total} 个文件 · 第 ${Math.floor(offset / 25) + 1} 页`;
  element<HTMLButtonElement>("previous").disabled = offset === 0;
  element<HTMLButtonElement>("next").disabled = offset + 25 >= result.total;
}
async function history() {
  const list = await api<{ id: string; name: string; state: string; created: string }[]>(
    `/api/uploads?folderId=${folder()}`,
  );
  element("history").replaceChildren(
    ...list.map((u) => {
      const r = document.createElement("tr"),
        c = cell("");
      c.append(
        action("查看 / 恢复", async () => {
          remember(u.id);
          await session();
          location.hash = "upload";
        }),
      );
      r.append(cell(u.name), cell(u.state), cell(u.created), c);
      return r;
    }),
  );
}
async function session() {
  if (!sessionInput.value) return;
  const u = await api<Session>(`/api/uploads/${sessionInput.value}`);
  element("session-detail").textContent = JSON.stringify(u, null, 2);
  return u;
}
async function page() {
  const name = location.hash.slice(1) || "list";
  document.querySelectorAll<HTMLElement>("[data-page]").forEach((e) => (e.hidden = e.dataset.page !== name));
  if (name === "list") await files();
  if (name === "history") await history();
}
async function upload(file: File, resume: string) {
  element("progress-text").textContent = `计算 ${file.name} 的 SHA-256…`;
  const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", await file.arrayBuffer())))
    .map((x) => x.toString(16).padStart(2, "0"))
    .join("");
  let u: Session;
  if (resume) {
    u = await api<Session>(`/api/uploads/${resume}`);
    if (u.folderId !== folder() || u.name !== file.name || u.size !== file.size || u.sha256 !== sha256)
      throw new Error("所选文件或文件夹与上传会话不一致");
    if (u.state !== "receiving") throw new Error("此会话已结束，不能继续上传");
  } else {
    u = await api<Session>("/api/uploads", {
      method: "POST",
      body: JSON.stringify({
        folderId: folder(),
        name: file.name,
        size: file.size,
        sha256,
        chunkSize: 1048576,
      }),
    });
  }
  remember(u.id);
  const confirmed = new Set(u.chunks.map((c) => c.number));
  const count = Math.ceil(file.size / u.chunkSize);
  for (let n = 0; n < count; n++) {
    if (paused) return;
    if (!confirmed.has(n)) {
      controller = new AbortController();
      try {
        await api(`/api/uploads/${u.id}/chunks/${n}`, {
          method: "PUT",
          body: file.slice(n * u.chunkSize, (n + 1) * u.chunkSize),
          headers: { "Content-Type": "application/octet-stream" },
          signal: controller.signal,
        });
      } catch (e) {
        if (paused) return;
        throw e;
      } finally {
        controller = undefined;
      }
    }
    element<HTMLProgressElement>("progress").value = ((n + 1) / count) * 100;
    element("progress-text").textContent = `${file.name}：${n + 1}/${count} 分块已确认 · ${u.id}`;
  }
  if (paused) return;
  await api(`/api/uploads/${u.id}/complete`, { method: "POST" });
  await session();
  remember("");
  element("progress-text").textContent = file.name + " 已校验并发布";
}
element<HTMLFormElement>("upload").onsubmit = async (e) => {
  e.preventDefault();
  showError("");
  const files = element<HTMLInputElement>("files").files;
  if (!files?.length) return;
  const resume = sessionInput.value.trim();
  if (resume && files.length !== 1) {
    showError("续传只允许选择一个文件");
    return;
  }
  paused = false;
  element<HTMLButtonElement>("upload-button").disabled = true;
  element<HTMLButtonElement>("pause").disabled = false;
  try {
    for (const file of files) {
      await upload(file, resume);
      if (paused) break;
    }
  } catch (e) {
    showError(e);
  } finally {
    element<HTMLButtonElement>("upload-button").disabled = false;
    element<HTMLButtonElement>("pause").disabled = true;
  }
};
element("pause").onclick = () => {
  paused = true;
  controller?.abort();
  element("progress-text").textContent = "已暂停；可重新点击开始 / 继续上传";
};
element("cancel").onclick = () => {
  paused = true;
  controller?.abort();
  api(`/api/uploads/${sessionInput.value}/cancel`, { method: "POST" })
    .then(() => session())
    .then(() => remember(""))
    .catch(showError);
};
element<HTMLFormElement>("folder-form").onsubmit = async (e) => {
  e.preventDefault();
  try {
    const v = await api<{ id: number }>("/api/folders", {
      method: "POST",
      body: JSON.stringify({
        name: element<HTMLInputElement>("folder-name").value,
      }),
    });
    await folders(v.id);
    offset = 0;
    await page();
  } catch (e) {
    showError(e);
  }
};
element<HTMLFormElement>("filters").onsubmit = (e) => {
  e.preventDefault();
  offset = 0;
  files().catch(showError);
};
element("folder").onchange = () => {
  offset = 0;
  page().catch(showError);
};
element("previous").onclick = () => {
  offset = Math.max(0, offset - 25);
  files().catch(showError);
};
element("next").onclick = () => {
  offset += 25;
  files().catch(showError);
};
window.onhashchange = () => page().catch(showError);
folders().then(page).catch(showError);
