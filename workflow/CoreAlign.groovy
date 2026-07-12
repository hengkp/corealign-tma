/**
 * STANDALONE one-file, resumable state-machine for the skin TMA workflow.
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

String REQUIRED_DETECTION_ALGORITHM_VERSION = 'skin-tma-detect-2.4-auto-geometry'
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
    Dialogs.showErrorMessage('Skin TMA auto pipeline',
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
    Dialogs.showErrorMessage('Skin TMA auto pipeline',
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
    def starterConfig = [schemaVersion: 1, activeProfile: 'auto', profiles: [auto: [
        description: 'Automatic geometry and channel detection profile.',
        grid: [rows: 18, columns: 7, coreDiameterMM: 0.6d, cropPaddingFactor: 1.75d,
            rowScheme: '1, 2, 3...', columnScheme: 'A, B, C...', showAdvancedDialog: false,
            autoDetectGeometry: true, autoInferLayout: true,
            useExistingGridUnlessRectangleSelected: true, trustNondefaultExistingGrid: true],
        detection: [algorithmVersion: 'skin-tma-detect-2.4-auto-geometry', channelMode: 'nuclear',
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
    starterConfig.profiles.auto.grid.coreDiameterMM = automaticSeedCoreMM
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
    'tma.grid.useExistingGridUnlessRectangleSelected': gridConfig.useExistingGridUnlessRectangleSelected,
    'tma.grid.trustNondefaultExistingGrid': gridConfig.trustNondefaultExistingGrid,
    'tma.grid.autoInferLayout': gridConfig.autoInferLayout,
    'tma.grid.exportQc': gridConfig.exportQc,
    'tma.grid.defaultPixelSizeMicrons': gridConfig.defaultPixelSizeMicrons,
    'tma.detection.algorithmVersion': detectionConfig.algorithmVersion,
    'tma.detection.channelMode': detectionConfig.channelMode,
    'tma.detection.customChannels': detectionConfig.customChannels,
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
v4YHbowl7CAwufbOTm3t/wVOlR/Bb34BAA==
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
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
pg7KXeUiCUrqGGnb+X8AqnpP4OpxAQA=
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
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
        Dialogs.showErrorMessage('Skin TMA — technical detection validation failed',
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
    Dialogs.showInfoNotification('Skin TMA — detector updated',
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
            Dialogs.showErrorMessage('Skin TMA auto pipeline',
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
        Dialogs.showInfoNotification('Skin TMA — review required',
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
    Dialogs.showInfoNotification('Skin TMA — orientation review required',
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
    println '=== Skin TMA all-in-one INTEGRATION TEST COMPLETE — approvals are test-only ==='
else
    println '=== Skin TMA pipeline COMPLETE — grid and orientation are human-approved ==='
