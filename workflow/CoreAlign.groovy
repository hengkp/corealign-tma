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
File profileStateDir = new File(new File(new File(workflowDir, 'tma_pipeline_state'), imageStem),
    'config')
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
/b/phycCQZgBAA==
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
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
wHlUxwEA
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
0TyJ/PXW2v8BUs6jlaGeAAA=
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
H4sIAJ6EVGoCA61Xe3PUNhD/359CyTDYDpwCTJmhDCkTLuHRIZAmgT6AZnT2ni2wLVeSE45w370r
yZbtuyuZljJDxift47fv1e7ODjkBpYUEonMgBVOa5E3JqgmraykuICVnR/skkzwlbK5BEkZ+aY6Z
zolEPib1biKZyinZ2Q0CXtZCapKIkmZCZAXQTImKPsM/TxpepCA7kr+aGmXQgs8oUpdINBWFkGdC
FGoDjZh9hEQrahS/dt/foMo5SCaTfEEPYM6aQqMJz9ACD/Aju2BUQdJIrhf0CJRiGRzwDC0KghTm
hJfmgGlG9kgGetpICZV+0Z1GsaX6iHYhQQWXZGBiFKNofSxB68Wx5JXmVYZniQSmATn5nEQD+cjf
FEVMrgKC/w44K0SmqMrF5aGUQrbgorAL01pcwtskfCUcZMIVETVUNIytOATRyCpYWrgK5AUGcK+3
jqJtp/a0tch7bpXqeXeBhMFTXgCZMQXEgQ+0XLT4jRDEZ+LgXHfsfkQOj7G9u95yzOTmzY7DKDIR
juLuMu7UrFG4LxMV/NbCQEIdS5IwneQkOsuluGQzxMmzCt2WooOX1vVO4NjrPf7OBswM1Oo8ZlS9
OXmhUNGcVym5InyEnltYp0kOJWIwssM5wgnJ0os0io3IVbNM6ljoeDkwqbXGcF5j0SabVmWfLpSG
krpg1CD1IgobNI3mooQwxvRxFT3BjJrAZ1MhmD7BqcbkzVwavGIljPxxBJqlthLML3ONlj9+SEJL
Ho6YT1F7l0+GkkqoC5bAflFEu9FjHr+nCOQ91Xx+68YuwmmTd0T37s/9yR9s8uXO5Ed6Pvlwy9Cd
h4NqMlooV4dljfbF8Uh1h8omrisgVtgfAzetfxhHohpdsvOa11DwCs6x52nrNC/fOLArynNTkNT0
hRba1lAbwnOR/a5q90m1jVXv7zH7kk+1wHZD4DNXWpG5kNjTsSFYpA/fVzeuRmAwbvszJYpGgyup
5fZK07AtoeNxRe5/oueMmXQuRfkzfkSrss/gs47CN2dPJw+Mu45YTRMcLyrGslhLafi+/rc9FU2R
kkpoBM/SHmTvFWs/uNR1EtftNQHrWKkJdKNMxYb7x8cnr98eHoTk61cvm/aFsTVIbiTxARqT/spT
nJpMkUJgZWwNq8leYQGtiX8OPMv1ZiZ3938lU3iWwya/kVSAsp4tbdDMlpC4cVgs7Kxxtq9NnMAw
J2aoH4DG1o1q90g/5GnNkk8nz55Ed26Te/fv2z9xz3PElTINZCOLpf/hgfnvxlaC1r3EpEfydx8C
70JzrCgwhH1FJJn85EeUuUHiwTrRzmf0zBTv3GEkaYJ2gvzNRCAVDabqbdId/j449G6UNOWYBXZR
GrKUrT14OENbgFXOXQaIWRdsB5W0MhmENK59jkmsI6JooyzyeOy2h2PPjwW9FMknSKM5KxT0YxmR
W2ouqlPRyAT8sPLGWQF145v/W1Y0mF4HHR9RljE0Bq8IwxHtbMJ83aRyKqo5T6H6T2oTzzxW3Qvd
qN7niWfYLzKBK2FevgWpjOR/j4V1IsiFk4GQrlf0D+5BbbL1Ig597EcShovH1kaKgazBPLzOgqkX
RFiF5c5cQDuh1rHfVjZIsnXxbQtKiZkXfSPK8ekwdJBpSs/Nc2KTWFvhjx7Z77Zbd8dU8S/g1sZo
JMw3XewsMdlZue3bq7n+zh182vdM214S0eCnmb8VJqhClNg2yCVG3T2nUl5CZYKvNm7rsvPYHhm/
YrqXRGf7ivtGFgf9UwgLvxUQdaKH1/YlULjoHokUCvNkwd4i/SkupG4FmHMJ/j3wpk7ts+a6RbVd
QtVwCcXFCne8Cw6X7bQ1bvTDNg5q83oqKrLts8c73nqwX2h84ixdgl2/6gTDKL+o5uKVwP2TJzbv
rx+ZPaQbVytJuLThVyuZPpioG2APa1Y1M+W+cTTevWfXlL8BUjBu86gPAAA=
''')
def step5 = new EmbeddedWorkflowScript(name: '05_finalize_orientation_review.groovy', payload: '''
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
4J/oJwAA
''')
def step6 = new EmbeddedWorkflowScript(name: '06_export_presentation_package.groovy', payload: '''
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
''')

def step7 = new EmbeddedWorkflowScript(name: '07_build_qupath_analysis_project.groovy', payload: '''
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
ylrJKj50AIH9/wetmHb0HTAAAA==
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
    File reportDir = new File(workflowDir, 'tma_grid_qc')
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
        File reportDir = new File(workflowDir, 'tma_grid_qc')
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
    File reportDir = new File(workflowDir, 'tma_grid_qc')
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
File approvalFile = new File(new File(new File(workflowDir, 'tma_pipeline_state'), imageStem),
    'approved_grid.json')
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
        println 'Inspect the live grid and tma_grid_qc output. Add TMA correction / TMA mark missing annotations where needed, then run this one-click script again.'
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
        File finalApprovalFileForReport = new File(new File(new File(workflowDir,
            'tma_pipeline_state'), imageStem), 'final_orientation_approval.json')
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
