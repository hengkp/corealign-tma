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
  assert.match(html, /Aligned TMA cores without the repetitive work/);
  assert.match(html, /One script\. Three clear steps/);
  assert.match(html, /START-HERE\.html/);
  assert.match(html, /rotates each core before cropping/);
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
  assert.match(builder, />Home</);
  assert.match(builder, />Theme</);
  assert.match(builder, /Two choices\. One ready-to-use config/);
  assert.match(builder, /Geometry is automatic/);
  assert.match(builder, /Presentation images/);
  assert.match(builder, /Research package/);
  assert.match(builder, /No row count, column count, or core diameter is required/);
  assert.match(builder, /%22autoDetectGeometry%22%3A%20true/);
  assert.match(builder, /%22autoEstimateCoreDiameter%22%3A%20true/);
  assert.match(builder, /%22cropPaddingFactor%22%3A%201.9/);
  assert.match(builder, /Optional channel words/);
  assert.doesNotMatch(builder, /Fallback rows|Fallback columns|Core-size seed|Profile name/);
  assert.match(builder, /download="corealign\.config\.json"/);
  assert.match(builder, /data:application\/json/);
  assert.match(docs, /Start with one file\. Follow one clear dashboard/);
  assert.match(docs, /only workflow HTML file/);
  assert.match(docs, /01-grid/);
  assert.match(docs, /02-orientation/);
  assert.match(docs, /results\/png/);
  assert.match(docs, /results\/ome-tiff/);
  assert.match(docs, /qupath\/project\.qpproj/);
  assert.match(docs, /work\/<\/code> folder stores approved state and per-core checkpoints/);
  assert.match(docs, /TMA orientation 4-C/);
  assert.match(docs, /TMA correction 4-C/);
  assert.match(docs, /Review a rotation/);
  assert.match(docs, /Missing positions use a synthetic black no-core image/);
  assert.match(docs, /All cores, QC pass, Missing, Needs review, and Changes/);
  assert.match(docs, /If no cores need review, All cores opens automatically/);
  assert.match(docs, /click Undo/);
  assert.match(docs, /Editing shows one slider with Reset, Cancel, and Confirm/);
  assert.match(docs, /saves <code>corealign-review-corrections\.json<\/code> automatically beside/);
  assert.match(docs, /Keep QuPath open while reviewing/);
  assert.match(docs, /reveals a normal Save button so no edit is lost/);
  assert.match(docs, /complete array opens in Fit view/);
  assert.match(docs, /corealign-review-corrections\.json/);
  assert.match(docs, /Only edited cores are recalculated/);
  assert.match(docs, /aria-current="location"/);
  assert.doesNotMatch(docs, /[ก-๙—–×·…°]/);
  assert.match(css, /\.siteHeader\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.siteHeader nav\s*\{\s*width:\s*100%;\s*order:\s*3;\s*display:\s*flex/);
  assert.doesNotMatch(css, /\.siteHeader nav\s*\{\s*display:\s*none/);
  assert.match(css, /\.builderAside\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.docsToc\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.docsToc nav a\.active/);
  assert.match(css, /\.automaticStrip\s*\{[\s\S]*?display:\s*grid/);
  assert.match(css, /:root\[data-theme="dark"\]/);
  assert.match(css, /--bg:\s*#ffffff/);
  assert.match(css, /\.themeMoon\s*\{\s*display:\s*none/);
  assert.match(css, /:root\[data-theme="dark"\] \.themeMoon\s*\{\s*display:\s*block/);
});

test("keeps the website runtime lightweight", async () => {
  const packageJson = JSON.parse(await readFile(new URL("package.json", root), "utf8"));
  assert.equal(packageJson.dependencies["drizzle-orm"], undefined);
  assert.equal(packageJson.devDependencies.tailwindcss, undefined);
  assert.equal(packageJson.devDependencies["@tailwindcss/postcss"], undefined);
});

test("ships one guarded production workflow", async () => {
  const [groovy, configText, detectorSource, orientationSource, placeholderJpeg] = await Promise.all([
    readFile(new URL("workflow/CoreAlign.groovy", root), "utf8"),
    readFile(new URL("workflow/corealign.config.json", root), "utf8"),
    readFile(new URL("workflow/embedded/01_build_tma_grid.groovy.src", root), "utf8"),
    readFile(new URL("_archieved/legacy-multi-file-workflow/02_auto_orient_epidermis.groovy", root), "utf8"),
    readFile(new URL("workflow/assets/no-core-placeholder.jpg", root)),
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
  assert.doesNotMatch(groovy, /new File\(completionDir, 'completion_report\.html'\)/);
  assert.match(groovy, /COMPLETE_HUMAN_APPROVED/);
  assert.match(groovy, /START-HERE\.html/);
  assert.doesNotMatch(groovy, /Layout follows the Mintlify DESIGN\.md system and Power Design web principles/);
  assert.doesNotMatch(groovy, /Three clear steps/);
  assert.match(groovy, /CoreAlign quality-control report/);
  assert.match(groovy, /gridZoomIn/);
  assert.match(groovy, /gridViewport/);
  assert.match(groovy, /fitGridImage/);
  assert.match(groovy, /gridZoomValue">Fit/);
  assert.match(groovy, /coreSearch/);
  assert.match(groovy, /coreSearchClear/);
  assert.match(groovy, /data-core-view/);
  assert.match(groovy, /data-filter="changes"/);
  assert.match(groovy, /data-filter="all"[\s\S]*?data-filter="ok"[\s\S]*?data-filter="missing"[\s\S]*?data-filter="review"[\s\S]*?data-filter="changes"/);
  assert.match(groovy, /reviewCountForPage > 0 \? filterStatus != 'review' : false/);
  assert.match(groovy, /no-core-placeholder\.jpg/);
  assert.match(groovy, /boolean previewAvailable = !preview\.isEmpty\(\) &&/);
  assert.match(groovy, /new File\(workflowDir, preview\)\.isFile\(\)/);
  assert.match(groovy, /Synthetic empty placeholder for missing TMA core/);
  assert.match(groovy, /data-card-confirm/);
  assert.match(groovy, /data-confirmed/);
  assert.match(groovy, /confirmed-badge/);
  assert.match(groovy, /reviewState\.confirmed/);
  assert.match(groovy, /confirmed\?"Undo":"Confirm"/);
  assert.match(groovy, /Undo confirmation/);
  assert.match(groovy, /data-edit/);
  assert.match(groovy, /data-edit-reset/);
  assert.match(groovy, /data-edit-cancel/);
  assert.match(groovy, /data-edit-confirm/);
  assert.match(groovy, /data-rotation-adjust/);
  assert.match(groovy, /downloadChanges/);
  assert.match(groovy, /class CoreAlignCorrectionBridge/);
  assert.match(groovy, /InetAddress\.getByName\('127\.0\.0\.1'\)/);
  assert.match(groovy, /data-auto-save-url/);
  assert.match(groovy, /queueAutoSaveCorrections/);
  assert.match(groovy, /fetch\(correctionAutoSaveUrl/);
  assert.match(groovy, /Saved beside START-HERE\.html/);
  assert.match(groovy, /Access-Control-Allow-Private-Network: true/);
  assert.match(groovy, /StandardCopyOption\.ATOMIC_MOVE/);
  assert.match(groovy, /COREALIGN_AUTOSAVE_SELF_TEST_PASSED/);
  assert.doesNotMatch(groovy, /Interactive review board/);
  assert.doesNotMatch(groovy, /reviewModeToggle/);
  assert.doesNotMatch(groovy, /data-core-zoom-in/);
  assert.doesNotMatch(groovy, /data-core-flag/);
  assert.doesNotMatch(groovy, /Export review CSV/);
  assert.match(groovy, /corealign-review-corrections\.json/);
  assert.match(groovy, /rotationAdjustmentDeg/);
  assert.match(groovy, /String\.fromCharCode\(10\)/);
  assert.match(groovy, /Current theme:/);
  assert.match(groovy, /Angle changes save automatically beside START-HERE\.html while QuPath is open/);
  assert.doesNotMatch(groovy, />Save angle changes</);
  assert.match(groovy, /removeLegacyWorkflowHtml/);
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

  const placeholderPayload = groovy.match(/String NO_CORE_PLACEHOLDER_JPEG_BASE64 = '''\n([\s\S]*?)\n'''/);
  assert.ok(placeholderPayload, "No-core placeholder should be embedded");
  const embeddedPlaceholder = Buffer.from(placeholderPayload[1], "base64");
  assert.deepEqual(embeddedPlaceholder, placeholderJpeg);
  assert.equal(placeholderJpeg[0], 0xff);
  assert.equal(placeholderJpeg[1], 0xd8);

  const payload = groovy.match(/def step1 = new EmbeddedWorkflowScript\(name: '01_build_tma_grid\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  assert.ok(payload, "Step 1 payload should be embedded");
  const embeddedDetector = gunzipSync(Buffer.from(payload[1], "base64")).toString("utf8");
  assert.equal(embeddedDetector, detectorSource);
  assert.match(embeddedDetector, /Automatic geometry accepted/);
  assert.match(embeddedDetector, /Automatic core-size estimate/);

  const orientationPayload = groovy.match(/def step2 = new EmbeddedWorkflowScript\(name: '02_auto_orient_epidermis\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  assert.ok(orientationPayload, "Step 2 payload should be embedded");
  const embeddedOrientation = gunzipSync(Buffer.from(orientationPayload[1], "base64")).toString("utf8");
  assert.equal(embeddedOrientation, orientationSource);
  assert.match(embeddedOrientation, /corealign-review-corrections\.json/);
  assert.match(embeddedOrientation, /webCorrectionsByCore/);
  assert.match(embeddedOrientation, /web_manual_rotation/);
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
  assert.match(embeddedReview, /signalP995/);
  assert.match(embeddedReview, /displayGamma = 0\.58d/);
  assert.match(embeddedReview, /new Color\(color\.getRed\(\), color\.getGreen\(\), color\.getBlue\(\), 14\)/);
  assert.doesNotMatch(embeddedReview, /Latest grid QC/);
  assert.doesNotMatch(embeddedReview, /AUTO DETECTED/);

  const orientPayload = groovy.match(/def step2 = new EmbeddedWorkflowScript\(name: '02_auto_orient_epidermis\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  assert.ok(orientPayload, "Step 2 payload should be embedded");
  const embeddedOrient = gunzipSync(Buffer.from(orientPayload[1], "base64")).toString("utf8");
  assert.match(embeddedOrient, /Delivery-only resume/);
  assert.match(embeddedOrient, /EXPORT-ONLY:/);
  assert.match(embeddedOrient, /MIGRATION: Reusing compatible earlier run/);
  assert.match(embeddedOrient, /CURRENT_PROCESSING_HASH/);
  assert.doesNotMatch(embeddedOrient, /setText\([^\n]*run_report\.html/);
  assert.match(embeddedOrient, /ORIENTATION_REVIEW_REQUIRED/);
  assert.match(embeddedOrient, /LATEST_START_HERE\.txt/);
  assert.match(embeddedOrient, /checkpoint_fast_resume/);
  assert.match(embeddedOrient, /Fast checkpoint resume must happen before region refinement/);
  assert.match(embeddedOrient, /corealign\.work\.runBaseDir/);
  assert.match(embeddedOrient, /corealign\.legacy\.runBaseDir/);
  assert.match(embeddedOrient, /Web review corrections loaded/);
  assert.match(embeddedOrient, /web_manual_rotation/);
  assert.match(embeddedOrient, /webRotationAdjustmentDeg/);
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
