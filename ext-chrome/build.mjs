// Build script for the Cadence Chrome extension.
//
// Bundles the MV3 service worker and the popup with esbuild and stages the
// static files (manifest + popup html/css) into dist/.
// Run via `npm run build` (which typechecks first). Pass --watch for dev.
import * as esbuild from "esbuild";
import { cpSync, mkdirSync, rmSync } from "node:fs";

const watch = process.argv.includes("--watch");
const outdir = "dist";

rmSync(outdir, { recursive: true, force: true });
mkdirSync(outdir, { recursive: true });

// Static assets copied verbatim into the extension root.
for (const file of ["manifest.json", "popup.html", "popup.css"]) {
  cpSync(file, `${outdir}/${file}`);
}

/** @type {import('esbuild').BuildOptions} */
const options = {
  entryPoints: {
    background: "src/background/index.ts",
    popup: "src/popup/popup.ts",
  },
  bundle: true,
  format: "esm",
  // MV3 service workers run in a recent Chromium; target matches our manifest.
  target: "chrome120",
  platform: "browser",
  outdir,
  sourcemap: true,
  logLevel: "info",
};

if (watch) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
  console.log("esbuild: watching for changes…");
} else {
  await esbuild.build(options);
  console.log("esbuild: build complete → dist/");
}
