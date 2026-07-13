/**
 * STEP 3 - Human-in-the-loop review, correction and approval of a TMA grid.
 *
 * Normal use:
 *   1. Inspect the cyan/red TMA grid and tma_grid_qc PNG from Step 1.
 *   2. To replace a wrong/missed core, draw a small annotation centred on the
 *      correct tissue and classify/name it "TMA correction".  Add the target
 *      core name to the annotation name when possible, e.g.
 *      "TMA correction 4-D".  Without a target name, CoreAlign first maps the
 *      circle to a robust fitted lattice position.  If a missed core was
 *      inserted between two detected cores, later cores are shifted into the
 *      next missing slot automatically so the row labels remain aligned.
 *   3. To mark a grid cell truly absent, draw an annotation near it and
 *      classify/name it "TMA mark missing", e.g. "TMA mark missing 4-D".
 *   4. Run this script.  Corrections are applied, validation is shown, and the
 *      operator must explicitly approve before the expensive orientation step.
 *
 * The approved coordinates and a SHA-256 hash are stored outside the QuPath
 * session.  They can therefore be restored after a crash or app restart.
 */

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import qupath.lib.common.ColorTools
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.DefaultTMAGrid
import qupath.lib.plugins.parameters.ParameterList

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat

String WORKFLOW_VERSION = 'skin-tma-hitl-1.0'
def json = new GsonBuilder().setPrettyPrinting().create()
def compactJson = new Gson()
String CORRECTION_CLASS = 'TMA correction'
String MARK_MISSING_CLASS = 'TMA mark missing'
int COLOR_DETECTED = ColorTools.packRGB(0, 255, 255)
int COLOR_MISSING = ColorTools.packRGB(255, 48, 48)
int COLOR_CORRECTED = ColorTools.packRGB(80, 220, 80)
double LOW_CONFIDENCE_THRESHOLD = 0.75d

def imageData = getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('TMA grid review', 'No image is open.')
    return
}
def server = imageData.getServer()
def hierarchy = imageData.getHierarchy()
def grid = hierarchy.getTMAGrid()
if (grid == null || grid.getTMACoreList().isEmpty()) {
    Dialogs.showErrorMessage('TMA grid review',
        'No TMA grid is available. Run 01_build_tma_grid.groovy first.')
    return
}

def resolveBaseDir = {
    File base = null
    try {
        def project = getProject()
        if (project != null && project.getPath() != null)
            base = project.getPath().getParent().toFile()
    } catch (Throwable ignored) {}
    if (base == null) {
        try {
            def uri = server.getURIs().find { it != null && it.getScheme() == 'file' }
            if (uri != null) base = new File(uri).getParentFile()
        } catch (Throwable ignored) {}
    }
    return base == null ? new File(System.getProperty('user.home'), 'QuPath-TMA-export') : base
}

String rawImageName = server.getMetadata().getName()
if (rawImageName == null || rawImageName.trim().isEmpty()) rawImageName = 'image'
String imageStem = rawImageName.replaceAll(/(?i)\.ome\.tif+$/, '')
    .replaceAll(/[^A-Za-z0-9._-]+/, '_')
if (imageStem.isEmpty()) imageStem = 'image'
File stateDir = new File(new File(resolveBaseDir(), 'tma_pipeline_state'), imageStem)
File approvalFile = new File(stateDir, 'approved_grid.json')
File coordinatesFile = new File(stateDir, 'approved_grid_coordinates.csv')
File queueFile = new File(stateDir, 'grid_review_queue.csv')
File eventsFile = new File(stateDir, 'pipeline_events.jsonl')
if (!stateDir.mkdirs() && !stateDir.isDirectory()) {
    Dialogs.showErrorMessage('TMA grid review',
        "Could not create state folder:\n${stateDir.getAbsolutePath()}")
    return
}

def fmt3 = { double v -> String.format(Locale.US, '%.3f', v) }
def csv = { value ->
    String s = value == null ? '' : value.toString()
    return '"' + s.replace('"', '""') + '"'
}
def sha256 = { String value ->
    byte[] digest = MessageDigest.getInstance('SHA-256').digest(value.getBytes('UTF-8'))
    return digest.collect { String.format('%02x', it & 0xff) }.join()
}
def canonicalGrid = { g ->
    def lines = ["width=${g.getGridWidth()}|height=${g.getGridHeight()}"]
    g.getTMACoreList().eachWithIndex { core, i ->
        def roi = core.getROI()
        double d = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight())
        lines << [i, core.getName() ?: '', fmt3(roi.getCentroidX()),
            fmt3(roi.getCentroidY()), fmt3(d), core.isMissing()].join('|')
    }
    return lines.join('\n')
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
def appendEvent = { Map event ->
    event.timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
    eventsFile.withWriterAppend('UTF-8') { it.println(compactJson.toJson(event)) }
}
def classNameOf = { obj ->
    try { return obj.getPathClass()?.getName() } catch (Throwable ignored) { return null }
}
def objectNameOf = { obj ->
    try { return obj.getName() } catch (Throwable ignored) { return null }
}
def isTagged = { obj, String tag ->
    String c = (classNameOf(obj) ?: '').trim()
    String n = (objectNameOf(obj) ?: '').trim()
    return c.equalsIgnoreCase(tag) || n.toLowerCase().contains(tag.toLowerCase())
}
def copyCoreMetadata = { source, target ->
    try {
        source.getMetadata().each { k, v -> target.putMetadataValue(k.toString(), v?.toString() ?: '') }
    } catch (Throwable ignored) {}
}

def median = { values ->
    if (values == null || values.isEmpty()) return Double.NaN
    def sorted = values.collect { (double) it }.sort()
    int n = sorted.size()
    return n % 2 == 1 ? sorted[n.intdiv(2)] :
        (sorted[n.intdiv(2) - 1] + sorted[n.intdiv(2)]) / 2.0d
}

// Apply explicitly tagged correction annotations.  Rebuilding the grid keeps
// the row/column order fixed and makes the operation deterministic/idempotent.
def cores = new ArrayList(grid.getTMACoreList())
int gridCols = grid.getGridWidth()
def presentDiameters = cores.findAll { !it.isMissing() }.collect {
    Math.max(it.getROI().getBoundsWidth(), it.getROI().getBoundsHeight())
}.findAll { it > 1.0d }
double nominalDiameter = median(presentDiameters)
if (!(nominalDiameter > 1.0d)) nominalDiameter = 1200.0d

def nearestNeighbourDistances = []
cores.eachWithIndex { a, i ->
    double best = Double.POSITIVE_INFINITY
    cores.eachWithIndex { b, j ->
        if (i == j) return
        double dx = a.getROI().getCentroidX() - b.getROI().getCentroidX()
        double dy = a.getROI().getCentroidY() - b.getROI().getCentroidY()
        best = Math.min(best, Math.sqrt(dx * dx + dy * dy))
    }
    if (Double.isFinite(best)) nearestNeighbourDistances << best
}
double typicalSpacing = median(nearestNeighbourDistances)
if (!(typicalSpacing > 0.0d)) typicalSpacing = nominalDiameter * 1.4d

def indexByName = [:]
cores.eachWithIndex { core, i -> indexByName[(core.getName() ?: '').toUpperCase()] = i }

// A correction drawn on a missed tissue core can sit halfway between two live
// (mis-indexed) circles.  Nearest-neighbour mapping therefore cannot resolve
// it.  Fit a robust affine lattice from row/column indices to slide positions;
// outlier trimming makes the prediction insensitive to misplaced/missing cores.
def solve3x3 = { double[][] matrix, double[] values ->
    double[][] a = new double[3][4]
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) a[r][c] = matrix[r][c]
        a[r][3] = values[r]
    }
    for (int pivot = 0; pivot < 3; pivot++) {
        int bestRow = pivot
        for (int r = pivot + 1; r < 3; r++)
            if (Math.abs(a[r][pivot]) > Math.abs(a[bestRow][pivot])) bestRow = r
        if (Math.abs(a[bestRow][pivot]) < 1.0e-9d) return null
        if (bestRow != pivot) {
            double[] tmp = a[pivot]
            a[pivot] = a[bestRow]
            a[bestRow] = tmp
        }
        double divisor = a[pivot][pivot]
        for (int c = pivot; c < 4; c++) a[pivot][c] /= divisor
        for (int r = 0; r < 3; r++) {
            if (r == pivot) continue
            double factor = a[r][pivot]
            for (int c = pivot; c < 4; c++) a[r][c] -= factor * a[pivot][c]
        }
    }
    return [a[0][3], a[1][3], a[2][3]] as double[]
}
def fitAffineLattice = { samples ->
    if (samples == null || samples.size() < 6) return null
    double[][] normal = new double[3][3]
    double[] rhsX = new double[3]
    double[] rhsY = new double[3]
    samples.each { s ->
        double[] p = [1.0d, (double) s.row, (double) s.col] as double[]
        for (int r = 0; r < 3; r++) {
            rhsX[r] += p[r] * (double) s.x
            rhsY[r] += p[r] * (double) s.y
            for (int c = 0; c < 3; c++) normal[r][c] += p[r] * p[c]
        }
    }
    double[] bx = solve3x3(normal, rhsX)
    double[] by = solve3x3(normal, rhsY)
    return bx == null || by == null ? null : [x: bx, y: by]
}
def predictLattice = { model, int row, int col ->
    if (model == null) return null
    return [
        x: model.x[0] + model.x[1] * row + model.x[2] * col,
        y: model.y[0] + model.y[1] * row + model.y[2] * col
    ]
}
def latticeSamples = []
cores.eachWithIndex { core, i ->
    latticeSamples << [row: i.intdiv(gridCols) + 1, col: i % gridCols + 1,
        x: core.getROI().getCentroidX(), y: core.getROI().getCentroidY()]
}
def latticeWorking = new ArrayList(latticeSamples)
def latticeModel = null
3.times {
    def fitted = fitAffineLattice(latticeWorking)
    if (fitted == null) return
    latticeModel = fitted
    def withResidual = latticeSamples.collect { s ->
        def p = predictLattice(fitted, (int) s.row, (int) s.col)
        double dx = (double) s.x - (double) p.x
        double dy = (double) s.y - (double) p.y
        return [sample: s, residual: Math.sqrt(dx * dx + dy * dy)]
    }
    double residualMedian = median(withResidual.collect { it.residual })
    double residualMad = median(withResidual.collect { Math.abs((double) it.residual - residualMedian) })
    double cutoff = residualMedian + Math.max(3.0d * residualMad, typicalSpacing * 0.12d)
    def filtered = withResidual.findAll { (double) it.residual <= cutoff }.collect { it.sample }
    if (filtered.size() >= Math.max(12, (int) Math.round(latticeSamples.size() * 0.55d)))
        latticeWorking = filtered
}

def explicitTargetForAnnotation = { ann ->
    String n = objectNameOf(ann) ?: ''
    def matcher = (n =~ /(?i)(?<![A-Za-z0-9])(\d+)-([A-Za-z]+)(?![A-Za-z0-9])/)
    if (matcher.find()) {
        String requested = "${matcher.group(1)}-${matcher.group(2).toUpperCase()}"
        if (indexByName.containsKey(requested))
            return [index: indexByName[requested], explicit: true, method: 'explicit_name', distance: 0.0d]
    }
    return null
}
def latticeTargetForAnnotation = { ann ->
    if (latticeModel == null) return null
    double ax = ann.getROI().getCentroidX()
    double ay = ann.getROI().getCentroidY()
    int bestIndex = -1
    double best = Double.POSITIVE_INFINITY
    double second = Double.POSITIVE_INFINITY
    cores.eachWithIndex { core, i ->
        int row = i.intdiv(gridCols) + 1
        int col = i % gridCols + 1
        def predicted = predictLattice(latticeModel, row, col)
        double dx = ax - (double) predicted.x
        double dy = ay - (double) predicted.y
        double d = Math.sqrt(dx * dx + dy * dy)
        if (d < best) {
            second = best
            best = d
            bestIndex = i
        } else if (d < second) second = d
    }
    boolean closeEnough = best <= typicalSpacing * 0.65d
    boolean unambiguous = second - best >= typicalSpacing * 0.20d
    return closeEnough && unambiguous ?
        [index: bestIndex, explicit: false, method: 'fitted_lattice', distance: best] : null
}
def nearestLiveTargetForAnnotation = { ann ->
    double ax = ann.getROI().getCentroidX()
    double ay = ann.getROI().getCentroidY()
    int bestIndex = -1
    double best = Double.POSITIVE_INFINITY
    cores.eachWithIndex { core, i ->
        double dx = ax - core.getROI().getCentroidX()
        double dy = ay - core.getROI().getCentroidY()
        double d = Math.sqrt(dx * dx + dy * dy)
        if (d < best) { best = d; bestIndex = i }
    }
    return best <= typicalSpacing * 0.65d ?
        [index: bestIndex, explicit: false, method: 'nearest_live_core', distance: best] : null
}
def targetForAnnotation = { ann ->
    return explicitTargetForAnnotation(ann) ?:
        latticeTargetForAnnotation(ann) ?:
        nearestLiveTargetForAnnotation(ann)
}

def correctionSignatureFor = { ann ->
    def roi = ann.getROI()
    String action = isTagged(ann, MARK_MISSING_CLASS) ? 'mark_missing' : 'replace_center'
    return [ann.getID(), action, fmt3(roi.getCentroidX()), fmt3(roi.getCentroidY())].join('|')
}
def appliedCorrectionSignatures = cores.collect {
    try { return it.getMetadataString('Correction annotation signature') }
    catch (Throwable ignored) { return null }
}.findAll { it != null && !it.trim().isEmpty() } as Set
def corrections = hierarchy.getAnnotationObjects().findAll {
    it.getROI() != null && (isTagged(it, CORRECTION_CLASS) || isTagged(it, MARK_MISSING_CLASS)) &&
        !appliedCorrectionSignatures.contains(correctionSignatureFor(it))
}
def positionNames = cores.collect { it.getName() ?: '' }
def correctionAudit = []
def correctionErrors = []
corrections.each { ann ->
    def target = targetForAnnotation(ann)
    int targetIndex = target == null ? -1 : (int) target.index
    if (targetIndex < 0 || targetIndex >= cores.size()) {
        correctionErrors << "Could not map correction annotation '${objectNameOf(ann) ?: ann.getID()}' to a grid cell. Move the circle closer to the tissue centre or add a core name such as 'TMA correction 4-D'."
        return
    }
    boolean markMissing = isTagged(ann, MARK_MISSING_CLASS)
    double annotationX = ann.getROI().getCentroidX()
    double annotationY = ann.getROI().getCentroidY()
    def currentTarget = cores[targetIndex]
    double currentDx = annotationX - currentTarget.getROI().getCentroidX()
    double currentDy = annotationY - currentTarget.getROI().getCentroidY()
    double currentDistance = Math.sqrt(currentDx * currentDx + currentDy * currentDy)

    // If an unnamed circle maps cleanly to a lattice cell but the live object
    // at that cell is far away, insert the missed core and shift later objects
    // into the first missing slot in the same row.  This fixes the common case
    // where one miss causes every later label in a row to be off by one.
    def shifted = []
    boolean insertionCandidate = !markMissing && !(boolean) target.explicit &&
        target.method == 'fitted_lattice' && !currentTarget.isMissing() &&
        currentDistance > typicalSpacing * 0.70d
    if (insertionCandidate) {
        int rowStart = targetIndex.intdiv(gridCols) * gridCols
        int rowEnd = Math.min(rowStart + gridCols, cores.size()) - 1
        Integer missingAfter = null
        for (int i = targetIndex + 1; i <= rowEnd; i++) {
            if (cores[i].isMissing()) { missingAfter = i; break }
        }
        if (missingAfter != null) {
            for (int i = missingAfter; i > targetIndex; i--) {
                def moved = cores[i - 1]
                String fromName = positionNames[i - 1]
                String toName = positionNames[i]
                cores[i] = moved
                moved.setName(toName)
                shifted << [from: fromName, to: toName]
            }
        }
    }

    def oldCore = cores[targetIndex]
    String coreName = positionNames[targetIndex] ?: String.format(Locale.US, 'C%03d', targetIndex + 1)
    double cx = markMissing ? oldCore.getROI().getCentroidX() : ann.getROI().getCentroidX()
    double cy = markMissing ? oldCore.getROI().getCentroidY() : ann.getROI().getCentroidY()
    double diameter = markMissing ? Math.max(8.0d, nominalDiameter * 0.05d) : nominalDiameter
    def replacement = PathObjects.createTMACoreObject(cx, cy, diameter, markMissing)
    replacement.setName(coreName)
    replacement.setColor(markMissing ? COLOR_MISSING : COLOR_CORRECTED)
    replacement.setLocked(false)
    copyCoreMetadata(oldCore, replacement)
    replacement.putMetadataValue('Detection source', markMissing ? 'human_confirmed_missing' : 'human_correction')
    replacement.putMetadataValue('Detection confidence', markMissing ? '1.000' : '1.000')
    replacement.putMetadataValue('Correction annotation UUID', ann.getID().toString())
    replacement.putMetadataValue('Correction annotation signature', correctionSignatureFor(ann))
    replacement.putMetadataValue('Correction mapping method', target.method.toString())
    cores[targetIndex] = replacement
    if (!(boolean) target.explicit) {
        try { ann.setName("${markMissing ? MARK_MISSING_CLASS : CORRECTION_CLASS} ${coreName}") }
        catch (Throwable ignored) {}
    }
    correctionAudit << [index: targetIndex + 1, core: coreName,
        action: markMissing ? 'mark_missing' : 'replace_center', centerX: cx, centerY: cy,
        annotationId: ann.getID().toString(), mappingMethod: target.method,
        mappingDistance: target.distance, shifted: shifted]
}
if (!correctionAudit.isEmpty()) {
    grid = DefaultTMAGrid.create(cores, gridCols)
    hierarchy.setTMAGrid(grid)
    hierarchy.getSelectionModel().clearSelection()
    try { fireHierarchyUpdate() } catch (Throwable ignored) {}
    appendEvent([event: 'CORRECTIONS_APPLIED', count: correctionAudit.size(), corrections: correctionAudit])
    println "Applied ${correctionAudit.size()} grid corrections"
}

// Validate structural invariants and build an explicit review queue.
cores = grid.getTMACoreList()
def hardErrors = new ArrayList(correctionErrors)
def warnings = []
def queue = []
def detectionVersions = cores.collect {
    try { return it.getMetadataString('Detection algorithm version') ?: 'unknown' }
    catch (Throwable ignored) { return 'unknown' }
}.unique()
String detectionAlgorithmVersion = detectionVersions.size() == 1 ? detectionVersions[0] : 'mixed'
if (detectionAlgorithmVersion == 'mixed')
    hardErrors << "Grid mixes detection algorithm versions: ${detectionVersions}"
if (grid.getGridWidth() <= 0 || grid.getGridHeight() <= 0)
    hardErrors << 'Grid width/height is invalid'
if (cores.size() != grid.getGridWidth() * grid.getGridHeight())
    hardErrors << "Grid contains ${cores.size()} cores but dimensions require ${grid.getGridWidth() * grid.getGridHeight()}"
def names = cores.collect { it.getName() ?: '' }
def duplicateNames = names.findAll { it.trim().isEmpty() } +
    names.groupBy { it.toUpperCase() }.findAll { k, v -> v.size() > 1 }.keySet()
if (!duplicateNames.isEmpty()) hardErrors << "Blank/duplicate core names: ${duplicateNames.unique()}"

for (int i = 0; i < cores.size(); i++) {
    def core = cores[i]
    def roi = core.getROI()
    if (roi == null) {
        hardErrors << "Core ${i + 1} has no ROI"
        continue
    }
    double cx = roi.getCentroidX(), cy = roi.getCentroidY()
    if (cx < 0 || cy < 0 || cx >= server.getWidth() || cy >= server.getHeight())
        hardErrors << "${core.getName()} is outside image bounds"
    for (int j = 0; j < i; j++) {
        def other = cores[j].getROI()
        double dx = cx - other.getCentroidX(), dy = cy - other.getCentroidY()
        if (Math.sqrt(dx * dx + dy * dy) < nominalDiameter * 0.22d)
            hardErrors << "${core.getName()} and ${cores[j].getName()} have nearly duplicate centers"
    }
    String source = ''
    double confidence = core.isMissing() ? 0.0d : 1.0d
    try { source = core.getMetadataString('Detection source') ?: '' } catch (Throwable ignored) {}
    try {
        String s = core.getMetadataString('Detection confidence')
        if (s != null && !s.trim().isEmpty()) confidence = Double.parseDouble(s)
    } catch (Throwable ignored) {}
    def reasons = []
    if (core.isMissing()) reasons << 'missing_position'
    if (!core.isMissing() && confidence < LOW_CONFIDENCE_THRESHOLD) reasons << 'low_detection_confidence'
    if (source == 'rescue_footprint') reasons << 'rescued_detection'
    if (!reasons.isEmpty()) queue << [index: i + 1, core: core.getName(),
        row: gridCols > 0 ? i.intdiv(gridCols) + 1 : 0,
        col: gridCols > 0 ? i % gridCols + 1 : 0,
        centerX: cx, centerY: cy, missing: core.isMissing(), confidence: confidence,
        source: source, reasons: reasons.join(';')]
}
int missingCount = cores.count { it.isMissing() }
int presentCount = cores.size() - missingCount
if (missingCount > 0)
    warnings << "${missingCount}/${cores.size()} positions are marked missing and require explicit confirmation"
if (!queue.isEmpty()) warnings << "${queue.size()} positions are in the targeted review queue"

def queueText = new StringBuilder()
queueText.append('index,core,row,col,center_x_px,center_y_px,missing,detection_confidence,detection_source,review_reason\n')
queue.each { q ->
    queueText.append([q.index, csv(q.core), q.row, q.col, fmt3(q.centerX), fmt3(q.centerY),
        q.missing, fmt3(q.confidence), csv(q.source), csv(q.reasons)].join(',')).append('\n')
}
writeAtomic(queueFile, queueText.toString())

String currentHash = sha256(canonicalGrid(grid))
String currentProfileHash = System.getProperty('tma.config.profileHash', '')
boolean approveForTest = 'true'.equalsIgnoreCase(System.getProperty('tma.approveForTest', 'false'))
boolean alreadyApproved = false
if (approvalFile.isFile() && corrections.isEmpty()) {
    try {
        def oldApproval = json.fromJson(approvalFile.getText('UTF-8'), Map.class)
        alreadyApproved = oldApproval.status == 'APPROVED' && oldApproval.gridHash == currentHash &&
            oldApproval.imageName == rawImageName &&
            oldApproval.detectionAlgorithmVersion == detectionAlgorithmVersion &&
            (oldApproval.profileHash == null || oldApproval.profileHash == currentProfileHash) &&
            (oldApproval.approvalMode == 'human' ||
                (oldApproval.approvalMode == 'automated_integration_test' && approveForTest))
    } catch (Throwable ignored) {}
}
if (alreadyApproved) {
    System.setProperty('tma.review.status', 'APPROVED')
    println "Grid already approved; hash ${currentHash}"
    return
}

println '=== TMA grid validation ==='
println "Grid: ${grid.getGridHeight()} rows x ${grid.getGridWidth()} cols; ${presentCount} present, ${missingCount} missing"
println "Review queue: ${queue.size()} positions"
println "Hard errors: ${hardErrors.isEmpty() ? 'none' : hardErrors}"
println "Warnings: ${warnings.isEmpty() ? 'none' : warnings}"
println "Review queue CSV: ${queueFile.getAbsolutePath()}"

if (!hardErrors.isEmpty()) {
    System.setProperty('tma.review.status', 'REJECTED')
    appendEvent([event: 'APPROVAL_BLOCKED', gridHash: currentHash, errors: hardErrors, warnings: warnings])
    Dialogs.showErrorMessage('TMA grid approval blocked',
        "The grid has structural errors and cannot be approved:\n\n- ${hardErrors.join('\n- ')}\n\n" +
        "Review queue:\n${queueFile.getAbsolutePath()}")
    return
}

// Never ask for approval in the same run that changed the grid.  The operator
// must first see the updated circles, labels and connections in the viewer.
if (!correctionAudit.isEmpty()) {
    System.setProperty('tma.review.status', 'CORRECTION_REVIEW_REQUIRED')
    appendEvent([event: 'POST_CORRECTION_REVIEW_REQUIRED', gridHash: currentHash,
        corrected: correctionAudit*.core, present: presentCount, missing: missingCount,
        reviewQueue: queue.size()])
    Dialogs.showInfoNotification('CoreAlign corrections applied',
        "Applied ${correctionAudit.size()} correction(s). Inspect the updated circles, labels and connecting lines, then press Run again only when the grid looks correct.")
    return
}

boolean approved = false
boolean missingConfirmed = missingCount == 0
String note = ''
if (approveForTest) {
    approved = true
    missingConfirmed = true
    note = 'Automated integration test approval'
} else {
    def params = new ParameterList()
        .addTitleParameter('Validation summary')
        .addEmptyParameter("${grid.getGridHeight()} x ${grid.getGridWidth()} grid; ${presentCount} present; ${missingCount} missing; ${queue.size()} queued for review")
        .addBooleanParameter('missingConfirmed',
            'I inspected and confirm all positions marked missing', missingCount == 0,
            'Required when any grid position is marked missing.')
        .addBooleanParameter('approve',
            'APPROVE this exact grid for epidermis orientation', false,
            'Approval is bound to a SHA-256 hash; moving any core invalidates it.')
        .addStringParameter('note', 'Optional audit note', '')
    if (!qupath.lib.gui.dialogs.Dialogs.showParameterDialog('TMA grid — human approval gate', params)) {
        System.setProperty('tma.review.status', 'CANCELLED')
        appendEvent([event: 'APPROVAL_CANCELLED', gridHash: currentHash])
        return
    }
    approved = params.getBooleanParameterValue('approve')
    missingConfirmed = params.getBooleanParameterValue('missingConfirmed')
    note = params.getStringParameterValue('note') ?: ''
}

if (!approved || (missingCount > 0 && !missingConfirmed)) {
    System.setProperty('tma.review.status', 'REJECTED')
    appendEvent([event: 'APPROVAL_REJECTED', gridHash: currentHash,
        missingConfirmed: missingConfirmed, note: note])
    Dialogs.showWarningNotification('TMA grid not approved',
        missingCount > 0 && !missingConfirmed ?
            'Approval was not saved because missing positions were not confirmed.' :
            'Approval was not selected. You can correct the grid and run this step again.')
    return
}

String approvedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
def coreRows = []
cores.eachWithIndex { core, i ->
    def roi = core.getROI()
    String source = ''
    String confidence = ''
    try { source = core.getMetadataString('Detection source') ?: '' } catch (Throwable ignored) {}
    try { confidence = core.getMetadataString('Detection confidence') ?: '' } catch (Throwable ignored) {}
    String correctionSignature = ''
    try { correctionSignature = core.getMetadataString('Correction annotation signature') ?: '' } catch (Throwable ignored) {}
    coreRows << [index: i + 1, name: core.getName() ?: '',
        row: gridCols > 0 ? i.intdiv(gridCols) + 1 : 0,
        col: gridCols > 0 ? i % gridCols + 1 : 0,
        centerX: roi.getCentroidX(), centerY: roi.getCentroidY(),
        diameter: Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()),
        missing: core.isMissing(), detectionSource: source, detectionConfidence: confidence,
        correctionSignature: correctionSignature]
}
def approval = [
    schemaVersion: 1,
    workflowVersion: WORKFLOW_VERSION,
    detectionAlgorithmVersion: detectionAlgorithmVersion,
    profileHash: currentProfileHash,
    status: 'APPROVED',
    approvalMode: approveForTest ? 'automated_integration_test' : 'human',
    approvedAt: approvedAt,
    imageName: rawImageName,
    imageWidth: server.getWidth(),
    imageHeight: server.getHeight(),
    gridWidth: grid.getGridWidth(),
    gridHeight: grid.getGridHeight(),
    coreCount: cores.size(),
    presentCount: presentCount,
    missingCount: missingCount,
    reviewQueueCount: queue.size(),
    gridHash: currentHash,
    note: note,
    warnings: warnings,
    corrections: correctionAudit,
    cores: coreRows
]
writeAtomic(approvalFile, json.toJson(approval) + '\n')

def coordText = new StringBuilder()
coordText.append('index,core,row,col,center_x_px,center_y_px,diameter_px,missing,detection_source,detection_confidence,approved_grid_hash\n')
coreRows.each { r ->
    coordText.append([r.index, csv(r.name), r.row, r.col, fmt3(r.centerX), fmt3(r.centerY),
        fmt3(r.diameter), r.missing, csv(r.detectionSource), csv(r.detectionConfidence),
        csv(currentHash)].join(',')).append('\n')
}
writeAtomic(coordinatesFile, coordText.toString())
appendEvent([event: 'GRID_APPROVED', gridHash: currentHash, present: presentCount,
    missing: missingCount, reviewQueueCount: queue.size(), note: note])
System.setProperty('tma.review.status', 'APPROVED')

println "APPROVED grid hash: ${currentHash}"
println "Checkpoint: ${approvalFile.getAbsolutePath()}"
if (!approveForTest)
    Dialogs.showInfoNotification('TMA grid approved',
        "Approval saved. Grid hash: ${currentHash.substring(0, 12)}. Orientation may now run/resume.")
