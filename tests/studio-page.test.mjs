import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const root = new URL("../", import.meta.url);
const page = await readFile(new URL("studio/static/index.html", root), "utf8");
const server = await readFile(new URL("studio/corealign_studio.py", root), "utf8");

// The first Studio page embedded CoreAlign's REPORT.html in an iframe. The report brings its
// own sticky header and review bar, which stacked on top of Studio's own and left a page that
// was hard to read and hard to click. The review screen is Studio's own now; keep it that way.
test("the page does not embed REPORT.html", () => {
  assert.ok(!/<iframe/i.test(page), "no iframe belongs on this page");
  // Comments may name the file; loading it is what must not happen.
  const markup = page.replace(/<!--[\s\S]*?-->/g, "").replace(/\/\*[\s\S]*?\*\//g, "");
  assert.ok(!markup.includes("REPORT.html"),
    "the review screen must be drawn from /api/review, not from the generated report");
});

// The bridge token authorises writing corrections into the run. It stays on the compute node.
test("no loopback address or token reaches the browser", () => {
  assert.ok(!page.includes("127.0.0.1"), "the page must go through Studio, never the bridge");
  assert.ok(!page.includes("token="), "a token must never appear in served markup");
});

// A typo in an element id fails silently in the browser: the handler never binds, and the
// control simply does nothing when pressed. That is exactly the "hard to click" complaint.
test("every id the script reaches for exists in the markup", () => {
  const declared = new Set([...page.matchAll(/\bid="([A-Za-z0-9_-]+)"/g)].map((m) => m[1]));
  const used = new Set([...page.matchAll(/\$\("([A-Za-z0-9_-]+)"\)/g)].map((m) => m[1]));
  const missing = [...used].filter((id) => !declared.has(id));
  assert.deepEqual(missing, [], `script reaches for ids that are not in the page: ${missing}`);
});

// Same failure mode one level out: a call to a route the server does not answer returns 404
// and the screen stays blank with nothing explaining why.
test("every route the page calls is answered by the server", () => {
  const called = new Set(
    [...page.matchAll(/(?:api|fetch)\("(\/api\/[a-z-]+(?:\/[a-z-]+)?)/g)].map((m) => m[1]));
  assert.ok(called.size >= 6, `expected the page to call several routes, saw ${called.size}`);
  for (const route of called) {
    // Some routes are prefixes the server dispatches on, e.g. /api/bridge/<action>.
    const parent = route.slice(0, route.lastIndexOf("/") + 1);
    const answered = server.includes(`"${route}"`) ||
      server.includes(`route.startswith("${parent}")`);
    assert.ok(answered, `the server has no handler for ${route}`);
  }
});

// Studio is the only place these two gates can be answered in a headless run, so the ids have
// to match what CoreAlign writes into gate.json.
test("the page handles both review gates by name", () => {
  for (const gate of ["grid", "orientation"]) {
    assert.ok(page.includes(`gateId === "${gate}"`), `no branch for the ${gate} gate`);
  }
});

// A rule that sets display beats the user-agent [hidden] rule, so buttons stayed on screen
// after being hidden in script. One global rule covers every future case.
test("hidden actually hides", () => {
  assert.match(page, /\[hidden\]\{display:none!important\}/,
    "elements given an explicit display need [hidden] to win");
});

// Rotating an image that is fitted to its frame clips the corners unless it is scaled down by
// 1/(|cos|+|sin|). A core whose corners are cut off cannot be judged.
test("the rotation preview scales to stay inside the frame", () => {
  assert.ok(page.includes("Math.abs(Math.cos(radians)) + Math.abs(Math.sin(radians))"),
    "the fit factor is missing, so a rotated core will be clipped");
});

// Cores are independent, so the run should use the CPUs the Slurm job was actually given.
// A fixed 2 left three quarters of an eight-CPU allocation idle.
test("the worker count comes from the allocation, not a constant", () => {
  assert.ok(server.includes('"parallelWorkers": orientation_workers()'),
    "build_config must ask for the allocation");
  assert.match(server, /SLURM_CPUS_PER_TASK/, "the allocation is read from Slurm");
  assert.ok(!/["']parallelWorkers["']:\s*\d/.test(server),
    "no hardcoded worker count belongs in the config");
});

// The worker count is the one number that can take a node down. Every path through it must
// end at the measured ceiling, including the one where nothing said how much memory the job
// holds: on a 112-CPU node the bare CPU count would ask for 111 workers.
test("the worker count is capped on every path", () => {
  const body = server.slice(server.indexOf("def orientation_workers"),
                            server.indexOf("def build_config"));
  const returns = [...body.matchAll(/^\s+return .+$/gm)].map((m) => m[0]);
  assert.ok(returns.length >= 2, "expected orientation_workers to have several exits");
  for (const line of returns) {
    assert.match(line, /MAX_WORKERS/, `an exit skips the cap: ${line.trim()}`);
  }
});

// CoreAlign removes gate.json in a finally block, which does not run when the JVM is killed:
// a cancelled job, an OOM kill, a node going away. The file then names a loopback port that
// belongs to a dead process, and answering it fails with a bare "Connection refused" that
// reads like a network fault. A gate is only real while the QuPath that asked it is alive.
test("a gate is only real while its QuPath is running", () => {
  const body = server.slice(server.indexOf("    def gate(self)"),
                            server.indexOf("    def report_html"));
  assert.match(body, /process\.poll\(\) is None/,
    "gate() must check that the process it belongs to is still running");
  assert.match(body, /if not alive:\s*\n\s*return None/,
    "a gate from a dead run must not be reported as open");
  assert.ok(server.includes('"abandonedGate"'),
    "the status should still surface the leftover, so the page can explain it");
});

// The liveness check alone is not enough: from the moment a new run starts there is a live
// process again, so a gate.json left by the previous one would be read as this run's
// question before it has asked anything.
test("starting a run clears a gate left by the previous one", () => {
  const body = server.slice(server.indexOf("    def start(self"),
                            server.indexOf("    def attach(self"));
  assert.match(body, /glob\("\*\/gate\.json"\)/,
    "start must remove a leftover gate before launching QuPath");
});

// A correction is applied by shifting later cores along, which is not idempotent. Tying the
// file to the grid it was made against is the only thing stopping a double apply.
test("grid corrections are tied to the grid they were made against", () => {
  assert.match(server, /"baseGridHash": grid\["gridHash"\]/,
    "the corrections file must name the grid it belongs to");
  assert.ok(page.includes("model.grid.gridHash !== gridKey"),
    "the page must drop edits when CoreAlign moves to a different grid");
});
