import { api, post, configure, node, url, message, cell, button, form } from "./api";
import { editor, addQuestion, readContent, renderQuestions, answers, type Content } from "./questions";
type Revision = {
  id: number;
  surveyId: number;
  number: number;
  status: string;
  editVersion: number;
  content: Content;
};
let surveyId = 1,
  editing: Revision | null = null,
  filling: Revision | null = null,
  historyRevision = 1,
  offset = 0,
  requestId = "";
const actor = () => node<HTMLSelectElement>("actor").value;
async function list() {
  const rows = await api("/api/surveys");
  node("surveys").replaceChildren(
    ...rows.map((s: any) => {
      const row = document.createElement("tr"),
        c = cell("");
      c.append(button("版本与详情", () => detail(s.id)));
      row.append(cell(s.name), cell(s.versions), cell(s.submissions), c);
      return row;
    }),
  );
}
async function detail(id: number) {
  surveyId = id;
  const [s, stats] = await Promise.all([api(`/api/surveys/${id}`), api(`/api/surveys/${id}/stats`)]);
  node("survey-title").textContent = s.name;
  node("stats").textContent = `共 ${s.revisions.length} 个版本 · ${stats.total} 份答卷`;
  node("revisions").replaceChildren(
    ...s.revisions.map((v: any) => {
      const row = document.createElement("tr"),
        c = cell(""),
        stat = stats.revisions.find((x: any) => x.id === v.id);
      if (v.status === "draft") c.append(button("编辑草稿", () => edit(v.id)));
      if (v.status === "published")
        c.append(
          button("填写问卷", () => fill(v.id)),
          button("关闭版本", async () => {
            await post(`/api/revisions/${v.id}/close`, { actor: actor() });
            await detail(id);
          }),
        );
      c.append(
        button("历史答卷", async () => {
          historyRevision = v.id;
          offset = 0;
          await history();
        }),
      );
      row.append(cell(v.number), cell(v.status), cell(stat.count), cell(Number(stat.average).toFixed(2)), c);
      return row;
    }),
  );
  node("events").replaceChildren(
    ...s.events.map((e: any) => {
      const row = document.createElement("tr");
      row.append(cell(e.action), cell(e.actor), cell(e.revisionId), cell(e.created));
      return row;
    }),
  );
  location.hash = "detail";
}
async function edit(id: number) {
  editing = await api<Revision>(`/api/revisions/${id}`);
  surveyId = editing.surveyId;
  if (editing.status !== "draft") throw new Error("只有草稿可以编辑");
  node("edit-title").textContent = `编辑版本 ${editing.number}`;
  node<HTMLInputElement>("content-title").value = editing.content.title;
  editor(editing.content.questions);
  node("edit-version").textContent = `草稿编辑版本 ${editing.editVersion}`;
  location.hash = "edit";
}
async function save() {
  if (!editing) return;
  editing = await api<Revision>(`/api/revisions/${editing.id}`, {
    method: "PUT",
    body: JSON.stringify({
      actor: actor(),
      editVersion: editing.editVersion,
      content: readContent(),
    }),
  });
  node("edit-version").textContent = `已保存，草稿编辑版本 ${editing.editVersion}`;
}
async function fill(id: number) {
  filling = null;
  node("questions").replaceChildren();
  node("fill-version").textContent = "加载问卷版本…";
  node<HTMLButtonElement>("submit").disabled = true;
  filling = await api<Revision>(`/api/revisions/${id}`);
  if (filling.status !== "published") throw new Error("问卷版本未开放");
  surveyId = filling.surveyId;
  requestId = crypto.randomUUID();
  node("fill-title").textContent = filling.content.title;
  node("fill-version").textContent = `发布版本 ${filling.number} · 历史结果固定使用本版规则`;
  renderQuestions(filling.content);
  node<HTMLButtonElement>("submit").disabled = false;
  location.hash = "fill";
}
async function result(id: number) {
  const v = await api(`/api/submissions/${id}`);
  surveyId = v.revision.surveyId;
  historyRevision = v.revisionId;
  node("result-meta").textContent = `答卷 #${v.id} · ${v.respondent} · 问卷版本 ${v.revision.number} · ${v.created}`;
  node("score").textContent = `总分 ${v.result.score} / 100 · 加权 ${v.result.weightedTotal} / ${v.result.maximum}`;
  node("dimensions").replaceChildren(
    ...Object.entries(v.result.dimensions).map(([name, value]) => {
      const d = value as any,
        row = document.createElement("tr");
      row.append(cell(name), cell(d.weighted), cell(d.maximum), cell(d.score));
      return row;
    }),
  );
  node("parts").replaceChildren(
    ...v.result.parts.map((p: any) => {
      const row = document.createElement("tr");
      row.append(cell(p.id), cell(p.dimension), cell(`${p.weighted} / ${p.maximum}`), cell(p.explanation));
      return row;
    }),
  );
  node("hidden-questions").textContent = "条件隐藏题：" + (v.result.hidden.join(", ") || "无");
  node("original-answers").replaceChildren(
    ...v.revision.content.questions.map((q: any) => {
      const li = document.createElement("li");
      li.textContent = q.label + "：" + JSON.stringify(v.answers[q.id] ?? "未作答 / 隐藏");
      return li;
    }),
  );
  node<HTMLAnchorElement>("export").href = url(`/api/submissions/${id}/export`);
  location.hash = "result";
}
async function history() {
  const v = await api(`/api/submissions?revisionId=${historyRevision}&offset=${offset}&limit=25`);
  node("history-title").textContent = `版本标识 ${historyRevision} 的历史答卷`;
  node("history-total").textContent = `共 ${v.total} 份 · 第 ${offset / 25 + 1} 页`;
  node("history").replaceChildren(
    ...v.items.map((x: any) => {
      const row = document.createElement("tr"),
        c = cell("");
      c.append(button("答案与评分详情", () => result(x.id)));
      row.append(cell(x.id), cell(x.respondent), cell(x.score), cell(x.created), c);
      return row;
    }),
  );
  node<HTMLButtonElement>("prev").disabled = offset === 0;
  node<HTMLButtonElement>("next").disabled = offset + 25 >= v.total;
  location.hash = "history";
}
function route() {
  const name = location.hash.slice(1) || "surveys";
  document.querySelectorAll<HTMLElement>("[data-page]").forEach((s) => (s.hidden = s.dataset.page !== name));
  if (name === "surveys") list().catch(message);
}
form("create-survey", async () => {
  const revision = await post("/api/surveys", {
    name: node<HTMLInputElement>("survey-name").value,
    actor: actor(),
  });
  await edit(revision.id);
});
form("editor", save);
form("answers", async () => {
  if (!filling) return;
  const response = await post("/api/submissions", {
    revisionId: filling.id,
    requestId,
    respondent: node<HTMLSelectElement>("respondent").value,
    answers: answers(filling.content),
  });
  await result(response.id);
});
node("add-question").onclick = () => addQuestion();
node("publish").onclick = () => {
  if (editing)
    post(`/api/revisions/${editing.id}/publish`, { actor: actor() })
      .then(() => detail(surveyId))
      .catch(message);
};
node("clone").onclick = () =>
  post(`/api/surveys/${surveyId}/revisions`, { actor: actor() })
    .then((r) => edit(r.id))
    .catch(message);
node("back-survey").onclick = () => detail(surveyId).catch(message);
node("result-history").onclick = () => {
  offset = 0;
  history().catch(message);
};
node("prev").onclick = () => {
  offset = Math.max(0, offset - 25);
  history().catch(message);
};
node("next").onclick = () => {
  offset += 25;
  history().catch(message);
};
window.onhashchange = route;
configure()
  .then(async () => {
    const actors = await api<string[]>("/api/actors");
    for (const id of ["actor", "respondent"]) node(id).replaceChildren(...actors.map((a) => new Option(a, a)));
    location.hash = "surveys";
    route();
  })
  .catch(message);
