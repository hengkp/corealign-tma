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
        try { return input.getText('UTF-8') } finally { input.close() }
    }
}

String REQUIRED_DETECTION_ALGORITHM_VERSION = 'skin-tma-detect-2.2-nuclear-rescue'
boolean ALL_IN_ONE_INTEGRATION_TEST = 'true'.equalsIgnoreCase(
    System.getProperty('tma.allInOneAutoTest', 'false'))
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
File configFile = new File(System.getProperty('tma.config',
    new File(workflowDir, 'corealign.config.json').getAbsolutePath()))
if (!configFile.isFile()) {
    def starterConfig = [schemaVersion: 1, activeProfile: 'edit_me', profiles: [edit_me: [
        description: 'Starter profile created by the one-file runner; edit before using a new array.',
        grid: [rows: 18, columns: 7, coreDiameterMM: 0.6d, cropPaddingFactor: 1.75d,
            rowScheme: '1, 2, 3...', columnScheme: 'A, B, C...', showAdvancedDialog: true,
            useExistingGridUnlessRectangleSelected: true, trustNondefaultExistingGrid: true],
        detection: [algorithmVersion: 'generic-tma-detect-1', channelMode: 'nuclear',
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
    configFile.setText(configJson.toJson(starterConfig) + '\n', 'UTF-8')
    Dialogs.showInfoNotification('TMA config created',
        "Created ${configFile.getName()}. Edit the edit_me profile for this array, then run the Groovy file again.")
    println "Created starter config: ${configFile.getAbsolutePath()}"
    return
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

String profileCanonical = configJson.toJson(activeProfile)
String profileHash = MessageDigest.getInstance('SHA-256')
    .digest(profileCanonical.getBytes('UTF-8'))
    .collect { String.format('%02x', it & 0xff) }.join()
System.setProperty('tma.config.path', configFile.getAbsolutePath())
System.setProperty('tma.config.profile', profileName)
System.setProperty('tma.config.profileHash', profileHash)

def setProp = { String key, value ->
    if (value != null) System.setProperty(key, value instanceof List ?
        value.collect { it.toString() }.join(',') : value.toString())
}
[
    'tma.grid.rows': gridConfig.rows,
    'tma.grid.columns': gridConfig.columns,
    'tma.grid.coreDiameterMM': gridConfig.coreDiameterMM,
    'tma.grid.cropPaddingFactor': gridConfig.cropPaddingFactor,
    'tma.grid.rowScheme': gridConfig.rowScheme,
    'tma.grid.columnScheme': gridConfig.columnScheme,
    'tma.grid.showAdvancedDialog': gridConfig.showAdvancedDialog,
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

def step1 = new EmbeddedWorkflowScript(name: '01_build_tma_grid.groovy', payload: '''
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
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
H4sIAAAAAAACE+297XbbuLIo+N9PgfbJbkkxRctOnE7LdnIVWY417a8jKZ3kpH21aBGSuE2RapKy
pJ1orXmIecJ5kllV+CBAgpKcTs+9Z2b3XtsRyUKhABQKhUKhav/58x3ynHR7rVtySKqkMUvCahh5
NEhI/OAFpHfVIIMwojGJQ0KnnkujiReTaegFSUxm07kTufYOQSydWUCcYUIj4mC5UeS5ZOzE5J7S
gAwi6iTUJV5A/nN26yRjm7xbEpcOnZmfWCQZUxIPIm+aAKo4Cacx8YYkCMksdu59mmKE6iMa0yCx
gKgonO8PQn82CYjn0iDxkqUEiR6ppO7j2EmIlxA3pHEdXhByYJMOHXoBjbH6iI68MCDhkFBnMCZe
4HqPnjtzfOwBeKZTGkAd/tJmGA5t0ooTb+IkHAfvIscn8SwaOgNKghAfncAliROMaJDwsi+gdseN
iUOiMHESLwyqsTOkZO4FbjgnwyicIM4w8kZeACh9z6W89EubdKAU1uskspAXxaw3ocehEwHDEItn
GsQRHdmk6zzSmPhhHPs0jslw5vvViMahPwOiyO31e4sETuI9UnJz1ar22ufnJA5n0YBiHRZDRAh0
+qNH57FFBmGQOIOExGNKE4uw9+Sid3VpkWb3dwv7g/EBcb2IDrAmJ4rCeSxG7MoJgNbwkUaR51I+
aGeRMycOSbxgSZwg4D2374YJCQN1DLyYxJ5LYTwdNoJQZ0yBC2Iy8J04ZhjDiATOhJIkJLstWVZU
uwvDVI1mgBtQIo/apAcDw0EIVD73Ao5vGEZsTGSlJn68DhPJiFXoY9l7xIkoImHP+47vjYIJ8nsQ
JuTPmQNczsZj6i0oMJfjL2MvtgU6oK7Z/Z1EdBBGLmdv3lXECUY+hakjGQuxxCSiE8cLSBI5Awpz
DtDt7+x4k2kYQb1TmLW+d28PwskkDOxm6IdRLwz92AAT3v+TDpLYhiG+Yb/XQOFwUAbdxKHJw7L5
GdvtiTOit74T0DVAHfy3Q/+c0TgxwYWe3blpm+rxAH9szyMvoVFshxNq31y1bpeRM/Hcj/hWdoof
DjzRHTC+3oDGdpf9OHcGSRgtNdAhSIMktic0cez2FU0c10kcI0g4S6Denjcc8kpNULLSm6vWp6tL
XrUABdoXE9+ehC71bRrMJrF95k1oEHthcBO5KVIT5C2wRW85LUA3jbyJB1wY27dhjL/aQUJHSvcM
wok9CsORT+1RHAb2+zgM3s0831Vg/uk8OgvW6V7IBrd9o360nXliv3Nib9BNovCB5r4hH+benodB
knv5PnKmY28QH57lPnVAtkdeMLqAtS33GQm0382GQxpRF8ksgGkMYU3pRU4QwyjdTHNwIxpOsmAa
UOCF9tDzqX3u+TQ2f+omTuA6kdsMp8ubKcxsDS6mg1nkJUv7isaxM6Jn3kiZCQiS0EVid73J1Kdn
TkLPkaV2dvb3SfVH/QfIbqY0cpIwqg4jSsWKH3Mx6cWkZr8ikwnTN5rLZvsc1nr7x5Kx49IhGQxH
3QRGmJySr4T/fKBLS/weOr5/7wweSPUN6S7jhE7sEU1uo3BKo2RZRlABUyErgbQdJDmMXpCo6HZg
eUyiJflKIprMooDwqWJPnSiGeVPeVKGdhKyCcqUClQPGgZMMxqTcG0fhHLUkbxSEEXUraT2SiNWO
JPgsnAFwlmaXvV5LNivKqGa//98k/F0Y+jmy78PQp06Qo5vjecc+M5L5w9NoTuvvhQ80iLdgH5UA
yXYZBrLjqe8l5ZJVqtiD0PfpICFfiZfYSeRNyhU7CS/DOY2aTkzLFbKyh17gNnyffCU/eYntxa3J
NFnCl53Vzg4fu8Z14/Jzt93tn918vO42rm4vW+Q0HfJyKZk4NlPwURmwhepwFs6D2AFRULLIS7vm
VgTK1qfbm05va4R0AQJGQ3eA6GBG3HZav7dbH/tXjU/92/an1mWXIQP+z2Hi+tCVs8CFKC5Z5Nda
TdLV7Nzc9rvNxiaCQEXtDhxByVHass5Nr9Fr31z3ux9usY1bYBNaVHc2hXamiF8CYsGKndZ7wNtp
nbevW1et616/dd14d9k6Y8iBCw2oucoCywKoe60AZoZbskgSzWhKNcPdbTU6zYutSEa8XepEg3FK
75HaEQwljEqzdd1rdfrdi/Z5r3/eaTShg8gpm7Sb6rhyFk0aJDTqjr1hch45qNaXLFKzX9Ry1fXa
3e6HVv+q0Xnfvt6mCT0vjmf0yolGXoBtODg0t2FLzkiJ1ljkIN8znG2bN9fn7bPWdXMrxB1k32YY
DGFjOqDYD0iyYJNu4/dW//zD5WW/0+reXH5AZoStwBouiZ1Hej7z/Y7coN0GI8kjGubrRq/9e6t/
c9Xq455tA9Zr3FLcTCionCUUVHEWJU6Z1ln/6sNlr928aFxfty6VCgSbFFfCNq3u1cxPvMHYCQLq
52rkfX970+315Rzt3Vy2Oo3rZqt/1nq/vvunYZx0+DzthT6NnGBAzyj00sFhKos09MA37V6rg09r
pZKC/MpZtBNQbmDLUbLIYWWHLwW9VrfXb950Wv3z9mWv1SGnJoUihzyhcdIMI3ru+QmNShYplSp8
OZDjcNvo9NqNS4Yd6iGn5KdsfenygG0FcdHs3XTWNcwJRjPfiboUdi3Qml8O5VDcfACh0Glfv++3
r6+xOWv6P5wlNOp4wagdBNiKmv1SmakKMvy5JbKbGeuSA7umIONCpHfRaXUvbi7Ptpn3CcqR3jii
8Tj0XTHzaxmZ+MQpXzTZRat/2xpX+JDBc/ia82z3otXq9Xtt0ULjQKLNpedhmw4Pa2rJy8a71mX/
YlPhS+ee+hfUG40T0AZeMhTNm+teo9nrM1QwYT62z3oX/dtPa/BxQ1AX0F45i4+em4xhEGu1Ws2I
tn29qX0aSi/gLT0QLT1rnTc+XPb67zvts37zJqdigBHRZhZD5PJ0ZjUuL28+9hvX10IonDcuL981
mr/1P7Z7FzcfGM5NUs7x/XDekJapc67uffSScThL3keem0o6Li1uOu3WNa+zcfn+ptPuXVz1f291
urj4KhqkobZRGHnJePI7jWJcbJG6EuymqtISxm261Rf2L9WISeDqRBHBJUlK80OnA/rKbefmvH3Z
6l80uhdrhNcAGHVkT6MQ9qQXTjxmQgsV5n/GYUBOSUDnRNn4lyt2DGhokixvIy9IUMu2mX24XNkR
lJy1eq0mrDXNy0a3279uXAFTlIRFmpRdmtBBQt1KSRRpdDo3HzPwqUVP2hkl/M3vrU6nfdbSi6zp
bWHxQxPVtTMBxivlbYZKd4Iq8uRqQGW9yVfFRhY7IAqnam18Il3edPo3v5FTklrm7KkzeOi8f1eu
WeTwoGaRgwM57wCciTlzkcOjI4scvK5ZRCty3mhfFhR4eWSRX2rwf7WAaL+50MHrI4v8emSRw6Oj
yo+3PqDhCMy+s+nfYVNAk8+ZkzjklIxo0pxFEQ2StngL6++QlBWoUxLMfL9CvuJonnmOH45iOx6H
81YUhRG31pRL6lFM76oBjHYdsurAnhxOaWCDaYXZvKVdHg8A7FJF2YDurHb49I3X6R5x4iQzkIil
zofr6/b1e+AraCLarSNymrYV5EAX35bZTB97NILtxTILdSE+cECh8t3/E3bRDDPACUtouQJPTQYV
64VgEkApBYe+bx7RBGBwSwzc501GH7VacPXhKpE3GV1oH9l6BwJoClLJD0jp9PSUdBM6JYd17Wgs
PWE4PT0tSfhdHPY6efa1qGGMvNVuWkRaY2MoBxSvyIL9uliR6UIBFb1Cys++ql1ix96/AGsFMKgf
VrtsAF1n6rXdBTklX+7whaT/yokeaKR9i0b3HepmX72PKA2yL9/5Myrf4ctgNvCpE0kziTSZ5NmN
g/JGMSgh4oBgaxzSwThOLDdy/rSSsDqNQisJ4W+8TEILy3sWR8NXHNmwbSiQwEYaBlPXGh86C2t6
9MJ6HY7dkfXgvfrFCqLhoTUOD6zJZHpgPaD2H1iDZRKK31MnGDzA3+rgwXqIEotOB87EotWB447B
wmxR8YuTHVF3G4LZ0GxN7Q8hbQQDvyVxyCQZ8kgp32scNepxXhA3giVCq2Y1OJCzyKUXJyRhlXOb
mvhOTkkZT+3e1tkmSTWWqdY3Vtx2AjBi4gNYd3/CX4oR7eefCdcsvSAu41dhWdMl0GUYPsTXjOuy
FGcNf5kGllmrtElSKaqiJU+Sv6uSzEzAajShAWfcoJO2A5cuyFfe497T+xnWt4L+wZIVsdbBf0IU
nZwQT75URIl8vyrELTuGYYeRK+gDOa00EgzCL0sNF4A/lBhlImnkqMJVqXC1k0pt/sueBd6fM+h2
o/zOv0wLqEJd/tY+KwJeedJAUnGfPqQAqOcISuW8Ei2VS+rHRgd0izq5DsVE2D9r3LYF/5NhOAtc
m8C2CRgQ7elJSBzfJ0EYVBvnApKfIOFenrC9AMihEtudPYHVxdj+VMzooBxFSQxIyiVnWKpU8pwM
Y1bQB+lQlmu2fWJYvLE+L064vmgYSwVd0fINJZWhUUpoo8epEeCcGXRoyS35ukQ5ySN6SYWRDGVT
bYYZc+Vggu4iei5V6tSe+uIld2Sl6k7pxmtM/SmNyATrQVyGHtyA9sefe86S6SwhwxB2vX/H3mMa
heA9cebBQgS7ih08qkNuVL6znckte1CEtvj8EysMsou/wn2+A6qy+FiRM0WrNAfPfsEWCA+w4Pi6
XNlZrTvvY7NGRatWmbZItAoMDR8iT9PdP3TacbmCJ2S4FVDbxDYG3cGY4tbg9JSUAEWJS3XRGwKt
aHCmd+mcYGM4mNJQ3kYUAd/fTnNtJrPLLKaRPQ4ntFSxSIn5UFV7V40qO3krpRYU3Il1EzpZs9Vi
O5J0k8rAee99+5biEKeS6WxnjGSsgzOPwPOWlBCuROpp27LQGjkK1pSCiE59Z0Abvl/eL7/1Kn+A
b84fduIN957tM7PT5oJf/mej+l9O9V+16q92v3q3BwX7pUwPqDJNRcmbwfYa2N3vnJhmBi0dSouA
mtx3ZknYZ7pyXw4S7qzBEpcpXYxm6k2p7wW0j8Vg9CVpDJ0znUbho+NDcRWlqMYiJQZC3T5aQcFA
V/obbC4XKI7jv8WHQ5jDboaoGIf3/9T8FFK9iqnH4f0/BYuhJa1ceavw2cZJm0WHUlas9kgQ82B7
OkU/joIRTTqhxw5eG4F75jkTmtAoSwxuNEOQm5yCzk2bE8CPKAao24UemmBokESh537KgCzzIJ91
kMiZKyRcgVfdxFmUeaF3oN7F3BBjEe2tsMBo+7cvg0WdDBYWGSzrZLC0VPx19cGSPYUn/uAxqtHy
XPEYuJO7Lt+ZTGsH2Fec/kfRYSARHskJqcGRoSAHHpSvb5hzg/h6IL7y50dRD/MC9v5FO47rOdyF
hFfoiArnY5i4ZYecnJIqdtxtu0IcsndK4NgSXWLxpQ79hiiwVSMsJ8cR5KALaC88o7ArMRPDn10x
homALjsVtX4XqT14zXrJBWJfvJK9JIHekBSkqoFw2lw5JPEjEvTo+DOqjQW+kAZUXq5UUver0BaE
Uxx4JII43eCXdksVWN7UV1b+1R9BqVLJzsDSbonskVisKoDLIqVdwLgHH9VWxaJV42Tit+KBM6Xf
0TjBTaaGwX8pKT8DKT87k+lxyfT5BD/7ifnrG/w6KviKrfz5z1lo/r5b2oXv//HiV/jMW828VF9k
pxcfLe7EWr4M4QTW/tC1SOkf9gvwBHgU7nUM5OBJGA40DPHYOTx6pVpRtM6/Xyb0yx1x0T0SmF11
lwTZ1A7iBBwIyqXuRaN6ePSqVLEZNBs0lF/LhMbl0ofeefV1SZdfDFTZfOiUl/5RO1yULNBZfya1
xXAInl7/DL2gLNzO0A+5kYQTb4CNwNU9caIRONbzFgHHgmFarDv4Naui2pMH14tizjcMzWSqKgrm
cpaCj9vX90jJTibTKkyEDx/aZ3bkBG44gZ9CfCeTKZw39OgiKXPyLCJ6yLA6oqerPQkfaRmKJiHT
CmXl6Yu846vdad1eNpqtfutTu9trX79P1wImG3IFGr2bq3azf3Xze+spa/APJbKSmnpA7DlBGHgD
x4cTYhzokbpy+3hN5ZR82Z3D2nn67OsIxgOA+WK6+jbG9VP9JFbU1e4dYsIPvasG+Jgwc0POOAJn
qxnjSKo54L0RTXX4GxZ++I819+SEfPEsWStnPjTQWEK2lPMKS0Uf/gLAzwCYfkyJr/AavfjKi2OU
tHdsSpa+lSqKQZDPcKSVA8CCIYTfxHmgUu/MG7fhEGoAx6FrtUWJwIb7QFzyMwRY+Ds0yEKUFV2v
nNJoABeqfCrc+7ERQz90EpCYTuJIn+GpyquPjh8zixSubnCXDA7RFJ44sEjZC5IKezP0wzAqAz7b
p8EoGZN9clhDDxFGEhj6ynhoR05J7Zh45IQo4PBi7xQrUZuLhJJHtHglzhfvTtvvg+5WqzBaT07I
Y8bQC+81y1ZGwcPvcRhJgwpQx0x8acOgqTWL//YChpQZ/EiVHPAvEcyBcvZjhTwn04q+lqT9Z9dc
C2n44rmLO+LEfCAk84VJPJMeTrlxUzUPtd9PT6FPsqou68iJF/xOTsk5PNi3N902Ohe2r8/b1+3e
ZxXQWaSA1633DQPgxgHd23vKUJ4gdRVB42NupIGmiqAsO9b4+uSU4+CtL/MOxUJyiIN3Hirvh0ev
xLsvd2TsoeoA6yi8QKA7fU8UjGD2YE1VrOi7+gFA770gw2VeUGZ0pTzFOa9c5v3FK62QfUYLcFda
qKJIXmjLl3svuNvbU7rJD4MRScIEj6MUCtVGxrMJuKefklph27BG3ioOvndKPPKcVcvHFSubv5OY
Uvy5d9CheA5Xk0N0T+PkHfZRho6E0ZGkdCR6787fATVISaJzGBCDUwM0GS+Y0XShQlrBt5b1TpXM
3+lFz3nR+4g6D/ITNmbvlCSi7UqNom3QWoTbV5GKr1BnmXdiFeFgdOfnWUCYNfN3MN6Co+fn8DR5
B0xxXlF/G+eNE6mdBP/JXn/UXqc9n8j3q/x6iZN0j5QF+L6kTOVI8pxxqhBoEXXc7p8zuBup7AXA
MCBNE/In3PuUD668iiClXpCQjKRmS9BgAT0JNzr3CXMUFuBLI/iyCHyugw+o55cBMIUYr4cYkvIC
TA8aewK/LOTjQnJ4KsmW2TJjKLOUj0tDmQXZI3PyBr1lKkg5+s1UeVUM7R4ZM5CLCpKO3jNVjnme
WdrnrBHjzOtxJbVDsXuZ5JRo9zS5E6DccWVNxJYymBZZWGRpkbmG2JuMUkM0sAzDX+YV6pYlj7nr
eJORxe/CfqoDVvb7c50s1Qrrym9pP7oHn0Z+tOKFzIVBuyPIsIOufcJvfL1JDzBVy5wTJ9zJCpX0
Dj6XdZZiQKonk8pO6VfpyiSXLZABIGlSKekEbqwVup5N3sFLXkyoC+FMLG3sTXCnfb6fDU2fRROV
S0teQt6ckho7kiEnnIIVbkBg36FtOVKyutjhMaxmNTbaFhl4FlSc24HcOzFayOFkKqe8lu9nQ3Sm
FhNVKTj2RuO1hX799ShfKgajg8bktPqrazFsVUlNWs64JOYWeV3huZ8NVX1HFc75CsR/4QwWU5j7
ZcTEpUz8Z5SUpc7AlMjyo0oq2WetUhQCgwQPZ4m6yemM7s2MrwrcecrbJublnzZwruC6iLrYNdrk
K+NUk4fXOhuj+8WaMuLYWi91j0a5wkL8TL2ywyxJ3I08oi5uG2B2UdemjxQvUHoJKgJ2bcg7U8Aj
aaIEPhSXwbN3gf/nn5Wy+kYPu8dX9BTZfvFyRagfU1W3VFBXOAospUGoFeZx7mhK75WzME+qiLp8
UmknB6PCAliTqch9YREgSpaQYjaVZhqzlplc0d7Zvc+3sG3p9Tvv32X2oUs2f2G5HR+TZV5Jj0Lg
9yV5Tub52b9gpWGBnx+TRX72CxEBWPaUJV9i15UHtoE8PDpSDP841fnpBvQ3iIN9HBJV2RcIR09E
iOPBUI4KUN4/ESWMF8N4b8AIwRBimoC/OFv2yxHs2g9eVcg3Uh7B79fw835bucVuQeBAN5A2gwSL
o4GV7uDw3CbVNhdtEAJS92xPNHNdEZ/F0UCRgBbhz0LsbWbBNIABDhr0C1OaxAcuO0fQXehtX8a/
9rvLRvM38W3owQXCQcJXVcCiUsWfdbMcYtSCJJT1kAn2b63PQGyrc3tziZdZLJKB+L1x+aGlw/Tf
tZsf3rWbT6mk07o+a8EFsoIK2Pf+f35oXLZ7n5+CuXENd+vajW4xcgnSv7kWqBk3lSWTcO5AthAw
buTMJRNgt9csxQFkZLtePA2z7qsKy8Kx5la8auBNZm6EDcbtQl2Vcfeiq+lSP2DQ6ov1zCvmrGF7
xWY/0rXlDouXWBaUWKhE1wpp5EUtsuC0LTeUE22RBZeVyuZJjZDs7/+S+ZsSsJbZVDqZFIW9H2+n
/LUtS+LdmgeqBJia0qiKV7NkqCEvSELClBkZbEg4es7ZhTh2YwWwAX9PwT4OGNECT1qwNxAFvJhL
bTeNjRWDxyZchALDqUtidisfsOHmjkfCSkmMmQXBi1lt1MX4THDEltAAzKhT3wmcSN6nQReWD+3r
3msEhF8HrzBAE6+KujJwlr/ENpGIxlM46ktCcg8Bx+gULHnJmAIuCP1Ck8gbQJglFvmFzGIak3tv
MLv3BtBlNJqGPnagRSZg1xed8ujFEBGL+RDZ6emg+VK1SUpg1C4eu8DKH+BsXONUOWKxM0SXxokX
sPHWncdZbTKKkO4hJt6in6B2oi30Yi/+8BrKZLGckhKOSCkLffBqDfjBq5LUoH9C1D//TH7CUqnO
kMDxCc7wtu/TkeM3otEMYzEsBhRP8cqaWrLLe56olymVIGmso2PGQfuMjGPm4gy3hHRSV7uV3PZc
GzDVLmDYseOBkjAtnCqijXV6kN6qshj+nP9+TE7IwZre6EJj064odVkQON5MfjkNIv4FIdrr8CBK
4C7ptoxcy+SyMjZ9zW0Mn7RyoZFIWZ5g8n8qWgrmQvjLjthqJcug/1yEfrwF+qURvVyIeNAvvhjp
4cYYa0gTmhYWjIVZY3TK4GNkIn6cCsR8SWJlU39RLCegYX1iwbrOyiV28a1WwpuiRig8u1VERXqe
ayzDIr4AavarCDf7qoc0K+uP9qfPzf/qrSmNUgglwlsiJ6PNhH498+bg1RpEXe9f9BM6cGbioTHL
7oaSn7+75H8ZSx5sKtY0FhPTdVPp3qZKWdiofOF33qgVwEJTFpGZzhuX3RaU2+RAre2iB2wXPSAn
UsQck4G+mVZr5+KvfVYWNynrtfqzr4PVLipHg8q6Usiqm6+rfhncaYy9CS03bt7SCPumuEslolUq
BViAQi4EtEiBZZODDQOHygX1HVBE6CMtC8oqBmAgI/Kp80jdMo9aINcHHOEUtBlOIMxljGvDv3zv
vrTViGbqc7NionGPkW4odxPX3FJSJeqUZALq4WrJdvlCGK7Zn0mzCwgrrq1wmWBQ6d997rX67zuN
z6Ru+vyhewHhpABAP7oFN7FbGrFhT2s4IHVyqEJ+uROLagcNSeJkeX6XgYKVRgcBgXGXtzltM1tE
p3IApjIWW8tkV1WKMHTEoYaKMXe6kaN1rXVNHqCu04yUQwN2RHRgkYGl9GneZK6RDIxowlErxrHK
dQLfrPCp/vSOBPBcLMmy5Hgr/5Ezp2pQUf+DbWOSivg22yHpZOqluHMkbIoEJ+ArVEyeE/6PztZ3
OVNgOBxC2F1x9LhuvAFj4ZDrlBaNOep2FtfB9sjSyuFB9sG9LhtSPonyHWay2DIKF2YKRYu5N6/A
/GVxZwTFfv3Cumdv7w4sINCXFeEOzD1CzdWIfUxlDRqO580bsI4WYVut4WMhl51HynxcBxarLmdn
ZRtsX11xtPVh4IcsjuHmBUGz2aqLgRczj1TYuqnvmS9KuQIuXcJahsdY52F0y2NPF5jMYLQmIsKg
6haVMSSdnCpgP/+ctX1p36UPURwNdgrPC72gnGJMfSBIxsq2ozhdFALrllqxxSpwt2N7jEz7nmvn
fuJUbhsMsgt0FH/9tOVJprL/zoZpo7GO9dETjHEiEP36OPRoObuPYMggbD74zaPljIcAh7jlgMz3
qAinzm9Y38OYO9HSJuS22cBl0PdyUe95iPtjZpzzRhBOH/DB45RGED/fG0CYOUEPWOLGYUwDbtPj
t4K5jy5JQsgwkN4kRntcDwOxJ5EDrYDYEFgZb2fkJGOKAdgDMqER9ZdkEj6COcrh7U6xQ0ggJivC
CE6x3/E23g4cg7yIRvfbHP9IZ43RfaGnBvu05WE3ozqg6ERc6IgxH3sJLQTaSrV62gGlWON4Y8TR
XMV8WikXIjiyg5Xo/Nx8CpldsQxw9+KSTxGAJ85ec6enaV/CWaPqpa44To4scp85f5S9qxbzgrSY
F2SLqWsZ55NM4EG1/tfojKF57ZZTYkG41uzXh652+I1EqdgMx+CScDgLf/1aR8BP6GUL5A9tf5Qu
QZqJTNjAmBsJWLWE2QqvxKXGRazlhBxo9+jwGqNqwP1yJyY/1zP56yD1Imffm+EsSLJ+oOwTmPRq
SA57/syfnzAHhNPgElloD4JCuqJp3zFRBDoAXCjoFio66Ym9IM8BdA+qfw5/3rAhes4O7vPep5sZ
HvDqTP/mNMuHJk2WgTBuh9CyBQA4GNxTOP/1E3os8mYXwHxGD0UTzMo4laBBKiOckBe1Su5yrGAL
vNIqiNlXidbBlhLsswEMhQ5fG7qZqwyHBs1ItzuTfXJwVFPmBOzc8cpZhNckvhxYtTvrS5X9U7MO
8G8V/jmw2F98qLKnKj6izz/HlAZ8YGl55NULISdwnenOJnJ60Kn3SX0onihqs/mcAX8x/e2aZUSH
xImSK7/GA6aYqX9KOfTbN10+n2QkY8HEESdHghjwbPciESrKVd0RVbIWYi67X2p3sPtV2mIssBSy
xP1ysLEAZ2TRuqXsgMwWcpXvEIG3oLnSN3oUoBd/5jKJ1oPV7Nqyx4O4GxAy7oLDA4b5efojPf+w
D49cK7P67hPmDqRj5TwMV78WdSk8LLKsSylh8Trr/F+9Z1Ju3zvlD9p35H3wjBS4YXE1w6FsKi+L
4bKCiREvbvGckINDZE5J0ckpya+CSl/SAY+bA8JKFtMhlgzicxHEABmUTe0B8h7/vViqs5yTyr1v
p9p9P2XRmtrgE08HC+MKObWX+DX1MIfa9+ADZwtc0OBPCrFcZiHYaqfgWBpxLJXehnr2T9MeOEa8
mTcL/Y0m8iHJEVhogF4oq1169+KBmCGKzywyMvrMQilQCpZ4e0J52sPcCeQ51o1/K5rG5TuTe9eB
68xlRsEeVlbhp3x50MMUtJoHBZ4TKE9OyQGtvipmLr43a2D6p1OmivA56iROcFg+lKRbJG2TiiIA
lmChCWIvKKsYdTjpZzMI42KwR+R2vHWRcA4TX5DL8YJFwrkL2hoAHzzCiAXQvY9LGZ7hK6ctWByz
2qvBUle+Yzp1WKh2bWgfJcpHhhI6OCMbUftScTmBF4dJFE6hJuHBKEeiSjL8woeyUiH7GV06dRoX
hfcKC2sU0EcWH1ypXxc++4TFW9BEgwwrrhSr2S9x2CVGkHYvkDcEiNJ1z9lpNElVPpTt5LnSJ5mu
YuyWjYBRVhgvWFokEC5awnkEw9HinWNJ9JvTTBj1t6QUPpRIPT1K16HzQdzfkhIzCEIsotIsGNAI
Yj2U9JshrPI6J8JijaizfyyForryO90msbVbpL+oa/uWfVLmgTm4Vi+Ue0s9JxyHbp2UotF9Xyzq
/enAgSwPYiqJvq4r/W7lYxzeIk/UicYbd9xi1KUj8G0RZhE69ZIx9T3H5+4n6L01CIMAw2xDMq9p
GFDAQzDDnBtOvMAJ0GBE4SKco4GBbWfC3MHuZxDIE0wyDhn54T1UgZVWWQI7aZGZj2mA3ltw6ghK
hEvvIwh3HBEvBqco8LXBiG7VqbiU7MU+kgteWTwzpG7WaQqK/m3X+bdd57+BXYdOvadYdX59+b+X
VWd7o04KRafev+0+/7b7aORrc/PNqTYtMAhqAZX/+9iNUv5+9GIvwStVZh7/ckf+nFE5TzCKQGrX
SVdeLawG2JBY7NcqO8g+sAj84v/ACx12KWCrKQgrdiBhJY/HFOmtHbNfeFMRfmUuO4HlAcYCPqEp
hjeVvTDwLi6W1EHcoNB4vuYZgN3wBV6zo2xAIz9quLNjzwKqqEJC1f7TTXGc7onjhfpafa/tlZVI
Zkj5CZJttFnheLA2AOjeXt4dAuvxIHBukLjeY3meX1qRLMBVJUv9EpnSpCkKl9RCgpuRpXFKDdh0
PYZugJ314hh6AX4tj7ET8CVEHMEv/BM84/dl+n1pduF4YJzyQE7I62PyYPaOQFVIEu26iy8Pdxbb
rSHZrrv88nBnlAUB3oYH9goWIAvm+HMpXy7h5bhAUsq6cY0R8jJYGGsCXg68O/QOF+wWeHdF3h4K
SJHENHF14G3thqGHqR1w4XVwaGisZkWKF2SfDbzFrUbxUrzJlmA2o8zGE/iCwzMTEGwSB4vcNWhm
ZMoWXi61wkssvMwXxpkGLJavapkFNhtttjfcaD38HUacJxpyjMacrCnJZNvJ1abZG1gJZu751SVv
UU6ROlHsD8JmAGsSQOekYc4WMl5Ow6QsbTFWanxZaw/RvFtCjM3BRvE5hDp/eQSWY8UwwF/X4HWB
ceHQVTpbWfjAIIyo64KpITAoFZFB6YAZrSBWKL7CaKEZ56/BAt6CeYt3VV38kGYW8eZQL+mYN9xK
R9aV3xbrjDr75y6bQyDdR+cDTKWaCESXYDEylAITZ8Fy3bDelnGfxH4ELwcMQnGeoZTMZo/F8CsV
RbqkuNkIsvOlrA2N425E1OlAW3GFlhXKeM+MJVOzjIRJkQsXphx33bN4iTqYkYozjzn2bkeEwucq
PQuVmCoLS6O/zcgNpezSWFZ9u401USJE05j/I1vDtnzb0s92huso3t8nDU4d3CT8F3X4hlVYjpgx
hgydKL2kJ+xEJAwoCYcJDymwv08m1AmY0xG79AdpzUT0VeKQYeQwCxWickgAhpX7kGXYvGqQ6SwY
jG2B7CwkQZiQ0QyMJbHn0yDxl3XpGOThjTyohqVnJdwxEKxWtnaVDC9zTX3KY9N48YM6JCnrQzgT
+6Dmkp9/TuM65XgToY6OXPQazIw0fvvlpfs9ZnrBqEsjp4qvZu5eaiz6d1v4DVOqwOJvmEA/8ATA
iagjrLJpZRvlkdleu8EYr9W1DyQeHn2XMV6xs+tGeUa+snoqbSi20rNSij5RwAf/NttnzPZbMUWB
EV/6R/blgszs+Wndebt+ZqCsvFE/5V8rL3+kmKob3lnF8qqef2WAZvKrnn2htD8nQ+uGd3eptzYz
1cuULkL8/dtY/29j/d9trN9gnT9y/5LT5a+1/4Xm+dfabjLne5la1/nVgqeb1z2WD9Ncnk69j8I9
aAvPM2SboOlFA6b6/Dew34tBjTSzQ86iX8kZ56OIW/kLzfusHzJG9r/T6s/G8G/35lR6gEWk4oo0
U5E3ka8q24XCASyEGRfAfKMFj8I6AbNHuF5Mw3m5vNYdDpTEF4fIwoc5BzZ+/l7Ymemc2DsldG76
nvdPM8Pl/dMycKvNglBRiNOpB3EapV1JExDK3SMBnjle4YAn5BBNs6lsOCGv+QvefuENV5jiZu1p
DMO6+TgmD6d52UlyCr3sjBBPcl1KrZOoT12z+8rf64OER4YqKtwBvTja3JGGfUo6PCaHIX4/SFgK
RSGt9ufkwP5lm72NrP25hvfJ247HpUUe/7+z7SjeeohnS99NSNHUF24zJV2P3qhEe6ravFmJfmLY
TqEMswzNReEzebJHvQhz7Skqks/pqJskZgMmoXMqHaME5GOv3e1+aPV7F51W9+Lm8oylg9JooFOv
K3ykFR0eUfCbpnfFAc5VMBbZVXXKYoiZ+spAMUwrb7eI85oqTwxetsqgakog7X4PWkpkd0ivY7WH
DLh4Sm0wSR0x3ffwyN35Dt31B+isuclVCsI+mxYlOcO4Y7MywVQ1NZ1O8PZObYj0S7mNwmGaLY9/
vO53W83eTad7pxdho7RNER6cAbbmsXKkn4cya9+qHrxOMe/OJlndvDubaCr1OkVcK86eN6j2/40U
8ifr45vUcXG4X6B5a8OVUfuEYTn1heJzH3J3vEmnZY4oVuC7NXYc4M1KO477dnq76F3QBoT6nu/K
gGv0r1GjV+Qea29WsBVo9AWdhIIJtJ0NKr3qYP0KTlqF3pqhp5qlp/I9Krvo6i20dtHd6xT3dZuI
lanDT8jNh16r04fL5/329XWrA7o2jIT6AX8qHG4aW6GCKRqXC4HwF/k5w0Ax5QD7ac5UqM6h2JRI
gRXeJ2W9MPSLFJn52mPgsvQ7opaPsLrozQOH5wcvSM+D4IRpSiNvOqYRrJ/cBiv8n21C+KBD6MYM
pnAGgW3Gjj9kLs5xEoXByF9a3E8ooqAngmaKyc0nYTQdh344WoLIyyJjOQViMoeL8MR1UEfjuazR
q/neg7KQHMxfEj+c28Y5iRZYRcq/wNN3NECBfTrAP4UiRlsPv8QYPF5FmSW6yXgonMWpsi58s8kE
Ml6B+/dsMOYdNKUDbwhhL9ELPIvt3gvgtDPr2s0P/OHQzncS6PJJGCdwpZ868SyiMRmH8ywuH3Kx
qaEL5DGiF1t4RDgfUwgCmsRK5AIYcgh7ENuZVCfKms+7JevCkWYKYlqcu7hj6UsKeo/pBV/iO2Vi
rwwB1As36GJFftI2XdkHp+MukeMe8sCwh/whCpj+zFWx/X1yG3kThwWHpUHC/ShmMRs9NWIFPN/P
/IdqPkCEwAUgnH8ibzBOh7aKQytK2IR8CHzvAVKdQnI3CCIPbhZOMIK4FAJbTCGaIsSPhQgVmFs3
Jk5AQEBElAxm0SN1FfZxogHhp9hJOMVgsZAaIRb4gNm4gGF5mhcYrMNfknEYef+CM2/fNltS3rCk
Gakm9uYULhayV3y+vxGHoDmXKXaIlRpQcKEy2EbyZZZqmc8bytCp1+RWFV6DbjbJgC4l6Od1oI8c
ZRPtLLwpOSCOrLlMgUyeYT/C9mK0v6CyUzvKqyQySRVwZRPCFLOIaqrRRc5TNdaO2tPGS7cbLDmv
akW3dbe16Lw6Mpcf8Fbc0sDxMWNFpnVvuLfD23yENXFwDftLUN71khU8JofsLKSe5tnLVb+9YQnO
ynVq8zpEhvgTwmj7+eeMIQnGd53G+WS7lZ6f9C/YsHSXxr9kzzLcQ/8Btq3vsnOxU4A+SG5p5eqz
zi3dbTBxm/b53UkYQn7D7bf5W5RIXeW5nzw5SRXQYxIbN8SMnsZgkHUw14RF8fe5uo3fMTpiV1+i
J/YpeWl0xUaKUQ+PyR55IHuqEv2P9MF42f9BCE+6mJar3Dmp/ICOq4e4k0kfKgYbJzRs7zSrbcYQ
F2H+YNC8OHxGDTPBz+UOTfmwynh3sFEFPe5UIWg/e69fZQEGnFKjAZsUNmYjU+tDsV5JXW12cuPN
yqjVmstgxiUEYrHnM2XSLNNeQlZqflK9NgrCKS2MuL7ktmXqV3GtGDbfRzW3crdjWOCCBPJ2Sbxo
zkM35YNDN+/BKNeDFLqqEQgrgvxmaIbc6+QNw6lnbYHnJBgncWsE5s4XNbZTeoXcrLemoguH+G8V
C1w7Sfko3305Rt5Pwc2yhCNdNyY5hlc6PnX+UaYDKkZyuNhAAHMozdjTR+m5Qo06dUTKzvx5P3Tf
wabuQ0OAJOyNIBIw3lUE4jgbOjkWsyfect4Aoi53cFerUGGmvxxh5HrzZIpz0wgOzfRpJKbl9jhy
U3EagbMtV5BSqvkRgMEzO4VRwhYwSixs0nN+sACzUQLv5P2XG3Nnabx3udU0AEB5cda5j8uQBvRe
pmzE2tSTB9fSbD1uRj0nbwgmeNBYQ5KpL0wa+bJA/rxcuzRR3LV6lyqVav232RH0CIWRMp4goV7i
y5SSSjqJwimaTajwbIUUq9nAVGmXiawCuQWfEyWwCabPDytqGodc0zjMaRpIEmoZkrCtlA1lQoc4
bBolmaxgGSLTcpkrF+mIsI0ysIZeWBkbhJF3spnBRhmh9MwrVXhFWsmdHLQYz/RlZcd04C2GjNmD
9xXmfm4yqRbuN5zUBft7thX/DY/EueGmP2XKYX/o+P69M3goiTAXHQphstE04wWu9+i5kESHG5L4
XQAvwFjWDhlFHtiK/DQ/0SR0veGSp+ABfGi+AesPwPJQGAFecfBFcTAXkYhO8B4Dy1M0pMmSOMFg
HEbHxJkl4cRJvAHmPWLgk5B5cYOtCu5B0MXUwSwWaAIeODJbkTNz4cqhzdMiQ+OaYUTfs8xCSzU9
MiermWZJFm+WSma7+ZnnTCjQIDLY6bBAzakG95w0Oze3yuE5XgPutN5D3NxO67x93bpqXff6revG
u8vWWd6oCHe3FOLgBpdCGbS/67m0rpKQucqlsBAz0ih2SixTsnjPdsfeMLldcNukgcm6UyeA70oL
Ff6CIe1HoVe6U9IXj9gqkCalLiutUVqidxrvoG6r0WlesN5L6WlcNy4/d9vd/tnNx+tu4+r2srWl
PwUjx+YZrzTHCqFdFnq7pq4RNfuXwzSq1E/ltAw3LlY0NOv9B9hWEDVr3R9bIbXQMTsDozmXqGYs
NDimHU+qoihP5gxy1NCreTxLFc8yi+fzFngmzgIk8CzOTRTsjDUI8rfW03T36el8bDian3gBAMwt
+AVfx5AubAHv4G7/xFl8xp9//dCe99KPO7bnw7dVJIy0a5+nv787JkbqdXNymvJzAbqBIcpFvO5M
PV53ks7HK43+7gWfIDPhsRi1VP12FvglW/xzpvhncPU/FiOtFv+sXQJY5W6gskvkzEMTaz9h5LHH
z+zx898st2u63GaTrs+1iydLb4Yt9ZTDhRGO/frpwVVmu4VniC6KEV1ygO4cs2MM7CsQZpskgMC2
zGL7zLF9fgI2nDAKgdW013eyE1WpOQXTHE9j6MNtHGJSadblRYwL2FXjU7/ZgmD5/e5F+7zXP+80
mhAzX7tGibU2md5yyml4I3GniRYUMNixcbjseZY2WqnM34NW7PNSz3XsWrGlUmzJ2r2mmOgx7YPB
e/ojrBw4gapsAu2Rgy2GlxW+4IU/s8Kfn1QY2F6d9JwecXZ0ofvj8ompFlCnqIpTjjL3lbxqdN63
eabbHB64fVPIIVJFTCVDhs3iB+V0e7Dppp15Jdi4ldaq2Wd+SRXyPGeH5CYtNvT5M8DMqn4kXRez
+yyVob99U2k6IVJFzm2rJDkZOQi7rPChtJOVwul8YGI4ZXRVDotf1o5JAKs72OyOLSN9sVHZu5JC
9KZPigDGldaJ+tD9/VQay21Z9Uf9t4PeKOBjQn2mEfPj9wD2eXC0F3kuZakjflyluPtKJs572PGd
krFHIycajJegsfauGvC6XGF5m0NwVMHATXjBLvLcZsgdKuE7vEBjdSecczjtNQeG4uAawKv8iQcG
gDg1/B2vGvri0ouTckWxgis+doXQLPA1Cw9x5cUxpmQlKyGFRTuKyrM0JWnrFDj4V1X1p5EXJH5A
dj/E4i4/FKyTZ18zpYTyvyIR9M4iD8Hxrsgg9ONdqR0DPt6hBSjTsOk00nOoibJ3Kkgz9DMg0M67
tGswlvJHLxm3A5dCKA14axFPDa2MWyvMbax28c8/p/32Brzpvhrvb3oiYpQAzl/zhFNDj/xDojO5
uZ4yf5KInMhOqvA++BLdZe+bgbooywx4Gaycd8qXgcmPSfCCytjlmm2fyCqViCS8ci+5Yz5NK90g
v0cOshg5i0mMSJCOEUjbjBEHRCNUmTTfvpGftCrVgC3pGJ15jh+OYjseh/OPTgRuf9dhAt5uDsuT
25glYZX5OAGvl3Q1dhfnM6TNpYCbMfqzrxpRKxRp7Psg9GeTQAMB2lbHPDE0ZQakmEycJQkgdhtd
JJHD413YuyKR5IpQP6bYA43Ly5uP/cb19U2P5T46b1xevms0f+t/bPcubj5ALsP2WVYQaDKvEQQ8
6/fNPRgmYiW7nzIwmIEW8mPeDMteUoHROWv1Ws1e66zfvGx0u/3rxlVLDUsDhx7kK3Escp8NyY/S
FUK6YqpHr4lLViNwxUJddgypCe/XwN/nZ9SyOQHr18CBsBQnp2/I4N4e6FHQ+LKMkD8xnzz8DdZU
B+JeiGKL7JZMkZZnrfPGh0vW0f3mzWVXF5TXoZSSLH+0Tc4d3wfpCcZOCG3y7CsTQ+x4aEVKz74a
unZVIo4cqhjZCuAyla/AYWrqO5Lb7F3BL4wHVJ5vRVEYXdE4hhRWa5i9dB2SWYwRY2Rb5k7M2/NH
8EdQUiJWlDqzgNQO+mjt6icTp4/21lEUho9LljXeIoMxhcaPKWLj6d6jWcD89uJB5E0TW7NNg/bB
9sDQV7n5/D3t0gYnjLbr+LTRu9lGYyicMKL7iNClCddqYkg9gg3PNwkSgC2mkPb4EQ8BBxQXF/Ry
9v1wTl0SBj66H7OUXQtnwBbJ1J7tBQSzm7xAVakRYOYs6u5HdOo7A+ruT9iKhcRhwswRTwA2duIx
spIXPDq+5zoJjTlecGtEISseIYWfzOQnur27jBM6wSTBUTilUbIsl5KJYyuOoTbTTEsWKb27vGn+
1jrrN25vOze/Ny77ndZ/fmh3Wme8W7YeRPJ//5//l6STRPTPmRdRVx1azDU2EgIanIfvKQ3IeDZx
gqroudww7iLvvuA6fH8QRhEdJH3wZuJl8syMSGAMWbBtYGyM2l3/I3j2Veu8XE5ckTlesgMIOlFk
h6VhlK08Jf+Mw8AGt9r/Iw6DchZ1jy6SculD77z6uoRXuaY8bThZ7eRSN4rC2Mt/bTDb1783Ltvp
oH7XWHL+k3SpQ9kMZ76LQwhme+Rb2SkF3Y0VsoTPrNJ8V/PdHwznhYM36+Kxc3j0qjxwgjAAn33c
CnAdtFIRBTyRFf08jBrp2BTlmGYppcnbOilhydKOMLMIWq9gbHBdlhNPbElPSYn1auusJHYCEiil
/DRtxc8/pwCSUoAwks1RlvUiqJiDAxOEhseVPm2cSDtpLsk09IKiMt9kriw/ARSNwe3Rt2+k6Hvz
Q6cDh1S3nZvz9mWrf9HoXhiwyv4NXeyBEs79Evn2LZXaEPezZNM/Z44ftzGbadOJaZlPhFF2InAZ
cB5GPRonMAkws3YJcjxospKP6V+bWGxRv2hcv/9OAckONVHcZ2QjOsJHlG+3iBtSJiMnKCfA510u
LukMy0vLJkNSJ1ynhRFa/RE0eFnYFubZ9S0e9QW0tMovoiyyG66jSDauTdGTZLIzcrzAJtchV3fn
cGUkonxppa5xCX6PSgjnM4s4/iiMvGQ8sZj6juo5GYmD2n9Cb/hLAiaYxBsuCejo8WwCkhWwRbPA
JqSBe86IKw77eDYsEZOAPtJIRrtDawcm3MXLCqHvwjn0LLCF1IlmQRtrQ9dtLqm+iD61jHOCjfhN
p9265ruDxuX7m067d3HV/73V6WI+1danW0h5nlosLeWImGHoiM1F98MtAnPbYLfxe6t/3ei1f2/1
b65a/V77/JyVwC9YrHXWv/pw2WsDG1+3LiXcnQ2dWC59K1Uqdjy7j7GZkLP74JBZX8JZcuZFfOeO
SgcbhndOTM+8iFW0++wrCp5uQicrZIN+yok5vKs+myD9Z1+V/oSVAU0FjMEylTIyLFLiSaz7HCwu
cTLxqMDxi4rNgoKCw5nvd2i8qToAi2i2tvP1hdNK9eKQdvmR3kxoUUGWnL3P4PrhhPKCHN0VBALj
WcjXYBGVTxRwBVkqUYoQpBCC8qkTwQW/Zvx4nt7h1gspwrQP09FPYpsXswfxY6nC9g/ln9KBticP
kDmOWXLU9x50Ld4kwi2GsmSUf1JGXC+vfliDACfH+YdL0H67N5cfcGrdXr9HHClX6LiV99+LOs87
ZvLPt68qM/kRjcpjegXal02o10qP7M3jnwr5U6egGGzdgGv8qiPUP+lI/tLONNV5WepsEIfTWUKG
uDTEZAZJqlHlZfy/xd5CmiRuEBUszsVlU+iG6jWFG8a6utorkLc0quIOkwnqOpcahAmVKmSehmV0
7gVuOJeGAFgWmbPlMXHDecAu1ZJnX3Mr0+qYoyI3V60q8sGzryY+XB0LgUVUEZQrtpbHVrvsqMCJ
onDehI0UHis+YBfhc7nR6dx8VCwFFmneXN50+je/cWnNjzDMpW9+b3U67bOWCQH/xNH4biOKmCl2
k+luvcUuSzBZMbVV1pC3q6QVRhSsCqImWcTC4PmZ44EOwoKFKkUtzFvghuTiPGE+cvBxV8TBEV3G
q3lSi5ldOMF4ijftckU5alEiB+V7xTAQqgDAYuUQ61MKghpbqthJeBnOaYTbhootYiCbBlcHhS2D
MievModezMyEc1TvEdGNnDth+tz8L+gz1BH/jo4rQrxN76EsMXShoZMy3Qj9ISBUP8zBQomwZKk3
9mexdLpMA75n43bBawjGCvkDzvCVfXvTbaOwal+ft6/bvc8Inx1knt0zvP+nlt8TNTHwkwrv/ymH
LOsEFqJbTOgBQJNfwftkAFvmwT7nwdChJcQUC+ZUouESo8PkvkGbc94qurP/ITlRuujnn6HQyano
3ufpjwP7dS13i5P3OWSbDReYZzZcWtAzdfhzl4MVA+EeGg+9+GEAQAphBFzRVHgHOYNvyGChA6a2
nsIqStle+ECZ8wTDo/O3ZKzAmaAXjomF9UmMs+2Jk00SUlH9ZPBwJnC5NfiU0aBdzDJJnTqD+2FT
IiXh/6+zocw6nt8aesMy5hRNkUOI+VUpcOOMMy5EvKHvQEDG3KRnEe2tsNYZwrcAspO0Zh7Uh9eh
EISXHzL+5usnb4y+L/D3x03kH+2xcssMSdx14G/wTYnoIIxcxaEkoHOp/3FflPAhzaqHXga4c9Xf
DR3P199IfVR7yw+Hsgjj2YS6+ktpQ4PDlc4sSL+qhDfjRziKQGGJVCvrZHzPt+5MEr6DI0Ia8XkW
34OBlQZu+UtqEmRGc4uUPHDOgB/Q8SW0MszZo3pYIC4R9JkbVH/Rn2KhzOslvlZdt8DNyc2Wyrxe
8tcg/vouP3vOYUInsNSay1+kLlvqS4YXfbQyeOTV2D7evem7dFSSppV+EvYToAFepmWmYZz0hcER
bCB4YyYLpVPCXMD6Q+5nB69S2lWTCvMPUxHlTFv9aaAQmXudbpb7EZ3FtAAZN1llkWVe5yxV/cQb
agNRYITKwrHNaj/despKodVwLt2H8Ya3wibODI2wFWbufcKYaYExk7Nw6Q95tQo2P3wJi9QFzMTv
uPLFj+WIHaiAVLaR8y3+Gi/TwdsonMM/g9C3sqndJk5yUI5s4SiKTPapYhV9+lwpwjAwFB2IMso7
MRcyiBjFjNe7yFSy1Av5oZlesFNR8o+qM2MhmVSLm3pGRzomZIRe2Aun+KkACUyeDh/2Dp86RfAv
cCBydMNr/bpbRQxbzJtv6B82sSSkMMPJF3wWsUFnYtmEJmO+k8X5PMi+lsY485gZDWUFfZEz1FSY
TYBVJSxFqSKxfrpoi3h8bychWyvKFaGWY6jcIIk8GnMFOrbnwn0P1VvhMcYd+dCtr/oG8pBF6D0r
39bZP3fcEHLb6PTajct+86bT6vda3V5F+k3pFfInxdpSxvxbEc2c+ZZwtwyoGNLz9mWv1YETeQUL
Fw6gdi7V1ZLfjsX3trx9LO7KsNescSznV3aPwc5+SZ4sBmOzASxfwqUean/oWqTU/EfthVsC50fw
aZeUwNHXGver9IZv9v4iFgonth5qO/XnF5+1aw+qyzYHUF7pKZ+4Z8spwZNY/Mb8JtIvObfNFcn5
Q3h4+At7zFXmtmAHjxLAescRvt1wmyb12Jbe3EVXMK1112p4fcpaZbpYs/4yjcBxp0QOyN883XwH
MuWEgb4fTjsFrUZpLdm9czndMG++calmU0SfL7VSbqFStzqZkcLxUcvYfJC0d9kcg3K0nnRDIhcy
Kd1kZa8TYsg9lQJ2dTnN47T1TVnmIt8X+nzGLzXDJmrCOb1T9Gxd6bDs5ONTaR1nLresrLvltabh
Kr+yhqGCLVtnCOkwSK9+sVHXspRpkMsc5NIM6aYiR4cXd2eMpSJxe9TVmTeN7xChi7rqJ07eknLe
J5zdIqoroaQH6LmeLVlW/cQzpbjwD2cJl/3Fkh4Eff8fsc30aZT3VmqN4q6DDd8v73/5n43qfznV
f9Wqv9r96t3evkVK/VIllQnhLMmeyqZnqZagRoEXqkr2KDc9QDWU4keg2ULpyei6msxl80eeBhxS
U8qWVs8zU9bn5UUPnsMxV3m//Nar/AF9/Qx6zw7B4OcN1U40q1zZOgtPMP86Aen2LFupdsqZVrSZ
t8BhUTJXRrg9gdEqO8qaLGR8fp3cENx0Z3NYu6LVU5vwOAIdlgc9E629YA8B8ZBeS1g+R/kM6VCY
46WSNn05W5q+6er8utJrQCTnrqkhw2IaJJ6LBo6/jL24I2IqaBlw5dfRffZTWKw9aOcwZZYi2OIi
VruzJ3Bg+O9khmpyilemXmV+ZopW8kUA2dxm3D4rK9u30LQ0Gj6rK6DiyaTp38p6p1Kp609rKNUW
TCO1hYu5eZtWuIYXYMxoJ0XtDCOtfcI1TZKwwf8sdWHzLOVQRVK0UMlTVQzxMrU9CHay8uxhmccj
xWZwg7vllz1B4bttf2pddq0iV7hUJBY44KxzltvOYU4zNH0Tcltsg7hdQNsGSTkj7hSg8wmbxKlm
rQn9nHu/3E7lr+NMcw7pGVzrXNIN6DpoPYYZMrWZJdmYioFyR3uoIm4tvFjdmn37RsoSk+EkW64+
HEbYVvCKnHgn5KepYM7rLItJMVdUZG8+DZOof2tU5Z8K/b7UDslYgoqyN+To2ABf3BC9vq2bs13v
rMNaWddNWZ81rYvkwvh0wmTRp5O03tct41GR7sky/ZxZsL97vDJ4CpqTPxccTG3IkuvTBBc2sACy
eWVn1ojTzKJRyIhFVxyK+qPoIkH+y9orBEUzQr26nRdDpiDTeW0VhRtuK9kbpXel/prCsGhx6UbZ
TJ4aGEAWVaIXbCifVYJTHJlUAxvwCLU5Lc/eKE28y/eQok1DQfmob7bV/4qVbGSBgq9v6zwyyRrE
qkaeFexvlWYwy26uvK625yTWFhhMyn2BOH0CPWZ0ma9b4MvsGvKSb/sWmjYX61cjc8FtapSqEUik
os9NQ8QoRbCZBAv4/5rkiPmueUayaQiN4sicLBWuiDSScOINMuqWxZSxJGSq2BQMQ+mhSnEaIiUM
e94qnlbRoY6r3RVMJw33wfvY6Fy3r9/XCd6jwpty/E5figVvsbLrzqDmr9A5L19H5uKeOVA8Oq7y
0VOJMjopKCObV2cRE1chTUMG38M11mezxC+Mpb+lFVf9jy8M65IUKHvTKu6alb0seCtlrctrLc1b
5wFYD50168qWGkvkFwft4nq+TzL2ByWmI7ccFBImbfRuaqA/sGuvc+GCDtCrqhhRYezHgnQTSvhD
TEGrN2Kb+IdrEWM8RMxga0T8+SmIdQsOuK53RvflDF7mpGAmDA8wJ9MwoDC1+bTAPKed0X1TfLkd
OGWlKkt0kfixLMY+HTg5vOi85kTLv4BWpK3I4dbTs34vehZNXUdehFmLD7pdFVIeK2JW8aWsp2Ny
ChCZ8cnZo6SDBIqgXnhGRxGl4DmqlcxqqhVcfvZLZC813mRKmBRUVordBnGi5em68hKqEVGnA/pe
Bs//2Kr0GU+gkyWCJQHbjgIUx76CYXVM7jkjnj77qvDp1v0ry2zRsymssU9Xx6Tz/l1VsDUMe4bD
tx91reA2g64XKKKPzYnTZ1/1ybE1XWqxLajSwM007RaLB9U1+kuGJ6yM9LC06W6lIutOcR3xErmz
XBmrRSU0dYfeqF+at55KzO/vOCrZ+tAkCPuq26Cku3RnxLp2jZfZ5uiQRjybYDQC12EKAQbwhhed
esmY+jAB5WAcr8MGccZl7B3MTxeFjgtzRA4ej0fDkyI6gbsOn5jphMYJ5oaLbUIuMZeh68UOcChP
YihSWK5F58Tcm5dETjLGZjsBGXuuSwNyD3fNFY6dOAtvMpvYm9kgK+rf1rML3du6vja9rafsuo7g
GwhPA7n/HDKiwcwLIDkeuCWBYxcNEgiCPBx6A4/ddGcZN9chFP0oY95o3Qijjrfr59R5kG1Yh28y
ixO8Son658zHDIJIhZF5yIRG1F+uHXI6cKDFXkLG6MAWAy/dg2NTUIWJbIOOiSkNIXjPOlRZFgG/
WBbxx0nI2BuN1eHm9a7Fh1lI96dR6HtDytNeqDlJoSOkw2RVpPcs5iCQecpoooapM84aQ7tuoNti
MTDmLqgYktJlHV4UeuqkrIn4rQncvC6wlHIv4b7DW3221Nm2sLACFrpKn4OKPVHt4Q1bzHyCxJSB
lDwsmSUyt3dci5snpVuv45EqBBOXdOcX3kplbSU8H2NK/Rvyi0jyoRlJMXpL+FCqbKQ5U0ymvCgs
uHrKgL210Zl86lOmmXe8+EGYujeNluZSpNKnxozd2F3mbvlRzV4ZbEVrLbY5pqqKoL/MCQo4JCrQ
yooyMvJr4jxasbJBl5v2NBo2egliyPbaQYHWYj6xNdROh/ze+hqDgkqcIXRKYZsYYmXfr9ak7vpz
KDdgTDf8Boyft8OIdjmwBEOrbhdaBnYrn4pI8XPLd4C5X6PRfZdf/k8tCRrBBXYEKCwvhvB0JnCQ
32CkpHgt2cXy19JSW1VAGMOcEicdVQogOQ1GMHFC/eeg8eh4PhpQ1fP4jE8GS8TgJVSmpqodK48n
2ijc3nR7fcnK4JHQ7rU6+NStKMXyiRwyhve0qexFGzre0J1ylpt6trKuBsMwaVV/x1CJQfhzgOSi
1cTxIQzXLRN0ZaVuK/XfNXlw/FKrVdbVsN5SxSko3iBxADVUGhOFlvpJhkJTHZ1N8j6lR+gE9xF1
HnbMVat8Zzzs0CKuRyIEw3ZyXJCyhSRff0pXpKGkFFWKOyRrh4fj3aKqTk4z86Z3c9nqNK6brf5Z
633RubqsTU5FCDr85nTtHNyErIjEN+TVEa5hxcO6dr1NP+4pI7rpzEceeigsU9nsvGgUjOIYkc9E
w9zUBLjJrWqtbC7G+2SsQkDPozChN8LL55TgtGzf2Hi4Vs40yCKl2+v3JUtzWt6Au8ODz2RR601K
MTP36Yp5jDRqN9h85RlcGj4Ia04XUQJeQZlTuN01FfOmfG+9T65Vb23WEzYfTA3tetzjuRihaEXG
6fZJyLQRhkNzhYOMwjZXIGULIzxQW+TIVbSqm4jReS4z+7LMnPrFV9ZXsIGnNezDTVjlwKikVwo8
l3M3g9cOUw69HHuj5/RTUK/WD/E1ukFsHN2M/1nRyIJyyBwrsvlf1inlRSunRKalq6opupI3GX2E
yzyyymIdJ1c7bmkUYkWO0k3EfF5DzMV3E7N8CjGGLvaC8jYVm3vuk0U04j8X1UvnEI7sdhk5E8/9
CFMpskU4BBYzt9jawS9Hl2WVvC5L7THl9xpM6bX3GPPlrAH1wzj2aRyDQhyBR3EYlNeAcyed+NZ3
AidaB4mB4tcBoKTpUrgrW9Zu4+Qj162RYnJ+6igUV9TMF58GI9Tg3/DbVYVyhqGu5O5V5IIUoHzJ
NYFdzH2qvOnkPaI2Cp+1nqZrFxlzbTgyHaNrVrGBMb/z37DfLJ6EyrbRWnODasMqZGhAMTeuvR5T
FHRiX02QsD3WJzNIXmPEXCiaVipfaHrDmg3MTzklQKLgc2pjYVMP562WPBQHBR+wvO1SJj0pG22p
OWMqUqluitdQWbw/W7ODrKw7UPgrltr1PnngL2L2xMte9JCgGNUKg05iTPquBwKfhwPYI6U6AbFU
1sCl/x26Vc6ChyCcB4QNTWVnw0lzSgofzCcfOG84ZKaLAZ1ivBr9VNl4Oc/o35vxFDVeuVvnlltQ
fh2M+dbdVnKlZPa5bHU6N526uqvCqE/UJfezBI9OIXb63PN9mScVoDOcss7DUrpFqlGiuDel+UAi
6+tX0cNOrS0Lqcy/fSNbIX1Q0UnZEBmEAk6wihojSy0lw2Tt7e2oN69xBhUduLyVkV6VK3tbdcbb
TIjX9eUl8aIYM3OQOn88b7QvlZgXGOkipkkTyC9jIzZGu9jJusCiY/OXUpp81TJM6Ls0iKBGdcWQ
lRhDvF5itEMlaJxIWq0daoDQggB5qFwPwrjo9Ig8l1hzSOBMZLAUSGKI47A9EvNlNxZmsHPTjm0W
jvnSCyiEGxTnQhCfiS4ttjcGjReX7TM6dGZ+gs9Z1ZRdkoU+AcVVhHRk2LMRWyFen6UEItYxOUEA
Y47iXPP6k6f8u8YCKpPsZPYmPN7cyQkAr1uNkKgtHcNzMa0zUYBN3uEp/q2cwvXhQ3dLX4mAc8Vy
bcJhs5L6UIkZ49vTWVIuSX9MtmwRFnwtY68uOthUlu+iiXdYkZUpJP24evXWMOULvZ3CaQFOsW7+
JfK/uyKd3pvUfYxoweqidZ4Z30/2X61Pp76HQpMoAfU23Kb6K6R/d2XbLAppTEftPgPu2OobEh6l
Gp2Is6XHgEgjceF97zSSTziHuPFzFdIHQCXKnR7Xrm4KXKPHtzNHZxLFB4vsy8917faAMK7V5S8r
E3moyxVf08ZEUZ9L2XJNRQ/WdzUKz+VvruXQ6AF/MpjUj2uR5cLo1bcUPerwqbH26sWzPy1SsPWq
F30wZ0Te6tZjdlOx9TXHWI6vMrLWTvaei3btsWDgxUahrm45lN5gGl9d2azky/JNRt2w68gOhSth
c5sYZbaIjUld26PkcGX2JvXiPYvKoXhDq05yoQszu5B69sVOeilIhMQ9OeE/118BE2ay9DpyZkvx
U9bYkHc5A5Cc1qtpFsq1Ntj7DsZ04vxOo5hdjLJk7XX5S9/mylxQstCWoTLqadCMrAFA3Cmsr0kI
pYpgeQm7rj/qkLHzSN1GUmeRe9F4cOYkcBYLgX92l8vlsnp1VXXdUq90cVGfTOpx/OnTp92KiA0E
5aBEOXsLTQqEumIB3NnCMrOFeEhtE/XUNKHPYGGV2Pa69ZMlzl+WPNuLmLtsy2Ge1Pm/d3oi68xM
U28UKOneKDcjmealea+09fVU+S1/TXXtNVQ8KHnydgP4d+tbqGkVm3Yc+T5Q269nrLIy0bHL/JHv
Cg2N5uVzjV3X0NkUblqIoqTZ/Z04Q/DVy7RWxW1q5Uok2/1J7gVVHknTiziuK5KKSMgKT1w69CJ6
ISA/IGWbY2/+8HDt0Acsd9LfEat98MR8ZCwPmTZNNvIHU8NBGfjPGZ3RLevDwNt/QgFDpRl0RZVr
d5QMZimygtBsLFw80zjVKPQCyQCfEYWqLZss9isWZl6T7GtQIq/+JEnjeTzLMm4bnulBYN4i877q
2L/O0s8z80az4MoJvCGNk8JRiGZBf8KBWPy7SrasWV0QK1QuBjHcfxMv4RnuwZWaN+Bv22txzVJo
GD92ld5up1eol2ypj6h5jOtkfcJkXuKJmpMM4ox8VJdsxDJrWEI1PY/CSTPNJ6yFhpAt0mIL1M3R
Bhhw+FAXVmqLCLVeMUJb3FZ/E/HIxPXUFs0wTMR71fgOVwrBqCyCmcE2QDWwWzvqdQNRa2Z6WoZJ
Vi+aeAwj05fPpL9A3RQzTcTN741pAC5BdRYIHBFwryAYTS8MbqFdheHSdrRtSc8bDuvGCGp3O8q8
KogRAqUhEurmEGuaiNTnuq6/KB9TBebHL11gYR8kJB5T+sNXLxC0iBhDy+aizMpHJRzya6vICWlA
PV+5lqHPL7wHJKvrsDwm2cJ6EbJPykIwSyIVJD0mfbsXrVav32tftlBZSZvzXAF7Q5o3171Gs9dn
4OAh+rF91rvo334SepWKVDYxU6x9jTWlWrbShKEfhlG5uB6yr/Vc2qRKJm8euzbKcjLTBCNMHePR
nTNIqliMJB4m9PkXJDV2ZwPqgr312VfZhNV0YUM6PegsgL3AoIyifXu80y4b71qX/Qtcmhhetpi9
mw2HNKIuHmSYe9RSBvI5q8LSy9m9z7eQzanX77x/V9l5HznTsTeID89IPBLU8KMO8a1c2YlHcCrR
gctkED7mwguSsvYU27+1PgPaVuf25hKFvUUyEL83Lj+0dJj+u/Zl+7rV6Igq2MEH/rU/XrR7Lfww
9EA8DRJwRKvxNipu/MqbNDUSw3ceBmzxxB+lrhPE4Kw0LFn4xr69bLSvMQFxZWdHMDrE/P8okhZA
ZhAIk8tj/+MJJDklEOhZDkEaTxr2ZDJ8tDI9BMCiBkce6pDJT0v4FEVi3PA9dgS5DyOXxb7OHsi+
RcZgnQZ5lF/XLPJrraJGlF935KmVP3hVgysYFjl8UYxBaINqwcOXRxY5eHVkESinfjisWeToCP7P
wwTnB/ndZaP5W0V8lAO9qFlkKYZa4eye9BjCE2WZgGNjTExvMlL8Y+H6WDmnIKbo8hH0oPyay5/i
ghz4waveiunsVsSmNxnlubcAyJzrS/DMfK7La+ZmqeMHZgOyzBjG4zUY5F2YtSgWcDS8qIGbStqS
KpnPK2IiHJoLLuE4eJkrOB6vKxiPbDdy5kwIepORRRaQpGxpkfncIuOxZbjsu9ZpB3iBu/0/2Xrh
uJBHVfLNCuT9QFUNYEefrWAb24U6Udj0ryjvu0kUPlBk4HdO7A3480u7NqxIOOglPpfIHjm0WE8f
quxWJS8zj1vUcqDVYpbZ5ulM9ozz2dLXvcpWkgJax6PL7cIIwBZiRXAwmLha7VqMKX/JVg2nXa+M
iMBgSJ59NSYSWqHFErenEmRdbqIVHLKuo+HFS8iaAxR48TTEzJJyxd/KkMAZrc8WP8gVUNnRvFC7
XHnQbwUguLwPIKur7KQOilgwo/4U3iPR+B1uk9SFymPeJ0Je1x+tjl+CnJqjtCIXvatLEaZjChrP
32BbGicTv9jMwKINA0ypsiNAMQ3SbSSsmJGMvUy+kulcqBfTuc17vFw6+ckNB8lySrG6N9wOq0FM
aOKQwdiJYpqc7s6SYfX1rhEw8RKfvuldNYiTJjZnx+qM3JN9BmIqHCdL85f70F1+HYZBUh06E89f
1qvOdOrTaryMEzqx3vle8HDlDLr4CPqWtdulo5CSD+1dK3aCuBqDMnY8caKRF9QPX04XxxD9ZYSr
UP0/hq+GvwxfH6MfTP0/Dg4OVgYixgeMBNC764eH04VAVyM18nK6WBEwyTtfOZajoyMOUL0PkySc
1A9eTxcmxHYShv69E32dhrGHZwNx4g0elsdJOK3Xjv9VZcfnRyaSp47rgnng9XRBauTgcLpYCXTg
/5eEwVdORARMW/9lupBlfpkusMQxE/z1g+mCxCFEa/yP+/t7/rbK3LXqr/Qum4+9hB4PZlEcRnW0
ldAoW3N9DArgV5VqSocvhq+MnQAbnq8gonxnWYeHY/hTTehk6jsJrQ5CfzYJ4npEp9RJysheIPet
iRfgnup1bbqwDoZRpXI8cqZ17AtTRQMncr/mmsL74GXaB67rZvrg9XRxDE0a+uG8zqLxHN+Hi2o8
dtxwXq8R6EHAEI3unXLNgv/ZtdcVIxnhw1eOnTNMrXb/8shZ2WyeZD4Oj5yjWm1ly/hNli2d8yxb
BL231QNNy86eXWZwuoMXv7z4ZWVntPUMlFN7+Yoah8ybjOSA3fvh4OF4Dppg/aBW+8exA1Flkipe
n60f7B8cs7TE1aGX1LnboMbPtVrN2E2+c0/9ryqbH9Smi+N0Jh68mC6OfS+g1THK5fqB/eJoZccT
x/fFXHz1ytiAk/1CiXMyPlgvxcYHhmK7J673SDC8/ekfuyAN/th9A4qZathYsXR1x5DXnZkGVyR8
sFCBk/a2FREXRJ99lRZB4dm7L5McPPuqmgVXMgg+Wg+EOIktJYW8AMzkmrdP9l3v8c2uoSfSJu3y
+b375oTNcBIGA98bPJzuDj0/gdzSkRuX/+Ar0x+lyu6ba0rdWPYaK7apOG8Flud20W2Lhg9Y6ua3
bQs4vo8lGr6fFsG+KK3vC+hiuQaqO/psis9C/gBBRJ59hUW3FQ+cKU1zRK7+2CVgZK+y59M/dgvh
3iienZm9qlYzbCzjaJBHJcAzuNAxWms8YHD85HRXNntd/+DELYLcPWHBt95kaMEkgiuYl/iVVIv6
5+Q+UonVUU/B74N3MsqBP3bfrFW5j7nngaZwZ3KGop59/B26+TE3vWebouVERRLmuK2IwvnKwrxf
bL/hQ39MnaCgvSXGrRrPrnbMQEalaxB50+TNcBYwBwR1esSVr244mIGfof3njEbLLoWsmmEEqZp2
cSVlR0ctZzAuCxTlQeXrwEbRavP14bQcn56e7jq+v/vt28AG1o6pOLM7PT2NK293cQnZre9CnMfd
41XleKXRssvYdLdyfLLPaS7JdKDxbDJxomWhqjwPowdYtfsc0E4WSamyoxR7utpsXB44RsPS0GYH
ac++rj9KW+VF8C7kaYzZDl9bSAygN7/VlXXFACDOefTVxgB4zi6RhFF6BKUuRYYSV8bFp55dpEwl
M6tRPbdgGVsyShdkMvSdEe+jzDmXqextGCdVcVCVzmmJpOAUzISqhSoXcZVDsWdfc8diKwL2DMhQ
JS7bTb0F9aFqfhhWMay9t1KBI0w9Jew2qYhBReZe4IbAnUKAVd+w+5AYe8fAhQ2evpoN0hiPZZ99
FWe25m5msfMhxY/iQMO6Oj0cNfayOBglydiL4fhbuXmkH5eK4mgbAHLQ0aSjOZ3gZT75Dezd5jCo
KgnvoZlzJwqQgSkAs4T0rNGyltUx/8i3Gep3qEnQl5esBpnKOIYPmeH7gU1uplSwLm7gYZ6ttbnY
BjyHNsGzajgFggilZEl9P5wz/RJ8b+LEUGz3hU3aQ+IoVy+8GAw5oFCClQpCY8KqCfc+xBQJA7xK
hgldpI8wifGSeUwTttxCIwLIOZmEpPTsq7je1G9eNrrd/nXjqrXC2wAuiWgVbqUhWzBhbhv45yUS
CvUCN4tlNEtsoNIJ1btQOaZnNVFATjClMgC+KREHtx8swiiNPehI/IwVppQaqDMNPGaCMYz4brP7
O3qYMUefTdI+Y9fVbFzF5TjXob8P4RVmnHy2QgB2rTpXVjYX+iCjyogIKii80yyeSvF9U6WG0mni
UEPhbUOkmIkEB4RqKnSFGTGfBNRQcwHpVYgTXAWGmRZXMFyHeLWzNjqIsXJ+61q9ZA7BJKqYLoqv
EkATdqiapXRdl64PDbCuEwoIkRQU5izNkLPa2RGW4NLpKeSupVNySNwwgKRRpyX5tVgpSkF0ZSh1
wNF1Hyuzq9b1HKtgT51Ta6zcntqgxaS0adqLWW85Rmccg5qSFirUU9SKtlu/j1M/pm2Wa2UgcrIt
Z4dXBm2dbCsup8q2Arm2sfCU69+qXCsu1GWKPNKo7BKMpwyq/5G81f9EHz4NieLKx7czf9GX7y+6
NaFWlmsQEwgqZrnxYnbId05M2f7rsgEF+uft68Zlv/Phmu2/MOwZlwF6r8qamUkfjuhuo3BKo2RZ
LiUTx1Z0Jd7lJeupXb4d9iz/lyyzw5+aI4+5VgVg+uqI0HVZV1hpI/pJmjWIF5Av4G9hGRIDKamI
K2oYi2ijR60CnFYFd1sEQnSRjf6yhyxIb+AUtd1vwFeDLcpnnuOHo9iOx+H8I9PIr8MEAo9jjeVS
I91ME9hcg9BXbk3xnRZ1SYFF1Sa80mdfFRJWXCveF7oddrOieoNup2UgUAltB8PwR1MJ3tRUQDGK
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
H4sIAAAAAAACE7087XLbOJL/9RSIN7skY4qWvUkmo9hOyR9JdOPYHsuZTM7rU8EkJGFNERwCsqzx
quoe4p7wnuSq8UECpBQ7M3fnqsQS0Gg0Go3+BLz14kULvUCDy+Nz9Bq10cGMpgnCKGbZiI7bSUHv
SBaivCCcZAILyrJ2QXCyQDmOb/GYRC0kMRwWBAvC0fbr7o/o/PQDitk0xwXlLEM5zkjKUZ7OOMJI
FDgm+CYlaIozOiJcIJwliJOUxIIkgKwgeUpjLAg6HPwSIXQ5IYjNRD4TKMYZuiGIZpwUgiQooQWJ
RbpANBMMnbM5Kc4ZzcTWT2SRMUFCwDef0JQgQaZ5igU5z8X9lvmCeEoTgqZE4AQLjChHBRGYZiRB
I1YgnCE8E2yK5WQkvgV8N8AlUkQInTKUEEFiYAxiBWIFNXxCc1bcKnw5kcMnpJD82mq16DRnhUAc
QGP02yzHYhKl9CbicUFzQbNx9PN59KIEjNk0GjM2Tkk05iyLPnCWHSgqDIhGMrqPEopTNubRkfpd
IvknvsP3EZ3iMaEs6sPv/pndGeG5iA4wp/FAFOyWNPoOWcqKRut7lolG44cC5xMa852jRtcFyRJS
0Gz8kWaCN7olgdHBbDQiBUkkmQ5MRlk0oimJ3tOU8NVdA4GzBBfJIcsXZzlshwMnyL2IBnSap+QI
C/KeFVMsWq2EjNA/QWL3UEbmyGKyH0SciPOCCLE4L2gGO+QHUSyl3g/kSEn3EQjRHhoTcTgrCpKJ
vmn1gxYdId+C2kPZLE0D9NBCCCG9WxGfsPlxUbDiE+Ecj4nvXX7qOScQkXtYihci75SpaUHOWE6y
yAsksoKIWZG1lmpNnBR3pEB7FYnRmIiBbPWDFvBRSusoZfMjCoBAWEsUC00bIAG+fi4o2tPoAMXn
iz73g2hEswQ9ICrQMzUU/e1viAo5STwhU+IHsFoPUHhoKVECLwzKZ4YTNSLIHAFtBi4AhOcYuCqb
g9YSxVjEE+RfTgo2l0qFjjNWkCRAD0vJbwflI/MMFlyQqZylYDkpxML3ZpwU0YRNiReEyPt5do7F
pH35qdfWmxC0WgMBwqyYe4qnxGHRJ61ZfEk9dPsBetdFngT3nMEDQaZmlwAyAkWIY9JLU3/Lf0eD
f0RsSv4RCTrafL4VIk/vtgN39R+99r/j9u+d9o/RsH29CXBDzxI+mCWi/Hiai4UfBM7UhiolE8oK
yI+PcElMcaSgI9BCXijpKodY/A4RAA9zmpOUZmSoR8Gx8ySLejecpTNBgNF+ECjCn1WkRJSr3TcH
J4fzmGZo47x5RhC/pXlOkq5eC8qYQCM2yxKEBXr+YKFtTL3ckOj1cnl9ufaJjECRzzicyMFP/fPz
46Ph6dnw8Oz0ff/DyhOpqdmT6iYaFWz6b5xlvkvOJbkXvvf58n37DQjfJ5xHcYo5D4zQ5AWDo6Fl
7rF9UcBeqCePcCzoHTlXze8iwRRWLZ6eUmp6FNpDLhoOJljgLCZsBJShd3WAK4u6a9RVKkXupW+Q
uiiCP6EJ5Tj42dDrQd7zB4uApQcaEjY/ISNp3GnW2H51OJfRRvBHNv7g5Ozwp+Oj4UHvaM3OK35a
1O8Z9jo4m5xdCdVFV93rkqEWSSQDRZiAthPFjDztnCSUq1Hg9Jj9cVkY/aHzcNQf9A5Ojo/WHYOE
wgAueWEhsHosdpxQLiQ/VkN20dW1xCvALk9JJg5ZOps2sTf6H5mjAV/OVLm5K5ZQdT26hgpUooZt
rRZWaWv0r381Fuf2WqhsJf/nT5a3huthg6BQOvT2mqYz8PLTFFz3jGVtAmQZX+V//Zwp46UWcIC5
Y7yalgjc+6Hy24elTZcYID7gQoYTxPETKtQh8k56l8eDy+H7/mnvZHjx+TQS90Kb22cOhobh+lN6
7pRJDqcEIgs77ChmGco1zZalc0lpGrs/qfVOz4ZnF/3j08veZf/stL4jkp3FLKv5Ww2aHIsXiYJO
/UBvhrXEQ35nY1F4Q+RZIMOC8FkqeBTzO7MZCi6i/EjGjKzQJ+aZi/p/d5sgdFXLbGwSBbVg9rCL
nj9oAv+f90aZJVxwcsjvTmgGx+UBaQ8DXDTU3i+DgDuczghouqtrRZEE00ESGlGSJnprnB5fzXnD
WEpwhn6bMZDaPTTCKSeyC8yOTzOBIMDovEUU7crJo5RkY2DCW0Q3N82WwE88wQWKJ2hPwcHXnvBp
UALApquZ7GGmB0buIW/DQ5grXBCzoE20XZ8ZOpwpAEgGNL41PKhPIlcFDIlwnpMsAWC1CgdsiUjK
iUVSDekKXpkfOdCZIp5Uy38yYnASWg5Oe0zorNAIwO6unrjyGt/qFk7EieJcJ9Cx3reJVTDrEVvi
qqFK90EJLMhj7QxDjkp2VdoEgtRemqIH9IwKrVss06ncswnBCSmkKbdOhG8muupcK7e4IDErEoAz
XVFSsNzfDqKYpZDJQg/rD4+DG6CCEqZgczhdXXW8NDkRwfHkCxWTfpaQe/SAJiGiqL0P0FeTawgZ
0a5GH3H6uwwx9fcrCs63Z8JuzceCzRs+2MGinyg/37gdeinHmSgo4egBXflURNTenKCLqLjW3Csd
AY2q4al8L0Kly84LygoqFnUHS6UMKcsGLljD2dLu7+NDrc0DGakioiXqSiRX3hRnM5wO2R0pCppA
OGWaYlYUMoUJbTjPC3anPrNb+L8gd5TM4dMsi0kBOUbv2lrlBc5uIXPgEjQ3u+4HTe7JHQ4RlVKh
Be3Kl61Bt45Jy0VbgV+X2z9hjJMLUrHnzrEApTj0k9C0lfvaT2z5LkFtKQJRuLKQXJfgJRZbUiS4
hf+6TBZZ2E0aR0DWR1qbfpqSMU57xXgG447vYyKzfv7G5+w2Y/PMIs6TcZ+hZ+lp4wpzWCT9kTmq
4d7zB2sR5RywbJymbE6SCzYHVVAtKyqg5R24/7YS8akABXw6m96QIohoJn6B/ZUyiTkaEKGozwQa
FzRRJw0Ql/NHVvtKTNUG4iyhiczj7xkVZ+lNYITebvixFgLhAAg09/2CzWElMofQ8QKYkWYCjKhj
uxRgzNIG4N6evZDaMBik5BqSht6Uck6zsQcmWs8sINU+HM3StCB8mGdjcPHsrlwdROiy7U+19ohD
QPyAcIhu7PUCizkujyic1yusqbmGZXRc0BsX9GYN6Aj5HMNq+E1g1DO/Qbt7+4jjEixhM8huxjC/
r3M6CcliYvNPATXGACH+zdPGyJMmyYkrcmJFTtwgR2hyBOV8RoajAqtKyKM0CU3T0wfK4ykJExVh
QhEmKsJ0h+/jSKnFungBvO/frO615EExopSJKpQup2poBrAlxFILpwzNZFZFeycEguKCSF/X1UFb
rrrYcHyeioqrznUZ3o5ohtOetDI4radomx++kYEFsZTZ7TIRHKgAxpNzDO2wCusJdb5WWhDTBj5q
nagymNJG2E13NsG/lfXUGS8TRExmU5z1tJlFeyUZRj+Ad987P784++X4SCqIEsB8+MQSIsEkqjIV
D9rnI+YTG6dpq6VJ5Zp8oykrZ/IdGHlpEzzUNZr0qnMdGbdgCAiHkybGamBg0zOYgE7as2jbs6Zw
pzMwkcC3xN/eCbTE6IptLQZfIyG2tzSUFV9PS8XG84dSUJZqHc8fShpBdvVsGUlrc1UU1GdQJWIv
aJlh0fQ2oQX3g1YLlGmMszvMv4DScJOdqiz8hSZici6P8/brTidYY+cqTB/XYfpI6HgiFKof12PS
WzNimdDZdweXaW9sbi+b5ROQNaiZIkFFSuQnzSSWCd+MDeXX6ODs5AhOgZhEU3zv7+yEMk4OVFMB
CR7frOkF6kSdv/89gaqJRFuw+Xei7zyGfudNhT7FNyR9ZILzk17/1Jph+/WjM+xUM/wW19F7A5zx
ASnoyFs3w/ZjM2y/lDO0VFlTlWilz1sVrdE4RE4JGtHpOJSG/V79Wqhfc/VrUjrCysDxGEuVrKii
mT9HW8hXnVByG4Oqk2LrByGarOhUkmgMDsyRzEuEsMwVq3TxoheKDBvF5IkozOyrcBR4/ivaQ/do
szncn6M2SuYB2kI7USdxR31Fe2ixctQERk3cUeMIxkjm+5L5cmL162uIknmIkkmo3HQTzUDfIZja
QtqE+o6a+IXcCyU88gSv3Fazn2NIaJSCXa2Hw2ZIVkHnJyIKGkMtnMsp1A7APKaYtop75UasOnWS
k7zipM0UrVMq/CVzFoGVHDnmMc6VZMug0KxJswG8fNWuox0wJGBBZOOaDIxvOfte6MkMqt200Wz6
R+YFxgGQfsWGhzYRN1VrGBMibwNGbsrOLuLV1QV1LUilHFWqMiPpJ3N1SDbbNY96qqTqC63PfTtk
LiPdslflQczXiCZ1Rb5R9Q7BlXPxbqJtUzzWAsfyOk7B8sMy9KuxWo+6YUKwaX2gal071oms+4mO
MatpTdeKULOW8mhGxf3EqSk9JTR2mJMs0QRDGbaikG8ELTmRq2yVutZ63+nSmvxLaCx5TU9Hl1/P
j4f908vhxYcDxRJbBwAv5Th9d8f0+UF12p37Sb57Wyn66fjrsHd62e+d9HuD/umHENUAfumdfD6u
QIZnp9+Dun96eXxxfnYiU/VrcDsww4P+4eeD/qE1ibyl5cv/oy8f+5fHpm9E0/SCxAL0TccwsOLk
GhQHJ73Dn/QugQZjM1UOq5yGN2sN7hdt0l8llgFJyUicgOvwxTFGrzqPoNnedtBI7+mrjeLvLx+z
/K92bAwFm3/A+fdheOnQEMssRQ3J9svH+LHtkCHdqI8OGa8eXUjHxiAd8kvmUPHDY0vZ7jhLUaHq
l/KAfEFtvdUv0A5qW7tWrZ6kcg99M7ZtMeRFTXGU+cftAGzaij53OQP6u+NAyclCVC6hXa3aENo2
O9o2PH2BdqQBtew2gcBb8apEsGlNuWnGbmpsLVsnS+tzCZJXU+fQVLcSboABt0cvV4GV7gFUKasJ
muX72vEE3ag+/dAJkfpn5SdsX8gfhxbpYRV5hIp1uuVruGrzTVpE5ZelLQtL43S9wuJaGeOCzR1b
+wdyxY5UwHkvce7toQ56V21jt9rdVfxy1JnTL/07HSzZXZavVeVppXjU99BZtGapJnjThGHyPqSU
dK1NZRbJPgd1VlqdoT5aDW7+gXS6PUwlJ2AXVhQCfGdZFp7AwWP8Se2rSTt8QdIyibwyLfuuW++1
MrMOepVPUJ31TJcp/tfntitKDjLXz9BY0R7S16Fl5dC3JgsaleNyzIoSwZnl+hziDC6cAUJ5k6pE
ueqqYW0aiEegqimP4KZtMTcdOXiBlF4smz/g3EVl4lupARQNoYx1pHCGSKvVUv9ZommX7DNCEn5h
mPVM57xMro1m6EqXub63PnbtkiuPo7p9LvWbdRvdt2l4h15FnRHqou3o1ShYgcOoSHtIpTB3Xr4K
0fbrVyHqQF6x6vjxVYjUvwZSUAbSe/om99YQslL3rNLSVdlmpZ5xNEKZfwkbNw/uLfXTsG2uMkKb
6Ae9kqawWwxcdcFhpTWymRusGFK6oRApvbQIfRnWzD1qozch2n7TCUKdCnLI3u4E36JoLc9dva8Q
r4Kw1P/Gz4dwQ0dJPWTxl9VXdQiWdr+ptCw3mlsjt0dSby19xep+cElaugezDIx3d9GVNO1dJ4LV
9l6qiS5aEaE2CSvY/IKlpNswsJ5gOUTlnjL66gqxsguu5WugLMW1JrlVhc/qsAqVTUzA89JiwJcQ
cTYrYnLB5pYlmYe65la2qa+rEJpNstCaJvCHoDL1XhemSpBawWoVE8GYXbJLlh+Rcc3GDQUbCpYP
EzJujtTa+Twbd9E3rUWICvLbjBaEf4TChTqdXVtFV2Z+aZW0xlFCec64cbWrPH3dqposfEWkOgbR
SL6U8U8Y5ASjz4MQeX/t7CTDv/Ioz8Ze+Lig2TL6lHcLWoMY8zwvqDA5ACgiyEnLNShYN0ME54Mm
9cMBt5q71cAVTK48NCtT00WO+2u1G3/Y0uDcEXoua3fGnuJsMTB3UE4d22oOdoSzhUrKrNhttCwx
3aQsvj3LyvHmsvhvM5xSsXgXSQD7zveXCcl6Zn6SHLKC2CQ8M3fNylcG1dCBrq3Vqm9Qfn/mEgIX
K9cs0uQCvfOL44G5lji8OO4dfZVa5ufD4dFF7/2lp1KAUyvXJ8dxeFOEfyEFl5zXAqbyOUlPKHNe
f93lbywWi0X706d2kniX3seP3em0y/mvv/66ERixhnFH8kGX3n6pnrvVg5xQ3yRiWnyq2/FOD1Tf
uk98jwGwnnzKo5che0EGu+hbr1MUtFaB0glWd1q7aN01UjXCVAerOqFqt6q8F/LurAIqK6Ar+9VQ
AzPQirdWiXWBoOjaXV2LVYCOZHXdr2HjJpeZs9mmp10tgt11HWrUdx6ZrnsILdkZkCnOBI15F97q
FVOc0t9Jgi4+HEh9xDgV6j7AlBX5hKVsvNhy7glLhG/ly5XfZoBK39Ca4uKWFBAokIzD9S596bi8
2HbOUhov6rfAFJD9ErZbe+lgdbnAA31hDt7Lrhlkg7iDD0vl+63xNaiw0ua862r12mJ5t3o/fK20
hjQUPcGmNJYFEGniBC7GRDh1IBNKq676C8N3VR26tJhimtu2cvXA0EKoH/1tIi8S01wne8Q0Bw9U
3nZQ9Shz50F1l28vzbw8mrI74sM4wYwjoCepGpoPX6OL4/OT3uHx8PjX/uASMta12L0xoHd59ql/
OPx09ovOHH/zmeX/DZEm57Rstayd9J9wlcCYC31FJVR3TgSTN05Mp9wNqEs9CbtU1EOe4ZxP2GrE
GqjCq2rLJJUVmCtPSi/o+corl5dClcsNH0s/Gr6UfgN8qdxifcg98H+hp/SA5Zfy4n/lx0pcjier
ZrV9VIN0hZvhKeZqt9S7brn3/c2Zqx5kuLf+1fqjfzKayVphUF6FlywqPR1Ie7n3Cy3MZoxGVpWr
ygInZO6uqLgO0HLtXMsnbLSZdFj+/QH9fiR06LEzSzWGTMQ0XckJb/dZwmKxyImE2d+FPzggb9Vz
IvY2ZmLUfrOx7wUt6C0p35VJ2f3GMxNN9e6W6m8M42KRkv0bliweoGjdHuEpTRfdXkFxGnKc8TaH
CxRvp7gY06y78zK/f3uD49uxLBJ0/0IIWdLp+GEOVezudqfz17dTfN/WX3c6HRd+PqGCGGTbr/N7
1Hl7w+7bfIITNu920E5+j7bhv7+8efNmiaI5LrKHGKLz7l/w61c3nc5bSedcXjro3rA0We5uqVXU
FrexO9nel9m0FYl1mUxfxy1vubs12d7fqOPL940PsctznCF52WzPc+cYVHfKSu8UAmNYifRZvaW3
v2oIrCPH2f7bNY8q3yoHp7pE9/zB8XiWu1s50OxYP3NgZKM5MjUu7QA50B9RQDLZ2d+l0zHiRby3
6urV1vMHN/pTf0fBknXrhawHFD3lRMlLlhFQBodIUuienu960NTkbvA0DBVNMlg0X1a8Nm+tfiSr
x8AjrW8NX26sGW9SEisl5G2Vy+EpE1xH9+CgFNq9fP5Q6sqYzTLxjbgQ7pKWwKquttxo6bz1N+V5
1Xu3fjZip0zQEahDyGqvPV7ov//zv9DPh5DPHDnPRs/VX3+Zk4KYGC1ENzOBxKS8k4gKMoW7IsgQ
g2aZoGn9bKhnpRATzeB+j87Dj1I85ggD+pTggiTwsFS/fvpTK1LXHu235WopQEYZlHIMAfAMyvQN
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
def technicalReferenceMissing = detectionConfig.technicalReferenceMissing instanceof List ?
    [(imageStem): detectionConfig.technicalReferenceMissing.collect { it.toString() } as Set] : [:]
int configuredPositionCount = configuredRows * configuredColumns
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
    if (!usableGrid()) return
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
        if (!validateDetectionAgainstTechnicalReference('restore_approved_checkpoint')) return
    } else {
        runWorkflowScript(step1)
        if (!usableGrid()) {
            Dialogs.showErrorMessage('Skin TMA auto pipeline',
                'Detection did not create a usable grid. Inspect the Step 1 QC output.')
            return
        }
        if (!validateDetectionAgainstTechnicalReference('new_detection')) return
        println '=== PAUSED AT HUMAN REVIEW GATE ==='
        println 'Inspect the live grid and tma_grid_qc output. Add TMA correction / TMA mark missing annotations where needed, then run this one-click script again.'
        Dialogs.showInfoNotification('Skin TMA — review required',
            'Detection is complete. Inspect/correct the grid, then run this script again to approve and start/resume orientation.')
        if (!ALL_IN_ONE_INTEGRATION_TEST) return
    }
}

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
