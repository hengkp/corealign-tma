import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

const root = new URL("../", import.meta.url);

test("exports the home page and config builder", async () => {
  await access(new URL("out/index.html", root));
  await access(new URL("out/config-builder/index.html", root));
});

test("keeps the public website concise and English only", async () => {
  const html = await readFile(new URL("out/index.html", root), "utf8");
  assert.match(html, /Prepare TMA cores for analysis and presentation/);
  assert.match(html, /corealign-hero-light\.webp/);
  assert.match(html, /corealign-hero-dark\.webp/);
  assert.match(html, /CoreAlign-TMA-tutorial-v3-1080p\.mp4/);
  assert.match(html, /required preflight check/);
  assert.doesNotMatch(html, /[ก-๙]/);
  assert.doesNotMatch(html, /[—–×·…°]/);
});

test("exports generated example images", async () => {
  await access(new URL("out/images/synthetic-array-hero.webp", root));
  await access(new URL("out/images/synthetic-core-grid.webp", root));
  await access(new URL("out/images/corealign-hero-light.webp", root));
  await access(new URL("out/images/corealign-hero-dark.webp", root));
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
  assert.match(builder, /Tutorial/);
  assert.match(builder, /Safety/);
  assert.match(builder, /Build config/);
  assert.match(builder, /Create your TMA config in under a minute/);
  assert.match(builder, /Presentation images/);
  assert.match(builder, /Advanced settings/);
  assert.match(builder, /download="corealign\.config\.json"/);
  assert.match(builder, /data:application\/json/);
  assert.doesNotMatch(builder, /On this page/);
  assert.match(css, /\.siteHeader\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.builderAside\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.presetButtons button\s*\{[\s\S]*?min-width:\s*0/);
  assert.match(css, /:root\[data-theme="dark"\]/);
});

test("keeps the website runtime lightweight", async () => {
  const packageJson = JSON.parse(await readFile(new URL("package.json", root), "utf8"));
  assert.equal(packageJson.dependencies["drizzle-orm"], undefined);
  assert.equal(packageJson.devDependencies.tailwindcss, undefined);
  assert.equal(packageJson.devDependencies["@tailwindcss/postcss"], undefined);
});

test("ships one guarded production workflow", async () => {
  const [groovy, configText] = await Promise.all([
    readFile(new URL("workflow/CoreAlign.groovy", root), "utf8"),
    readFile(new URL("workflow/corealign.config.json", root), "utf8"),
  ]);
  const config = JSON.parse(configText);
  const profile = config.profiles.skin_18x7;

  assert.match(groovy, /PREFLIGHT_BLOCKED: multiple config files/);
  assert.match(groovy, /STRUCTURAL QC:/);
  assert.match(groovy, /TECHNICAL DETECTION VALIDATION:/);
  assert.equal(profile.grid.rows, 18);
  assert.equal(profile.grid.columns, 7);
  assert.equal(profile.grid.coreDiameterMM, 0.6);
  assert.equal(profile.grid.showAdvancedDialog, false);
  assert.equal(profile.detection.requireEveryRowAndColumn, true);
});
