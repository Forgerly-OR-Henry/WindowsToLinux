function loadConfiguration() {
  const raw = process.env.PORT ?? "";
  const port = Number(raw);
  if (!/^[0-9]+$/.test(raw) || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");
  const mode = "smoke";
  const label = mode === "config" ? (process.env.FIXTURE_LABEL ?? "runtime-config-default") : "deployment-smoke-ok";
  return { port, mode, status: 200, label };
}
module.exports = { loadConfiguration };
