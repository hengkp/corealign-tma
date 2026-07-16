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
  assert.match(html, /Aligned TMA cores\. Less repetitive work/);
  assert.match(html, /One script\. Three clear steps/);
  assert.match(html, /REPORT\.html/);
  assert.match(html, /Detect, rotate, and crop every core in QuPath/);
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
  await access(new URL("out/images/corealign-hero-v2-light.webp", root));
  await access(new URL("out/images/corealign-hero-v2-dark.webp", root));
});

test("shows the complete hero artwork without an animation overlay", async () => {
  const [html, css] = await Promise.all([
    readFile(new URL("out/index.html", root), "utf8"),
    readFile(new URL("app/globals.css", root), "utf8"),
  ]);
  assert.doesNotMatch(html, /corealign-flow|motionBadge|lottie/i);
  assert.match(css, /\.heroArtwork > img \{[^}]*object-fit: contain;/s);
  assert.match(css, /\.heroArtwork \{[^}]*aspect-ratio: 1693 \/ 929;/s);
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
  assert.match(builder, /Optional marker channels/);
  assert.match(builder, /Presentation PNG markers/);
  assert.match(builder, /shared_slide_range/);
  assert.doesNotMatch(builder, /Fallback rows|Fallback columns|Core-size seed|Profile name/);
  assert.match(builder, /download="corealign\.config\.json"/);
  assert.match(builder, /data:application\/json/);
  assert.match(docs, /Prepare TMA cores in one repeatable workflow/);
  assert.match(docs, /only workflow HTML file/);
  assert.match(docs, /01-grid/);
  assert.match(docs, /02-orientation/);
  assert.match(docs, /results\/png/);
  assert.match(docs, /results\/ome-tiff/);
  assert.match(docs, /One display range is shared by every core/);
  assert.match(docs, /Presentation PNG runs process up to two cores at once/);
  assert.match(docs, /Do not measure intensity from PNG files/);
  assert.match(docs, /qupath\/project\.qpproj/);
  assert.match(docs, /work\/<\/code> folder stores the accepted grid, rotation, crop, and per-core checkpoints/);
  assert.match(docs, /TMA orientation 4-C/);
  assert.match(docs, /TMA correction 4-C/);
  assert.match(docs, /Review a rotation/);
  assert.match(docs, /Missing positions use a synthetic black no-core image/);
  assert.match(docs, /All cores, QC pass, Missing, Needs review, and Changes/);
  assert.match(docs, /save panel stays at the top of Orientation QC/);
  assert.match(docs, /Saving works in every supported browser/);
  assert.match(docs, /Safari, Chrome, Edge, and Firefox/);
  assert.match(docs, /AppHub also saves directly/);
  assert.match(docs, /complete array opens in Fit view/);
  assert.match(docs, /corealign-review-corrections\.json/);
  assert.match(docs, /reprocesses only the changed core/);
  assert.match(docs, /Presentation or Research/);
  assert.match(docs, /report updates <code>corealign\.config\.json<\/code> through QuPath, AppHub, or a connected project folder/);
  assert.match(docs, /using the accepted transforms/);
  assert.match(docs, /aria-current="location"/);
  assert.match(docs, /Back to top/);
  assert.match(docs, /sectionCoral/);
  assert.match(docs, /sectionMint/);
  assert.match(docs, /sectionPurple/);
  assert.match(docs, /sectionCyan/);
  assert.match(docs, /sectionYellow/);
  assert.doesNotMatch(docs, /[ก-๙—–×·…°]/);
  assert.match(css, /\.siteHeader\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.siteHeader nav\s*\{\s*width:\s*100%;\s*order:\s*3;\s*display:\s*flex/);
  assert.doesNotMatch(css, /\.siteHeader nav\s*\{\s*display:\s*none/);
  assert.match(css, /\.builderAside\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.docsToc\s*\{[\s\S]*?position:\s*sticky/);
  assert.match(css, /\.docsToc\s*\{[\s\S]*?grid-column:\s*2/);
  assert.match(css, /\.tocTop\s*\{/);
  assert.match(css, /\.docsToc nav::before\s*\{/);
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
  const [groovy, configText, detectorSource, orientationSource, gridReviewSource, finalReviewSource, placeholderJpeg] = await Promise.all([
    readFile(new URL("workflow/CoreAlign.groovy", root), "utf8"),
    readFile(new URL("workflow/corealign.config.json", root), "utf8"),
    readFile(new URL("workflow/embedded/01_build_tma_grid.groovy.src", root), "utf8"),
    readFile(new URL("_archieved/legacy-multi-file-workflow/02_auto_orient_epidermis.groovy", root), "utf8"),
    readFile(new URL("_archieved/legacy-multi-file-workflow/03_review_correct_and_approve_grid.groovy", root), "utf8"),
    readFile(new URL("_archieved/legacy-multi-file-workflow/05_finalize_orientation_review.groovy", root), "utf8"),
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
  assert.match(groovy, /tma\.presentation\.channelTokens/);
  assert.match(groovy, /presentationRendering: presentationConfig/);
  assert.match(groovy, /presentationRendererVersion: 'slide-color-2\.0'/);
  assert.match(groovy, /compatibleLegacyProfileHashes/);
  assert.match(groovy, /Research-package upgrade reused all accepted core transforms/);
  assert.match(groovy, /CoreAlign run finished: review required/);
  assert.doesNotMatch(groovy, /new Date\(\)\.format\(/);
  assert.match(groovy, /This is a planned review pause, not an error/);
  assert.match(groovy, /CoreAlign \| Run summary/);
  assert.match(groovy, /What CoreAlign will do/);
  assert.match(groovy, /Click OK to start this run\. Click Cancel to change nothing/);
  assert.match(gridReviewSource, /CoreAlign \| Confirm missing positions/);
  assert.match(gridReviewSource, /CoreAlign \| Approve grid and continue/);
  assert.match(finalReviewSource, /CoreAlign \| Approve rotated cores/);
  assert.doesNotMatch(gridReviewSource, /I inspected and confirm all positions marked missing/);
  assert.doesNotMatch(gridReviewSource, /APPROVE this exact grid for epidermis orientation/);
  assert.match(groovy, /tma\.analysisProject\.status/);
  assert.doesNotMatch(groovy, /new File\(completionDir, 'completion_report\.html'\)/);
  assert.match(groovy, /COMPLETE_HUMAN_APPROVED/);
  assert.match(groovy, /REPORT\.html/);
  assert.doesNotMatch(groovy, /Layout follows the Mintlify DESIGN\.md system and Power Design web principles/);
  assert.doesNotMatch(groovy, /Three clear steps/);
  assert.match(groovy, /CoreAlign quality-control report/);
  assert.match(groovy, /--accent:#4285f4/);
  assert.match(groovy, /--success:#5bcb75/);
  assert.match(groovy, /--success-text:#137333/);
  assert.match(groovy, /--danger:#ff6b62/);
  assert.match(groovy, /--danger-text:#c5221f/);
  assert.match(groovy, /--warning:#fbbc04/);
  assert.match(groovy, /--warning-bg:#fff8d8/);
  assert.match(groovy, /--yellow:#fbbc04/);
  assert.match(groovy, /--bg:#202124/);
  assert.match(groovy, /Bright editorial report/);
  assert.match(groovy, /body\{background:var\(--bg\);background-image:none\}/);
  assert.match(groovy, /gridZoomIn/);
  assert.match(groovy, /gridViewport/);
  assert.match(groovy, /fitGridImage/);
  assert.match(groovy, /gridZoomValue">Fit/);
  assert.match(groovy, /coreSearch/);
  assert.match(groovy, /coreSearchClear/);
  assert.match(groovy, /data-core-view/);
  assert.match(groovy, /data-filter="changes"/);
  assert.match(groovy, /data-filter="ok"[^}]*?var\(--success-bg\)/);
  assert.match(groovy, /data-filter="missing"[^}]*?var\(--danger-bg\)/);
  assert.match(groovy, /data-filter="review"[^}]*?var\(--warning-bg\)/);
  assert.match(groovy, /data-filter="changes"[^}]*?var\(--purple-bg\)/);
  assert.match(groovy, /data-filter="all"[\s\S]*?data-filter="ok"[\s\S]*?data-filter="missing"[\s\S]*?data-filter="review"[\s\S]*?data-filter="changes"/);
  assert.match(groovy, /reviewCountForPage > 0 \? filterStatus != 'review' : false/);
  assert.match(groovy, /no-core-placeholder\.jpg/);
  assert.match(groovy, /boolean previewAvailable = !preview\.isEmpty\(\) &&/);
  assert.match(groovy, /new File\(workflowDir, preview\)\.isFile\(\)/);
  assert.match(groovy, /Synthetic empty placeholder for missing TMA core/);
  assert.match(groovy, /data-card-confirm/);
  assert.match(groovy, /data-confirmed/);
  assert.doesNotMatch(groovy, /<span class="confirmed-badge"/);
  assert.doesNotMatch(groovy, /<span class="change-badge"/);
  assert.match(groovy, /data-edit>Edit<\/button><button[^>]+data-card-confirm>Confirm/);
  assert.match(groovy, /reviewState\.confirmed/);
  assert.match(groovy, /confirmed\?"Undo":"Confirm"/);
  assert.match(groovy, /Undo confirmation/);
  assert.match(groovy, /data-edit/);
  assert.match(groovy, /data-edit-reset/);
  assert.match(groovy, /data-edit-cancel/);
  assert.match(groovy, /data-edit-confirm/);
  assert.match(groovy, /data-edit-confirm>Update<\/button>/);
  assert.match(groovy, /data-confirmed="true"\] \.confirm-button\{border-color:var\(--warning\);background:var\(--warning-bg\)/);
  assert.match(groovy, /confidence-high/);
  assert.match(groovy, /confidence-medium/);
  assert.match(groovy, /confidence-low/);
  assert.match(groovy, /deg\.<\/span><span>Residual/);
  assert.match(groovy, /grid-template-columns:repeat\(3,minmax\(0,1fr\)\)/);
  assert.match(groovy, /width:min\(100%,180px\)/);
  assert.match(groovy, /data-rotation-adjust/);
  assert.match(groovy, /downloadChanges/);
  assert.match(groovy, /confirmAllPass/);
  assert.match(groovy, /Confirm all QC pass/);
  assert.match(groovy, /savedModal/);
  assert.match(groovy, /Your changes are saved beside REPORT\.html/);
  assert.match(groovy, /class CoreAlignCorrectionBridge/);
  assert.match(groovy, /InetAddress\.getByName\('127\.0\.0\.1'\)/);
  assert.match(groovy, /data-auto-save-url/);
  assert.match(groovy, /data-open-qupath-url/);
  assert.match(groovy, /outputEndpoint/);
  assert.match(groovy, /saveOutputMode/);
  assert.match(groovy, /data-output-mode-choice="presentation"/);
  assert.match(groovy, /data-output-mode-choice="research"/);
  assert.match(groovy, /Switch to Research\?/);
  assert.match(groovy, /Save Research/);
  assert.match(groovy, /Go to QuPath and run CoreAlign again to create multichannel OME-TIFF files/);
  assert.match(groovy, /Angle changes saved/);
  assert.match(groovy, /The selected folder does not contain REPORT\.html/);
  assert.match(groovy, /def projectPathString/);
  assert.match(groovy, /value\.strings instanceof List/);
  assert.match(groovy, /corealign\.reportOnly/);
  assert.match(groovy, /Research saved\. Run CoreAlign again to create OME-TIFF files/);
  assert.match(groovy, /orientation\.saveRotatedMultichannelOmeTiff = mode == 'research'/);
  assert.match(groovy, /bringQuPathToFront/);
  assert.match(groovy, /QuPathGUI\.getInstance/);
  assert.match(groovy, /stage\.toFront/);
  assert.match(groovy, /queueAutoSaveCorrections/);
  assert.match(groovy, /fetch\(correctionAutoSaveUrl/);
  assert.match(groovy, /window\.showDirectoryPicker/);
  assert.match(groovy, /window\.corealignAppHub/);
  assert.match(groovy, /indexedDB\.open\("corealign-report",1\)/);
  assert.match(groovy, /Saved\. Run CoreAlign again when you finish reviewing/);
  assert.match(groovy, /Download one correction file when you finish reviewing/);
  assert.doesNotMatch(groovy, /showAutoSaveErrorBriefly/);
  assert.match(groovy, /top:calc\(var\(--header\) \+ 12px\)/);
  assert.doesNotMatch(groovy, /projectHtmlEscape\(record\.regionStatus/);
  assert.doesNotMatch(groovy, /record\.reasons\.join/);
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
  assert.match(groovy, /Correct\? Click Confirm\. Wrong\? Click Edit, adjust the angle, then Update/);
  assert.ok(groovy.indexOf('id="changeBar"') < groovy.indexOf('class="filter-tools section-block"'));
  assert.match(groovy, /card\.dataset\.manualRotation=String\(applied\)/);
  assert.match(groovy, /delete reviewState\.angles\[key\]/);
  assert.match(groovy, /if\(Math\.abs\(angle\)>=\.05\)/);
  assert.doesNotMatch(groovy, />Save angle changes</);
  assert.match(groovy, /crumb-separator/);
  assert.match(groovy, /data-nav="results">Results<\/button><\/nav><div class="header-actions"><button class="help-button"/);
  assert.doesNotMatch(groovy, /data-next|historyBack|historyForward|class="pager"/);
  assert.doesNotMatch(groovy, /Back and Forward controls|Previous and Next buttons/);
  assert.match(groovy, /removeLegacyWorkflowHtml/);
  assert.match(groovy, /\['START-HERE\.html', 'PROJECT-README\.txt', 'READ-ME-FIRST\.md'\]/);
  assert.doesNotMatch(groovy, /new File\(workflowDir, 'PROJECT-README\.txt'\)/);
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
  assert.equal(profile.orientation.parallelWorkers, 2);
  assert.deepEqual(profile.presentation.channelTokens, []);
  assert.equal(profile.presentation.gradeMode, "shared_slide_range");
  assert.equal(profile.presentation.highPercentile, 0.998);
  assert.match(orientationSource, /nuclear-white-black-1\.0/);
  assert.match(orientationSource, /def makeQcReviewRgb/);
  assert.match(orientationSource, /qcPreviewRendererVersion/);
  assert.match(orientationSource, /'qc\/02-orientation\/' \+ r\.preview\.toString\(\)/);

  const placeholderPayload = groovy.match(/String NO_CORE_PLACEHOLDER_JPEG_BASE64 = '''\n([\s\S]*?)\n'''/);
  assert.ok(placeholderPayload, "No-core placeholder should be embedded");
  const embeddedPlaceholder = Buffer.from(placeholderPayload[1], "base64");
  assert.deepEqual(embeddedPlaceholder, placeholderJpeg);
  assert.equal(placeholderJpeg[0], 0xff);
  assert.equal(placeholderJpeg[1], 0xd8);

  const reportScript = groovy.match(/html\.append\('''<script>(\(function\(\)\{[\s\S]*?\}\)\(\);)<\/script><\/body><\/html>'''\)/);
  assert.ok(reportScript, "REPORT browser script should be embedded");
  assert.doesNotThrow(() => new Function(reportScript[1]));

  const payload = groovy.match(/def step1 = new EmbeddedWorkflowScript\(name: '01_build_tma_grid\.groovy', payload: '''\n([\s\S]*?)\n'''\)/);
  assert.ok(payload, "Step 1 payload should be embedded");
  const embeddedDetector = gunzipSync(Buffer.from(payload[1], "base64")).toString("utf8");
  assert.equal(embeddedDetector, detectorSource);
  assert.match(embeddedDetector, /Automatic geometry accepted/);
  assert.match(embeddedDetector, /Automatic core-size estimate/);
  assert.match(embeddedDetector, /renderSlideQcRgb/);
  assert.match(embeddedDetector, /Grid QC white-on-black nuclear channels/);
  assert.match(embeddedDetector, /pixels\[i\] = \(white << 16\) \| \(white << 8\) \| white/);

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
  assert.match(embeddedReview, /renderSlideQcRgb/);
  assert.match(embeddedReview, /Grid QC white-on-black nuclear channels/);
  assert.match(embeddedReview, /pixels\[i\] = \(white << 16\) \| \(white << 8\) \| white/);
  assert.doesNotMatch(embeddedReview, /getRGBThumbnail/);
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
  assert.match(embeddedOrient, /LATEST_REPORT\.txt/);
  assert.doesNotMatch(embeddedOrient, /START-HERE\.html/);
  assert.match(embeddedOrient, /checkpoint_fast_resume/);
  assert.match(embeddedOrient, /Fast checkpoint resume must happen before region refinement/);
  assert.match(embeddedOrient, /corealign\.work\.runBaseDir/);
  assert.match(embeddedOrient, /corealign\.legacy\.runBaseDir/);
  assert.match(embeddedOrient, /Web review corrections loaded/);
  assert.match(embeddedOrient, /web_manual_rotation/);
  assert.match(embeddedOrient, /webRotationAdjustmentDeg/);
  assert.match(embeddedOrient, /makeDisplayRgb/);
  assert.match(embeddedOrient, /shared slide-level ranges/);
  assert.match(embeddedOrient, /display_ranges\.json/);
  assert.match(embeddedOrient, /Use the OME-TIFF files for quantitative analysis/);
  assert.match(embeddedOrient, /Orientation workers:/);
  assert.match(embeddedOrient, /Executors\.newFixedThreadPool\(PARALLEL_WORKERS\)/);
  assert.match(embeddedOrient, /Keep all QuPath hierarchy mutations on the script thread/);
  assert.doesNotMatch(embeddedOrient, /base\.getAbsolutePath\(\)/);
  assert.match(embeddedOrient, /ROIs\.createEllipseROI\(\(r\.centerX as double\) - diameter \/ 2\.0d/);
  assert.match(embeddedOrient, /ann\.getMetadata\(\)/);
  assert.match(embeddedOrient, /ann\.setName\("TMA orientation \$\{r\.core\}"\)/);
  assert.match(embeddedOrient, /cropOverride\.obj\.setName\("\$\{CROP_OVERRIDE_CLASS_NAME\} \$\{coreName\}"\)/);
  assert.match(embeddedOrient, /override\.obj\.setName\("\$\{OVERRIDE_CLASS_NAME\} \$\{coreName\}"\)/);
  assert.doesNotMatch(embeddedOrient, /ROIs\.createLineROI/);
  assert.doesNotMatch(embeddedOrient, /ann\.setName\("\$\{coreName\} epidermis"\)/);
  assert.doesNotMatch(embeddedOrient.match(/String coreSignature = sha256\(\[[\s\S]*?\]\s*\.join\('\|'\)\)/)?.[0] ?? "", /SAVE_ROTATED_MULTICHANNEL_OME_TIFF/);

  const reviewSource = await readFile(new URL("_archieved/legacy-multi-file-workflow/03_review_correct_and_approve_grid.groovy", root), "utf8");
  const restoreSource = await readFile(new URL("_archieved/legacy-multi-file-workflow/04_restore_approved_grid.groovy", root), "utf8");
  assert.match(reviewSource, /cropPaddingFactor: desiredCropPaddingFactor/);
  assert.match(restoreSource, /CoreAlign crop padding factor/);

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
