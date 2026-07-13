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

Open START-HERE.html for status, QC, results, and instructions. It is the only workflow HTML file.

qc/01-grid          Check detected core circles and coordinates.
qc/02-orientation   Check rotation, crop, review flags, and machine-readable run data.
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
H4sIAAAAAAACE+2923IbSZIo+M6vyKJpGkgJgEhWqS6UKBnEi0Rr3hqgSuJRc2kJIElmEUBCmQmS
GDXN+mk/YG3M5v18wdqa7Q/MfMD+Q3/J+iXuGQmAquo556xtWbeYyIzw8Ijw8HD38HB//vTpSvA0
OD1sB504yrJofBWP4nERnKbpMPjH3/8t6BbxJFjfDHbiIu4XQT/N4jx4FkR5nlyNg6ssGQT1LL1r
wJdhCLAQXGc6zoNkHPxlehIV18Fa66cguoqScV4ExXUcRMMsjgaz5jCNBvEgOD7cbZ7u7+21RO2T
ZBIPk3Ec1PNkNBnGwc7u6e72aTBKB3G4iSWCYL0V7F8GUTCMsqs4yAAzwByKJnmQx0P4GQ8awTSH
F8XLIIVGs7sEfkXTIoVm+1EREybQ7xYD3GgF3X6WTIoAccvpaxZfJek4iApo6DqObmfBIL0b5xEi
Jap93wreTpMhVIiCcZqNomHyr9Cl8bQ/hOF8vtM+2Q8mWfobYISgLtOMRjAY0GjCKwEH/msHoxg6
A5XTcbO9Z1aDXkFfBkE6Hs6CIgXE8v40fp5DW8EoyfFTAX+mEqsfWsFxkU+bxTWUvE4JvfEguEzG
8NRPx2MaoGY/HU3SMc52b5j28qAeR/3rYAuaiQnLUIB70Qr2kmERZ7ko2JsF8f2EgHB3Ihg0UfjH
VrA9nOZU+izoA/QsTQZIDoh4etfsASq5KPxTK9jNi2QE85EHVwA7GiIdTUdjqgkwGjgTY+gxIA9l
CEOAEtwlQFgw/zCXQzWEw6gokn4c5GmQXyeXgN7zHnYPKuQwdzCyQMCAKsx+PJoUsyC9jbNhNJPo
/Azo0HtN0YDHcJhjB2H4hkPosGh5FQc+GV+tihEg0laYAOnA0Ocwec0E+zFJATeoTOtlkuaJnHta
LnE+HRabQQzYzARpyJGFknmARNgvpjA4PM0NRgIKIOXb6w8GehDfA2Ivg7zIoG+EO6xVgDZ4GRTZ
FGhI4K4w4UIwp8OknxRYIMpu4oFE8BgXEK2/XKy/f/z9vwcfcqySwPwBJGQVNzGwirtr6Ch0hLsK
lCtWO/IDXFTXSZxFWf96JoG/xS5fRjAC8B3XLy9DoFuYtHEaTKIsGsGYZMEgiYbpVQt4E3QjnfaG
SNwprs5xfIegYCrTadEAFlAE3ffHHy/aO7+2j7Z3dy529tsHx++AtqH/MRSPMkKmSCdBekmP3Cwi
9XxlBfhOmhXBl+kE+FdrmPRak+H0ChhYS2GTt07k4wGMgacKkPVVnLf28c9OVESeImkPlziCKq6P
+XlOKT10OzxgwL7ewSh7qjDrylsd+tuJv0zjEo7pJB73b1sFcPq8dQw/tn9Frp+r7qfZVas3A1KM
+6kszX8ukDZbh1Ghyv4W3Ub33OUk5T7vH5sfW9Fd0Xob5Um/C5N3E5e+bafDNCu93UvHRellJwYa
z4Ds3sPiykufCYvW2+nlZQw0T7goPPMiAhbh7RrzH6uHTx9dLRldAePuQ80VoOqgf3kF3cUFshV8
DcTjTTxryOfLaDjsRf2boPk66M6AbY5aV3FxkgG4rJjVqagsEwYPEug+cDUXIoyFCW4F+VABDOUr
cKNimo0DqBRfxRnScB7Dj/qiBoE2uIF6GGLjCBH2TmDB9dNrYDpRb6hYS6jbUUg8rCiEd2i9lnAe
8Ou5aHNVxpqf/ysRf4uCkIt2D17G0biEt4Dzlj8zyuLH43CG9ldEgyz97B8fXbQP3h139k/fH178
utvpwhvASxFYvVaMopaWK6LhVZrBLjH6FXgVvKg1CMNafpOMm1C0yUWbG62NphBXmixX1MKVlefP
g62tLRSzutE4gR2xfx33b2gnQmYpJDdaaFiQBiuRjA7Qgl5uT2F3GheK/dXDleQyqBultkBOGsIW
+5Uw2yHunreQ8e9mWZodwgYKZes1SzqtNYLaUSqahu0C1x2IO/AvYSaFSUP8vEyyvGhBr/QMienN
4wx3qi2NOk5Pl94CukQBEc4+F6S5S+7j4TaIeb0swnGWxa4jkKqGwMNzq/hhXEQD6jz+2uZSuV3p
CHYRrGXAaMFmjlIskF1SYE0sU0dKXhErZmd3r/3h4PTiZP/T7sFFd/+/7V4c7m93jo+6TBRioRBR
4E7cEnss4d8FwfEw6WewQ8BwrrVeDEIJdzo6ibOTewBS3cIKLU8aTl2cBopnNHgzD73NoM5thVgH
O9eGwYLxH7i4wTA9zFu2DgZzEH4g0vuuLgu/hk6vAYi//U1ylyQ/io7kd/vD/hhkz6SI1VfZ9ATW
XTEcB6sf252j/aN3mwERe3AdkeAyzQnfCfYKuypJ5iUqT9MRru0nX6uRfgj+4/8ePZ/cK6WhSfJ+
LOXl1upj+o8ruo3aDy97qaE0YWODlSJID2VHkJ9BE+hHeQySax6PUUC8RVUAAJA6k6AKMRoBJwCh
EETR3rQAMZCUoiAfJiAjgvQ4I+XrfRr3r3MQyHY67b+8aASnx82TznEjiIt+y10BB2l6kx8xVia/
HcM3yV7lK/hep/dvNoNaLQTOeZDexdk2IF03l3kwhoU0xh7m9dogmiQ1mlnz7TWjWP4wyKIv9HYl
EP+ZX2EgYZsv1wKB0vc6nxVpNTCaCw9uYo5qoWBX2IN9MUNbwefzFZOBtFA1+ggMfx/lfxg/HCAQ
CuTY4QKoGG4aS6BqE/6rV0GCZKNoHLcDixEb1gHcAlZ1UVoFm0DcBtsERlwPH4xCO8kIiQuWuV3y
YzKgosF//rv1/n2cXF0X+GFyb4AhnhHgykAwYjHIpWOUk6w3qD/5ag0bVgWoIVY3P5i4SrLsSyBK
RwO1T6wZrG8M4AM2ZPw2OLrZyuekOA8eHsJVFheVhO/uSe/lB7F1SCNHu+gWUYayoFYOaAsbshhw
CDrbkDefrqjCegbAkUKMhMW6Aggntwlu7lsomeQxcU63ue8Eo//Tn1xMgF+2x6Bni83RVwJw6Rzv
w7fvbAGA+pXR9umvwKV4n0KLw14W9ZEV5EQgb9PpeJAL8gFl0HwriScMnqsVWFfbUIn4oLbnowJC
ICpHTWFGm8zaiwGMgWrUh+prLwJQt7qqRMWqq16KymrZf+fHNVSgJaHXJI0EkZpD5PegIgb5CORU
ND712HR2ifOPDIFVzZe8LyN7Tgraspg1tGrIRXBqD493di+EKW8rqFn2xERToWHMq8Mj7DSo7A3C
mgay+2m/ewr7LYKxjBAo6NS4te337aOj3YPuBe1ZUPDINMmphUwWNU87qvrhbufd7g4COLSsc2yI
0YDqLDu71dsHB1i3DWOlikJbvWQcD5yi2x+6p8eHWHp7mhfpCOh6EveTyxmM+DC9A8iWQrC7c7F9
0O52L47ah7tYC+eCTEZ1yZ1CMRb9IQgcyG6OL2lnTXu/WYqWogOxa8J3ybO3sWo9fKNlUCq8WCwz
N2GgFK4l5BDcSjZIZgeBKM2al1kcSxtQTurE8+dY7hTIjI2qwTDqBXdpdnMJI4GmoelwQNa8KL8h
akwFLCTQYjqOA8NOg6CQ/cOW5TUKYRW0ChEt3KG10bQu4YiTgYmInS1MLcU7K6xMQm80xG/UatqD
22jcjwes6dQazGA1I25/OD0WK+Ti3S5oMqedMy8wNGPz+nkXp9DLbAbAsA8OrF1YJoft092L7ePO
LmIHIHc7lSClKXYbJhJwpOHzA+4gamJtXEgKduEauigA7yCavIbkXlyC3dnd2+3swlCq7l8cHG//
mdZfGeUsRvsODOhB2r+B5VQazw9dzSsu3nX2dy4+HB3swpLpwAi3j94d7F50dw9oLQVbbCMoNQJi
7K5gL2hq+zAGssg7kkVJfllu+7QDy/ni6PhISuUWIt7+wFjkxVE6FivBbNY/C/tHMFzcsYP22fGH
U3gDcv/hCXQMGWXlRINGE2cHRMolyLufTo47pwz1L9teEPE92sT+0ld1xZ5MSHV3253t9xc7xx+P
um1EpayP2oTRjVFs2VEnKwD1xx9QO7PAdnbfoQnkpL2zg4OIBN1dBFlsd9FgAMOIRI0Ut2GCPoQB
o7Wx12mzjWXvuHNBcweErWfK3waocKc4Z/EA5wj3/IIMLbD9/vBi2UYMEuH2JCEualNTSqn1n+a2
DqP59sP+wc6y7bXpqC9WTZymdOQl6HKt9b05oO1PoHR2uzhHVndpEk9g0nY7v+4ubDS6P+QTCt3m
CUwfijrU5NqPdpPaRna6D8RPGjCSx/rGGv0Hc76C5lEcjM7ur/u7H2HkgWsdn7axVlejyAsJqn7/
o2xg+/jX3U773e7F6XvA//0xjFx3u02EDRO9JovBt+0P/kI/q0J7x8enJ539o9OLw3bnHWCzB2N0
jCx5vfXDhjlnsLXvvzsCNHUVOaAEc/37BaWR3dtVNK44ZqrK9u4R7AoXx3t73V2nkRcvvDWAR7AE
Uq6zbnSWR5tGpaoTL+aWLXfh+59NhESFyg6sA216GkAR8KL74YQYnQV+be1nZzqp7Pw5NSCLHdEP
+4VLKqK0D/qP1sB337c7ztx2T1HksFv40SIgWmxQlsjXM5Q/W9QgS1O57kl721zBpQ7zAgcS/rMX
9NoLFjzxxBBeGJI/vabj2C1xjGFu6PAelvf6z8I8mg69xfiAGEv+JAtqeeXwsML+aRciLkJMBOtn
6UTsEnvAbtKsCoRbDpFt/bImwAD63f51PIrLZnnZPf6OJux12IkawfetFhqmRW/n1uZeawDtRvC2
EWwzAKEZCBUDlf4/x7N5pwNGSQSmLE2OKY2sTlRpW1fQpmrVEKgf7LxQC96UdKdNZvblKqBQWuVR
Waos3CelyCovNKZNW9nj0aTSAmkYgrljYZZFsqrVzJ5ryWSeLDAw5ZefW5ImesNp1k2uRtJWUVnf
Kqg28o0XAo7y5Oj2o/mIpEU+PbVKs0ggMYIN/e0w7S3CRxRrC2OGwmhNYgR79FJwuJgD5wUNEK+a
+DaJ70ApgPWpLUd5hWFLlxDH5UIhbaFvC2rZX01tt54UIRKPT2P+05/E0YpliwIdFeUE6d2CSG2n
03Gh0SHm9ZSYExWFgeqILnjRX05SB2y+8+qSb6h7S8stvHwO8XgfRh4dQ/JGUAc8Q36ZofmoXtm9
p0tIxKHWFbJyx9Gu/IEOPtQQCIP8vGkWVtjg9db88WSSiQ3NqIJOhGcEnjnSdHIFkiHNyVyzPp9C
a0Pzo+yn2eBOMsp3pfOTtJG6BYX02u7340kR8cG3tqb68HdsoRUoeWqK3uK4oh9KPRRjaYEpdXwp
QH0kDVhR3yVo1hV9qsvz9DmDUgEdfyoj5ZZYSoaFc041aReFWrTyzOK+wfaO3+tgzWyuXveWalaM
Whg811ZhX80weLWlgNMyXEoZEoc7ZufpcKYT09HzHGKRnamcCed7eaCcAs5orbvfHTKC1ao4zRJT
3tAjX2ZIHgSWZEbm4IFOvKN14kcO43eLxnGBTee/cLTVSD5q/JdYYH/4JDnmjZCdQB5jmYOt0b82
cM+sONkQXNTUP2RzvBuxBrLEoLE4yprI4uFbeQhAhIyD39HHKhL+n7K3KzSbPsHFPNQjWzyCRCu6
5TVZ12dQLVCstq/TpB+rEvXaSOgnO1KeDPiNxWbxVSP4bGiZDXsQzoMoD7A5u17tfXqHxv84x+WZ
5NfSJZzPkWzUQAU18BKaaq2DI1sv0KUYvebz4B4EHXSiRQeNEEqwAFZzMF4H8RdUR7JLoTvTdNSL
M3REpZkSjrJLIYJzhYhss04c1PvpeMAuvdg8fn5E80KzXoABC/oWEo5qXdsmH3fxKqhPpuP+NR3S
M1JmcSg9GjkIrrXWB6wkNPhciwEogE12h8dTRnRIR8/sgvxBF2Lq0eBr2/ASaJTeInpuGShyL7z2
1aGIZzy/Z3RPhlEfnbeTrI8OMeiysyWhBy6cBeRvWQyA1vAYjFRUq3lVChaBaVewjQRViwBmH4YO
XVuDaDi5hgbQ0TXTjS3AUdktNBn60VQF/0A0+1Z7CzC1DBm5zVb66q3VbMn2AbhbxoaGa+1oWOaM
hmusqOrex+sECFwdERcp+U8pAlf6tNNHNmiYs1GyZYjDZOnhNSZnwzqed0fNPEbWTCfFjZLJxMHw
g7qAklxqfDTKWwG3tGgJukYVex4sM4pbFBeid+nReRGuPXIG0jBoAJGT6bs0i9Cz7DCI21t4AcsY
3sAv6+tCvsD8lVGsvYumIA+Cjogwgl58iRWVWWcJ3lW4Rp2aMvMEuXhllyEE8SKQl71uMF5k7uPj
fCCMQTJ6npPbMntmvAS1+govf+DFDCC2fplrlRA1LEyI5WEyZiJGXggvjM+EYPkm0bxh7MQ4keIe
EvmkxDjF0Zgvj1wKGxPuZqSPG7AX4q0tWoR3dG/jrT8vhzdj/GLNgzhdW/Phjb7Xym3HuM9xNU1a
A+EgbTpKK/T5JftKC6e8pryz9ydxWw86waJYGDo+GiumAMll2F/ZYqG/RsNpLISy0JQsdQ1TOhHF
SVayJMu5xUmikcUdu76u6EyeqmsJIgKKx7q/EFBJTlD9VUb6RcOkN2/V+aXr6k1VOL15bPCLYDjb
nUCibBfXcJzdROJibykmQpZpfNGYlhg/Q3It5IvA2AyaYZSs44uAOLyUodiW8UUgTC4n6lsW8YX1
DW6j9EbHr7zL919xwaF7Kf59eMkX6558xXX1ABLlk6+4ZOA98SH8ZS6Ch2A0eum9RreqGA2ve0dz
NB25lH/grulpB7vrdDygm6Xj1HDb0xdw81TyortkOAQwfISPhveaAk6srsr0ElocVQ2MjQfwzydf
l9BuebQW67UPAV5VjqOBvBsIStVlcjXNYu+4i24J18de3I/44nEwROUd3f1gEotYDLjs8jK284re
S/t4cB0PJzCh2lmT73FO2HCEHshLmd0fQnLipzlCpzq8FXuXJeKOtOVdKTphmDnmG9aqjWo++nLm
daBcxKlbf+wcN/QG7plTk2yzuIf+Lt/e9ZIl0Oy7eQAhC5bM9EsZqBcNJ15LGUVj3+3fuh4yG4eH
5/qLicRD6B0jc+FYy6I8eEvRv49GFPWz3GXQPvOj5ane3wWkeWZvGehN8GskkTfcV79v4a3tdHir
XIAF20PZU16IG6fj9uU3X9hY/rILcdBxK8fO5QixXosua2FoIyBudNDZremGqRE0i7eSnK7C18Pg
TVBfa7VeeW5NEDJkxgs2rdrWHYVSOzAQiLJHqNly1WlJAZWwfF0xrbDzGwEVfWELizu/fHtoMpAN
UmnjgogacIs74NTOs6+Wtmh1F+0otcNQlC6vvAxyLV/wGsCNKzJ81Y1t+veOkYRhSTryv6Xvflq1
2DC0qJutoHuXoLN6kQarpiP+KhqTVtlqsdqq2Zut0IsU1ite3CsHxJhYyTvMurg87qIxn5a6Mnkr
nwyTol5r1EL7HigwhBEevxrODiBlMGoMjXiKYCWSj8j2ksG99tzhSUI4xH2OL7mF+Ms0Gub75MpP
DIZuhokmJD0ioNdbwVpY1X3kNIN7fayL/S7flezbhqnak6/Y2EONRBBm5XjDLL9JJhOMs7Gi58G8
kmS37V9Fj7tafBsNgfvb6OHOiRaSgUknBo3g3qCvtpWtY3gZzOUMfOesoh9zr4sZV9M6HAqmn/Id
WqNFK5oMNeXjlYsakhveD63gAK2sQubM9SbHhln62M746ibKNGOMaFBfb7XGLhWra+6wnng/Iouu
FwINLnEXG4xL3HlPHC8xbHJQpjvcZiGk/0S9ubtO8I4RebA77Ahb77OWXa/zOWi91q6h7RYGKQPa
ehbU74N/CTZ+DG22kfdaeGs2K+prDbpvmYUEyS6FiNxDwWKQ3NYBBoh06zYClwj/Fa6xXhZHNw4f
Ei3pkVQ0SD5n2PnyaKqQEzlbIcQI8i9LeFiv4abvTGx9jJu8NVPwSjQJ8rPRoIWA8EFS1hDl7VhZ
gQ+tlAlERSP4tv8kAetFKSm43h+mOSh1eRgsqPyNLWNtNL9q+wRF8gkuhynGlAKmM+Nr0JbPHs0Y
Ffl8HmDsAPMGL/5uDePxVXGNggXQh7hkRS7uWIqBj5LxrwBoD3+0To67+6f7v+6iG9r+0f7pGRkz
9fej3Xdt6zsDgt0RaR8WGzT0Ev68Cozm4cWzZ+bC4YZvafMros/JubVp3EJtRCqUqN06n18TTqHE
7NZh9/T61ZaAITqt3HHw64pcueO3sAQBxMaLH+U7GMlrEEMEj8AXVOjcvFHKl5q3GIEmNfRN44BF
ewmJ7dqDAqDVGS9Y6g3ttoFcoi7GSzSKfkaEC11DVZVCg9NgXz5DG+fPnhnDNExhcReopIk5EBia
ncynIxQc0Lmtqm/UouiVKP4M+CYgQ82KeaXG7t4qSBo+vWMKo5v8a2pienFevKWRcVovuPVCt17Y
YwoNPdvi9gubrhAFWgd4bT4ZT2P1lTHcw5hPNCZNgGJX3RNVbRZLXYDWCtljo0XRS+ojlXtuApVf
9+hSMg9dk8rhnN7tuQVxrQD+xmVjQAl+AVwghb3QfPauFtxdvuqRvn1pDLEUDB/MeAi09mDvksW0
R5tJaNAuEaC8uQvcEqOxwXZ5knI4iBKPUpF8JpJbYUWQpIS+J0kgR4XbcGRat7xTAWKaWTzuecA3
eATxL16LOHPYyGM402skAsIVJFeX8eB7U6oUI7ku+S19z9NM6jiEHYna5vLnpa5YAVdin1fNENjN
yv2I0zGRq1+0rsePDpewxmdolE6XxYQKY0UHrTGRCmUIQvUkSwfTfmwHLGTRsCmFcX1e2gp2I30y
jRApRFuEkfB6MwyMmFxdGxTC0XbQ8otRBHsZWuU4mhweMqItBhCEIYiKuCX8v6PBiQ51iISVZ7eK
nga5eszu9aMOHZXd6cfrhkR0f3Cfq30TZ8SZEKa07N6Ys5m3xMwocWeX6MfJsJ7dGQWufQWujQKj
q4/IObJb466/+fW9/mq5ZJHaJQRCY6e5I86sNS3q5ZpDwNAxt9411ZvpFzNfvXvgFNDEa0I75O5T
D5qyTQH+GUKkYu9DHgTqSlM2obHHCCeMDz2RHkkg+f1rURN/3UGpdXq6xifNAAu8ck77+D6oA1cR
yIxXU/S+2r1H+yqGnVgVAe+ARrWnglgCGDJqWuTJgK3bCccqud968jW7f2gEM3yYwcMdPtzBwzU+
XD+shoqtZQwd+mmF12txaMn9McjS435cFxPJoU8aRMkJkHCCodqAaJNrDRF6LWYelwNDrYtmjHYj
DKvJw0s3F+i3QUE4Q1zIR1/X1lebvkjdgt0I9zwtRgJzR04gxCZ+NT63vveml77PtN1joE+rzSPY
CvFlXfeJgyRtay8SYwHbdg40OfCtDaAHhq2J1QZj8Ouy/VgZIMbIocQJT+YhGKVK4+FDdBslQw50
MNaEExDLA+We8HlgtEIZmcnGSZhm+pbuqsemS4eiOW4T8D+gD2RmCYZXugxdwSGXx5yljbkOxfGa
zi+/vDDOzrxiXklwtbdKAGTulM5u+bVkiBOo4Z6iNnjY6cQGBRs5IR2W6iGBofALHKlOrQvmmX+B
LRWh2VUeXJOcuS1+RmCbBBKGcJNGcRMHMoV9KBl/2qQFyD/O4MfsXOySRxFJNRwEM7BcdVoBxaHg
SiBGUjivZm/W5Lhe77I0vZ2h9HmbDqdINwivSNObYMQePhGqfDAU0yKW4fOscFkvZZw6GSg1v04m
ucClgdDyVIQyzoOCiXTM+JL5FlkfR2rB3b2fTmaCojGobEDRCTHCxXWMoNRhHg11k3RQ6BIHXOYN
+Ur0nhyfynIe8Rn+c612XvKPMvVUfvFqS8RZE/ODEOQFJTSoCc4Bv+rXRPPbv158v7G3Lbi9HZzE
iFDamkw5kF1OCiwoh0WDgIfe0rJHHMSYSxOCrinPqhXfF+iUQy3kWEmGPrlEOhiaqMHHFhoTYsMS
Y5DVD00VczlQMZdzIKy9NC2IL7HNkWeRor3gcToHQOaowAgtovNDEwbRk4gIjCF6pVMZBtSE/emK
g4Ogn6QIw0wh22ALRHATip9BLA3oIwryGH2HYWSweUHWGLsYI8DNmne0XaDTLchFA4qF3DIuSb4l
tyaTWngReqmFzSGSXJAW8qy/gBYOKcCyMJEcMunIuDJMTfmNAUJXYt9Q7ycM6ur/osNWu1/5Uid3
Vug1ZH9EUGRKpw1aoUa2LgHM83lpCocBatCQWnKQORpmZWS1UQbv0Z53OpvEdS7cwIFuFfjC2BmR
nGCL4zZMqA0aVZiJw5OLd7uhZeEYH8hxVbS9rUgbm+0WvGwQAM9Bg0epoUe3Efwg5rmroTtDST+V
aEVvDbNueWwV8Mo6ajcktDBMwEvx+Ep2S7wo749sGZ5S9F2W9E10cR+vU03o1vZF97R9etHu7LbD
0jbKMCq2UnGt9NPSbRzs7p2GVWDOlgZzenzih9K7WxrGx/2d0/cVUK6XhvJ+d//de0+XeOWBlv6Z
BnCT56J8KkikcW9GGrXpxGxzLayoP1uu/npF/REJHPgvGcPEMyhLMJqo81dVOqOCbKMVz1jpGiud
z5eFPFsTbcbmilIXTa05yOJhrB0KFJU6q0vVdYejXP1zJTepYgbntrivLmRLwTlRe6xP9CPKkAYr
GSP1lPZGvcsuuT/1taGhP/PPk0fyRSfwna4WidLL4lRwXfmKIh11okEyzYUrqmGiyOh92W70Q8Ow
JnAjGN2wBCvUqtz9mmljW/PY2Pr3QE7collv3RTdmUxLBo0+2gVKVWeLm5z5mpxZTV5XNDnzNUlc
6R79nKG/z8QJmmI0M/wwMz4APUMNFEnRqgBl8DH0BquzjgU2SIWluTEstfxGK88HKUeK7qEK3WMd
WtwKB2IzpBP5kmucOwVvE5C2yCugqqzevWaszA3RntK7xofyQQSWoUEYzlwlckDf4BPGf4ax6s88
O+S9aAOtN707fPDviXSiSe3cr/jWBn032rov7YhQ5CmWe4aIPcV/XsPYe+z6tppK6iNWuIOa9159
dctYi2hAuOXInJfl7QWn6fMQofXuqC/nImNESenUsiAbgrTAxx3G111yUNsKmspWLCTFvqSFNqpf
O2h6qDv27VzGIHgpHl8JEhO/nZlGLyFCnj6eI4ELShJvPONIiLQ4AHDovJ1M8+t6brugmqLP2ooj
ZBx8ItqnTYuem+t00+MApY/eNb8/o/clY8Z0tM9Rg1D5gB/31q+Z+OWe23/HmHr9PQw7vFyXSu7A
3qWTelkkU4WldVL+BgLisa8gRiIzMdqyEk0BT4l6VVG9XNWhOdkbWvCylPQe6N2F5YL3RkFYbYKi
l160VYxj+ZVHdCIOJ41ztX208tyW3t7ja1z+5U8z/DQrfcIhJ55EpBcqChze+4q9ZqoMFXH6is0E
tLNQ0a3TdVGMoZ2FiqR9xZCPrRPPDM0lpSblmWnRtlAFjcBdiMZU+mrNZFvXVW2ViET2ZH5jZjXb
BUzwAbGX0swqW0+JyMU6V7aE7Xs+PYV5f05/q0vORMlZRUm2kRxfXmLGny3DcFg3W8P9hg5Tyy9t
Eq2bLeOO6NQSLz32WOb0PCzPA7J6wrgTPpP0rm7h+dw9vlNCHceNFDEqbVGcm3itNxaX35k7DhUu
fUWLRWnLm69HsQ5DPIKWmKcEaTZcAleXFwaoMTMJ48wLQ5XANdXwqY9y4jbNH/NKzsySHjHenJOd
7qb12yp87jU5kzxJoyqUooWSZK+X3hPtU72W1AbpGUYRVzEtoOc0/6WaM7PmmVHzbF7NYTwe0A76
kwgILk3lK5aeTATb5OJI9ATbXC7PBKinoht6PElPXlB/5tY35oNcF3doCWzqlaGHpRmYw8UrRndd
f1XDYIIm4qYSDoW7s89lrJcrPPlCp6T620nWnw6jrDudUFqof4JCqbyRUL9wTKboHWZojP/fV/O8
OpijetkyqZKgWYB5CX9foTIID7bM/AgtiOWkl/D3FSqc8FBWgn6PogNQoYegmTgiFUqeauJDP+s2
hCyPVYSKiMOIA057eRdHN88vs+gKz43ELZqc77pg5sAJRsGHhRJMx3j6lelklkGM0WbG/ZgCqn8Y
D5MbPmsQjsayuPYdafDdZuH2QQhNx8klnhDisdQV+buQSwnmhsTTWE7n6TktEadw6hwjGuapOCOh
/AHQ8hUuTIQmgqioi0iA/C1HcRdBOThXI/4f+l4kfYovz5kjdWpG047kLPulDUoLTUcu+diMwuEF
j4b2/zONJZmG1kQ/mbrnmal6Gkb5+3W26BJbaEgb+4zf0uP/urwIjS4o07us6JXBifx6bIXO94mV
O8bC/XjG6p3noxhqNftsR78PX8qRV/SEv/GLW/3MqX7WCGZc/cypTl8qhTyt64SljBaScIRG8wkk
MJYzglyoLmfylSV8WVI3SWA5rHyUpPKFkpEQiix5SIhCFVKQI+oYWlJuKEfq+Rk8G+qPfA6ls8S7
aNLsRRgOZr25E/Q52S8wWEAeLy4F6AwJH2/xnnsumW39prkeMn/Hk+lownk5DlFhydLelFJDR+Pg
pjmKo3HOeTgEbIAb9ekeaU7XWeQmUo9bV60GJ6EBno/wKC+uisfVxC1mEogwN2mWh3htE6cgl1jK
U3b0neAoGRTIi/k/v3g7O2C0oeN8xs1dYy5/YzJX9KHij2YUTqSisU1Bn0W7m8Hn84bZ8KbyUF87
P1e1b3C1kzccwVm3mAde6DTaneIdsefB2NVHjCZHVW2ORZtM/SJfKOxtaTbgg2v0uhNvx+cri3yL
qBo698h7N2TwFO6y9LEBoxmBPI+XhUTCPXkcrUzt3LHPESDtvuud4yWiKA+2qRLOsbkHAOY8zb8K
3MXrJVAX9Rh5t1nZr3PW74Du6MJRUAe6bigZIgwmUZLl1Iy+ao03oprrVJSBGy019Y/m+rnhmico
b6wuCumpATjHvtnBKnO6iZ9FVyUEMVHqxMScLFnGmC9397qKjM5EZmci++hSlu8Z5Xtm+Z5d3k4D
K8njqteAJkMe/kGcA3VjuBZxEOohCb4agiJnlCV0SVPtDjcsWtCghMadkZ5ZWt8cUW/njK8uJEZZ
w+JxNka9NNq6rAwHROjwkjVROTe+8ui1q8sEfc7IKtKz21czuMXZ/uB+/uWQktegMMebAKzuk8sm
ajLGABiFz13hxUbSkSeMiiXFh0QKYwx4mG1wqiAPpV7I/pIPanxu2px/3S1GplciwT7lpOrF0Psb
5NljfFD8AYkRHQDVdpMHvM9h1EUBahRjHMbc4V9TFTlTvLsxJ50kCJM6b84fMXcI/HMkR4t9MB1W
Z7iAcltGeev2kfR0oruhfGXzxryy2UcWL0D0zykW8xtGAH49N75sykDsK97NSzw4Gxj/aMh52pQP
UmZBSeXPUrRAGcG4sYDJXRO8BxHxhYfHSAg3BHJ9pywW8B8QzPYpYqUhI/zKglGlpCC//255waNi
+AQFbu+bxAWuagkNdMbJI2ccclIQAG44NG/LKDrXpFMmdR8x35SIWV1AAupDMyjIrolhGZXvbkrW
e76tM6HbdmLsrYMWOlJLfbdDoKZd7Drx3ACxSsmE5xwkCptuAnBHVs8dsYPH8/MwPUckoUdQCSGQ
rO4Uuk6wEH61WZh3/xDz54yy2Bxe8tMrScT82x50Gfi+T/n1dED8uZ7n3KrX/9y4GTeHE5kXCq3T
aOcEfofvfArJoXQN1j7YlJj+xpj+xkT2WxlHU8WWUkTUy+u3qCeJ+fvt3OMDhwo3ntMJzPDunoHl
4KXsz2/GjmbvbxKM4sHoJoW1QnF/n+cAj49fBpGxDRLkh4rTvO9kTbpdAVP+unwrcvGGtHhT+haC
eNwWNW+bsvu9/HQrGwDOKg+NnmVW8ulJ7WG/+Q+MYBfq4pmBFgGEyEo7C8gUHIbZYLRLKGFLcMjf
pYTJnqLyJZ8rlS6efdWT0vw7Stn2MiwfPgmZ9KV8pq7xc9nhKR0OuDx3m4uZugQj95nLnXPj+0a0
Egs3WV/4FeMbUbHEXKvEbz+LrSL+sgCrMNYE7ReMLMRV3B1n5zbhS9noIBmjeT+Lr7I4x3BrZK8c
PcXds4dXDGRW+DVSXUHtSm5jvNQyRMY1kwJVCnXpBhBIb2zckYJUFIhICxS3IxBBD4bU7F5SHN/G
mY6c8xUhOiaVmSUkUSgPx//evmm8nPSLC3uWCyYqkq8gYAxf81LdB+YSD75bweKO7qtgwyOHRLdX
LGvZobTQngwiLtcXEpgBzJXCvppCCowdQn1wdJPxRDZkDpJt0tb2bHr6ZDxuzFf4JlWCFlmXcXAa
RC98B/l8pWT6fakNvS9F28LdR/zekL/vTTFSbLDxOEWZdYziGRduMmj+aZYdAQtTE6R2ZYKAk7Qe
N9fXrLCDI2Hfh41XG2vHE3+opxHFYZNonFlonOEsUkNaMBIxAMgsKyqFBviVOZOM5ROyyvf4KhFS
+JdpxOqKqWmIel/Mq1e3Sn0gPQpdAVngL19it66pPFZoF3KzPtchS7SyqTA44x47ff5CFmXPp9A4
91kgbC8WtOcI2TJyiEe2DoVwPTCla494HUr5WszMKB4k0VjewOwKWzVOk9zqjZAIfRKX1XZS5fOu
9WaFAIb6smYBL18ll5dVfG+dFzA1KMa6ailTFDLyNCYjpHi0rXAsvb4WTl7cMLDHgcMY6cNcUqMC
Rj/k3UB8/7kcDIKLM6mwf0uofDLyaMT3ew5YhLZtkyQom+ugZ/6IJFCUoGW4OomMVmLoGAAtm5E5
J4Slb0J6uO57C4v6OG0UVc0SdSSS0n7vM5JgCVGLo5D/qEWeR7DXgg5whNf4emmGOgdeUqdB41s1
xqiJC27GyOELa7/zcZAxtzF/HzZBVdGj0IMW6m0+Id6GXyHPJ9i53yoOMs3jUx4KGPFWlhafjPAa
sDjEt99K37zQZi60sznQzhZBs5wdS6e4ZW9PuXJRxZM6aCjHeVChF4rxT/I9NJHF5JOFUUXFNL9i
MGXqEwUsuYcoaVPtYnVRBu+qv8BckIJYE8xM3r5PcmFf3Rbn9KUtD30mT7S3xFjQteS+Ho4rdsFv
Z7ikCBrclBZC2WwmjnaNgo1gggEo2bDZUEZsPG1gex7Flybfmk2apfOV/4U4fNUIrH9rt9FcPs1u
QRaRJ7voQgRaxhVGF8egM8NRmhfkMGSl32gFgss1JTlIeLzbmufIEV/PzzkzGwdU4HQMdGcMBlr6
+FAKH1DRJaxJFt9SridqnxHFiCTkQtWLKcIenYOTVxMFXCYuTT1pWQJrgidOpgzFVE2ewC9eDPQR
vkveYjG/KX3Amt+/QA2DZlGRER28i1NEnkA3toZApkTxquY/c8ql7ZbitBoWHsUuFBYqK64yXtW5
lqDbsAxE1f28ZgV8k9iZkfe0lwUNh+gPDeuPlLLWdSBa11u/0VlDOaXBFlcYB+axqVhuW2U8fFdZ
8mFa5NWRszh01AAkJELYiUrJyGJkLITiPVEjrNVqd21XFK8WtPxkXE1DrzwDaulywvnjI8HBYDMK
oLVTCEJ8ym5LmzofYYkoNAAKeSKpQqq+lBwmL8RRJO6/Zmc9Wnm5KasCajV2C+xXwmGrSs2V3Pyt
/luA9KqYo1AxkLrRaFMdbqzrCwN+FyOxLOmPuy7NX6bzkV6p+tm40rtZnm8lmEfTIuWAm6RIckAj
2sat7duK0CViY2GCiGxeeCxVwIpgpMKFyZTL3d12Z/v9xc7xx6NuGxMt26k1jnwZK2BEEfPmEF1l
kZ3KLHgcJyUqzNxST74OcgyQq6NCXZG5zgxnVmdsOfgTzyf/q4UM6Lb9832jIq6yTXsYv2Sna3EE
AZmH9jm0GdIq2pAbAWDYEvGczCAvdfm+QSXu+M91Q7ZhtStid2zZMUsVCGzRTq5SipphBAypbJlB
hNbmIG7JbAVGB83vMp0DphqXw3KyT3d2uabQHstvnD3ZBEAXsGlcLfA4rGvfG/srCCgDDOBCJkxW
QUzmKFIp8zZLEB5scmwj2YkIZRoWBm3Wv2TeAjGSJG88+WrqOw9BFt3x51WdR6oEAVg1yiLeDC8q
OpaJEUfjLtI0uIzvSOZqkvc3tfQSxZshrhYj1lBwiWI1xcgyYshL3nS/KZbBTD7cbbpL4nqztCrS
aYbcqHYHxCUDcGHb2G7t3L2ZSyZio+9WEOo+qUy5sb3N5hafWcWFtBINNDU+Za7T2X2HGXBP2js7
mPYV8+J2zTpfyI26vJXdM39Yt4nxy/3GnNK//OKUnvlhz/ywZxtzSruwDe9vtTN9obv3MAp2yQ3T
s8qZVOzQs1KVmQ/4zAd8Vgn8fYN6JIAr2q/fo8H4fh1pXs0UpSGTkvLf/hbUZ1hoZhcivUOyz6VX
Cqb8iXmpkEDwP+PiMBpCT3ZoBl3XoRExVgReDIkBmHZGmVShJg+PdsUVCU6zeD3L6TqFpZYxp4pg
bO5gmHIRJo2yDGGaFpELkSJcjWdcFfOvoZbGIXwGreAUL5KMohvgr9gQRy4N7tLs5hLDVRk3V0RE
P4pwpdLO0hHrTRxPcjefk0IyQp0wjy71qFG2y5aSZ2RPt438WijS/DMkGL2n/0gU4JdofPuXHFUO
HkVGckRLSgN1woF+qTXiVHlNees5kTLystP9g92Lk/1PuwdGkCaNJVnezYu32NxTmzxRRK0GKpar
zlrEA42LhoiBJpBoiOLlacLxymPfIo6VBf9Hi2WPla3WW466UhhaaLVsRWqoERD07lEClbpGJoWd
6F4IO1Taim+C9VjO2XgxcCoZ5K+YsdkeY7r+88AvHwHahnREOTbL7sR3uHjMHQrLyRuhzkt9FcKF
ck0rzAfmzAfmrAJMlKPwZy5MQq8h4IdlZU/flLIKlhzB4i/T5BbE5HFhDKpwVTNWlYWovGYuRFwN
0/gKouY63Sk03r3aUjP+pz/JPr3CZFYv0DBs2QU8eL3GLCBsQvZ8ZeDyp3uHxiOC/ujN7oWbKS7+
PouZtPblNbxgOlZhWDc5F7FXPlYMQgdbfBlMc6nQEZPP43hgZORzjzMeTAssd6lSPnQJ+Ftm7+Fx
hyilTv8zTlI8jfzu4xQVT03BxtMQunHo+/Ybfqs+TqmANpsDbeaFpk/DHneqIusZhyvylXvGIt7/
EUcttulIkLjBPspCvaJh3PB+dHYeaUM+EYZT3/FNuZmntIXNtc+5ZzzKF15e71ULVRjdQcab5lOK
YAdbPfBLeAD+qTMlxxgIF6RnZdcXF3OFqKeN9tEl4uhInBzOt41kioIABWeVkBSPiW4p7ideaibf
vMFvEZ4eiHvSg2km2QgGvjehT6LcNu9LkAM5Zoa5BjYG35CC4oWbhz0jtOv/bM+Zgk15kX0tkb76
NJiOTuKMrCXra2trTnSILxsvFpPLhkMuX35aotJPTiUgf8y5w1bAOoJoYuuejbM0LI6lFJinDHPi
mEbLm8zz4PsfXQHZsrXSMQtphAqsdhalaCySkWvNVHiI6B6FZgy3YDIE8Y8c4basWcLw5a31F7x9
Gu9h33zBvEMnTPNMp7H5Gj0goIDlysrSmyjalDgRVAsvUUZFvfYvre8vaw0TqxDTBgerxmCsCnOT
Z7s1d1kDt6pmdJHwQecg/k6Nm1fZnisW3EU5JY1TIPy7fW3Obk8JN2d5EY9aeVyAxgBzC6yvVoyi
lgijPGs56cWtETOScYWPh7atxsQaoBJQgbjRrs5J+vvSYx1GyXhTqBKYFxH4bFMFC/8npccq5Z5m
/TB002AfUv5r/sh8WNMNaca73dP9w/bpLtnbMEHmIZTtUA7ezu7ebmf3aHv34t3uMbzunF0cHG//
WWcWlXKXGlPU74mrVin9TpBUt6J2JvVnb/5ApBlJgqbdTsFwLCcLlyq3ycvVCHmB9O5NBW6IvHzI
YxYwOZbojHJD8R8LWoc5Wy64p3LPAUYs9iF7YrfNziJjYkgPweQ+qJORDLOzWWcs3sS+pMDOS/lb
yhrJJ1KwMjGmycClhZwCT4uiRv7ozvF+vRz8iy7/kz2Hzv7yT+TUyF0pFZ45hc/mFb5zCguDEoik
QseoqHft1JN2psqKjx5XQ3+PxmNT+1FTlaK+Ah/LA2cq1jh4UBQLbYvgLp8qis7KRc+cohFllIEZ
Qf2WQmTkKMDDQOILSjADM8DPr+gZPl5XXYup6rvHEWLp/OywSNGGpU4c2W6MtF/VmthmVx2vb5B7
OuUU2ntphtnI5SEo6Hm7nYt3nf2di4P22fGHU3hz0d1H4+EFclxikb70wsEb1bVDqNLZ/XV/9yN8
bR8dHZ+20YTXvdg77lxQKwzaUAWUxMS5H0t+CsK2Luzw2AAx7r1Om62DCPm086F7Ci0i8qEtYsHe
5On6HGIV4iAfv1WPmrZWQf235SD/KjUMbNdFfK/ivhpZaHyIee0cvAssnHXVJcyPyzsgjMn2Qbvb
vTiCbe6hZlEXJigl+7nwLl5dtMJLScgBWkP4oT92SetRwwDtVZEGPYHPk9HVp00PF/CWPNv0MAFP
dD8ZO+XkftOkx6TETRuB9VbySg9MFIH12G2Sl225FB+Y0HhuWnmQvbEEdebwJdiNj5D26Gz2EYRE
BGIQTUNkHEFh+cnXuQvkATTTeEDnW5yzNYiCK3gv5W0+ddIZlIREIsiwdKXCTBJf3nDdXQ3dNT41
6M8Z//nIf96rkiLBLfPULs2DHURz4e5vk7os76d3QkjvSWrzLxU6cwudeQp9dAuZp0dmwfduQesU
SZfUYwAVamqc9czX5udkl+deynnH69Qj3E38I6Ort+69o2IUmHlHxChw5x0Jo8D1vAEwyuU2YTyU
19SOmzSujutLw8PM3ZQxDvspk8Zhl2TeOMRepo5DRKWfkDA2nYCUzjmaIikBoF9ryaEVL9+R6yDL
DKkwQnFIJROgcmWq5UE8gDUYZfG4VgT9YTKZwKTX+6AZ09ofNn9id9TmKMlx0ebi/DVslW7SG/4N
1h3LuQedyx12Wus6GnxyAsQxARnn/075M0/5sznlP5rWIpHDkNoVjAQFY0yzWVH9vVP9vagu+NB7
s3rpgOfyEmnpNmai2tEHlvLkTr8qHTtNJll6zxX1gS536Hk1ZDroZbznlbLvl5SbmncQ7PCKeZ30
nhJzH57y6C46HvYK2TuexI7kYSk3IzT8ZPYBcSWaD6byDGqo+9kQpB6slbze4gyrOuilzA9YB2qg
zUW7GTKi1vY395S6Et2GJl5BhDCa/GfBMbU1hLpJ1j+uWncPwX/+u3i+ZjVcDyAlfJ03iOGqOTR/
juNJgNJ6KZCoMVh47mQ6bwB7608t1qYzo70EaQNPA3R+vnyCXc4ppil6K2J+PvK67w2j8Q1IwXQT
2QpdT4E6QU7JZiKY5wkf2quTc5ShRckQE86M4zLLsiFQinf30L7ciGcCPpSGJheBhREmTkq5JWcr
2WgFlKgPrxpgHFaGlAT/+N//D3kcl06FVsA5crxpay4mZFGvL7bghPN4SskSobxUjWae0kFMFz/t
yTAlVt5ANHeT97dPMitEXkJhXxXhmE6TUXyYDIdJXpaDvt3ntTRhb8V00Y13Kgo7vKjyYPCaQROW
DjCCJ1+r0YQ9hPryEIzy1fmimNV+kN+ITb2EgHI7C6v41fctIi3D26QuclKWw/eWyD71EbryTlnx
Rp8V5f1uwVbfbLyQ+LHig2TnT77ytweMhR8RO7chPoT2yvihZZw06jMKi/0Wa8tR0rf6L1dqaxVe
u8vQzBoTjNNVzm2JHJrORuoWLVKmUhAJo9C/+k2n6qXX9u/0t/b6XDtO1vABx9niEiXXJbdOdF+q
Y9pzlnHOpjg4ZacZj6bAw84aMdvb31ozq06rBmKC6zgNOM2fSddGuKAowDO38QDb0/1//F8OKbeD
MbDkQbDThgGWOdrxThyd0IAqjqGQePVPoiyPaVtFxRxPKckSMmuZ8Dp4AAXKRT9mToZBVVFBuhxO
UcHo0/mdOJPXWZCFCyXe9UHfGxMgRWuF9yK3PF2vizAqLJ/lF1k6vsIE8GSbkieIIKgULYv7Cxdq
PKQ53O28Q0vX+/bREUiBfO5pj27wannbpOPdhP99Z90er/ulpoaIbu7IUr6NKUJKwLz2nW+T6UoI
zpPxvFiV/FOc3bTUgomyd5csFfAHIbeKARe0fldtqlLHkWVPKx0gS1iE1dtJeTZKPpJLdGpRh8pI
h9UYSM5joqQZUNnGuTRDmhMkTJrK9SrsykW45WBmWMd/9K2TytJGDHZ3ZaJzEPqNLA8P3SDW1vxS
jz78Z7JvShaYIR9DScEL88H0Ol11Uv2QTwOsyByEYx/jlgK5BvFSJWDeQnbtjuuDc7RKh9ulUqG1
F9lo+0Q21hFRJ5Jc5aqkz/Cg2OpMWf2wj7b+QN5no/dHKrKLmZyv63WF0GJ9bllG6YAs6RLm14ZR
+s784WOEDyW5xuyRV7Ms9dljssImj/3SuolqqSbJCtGVTpl6KoV4A+bTYPv4191O+93uxen7zm73
/fHBzkV3uy2uWJYQWQAMIGx/qAalvUCo38+plsTT1hcqsDc0hirEH4J71ovQ9CFxM+0ZXiy4qI2D
t8sGBv7eLmzfc/T42WGW6W+bDp02XHaKLOGECpb5Q2P+wtosv7Jr3G1adG99u960loH1Lc2SK8oy
pkuIV75yZ+VyTrowrfhsLitsDUSKBZHB17ZsGtrFpvXLO7qnmhbKpps5Q2zUK3GAxkrpYLNM5JtV
HxoeZl2q6329og8t3XSftF+9pXODg2iWTos/0uFAexDgtRLyIFhWzg8NJwTqLqaFsAN5lnQIX3fc
PW4HNvH0Cipdp3e7WZZmh3Gew1jXa3g7vBPj/XDY3mtlSXL1WFxTWKwayrt1pQPZEnrqHJaPbljV
G/JEoFrYMxyEW38d/3Xsk35q29dx/2bzr+Oa72Pwj7//d+iYNF0TceX68iJdiJ9XVZvnlQJZR6Vz
FYXefjrqJeN4sIpzQkptkgvVNZwH9CC9I1/wGPhwwjm/ydyxGmBMBDJaYm99AFb3xK0+IPbpOEgn
iFs0vBhF4yn8wcm4UEy2dZWl6e2sIQ7N+c7gDMMcUwKpHA08MR48NBEWJbnK+1kyKVqrrvUR/TUr
bHEvyEAF41pwT1QmdDQm8FF6P02zQTImCZYc4tDhPCzbNUSAkZIOcmlkqqpIir689FIaUyNSUgsz
V1m/Zw2VEb2KM5lBO8xFqrGu8KUUahL6jShpwtg4MG6fgoGXS4xAW0+XMHDZTZx5mjhzmpj9jiYM
TxWUXg2oOv2PBd6rglefwD0N9o6PT086+0enF4ftzjtgpXvt7dPjjq0++uzO7jibIywv0X/bcJoD
Ke/XPwIS3jkSKSFlCC9Wkel+oPMKWymVOptHW07qTTw9rpvE7d5CTKz0lI+jAKet9562zsptnX1z
WyWK0yk3sasNxiJckmzKlocHf6505HdRS18X54gqRTIsgtskCk6220E6dvigYLMD0KFvoQbs5uy4
IgHif8V1VIAenVyNeXciuDU88BpORzLZAzDnGACdBex5CHs5ng/zy0/C8YHTwwgnZnOntu6+xhGl
JdNlrDARtFAedGaFt27ds7l1zyrr5vdGUvTZTD/fW+nRK/cD8w6exLPJvfEmoZP4cBl9TRDxeLYV
iKtxLwkX/M0X5F4SPur7zPBYxYrPYcG95Try8V48Whehs4g8dqgtLO3cI+pbt/SstIJYpYlVREIz
9esZX555Si3Sv3auwGE06g0iCp/BzT+jlqyMuui3s70esDxEt2Hum9E9bP11zC0Yje34PJP+eptK
bhmxiLHd4LWMRKxG9Y3IIQCy1kZdotLEAaAZ1gU3uVOvaQxVLGl58mKergCyH/FiDBA1IU0UD/T/
//z9+QbGsuXwFhHIjtfAiP8VlMpo2KKi17DVrP+89h//ZwCIJFfTpJhhEAh14w5oNprk5KiUCQkP
xJXPTYD8QyPAf8+tW3By0RpHRIwouuuIQVoxEgmp8q+N8j8Qq1Ofmi4sL4BXQbMSwjMvBG1x1Nyp
DoMSei9NbeBNDIKCfnFXwE1y1XgYPsAI/uPv/6bGCUOAsLQOQ/8chtEIzL7qXOnMO8pvJjVgov1o
rD7lpB2JT/81yx/eU6hTscIJ0SavfsLsmQOTy5/J8qIMlae6zwz4D9qN2q8gwtZNmh57CanrPK6J
eXk1dLmrQf74orCq58bV1YpmGLzZ1FFl+ES0fKtGxT7N4kEnvcMIqDgPvmiodb/Ttb2b0LA/NFSY
VAd/x1VPN+yGGNRXLNdU3lYHTz6WCO3D8gn61uakYgmonlyKj+qJiWToJBwxWtt2c4+Y9c4Ne56F
X8tM7CSCNcIyZaf45utyA5/V1wgP7YvzZ8/M2yPoyRRBAWtcTUxMPRCDnUbFlI9GRezQCG3gQ4or
Sg5NEUXeSsdxkz15ovEgR/URD4SK6zS3XKbSaTFM0BkAS9Exr1JYZa4xfImuaxOKrwNTBsIQ9v65
KmICRLz6Ud6PBhxJR20CEbtnwYbRRFRZqBPZnIfAQO9SI+qppnPKtyUDpqsZKw2yE6M0WLPi9OIo
yXVUtqOLRj5MJnF23O9PJ7AqibX5Gp8XC5IUXXXJ2VtdXnj2BW0WBrNkNB114mGCoSve4hAay2zD
kxDZj76IWRXa4NH77S1NtZkKSUV0QCpgQtksD7EvmoOugEOOIUTUi1e+voQaA2+qHc85HhBFs0eU
hKeU0wnurwrIA/vzMfWyKYzMYG67D2bu8idfnQUnvGpooaxaNkA9XqJ/Fv9rGuP5estO7cEjbhbe
0qVdc/xj+d8380CnvSU54D+fCz5Yq34MzAvX2B+83M39azslB2m7JWttrzsLe9FWZ9xZ01EtLPAN
jtlgrv3Qv7tuozeqsw1gog8D+ZIwJg2SpynW7qCshx18Ww6boO0DZnM2JsK5lJrethKgyxLxfT/O
8x192OQW+kO4ii1iWLOHiffCMqJOGlAvms/sKwG6vabVxJyzV7PZnDLCuOO1eNjtpalc5RBfdEkT
mpC3A9XAx2/LvphyU9g241l4CYaCRPxYDvHkLYx5IylMxRsOdFGxXqxQGEq/paWAGoEHcjn0FR9Y
bNvxONYp5Ea9LGOqUoYp1KaKtdYPrONb0/hMxHl86h0yF8L6WnXED2cWn7JOWUqFqKJQqJMxa8Xj
nmJuOkT24uXi6XktY5ZgqKHK+dsoT7Y9KAyGw4mUpoE+/iSDipTD1IqDJhV+g/ZfeXgVqHxkT74m
1nZ8b7zBTj+UfHJW0TwnIq5btYkERFx3Ych9E9TGz6MacJ+SXr6Germ3dgh7aKnRsZMzwKfqE0hX
h/IBWyL+iTveXjg4EIpe6R6Au7/DMkDfHW9lEHwQAEtQZcnI9C5F1uxQbGXkCi2/ySAmyiVqiel+
CeoHtETqs4hPkamSeBXc9aDK5qhNLCxZ+6m1gYr6BxHIj+0Mnd7ovqr8LY6V4d9u/xoUH/ci87Cy
IjaKJ1pDX8WFMV/yAhS9HIigtn18tLe/s3t0WnskCMS81mC1yg0PszQQYR2nYDPD3wXHiFvjErYX
rPec6RHj1j58u//uw/GHbu2fjur8g3+9GNAFQC0IjEXEhtMUES6fQ7/FeG5mMKaFy2Y+T9nw85Q5
x/54QRwPTIK8SOkiibBLEqFTbJpgkhaYHJu8t5OxMGGSm0Er6JKzIFkopHdAlHGAcHn+ggGEJny7
WpoIyMwA7y6TobJGTMdBdBUl41at4roN2fKUce4tW+c29c7jDN69M3QNe+jcQaLvxEvn8dfV+Qf6
ziX+pWyKjyB3DY/BGCP1uznFH8ElHrfsaiBJ1cKVJTaWTYNqshjmlOZwmPYxunZUVO4dKtXuPjpW
5MbxQ50uQBdpMB036WWMklo/RqerOMP0pQnH0EAVR9yN5huEoWucT0zrfNMxzyemfb5pG+hRD56O
RetGNoxMJ7ICFlKOhUuG++x+rs0eKzrmehnoWxnrE9Nan0hzfUNb5xPTPC8LnJ07B7k/tgJhkObh
jQfNM7b6Ea1xsO5rED6GcU53S6bjBBkWlokztBQaJ7gyJ1M+BekOFl2BIb/RBNG8Aq4ykUlW0kwG
V6ToDHedOJ8Oiwq7ygIzSqZ0NPvExIyD0mtwstoeLiOKuCCaNI0kOjMsrn1d5KbdL6bRcE6ehVUV
Pf4qmjR1xAxxgWYgo9uWYD7QgJIBggUkFMXp0jIvCOmhuOqGsZ1k8TAZJWN0AY2zDnVKWogyZRly
j5DK1T7TQN5pU49zI1u5s02IPO4sHzXD3Ae6VesVDY8xSb7WYIjlnVegmJ8MH4IraIeCzSObkoqH
2FxErvsY7z6xW0FvJiObGtQnzuJ0/Raem+JG3kuLa8xcbMQKDYbxZdHsJRFyEhJh0YQZmf4IsGOq
GPlU5CXtisOooED4yX2Mycxy+PfuGpgaR4HAuAiU0Yxs/iBOGAAFcihWcG6xEZ4w3VAP8WJVNHuO
13yzdCgubLWMKEZYtQtIfDoAxO1YRsbHjgiR7cYbzjgUcCbIGJ6cEMO8EuVlBk061uU6Wj6wfkRm
MbUSPxn2FwmlhT0jETuU9Od2ApMzV9fH1GzA9evhfEjc4xIoI1613ap2Fu1bFqTyCFdznk9+8ITK
EvDlJM1vwOSQOr26ZiJijZjFKMYs5SDHpN++VLZ1DSy0T/bvZMhgB47S0dW5J8cKNsLLmxUcoKfp
MM4iJxmWkRnvpxeDhm78KUdADVfcCNqMsrnNUgbIhpuVV9o+RaDm5rovhvZOslwcbRl4y91O+L03
shYRBOM6xyXNiFQthwUtbtQldA/T8a4JVBh6neao+ivVoyrfNzEUbvAq87MYEAT4SIcwUzJJOEpW
j6JEI6xNBbyUzyfGvlIoTloUwPqK+GpG8yviPuXi3VE0ihvEfT9ZwhSzvbmSA1VqOMtQVBRrYFvd
X93S4KyVZdRZYnV5IHvu5TAkp+3HrTmrgqcFte7MVioXYBkltRKtMRCnHp83z+2zCNy68tIBxGA6
GSZ9kSdirdLROej54tONMx29nIfUCL1P0l8jcHmZVb+/oD5TRvVsyWU2zlpE2bDU1jDB0Ljv/M5a
tBJf2+yOS4ov3gnxLVgeSeeEpNKdVx51mCG0J+ldXaL03EKpIRxWnnn5gK7cl5W9WDeca/8WfRi0
1gOFA2VRVXEzkCPJA2+878v3KFdS5G7jK/Xu3NveTYws47OGKyH5i2Oy1wQDqGwxJX+G+ude1qpL
zvcr1mDMfBJlHqptCjxXrxQurXKXf2dj1Fu18p49q8Lm6zfUXLgXSHHFPbbOrONqyYPdUsipz1es
/koeAd1ulBmFagqH5fPa+bmDtGqEvq9b3+1jZ3EKzEG5UJthcZNvaX81AKEOA1Sx5tYfXMWHILNz
3B1ZeE2UpcPqYA19V42vfXFI6hRyufkX0BSTYiapVsqXTcl2RV4zNAdohiszojWNnj2VrpoKWT72
Ku3kGKBi07MDb3PUT9pXVTpszUQ3fQzVF82TN5vN8rbTsHjOZgUHKkHENURH5Js8RA0eGokuDVND
j454r0fLEzOAxAnVY3PgG6gMgza7qamPXsEQb2o6KYNU87CpHxtycjflQ0lS6psSUkJbqb4s6hej
6jVDt2WDn6nZGMFQq+qbmi8BmKOnLAGOlGIvPqQJMcVLm4XdXbn+LQnfiDlOCrWk1ODJ17yFxPuw
qWaQ3lnT+dCQKwc/aUp5aJgLCL/ZFAPf2VQjryNgETWVD2oufYb9dTTs5y1Rwg4AbUZONSTh0kiA
4MbjLtt5WCkLtEagUwmrpb+ueMTQihr0baVCqKyoo74rxNTKNKTGUk1VqJrZS8sd/d0u7X2YiGlI
n03JwvisdlkqVN5zzYbiUXobA9EaDRl2bl3AbMpboNyMUUx1Wu7oPScmuCZM32jrryslcbuykl3C
yTPSlUFl+/aiQvteCRItMumsV0ZO7hUPbFkT+tSq45evbK9BoVl+Rh6C/kN7U5oNMaIh8ZLK4iZF
UnFteXwrAh7noxRNg+jCeJfkbNtL+sINgkyydCcJrXJRMsbDimEc5XhJSaUtkkZayRZaQXCKTrwU
pmwITJThUHKhWzYSQpNxRu2p00UJ7csUD8RSGJ676wQWg5gMTJjJxxhvVjHglAgNxSZ6aI+iRI2E
oy9my4ySrAn9vk4zmIIoI0hT6HJ+nVwWwpKqxXXcL8j+CW3jheD+dRLjLS0JDtMjsSDeEFZtPlq8
TUSGJ50oB+NXqVNGOnEEeRxGh84bTX7CBl9iM3tpJry0FFMSSbje6DebtjJcBrWPRIYeDBY4wfmE
KMZqtU5z5dvZzNt5vMVxtnjTWcmTKctqS+/fl0nRicY38aB9eZmM+aQooxdsa3UMV2NKqag+m9fW
lEIK6sNGWMp7Y/m+jbb1tbLp6JP5Y9v6tP3JvHNmmYz7bDLuQ3Nj+FPOFieTBJsZpAzcP/fPafwc
1yVA7RnMjPv2E769L5XlwmYyy1LN7U+yzH21O+AgHsOKBgIgnzKE22Rc+Kc1wMogR5XQkrseN38Z
zB/yYTqh+2GygU9WA5/w9hmBK/u2C6rFyjQOTQGM4dD9wVIsllFuXZwzKeZOWirr5mFMD9hhycwi
HBqtjJpM8k0iAYHYM4VQ39b78ZURe1vfdwxLKgVB2GRADd3pTf3YwF5t4j8lGZgZGWYO0vqZe9pE
K/vhf9ShhzgydKJRkA5ZTqSI8XTH6d1YeHpsonNYxP4KgTz/ucyiKzqWDPKkkKmmSY6WOZENaDkw
azxujHOowSfkYjOiA7SMD+3EdiBChCLLp3Mqqmxf6sA8TihOObyr7jnasVmTqCiNJqWeUxZvSWpU
uMWk9cq3HTwVrq5/+1t1tAITyGs/kHVK/bYYCC6q11V4fL8x8PTHJMzPGUpxktB9CPrj7WnmL5eE
U1evkHn1VWpvKXbg0bwQAVTKcHtBiU3SWE5mIiCjZ2jzkJfunXPiLgtQUm7iauQwqtuwHf+fBevB
wwNG4TdKqOCpiIaRI4r6PkmHlB8NkeE5oV2UV7i5f2Loc2dG1CmM5N3xXXCQIE2/j/Lrw2ji1rAt
3kMKUrJlDJh9seEVsgWrxhRv+cyp8dqqgTILxvHLMHYtNWZdcaAR3xT3F/gzmu/Dcn287kxNV9fn
zyTK2HxrzYyoAJAqTq7klW4Ky4ZB89eQ1ytirWfr/NLjY+XJICPXifDHLkJzj7fnZO1cLHC/5bqo
rrl+XrXyzOW2HArGfvgNaMxZxGrlmsTeNJdxXpubd8YkBJ1x2JjWN/gDttZ1W44URSum29m5/V0T
MM7nMTgfZ/MDWcDp1EBJL29zjGrnc44mRR/KvH0uYfjVijkTyBWalyI6Ua0syJDK20E19lDssYKP
ibtXDSWPdKK7htiInWNPQ2JBbkbX2ynyo1E1rBZVXIHVs1HNlVErNie3zv3Ck/8N1ECEYLnW+uHn
cizpmQnDOl3DXf37tYEdWHmItwsRLZRqlGIfDDJQeRukmI5R8cXMA9cAqSkNHagSt9wonckANFsj
Z5jlmlJ1cCnVptKFs3pZwFYjh1yUxqF89tin+xsUL1mJk5XHgBLpbxbnS/qPhEgRXe2pMobo1Sv1
Y+H5EO03sqYRfpZu0dhMU6yI86ozOAMMkLo/zSBbO0z1VFb6XLqlW0JLhOA4rwR8kIwSsrOpkdn4
ebDiZSbWhbamrm3cVDRf0rPpaG8HkR2kbDsz/WGGaT8adlm9Ml/ta/1KelZwGiXPmTsdc7xFc7Zz
yL/gRH+ptfHY9WFjj9Ohu+in2748kqdlw6vF8fH2+eIqcvesGhsJWDoaB1o/YSVkTnAp/RPK5Oy/
nG6dxpdxsoJH+aBXeAphmJl7yzcB8Htt8NdFA1VyMRhgeoH7x/oWQKuli5EGEo/1Ldg09s0+Hb8Z
LgTCc2Cu0uTcLyIdpit2c14mfk8CDmyul8vnvt+PgMopbdgsPvcI/1vP50sqtmrCOAB5pSkI0NMf
wsqBWh7xhzlRzKUs1jcOagmoeexmnsqeV7E/dbS3pThh3TUsNYI6szsl2NB0hnbmBLLIUjTf5+nl
ZR5zMgTOgKBs/ZdQM5iOlalenf9FGCbITnogUWtZR/WY7/3xGxwJImR+OYmSTIcdUNBv4lk3hp1P
bICGmt33Md/PfakJ9lHMLD6Z8rgFWVpuz+dMp22sJgxdY3XZIm0W9hvc+9KMWAHl01JQhNl+HqDt
ZfF5+ij8tj99E9hFCC9rvq4yYb+WJuxK6fEyKfAI79FmbC+YfY9R22zAZ9q2goYaZV+bAtZPP9M1
Z/O7IZqutzY2Br40BaVt1UXTEsctYff7F4MqhyypQgFadNPcxIpuqvMNdkuj8kuHLgxT4pBwypqW
z2FlPk+ssVpEpsEMuF9RC3+Hw6/g5DbvKO+3xH83lzsCkKq0ybPPyyBdVx6FwjyfHtMnyLXVNwMv
5y4p8FAPz23fedwaxGchRSx9WCG0f4S6U5auja9d28nWwGaOt92jjkKk2b/C6FrPQtcOsZdo32tt
1ahnjcVnKcK4Ea6UJTDTns6NtKisWdQZr2dSpxDlHU8Hp6IYSreS41MhKuAXyxWx73dEtOji8+dM
hp3pn2vvDqe4DLxTdkuUAfRg5tio1BbkyZHDO1wfc1co3xlj8xWnURZGbq4XndF9XgPNYMPKEWL6
87gtrJQXmi7FQ77iX8C6mJ7SsrNX1++rYbl6ucMhw0qVo1PgJSwoX933h/CluvZWccPNnkZ5u21Z
xIGP/saeI3TfT4/Sg/RPQ48M4TdiFNJj9GBIy1V5uRdiMUxTvIeLoa+gt3XVzLvSUN4uMWKYVAyN
DOI6n75faaavX45LWhM2x4S7hMl2kYm2vPB4BVlo2uc46jzMn9L99/RRurxYPatX+LLIwLF+P5Tw
UV03ODtJMXso4A8TNLcDFzy+jTORuKc8NuYR3xtxXPMQmhD1pHwrVC0v2ZC5u3PB2lfe8AKS8Nrq
gOANVIKEro3xDWGocgzvcn/07FRV1nXrQBztLuZ5ddkfyTqV1rMAO2+w+WhLfUXbRpGF7Rtz5sWh
LJNSyNe6PI9H2sQQYGI0HundZciaXuu1u1PK1ASdmB0OhROiJUzJMjvxKC1KBdBW5ibn8RyPyWRd
EphIVuVWdVNWWY5esu5HZQ4tVb/zVnhfXeG6KgXUsYj0b1jCS5VFNoD5IM6WAHFWBcLKJF4NZVCZ
pVQBkolO5wChIlUAVPKqagAVeS586U7FXqiSuiv56mc0cPoXV6k9J/MpJslpd7v77452dy508P69
TpuSqodz8FCj4wxXJdCd/fahgmyb2nhGMY5KFmsPc/NoYAnnbRU1kINVG84GPQ5NLZdYSWXzNC/d
pT/fb5rHXAi5Eczcd2fnFZeUAEp+mlLWHEuKVja1IqUzVY2TUcPoqu8YpWdCLF8yk+oYL2K+uDTn
FJFvLnkd94w9zMida2xppImFC+qeOScV5xUbg7s5yG3X2RksUKWxqcuGYbbk41mIzisilErd7EbD
wtNvc6ObwVuCBAy5SDUUlAilCs6ZhHPmgXNWgnPmg0MHRMRpxZ1llwmHdLPS5YiVoM4kqLMSqLNl
QC2ZOMjcynSSn4/68X3ZoMJdbQg8Gw6/aTjc1pshSFoNJR77nJ3GwFjNg7iufxQdec7M3I1ubtKg
5UbfxOns9+O0IMvQcjhJWjzmw4q5SJXiGnhQ1B7EdXsKmnr5kFfUnK/+Y6i6PXxNc7E/nfc19Ka+
zeJpHucYn7832+ajSNBj8vjb0k15thXJ0ulqxp9xC0Or8OvKIzZZDpsBDl1lIjZN+MdYxXLbLg2r
4i+TonVflR2qcpg9EJ6tLF37zKk9e3T7ZQjVZ4okavOIvNKi/2H700X3fbvjiCfdU0we4RF+vCe5
ZVIpsqnfAv/wqBNMSYyqzwdpepN3YnK5KRGe7xyizlu6mRYM0y3I7NC2IOmtvzCjmA2Kv4cLYdEi
YK5ig3u15b+LXhItYeaUaLm9e3S627k43tvr7hoyqw8Lh6O92rKJQYHc/XSyu30KDw5QbzJ4lwRW
qtmEmsI5R2Pp0C9h2GtvvpRhg/MKGvZSmi9s8EXDxNkt932tynZs+J5iWVmM9Jc6s0udVZ4FygbL
6ckW7BmV6fJ8umRlcjO/64emLyh40tntArFeUC5hWxEKPZ2vSLZWt7+82WTDh8a84Y7FHMc73hl6
MrEPEh9tmc6bas5e78mEP0hpuq56g8fBhiPmmi9JtrnIHXuKPzoJG356Lbw52R6PZaRGGAvaqEPf
+tJql7gyiztppRxlmmxcHOZlqtiW+aVFFEpAC5Pkyj4ZuaadjorDg5cgRFPLRkkTF05x8ZzvhlMY
Sk66sOrapzjx5OCQL3L+PtMTw3qs4UnWNtJH+6vrzxXmK25/aeMVF3+M6Uqkzv52w5UJ4JvMVgzg
dxutBJhvNVnZmadVaulga0m7kpvLWidpx1y9i5K+gyhxYmcX/6ONXCYy3Q8nJ8cdn5Vr6XPsqhus
6A3qvcTqxBJCg4nfl8+5sP9d5SW3P8JK8/stNb/bWvPHW2y8VhJtpmnaax6NG+4SnAvuzAR35oA7
Ww4cBg6bYmDTbPdWJbKQNpPtJOtPh1EmloI2nhj80i9zCG4pUzC/b5RsJxaXeMppO/ywKhiCX6Io
dUadBlWLD04dEQOJkwKJHOtenvA4mF757pU7DhzZ6FGQK1WZ155BXhvMWcDIQuzRRh+bOaMz52YG
tLtvZrg2DFUliI65atlFoFo5c1o587Yye2Qrtilm9z7JC5JiMtsNhYJY5vWwFY1nwVfgBlxuni1F
lrH1q7/9LTA/nGk3WXEmWDYAuRYXiaRtdDFmw7BXWEgsMn0sB2OOiG7MVUX9s+VxmAMjrGBHNIB6
fB5tgllZbEnhqz4urRjLzct9uVNWkCtbFyD3Pe8irLh9CFOxaazAylJnRqmzCjZOTtPWdldV7swq
VwkPrzA0KnpK9xm834w7DhzA078KoklicehNh5s1qrU+1lI3DRV3SQ5eTbMlcfqRevujdHc/CNRN
O4SG2rs3yThYXfwjqHZqj+W6m3574nm1eivPLHkIVvwjY+mFnssdSp9lLNAfDO/SPzSffK33ryMQ
6+q1dq2Fj+2iTrEG++GDWitbmBfDnH2RUBZEdPzmj1i23KQDJL49sTycqk3aydAx5wphSZN+HaxV
ZB7hASMPunK9B5UfgoPak3PjlF5g6KJJDP8A9Om4N5xiMhLZlyCWnVn13/LVziVeH00KSFodu198
5qt45fiYS5y6M3BPYEwG60TEfFjxBeeiQbOD5wmHyiIlHF4Gg4zzzjz5akTSMx0Yn3x1wuhpp0Ua
bX2dyA0Q9o4mhJoTjpjVuQbsBAPzwPDtHQVJxvk0IOHoWKkKRN5XOSagthFM15NWx4RyrzdmHEak
z1E4kXltH3f0bnpxenzx9sM+KOGY5TrUTrw2Jb3yIGGqs9X5jTCrUSfGdD7jq9hJZLR6zNkpvNPM
QcN6sfaxpdQgGECmlLWFQ3z5UhWtUhKRRCaAxdp5dBkXM3iUZok6pYV1evdA/otUgVYnOu2OU1Ds
M0p17G+sMx0H6aSgeDoXo2g8hT/YmwtljmhdZWl6O0Ot6zaJGaH+DPMig4Y3jHOVYbmJaY0KRD7v
Z8mkaK26kY2cbCo/t0RouQnnRjFyqkTCConWCW+ymlbQEYU/GTkjdC6bfjoSgekoFpDjx0sDJVkZ
sPkbdNW5TPFOvAGN1E19Kx5mMy/iaIAxhfJxNCHXXXQODShNNWoPAzszh5W35SyruuCQub6Ts1Jg
XfOumrFVsgXm/I1I8FLhdKtgz3JfwBN4K2NRzSyua/m4ns11GT3LQjWtv8hppfRgSIl1Su8hsw9w
/g9OlXKJK/6ldYlCB/xr8NQM5EyFhmPiMM1U1pWtYO1+bW0P/jPuK/Y4/hNR6j/+/m9BHOUznKg8
jjGq0wAgB/kQdiUbqNjtAgK6t/f92vdrBlDc0hDY5TC6yg1qA7jJ+BZNH1fypqdMoRDl8QkS8ECd
hCi5rA+7wUk0wMxje1G/SLMVy5hIiBwScZZPUciQaIp43e7+0TsUDf/sP5uRnjsce387mpRhurhq
1qfu3cvkEzoKfijUIClgUtPdk/Y2ouOgQMFXs/iAMygIIzkbDigal7LyN2T3bbv/0obNRxk1y15o
Yln5nEr6eHt65j3pFij7ak3UqKIVU0RWdUa77AE4zyujf68OWaFTM3VE6tUs7RO37+aHaffhquMu
OOTTmH970EtV5lmtgVcYLqEnS569VWHQMAlp6cQAaLfNcGKz/qzSVmuZnxuCI9Z96UZoagCemJfM
IZeqMfYs+Dnd92pVo2ql6KEcv4KbPYF5Ou7h3Zq8xZICsG1EiV/WmeAbZZQbNlvWGnEWY1Y8DIRe
XyW5R6YQ/ZydowJmJBn93D9/WPXX30ZuXJcdfmNz5017B/BDOKCceXU+UWX+HVHUm2kOkgSw7AGI
GM/H08FVzAKcBQVKn8BuhawqEqJPM80S3MMoneNtPKbwEBzsIZUJt5Re1HKhSbEO97oxC1wgSkbB
dTzN0OTThzlNUOYRoWNRdIuC/jBKRvHABQYI9KJeQpGtU5pLjCA7BhGW026BdAIowqaL+xGgP4pA
eAmupyDh2YiJMCdK2uOADpocMd2weLwwBLGaxz+uXu+9qTzRRjg8ivHgIlIFahUhPRQsj0nBBYrv
LvBU+WI0HcI4XgP4eFgF2YLu2De8kJUzAqZcrgmzgBaPa2FYcQldH+eZab7XBiVG/51cSIuzlgDe
RnRu41rTC/tekxG228PvfJgZmb83ULRQLH+t9cvP8jcwxHpT3r4mnMKKfEvzSGEBAp7PXndYp6nq
uZzXHnSwor0fN+a3N58yv63NF2sVbWKjbtQVbK1WC43spC0Rejuvl+6vVRi3fWg+RTL9eWMwZ//A
NIEemQQZ72RaHMZFNIiKiNIt1Ws7Kscj366rNVx2Ez4eVDS8Am5cXI8CzHuH67AR7Oye7rKJoH3w
7rizf/r+8OLX3U7X659U0YLOEYzSeTBh8RxmFeXzml/csc14B6g1xq0P3UYgE5+XBP3wG/CR6UKl
HTLoRxOMfMtc6BsxWyy5f8PcWJlvF6LgIcHlGyXTkr43yzwJgFbwyVrVrjAPyx8QSx9nddB8gBnB
23l14ArpXURKnMCWDEw+9l7OirpNRiTa/IsCzZU6RaqEhrILilEwIldC0ELb9ZOvVnP4Tdq0qk3F
cmxJJ3v1KrB2DPeq6xUb8nbiywh2WpATcfiF1FiXUMwcctdJnGE8fcxyIMuTb4j7GXDllArQTw6L
AMwMLcfqbT1c8VgqMXN3sUlprLXg/yDVgAaOic98vbqyYqkDCii2vRl86O4Gu5/2u6cwI9TpVeMq
rDoltbCXndOabpJ3Uc4b0y1YVc3j71zXZ7oACKG8j9F2UQ+Rctfx6pdb4GMyKK71dzvOuFFWiPJ8
OavFEiqFlkhyIUoDkIfQ9Rn4BhAr7vm0eTSth+L3W2BLDZTWFCzzo1SPuLJCka2JrKC1Uh3YSTEv
hKpEFZKcZOkBYTKLs7/W8mCVoOD6vEY6Mo2TDNo9ya517+jOLqxZkSdYWIsxMYXMIqISyq/SXWEz
LbxlOX1YWQHhfwt6/mucJZczfFxBYitGkbCyV1AlTo0qZBsAlp4HGlc1nGKNBdElbkbdIp4E6xJx
gTSgK9mGCJ8tUHAJXRUTScWcYoLcVxwDklHKolIqJ1b6Hl5dEeHq8atKUuZS8IpIeod9KlVSgW9M
qGYNztks68kjKNnxc6co9NJXdJtOrFSjpUyqpBYm0lhMmgNtAmY/gFmoccRDPmO9ScNZgjfRB8lt
XRYMV9zLlEnwLwqMfZaoYqyi7U32L/QMgx2opRyfVTXuGZfPfX3SxhND/sLYlBw3so6r9g3DtwcT
IwudgoVNl2ARPl5YCjMD1oq1FW2KgCodOuehM6z//HcZZIVOfHBbXF0xPK2zmGK/O1T2AKyiiIYN
valRy75NTXywreRGE9xBuVD9h4N6DK1k5L4xxCO+hbCdE0M9rD7w1rDSCSKwt9178h7DfEOgvbCA
mIHSxEaSv2wHaDMcRjM6MC2Ia0fEVdMMJGyKcDTFPOW9GUKTx2GU+QczA0U3MZ61BtG0SEd04IDn
VtF0kBQks2FKEEwt/69pOsKtoABpbnp1TVmMAN76xo+G4R+Y+F+maEJrEDZXyW3MmwabiljzzZE3
RgGfhF+lz8dp8ypFWH28Hz1J8Q4HMejdT+TRi+eaF3/ZlmvXVrb2Esxy2H8bUUQjawMsa2XMdlI0
5KGBOy5O+Ee9HD1ZFjMunItXyF+xi8BdKu+eK4RKdfgpg8Gge+KIvtG6R2YGUR6ICc9qbTcG2YTf
eu3XSDmn0DD+kCWU4isDykGEPnT20esOV7p9Vga9Tgj5LswNytXYXg1B1Cq8tiR4ZVXXcwOsnbor
ihhD4YzCI0Zi7piseKdE4dGdASWOWkwGsFaLWb2GttDWdTqKayHs70zLTdhNmzGtwlq44lLeTpIt
AorcIEKltfWlT+oS1HHEOFWf0YS2YTe/wLIXX/o1Gqp2LwdeUsRMR07ylO8IEdj64N8YVWoKKcQv
RzeDJMuN8RVGzi99kFJGFiFIdZLplFUpsqskI5CAtJyoqvJDK4tJ8INtov68/iYJ/9qCIfxrq0gu
nz153kCzjNVbq/zn/63d/G9R81/Xmr+0Lprnz7D8RS10JpaaMaIlKQwkaqXMp33r9sX3G6apERfC
l34nviLHqmgg+ADqVTwYDat+Q+S8Wqu+rqDHUEhnDd83KeA1yMFOHBiXk7b2DyM8NJlg2BpQFIax
TBVeJ6TdKzpkDvzlp4FDEnWGoyLpSLDr5ki8nV5e4qWlfRxFKMJ/maCtb+L6KSNwJwPU8c9re1Cs
eq3TsxO8R3560Xn3NizdJvrSV7dCvH0rn2HO+AxzhsGITRzgVfkwU8RAVHfeZ3ijzeyI//LHPbdx
77RxB68qboHwzN2aRk0OUW0YkvGn7O9njdWz4P48eM7T4zMfAz49X9zzjRcvWmR51v7JtxX1r3z1
e+Qj/4vXrmuFGSnV+clXR9AOmjZgpuv3jWDWQAn51atg/UfQqYP6FT7/jI+9sNJVz16jV0QXDJgN
K++yaHKd9E1+9uWKGkVvO2Rs7/FahfUrb/159+yifXS63z7Yb6NnQCNwCvzaPviwq4tcmDZShr+X
Alji0/hQ60ZjUONB6wRFEN+03h4f7DSC9fXQrQjsNr2JqSrw9qQvfgNDujTKLqvn2FE0E6GWIXPp
HO/7AwDzCWyaYCG8sAOPg0/k4GIyOW/NWbnm2VI1LQ+39TLjtM7ERQtvkcRyxT+tt5Jzum2HpeV+
76NbGIIm4PTcEyRe8pRyndncOoOBr5JTkCmAD29Lyukb4rL8ERZzI/iR/o93rPSHtUaw8T2839hY
C0uwB1l0d3wbDcVqG6APjIOB2OyHeMRsEIve21effE3IGXjVB12cpVD1hndcn6kxgkcaqvJM+8b2
GeUPtxJVmG0nOagVcb0scJ2QtdEQmEDGaWAvWCR4kFJTawLKX+jW3s5vl6htKE+tfn5bBnN6XywB
Jp+O8HS0VdwXBgjiZvvHrbssKbAq/Qah5+ToXa3BHTRYCGJMeRlPUNX8iHWyeu3D6V7z51qI2uOd
yxkmdy2hltZrlEG1AXtNgxXRBnETdj27uL+Y3MvnGT7LsxR8FupzLfTaxpflUstyKrXp+C0ySJ/+
hCCuecZb0hiRz0TrwFzQcaXfcNbD44+N1jm3sMtb/yBIZ98O6ZuYa1h1rcPmXOet30BLr9ca7jmq
TzGjxfLNFLxK4v0WJoqep6S4bjMmBCSNLcMadW9YoeZUE2aZLcfuNKeGWDJbjkFqcRsXIOlfwCLd
+iYj1HLgef1vfZMdKpwXIoFs6NoMhaYu4mFlndUX31g42Ct2y9WR51VV91gGvvRLJ4rzThJZl2ez
/V+2ucXKM0I6ZZB3uMls/90W2zP/9jfNdb7bsjJsmWcIH6NsDLRwlII2jDcXUMmkowQ+IzAMATwY
SW6aTv/z3w1q1RekpQc9fiYHevQuN6Np2BZiw88ZsP7ONvmWgxD/HvTpCJIzpl2DGseRLWi8nnx1
kPKl9kSzIdeRmVJENY2up1or2B/nODJBj/PVEnIbzqBYxxmvSscZTzmRwh82CGQ6DUZ4l1V6j2lL
ad1hEs/Lhu9wca/KmO6PL9Ol0Tz+82YlrZFj9/I2d/7VCvACxdrGBVqTL9joexFPkgFarHNxdQLE
pnu6DWGcGdTwEI+xBA0CU51vbdVW/l9NT8BilZgBAA==
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
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
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
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
942j8d59u6b8DWzyzTEQEAAA
''')
def step5 = new EmbeddedWorkflowScript(name: '05_finalize_orientation_review.groovy', payload: '''
H4sIAAAAAAACE8Ua7XLbxvG/nuKkcQLAISHHM8m0dFwNLVExU31VpBJPbZcDEUfybBCHAAdJjMyZ
PkSfsE/S3fvCASAlJTOZ6odE3u3u7e7t92n/+fMd8pyMxoML8h3pkmOWRglZlMsoJVGW5fwGvvIZ
EQtKpgs6/ZxxlgoaE54zmopIMJ6SnBZlIkIghLQuy5TwNFmRaCZoTlhaZHQqWDqHU/qX4+7bweUg
XIhlsj/lQGEqSLGgVJAojfHIZIWgUbpCWnuDjMU0X7KC8Bua5/BlD/ZSrk4uQkL6hkuAueYlEBGc
UABekYzm3SnPKVIq2DyNRJlTeU6b+1cgXpTO8WyegqyABqwDXRZHghaESfn2d3bYMuO5AIBlOOd8
ntBwXvA0/BF+PbT3pmQJSGJAfi2zSCzChF2HWVLCsUWYRXm0pKCyIrwwH09YIeyRn6KbKEwZD2cM
KB/Dr2Lz1kiAjFEeH/JsdZ6hkDW4gk7LnIlVeEqLIprTIzancIwLIuidCEewktAjEP+Y58sIGInp
jHwCYchrktJb4sjlB0BWXORUiNVFDiYCioS1aU4B3Q8kJqglg+v+qU5Ab7IlchKJCLbmVByWeQ43
NDSrAMVmxHeggEKZJAG53yHwc8SihM+LsFjw20Ge81zL5nvSotlvlIxP++69ex3inXF1LtoOz2ga
eoGkBmKUebqzlpwVNAdrArbs4SEwOJKrwBbeA7mOCkoURzsCLE8xhdhgnJ/A/JVUF+qLr05Bgcz2
rkImX39tMPCQC7ARPzCbgTmmBaE+ocLgs+DIEpyxJtNITBfEHy9yfhtdA5/gBGDYMWhtLfWpCNZV
WfFvZABrgVOVHvCoq8thAQfNGHjSPXiGyz2TbI0gViyBB6TtoU16ZG1J4sFIsikWWoRkHTYdkbQ0
iPmIRJtkatIerQpBl6G6DAgQYuV7JYgWLviSegEYxT9KVGoXzKVL79AlwCh2RiLH0CBN4Ax8s6aP
UyqiWBopfsNtkPygRzwJ7tWQR3C6sSWEDHOaJdGU9pPE3/cPWPAhBEY+hILNvnm2D+xok6zBvf9X
v/vPqPvbi+5fw0n34zcIN/EcF8FTQlYMlhnIFwS1ow1X0nALcAYIAPljKsJ4CG40T8Nbnn8ODZrX
kcxZVPsB1Q5MiWU0yVhGE5bSiURCFVtupL761wVPSkGVKQfao+YQ601olwsOg+Z0OEDlKBpPED7E
2ORpAjP0+ydQkHATJzBMTN6rkYOMkoriATpWTAUpkRN9J7tNaeBulFkbp9M6L1ydg/JCyZ1Ud1lg
xHpzcn7498GRNoo/FvXGC6XeKsFXmR0jIWTbAux1YzB0BQFFoJDhLOdLDOp+S0q43TFkEt+7Gh93
/4JXfxpl4TSJiiIwXoFIb6NigRHSwQ/N+gEENAWqnUpbrvLNN09w74bt5mWKWBus1zHaqBRcG8XE
RIHtxppgiSAuZGlUc6WKSaB70h8PRuPJ8fCsfzK5vDoLxZ0w5gVMKS+skbJmQg4qmnWIpopDUNUS
7apXl83lZO/ZvXXBtfScybN7o++wKK8Lpe8XeF9QpSxZ6n/7smOvKkxoOlfyr/e0AFAwshnw1fQQ
JRhIDx8mBqjmWZXxNeKQRa0gCuNQ7nGVlr58Ibs1crAFvyFX8nz1/3A2m/P2zp2Kk5ciK6WrsRSL
ogRKvRDr5n2sRSG5wMVk5CWEsBx09SF9dq9U0TZAVH/LR41uWv5ZU9qDvinLNatIWWoCtbpqE6Yo
QSlwIGsBSE2yHKjuw9YDKieGNI2LXxgw7mkLIOuDsMCq874GCIkePP39xwYfyINepHgB+nuD0ZBG
UCLckxnp/m1LQTPNWrqZPawQg+0y88MPQKhW1UwBXt8nVjciL2lgWAXovaG9bodQjzy7n1Wyr/cs
SR0hlUGi/rNQNSsH2kg3xEaXnfcexNIpldF8ItlAq1aRQH//GMomDPoPX5EMKlnxx2G+ziVyrTA0
wxtqMxo4encoXaUQkGMJ4mSeNn2qiivlZUYxsswDcBAD/JpCk1Omopk8MOSrjQidTMig0bSTAlxW
Vdc1WkoBDrvHsqt8dr8Zf+3axCtLCtl3qQL3yIONgPpS4XDv8Pz04mQwHngYvyyAzYu7r23gbbLm
Ya9t3b0WTyAOkWua8HReYEMcQcu8gNyElDwVQhUZt0r8E6NjreO2dcd1wqefaezGyWN2Z6IgMAzJ
nQEzhQqFvQ/ph7SLmlWsfwKd+x4uee1IKMPEbCm+B9u4J3BaSSEgaJ8KZ7Kj9U/4NIJYeDUCeb4K
v5+BXAoSzCbmJVgoxCIVhUCDKZvKqse58PAWwtkwjekddrs8SbCZuwdPhSrXxJ/3rENkZMjpyE4h
pLd2Kpc2BqHXkXG/2ozSObBSqamxDT48YzFNpwDjkFxSseCxIvlRa+sL3Nfaas6zZZhC0YVYbTCA
TjhMgT0g73ujt/3uy+++N11JLEF831rrNwTJwh+rMFk3vVlB0WJDq0au9FW/Fe+rFy/vQAnQWX5N
XtzNZnAJimXLrjH6CxvgNOuVh2X1LdCCu4VNqVn3sGyygXRn55rzhNoBGE4/xiqdehjRvZD+WkZJ
MZSd5yFUVBurTusy/RoV9JwZYFPUAvphq09p9Qbt/EXvIPeiGlpZrEXsSVlNK9WQbSq1lhF8A7VB
we7WAwp2c5RFMVkOtNy/uLg8/xlCCxYQFqBqFKqACAA15mR4NAhOyLl0rPu1a+sP4De18Hqb0W2g
UYllYt0pj+VUwpOzVQz1LaRHELElAe+AXhcr/3mumlXsBaSe6sYaBG7u/T1h3Sq/XghkONRLUgzQ
rXCeYDpfGQ7iV1rBZIHageLVqtupbZxYbb6unzbjqdwzSS7pDaO3kG1fE+lUdo+aufEhhsZ8uQEk
p3McIW8HmJV4gCy3AXA7nJbbrmtvgoyLa55Kt43r0ZdTFwGji8rwm7i3uxsYt3vbebYgFbt2ybDa
NyZGHBMjaGI2Z3s7a0JBSnfEidPqQndutdG14+thFMdjJhJqATZVBrlUhldHk/VJhQaFqA3kzTpv
TTJeMPk08Io4cHqkYaH09xqMOtuCWHfTTJFZEs2LvcdY8x16cxl5JDIEwReBpa22FEXkwUlavBCX
3AStDagI0M159XbBYkhGhpTcoF2onNLuFNz8tSufRhrD5iHsYVBBC1g3ZHqjzNq5J8dKnVINf7yh
ed8BPalHF8j6XaNu/YrSfPjB2lQ+KLnvP1j0oPs8xkzbN9o8TdVW5UdYG0dCHipAcqgztnD7VC6a
PvgAD9iNQnfMbtRNSZ1oA8CnKBl/G9eKV/dUTrZ4/FaG4KJQDTkUMQyCqowY3dwS2If6lN2Y8Vqh
suFTedGhpXm2nHgRnVjgdIZk8eLbz3DmpAaFfrpSI8slRi68vda7YO21Tp5h57iNeKLis8M2hj9M
feqtDG4jKmOoPc2yVz3X7DrvdvOSQfmruh63+7GE1aLvNaZC5L///o++dfXOOo/kOSqM1vL2U3P2
Yf/scHByUkvaTnJdb8g16jRZmjeu8WdsgOpOH2zPSo/S2eCvwbY09iixltsFj2S9Ryluc5+gkSsf
15e2/cDNqBVWw+g0kjQxM7pRz1e77j3hQHOD2nG5pT6notzdphBE1Iz+3nb/cvDT4HC8sd//JcpT
EO6MCzaDjg+P9D134Ali2urQCQ4eTgzlOV2dY3WMUo/7JMqrOBWSflx7t9/HIGm9vyC3CwrgKaUx
yImBtZoiEPM203rSkK39bc4E7UO2Z1M5K5BTaRHlcGsd0xphqoKTTUuvQJaZO65WGM13yw6p1vVw
E1rkEHC72CVfXQ2PwhzY5Uv86OvWGLbxRmTnpo+GK9At3IamUA2kMDj6iCq4Gg/bw6uF9v8GhJeD
i5P+4WAyeDccjYdnP9Zj7waE/vj8dHg4OYVo/oR32T+HycAOA/UNGfvqC30pzX9f8PdW8NM9Pe3G
sTf23r7tLZe9onj37t1eYCYPiHck/2NBzcBr/TROmwt82I5+pnkBTPXItx09ne05jVPHCR2qf+s1
RwkHD7dzPdMmdhyxes5ndYR9RO5VH6tXmp791CEbW+Ge0wgrgk7b/tC0xAI7XXBvS3esYNWLR4Nu
teiSjZI5cCsWS6tki9Dc6hBb/ztQdk3R09VdE0ovd6o6XwXdYyykHTgVmBSpWQRqiM/zU4XrQDV2
Ok6C20a20RxonbbK/ybilgZBn9go8d0Dt1T/ClFVe0f8Ni0i9BoHsbnVsfmtJ3/rk+UDlXpo65Ft
z1Ufd5xY2x5WddQ0S/D2LCvQg8Vgp3qIl7PXX5BgDmA0je2QSz0n6ZGF7/zfkaH9XkmNpHq6Pp2c
Xw4HZ+P+eHh+Nql8mQi2BDWA6G3/+2Oe5ujuY4Dj5T8wmammMW5pfbShoJZDmF5zCqMKjfpIopXW
h+mM13N6s5LdkNP1fMiO+IvoBpsMKNk1Py4n9bfmb1/KKf7/ACBwZMqTKAAA
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
def projectHtmlEscape = { value ->
    (value == null ? '' : value.toString())
        .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
        .replace('"', '&quot;').replace("'", '&#39;')
}
def projectIcon = { String name ->
    def paths = [
        back: 'M7.82843 10.9999H20V12.9999H7.82843L13.1924 18.3638L11.7782 19.778L4 11.9999L11.7782 4.22168L13.1924 5.63589L7.82843 10.9999Z',
        forward: 'M16.1716 10.9999L10.8076 5.63589L12.2218 4.22168L20 11.9999L12.2218 19.778L10.8076 18.3638L16.1716 12.9999H4V10.9999H16.1716Z',
        home: 'M13 19H19V9.97815L12 4.53371L5 9.97815V19H11V13H13V19ZM21 20C21 20.5523 20.5523 21 20 21H4C3.44772 21 3 20.5523 3 20V9.48907C3 9.18048 3.14247 8.88917 3.38606 8.69972L11.3861 2.47749C11.7472 2.19663 12.2528 2.19663 12.6139 2.47749L20.6139 8.69972C20.8575 8.88917 21 9.18048 21 9.48907V20Z',
        sun: 'M12 18C8.68629 18 6 15.3137 6 12C6 8.68629 8.68629 6 12 6C15.3137 6 18 8.68629 18 12C18 15.3137 15.3137 18 12 18ZM12 16C14.2091 16 16 14.2091 16 12C16 9.79086 14.2091 8 12 8C9.79086 8 8 9.79086 8 12C8 14.2091 9.79086 16 12 16ZM11 1H13V4H11V1ZM11 20H13V23H11V20ZM3.51472 4.92893L4.92893 3.51472L7.05025 5.63604L5.63604 7.05025L3.51472 4.92893ZM16.9497 18.364L18.364 16.9497L20.4853 19.0711L19.0711 20.4853L16.9497 18.364ZM19.0711 3.51472L20.4853 4.92893L18.364 7.05025L16.9497 5.63604L19.0711 3.51472ZM5.63604 16.9497L7.05025 18.364L4.92893 20.4853L3.51472 19.0711L5.63604 16.9497ZM23 11V13H20V11H23ZM4 11V13H1V11H4Z',
        moon: 'M10 6C10 10.4183 13.5817 14 18 14C19.4386 14 20.7885 13.6203 21.9549 12.9556C21.4738 18.0302 17.2005 22 12 22C6.47715 22 2 17.5228 2 12C2 6.79948 5.9698 2.52616 11.0444 2.04507C10.3797 3.21152 10 4.56142 10 6ZM4 12C4 16.4183 7.58172 20 12 20C14.9654 20 17.5757 18.3788 18.9571 15.9546C18.6407 15.9848 18.3214 16 18 16C12.4772 16 8 11.5228 8 6C8 5.67863 8.01524 5.35933 8.04536 5.04293C5.62119 6.42426 4 9.03458 4 12Z',
        microscope: 'M13.1962 2.26797L16.4462 7.89714C16.7223 8.37543 16.5584 8.98702 16.0801 9.26316L14.7806 10.0123L15.7811 11.7452L14.049 12.7452L13.0485 11.0123L11.75 11.7632C11.2717 12.0393 10.6601 11.8754 10.384 11.3971L8.5462 8.21466C6.49383 8.83736 5 10.7442 5 13C5 13.6254 5.1148 14.2239 5.32447 14.7757C6.0992 14.284 7.01643 14 8 14C9.68408 14 11.1737 14.8326 12.0797 16.1086L19.7681 11.6704L20.7681 13.4025L12.8898 17.951C12.962 18.2893 13 18.6402 13 19C13 19.3427 12.9655 19.6774 12.8999 20.0007L21 20V22L4.00054 22.0012C3.3723 21.1654 3 20.1262 3 19C3 17.9928 3.29782 17.0551 3.81021 16.2703C3.29276 15.2948 3 14.1816 3 13C3 10.0047 4.88131 7.44881 7.52677 6.44948L7.13397 5.76797C6.58169 4.81139 6.90944 3.58821 7.86603 3.03592L10.4641 1.53592C11.4207 0.983638 12.6439 1.31139 13.1962 2.26797ZM8 16C6.34315 16 5 17.3432 5 19C5 19.3506 5.06014 19.6872 5.17067 19.9999H10.8293C10.9399 19.6872 11 19.3506 11 19C11 17.3432 9.65685 16 8 16ZM11.4641 3.26797L8.86602 4.76797L11.616 9.53111L14.2141 8.03111L11.4641 3.26797Z',
        check: 'M4 12C4 7.58172 7.58172 4 12 4C16.4183 4 20 7.58172 20 12C20 16.4183 16.4183 20 12 20C7.58172 20 4 16.4183 4 12ZM12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2ZM17.4571 9.45711L16.0429 8.04289L11 13.0858L8.20711 10.2929L6.79289 11.7071L11 15.9142L17.4571 9.45711Z',
        warning: 'M12 22C6.47715 22 2 17.5228 2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22ZM12 20C16.4183 20 20 16.4183 20 12C20 7.58172 16.4183 4 12 4C7.58172 4 4 7.58172 4 12C4 16.4183 7.58172 20 12 20ZM11 15H13V17H11V15ZM11 7H13V13H11V7Z',
        image: 'M19.5761 14.5764L15.7067 10.707C15.3162 10.3164 14.683 10.3164 14.2925 10.707L6.86484 18.1346C5.11358 16.6671 4 14.4636 4 12C4 7.58172 7.58172 4 12 4C16.4183 4 20 7.58172 20 12C20 12.9014 19.8509 13.7679 19.5761 14.5764ZM8.58927 19.2386L14.9996 12.8283L18.6379 16.4666C17.1992 18.6003 14.7613 19.9998 11.9996 19.9998C10.7785 19.9998 9.62345 19.7268 8.58927 19.2386ZM12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22ZM11 10C11 11.1046 10.1046 12 9 12C7.89543 12 7 11.1046 7 10C7 8.89543 7.89543 8 9 8C10.1046 8 11 8.89543 11 10Z',
        folder: 'M3 21C2.44772 21 2 20.5523 2 20V4C2 3.44772 2.44772 3 3 3H10.4142L12.4142 5H20C20.5523 5 21 5.44772 21 6V9H19V7H11.5858L9.58579 5H4V16.998L5.5 11H22.5L20.1894 20.2425C20.0781 20.6877 19.6781 21 19.2192 21H3ZM19.9384 13H7.06155L5.56155 19H18.4384L19.9384 13Z',
        chart: 'M11 7H13V17H11V7ZM15 11H17V17H15V11ZM7 13H9V17H7V13ZM15 4H5V20H19V8H15V4ZM3 2.9918C3 2.44405 3.44749 2 3.9985 2H16L20.9997 7L21 20.9925C21 21.5489 20.5551 22 20.0066 22H3.9934C3.44476 22 3 21.5447 3 21.0082V2.9918Z',
        external: 'M10 6V8H5V19H16V14H18V20C18 20.5523 17.5523 21 17 21H4C3.44772 21 3 20.5523 3 20V7C3 6.44772 3.44772 6 4 6H10ZM21 3V11H19L18.9999 6.413L11.2071 14.2071L9.79289 12.7929L17.5849 5H13V3H21Z'
    ]
    String path = paths[name] ?: paths.folder
    return '<svg class="icon" aria-hidden="true" viewBox="0 0 24 24" fill="currentColor"><path d="' + path + '"></path></svg>'
}
def readProjectJson = { File file ->
    if (!file.isFile()) return [:]
    try { return configJson.fromJson(file.getText('UTF-8'), Map.class) ?: [:] }
    catch (Throwable ignored) { return [:] }
}
def asProjectInt = { value ->
    try { return value == null ? 0 : (value as Number).intValue() }
    catch (Throwable ignored) { return 0 }
}
def asProjectDecimal = { value, int digits ->
    try { return String.format(Locale.US, "%.${digits}f", (value as Number).doubleValue()) }
    catch (Throwable ignored) { return '0' }
}
def removeLegacyWorkflowHtml = {
    Set<String> oldNames = ['review.html', 'run_report.html', 'completion_report.html'] as Set
    [orientationQcDir, runBaseDir, legacyRunBaseDir].each { root ->
        if (!root.isDirectory()) return
        root.eachFileRecurse { file ->
            if (file.isFile() && (oldNames.contains(file.getName()) ||
                    file.getName() == 'LATEST_RUN_REPORT.txt')) {
                try { Files.deleteIfExists(file.toPath()) } catch (Throwable ignored) {}
            }
        }
    }
}
def writeProjectIndex = { File runDir = null ->
    String gridLink = new File(gridQcDir, "${imageStem}_grid_qc_latest.png").isFile() ?
        "qc/01-grid/${imageStem}_grid_qc_latest.png" :
        "qc/01-grid/${imageStem}_grid_qc.png"
    boolean hasGridQc = new File(workflowDir, gridLink).isFile()
    File gridJsonFile = new File(gridQcDir, "${imageStem}_grid_qc_latest.json")
    File orientationJsonFile = new File(orientationQcDir, 'run_report.json')
    File completionJsonFile = new File(orientationQcDir, 'completion_report.json')
    def gridReport = readProjectJson(gridJsonFile)
    def orientationReport = readProjectJson(orientationJsonFile)
    def completionReport = readProjectJson(completionJsonFile)
    boolean hasOrientation = !orientationReport.isEmpty()
    boolean hasCompletion = !completionReport.isEmpty()
    boolean hasProject = new File(qupathProjectDir, 'project.qpproj').isFile()
    boolean hasContactSheet = new File(orientationQcDir, 'orientation_contact_sheet.png').isFile()
    boolean hasResultsCsv = new File(resultsTablesDir, 'orientation_results.csv').isFile()
    boolean hasReviewCsv = new File(resultsTablesDir, 'orientation_review_queue.csv').isFile()
    boolean hasPng = (resultsPngDir.listFiles()?.any { it.isFile() } ?: false)
    boolean hasOme = (resultsOmeDir.listFiles()?.any { it.isFile() } ?: false)
    boolean gridReviewPending = 'true'.equalsIgnoreCase(
        System.getProperty('corealign.dashboard.gridReviewPending', 'false'))
    def counts = orientationReport.counts instanceof Map ? orientationReport.counts : [:]
    def coreRecords = orientationReport.cores instanceof List ? orientationReport.cores : []
    int positionCount = asProjectInt(counts.positions)
    int okCountForPage = asProjectInt(counts.ok)
    int reviewCountForPage = asProjectInt(counts.needsReview ?: counts.review)
    int missingCountForPage = asProjectInt(counts.missing)
    int reusedCountForPage = asProjectInt(counts.resumedFromCheckpoint)
    int gridWidthForPage = asProjectInt(gridReport.gridWidth)
    int gridHeightForPage = asProjectInt(gridReport.gridHeight)
    int gridPresentForPage = asProjectInt(gridReport.present)
    int gridMissingForPage = asProjectInt(gridReport.missing)
    int gridCorrectedForPage = asProjectInt(gridReport.humanCorrectedTotal)
    int gridAutomaticForPage = Math.max(0, gridPresentForPage - gridCorrectedForPage)
    int gridReviewQueueForPage = asProjectInt(gridReport.reviewQueueCount)
    int dashboardPositionCount = positionCount > 0 ? positionCount :
        (gridWidthForPage > 0 && gridHeightForPage > 0 ? gridWidthForPage * gridHeightForPage : 0)
    String currentStage = hasCompletion ? 'Complete and human approved' :
        gridReviewPending ? 'Grid ready for review' :
        hasOrientation ? 'Orientation ready for review' :
        hasGridQc ? 'Grid ready for review' : 'Ready to run'
    String stageTone = hasCompletion ? 'success' :
        (gridReviewPending || hasOrientation) ? 'warning' : 'neutral'
    String stageIcon = hasCompletion ? projectIcon('check') :
        (gridReviewPending || hasOrientation) ? projectIcon('warning') : projectIcon('microscope')
    String primaryTarget = hasCompletion ? 'results' : gridReviewPending ? 'grid' : hasOrientation ? 'orientation' :
        hasGridQc ? 'grid' : 'help'
    String primaryLabel = hasCompletion ? 'Open results' : gridReviewPending ?
        'Review grid QC' : hasOrientation ?
        'Review flagged cores' : hasGridQc ? 'Review grid QC' : 'How to run'
    String nextAction = hasCompletion ?
        'Use the prepared images or open the ordered QuPath project for analysis.' :
        gridReviewPending ?
            'Check every detected circle and missing position in QuPath, then run the same script again.' :
        hasOrientation ?
            'Check the flagged cores below. Correct only what is needed in QuPath, then run CoreAlign again.' :
        hasGridQc ?
            'Check every detected circle and missing position in QuPath, then run the same script again.' :
            'Open the slide in QuPath and run CoreAlign.groovy. No configuration is required for automatic mode.'

    StringBuilder html = new StringBuilder(64000)
    html.append('''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><meta name="theme-color" content="#ffffff"><title>CoreAlign | Start here</title><script>try{var savedTheme=localStorage.getItem("corealign-theme");if(savedTheme){document.documentElement.dataset.theme=savedTheme;}}catch(e){}</script><style>
:root{color-scheme:light;--bg:oklch(1 0 0);--surface:oklch(.985 .003 165);--surface-strong:oklch(.965 .006 165);--fg:oklch(.19 .018 175);--muted:oklch(.46 .018 175);--border:oklch(.90 .01 170);--accent:oklch(.76 .17 165);--accent-deep:oklch(.57 .15 166);--accent-ink:oklch(.18 .03 168);--warning:oklch(.66 .16 70);--warning-bg:oklch(.97 .035 80);--danger:oklch(.58 .20 28);--danger-bg:oklch(.97 .03 28);--success:oklch(.57 .15 160);--success-bg:oklch(.97 .035 160);--shadow:0 1px 2px rgb(13 34 29/.04),0 10px 30px rgb(13 34 29/.06);--radius-sm:8px;--radius-md:12px;--radius-lg:16px;--header:72px}
html[data-theme="dark"]{color-scheme:dark;--bg:oklch(.15 .012 175);--surface:oklch(.19 .014 175);--surface-strong:oklch(.23 .015 175);--fg:oklch(.95 .008 165);--muted:oklch(.72 .012 165);--border:oklch(.30 .014 175);--accent:oklch(.73 .15 165);--accent-deep:oklch(.78 .14 165);--accent-ink:oklch(.14 .02 168);--warning:oklch(.78 .14 75);--warning-bg:oklch(.25 .05 70);--danger:oklch(.73 .15 28);--danger-bg:oklch(.24 .05 28);--success:oklch(.73 .14 160);--success-bg:oklch(.23 .045 160);--shadow:none}
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--bg);color:var(--fg);font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;font-size:16px;line-height:1.55}.icon{width:20px;height:20px;flex:0 0 auto}.skip{position:fixed;left:16px;top:-80px;z-index:100;padding:12px 16px;border-radius:8px;background:var(--fg);color:var(--bg);font-weight:700}.skip:focus{top:12px}.topbar{position:sticky;top:0;z-index:40;min-height:var(--header);display:flex;align-items:center;gap:24px;padding:12px clamp(16px,4vw,48px);border-bottom:1px solid var(--border);background:color-mix(in oklch,var(--bg) 92%,transparent);backdrop-filter:blur(18px)}.brand{display:flex;align-items:center;gap:10px;min-width:max-content;font-size:17px;font-weight:750;letter-spacing:-.02em}.brand-mark{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;background:var(--fg);color:var(--bg)}.brand-mark .icon{width:19px;height:19px}.nav{display:flex;gap:4px;overflow-x:auto;scrollbar-width:none}.nav::-webkit-scrollbar{display:none}.nav button,.icon-button,.history-button{min-height:44px;border:1px solid transparent;border-radius:999px;background:transparent;color:var(--muted);font:inherit;font-size:14px;font-weight:650;cursor:pointer}.nav button{padding:8px 14px;white-space:nowrap}.nav button[aria-current="page"]{background:var(--surface-strong);color:var(--fg)}.header-actions{display:flex;align-items:center;gap:8px;margin-left:auto}.history-button{display:inline-flex;align-items:center;gap:7px;padding:8px 12px;border-color:var(--border);color:var(--fg)}.icon-button{display:grid;place-items:center;width:44px;padding:0;border-color:var(--border);color:var(--fg)}.sun-icon{display:none}html[data-theme="dark"] .sun-icon{display:block}html[data-theme="dark"] .moon-icon{display:none}.shell{width:min(100%,1280px);margin:auto;padding:clamp(24px,5vw,64px) clamp(16px,4vw,48px) 96px}.panel{display:none;animation:panel-in .22s ease-out}.panel.is-active{display:block}.hero{position:relative;overflow:hidden;display:grid;gap:32px;grid-template-columns:minmax(0,1.5fr) minmax(260px,.75fr);padding:clamp(28px,5vw,56px);border:1px solid var(--border);border-radius:24px;background:linear-gradient(135deg,var(--surface) 0%,var(--bg) 62%);box-shadow:var(--shadow)}.hero:after{content:"";position:absolute;right:-80px;top:-120px;width:320px;height:320px;border-radius:50%;background:radial-gradient(circle,var(--accent) 0%,transparent 68%);opacity:.14;pointer-events:none}.eyebrow,.status-badge{display:inline-flex;align-items:center;gap:8px;width:max-content;font-size:12px;font-weight:750;letter-spacing:.07em;text-transform:uppercase}.status-badge{padding:7px 11px;border-radius:999px}.status-badge.success{background:var(--success-bg);color:var(--success)}.status-badge.warning{background:var(--warning-bg);color:var(--warning)}.status-badge.neutral{background:var(--surface-strong);color:var(--muted)}.status-badge .icon{width:16px;height:16px}h1,h2,h3{margin:0;letter-spacing:-.025em;line-height:1.16}h1{margin-top:16px;font-size:clamp(2rem,1.35rem + 3vw,3.55rem);max-width:16ch}h2{font-size:clamp(1.55rem,1.3rem + 1vw,2.15rem)}h3{font-size:1.1rem}.lede{max-width:62ch;margin:16px 0 0;color:var(--muted);font-size:clamp(1rem,.96rem + .25vw,1.12rem)}.hero-side{position:relative;z-index:1;align-self:end;padding:24px;border-radius:16px;background:var(--fg);color:var(--bg)}.hero-side .eyebrow{color:var(--accent)}.hero-side p{margin:10px 0 20px;line-height:1.5}.primary,.secondary,.text-link,.control-button{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:44px;border-radius:999px;padding:10px 18px;font:inherit;font-size:14px;font-weight:750;text-decoration:none;cursor:pointer}.primary{border:1px solid var(--accent);background:var(--accent);color:var(--accent-ink)}.secondary,.control-button{border:1px solid var(--border);background:var(--bg);color:var(--fg)}.text-link{min-height:auto;padding:0;border:0;background:transparent;color:var(--fg)}.primary .icon,.secondary .icon,.text-link .icon,.control-button .icon{width:18px;height:18px}.section-head{display:flex;align-items:end;justify-content:space-between;gap:24px;margin:0 0 24px}.section-head p{max-width:65ch;margin:8px 0 0;color:var(--muted)}.section-block{margin-top:48px}.metric-grid,.file-grid,.grid-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,170px),1fr));gap:16px}.metric,.file-card,.notice{border:1px solid var(--border);border-radius:var(--radius-md);background:var(--bg)}.metric{padding:20px}.metric strong{display:block;font-size:clamp(1.65rem,1.4rem + 1vw,2.25rem);line-height:1.1}.metric span{display:block;margin-top:6px;color:var(--muted);font-size:14px}.file-card{display:flex;flex-direction:column;padding:24px}.step-number{display:grid;place-items:center;width:36px;height:36px;margin-bottom:20px;border-radius:10px;background:var(--surface-strong);color:var(--fg);font-weight:800}.file-card p{margin:8px 0 20px;color:var(--muted)}.file-card a{margin-top:auto;align-self:flex-start}.notice{display:flex;gap:14px;padding:18px 20px}.notice .icon{color:var(--accent-deep);margin-top:2px}.notice p{margin:0;color:var(--muted)}.legend{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 16px}.legend span{display:inline-flex;align-items:center;gap:8px;padding:7px 11px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--muted);font-size:13px;font-weight:700}.legend i{width:10px;height:10px;border-radius:50%}.legend .auto i{background:rgb(0,235,230)}.legend .corrected i{background:rgb(80,240,125)}.legend .missing i{background:rgb(255,70,80)}.qc-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:12px}.zoom-controls{display:flex;align-items:center;gap:7px}.zoom-controls .control-button{min-width:44px;padding:8px 12px}.zoom-value{min-width:52px;text-align:center;color:var(--muted);font-size:13px;font-weight:750}.image-viewport{overflow:auto;max-height:72vh;border:1px solid var(--border);border-radius:var(--radius-lg);background:oklch(.12 0 0);cursor:grab;touch-action:pan-x pan-y}.image-viewport.is-panning{cursor:grabbing;user-select:none}.image-viewport img{display:block;width:100%;height:auto;max-width:none}.media-card{overflow:hidden;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--surface)}.media-card img{display:block;width:100%;height:auto;max-height:720px;object-fit:contain;background:oklch(.12 0 0)}.media-caption{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 20px}.media-caption p{margin:0;color:var(--muted)}.filter-tools{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.filterbar{display:flex;align-items:center;gap:8px;overflow-x:auto;padding-bottom:4px}.filterbar button,.preview-toggle button{min-height:44px;padding:8px 15px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--muted);font:inherit;font-weight:700;cursor:pointer;white-space:nowrap}.filterbar button[aria-pressed="true"],.preview-toggle button[aria-pressed="true"]{border-color:var(--fg);background:var(--fg);color:var(--bg)}.core-search{min-height:44px;width:min(100%,250px);padding:9px 14px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--fg);font:inherit}.core-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,230px),1fr));gap:16px;margin-top:24px}.core-card{overflow:hidden;border:1px solid var(--border);border-top:4px solid var(--success);border-radius:var(--radius-md);background:var(--bg);box-shadow:var(--shadow)}.core-card[data-status="review"],.core-card[data-status="uncertain"]{border-top-color:var(--warning)}.core-card[data-status="missing"],.core-card[data-status="no_tissue"],.core-card[data-status="processing_error"],.core-card[data-status="export_error"]{border-top-color:var(--danger)}.core-card[hidden]{display:none}.core-image{display:block;aspect-ratio:1/1;background:oklch(.12 0 0)}.core-image img{display:block;width:100%;height:100%;object-fit:contain}.core-image img[hidden]{display:none}.core-placeholder{display:grid;place-items:center;width:100%;height:100%;color:oklch(.72 0 0)}.core-placeholder .icon{width:36px;height:36px}.core-body{padding:16px}.core-title{display:flex;align-items:center;justify-content:space-between;gap:8px}.core-title strong{font-size:16px}.core-status{font-size:11px;font-weight:800;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}.preview-toggle{display:flex;gap:6px;margin:12px 0 0}.preview-toggle button{min-height:34px;padding:5px 10px;font-size:12px}.core-meta{margin:10px 0 0;color:var(--muted);font-size:13px;line-height:1.55}.help-list{display:grid;gap:16px;counter-reset:help}.help-item{position:relative;padding:24px 24px 24px 72px;border-left:2px solid var(--border)}.help-item:before{counter-increment:help;content:counter(help);position:absolute;left:20px;top:22px;display:grid;place-items:center;width:32px;height:32px;border-radius:50%;background:var(--fg);color:var(--bg);font-weight:800}.help-item p{margin:8px 0 0;max-width:68ch;color:var(--muted)}.pager{display:flex;justify-content:space-between;gap:16px;margin-top:48px;padding-top:24px;border-top:1px solid var(--border)}.footer{margin-top:64px;padding-top:24px;border-top:1px solid var(--border);color:var(--muted);font-size:13px}.path{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;word-break:break-all}.empty{padding:32px;border:1px dashed var(--border);border-radius:var(--radius-md);color:var(--muted);text-align:center}.empty .icon{width:32px;height:32px;margin-bottom:8px}.nav button:hover,.history-button:hover,.icon-button:hover,.secondary:hover,.control-button:hover,.filterbar button:hover,.preview-toggle button:hover{background:var(--surface-strong);color:var(--fg)}.primary:hover{filter:brightness(.96);transform:translateY(-1px)}button:active,a:active{transform:translateY(1px)}button:focus-visible,a:focus-visible,input:focus-visible{outline:3px solid color-mix(in oklch,var(--accent-deep) 70%,white);outline-offset:3px}.panel,.core-card,.primary{transition:opacity .2s ease,transform .2s ease,background-color .2s ease,border-color .2s ease}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@keyframes panel-in{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
.core-image>a{display:block;width:100%;height:100%}.core-image>a[hidden]{display:none}
@media(max-width:980px){.topbar{align-items:flex-start;flex-wrap:wrap;gap:8px}.nav{order:3;width:100%}.header-actions{margin-left:auto}.history-button span{display:none}.hero{grid-template-columns:1fr}.hero-side{max-width:none}.section-head{align-items:flex-start;flex-direction:column}}
@media(max-width:600px){:root{--header:116px}.brand-name{display:none}.shell{padding-top:24px}.topbar{padding-inline:12px}.nav button{padding-inline:12px}.hero{padding:24px;border-radius:16px}.metric-grid,.grid-summary{grid-template-columns:repeat(2,1fr)}.metric{padding:16px}.media-caption,.filter-tools{align-items:flex-start;flex-direction:column}.core-search{width:100%}.pager{flex-direction:column}.pager button{width:100%}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation-duration:.01ms!important;animation-iteration-count:1!important;transition-duration:.01ms!important;scroll-behavior:auto!important}}
</style></head><body><a class="skip" href="#main">Skip to content</a><header class="topbar"><div class="brand"><span class="brand-mark">''')
    html.append(projectIcon('microscope')).append('</span><span class="brand-name">CoreAlign</span></div>')
    html.append('''<nav class="nav" aria-label="Project sections"><button type="button" data-nav="overview" aria-current="page">Overview</button><button type="button" data-nav="grid">Grid QC</button><button type="button" data-nav="orientation">Orientation QC</button><button type="button" data-nav="results">Results</button><button type="button" data-nav="help">Help</button></nav><div class="header-actions"><button class="history-button" type="button" id="historyBack" title="Go back">''')
    html.append(projectIcon('back')).append('<span>Back</span></button><button class="history-button" type="button" id="historyForward" title="Go forward">')
    html.append(projectIcon('forward')).append('<span>Forward</span></button><button class="icon-button" type="button" id="themeToggle" aria-label="Switch color theme" title="Switch color theme"><span class="moon-icon">')
    html.append(projectIcon('moon')).append('</span><span class="sun-icon">').append(projectIcon('sun')).append('</span></button></div></header>')
    html.append('<main class="shell" id="main" tabindex="-1"><section class="panel is-active" id="overview" data-panel><div class="hero"><div><div class="status-badge ')
        .append(stageTone).append('">').append(stageIcon).append(projectHtmlEscape(currentStage)).append('</div><h1>CoreAlign quality-control report</h1><p class="lede">')
        .append(projectHtmlEscape(imageName)).append(' has one comprehensive view of detection, orientation, review flags, and available results.</p></div><aside class="hero-side"><div class="eyebrow">Current action</div><p>')
        .append(projectHtmlEscape(nextAction)).append('</p><button class="primary" type="button" data-nav="')
        .append(primaryTarget).append('">').append(projectHtmlEscape(primaryLabel)).append(projectIcon('forward')).append('</button></aside></div>')
    html.append('<div class="section-block metric-grid">')
    [[dashboardPositionCount, 'TMA positions'], [okCountForPage, 'Automatic QC pass'],
     [reviewCountForPage, 'Needs review'], [missingCountForPage, 'Missing'],
     [reusedCountForPage, 'Reused checkpoints']].each { metric ->
        html.append('<div class="metric"><strong>').append(metric[0]).append('</strong><span>')
            .append(projectHtmlEscape(metric[1])).append('</span></div>')
    }
    html.append('</div><div class="section-block notice">').append(projectIcon('check'))
        .append('<div><strong>Comprehensive project report</strong><p>Use the sections above to inspect current QC evidence and available outputs. Technical PNG, CSV, JSON, OME-TIFF, and checkpoint files remain linked to this report.</p></div></div>')
    html.append('<div class="pager"><span></span><button class="secondary" type="button" data-next="1">Grid QC ').append(projectIcon('forward')).append('</button></div></section>')

    html.append('<section class="panel" id="grid" data-panel><div class="section-head"><div><div class="eyebrow">Detection report</div><h2>Grid QC</h2><p>Confirm that every circle covers one core and that row-column assignments follow the slide.</p></div>')
    if (hasGridQc) html.append('<a class="secondary" href="').append(projectHtmlEscape(gridLink)).append('" target="_blank" rel="noopener">Open full image ').append(projectIcon('external')).append('</a>')
    html.append('</div>')
    if (hasGridQc) {
        html.append('<div class="grid-summary">')
        [[gridWidthForPage > 0 && gridHeightForPage > 0 ? "${gridHeightForPage} x ${gridWidthForPage}" : 'Available', 'Rows x columns'],
         [gridPresentForPage, 'Present'], [gridMissingForPage, 'Missing'],
         [gridCorrectedForPage, 'Human corrected'], [gridReviewQueueForPage, 'Review queue']].each { metric ->
            html.append('<div class="metric"><strong>').append(projectHtmlEscape(metric[0])).append('</strong><span>')
                .append(projectHtmlEscape(metric[1])).append('</span></div>')
        }
        html.append('</div><div class="section-block"><div class="qc-toolbar"><div class="legend" aria-label="Grid annotation legend"><span class="auto"><i></i>Automatic ')
            .append(gridAutomaticForPage).append('</span><span class="corrected"><i></i>Human corrected ')
            .append(gridCorrectedForPage).append('</span><span class="missing"><i></i>Missing ')
            .append(gridMissingForPage).append('</span></div><div class="zoom-controls" aria-label="Grid image controls"><button class="control-button" type="button" id="gridZoomOut" aria-label="Zoom out">-</button><span class="zoom-value" id="gridZoomValue">100%</span><button class="control-button" type="button" id="gridZoomIn" aria-label="Zoom in">+</button><button class="control-button" type="button" id="gridZoomReset">Reset</button></div></div><div class="image-viewport" id="gridViewport"><img id="gridImage" src="')
            .append(projectHtmlEscape(gridLink)).append('" alt="Latest whole-slide TMA grid quality-control image without overlay text"></div><div class="media-caption"><p>Zoom with the controls, then drag the image to inspect every row. Labels and counts stay outside the TMA area.</p></div></div>')
    } else {
        html.append('<div class="empty">').append(projectIcon('image')).append('<h3>No grid QC yet</h3><p>Run CoreAlign.groovy in QuPath to create the first detection overview.</p></div>')
    }
    html.append('<div class="section-block notice">').append(projectIcon('warning')).append('<div><strong>After correcting circles</strong><p>Draw or adjust TMA correction annotations in QuPath, then run the same script again. CoreAlign refreshes this overview and keeps the accepted grid state.</p></div></div>')
    html.append('<div class="pager"><button class="secondary" type="button" data-next="-1">').append(projectIcon('back')).append(' Overview</button><button class="secondary" type="button" data-next="1">Orientation QC ').append(projectIcon('forward')).append('</button></div></section>')

    html.append('<section class="panel" id="orientation" data-panel><div class="section-head"><div><div class="eyebrow">Rotation report</div><h2>Orientation QC</h2><p>Review flagged cores first. Compare before and rotated previews, then open either image at full size.</p></div>')
    if (hasContactSheet) html.append('<a class="secondary" href="qc/02-orientation/orientation_contact_sheet.png" target="_blank" rel="noopener">Contact sheet ').append(projectIcon('external')).append('</a>')
    html.append('</div>')
    html.append('<div class="notice">').append(projectIcon('refresh')).append('<div><strong>How to change a wrong rotation</strong><p>In QuPath, draw a small annotation on the true epidermis side of that core. Name or classify it <b>Epidermis override row-column</b>, for example <b>Epidermis override 4-C</b>. Then run the same CoreAlign.groovy again. A rerun is required, but only the changed core is recalculated and accepted cores are reused.</p></div></div>')
    if (hasOrientation && !coreRecords.isEmpty()) {
        html.append('<div class="filter-tools section-block"><div class="filterbar" role="group" aria-label="Filter core review cards"><button type="button" data-filter="review" aria-pressed="')
            .append(reviewCountForPage > 0 ? 'true' : 'false').append('">Needs review</button><button type="button" data-filter="missing" aria-pressed="false">Missing</button><button type="button" data-filter="ok" aria-pressed="')
            .append(reviewCountForPage > 0 ? 'false' : 'true').append('">QC pass</button><button type="button" data-filter="all" aria-pressed="false">All cores</button></div><label><span class="sr-only">Find a core by row and column</span><input class="core-search" id="coreSearch" type="search" placeholder="Find core, for example 4-C"></label></div><div class="core-grid" id="coreGrid">')
        coreRecords.each { record ->
            String status = (record.status ?: 'unknown').toString()
            String filterStatus = status in ['review', 'uncertain'] ? 'review' :
                status in ['missing', 'no_tissue', 'processing_error', 'export_error'] ? 'missing' : 'ok'
            String preview = record.rotatedPreview?.toString() ?: ''
            String sourcePreview = record.unrotatedPreview?.toString() ?: ''
            String coreNameForSearch = record.core?.toString() ?: ''
            html.append('<article class="core-card" data-status="').append(projectHtmlEscape(status))
                .append('" data-filter-status="').append(filterStatus).append('" data-core-name="')
                .append(projectHtmlEscape(coreNameForSearch.toLowerCase())).append('"')
            boolean initiallyHidden = reviewCountForPage > 0 ? filterStatus != 'review' : filterStatus != 'ok'
            if (initiallyHidden) html.append(' hidden')
            html.append('>')
            if (!preview.isEmpty()) {
                html.append('<div class="core-image"><a data-preview="rotated" href="').append(projectHtmlEscape(preview)).append('" target="_blank" rel="noopener"><img loading="lazy" src="')
                    .append(projectHtmlEscape(preview)).append('" alt="Rotated preview for TMA core ').append(projectHtmlEscape(record.core)).append('"></a>')
                if (!sourcePreview.isEmpty()) html.append('<a data-preview="source" href="').append(projectHtmlEscape(sourcePreview)).append('" target="_blank" rel="noopener" hidden><img loading="lazy" src="')
                    .append(projectHtmlEscape(sourcePreview)).append('" alt="Before rotation preview for TMA core ').append(projectHtmlEscape(record.core)).append('"></a>')
                html.append('</div>')
            } else {
                html.append('<div class="core-image"><div class="core-placeholder">').append(projectIcon('image')).append('<span class="sr-only">No preview available</span></div></div>')
            }
            html.append('<div class="core-body"><div class="core-title"><strong>').append(projectHtmlEscape(record.core))
                .append('</strong><span class="core-status">').append(projectHtmlEscape(status.replace('_', ' '))).append('</span></div>')
            if (!preview.isEmpty() && !sourcePreview.isEmpty()) html.append('<div class="preview-toggle" role="group" aria-label="Preview for core ').append(projectHtmlEscape(record.core)).append('"><button type="button" data-core-view="rotated" aria-pressed="true">Rotated</button><button type="button" data-core-view="source" aria-pressed="false">Before</button></div>')
            html.append('<p class="core-meta">Confidence ')
                .append(asProjectDecimal(record.confidence, 3)).append('<br>Rotate ')
                .append(asProjectDecimal(record.rotateToTopDeg, 1)).append(' deg | residual ')
                .append(asProjectDecimal(record.postRotationResidualDeg, 1)).append(' deg<br>Region ')
                .append(projectHtmlEscape(record.regionStatus ?: 'unknown'))
            if (record.reasons instanceof List && !record.reasons.isEmpty())
                html.append('<br>').append(projectHtmlEscape(record.reasons.join(', ')))
            html.append('</p></div></article>')
        }
        html.append('</div>')
    } else if (hasContactSheet) {
        html.append('<figure class="media-card"><a href="qc/02-orientation/orientation_contact_sheet.png" target="_blank" rel="noopener"><img src="qc/02-orientation/orientation_contact_sheet.png" alt="TMA orientation contact sheet"></a><figcaption class="media-caption"><p>The contact sheet is available. Run the updated CoreAlign once to add interactive per-core filters here.</p></figcaption></figure>')
    } else {
        html.append('<div class="empty">').append(projectIcon('image')).append('<h3>No orientation results yet</h3><p>Approve the grid and run CoreAlign.groovy again.</p></div>')
    }
    html.append('<div class="pager"><button class="secondary" type="button" data-next="-1">').append(projectIcon('back')).append(' Grid QC</button><button class="secondary" type="button" data-next="1">Results ').append(projectIcon('forward')).append('</button></div></section>')

    html.append('<section class="panel" id="results" data-panel><div class="section-head"><div><div class="eyebrow">Output report</div><h2>Results</h2><p>Choose files by purpose. PNG is for presentation. Multichannel OME-TIFF and the QuPath project are for research analysis.</p></div></div><div class="file-grid">')
    [[hasPng, 'Presentation PNG', 'Rotated and cropped color images ready for slides.', 'results/png/', 'folder'],
     [hasOme, 'Research OME-TIFF', 'Original-quality multichannel images using the accepted transform.', 'results/ome-tiff/', 'folder'],
     [hasResultsCsv, 'Results table', 'Per-core angles, confidence, status, and output paths.', 'results/tables/orientation_results.csv', 'chart'],
     [hasReviewCsv, 'Review queue', 'Only the cores that require focused human review.', 'results/tables/orientation_review_queue.csv', 'chart'],
     [hasProject, 'QuPath project', 'Ordered, analysis-ready core entries after final approval.', 'qupath/project.qpproj', 'microscope']].each { fileCard ->
        html.append('<article class="file-card"><div class="step-number">').append(projectIcon(fileCard[4].toString())).append('</div><h3>')
            .append(projectHtmlEscape(fileCard[1])).append('</h3><p>').append(projectHtmlEscape(fileCard[2])).append('</p>')
        if (fileCard[0]) html.append('<a class="text-link" href="').append(projectHtmlEscape(fileCard[3])).append('" target="_blank" rel="noopener">Open ').append(projectIcon('external')).append('</a>')
        else html.append('<span class="core-status">Not created yet</span>')
        html.append('</article>')
    }
    html.append('</div><div class="section-block notice">').append(projectIcon('folder')).append('<div><strong>Keep the work folder</strong><p>It contains resumable checkpoints. Do not delete it when changing from a presentation package to a research package.</p></div></div><div class="pager"><button class="secondary" type="button" data-next="-1">').append(projectIcon('back')).append(' Orientation QC</button><button class="secondary" type="button" data-next="1">Help ').append(projectIcon('forward')).append('</button></div></section>')

    html.append('<section class="panel" id="help" data-panel><div class="section-head"><div><div class="eyebrow">Quick guide</div><h2>Run, review, continue</h2><p>The workflow always uses the same script and the same START-HERE.html dashboard.</p></div></div><div class="help-list"><article class="help-item"><h3>Open the slide</h3><p>Keep the slide, CoreAlign.groovy, and optional corealign.config.json in this project folder. Open the slide in QuPath.</p></article><article class="help-item"><h3>Run CoreAlign.groovy</h3><p>Detection, rotation, cropping, exports, and safe resume are handled automatically. Stop points are intentional human review gates.</p></article><article class="help-item"><h3>Use this dashboard</h3><p>Open START-HERE.html after every run. Use the top menu, Back and Forward controls, or the Previous and Next buttons. Images open in a new tab, so this page stays available.</p></article><article class="help-item"><h3>Correct only flagged cores</h3><p>Use TMA correction, TMA crop override, or Epidermis override annotations in QuPath. Run the same script again. Accepted checkpoints are reused.</p></article></div><div class="section-block notice">').append(projectIcon('check')).append('<div><strong>No report filenames to remember</strong><p>START-HERE.html is the only workflow HTML. Machine-readable audit files stay in JSON and CSV format.</p></div></div><div class="pager"><button class="secondary" type="button" data-next="-1">').append(projectIcon('back')).append(' Results</button><button class="secondary" type="button" data-nav="overview">').append(projectIcon('home')).append(' Overview</button></div></section>')
    html.append('<footer class="footer"><p>Project folder: <span class="path">').append(projectHtmlEscape(workflowDir.getAbsolutePath())).append('</span></p>')
    if (runDir != null) html.append('<p>Latest internal run: <span class="path">').append(projectHtmlEscape(runDir.getAbsolutePath())).append('</span></p>')
    html.append('<p>CoreAlign keeps the current quality-control evidence, review status, and result links together in this project report.</p></footer></main>')
    html.append('''<script>(function(){
var order=["overview","grid","orientation","results","help"];
function showPanel(id,push){if(order.indexOf(id)<0){id="overview";}document.querySelectorAll("[data-panel]").forEach(function(panel){panel.classList.toggle("is-active",panel.id===id);});document.querySelectorAll("[data-nav]").forEach(function(button){if(button.closest(".nav")){if(button.dataset.nav===id){button.setAttribute("aria-current","page");}else{button.removeAttribute("aria-current");}}});if(push){history.pushState({section:id},"","#"+id);}document.getElementById("main").focus({preventScroll:true});window.scrollTo({top:0,behavior:"smooth"});}
document.querySelectorAll("[data-nav]").forEach(function(button){button.addEventListener("click",function(){showPanel(button.dataset.nav,true);});});
document.querySelectorAll("[data-next]").forEach(function(button){button.addEventListener("click",function(){var active=document.querySelector("[data-panel].is-active");var current=order.indexOf(active?active.id:"overview");var target=Math.max(0,Math.min(order.length-1,current+Number(button.dataset.next)));showPanel(order[target],true);});});
document.getElementById("historyBack").addEventListener("click",function(){history.back();});document.getElementById("historyForward").addEventListener("click",function(){history.forward();});window.addEventListener("popstate",function(event){showPanel(event.state&&event.state.section?event.state.section:(location.hash.slice(1)||"overview"),false);});var initial=location.hash.slice(1)||"overview";history.replaceState({section:initial},"",location.hash||"#"+initial);showPanel(initial,false);
var pressedFilter=document.querySelector("[data-filter][aria-pressed=true]");var activeCoreFilter=pressedFilter?pressedFilter.dataset.filter:"all";var coreSearch=document.getElementById("coreSearch");
function applyCoreFilters(){var query=coreSearch?coreSearch.value.trim().toLowerCase():"";document.querySelectorAll(".core-card").forEach(function(card){var statusOk=activeCoreFilter==="all"||card.dataset.filterStatus===activeCoreFilter;var nameOk=!query||(card.dataset.coreName||"").indexOf(query)>=0;card.hidden=!(statusOk&&nameOk);});}
document.querySelectorAll("[data-filter]").forEach(function(button){button.addEventListener("click",function(){activeCoreFilter=button.dataset.filter;document.querySelectorAll("[data-filter]").forEach(function(other){other.setAttribute("aria-pressed",other===button?"true":"false");});applyCoreFilters();});});if(coreSearch){coreSearch.addEventListener("input",applyCoreFilters);}
document.querySelectorAll("[data-core-view]").forEach(function(button){button.addEventListener("click",function(){var card=button.closest(".core-card");var view=button.dataset.coreView;card.querySelectorAll("[data-core-view]").forEach(function(other){other.setAttribute("aria-pressed",other===button?"true":"false");});card.querySelectorAll("[data-preview]").forEach(function(preview){preview.hidden=preview.dataset.preview!==view;});});});
var gridImage=document.getElementById("gridImage"),gridViewport=document.getElementById("gridViewport"),gridZoomValue=document.getElementById("gridZoomValue"),gridZoom=100;function setGridZoom(value){gridZoom=Math.max(50,Math.min(300,value));if(gridImage){gridImage.style.width=gridZoom+"%";}if(gridZoomValue){gridZoomValue.textContent=gridZoom+"%";}}var zoomIn=document.getElementById("gridZoomIn"),zoomOut=document.getElementById("gridZoomOut"),zoomReset=document.getElementById("gridZoomReset");if(zoomIn){zoomIn.addEventListener("click",function(){setGridZoom(gridZoom+25);});}if(zoomOut){zoomOut.addEventListener("click",function(){setGridZoom(gridZoom-25);});}if(zoomReset){zoomReset.addEventListener("click",function(){setGridZoom(100);if(gridViewport){gridViewport.scrollTo(0,0);}});}if(gridViewport){var panning=false,startX=0,startY=0,startLeft=0,startTop=0;gridViewport.addEventListener("pointerdown",function(event){if(event.button!==0){return;}panning=true;startX=event.clientX;startY=event.clientY;startLeft=gridViewport.scrollLeft;startTop=gridViewport.scrollTop;gridViewport.classList.add("is-panning");gridViewport.setPointerCapture(event.pointerId);});gridViewport.addEventListener("pointermove",function(event){if(!panning){return;}gridViewport.scrollLeft=startLeft-(event.clientX-startX);gridViewport.scrollTop=startTop-(event.clientY-startY);});function stopPan(){panning=false;gridViewport.classList.remove("is-panning");}gridViewport.addEventListener("pointerup",stopPan);gridViewport.addEventListener("pointercancel",stopPan);}
document.getElementById("themeToggle").addEventListener("click",function(){var current=document.documentElement.dataset.theme;var next=current==="dark"?"light":"dark";document.documentElement.dataset.theme=next;try{localStorage.setItem("corealign-theme",next);}catch(e){}});
})();</script></body></html>''')
    new File(workflowDir, 'START-HERE.html').setText(html.toString(), 'UTF-8')
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
    ['orientation_contact_sheet.png', 'run_report.json',
     'orientation_review_queue.csv', 'completion_report.json'].each { name ->
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
    removeLegacyWorkflowHtml()
    writeProjectIndex(runDir)
    println "Published ${published} easy-to-find project file(s) under qc/ and results/."
}
removeLegacyWorkflowHtml()
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
System.setProperty('corealign.dashboard.gridReviewPending', 'true')
writeProjectIndex(null)
runWorkflowScript(step3)
if (System.getProperty('tma.review.status', '') != 'APPROVED') {
    writeProjectIndex(null)
    println 'Pipeline stopped safely: the current grid was not approved.'
    return
}
System.clearProperty('corealign.dashboard.gridReviewPending')
writeProjectIndex(null)

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
        "Project dashboard:\n${reportPath}\n\n" +
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
File completionDashboardFile = new File(workflowDir, 'START-HERE.html')
String completedRunDir = System.getProperty('tma.orientation.runDir', '')
if (!completedRunDir.isEmpty()) {
    File completionDir = new File(completedRunDir)
    if (completionDir.isDirectory()) {
        File completionJsonFile = new File(completionDir, 'completion_report.json')
        File finalApprovalFileForReport = new File(stateDir, 'final_orientation_approval.json')
        def finalApprovalForReport = finalApprovalFileForReport.isFile() ?
            configJson.fromJson(finalApprovalFileForReport.getText('UTF-8'), Map.class) : [:]
        String completedAt = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
        def completionReport = [schemaVersion: 1, status: 'COMPLETE_HUMAN_APPROVED',
            completedAt: completedAt, image: imageName, profile: profileName,
            runDirectory: completionDir.getAbsolutePath(),
            projectDashboard: completionDashboardFile.getAbsolutePath(),
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
        System.setProperty('tma.completion.reportPath', completionDashboardFile.getAbsolutePath())
        println "Completion data: ${completionJsonFile.getAbsolutePath()}"
    }
}
publishCurrentRun()
println "Project dashboard: ${completionDashboardFile.getAbsolutePath()}"
if (ALL_IN_ONE_INTEGRATION_TEST)
    println '=== CoreAlign all-in-one INTEGRATION TEST COMPLETE; approvals are test-only ==='
else
    println '=== CoreAlign COMPLETE; grid and orientation are human-approved ==='

if (!ALL_IN_ONE_INTEGRATION_TEST) {
    String reportPath = completionDashboardFile.getAbsolutePath()
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
        "Project dashboard:\n${reportPath}\n\n${projectMessage}")
}
