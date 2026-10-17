import { spawnSync } from "node:child_process";
import process from "node:process";

const wrapper = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const result = spawnSync(wrapper, ["build"], {
  cwd: process.cwd(),
  shell: process.platform === "win32",
  stdio: "inherit",
});

if (result.error) {
  throw result.error;
}
process.exit(result.status ?? 1);
