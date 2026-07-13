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
H4sIAGOFVGoCA+2963rbSK4o+t9PwXj1jKREki+5dFqOk63Icqzdvi1J7sQr7a2PliiLY4lUk1Rs
Tdrftx/iPOF5kgOg7mSRkpP0WXvOmV5rYpGsQqGqUCgAhQK2nj7dcJ46vX773Nl1ak5zkYS1MPK9
IHHiWz9w+idNZxhGXuzEoePN/ZEXzfzYmYd+kMTOYn7nRqM6gEAo3UXguOPEixyX6t1E/siZuLFz
7XmBM4w8N/FGDgD9z8W5m0zqzvulM/LG7mKaVJ1k4jnxMPLnCYKKk3AeO/7YCUJnEbvXU09BxOYB
IUCxikhF4d3WMJwuZoED2AWJnyxlkeiLJ7H7OHETx0+cUejFDXzhODt1p+uN/QB6h81H3o0fBk44
djx3OAFER/4Xf7RwpzQC+OzNvQDbmC7rDMJu3WnHiT+DnjEYfIigTryIxu7Qgx7QoxuMnMQNbqA2
r/scW3dHMYxWFCZuAm3XYnfsOXfQUnjnjKNwRjBhPm78AEFOATiv/QJqYy1qF3omKvlRzEYTRxwH
ESGMqXqqQxzQy7rTc78AmGkYx1Mvjp3xYjqtwfDBoCJSzvnph6oTAIJfPOfspF3rdw4PYeQXEfQO
26gyQPAfDPoX37uLq9BAkLhDIKKJ5wE+7L1z1D85rjqt3m9VGg9GB87Ij7whteRGMJuxmLETN0Bc
wy9eBPPu8Uk7iNw7GLLED5YAJOAjtzUKEwcgaHMARBDDD5xPl80gthl7SAWxM5y6ccwghhH0buY5
SehstmVd0ewmTlMtWiBsBEk0Wnf6ODG8iIONwwRweGMASHMiG7XR42mYSEKs4RjL0YNh8AgIe95y
p/5NMCN6h+46fyxcpHI2H3P/3kPicqfL2I/rAhxiB8MMEACHESdvPlRQ+gbWEywdSVgEJYbSMxeW
ZxIB3eKaQ3BbGxv+bB5G2O4cV+3Uv64Pw9ksDOqtcBpG/TCcxpYy4fU/YFbjOk7xGftdUIqmw2Ol
WzQ12bJsfcb1zsy98c6nbuAVFOrS3673x8KLE1u50K93zzq2dnyEH9fvIh94WVwPZ14dyP58Gbkz
f/SR3spBmYZDXwwHzq8/hJo99uMQFkAYLY2iY+QG0N+Zl7j1zgn8O3IT11okXCTYbt8fj3mjtlKy
USj66eSYNy2KIu73s2l9Fo68ad0LFrO4fuADLcUwOGfRSAG1lTxHsugv5zng5pE/85EKoWQY069O
kHg32vDAyNRvwhDorX4TwxB9gH/eL/zpSCvzD/eLe88G3Q/Z5HbO9I919y6pv3djf9hLovDWy3wj
Osy8PQQWlHn5IXLnE38Y7x5kPnWRt0d+cHOEe1vmMyFYf78Yj73IGxGaOWWaY9xT+pEbxDhLZ/NM
uRsPxiVVzCgUwEiMfRi1Q/gntn/qwXYygu23Fc6XZ3Nc2Ua52BsugGyW9RPg6YDWgX+jrQQqknj3
Sb0Hb6beAewkh0RSGxtbW07tR/2HwM7mXuTCSqiNI88TO37M2SSwxu36K2c2Y/JGa9nqHOJeX/+x
aGxAs85wfAMUBDPs7DtfHf7z1ltWxe+xO51eu8Nbp/bW6S3jxAPq9ZJz2OO8KFmWqagoU3EeBFCg
+gxEoCEd3AZuj0m0hFKRlyyiwOFLpT53oxjXTXlVg/UkZA2UKxVsHCEO3QQklXJ/AvsmSUmwU8Cm
M6qodiQSDxsS4YNwgYXTOI/Y60K0WVWGNfv9/ybi72GzyaB9DS89N8jgzeG8Z58ZyvzhcTir9vvA
foJ4DfLREZBklyKgejyf+km5VC1VYAuZTmEjBLh+UofCs3IFUDgO77yo5cZeGcYNVn0wak6nUOYJ
FPLj9mwOaFdofDb43DVPm8eXvU5vcHD28bTXPDk/bgO2csrLpWTm1pmAT8JAXYgOB+FdELvICkpV
EC63RxUBsv3p/KzbXxugd48MxgC3Q+BwRZx327912h8HJ81Pg/POp/ZxjwFD+s9A4vLQiXtPG1EM
kH7Z3pZ4tbpn54Neq7kKIRRRe0NXYPJS9ax71m/2O2eng97FOfVxDWhCiuot5thPBfgFAhak2G1/
QLjd9mHntH3SPu0P2qfN98ftAwYcqdACmossuC2guNcOcGWMAHoSLTyFNYPdaze7raO1UCa4Pc+N
hhOF70t9IBhInJUWINvuDnpHncP+4LDbbOEAOfts0a5qA6aqBa+8qDfxx8khyJH4EZrbrj/fzjTX
7/R6F21otfuhc7pOF/p+HC+8EzcCqZX6sLNr78OalKGQNkhkJzsynGxbZ6eHnYP2aWstwF0i31YY
jFExHXo0DoSyIJNe87f24PDi+Bga6J0dXxAxoipQQCUxKGuHoKF1pYJ2HtxIGjEgnwJ1wx+QDAek
s62AekoqxdnMQ5GzRIwqToOkJdM+GJxcHPc7raPm6Wn7WGtAkEl+I0xpHZ2ACOAPJ6DAedNMi3zs
z896/YFco/2z43a3CUM/OGh/KB7+eRgnXb5O+4A8iFhD78DDUdrZVbzIAI900wHKp6dCrqQBB9Lp
JCjcoMoB0HcrG3wr6LcBeOusC9PbOQawANCy5WSAg0qYtGAfBLEP4ALEEuwNbDuQ83De7PY7zWMG
HdsB2E/S7antgfqK7KLVP+sWdQzUwsXUjXoeai3Ym5935VScXSBT6HZOPww6MOXd4vEHzcWLujAM
HZjeiMj+hbZSNWD0c01gZws2JMDDNWCcifSPYAUdnR0frLPuE+IjIHt48SScjsTK307xxEcu+bzF
Lnr969qwwtsUnN3XnGZ7R+12H9aa6KF1Isnm0vepT7u723rN4+Z7WK9Hqyofu9fe9MjzbyYJSgMv
GAhAvw/7wYCBwgXzsXPQPxqcfyqAxw1BPQQLy+WjP0omOInb29vbVrCd01X9M0D6Ae/pjujpQfuw
Cdxp8KHbOYAhz4gYaESsM4shUblaWc3j47OPA+Bpgikcwpv3zdav0NH+EdAqwVzF5UC8C++a0jJ1
yMW9j34yAVr+AK0rTse5xVm3A9sua7N5/AEe+0cng9/a3R5tvpoEaWntBh6Tyew3L4ppsyXsSqhN
1aQljNt0a8/rP9cixoFrM40FlyQqrYtuF+WV8+4ZcJL24KjZOypgXkMk1BsQ1ULUSY/ceMKYlgVc
q93r4ZpfG+IQFFdUxwko9cqGXKYpmKdzmKr1moEZmS+SxzZx0O63STBiS3rdPo28hBk6V7aH6kbr
7OQcSAKkxsFx+0OzdWmUafc4Ha5ocxjO5kApwGeOvRt3uDxXM+Uh+dvbd/h/K1SUIqXEcWOn5yXU
lX/EYQDDE3h3jmYBAhUnRqy9JAG0YOmSulVnBwWwcYlRZ6MNQkfruNnrgVRzgtyhJI4mnDIbV1Ae
S6JKs9uFlWyWV6ZdaXCW5c9grcHSbptVCpadMP2SrfLUnSEHKmWNx9pCQJn00c2g7nKWbYotcRoA
KKC3xjnq8Vl3cPYrwFYmWtB/h7fdD+/L27Ar7MA/OzuSAWNxtt/Zq+y+fAnlX0Mlo8phs3OcU+EF
VPh5G/+nVxD9t1faeQ2VfoH/QXOVH2+GIgsi2v8X87/CuES2vwM3caFzsBhbiyiCaeyItyiIjZ2y
VgrWA4jxFecrzeaB707Dmxj23/CuHUVhxM125ZJ+JgdTjoR2GrLm8GAB1nxQRxsbO/yQBzR0ElQv
VTRLxMbDBucWcZEQGsPfBfKGUvfi9BRYNtIVdpEOMCLon+wFsp0evS0zpjXxQRwGPXOZLnUkPvCC
Qva//geaUxhkLCdM4sAIcBRZqdishIsAa2kwTO4ENbEM2UaQ+vzZzUejFRJDuGwMH4+Mj0zwQQY0
R640DZzSPsxWL/Hmzm7DOCNVR01QoiTLb9K0N5yfvuZ1jKH3sKmqSLN8jPUQ4wfnnv06enDm91pR
MSpO+aev+pDUY/+fCLWCEPQP0A6N38id+53RPfT28xW9kPiDUn3rRca36Oa6643Srz5EnhekX76f
Ljz5jl4GiyGIVJG0l0nbWZbceFHeKVZKsDhEuDoJveEkTqqjyP2jCmMPogH8wX/jZRJWqb5f5WBK
FbNj62AgC1txGM5H1cmue1+dv3xefR1ORjfVW//Vz9UgGu8CbjvV2Wy+U70lNTCoDgEl8XsOeuct
/luDP7dRUvXmQ3dW9WpDdzTBo4aqJ35xtCNvtA7CbGrWxvaHoHaDE78mckQkKfScUnbUOGgS6P0g
bgZLKq3bV/Fktuoc+3HiJKxxblwV36FwmY5v3zWYtqxbTXUzLKsOai5as+kBzfxP6JcmuPz97w5X
MQCjMn0VJlaTAx2H4W18yqgujXHaApzqYJn1ylgklbwm2tKl4JsaSa0EasZgGujsgMpJJxh599AC
q+Y/fpxxf8sZH6pZEXsd/idY0Zs3ji9faqxEvn/IhS0HhkHHmcsZA7msDBQszC+NDWeAPxQZbSEZ
6OjMVWvwYUNxbf6rvgj8PxY47Fb+nX2pKuhMXf42PmsMXnsyiih2rx5UAZJzBKZyXYmeyi31Y7OL
skXDAWGGL4Stg+Z5R9C/Mw4XwajuoP6MBEgHK0nowKMThEGteShK8qNEMuo4Useql5ia/ghSF3P7
JJ/QUTiKkhiBlEvuuAT9ylAyzlnOGKipLG/X628smze1B9yOy4uWudTA5W3fWFObGq2GMXscG1Gc
E4NZWlJLti1RT9KIWVMjJEtdJc0wq76cTJRdxMgpoU4fqc9+cuU86LKTUrwm3hSkWmdG7RAsywiu
APvjD8DJyABUilrvX6F7gCyEbjQHPm5EqFVs0JktUaP2nWkm5+xBY9ri8xNWGXkXf0VmBRdFZfFR
2QaMRjPl2S9UgegkE/0YoMWHooNftmp0sHqTqkeiV2jHuIh8Q3a/6HZAWSCrBKkCep+YYtAbTjxS
DQB6CUGUOFcXoyHAig6nRte7c6gzvJjWUd5HYgHf3k97azYrzwK6XZ+EM9D7QbpiznQ10BBr7Ai2
pCwopImB/jIrULWYRqKUVFacj96ffyoY4nharXZGSNY2OPEIOO+cEpUrOQ3Vt3RpAx0NqsIg8uZT
d+g1p9PyVvmdX/kdnbR+ryf++NlPW8z+uLri5//VrP2XW/vndu2X+qB29QwrDkqpEdB5mg6Sd4Pp
GjTc72F/SE2amkoADWLywAXVccBk5YGcJNKs0SSbqp0PZu7PPdgTvQFVw9mXqDFw7hzqfHGnWF0H
KZoBOKyINxqQORwNdKW/wOZyROw4/kuceYQ57GxMgnF4/Q/DYUXJVUw8hu+CxMiSVq680+hs5aJN
gyMuK3Z7Qoi5Mj4eox+HAQDrhj47gW8GowMfICdelEaGFM0Q+SbHoHvW4Qjws6ohyXahTyYYAAc/
R59SRZbZIpdmkci901A4QffKmXtf5pXeo3gXc0NM1THeCguMob99Ht43ALMqNA1/l1UdfkN/qMqR
ItcPdB02cHmquY5cSa1r6s7m2zs0Vhz/L2LAkCN8cd4423h2LNDBB+3rW+blIr7uiK/8+Ytoh7mD
g6jXdUe+y32JeIOuaPBuggu37Dpv9p0aDdx5pwKfn+07eH5NvtH00iz91tHK1qxlOTquQId8gfvh
gYdaiR0Z/jwSc5iI0mW3orc/Imx3XrNRGiGyz1/JUZKF3jqqSM0ownEbySmJvxBCwMgWnjEX9EIa
UHm9UknXV7EvVE7z5JIAYqXglzZLFdze9FfV7KvfgT1W0isQ6jrPoBjfVRAWcNZNhPgMP+q9ikWv
Jsls2o6H7tz7hs4JarJ1jM5rJCp/R1T+DjS9V7J9fkOfp4n961v6epPzlXr59z8Wof37ZmkTv//H
81/wM+81c1d+nl5efLa4N3P5OMSj+PpFDwD8rf4cXUK+CD9LVmTnURB2DAjxxN19+Uq3ohiDf71M
vM9Xzoj8ZJHYdb9Z5E2dAPbPAEegd9SsAShQDllpNmnEvwAIkMtF/7D2umTyL1ZUUz5MzEt/2969
B3RBZv27s30/HuPp2j9CPygL/0NySG8m4cwfUidodwd19AZvWPAeIcWiYVrsO/Q1LaLWZ7cjP4o5
3TAws7kuKNjrVTV43L4OdF6HujVcCBcXnYN65AajcIY/BfuGz3je0PfukzJHDyaHj5BldySX5/oM
hJMyVk1CJhXKxtWLrAd0vds+P2622oP2p06v3zn9oPYCxhsyFZr9s5NOa3By9lv7MXvwD0Wyokw9
yPbcIAx8IGN0FaCJvtF37indVwI1f/MO9879n77e4HxgYb6ZPvw5of1T/yR21IfNK4JEH0BXQGcj
Zm7IGEfwbDVlHFGSA10gMkSHv2Djx/9Yd9+8cT77VdkqJz4y0FQFbylnBZaKOf05BS+xoPqokK/w
Fv34xCdXhHLlii3J0p+limYQ5CuccOUFcMMQzG/m3npS7swat/EQaojHoYXSogRQx4thnPMzAFT5
GyTIXJAVU64EEX6IN+umnrjnQZ0YT0M3QY4JOqR0Hp/rtApsMWYWKdrd8FIhHqJpNLFTBWUrSCrs
DUAMozLCq0+94CaZOFvO7ja5CjGU0NBXpkM7ALK9B3/eOFpxfAFiBzaid5cQdb6QxStxP/tXhr6P
stt2heEKdPYlZejF94ZlKyXg0fcYVDkhXyB2zMSnOoZd3a7y30AerBIZ/Jyas8O/RLgGyumPFRDh
5hVzL1HjB2hUCYfP0OgVOl2wiZDEFybxQrq6ZeZNlzz0cQf5Yzsr6rKBhA78BoAO8aF+ftbrkJdp
5/Swc9rpX+oF3XtV8LT9oWkpuHJCnz17zFS+IewqAscvmZlGnCoCs/Rc02uQYhkM3vsyH1CqJKc4
eO+T8A5igHgHQzrxSXTAfRRfUKErUycKbnD1UEs1auibxgGLXvtBisqAshheiqY45ZXLfLx4oxVY
WYQLUpeqVNE4L/blM7Rx9eyZNkzTEPhWEiZ0HKVhqHcyXszQJQg6kts3apH3iheHhesDMtQsn1dq
7O69hKTgZ97hgNI53LacomsQt97TGKXwSBgeicIjMUcXmgRsCJPEpDBEhpYGSjJ+sPDURkW4opM1
G50aQDGrHvKq15Hn3spP1BloLRF911oUfcPeUrktHaj4im2W+SDWqBzO7t1huiCuGsD/qaJoQAme
AC4QxWFF/21dN26kDxL+J0f9i/FajXwi3z9k90tapM+csii+JTHTKRLQIkoVDA1Gb9T7Y4GXZDVd
AA0D0jQhf+IFYPkwkndSJNcDYkhxarYFDe9xJPFq75bDPMZF8aW1+DKv+J1ZfOj50zIWVCUmxSVg
+O/R9GCQJ9LLvXy8lxSuONkyXWeCdZbycWmpcw9zcQdTjb4nFcKc/GZqvCkG9hlAoiJHFUKdvGdq
HPJdamu/Y52YpF5PKsoOxS7oQgHjwi53ApQaV9pEXNUms+rA1MOU3xmAAS9liEaSYfDLvEHTsuQz
dx2oU+WXoj81ECr7fdlwlnqDDe23tB9do08jP1rxQ+bCYFwWZdBR1n7Dr/69VQeYumXOjRPuZEVC
epeeyyZJsUK6J5NOTuqrdGWS2xbyAOQ0ikuCehIblU6Bg+BLXk2IC+FCbG3sTXBlfL5ejG2fRRc1
R1HQcN8C9bEjGaBThsEDKSCodxgqh0KrRwMe4262zWYbJF9QCqDhjAZy7cZkIceTqYzwWoYa5FUv
FqpWcQLjVVjpl19eZmvFaHQwiNyr/QJyGUGrSWxUPeuWmNnkTYEHENDlHZ05ZxsQ/8Gk4aYPa79M
kDiXif8AeVXKDEyIBEgaqsDJqFeaQGDh4ABeV3K6N9d2wtcZ7p2ibRvx8k8rKFdQHTRCQ2MsvjIt
NXl4bZIxuV8U1BHH1matazLK5VbiZ+qVDWZJ4vcJADlSG3B1eaO6B6xoyegfBYH69pgPpihPqIka
9JBfh87eBXxYSaquqejR8Ew1OUX2X7x8cGB5erpsqYGucBBUyyihN5iFuWEIvSfuvX1RAXC+qIyT
g5vcCtSSrcp1bhVEStaQbFZxM4NYy4yvGO/q/ctzVFv6g+6H9yk9dMnWL263IKQvs0I6aOFQZAmE
e5dd/fesNm7wd/Anu/oFi0Aoz7QtX0I3hQemQO6+fKkZ/mmp89MNHG9kB1s0JbqwLwDePBIgzQcD
eZMD8vqRIHG+GMRrC0SMihHDrvjhfZlt++UItfadVxXnTyBK/P0af16vy7fYdRia6CbhZuFgcTSs
Kg2Ozm2UtHnfQSYgZc/OzDDX5dEZgNQ4YNXhz4LtrSZBFcmCJg3HhQlN4gPnnTc4XORtX6Z/6++P
m61fxbexjzdJhwnfVRGKjhV/Ns1yBNGIllE2Y2fUf21fIrLt7vnZMd1qqjqpEr81jy/aZpnB+07r
Av73mEa67dODNt4kzGmAfR/850XzuNO/fAzk5ilesuw0e/nAZZHB2akAzaipLImEUweRhSgzitw7
SQQ07NtVzQEECvjxPEy7r2oki8eaa9GqhTaZuREVjPN7fVcm7cUU06V8wErrL4qJV6xZi3rFVj/h
taaGxWssc2rc60hv5+LIq4KuwHFbrqgn+iIrLiuV1YuaSrJ//1vWr0KgkNh0PBkXRd2P91P+Wpck
6W7NradFGoNduEZXs2TMKZjP0GHCjIw6JRw979jNSHZjBaEhfc/RPo4QyQLvtFE3EBX8mHPtkQqS
FqPHJl6EQsPpyIlZeAaERsodD4mmUIyZBQFAUWtQBQN14RFbAuXc2JlP3cCN5H0acmG5gBl8TQXx
184ritTFmwIIIoLadEl9gmGK53jUB12/xshz3hwteYADwsIYQF4S+UOMt8VCADmL2Iuh6HAB/8Mh
86J5OKUBrIJ8nQwnYlC++DGGRmM+RHV1Omi/XW/jEhS+jQexqGYPcFbucTofqbIzxBHo1X7A5tt0
HmetyXBSpoeYeEt+gsaJtpCL/fjiNdZJQ9l3SjQjpXTpnVcFxXdelaQE/YRAg/j8hGopmSHB4xNa
4Z3p1Ltxp83oZkFBOe6HHp3ilQ2xZJOPvKPfqtWi5bGBjhkFbTE09piLM94SMlF92Kxk1HNjwnS7
gEVjpwMlYVrY11gbG/RA3aqqMvgZ//0YJNKdgtHoYWfVUJR6LBog7ya/nIahH4OQ7HV0ECVgl0xb
RqZncluZ2L5mFMNH7VxkJNK2J1z8n/K2gjvB/OVArLWTpcBf5oGfrAF+aQUvNyIe/Y1vRmbcOUYa
0oRmxIdj8fYYnjIKnTMTP/YFYL4lsbrKX5TqidK4P7GobQflErv4tl2im6LWUnR2q7EKdZ5rrcNC
/yBo9isPNvtqxrYrm4/1T5et/+oX1CYuRBzhnSMXY50x/Ubqzc6rAkA92IM+kQNnKjAes+yuqHn5
zTX/y1pzZ1W1lrWaWK6ravdXNcrih2Urv/dv2gFuNGURouuwedxrY71VDtSGFj1kWvQQeJbAGZ5M
ZVpvnbM/oClxk7Kx3fjp6/Bhk4SjYaWoFpHq6uuqn4dXBmGvAsuNm+deRGOTP6QS0IPiAixSJWcC
RsjIss3BhhXHxgX2XRREQDorC8wqlsKIRgTT9MUblXn4Crk/0Ayroq1whvFOY9ob/jn1r0trzWiq
vVGaTTSvKeSRx93EDbcUJUTtO6nIirRbMi1fMMMC/UyaXZBZcWmF8wSLSP/+st8efOg2L4FBWD5f
9I4wrhgWMI9u0U0MJptNu2phB8Ds6iU/X4lNtUuGJHGyfHeVKoU7jVkEGcZV1ua0zmoRg8oLMJEx
31omh6qSB6ErDjV0iJnTjQyuhdY1eYBaJBlphwbsiAikA1CD1JhmTeYGykiINhjb+TAeMoPAlRW+
1B8/kFg8E1S0LCm+mv3IiVM3qOj/odqYKBbfYRqSiaZZiztHolIkKIFekWDy1OF/TLK+ypgCw/EY
4y+Lo8ei+UaIuVNuYpo35yTbVbkM9gzmLgOHyId0XTalfBFlB8xmsWUY3tsxFD3m3rwC8uf7K2tR
GtfPbHiePbtCCwiOZUW4A3OPUHszQo+pFIDhcN6+RetoHrSHAjoWfBnYP/NxhVVEzWXsrEzBnuo7
jrE/DKchC2i5ekMwbLb6ZuDHzCMVVTf9PfNFgfdvgcS4tYyOsQ7D6JwHIc8xmeFszUSoSd0tKmVI
erOvFYPm0/Yi/bv0IYJCG7nnhaAKKIjKB8JJWdk2NKeL3MKmpVaoWDnudkzHSPXvqXHuJ07l1oEg
h8AE8f2nLY8ylf0rG6atxjo2Ro8wxomMBMUJCchydh3hlGH+BPSbJ8sZjwWPAewR2NT3RFx9fsP6
GufcjZZ1xzlvNWkbhELp9Ac818EeM87Bwgbmj/Dwce5FmEjBH2K8QYEPWuIm0KuA2/T4rWDuo+sk
IaaaUDeJyR7Xp4j8sBFiLzA2BDXG+xkBgXoUiT8AiTvygCPNwi9ojnJ5vxV0DAnEeAXwn+7N9Xve
x/Oha+EX0c31Osc/0lnj5jrXU4N9WvOwm2EdeOREnOuIcTcBNptbaC3R6nEHlGKP450RR3MV+2ml
3IjwyA53osND+ylkeseylLsWl3zyCvji7DVzeqrGEs8adS91zXESJvY6df4oR1evBhw80gw7qWr6
XsbpJBWBUm//NTljGF67ZYUsMtft+uvdkXH4TUjp0CzH4BJxPAt//doEwE/oZQ/kD0M/UluQYSIT
NjDmRoJWLWG2oitxyrhIrbxxdox7dHSNUTfgAgnzxc/lTP46UF7k7HsLFmmS9gNln9Ckt03osOdL
/vyINSCcBpdEQs8wOuhIdO0bFooAhwXvNXD3OjjpiX0P0zzCYiOkXvjnLZuip+zgPut9uprgEa5J
9G/303Rok2RZEUbtGGM4pwBNBvcUzn79RB6LvNs5ZS7JQ9FW5sG6lLBDOiG8cZ5vVzKXYwVZ0JVW
gcyWjrRZbCmLXVqKEdPhe0MvdZVh1yIZmXZnALjzcltbE6i505WziK5JfN6pbl9VP9fYn+3qDv1b
wz87VfYvPdTYU40eyeefQ1IBH1h+Jnn1QvAJ2md6i5lcHrCfftIf8heK3m2+ZtBfzHxbsI2YJWmh
ZOoXeMDkE/UTRaF//mny5zcpzpizcMTJkUAGPdthSnioqJHujqijdS/W8ujz9hVqv1pfrBWWgpeM
Pu+srMAJWfRuKQcgpUI+ZAdEwM3prvSNvgnIiz91mcQYwVp6b3nGo/lbADLqwsMDBvmp+qHOP+q7
L6ENkxFtOcwdyITKaRivft03JPMAwaIhuUSVt9ngf82RUdQOjIU9GN+J9tEzUsDGzdVejnhTeZlf
Ls2YGPLiFg/sebtEnBKjN/tOdhfUxtIb8rg5yKxkNbPEkpW4zCsxJAJlS3tItMd/3y/1Vc5R5d63
c+O+n7ZpzevoEw94WXfIeX1JX5WHObb+DD9wsqANDf9RJQCnVAm222kwllYYS220sZ2tfTUCewQ3
9ebefGOwfMx2hRYaxBfrGpfe/XgoVojmM0uETD6zWAuFgiXdntCenlESDXiJbdO/FUPimrqz65GL
15nLDINn1FiFn/Jli+6qorVsUaQ5ARJoa8ervconLq6bNSkP2D4TRfgadeHbbnlXol51VJ90EAGS
BAtNEMP2pkM0y0k/m2EY5xf7QtROty4STmHiC1E5XbBIOHVhXwOkgy84YwEO75elDM/wleMW3O+x
1mvwryF8x97cZTH7jan9IkF+YSBxgFO8kaQvHZYb+HEIOuQcWxIejHImak6KXvhUVgB0SpZWTuOi
8rPcygYG3hcWKF5r32Q+Ww6Lt2CwBhlfXqu2XX9B0y4hIrd7TrQhimhD95SdRjtK5CPeDu/VmKSG
ipFbOgJGWSO8AAguEC5awnmEwtHSnWOJNIiuZjz9d04pvC05DXWUbpbORvOHGswgiLGISgsoF2Gs
h5J5M4Q13uBIVFknGuxPVcOoof1WahLbu0UelIaht2w5ZR6Yg0v1Qriv6ueEk3AE2IFmPRCb+mA+
dDHdh1hKYqwb2rhXszEOz4kmGo5BG1fcYtTzbtC3RZhFoFIy8aY+bN3M/YS8t6CDAYXZxqxu8zDw
EI5DqQZHIci4bkAGIw8vwrlGMbTtzJg72PUCA3miScZ1bqbhNTZBjdZYJkNpkbmbeAF5b+GpIwoR
Iw8KxpisEXo5Zb42FNGtNheXkv14SuiiVxZPEWqadVoCo3/bdf5t1/kXsOvAQnyMVeeXF/9nWXXW
N+qoUtDlf9t9/m33MdA31ubbfWNZUBDUHCz/z7EbKfr+4sPKpStVdhqHIn8sPLlOKIqAsuuondcI
q4E2JBb7tcYOsuFf/MX/4Auz7FKUrakirNqOLCtpPPYIXyBz+kU3FfFX6rITWh5wLvATmWJ4V9kL
C+3SZum5BBsFGn9qeAbQMHzG1+woG8HIjwbs9NyzgCo6k9Clf6UUx0onju/11/p7Q1fWIpkR5m8I
bavNiuaD9QGLQh82rPYgHwPnBsnI/1K+y26thBbCqjlL8xKZ1qU5MRdlISFlZGldUkO2XPdwGFCz
BiUpZjr2co8GgV5ixBH6wj/hM31fqu9LuwvHLaOUWxia1/DH7h1BopBEGkj38+1VlWlrhDYQKLyx
8oKAbsMjecGvtyhN4c+lfLnEl5McTinbpj1G8Mvg3toS0nIAjAW9wwW5wXOet4dWJI9j2qg68Nd2
wzDD1A4589rZtXTWsCLBVG+xia9yqxFMOX+TrsFsRinFE+mCl2cmIFQSh/eZa9DMyJSuvFwalZdU
eZmtTCsNSSzb1DJd2G60Wd9wY4zwNxhxHmnIsRpz0qYkm20n05phb2A1mLnnlxFotAgJtFnN/iBs
BrgnYekMN8zYQibLeZiUpS2mqowvhfYQw7slpNgcbBafYqjzFy/RcqwZBvjrbXydY1zYHWmDrW18
aBAm0A1B1BgY1BORQb0hM1phrFB6RdFCU85fMJMNZt7iQ9UQP6SZRbzZNWu6doVbG8iG9rvKBqPB
/lylcwgoPTobYEpJIhhdgsXI0CrAHLBcN2y0ZdwnoY/Q5QDgC/w8Q6uZzthF4VcqGndRsNkMsvOl
tA2Nw25GntvFvtIOLRuU8Z4ZSSqzjCyjgAsXpgx1XbN4iWYxKxYHPnPsXQ8Jjc51fO51ZGosLI35
NsU3tLpLa1397TrWRAmQTGPTH9kbpvKtiz/TDIsw3tpymhw7vEn4T8/lCquwHDFjjDN2I3VJT9iJ
HCBFJxwnPKQAwJp5bsCcjtilP0xrJqKvOi5AcJmFikC5IBqDQnMdslSrJ01nvgiGk7oAdhA6QZg4
Nws0lsQgrQXJFJarcAzy6UYeNsPy9DrcMRCtVnXjKhld5ppPPR6bxo9v9SlRpI/hTOo7MCt//7uK
65ShTSr18uWIvAZTM03ffn4x+hYzvSDUpZVSxVc7dS8NEv2rLfyWJZVj8bcsoB94AuDCzAmrrGps
JT+y22tXGOONtrYQxd2X32SM1+zsplGeoa/tnlof8q30rJYmT+TQwb/N9imz/VpEkWPEl/6RA7kh
M3u+ajtr109NVDVr1Ff0W83yH8mmGpZ31Xx+1ci+spRm/KuRfqH1P8NDG5Z3V8pbm5nqZUoXwf7+
baz/t7H+rzbWr7DOvxx9l9PlL9v/jeb514Y2mfG9VNZ1frXg8eZ1n+XDtNeHDx+Fe9AanmdENkHL
j4ZM9PkXsN+LSY0Ms0PGol/JGOehxtti8z4bh5SR/a+0+rM5/Mu9ObURYBGpuCDNRORV6OvCdi5z
QAthygUw22lBo7hP4OoRrhfz8K5cLnSHQyHx+S6R8G7GgY2fv+cOploTMFTene171j/NXi7rn5Yq
97CaEWoCsVp6GKdR2pUMBqHdPRLFU8crvOAbZ5dMs4o3ADfiL3j/hTdcboqbwtMYBnX1cUy2nOFl
J9HJ9bKzlniU65KyTpI8dcruK3+rDxIdGeqgSAN6/nL1QFr0FDU9Nochfj9IWApFJaP1p7B4fl5H
t5GtPzXgPlrt+AKy8Jf/76gd+aqHeK6a2oRkTQPhNlMy5eiVQrSvi82rhehHhu0UwjDL0JwXPpMn
ezSrMNeevCrZnI6mSWIxZBw6I9IxTJA/9ju93kV70D/qtntHZ8cHLB2UgQO00hM+0poMTyD4TdOr
/ADnejEW2VV3ymKAmfjKilKYVt5vEedVCU+svOyVRdSUhYz7PWQpkcMhvY71EbLA4im10ST1ksm+
u7CJf4Ps+gNk1sziKgXhgC2Lklxh3LFZW2C6mKqWE7690jsi/VKAxMYqWx7/eDrotVv9s27vyqzC
ZmmdKjw4A6rmsXakny1ll751ObhIMO8tZmnZHF4ZInWRIG5UZ88rRPt/IYH80fL4KnFcHO7nSN7G
dKXEPmFYVr5QfO1j7o63allmkGIVvllipwleLbTTvK8nt4vRRWlAiO/ZoQy4RP+aJHqN77H+phlb
jkSfM0jEmFDaWSHS6w7Wr/CkVcitKXxqaXwq3yKyi6FeQ2oXw10kuBcpEQ+2AX/jnF30290BXj4f
dE5P212UtXEm9A/0U6Nw29wKEUyTuEYYCP8+u2ZYUUo5wH7aMxXqayi2JVJglUG1MCvjuEiWmW09
RipT3wm0fMTdxeweOjzf+oE6D8ITJtgF/fnEi3D/5DZY4f9cdxw+6Ri6MQUpXGBgm4k7HTMX5xjk
MOjEssr9hGD3AjkRJVNKbg5l5iCJhDdLZHlpYCynQAxVwxhYnEsyGs9lTV7N1z7WxeRg0yXIznd1
65okC6zG5Z/T6TsZoNA+HdA/uSzG2A8/xxQ8XgeZRrrFaChcxEpYF77Z8CeOyf17MZzwAZp7Q3+M
YS/JCzwN7doP8LQz7drND/zx0G7qJjjkszBO8Eq/58I8wphNwrs0rCnmYtNDF8hjRB+EczwivIMZ
xwPBWItcgFOOYQ/ieirVibbn82FJu3CoTEFMigOuwtKX5IwekwsAmLawHywB1HMVdLEjP0pN1/Rg
Ne8SOOmQOxYd8ocIYOYzF8Vgqs4jf+ay4LAwP9yPYhGz2dMjVuDz9WJ6W8sGiBCwsAinn8gHspNT
W6OpFTVgWV8EU/8WU51icjcMIo9uFtAjjEshoMUeRlPE+LEYoYJy68JaDBxkEEA7w0X0xRtp5ONG
Q4efYifhnILFYmqEWMBDYuMMhuVpvqdgHUDKE+j8P/HMe1q3W1LesqQZShIDtrezy1/x9f5WHIJm
XKbYIZYyoNBGZbGNZOss9TqXK+oAKi1uVeEtmGaTVNGlLHpZVPQLB9kiOwvvSqYQB9ZaqkI2z7Af
YXux2l9I2Nl+mRVJZJIqpMoWhilmEdV0o4tcp3qsHX2krZduV1hyXm3n3dZd16Lz6qW9/pD34twD
eYkyVqR695Z7O7zLRlgTB9eoX6Lwbtas0DE5ZmcBZraj+9B9o2EJz8pNbLMyRAr5Nw7DDdaWaRrC
+S2SOB9ttzLzk36HDct0afwue5blHvoPsG19k52LnQIMkHNLK9eADW7paoWJ26bn92ZhiPkN11fz
16ihXOW5nzzQjywCj1aFmOHTHA7TDuYGs8j/fqer8RtWR+zaC/LE3ndeWF2xCWOSw2PgerfwP02I
/pt6sF72vxXM07ufl2vcOal8S46ru6TJqIeKxcaJHXu2n5Y2Y4yLcHdrkbx4+ZQYZit/JzU07cND
yruDzSrKcfsaQlvpe/06CbDCChujsE1gYzYyvT1i6xXlarORmW9WR2/WXocyLlEhFns+VUdlmfYT
50HPT2q25iFzUpUJ1ueMWqZ/FdeKUfl+CTz6asOywcE6dcljVXWKuSnv7I6yHoxyP1ClawaCuCPI
b5ZuSF0naxhWnrU5npNonCTVCM2dz7eZpvSKqNnsTcVkDvFfyha4dKLoKDt8GULeUsXtvIQDLZqT
DMFrA6+cf7TlQIKRnC42EUgcWjeembP0VMNGXzoiZWf2vB+Hb2fV8JEhQCL2ViCJEEEJ44DjdOjk
WKyeeM11g4B63MFdb0IvM//5JUWuty+mOLOM8NDMXEZiWa4PI7MUQbMA0uYCksKaHwFYPLNVGS1s
AcOkSl16yg8WcDXKwhtZ/+Xmnbu03rtcaxlgQXlx1r2Oy5gG9FqmbKTW9JMHWLe6rWeUEs+BEijB
g0EaEk1zYzLQlxWy5+XGpYn8oTWHVGvUGL/VjqAviRlp84kc6gW9VJhU1CIK52Q28YRnK6ZYTQem
UkMmsgpkNnyOlIAmiD47rSRp7HJJYzcjaRBKJGVIxNYSNrQFHdK0GZiksoKlkFT1Ulcu1IwwRRlJ
w6yszQ2VkXeymcFGmyF15qUEXpFWciNTWsynelnZsB14iylj9uAtjbif2kyqufqGq1ywv0Wt+Bc8
EueGm8GcCYeDsTudXrvD25IIc9H1MEw2mWb8YOSDsohJdLghid8F8AOKZe06N5GPtqKpyk80C0f+
eMlT8CA8Mt+g9QfL8lAYAV1xmIrqaC4C0DO6x8DyFI090JZBxZ6E0Z7jLpJwBgt4SHmPWPFZyLy4
0VaF9yBAwHYpiwWZgIeuzFbkLkZ45bDO0yJj51pAth9YZqGlnh6Zo9VSWZLFm6WW2e7uwHehKuAg
MtiZZRGbfaPcU6fVPTvXDs/pGnC3/QHj5nbbh53T9kn7tD9onzbfH7cPskZFvLulIYc3uDTMsP89
mI+GjkLqKpdGQsxIo9kpqU6pyke2N/HHyfk9t01aiKwHA43ftR5q9IVTOgD9s3SlpS++YbuASkpd
1nqj9cQcND5AvXaz2zpio6fwaZ42jy97nd7g4Ozjaa95cn7cXtOfgqFT5xmvDMcKIV3mersq1wgQ
SHZVVKknZVWHGxcrBphi/wGmCpJkbfpja6jmOmanyhjOJboZiwyOauBht+VVeTJn5KOWUc3CWepw
lmk4l2vAAaEJOfAiziwUGowCANlb6yrdvTqdjy1H84AuFrir4i/8OsF0Yff4Du/2w89L+vn9h/Z8
lH7csT2fvrUiYaihfap+f3NMDOV1A1JLsiJC5NAS5SIuOlOPi07S+Xyp6O/wjJkJ98SsKfEbnvFL
uvplqvoluvrviZnWq18alwAeMjdQ2SVy5qFJrb9h6LHHS/Z4+Rfz7W2Tb7NFN+DSxaO5N4OmPOVo
Y8Rjv4E6uEqpW3SGOCI2YnIOlJ1jdoxBY4XMbBUHENCWaWiXHNrlI6DRgtEQrKlR30gvVK1lVcxw
PI1xDNdxiFHcrMerWDewk+anQauNwfIHvaPOYX9w2G22MGa+cY2SWm0xuWWf4/BWwlaJFrRiqLHx
cunzLGO2FM9/hr3Y4rWemtCNakut2pL1u6CaGDHjg8V7+iPuHLSAamwBPXN21pheVvmIV75klS8f
VRnJXl/0HB9xdnRk+uPyhalX0JeoDlPOMveVPGl2P3R4ptsMHLx9k0shUkRUnCFFZvGtdro9XHXT
zr4TrFSljWa2mF8SDHPGDslNWmzqs2eAqV39pXRdTOtZOkEDP9VweuNIETmjVkl0UnwQtSxQzTbS
XFitB8aGFaHrfFj8qm7YGLCuwaY1thT3pU6l70oK1queNAZMOy3oZTj8A8WNpVpW+1H/bZA3CvqY
eFMmEfPj9wD1PDzaAwneY6kjflyjpH0lM/cDanwge/le5EbDyRIl1v5JE1+DyMqiN6GjCgVuogt2
8KUVcodK/I4vyFjdDe94OeM1L4zV0TWAN/mEBwbAODX8HW8ax+LYj0Fi1qzgmo9dbmkW+JqFhziB
KaWUrM6D4MKiH3n1WZoS1TutHP7VRf05rJhkGjibF7G4y48VG85PX1O1hPD/4EQ4OvfZEhzuA+A3
jTeldIzw+IDmgFRh073IzKEm6l7pRaBXqSLYzys1NBRL+aOfTDrByMNQGvi2CvKoFlqZVCvKbawP
McyhHLe36E331Xp/0xcRo0Th7DVPPDX0nb9JcDY3133mTxIBOxIdrfAx+Bxdpe+bobgo6wx5HWqc
D8rnoc2PSdCCTtjl7Xr9jWxSi0jCG/eTK+bT9GAa5GFbTEPkJCYhEkImRERtNUSaEANRbdEA935i
NKkHbFFzBPvCNLyJ66BK3H10I3T7Ow0T9HZzWZ7c5iIJa8zHCWm9ZIqxm7SeMW2uh7AZof/01UDq
gVga+w49WcwCowji9rDHE0N7zIAUAwdcAr2im9J9Erk83kV9UySSfHC8aezRCDSPj88+Dpqnp2d9
lvvoEN68b7Z+HXzs9I/OLjCXYecgzQgMntcMAp71++waDROxlt1PmxjKQIv5Mc/GZR8kYZidg3a/
3eq3Dwat42avNzhtnrT1sDR46AEV3apznQ7JT9wVQ7pSqke/RVtWMxiJjbrsWlITXheUv86uqGVr
htavoYthKd7svwUA9aEZBY1vy1TyCfPJo99oTXUx7oWodp9WyTRuedA+bF4cs4EGweC4ZzLK01By
SZY/uu4cutMpck80dmJok5++MjbEjocenNJPXy1D+1ACUhJTFRNZYblU4w/oMDWfupLa6puCXhgN
6DTfjqIwOvHiGFNYFRB7CTqxiClijOzLHZA968/vwe9BSYtYUeouAmd7Z0DWrgHw8AHZW2+iMPyy
ZFnjQUqZeNj5iUfQeLr3aBEwv714GPnzpG7YplH6YDowjlVmPX9Lv4zJCaP1Bl51ejPdaQqFA+ht
EcARUCaTamJMPUIdz3YJE4DdzzHt8Rc6BBx6tLmQl/N0Gt4BFwiDKbkfs5Rd9yAQM4ylPdsPHMpu
8pxEpWZAmbO80VbkASUM4ceM7ViEHCXMvOEJwIB5TYiU/OCLO/VHbgIfGFx0ayQmKx4xhZ/M5CeG
vbeEzs0oSTCwLi+CKSnBlNc1x9A6k0xLVaf0/vis9SsMbvP8vHv2W/MY5On/vOh02wd8WNaeROf/
/t//l8QTRvOPhQ8itD61lGvsRjBodB6+9oDEJgsQL2ti5DLTuEm0+5zL8AMYrwjmcIDeTLxOlpgJ
CM4hC7aNhE1Ruxu/Bz99NQYvkxNXZI6X5ICMTlTZYGkYZS/3nX/EMJroVvs/4Uc5DboPu0W5dNE/
rL0u0VWuOU8bDjwrk7pRVKZR/r7J7JzCTHbUpH7TXHL6k3jpU9kKF9MRTSGa7Ylu5aDkDDc1yBI+
s0azQ821P5zOI5du1sUTd/flK2D9QRigzz6pAlwGBZWRV/BFVvTDMGqqucnLMc1SSjvvQLGimqUN
YWYRuJ7g3NC+LBeeUEn3nRIbVVghQhOQhRTm+6oXIOvJAhJTLGFFm4Msm1VIMEcHJgwNTzu96pxI
O2mvyST0nKoy32SmrhyJcES4lmiVlkCMU+4OJQzRWarDSnencYcSj7bc2Ctzmr1J0yxfrtDbvhcn
SK+UBLtUqaSvLZUVIpJlt1DBvqERVfpaxjuysB70o3XR7eJpGttUUDwjw8GHwVGzd1TRu+eYA5ID
Lw8PVZGfpRoV0s2kkU/VEUgD2R12jtsMV/zP3A041X4f62Biy1Hz9MM3bgHs2JY2tBT3J1f/yOMK
pTMKPbYLzIgTole/3D4VD8nuBy0GpOFwqR1H6eH3oMnrouKbXZDv6DAz8EoPWTGBxa4jSYHQpt03
etSu4964flB3TkMu0N/hpRjY65nwALuaTcggXQXb0mQMmK0E/sYO2pJA7wHQUCmGxUfnywto5MCb
gmACG1F859OEiyQPAU4p3WsaUfi+BtTFNA94fB57qF04c5BvKfSTt4i5vOEOh948ofPwmyk7lOfH
5lwERJkYqBEka5J6hOCCBBrXBQsGzDqEMfmxc7b9WQx/VSfhVrvXwwt0SMWMPs66HfjGtKXm8Qd4
7B+dDH5rd3uUX7b96RxTwCsLblU7MmcQukLZ6l2cU2H6dlX/B5BQufQnsJh6vLiOCVfMQr6zy+xJ
4SI58CNuiyAxiul974GPwQcGfPOnr8RKQaCbPdC0DxTlZeA+DNiCgDLaoOBeh/BpQuIEZBqKPOYV
tO2Ujpv9dq8/OOycolx2cVpP7pMSX/SyEusClFZrJi6huQre0sUbZrLKNJyRG0m2kcuCIevduMMl
1EkNUgZWWtapw4jMyprXsgbuxA38MVQ/VDe/GVCtMegODN5gxovWUcwqmX5penFLbzNNZfqra7Jm
8YxcZ4FWJN1ZLyxjuDpgurCMjwXm0I4JOU+AyOwWqWru9AaILpnMfvMicnOB6oWLyrplpYAqrmTZ
h/RFnLedrQaYt3uK/1pnJ+fQg/ew5x23PzRbl8YW2O7VRYDTci4Iy2hpu+u7ehIyDsYFwlKlkr3Z
Ypk62/0VyUx00syUkmaIk86HLk1Pw+kCO2bKoGjIAX499ZF1A5n89JWBVsLrAymf8Ha+SGrElhfz
m8gdefXN4hsd2YzxM/+G+T8aeoeB6cdm9xQmu+EosR/GHLUrA800ShIybslmMyk9QFjRiCfP2Z6b
YjmSzUWo9nujAS8GvI5xcjofdqd51RZBTsUx0GDXi1c1h8VgG021dlhcWTVqVg9cdPI5m3l5FeNw
EQ29ASs3CGcer8jBnWD0RxRWAm9aAEU0PtOKa8DUhpEHwNhS2OS4Ed7qbsVf0uxbVtLkywGKLtMk
rvNq9WH8BQDRDlZ+oia6PrvFdKGMdevvDb5uiOflJ9qMm/X1DwUAes3f2oPDi2M0efTOji+IU56f
fiAYiipM2Nr7bwWdpR07+ofrN3UKfAT+nJ20B/3O4SGB0WnMbMD4sgo0SVagF5xcHPc7qBecto/1
hgx28ySXPk0M8osVTbhBryZA85MJ5LvMkYrjDSMPcOYMDnjddITXmBcB/CE7h2LRKwxKkq2eEaiG
U1RXlW7qrrJkJWzoCpBW8tyLamRWZPJkg3MNhzGVWryY42vnzg9G4Z20/qJnKvOw3wP94S5gkRSg
iYz4/bDHQTlABzWig5++2ugQCvKZdnQWlKlWSGPQM2aDgym7a6F8Rb4ktzRE9FxudrtnHzXzMOgH
Z8dn3cHZr5xb83Nre+0zkIlA6W3bAPBPHMx01Iwidv626rym+JgmjTDsfSTUyxayxnTVYOShKVm0
JKtUKWNK6ky4S2XxWEKBFmca6Hs6onXCHKPx46YIfiaGjDfzqB6zw8CEguiedWCFWuw1ZcuoWCYi
LVqWyyG1p1VkUhvIccchaN1kgKooudAC0yyK8p62Jk9Sng7sbIHWqDkiYhg5deLyOftvGDNShP+K
gcsDvM7oES+xDKFlkFLDiOMhSujO98N7LaxeVQ/Tsoilp73K8pEO1oivMQI3Jo05oFf187Neh5hV
5xT0607/ksqnJ5mndIaxM5I6kySGzrHwQU5Z2vM3JF/I0McCLX7v+pOl2DJb7DJbjLwYQ8qrY88f
HS4pJFjmG/Y546Jo3vDadd5oQwT7Kb7ZF8P7VP3Yqb/ezlzd52OOKcbDe0ouHsIUwcg08J+rTFkx
EaNdq6cDPwHGkoIZIVW0NNohyuCGJ9zokKirjyEVrW4/vPWYxxyDY9K3JKwAPqFvko2EzUVMq+2R
i00iUtGdI+lEPhjxI8B9hoNxG9fGdRqs3A9bEgqF/7+uhjIbeH5V9C1Lk5a3RHYx0GMlx3c/TvmN
8o6+RwYZ83OcqmO8FUc0lphdCOyNaplHcuNtaAjRjbfUJaPixRuTwyP+++MW8o92Uzxn1iTuL/YX
OCSCZBRGI82LEFRdKf9xB8TwVqVSJdcy0lzNd2PXn5pvpDxqvOWG9TTAeAHEZ76Uxwp4ot5dBOZX
JvWfBdNl5rPeL1Dh0YBJvJQ6pW2j8TXX7BmjfI9uI17El2F8jSd5XjAqf1aHKOwgFbR/Hx328AfO
S4mMEHfsUT9AFhfLBsw1dnA/mFOl1OslvdbdedH1dZSulXq95K+ROw5G3B8pA4kcg9X5F3+h3Hj1
lwwu+e2m4MhwCQM6PhmMvJuStLwMkhD+f85eqjrzME4GUahMJHSLMl3KxIS5BQ/G3PcaXyncdYsL
8xnWAWUsX4N5oCGZea106QEdE+UA4xatNLDU64wha5D4Y2MicmxU6XKMqgdKM5WNYq/xoGqA841v
xSkiO6pBTZm5fIvjoCoeB3ESLv0ur9uibsR3uEjf32z0Thtj/KUcsUN2ZNp1ovwqf00XrPEtgMU/
QP7VdLrPmZvsQFFxeYCI7BNUyvl0WcmDMLRUHYo62juxFlKAGMaM1ntEVLLWc/mhpS5d6yD5R93B
PRdNz4ilfeDdmJCIEPphP5zTpxwguHi6fNq7fOnklX9OE5HBG1+bV6ArYtpi3n3L+LCFJUsKK518
wVcRm3TGtW1gUtY9WZ2vg/Rraauzz5nVjpYzFhk7ToWZDFhTwpCk5Izi5WLs8bBE1DmKkNopfDpI
cL4Xc/k6rt8Jl26SfoUXMXfuJlfv2lvMTRnRjQr5tsH+XHE7yXmz2+80jwets257gEekFelLazbI
nzRjTJlyMkZeyg+oRMo0nbYS0MPOcb/dRS8tDQpnDiiVLvXdkkdMoPd1GZFC3J9kr1nnWB7ItArC
/IGcLFqsTJ1NYPkYL3p69YsesLjW37afA1+GBvCek8TkxgtnBS65KupD+k47VQpndTP9grrjJT4b
V+H0azy8gPbKTAPInQb2HXL5UefN2peMK7/lsMonLyNUQR9SN8i7dNKAxj0O8N2KG5bqFo+84ZN3
Lb9adNWSt6ftVbbLlsUXLAWMKy2aTDYawep78YoShqa6rAaFjEqqlbRqXVb69Opb+HqGXTon1Rvl
BixdE0rNFM2PXqfOJ8l4l847K2frUbfmMsewSgdLXzGnMKw6Biychcrtt3b0BHZtaiDE/dRdhRSZ
6ElIzUExMziqacme4JsDZ6+3rBTd/C3ouE6vrGMkYMveWcL8DNV1YDbrRuZKo+QyU3JpLzlSLMcs
L+5TWmtFIqLAyCReFfMnomtL+t0hWC3l7D0hdrO0oaUXGNJtpnTNsn53KFWLM/9wkXDen8/pkdEP
/hbXmTxN/L6qjFXcnRz2tvLW5//VrP2XW/vndu2X+qB29WwLag9KFcUToLn0oa06aq0KbLTyQlRJ
n/Sq81VLLX5Cmq6kDk6LWrLXzZ6IWmBISSldWz/uVKTP64sRPMRTsPJW+Z1f+R3H+iccvXqI9kDQ
Q7RBtItc6TZzDzi/HwGlnqUbNQ5BVUOraYu8qwRxpZjbIwitsqHtyYLHZ/fJFQGvN1aHOs3bPY0F
TzPQdUeWDB45OgTGyHsty/I1yldI18M1XioZy5eTpe2bKc4X1S4oIim3oIUUiRkl6dgUmljGftwV
cXaMrOjy6811+lOYLz0YxzRllja+ylmscY9bwKCUEKApIDAFV6bjZp65mlTyWRSqc5Ny56CsqW+h
bWu0fNZ3QM0X1JC/tf1Ox9KUnwowNTZMK7a5m7ldTcvdw3MgpqSTvH7CQtb7Jzx0JQorfHCVJ69f
1c5cJEb3Onq6iCFeKtuDIKdqljyq9vlQ0CyuwOc8AAAKfOedT+1j4Gx2d2DDCPSn4KlCReE6u6Gi
SN9NyVlPPW8Uf4xYbMBswRH3zW6FaKKiQqhimwxFeEGSEwpbrUqENri71WfV9NOV28I847WagrW2
xyoD1yUzMS6FeZ2ZjK1urdduDH0OaAeM2/d+rOtgf/4JPRKQCm5tiDLCiEL3o8U7wShtFTPeZ2lI
ml2iIkfzcZBE+1ZQdl9f5gDac7/IHYDGkQF8l7L1FA3Iu7S9aFVzp7RlGK3JXWRV3W52R7Ghndpx
Nizh1xnQ1iqe8+N4TwakzoseyYOyk2HlSWvyogw0O2+qWmimmp3Yao4neN78GcUzrM9OCzwyeEv5
IhMPWN97u8xKW249fa9DtwlYX5D2Dimc1Y2+bFcEY7KkWZg/wgu+kTM3mfG0ohoLwjIwTW3b+6l9
3OJ4X86bRGKpWYCWpZo9ai5TTbGlsT2N4vHo49NKFajkefZnuppTDqDnXajMTmwujMzuY9mybN78
WRWGNkKyNbA3Gv1JpUaVYWFllfXEjp4eQUhW1cIcraif1owUjFROohVwhC6l6rM3WhevsiOkqVhY
UT6aFhjj8kOu5kVMI+fruwYPYVYAWFfT0kJA9pZH9u6Goctl9v41INg0PgnH/PgIfOzgUl/XgJdS
JSUk+X79Hto0zsK1lyM4rNOilMmlFG353LKElqT27VJ7/l6Wc48JKjCvb7FbiB3g7KJ/ftEvvAOV
2gqf2C/zZlPhZPUIgJR5+0TXQSx3frIHKGpMup47ylz5sV/7obvddNGehwRQUOjSD4uWglLZA7l5
Ztuw3ffJ5JkhF2g+p+Y9JIu7izbfWYWIIHERz8bb8XtYcFBh3wdy75itafA3rnqz7aIox5FmxqiR
gUUze6DfW6WSDz5zKLF2GqHi0ukTANlTa43slmHEvcmOScpUpYWE5kamXMTkcc5IneVAV15nog3u
kH9ePqDc0NE52aq06MmUwd7sxDrhkwsBUzjl4TIP8OVjAJvGPrwEAb/KKbjMn8WOGJ11z+Zh4OHS
5suC0qQDoJb4cj50y1pTVTFE4scyH/p86GbgkhukGy2/A6zIepWBbWZ3/1bwLBmLCTwPshFefL0m
JD/W2KzmldtQc7KPJVLzkzFdSl8aYkH9EASsyPPicqpmWn6t4BFWaasE/0rdOlXDJrayWuxeEUzi
flF9WaoJC7+LUmAKzv9Yq/YBz7+XRoLlEF0PA2LHUw3Cwx5oaowQYZQ1Ol17fGWdNUZWlbWOKSDT
/fC+Jsgapz1F4evPulFxnUk3K+Thx9YEYGYujrXx0qutgZVR3I7TZj570J3sP6doopriHlVjuVcV
y7rSvIz8ROqbD9ZmydSrHOutoRBXK6RaypBvOFVb+3wtCAe6h6nEu3RlhVq4x8tktd7Yi3gy4ugG
ndA9jE9EdwW9uQ8fprgA5WTsFUHDNCUydB+lt41CUENhjcjJ47FMeE5l6EERPLHSHcCLUsvGdcc5
plTIIx9kWKBQngNZZMAuBAdEyM3aEUgp1G03cCb+aITRVTDCi0axIMP4s8WsvpoM0qweVKgUG3jX
MPcmeJbkWoTwGQUUiCmHixcs/ABz66IHG/oAQhOYQ2E89odIE/CFJewuAijGUYbMM4YRZx0GuOnc
ee6t7EMRvNkCCAYv5ZL8uZhSAmLCwko8QMoRdKFwyr2hiz2GlTshX8cYaekafeCCGi7kOsqYlBEZ
YxsUgUqTCLpQs9A5LgD3byb6dPN2C+FREvMt0IKmPiwaljVLT2mOAyF9a2siO3g+BSHP02aTJEyT
cJ6sDs3BDL1rbAbW1EcVi7E17Rul4dNwygaLXxvB1fsCy0j7Am/OvDNXS4OphbkNiMgg+hrUrIz6
CK9QMbP5lRUBaWncUltkRncshM1z2hbLeKBmlDW8sxtvpVLYCE/nrLB/6/wscoQZplMKKRfelior
cU5Vkxmzcis+PGbC3tXp3sF86jHJvOvHt8p+XTxbhveZjp8ecn7lcNmH5Ud1O/uW21ry7LgZoqqJ
nAHMXw4pJMqRyvISOvOAAzzZgaagS6VdJdMgh1LK+LK9kyO12A/QLK0Dm2O3RgoMCjpyliO93D4x
wJrer7eka/0ZkCsgKoXfAvFyPYhkl0P7MPbq/F6/cbhTzWYy1FwiswNgH9fo5rrHw0goS4KBcI4d
ASvLO0Q8GxqerzYZKgpuVQ6x/AWzpfUqBzEGWSEnfZpySnIcrMXE2dwfw+YX15+67Cw0a3E18jj5
iSczW27vaY9vjFk4P+v1B5KU8cC402+zeFC9ilYtmwcqZY5XXWUvOjjwluGUq9w2spWiFizTZDT9
DVMlJuGPIaFLVhN3iqFBzxmjK2ttV5Wrt83Z5+ft7UpRC8WWKo5BvoLEC+iRVhkrrOqfZCRV3Sfe
xu8VPkImuAaudLthb1qnO+sRiJGwJRLBPNbj4wKVNTh58dldnoSiMKrkD0jaDo/nynlNvdlPrZv+
2TEsGhAoBwftD3lnMLI1uRQxZ8Hb/cI1uApYHopvnVcvaQ/Ln9bC/VZ9fKbNaGVj9c5Ohx4ayVRW
+7laGaM4XOQr0bI2DQZu83op5M35cB8NVTDoO6jpnQk/sX2HlmXnrH6HR37lVIeqTun89EOpavi3
r4DNXWsyoM0uKcjM075inyMD2xU2X0voPWpZbaIOxhVLncJtFjTMu/Kt7T66VbO3aafpbFg+sutx
5/h8gKIXKf/sRwEzZhiP0jUKsjLbTAVFFtbyiG1eKLi8Xd2GjElzqdWXJmZ1haJS3MAKmjagj1dB
lROjo17JcXLPXCIvnKYMeDn3Vif7x4B+KJ5i6UxZPLupqGd5M4vCIXO3SKePKxLK83ZOCczIdrmt
yUr+7OYj3vuSTebLOJnWSaXRkBUpzlchc1mAzNE3I7N8DDKWIYb212nYPnKfqo6B/GVeu7ClAQmc
LyN35o/I2SSqi8gZLOR+vrWD36MvyyZ5W1V9xLTfBZBUhISY0u0VFJ2GcQzFYhSII/SHAQwKinPX
nfh86gZuVFSS8swUFSBO0/PwWnXZuLiVjYFYwMXk+jRBaG7eqS9TL7ghCf4tv4iXy2cY6ErmCk4m
ngXxl0wX2B3ux/IbuwN2MfMpjJ1YuMnYW6OZ6VodtvINjFnNf4W+mb8INbWxWnDZbsUuZOlAPjUW
3qTKi0+ypedXWh/qowkkKzFSKjVDKpUvDLmhQIF5khECJAi+plZWto2wzW3Nbi2R2dLKVitqxoxK
+OnqcAF++ZpZge5YKTpK+B4bbbE3HnqK2H3w0peEZFGKjEaBSymZTc/HoeYxI545pYaDDKlsFJee
d+RmuQhuA9gjHA8/l9JXf9aYQMtBtMJ3wMA++jx6xRm0d49ZKjDykXnobL3maXUKTrmXWi9vFvny
5tQvKmO/v7kW2ynZXTLb3e5Zt6ErXRReDBbw9SKhk1UMxH7nT6cyCzuWTpFTngPm1pZMLsIiuDNn
zLrjdMYEfAFyDE8p4gVIwSyFSDq9SFVAu/W8uZYcChO7UNADHm4YE40QmVRVGikeWXju33uUcWSo
oLG8JCwKM2KHxUHqIjz/WHgxJTGJEn/sYlTGDf2EZcYSYtt8QouUNKxjUawKL5KNLeXSy/t71aJv
VnweNlZpL1mhaT1xqlJI+d8mNq0n5NgmoWDzLZq7gmr5ff1x4sNaIsNGWnSUxM/iiYtLmGb43ye5
JG67Q7SC5u00ns3i9cRKYLYGv4ni8hosphZb899ILo8hGZOqbZ781nms5N1iMLQCy7ZthZYlHsxp
q66oFvDB1EAXUggKa1YSsTYvtbgcdpRqOYdUoM0cUsGkxfaW7RrQ4zlO+ck6BGQhvHWIx0QfiUef
M+y2GkP+ZJXNzR3IHiQ0dYlmHdLLIb9MmewljZwzevuBbebUPSPs2Y/SrcUKz8c31rJJPfLMPGfa
1pioR/tSFJu3LE4WaztUpDX9H+NQoaD+EG8KDdyPcKV4WDWVufP1PV4S3+8psY59Jb/db3YtWGEh
Wu1ykPXMeJzbwbf323qWiJO83gFMbsNrH8zYTIHW9h/T+loHN0+yfU7bmA4LXSETtGiQBbxz1haa
ermUPjtEZGqgmqB1F/kHom4H+qPOjf6CEyKx/oWF+N+HPf8+7Pn3Yc9fetjDEp5+n9z/jSyLwZYZ
q0pFZPjXnRIJnvMIeXGNg6V/+aOeJ48/61lz2gvTlpUqj7AEfM8x0g86QlLWYxJ3a2enx5emDZnF
+WcpuEU6aBFbX6aDTqcWzR5nKKXSeqix9gGD/QSk1CbwZJlmpxyp9gpPRqyDLE5LLIDWPzNZZaAX
1vGaSL4tEmpz0/2es5mD3qaRn1tOiBZ94c6N8ZSDdpxRsaU/dRiVDrwgLeN6GhJuD7DfU0iHAKiY
eU0K64a3dKS3FtBbHZw8OIwsJ4Z0+lbRk7DotWQeFnipx+4l4sq7h/FOphLU4jutNRjvUjkEi+tL
5EU15v3oNPjjYbNzrEVNp1jpMZA7ol+mTqyMl65ibIjwfHgi8hmvd7LDN8ydkTnIu1IhuQysjTMN
bpygHILHlE5Ly0r08+5oI2OgQcUOMzCRGDYM47xLJQBBQM0AQf0exEUOJMZI4OsDsUdRZHmsumed
uM5Omo79wMN8VsK6gRk+PPhLGhnKRsSKD7yxCw3Tc1qIYWFWcUxQxBE5wxj0dEpATAhV1TJdmpAA
Cs45cTQjGIC8/LdpraATyUZKiuUJjd68wcJFR9WE1JrxYjJJU1NpJm1BYxT8tWLFmNNHURimWg6F
E8+NFxHdQTv240TbJnnWgWl9vkjKJRmmgZ1DOix9T8qNPe++k8ZO8xbebkU2pqH049o1e8MkIboE
Hc5zYApR7bvQ/+aGTHzP1K1yx0h3FBVd2Px2tL+3PRP7PjFNR0vJtCL02veg/s2NrbMpqKxgRpgj
ku4bDlNn2apK3JGbuEy+YpKVEu9FphYzirjK5UJRO1UuiPAOExPf6SWnWFDLk2RmRmrYUh+YGZLs
+T1E9eF9+iVU0IMKCTNMQ/6qpnJX9LjDi81rSZMTS+l6Lc3/xXR50mguG+YuA8ZMGZGCpH8sBJZJ
xNRYk/Xo06dna2rkr35VJccvq5H3QacMNXprhUhMOxOtHRMxlvOrzaz6LNyRjBiJORMvTJgN3dVI
Gw0m8TU0J6VsXW63bFjsoempGMmyGZuntlqEBaJhmCwysFIaZyNft9UplNxsGk4m+ZXQ+cTiyeiA
7zDbHfuNIWFErjfuSbaRoxI20i9YfG+el4rlbgS5hseuzkSYo/illgNOXbzJxpE1Ttj11f9k3yI8
ZwCpIKo2mJh5NzU2hoijKX7ofAePM/c3L4qJxnerEnpD/qrmeBW2ZMnsu2rh2W52+szy7vQGiDWZ
zCRia0Z3bqg4z2mkRfjEhjV4Yl4fMzX0aL1mJRXesWEL7pjNiSFj5TbMR7NkjFGZm0mDpdIkc8QB
LKNDloljcwn/1U5OaqNRqV86OmrMZo04/vTp02ZFJOvAelijnI71J/lrI+/w7du5rXLxbCgPT5Mh
CufOdUPdPpqBfzcjX59jX6V7juyiwf9eGY4XT1IMR4/bpNZm3eMGKxt7squeZHRsJiC7DFNR+6ss
pn8SpiL6k6+vTMaX514sixNne7T2hvS7dqxP1cQqBS47Bnr/5+jCCRJc/IX130xXW+aPXMm2dJrX
z3S2qKOLOcazElWdVu83xx2jX0WqtzpsWy8fMPchO60QqrVOIxPfi9ASuKy7oxG3BJRlycoG0y/G
oCwfiZIXhNnqZHg/PL0yjgFji39FbuUhm10jyRPPu2Bks2XLNq5DeSB1Y5mspA+m1aBs9Z8Lb+Gt
2R5lwv0DK1gaTYHLa9yIBGex8jkPmCuJpXdmAryeNVoAGdIzgdCVD9vtiAeWFtrg7AUgiVafSNTq
3h+wB8RlmUiJHC8xU2beVQo9fFLRrQpYDjQLi+DEDfyxFye5swBlBjNeiCWkqqTr2oUesUNlkoKi
WCle4jOJlpiA4Ljdb3OxUshJP3aXXk9xzpV81pR4HiXprCXhMC99diG+YTxlT2p5jUdKezJPK1Fm
QxJm7P+ThoTL5odROGvJnaVhBPo2Oi9jQjfsUaJZ4YyzYyPH/5GP1W1DnDpUHaGmaYcKVX5wcxbx
XKUNdbbAIMzEe/0wBSNH4iGBSCWCap1+YFLd0KNKiVZT/KFqWeWNvJWv9/5Aego0bJlLxOFSf+IF
6FjUYKmBNzQfIu61c479ynMUZhWkbtn3x+OG1a/3akNb2DkB4rH2Wv64Jo82mY0pQGkflQT14/dO
PDEZgvw08bwfvn0ipyfAlGwyk3dSPmoJUl9X89yPhp4/1aJvmcuRwr3J5rp4TrCfqWxWcbacstgZ
JJIakD5j/72jdrsPU3fcJmlJdeepVuyt0zoDTtLqD1hxDATysXPQPxqcfxKCnQ5UdjFVrXNKLSkx
X+vCeBqGUTm/HeiPPnKqS1z+lEIliw6Kgw/6cQKCHZ7rDhkd1Kga6DLovwvDBKt6tBh6I7Sf//RV
duFhfl/f3GBbOZY9opRJon/P+KAdN98D5R/R3sjgst30/WI89gAwHUzZR7SqTeRT1kTVrFfvX563
Bx3YG7of3lc2PkTufOIP490DJ74R2PCjK/GtXNmIb/CUqYsxAzFLwBF0oGw8xfVf25cItt09Pzum
vaHqpEr81jy+aJtlBu87x53TdrMrmmAHWfRv/eNRp9+mD3iU3sXDs+2qs837qEVr0t6IIE0C3iHM
D+3e9KPUc4MY3ZTGpSq9qZ8fNzuA6c4uimuC0DEL+EeRxhzEswgTZ/Js4HSiDAOFqV/lFKgMs6gU
yoSy2vIQBe638QhLnzL5aYmfokjMG72ngXCuASuWDTd9wP6OCIMNGgzMzmv455ftip5juugI26i/
82obI21Vnd3n+RCEOKpX3H3xEpp+Bf9gPf3DLkB7+RL/xy81ZSf5/XGz9WtFfJQTfQ81l2KqNcru
S28h8hAQ6V9WJ8/zZzealy06tZczEqoCl82PhPULYnwK330Md6T7KarVrbFNgJWl3pxCiqBtXpp3
dya/Zg6WJnwkNkTLDmEyKYAgQ54VgrjHo34g7GeO1t0a4FYRC2HXXnGJx/vLTMXJpKgiUMkocu8Y
EwQ8q9A+0MqyCg1WoW7VEtO18IY20gKP7vRo8wko+cDfJd08IL8f6qIBmhTSDaxjPNEXClv+Fe19
L4nCW48I+L0b+0P+/KK+Pa7IcjhKfC3BCO9W2Ujv6uRWc16kHtdoZcdoxc6z7csZmret56q571XW
4hTYO55aaBNnADWOB4cmg7Grh80qI8qf003j6eUrKyC0WAIIEXI+0kyYlQcymZJ+LIvsIAeyK9BQ
fuTdFOHwHAb7AbepkR8DEHT0kzv+WpYMTmgDtvlh9vDKhuET2uPCg3m3gIrLsE+yucqG8rekiinx
JzdcmEHv6PDfECKPXa0Eiv/h4vgx8qk74lbOUf/kWERjn6PE8xcYtybJbJpv52BpSbEMzIgoWr8D
ieI8EmbUSCZpBQFjfifEi/ldnY94ufTmySgcJsu5R8295YZgo8TMS1wH1KkI1sr+5iIZ115vWgsm
fjL13vZPmo67SMKaRkZ8qN5ssSK2ynGytH+5DkdLWApBUhu7M3+6bNTcOaiftXgZJ96s+n7qB7cn
7rBHjyhvVTd73k3oORedzWoMslgtRmFsbwaitR80dl/M7/fQdfKGdqHGf4xfjX8ev94jv6bGf+zs
7DxYkJjsMBRQ7m7s7gIIDm4btCWA+ODgmYD7lUN5+fIlL1C7DhNQKBs7r6GQBTBolOH02o2+wur0
6XAiBt31drmXgPK8vffPGnOHeGlDeQ47A5oHADRgsQNYPQhwGMchCYOvHIkIibbxM6At6sBvqrHH
GH9jB55hAYHe8R/X19f8bY253zVemUN2NwHa2hsuohj6SqYVL0q33JigAPhVx9rzxs/Hr6yDgArP
V2RRU3fZwIc9/KcGEzrHMBE1GNbFLIgbkTcHpaFM5IV8vwriD+lUr7fn99WdcVSp7N248waNha2h
oRuNvma6wsfghRqD0WiUGgMY5D3sEqh6dw2WdAFK3INS5o7gDYw/1EYI0c21C3Iy/l99+3XFikZ4
+5VD5wSzvX394qX7UGfrJPVx/NJ9ub39UJdpOqp16WxZrYsUtHX9uLtaTx8ip2COhs9/fv7zQz0l
radKudsvXnnWKQOhSE7Y9TQc3u7doSTY2Nne/tuei8kDkhpFSW3sbO3shXRIAVOWNLgbqEHP29A7
2zBN3Wtv+lUn8x2Y5z21EneewyPm4KxNiC83durPXz7U45k7nYq1+OqVtQNvtnI5zpvJTjEXg+/Z
aptvQJh0KA/2/u+byA1+33yLgplu2Hggq2W8B7sXNw0+OOFtlQQ4aW97cEQcUJABhEVQRGjZkimH
f/qqmwUfZLZssh4IdhJjOcM++MDth9JqGNffbAHqbzctI6G6tMnX9+bbN2yFY1CVKbCq/U1YicAB
WrC04vLvfGf6vVTZfEveEHLUWLVV1XkvqD63i65bNbylWme/rlsBqIRqNKdTVYXGolQ8FjjEcg/U
NXpU5MU2W0gfyIhgZnDTbYPaM/fKQqasPPy+6aCVv8aeoXBuubeb5pV+TVc1WkbFMo6GWVCieAoW
ObobnUcI7hT2f9ntovGhhZtXcvMNy7HyNoULrozKA65L+gqaQk6/31xHOrIm6Dm6x/BBJj4APSsU
ufe464MhcJt+aUzO3vsG2XyPm97TXdFP4hgKd6RWwN8HdMCZOkzfmOJ4QJdy+lti1GrQ7MOGvZBV
6BpG/jx5O4adhRicvjziylcQDRfoN1r/Y4F+V94UmHgYwWopb9JOys6u2kD3ZQGiPKx8HdaJtdb5
/rBfjvf39zdhKjb//HNYR9IGSZLPJnyJK+82aQvZbGxiOq/NvYfK3oOByyYj083KHgwHw7mEig1T
ZhYzEHOWuaLyXRjd4q494AXryX0CtbVqjxebrdsDh2jZGjrsJA9UlsKzvIcsC97ElNox0/CNjcRS
9OzXhravWAqIcx5zt7EUPGTBwMJIHUHpW5Glxol182mkNylbzdRu1MhsWNae3KgN2RlP3Rs+Rqlz
Llvdc1iwNXkLSq5pCSTnFMwGil0lc0baodhPXzPHYg8O2jOANoX7JQtL5qgr7BXL3nsuBTiHiaci
phmPjOHcgWoQInUKBga/6C4kXbOzUGFTRE+jSZrQIe5PX8WhsX2YmY/jOApnmgcPG2p1lmodZXGO
6iQTP8bzd+1emXm6mj+yLHycvOimQ7KfvApQZGbAnpHTTNdwoKGgBPIbms7tifN0dD7giN25UUBr
wcPCDt14YeMnWwFWzj5yjUX/ji0J/LJM2sKeGfHx2bd836k7Z3NPrAKyBeCSLTTf1C1wdusOnZLj
gRJd01x6U+CXTFRFP6I4sVTbfF7HeH6udisHZucO9+6qgwYvTKaGGzBeCRKrDf4fLyPiobByH3di
uqkOmwLbubETATBENHSWfvoqbr4NWsfNXm9w2jxpP9BFkREgXMNAhUQXbF+oW2jpRV0EHqRwgXxH
TiMb6Hhi8yNsvNU9Ox/YMHDe4PBQwbclxyVNhuWkA4aCA0mfZXxChqkFO9vE454UW2Z8s9X7jbzl
mNPSqo0jZSI2zGX59TjVke+SwxtMOSytBQBNZA0u96yudCHzEIiY+7QPcIYJG7lWfcvWqKU2/22v
vG5QfTuSlhAkOr7cad7ecg7qNcwsWUOCmec3MC4C/LBRGE/e2jiP+GW9oC42HMSJBlQ6ZKwY0uJg
0kWDkIOIxMDu4ZFFB0RDYVQugYjp9BJv7uzCRh2AjLi/X5Jf8+UrVcSUq5QvjylGVVMKuikyVXPU
84yEVM2o5xaBSOFmCEJ2EWiP/HosEo+qlCvy6A2tJwrsKQ+qNXb+Pe5WZG70Rfu7NnMZZpg5A9Bm
uYgZ5tfTmWEOI1xZec5lf50R5lfqMSWCcNQ0FOsJh+77JMNHP9KB0QCi+TFyVeo7HRm/06WKxLhM
hxgH0SFLpY9RzXtQMJnud9zECoPDzikA6F6cMt2PMutwpmGOqmyZHSfg8SAIsnMvAuGwlMzcuiZc
8SEHUeSRQ74e9PSCgXasawgGkB8tVtYDnFlZpWqOH6MBmhyKMHpM3BWJl9IuxtL09URaaxw/cD6j
G0k16xmCr7ir8JUR1zJa6amsFVZN4d0pAZBcj6Pv9jzGnUREzRH9xjCsXEA48N1peAN7xiS8+8i0
g9MwwbS5LgsI01Q2AgdtBrgBaVfRuJrjjZwcQ3Hd4Y3C5qtQeOAS+paQM2mYNTUA5Uwjf7aOaCcY
hz8aS/RS90QphtHcpT1Au6BDu0ZMuG38PwVMIqnwlgEA
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
H4sIAJ6EVGoCA7087XbbNrL//RSImy6pWKIlN0lTxY6P/JFEW8dWLSdpruurQ4uQxJoiVZKKrPXq
nPsQ9wn3SXZmAJAASdlOu/f6tLYEDAaDmcF8Acj2s2cb7BnrXxz32EvWYAdzP/CYy4ZROPLHDS/2
v/KwzmYxT3iYuqkfhY2Yu96SzdzhjTvmDgxHDIfQmvKEtV62f2K903eAYTpzYz+JQgANeZCwWTBP
AHUau0PuXgecTd3QH/EkZW7osYQHfJhyD5HFfBb4Q8DHDvufHMYuJpxF83Q2T9nQDdk1Z36Y8Big
mefHMCxYQksasV604HEvgs/bP/NlGKW8jvgWEx+mS/l0FgDS3iy93VZfWBL4HpDCU9dzU5f5Ccye
un4IuEdRDKQxd55GU5cm48MbxHeNXOIxUHYaQWMKFABjGIBHsa/4xBZRfCPwzTgNn/CY+LW9seFP
Z1GcsgRBh+yP+cxNJ07gXzvJMPZnqR+OnV96zrMMELjpjKNoHHBnDCx13sGvA0GFApFIRreO57tB
NE6cI/E3Q/K7+9W9dfwpiM2PnC7+7Z7pnY67SJ0DN/GH/TSObnip7zAKorjU+jYK01Lju9idTfxh
snNU6jrnIdANa3wPgkpK3USgczAfjYBfHpFpwIRA/AgE6ryFX0l1Vz8FnXJj7zCaLc9mKA4DLuW3
qdOHloAfgWjeRjEIeGPD4yP2O2rsHgv5gmlMtmtOwtMeqEa67AHpKCFoG5LW2zUaSXQfoRLtsTFP
D+dxDLrQVa0A5Y+YrUHBLPMgqLG7DQY/UlpOMokWx3EcxR94kgCsbV186Bg7kPFbXIpVZxboHyFE
PYtmPHSsGiEDQudxuLESa4K98pXHQFY2uQME9qkVyEI+kraOgmhx5CMgEraRxktJGyJBvn6MfegU
6BDFx/NuAmwY+bCB75ifsidiKPvb3+AbTTKc8ClwCFdrIQqLrQgl8kKhfKI4USACZIC0KbgaIuy5
yFVqrm2swB6kwwmzLyZxtCCj4o/DCNQGuLoifhsoH5inv0zALtAsMXAzTpe2NYfVOpNoyq0a8PuX
eQ82WQMk0pBCqG1swGYBdRDMPXWn3GDRB2lZbKIeu4EZ+21mEbhlDO7D7EpKCOmgIQRr2QkCe9ve
92u/OUDIb07qj7aebgM5UtoG3OV/dxr/5Tb+0Wz85AwaV1sIN7A05cNZHD85ns5gfbWaMbWiSuiE
8AL08QEupVPXEdAOWiGrTnRlQzR+AzUAPJj5Mx6AlR3IUbjtLGJR5zqJgjmYacAD5AnCn+SkAOlC
+mrjzHA/BiHb7JX3CEtu/NmMe225FgY+Aez6HPTVTdnTOw1taerVJqGXy02Ky9V3pIOGfJ7gjuz/
3O31jo8Gp2eDw7PTt913lTtSUrNH5sYZxdH07/DBNsm5ACtlWx8v3jZeofJ9cGfOMHCTpKaUZhZH
uDWkzj0kFwEMNMoGF7zWV94TzftOGgmsUj0tYdTkKEBvoknQBYORHfJohJSx/SLApUbdFWsLk0Ky
tBVSE0XtL1hCGoc/m3I9zHp6pxGwstBCovBhUeTc/bAkfrE5V85m7c8I/uDk7PBnEPxB52iN5AU/
Ner3FHsNnGXOVkK12WX7KmOoRhIP0RB6aO3SeM4ft088PxGjMOhR8jFZ6Pyp/XDU7XcOTo6P1m0D
z8cBCfFCQ6D1aOw48SFa3F8LCSy5Irwp+uUpgEDAMp+WsZf6H5ijBJ/NlIe5FUvIux5cQw5KqFGs
+cJya83++c/S4sxeDZVu5P/6zrLWcL1eIqhOAb2+pukco3yICyB0DyGF4EiWilX+4/tMOC+xAIhm
DedV9kQY3g9E3D7IfDphwPwgSSmd4EackKMGHCedi+P+xeBt97RzMjj/eOqkt6l0t08MDCXH9Zfs
HER+yOGAY2ahpx3xHDIuSbPm6UxSys7uL1o9cHdn593j04vORffstCgRYicQVoi3SjQZHg92nT8F
bonR2hIPk686FoEXaNFABkDtPEgTZ5h8VcIQcCCFI8oZo1jumCcm6v+smDB1FcssCclHs6Bk2AYR
SQL/n2Uj3JIbJxwWfwK+EXh7x2SEgSEaa7zJkoCvbjDnaOnARhFFBCaTJEgReOBJ0Rg9tpjzOooC
Dhn1H/MItXaPjdwg4dSFbscGPWCYYDRfw59dmtwJeDhGJkDT1pYSCf4MJ24MvwCc4PBrJ7X9WgaA
Qhcz6cNUD46EeHfTYm4icGHOwrZYqzgzdhhTIBAlNLY2vFachFaFDHFciEBDD4HFKgywFePAA42k
AtIKXqkfGmhMMZzky380YgwSNgyc+pi6sUKlALu7cuI8anwtW0A7TwTnmjWZ691PrIBZj1hTVwmV
hQ9CYVEfC3sYa1TUlVsTTFIhQYI1PIHUVNgWzXWK8GwC43hMrlzbEbaa6LJ5JcJiMCBR7CGc6nI8
2JJ2qwaeMcBKFsyzdvMYuBGqlsFAGou7qy22lyTH4e5w8tlPJ93Q47eAeVIHVW28QejLyRWmjKC0
Ar2T+P+gFFN+v/Qx+LZU2i35CANLMdjBsuuJOF+FHXIpxyFwCwi/Y5c2sM7XhVNrQ6J/JbmXBQIS
VSlS+VaEwpb1Yh/Emy6LAZYoGcKnvglWCrZk+PvwUE14qCN5RrRibUJyaU3dcO4GgwjS+9j3MJ1S
TaARMZUwsQ10PAYY+hzd4O+Yf/X5Aj/NgbYYa4zWlbbKcze8wcqBSdBCSd2ulblHEgZVIK2QinZp
UyuwsYBJ6kVDgF9l4p9EUcLPec6er4YHyNSh69VVWyZXkLKm3xmorkWoCpcakqsMPMOiawqBa/iv
smKRhl2VcVKs+pC36QJnxm7QicdzHHd8O+RU9bM3P4Y3YbQINeIsyvsUPStLOlecQyPpz8yRD4c5
tEVkc+CyIQyOFtw7jxZoCvJlOTG27GP4rxsR2CBogE/n02seg8EK008oX9JJaO/zVFAPk45BIcVO
Q8TZ/I7WXokpFyDE7b5Hdfw9ZeI0u4mMkOLGH20hmA6gQie2DUC4EqohNK0azggzoRM1fJcAhGWW
AIHzGsGFYThI6DUWDa2pnySgkBa6aDlziqX2wQhkBwo9mIG2Qoind83ERsQu3f/ka3cSTIjvmFtn
1/p6kcWJm21R3K+XrqTmCpfRNEGvTdDrNaCgdoAVVpNc15R5hrG7e29gtgzMi+ZY3Rzi/Las6Xgc
DInOPwFUGoOE2NePG0M7jcgZ5uQMBTnDEjmpJCcFOcz5YBS74iTkQZpSSdPjB9L2JMLSnLBUEJbm
hMkOG4gSZrGoXghvw8yVvZo+CEZkOpGn0tlUJcuAvoRrZgFStDlVVWR0wjEpjjnFuqYN2jbNxaYR
8+RUQPiRpbewLcEWkZdxg2KJtvzhngosqiVVt7NCcE0kMBbNMdDTKldOKOu15EFUG8aoRaKyZEo6
YbPcWQa/r+opK14qiZjMwe92pJuFuTPSpH3A6L7T652ffTo+IgORAagPHyKPExihykrxaH3eu8lE
x6naCmVSWpOtLGUeTO6jkyefYAHVsh+k56iwYIAIB5MyxnxgTaenP0GbtKfRtqdNYU6nYJzUveF2
a6cmNUae2BZy8DUaokdLAzrxtaRWbD69yxRlJdbx9C6jEXVXzhbyoDBXTkFxBnFEDGtWw5zpjefH
CTinDTSmsAe+uslnNBpmsVMcC3/2vXTSo+3cetls1tb4uRzT+3WY3nN/PEkFqp/WY5KiGYHbk9V3
A5dqLwm3E85nE9Q1PDNlqZ8GnD5JJsFHW42t01fn4OzkCHdBOnGm7q29s1OnPLkmmmIs8NhqTc9Y
02n+8IOHpyaEFgzUN6JvPoR+51WOPnCvefDABL2TTvdUm6H18sEZdvIZ/hgW0Vt9N0z6PPZH1roZ
Wg/N0HpOM2yIY01xREsxb35ozcZ1ZhxBg3Uc18mx34o/S/FnIf5MskBYOLhk6JJJFlT5ob1g28wW
nXjkNkZTR2prg5GbVHQKTVQOB+fwFhlCXGbFKk28sFgiQ0cxeSQKNXsVjthd/ApobtlWeTisswGE
1mBBO07TM0d9gVHLylETHDUxR40dHEPMt4n5NLH48wX+AOc9SIQpTFfZDPYdoquNyScUJaryF3Ax
QnloB1eKVclzjAWNTLHz9SQoDGIVdn7ggHmIZ+EJTSEkgPOow7Qq7mWCqNp1xMkk56TOFGlTcvwZ
c5Y1rThyDKKbCc2mpFCtSbIBo3zRLrMddCToQahxTQXG1oJ9q25RBVVv2iw3/QbOTAUAFFdsWrD4
RJ1a4xjwB5s4cos6IXfNry6Ia0Gi5ChKleAhPqirQ9Ssn3kUSyV5X1373NVT5izTzXpFHUR9dXyv
aMg3894BhnIm3i3WUofHUuGiWREnNB1mqV+B1XLUdZSm0bQ4ULSuHWtk1l1P5pj5tKqrItUslDzK
WXHXM86UHpMaG8zxVgxCHhZGOYUJxAs0kWlshbmWdt/okpb8c1158oKddi6+9I4H3dOLwfm7A8ES
3QYgL2mcvLuj+uxavtuN+0m2eVvJ+fn4y6BzetHtnHQ7/e7puzorAHzqnHw8zkEGZ6ffghoIPz7v
nZ1QqX4NbgNmcNA9/Aj/a5PQLS2bfjuf33cvjlXfyA+Cc5A12pumYmDOyTUoDk46hz9LKaEFg0CJ
jsPyoOHVWof7Wbr0F57mQAI+Sk8wdPhsOKMXzQfQtFoGGoqevugofnj+kOd/saNjAPV9586+DcNz
g4YhVSkKSFrPH+JHyyCDwqj3BhkvHlxIU8dAAflFZFDx40NLaTWNpYhU9XO2QT6D+xGifsZ24HMu
tXz1PCAZ2mpsQ2PIs4LhyOqPrRr6tIo+czl9aNMDKJqszrIlNPJVK0IbSqINxVMgnRyo5rc5Jt6C
VxmCLW3KLTV2S2Lb0G0yeZ8L1LyCOcemopcwEwy8PXpRBZaFB3hKmU9QPr4vbE+0jeLTj7B1xP9a
fUKPhWwIfnLM9TzzqAvWyZYv9Srhq7KIqC+TL6tnzumqwuNqFWNgoeFr/0St2NAK3O8ZTghbmhCz
ZGJs59Kt4pdhzox+iu9ksqR3abFWXqcl9SjK0Fi0ZKkkeEulYXQfkjRdWlOqIun7oMhKrbMut1aJ
m3+inK4PE8UJlELFQYBtLEvDUzPwqHhSxmrkh895kBWRK8uy++1ir1aZNdCLeoLoLFa61OF/cW79
RMlAZsYZEitglNeh6eTQ1iarlU6OszEVRwRnWuhz6IZ44QwR0k2qDGXVVcPCNJiP4KkmbcEt3WNu
GXoARlYY4a3c8pqoVH5LFkDQUKdch5SzzqRZzeyfppr6kX3IuZecK2Y9kTUvVWvzQ3Ypj7m+9Xzs
yiSXtqO4fU72TbuNbus07LMXTnMEO77lvBjVKnAoE6kPyQ3mznPwsK2X8KuJdcW84ydoEv+XkKIx
oOjpXu6tIaTS9lRZ6fzYptLOGBYhq7/USzcPbjXzU/JtpjGC1h/lSsrKrjGw6oJDpTfSmVurGJKF
oZgpPdcIfV4vuHvwQhBbtl41a3VZCjLIbjVr91G0luem3ReIqyA087/5yyHe0BFaj1X8Vf5VbIKV
3q9OWlabZdGQeIh6bekVq/vRJGllbswsMd7dZZfk2ttGBiv9PZmJNqvIUMuEgRU7h93eLjlYC3w+
ZuWWcPriCrHwC6bnK6HM1LWgufkJn9ahHVSWMSHPM4+BX8DmR/N4yM+jheZJwLgJcWRt4msVQiUk
Da1qwngIT6beyoOpDKRwYFXFRHRmFxHEIEd8XPBxgzSC/2YDj4/LI6V17oUw6l5vAVEV/2PugyN9
jwcXYne2dROdu/mVdqQFCu0nM3DyMtTO6/RFr6qq8DmRYhs4I3opY59EWBN0PvbBkn/f3PEG3ycO
eG2r/rCi6Tr6mHcL0oIo97yI/VTVAPAQgSbN1iBgzQoR7g/fK24OvNXczgdWMDmP0LRKTZsZ4a/W
ruJhzYInhtIndHan/KkbLvvqDsqp4VvVxnYARBRlKqQNElWYroNoeHMWZuPVZfE/wOf66XLfIQD9
zvfnCQ87an4OOy7mOglP1F2z7JVBPrQvz9YKp294/P7EJAQvVq5ZpKoFWr3z4766ljg4P+4cfSEr
88vh4Oi88/bCEiXAqVbro3EJvilyP/E4Ic5LBRP1HK+TCndefN1lby7hp/HhQ8PzrAvr/fv2dNpO
kl9//XWzptQaxx3Rgy4pfjLP7fxBTl3eJIqk+uS3440ePH1rP/I9BsJa9JRHLoN6UQfb7L7XKQJa
mkAKgsWd1jZbd41UjFCng/k5oWjXTnnP6e6sAMpOQCv7xVAF05eGt3ASawLhoWu7+ixWABqa1Ta/
1ks3udSc5TY5bbUKttd1iFHfuGXa5ibUdKcPihqm/hAotE5RyQLw7h47f3dA9ihKwJrRfYBpFM8m
EK+Ml9vGPWFC+JpersCGBlTyhtbUjW8gNcCb02GC17vkpePsYlsvCvzhsngLTADpL2HbhZcOWpcJ
3JcX5vC97JpBOog5+DAzvveNL0DVc2uetE2rXlhs0s7fD18Jq0GOogMm2R/SAQi5uNSNYVsY50Aq
lRZdxReG+/k5dOYx0+lM95XVA+saQvnob4tZDoyVxR74hBEo3XYQ51HqzoPozt5eqnkTZwpbwMZx
aaQCATlJ3lB++OqcH/cgBD4eHP/a7V9gxbqQu5cGdC7OPnQPBx/OPsnK8b3PLP9viFQ1J3CZmiTt
R1wlUO5CXlGpizsnaUQ3TlQnSQPPpR6FnQz1IAndWTKJqhFLoByvOFvmAZ3AXFqkvWjn86icLoWK
kBs/ZnE0fsniBvySh8Vyk1sY/2JPFgHTl+zifx7HEi4jkhWz6jGqQloRZliCuTIsta42zPv+as/l
DzLMW/9i/c7vEeRzeFZYy67CE4uySAfLXub9Qg2zGiOR5cdV2QEnVu4u/fSqxlZr51o9QtBq0kH2
7w/I9yN1gx69slRgyCSdBpWcsHafeNEwXc44wbzZxX9wgG7Vgw3Y25yno8arzTdAJ/ZmlO9SUfZN
6ZmJpHp3W/SXhiXpEpqvI295h4fWjZE79YNluxP7blBP3DBpJHiB4jU4kbEftneez25fXwPOMR0S
tL/jnK/86fhugafY7Vaz+T2A3jbk151m04RfTICzClnr5eyWNV9fR7eNZOJ6kJw12Q40tfDXd69e
vVoxZ+HG4d0Qs/P2d+7LF9fN5muic0GXDtrXUeCtdrfFKgqL29ydtN5QNa2isE7F9HXcsgAljN0s
4pu9UTHEbgKbk9Flsz3LnKOf3ynLolNMjHElFLNaK+tN1RBcB2B983rNo8rXIsDJL9E9vTMiHhg/
Q5oN76c2DDWqLVPg0g6Sg/2Oj0jg+y6IlCXxcK/q6tX20zsz+xP/joKm69oLWQspesyOokuWDlKG
m4goNHfPNz1oKnO39jgMOU2ULKovFa/NN6ofycox+EjrvuGrzTXjVUmiUkNe57WcJIjSRGb3GKDE
Mrx8epfZyiFsufSevBDvkmbA4lwNyJJ163v1ueq9WzccRadR6o/QHGJVe+32Yv/6n/9lvxxiPXNk
PBvtiX/9ZcFjrnI0SJ/nKUsn2Z1EWOgU74owRQyDRfpBcW+IZ6WYE83xfo+sw48Cd5wwF9FDQgwd
+LBUvn76SysS1x71t+ViKUhGlpQmLibAczymL2mH9qB8tfFvzY53TmtHAAA=
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
runWorkflowScript(step2)
if (System.getProperty('tma.orientation.status', '') != 'COMPLETE') return
int processedThisRun = System.getProperty('tma.orientation.processedThisRun', '0') as int
int exportOnlyThisRun = System.getProperty('tma.orientation.exportOnlyThisRun', '0') as int
if (processedThisRun > 0) {
    println "=== PAUSED AT FINAL ORIENTATION REVIEW GATE (${processedThisRun} cores changed) ==="
    Dialogs.showInfoNotification('CoreAlign orientation review required',
        "${processedThisRun} core(s) were newly processed. Inspect review.html/contact sheet, add Epidermis override annotations if needed, then run this script again for final approval.")
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
if (ALL_IN_ONE_INTEGRATION_TEST)
    println '=== CoreAlign all-in-one INTEGRATION TEST COMPLETE; approvals are test-only ==='
else
    println '=== CoreAlign COMPLETE; grid and orientation are human-approved ==='
