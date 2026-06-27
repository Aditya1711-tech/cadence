// Bundles every *.test.ts under src/ into dist-test/ with esbuild, then they are
// run with `node --test`. Node can't run our .ts sources directly (the .js
// import specifiers resolve to .ts only through esbuild), so we bundle first.
// Node builtins (node:test, node:assert) stay external via platform: "node".
import * as esbuild from "esbuild";
import { readdirSync, rmSync, statSync } from "node:fs";
import { basename, join } from "node:path";

function findTests(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) findTests(p, acc);
    else if (name.endsWith(".test.ts")) acc.push(p);
  }
  return acc;
}

const entries = findTests("src");
// Flatten outputs to dist-test/<name>.test.js so a single-level glob
// (dist-test/*.test.js) picks them all up — `node --test <dir>` is unreliable.
const entryPoints = Object.fromEntries(
  entries.map((p) => [basename(p).replace(/\.ts$/, ""), p]),
);
rmSync("dist-test", { recursive: true, force: true });
await esbuild.build({
  entryPoints,
  outdir: "dist-test",
  bundle: true,
  format: "esm",
  platform: "node",
  target: "node20",
  logLevel: "warning",
});
console.log(`built ${entries.length} test bundle(s) → dist-test/`);
