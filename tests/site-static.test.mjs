import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";
import { gunzipSync } from "node:zlib";

const root = new URL("../", import.meta.url);

test("exports the home page and config builder", async () => {
  await access(new URL("out/index.html", root));
  await access(new URL("out/config-builder/index.html", root));
});

test("keeps the public website concise and English only", async () => {
  const html = await readFile(new URL("out/index.html", root), "utf8");
  assert.match(html, /Turn one TMA slide into aligned core images/);
  assert.match(html, /corealign-hero-light\.webp/);
  assert.match(html, /corealign-hero-dark\.webp/);
  assert.match(html, /corealign-workflow-v1\.webp/);
  assert.match(html, /finds the array, core size, rows, columns, and useful channels/);
  assert.doesNotMatch(html, /<video|\.mp4|Watch or download|Video tutorial/);
  assert.doesNotMatch(html, /[ก-๙]/);
  assert.doesNotMatch(html, /[—–×·…°]/);
});

test("exports generated example images", async () => {
  await access(new URL("out/images/synthetic-array-hero.webp", root));
  await access(new URL("out/images/synthetic-core-grid.webp", root));
  await access(new URL("out/images/corealign-hero-light.webp", root));
  await access(new URL("out/images/corealign-hero-dark.webp", root));
  await access(new URL("out/images/corealign-workflow-v1.webp", root));
});

test("includes persistent navigation and a theme control on both pages", async () => {
  const [home, builder, css] = await Promise.all([
    readFile(new URL("out/index.html", root), "utf8"),
    readFile(new URL("out/config-builder/index.html", root), "utf8"),
    readFile(new URL("app/globals.css", root), "utf8"),
  ]);

  assert.match(home, /Toggle color theme/);
  assert.match(builder, /Toggle color theme/);
  assert.match(builder, /How it works/);
  assert.match(builder, /Outputs/);
  assert.doesNotMatch(builder, />Tutorial</);
  assert.match(builder, /Safety/);
  assert.match(builder, /Build config/);
  assert.match(builder, /Choose two things\. CoreAlign handles the rest/);
  assert.match(builder, /Presentation images/);
  assert.match(builder, /Detection is fully automatic/);
  assert.match(builder, /No array geometry or punch size is required/);
  assert.match(builder, /%22autoDetectGeometry%22%3A%20true/);
  assert.match(builder, /%22autoEstimateCoreDiameter%22%3A%20true/);
  assert.match(builder, /Channel name helper/);
  assert.doesNotMatch(builder, /Fallback rows|Fallback columns|Core-size seed|Profile name/);
  assert.match(builder, /download="corealign\.config\.json"/);
  assert.match(builder, /data:application\/json/);
  assert.doesNotMatch(builder, /On this page/);
  assert.match(css, /\.siteHeader\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.builderAside\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.autoGeometryCard\s*\{[\s\S]*?display:\s*grid/);
  assert.match(css, /:root\[data-theme="dark"\]/);
});

test("keeps the website runtime lightweight", async () => {
  const packageJson = JSON.parse(await readFile(new URL("package.json", root), "utf8"));
  assert.equal(packageJson.dependencies["drizzle-orm"], undefined);
  assert.equal(packageJson.devDependencies.tailwindcss, undefined);
  assert.equal(packageJson.devDependencies["@tailwindcss/postcss"], undefined);
});

test("ships one guarded production workflow", async () => {
  const [groovy, configText, detectorSource] = await Promise.all([
    readFile(new URL("workflow/CoreAlign.groovy", root), "utf8"),
    readFile(new URL("workflow/corealign.config.json", root), "utf8"),
    readFile(new URL("workflow/embedded/01_build_tma_grid.groovy.src", root), "utf8"),
  ]);
  const config = JSON.parse(configText);
  const profile = config.profiles.automatic;

  assert.match(groovy, /PREFLIGHT_BLOCKED: multiple config files/);
  assert.match(groovy, /Gson represents JSON integers as doubles/);
  assert.match(groovy, /TMA runtime config verified/);
  assert.match(groovy, /known slide.*requires/);
  assert.match(groovy, /CoreAlign adopted automatic geometry/);
  assert.match(groovy, /AUTO_GEOMETRY_REFERENCE_OVERRIDE/);
  assert.match(groovy, /STRUCTURAL QC:/);
  assert.match(groovy, /TECHNICAL DETECTION VALIDATION:/);
  assert.equal(config.schemaVersion, 2);
  assert.equal(profile.grid.rows, undefined);
  assert.equal(profile.grid.columns, undefined);
  assert.equal(profile.grid.coreDiameterMM, undefined);
  assert.equal(profile.grid.showAdvancedDialog, false);
  assert.equal(profile.grid.autoDetectGeometry, true);
  assert.equal(profile.grid.autoEstimateCoreDiameter, true);
  assert.equal(profile.detection.requireEveryRowAndColumn, true);
  assert.equal(profile.detection.autoRetryMergedChannels, true);

  const payload = groovy.match(/def step1 = new EmbeddedWorkflowScript\(name: '01_build_tma_grid\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  assert.ok(payload, "Step 1 payload should be embedded");
  const embeddedDetector = gunzipSync(Buffer.from(payload[1], "base64")).toString("utf8");
  assert.equal(embeddedDetector, detectorSource);
  assert.match(embeddedDetector, /Automatic geometry accepted/);
  assert.match(embeddedDetector, /Automatic core-size estimate/);
  assert.match(embeddedDetector, /Automatic merged-channel retry/);
  assert.match(embeddedDetector, /AUTO_GEOMETRY_BLOCKED/);
});
