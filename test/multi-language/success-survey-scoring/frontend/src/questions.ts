import { node, button } from "./api";
export type Question = {
  id: string;
  label: string;
  type: string;
  required: boolean;
  min?: number;
  max?: number;
  options?: { value: string; label: string; score: number }[];
  visibleWhen?: { question: string; equals: string };
  rule: { dimension: string; weight: number; reverse: boolean };
};
export type Content = { title: string; questions: Question[] };
function field(label: string, input: HTMLElement) {
  const e = document.createElement("label");
  e.append(document.createTextNode(label), input);
  return e;
}
function input(value: string, type = "text") {
  const e = document.createElement("input");
  e.type = type;
  e.value = value;
  return e;
}
export function editor(questions: Question[]) {
  node("questions-editor").replaceChildren();
  questions.forEach(addQuestion);
}
export function addQuestion(q?: Question) {
  const question: Question = q || {
    id: "question_" + (node("questions-editor").children.length + 1),
    label: "新问题",
    type: "scale",
    required: true,
    min: 1,
    max: 5,
    rule: { dimension: "体验", weight: 1, reverse: false },
  };
  const card = document.createElement("section");
  card.className = "question-editor";
  const controls: Record<string, HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement> = {};
  for (const [key, label, value, type] of [
    ["id", "题号", question.id, "text"],
    ["label", "题目", question.label, "text"],
    ["dimension", "维度", question.rule.dimension, "text"],
    ["weight", "权重", String(question.rule.weight), "number"],
    ["min", "量表下限", String(question.min ?? 1), "number"],
    ["max", "量表上限", String(question.max ?? 5), "number"],
    ["condition", "显示条件题号", question.visibleWhen?.question || "", "text"],
    ["equals", "显示条件值", question.visibleWhen?.equals || "", "text"],
  ]) {
    const e = input(value, type);
    e.dataset.field = key;
    controls[key] = e;
    card.append(field(label, e));
  }
  const type = document.createElement("select");
  type.dataset.field = "type";
  type.append(new Option("单选", "single"), new Option("多选", "multi"), new Option("量表", "scale"));
  type.value = question.type;
  controls.type = type;
  card.append(field("题型", type));
  for (const [key, label, value] of [
    ["required", "必填", question.required],
    ["reverse", "反向计分", question.rule.reverse],
  ] as const) {
    const e = input("", "checkbox");
    e.checked = value;
    e.dataset.field = key;
    controls[key] = e;
    card.append(field(label, e));
  }
  const options = document.createElement("textarea");
  options.dataset.field = "options";
  options.rows = 3;
  options.value = (
    question.options || [
      { value: "yes", label: "是", score: 1 },
      { value: "no", label: "否", score: 0 },
    ]
  )
    .map((o) => `${o.value}|${o.label}|${o.score}`)
    .join("\n");
  controls.options = options;
  card.append(field("选项（每行 值|文本|分数）", options));
  const refresh = () => {
    for (const key of ["min", "max"]) controls[key].parentElement!.hidden = type.value !== "scale";
    options.parentElement!.hidden = type.value === "scale";
  };
  type.onchange = refresh;
  refresh();
  card.append(button("删除问题", async () => card.remove()));
  node("questions-editor").append(card);
}
export function readContent(): Content {
  return {
    title: node<HTMLInputElement>("content-title").value,
    questions: Array.from(document.querySelectorAll<HTMLElement>(".question-editor")).map((card) => {
      const get = (key: string) => card.querySelector<HTMLInputElement>(`[data-field="${key}"]`)!;
      const q: Question = {
        id: get("id").value,
        label: get("label").value,
        type: get("type").value,
        required: get("required").checked,
        rule: {
          dimension: get("dimension").value,
          weight: Number(get("weight").value),
          reverse: get("reverse").checked,
        },
      };
      if (q.type === "scale") {
        q.min = Number(get("min").value);
        q.max = Number(get("max").value);
      } else
        q.options = get("options")
          .value.split("\n")
          .filter((s) => s.trim())
          .map((s) => {
            const [value, label, score] = s.split("|");
            return { value, label, score: Number(score) };
          });
      if (get("condition").value)
        q.visibleWhen = {
          question: get("condition").value,
          equals: get("equals").value,
        };
      return q;
    }),
  };
}
export function renderQuestions(content: Content) {
  node("questions").replaceChildren(
    ...content.questions.map((q) => {
      const section = document.createElement("section");
      section.dataset.question = q.id;
      const h = document.createElement("h3");
      h.textContent = q.label + (q.required ? " *" : "");
      section.append(h);
      if (q.type === "scale") {
        const e = input("", "number");
        e.min = String(q.min);
        e.max = String(q.max);
        e.step = "1";
        e.name = q.id;
        e.setAttribute("aria-label", q.label);
        section.append(e);
      } else
        for (const option of q.options!) {
          const e = input(option.value, q.type === "single" ? "radio" : "checkbox");
          e.name = q.id;
          section.append(field(option.label, e));
        }
      return section;
    }),
  );
  node("questions").onchange = () => visibility(content);
  visibility(content);
}
export function answers(content: Content) {
  const result: Record<string, string | number | string[]> = {};
  for (const q of content.questions) {
    const all = Array.from(document.getElementsByName(q.id)) as HTMLInputElement[];
    if (q.type === "scale") {
      if (all[0].value !== "") result[q.id] = Number(all[0].value);
    } else if (q.type === "single") {
      const selected = all.find((x) => x.checked);
      if (selected) result[q.id] = selected.value;
    } else result[q.id] = all.filter((x) => x.checked).map((x) => x.value);
  }
  return result;
}
function visibility(content: Content) {
  const values = answers(content);
  for (const q of content.questions) {
    const section = document.querySelector<HTMLElement>(`[data-question="${q.id}"]`)!;
    const visible = !q.visibleWhen || values[q.visibleWhen.question] === q.visibleWhen.equals;
    section.hidden = !visible;
    section
      .querySelectorAll<HTMLInputElement>("input")
      .forEach((e) => (e.required = visible && q.required && q.type !== "multi"));
  }
}
