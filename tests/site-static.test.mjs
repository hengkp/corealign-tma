import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";
import { gunzipSync } from "node:zlib";

const root = new URL("../", import.meta.url);

test("exports the home page, config builder, and documentation", async () => {
  await access(new URL("out/index.html", root));
  await access(new URL("out/config-builder/index.html", root));
  await access(new URL("out/docs/index.html", root));
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
  const [home, builder, docs, css] = await Promise.all([
    readFile(new URL("out/index.html", root), "utf8"),
    readFile(new URL("out/config-builder/index.html", root), "utf8"),
    readFile(new URL("out/docs/index.html", root), "utf8"),
    readFile(new URL("app/globals.css", root), "utf8"),
  ]);

  assert.match(home, /Toggle color theme/);
  assert.match(builder, /Toggle color theme/);
  assert.match(builder, /How it works/);
  assert.match(builder, /Outputs/);
  assert.doesNotMatch(builder, />Tutorial</);
  assert.match(builder, /Documentation/);
  assert.match(builder, /Build config/);
  assert.match(builder, /Choose two things\. CoreAlign handles the rest/);
  assert.match(builder, /Presentation images/);
  assert.match(builder, /run the same script again/);
  assert.match(builder, /reuses every accepted grid, rotation, and crop checkpoint/);
  assert.match(builder, /analysis-ready QuPath project/);
  assert.match(builder, /Detection is fully automatic/);
  assert.match(builder, /No array geometry or punch size is required/);
  assert.match(builder, /%22autoDetectGeometry%22%3A%20true/);
  assert.match(builder, /%22autoEstimateCoreDiameter%22%3A%20true/);
  assert.match(builder, /%22cropPaddingFactor%22%3A%201.9/);
  assert.match(builder, /Channel name helper/);
  assert.doesNotMatch(builder, /Fallback rows|Fallback columns|Core-size seed|Profile name/);
  assert.match(builder, /download="corealign\.config\.json"/);
  assert.match(builder, /data:application\/json/);
  assert.doesNotMatch(builder, /On this page/);
  assert.match(docs, /One project folder\. Clear results at every step/);
  assert.match(docs, /Documentation table of contents/);
  assert.match(docs, /qc\/01-grid/);
  assert.match(docs, /qc\/02-orientation/);
  assert.match(docs, /results\/png/);
  assert.match(docs, /results\/ome-tiff/);
  assert.match(docs, /qupath\/project\.qpproj/);
  assert.match(docs, /work\/<\/code> folder stores approved state and per-core checkpoints/);
  assert.match(docs, /One annotation format/);
  assert.match(docs, /TMA orientation 4-C/);
  assert.match(docs, /TMA correction 4-C/);
  assert.match(docs, /Why the ROI is now an ellipse/);
  assert.doesNotMatch(docs, /[ก-๙—–×·…°]/);
  assert.match(css, /\.siteHeader\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.builderAside\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.docsToc\s*\{[\s\S]*?position:\s*sticky/);
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
  assert.match(groovy, /CoreAlign adopted automatic geometry/);
  assert.match(groovy, /STRUCTURAL QC:/);
  assert.match(groovy, /TECHNICAL DETECTION VALIDATION:/);
  assert.match(groovy, /not_configured; human_grid_approval_required/);
  assert.match(groovy, /Keep computation and delivery identities separate/);
  assert.match(groovy, /tma\.config\.processingHash/);
  assert.match(groovy, /tma\.config\.outputHash/);
  assert.match(groovy, /compatibleLegacyProfileHashes/);
  assert.match(groovy, /Research-package upgrade reused all accepted core transforms/);
  assert.match(groovy, /CoreAlign run finished: review required/);
  assert.match(groovy, /This is a planned review pause, not an error/);
  assert.match(groovy, /tma\.analysisProject\.status/);
  assert.match(groovy, /completion_report\.html/);
  assert.match(groovy, /COMPLETE_HUMAN_APPROVED/);
  assert.match(groovy, /START-HERE\.html/);
  assert.match(groovy, /PROJECT-README\.txt/);
  assert.match(groovy, /qc\/01-grid/);
  assert.match(groovy, /qc\/02-orientation/);
  assert.match(groovy, /results\/png/);
  assert.match(groovy, /results\/ome-tiff/);
  assert.match(groovy, /corealign\.work\.runBaseDir/);
  assert.match(groovy, /Published \$\{published\} easy-to-find project file/);
  assert.doesNotMatch(groovy, /knownReferenceLayouts|AUTO_GEOMETRY_REFERENCE_OVERRIDE/);
  assert.doesNotMatch(groovy, /'TMA_0\.6mm_7_backsub':/);
  assert.equal(config.schemaVersion, 2);
  assert.equal(profile.grid.rows, undefined);
  assert.equal(profile.grid.columns, undefined);
  assert.equal(profile.grid.coreDiameterMM, undefined);
  assert.equal(profile.grid.showAdvancedDialog, false);
  assert.equal(profile.grid.autoDetectGeometry, true);
  assert.equal(profile.grid.autoEstimateCoreDiameter, true);
  assert.equal(profile.grid.cropPaddingFactor, 1.9);
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
  assert.match(embeddedDetector, /MAX_PRESENT_DIAM_SPACING_FRACTION = 0\.80/);

  const reviewPayload = groovy.match(/def step3 = new EmbeddedWorkflowScript\(name: '03_review_correct_and_approve_grid\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  assert.ok(reviewPayload, "Step 3 payload should be embedded");
  const embeddedReview = gunzipSync(Buffer.from(reviewPayload[1], "base64")).toString("utf8");
  assert.match(embeddedReview, /fitted_lattice/);
  assert.match(embeddedReview, /row labels remain aligned/);
  assert.match(embeddedReview, /POST_CORRECTION_REVIEW_REQUIRED/);
  assert.match(embeddedReview, /Inspect the updated circles, labels and connecting lines/);
  assert.match(embeddedReview, /Always refresh the whole-slide detection QC/);
  assert.match(embeddedReview, /_grid_qc_latest\.png/);
  assert.match(embeddedReview, /correctionsAppliedThisRun/);
  assert.match(embeddedReview, /LATEST_GRID_QC_EXPORTED/);

  const orientPayload = groovy.match(/def step2 = new EmbeddedWorkflowScript\(name: '02_auto_orient_epidermis\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  assert.ok(orientPayload, "Step 2 payload should be embedded");
  const embeddedOrient = gunzipSync(Buffer.from(orientPayload[1], "base64")).toString("utf8");
  assert.match(embeddedOrient, /Delivery-only resume/);
  assert.match(embeddedOrient, /EXPORT-ONLY:/);
  assert.match(embeddedOrient, /MIGRATION: Reusing compatible earlier run/);
  assert.match(embeddedOrient, /CURRENT_PROCESSING_HASH/);
  assert.match(embeddedOrient, /run_report\.html/);
  assert.match(embeddedOrient, /ORIENTATION_REVIEW_REQUIRED/);
  assert.match(embeddedOrient, /LATEST_RUN_REPORT\.txt/);
  assert.match(embeddedOrient, /checkpoint_fast_resume/);
  assert.match(embeddedOrient, /Fast checkpoint resume must happen before region refinement/);
  assert.match(embeddedOrient, /corealign\.work\.runBaseDir/);
  assert.match(embeddedOrient, /corealign\.legacy\.runBaseDir/);
  assert.match(embeddedOrient, /ROIs\.createEllipseROI\(cx - radius, cy - radius/);
  assert.match(embeddedOrient, /ann\.getMetadata\(\)/);
  assert.match(embeddedOrient, /ann\.setName\("TMA orientation \$\{coreName\}"\)/);
  assert.match(embeddedOrient, /cropOverride\.obj\.setName\("\$\{CROP_OVERRIDE_CLASS_NAME\} \$\{coreName\}"\)/);
  assert.match(embeddedOrient, /override\.obj\.setName\("\$\{OVERRIDE_CLASS_NAME\} \$\{coreName\}"\)/);
  assert.doesNotMatch(embeddedOrient, /ROIs\.createLineROI/);
  assert.doesNotMatch(embeddedOrient, /ann\.setName\("\$\{coreName\} epidermis"\)/);
  assert.doesNotMatch(embeddedOrient.match(/String coreSignature = sha256\(\[[\s\S]*?\]\s*\.join\('\|'\)\)/)?.[0] ?? "", /SAVE_ROTATED_MULTICHANNEL_OME_TIFF/);

  for (const [step, name, sourcePath] of [
    ["2", "02_auto_orient_epidermis.groovy", "_archieved/legacy-multi-file-workflow/02_auto_orient_epidermis.groovy"],
    ["3", "03_review_correct_and_approve_grid.groovy", "_archieved/legacy-multi-file-workflow/03_review_correct_and_approve_grid.groovy"],
    ["4", "04_restore_approved_grid.groovy", "_archieved/legacy-multi-file-workflow/04_restore_approved_grid.groovy"],
    ["5", "05_finalize_orientation_review.groovy", "_archieved/legacy-multi-file-workflow/05_finalize_orientation_review.groovy"],
    ["6", "06_export_presentation_package.groovy", "_archieved/legacy-multi-file-workflow/06_export_presentation_package.groovy"],
    ["7", "07_build_qupath_analysis_project.groovy", "_archieved/legacy-multi-file-workflow/07_build_qupath_analysis_project.groovy"],
  ]) {
    const match = groovy.match(new RegExp(`def step${step} = new EmbeddedWorkflowScript\\(name: '${name.replaceAll(".", "\\.")}', payload: '''\\n([\\s\\S]*?)\\n'''\\)`));
    assert.ok(match, `Step ${step} payload should be embedded`);
    const [embeddedSource, canonicalSource] = await Promise.all([
      Promise.resolve(gunzipSync(Buffer.from(match[1], "base64")).toString("utf8")),
      readFile(new URL(sourcePath, root), "utf8"),
    ]);
    assert.equal(embeddedSource, canonicalSource, `Step ${step} should match its canonical source`);
  }

  const projectPayload = groovy.match(/def step7 = new EmbeddedWorkflowScript\(name: '07_build_qupath_analysis_project\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  const embeddedProject = gunzipSync(Buffer.from(projectPayload[1], "base64")).toString("utf8");
  assert.match(embeddedProject, /rotated_multichannel_ome_tiff/);
  assert.match(embeddedProject, /RESEARCH_OUTPUT_REQUIRED/);
  assert.match(embeddedProject, /CoreAlign row/);
  assert.match(embeddedProject, /project\.qpproj/);
  assert.match(embeddedProject, /corealign\.qupath\.projectDir/);
});
