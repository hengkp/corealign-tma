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
  assert.match(html, /Detect, rotate, and crop every core/);
  assert.match(html, /corealign-hero-light\.webp/);
  assert.match(html, /corealign-hero-dark\.webp/);
  assert.match(html, /CoreAlign-TMA-complete-tutorial-1080p\.mp4/);
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
  assert.match(builder, /Workflow/);
  assert.match(builder, /Human review/);
  assert.match(builder, /Tutorial/);
  assert.match(builder, /Build a config/);
  assert.match(builder, /On this page/);
  assert.match(css, /\.siteHeader\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.builderAside\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /:root\[data-theme="dark"\]/);
});
