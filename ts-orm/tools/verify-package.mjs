import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cp, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(import.meta.url);
const compilerManifest = require.resolve("typescript/package.json");
const compilerPackage = JSON.parse(await readFile(compilerManifest, "utf8"));
const compiler = resolve(dirname(compilerManifest), compilerPackage.bin.tsc);
const packageManager = process.env.npm_execpath;
if (!packageManager) throw new Error("Run this check with pnpm test:consumer");
const temporaryParent = await realpath(tmpdir());
const workspace = await mkdtemp(join(temporaryParent, "eqts-package-consumer-"));

function run(executable, args, cwd) {
  const result = spawnSync(executable, args, {
    cwd,
    encoding: "utf8",
    windowsHide: true,
    timeout: 120_000,
    maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, NODE_PATH: "" },
  });
  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(
      `Consumer command failed (${result.status}): ${result.stdout}\n${result.stderr}`,
    );
  return result.stdout;
}
function pnpm(args, cwd) {
  return /\.[cm]?js$/i.test(packageManager)
    ? run(process.execPath, [packageManager, ...args], cwd)
    : run(packageManager, args, cwd);
}
function isInside(parent, child) {
  const path = relative(parent, child);
  return path !== "" && !path.startsWith("..") && !isAbsolute(path);
}

let failure;
let failed = false;
try {
  const tarball = join(workspace, "orm.tgz");
  const packed = JSON.parse(pnpm(["pack", "--out", tarball, "--json"], root));
  const entries = Array.isArray(packed) ? packed[0].files : packed.files;
  const names = entries.map((entry) => (typeof entry === "string" ? entry : entry.path));
  assert.ok(names.includes("dist/index.js") && names.includes("dist/index.d.ts"));
  // 声明/source map 要能跳到同包源码；运行和类型入口仍是 dist，不依赖工作区源文件。
  assert.ok(names.includes("src/index.ts"));
  assert.ok(
    names.every((name) => /^(dist\/|src\/|README\.md$|package\.json$)/.test(name)),
    "Unexpected packed files",
  );
  await cp(join(root, "fixtures", "consumer"), workspace, { recursive: true });
  await writeFile(
    join(workspace, "package.json"),
    JSON.stringify(
      {
        name: "easy-query-pro-packed-consumer",
        version: "0.0.0",
        private: true,
        type: "module",
        dependencies: { "@easy-query-pro/orm": "file:./orm.tgz" },
      },
      null,
      2,
    ),
  );
  pnpm(
    [
      "install",
      "--offline",
      "--ignore-scripts",
      "--ignore-pnpmfile",
      "--ignore-workspace",
      "--no-frozen-lockfile",
      "--config.auto-install-peers=false",
      "--virtual-store-dir",
      join(workspace, "node_modules", ".pnpm"),
    ],
    workspace,
  );
  const localRequire = createRequire(join(workspace, "package.json"));
  const probe = join(workspace, "resolve.mjs");
  await writeFile(
    probe,
    `
for (const [path, name] of [["postgres", "postgresDriver"], ["mysql", "mysqlDriver"], ["hana", "hanaDriver"]]) {
  const adapter = await import("@easy-query-pro/orm/" + path);
  if (typeof adapter[name] !== "function") throw new Error("Missing driver export: " + name);
}
process.stdout.write(import.meta.resolve("@easy-query-pro/orm"));
`,
  );
  const entry = await realpath(fileURLToPath(run(process.execPath, [probe], workspace)));
  assert.ok(
    isInside(await realpath(workspace), entry),
    "Consumer escaped into the development checkout or a shared virtual store",
  );
  assert.ok(entry.endsWith(join("dist", "index.js")));
  for (const peer of ["pg", "mysql2", "@sap/hana-client", "@types/node", "@types/pg"])
    assert.throws(() => localRequire.resolve(peer + "/package.json"), { code: "MODULE_NOT_FOUND" });
  const diagnostics = run(
    process.execPath,
    [compiler, "--project", "tsconfig.json", "--extendedDiagnostics", "--listFiles"],
    workspace,
  );
  const lines = diagnostics.split(/\r?\n/);
  const files = lines.filter((line) => line.endsWith(".ts"));
  assert.ok(files.length > 0, "The compiler did not report its resolved source files");
  const packageRoot = dirname(dirname(entry));
  const standardLibraries = [await realpath(join(dirname(compilerManifest), "lib"))];
  // TS 7 原生编译器从其锁定平台包读取 lib；这仍是编译器标准库，不是 ORM 工作区源码。
  const platformPackage = `@typescript/typescript-${process.platform}-${process.arch}`;
  if (compilerPackage.optionalDependencies?.[platformPackage]) {
    const platformManifest = createRequire(compilerManifest).resolve(
      platformPackage + "/package.json",
    );
    standardLibraries.push(await realpath(join(dirname(platformManifest), "lib")));
  }
  for (const file of files) {
    const actual = await realpath(file);
    assert.ok(
      isInside(join(packageRoot, "dist"), actual) ||
        standardLibraries.some((library) => isInside(library, actual)) ||
        actual === join(await realpath(workspace), "consumer.ts"),
      "Unexpected compiler input: " + actual,
    );
  }
  run(process.execPath, [join(workspace, "dist", "consumer.js")], workspace);
  for (const name of names.filter((name) => name.endsWith(".d.ts.map"))) {
    const mapFile = join(packageRoot, name);
    const map = JSON.parse(await readFile(mapFile, "utf8"));
    for (const source of map.sources)
      assert.ok(
        isInside(
          packageRoot,
          await realpath(resolve(dirname(mapFile), map.sourceRoot ?? "", source)),
        ),
        "A declaration map left the package",
      );
  }
  process.stdout.write(lines.filter((line) => !line.endsWith(".ts")).join("\n"));
} catch (error) {
  failed = true;
  failure = error;
  throw error;
} finally {
  // 唯一清理目标由本次 mkdtemp 创建；解析后的父目录和前缀均核对，绝不删除共享依赖缓存。
  try {
    const owned = await realpath(workspace);
    if (dirname(owned) !== temporaryParent || !basename(owned).startsWith("eqts-package-consumer-"))
      throw new Error("Refusing to clean an unowned consumer directory: " + owned);
    await rm(owned, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  } catch (cleanup) {
    if (failed)
      throw new AggregateError([failure, cleanup], "Consumer verification and cleanup failed");
    throw cleanup;
  }
}
console.log(
  "Packed consumer passed without database peers or workspace source resolution; temporary files removed.",
);
