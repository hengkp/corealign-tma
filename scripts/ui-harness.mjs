// Renders the two CoreAlign dialogs outside QuPath's normal flow and asserts
// that they show, that the segmented controls report the option that was
// clicked, and that a gate decision is delivered exactly once.
//
//   node scripts/ui-harness.mjs [path-to-QuPath-launcher]
//
// It needs a real JavaFX toolkit, so it runs inside QuPath's own JVM. On macOS
// the launcher is
// /Applications/QuPath-<version>.app/Contents/MacOS/QuPath-<version>.
import { readFile, writeFile, mkdtemp } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";

const root = new URL("../", import.meta.url);
const launcher = process.argv[2] ??
  "/Applications/QuPath-0.7.0-arm64.app/Contents/MacOS/QuPath-0.7.0-arm64";

const runner = await readFile(new URL("workflow/CoreAlign.groovy", root), "utf8");
const imports = runner.slice(0, runner.indexOf("/**\n * Loopback save bridge"));
const classes = runner.slice(
  runner.indexOf("/**\n * The whole setup surface"),
  runner.indexOf("// Generated synthetic microscopy placeholder."),
);
const body = await readFile(new URL("tests/ui-harness-body.groovy", root), "utf8");

const dir = await mkdtemp(join(tmpdir(), "corealign-ui-"));
const script = join(dir, "harness.groovy");
await writeFile(script, imports + classes + body, "utf8");

const result = spawnSync(launcher, ["-D", `harness.out=${dir}`, "script", script], {
  encoding: "utf8", timeout: 240000,
});
const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
const cssErrors = [...output.matchAll(/CSS Error parsing[^\n]*/g)].map((m) => m[0]);
for (const line of output.split("\n")) {
  if (/HARNESS|SNAPSHOT|SETUP_RESULT|GATE_DECISION/.test(line)) console.log(line.trim());
}
if (cssErrors.length > 0) {
  console.error(`FAILED: JavaFX rejected ${cssErrors.length} style declaration(s)`);
  cssErrors.forEach((line) => console.error(`  ${line}`));
  process.exit(1);
}
if (!output.includes("COREALIGN_UI_HARNESS_PASSED")) {
  console.error("FAILED: the UI harness did not pass");
  console.error(output.split("\n").slice(-25).join("\n"));
  process.exit(1);
}
console.log(`Screenshots: ${dir}`);
