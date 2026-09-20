const object = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const fields = (v, shape) =>
  object(v) &&
  Object.entries(shape).every(([k, t]) =>
    t === "array" ? Array.isArray(v[k]) : typeof v[k] === t,
  );
const list = (v, shape) => Array.isArray(v) && v.every((x) => fields(x, shape));
export function validResponse(path, method, status, v) {
  if (status >= 400) return fields(v, { error: "string" });
  if (path === "/readyz")
    return (
      fields(v, { status: "string", version: "number", component: "string" }) &&
      v.version === 2
    );
  if (path === "/api/actors")
    return Array.isArray(v) && v.every((x) => typeof x === "string");
  if (path === "/api/categories")
    return method === "GET"
      ? list(v, { id: "number", name: "string" })
      : fields(v, { id: "number", name: "string" });
  if (path === "/api/assets" && method === "GET")
    return (
      fields(v, { items: "array", total: "number" }) &&
      list(v.items, {
        id: "number",
        name: "string",
        serial: "string",
        status: "string",
      })
    );
  if (path === "/api/assets")
    return fields(v, {
      id: "number",
      name: "string",
      serial: "string",
      status: "string",
      categoryId: "number",
    });
  if (path.endsWith("/history"))
    return (
      fields(v, { asset: "object", events: "array" }) &&
      fields(v.asset, {
        id: "number",
        name: "string",
        serial: "string",
        status: "string",
      })
    );
  if (path === "/api/stats")
    return fields(v, {
      total: "number",
      states: "array",
      loans: "array",
      overdue: "number",
      repairs: "number",
    });
  if (path === "/api/loans" && method === "GET")
    return (
      fields(v, { items: "array", total: "number" }) &&
      list(v.items, {
        id: "number",
        borrower: "string",
        status: "string",
        dueDate: "string",
      })
    );
  if (path.startsWith("/api/loans"))
    return fields(v, {
      id: "number",
      borrower: "string",
      dueDate: "string",
      status: "string",
      items: "array",
      events: "array",
    });
  if (path === "/api/maintenance")
    return list(v, { id: "number", assetId: "number", status: "string" });
  if (path.startsWith("/api/maintenance"))
    return fields(v, { id: "number", assetId: "number", status: "string" });
  return false;
}
