import { api, text, run, table, pager, statuses } from "./ui.js";
const view = document.querySelector("#view");
let timer;
function poll(fn) {
  clearTimeout(timer);
  timer = setTimeout(() => run(fn), 700);
}
function form(id, fn) {
  document.querySelector(id).addEventListener("submit", (event) => {
    event.preventDefault();
    const button = event.target.querySelector("button[type=submit]") || event.target.querySelector("button");
    button.disabled = true;
    run(fn).finally(() => (button.disabled = false));
  });
}
function options(items, label = "name") {
  return items.map((item) => `<option value="${text(item.id)}">${text(item[label])}</option>`).join("");
}
async function datasets(offset = 0) {
  const data = await api(`/api/datasets?offset=${offset}`);
  view.innerHTML = `<h2>数据集</h2><form id="upload"><label>CSV 文件<input id="file" type="file" accept=".csv" required></label><button>上传数据集</button></form><p>支持 UTF-8 CSV；数据集上传后不可原地修改。演示数据可直接开始检查。</p>${table(
    ["名称", "字节数", "SHA-256", "创建时间"],
    data.items.map((d) => [d.name, d.size, d.sha256, d.created]),
  )}<div id="pages"></div><a href="#jobs">创建检查任务</a>`;
  pager(data, (offset) => run(() => datasets(offset)));
  form("#upload", async () => {
    const file = document.querySelector("#file").files[0];
    await api("/api/datasets?name=" + encodeURIComponent(file.name), {
      method: "POST",
      body: file,
      headers: { "Content-Type": "text/csv" },
    });
    await datasets();
  });
}
async function templates() {
  const { items } = await api("/api/templates");
  view.innerHTML = `<h2>规则模板</h2><p>模板保存后固定。修改规则请另存为新模板，历史任务保留原规则。</p><label>参考模板<select id="source">${options(items)}</select></label><form id="template"><label>新模板名称<input id="template-name" required maxlength="80"></label><label>必填列（逗号分隔）<input id="required"></label><label>类型（每行 列=number 或 列=date）<textarea id="types"></textarea></label><label>范围（每行 列=下限,上限）<textarea id="ranges"></textarea></label><label>枚举（每行 列=值1,值2）<textarea id="enums"></textarea></label><label>组合列去重（每行一组，逗号分隔）<textarea id="unique"></textarea></label><button>另存规则模板</button></form><h3>已有模板</h3>${table(
    ["模板", "创建时间"],
    items.map((t) => [t.name, t.created]),
  )}`;
  const load = () => {
    const r = items.find((t) => t.id === document.querySelector("#source").value).rules;
    document.querySelector("#required").value = r.required.join(",");
    for (const kind of ["types", "ranges", "enums"])
      document.querySelector("#" + kind).value = Object.entries(r[kind])
        .map(([k, v]) => `${k}=${kind === "ranges" ? `${v.min},${v.max}` : Array.isArray(v) ? v.join(",") : v}`)
        .join("\n");
    document.querySelector("#unique").value = r.unique.map((g) => g.join(",")).join("\n");
  };
  document.querySelector("#source").addEventListener("change", load);
  load();
  form("#template", async () => {
    const list = (value) =>
      value
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean);
    const pairs = (id) =>
      document
        .querySelector(id)
        .value.split("\n")
        .filter((s) => s.trim())
        .map((line) => {
          const i = line.indexOf("=");
          if (i < 1) throw Error("每行规则需使用 列=规则");
          return [line.slice(0, i).trim(), line.slice(i + 1).trim()];
        });
    const rules = {
      required: list(document.querySelector("#required").value),
      types: Object.fromEntries(pairs("#types")),
      ranges: Object.fromEntries(
        pairs("#ranges").map(([k, v]) => {
          const [min, max] = list(v).map(Number);
          if (!Number.isFinite(min) || !Number.isFinite(max)) throw Error("范围需填写两个数字");
          return [k, { min, max }];
        }),
      ),
      enums: Object.fromEntries(pairs("#enums").map(([k, v]) => [k, list(v)])),
      unique: document
        .querySelector("#unique")
        .value.split("\n")
        .filter((s) => s.trim())
        .map(list),
    };
    await api("/api/templates", {
      json: { name: document.querySelector("#template-name").value, rules },
    });
    await templates();
  });
}
async function jobs(offset = 0) {
  const [data, sets, templates, worker] = await Promise.all([
    api(`/api/jobs?offset=${offset}`),
    api("/api/datasets?limit=100"),
    api("/api/templates"),
    api("/api/worker"),
  ]);
  view.innerHTML = `<h2>检查任务</h2><p id="worker">工作进程：${worker.responsive ? "在线" : "无近期心跳，请检查 worker.php"}</p><form id="new-job"><label>数据集<select id="dataset">${options(sets.items)}</select></label><label>规则模板<select id="template-id">${options(templates.items)}</select></label><button>开始后台检查</button></form><button id="refresh">刷新任务</button><table><thead><tr><th>任务</th><th>状态</th><th>尝试</th><th>已检查行</th><th>问题数</th><th>更新时间</th></tr></thead><tbody>${data.items.map((j) => `<tr><td><a href="#job/${j.id}">${j.id.slice(0, 10)}</a></td><td>${statuses[j.status]}</td><td>${j.attempt}</td><td>${j.progress_rows}</td><td>${j.issue_count}</td><td>${j.updated}</td></tr>`).join("")}</tbody></table><div id="pages"></div>`;
  pager(data, (offset) => run(() => jobs(offset)));
  document.querySelector("#refresh").onclick = () => run(() => jobs(offset));
  const key = crypto.randomUUID();
  form("#new-job", async () => {
    const j = await api("/api/jobs", {
      json: {
        datasetId: document.querySelector("#dataset").value,
        templateId: document.querySelector("#template-id").value,
        requestId: key,
      },
    });
    location.hash = "job/" + j.id;
  });
}
async function job(id, offset = 0) {
  const [j, issues] = await Promise.all([api("/api/jobs/" + id), api(`/api/jobs/${id}/issues?offset=${offset}`)]);
  if (location.hash !== `#job/${id}`) return;
  const active = ["queued", "running"].includes(j.status);
  view.innerHTML = `<h2>任务详情</h2><p id="job-id">${j.id}</p><p>数据集：${text(j.datasetName)} · 模板：${text(j.templateName)}</p><p id="job-status">${statuses[j.status]} · 第 ${j.attempt} 次尝试</p><p id="summary">已检查 ${j.progress_rows} 行，${j.issue_count} 个问题${j.summary ? `，${j.summary.validRows} 行有效` : ""}</p>${j.error ? `<p class="error">${text(j.error)}</p>` : ""}${active ? '<button id="cancel">取消检查</button>' : ["failed", "interrupted", "cancelled"].includes(j.status) ? '<button id="retry">重试此任务</button>' : ""}${j.status === "completed" ? `<a id="export" href="/api/jobs/${id}/export">导出 JSON 报告</a>` : "<p>任务未成功，报告不可导出。当前明细仅供诊断。</p>"}<h3>问题明细</h3>${table(
    ["CSV 记录", "列", "类型", "值", "说明"],
    issues.items.map((i) => [i.row, i.column, i.code, i.value, i.detail]),
  )}<div id="pages"></div><h3>使用的规则</h3><pre>${text(JSON.stringify(j.rules, null, 2))}</pre><h3>任务历史</h3>${table(
    ["尝试", "操作", "说明", "时间"],
    j.events.map((e) => [e.attempt, e.action, e.detail, e.created]),
  )}`;
  pager(issues, (offset) => run(() => job(id, offset)));
  for (const action of ["cancel", "retry"]) {
    const button = document.querySelector("#" + action);
    if (button) {
      const key = crypto.randomUUID();
      button.onclick = () => {
        button.disabled = true;
        run(async () => {
          await api(`/api/jobs/${id}/${action}`, { json: { requestId: key } });
          await job(id, offset);
        }).finally(() => (button.disabled = false));
      };
    }
  }
  if (active) poll(() => job(id, offset));
}
async function compare() {
  const data = await api("/api/jobs?limit=100");
  const items = data.items
    .filter((j) => j.status === "completed")
    .map((j) => ({ ...j, name: `${j.id.slice(0, 10)} · ${j.template_id}` }));
  view.innerHTML = `<h2>报告比较</h2><p>选择同一数据集的两份成功报告。差异按 CSV 记录、列和问题类型识别。</p><form id="comparison"><label>原报告<select id="left">${options(items)}</select></label><label>新报告<select id="right">${options(items)}</select></label><button>比较报告</button></form><section id="comparison-result"></section>`;
  form("#comparison", async () => {
    const d = await api(
      `/api/compare?left=${document.querySelector("#left").value}&right=${document.querySelector("#right").value}`,
    );
    document.querySelector("#comparison-result").innerHTML =
      `<p id="delta">新增 ${d.added} 个问题，消除 ${d.resolved} 个问题</p>${table(["问题类型", "数量变化（新−原）"], Object.entries(d.statisticsDelta))}<h3>原规则</h3><pre>${text(JSON.stringify(d.left.rules, null, 2))}</pre><h3>新规则</h3><pre>${text(JSON.stringify(d.right.rules, null, 2))}</pre>`;
  });
}
function route() {
  clearTimeout(timer);
  const [page, id] = location.hash.slice(1).split("/");
  return run(() =>
    page === "job"
      ? job(id)
      : page === "templates"
        ? templates()
        : page === "jobs"
          ? jobs()
          : page === "compare"
            ? compare()
            : datasets(),
  );
}
window.addEventListener("hashchange", route);
route();
