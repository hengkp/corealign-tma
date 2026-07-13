/**
 * STEP 6 - Build a config-driven, presentation-ready package.
 *
 * Creates 16:9 PNG comparison panels plus a traceable manifest and selected
 * replicate CSV.  The output can be inserted directly into PowerPoint/Keynote,
 * while templatePptx/template slide metadata is retained for an automated deck
 * builder.  No detection or orientation work is repeated here.
 */

import static qupath.lib.scripting.QP.*

import com.google.gson.GsonBuilder
import qupath.fx.dialogs.Dialogs

import javax.imageio.ImageIO
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat

def json = new GsonBuilder().setPrettyPrinting().create()
def imageData = getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('TMA presentation export', 'No image is open.')
    return
}

def server = imageData.getServer()
File workflowDir = null
try {
    def fileUri = server.getURIs().find { it != null && it.getScheme() == 'file' }
    if (fileUri != null) workflowDir = new File(fileUri).getParentFile()
} catch (Throwable ignored) {}
if (workflowDir == null) workflowDir = new File(System.getProperty('user.home'), 'QuPath-TMA-export')

String imageName = server.getMetadata().getName() ?: 'image'
String imageStem = imageName.replaceAll(/(?i)\.ome\.tif+$/, '')
    .replaceAll(/[^A-Za-z0-9._-]+/, '_')
if (imageStem.isEmpty()) imageStem = 'image'

File configFile = new File(System.getProperty('tma.config.path',
    new File(workflowDir, 'tma_pipeline_config.json').getAbsolutePath()))
if (!configFile.isFile()) {
    println "Presentation export skipped: config not found at ${configFile.getAbsolutePath()}"
    System.setProperty('tma.presentation.status', 'SKIPPED_NO_CONFIG')
    return
}

def config = json.fromJson(configFile.getText('UTF-8'), Map.class)
String profileName = System.getProperty('tma.config.profile', config.activeProfile?.toString() ?: '')
def profile = config.profiles instanceof Map ? config.profiles[profileName] : null
if (!(profile instanceof Map)) {
    Dialogs.showErrorMessage('TMA presentation export',
        "Profile '${profileName}' is not defined in ${configFile.getName()}.")
    System.setProperty('tma.presentation.status', 'BLOCKED_BAD_CONFIG')
    return
}
def presentation = profile.presentation instanceof Map ? profile.presentation : [:]
if (!(presentation.enabled == true)) {
    println "Presentation export disabled for profile ${profileName}."
    System.setProperty('tma.presentation.status', 'DISABLED')
    return
}

def conditions = presentation.conditions instanceof List ? presentation.conditions : []
def treatmentColumns = presentation.treatmentColumns instanceof List ? presentation.treatmentColumns : []
def comparisons = presentation.comparisons instanceof List ? presentation.comparisons : []
if (conditions.isEmpty() || treatmentColumns.isEmpty() || comparisons.isEmpty()) {
    Dialogs.showErrorMessage('TMA presentation export',
        'presentation.conditions, treatmentColumns, and comparisons must all be non-empty.')
    System.setProperty('tma.presentation.status', 'BLOCKED_BAD_CONFIG')
    return
}

File exportBase = new File(System.getProperty('corealign.work.runBaseDir',
    new File(workflowDir, 'tma_auto_orient_export').getAbsolutePath()))
File latestPointer = new File(exportBase, 'LATEST_FINAL_RUN.txt')
if (!latestPointer.isFile()) {
    Dialogs.showErrorMessage('TMA presentation export',
        "No completed orientation run pointer found at ${latestPointer.getAbsolutePath()}.")
    System.setProperty('tma.presentation.status', 'BLOCKED_NO_ORIENTATION')
    return
}
File runDir = new File(latestPointer.getText('UTF-8').trim())
File orientationCsv = new File(runDir, 'orientation_results.csv')
if (!runDir.isDirectory() || !orientationCsv.isFile()) {
    Dialogs.showErrorMessage('TMA presentation export',
        "The latest orientation run is incomplete: ${runDir.getAbsolutePath()}.")
    System.setProperty('tma.presentation.status', 'BLOCKED_NO_ORIENTATION')
    return
}

def parseCsvLine = { String line ->
    def values = []
    StringBuilder field = new StringBuilder()
    boolean quoted = false
    for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i)
        if (quoted) {
            if (ch == '"' as char && i + 1 < line.length() && line.charAt(i + 1) == ('"' as char)) {
                field.append('"'); i++
            } else if (ch == ('"' as char)) quoted = false
            else field.append(ch)
        } else if (ch == ('"' as char)) quoted = true
        else if (ch == (',' as char)) { values << field.toString(); field.setLength(0) }
        else field.append(ch)
    }
    values << field.toString()
    return values
}

def csvLines = orientationCsv.readLines('UTF-8').findAll { !it.trim().isEmpty() }
def headers = parseCsvLine(csvLines[0])
def records = csvLines.drop(1).collect { line ->
    def values = parseCsvLine(line)
    def row = [:]
    headers.eachWithIndex { h, i -> row[h] = i < values.size() ? values[i] : '' }
    return row
}

def conditionById = conditions.collectEntries { [(it.id.toString()): it] }
def treatmentById = treatmentColumns.collectEntries { [(it.id.toString()): it] }
def statusPriority = presentation.selectionStatusPriority instanceof List ?
    presentation.selectionStatusPriority.collect { it.toString() } :
    ['manual_override', 'manual_corrected', 'approved', 'ok', 'review', 'uncertain']
def statusRank = statusPriority.withIndex().collectEntries { value, index ->
    [(value): statusPriority.size() - index]
}

def chooseRepresentative = { String conditionId, String treatmentId ->
    def condition = conditionById[conditionId]
    def treatment = treatmentById[treatmentId]
    if (condition == null) throw new IllegalArgumentException("Unknown condition '${conditionId}'")
    if (treatment == null) throw new IllegalArgumentException("Unknown treatment '${treatmentId}'")
    def allowedRows = (condition.rows ?: []).collect { (it as Number).intValue() } as Set
    int gridColumn = (treatment.gridColumn as Number).intValue()
    def candidates = records.findAll { row ->
        allowedRows.contains((row.row ?: '0') as int) &&
            ((row.col ?: '0') as int) == gridColumn &&
            row.status != 'missing' && (row.rotated_fullres_png || row.rotated_preview_png)
    }
    candidates.sort { a, b ->
        int sa = statusRank[a.status] ?: 0
        int sb = statusRank[b.status] ?: 0
        if (sa != sb) return sb <=> sa
        double ca = (a.confidence ?: '0') as double
        double cb = (b.confidence ?: '0') as double
        if (ca != cb) return cb <=> ca
        double ta = (a.tissue_fraction ?: '0') as double
        double tb = (b.tissue_fraction ?: '0') as double
        if (ta != tb) return tb <=> ta
        return ((a.index ?: '0') as int) <=> ((b.index ?: '0') as int)
    }
    if (candidates.isEmpty())
        throw new IllegalStateException("No usable oriented core for ${conditionId}/${treatmentId}")
    return candidates[0]
}

File stateDir = new File(System.getProperty('corealign.work.stateDir',
    new File(new File(workflowDir, 'tma_pipeline_state'), imageStem).getAbsolutePath()))
File finalApprovalFile = new File(stateDir, 'final_orientation_approval.json')
def approval = finalApprovalFile.isFile() ?
    json.fromJson(finalApprovalFile.getText('UTF-8'), Map.class) : [:]
boolean humanApproved = approval.status == 'APPROVED' && approval.approvalMode == 'human'
String gridHash = approval.gridHash?.toString() ?:
    (records.isEmpty() ? 'unknown' : records[0].approved_grid_hash?.toString() ?: 'unknown')
String gridShort = gridHash == 'unknown' ? 'unknown' : gridHash.take(12)

File presentationRoot = new File(System.getProperty('corealign.results.presentationDir',
    new File(workflowDir, 'presentation_ready').getAbsolutePath()))
File packageDir = new File(presentationRoot, "${imageStem}_grid_${gridShort}")
File panelDir = new File(packageDir, 'presentation_panels')
panelDir.mkdirs()

int canvasW = ((presentation.outputWidthPx ?: 1600) as Number).intValue()
int canvasH = ((presentation.outputHeightPx ?: 900) as Number).intValue()
String fontName = presentation.fontName?.toString() ?: 'Anuphan'
Font titleFont = new Font(fontName, Font.BOLD, Math.max(22, (int) Math.round(canvasH * 0.033d)))
Font rowFont = new Font(fontName, Font.BOLD, Math.max(20, (int) Math.round(canvasH * 0.028d)))
Font labelFont = new Font(fontName, Font.PLAIN, Math.max(16, (int) Math.round(canvasH * 0.022d)))
Font qcFont = new Font('SansSerif', Font.PLAIN, Math.max(11, (int) Math.round(canvasH * 0.014d)))

def fitImage = { Graphics2D g, BufferedImage img, int x, int y, int w, int h ->
    double scale = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight())
    int dw = Math.max(1, (int) Math.round(img.getWidth() * scale))
    int dh = Math.max(1, (int) Math.round(img.getHeight() * scale))
    int drawX = x + (int) Math.round((w - dw) / 2.0d)
    int drawY = y + (int) Math.round((h - dh) / 2.0d)
    g.drawImage(img, drawX, drawY, dw, dh, null)
}

def drawCentered = { Graphics2D g, String text, Font font, int x, int y, int w ->
    g.setFont(font)
    int sw = g.getFontMetrics().stringWidth(text ?: '')
    int drawX = x + Math.max(0, (int) Math.round((w - sw) / 2.0d))
    g.drawString(text ?: '', drawX, y)
}

def csvEscape = { value ->
    String s = value == null ? '' : value.toString()
    return (s.contains(',') || s.contains('"') || s.contains('\n')) ?
        '"' + s.replace('"', '""') + '"' : s
}

def selected = []
def panelManifest = []
comparisons.eachWithIndex { comparison, comparisonIndex ->
    String comparisonId = comparison.id?.toString() ?: "comparison_${comparisonIndex + 1}"
    String topId = comparison.topCondition.toString()
    String bottomId = comparison.bottomCondition.toString()
    def treatmentIds = (comparison.treatments ?: []).collect { it.toString() }
    if (treatmentIds.isEmpty()) throw new IllegalArgumentException("${comparisonId} has no treatments")

    BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB)
    Graphics2D g = canvas.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setColor(Color.WHITE)
    g.fillRect(0, 0, canvasW, canvasH)
    g.setColor(Color.BLACK)

    int outer = Math.max(28, (int) Math.round(canvasW * 0.025d))
    int leftLabelW = Math.max(150, (int) Math.round(canvasW * 0.115d))
    int titleY = Math.max(34, (int) Math.round(canvasH * 0.052d))
    int rowGap = Math.max(34, (int) Math.round(canvasH * 0.045d))
    int columnGap = Math.max(14, (int) Math.round(canvasW * 0.012d))
    int labelH = Math.max(35, (int) Math.round(canvasH * 0.050d))
    int imageTop = Math.max(74, (int) Math.round(canvasH * 0.105d))
    int usableW = canvasW - outer * 2 - leftLabelW
    int cellW = (usableW - columnGap * (treatmentIds.size() - 1)) / treatmentIds.size()
    int imageSize = Math.min(cellW, (canvasH - imageTop - outer - rowGap - labelH * 2) / 2)
    int secondTop = imageTop + imageSize + labelH + rowGap

    String panelTitle = comparison.title?.toString() ?: (presentation.deckTitle?.toString() ?: '')
    if (!panelTitle.isEmpty()) {
        g.setColor(new Color(70, 70, 70))
        drawCentered(g, panelTitle, titleFont, outer, titleY, canvasW - outer * 2)
    }

    [topId, bottomId].eachWithIndex { conditionId, rowIndex ->
        def condition = conditionById[conditionId]
        int imageY = rowIndex == 0 ? imageTop : secondTop
        g.setColor(Color.BLACK)
        g.setFont(rowFont)
        g.drawString(condition.label?.toString() ?: conditionId, outer, imageY + rowFont.getSize())

        treatmentIds.eachWithIndex { treatmentId, columnIndex ->
            def treatment = treatmentById[treatmentId]
            def record = chooseRepresentative(conditionId, treatmentId)
            String selectedImageRel = record.rotated_fullres_png ?: record.rotated_preview_png
            File previewFile = new File(runDir, selectedImageRel.toString())
            BufferedImage preview = ImageIO.read(previewFile)
            if (preview == null) throw new IOException("Cannot read ${previewFile.getAbsolutePath()}")
            int x = outer + leftLabelW + columnIndex * (cellW + columnGap)
            fitImage(g, preview, x, imageY, cellW, imageSize)

            boolean needsReview = !(record.status in ['ok', 'manual_override', 'manual_corrected', 'approved'])
            g.setStroke(new BasicStroke(needsReview ? 5.0f : 1.5f))
            g.setColor(needsReview ? new Color(245, 165, 0) : new Color(95, 95, 95))
            g.drawRect(x, imageY, cellW, imageSize)
            g.setColor(Color.BLACK)
            drawCentered(g, treatment.label?.toString() ?: treatmentId, labelFont,
                x, imageY + imageSize + labelFont.getSize() + 7, cellW)
            if (needsReview) {
                g.setColor(new Color(245, 165, 0))
                g.fillRect(x + 4, imageY + 4, Math.min(cellW - 8, 180), qcFont.getSize() + 10)
                g.setColor(Color.BLACK)
                g.setFont(qcFont)
                g.drawString("QC ${record.core} ${record.status} ${record.confidence}",
                    x + 10, imageY + qcFont.getSize() + 7)
            }

            selected << [panel: comparisonId, panelIndex: comparisonIndex + 1,
                rowRole: rowIndex == 0 ? 'top' : 'bottom', condition: conditionId,
                treatment: treatmentId, gridColumn: treatment.gridColumn,
                core: record.core, sourceRow: record.row, status: record.status,
                confidence: record.confidence, tissueFraction: record.tissue_fraction,
                rotateToTopDeg: record.rotate_to_top_deg,
                previewPng: previewFile.getAbsolutePath(), requiresHumanReview: needsReview]
        }
    }
    g.dispose()
    File panelFile = new File(panelDir,
        String.format(Locale.US, '%02d_%s.png', comparisonIndex + 1,
            comparisonId.replaceAll(/[^A-Za-z0-9._-]+/, '_')))
    ImageIO.write(canvas, 'png', panelFile)
    panelManifest << [id: comparisonId, file: panelFile.getAbsolutePath(),
        topCondition: topId, bottomCondition: bottomId, treatments: treatmentIds]
}

boolean anySelectionNeedsReview = selected.any { it.requiresHumanReview }
boolean blockOnReview = profile.quality?.blockPresentationWhenAnySelectedCoreNeedsReview != false
String presentationStatus = humanApproved && (!blockOnReview || !anySelectionNeedsReview) ?
    'PRESENTATION_READY' : 'QC_DRAFT'

def manifest = [
    schemaVersion: 1,
    createdAt: new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()),
    image: imageName,
    profile: profileName,
    profileHash: System.getProperty('tma.config.profileHash', ''),
    configPath: configFile.getAbsolutePath(),
    sourceRunDirectory: runDir.getAbsolutePath(),
    gridHash: gridHash,
    orientationResultHash: approval.orientationResultHash,
    approvalStatus: approval.status,
    approvalMode: approval.approvalMode,
    humanApproved: humanApproved,
    presentationStatus: presentationStatus,
    anySelectionNeedsReview: anySelectionNeedsReview,
    blockPresentationWhenAnySelectedCoreNeedsReview: blockOnReview,
    imageSemantics: 'Normalized RGB composite for morphology/orientation review; not quantitative marker intensity.',
    selectionPolicy: statusPriority,
    templatePptx: presentation.templatePptx,
    templateSectionSlide: presentation.templateSectionSlide,
    templateComparisonSlide: presentation.templateComparisonSlide,
    panels: panelManifest,
    selections: selected
]

def writeAtomic = { File target, String text ->
    target.getParentFile()?.mkdirs()
    File tmp = new File(target.getParentFile(), target.getName() + '.tmp')
    tmp.setText(text, 'UTF-8')
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
    } catch (Throwable ignored) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

writeAtomic(new File(packageDir, 'presentation_manifest.json'), json.toJson(manifest) + '\n')
writeAtomic(new File(packageDir, 'profile_snapshot.json'), json.toJson(profile) + '\n')
def fields = ['panel', 'panelIndex', 'rowRole', 'condition', 'treatment', 'gridColumn',
    'core', 'sourceRow', 'status', 'confidence', 'tissueFraction', 'rotateToTopDeg',
    'requiresHumanReview', 'previewPng']
StringBuilder selectedCsv = new StringBuilder(fields.join(',')).append('\n')
selected.each { row ->
    selectedCsv.append(fields.collect { csvEscape(row[it]) }.join(',')).append('\n')
}
writeAtomic(new File(packageDir, 'selected_replicates.csv'), selectedCsv.toString())

StringBuilder html = new StringBuilder('<!doctype html><meta charset="utf-8">')
html.append('<title>TMA presentation package</title>')
html.append('<style>body{font-family:Arial,sans-serif;margin:24px;background:#eee}img{width:100%;max-width:1200px;background:white;margin:16px 0;box-shadow:0 2px 12px #888} .warn{color:#a65b00;font-weight:bold}</style>')
html.append("<h1>${presentation.deckTitle ?: 'TMA presentation package'}</h1>")
html.append("<p>Status: <span class='${presentationStatus == 'QC_DRAFT' ? 'warn' : ''}'>${presentationStatus}</span>; profile ${profileName}; human approval ${humanApproved}</p>")
panelManifest.each { panel ->
    html.append("<h2>${panel.id}</h2><img src='presentation_panels/${new File(panel.file.toString()).getName()}'>")
}
writeAtomic(new File(packageDir, 'index.html'), html.toString())

System.setProperty('tma.presentation.status', presentationStatus)
System.setProperty('tma.presentation.packageDir', packageDir.getAbsolutePath())
println "Presentation package: ${packageDir.getAbsolutePath()}"
println "Presentation status: ${presentationStatus}; selected slots requiring review: ${selected.count { it.requiresHumanReview }}/${selected.size()}"
if (presentationStatus == 'QC_DRAFT') {
    Dialogs.showInfoNotification('TMA presentation package — QC draft',
        'Panels were created, but the package remains QC_DRAFT until human approval and configured review flags are cleared.')
} else {
    Dialogs.showInfoNotification('TMA presentation package ready',
        "Panels and manifest saved under ${packageDir.getName()}.")
}
