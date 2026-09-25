import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e-boot",
  globalSetup: "./e2e-boot/setup.ts",
  workers: 1,
  retries: 0,
  timeout: 30000,
  reporter: "list",
  use: { ...devices["Desktop Chrome"], trace: "retain-on-failure" },
});
