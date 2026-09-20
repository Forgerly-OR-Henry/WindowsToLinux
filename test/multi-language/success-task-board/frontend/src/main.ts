import { api, configure, node, message, cell, button, form } from "./api";
type Task = {
  id: number;
  projectId: number;
  title: string;
  description: string;
  ownerId: number;
  owner: string;
  priority: number;
  labels: string[];
  dueDate: string;
  status: string;
  version: number;
  dependencies: { id: number; title: string; status: string }[];
  comments: { text: string; actor: string; created: string }[];
  events: { action: string; actor: string; detail: string; created: string }[];
};
const input = (id: string) => node<HTMLInputElement>(id),
  project = () => Number(input("project").value),
  actor = () => Number(input("actor").value);
const states: Record<string, string> = {
  todo: "待办",
  doing: "进行中",
  review: "待审核",
  done: "完成",
};
let page = 1,
  editing: Task | null = null,
  current: Task | null = null;
async function projects(selected?: number) {
  const list = await api<{ id: number; name: string }[]>("/api/projects");
  node<HTMLSelectElement>("project").replaceChildren(
    ...list.map((p) => new Option(p.name, String(p.id))),
  );
  if (selected) input("project").value = String(selected);
  await members();
}
async function members() {
  const list = await api<{ id: number; name: string }[]>(
    `/api/members?projectId=${project()}`,
  );
  for (const id of ["actor", "owner", "owner-filter"])
    node(id).replaceChildren(
      ...(id === "owner-filter" ? [new Option("全部", "0")] : []),
      ...list.map((m) => new Option(m.name, String(m.id))),
    );
}
async function overview() {
  const s = await api(`/api/stats?projectId=${project()}`);
  node("stats").textContent = `共 ${s.total} 个任务 · 逾期 ${s.overdue}`;
  node("status-stats").textContent = s.statuses
    .map((x: any) => `${states[x.status]} ${x.count}`)
    .join(" · ");
  node("owner-stats").replaceChildren(
    ...s.owners.map((m: any) => {
      const r = document.createElement("tr");
      r.append(cell(m.name), cell(m.count));
      return r;
    }),
  );
}
async function list() {
  const q = new URLSearchParams({
    projectId: String(project()),
    q: input("query").value,
    label: input("label-filter").value,
    priority: input("priority-filter").value,
    status: input("status").value,
    ownerId: input("owner-filter").value,
    overdue: String(input("overdue").checked),
    page: String(page),
    size: "25",
  });
  const result = await api<{ items: Task[]; total: number }>("/api/tasks?" + q);
  node("rows").replaceChildren(
    ...result.items.map((t) => {
      const r = document.createElement("tr"),
        c = cell("");
      c.append(
        button("详情", () => detail(t.id)),
        button("编辑", () => edit(t.id)),
      );
      r.append(
        cell(t.id),
        cell(t.title),
        cell(t.owner),
        cell(t.priority),
        cell(t.labels.join(", ")),
        cell(t.dueDate),
        cell(states[t.status]),
        c,
      );
      return r;
    }),
  );
  node("total").textContent = `共 ${result.total} 个任务`;
  node("page").textContent = `第 ${page} 页`;
  node<HTMLButtonElement>("prev").disabled = page === 1;
  node<HTMLButtonElement>("next").disabled = page * 25 >= result.total;
}
async function detail(id: number) {
  current = await api<Task>(`/api/tasks/${id}?projectId=${project()}`);
  const t = current;
  node("detail-title").textContent = t.title;
  node("detail-meta").textContent =
    `编号 ${t.id} · ${t.owner} · ${states[t.status]} · 截止 ${t.dueDate || "未设置"} · 版本 ${t.version}`;
  node("detail-description").textContent = t.description;
  node("transitions").replaceChildren(
    ...(
      {
        todo: ["doing"],
        doing: ["review"],
        review: ["doing", "done"],
        done: [],
      } as Record<string, string[]>
    )[t.status].map((status) =>
      button(
        {
          doing: t.status === "review" ? "退回修改" : "开始任务",
          review: "提交审核",
          done: "审核完成",
        }[status]!,
        async () => {
          await api(`/api/tasks/${t.id}/transition?projectId=${project()}`, {
            method: "POST",
            body: JSON.stringify({
              status,
              version: t.version,
              actorId: actor(),
            }),
          });
          await detail(t.id);
        },
      ),
    ),
  );
  node("dependency-list").replaceChildren(
    ...t.dependencies.map((d) => {
      const li = document.createElement("li");
      li.append(
        button(`#${d.id} ${d.title} · ${states[d.status]}`, () => detail(d.id)),
      );
      return li;
    }),
  );
  node("comments").replaceChildren(
    ...t.comments.map((c) => {
      const li = document.createElement("li");
      li.textContent = `${c.actor} · ${c.created}：${c.text}`;
      return li;
    }),
  );
  node("history").replaceChildren(
    ...t.events.map((e) => {
      const r = document.createElement("tr");
      r.append(cell(e.action), cell(e.actor), cell(e.detail), cell(e.created));
      return r;
    }),
  );
  location.hash = "detail";
}
async function edit(id?: number) {
  editing = id
    ? await api<Task>(`/api/tasks/${id}?projectId=${project()}`)
    : null;
  node<HTMLFormElement>("editor").reset();
  node("edit-title").textContent = editing ? "编辑任务" : "新建任务";
  node("edit-version").textContent = editing
    ? `正在编辑版本 ${editing.version}。出现冲突时保留你的输入，重新加载后再决定修改。`
    : "";
  node("reload-edit").hidden = !editing;
  if (editing) {
    input("title").value = editing.title;
    input("description").value = editing.description;
    input("owner").value = String(editing.ownerId);
    input("priority").value = String(editing.priority);
    input("due-date").value = editing.dueDate;
    input("labels").value = editing.labels.join(",");
    input("dependencies").value = editing.dependencies
      .map((d) => d.id)
      .join(",");
  }
  location.hash = "edit";
}
async function route() {
  const name = location.hash.slice(1) || "overview";
  document
    .querySelectorAll<HTMLElement>("[data-page]")
    .forEach((p) => (p.hidden = p.dataset.page !== name));
  if (name === "overview") await overview();
  if (name === "list") await list();
}
form("editor", async () => {
  const dependsOn = input("dependencies")
    .value.split(",")
    .map((x) => x.trim())
    .filter(Boolean)
    .map(Number);
  const body = {
    projectId: project(),
    title: input("title").value,
    description: input("description").value,
    ownerId: Number(input("owner").value),
    priority: Number(input("priority").value),
    labels: input("labels")
      .value.split(",")
      .map((s) => s.trim())
      .filter(Boolean),
    dependsOn,
    dueDate: input("due-date").value,
    version: editing?.version || 0,
    actorId: actor(),
  };
  const result = await api<Task>(
    editing ? `/api/tasks/${editing.id}` : "/api/tasks",
    { method: editing ? "PATCH" : "POST", body: JSON.stringify(body) },
  );
  editing = null;
  await detail(result.id);
});
form("project-form", async () => {
  const p = await api<{ id: number }>("/api/projects", {
    method: "POST",
    body: JSON.stringify({ name: input("project-name").value }),
  });
  await projects(p.id);
  page = 1;
  current = editing = null;
  await overview();
});
form("filters", async () => {
  page = 1;
  await list();
});
form("comment-form", async () => {
  if (!current) return;
  await api(`/api/tasks/${current.id}/comments?projectId=${project()}`, {
    method: "POST",
    body: JSON.stringify({
      text: input("comment").value,
      actorId: actor(),
      requestId: crypto.randomUUID(),
    }),
  });
  input("comment").value = "";
  await detail(current.id);
});
node("new-task").onclick = (e) => {
  e.preventDefault();
  edit().catch(message);
};
node("reload-edit").onclick = () => {
  if (editing) edit(editing.id).catch(message);
};
node("history-link").onclick = () => {
  location.hash = "history";
};
node("back-detail").onclick = () => {
  location.hash = "detail";
};
node("project").onchange = () => {
  page = 1;
  editing = current = null;
  members()
    .then(() => {
      location.hash = "overview";
      return route();
    })
    .catch(message);
};
node("prev").onclick = () => {
  page--;
  list().catch(message);
};
node("next").onclick = () => {
  page++;
  list().catch(message);
};
window.onhashchange = () => route().catch(message);
configure()
  .then(() => projects())
  .then(() => {
    if (["detail", "edit", "history"].includes(location.hash.slice(1)))
      location.hash = "overview";
    return route();
  })
  .catch(message);
