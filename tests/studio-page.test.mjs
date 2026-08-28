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

// Arrange is a real workflow destination, not a dialog layered over Review. Keeping the
// registration and panel together catches the two one-sided wiring mistakes that leave a
// tab with nowhere to go, or a screen nobody can reach.
test("the arrange stage is registered and has its own panel", () => {
  assert.match(page,
    /\{key:"review",\s*labelKey:"stageReview"\},\s*\{key:"arrange",\s*labelKey:"stageArrange"\},\s*\{key:"export"/,
    "Arrange must sit between Review and Results");
  assert.match(page, /<section class="panel arrange" data-panel="arrange"/,
    "the Arrange tab needs a matching panel");
});

// English and Thai are selected at runtime by the same key. A missing translation silently
// falls back to English, which makes a partly translated editor look finished in tests.
test("the English and Thai dictionaries have exactly the same keys", () => {
  function keys(name) {
    const start = page.indexOf(`var ${name} = {`);
    const body = page.slice(start, page.indexOf("\n};", start));
    return [...body.matchAll(/^\s{2}([A-Za-z0-9]+):/gm)].map((match) => match[1]).sort();
  }
  assert.deepEqual(keys("EN"), keys("TH"));
});

test("the arrange API is answered by the server", () => {
  assert.ok(server.includes('if route == "/api/arrange"'),
    "GET and POST need an /api/arrange dispatch");
  assert.ok(server.includes("RUN.arrange()"), "GET must return the attached arrangement");
  assert.ok(server.includes("RUN.save_arrangement"), "POST must validate and save it");
});

// Fluorescence channels contribute light. Drawing one opaque layer after another would leave
// only the last stain visible, so the browser must use a light-adding composite operation.
test("arrange channel compositing adds light instead of alpha covering", () => {
  const body = page.slice(page.indexOf("function arrangeComposite"),
                          page.indexOf("function queueArrangeCanvas"));
  assert.match(body, /globalCompositeOperation = "screen"/,
    "fluorescence layers must use screen compositing");
  assert.match(body, /strength \* rgb\.r \/ 255/,
    "each grayscale tile must be multiplied by its chosen colour");
});

// An interrupted write must leave either the previous arrangement or the complete next one.
// This is the same guarantee used by angle corrections beside the same slide.
test("arrangements use the corrections atomic replace pattern", () => {
  const body = server.slice(server.indexOf("    def save_arrangement"),
                            server.indexOf("    GRID_ACTIONS"));
  assert.match(body, /temporary = target\.with_name\("\." \+ target\.name \+ "\.tmp"\)/,
    "the temporary arrangement must be dot-prefixed and beside its target");
  assert.match(body, /temporary\.write_text[\s\S]*os\.replace\(temporary, target\)/,
    "the complete temporary file must replace the arrangement atomically");
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
  const alive = server.slice(server.indexOf("    def alive(self)"),
                             server.indexOf("    def poll(self)"));
  assert.match(alive, /process is not None and process\.poll\(\) is None/,
    "alive() must check that the process is still running");
  const body = server.slice(server.indexOf("    def gate(self)"),
                            server.indexOf("    def report_html"));
  assert.match(body, /if not self\.alive\(\):\s*\n\s*return None/,
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

// The bridge opens ServerSocket(0), so it takes a new random port every time CoreAlign
// rewrites the report. Caching the address meant one gate answered and the next failed with
// "Connection refused" against a port nothing was listening on.
test("the bridge address is never cached across steps", () => {
  const body = server.slice(server.indexOf("    def bridge_url"),
                            server.indexOf("    # -- results"));
  assert.match(body, /self\.discover_bridge\(\)/,
    "bridge_url must resolve the address again on every call");
  assert.ok(!/self\.bridge_tokens\.update/.test(server),
    "discovery must replace the tokens, not merge a stale port into them");
});

// REPORT.html outlives the JVM that wrote it and keeps naming the loopback port that JVM
// opened, so a project that finished hours ago still hands back a live-looking address.
// Adjusting an angle on a finished run then posted to a dead port and died on "Connection
// refused", and the edits were lost, because the branch that writes the corrections file was
// never reached. An address is only real while the QuPath that opened it is alive.
test("the bridge address is only real while its QuPath is running", () => {
  const body = server.slice(server.indexOf("    def bridge_url"),
                            server.indexOf("    # -- results"));
  assert.match(body, /if not self\.alive\(\):\s*\n\s*return None/,
    "bridge_url must refuse every action once the run that opened the bridge is gone");
  const guard = body.indexOf("self.alive()");
  assert.ok(guard >= 0 && guard < body.indexOf("self.discover_bridge()"),
    "the check has to come before REPORT.html is read, or a dead port is returned anyway");
  assert.ok(guard < body.indexOf('action == "gate"'),
    "gate falls through to REPORT.html when gate.json is gone, so it needs the same check");
  // Catching the refusal and writing the file instead would hide a bridge that died under a
  // run that is still going, which is a different fault and has to stay visible.
  assert.ok(!/ECONNREFUSED|errno 111/i.test(server),
    "liveness is the question, not the shape of the failure");
});

// Saving into a waiting run and saving to a file are different outcomes for the reader: one
// is applied at the gate in front of them, the other sits on disk until somebody runs again.
// The screen looks the same either way, so the page has to say which happened.
test("a save that went to the file rather than to a run says so", () => {
  assert.ok(server.includes('"via": "file"') && server.includes('answer["via"] = "bridge"'),
    "the server must name which of the two paths it took");
  assert.ok(page.includes('savedVia === "file"'),
    "the page must read that back rather than reporting every save the same way");
  const dictionaries = ["EN", "TH"].map((name) => {
    const start = page.indexOf(`var ${name} = {`);
    return page.slice(start, page.indexOf("\n};", start));
  });
  for (const key of ["savedAnglesForNextRun", "savedCoresForNextRun"]) {
    for (const [index, dictionary] of dictionaries.entries()) {
      assert.ok(dictionary.includes(`${key}:`),
        `${key} is missing from the ${["EN", "TH"][index]} dictionary`);
    }
  }
});

// An IntersectionObserver only delivers callbacks while the document is being rendered, so a
// tab that is still in the background when the arrange stage opens hears nothing and every
// core stays black until the person scrolls. Found the hard way: in a browser pane reporting
// visibilityState "hidden", 35 of 117 cores drew and no amount of scrolling added one more,
// while calling the geometric sweep by hand drew all 117.
test("the slide map draws what is on screen without waiting for an observer", () => {
  assert.match(page, /function renderArrangeInView\(\)/,
    "a direct geometric sweep has to exist alongside the observer");
  assert.match(page, /getBoundingClientRect\(\)[\s\S]{0,400}?renderArrangeCanvas\(canvas\)/,
    "the sweep must decide from the element's own box, not from an intersection callback");
  for (const event of ["scroll", "visibilitychange", "resize"]) {
    assert.ok(page.includes(`"${event}"`), `nothing sweeps again on ${event}`);
  }
  // Scroll does not bubble, so a listener without capture never hears an inner pane move.
  assert.match(page, /addEventListener\("scroll",[\s\S]{0,120}?\}, true\)/,
    "the scroll listener has to run in the capture phase to hear the panes");
});

// The tiles are served immutable for a year. Without a version in the URL a browser that has
// seen one cut keeps it for ever, which is exactly what happened when the tiles were re-cut
// with a corrected display range and the page went on serving the washed-out ones.
test("a tile URL changes when the tiles are cut again", () => {
  assert.match(page, /arrangeTileVersion/,
    "the tile URL needs a version token taken from the manifest");
  assert.match(page, /encodeURIComponent\(arrangeManifest\.createdAt\)/,
    "the token has to come from the manifest stamp, so a fresh cut is a fresh URL");
  assert.match(server, /immutable/,
    "the long cache header is right once the URL carries a version");
});

// Selecting every channel is what a run did before the question existed. Saying so
// explicitly puts a list into both identity hashes and throws away every core a previous
// run already computed, so the all-channels case has to normalise back to silence.
test("an all-channels selection is sent as no selection at all", () => {
  assert.ok(page.includes("picked.length < everything"),
    "the page must omit channels when every one of them is ticked");
  assert.match(server, /channels == list\(range\(count\)\)/,
    "the server must normalise an explicit full list back to None");
  assert.match(server, /index < 0 or index >= count/,
    "an index outside the slide's channel count must be refused, not silently dropped");
});

// Counting edits cannot tell "saved" from "changed since saved". Dragging an already-saved
// core leaves the count at one while the coordinates move, and the gate would then go
// through with the old position while the page said the work was saved.
test("the grid save state compares the edits, not how many there are", () => {
  assert.ok(page.includes("function gridFingerprint()"),
    "the saved state needs a content fingerprint");
  assert.ok(!/gridSynced = gridChanges === gridSaved/.test(page),
    "comparing counts hides a re-drag of an already-saved core");
  assert.ok(page.includes("gridSaved = sentFingerprint"),
    "stamp what was sent, not what the edits look like when the reply lands");
});

// Picking a second slide after a finished run reported the new one as complete and sent the
// reader straight to a results screen belonging to the previous project.
test("attaching a different project does not inherit the last run's state", () => {
  const body = server.slice(server.indexOf("    def attach(self"),
                            server.indexOf("    def stop(self"));
  assert.match(body, /if self\.project != slide\.parent:/,
    "attach must notice it is being pointed somewhere else");
  assert.match(body, /self\.state = "idle"/,
    "a different project starts idle, whatever the last one ended as");
});
