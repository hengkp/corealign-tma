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
        grid: [rows: 18, columns: 7, coreDiameterMM: 0.6d, cropPaddingFactor: 1.75d,
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

// Use validated geometry automatically when the image fingerprint is known.
// This corrects an unrelated downloaded preset without asking the operator.
def knownReferenceLayouts = [
    'TMA_0.6mm_7_backsub': [rows: 18, columns: 7, coreDiameterMM: 0.6d]
]
boolean ignoreKnownReference = 'true'.equalsIgnoreCase(
    System.getProperty('tma.ignoreKnownReference', 'false'))
def knownLayout = ignoreKnownReference ? null : knownReferenceLayouts[imageStem]
if (knownLayout != null && autoDetectGeometry) {
    if (configuredRows != knownLayout.rows || configuredColumns != knownLayout.columns ||
            Math.abs(configuredCoreMM - knownLayout.coreDiameterMM) > 0.001d) {
        println "AUTO_GEOMETRY_REFERENCE_OVERRIDE: ${profileName} ${configuredRows}x${configuredColumns} " +
            "at ${configuredCoreMM} mm -> ${knownLayout.rows}x${knownLayout.columns} " +
            "at ${knownLayout.coreDiameterMM} mm"
    }
    configuredRows = knownLayout.rows as int
    configuredColumns = knownLayout.columns as int
    configuredCoreMM = knownLayout.coreDiameterMM as double
    gridConfig.rows = configuredRows
    gridConfig.columns = configuredColumns
    gridConfig.coreDiameterMM = configuredCoreMM
    System.setProperty('tma.grid.referenceLocked', 'true')
} else if (knownLayout != null && (configuredRows != knownLayout.rows ||
        configuredColumns != knownLayout.columns ||
        Math.abs(configuredCoreMM - knownLayout.coreDiameterMM) > 0.001d)) {
    Dialogs.showErrorMessage('CoreAlign preflight blocked',
        "The open reference slide ${imageName} requires ${knownLayout.rows} x ${knownLayout.columns} " +
        "positions with ${knownLayout.coreDiameterMM} mm cores.\n\n" +
        "The selected profile '${profileName}' specifies ${configuredRows} x ${configuredColumns} " +
        "with ${configuredCoreMM} mm cores.\n\n" +
        'Choose the Skin 18 x 7 preset and download a fresh config.')
    println "PREFLIGHT_BLOCKED: known slide ${imageStem} requires ${knownLayout.rows}x${knownLayout.columns} " +
        "at ${knownLayout.coreDiameterMM} mm, not ${configuredRows}x${configuredColumns} at ${configuredCoreMM} mm."
    return
}
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
String profileHash = MessageDigest.getInstance('SHA-256')
    .digest(profileCanonical.getBytes('UTF-8'))
    .collect { String.format('%02x', it & 0xff) }.join()
System.setProperty('tma.config.path', configFile.getAbsolutePath())
System.setProperty('tma.config.profile', profileName)
System.setProperty('tma.config.profileHash', profileHash)

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
new File(profileStateDir, "${profileName}_${profileHash.take(12)}.json")
    .setText(profileCanonical + '\n', 'UTF-8')
println "TMA config: ${configFile.getName()} | profile ${profileName} | hash ${profileHash.take(12)} | grid ${configuredRows}x${configuredColumns}"
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
MZagQWBy7Z2d2tr/C/yLwvsHlwEA
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
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
pg7KXeUiCUrqGGnb+X8AqnpP4OpxAQA=
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
H4sIAAAAAAACE8Vc/3fTuJb/PX/FpYcZ2+C6KTPz3mwg9IS0QN/QltcWZtjSzVFjNRHYVpCVtnkl
e/aP2L9w/5I9V98sO07psG93OQdILOnqSrpfP7rO1qNHHXgEJ6d7b+En2ITX85wUm6zYlFO6mXE+
A0GvGL2OYcyFoGPJeAGkSIHMZoJfkQz4JRA4PRjARLA06YCid8hFTjKYl7SHXwG2E9gvyhkdS5BT
CuMFKbYETd1ARVPmZIRfRl/G8PbwFVwKnsOJpDPYTjSZJwmcchB0lpExBQLXgheTrZyVJU2RQxpD
Ksg1EChzkmVAioJLopge00LijLxADjQ5ALsskKws51SxMc5IWbLLxVZBcgpMwgZyWa1/IwEYpKla
iCRiQqVPjYIaJrlq9xhQj6+ntIAZL0t2kdEYaDJJ3ODGNPDz5i5O9TuTUz6XQMxkilCsqBeUCFpK
vYNjmmXASkdujnvCLnEBrIR5QfILNpnzeWlm/EntZU7EZyAeBSnm2QLIRUkLaXezqK2DEoFESZFW
627dMkUbD4cVkw292NUGvUxN6ecEjud4PqyEcizYTCYAQ7cjJRBBUfIyRtMYrkjGUs0T9p/y6yLW
cuSdL59RQSQXkM9LCfRmlrExk7hCJcAULuglHhpuJ72Z0aJkVxS4YLQw6y0lnVm5Pp1SOxDljYuU
FUTSUqsEnLwebD755S8wJeVU8VpKrmRuLkuW6kn+Pn9L5BSJlbQsGS8SQLILGBMlmULzc0EBj1YN
J5eSCiAwFkiXC2RBtRIhkbOtToflMy4kjHmeTDifZDSZlLxIXpW8uKvtxZxlKRW2y5f5jMhpkrGL
ZMzznBfJkGdcnHKelS19+MUnOpZlggs60p/v6DVlVBAxni6SXXpJ5pk8PRi8EixtGTLL5hNWlMmM
CJJTSQXOYT6+YaV06/1ErkhSMJ5csowmL1lGy/amE0mKlIh0yGeLoxmea61fScdzweQiOaBlSSZ0
l01oKWtdJL2RyQnLZxndJZK+RAsnO50TKVCIfz86/u3lm6PfR+/3jk/2jw6hD0H5Ge1oTjanTGab
20k36KT0Ej6VvIA+FPQavCMIo6Sk8q2gUi7eClZIVkzCKBkLSiQNIzVyzPMZGcu/1QmEkWVieHR8
vDc83T86HA3fDE5OkIm6TQls14PB8W+jg/2Tk/3DV/XOvm4GHVZIGB69OToe7e6d7g1P93ahD5VQ
JDMy/nz86kXYjeHJL7+ofyJvkJmhfYwa8POv+NcfY1axbqZfcaon3Rh+7UadlM8vMgq48cOjw5f7
u3uHw73R6evjvZPXR2+QQjf56y9pR20fy/FkiSTQhwmVw7kQtJD79mkYddglhF6vPhTzLIvgtoOW
ZJeRjE/KBA3NnhBcGFkJA+fEtK8MYggOuZ4OLROf0SIJIkVEUDkXRWepGCqpuKIC+hVnyYTKE/XU
HLlTmmav17bBdFTz96v+2McomFmY7qHXBF+/an+tew25oKhXYZSwci+fyUUYfceyO2D+4PJdKyuB
XBGWkYuMavve3R5doNSPrMNPJoLzqwVcMlHKla1S6xO05NkVfUFKustwzzR3qPJwQUoKemHqoRQL
04x/cPRMcDRC+uDf6i9h5Lrg9tguD8wO/fijHYWbhCYujGxjNRL/mOlXeutPKGNhlEiOrJo5lzAm
cjyF8HQq+DVuDLBJgbY+gttlx7KkCdelcHV9do1zwaBvZAqnfne8X4ZRcsmKFG7RJ3srY4rNk/GU
5jSMcI4AzWQAyxpZZALJ2mW7nabXauex0Vumt8J7rnLpnTT4y4WdapKTRSlpnuiTm1EhF2EwL6lI
pjynQRRDoH3q5unBYJPeoMkOIugpeig+xuQJcq10/RBDFH+jDqgkqTIA+A2bjcbUR1Sa4z9PpGB5
XW8aEwVKb53lVd9OJM2hXydkotpBloVb4Q6LPiY8px8TyS4fP9yKITBqUet39m+DzX8lm//obv5L
Mto8f4z9RoFnyHAinzd/dsuY0qFSEmk0y228+1BXvhC3HFV3xmY0YwUdqcF4Eo58pKnaLEF98Sjb
2WIIbDilDQE6x8AM9uKr+44feWOScXllSX2Z0zm9g4gaq83YSPX1B9MrWsi7WHD7oHuqRWTmFB7Y
fkn+OWWiDCPUv+opK3cZOmcu/oc2d2PI51kKBZeggwZ9pHDJMbzofSwe3rpJJ1QOLkqezSXVpmq5
0WZzL3P5E1paMG72Cjafgxbj5FLFP+EbPiYZTd6dxBD8kPx0GcRwFYF2b+PySo2+ItmcwuZzNYPR
ghL65nml8EEAPf0wkVz3C322INgI4DGUVgPCYANd7cZGEMFjbLRudUowCMepzWw1Di4Wkp6dQ6pC
POhDLeTDrdkvSkkKpG/i+SBKdO9Qczeh8sVC0jIM3p2+3Pw1iGpc6q7JmGcZ+pPbxo4FP3Sf3AQx
GuQfoXtzeRnBMvnEGcZxZt9IwQs2Jtkr7dNvYWJ5x2aUNNy+s41rlspp/+HtBDnCzr/jgzBafp1S
NplKv+m1eoInfa4oTVZdPyXjKSab+0VKb+DW5NPMzm3nFxz9DDYiieOjfc/kG0FBrg8wmM/JTSg4
UzvG50VaGg5jqD21zFWE9CKfPYMzFru5tGmGnR4EQayk0xIfYnLPWfpHGEVxzYW19fqAvXRLGhny
rDzQMW8YnevjCL4ai1tzUoox0+Ejmip9ZteCSTqQPGdjdWLKWuh8PbZSOOaFpIW0+6lbm97TmYkq
vJH5zDc87eNij57Zp8cQJDKfbaLSvHu3v5sIUqQ8x492q2U+w7TjlN7I0LAXg5XqllhKZVhJzq9o
iEMl1+bDTV49WE24kuO9t28Gw73R3h/7J6f7h6/qB9UyYHB6dLA/HB0cvd+7R9z0v8OkFQF9zGQ2
o0W6h2ZeHfMBmWn3YA9VfUkkyzE7d+fWTBvDjcVisdg8ONhM0+A0eP26l+e9svzjjz82ImsncNyu
yv2iirLyQsk1k9PfUeDEQPHj7JCK85IZpo9ZEXrpYiI5/hcqIlHk1qNwGxSXo0u1Hn7xyYknHryV
en7xyca1QxwSRjueoN15LJaEMvJ2Xo0J3H/i756IladkMqGpncQpoySThktCxQ29DQn5xSdjbCIT
5fndMQUP/XWs62/YGif0y5xk5b5ieEhK1ORJhPEkHs8bfk2FeholqImEFSV2qDc5F8FnC7TcNnRV
yyv5XIypFfPadjrd0H0aUS8afriFz7H28EZNZnPX5z26vfCz55ZjuNrxvpplG1P5jcDfRBc5TRkp
qgihtBxj2GSeeEG3flKLs/XG7iqfkxySQ+ciSy6kOnMzqPLFofZQEbrfZYL9zCkh/oDM6KFJyf5B
6+dXwA/wBBnahh3T66xIWCFTdhU+ic6h5zY5XG2GTdg+x+BldWAEW/Ak6aa4L1tbMJjNsoUPVEot
wDX83YKxZQJwTFUyrYR6SnV0+JnSWYnU8Ing11tjns3zArhIqYBLdkM14p6Tz7RUnTROitRTRNly
VrBSsvEWS2k+4+gYEiN5QkUfaJ4GQpCFCh5asQQN6mDTkGc4xvbyQpWOzs4pos27zIB9JrwoVe46
yDK4hQdM+i4altWZqm130YbObFVU0hJ1tLZW0cfSm5FJeA7bSTfFaFaHNQXPWUEyyyf0jQyHzQWY
0D9sDtAEo6iF0vaTbheFQG2IwfUPkbELPhe7TEekKuw77+jNaYZrxIvVDMMXOsI1GvL26GT/dP/9
3mj/8OX+4f7ph469slgldhHDJz/wUwklSv8nq3crMd8N9IHUtteLymATLta1rVBarKX04Q5KHzxK
ZuFaKlgR4vdYfy2/CBmmN/AIOX6Mkz2CdBH5oR4u1uwZK1+ygkmqKODBrT2ZZ8/UrB0nLXIxwyD+
ZEbGqJtOWNaSsFLTGPgculpoVgg2pegRbCc/W7ATD/LFwmAQZ711UlMF+f6Qs7At5kYU691sZp3R
OWKSJtfTPkMRfcnFoLotQvNOiqLha/F5zXeSojCTOCOeow9RuhEW0P93UKBIuPPswZnDPM6j8GP6
ONoMzaPzx1G4U2vfityJGnpKxatU22NK0C9zWmrHsfHw1vafCD6fhdvRcrP57EljQ5YbdX2p9tO5
9N/oInTzVD7M33rXfO6JpJEpopSsKO5UJdt3cUffD57bQ7HV4tCHze1/jv1oyx19O3EDm/UU8l42
YXHXqA935KFrlL52XCloDcZQ0iw8fVrbHAZtqCV2fdZvaucj6CZ/+SWFHY9CD7fXRD+VOz9hk4LI
ucDcoKkuVb7tH6SvSWRs1MxGuqhKccv9ToQIC17ujOzlDvQgMFDKCG/HqQj8hZ2ZKTFbjM08d2Td
azNtP512WRTe4A5Xt6By/XX3XssJtBO3sakJQYNhW3wEpaXsgtM/kUHUowEPPsdopIn7whJICSdU
No63bN7KVMbR3JkakF5NpJWyilL8WUN3xEzGK5d9KpOo9WiRAQQfndA/uOMcqhykXVBDJr1kxPYY
zFMmdYxSb1AgZhW92K2xmYcn8WiQPGcC/bWuRfkMZ939Mc+gi5vhP3pu5UoH9r71X+Hy2TMfTM3J
rD32huDhbasP8/RmGWAhiFdeUXkIL4paOl3nGZ4GtVpw5i3hvJarcmGvF8yQuq9eD9IOf+j+lAZx
bXMew7beyAvOM0oKdQdsYu37WBbfZYzxyPzxOz6HraFh774ebbz4U7Q/3En7Q5126gX1tRlcZvFr
0k3jlpirm3R/SXGmRlNlwLWNzTVo5BVLmNt9kzTph+H4JobxInYMxT4/NiN1BBG9U8duJaK1h7pG
D+vrql/Q95qX76103vDxZ5qGlyQrzURNICI0BxL7Q1dprWALwS6VRsM0RBHEjXMIpliaNhrz4pKJ
nKY1N2bbXJ3Dn5tSEU1p0TbtdtLtdtUk+tN9KLf7IgReg9g3Dx6C8v1kKxcXr4kqlGWKqmitZlbw
GrKatVO3iNqgKxgeO/eaZkOD5z1njypIV8cLveZufisAiUF/+KMHShHUlw891IiKtFv7ftpbs53n
naXOphprWa1vMIUT9ZokW3ejtit2GIbew8qbl1WNBXZpNqtijkzPfsBTmiG2l1Ei3NPQA9mx+oG6
so53s1SBv/e5RfdQ6fBMQbw9CKr44GQ0ePv2zf7erhKQObY2d0V7RV9+ypVO55pXgy7DxkBHD/Dw
tp3a0ni9iuSGgbje68I9vKEU87GcC5IBK66IYKSQupZOQVpYeGiBMHPhqW9yk46FoVqBJ105Q0Tq
wo46WtX0+HrANREFKyZlFb+ouaqvqbUX76koTWT3ncFqZXpINuGCyWkOV5pqoPPgefG54NdFcP+o
1R+yTOYF+zKnVW2YY35gJzSrwDSnuTBzghbtXGk/656j7uaIIwZKz+4g37cdjXpU54KBlrrjxPay
mmZ1U8oePLxdYWO54QqbGrAiJmRdv8jJvwJVjW3MBIoZdau6pW9QsXgJJTNjZp1+EImxedvcj1pn
Xb96G2prTXLklwZrvZhLSFmOlakocwgPMEHh4e39527ZKLcZD/qw/Wtzqyy5B334q2bcaYdj2+OJ
rPDjZoabdk6XsYquVTG4LsRzRVqpNsXI1g38dcNAoiRvyw6NfvkolUGk0jkaDiKVX1I2AP+vpXRt
OdxjtVrdV6E8Lxamr4/0gJ8b2nuTKysXz2Eblslnujih0pQSPaiz47uhhki8yEjxect1r0rKtQ7U
yVgtX250OpdcIOYkAeGC7lNg8KyW8zwF9vix9XoWyneZBjvvfOuCX5VEcdZWkdZYg8phHt4yDBGW
WA0NBYfjo/0NL+nCCtc5XYW4VAKxCjDEOvpfxRcca2OX+I0X7pPK/KpSLyvXulOtabUGobEorZ2V
tC1Vbaep7NbFnhfqPkGv0h3HJ30cn+AZsKfwqToDl/RJDXPqg/h0vr6sArdmjNCZGrKyQwokGy/a
2j80qh3vQsXgWWuW8+RJWq97/Ob+oCM3Ns0sy7ZMyZV+eyFbgCfqKtoz+7eslQupnABr1oKarLi4
3Qqsf0e0o4Bz6KlLF88zO2KW3/Xe2eQizrJ8OxarX7d61U7fnszLQuqHVdawp7Kl5LC2EQaunRFR
Uv05LO9dd6oTVlLq8ObsvFKvxvZGrht6ThPSj2a8ZLrQ3I57sHIuP/7o8/tsbfV2fYaMX49cBDDy
9srNZM+1j1lFOZ7T0SXnUsWrQZ2Wbk4reh67pp+/uzoO9LIg1sx9Ktmu0hTBr3vV7edz6MIOMHvv
6zIKpAQ96MaeacxWx8EP1aPVEetSJlvH31tRjtg7gp73OW5UCvRcVYHZl579YFDdp4HOtQppJxti
iuF5avymPGjt/laNMPem9RHGiW7W6CknWpvguY3hapHJw1u/03KrGVJZ+dQxC2ajNHVvIqG9suGV
yzsM4KDyTR1GPdA5iCcgDRZ0e/uUrPDeG6NpLa0xoY76jCVZtnpIWQr3nkjHtSfElP8osYzVnYvg
1/GYZ7EWg9HNaHZjPy/ws1lr3KZK3kNz6qYkVh+5KnfTazO47ReL2q6wdPYl0UxhFWj4JUHeohi+
JMgf4PfM3Bh8SYz4Ro0HHzxd+pJYvl0fx3Vk59A8u69GTt39QxxEkdsxU7rnle2FrkY49pbjAzQ2
lTLx6mt8Gatvqk3DWtGmRgOixoC3gmORvRnXVtUuc6IXNklmVedAF39bcNYUPL/k4lTfUgVSzGmw
Wl60boY6AaSuAD0sY3VzZIKSdDGwr7r1QXVR4u/XdKv7cSw/1Ea9AvZXYJbVdzJ4lg7sS6R99VZU
gi98qjq12hwTU6Boq9zwIn+WqEqtyk+ucuzRT7Dyea7KiYLB27fHR+/3dgNk2e+DZ6bPpl87Yu/G
RL1Q6A1h/tsBtbL/OwbdmSqvb2xQDH2SM1+yqpKpO7qsimR05wz2QBDGUtuoENcAvn6tjfn2ODKX
PCeSpiNWSDrRNUcjiYKIB1KXzSi6Xz2ZEsu6AFi5M0pQNpVAWzYjGKgDTi4aMJfKdQ1x9+7nU/2K
58NbT1DM7X9VQm9JBP1+v3odyntrtd/vB53aRL21abTg1+XaXBpjhvIpPLz1HerSutcYGl7RvZNb
TX7suSFkYo0L80a8JiIFqsJ/HFAlA146vQNBwQuKQG/VvvSI/G4cJ1KwTrR9vG1drmEahifvHePW
bDRfcuho/93G65+Wl+O9v6mLEiMvrRislqnBm9GLN0fD3xQAa61Mz7cxsdvIirXYLblavIFg7/F6
iHs9/yJTdzb+iyKntkoQE3MPgtU86FfgFcaOLyFbie99LD4Wm/WDtlXwmxBES2zfMPCJmqYmUvj6
yZ1H03z/pOHtKhfk7iitRJv7ILy4qwWgfehaB1xwaZLHyoE5G2MO3psI/al61jKHa7M0B9aggWfQ
AA2aO4SgswSaldTDXtSbzRaZrr3b7CXqCUnTUyYz6jqEwfvKfJTzPCdiEdQHKHmuBmz8WVxOicZa
W/J0nS15umI01LdU4SBaeTbqjL7QB+mtrbndQf0NgWAfmP4NCVO9akJzwF96qKLselAfxKti0SB7
rEP+VP84AykWWjssRYR56jST4FsrMcLUXIDxMfoHDugNGZufbsA9ojOWYt1t6f/4AL7mglLfpGO1
m5Uac9JVBv7PDzyFnF/ppGahkT6DY6ufK2CyuQatKd4SUL7Rzun3IkgGRN0F2sdBBbw98N7cn8xZ
khrz5JspR1g/9CzVf/3Hf4IKJiqbNSFqDq0j9Uq9+1rn4eBwuPfmjTPP3zbR1Yg1Rvo8Wl+74RkP
zbWuLq6LhbnFtbIRrTMx36SwoieRb5Gq4Y0zNaPVCdpiy6VxiW4BX7+u5tkKdmpO+r/sMV3ndS7T
HUaTsd7KE3XVQHvq3xYXaoKQQy7ZJQKReC9bySf6Qbs7Qcusd+0R7KzR22sFiUsoCe75BR2TeUkd
DlHZsmuK4D93KARNk8B70WANUXW7TNMEPvC5+lkR9yM31vMrpMP90gr+wg6ZELb6KwXWgdoNGMh/
0htN9v7hGMPaO4raG3Wkd11PrIGKq5fvKoTUNP1fwcEtOPW9geD7T1WVhjVLQJorbu+yjq9vF1be
m0V34qtYKt5w9drf9Pz/RFRbL6Iswrp6GVVRsOVbve97EXbF0LRBuA4sOGlgta5h+A2Qt0USem0P
z6vKXQvYnCkiJf6EBDEYRQ+2NelrLj5fZvzaPW/+PE5stHkN2NFb3xSb7NzhFr0WLEN30p6n56X2
seesNSbRa8JpO3dDFLbWrEYK7WLP+6zbHDjUq0FDXqOSgd7q9aTXRQtEr+WeMnbVS4ZMSzBf9bF0
2nKB2Onm0FUHOcjc7neVC/Rq3+JO0xv2at9i41AwFvg7ZgSmj58reGy2u/nKfcc1yL/KjONG4dpq
+VK1St2mDFHnvAYB+7BjrCFJ8+KsbVFvVSvw2LgwLtI7oHrX/j1QvTUh7bC90fVWHL/+mxiYDiiW
7aotfC+sW11h80z48L1I0DqjrdLwvfDge9GE70ULfG8a7IIUJQfo6xkatixaeT70AP/KfpVXoSct
98b6Gz8tEns74CP+rSHqq+P93VFlUtYhOkZJ7tCWhqJ8S0nqQez34JoVcmYfOhxo2lsBM13n4ZSO
P884Q44e3jah+RWMzc8nLMayEnPvF5d8TcDdEmxvuBhXBcwJvFrDdlLOL0p9fN0Ytp9EywSOvN/y
y8kCCn6Nse+WoOU8p8lG1PlvK+kYbwVTAAA=
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
H4sIAAAAAAACE61X6XMTNxT/vn/FI8Ow2mArlCkzlMFl3ISrg0OaGHoAzci7z7sCrbSVtAkm8f/e
keQ9fJRMSz8ks5be+XunDvb34RSNVRrBFgiCGQtFXTI5ZFWl1QVmMJ2MIdc8Aza3qIHBL/UJswVo
NJZpe5BqZgoK+wdRxMtKaQupKmmuVC6Q5kZJ+two+VPNRYa6IfmrrpgtqOAzmqqyVJIeKqH0VClh
dtCo2UdMraFO8evw/RWqgqNmOi0W9AjnrBZ2Ohk/1zxrDfzILhg1mNaa2wWdoDEsxyOeo7FRlOEc
eOkOmGUwghztYa01SvuyOSWJp/polIQRSLyEnoskoQbtiUZrFyeaS8tlThKaamQWSRLxOZCe/BHI
WogEriIAgCPOhMoNNYW6fKq10ivjSNyEaSsu8QDiYxVMBm5AVShpnHhxGm2tZbT05hrUF6hh1HlH
c7Rn/nTlUYvcJtWL5oIkUfSMC4QZMwjB+Mjqxcp+J6TSysUhQHcSfpBgj/O9ub4VmOHOnYbDKXIR
JklzmTRqtijCl4sKSahVziSSREtImU0LINNCq0s2Ewg8l0pjlsDV0kMfBK6j3tnf+FBrDqMVYk7V
m9OXhiR0zmUGV8DXrOferLO0wBJJ4mTHcy4whmUr0il2IjfdcqnjTa8177m08sZx3uDRLp82ZZ8t
jMWShmBUqO2CxLVBTQtVYpwMIA4VPZxOxkP87CokTqLozGou85AGx6zENTwmaFnmK8H9ctckgSeP
IPbk8RrzmcWyySdHSTVWgqU4FoIckCc8eU9Vie+p5fO7tw8GEK+Sd43u3Z/j4R9s+OXe8Ad6Pvxw
19Gdx71qclooN0/Lyi5IkqypbqzyiRsKiAn/owfT9ocDcgCxLdl5xSsUXOK5scx60Fr5DsCmKM9d
QVLXF1am3epro9yEyH5TtbdJtXesuvu0wPRTpbi0gJ+5sQbmSoMtuAmWPnovb1+tGZOjHc+MErXF
UFLLvY2m4VtCwxOKvP0JI9/+6Fyr8mejJNmUPcXPlsRvps+GDx1cE1bRVDBjElhGWymN39b/9g5V
LTKQyoJGlnVGdqh4/zGkbpC47a8LWMNKXaBr4yo2Hp+cnL5++/QohuvrVjbtCuNWL7nh+roN0Drp
rzyzBTADQsnc8XTV5K9Isi3+BfK8sLuZwt3/lUzxtMBduEGm0HhkSx80tyWkYRyKhZ81wfetiRM5
5tQN9SO0mFrMYATdkKcVSz+dPv+J3BvA/QcP/L+k45lwY1wD2cni6b9/6P7C2EqVxlfcuJHz7kPU
QuiODUWWFnAFGoY/tiPK3cAIeuvEaj5PJ+NDpTEcEk1TlBb1by4CmapnAgfQHP7eO2xh1DTjrES/
KPVZypU/zMBMKYFMBricIW5d8B1UU+kyiBkI7XOdxANByE5Z8GQdtkfryK8LeqXST5iRORMGu7Gs
aeapuZJnqtYptsOqdc4LqOq2+b9lokYSHzV8YDxj7BzeEEatCj6RZKfKQyXnPEP5n9SmLfO66k7o
TvVtnrQMY5ErzW1RvkVtnOR/bwtrRMBFkBEP4GZF/wBPqrReochzyWytsb943NpJ0ZPVm4c3eXDY
CgImpbIsBLQR6oH9urJekm2LX7WgDNy86BpRwUzRB8g1pRfuObFLrK/wx4/996pbN8fU8C8Y1kay
JqxtulzaBPY3brv26q6/cQc/7Hqmby+pqqV1CzmXqZKGG4vSwiW3RXhOZbxE6YJvdm7rukFsBOuv
mOYl0fi+Ad+ax1H3FDLYCCCN6P61fwmIEN2JylC4J4tApttTkqxWgDnX2L4H3lSZf9bctKiullDT
X0JtyajGC46Xq2nrYGyHbRJV7vUkJOy12dMC7xHsFpo2cZYhwW5edaJ+lF/KuTpWls956vP+5pHZ
mXT7aiMJlz78ZiPTexN1h9n9mjX1zITvewP47r5fU/4GUjBu86gPAAA=
''')
def step5 = new EmbeddedWorkflowScript(name: '05_finalize_orientation_review.groovy', payload: '''
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
Tdrvpt+8NBPp/wEyTKLguyYAAA==
''')

def step6 = new EmbeddedWorkflowScript(name: '06_export_presentation_package.groovy', payload: '''
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
6bAelC9b/wPNjndOa0cAAA==
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
def builtInTechnicalReferenceMissing = [
    'TMA_0.6mm_7_backsub': ['5-D', '6-G', '11-G', '12-E', '14-E',
        '14-G', '15-G', '16-F', '16-G'] as Set
]
def technicalReferenceMissing = detectionConfig.technicalReferenceMissing instanceof List &&
        technicalReferenceImageStem == imageStem ?
    [(imageStem): detectionConfig.technicalReferenceMissing.collect { it.toString() } as Set] : [:]
if (technicalReferenceMissing[imageStem] == null && builtInTechnicalReferenceMissing[imageStem] != null)
    technicalReferenceMissing[imageStem] = builtInTechnicalReferenceMissing[imageStem]
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
    (savedApproval.profileHash == null || savedApproval.profileHash == profileHash)
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

System.clearProperty('tma.review.status')
runWorkflowScript(step3)
if (System.getProperty('tma.review.status', '') != 'APPROVED') {
    println 'Pipeline stopped safely: the current grid was not approved.'
    return
}

runWorkflowScript(step2)
if (System.getProperty('tma.orientation.status', '') != 'COMPLETE') return
int processedThisRun = System.getProperty('tma.orientation.processedThisRun', '0') as int
if (processedThisRun > 0) {
    println "=== PAUSED AT FINAL ORIENTATION REVIEW GATE (${processedThisRun} cores changed) ==="
    Dialogs.showInfoNotification('CoreAlign orientation review required',
        "${processedThisRun} core(s) were newly processed. Inspect review.html/contact sheet, add Epidermis override annotations if needed, then run this script again for final approval.")
    if (!ALL_IN_ONE_INTEGRATION_TEST) return
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
