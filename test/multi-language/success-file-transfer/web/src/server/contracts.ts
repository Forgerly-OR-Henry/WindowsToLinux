type RecordValue = Record<string, unknown>;
const object = (v: unknown): v is RecordValue =>
  v !== null && typeof v === "object" && !Array.isArray(v);
const fields = (v: unknown, shape: Record<string, string>) =>
  object(v) &&
  Object.entries(shape).every(([k, t]) =>
    t === "array" ? Array.isArray(v[k]) : typeof v[k] === t,
  );
const list = (v: unknown, shape: Record<string, string>) =>
  Array.isArray(v) && v.every((x) => fields(x, shape));
export function validResponse(
  path: string,
  method: string,
  status: number,
  v: unknown,
): boolean {
  if (status >= 400) return fields(v, { error: "string" });
  if (path === "/readyz")
    return (
      fields(v, { status: "string", version: "number", component: "string" }) &&
      (v as RecordValue).version === 2
    );
  if (path === "/api/folders")
    return method === "GET"
      ? list(v, { id: "number", name: "string" })
      : fields(v, { id: "number", name: "string" });
  if (path === "/api/files")
    return (
      fields(v, { items: "array", total: "number" }) &&
      list((v as RecordValue).items, {
        id: "string",
        name: "string",
        versionId: "string",
        sha256: "string",
        size: "number",
        version: "number",
      })
    );
  if (/^\/api\/files\/[^/]+\/versions$/.test(path))
    return list(v, {
      id: "string",
      number: "number",
      size: "number",
      sha256: "string",
    });
  if (/\/chunks\/\d+$/.test(path))
    return fields(v, {
      number: "number",
      sha256: "string",
      duplicate: "boolean",
    });
  if (path.endsWith("/complete"))
    return fields(v, {
      id: "string",
      fileId: "string",
      number: "number",
      size: "number",
      sha256: "string",
    });
  if (path === "/api/uploads" && method === "GET")
    return list(v, { id: "string", name: "string", state: "string" });
  if (path.startsWith("/api/uploads"))
    return fields(v, {
      id: "string",
      folderId: "number",
      name: "string",
      size: "number",
      sha256: "string",
      state: "string",
      chunks: "array",
      events: "array",
    });
  return false;
}
