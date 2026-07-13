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
/b/phycCQZgBAA==
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
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
pg7KXeUiCUrqGGnb+X8AqnpP4OpxAQA=
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
PU//Awh3Lvr1eQAA
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
if (!validateDetectionAgainstTechnicalReference('before_grid_approval')) return

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
