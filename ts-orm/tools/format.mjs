import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const cli = require.resolve("prettier/bin/prettier.cjs");
const root = fileURLToPath(new URL("../", import.meta.url));

function run(arguments_) {
  const result = spawnSync(process.execPath, [cli, ...arguments_], {
    cwd: root,
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.error) throw result.error;
  if (result.stderr) process.stderr.write(result.stderr);
  return result;
}

// Prettier 3.9.10 的部分 TS 链式表达式首遍输出不是稳定格式，纯 format(format(source)) 已复现。
// 保留原来的检查规则，仅重写仍有差异的文件；有限次数后失败，避免隐藏持续不稳定的格式器。
let targets = ["."];
for (let pass = 1; pass <= 3; pass++) {
  const written = run(["--write", "--ignore-unknown", "--log-level", "warn", "--", ...targets]);
  if (written.status !== 0) throw new Error("Prettier could not format the sources");
  const checked = run(["--list-different", "--ignore-unknown", "."]);
  if (checked.status === 0) {
    console.log(`Formatting is stable after ${pass} pass(es).`);
    process.exit(0);
  }
  if (checked.status !== 1) throw new Error("Prettier could not check the sources");
  targets = checked.stdout.split(/\r?\n/).filter(Boolean);
  if (!targets.length) throw new Error("Prettier reported changes without identifying files");
}
throw new Error("Prettier formatting did not stabilize: " + targets.join(", "));
