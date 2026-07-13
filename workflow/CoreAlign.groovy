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
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
H4sIAAAAAAACE61XfW/UNhj/P5/CrRBJSs8FNCSG6FC5lpeJQtcW9gKs8iXPXUyTOLOdlqPcd99j
O3GSuxunjSFR5ezn9fe8em9nh5yC0kIC0RmQnClNsrpg5YhVlRRXkJLz4wMykzwlbKpBEkZ+qU+Y
zohEPib1XiKZyijZ2QsCXlRCapKIgs6EmOVAZ0qU9Dn+eVrzPAXZkvxVVyiD5nxCkbpAorHIhTwX
IldraMTkEyRaUaP4jfv+BlXGQTKZZHN6CFNW5xpdeI4eeAM/sStGFSS15HpOj0EpNoNDPkOPgiCF
KeGFOWCakX0yAz2upYRSv2xPo9hSfUK/kKCEa9JzMYpRtD6RoPX8RPJS83KGZ4kEpgE5+ZREPfnI
X+d5TG4Cgv8OOcvFTFGViesjKYVsjIvCNkwrcQl3SfhaOJMJV0RUUNIwtuLQiFqWwcKaq0BeYQD3
O+8o+nZmTxuPPHLLVC/aCyQMnvEcyIQpIM74QMt5Y78RgvaZODjoTtyPyNljfG+vtxwzuX275TCK
TISjuL2MWzUrFO7LRAW/tTAmoY4FSZhOMhKdZ1JcswnayWclwpYiwAsLvRM4RL2zv/UBMwO1OsSM
qrenLxUqmvIyJTeED6zn1qyzJIMCbTCywymaE5KFF2kUG5HLbpnUsabjZc+lxhvDucGjdT4tyz6b
Kw0FdcGoQOp5FNboGs1EAWGM6eMqeoQZNYLPpkIwfYIzjck7c2nwmhUwwOMYNEttJZhf5ho9f/KI
hJY8HDCfofY2nwwllVDlLIGDPI/2oic8/kDRkA9U8+mdW3toTpO8A7r3fx6M/mCjL3dHP9KL0cc7
hu4i7FWT0UK5Oioq9C+OB6pbq2ziYtfSWOxyE0QJgsxyBJteC3lJW7Zw1xrnWf2HgR2N0gW7qHgF
OS/hwjIZiL01Fq+DiRJ5rcGlchw7w1xls9z+6BnXakbhbfFfmMKnpv80EGz1mREGl0Hf1VV88m5j
d/H3mOXJZSWwrRH4zJVWZCokzg5sPNbHRx/KWzcDY1b8XWwvNSfbeloe10z8TwTCuEmnUhQ/40e0
LPscPusofHv+bPTQAH3MKprgGFMxlt9K6cD39dntsajzlJRCo/Es7YzsULH+gysRJ3HVXxOwltXm
Va1MZwgPTk5O37w7OgzJ169eNu0KcKtXREjiAzQk/ZWnOJ2ZIrnACtzqV629wkJdEf8C+CzT65nc
3f+VTOF5ButwI6kAZZEtbNDMNpK4sZvP7Uxzvq9MtsAwJ2Z5OASNIwLV7pNumaAVSy5Pnz+N7u6S
+w8e2D9xx3PMlTKNai2Lpf/hofnvxqNpCa8w6ZH8/cfAQ2iOFQWGZt8QSUY/+VFobpC4t7Y0ewAi
M8Y7dxhJmqCfIH8zEUhFjam6S9rD33uHHkZJU45ZYBeyPkvR+IOHE/QFWOngMoaYtcR2aklLk0FI
49r0kMQCEUVrZZEnQ9geDZEfCnolkktIoynLFXTjHy231FyUZ6KWCfih6J2zAqraD5l3LK8xvQ5b
PqIsY2gcXhKGq4DzCfN1ncqxKKc8hfI/qU0881B1J3Step8nnuEgnwlcPbPiHUhlJP97W1grglw5
GWjSZkX/AA9qkw2KOO+wH0noLzhbayl6snpzd5MHYy+IsBLLnbmAtkItsN9W1kuyVfFNC0qJmRdd
I8rwidIHyDSlF+bZsk6srfDHj+13063bY6r4F3DraTQQ5psudpaY7Czddu3VXH/nrj/ueqZtL4mo
8dPM3xITVKGV2DbINUbdPdtSXkBpgq/Wvgpki9g+Gb6W2hdL6/sSfAOPg+7JhYXfCIha0f1r++LI
XXSPRQq5eRphb5H+FBdftwJMuQT/7nhbpfb5tGkhbjY51d/kcCXDXfKKw3UzbQ2MftjGQWVeaXlJ
tn32eOAtgt1C4xNn4RJs86oT9KP8spyK1wL3XJ7YvN88MjuTbt0sJeHChl8tZXpvoq4xu1+zqp4o
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
