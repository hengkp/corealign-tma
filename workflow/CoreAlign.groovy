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
        orientation: [algorithmVersion: 'generic-peripheral-orient-3.6-component-region-qc', analysisDownsample: 4.0d,
            exportDownsample: 1.0d, previewMaxPixels: 900, cropScale: 1.05d,
            rotationSupportScale: 1.45d, regionRefinementEnabled: true,
            regionSearchScale: 1.55d, regionMaxCenterShiftFraction: 0.30d,
            regionTissueMargin: 1.12d, regionMaxCropScale: 1.15d,
            regionReviewConfidence: 0.12d,
            saveFullResolutionPng: true, saveNativeOmeTiff: false,
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
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
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
H4sIAAAAAAACE8Va/XLbNhL/X08Be9yQbCQ6zUw7d0p8HsWWG/f8dbaSdi7JaWByJSElARYAbauu
Zu4h7gnvSW4WAEmQkmO3M53LHx2Z2F0sFru//UB3v/66R74mV5PxBfmWDMgR4zQjizKnnNCikOKG
ZkTMiF4ASRaQ/FwIxjWkREgGXFPNBCcSVJnpuEeMrMuSE8GzJaEzDZIwrgpINONzIuGGwW280Hm2
mwiuaaKJWgBoQnmK22VLJKN8iXK2xwVLQeZMEXEDUrIUtgnlXNhdVUzIqNKQKXItSp4SLQjcgFyS
AuQgERJQkmJzTnUpweyzrvkrkiwon+PeggNBNsL4Dc1YSjUowszZdns9lhdCapKIPJ4LMc8gnivB
4++V4F9ae1OyLAVZkfxSFlQv4oxdx0VWzhlXcUElzUGDVPFF9fOEKV1v+Zne0JgzEc9YBvERy0Bt
XrrSlKdUpgeiWJ4XeMgWnYKklEwv41NQis7hkM1B6RaJhjsdX7G8yOCQajgSMqe610thRj4rwcke
4XBLvHOFUaxAX0jQenkhGce7DqM4kUA1hJHhTERe0ET/0BbgFlmOmlBNyR6Zgz4opQSuj6uvYdRj
MxJ6VHuEl1kWkfseIYQcMpqJuYrVQtyOpRTSnS0MjDezX4FMTkf+vQd9EpwJuy/6jiiAx0FkpEnQ
peS9ldFMgbwBSfYaFeM56CvzNYx6eA/kmiogVqOelkunFHIXUnyGRNtTXdg/QrsLHqha3rLM5Nmz
igM3uaB6EUbVYlRts0Zhf6HBwijWAlUKo96KJFQnCxJOFlLc0usMCJtzISGNyP3K2NMKbJuy0b86
QykZ2XN2wK3eXR6rMIpnjKfknrCW9syodZUsIIcwQtkB+mRAVrVI3BhFdo+FHmFULyXzjuROg5yP
nGjTmbqyr5ZKQx7byyhA6mUYlApkvBA5BFGfBP8o0aiDyeloAHcYEkHU611pidBgXOCM5tCyxylo
mhonxb9wOYzI/pAEhjxoMV9pyCtfQspYQpHRBEZZFu6G+yz6GIscPsaazZ7v7PZJ4FyyRffhX6PB
P+ng1xeDv8bTwafnSDcNvBDBXWKmxnmhl2EUtbautDKOqzTVcMikb6L6B9quTwKd02nBCsgYh6lh
QDvVIl0IzCVLKyw2HzyJ1S59EtiEAukU6WMEk8AJmGGgPkGCoZt6kTytklRLHNwA1+oLcuojWUrD
nDkjbnVPEzNl/bCKEudHyvcjndPYaBfjNqVCiHlzcn7w9/Ghu8U/BlOThTVvk42bNIzQlTOlGJ9v
RC//IGTPoHc8kyJHFA7XTjkHPYE7HQbvJkeDv+A1n9IiTjKqVFS5MTK9pWqBkObxx9X3/VgLS+qi
wLmaDaY3nXj0fIyWWrh7ndaRZ1gzTMH6wpQdLVdtZPZJcDKajK8m06Pjs9HJ9PLdWazvagmy5NbL
W6LqWyX7jcw2RdcisZYsRzcYGltv1GR7576OjpVx9OnOfWWeWJXXyprnBZpXL+Kc8fCbl/3asnEG
fI64HkWrbXeAnHI2A6W7Dm0P1ieBLPm0ImoFQuMrnTivWRsKVfm/v11jpd9+I1stcTFTh0xCooVc
/j9io84p2+deRSdKXZQmMhjHoiMDDTHWpLtY6+VArjQU5CWZMal0/JHv3FtT4GWPrpXISg02saL5
10Kqss1aOLWM9sVQMuVQbUhTypG99k3FGbOSVBjtm1w7yjKTbpv7qPOtzTkx8FT9yPQiDJwHkNV+
rLCqu28RkhUG5odPHT1QB/cR8ALc3x1FY6DJgtyTGRn87YGCISnWbDP7skEqbl+Z169JUrSqhqSI
q/vE6kHLEqJK1devyfZxfd2eoCHZuZ81Z19t1yIdoFmHRPsXsW0G9p2TboAyX50PQSFFAgZ8p0YN
9GqLBO7vT7FpchhXoRUZNWfFf57ybS1Ra8vhFN5Q+0Dk2d2T9I5LoKkh8RLFunywxYuNssowpozi
GuEaEg3pgSi57mI9tkd2gWKQaQMaXT9R7Few1WtLljWAp+6R6dp27jfzr3yfeFWLQvV9qatto0ON
gO5St/ZIcHB+enEynowDxK+aoE5jW3s18HZVC7CPrcO9hSdESHINmeBzhQ0n5UIvQBpJgYVQK8av
wv5EdGx1tHWZcJ2J5GdIfZw8YncVCuoFKCBMQ64sFA4/8o98gJa1qn8WjIcBfgrWkdDAxCzX35E9
ck9uaFYCGfzNxVQ8Mx1jeCISmkH87qpPgq/i72ZB31FSRVJRXmcQEQuqCeWCs8QUKd6Fx7dML455
CnfYTYosw2bpniRFn7AKfz6wPjHIIOGq7vJNtPabkK4cwn1HxcNmkfJ5BlFjps5yIviMpcATiHyR
OeiFSK3IT85avwVRb1VbLqirJsvi6qZW441BeMyVpjyBMLh6Oxq8/Pa7qupPDUkY1t76nKBY8rwx
mGk63iw1qBpaHXNjr/atBF+9eHkX9LFze0Ze3M1mEXEqR73etRAZ1JMfbP0nNtcFCLdBDL+UNFPH
pu06oGpzR1X786glBd16RjMFqCIGyVrNv1ZnrycXuGPKjJLWUsyasCelHNSjkll5Cbato4uLy/P3
48MA02xN0FS/DWyQZ89aoG5ApGLwAvPS84E93yO+xF9IgQ10xdUgnL9gXDDaJKc5WoUKpyI1/XFg
JnwIimtMjzBioZ5TDekUa+S5tF0YVs3GVm3PiSI/S/0eAKwvoJ0yCxwvZRyhbA34Mkx8y0qD9JUz
MlmgkXbuG5N7VYCHatWfq6dNG5pYybJLM9eElOwR4+H1GlQTzAMEEZlvIJEwx2HmwwSzEjcwhSkT
/GE6d+76u8MeLjR+C2xi6lyPu5z2ETDUbS7cpH29ukHxeu1hnWuSRt36U6XqqHIx4rkYQRers1vQ
WxHIFPjDNpybKtfjtIaoYeNDMU3TCdMZ1ASbcqidUwdtNpPJG7btnfs6GrsV0YoUQjEzpH5FPDrX
q9dU7u8Wjd27JqnDzSlFZhmdq+3HVAs9eXODPoZ5f0heRLVsu2Qlog4evAilL0UFXBtYkWAgRTNF
Z2lJs0qUWYCBXgAfJFIUe/75HNNkAfxAigJBBT1g1TnTG+vW3j15XuoVNfgvOK5eGSB1438u+KAy
t5vn2zcM/9lh19m0wJlY38bNY1qsB8W6MoldagIIy0eqjQJaFGQm5ANqPlWLbvB9QQds2BhP2Y29
ImMMd/P4GmKAt3OfeGdP1eSBUH9QIUiNGST8UjIJqYGKgawF7HKq2U01MFI2FT5VF4cp3b3NUIi4
jEL0gqFYdIL1l6Bqp46EEV/aIVyOkIW3t/Y01XowMnvUk8kOkFhg9tRG3MOcZ59raEZomTJNqs9B
82Kw5T0dzUsWp64x8BuEWrD9GAadwQn577//427dPvPNqdnH4mcrYT81WR+Mzg7GJyetbO1l1dWG
JGN3M9Vr5xrfY4/Qjvbo4XT0qJwN8Ro9lL8eFbYWdtEj6e5RiQ+FT9RJko/by/l+5KfShqvjdI7J
uFg13bAvKFv+PeHMb4PZ8fOa+bxScushgyCjU/T3dsSX4x/GB5ONLfGPVHLG52dCsxlLjJOHgT8T
5ELXZaEHDgEO1cw+A5cIHEbZ92VCZYNTMRmlrafjXQTJOvoVuV2ABMIBUkgNsDaNNqleG9aG9Kb7
vZVMw0iLnCWmnTaDW03lHHS/GlNh2gKuq67XkuSFP9G1HN2nsz5pvrv533MSxDovBthIvnt3fBhL
ylOR48/QdY86L/BGTP/ktu6TqpHa0JrZmQ2CY4isWtgJar1582H9eTq+HF+cjA7G0/FPx1eT47Pv
29i7gWE0OT89Ppienr8fP+Fp8M9RMqrnZe6GKv8aaXcp3Rf0cHu5XC4Hp6eDNA0mwdu3wzwfKvXT
Tz9tR1VzjnyH5tHcjolbXS0OZBW+rdL3IBUTfEi+6bsB5tDrmPoedNjGbdht6Pe/3McNq/6w7x1r
6P22W9TvmMPmZ/OQMax/9cnGPnjodcFWoNfVDsnDva47YDYXkulFXluj5ugu9UldoXtU9Tcrz5Vh
XSr3ud9U4hYdj7DU9egsglhRM8oySM/lqeX1qDorfS8TPSS2U747S60V6F3GB0p4t2OnCPc3fKA+
t4y2LDsUt1xRdG+PsbvUrxPR0PzX7WweW+yj0ZA89PTyqeeB4vpsp2+HP1qsj34iNySLes0bsJkj
/ogC5agogKf1TMg+jbihQuj9PyqV7A/21Chq6ArJ6fnl8fhsMpocn59Nm6AjmuWgNM2L9UD5YyHh
2e5ThKPSPzA7aeYlfg18uKHyNWOSYXdOYiuC9tBgLf8e85loJ99uybkh+boJTj2uVvQGuwEhK318
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
