import { el, api, post, error, cell, action, form } from "./ui.js";
let assetOffset = 0,
  loanOffset = 0,
  currentLoan;
const cart = new Map();
const labels = {
  available: "可借",
  reserved: "已占用",
  lent: "已领用",
  maintenance: "维修中",
  draft: "草稿",
  submitted: "待审批",
  approved: "已批准",
  rejected: "已拒绝",
  checked_out: "已领用",
  partially_returned: "部分归还",
  closed: "已结清",
};
const actor = () => el("actor").value;
const request = () => ({ requestId: crypto.randomUUID(), actor: actor() });
async function categories() {
  const list = await api("/api/categories");
  for (const id of ["category", "category-filter"])
    el(id).replaceChildren(
      ...(id === "category-filter" ? [new Option("全部", "0")] : []),
      ...list.map((c) => new Option(c.name, c.id)),
    );
}
async function stats() {
  const s = await api("/api/stats");
  el("stats").textContent =
    `资产 ${s.total} 件 · ${s.states.map((x) => `${labels[x.status]} ${x.count}`).join(" / ")} · 逾期单 ${s.overdue} · 待维修 ${s.repairs}`;
}
function showCart() {
  el("cart-count").textContent = `已选 ${cart.size} 件；在“新建借用单”填写用途和期限`;
  el("cart").replaceChildren(
    ...Array.from(cart.values()).map((a) => {
      const li = document.createElement("li");
      li.textContent = a.name + " (" + a.serial + ") ";
      li.append(
        action("移除", async () => {
          cart.delete(a.id);
          showCart();
        }),
      );
      return li;
    }),
  );
}
function events(id, list) {
  el(id).replaceChildren(
    ...list.map((e) => {
      const r = document.createElement("tr");
      r.append(cell(e.action), cell(e.actor), cell(e.detail), cell(e.created));
      return r;
    }),
  );
}
async function assets() {
  const r = await api(
    "/api/assets?" +
      new URLSearchParams({
        q: el("query").value,
        status: el("status").value,
        categoryId: el("category-filter").value,
        offset: assetOffset,
        limit: 25,
      }),
  );
  el("assets").replaceChildren(
    ...r.items.map((a) => {
      const row = document.createElement("tr"),
        check = cell(""),
        input = document.createElement("input");
      input.type = "checkbox";
      input.setAttribute("aria-label", "选择 " + a.name);
      input.checked = cart.has(a.id);
      input.onchange = () => {
        if (input.checked) {
          if (cart.size >= 20) {
            input.checked = false;
            error("每单最多 20 件资产");
            return;
          }
          cart.set(a.id, a);
        } else cart.delete(a.id);
        showCart();
      };
      check.append(input);
      const actions = cell("");
      actions.append(
        action("资产履历", async () => {
          const h = await api(`/api/assets/${a.id}/history`);
          el("history-title").textContent = h.asset.name + " / " + h.asset.serial;
          events("asset-history", h.events);
          location.hash = "asset-history";
        }),
      );
      row.append(check, cell(a.serial), cell(a.name), cell(a.category), cell(labels[a.status]), actions);
      return row;
    }),
  );
  el("asset-count").textContent = `共 ${r.total} 件 · 第 ${assetOffset / 25 + 1} 页`;
  el("asset-prev").disabled = assetOffset === 0;
  el("asset-next").disabled = assetOffset + 25 >= r.total;
  showCart();
  await stats();
}
async function loans() {
  const r = await api(
    "/api/loans?" +
      new URLSearchParams({
        status: el("loan-status").value,
        borrower: el("borrower-filter").value,
        overdue: el("overdue").checked,
        offset: loanOffset,
        limit: 25,
      }),
  );
  el("loans").replaceChildren(
    ...r.items.map((l) => {
      const row = document.createElement("tr"),
        c = cell("");
      c.append(action("详情", () => detail(l.id)));
      row.append(cell(l.id), cell(l.borrower), cell(l.dueDate), cell(l.itemCount), cell(labels[l.status]), c);
      return row;
    }),
  );
  el("loan-count").textContent = `共 ${r.total} 单 · 第 ${loanOffset / 25 + 1} 页`;
  el("loan-prev").disabled = loanOffset === 0;
  el("loan-next").disabled = loanOffset + 25 >= r.total;
  await stats();
}
async function detail(id) {
  const loan = await api(`/api/loans/${id}`);
  currentLoan = loan;
  el("loan-title").textContent = `借用单 #${id} · ${labels[loan.status]}`;
  el("loan-summary").textContent = `申请人 ${loan.borrower} · 期限 ${loan.dueDate} · 用途 ${loan.purpose}`;
  const available =
    {
      draft: ["submit"],
      submitted: ["approve", "reject"],
      approved: ["checkout"],
    }[loan.status] || [];
  el("loan-actions").replaceChildren(
    ...available.map((op) =>
      action(
        {
          submit: "提交审批",
          approve: "批准整单",
          reject: "拒绝申请",
          checkout: "确认领用",
        }[op],
        async () => {
          await post(`/api/loans/${id}/${op}`, request());
          await detail(id);
          await stats();
        },
      ),
    ),
  );
  const canReturn = ["checked_out", "partially_returned"].includes(loan.status);
  el("return-button").hidden = !canReturn;
  el("loan-items").replaceChildren(
    ...loan.items.map((a) => {
      const row = document.createElement("tr"),
        c = cell(""),
        pick = document.createElement("input");
      pick.type = "checkbox";
      pick.dataset.asset = String(a.assetId);
      pick.disabled = !canReturn || Boolean(a.returned);
      pick.setAttribute("aria-label", "归还 " + a.name);
      c.append(pick);
      const condition = cell(""),
        select = document.createElement("select");
      select.dataset.condition = String(a.assetId);
      select.append(new Option("完好", "good"), new Option("损坏", "damaged"));
      select.disabled = pick.disabled;
      condition.append(select);
      const notes = cell(""),
        input = document.createElement("input");
      input.dataset.note = String(a.assetId);
      input.setAttribute("aria-label", a.name + " 归还备注");
      input.disabled = pick.disabled;
      notes.append(input);
      row.append(
        c,
        cell(a.name),
        cell(labels[a.assetStatus]),
        cell(a.returned ? "已归还 " + a.condition : "未归还"),
        condition,
        notes,
      );
      return row;
    }),
  );
  events("loan-history", loan.events);
  location.hash = "detail";
}
async function repairs() {
  const list = await api("/api/maintenance");
  el("repairs").replaceChildren(
    ...list.map((m) => {
      const row = document.createElement("tr"),
        c = cell("");
      if (m.status === "open") {
        const note = document.createElement("input");
        note.placeholder = "维修结果说明";
        note.setAttribute("aria-label", "维修说明 " + m.id);
        c.append(
          note,
          action("完成维修", async () => {
            await post(`/api/maintenance/${m.id}/complete`, {
              ...request(),
              note: note.value,
            });
            await repairs();
            await stats();
          }),
        );
      }
      row.append(cell(m.id), cell(m.name), cell(m.status), cell(m.note), c);
      return row;
    }),
  );
  await stats();
}
async function page() {
  const name = location.hash.slice(1) || "assets";
  document.querySelectorAll("[data-page]").forEach((p) => (p.hidden = p.dataset.page !== name));
  if (name === "assets") await assets();
  if (name === "loans") await loans();
  if (name === "new-loan") showCart();
  if (name === "maintenance") await repairs();
}
form("filters", async () => {
  assetOffset = 0;
  await assets();
});
form("loan-filters", async () => {
  loanOffset = 0;
  await loans();
});
form("register", async () => {
  await post("/api/assets", {
    name: el("name").value,
    serial: el("serial").value,
    categoryId: Number(el("category").value),
  });
  el("register").reset();
  error("资产登记成功");
  location.hash = "assets";
});
form("category-form", async () => {
  await post("/api/categories", { name: el("category-name").value });
  await categories();
  error("分类已保存");
});
form("loan-form", async () => {
  const loan = await post("/api/loans", {
    ...request(),
    assetIds: Array.from(cart.keys()),
    borrower: el("borrower").value,
    dueDate: el("due-date").value,
    purpose: el("purpose").value,
  });
  cart.clear();
  showCart();
  await detail(loan.id);
});
form("return-form", async () => {
  const items = Array.from(document.querySelectorAll("[data-asset]:checked")).map((c) => ({
    assetId: Number(c.dataset.asset),
    condition: document.querySelector(`[data-condition="${c.dataset.asset}"]`).value,
    note: document.querySelector(`[data-note="${c.dataset.asset}"]`).value,
  }));
  await post(`/api/loans/${currentLoan.id}/return`, { ...request(), items });
  await detail(currentLoan.id);
  await stats();
});
for (const [id, delta, kind] of [
  ["asset-prev", -25, "asset"],
  ["asset-next", 25, "asset"],
  ["loan-prev", -25, "loan"],
  ["loan-next", 25, "loan"],
])
  el(id).onclick = () => {
    if (kind === "asset") {
      assetOffset = Math.max(0, assetOffset + delta);
      assets().catch(error);
    } else {
      loanOffset = Math.max(0, loanOffset + delta);
      loans().catch(error);
    }
  };
window.onhashchange = () => page().catch(error);
async function start() {
  const actors = await api("/api/actors");
  for (const id of ["actor", "borrower", "borrower-filter"])
    el(id).replaceChildren(
      ...(id === "borrower-filter" ? [new Option("全部", "")] : []),
      ...actors.map((a) => new Option(a, a)),
    );
  el("due-date").value = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10);
  await categories();
  await page();
}
start().catch(error);
