const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
function check(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) check(file);
    else if (file.endsWith(".js")) execFileSync(process.execPath, ["--check", file]);
  }
}
check("src");
execFileSync(process.execPath, ["--check", "server.js"]);
const { summarize } = require("./src/service");
if (summarize("2,3,5").total !== 10) throw new Error("Business module verification failed");
fs.writeFileSync("build.marker", "built");
