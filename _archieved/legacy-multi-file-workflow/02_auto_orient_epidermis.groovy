/**
 * STEP 2 - Auto-orient skin TMA cores so epidermis points upward.
 *
 * Run after a TMA grid has been created in QuPath. By default, the script
 * stops if no usable TMA grid is present, so row/column identity is preserved.
 *
 * What it does:
 *   1. Refines the region of each individual core independently.
 *   2. Estimates the epidermal surface normal and tangent.
 *   3. Reads a rotation-safe window from the original slide.
 *   4. Rotates that window first, then crops the final individual core.
 *   5. Saves lossless full-resolution PNG, native OME-TIFF source crop,
 *      previews, contact sheet, review HTML, CSV, and circular QuPath QC ROIs.
 *
 * Manual override:
 *   Draw a tiny annotation/dot on the epidermis side of a core and set its class
 *   or name to "Epidermis override". Re-run this script. The override dot wins
 *   for that core and is preserved.
 *
 * Notes:
 *   - PNG previews are for review/alignment, not quantitative pixel analysis.
 *   - The CSV records the rotation angle so original pixels remain traceable.
 */

import qupath.lib.common.ColorTools
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import qupath.lib.images.writers.ome.OMEPyramidWriter

import loci.common.services.ServiceFactory
import loci.formats.meta.IMetadata
import loci.formats.out.OMETiffWriter
import loci.formats.services.OMEXMLService
import ome.xml.model.enums.DimensionOrder
import ome.xml.model.enums.PixelType
import ome.xml.model.primitives.PositiveInteger

import com.google.gson.GsonBuilder

import javax.imageio.ImageIO
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.AffineTransformOp
import java.awt.geom.AffineTransform
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat

// -------------------------------------------------------------------------
// Operator-free defaults for this 0.6 mm skin CyCIF TMA.
// -------------------------------------------------------------------------

def cfgString = { String key, String fallback -> System.getProperty(key, fallback) }
def cfgInt = { String key, int fallback ->
    try { return Integer.parseInt(System.getProperty(key, fallback.toString())) }
    catch (Throwable ignored) { return fallback }
}
def cfgDouble = { String key, double fallback ->
    try { return Double.parseDouble(System.getProperty(key, fallback.toString())) }
    catch (Throwable ignored) { return fallback }
}
def cfgBool = { String key, boolean fallback ->
    return Boolean.parseBoolean(System.getProperty(key, fallback.toString()))
}
def cfgTokens = { String key, String fallback ->
    return cfgString(key, fallback).split(',').collect { it.trim().toLowerCase() }.findAll { !it.isEmpty() }
}

double ANALYSIS_DOWNSAMPLE = cfgDouble('tma.orientation.analysisDownsample', 4.0d)
double EXPORT_DOWNSAMPLE = cfgDouble('tma.orientation.exportDownsample', 1.0d)
int PREVIEW_MAX_PIXELS = cfgInt('tma.orientation.previewMaxPixels', 900)
double CROP_SCALE = cfgDouble('tma.orientation.cropScale', 1.05d)
double ROTATION_SUPPORT_SCALE = cfgDouble('tma.orientation.rotationSupportScale', 1.45d)
boolean REGION_REFINEMENT_ENABLED = cfgBool('tma.orientation.regionRefinementEnabled', true)
double REGION_SEARCH_SCALE = cfgDouble('tma.orientation.regionSearchScale', 1.55d)
double REGION_MAX_CENTER_SHIFT_FRACTION =
    cfgDouble('tma.orientation.regionMaxCenterShiftFraction', 0.30d)
double REGION_TISSUE_MARGIN = cfgDouble('tma.orientation.regionTissueMargin', 1.12d)
double REGION_MAX_CROP_SCALE = cfgDouble('tma.orientation.regionMaxCropScale', 1.15d)
double REGION_REVIEW_CONFIDENCE = cfgDouble('tma.orientation.regionReviewConfidence', 0.12d)
boolean SAVE_FULL_RESOLUTION_PNG = cfgBool('tma.orientation.saveFullResolutionPng', true)
boolean SAVE_NATIVE_OME_TIFF = cfgBool('tma.orientation.saveNativeOmeTiff', false)
boolean SAVE_ROTATED_MULTICHANNEL_OME_TIFF =
    cfgBool('tma.orientation.saveRotatedMultichannelOmeTiff', false)
def PRESENTATION_CHANNEL_TOKENS = cfgTokens('tma.presentation.channelTokens', '')
double DISPLAY_LOW_PERCENTILE = cfgDouble('tma.presentation.lowPercentile', 0.50d)
double DISPLAY_HIGH_PERCENTILE = cfgDouble('tma.presentation.highPercentile', 0.998d)
double DISPLAY_GAMMA = cfgDouble('tma.presentation.gamma', 0.85d)
int DISPLAY_MAX_CHANNELS = cfgInt('tma.presentation.maxChannels', 6)
String DISPLAY_RENDERER_VERSION = cfgString('tma.presentation.rendererVersion',
    'slide-color-2.0')
double POST_ROTATION_TOLERANCE_DEG = cfgDouble('tma.orientation.postRotationToleranceDeg', 12.0d)
int POST_ROTATION_MAX_ITERATIONS = cfgInt('tma.orientation.postRotationMaxIterations', 2)
String TEST_CORE_FILTER = System.getProperty('tma.orientation.testCoreFilter', '').trim()
boolean PARTIAL_CORE_TEST = !TEST_CORE_FILTER.isEmpty()
int N_SECTORS = cfgInt('tma.orientation.angularSectors', 72)
double OUTER_RING_INNER = cfgDouble('tma.orientation.outerRingInner', 0.42d)
double OUTER_RING_OUTER = cfgDouble('tma.orientation.outerRingOuter', 1.02d)
double TISSUE_THRESHOLD_SCALE = cfgDouble('tma.orientation.tissueThresholdScale', 0.55d)
double REVIEW_CONFIDENCE = cfgDouble('tma.orientation.reviewConfidence', 0.12d)
double OK_CONFIDENCE = cfgDouble('tma.orientation.okConfidence', 0.28d)
int SHEET_TILE = cfgInt('tma.orientation.sheetTile', 220)
int SHEET_LABEL_H = cfgInt('tma.orientation.sheetLabelHeight', 44)
int CONTACT_SHEET_MAX_WIDTH_PX = cfgInt('tma.orientation.contactSheetMaxWidth', 10000)
int CONTACT_SHEET_MIN_TILE = cfgInt('tma.orientation.contactSheetMinTile', 120)
int DEFAULT_GRID_COLS = cfgInt('tma.grid.columns', 7)
boolean ALLOW_ANNOTATION_FALLBACK_WITHOUT_GRID =
    cfgBool('tma.orientation.allowAnnotationFallbackWithoutGrid', false)
String ORIENTATION_ALGORITHM_VERSION = cfgString('tma.orientation.algorithmVersion',
    'skin-epidermis-orient-3.7-rotated-multichannel')
String CURRENT_PROFILE_HASH = System.getProperty('tma.config.profileHash', '')
String CURRENT_PROCESSING_HASH = System.getProperty('tma.config.processingHash',
    CURRENT_PROFILE_HASH)
String CURRENT_OUTPUT_HASH = System.getProperty('tma.config.outputHash',
    CURRENT_PROFILE_HASH)
String CURRENT_DETECTION_CONFIG_HASH = System.getProperty('tma.config.detectionHash',
    CURRENT_PROFILE_HASH)
def COMPATIBLE_LEGACY_PROFILE_HASHES =
    System.getProperty('tma.config.compatibleLegacyProfileHashes', CURRENT_PROFILE_HASH)
        .split(',').collect { it.trim() }.findAll { !it.isEmpty() } as Set
def json = new GsonBuilder().setPrettyPrinting().create()
File webCorrectionFile = new File(System.getProperty('corealign.project.root', '.'),
    'corealign-review-corrections.json')
def webCorrectionsByCore = [:]
String webCorrectionImageName = ''
if (webCorrectionFile.isFile()) {
    try {
        def webReview = json.fromJson(webCorrectionFile.getText('UTF-8'), Map.class)
        webCorrectionImageName = webReview?.image?.toString()?.trim() ?: ''
        if (webReview?.corrections instanceof List) {
            webReview.corrections.each { correction ->
                String key = correction?.core?.toString()?.trim()?.toLowerCase()
                if (key) webCorrectionsByCore[key] = correction
            }
        }
        println "Web review corrections loaded: ${webCorrectionsByCore.size()} from ${webCorrectionFile.getName()}"
    } catch (Throwable webReviewError) {
        println "WARNING: Ignoring invalid ${webCorrectionFile.getName()}: ${webReviewError.getMessage()}"
        webCorrectionsByCore.clear()
    }
}

String DETECTED_CLASS_NAME = 'TMA core (detected)'
String ORIENTATION_QC_CLASS_NAME = 'TMA orientation QC'
String LEGACY_DIRECTION_CLASS_NAME = 'Epidermis direction'
String OVERRIDE_CLASS_NAME = cfgString('tma.orientation.overrideClassName', 'Epidermis override')
String CROP_OVERRIDE_CLASS_NAME = cfgString('tma.orientation.cropOverrideClassName',
    'TMA crop override')

int COLOR_OK = ColorTools.packRGB(0, 210, 110)
int COLOR_REVIEW = ColorTools.packRGB(255, 180, 0)
int COLOR_FAIL = ColorTools.packRGB(245, 70, 70)
int COLOR_OVERRIDE = ColorTools.packRGB(185, 95, 255)

// -------------------------------------------------------------------------
// Basic setup.
// -------------------------------------------------------------------------

def imageData = getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('Auto-orient TMA', 'No image is open. Open the OME-TIFF first.')
    return
}
System.setProperty('tma.orientation.status', 'RUNNING')
long runStartedAtMs = System.currentTimeMillis()
String runStartedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(runStartedAtMs))

def server = imageData.getServer()
def hierarchy = imageData.getHierarchy()
if (!webCorrectionImageName.isEmpty() &&
        webCorrectionImageName != server.getMetadata().getName()) {
    println "WARNING: ${webCorrectionFile.getName()} belongs to ${webCorrectionImageName}; " +
        "ignoring it for ${server.getMetadata().getName()}."
    webCorrectionsByCore.clear()
}
def channelObjs = server.getMetadata().getChannels()
def channelNames = channelObjs.collect { it.getName() }
int imgW = server.getWidth()
int imgH = server.getHeight()

println '=== Step 2: Auto-orient epidermis ==='
println "Image: ${server.getMetadata().getName()}"
println "Dimensions: ${imgW} x ${imgH} px"
println "Channels (${channelNames.size()}): ${channelNames}"

def dapiIdx = []
def epidermisMarkerIdx = []
def rgbRedIdx = []
def rgbGreenIdx = []
def rgbBlueIdx = []

def nuclearTokens = cfgTokens('tma.orientation.nuclearChannelTokens',
    'dapi,hoechst,draq,to-pro,topro,syto,nuclei,nuclear')
def epidermisTokens = cfgTokens('tma.orientation.epidermisChannelTokens',
    'cpd,h2ax,p53,8ohdg,ki67,nrf2,ho1,mmp1,keratin,cytokeratin,panck,pan-ck,krt,epcam,e-cadherin,ecadherin')
def redTokens = cfgTokens('tma.orientation.rgbRedChannelTokens',
    'cpd,h2ax,p53,8ohdg,keratin,cytokeratin,panck,pan-ck,krt,epcam,e-cadherin,ecadherin')
def greenTokens = cfgTokens('tma.orientation.rgbGreenChannelTokens', 'ki67,nrf2,ho1,mmp1')
def containsAnyToken = { String name, List tokens ->
    String n = (name ?: '').toLowerCase()
    return tokens.any { token -> !token.isEmpty() && n.contains(token) }
}

def channelNameLooksNuclear = { String name ->
    return containsAnyToken(name, nuclearTokens)
}

def channelNameLooksEpidermal = { String name ->
    return containsAnyToken(name, epidermisTokens)
}

channelNames.eachWithIndex { name, i ->
    String n = (name ?: '').toLowerCase()
    if (channelNameLooksNuclear(name)) {
        dapiIdx << i
        rgbBlueIdx << i
    }
    if (channelNameLooksEpidermal(name) && containsAnyToken(name, redTokens)) {
        epidermisMarkerIdx << i
        rgbRedIdx << i
    }
    if (channelNameLooksEpidermal(name) && containsAnyToken(name, greenTokens)) {
        rgbGreenIdx << i
    }
}
dapiIdx = dapiIdx.unique()
epidermisMarkerIdx = epidermisMarkerIdx.unique()
rgbRedIdx = rgbRedIdx.unique()
rgbGreenIdx = rgbGreenIdx.unique()
rgbBlueIdx = rgbBlueIdx.unique()

if (dapiIdx.isEmpty()) {
    println 'WARNING: No nuclear/DAPI channel found. Falling back to all non-AF channels for tissue detection.'
    channelNames.eachWithIndex { name, i ->
        if (!(name ?: '').toLowerCase().startsWith('af')) dapiIdx << i
    }
}
if (dapiIdx.isEmpty()) dapiIdx = (0..<channelNames.size()).toList()
if (epidermisMarkerIdx.isEmpty()) epidermisMarkerIdx = []
if (rgbBlueIdx.isEmpty()) rgbBlueIdx = dapiIdx
if (rgbRedIdx.isEmpty()) rgbRedIdx = epidermisMarkerIdx
if (rgbGreenIdx.isEmpty()) rgbGreenIdx = epidermisMarkerIdx

println "Tissue channels: ${dapiIdx.collect { channelNames[it] }}"
println "Epidermis helper markers: ${epidermisMarkerIdx.collect { channelNames[it] }}"

// -------------------------------------------------------------------------
// Output folder.
// -------------------------------------------------------------------------

def projectDir = null
try {
    def project = getProject()
    if (project != null && project.getPath() != null)
        projectDir = project.getPath().getParent().toFile()
} catch (Throwable ignored) {}
if (projectDir == null)
    try {
        def fileUri = server.getURIs().find { it != null && it.getScheme() == 'file' }
        if (fileUri != null) projectDir = new File(fileUri).getParentFile()
    } catch (Throwable ignored) {}
if (projectDir == null)
    projectDir = new File(System.getProperty('user.home'), 'QuPath-TMA-export')

String imageStem = server.getMetadata().getName()
if (imageStem == null || imageStem.trim().isEmpty())
    imageStem = server.getPath() == null ? 'image' : new File(server.getPath()).getName()
imageStem = imageStem.replaceAll(/(?i)\.ome\.tif+$/, '')
imageStem = imageStem.replaceAll(/[^A-Za-z0-9._-]+/, '_')
if (imageStem.isEmpty()) imageStem = 'image'
def exportBaseDir = new File(System.getProperty('corealign.work.runBaseDir',
    new File(projectDir, 'tma_auto_orient_export').getAbsolutePath()))
def stateDir = new File(System.getProperty('corealign.work.stateDir',
    new File(new File(projectDir, 'tma_pipeline_state'), imageStem).getAbsolutePath()))
def approvalFile = new File(stateDir, 'approved_grid.json')

// -------------------------------------------------------------------------
// Helpers.
// -------------------------------------------------------------------------

def classNameOf = { obj ->
    try {
        return obj.getPathClass()?.getName()
    } catch (Throwable ignored) {
        return null
    }
}

def objectNameOf = { obj ->
    try {
        return obj.getName()
    } catch (Throwable ignored) {
        return null
    }
}

def getRoiCenterAndDiameter = { obj ->
    def roi = obj.getROI()
    double cx = roi.getCentroidX()
    double cy = roi.getCentroidY()
    double rawDiameter = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight())
    return [cx: cx, cy: cy, rawDiameter: rawDiameter,
        cropSide: rawDiameter * CROP_SCALE]
}

def clamp01 = { double v ->
    if (v < 0.0d) return 0.0d
    if (v > 1.0d) return 1.0d
    return v
}

def normalizeRadians = { double a ->
    while (a <= -Math.PI) a += 2.0d * Math.PI
    while (a > Math.PI) a -= 2.0d * Math.PI
    return a
}

def angleToDegrees = { double a ->
    double d = Math.toDegrees(a)
    while (d <= -180.0d) d += 360.0d
    while (d > 180.0d) d -= 360.0d
    return d
}

def csv = { value ->
    if (value == null) return ''
    String s = value.toString()
    if (s.contains('"') || s.contains(',') || s.contains('\n'))
        return '"' + s.replace('"', '""') + '"'
    return s
}

def htmlEscape = { value ->
    if (value == null) return ''
    return value.toString()
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
        .replace("'", '&#39;')
}

def format3 = { double v -> String.format(Locale.US, '%.3f', v) }
def format1 = { double v -> String.format(Locale.US, '%.1f', v) }
def sha256 = { String value ->
    byte[] digest = MessageDigest.getInstance('SHA-256').digest(value.getBytes('UTF-8'))
    return digest.collect { String.format('%02x', it & 0xff) }.join()
}
def writeAtomic = { File target, String content ->
    target.getParentFile().mkdirs()
    File tmp = new File(target.getParentFile(), target.getName() + '.tmp-' + UUID.randomUUID())
    tmp.setText(content, 'UTF-8')
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
    } catch (Throwable ignored) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
def canonicalGrid = { g ->
    def lines = ["width=${g.getGridWidth()}|height=${g.getGridHeight()}"]
    g.getTMACoreList().eachWithIndex { core, i ->
        def roi = core.getROI()
        double rawDiameter = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight())
        lines << [i, core.getName() ?: '', format3(roi.getCentroidX()),
            format3(roi.getCentroidY()), format3(rawDiameter), core.isMissing()].join('|')
    }
    return lines.join('\n')
}

def makePathClass = { String name, int color ->
    try {
        return PathClass.fromString(name, color)
    } catch (Throwable ignored) {
        return PathClass.fromString(name)
    }
}

def percentilePositive = { float[] data, double p ->
    def vals = []
    int step = Math.max(1, (int) Math.floor(data.length / 200000))
    for (int i = 0; i < data.length; i += step) {
        float v = data[i]
        if (v > 0) vals << v
    }
    if (vals.isEmpty()) return 1.0d
    vals.sort()
    int idx = (int) Math.max(0, Math.min(vals.size() - 1, Math.round((vals.size() - 1) * p)))
    return Math.max(1.0d, vals[idx] as double)
}

def otsuThreshold = { float[] data ->
    if (data.length == 0) return 0.0d
    float minV = Float.POSITIVE_INFINITY
    float maxV = Float.NEGATIVE_INFINITY
    for (int i = 0; i < data.length; i++) {
        float v = data[i]
        if (v < minV) minV = v
        if (v > maxV) maxV = v
    }
    if (maxV <= minV) return (double) maxV
    int nBins = 256
    int[] hist = new int[nBins]
    double range = maxV - minV
    for (int i = 0; i < data.length; i++) {
        int bin = (int) Math.min(nBins - 1, Math.max(0, ((data[i] - minV) / range) * (nBins - 1)))
        hist[bin]++
    }
    long total = data.length
    double sumAll = 0
    for (int i = 0; i < nBins; i++) sumAll += i * hist[i]
    long wB = 0
    double sumB = 0
    double maxVar = 0
    int bestBin = 0
    for (int t = 0; t < nBins; t++) {
        wB += hist[t]
        if (wB == 0) continue
        long wF = total - wB
        if (wF == 0) break
        sumB += t * hist[t]
        double mB = sumB / wB
        double mF = (sumAll - sumB) / wF
        double v = wB * (double) wF * (mB - mF) * (mB - mF)
        if (v > maxVar) {
            maxVar = v
            bestBin = t
        }
    }
    return minV + (bestBin / (double)(nBins - 1)) * range
}

def readSquare = { double cx, double cy, double side, double downsample ->
    int x = (int) Math.floor(cx - side / 2.0d)
    int y = (int) Math.floor(cy - side / 2.0d)
    int w = (int) Math.ceil(side)
    int h = (int) Math.ceil(side)
    if (x < 0) {
        w += x
        x = 0
    }
    if (y < 0) {
        h += y
        y = 0
    }
    if (x + w > imgW) w = imgW - x
    if (y + h > imgH) h = imgH - y
    w = Math.max(1, w)
    h = Math.max(1, h)
    def request = RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h)
    def img = server.readRegion(request)
    return [image: img, originX: x, originY: y, downsample: downsample]
}

def buildProjection = { BufferedImage img, List<Integer> channels ->
    def raster = img.getRaster()
    int w = raster.getWidth()
    int h = raster.getHeight()
    int n = w * h
    int bands = raster.getNumBands()
    float[] out = new float[n]
    float[] buf = new float[n]
    channels.findAll { it >= 0 && it < bands }.each { ci ->
        raster.getSamples(0, 0, w, h, ci, buf)
        double baseline = percentilePositive(buf, 0.20d)
        double high = percentilePositive(buf, 0.995d)
        double scale = Math.max(1e-9d, high - baseline)
        for (int i = 0; i < n; i++) {
            float v = buf[i]
            if (v > baseline)
                out[i] += (float) Math.sqrt(Math.min(1.0d, (v - baseline) / scale))
        }
    }
    return out
}

def makeRgb = { BufferedImage img ->
    int w = img.getWidth()
    int h = img.getHeight()
    int n = w * h
    float[] red = buildProjection(img, rgbRedIdx)
    float[] green = buildProjection(img, rgbGreenIdx)
    float[] blue = buildProjection(img, rgbBlueIdx)

    boolean redEmpty = red.every { it == 0.0f }
    boolean greenEmpty = green.every { it == 0.0f }
    if (redEmpty && greenEmpty) {
        red = blue
        green = blue
    } else {
        if (redEmpty) red = green
        if (greenEmpty) green = blue
    }

    double rMax = percentilePositive(red, 0.995)
    double gMax = percentilePositive(green, 0.995)
    double bMax = percentilePositive(blue, 0.995)

    def out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (int y = 0; y < h; y++) {
        int row = y * w
        for (int x = 0; x < w; x++) {
            int i = row + x
            int r = (int) Math.round(255.0d * Math.sqrt(clamp01(red[i] / rMax)))
            int g = (int) Math.round(255.0d * Math.sqrt(clamp01(green[i] / gMax)))
            int b = (int) Math.round(255.0d * Math.sqrt(clamp01(blue[i] / bMax)))
            out.setRGB(x, y, (r << 16) | (g << 8) | b)
        }
    }
    return out
}

// Presentation and QC rendering deliberately uses one calibration for the
// entire slide. Per-core normalization makes background noise look different
// in every card and prevents meaningful visual comparison between cores.
// Orientation scoring keeps using makeRgb above; research OME-TIFF values are
// written from the source raster and are never display-graded.
def displayPalette = [
    [name: 'blue', rgb: [55, 126, 255]],
    [name: 'magenta', rgb: [255, 65, 176]],
    [name: 'green', rgb: [49, 224, 142]],
    [name: 'yellow', rgb: [255, 205, 64]],
    [name: 'cyan', rgb: [45, 210, 238]],
    [name: 'red', rgb: [255, 88, 88]]
]
def displayStats = []
try {
    double overviewDownsample = Math.max(1.0d,
        Math.max(imgW, imgH) / 1500.0d)
    def overviewRequest = RegionRequest.createInstance(server.getPath(),
        overviewDownsample, 0, 0, imgW, imgH)
    def overview = server.readRegion(overviewRequest)
    def overviewRaster = overview.getRaster()
    int overviewW = overviewRaster.getWidth()
    int overviewH = overviewRaster.getHeight()
    int overviewN = overviewW * overviewH
    int overviewBands = Math.min(overviewRaster.getNumBands(), channelNames.size())
    float[] overviewBuffer = new float[overviewN]
    for (int band = 0; band < overviewBands; band++) {
        overviewRaster.getSamples(0, 0, overviewW, overviewH, band, overviewBuffer)
        double low = percentilePositive(overviewBuffer,
            Math.max(0.0d, Math.min(0.95d, DISPLAY_LOW_PERCENTILE)))
        double high = percentilePositive(overviewBuffer,
            Math.max(DISPLAY_LOW_PERCENTILE + 0.01d,
                Math.min(0.9999d, DISPLAY_HIGH_PERCENTILE)))
        double span = Math.max(1.0d, high - low)
        displayStats << [index: band, name: channelNames[band], low: low,
            high: Math.max(low + 1.0d, high), span: span]
    }
} catch (Throwable calibrationError) {
    println "WARNING: Slide display calibration fell back to image type ranges: ${calibrationError.getMessage()}"
    int availableBands = Math.min(channelNames.size(),
        Math.max(1, server.getMetadata().getChannels().size()))
    double fallbackHigh = 65535.0d
    try {
        int bits = server.getPixelType()?.getBitsPerPixel() ?: 16
        fallbackHigh = bits <= 8 ? 255.0d : Math.pow(2.0d, Math.min(24, bits)) - 1.0d
    } catch (Throwable ignored) {}
    for (int band = 0; band < availableBands; band++)
        displayStats << [index: band, name: channelNames[band], low: 0.0d,
            high: fallbackHigh, span: fallbackHigh]
}

def selectedDisplayStats = []
if (!PRESENTATION_CHANNEL_TOKENS.isEmpty()) {
    PRESENTATION_CHANNEL_TOKENS.each { token ->
        def exact = displayStats.find {
            (it.name ?: '').toString().trim().equalsIgnoreCase(token)
        }
        def matched = exact ?: displayStats.find {
            (it.name ?: '').toString().toLowerCase().contains(token)
        }
        if (matched != null && !selectedDisplayStats.any { it.index == matched.index })
            selectedDisplayStats << matched
    }
    if (selectedDisplayStats.isEmpty())
        println "WARNING: Requested presentation markers ${PRESENTATION_CHANNEL_TOKENS} were not found. Automatic channels will be used."
}
if (selectedDisplayStats.isEmpty()) {
    def nuclear = displayStats.find { channelNameLooksNuclear(it.name?.toString()) }
    if (nuclear != null) selectedDisplayStats << nuclear
    def nonAf = displayStats.findAll {
        String name = (it.name ?: '').toString().toLowerCase()
        !name.startsWith('af') && !channelNameLooksNuclear(name) &&
            !selectedDisplayStats.any { chosen -> chosen.index == it.index }
    }
    def epidermal = nonAf.findAll { channelNameLooksEpidermal(it.name?.toString()) }
    epidermal.take(1).each { selectedDisplayStats << it }
    nonAf.sort { a, b -> (b.span as double) <=> (a.span as double) }.each { candidate ->
        if (selectedDisplayStats.size() < 3 &&
                !selectedDisplayStats.any { it.index == candidate.index })
            selectedDisplayStats << candidate
    }
}
if (selectedDisplayStats.isEmpty() && !displayStats.isEmpty())
    selectedDisplayStats << displayStats[0]
selectedDisplayStats = selectedDisplayStats.take(
    Math.max(1, Math.min(displayPalette.size(), DISPLAY_MAX_CHANNELS)))
int displayNonNuclearPaletteIndex = 1
selectedDisplayStats.each { stat ->
    def palette = channelNameLooksNuclear(stat.name?.toString()) ? displayPalette[0] :
        displayPalette[Math.min(displayNonNuclearPaletteIndex++, displayPalette.size() - 1)]
    stat.colorName = palette.name
    stat.rgb = palette.rgb
}
println 'Presentation PNG rendering: shared slide-level ranges'
selectedDisplayStats.each { stat ->
    println "  ${stat.name} -> ${stat.colorName}; range ${format3(stat.low as double)} to ${format3(stat.high as double)}"
}

def makeDisplayRgb = { BufferedImage img ->
    int w = img.getWidth()
    int h = img.getHeight()
    int n = w * h
    def raster = img.getRaster()
    int bands = raster.getNumBands()
    int[] hue = new int[n]
    float[] total = new float[n]
    float[] samples = new float[n]
    selectedDisplayStats.findAll { (it.index as int) < bands }.each { stat ->
        int band = stat.index as int
        def rgb = stat.rgb as List
        double low = stat.low as double
        double range = Math.max(1e-9d, (stat.high as double) - low)
        raster.getSamples(0, 0, w, h, band, samples)
        for (int i = 0; i < n; i++) {
            double normalized = clamp01((samples[i] - low) / range)
            if (normalized <= 0.02d) continue
            normalized = (normalized - 0.02d) / 0.98d
            double signal = Math.pow(normalized, DISPLAY_GAMMA)
            double previous = total[i]
            double combined = previous + signal
            int old = hue[i]
            int oldR = (old >> 16) & 0xff
            int oldG = (old >> 8) & 0xff
            int oldB = old & 0xff
            int mixedR = (int) Math.round((oldR * previous + (rgb[0] as int) * signal) / combined)
            int mixedG = (int) Math.round((oldG * previous + (rgb[1] as int) * signal) / combined)
            int mixedB = (int) Math.round((oldB * previous + (rgb[2] as int) * signal) / combined)
            hue[i] = (mixedR << 16) | (mixedG << 8) | mixedB
            total[i] = (float) combined
        }
    }
    def out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    int[] pixels = new int[n]
    for (int i = 0; i < n; i++) {
        if (total[i] <= 0.0f) continue
        int mixed = hue[i]
        int mixedR = (mixed >> 16) & 0xff
        int mixedG = (mixed >> 8) & 0xff
        int mixedB = mixed & 0xff
        int peak = Math.max(1, Math.max(mixedR, Math.max(mixedG, mixedB)))
        double brightness = Math.min(0.94d,
            1.0d - Math.exp(-1.45d * total[i]))
        int r = Math.max(0, Math.min(242, (int) Math.round(
            255.0d * brightness * Math.pow(mixedR / (double) peak, 0.90d))))
        int g = Math.max(0, Math.min(242, (int) Math.round(
            255.0d * brightness * Math.pow(mixedG / (double) peak, 0.90d))))
        int b = Math.max(0, Math.min(242, (int) Math.round(
            255.0d * brightness * Math.pow(mixedB / (double) peak, 0.90d))))
        pixels[i] = (r << 16) | (g << 8) | b
    }
    out.setRGB(0, 0, w, h, pixels, 0, w)
    return out
}

def rotateImageAround = { BufferedImage src, double radians, double cxImg, double cyImg ->
    def out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB)
    Graphics2D g = out.createGraphics()
    g.setColor(Color.BLACK)
    g.fillRect(0, 0, out.getWidth(), out.getHeight())
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.rotate(radians, cxImg, cyImg)
    g.drawImage(src, 0, 0, null)
    g.dispose()
    return out
}

def cropAround = { BufferedImage src, double cxImg, double cyImg, int sidePx ->
    int side = Math.max(1, Math.min(sidePx, Math.min(src.getWidth(), src.getHeight())))
    int x = (int) Math.round(cxImg - side / 2.0d)
    int y = (int) Math.round(cyImg - side / 2.0d)
    x = Math.max(0, Math.min(src.getWidth() - side, x))
    y = Math.max(0, Math.min(src.getHeight() - side, y))
    def out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    Graphics2D g = out.createGraphics()
    g.setColor(Color.BLACK)
    g.fillRect(0, 0, side, side)
    g.drawImage(src, 0, 0, side, side, x, y, x + side, y + side, null)
    g.dispose()
    return out
}

// Bake the final per-core rotation into every original channel without first
// cropping the core. Each channel is rotated from the same oversized support
// image, then the final square is cropped and written as planar OME-TIFF.
// UINT8 and UINT16 are supported losslessly with respect to bit depth; the
// geometric transform uses bicubic interpolation, matching the visual export.
def writeRotatedMultichannelOme = { BufferedImage sourceSupport,
        double radians, double cxImg, double cyImg, int sidePx, File destination ->
    String sourcePixelType = server.getPixelType().toString()
    boolean isU8 = sourcePixelType == 'UINT8'
    boolean isU16 = sourcePixelType == 'UINT16'
    if (!isU8 && !isU16)
        throw new IllegalArgumentException(
            "Rotated multichannel OME-TIFF supports UINT8/UINT16; found ${sourcePixelType}")
    int bands = sourceSupport.getRaster().getNumBands()
    int channels = Math.min(server.nChannels(), bands)
    if (channels < 1)
        throw new IllegalStateException('Source support image has no readable channels')
    int w = sourceSupport.getWidth(), h = sourceSupport.getHeight()
    int side = Math.max(1, Math.min(sidePx, Math.min(w, h)))
    int cropX = Math.max(0, Math.min(w - side,
        (int) Math.round(cxImg - side / 2.0d)))
    int cropY = Math.max(0, Math.min(h - side,
        (int) Math.round(cyImg - side / 2.0d)))

    def service = new ServiceFactory().getInstance(OMEXMLService.class)
    IMetadata metadata = service.createOMEXMLMetadata()
    metadata.setImageID('Image:0', 0)
    metadata.setImageName(destination.getName(), 0)
    metadata.setPixelsID('Pixels:0', 0)
    metadata.setPixelsDimensionOrder(DimensionOrder.XYCZT, 0)
    metadata.setPixelsType(isU8 ? PixelType.UINT8 : PixelType.UINT16, 0)
    metadata.setPixelsSizeX(new PositiveInteger(side), 0)
    metadata.setPixelsSizeY(new PositiveInteger(side), 0)
    metadata.setPixelsSizeZ(new PositiveInteger(1), 0)
    metadata.setPixelsSizeC(new PositiveInteger(channels), 0)
    metadata.setPixelsSizeT(new PositiveInteger(1), 0)
    try { metadata.setPixelsBigEndian(Boolean.FALSE, 0) } catch (Throwable ignored) {}
    for (int c = 0; c < channels; c++) {
        metadata.setChannelID("Channel:0:${c}", 0, c)
        metadata.setChannelName(server.getMetadata().getChannels()[c].getName(), 0, c)
        metadata.setChannelSamplesPerPixel(new PositiveInteger(1), 0, c)
    }

    def writer = new OMETiffWriter()
    try {
        writer.setMetadataRetrieve(metadata)
        writer.setInterleaved(false)
        try { writer.setCompression('zlib') } catch (Throwable ignored) {}
        writer.setId(destination.getAbsolutePath())
        def transform = AffineTransform.getRotateInstance(radians, cxImg, cyImg)
        int imageType = isU8 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_USHORT_GRAY
        int bytesPerSample = isU8 ? 1 : 2
        int[] supportRow = new int[w]
        int[] cropRow = new int[side]
        for (int c = 0; c < channels; c++) {
            def channelImage = new BufferedImage(w, h, imageType)
            def channelRaster = channelImage.getRaster()
            for (int y = 0; y < h; y++) {
                sourceSupport.getRaster().getSamples(0, y, w, 1, c, supportRow)
                channelRaster.setSamples(0, y, w, 1, 0, supportRow)
            }
            def rotatedChannel = new BufferedImage(w, h, imageType)
            new AffineTransformOp(transform, AffineTransformOp.TYPE_BICUBIC)
                .filter(channelImage, rotatedChannel)
            byte[] plane = new byte[side * side * bytesPerSample]
            int offset = 0
            for (int y = 0; y < side; y++) {
                rotatedChannel.getRaster().getSamples(cropX, cropY + y,
                    side, 1, 0, cropRow)
                for (int x = 0; x < side; x++) {
                    int value = cropRow[x]
                    plane[offset++] = (byte) (value & 0xff)
                    if (!isU8) plane[offset++] = (byte) ((value >> 8) & 0xff)
                }
            }
            writer.saveBytes(c, plane)
        }
    } finally {
        try { writer.close() } catch (Throwable ignored) {}
    }
    return destination.isFile() && destination.length() > 0
}

def scaleForPreview = { BufferedImage src, int maxPixels ->
    if (src.getWidth() <= maxPixels && src.getHeight() <= maxPixels) return src
    double scale = Math.min(maxPixels / (double) src.getWidth(),
        maxPixels / (double) src.getHeight())
    int w = Math.max(1, (int) Math.round(src.getWidth() * scale))
    int h = Math.max(1, (int) Math.round(src.getHeight() * scale))
    def out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    Graphics2D g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return out
}

// Estimate the epidermal surface normal from bright/colocalized pixels that
// lie on the tissue boundary.  PCA supplies the epidermal tangent; the sign of
// the perpendicular normal is chosen from tissue centroid toward epidermis.
// This straightens the surface rather than merely moving a bright centroid up.
def scoreRgbBoundaryPca = { BufferedImage rgb, double cxImg, double cyImg ->
    int w = rgb.getWidth()
    int h = rgb.getHeight()
    int n = w * h
    float[] brightness = new float[n]
    float[] whiteness = new float[n]
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int value = rgb.getRGB(x, y)
            int r = (value >> 16) & 0xFF
            int g = (value >> 8) & 0xFF
            int b = value & 0xFF
            int i = y * w + x
            brightness[i] = Math.max(r, Math.max(g, b))
            whiteness[i] = Math.min(r, Math.min(g, b))
        }
    }
    double tissueThreshold = Math.max(8.0d, otsuThreshold(brightness) * 0.82d)
    double whiteThreshold = percentilePositive(whiteness, 0.88d)
    double rMax = Math.min(Math.min(cxImg, cyImg),
        Math.min(w - cxImg - 1.0d, h - cyImg - 1.0d))
    if (rMax < 10.0d) return null

    boolean[] tissue = new boolean[n]
    int tissueCount = 0
    double tissueX = 0.0d, tissueY = 0.0d
    for (int y = 0; y < h; y++) {
        double dy = y + 0.5d - cyImg
        for (int x = 0; x < w; x++) {
            double dx = x + 0.5d - cxImg
            if (dx * dx + dy * dy > rMax * rMax) continue
            int i = y * w + x
            if (brightness[i] >= tissueThreshold) {
                tissue[i] = true
                tissueCount++
                tissueX += x + 0.5d
                tissueY += y + 0.5d
            }
        }
    }
    if (tissueCount < 30) return null
    double tcx = tissueX / tissueCount
    double tcy = tissueY / tissueCount
    int boundaryStep = Math.max(2, (int) Math.round(Math.min(w, h) / 150.0d))
    int[][] dirs = [[1,0],[-1,0],[0,1],[0,-1],[1,1],[1,-1],[-1,1],[-1,-1]] as int[][]
    def points = []
    double weightSum = 0.0d, epiX = 0.0d, epiY = 0.0d
    for (int y = boundaryStep; y < h - boundaryStep; y++) {
        for (int x = boundaryStep; x < w - boundaryStep; x++) {
            int i = y * w + x
            if (!tissue[i] || whiteness[i] < whiteThreshold) continue
            boolean boundary = dirs.any { d ->
                int xx = x + d[0] * boundaryStep
                int yy = y + d[1] * boundaryStep
                return !tissue[yy * w + xx]
            }
            if (!boundary) continue
            double signal = Math.max(1.0d, whiteness[i] - whiteThreshold + 1.0d)
            double weight = signal * signal * Math.max(0.25d, brightness[i] / 255.0d)
            points << [x: x + 0.5d, y: y + 0.5d, weight: weight]
            weightSum += weight
            epiX += (x + 0.5d) * weight
            epiY += (y + 0.5d) * weight
        }
    }
    if (points.size() < 12 || weightSum <= 0.0d) return null
    double ecx = epiX / weightSum
    double ecy = epiY / weightSum
    double cxx = 0.0d, cyy = 0.0d, cxy = 0.0d
    points.each { p ->
        double dx = p.x - ecx
        double dy = p.y - ecy
        cxx += p.weight * dx * dx
        cyy += p.weight * dy * dy
        cxy += p.weight * dx * dy
    }
    cxx /= weightSum; cyy /= weightSum; cxy /= weightSum
    double trace = cxx + cyy
    double disc = Math.sqrt(Math.max(0.0d, (cxx - cyy) * (cxx - cyy) + 4.0d * cxy * cxy))
    double lambda1 = (trace + disc) / 2.0d
    double lambda2 = (trace - disc) / 2.0d
    if (lambda1 <= 1e-6d) return null
    double tangentAngle = 0.5d * Math.atan2(2.0d * cxy, cxx - cyy)
    double nx = -Math.sin(tangentAngle)
    double ny = Math.cos(tangentAngle)
    double vx = ecx - tcx
    double vy = ecy - tcy
    if (nx * vx + ny * vy < 0.0d) { nx = -nx; ny = -ny }
    double separation = Math.sqrt(vx * vx + vy * vy) / Math.max(1.0d, rMax)
    double anisotropy = clamp01((lambda1 - Math.max(0.0d, lambda2)) /
        Math.max(1e-9d, lambda1 + Math.max(0.0d, lambda2)))
    double evidence = clamp01(points.size() / 80.0d)
    double confidence = clamp01(0.40d * evidence + 0.35d * clamp01(separation * 2.0d) +
        0.25d * anisotropy)
    double angle = normalizeRadians(Math.atan2(ny, nx))
    String status = confidence >= OK_CONFIDENCE ? 'ok' :
        (confidence >= REVIEW_CONFIDENCE ? 'review' : 'uncertain')
    return [status: status, angle: angle, confidence: confidence,
        tissueFraction: tissueCount / (Math.PI * rMax * rMax),
        method: 'rgb_boundary_pca', tangentAnisotropy: anisotropy,
        epidermisPoints: points.size()]
}

// Segment bright epithelial bands into connected components.  The dominant
// elongated component is more robust than a global bright-pixel centroid when
// internal debris or isolated marker-positive islands are present.
def scoreRgbComponentPca = { BufferedImage rgb, double cxImg, double cyImg ->
    int w = rgb.getWidth()
    int h = rgb.getHeight()
    int n = w * h
    float[] brightness = new float[n]
    float[] whiteness = new float[n]
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int value = rgb.getRGB(x, y)
            int r = (value >> 16) & 0xFF
            int g = (value >> 8) & 0xFF
            int b = value & 0xFF
            int i = y * w + x
            brightness[i] = Math.max(r, Math.max(g, b))
            whiteness[i] = Math.min(r, Math.min(g, b))
        }
    }
    double tissueThreshold = Math.max(8.0d, otsuThreshold(brightness) * 0.82d)
    double epiThreshold = percentilePositive(whiteness, 0.94d)
    double rMax = Math.min(Math.min(cxImg, cyImg),
        Math.min(w - cxImg - 1.0d, h - cyImg - 1.0d))
    if (rMax < 10.0d) return null
    boolean[] tissue = new boolean[n]
    boolean[] epi = new boolean[n]
    int tissueCount = 0
    double tissueX = 0.0d, tissueY = 0.0d
    for (int y = 0; y < h; y++) {
        double dy = y + 0.5d - cyImg
        for (int x = 0; x < w; x++) {
            double dx = x + 0.5d - cxImg
            if (dx * dx + dy * dy > rMax * rMax) continue
            int i = y * w + x
            if (brightness[i] >= tissueThreshold) {
                tissue[i] = true
                tissueCount++
                tissueX += x + 0.5d
                tissueY += y + 0.5d
                if (whiteness[i] >= epiThreshold) epi[i] = true
            }
        }
    }
    if (tissueCount < 30) return null
    double tcx = tissueX / tissueCount
    double tcy = tissueY / tissueCount
    boolean[] visited = new boolean[n]
    int[] queue = new int[n]
    def components = []
    int[] ddx = [-1, 0, 1, -1, 1, -1, 0, 1]
    int[] ddy = [-1, -1, -1, 0, 0, 1, 1, 1]
    for (int seed = 0; seed < n; seed++) {
        if (!epi[seed] || visited[seed]) continue
        int head = 0, tail = 0
        queue[tail++] = seed
        visited[seed] = true
        int count = 0
        double sx = 0.0d, sy = 0.0d, sxx = 0.0d, syy = 0.0d, sxy = 0.0d
        while (head < tail) {
            int idx = queue[head++]
            int yy = idx.intdiv(w)
            int xx = idx - yy * w
            double px = xx + 0.5d, py = yy + 0.5d
            count++; sx += px; sy += py; sxx += px * px; syy += py * py; sxy += px * py
            for (int k = 0; k < 8; k++) {
                int nx = xx + ddx[k], ny = yy + ddy[k]
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                int ni = ny * w + nx
                if (epi[ni] && !visited[ni]) {
                    visited[ni] = true
                    queue[tail++] = ni
                }
            }
        }
        if (count < 12) continue
        double ecx = sx / count, ecy = sy / count
        double cxx = Math.max(0.0d, sxx / count - ecx * ecx)
        double cyy = Math.max(0.0d, syy / count - ecy * ecy)
        double cxy = sxy / count - ecx * ecy
        double trace = cxx + cyy
        double disc = Math.sqrt(Math.max(0.0d,
            (cxx - cyy) * (cxx - cyy) + 4.0d * cxy * cxy))
        double lambda1 = (trace + disc) / 2.0d
        double lambda2 = Math.max(0.0d, (trace - disc) / 2.0d)
        double anisotropy = trace <= 1e-9d ? 0.0d : (lambda1 - lambda2) / trace
        double separation = Math.hypot(ecx - tcx, ecy - tcy) / Math.max(1.0d, rMax)
        double score = count * (0.45d + anisotropy) * (0.40d + clamp01(separation * 2.2d))
        components << [count: count, cx: ecx, cy: ecy, cxx: cxx, cyy: cyy,
            cxy: cxy, lambda1: lambda1, lambda2: lambda2,
            anisotropy: anisotropy, separation: separation, score: score]
    }
    if (components.isEmpty()) return null
    def best = components.max { it.score as double }
    def secondary = components.findAll { !it.is(best) }
        .max { it.count as int }
    double secondaryAreaRatio = secondary == null ? 0.0d :
        (secondary.count as double) / Math.max(1.0d, best.count as double)
    double secondaryDistance = secondary == null ? 0.0d :
        Math.hypot((secondary.cx as double) - (best.cx as double),
            (secondary.cy as double) - (best.cy as double)) / Math.max(1.0d, rMax)
    double secondaryRadial = secondary == null ? 0.0d :
        Math.hypot((secondary.cx as double) - cxImg,
            (secondary.cy as double) - cyImg) / Math.max(1.0d, rMax)
    // A second, sizeable epithelial island far from the dominant one often
    // means that the crop contains a fragment from a neighboring TMA punch.
    // Do not guess silently: surface it to the region review gate.
    boolean multipleRegionRisk = secondaryAreaRatio >= 0.10d &&
        secondaryDistance >= 0.55d && secondaryRadial >= 0.74d
    double tangentAngle = 0.5d * Math.atan2(2.0d * (best.cxy as double),
        (best.cxx as double) - (best.cyy as double))
    double nx = -Math.sin(tangentAngle)
    double ny = Math.cos(tangentAngle)
    double vx = (best.cx as double) - tcx
    double vy = (best.cy as double) - tcy
    if (nx * vx + ny * vy < 0.0d) { nx = -nx; ny = -ny }
    double areaFraction = (best.count as double) / Math.max(1.0d, Math.PI * rMax * rMax)
    double evidence = clamp01(areaFraction / 0.025d)
    double confidence = clamp01(0.40d * evidence +
        0.35d * clamp01((best.separation as double) * 2.0d) +
        0.25d * (best.anisotropy as double))
    double angle = normalizeRadians(Math.atan2(ny, nx))
    String status = confidence >= OK_CONFIDENCE ? 'ok' :
        (confidence >= REVIEW_CONFIDENCE ? 'review' : 'uncertain')
    return [status: status, angle: angle, confidence: confidence,
        tissueFraction: tissueCount / Math.max(1.0d, Math.PI * rMax * rMax),
        method: 'rgb_epidermal_component_pca',
        tangentAnisotropy: best.anisotropy, epidermisPoints: best.count,
        secondaryAreaRatio: secondaryAreaRatio,
        secondaryDistance: secondaryDistance,
        secondaryRadial: secondaryRadial,
        multipleRegionRisk: multipleRegionRisk]
}

def scoreRgbEpidermisAngle = { BufferedImage rgb, double cxImg, double cyImg ->
    int w = rgb.getWidth()
    int h = rgb.getHeight()
    int n = w * h
    float[] brightness = new float[n]
    float[] whiteness = new float[n]
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int value = rgb.getRGB(x, y)
            int r = (value >> 16) & 0xFF
            int g = (value >> 8) & 0xFF
            int b = value & 0xFF
            int i = y * w + x
            brightness[i] = Math.max(r, Math.max(g, b))
            whiteness[i] = Math.min(r, Math.min(g, b))
        }
    }
    double tissueThreshold = otsuThreshold(brightness) * 0.85d
    double whiteThreshold = percentilePositive(whiteness, 0.90d)
    double rMax = Math.min(Math.min(cxImg, cyImg),
        Math.min(w - cxImg - 1.0d, h - cyImg - 1.0d))
    if (rMax < 8.0d)
        return null

    int tissuePixels = 0
    double tissueX = 0.0d, tissueY = 0.0d
    int epiPixels = 0
    double epiWeight = 0.0d, epiX = 0.0d, epiY = 0.0d
    int inCircle = 0
    for (int y = 0; y < h; y++) {
        double dy = y + 0.5d - cyImg
        for (int x = 0; x < w; x++) {
            double dx = x + 0.5d - cxImg
            double rr = Math.sqrt(dx * dx + dy * dy)
            if (rr > rMax) continue
            inCircle++
            int i = y * w + x
            if (brightness[i] >= tissueThreshold) {
                tissuePixels++
                tissueX += x + 0.5d
                tissueY += y + 0.5d
            }
            if (rr / rMax >= 0.15d && brightness[i] >= tissueThreshold &&
                    whiteness[i] >= whiteThreshold) {
                double ew = 1.0d + Math.pow((whiteness[i] - whiteThreshold) / 32.0d, 2.0d)
                epiPixels++
                epiWeight += ew
                epiX += (x + 0.5d) * ew
                epiY += (y + 0.5d) * ew
            }
        }
    }
    double tissueFraction = inCircle == 0 ? 0.0d : tissuePixels / (double) inCircle
    if (tissuePixels < 20 || epiPixels < 8 || epiWeight <= 0.0d)
        return null
    double tcx = tissueX / tissuePixels
    double tcy = tissueY / tissuePixels
    double ecx = epiX / epiWeight
    double ecy = epiY / epiWeight
    double vx = ecx - tcx
    double vy = ecy - tcy
    double distanceNorm = Math.sqrt(vx * vx + vy * vy) / Math.max(1.0d, rMax)
    if (distanceNorm < 0.035d)
        return null
    double evidence = clamp01(epiPixels / 80.0d)
    double localization = clamp01(distanceNorm * 1.75d)
    double confidence = clamp01(evidence * localization)
    double angle = normalizeRadians(Math.atan2(vy, vx))
    String status = confidence >= OK_CONFIDENCE ? 'ok' :
        (confidence >= REVIEW_CONFIDENCE ? 'review' : 'uncertain')
    return [status: status, angle: angle, confidence: confidence,
            tissueFraction: tissueFraction, method: 'rgb_whiteness_centroid']
}

def scoreEpidermisAngle = { BufferedImage img, double cxImg, double cyImg ->
    int w = img.getWidth()
    int h = img.getHeight()
    float[] nuclei = buildProjection(img, dapiIdx)
    float[] marker = buildProjection(img, epidermisMarkerIdx)
    double nucThresh = otsuThreshold(nuclei) * TISSUE_THRESHOLD_SCALE
    float[] epiSignal = new float[nuclei.length]
    for (int i = 0; i < nuclei.length; i++)
        epiSignal[i] = nuclei[i] + marker[i]
    double epiSignalThresh = percentilePositive(epiSignal, 0.88d)
    if (nucThresh <= 0.0d) nucThresh = percentilePositive(nuclei, 0.75) * 0.25d

    double rMax = Math.min(Math.min(cxImg, cyImg), Math.min(w - cxImg - 1.0d, h - cyImg - 1.0d))
    if (rMax < 8.0d) return [status: 'no_tissue', angle: 0.0d, confidence: 0.0d, tissueFraction: 0.0d]

    double[] tissueProfile = new double[N_SECTORS]
    double[] markerProfile = new double[N_SECTORS]
    int[] counts = new int[N_SECTORS]
    int tissuePixels = 0
    int inCirclePixels = 0
    double tissueSumX = 0.0d, tissueSumY = 0.0d
    double epiWeight = 0.0d, epiSumX = 0.0d, epiSumY = 0.0d
    int epiPixels = 0

    for (int y = 0; y < h; y++) {
        double dy = y + 0.5d - cyImg
        for (int x = 0; x < w; x++) {
            double dx = x + 0.5d - cxImg
            double r = Math.sqrt(dx * dx + dy * dy)
            if (r > rMax) continue
            int idx = y * w + x
            inCirclePixels++
            boolean tissue = nuclei[idx] > nucThresh
            if (tissue) {
                tissuePixels++
                tissueSumX += x + 0.5d
                tissueSumY += y + 0.5d
            }
            double rn = r / rMax
            if (rn >= 0.18d && epiSignal[idx] > epiSignalThresh &&
                    nuclei[idx] > nucThresh * 0.35d) {
                double ew = Math.max(1e-6d, (double) epiSignal[idx] - epiSignalThresh)
                epiWeight += ew
                epiSumX += (x + 0.5d) * ew
                epiSumY += (y + 0.5d) * ew
                epiPixels++
            }
            if (rn < OUTER_RING_INNER || rn > OUTER_RING_OUTER) continue

            double angle = Math.atan2(dy, dx)
            if (angle < 0) angle += 2.0d * Math.PI
            int s = (int) Math.floor(angle / (2.0d * Math.PI) * N_SECTORS)
            if (s >= N_SECTORS) s = N_SECTORS - 1

            // Skin surface is a peripheral, tangential band.  Weight the
            // outer half more strongly, while retaining DAPI morphology for
            // samples whose damage markers are biologically low.
            double radialWeight = 0.35d + 0.90d * rn * rn
            if (tissue) tissueProfile[s] += radialWeight
            // Continuous normalized marker mass is much more specific than a
            // binary marker-positive count: the latter mostly measures how
            // large the tissue fragment is, not where its epidermal band lies.
            markerProfile[s] += Math.max(0.0d, (double) marker[idx]) * radialWeight
            counts[s]++
        }
    }

    double tissueFraction = inCirclePixels == 0 ? 0.0d : tissuePixels / (double) inCirclePixels
    if (tissueFraction < 0.015d)
        return [status: 'no_tissue', angle: 0.0d, confidence: 0.0d, tissueFraction: tissueFraction]

    // Primary orientation: use the normal from the bulk-tissue centroid to
    // the marker-rich epidermal-band centroid.  Unlike a single best angular
    // sector, this centers an entire curved epidermal arc at the top and makes
    // its tangent approximately horizontal.
    if (tissuePixels > 0 && epiPixels >= 12 && epiWeight > 0.0d) {
        double tissueCx = tissueSumX / tissuePixels
        double tissueCy = tissueSumY / tissuePixels
        double epiCx = epiSumX / epiWeight
        double epiCy = epiSumY / epiWeight
        double vx = epiCx - tissueCx
        double vy = epiCy - tissueCy
        double distanceNorm = Math.sqrt(vx * vx + vy * vy) / Math.max(1.0d, rMax)
        if (distanceNorm >= 0.055d) {
            double markerCoverage = epiPixels / (double) Math.max(1, tissuePixels)
            double evidence = clamp01(epiPixels / 60.0d)
            double localization = clamp01(distanceNorm * 1.65d)
            double coveragePenalty = markerCoverage > 0.55d ?
                clamp01((0.75d - markerCoverage) / 0.20d) : 1.0d
            double confidence = clamp01(evidence * localization * coveragePenalty)
            if (markerCoverage < 0.75d && confidence >= 0.05d) {
                double angle = normalizeRadians(Math.atan2(vy, vx))
                String status = confidence >= OK_CONFIDENCE ? 'ok' :
                    (confidence >= REVIEW_CONFIDENCE ? 'review' : 'uncertain')
                return [status: status, angle: angle, confidence: confidence,
                        tissueFraction: tissueFraction, method: 'bright_band_centroid_normal']
            }
        }
    }

    double[] tissueSmooth = new double[N_SECTORS]
    double[] markerSmooth = new double[N_SECTORS]
    for (int s = 0; s < N_SECTORS; s++) {
        double tissueAcc = 0.0d
        double markerAcc = 0.0d
        double weight = 0.0d
        for (int k = -4; k <= 4; k++) {
            int ss = (s + k + N_SECTORS) % N_SECTORS
            double wk = Math.exp(-0.5d * (k / 2.2d) * (k / 2.2d))
            tissueAcc += tissueProfile[ss] * wk
            markerAcc += markerProfile[ss] * wk
            weight += wk
        }
        tissueSmooth[s] = tissueAcc / weight
        markerSmooth[s] = markerAcc / weight
    }

    double tissueMax = tissueSmooth.max() as double
    double markerMax = markerSmooth.max() as double
    def markerSorted = markerSmooth.collect { it }.sort()
    double markerMedian = markerSorted[(int) Math.floor(markerSorted.size() * 0.50d)]
    double markerContrast = markerMax <= 1e-12d ? 0.0d :
        clamp01((markerMax - markerMedian) / markerMax)
    double markerWeight = epidermisMarkerIdx.isEmpty() ? 0.0d :
        Math.min(0.90d, 0.30d + 0.65d * markerContrast)
    double[] smooth = new double[N_SECTORS]
    for (int s = 0; s < N_SECTORS; s++) {
        double tissueNorm = tissueMax <= 1e-12d ? 0.0d : tissueSmooth[s] / tissueMax
        double markerNorm = markerMax <= 1e-12d ? 0.0d : markerSmooth[s] / markerMax
        smooth[s] = (1.0d - markerWeight) * tissueNorm + markerWeight * markerNorm
    }

    int best = 0
    for (int s = 1; s < N_SECTORS; s++) {
        if (smooth[s] > smooth[best]) best = s
    }

    def sorted = smooth.collect { it }.sort()
    double bestScore = smooth[best]
    double p75 = sorted[(int) Math.floor(sorted.size() * 0.75d)]
    double median = sorted[(int) Math.floor(sorted.size() * 0.50d)]
    double prominence = bestScore <= 0.0d ? 0.0d :
        (bestScore - Math.max(median, p75 * 0.88d)) / bestScore

    double secondAway = 0.0d
    for (int s = 0; s < N_SECTORS; s++) {
        int d = Math.abs(s - best)
        d = Math.min(d, N_SECTORS - d)
        if (d > 8 && smooth[s] > secondAway)
            secondAway = smooth[s]
    }
    double separation = bestScore <= 0.0d ? 0.0d : (bestScore - secondAway) / bestScore
    double confidence = clamp01(0.55d * prominence + 0.45d * separation)

    int opposite = (best + (int) Math.round(N_SECTORS / 2.0d)) % N_SECTORS
    double oppositeScore = 0.0d
    for (int k = -2; k <= 2; k++) {
        int os = (opposite + k + N_SECTORS) % N_SECTORS
        if (smooth[os] > oppositeScore)
            oppositeScore = smooth[os]
    }
    if (bestScore > 0.0d && oppositeScore / bestScore > 0.82d)
        confidence = Math.min(confidence, 0.20d)

    confidence = clamp01(confidence)

    double angle = (best + 0.5d) / N_SECTORS * 2.0d * Math.PI
    angle = normalizeRadians(angle)
    String status = confidence >= OK_CONFIDENCE ? 'ok' : (confidence >= REVIEW_CONFIDENCE ? 'review' : 'uncertain')
    return [status: status, angle: angle, confidence: confidence,
            tissueFraction: tissueFraction, method: 'angular_profile_fallback']
}

// Refine the individual tissue region inside a grid cell without modifying the
// approved grid.  The nominal grid center remains the safety anchor; automatic
// center motion and crop expansion are capped and audited.
def refineCoreGeometry = { double nominalCx, double nominalCy, double rawDiameter ->
    double nominalCrop = rawDiameter * CROP_SCALE
    if (!REGION_REFINEMENT_ENABLED)
        return [cx: nominalCx, cy: nominalCy, cropSide: nominalCrop,
            confidence: 1.0d, status: 'nominal', centerShiftPx: 0.0d,
            tissueSpanPx: rawDiameter, method: 'grid_roi']
    def region = readSquare(nominalCx, nominalCy, rawDiameter * REGION_SEARCH_SCALE,
        ANALYSIS_DOWNSAMPLE)
    float[] nuclei = buildProjection(region.image, dapiIdx)
    double threshold = otsuThreshold(nuclei) * 0.72d
    if (!(threshold > 0.0d)) threshold = percentilePositive(nuclei, 0.72d) * 0.30d
    int w = region.image.getWidth()
    int h = region.image.getHeight()
    double localCx = (nominalCx - region.originX) / ANALYSIS_DOWNSAMPLE
    double localCy = (nominalCy - region.originY) / ANALYSIS_DOWNSAMPLE
    double maxRadius = rawDiameter * 0.72d / ANALYSIS_DOWNSAMPLE
    int count = 0
    double sumX = 0.0d, sumY = 0.0d
    int minX = w, minY = h, maxX = -1, maxY = -1
    for (int y = 0; y < h; y++) {
        double dy = y + 0.5d - localCy
        for (int x = 0; x < w; x++) {
            double dx = x + 0.5d - localCx
            if (dx * dx + dy * dy > maxRadius * maxRadius) continue
            int i = y * w + x
            if (nuclei[i] <= threshold) continue
            count++
            sumX += x + 0.5d
            sumY += y + 0.5d
            minX = Math.min(minX, x); maxX = Math.max(maxX, x)
            minY = Math.min(minY, y); maxY = Math.max(maxY, y)
        }
    }
    if (count < 20 || maxX < minX || maxY < minY)
        return [cx: nominalCx, cy: nominalCy, cropSide: nominalCrop,
            confidence: 0.0d, status: 'region_review', centerShiftPx: 0.0d,
            tissueSpanPx: 0.0d, method: 'refinement_no_tissue']
    double measuredCx = region.originX + (sumX / count) * ANALYSIS_DOWNSAMPLE
    double measuredCy = region.originY + (sumY / count) * ANALYSIS_DOWNSAMPLE
    double dx = measuredCx - nominalCx
    double dy = measuredCy - nominalCy
    double shift = Math.sqrt(dx * dx + dy * dy)
    double maxShift = rawDiameter * REGION_MAX_CENTER_SHIFT_FRACTION
    boolean shiftCapped = shift > maxShift
    if (shiftCapped && shift > 0.0d) {
        measuredCx = nominalCx + dx / shift * maxShift
        measuredCy = nominalCy + dy / shift * maxShift
        shift = maxShift
    }
    double tissueW = (maxX - minX + 1) * ANALYSIS_DOWNSAMPLE
    double tissueH = (maxY - minY + 1) * ANALYSIS_DOWNSAMPLE
    double tissueSpan = Math.max(tissueW, tissueH)
    double cropSide = Math.max(nominalCrop, tissueSpan * REGION_TISSUE_MARGIN)
    cropSide = Math.min(rawDiameter * REGION_MAX_CROP_SCALE, cropSide)
    double maskFraction = count / Math.max(1.0d, Math.PI * maxRadius * maxRadius)
    double confidence = clamp01(maskFraction / 0.18d) *
        clamp01(1.0d - shift / Math.max(1.0d, rawDiameter * 0.50d))
    String status = shiftCapped || confidence < REGION_REVIEW_CONFIDENCE ?
        'region_review' : 'ok'
    return [cx: measuredCx, cy: measuredCy, cropSide: cropSide,
        confidence: confidence, status: status, centerShiftPx: shift,
        tissueSpanPx: tissueSpan, method: 'nuclear_mask_refinement']
}

// -------------------------------------------------------------------------
// Core selection and manual overrides.
// -------------------------------------------------------------------------

def tmaGrid = hierarchy.getTMAGrid()
def cores = []
int gridCols = 0
def gridEmptyRows = []
def gridEmptyCols = []
if (tmaGrid != null && !tmaGrid.getTMACoreList().isEmpty() &&
        tmaGrid.getTMACoreList().any { !it.isMissing() }) {
    cores = tmaGrid.getTMACoreList()
    gridCols = tmaGrid.getGridWidth()
    println "Using TMA grid: ${tmaGrid.getGridHeight()} rows x ${tmaGrid.getGridWidth()} cols"
    int gridRows = tmaGrid.getGridHeight()
    def perRow = new int[gridRows]
    def perCol = new int[gridCols]
    cores.eachWithIndex { core, i ->
        if (!core.isMissing() && gridCols > 0) {
            int r = i.intdiv(gridCols)
            int c = i % gridCols
            if (r >= 0 && r < gridRows) perRow[r]++
            if (c >= 0 && c < gridCols) perCol[c]++
        }
    }
    gridEmptyRows = (0..<gridRows).findAll { perRow[it] == 0 }.collect { it + 1 }
    gridEmptyCols = (0..<gridCols).findAll { perCol[it] == 0 }.collect { it + 1 }
    if (!gridEmptyRows.isEmpty() || !gridEmptyCols.isEmpty()) {
        Dialogs.showWarningNotification('Auto-orient TMA',
            "Grid has empty rows ${gridEmptyRows} and empty columns ${gridEmptyCols}; exported crops may need extra review.")
    }
} else if (ALLOW_ANNOTATION_FALLBACK_WITHOUT_GRID) {
    cores = hierarchy.getAnnotationObjects()
        .findAll { classNameOf(it) == DETECTED_CLASS_NAME }
        .sort { a, b ->
            def ca = getRoiCenterAndDiameter(a)
            def cb = getRoiCenterAndDiameter(b)
            int yCmp = ca.cy <=> cb.cy
            return yCmp != 0 ? yCmp : (ca.cx <=> cb.cx)
        }
    gridCols = DEFAULT_GRID_COLS
    println "No TMA grid found. Falling back to ${cores.size()} '${DETECTED_CLASS_NAME}' annotations and ${DEFAULT_GRID_COLS} display columns."
} else {
    Dialogs.showErrorMessage('Auto-orient TMA',
        'No usable TMA grid was found.\n\n' +
        'Run 01_build_tma_grid.groovy first, check the grid, then run this script.')
    return
}

if (cores.isEmpty()) {
    Dialogs.showErrorMessage('Auto-orient TMA',
        "No TMA grid or '${DETECTED_CLASS_NAME}' annotations found.\n\n" +
        'Run the core/grid detection step first.')
    return
}

// Expensive processing is allowed only for the exact grid approved in Step 3.
// Any moved/replaced/missing core changes the hash and invalidates approval.
if (!approvalFile.isFile()) {
    System.setProperty('tma.orientation.status', 'BLOCKED_APPROVAL_REQUIRED')
    Dialogs.showErrorMessage('Auto-orient TMA — approval required',
        "This grid has not been human-approved.\n\n" +
        "Run 03_review_correct_and_approve_grid.groovy first.\n\nExpected checkpoint:\n${approvalFile.getAbsolutePath()}")
    return
}
def approval
try { approval = json.fromJson(approvalFile.getText('UTF-8'), Map.class) }
catch (Throwable approvalError) {
    System.setProperty('tma.orientation.status', 'BLOCKED_INVALID_APPROVAL')
    Dialogs.showErrorMessage('Auto-orient TMA — invalid approval',
        "Could not read the approval checkpoint:\n${approvalError.getMessage()}")
    return
}
String gridHash = sha256(canonicalGrid(tmaGrid))
String imageNameForApproval = server.getMetadata().getName() ?: 'image'
boolean approvalMatches = approval.status == 'APPROVED' &&
    approval.gridHash == gridHash && approval.imageName == imageNameForApproval &&
    (approval.imageWidth as long) == server.getWidth() &&
    (approval.imageHeight as long) == server.getHeight() &&
    (approval.approvalMode == 'human' ||
        ('true'.equalsIgnoreCase(System.getProperty('tma.approveForTest', 'false')) &&
            ((approval.detectionConfigHash != null &&
                approval.detectionConfigHash == CURRENT_DETECTION_CONFIG_HASH) ||
             (approval.detectionConfigHash == null &&
                (approval.profileHash == null ||
                    approval.profileHash == CURRENT_PROFILE_HASH)))))
if (!approvalMatches) {
    System.setProperty('tma.orientation.status', 'BLOCKED_GRID_CHANGED')
    Dialogs.showErrorMessage('Auto-orient TMA — grid changed',
        "The current grid does not match its approved checkpoint.\n\n" +
        "Current:  ${gridHash}\nApproved: ${approval.gridHash ?: 'none'}\n\n" +
        'Review the change and run 03_review_correct_and_approve_grid.groovy again. No cores were reprocessed.')
    return
}

// Grid and processing settings identify a resumable run. Delivery switches
// intentionally do not: a later research package reuses the accepted angle
// and crop, then backfills only missing files.
String runIdentity = sha256([gridHash, CURRENT_PROCESSING_HASH,
    ORIENTATION_ALGORITHM_VERSION, EXPORT_DOWNSAMPLE, CROP_SCALE,
    ROTATION_SUPPORT_SCALE].join('|')).substring(0, 12)
def outDir = new File(exportBaseDir,
    "${imageStem}_grid_${gridHash.substring(0, 12)}_orient_${runIdentity}")
File latestRunPointer = new File(exportBaseDir, 'LATEST_FINAL_RUN.txt')
def compatibleRunPointers = [latestRunPointer]
String legacyRunBasePath = System.getProperty('corealign.legacy.runBaseDir', '').trim()
if (!legacyRunBasePath.isEmpty()) {
    File legacyPointer = new File(new File(legacyRunBasePath), 'LATEST_FINAL_RUN.txt')
    if (legacyPointer.getAbsolutePath() != latestRunPointer.getAbsolutePath())
        compatibleRunPointers << legacyPointer
}
if (!new File(outDir, 'checkpoints').isDirectory()) {
    try {
        compatibleRunPointers.find { pointer ->
            if (!pointer.isFile()) return false
            File legacyRunDir = new File(pointer.getText('UTF-8').trim())
            File legacyManifestFile = new File(legacyRunDir, 'run_manifest.json')
            if (legacyRunDir.isDirectory() && legacyManifestFile.isFile()) {
                def legacyManifest = json.fromJson(legacyManifestFile.getText('UTF-8'), Map.class)
                boolean compatibleLegacyRun = legacyManifest.gridHash == gridHash &&
                    legacyManifest.algorithmVersion == ORIENTATION_ALGORITHM_VERSION &&
                    (legacyManifest.processingHash == CURRENT_PROCESSING_HASH ||
                        (legacyManifest.processingHash == null &&
                            COMPATIBLE_LEGACY_PROFILE_HASHES.contains(
                                legacyManifest.profileHash?.toString() ?: '')))
                if (compatibleLegacyRun) {
                    outDir = legacyRunDir
                    println "MIGRATION: Reusing compatible earlier run ${outDir.getName()} for output-only upgrade."
                    return true
                }
            }
            return false
        }
    } catch (Throwable migrationError) {
        println "WARNING: Could not inspect earlier run for output-only migration: ${migrationError.getMessage()}"
    }
}
def previewDir = new File(outDir, 'rotated_previews')
def originalDir = new File(outDir, 'unrotated_previews')
def fullResDir = new File(outDir, 'rotated_fullres')
def originalFullResDir = new File(outDir, 'unrotated_fullres')
def nativeOmeDir = new File(outDir, 'source_native_ome')
def rotatedMultichannelOmeDir = new File(outDir, 'rotated_multichannel_ome')
def checkpointDir = new File(outDir, 'checkpoints')
def partialCsvFile = new File(outDir, 'orientation_results.partial.csv')

if ((!previewDir.mkdirs() && !previewDir.isDirectory()) ||
        (!originalDir.mkdirs() && !originalDir.isDirectory()) ||
        (SAVE_FULL_RESOLUTION_PNG && !fullResDir.mkdirs() && !fullResDir.isDirectory()) ||
        (SAVE_FULL_RESOLUTION_PNG && !originalFullResDir.mkdirs() && !originalFullResDir.isDirectory()) ||
        (SAVE_NATIVE_OME_TIFF && !nativeOmeDir.mkdirs() && !nativeOmeDir.isDirectory()) ||
        (SAVE_ROTATED_MULTICHANNEL_OME_TIFF &&
            !rotatedMultichannelOmeDir.mkdirs() && !rotatedMultichannelOmeDir.isDirectory()) ||
        (!checkpointDir.mkdirs() && !checkpointDir.isDirectory())) {
    Dialogs.showErrorMessage('Auto-orient TMA',
        "Could not create output folders under:\n${outDir.getAbsolutePath()}")
    return
}
println "Output: ${outDir.getAbsolutePath()}"
println "Approved grid hash: ${gridHash}"
println "Per-core export: rotate source-support window first, crop second; downsample ${EXPORT_DOWNSAMPLE}; source OME-TIFF ${SAVE_NATIVE_OME_TIFF}; rotated multichannel OME-TIFF ${SAVE_ROTATED_MULTICHANNEL_OME_TIFF}"

def orientationQcClass = makePathClass(ORIENTATION_QC_CLASS_NAME, COLOR_OK)
def oldOrientationQc = hierarchy.getAnnotationObjects().findAll {
    String className = classNameOf(it) ?: ''
    String objectName = objectNameOf(it) ?: ''
    return className == ORIENTATION_QC_CLASS_NAME ||
        className == LEGACY_DIRECTION_CLASS_NAME ||
        objectName ==~ /(?i)^\d+-[A-Za-z]+ epidermis$/
}
if (!oldOrientationQc.isEmpty()) {
    hierarchy.removeObjects(oldOrientationQc, true)
    println "Removed ${oldOrientationQc.size()} old CoreAlign orientation QC objects"
}

def overrideObjects = hierarchy.getAnnotationObjects().findAll {
    it.getROI() != null &&
        (classNameOf(it) == OVERRIDE_CLASS_NAME ||
            ((objectNameOf(it) ?: '').toLowerCase().contains(OVERRIDE_CLASS_NAME.toLowerCase())))
}
println "Manual overrides found: ${overrideObjects.size()}"

def cropOverrideObjects = hierarchy.getAnnotationObjects().findAll {
    it.getROI() != null &&
        (classNameOf(it) == CROP_OVERRIDE_CLASS_NAME ||
            ((objectNameOf(it) ?: '').toLowerCase().contains(CROP_OVERRIDE_CLASS_NAME.toLowerCase())))
}
println "Manual crop overrides found: ${cropOverrideObjects.size()}"

def findOverride = { double cx, double cy, double radius ->
    def best = null
    double bestDist2 = Double.POSITIVE_INFINITY
    overrideObjects.each { obj ->
        def roi = obj.getROI()
        double ox = roi.getCentroidX()
        double oy = roi.getCentroidY()
        double dx = ox - cx
        double dy = oy - cy
        double d2 = dx * dx + dy * dy
        if (d2 < bestDist2 && d2 <= radius * radius * 1.80d) {
            best = [x: ox, y: oy, obj: obj]
            bestDist2 = d2
        }
    }
    return best
}

def findCropOverride = { String coreName, double cx, double cy, double radius ->
    String coreToken = coreName.toLowerCase()
    def named = cropOverrideObjects.findAll {
        (objectNameOf(it) ?: '').toLowerCase().contains(coreToken)
    }
    def candidates = named.isEmpty() ? cropOverrideObjects : named
    def best = null
    double bestDist2 = Double.POSITIVE_INFINITY
    candidates.each { obj ->
        def roi = obj.getROI()
        double ox = roi.getCentroidX()
        double oy = roi.getCentroidY()
        double dx = ox - cx
        double dy = oy - cy
        double d2 = dx * dx + dy * dy
        if (d2 < bestDist2 && (named.size() > 0 || d2 <= radius * radius * 2.25d)) {
            double side = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight())
            if (side < radius * 0.35d) side = radius * 2.0d * CROP_SCALE
            best = [x: ox, y: oy, side: side, obj: obj]
            bestDist2 = d2
        }
    }
    return best
}

// -------------------------------------------------------------------------
// Process cores.
// -------------------------------------------------------------------------

def records = []
def newOrientationQc = []
int okCount = 0
int reviewCount = 0
int failCount = 0
int overrideCount = 0
int missingCount = 0
int resumedCount = 0
int processedThisRunCount = 0
int exportOnlyThisRunCount = 0

def recordsCsvText = { rows ->
    def sb = new StringBuilder()
    sb.append([
        'image', 'index', 'core', 'row', 'col',
        'nominal_center_x_px', 'nominal_center_y_px',
        'refined_center_x_px', 'refined_center_y_px', 'crop_diameter_px',
        'region_status', 'region_confidence', 'region_center_shift_px',
        'epidermis_angle_deg', 'rotate_to_top_deg', 'web_rotation_adjustment_deg',
        'post_rotation_residual_deg',
        'confidence', 'tissue_fraction', 'status', 'orientation_method',
        'unrotated_preview_png', 'rotated_preview_png', 'checkpoint_reused',
        'unrotated_fullres_png', 'rotated_fullres_png', 'source_native_ome_tif',
        'rotated_multichannel_ome_tif',
        'export_downsample', 'rotation_then_crop', 'approved_grid_hash'
    ].join(',')).append('\n')
    rows.each { r ->
        sb.append([
            csv(r.image), r.index, csv(r.core), r.row, r.col,
            format1(r.nominalCenterX), format1(r.nominalCenterY),
            format1(r.centerX), format1(r.centerY), format1(r.diameter),
            csv(r.regionStatus), format3(r.regionConfidence), format1(r.regionCenterShiftPx),
            format1(r.epidermisAngleDeg), format1(r.rotateToTopDeg),
            format1(r.webRotationAdjustmentDeg ?: 0.0d),
            format1(r.postRotationResidualDeg),
            format3(r.confidence), format3(r.tissueFraction), csv(r.status),
            csv(r.method), csv(r.original), csv(r.preview), r.resumed,
            csv(r.originalFullRes), csv(r.rotatedFullRes), csv(r.nativeOme),
            csv(r.rotatedMultichannelOme),
            format3(EXPORT_DOWNSAMPLE), true, csv(gridHash)
        ].join(',')).append('\n')
    }
    return sb.toString()
}

def coreEntries = cores.withIndex().collect { core, index -> [core: core, index: index] }
if (PARTIAL_CORE_TEST)
    coreEntries = coreEntries.findAll { (it.core.getName() ?: '') == TEST_CORE_FILTER }
coreEntries.each { entry ->
    def core = entry.core
    int i = entry.index as int
    String coreName = core.getName() ?: String.format(Locale.US, 'C%03d', i + 1)
    def webCorrection = webCorrectionsByCore[coreName.toLowerCase()]
    double webRotationAdjustmentDeg = 0.0d
    try {
        webRotationAdjustmentDeg = (webCorrection?.rotationAdjustmentDeg ?: 0.0d) as double
        if (!Double.isFinite(webRotationAdjustmentDeg)) webRotationAdjustmentDeg = 0.0d
        webRotationAdjustmentDeg = Math.max(-180.0d, Math.min(180.0d,
            webRotationAdjustmentDeg))
    } catch (Throwable ignored) { webRotationAdjustmentDeg = 0.0d }
    boolean webManualRotation = Math.abs(webRotationAdjustmentDeg) >= 0.05d
    def geom = getRoiCenterAndDiameter(core)
    double nominalCx = geom.cx
    double nominalCy = geom.cy
    double rawDiameter = geom.rawDiameter
    boolean missing = false
    try { missing = core.isMissing() } catch (Throwable ignored) {}
    def checkpointFile = new File(checkpointDir,
        String.format(Locale.US, '%03d_%s.json', i + 1,
            coreName.replaceAll(/[^A-Za-z0-9._-]+/, '_')))
    def cropOverride = missing ? null :
        findCropOverride(coreName, nominalCx, nominalCy, rawDiameter / 2.0d)
    def orientationOverrideProbe = missing ? null :
        findOverride(nominalCx, nominalCy, rawDiameter * 0.75d)

    // Fast checkpoint resume must happen before region refinement, because
    // refinement reads source pixels.  The approved grid hash already binds
    // every nominal core center and diameter. Existing no-override checkpoints
    // can therefore restore their accepted refined center and crop directly.
    // Any current or previous manual override deliberately falls back to the
    // full signature path so additions, moves and removals are never hidden.
    def fastCheckpoint = null
    if (!missing && checkpointFile.isFile() && cropOverride == null &&
            orientationOverrideProbe == null && !webManualRotation) {
        try {
            def candidate = json.fromJson(checkpointFile.getText('UTF-8'), Map.class)
            def candidateRecord = candidate.record
            boolean legacyProfileCompatible = candidate.processingHash == null &&
                (candidate.profileHash == null || COMPATIBLE_LEGACY_PROFILE_HASHES.contains(
                    candidate.profileHash.toString()))
            boolean processingMatches = candidate.processingHash != null ?
                candidate.processingHash == CURRENT_PROCESSING_HASH :
                legacyProfileCompatible
            boolean checkpointHadManualOverride =
                candidate.result?.method?.toString() == 'manual_override' ||
                candidateRecord?.regionStatus?.toString() == 'manual_override'
            boolean baselineFilesExist = candidateRecord?.preview &&
                candidateRecord?.original &&
                new File(outDir, candidateRecord.preview.toString()).isFile() &&
                new File(outDir, candidateRecord.original.toString()).isFile()
            if ((candidate.complete == true || candidate.processingComplete == true) &&
                    candidate.gridHash == gridHash && processingMatches &&
                    candidateRecord != null && baselineFilesExist &&
                    !checkpointHadManualOverride) {
                fastCheckpoint = candidate
            }
        } catch (Throwable fastResumeError) {
            println "WARNING: Fast-resume preflight failed for ${coreName}: ${fastResumeError.getMessage()}"
        }
    }
    def regionResult = missing ? [cx: nominalCx, cy: nominalCy,
        cropSide: rawDiameter * CROP_SCALE, confidence: 0.0d, status: 'missing',
        centerShiftPx: 0.0d, tissueSpanPx: 0.0d, method: 'missing'] :
        (fastCheckpoint != null ? [
            cx: fastCheckpoint.record.centerX as double,
            cy: fastCheckpoint.record.centerY as double,
            cropSide: fastCheckpoint.record.diameter as double,
            confidence: (fastCheckpoint.record.regionConfidence ?: 0.0d) as double,
            status: fastCheckpoint.record.regionStatus?.toString() ?: 'ok',
            centerShiftPx: (fastCheckpoint.record.regionCenterShiftPx ?: 0.0d) as double,
            tissueSpanPx: fastCheckpoint.record.diameter as double,
            method: 'checkpoint_fast_resume'
        ] : refineCoreGeometry(nominalCx, nominalCy, rawDiameter))
    if (cropOverride != null) {
        regionResult = [cx: cropOverride.x, cy: cropOverride.y,
            cropSide: Math.min(rawDiameter * REGION_MAX_CROP_SCALE,
                Math.max(rawDiameter * 0.70d, cropOverride.side as double)),
            confidence: 1.0d, status: 'manual_override',
            centerShiftPx: Math.hypot((cropOverride.x as double) - nominalCx,
                (cropOverride.y as double) - nominalCy),
            tissueSpanPx: cropOverride.side as double, method: 'manual_crop_override']
    }
    double cx = regionResult.cx as double
    double cy = regionResult.cy as double
    double diameter = regionResult.cropSide as double
    double radius = diameter / 2.0d

    int row = gridCols > 0 ? (i.intdiv(gridCols) + 1) : 0
    int col = gridCols > 0 ? ((i % gridCols) + 1) : 0
    String outName = String.format(Locale.US, '%03d_%s.png', i + 1, coreName.replaceAll(/[^A-Za-z0-9._-]+/, '_'))
    def outFile = new File(previewDir, outName)
    def originalFile = new File(originalDir, outName)
    def fullResFile = new File(fullResDir, outName)
    def originalFullResFile = new File(originalFullResDir, outName)
    def nativeOmeFile = new File(nativeOmeDir,
        outName.replaceFirst(/(?i)\.png$/, '.ome.tif'))
    def rotatedMultichannelOmeFile = new File(rotatedMultichannelOmeDir,
        outName.replaceFirst(/(?i)\.png$/, '.ome.tif'))
    def result = [status: 'missing', angle: 0.0d, confidence: 0.0d,
                  tissueFraction: 0.0d, method: 'missing']
    double rotateRad = 0.0d
    double postRotationResidualDeg = 180.0d
    String previewRel = ''
    String originalRel = ''
    String rotatedFullResRel = ''
    String originalFullResRel = ''
    String nativeOmeRel = ''
    String rotatedMultichannelOmeRel = ''
    def analysisRegion = null
    def analysisRgb = null
    def override = missing ? null : findOverride(cx, cy, radius)
    if (cropOverride != null) {
        try { cropOverride.obj.setName("${CROP_OVERRIDE_CLASS_NAME} ${coreName}") }
        catch (Throwable ignored) {}
    }
    if (override != null) {
        try { override.obj.setName("${OVERRIDE_CLASS_NAME} ${coreName}") }
        catch (Throwable ignored) {}
    }
    String overrideSignature = override == null ? 'none' :
        [override.obj.getID(), format3(override.x as double), format3(override.y as double)].join('|')
    String cropOverrideSignature = cropOverride == null ? 'none' :
        [cropOverride.obj.getID(), format3(cropOverride.x as double),
            format3(cropOverride.y as double), format3(cropOverride.side as double)].join('|')
    String webCorrectionSignature = webManualRotation ?
        "web_rotation_${format3(webRotationAdjustmentDeg)}" : 'none'
    String coreSignature = sha256([
        ORIENTATION_ALGORITHM_VERSION, gridHash, i, coreName, format3(cx), format3(cy),
        format3(diameter), missing, overrideSignature, cropOverrideSignature,
        webCorrectionSignature,
        EXPORT_DOWNSAMPLE, PREVIEW_MAX_PIXELS, ROTATION_SUPPORT_SCALE
    ].join('|'))
    boolean resumed = false
    boolean checkpointNeedsWrite = false
    boolean deliveryComplete = true
    boolean displayOutputChanged = false
    String processingError = ''

    if (checkpointFile.isFile()) {
        try {
            def cp = json.fromJson(checkpointFile.getText('UTF-8'), Map.class)
            def cpRecord = cp.record
            boolean baselineFilesExist = missing || (cpRecord != null &&
                cpRecord.preview && cpRecord.original &&
                new File(outDir, cpRecord.preview.toString()).isFile() &&
                new File(outDir, cpRecord.original.toString()).isFile())
            boolean legacySavedFullRes = cpRecord?.rotatedFullRes &&
                cpRecord?.originalFullRes
            boolean legacySavedNative = cpRecord?.nativeOme
            boolean legacySavedRotatedMultichannel = cpRecord?.rotatedMultichannelOme
            String legacyCoreSignature = sha256([
                ORIENTATION_ALGORITHM_VERSION, gridHash, i, coreName, format3(cx),
                format3(cy), format3(diameter), missing, overrideSignature,
                cropOverrideSignature, EXPORT_DOWNSAMPLE, PREVIEW_MAX_PIXELS,
                ROTATION_SUPPORT_SCALE, legacySavedFullRes, legacySavedNative,
                legacySavedRotatedMultichannel
            ].join('|'))
            boolean legacyProfileCompatible = cp.processingHash == null &&
                (cp.profileHash == null || COMPATIBLE_LEGACY_PROFILE_HASHES.contains(
                    cp.profileHash.toString()))
            boolean processingMatches = cp.processingHash != null ?
                cp.processingHash == CURRENT_PROCESSING_HASH :
                legacyProfileCompatible
            boolean signatureMatches = cp.coreSignature == coreSignature ||
                (legacyProfileCompatible && cp.coreSignature == legacyCoreSignature)
            if ((cp.complete == true || cp.processingComplete == true) &&
                    signatureMatches &&
                    cp.gridHash == gridHash && processingMatches &&
                    cpRecord != null && baselineFilesExist) {
                result = [status: cp.result.status.toString(), angle: cp.result.angle as double,
                    confidence: cp.result.confidence as double,
                    tissueFraction: cp.result.tissueFraction as double,
                    method: cp.result.method.toString()]
                rotateRad = cp.rotateRad as double
                postRotationResidualDeg = (cp.postRotationResidualDeg ?: 0.0d) as double
                previewRel = cpRecord.preview?.toString() ?: ''
                originalRel = cpRecord.original?.toString() ?: ''
                rotatedFullResRel = cpRecord.rotatedFullRes?.toString() ?: ''
                originalFullResRel = cpRecord.originalFullRes?.toString() ?: ''
                nativeOmeRel = cpRecord.nativeOme?.toString() ?: ''
                rotatedMultichannelOmeRel =
                    cpRecord.rotatedMultichannelOme?.toString() ?: ''
                resumed = true
                resumedCount++
                displayOutputChanged = cp.outputHash != CURRENT_OUTPUT_HASH
                checkpointNeedsWrite = cp.processingHash == null ||
                    displayOutputChanged ||
                    cp.profileHash != CURRENT_PROFILE_HASH
                deliveryComplete = cp.deliveryComplete != false
            }
        } catch (Throwable checkpointReadError) {
            println "WARNING: Ignoring invalid checkpoint for ${coreName}: ${checkpointReadError.getMessage()}"
        }
    }

    if (!resumed) {
        processedThisRunCount++
        try {
            if (!missing) {
                if (override != null) {
                    result = [
                        status: 'manual_override',
                        angle: normalizeRadians(Math.atan2(override.y - cy, override.x - cx)),
                        confidence: 1.0d,
                        tissueFraction: 1.0d,
                        method: 'manual_override'
                    ]
                } else {
                    analysisRegion = readSquare(cx, cy,
                        Math.max(diameter * 1.08d, rawDiameter * 1.25d),
                        ANALYSIS_DOWNSAMPLE)
                    double localCx = (cx - analysisRegion.originX) / ANALYSIS_DOWNSAMPLE
                    double localCy = (cy - analysisRegion.originY) / ANALYSIS_DOWNSAMPLE
                    analysisRgb = makeRgb(analysisRegion.image)
                    def componentResult = scoreRgbComponentPca(analysisRgb, localCx, localCy)
                    def pcaResult = scoreRgbBoundaryPca(analysisRgb, localCx, localCy)
                    def centroidResult = scoreRgbEpidermisAngle(analysisRgb, localCx, localCy)
                    def markerResult = scoreEpidermisAngle(analysisRegion.image, localCx, localCy)
                    println "${coreName} candidates: component=${componentResult == null ? 'none' : format1(angleToDegrees(componentResult.angle as double)) + '/' + format3(componentResult.confidence as double) + '/secondary=' + format3(componentResult.secondaryAreaRatio as double) + '@' + format3(componentResult.secondaryDistance as double) + '/radial=' + format3(componentResult.secondaryRadial as double)}; boundary=${pcaResult == null ? 'none' : format1(angleToDegrees(pcaResult.angle as double)) + '/' + format3(pcaResult.confidence as double)}; RGB-centroid=${centroidResult == null ? 'none' : format1(angleToDegrees(centroidResult.angle as double)) + '/' + format3(centroidResult.confidence as double)}; marker=${markerResult == null ? 'none' : format1(angleToDegrees(markerResult.angle as double)) + '/' + format3(markerResult.confidence as double)}"
                    def candidates = [componentResult, centroidResult, markerResult, pcaResult].findAll { it != null }
                    if (candidates.isEmpty()) {
                        result = [status: 'uncertain', angle: 0.0d, confidence: 0.0d,
                            tissueFraction: 0.0d, method: 'no_orientation_candidate']
                    } else {
                        // Prefer the largest elongated epithelial component;
                        // fall back to the broad RGB centroid, then marker and
                        // boundary estimates.  Large disagreement is surfaced
                        // as review rather than hidden by a confidence maximum.
                        result = componentResult ?: centroidResult ?: markerResult ?: pcaResult
                        // Only use a genuinely independent, sufficiently strong
                        // estimate for the disagreement gate.  A weak centroid
                        // must not overrule a strong epithelial component merely
                        // because it happens to be non-null.  Marker-only
                        // disagreement is used only at high confidence because
                        // damage/proliferation markers are not epidermis-specific.
                        def independent = (centroidResult != null &&
                            (centroidResult.confidence as double) >= REVIEW_CONFIDENCE) ?
                            centroidResult : ((markerResult != null &&
                            (markerResult.confidence as double) >= 0.45d) ? markerResult : null)
                        if (componentResult != null && independent != null) {
                            double disagreement = Math.abs(angleToDegrees(normalizeRadians(
                                (componentResult.angle as double) - (independent.angle as double))))
                            if (disagreement > 70.0d && result.status == 'ok')
                                result.status = 'review'
                        }
                        if (componentResult?.multipleRegionRisk == true) {
                            regionResult.status = 'region_review'
                            if (result.status == 'ok') result.status = 'review'
                        }
                    }
                }

                rotateRad = normalizeRadians(-Math.PI / 2.0d - (result.angle as double))
                if (webManualRotation)
                    rotateRad = normalizeRadians(rotateRad +
                        Math.toRadians(webRotationAdjustmentDeg))
                double supportSide = diameter * Math.max(Math.sqrt(2.0d) + 0.01d,
                    ROTATION_SUPPORT_SCALE)
                def exportRegion = readSquare(cx, cy, supportSide, EXPORT_DOWNSAMPLE)
                double exportCx = (cx - exportRegion.originX) / EXPORT_DOWNSAMPLE
                double exportCy = (cy - exportRegion.originY) / EXPORT_DOWNSAMPLE
                int finalSidePx = Math.max(1, (int) Math.round(diameter / EXPORT_DOWNSAMPLE))
                def rgbSupport = makeRgb(exportRegion.image)
                def unrotatedCrop = cropAround(rgbSupport, exportCx, exportCy, finalSidePx)
                def rotatedSupport = null
                def rotatedCrop = null
                boolean qcAvailable = false
                for (int iteration = 0; iteration < Math.max(1, POST_ROTATION_MAX_ITERATIONS); iteration++) {
                    rotatedSupport = rotateImageAround(rgbSupport, rotateRad, exportCx, exportCy)
                    rotatedCrop = cropAround(rotatedSupport, exportCx, exportCy, finalSidePx)
                    def qcImage = scaleForPreview(rotatedCrop, Math.min(PREVIEW_MAX_PIXELS, 700))
                    def qcResult = scoreRgbComponentPca(qcImage,
                        qcImage.getWidth() / 2.0d, qcImage.getHeight() / 2.0d)
                    if (qcResult == null) break
                    qcAvailable = true
                    double correction = normalizeRadians(-Math.PI / 2.0d - (qcResult.angle as double))
                    postRotationResidualDeg = Math.abs(angleToDegrees(correction))
                    if (override != null || webManualRotation ||
                            postRotationResidualDeg <= POST_ROTATION_TOLERANCE_DEG ||
                            iteration + 1 >= POST_ROTATION_MAX_ITERATIONS ||
                            postRotationResidualDeg > 65.0d) break
                    rotateRad = normalizeRadians(rotateRad + correction)
                }
                if (!qcAvailable) postRotationResidualDeg = 180.0d
                if (webManualRotation) {
                    result.status = 'manual_override'
                    result.method = 'web_manual_rotation'
                    result.confidence = 1.0d
                }

                // Re-render only after the accepted transform is known. These
                // images use one slide-wide range; the scoring image above is
                // intentionally separate and never written to disk.
                def displaySupport = makeDisplayRgb(exportRegion.image)
                unrotatedCrop = cropAround(displaySupport, exportCx, exportCy,
                    finalSidePx)
                def displayRotatedSupport = rotateImageAround(displaySupport,
                    rotateRad, exportCx, exportCy)
                rotatedCrop = cropAround(displayRotatedSupport, exportCx, exportCy,
                    finalSidePx)
                def originalPreview = scaleForPreview(unrotatedCrop, PREVIEW_MAX_PIXELS)
                def rotatedPreview = scaleForPreview(rotatedCrop, PREVIEW_MAX_PIXELS)
                boolean wroteOriginal = ImageIO.write(originalPreview, 'PNG', originalFile)
                boolean wroteRotated = ImageIO.write(rotatedPreview, 'PNG', outFile)
                if (!wroteOriginal)
                    println "WARNING: Could not write unrotated PNG for ${coreName}"
                if (!wroteRotated)
                    println "WARNING: Could not write rotated PNG for ${coreName}"
                if (wroteOriginal) originalRel = 'unrotated_previews/' + outName
                if (wroteRotated) previewRel = 'rotated_previews/' + outName
                boolean wroteFullOriginal = true
                boolean wroteFullRotated = true
                if (SAVE_FULL_RESOLUTION_PNG) {
                    wroteFullOriginal = ImageIO.write(unrotatedCrop, 'PNG', originalFullResFile)
                    wroteFullRotated = ImageIO.write(rotatedCrop, 'PNG', fullResFile)
                    if (wroteFullOriginal) originalFullResRel = 'unrotated_fullres/' + outName
                    if (wroteFullRotated) rotatedFullResRel = 'rotated_fullres/' + outName
                }
                boolean wroteNative = true
                if (SAVE_NATIVE_OME_TIFF) {
                    int nativeSide = Math.max(1, (int) Math.round(diameter))
                    int nativeX = Math.max(0, Math.min(imgW - nativeSide,
                        (int) Math.round(cx - nativeSide / 2.0d)))
                    int nativeY = Math.max(0, Math.min(imgH - nativeSide,
                        (int) Math.round(cy - nativeSide / 2.0d)))
                    nativeSide = Math.min(nativeSide,
                        Math.min(imgW - nativeX, imgH - nativeY))
                    new OMEPyramidWriter.Builder(server)
                        .region(nativeX, nativeY, nativeSide, nativeSide)
                        .downsamples(1.0d)
                        .losslessCompression()
                        .channelsPlanar()
                        .build()
                        .writeSeries(nativeOmeFile.getAbsolutePath())
                    wroteNative = nativeOmeFile.isFile() && nativeOmeFile.length() > 0
                    if (wroteNative) nativeOmeRel = 'source_native_ome/' + nativeOmeFile.getName()
                }
                boolean wroteRotatedMultichannel = true
                if (SAVE_ROTATED_MULTICHANNEL_OME_TIFF) {
                    wroteRotatedMultichannel = writeRotatedMultichannelOme(
                        exportRegion.image, rotateRad, exportCx, exportCy,
                        finalSidePx, rotatedMultichannelOmeFile)
                    if (wroteRotatedMultichannel)
                        rotatedMultichannelOmeRel = 'rotated_multichannel_ome/' +
                            rotatedMultichannelOmeFile.getName()
                }
                if (!wroteOriginal || !wroteRotated || !wroteFullOriginal ||
                        !wroteFullRotated || !wroteNative ||
                        !wroteRotatedMultichannel) deliveryComplete = false
                else if ((regionResult.status == 'region_review' || !qcAvailable ||
                        postRotationResidualDeg > POST_ROTATION_TOLERANCE_DEG) &&
                        result.status == 'ok') result.status = 'review'
            }
        } catch (Throwable coreError) {
            processingError = coreError.getClass().getSimpleName() + ': ' + (coreError.getMessage() ?: 'unknown error')
            deliveryComplete = false
            result = [status: 'processing_error', angle: 0.0d, confidence: 0.0d,
                tissueFraction: 0.0d, method: 'exception']
            rotateRad = 0.0d
            previewRel = ''
            originalRel = ''
            rotatedFullResRel = ''
            originalFullResRel = ''
            nativeOmeRel = ''
            rotatedMultichannelOmeRel = ''
            println "ERROR: ${coreName} failed but the run will continue: ${processingError}"
        }
    }

    // Delivery-only resume.  If the user later enables the research package,
    // keep the approved center, crop and angle, read the source pixels once,
    // and create only the newly requested artifacts.
    if (resumed && !missing) {
        if (SAVE_FULL_RESOLUTION_PNG && originalFullResFile.isFile() &&
                fullResFile.isFile()) {
            originalFullResRel = 'unrotated_fullres/' + outName
            rotatedFullResRel = 'rotated_fullres/' + outName
        }
        if (SAVE_NATIVE_OME_TIFF && nativeOmeFile.isFile() && nativeOmeFile.length() > 0)
            nativeOmeRel = 'source_native_ome/' + nativeOmeFile.getName()
        if (SAVE_ROTATED_MULTICHANNEL_OME_TIFF &&
                rotatedMultichannelOmeFile.isFile() &&
                rotatedMultichannelOmeFile.length() > 0)
            rotatedMultichannelOmeRel = 'rotated_multichannel_ome/' +
                rotatedMultichannelOmeFile.getName()

        boolean requestedOutputsExist =
            (!SAVE_FULL_RESOLUTION_PNG ||
                (originalFullResFile.isFile() && fullResFile.isFile())) &&
            (!SAVE_NATIVE_OME_TIFF ||
                (nativeOmeFile.isFile() && nativeOmeFile.length() > 0)) &&
            (!SAVE_ROTATED_MULTICHANNEL_OME_TIFF ||
                (rotatedMultichannelOmeFile.isFile() &&
                    rotatedMultichannelOmeFile.length() > 0))
        if (deliveryComplete != requestedOutputsExist) checkpointNeedsWrite = true
        deliveryComplete = requestedOutputsExist

        boolean needDisplayPng = displayOutputChanged
        boolean needFullRes = SAVE_FULL_RESOLUTION_PNG &&
            (displayOutputChanged || !originalFullResFile.isFile() || !fullResFile.isFile())
        boolean needNative = SAVE_NATIVE_OME_TIFF &&
            (!nativeOmeFile.isFile() || nativeOmeFile.length() == 0)
        boolean needRotatedMultichannel = SAVE_ROTATED_MULTICHANNEL_OME_TIFF &&
            (!rotatedMultichannelOmeFile.isFile() ||
                rotatedMultichannelOmeFile.length() == 0)
        if (needDisplayPng || needFullRes || needNative || needRotatedMultichannel) {
            exportOnlyThisRunCount++
            checkpointNeedsWrite = true
            deliveryComplete = true
            try {
                def exportRegion = null
                double exportCx = 0.0d
                double exportCy = 0.0d
                int finalSidePx = Math.max(1,
                    (int) Math.round(diameter / EXPORT_DOWNSAMPLE))
                if (needDisplayPng || needFullRes || needRotatedMultichannel) {
                    double supportSide = diameter * Math.max(
                        Math.sqrt(2.0d) + 0.01d, ROTATION_SUPPORT_SCALE)
                    exportRegion = readSquare(cx, cy, supportSide, EXPORT_DOWNSAMPLE)
                    exportCx = (cx - exportRegion.originX) / EXPORT_DOWNSAMPLE
                    exportCy = (cy - exportRegion.originY) / EXPORT_DOWNSAMPLE
                }
                if (needDisplayPng || needFullRes) {
                    def displaySupport = makeDisplayRgb(exportRegion.image)
                    def unrotatedCrop = cropAround(displaySupport, exportCx, exportCy,
                        finalSidePx)
                    def rotatedSupport = rotateImageAround(displaySupport, rotateRad,
                        exportCx, exportCy)
                    def rotatedCrop = cropAround(rotatedSupport, exportCx, exportCy,
                        finalSidePx)
                    if (needDisplayPng) {
                        boolean wroteOriginal = ImageIO.write(
                            scaleForPreview(unrotatedCrop, PREVIEW_MAX_PIXELS),
                            'PNG', originalFile)
                        boolean wroteRotated = ImageIO.write(
                            scaleForPreview(rotatedCrop, PREVIEW_MAX_PIXELS),
                            'PNG', outFile)
                        if (!wroteOriginal || !wroteRotated)
                            throw new IOException('Could not refresh QC preview PNG')
                        originalRel = 'unrotated_previews/' + outName
                        previewRel = 'rotated_previews/' + outName
                    }
                    if (needFullRes) {
                        boolean wroteOriginalFull = ImageIO.write(unrotatedCrop, 'PNG',
                            originalFullResFile)
                        boolean wroteRotatedFull = ImageIO.write(rotatedCrop, 'PNG',
                            fullResFile)
                        if (!wroteOriginalFull || !wroteRotatedFull)
                            throw new IOException('Could not write full-resolution PNG')
                        originalFullResRel = 'unrotated_fullres/' + outName
                        rotatedFullResRel = 'rotated_fullres/' + outName
                    }
                }
                if (needNative) {
                    int nativeSide = Math.max(1, (int) Math.round(diameter))
                    int nativeX = Math.max(0, Math.min(imgW - nativeSide,
                        (int) Math.round(cx - nativeSide / 2.0d)))
                    int nativeY = Math.max(0, Math.min(imgH - nativeSide,
                        (int) Math.round(cy - nativeSide / 2.0d)))
                    nativeSide = Math.min(nativeSide,
                        Math.min(imgW - nativeX, imgH - nativeY))
                    new OMEPyramidWriter.Builder(server)
                        .region(nativeX, nativeY, nativeSide, nativeSide)
                        .downsamples(1.0d)
                        .losslessCompression()
                        .channelsPlanar()
                        .build()
                        .writeSeries(nativeOmeFile.getAbsolutePath())
                    if (!nativeOmeFile.isFile() || nativeOmeFile.length() == 0)
                        throw new IOException('Could not write native OME-TIFF')
                    nativeOmeRel = 'source_native_ome/' + nativeOmeFile.getName()
                }
                if (needRotatedMultichannel) {
                    boolean wroteRotatedMultichannel = writeRotatedMultichannelOme(
                        exportRegion.image, rotateRad, exportCx, exportCy,
                        finalSidePx, rotatedMultichannelOmeFile)
                    if (!wroteRotatedMultichannel)
                        throw new IOException('Could not write rotated multichannel OME-TIFF')
                    rotatedMultichannelOmeRel = 'rotated_multichannel_ome/' +
                        rotatedMultichannelOmeFile.getName()
                }
                println "EXPORT-ONLY: ${coreName} reused its accepted rotation and crop."
            } catch (Throwable exportOnlyError) {
                deliveryComplete = false
                processingError = 'Export-only: ' + exportOnlyError.getClass().getSimpleName() +
                    ': ' + (exportOnlyError.getMessage() ?: 'unknown error')
                println "ERROR: ${coreName} research-package backfill failed; " +
                    "the accepted rotation checkpoint was preserved: ${processingError}"
            }
        }
    }

    if (missing) missingCount++
    if (result.status == 'manual_override') overrideCount++
    if (result.status == 'ok' || result.status == 'manual_override') okCount++
    else if (result.status == 'review') reviewCount++
    else failCount++

    int color = (result.status == 'ok') ? COLOR_OK :
        (result.status == 'manual_override') ? COLOR_OVERRIDE :
        (result.status == 'review') ? COLOR_REVIEW : COLOR_FAIL
    try { core.setColor(color) } catch (Throwable ignored) {}

    if (!missing && !['no_tissue', 'processing_error'].contains(result.status)) {
        try {
            // Use the refined crop footprint as an ellipse. The previous
            // direction line made the annotation list look like the core ROI
            // itself was linear, although TMA grid cores are circular.
            def roi = ROIs.createEllipseROI(cx - radius, cy - radius,
                diameter, diameter, ImagePlane.getDefaultPlane())
            def ann = PathObjects.createAnnotationObject(roi, orientationQcClass)
            ann.setName("TMA orientation ${coreName}")
            ann.setColor(color)
            def metadata = ann.getMetadata()
            metadata.put('CoreAlign core', coreName)
            metadata.put('CoreAlign ROI role', 'refined_rotate_then_crop_footprint')
            metadata.put('CoreAlign orientation status', result.status.toString())
            metadata.put('CoreAlign orientation method',
                (result.method ?: 'unknown').toString())
            metadata.put('CoreAlign epidermis angle deg',
                format1(angleToDegrees(result.angle as double)))
            metadata.put('CoreAlign rotate to top deg',
                format1(angleToDegrees(rotateRad)))
            metadata.put('CoreAlign confidence',
                format3(result.confidence as double))
            newOrientationQc << ann
        } catch (Throwable qcObjectError) {
            println "WARNING: Could not create ellipse QC object for ${coreName}: ${qcObjectError.getMessage()}"
        }
    }

    try {
        def ml = core.getMeasurementList()
        try { ml.put('Epidermis angle deg', angleToDegrees(result.angle as double)) }
        catch (Throwable ignored2) { ml.putMeasurement('Epidermis angle deg', angleToDegrees(result.angle as double)) }
        try { ml.put('Rotate to top deg', angleToDegrees(rotateRad)) }
        catch (Throwable ignored2) { ml.putMeasurement('Rotate to top deg', angleToDegrees(rotateRad)) }
        try { ml.put('Orientation confidence', result.confidence as double) }
        catch (Throwable ignored2) { ml.putMeasurement('Orientation confidence', result.confidence as double) }
        try { ml.put('Tissue fraction', result.tissueFraction as double) }
        catch (Throwable ignored2) { ml.putMeasurement('Tissue fraction', result.tissueFraction as double) }
    } catch (Throwable ignored) {}

    def record = [
        image: server.getMetadata().getName(),
        index: i + 1,
        core: coreName,
        row: row,
        col: col,
        nominalCenterX: nominalCx,
        nominalCenterY: nominalCy,
        centerX: cx,
        centerY: cy,
        diameter: diameter,
        regionStatus: regionResult.status ?: 'unknown',
        regionConfidence: (regionResult.confidence ?: 0.0d) as double,
        regionCenterShiftPx: (regionResult.centerShiftPx ?: 0.0d) as double,
        epidermisAngleDeg: angleToDegrees(result.angle as double),
        rotateToTopDeg: angleToDegrees(rotateRad),
        webRotationAdjustmentDeg: webRotationAdjustmentDeg,
        postRotationResidualDeg: postRotationResidualDeg,
        confidence: result.confidence as double,
        tissueFraction: result.tissueFraction as double,
        status: result.status,
        method: result.method ?: 'unknown',
        original: originalRel,
        preview: previewRel,
        originalFullRes: originalFullResRel,
        rotatedFullRes: rotatedFullResRel,
        nativeOme: nativeOmeRel,
        rotatedMultichannelOme: rotatedMultichannelOmeRel,
        resumed: resumed,
        deliveryStatus: deliveryComplete ? 'complete' : 'export_error',
        processingError: processingError
    ]
    records << record

    if (!resumed || checkpointNeedsWrite) {
        boolean processingComplete = result.status != 'processing_error'
        boolean complete = processingComplete && deliveryComplete
        def checkpoint = [schemaVersion: 2, complete: complete,
            processingComplete: processingComplete,
            deliveryComplete: deliveryComplete,
            algorithmVersion: ORIENTATION_ALGORITHM_VERSION, gridHash: gridHash,
            profileHash: CURRENT_PROFILE_HASH,
            processingHash: CURRENT_PROCESSING_HASH,
            outputHash: CURRENT_OUTPUT_HASH,
            coreSignature: coreSignature,
            savedAt: new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()),
            rotateRad: rotateRad,
            postRotationResidualDeg: postRotationResidualDeg,
            result: [status: result.status, angle: result.angle as double,
                confidence: result.confidence as double,
                tissueFraction: result.tissueFraction as double,
                method: result.method ?: 'unknown'],
            record: record]
        if (!processingError.isEmpty()) checkpoint.error = processingError
        try {
            writeAtomic(checkpointFile, json.toJson(checkpoint) + '\n')
        } catch (Throwable checkpointWriteError) {
            println "WARNING: Could not save checkpoint for ${coreName}: ${checkpointWriteError.getMessage()}"
        }
    }
    try {
        writeAtomic(partialCsvFile, recordsCsvText(records))
    } catch (Throwable partialError) {
        println "WARNING: Could not update partial CSV after ${coreName}: ${partialError.getMessage()}"
    }
}

if (!newOrientationQc.isEmpty()) hierarchy.addObjects(newOrientationQc)
try { fireHierarchyUpdate() } catch (Throwable ignored) {}

// -------------------------------------------------------------------------
// CSV output.
// -------------------------------------------------------------------------

def csvFile = new File(outDir, 'orientation_results.csv')
writeAtomic(csvFile, recordsCsvText(records))
def reviewQueueFile = new File(outDir, 'orientation_review_queue.csv')
writeAtomic(reviewQueueFile, recordsCsvText(records.findAll { it.status == 'review' }))

int regionReviewCount = records.count { it.regionStatus == 'region_review' }
int postRotationReviewCount = records.count {
    !it.status.equals('missing') && (it.postRotationResidualDeg as double) > POST_ROTATION_TOLERANCE_DEG
}
def reviewRecords = records.findAll { r ->
    !(r.status in ['ok', 'manual_override', 'missing']) ||
        r.regionStatus == 'region_review' ||
        (r.status != 'missing' &&
            (r.postRotationResidualDeg as double) > POST_ROTATION_TOLERANCE_DEG)
}
int needsReview = reviewRecords.size()
long runCompletedAtMs = System.currentTimeMillis()
long elapsedSeconds = Math.max(0L, Math.round((runCompletedAtMs - runStartedAtMs) / 1000.0d))
String runCompletedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
    .format(new Date(runCompletedAtMs))
def displayRangeRecords = selectedDisplayStats.collect { stat ->
    [channelIndex: (stat.index as int) + 1, channel: stat.name,
        color: stat.colorName, rgb: stat.rgb,
        low: stat.low, high: stat.high]
}
def displayRangesFile = new File(outDir, 'display_ranges.json')
writeAtomic(displayRangesFile, json.toJson([
    schemaVersion: 1,
    rendererVersion: DISPLAY_RENDERER_VERSION,
    scope: 'slide',
    mode: 'shared_slide_range',
    lowPercentile: DISPLAY_LOW_PERCENTILE,
    highPercentile: DISPLAY_HIGH_PERCENTILE,
    gamma: DISPLAY_GAMMA,
    channels: displayRangeRecords,
    note: 'The same ranges are applied to every PNG from this slide. PNG files are for visual review and presentation, not quantitative measurement.'
]) + '\n')
def formatDuration = { long totalSeconds ->
    long hours = totalSeconds.intdiv(3600)
    long minutes = (totalSeconds % 3600L).intdiv(60)
    long seconds = totalSeconds % 60L
    return String.format(Locale.US, '%02d:%02d:%02d', hours, minutes, seconds)
}
def runManifestFile = new File(outDir, 'run_manifest.json')
def runManifest = [schemaVersion: 2, status: PARTIAL_CORE_TEST ? 'PARTIAL_TEST' : 'COMPLETE',
    startedAt: runStartedAt, completedAt: runCompletedAt,
    elapsedSeconds: elapsedSeconds, elapsed: formatDuration(elapsedSeconds),
    image: server.getMetadata().getName(), gridHash: gridHash,
    profileHash: CURRENT_PROFILE_HASH,
    processingHash: CURRENT_PROCESSING_HASH,
    outputHash: CURRENT_OUTPUT_HASH,
    approvalFile: approvalFile.getAbsolutePath(),
    algorithmVersion: ORIENTATION_ALGORITHM_VERSION,
    coreCount: records.size(), resumedFromCheckpoint: resumedCount,
    processedThisRun: processedThisRunCount,
    exportOnlyThisRun: exportOnlyThisRunCount,
    ok: okCount, review: reviewCount, needsReview: needsReview,
    failedOrMissing: failCount,
    missing: missingCount, manualOverrides: overrideCount,
    regionReview: regionReviewCount, postRotationReview: postRotationReviewCount,
    exportDownsample: EXPORT_DOWNSAMPLE, rotationThenCrop: true,
    fullResolutionPng: SAVE_FULL_RESOLUTION_PNG,
    nativeOmeTiff: SAVE_NATIVE_OME_TIFF]
runManifest.rotatedMultichannelOmeTiff = SAVE_ROTATED_MULTICHANNEL_OME_TIFF
runManifest.presentationRendering = [mode: 'shared_slide_range',
    rendererVersion: DISPLAY_RENDERER_VERSION,
    lowPercentile: DISPLAY_LOW_PERCENTILE,
    highPercentile: DISPLAY_HIGH_PERCENTILE,
    gamma: DISPLAY_GAMMA, channels: displayRangeRecords,
    quantitative: false]
writeAtomic(runManifestFile, json.toJson(runManifest) + '\n')

// -------------------------------------------------------------------------
// Contact sheet.
// -------------------------------------------------------------------------

int sheetCols = gridCols > 0 ? gridCols : Math.min(8, Math.max(1, (int) Math.ceil(Math.sqrt(records.size()))))
int sheetRows = (int) Math.ceil(records.size() / (double) sheetCols)
int sheetTile = SHEET_TILE
if (sheetCols * sheetTile > CONTACT_SHEET_MAX_WIDTH_PX) {
    sheetTile = Math.max(CONTACT_SHEET_MIN_TILE,
        (int) Math.floor(CONTACT_SHEET_MAX_WIDTH_PX / Math.max(1, sheetCols)))
    println "Large grid detected; contact-sheet tile size reduced to ${sheetTile}px."
}
int tileH = sheetTile + SHEET_LABEL_H
def sheet = new BufferedImage(sheetCols * sheetTile, sheetRows * tileH, BufferedImage.TYPE_INT_RGB)
Graphics2D sg = sheet.createGraphics()
sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
sg.setColor(Color.WHITE)
sg.fillRect(0, 0, sheet.getWidth(), sheet.getHeight())
sg.setFont(new Font('SansSerif', Font.PLAIN, 12))

records.eachWithIndex { r, i ->
    int c = i % sheetCols
    int rr = i.intdiv(sheetCols)
    int x0 = c * sheetTile
    int y0 = rr * tileH
    Color border = r.status == 'ok' ? new Color(0, 180, 90) :
        r.status == 'manual_override' ? new Color(160, 70, 230) :
        r.status == 'review' ? new Color(245, 165, 0) : new Color(220, 55, 55)

    sg.setColor(Color.BLACK)
    sg.fillRect(x0, y0, sheetTile, sheetTile)
    if (r.preview) {
        try {
            def img = ImageIO.read(new File(outDir, r.preview))
            if (img != null) {
                double scale = Math.min(sheetTile / (double) img.getWidth(), sheetTile / (double) img.getHeight())
                int ww = (int) Math.round(img.getWidth() * scale)
                int hh = (int) Math.round(img.getHeight() * scale)
                int xx = x0 + (sheetTile - ww).intdiv(2)
                int yy = y0 + (sheetTile - hh).intdiv(2)
                sg.drawImage(img, xx, yy, ww, hh, null)
            }
        } catch (Throwable readPreviewError) {
            println "WARNING: Could not add ${r.preview} to contact sheet: ${readPreviewError.getMessage()}"
        }
    }
    sg.setColor(border)
    sg.setStroke(new BasicStroke(4.0f))
    sg.drawRect(x0 + 2, y0 + 2, sheetTile - 4, sheetTile - 4)
    sg.setStroke(new BasicStroke(1.0f))
    sg.setColor(Color.WHITE)
    sg.fillRect(x0, y0 + sheetTile, sheetTile, SHEET_LABEL_H)
    sg.setColor(Color.BLACK)
    sg.drawString("${r.core}  ${r.status}", x0 + 7, y0 + sheetTile + 16)
    sg.drawString("conf ${format3(r.confidence)}  residual ${format1(r.postRotationResidualDeg)} deg", x0 + 7, y0 + sheetTile + 34)
}
sg.dispose()
def sheetFile = new File(outDir, 'orientation_contact_sheet.png')
boolean wroteSheet = ImageIO.write(sheet, 'PNG', sheetFile)
if (!wroteSheet)
    println "WARNING: Could not write contact sheet PNG: ${sheetFile.getAbsolutePath()}"

// -------------------------------------------------------------------------
// Machine-readable run report. The one user-facing HTML file is always the
// project-root REPORT.html, rebuilt by the one-file runner after publish.
// -------------------------------------------------------------------------

def reviewReasons = { r ->
    def reasons = []
    if (!(r.status in ['ok', 'manual_override', 'missing']))
        reasons << "orientation status ${r.status}".toString()
    if (r.regionStatus == 'region_review') reasons << 'crop region review'
    if (r.status != 'missing' &&
            (r.postRotationResidualDeg as double) > POST_ROTATION_TOLERANCE_DEG)
        reasons << "residual ${format1(r.postRotationResidualDeg)} deg".toString()
    return reasons
}
String reportStatus = PARTIAL_CORE_TEST ? 'PARTIAL_TEST' : 'ORIENTATION_REVIEW_REQUIRED'
def runReportJsonFile = new File(outDir, 'run_report.json')
def reportFile = new File(System.getProperty('corealign.project.root',
    projectDir.getAbsolutePath()), 'REPORT.html')
def runReport = [
    schemaVersion: 1,
    status: reportStatus,
    stoppedSafely: true,
    message: PARTIAL_CORE_TEST ?
        'The selected test cores finished.' :
        'Orientation processing finished successfully. The workflow paused intentionally for human review.',
    image: server.getMetadata().getName(),
    profileHash: CURRENT_PROFILE_HASH,
    processingHash: CURRENT_PROCESSING_HASH,
    outputHash: CURRENT_OUTPUT_HASH,
    gridHash: gridHash,
    startedAt: runStartedAt,
    completedAt: runCompletedAt,
    elapsedSeconds: elapsedSeconds,
    elapsed: formatDuration(elapsedSeconds),
    counts: [positions: records.size(), ok: okCount, review: reviewCount,
        needsReview: needsReview, failedOrMissing: failCount, missing: missingCount,
        manualOverrides: overrideCount, resumedFromCheckpoint: resumedCount,
        processedThisRun: processedThisRunCount,
        exportOnlyThisRun: exportOnlyThisRunCount],
    qc: [regionReview: regionReviewCount,
        postRotationResidualReview: postRotationReviewCount,
        postRotationToleranceDeg: POST_ROTATION_TOLERANCE_DEG],
    presentationRendering: [mode: 'shared_slide_range',
        rendererVersion: DISPLAY_RENDERER_VERSION,
        channels: displayRangeRecords,
        rangesFile: displayRangesFile.getAbsolutePath(),
        note: 'One slide-wide display range is used for every core PNG. Use the OME-TIFF files for quantitative analysis.'],
    outputs: [runDirectory: outDir.getAbsolutePath(),
        reportHtml: reportFile.getAbsolutePath(),
        contactSheet: sheetFile.getAbsolutePath(),
        resultsCsv: csvFile.getAbsolutePath(),
        reviewQueueCsv: reviewQueueFile.getAbsolutePath(),
        displayRanges: displayRangesFile.getAbsolutePath(),
        rotatedPreviewDirectory: previewDir.getAbsolutePath(),
        rotatedFullResolutionPngDirectory:
            SAVE_FULL_RESOLUTION_PNG ? fullResDir.getAbsolutePath() : null,
        rotatedMultichannelOmeTiffDirectory:
            SAVE_ROTATED_MULTICHANNEL_OME_TIFF ?
                rotatedMultichannelOmeDir.getAbsolutePath() : null],
    analysisProject: [status: SAVE_ROTATED_MULTICHANNEL_OME_TIFF ?
            'WAITING_FOR_FINAL_HUMAN_APPROVAL' : 'RESEARCH_OUTPUT_REQUIRED',
        note: SAVE_ROTATED_MULTICHANNEL_OME_TIFF ?
            'The QuPath core project will be built after final human approval.' :
            'Presentation PNG files are not used for quantitative analysis. Choose Research package and run CoreAlign again to backfill multichannel OME-TIFF files without repeating detection or rotation.'],
    reviewCores: reviewRecords.collect { r ->
        [core: r.core, row: r.row, col: r.col, status: r.status,
            confidence: r.confidence, regionStatus: r.regionStatus,
            postRotationResidualDeg: r.postRotationResidualDeg,
            reasons: reviewReasons(r)]
    },
    cores: records.collect { r ->
        [index: r.index, core: r.core, row: r.row, col: r.col,
            status: r.status, confidence: r.confidence,
            regionStatus: r.regionStatus,
            rotateToTopDeg: r.rotateToTopDeg,
            webRotationAdjustmentDeg: r.webRotationAdjustmentDeg,
            postRotationResidualDeg: r.postRotationResidualDeg,
            reasons: reviewReasons(r),
            rotatedPreview: r.preview ? "qc/02-orientation/${r.preview}" : null,
            unrotatedPreview: r.original ? "qc/02-orientation/${r.original}" : null]
    },
    nextSteps: PARTIAL_CORE_TEST ?
        ['Inspect the selected test-core outputs.'] :
        ['Open REPORT.html.',
         'Check the listed review cores and correct only those that need changes in QuPath.',
         'Run CoreAlign.groovy again. Accepted checkpoints will be reused.']
]
writeAtomic(runReportJsonFile, json.toJson(runReport) + '\n')
writeAtomic(new File(exportBaseDir, 'LATEST_REPORT.txt'),
    reportFile.getAbsolutePath() + '\n')
try { new File(exportBaseDir, 'LATEST_RUN_REPORT.txt').delete() } catch (Throwable ignored) {}

def summaryFile = new File(outDir, 'workflow_summary.txt')
summaryFile.withPrintWriter('UTF-8') { pw ->
    pw.println('TMA auto-orientation summary')
    pw.println("Image: ${server.getMetadata().getName()}")
    pw.println("Cores: ${records.size()}")
    pw.println("OK: ${okCount}")
    pw.println("Review: ${reviewCount}")
    pw.println("Failed or missing: ${failCount}")
    pw.println("Missing grid positions: ${missingCount}")
    pw.println("Manual overrides: ${overrideCount}")
    pw.println("Region review flags: ${regionReviewCount}")
    pw.println("Post-rotation residual flags: ${postRotationReviewCount}")
    pw.println("Export downsample: ${EXPORT_DOWNSAMPLE} (1.0 = original pixel resolution)")
    pw.println('Processing order: source support window -> rotate -> final crop')
    pw.println("Approved grid hash: ${gridHash}")
    pw.println("Resumed from checkpoint: ${resumedCount}")
    pw.println("Processed this run: ${processedThisRunCount}")
    pw.println("Export-only backfill this run: ${exportOnlyThisRunCount}")
    if (!gridEmptyRows.isEmpty() || !gridEmptyCols.isEmpty()) {
        pw.println("Grid warning: empty rows ${gridEmptyRows}; empty columns ${gridEmptyCols}")
    }
    pw.println('')
    pw.println('Review order:')
    pw.println('1. Open the project-root REPORT.html.')
    pw.println('2. Check red and yellow cores first.')
    pw.println("3. If a direction is wrong, draw a small annotation on the true epidermis side, set class or name to '${OVERRIDE_CLASS_NAME}', and re-run this script.")
    pw.println("4. If the crop region is wrong, draw an annotation named '${CROP_OVERRIDE_CLASS_NAME} <core name>' around the desired core crop and re-run.")
    pw.println('')
    pw.println('Files:')
    pw.println("CSV: ${csvFile.getName()}")
    pw.println("Contact sheet: ${sheetFile.getName()}")
    pw.println("Review queue CSV: ${reviewQueueFile.getName()}")
    pw.println('Interactive dashboard: REPORT.html')
    pw.println("Unrotated previews: ${originalDir.getName()}/")
    pw.println("Rotated previews: ${previewDir.getName()}/")
    if (SAVE_FULL_RESOLUTION_PNG) {
        pw.println("Unrotated full-resolution PNG: ${originalFullResDir.getName()}/")
        pw.println("Rotated-then-cropped full-resolution PNG: ${fullResDir.getName()}/")
    }
    if (SAVE_NATIVE_OME_TIFF)
        pw.println("Native multichannel OME-TIFF source crops: ${nativeOmeDir.getName()}/")
    if (SAVE_ROTATED_MULTICHANNEL_OME_TIFF)
        pw.println("Rotated multichannel OME-TIFF crops: ${rotatedMultichannelOmeDir.getName()}/")
}

println '=== Step 2 done ==='
println "Cores: ${records.size()}"
println "OK: ${okCount}, review: ${reviewCount}, failed/missing: ${failCount}, missing grid positions: ${missingCount}, manual overrides: ${overrideCount}"
println "Region review: ${regionReviewCount}; post-rotation residual review: ${postRotationReviewCount}"
println "Resumed from checkpoint: ${resumedCount}; processed this run: ${processedThisRunCount}; export-only backfill: ${exportOnlyThisRunCount}"
println "CSV: ${csvFile.getAbsolutePath()}"
println "Contact sheet: ${sheetFile.getAbsolutePath()}"
println "Review queue: ${reviewQueueFile.getAbsolutePath()}"
println "Interactive dashboard: ${reportFile.getAbsolutePath()}"
println "Summary: ${summaryFile.getAbsolutePath()}"

runManifest.status = PARTIAL_CORE_TEST ? 'PARTIAL_TEST' : 'COMPLETE'
runManifest.completedAt = runCompletedAt
runManifest.projectDashboard = reportFile.getAbsolutePath()
writeAtomic(runManifestFile, json.toJson(runManifest) + '\n')
if (!PARTIAL_CORE_TEST)
    writeAtomic(new File(exportBaseDir, 'LATEST_FINAL_RUN.txt'), outDir.getAbsolutePath() + '\n')
System.setProperty('tma.orientation.status', PARTIAL_CORE_TEST ? 'PARTIAL_TEST' : 'COMPLETE')
System.setProperty('tma.orientation.processedThisRun', processedThisRunCount.toString())
System.setProperty('tma.orientation.exportOnlyThisRun', exportOnlyThisRunCount.toString())
System.setProperty('tma.orientation.reportPath', reportFile.getAbsolutePath())
System.setProperty('tma.orientation.reportJsonPath', runReportJsonFile.getAbsolutePath())
System.setProperty('tma.orientation.runDir', outDir.getAbsolutePath())
System.setProperty('tma.orientation.totalCount', records.size().toString())
System.setProperty('tma.orientation.okCount', okCount.toString())
System.setProperty('tma.orientation.reviewCount', needsReview.toString())
System.setProperty('tma.orientation.missingCount', missingCount.toString())
System.setProperty('tma.orientation.elapsed', formatDuration(elapsedSeconds))

if (needsReview > 0) {
    Dialogs.showWarningNotification('Auto-orient TMA done',
        "Exported ${records.size()} cores. Open REPORT.html and review ${needsReview} flagged cores.")
} else {
    Dialogs.showInfoNotification('Auto-orient TMA done',
        "Exported ${records.size()} cores. All exported cores passed confidence checks.")
}
