import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, request, submitTask, terminal } from "./client";
import { language, t, errorText } from "../i18n/messages";
afterEach(() => {
  vi.unstubAllGlobals();
  language.value = "zh-CN";
});
describe("internal HTTP client", () => {
  it("sends guarded same-origin JSON task submissions", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{"id":"t1"}'));
    vi.stubGlobal("fetch", fetch);
    await expect(submitTask("ANALYZE", { sourceId: "s1" })).resolves.toEqual({ id: "t1" });
    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/tasks",
      expect.objectContaining({
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-W2L-Client": "web" },
        body: '{"kind":"ANALYZE","input":{"sourceId":"s1"}}',
      }),
    );
  });
  it("streams Blob content without JSON encoding", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response("{}"));
    vi.stubGlobal("fetch", fetch);
    const file = new Blob(["hello"]);
    await request("sources/id/files?path=index.html", "PUT", file);
    expect(fetch).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        body: file,
        headers: { "Content-Type": "application/octet-stream", "X-W2L-Client": "web" },
      }),
    );
  });
  it("localizes stable errors without displaying server diagnostics", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response('{"code":"STATE_CONFLICT","message":"private data","correlationId":"c1"}', { status: 409 }),
        ),
    );
    const failure = await request("servers").catch((e) => e);
    expect(failure).toBeInstanceOf(ApiError);
    if (!(failure instanceof ApiError)) throw failure;
    expect(failure.correlationId).toBe("c1");
    expect(errorText(failure)).not.toContain("private data");
  });
  it("handles network failures and non-JSON responses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network details")));
    await expect(request("health")).rejects.toMatchObject({ code: "CONNECTION_FAILED" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>unavailable</html>")));
    await expect(request("health")).rejects.toMatchObject({ code: "CONNECTION_FAILED" });
  });
  it("keeps pending decisions active and revalidation terminal", () => {
    expect(terminal("WAITING_DECISION")).toBe(false);
    expect(terminal("REVALIDATION_REQUIRED")).toBe(true);
  });
  it("localizes shared execution codes and concrete confirmation parameters", () => {
    expect(t("event.source-upload")).toBe("源码上传");
    expect(t("confirm.db.existingSchema", { database: "sample", files: "init.sql" })).toContain("init.sql");
    language.value = "en";
    expect(t("event.source-upload")).toBe("Source upload");
  });
});
