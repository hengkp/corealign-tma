/**
 * STANDALONE one-file, resumable state-machine for TMA preparation.
 * All numbered workflow steps are gzip/base64-embedded below; no companion
 * Groovy scripts are required at runtime.
 *
 * State transitions:
 *   NO GRID -> detect -> STOP FOR HUMAN REVIEW
 *   GRID, NOT APPROVED -> apply corrections + explicit approval -> orient
 *   APPROVED GRID -> resume orientation from per-core checkpoints
 *   APPROVED CHECKPOINT, NO LIVE GRID -> restore grid -> resume orientation
 *
 * Detection is never repeated automatically when a usable grid/checkpoint is
 * already available.  To force redetection, remove the live grid deliberately
 * in QuPath and move the approved checkpoint aside; this prevents accidental
 * destruction of reviewed work.
 */

import com.google.gson.Gson
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

class EmbeddedWorkflowScript {
    String name
    String payload
    boolean isFile() { true }
    String getName() { name }
    String getText(String ignoredEncoding) {
        byte[] compressed = Base64.getMimeDecoder().decode(payload)
        def input = new GZIPInputStream(new ByteArrayInputStream(compressed))
        try {
            String source = input.getText('UTF-8')
            if (name == '01_build_tma_grid.groovy') {
                source = source
                    .replace('Inspect before Step 2.', 'Inspect before continuing.')
                    .replace('Run 02_auto_orient_epidermis.groovy next.',
                        'Review the grid, then run CoreAlign.groovy again.')
            }
            return source
        } finally { input.close() }
    }
}

String REQUIRED_DETECTION_ALGORITHM_VERSION = 'corealign-grid-3.0-adaptive'
boolean ALL_IN_ONE_INTEGRATION_TEST = 'true'.equalsIgnoreCase(
    System.getProperty('tma.allInOneAutoTest', 'false'))
boolean STOP_AFTER_DETECTION = 'true'.equalsIgnoreCase(
    System.getProperty('tma.stopAfterDetection', 'false'))
if (ALL_IN_ONE_INTEGRATION_TEST) {
    System.setProperty('tma.approveForTest', 'true')
    System.setProperty('tma.finalApproveForTest', 'true')
    println 'WARNING: all-in-one automated integration-test mode is active; approvals will be marked as test, never human.'
}

def imageData = getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('CoreAlign',
        'No image is open. Open the OME-TIFF in QuPath first.')
    return
}

def server = imageData.getServer()
File workflowDir = null
try {
    def fileUri = server.getURIs().find { it != null && it.getScheme() == 'file' }
    if (fileUri != null) workflowDir = new File(fileUri).getParentFile()
} catch (Throwable ignored) {}
if (workflowDir == null) {
    Dialogs.showErrorMessage('CoreAlign',
        'Could not resolve the local image folder required for checkpoints and exports.')
    return
}

String imageName = server.getMetadata().getName() ?: 'image'
String imageStem = imageName.replaceAll(/(?i)\.ome\.tif+$/, '')
    .replaceAll(/[^A-Za-z0-9._-]+/, '_')
if (imageStem.isEmpty()) imageStem = 'image'

// -------------------------------------------------------------------------
// Stable project layout.
//
// Only qc/, results/, and qupath/ contain files that a researcher normally
// opens. All resumable engine state is kept under work/. Older tma_* folders
// remain readable so an existing run can be upgraded without starting again.
// -------------------------------------------------------------------------
File gridQcDir = new File(workflowDir, 'qc/01-grid')
File orientationQcDir = new File(workflowDir, 'qc/02-orientation')
File resultsPngDir = new File(workflowDir, 'results/png')
File resultsOmeDir = new File(workflowDir, 'results/ome-tiff')
File resultsTablesDir = new File(workflowDir, 'results/tables')
File resultsPresentationDir = new File(workflowDir, 'results/presentation')
File qupathProjectDir = new File(workflowDir, 'qupath')
File workDir = new File(workflowDir, 'work')
File stateDir = new File(new File(workDir, 'state'), imageStem)
File runBaseDir = new File(workDir, 'runs')
File legacyStateDir = new File(new File(workflowDir, 'tma_pipeline_state'), imageStem)
File legacyRunBaseDir = new File(workflowDir, 'tma_auto_orient_export')

[gridQcDir, orientationQcDir, resultsPngDir, resultsOmeDir,
 resultsTablesDir, resultsPresentationDir, qupathProjectDir, stateDir,
 runBaseDir].each { folder ->
    if (!folder.mkdirs() && !folder.isDirectory())
        throw new IOException("Could not create CoreAlign folder: ${folder.getAbsolutePath()}")
}

// State files are small. Copy them once into the new work/state location,
// leaving the legacy source untouched as a safety backup.
if (legacyStateDir.isDirectory()) {
    legacyStateDir.eachFileRecurse { source ->
        if (!source.isFile()) return
        String relative = legacyStateDir.toPath().relativize(source.toPath()).toString()
        File target = new File(stateDir, relative)
        if (!target.isFile()) {
            target.getParentFile()?.mkdirs()
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}

System.setProperty('corealign.project.root', workflowDir.getAbsolutePath())
System.setProperty('corealign.qc.gridDir', gridQcDir.getAbsolutePath())
System.setProperty('corealign.qc.orientationDir', orientationQcDir.getAbsolutePath())
System.setProperty('corealign.results.pngDir', resultsPngDir.getAbsolutePath())
System.setProperty('corealign.results.omeDir', resultsOmeDir.getAbsolutePath())
System.setProperty('corealign.results.tablesDir', resultsTablesDir.getAbsolutePath())
System.setProperty('corealign.results.presentationDir', resultsPresentationDir.getAbsolutePath())
System.setProperty('corealign.qupath.projectDir', qupathProjectDir.getAbsolutePath())
System.setProperty('corealign.work.stateDir', stateDir.getAbsolutePath())
System.setProperty('corealign.work.runBaseDir', runBaseDir.getAbsolutePath())
System.setProperty('corealign.legacy.runBaseDir', legacyRunBaseDir.getAbsolutePath())

new File(workflowDir, 'PROJECT-README.txt').setText("""CoreAlign project

Open START-HERE.html for the current status and direct links.

qc/01-grid          Check detected core circles and coordinates.
qc/02-orientation   Check rotation, crop, review flags, and run reports.
results/png         Final rotated-then-cropped presentation images.
results/ome-tiff    Final rotated multichannel research images.
results/tables      Per-core measurements and run metadata.
results/presentation Optional generated comparison panels.
qupath              Analysis-ready QuPath core project after final approval.
work                Checkpoints and run state. Do not edit or delete while work is active.

Keep the slide, CoreAlign.groovy, corealign.config.json, and work folder together.
""", 'UTF-8')

// -------------------------------------------------------------------------
// Optional config layer.  The one-file runner still contains safe defaults,
// but a JSON profile allows the same workflow to be reused for other arrays,
// stains, layouts, and presentation mappings without editing Groovy code.
// -------------------------------------------------------------------------
def configJson = new Gson().newBuilder().setPrettyPrinting().create()
String explicitConfigPath = System.getProperty('tma.config', '').trim()
def siblingConfigs = (workflowDir.listFiles() ?: [] as File[]).findAll { candidate ->
    candidate.isFile() && candidate.getName() ==~ /(?i)corealign\.config.*\.json/
}
if (explicitConfigPath.isEmpty() && siblingConfigs.size() > 1) {
    Dialogs.showErrorMessage('CoreAlign preflight failed',
        "Found multiple CoreAlign config files beside the open slide:\n" +
        siblingConfigs.collect { "- ${it.getName()}" }.sort().join('\n') +
        "\n\nKeep exactly one file named corealign.config.json, then run again.")
    println "PREFLIGHT_BLOCKED: multiple config files in ${workflowDir.getAbsolutePath()}: ${siblingConfigs*.name}"
    return
}
File configFile = new File(explicitConfigPath.isEmpty() ?
    new File(workflowDir, 'corealign.config.json').getAbsolutePath() :
    explicitConfigPath)
if (!configFile.isFile()) {
    double automaticSeedCoreMM = 0.6d
    def diameterMatcher = imageStem =~ /(?i)(\d+(?:[._]\d+)?)mm/
    if (diameterMatcher.find()) {
        try { automaticSeedCoreMM = diameterMatcher.group(1).replace('_', '.').toDouble() }
        catch (Throwable ignored) {}
    }
    def starterConfig = [schemaVersion: 2, activeProfile: 'automatic', profiles: [automatic: [
        description: 'Automatic TMA preparation.',
        grid: [rows: 18, columns: 7, coreDiameterMM: 0.6d, cropPaddingFactor: 1.90d,
            rowScheme: '1, 2, 3...', columnScheme: 'A, B, C...', showAdvancedDialog: false,
            autoDetectGeometry: true, autoEstimateCoreDiameter: true, autoInferLayout: true,
            useExistingGridUnlessRectangleSelected: true, trustNondefaultExistingGrid: true],
        detection: [algorithmVersion: 'corealign-grid-3.0-adaptive', channelMode: 'nuclear',
            autoRetryMergedChannels: true,
            downsample: 8.0d, blurSigmaFraction: 0.25d, otsuThresholdScale: 0.70d,
            minBlobAreaFraction: 0.05d, maxBlobAreaFraction: 5.0d],
        orientation: [algorithmVersion: 'generic-peripheral-orient-3.7-rotated-multichannel', analysisDownsample: 4.0d,
            exportDownsample: 1.0d, previewMaxPixels: 900, cropScale: 1.05d,
            rotationSupportScale: 1.45d, regionRefinementEnabled: true,
            regionSearchScale: 1.55d, regionMaxCenterShiftFraction: 0.30d,
            regionTissueMargin: 1.12d, regionMaxCropScale: 1.15d,
            regionReviewConfidence: 0.12d,
            saveFullResolutionPng: true, saveNativeOmeTiff: false,
            saveRotatedMultichannelOmeTiff: false,
            cropOverrideClassName: 'TMA crop override', postRotationToleranceDeg: 12.0d,
            postRotationMaxIterations: 2, angularSectors: 72,
            outerRingInner: 0.42d, outerRingOuter: 1.02d, tissueThresholdScale: 0.55d,
            reviewConfidence: 0.12d, okConfidence: 0.28d,
            nuclearChannelTokens: ['dapi', 'hoechst', 'nuclear'],
            epidermisChannelTokens: ['keratin', 'panck', 'epcam'],
            rgbRedChannelTokens: ['keratin', 'panck', 'epcam'], rgbGreenChannelTokens: [],
            overrideClassName: 'Orientation override'],
        quality: [requireHumanGridApproval: true, requireHumanOrientationApproval: true,
            blockPresentationWhenAnySelectedCoreNeedsReview: true],
        presentation: [enabled: false, conditions: [], treatmentColumns: [], comparisons: []]
    ]]]
    starterConfig.profiles.automatic.grid.coreDiameterMM = automaticSeedCoreMM
    configFile.setText(configJson.toJson(starterConfig) + '\n', 'UTF-8')
    println "Created automatic config: ${configFile.getAbsolutePath()}"
}

def pipelineConfig
try { pipelineConfig = configJson.fromJson(configFile.getText('UTF-8'), Map.class) }
catch (Throwable configError) {
    Dialogs.showErrorMessage('TMA config invalid',
        "Could not parse ${configFile.getAbsolutePath()}:\n${configError.getMessage()}")
    return
}
if (!(pipelineConfig.profiles instanceof Map) || pipelineConfig.profiles.isEmpty()) {
    Dialogs.showErrorMessage('TMA config invalid', 'profiles must be a non-empty JSON object.')
    return
}

String profileName = System.getProperty('tma.profile', '').trim()
if (profileName.isEmpty() && pipelineConfig.profileRules instanceof List) {
    def matchedRule = pipelineConfig.profileRules.find { rule ->
        try { return imageName ==~ rule.imageRegex.toString() }
        catch (Throwable ignored) { return false }
    }
    if (matchedRule != null) profileName = matchedRule.profile.toString()
}
if (profileName.isEmpty()) profileName = pipelineConfig.activeProfile?.toString() ?: ''
def activeProfile = pipelineConfig.profiles[profileName]
if (!(activeProfile instanceof Map)) {
    Dialogs.showErrorMessage('TMA config invalid',
        "Profile '${profileName}' is not defined. Available: ${pipelineConfig.profiles.keySet()}.")
    return
}

def gridConfig = activeProfile.grid instanceof Map ? activeProfile.grid : [:]
def detectionConfig = activeProfile.detection instanceof Map ? activeProfile.detection : [:]
def orientationConfig = activeProfile.orientation instanceof Map ? activeProfile.orientation : [:]
int configuredRows = ((gridConfig.rows ?: 18) as Number).intValue()
int configuredColumns = ((gridConfig.columns ?: 7) as Number).intValue()
double configuredCoreMM = ((gridConfig.coreDiameterMM ?: 0.6d) as Number).doubleValue()
gridConfig.rows = configuredRows
gridConfig.columns = configuredColumns
gridConfig.coreDiameterMM = configuredCoreMM
if (gridConfig.autoDetectGeometry == null) gridConfig.autoDetectGeometry = true
if (gridConfig.autoEstimateCoreDiameter == null) gridConfig.autoEstimateCoreDiameter = true
if (gridConfig.autoInferLayout == null) gridConfig.autoInferLayout = true
double configuredRingInner = ((orientationConfig.outerRingInner ?: 0.42d) as Number).doubleValue()
double configuredRingOuter = ((orientationConfig.outerRingOuter ?: 1.02d) as Number).doubleValue()
if (configuredRows < 1 || configuredColumns < 1 || configuredCoreMM <= 0.0d ||
        configuredRingInner < 0.0d || configuredRingOuter <= configuredRingInner) {
    Dialogs.showErrorMessage('TMA config invalid',
        "Profile ${profileName} has invalid rows/columns/core diameter or orientation ring limits.")
    return
}

boolean autoDetectGeometry = gridConfig.autoDetectGeometry != false

// Geometry must come from the slide, not from a hard-coded filename. A named
// slide may be re-scanned, re-cropped or reused for another array layout.
System.clearProperty('tma.grid.referenceLocked')
boolean advancedDialogRequested = gridConfig.showAdvancedDialog == true
boolean allowAdvancedDialog = 'true'.equalsIgnoreCase(
    System.getProperty('tma.allowAdvancedDialog', 'false'))
if (advancedDialogRequested && !allowAdvancedDialog) {
    Dialogs.showErrorMessage('CoreAlign preflight failed',
        "Profile '${profileName}' enables showAdvancedDialog. This can mix values from different presets.\n\n" +
        'Set grid.showAdvancedDialog to false in the Config Builder and download a fresh config.')
    println 'PREFLIGHT_BLOCKED: showAdvancedDialog must be false for the production runner.'
    return
}

String profileCanonical = configJson.toJson(activeProfile)
def sha256Text = { String value -> MessageDigest.getInstance('SHA-256')
    .digest(value.getBytes('UTF-8'))
    .collect { String.format('%02x', it & 0xff) }.join() }
String profileHash = sha256Text(profileCanonical)

// Keep computation and delivery identities separate.  Export-only switches
// must never invalidate an approved grid or accepted per-core rotation.
def orientationProcessingConfig = new LinkedHashMap(orientationConfig)
['saveFullResolutionPng', 'saveNativeOmeTiff',
 'saveRotatedMultichannelOmeTiff'].each { orientationProcessingConfig.remove(it) }
def detectionIdentity = [grid: gridConfig, detection: detectionConfig]
def processingIdentity = [grid: gridConfig, detection: detectionConfig,
    orientation: orientationProcessingConfig]
def outputIdentity = [
    saveFullResolutionPng: orientationConfig.saveFullResolutionPng != false,
    saveNativeOmeTiff: orientationConfig.saveNativeOmeTiff == true,
    saveRotatedMultichannelOmeTiff:
        orientationConfig.saveRotatedMultichannelOmeTiff == true
]
String detectionConfigHash = sha256Text(configJson.toJson(detectionIdentity))
String processingHash = sha256Text(configJson.toJson(processingIdentity))
String outputHash = sha256Text(configJson.toJson(outputIdentity))
System.setProperty('tma.config.path', configFile.getAbsolutePath())
System.setProperty('tma.config.profile', profileName)
System.setProperty('tma.config.profileHash', profileHash)
System.setProperty('tma.config.detectionHash', detectionConfigHash)
System.setProperty('tma.config.processingHash', processingHash)
System.setProperty('tma.config.outputHash', outputHash)

def setProp = { String key, value ->
    if (value == null) return
    String propertyValue
    if (value instanceof List) {
        propertyValue = value.collect { it.toString() }.join(',')
    } else if (value instanceof Number) {
        // Gson represents JSON integers as doubles in an untyped Map. Without
        // normalization, rows=12 becomes "12.0" and the embedded detector's
        // Integer.parseInt falls back silently to its 18-row default.
        double numericValue = ((Number) value).doubleValue()
        propertyValue = Double.isFinite(numericValue) && numericValue == Math.rint(numericValue) ?
            Long.toString(((Number) value).longValue()) : value.toString()
    } else {
        propertyValue = value.toString()
    }
    System.setProperty(key, propertyValue)
}
[
    'tma.grid.rows': gridConfig.rows,
    'tma.grid.columns': gridConfig.columns,
    'tma.grid.coreDiameterMM': gridConfig.coreDiameterMM,
    'tma.grid.cropPaddingFactor': gridConfig.cropPaddingFactor,
    'tma.grid.rowScheme': gridConfig.rowScheme,
    'tma.grid.columnScheme': gridConfig.columnScheme,
    'tma.grid.showAdvancedDialog': gridConfig.showAdvancedDialog,
    'tma.grid.autoDetectGeometry': gridConfig.autoDetectGeometry,
    'tma.grid.autoEstimateCoreDiameter': gridConfig.autoEstimateCoreDiameter,
    'tma.grid.useExistingGridUnlessRectangleSelected': gridConfig.useExistingGridUnlessRectangleSelected,
    'tma.grid.trustNondefaultExistingGrid': gridConfig.trustNondefaultExistingGrid,
    'tma.grid.autoInferLayout': gridConfig.autoInferLayout,
    'tma.grid.exportQc': gridConfig.exportQc,
    'tma.grid.defaultPixelSizeMicrons': gridConfig.defaultPixelSizeMicrons,
    'tma.detection.algorithmVersion': detectionConfig.algorithmVersion,
    'tma.detection.channelMode': detectionConfig.channelMode,
    'tma.detection.customChannels': detectionConfig.customChannels,
    'tma.detection.autoRetryMergedChannels': detectionConfig.autoRetryMergedChannels,
    'tma.detection.downsample': detectionConfig.downsample,
    'tma.detection.blurSigmaFraction': detectionConfig.blurSigmaFraction,
    'tma.detection.otsuThresholdScale': detectionConfig.otsuThresholdScale,
    'tma.detection.minBlobAreaFraction': detectionConfig.minBlobAreaFraction,
    'tma.detection.maxBlobAreaFraction': detectionConfig.maxBlobAreaFraction,
    'tma.detection.minAssignedFractionToBuildGrid': detectionConfig.minAssignedFractionToBuildGrid,
    'tma.detection.minAssignedFractionForReview': detectionConfig.minAssignedFractionForReview,
    'tma.detection.requireEveryRowAndColumn': detectionConfig.requireEveryRowAndColumn,
    'tma.detection.maxMissingFractionToPreserve': detectionConfig.maxMissingFractionToPreserve,
    'tma.detection.autoSearchDownsample': detectionConfig.autoSearchDownsample,
    'tma.detection.autoRegionPaddingCores': detectionConfig.autoRegionPaddingCores,
    'tma.detection.minTrustedGridFraction': detectionConfig.minTrustedGridFraction,
    'tma.detection.minTrustedNondefaultGridFraction': detectionConfig.minTrustedNondefaultGridFraction,
    'tma.orientation.algorithmVersion': orientationConfig.algorithmVersion,
    'tma.orientation.analysisDownsample': orientationConfig.analysisDownsample,
    'tma.orientation.exportDownsample': orientationConfig.exportDownsample,
    'tma.orientation.previewMaxPixels': orientationConfig.previewMaxPixels,
    'tma.orientation.cropScale': orientationConfig.cropScale,
    'tma.orientation.rotationSupportScale': orientationConfig.rotationSupportScale,
    'tma.orientation.regionRefinementEnabled': orientationConfig.regionRefinementEnabled,
    'tma.orientation.regionSearchScale': orientationConfig.regionSearchScale,
    'tma.orientation.regionMaxCenterShiftFraction': orientationConfig.regionMaxCenterShiftFraction,
    'tma.orientation.regionTissueMargin': orientationConfig.regionTissueMargin,
    'tma.orientation.regionMaxCropScale': orientationConfig.regionMaxCropScale,
    'tma.orientation.regionReviewConfidence': orientationConfig.regionReviewConfidence,
    'tma.orientation.saveFullResolutionPng': orientationConfig.saveFullResolutionPng,
    'tma.orientation.saveNativeOmeTiff': orientationConfig.saveNativeOmeTiff,
    'tma.orientation.saveRotatedMultichannelOmeTiff': orientationConfig.saveRotatedMultichannelOmeTiff,
    'tma.orientation.cropOverrideClassName': orientationConfig.cropOverrideClassName,
    'tma.orientation.postRotationToleranceDeg': orientationConfig.postRotationToleranceDeg,
    'tma.orientation.postRotationMaxIterations': orientationConfig.postRotationMaxIterations,
    'tma.orientation.angularSectors': orientationConfig.angularSectors,
    'tma.orientation.outerRingInner': orientationConfig.outerRingInner,
    'tma.orientation.outerRingOuter': orientationConfig.outerRingOuter,
    'tma.orientation.tissueThresholdScale': orientationConfig.tissueThresholdScale,
    'tma.orientation.reviewConfidence': orientationConfig.reviewConfidence,
    'tma.orientation.okConfidence': orientationConfig.okConfidence,
    'tma.orientation.nuclearChannelTokens': orientationConfig.nuclearChannelTokens,
    'tma.orientation.epidermisChannelTokens': orientationConfig.epidermisChannelTokens,
    'tma.orientation.rgbRedChannelTokens': orientationConfig.rgbRedChannelTokens,
    'tma.orientation.rgbGreenChannelTokens': orientationConfig.rgbGreenChannelTokens,
    'tma.orientation.overrideClassName': orientationConfig.overrideClassName
].each { key, value -> setProp(key, value) }

// Fail before reading any pixels if config propagation ever regresses.
try {
    int runtimeRows = Integer.parseInt(System.getProperty('tma.grid.rows'))
    int runtimeColumns = Integer.parseInt(System.getProperty('tma.grid.columns'))
    double runtimeCoreMM = Double.parseDouble(System.getProperty('tma.grid.coreDiameterMM'))
    if (runtimeRows != configuredRows || runtimeColumns != configuredColumns ||
            Math.abs(runtimeCoreMM - configuredCoreMM) > 0.000001d) {
        throw new IllegalStateException(
            "expected ${configuredRows}x${configuredColumns} at ${configuredCoreMM} mm, " +
            "propagated ${runtimeRows}x${runtimeColumns} at ${runtimeCoreMM} mm")
    }
    println "TMA runtime config verified: grid ${runtimeRows}x${runtimeColumns} | core ${runtimeCoreMM} mm"
} catch (Throwable propagationError) {
    Dialogs.showErrorMessage('CoreAlign preflight failed',
        "The detector did not receive the selected grid configuration.\n\n${propagationError.getMessage()}")
    println "PREFLIGHT_BLOCKED: config propagation failed: ${propagationError.getMessage()}"
    return
}

REQUIRED_DETECTION_ALGORITHM_VERSION = System.getProperty('tma.detection.algorithmVersion',
    REQUIRED_DETECTION_ALGORITHM_VERSION)
File profileStateDir = new File(stateDir, 'config')
profileStateDir.mkdirs()
def processingHashForSavedProfile = { Map savedProfile ->
    def savedGrid = savedProfile.grid instanceof Map ?
        new LinkedHashMap(savedProfile.grid as Map) : [:]
    def savedDetection = savedProfile.detection instanceof Map ?
        new LinkedHashMap(savedProfile.detection as Map) : [:]
    def savedOrientation = savedProfile.orientation instanceof Map ?
        new LinkedHashMap(savedProfile.orientation as Map) : [:]
    savedGrid.rows = ((savedGrid.rows ?: 18) as Number).intValue()
    savedGrid.columns = ((savedGrid.columns ?: 7) as Number).intValue()
    savedGrid.coreDiameterMM = ((savedGrid.coreDiameterMM ?: 0.6d) as Number).doubleValue()
    if (savedGrid.autoDetectGeometry == null) savedGrid.autoDetectGeometry = true
    if (savedGrid.autoEstimateCoreDiameter == null) savedGrid.autoEstimateCoreDiameter = true
    if (savedGrid.autoInferLayout == null) savedGrid.autoInferLayout = true
    ['saveFullResolutionPng', 'saveNativeOmeTiff',
     'saveRotatedMultichannelOmeTiff'].each { savedOrientation.remove(it) }
    return sha256Text(configJson.toJson([grid: savedGrid, detection: savedDetection,
        orientation: savedOrientation]))
}
def compatibleLegacyProfileHashes = [profileHash]
(profileStateDir.listFiles() ?: [] as File[]).findAll {
    it.isFile() && it.getName().endsWith('.json')
}.each { savedConfigFile ->
    try {
        String savedCanonical = savedConfigFile.getText('UTF-8').trim()
        def savedProfile = configJson.fromJson(savedCanonical, Map.class)
        if (savedProfile instanceof Map &&
                processingHashForSavedProfile(savedProfile) == processingHash)
            compatibleLegacyProfileHashes << sha256Text(savedCanonical)
    } catch (Throwable ignored) {}
}
System.setProperty('tma.config.compatibleLegacyProfileHashes',
    compatibleLegacyProfileHashes.unique().join(','))
new File(profileStateDir, "${profileName}_${profileHash.take(12)}.json")
    .setText(profileCanonical + '\n', 'UTF-8')
println "TMA config: ${configFile.getName()} | profile ${profileName} | processing ${processingHash.take(12)} | output ${outputHash.take(12)} | grid ${configuredRows}x${configuredColumns}"
if ('true'.equalsIgnoreCase(System.getProperty('tma.configValidateOnly', 'false'))) {
    println 'TMA_CONFIG_VALIDATION_OK'
    return
}
def existingGridForPreflight = imageData.getHierarchy().getTMAGrid()
boolean needsDetectionPreflight = existingGridForPreflight == null ||
    existingGridForPreflight.getTMACoreList().isEmpty() ||
    !existingGridForPreflight.getTMACoreList().any { !it.isMissing() }
if (needsDetectionPreflight && !ALL_IN_ONE_INTEGRATION_TEST && !STOP_AFTER_DETECTION) {
    if (autoDetectGeometry) {
        println "Automatic geometry enabled: no row, column, or diameter confirmation is required."
    } else {
    String preflightMessage = "Open slide: ${new File(workflowDir, imageName).getAbsolutePath()}\n" +
        "Working folder: ${workflowDir.getAbsolutePath()}\n" +
        "Config: ${configFile.getAbsolutePath()}\n" +
        "Profile: ${profileName}\n" +
        "Grid: ${configuredRows} rows x ${configuredColumns} columns\n" +
        "Core diameter: ${configuredCoreMM} mm\n\n" +
        'Continue only if every value matches the physical TMA.'
    if (!Dialogs.showConfirmDialog('CoreAlign preflight: verify before detection', preflightMessage)) {
        println 'PREFLIGHT_CANCELLED: no image processing was started.'
        return
    }
    }
}

def step1 = new EmbeddedWorkflowScript(name: '01_build_tma_grid.groovy', payload: '''
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
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
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
QWQLf7icwbi9l6jb4Dczef6YL8oBAA==
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
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
942j8d59u6b8DWzyzTEQEAAA
''')
def step5 = new EmbeddedWorkflowScript(name: '05_finalize_orientation_review.groovy', payload: '''
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
''')
def step6 = new EmbeddedWorkflowScript(name: '06_export_presentation_package.groovy', payload: '''
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
''')

def step7 = new EmbeddedWorkflowScript(name: '07_build_qupath_analysis_project.groovy', payload: '''
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
QwQxAAA=
''')

def runWorkflowScript = { scriptFile ->
    String preamble = '''
import static qupath.lib.scripting.QP.*
import qupath.fx.dialogs.Dialogs
'''
    println "=== Running ${scriptFile.getName()} ==="
    evaluate(preamble + scriptFile.getText('UTF-8'))
}
def usableGrid = {
    def g = imageData.getHierarchy().getTMAGrid()
    return g != null && !g.getTMACoreList().isEmpty() &&
        g.getTMACoreList().any { !it.isMissing() }
}

def publishFile = { File source, File target ->
    if (source == null || !source.isFile()) return false
    target.getParentFile()?.mkdirs()
    try { Files.deleteIfExists(target.toPath()) } catch (Throwable ignored) {}
    try {
        Files.createLink(target.toPath(), source.toPath())
    } catch (Throwable linkError) {
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    return target.isFile()
}
def publishFolder
publishFolder = { File sourceDir, File targetDir ->
    if (sourceDir == null || !sourceDir.isDirectory()) return 0
    int count = 0
    sourceDir.eachFileRecurse { source ->
        if (!source.isFile()) return
        String relative = sourceDir.toPath().relativize(source.toPath()).toString()
        if (publishFile(source, new File(targetDir, relative))) count++
    }
    return count
}
def writeProjectIndex = { File runDir = null ->
    String gridLink = new File(gridQcDir, "${imageStem}_grid_qc_latest.png").isFile() ?
        "qc/01-grid/${imageStem}_grid_qc_latest.png" :
        "qc/01-grid/${imageStem}_grid_qc.png"
    boolean hasGridQc = new File(workflowDir, gridLink).isFile()
    boolean hasRunReport = new File(orientationQcDir, 'run_report.html').isFile()
    boolean hasCompletionReport = new File(orientationQcDir, 'completion_report.html').isFile()
    boolean hasProject = new File(qupathProjectDir, 'project.qpproj').isFile()
    String currentStage = hasCompletionReport ? 'Complete and human approved' :
        hasRunReport ? 'Orientation ready for review' :
        hasGridQc ? 'Grid ready for review' : 'Ready to run'
    String html = '''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CoreAlign project</title><style>:root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;background:#f4f7fb;color:#17202a;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{max-width:980px;margin:auto;padding:36px 22px 70px}.hero,.card{background:white;border:1px solid #dbe4ed;border-radius:16px;box-shadow:0 5px 22px rgba(20,35,50,.07)}.hero{padding:28px;border-top:7px solid #0787c8}.status{color:#087f5b;font-weight:750;text-transform:uppercase;font-size:13px;letter-spacing:.06em}h1{margin:7px 0 8px;font-size:34px}h2{font-size:20px;margin:0 0 8px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(245px,1fr));gap:14px;margin-top:18px}.card{padding:20px}.card p{color:#5f6b7a;line-height:1.55}.card a{display:inline-flex;margin-top:8px;padding:10px 13px;border-radius:9px;background:#0d1d35;color:white;text-decoration:none;font-weight:700}.path{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;word-break:break-all}.note{margin-top:20px;padding:15px;border:1px solid #dbe4ed;border-radius:12px;color:#5f6b7a}@media(prefers-color-scheme:dark){body{background:#0b1422;color:#f4f7fb}.hero,.card{background:#111f31;border-color:#2b3d54}.card p,.note{color:#b6c2d2}.note{border-color:#2b3d54}.card a{background:#52bff1;color:#07101d}}</style></head><body><main>'''
    html += '<section class="hero"><div class="status">' + currentStage +
        '</div><h1>CoreAlign project</h1><p>' + imageName +
        '</p><p class="path">' + workflowDir.getAbsolutePath() + '</p></section>'
    html += '<div class="grid">'
    html += '<section class="card"><h2>1. Grid QC</h2><p>Check every detected circle, row, column, and missing position.</p>' +
        (hasGridQc ? '<a href="' + gridLink + '">Open grid QC</a>' : '<p>Run CoreAlign to create this folder.</p>') + '</section>'
    html += '<section class="card"><h2>2. Orientation QC</h2><p>Review epidermis direction, crop boundaries, and flagged cores.</p>' +
        (hasRunReport ? '<a href="qc/02-orientation/run_report.html">Open run report</a>' : '<p>Approve the grid and run CoreAlign again.</p>') + '</section>'
    html += '<section class="card"><h2>3. Results</h2><p>Use PNG for presentations and multichannel OME-TIFF for research analysis.</p><a href="results/">Open results</a></section>'
    html += '<section class="card"><h2>4. QuPath project</h2><p>Open the ordered core project after the research package is approved.</p>' +
        (hasProject ? '<a href="qupath/project.qpproj">Open project file</a>' : '<p>Created only for an approved research package.</p>') + '</section>'
    html += '</div><p class="note">The work folder contains resumable checkpoints. Keep it, but you normally do not need to open it.</p>'
    if (runDir != null) html += '<p class="path">Internal run: ' + runDir.getAbsolutePath() + '</p>'
    html += '</main></body></html>'
    new File(workflowDir, 'START-HERE.html').setText(html, 'UTF-8')
}
def publishCurrentRun = {
    String runPath = System.getProperty('tma.orientation.runDir', '').trim()
    if (runPath.isEmpty()) {
        writeProjectIndex(null)
        return
    }
    File runDir = new File(runPath)
    if (!runDir.isDirectory()) {
        writeProjectIndex(null)
        return
    }

    int published = 0
    ['orientation_contact_sheet.png', 'review.html', 'run_report.html',
     'run_report.json', 'orientation_review_queue.csv', 'completion_report.html',
     'completion_report.json'].each { name ->
        if (publishFile(new File(runDir, name), new File(orientationQcDir, name))) published++
    }
    published += publishFolder(new File(runDir, 'rotated_previews'),
        new File(orientationQcDir, 'rotated_previews'))
    published += publishFolder(new File(runDir, 'unrotated_previews'),
        new File(orientationQcDir, 'unrotated_previews'))
    published += publishFolder(new File(runDir, 'rotated_fullres'), resultsPngDir)
    published += publishFolder(new File(runDir, 'rotated_multichannel_ome'), resultsOmeDir)
    ['orientation_results.csv', 'orientation_review_queue.csv', 'run_manifest.json',
     'workflow_summary.txt'].each { name ->
        if (publishFile(new File(runDir, name), new File(resultsTablesDir, name))) published++
    }
    def layoutManifest = [schemaVersion: 1, image: imageName,
        updatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"),
        internalRunDirectory: runDir.getAbsolutePath(),
        folders: [gridQc: 'qc/01-grid', orientationQc: 'qc/02-orientation',
            presentationPng: 'results/png', researchOmeTiff: 'results/ome-tiff',
            tables: 'results/tables', presentation: 'results/presentation',
            qupathProject: 'qupath', resumableWork: 'work']]
    new File(resultsTablesDir, 'project_layout.json')
        .setText(configJson.toJson(layoutManifest) + '\n', 'UTF-8')
    writeProjectIndex(runDir)
    println "Published ${published} easy-to-find project file(s) under qc/ and results/."
}
writeProjectIndex(null)

// A technical reference is optional and profile-specific.  It is useful for a
// validated development slide but is never silently applied to another image.
String technicalReferenceImageStem = detectionConfig.technicalReferenceImageStem?.toString() ?: ''
def technicalReferenceMissing = detectionConfig.technicalReferenceMissing instanceof List &&
        technicalReferenceImageStem == imageStem ?
    [(imageStem): detectionConfig.technicalReferenceMissing.collect { it.toString() } as Set] : [:]
int configuredPositionCount = configuredRows * configuredColumns
def adoptAutomaticGeometry = {
    if (!autoDetectGeometry) return
    def detectedGrid = imageData.getHierarchy().getTMAGrid()
    if (detectedGrid == null || detectedGrid.getTMACoreList().isEmpty()) return
    String geometryStatus = System.getProperty('tma.geometry.status', '')
    if (!['CONFIDENT', 'REFERENCE_LOCKED'].contains(geometryStatus)) return
    configuredRows = detectedGrid.getGridHeight()
    configuredColumns = detectedGrid.getGridWidth()
    configuredPositionCount = configuredRows * configuredColumns
    System.setProperty('tma.grid.rows', configuredRows.toString())
    System.setProperty('tma.grid.columns', configuredColumns.toString())
    println "CoreAlign adopted automatic geometry: ${configuredRows}x${configuredColumns}; " +
        "confidence ${System.getProperty('tma.geometry.confidence', 'n/a')}"
}
double minAssignedFractionForReview = ((detectionConfig.minAssignedFractionForReview ?: 0.75d) as Number).doubleValue()
boolean requireEveryRowAndColumn = detectionConfig.requireEveryRowAndColumn != false
def validateGridStructure = { String stage ->
    def g = imageData.getHierarchy().getTMAGrid()
    if (g == null || g.getTMACoreList().size() != configuredPositionCount) {
        Dialogs.showErrorMessage('CoreAlign structural QC failed',
            "Expected ${configuredPositionCount} grid positions but found ${g == null ? 0 : g.getTMACoreList().size()}.\n" +
            'The workflow stopped before approval or orientation.')
        return false
    }
    def cores = g.getTMACoreList()
    int present = cores.count { !it.isMissing() }
    double assignedFraction = present / (double) configuredPositionCount
    def perRow = (0..<configuredRows).collect { row ->
        cores.subList(row * configuredColumns, (row + 1) * configuredColumns).count { !it.isMissing() }
    }
    def perColumn = (0..<configuredColumns).collect { col ->
        (0..<configuredRows).count { row -> !cores[row * configuredColumns + col].isMissing() }
    }
    def emptyRows = (0..<configuredRows).findAll { perRow[it] == 0 }.collect { it + 1 }
    def emptyColumns = (0..<configuredColumns).findAll { perColumn[it] == 0 }.collect { it + 1 }
    boolean passed = assignedFraction >= minAssignedFractionForReview &&
        (!requireEveryRowAndColumn || (emptyRows.isEmpty() && emptyColumns.isEmpty()))
    def report = [schemaVersion: 1, stage: stage, image: imageName, profile: profileName,
        rows: configuredRows, columns: configuredColumns, present: present,
        missing: configuredPositionCount - present, assignedFraction: assignedFraction,
        minimumAssignedFraction: minAssignedFractionForReview, emptyRows: emptyRows,
        emptyColumns: emptyColumns, passed: passed]
    File reportDir = gridQcDir
    reportDir.mkdirs()
    new File(reportDir, "${imageStem}_structural_qc.json")
        .setText(configJson.toJson(report) + '\n', 'UTF-8')
    println "STRUCTURAL QC: ${present}/${configuredPositionCount} present (${String.format(Locale.US, '%.1f', assignedFraction * 100.0d)}%), empty rows ${emptyRows}, empty columns ${emptyColumns}, passed=${passed}"
    if (!passed) {
        Dialogs.showErrorMessage('CoreAlign structural QC failed',
            "Detected ${present}/${configuredPositionCount} present positions. Empty rows: ${emptyRows}; empty columns: ${emptyColumns}.\n\n" +
            "Required present fraction is ${String.format(Locale.US, '%.0f', minAssignedFractionForReview * 100.0d)}%. " +
            'Check the selected profile, grid size, core diameter, and QC image. The workflow stopped before approval or orientation.')
    }
    return passed
}
def validateDetectionAgainstTechnicalReference = { String stage ->
    def expectedMissing = technicalReferenceMissing[imageStem]
    if (expectedMissing == null) {
        File reportDir = gridQcDir
        reportDir.mkdirs()
        def report = [
            schemaVersion: 2,
            image: imageName,
            detectorVersion: REQUIRED_DETECTION_ALGORITHM_VERSION,
            stage: stage,
            technicalReferenceAgreement: null,
            referenceStatus: 'not_configured; human_grid_approval_required'
        ]
        new File(reportDir, "${imageStem}_detection_validation.json")
            .setText(new Gson().newBuilder().setPrettyPrinting().create().toJson(report) + '\n', 'UTF-8')
        println "No slide-specific technical reference for ${imageStem}; structural and human QC gates remain active."
        return true
    }
    def g = imageData.getHierarchy().getTMAGrid()
    if (g == null || g.getTMACoreList().size() != configuredPositionCount) return false
    def actualMissing = g.getTMACoreList().findAll { it.isMissing() }
        .collect { it.getName() } as Set
    int correct = g.getTMACoreList().count { core ->
        boolean expected = expectedMissing.contains(core.getName())
        return core.isMissing() == expected
    }
    double accuracy = correct / (double) configuredPositionCount
    def report = [
        schemaVersion: 1,
        image: imageName,
        detectorVersion: REQUIRED_DETECTION_ALGORITHM_VERSION,
        stage: stage,
        evaluatedPositions: configuredPositionCount,
        correctPresenceMissingClassifications: correct,
        technicalReferenceAgreement: accuracy,
        expectedMissing: expectedMissing.toList().sort(),
        detectedMissing: actualMissing.toList().sort(),
        falsePresent: (expectedMissing - actualMissing).toList().sort(),
        falseMissing: (actualMissing - expectedMissing).toList().sort(),
        referenceStatus: 'technical_visual_QC; pathologist_adjudication_required_for_clinical_claim'
    ]
    File reportDir = gridQcDir
    reportDir.mkdirs()
    new File(reportDir, "${imageStem}_detection_validation.json")
        .setText(new Gson().newBuilder().setPrettyPrinting().create().toJson(report) + '\n', 'UTF-8')
    println "TECHNICAL DETECTION VALIDATION: ${correct}/${configuredPositionCount} (${String.format(Locale.US, '%.1f', accuracy * 100.0d)}%)"
    if (correct != configuredPositionCount) {
        Dialogs.showErrorMessage('CoreAlign technical detection validation failed',
            "Agreement is ${correct}/${configuredPositionCount}. False-present: ${report.falsePresent}; false-missing: ${report.falseMissing}.\n" +
            'The workflow stopped before approval/orientation.')
        return false
    }
    return true
}
File approvalFile = new File(stateDir, 'approved_grid.json')
def savedApproval = null
if (approvalFile.isFile()) {
    try { savedApproval = new Gson().fromJson(approvalFile.getText('UTF-8'), Map.class) }
    catch (Throwable ignored) {}
}
boolean approvalUsesCurrentDetector = savedApproval != null &&
    savedApproval.detectionAlgorithmVersion == REQUIRED_DETECTION_ALGORITHM_VERSION &&
    (savedApproval.approvalMode == 'human' ||
        (savedApproval.detectionConfigHash != null &&
            savedApproval.detectionConfigHash == detectionConfigHash) ||
        (savedApproval.detectionConfigHash == null &&
            (savedApproval.profileHash == null || savedApproval.profileHash == profileHash)))
boolean staleTestCheckpoint = savedApproval != null && !approvalUsesCurrentDetector &&
    savedApproval.approvalMode != 'human'

def liveGrid = imageData.getHierarchy().getTMAGrid()
def fmt3 = { double v -> String.format(Locale.US, '%.3f', v) }
def canonicalGrid = { g ->
    def lines = ["width=${g.getGridWidth()}|height=${g.getGridHeight()}"]
    g.getTMACoreList().eachWithIndex { core, i ->
        def roi = core.getROI()
        lines << [i, core.getName() ?: '', fmt3(roi.getCentroidX()),
            fmt3(roi.getCentroidY()),
            fmt3(Math.max(roi.getBoundsWidth(), roi.getBoundsHeight())),
            core.isMissing()].join('|')
    }
    return lines.join('\n')
}
def hashText = { String value -> MessageDigest.getInstance('SHA-256')
    .digest(value.getBytes('UTF-8')).collect { String.format('%02x', it & 0xff) }.join() }
String liveGridHash = liveGrid == null ? '' : hashText(canonicalGrid(liveGrid))
def liveDetectorVersions = liveGrid == null ? [] : liveGrid.getTMACoreList().collect {
    try { return it.getMetadataString('Detection algorithm version') ?: 'unknown' }
    catch (Throwable ignored) { return 'unknown' }
}.unique()
boolean liveGridUsesCurrentDetector = liveDetectorVersions.size() == 1 &&
    liveDetectorVersions[0] == REQUIRED_DETECTION_ALGORITHM_VERSION
boolean liveHasHumanCorrections = liveGrid != null && liveGrid.getTMACoreList().any {
    try { return (it.getMetadataString('Detection source') ?: '').startsWith('human_') }
    catch (Throwable ignored) { return false }
}

// QuPath may still hold an older grid in memory while a newer integration run
// has already written a current checkpoint. Replace only non-human stale state;
// never overwrite a grid containing explicit human corrections.
if (usableGrid() && approvalUsesCurrentDetector && savedApproval.approvalMode != 'human' &&
        (!liveGridUsesCurrentDetector || liveGridHash != savedApproval.gridHash) &&
        !liveHasHumanCorrections) {
    println "Live grid is not the current checkpoint (${liveGridHash}); restoring ${savedApproval.gridHash} before review."
    runWorkflowScript(step4)
    if (!validateDetectionAgainstTechnicalReference('restore_current_checkpoint')) return
}

if (staleTestCheckpoint) {
    println "Detector checkpoint is stale (${savedApproval.detectionAlgorithmVersion ?: 'unknown'}); running the current detector instead of restoring it."
    runWorkflowScript(step1)
    writeProjectIndex(null)
    adoptAutomaticGeometry()
    if (!usableGrid()) return
    if (!validateGridStructure('detector_version_refresh')) return
    if (!validateDetectionAgainstTechnicalReference('detector_version_refresh')) return
    Dialogs.showInfoNotification('CoreAlign detector updated',
        'A newer detector rebuilt the grid. Inspect the new QC, then run this script again for human approval.')
    if (!ALL_IN_ONE_INTEGRATION_TEST) return
}

if (!usableGrid()) {
    if (approvalFile.isFile() && approvalUsesCurrentDetector) {
        println 'No live grid; restoring the approved checkpoint instead of redetecting.'
        runWorkflowScript(step4)
        if (!usableGrid()) return
        if (!validateGridStructure('restore_approved_checkpoint')) return
        if (!validateDetectionAgainstTechnicalReference('restore_approved_checkpoint')) return
    } else {
        runWorkflowScript(step1)
        writeProjectIndex(null)
        adoptAutomaticGeometry()
        if (!usableGrid()) {
            Dialogs.showErrorMessage('CoreAlign',
                'Detection did not create a usable grid. Inspect the Step 1 QC output.')
            return
        }
        if (!validateGridStructure('new_detection')) return
        if (!validateDetectionAgainstTechnicalReference('new_detection')) return
        if (STOP_AFTER_DETECTION) {
            println 'COREALIGN_DETECTION_TEST_OK'
            return
        }
        println '=== PAUSED AT HUMAN REVIEW GATE ==='
        println 'Inspect the live grid and qc/01-grid output. Add TMA correction / TMA mark missing annotations where needed, then run this one-click script again.'
        Dialogs.showInfoNotification('CoreAlign review required',
            'Detection is complete. Inspect/correct the grid, then run this script again to approve and start/resume orientation.')
        if (!ALL_IN_ONE_INTEGRATION_TEST) return
    }
}

if (!validateGridStructure('before_grid_approval')) return
if (!validateDetectionAgainstTechnicalReference('before_grid_approval')) return

System.clearProperty('tma.review.status')
runWorkflowScript(step3)
if (System.getProperty('tma.review.status', '') != 'APPROVED') {
    println 'Pipeline stopped safely: the current grid was not approved.'
    return
}

System.clearProperty('tma.orientation.processedThisRun')
System.clearProperty('tma.orientation.exportOnlyThisRun')
System.clearProperty('tma.orientation.reportPath')
System.clearProperty('tma.orientation.reportJsonPath')
System.clearProperty('tma.orientation.runDir')
runWorkflowScript(step2)
if (System.getProperty('tma.orientation.status', '') != 'COMPLETE') return
publishCurrentRun()
int processedThisRun = System.getProperty('tma.orientation.processedThisRun', '0') as int
int exportOnlyThisRun = System.getProperty('tma.orientation.exportOnlyThisRun', '0') as int
if (processedThisRun > 0) {
    println "=== PAUSED AT FINAL ORIENTATION REVIEW GATE (${processedThisRun} cores changed) ==="
    String reportPath = System.getProperty('tma.orientation.reportPath', '')
    String elapsed = System.getProperty('tma.orientation.elapsed', 'unknown')
    String total = System.getProperty('tma.orientation.totalCount', '0')
    String ok = System.getProperty('tma.orientation.okCount', '0')
    String review = System.getProperty('tma.orientation.reviewCount', '0')
    String missing = System.getProperty('tma.orientation.missingCount', '0')
    Dialogs.showMessageDialog('CoreAlign run finished: review required',
        "Orientation processing finished successfully.\n" +
        "This is a planned review pause, not an error.\n\n" +
        "Positions: ${total}\nAutomatic QC pass: ${ok}\n" +
        "Needs review: ${review}\nMissing: ${missing}\nDuration: ${elapsed}\n\n" +
        "Report:\n${reportPath}\n\n" +
        'Inspect the flagged cores, make any corrections in QuPath, then run CoreAlign.groovy again. ' +
        'Accepted checkpoints are preserved, so completed cores will not be processed again.')
    if (!ALL_IN_ONE_INTEGRATION_TEST) return
}
if (processedThisRun == 0 && exportOnlyThisRun > 0) {
    println "Research-package upgrade reused all accepted core transforms; ${exportOnlyThisRun} core export(s) were added without redetection or reorientation."
    Dialogs.showInfoNotification('CoreAlign research package updated',
        "Added requested research files for ${exportOnlyThisRun} core(s). Detection, rotation and crop checkpoints were reused.")
}

System.clearProperty('tma.final.status')
runWorkflowScript(step5)
if (System.getProperty('tma.final.status', '') != 'APPROVED') {
    println 'Pipeline stopped safely: final orientation result was not approved.'
    return
}
System.clearProperty('tma.presentation.status')
runWorkflowScript(step6)
println "Presentation export status: ${System.getProperty('tma.presentation.status', 'UNKNOWN')}"
System.clearProperty('tma.analysisProject.status')
System.clearProperty('tma.analysisProject.path')
runWorkflowScript(step7)
String analysisProjectStatus = System.getProperty('tma.analysisProject.status', 'UNKNOWN')
String analysisProjectPath = System.getProperty('tma.analysisProject.path', '')
println "QuPath analysis project status: ${analysisProjectStatus}"
String presentationStatus = System.getProperty('tma.presentation.status', 'UNKNOWN')
File completionReportFile = null
String completedRunDir = System.getProperty('tma.orientation.runDir', '')
if (!completedRunDir.isEmpty()) {
    File completionDir = new File(completedRunDir)
    if (completionDir.isDirectory()) {
        completionReportFile = new File(completionDir, 'completion_report.html')
        File completionJsonFile = new File(completionDir, 'completion_report.json')
        File finalApprovalFileForReport = new File(stateDir, 'final_orientation_approval.json')
        def finalApprovalForReport = finalApprovalFileForReport.isFile() ?
            configJson.fromJson(finalApprovalFileForReport.getText('UTF-8'), Map.class) : [:]
        String completedAt = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
        def completionReport = [schemaVersion: 1, status: 'COMPLETE_HUMAN_APPROVED',
            completedAt: completedAt, image: imageName, profile: profileName,
            runDirectory: completionDir.getAbsolutePath(),
            orientationReport: System.getProperty('tma.orientation.reportPath', ''),
            counts: [positions: System.getProperty('tma.orientation.totalCount', '0') as int,
                automaticQcPass: System.getProperty('tma.orientation.okCount', '0') as int,
                reviewFlags: System.getProperty('tma.orientation.reviewCount', '0') as int,
                missing: System.getProperty('tma.orientation.missingCount', '0') as int],
            finalApproval: [status: finalApprovalForReport.status,
                approvalMode: finalApprovalForReport.approvalMode,
                approvedAt: finalApprovalForReport.approvedAt,
                gridHash: finalApprovalForReport.gridHash,
                orientationResultHash: finalApprovalForReport.orientationResultHash],
            presentationStatus: presentationStatus,
            analysisProject: [status: analysisProjectStatus,
                projectFile: analysisProjectPath ?: null,
                statusFile: System.getProperty('tma.analysisProject.statusPath', '') ?: null]]
        completionJsonFile.setText(configJson.toJson(completionReport) + '\n', 'UTF-8')
        String projectHtml = analysisProjectStatus == 'READY' ?
            '<p><strong>READY</strong></p><p class="path">' + analysisProjectPath + '</p>' :
            '<p><strong>' + analysisProjectStatus + '</strong></p><p>Choose Research package and run CoreAlign again if an analysis-ready multichannel project is needed. Accepted processing checkpoints will be reused.</p>'
        completionReportFile.setText('''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CoreAlign completion report</title><style>:root{color-scheme:light dark}body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:0;background:#f4f6f8;color:#17202a}main{max-width:900px;margin:auto;padding:36px 22px}.hero,.card{background:white;border:1px solid #dfe5eb;border-radius:14px;padding:24px;margin-bottom:16px;box-shadow:0 4px 18px rgba(20,35,50,.06)}.hero{border-top:7px solid #087f5b}h1{margin:0 0 8px}.status{font-weight:750;color:#087f5b}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px}.metric{background:#f7f9fb;border-radius:9px;padding:13px}.metric b{display:block;font-size:24px}.path{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;word-break:break-all}@media(prefers-color-scheme:dark){body{background:#101418;color:#eef2f5}.hero,.card{background:#171d23;border-color:#303842}.metric{background:#202830}}</style></head><body><main>''' +
            '<section class="hero"><div class="status">COMPLETE AND HUMAN APPROVED</div><h1>CoreAlign finished successfully</h1><p>' + imageName + '</p></section>' +
            '<section class="card"><h2>Results</h2><div class="grid"><div class="metric"><b>' + System.getProperty('tma.orientation.totalCount', '0') + '</b>Positions</div><div class="metric"><b>' + System.getProperty('tma.orientation.okCount', '0') + '</b>Automatic QC pass</div><div class="metric"><b>' + System.getProperty('tma.orientation.reviewCount', '0') + '</b>Reviewed flags</div><div class="metric"><b>' + System.getProperty('tma.orientation.missingCount', '0') + '</b>Missing</div></div></section>' +
            '<section class="card"><h2>Presentation package</h2><p><strong>' + presentationStatus + '</strong></p></section>' +
            '<section class="card"><h2>QuPath analysis project</h2>' + projectHtml + '</section>' +
            '<section class="card"><h2>Audit</h2><p>Final approval: ' + (finalApprovalForReport.approvalMode ?: 'unknown') + '</p><p class="path">' + completionDir.getAbsolutePath() + '</p></section></main></body></html>', 'UTF-8')
        System.setProperty('tma.completion.reportPath', completionReportFile.getAbsolutePath())
        println "Completion report: ${completionReportFile.getAbsolutePath()}"
    }
}
publishCurrentRun()
if (ALL_IN_ONE_INTEGRATION_TEST)
    println '=== CoreAlign all-in-one INTEGRATION TEST COMPLETE; approvals are test-only ==='
else
    println '=== CoreAlign COMPLETE; grid and orientation are human-approved ==='

if (!ALL_IN_ONE_INTEGRATION_TEST) {
    String reportPath = completionReportFile?.getAbsolutePath() ?:
        System.getProperty('tma.orientation.reportPath', '')
    String projectMessage = analysisProjectStatus == 'READY' ?
        "Analysis-ready QuPath project:\n${analysisProjectPath}\n\n" +
            'Open it with File > Project > Open project. Core entries are ordered by row and column and include QC metadata.' :
        analysisProjectStatus == 'RESEARCH_OUTPUT_REQUIRED' ?
            'Analysis project: not created yet because this run contains presentation images only.\n' +
            'Choose Research package in the Config Builder and run this same script again. ' +
            'Detection, rotation, and crop checkpoints will be reused.' :
            "Analysis project status: ${analysisProjectStatus}"
    Dialogs.showMessageDialog('CoreAlign complete',
        "The approved workflow finished successfully.\n\n" +
        "Run report:\n${reportPath}\n\n${projectMessage}")
}
