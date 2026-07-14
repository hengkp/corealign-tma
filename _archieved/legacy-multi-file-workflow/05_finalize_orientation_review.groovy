/**
 * STEP 5 - Final human approval of the checkpointed orientation result.
 *
 * Run only after inspecting START-HERE.html/contact sheet and applying any
 * "Epidermis override" annotations.  Approval is bound to every per-core
 * signature and orientation result; changing one core invalidates it.
 */

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import qupath.lib.plugins.parameters.ParameterList

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat

def json = new GsonBuilder().setPrettyPrinting().create()
def compactJson = new Gson()
def imageData = getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('Finalize TMA orientation', 'No image is open.')
    return
}
def server = imageData.getServer()
File base = null
try {
    def project = getProject()
    if (project != null && project.getPath() != null) base = project.getPath().getParent().toFile()
} catch (Throwable ignored) {}
if (base == null) {
    try {
        def uri = server.getURIs().find { it != null && it.getScheme() == 'file' }
        if (uri != null) base = new File(uri).getParentFile()
    } catch (Throwable ignored) {}
}
if (base == null) base = new File(System.getProperty('user.home'), 'QuPath-TMA-export')

String imageName = server.getMetadata().getName() ?: 'image'
String imageStem = imageName.replaceAll(/(?i)\.ome\.tif+$/, '')
    .replaceAll(/[^A-Za-z0-9._-]+/, '_')
if (imageStem.isEmpty()) imageStem = 'image'
File stateDir = new File(System.getProperty('corealign.work.stateDir',
    new File(new File(base, 'tma_pipeline_state'), imageStem).getAbsolutePath()))
File gridApprovalFile = new File(stateDir, 'approved_grid.json')
File finalApprovalFile = new File(stateDir, 'final_orientation_approval.json')
File eventsFile = new File(stateDir, 'pipeline_events.jsonl')
if (!gridApprovalFile.isFile()) {
    System.setProperty('tma.final.status', 'BLOCKED')
    Dialogs.showErrorMessage('Finalize TMA orientation', 'The grid approval checkpoint is missing.')
    return
}
def gridApproval = json.fromJson(gridApprovalFile.getText('UTF-8'), Map.class)
String gridHash = gridApproval.gridHash?.toString() ?: ''
File exportBase = new File(System.getProperty('corealign.work.runBaseDir',
    new File(base, 'tma_auto_orient_export').getAbsolutePath()))
File latestPointer = new File(exportBase, 'LATEST_FINAL_RUN.txt')
File runDir = latestPointer.isFile() ? new File(latestPointer.getText('UTF-8').trim()) :
    new File(exportBase, "${imageStem}_grid_${gridHash.substring(0, Math.min(12, gridHash.length()))}")
File manifestFile = new File(runDir, 'run_manifest.json')
File checkpointDir = new File(runDir, 'checkpoints')
if (!manifestFile.isFile() || !checkpointDir.isDirectory()) {
    System.setProperty('tma.final.status', 'BLOCKED')
    Dialogs.showErrorMessage('Finalize TMA orientation',
        "Orientation output is incomplete. Run/resume Step 2 first.\n${runDir.getAbsolutePath()}")
    return
}
def manifest = json.fromJson(manifestFile.getText('UTF-8'), Map.class)
def checkpointFiles = checkpointDir.listFiles()?.findAll { it.isFile() && it.getName().endsWith('.json') }?.sort { it.getName() } ?: []
def checkpoints = []
def errors = []
checkpointFiles.each { f ->
    try {
        def cp = json.fromJson(f.getText('UTF-8'), Map.class)
        checkpoints << cp
        if (cp.complete != true) errors << "Incomplete checkpoint: ${f.getName()}"
        String status = cp.result?.status?.toString() ?: ''
        if (['processing_error', 'export_error'].contains(status))
            errors << "${f.getName()}: ${status}"
    } catch (Throwable e) {
        errors << "Unreadable checkpoint ${f.getName()}: ${e.getMessage()}"
    }
}
int expectedCount = gridApproval.coreCount as int
if (checkpointFiles.size() != expectedCount)
    errors << "Found ${checkpointFiles.size()} checkpoints; expected ${expectedCount}"
if (manifest.status != 'COMPLETE' || manifest.gridHash != gridHash)
    errors << 'Run manifest is incomplete or belongs to another grid'
if (!errors.isEmpty()) {
    System.setProperty('tma.final.status', 'BLOCKED')
    Dialogs.showErrorMessage('Final orientation approval blocked',
        "Fix/resume these items first:\n\n- ${errors.join('\n- ')}")
    return
}

def fmt6 = { value -> String.format(Locale.US, '%.6f', value as double) }
def canonical = checkpoints.withIndex().collect { cp, i ->
    [i, cp.coreSignature ?: '', cp.result.status ?: '', fmt6(cp.result.angle),
        fmt6(cp.result.confidence), cp.result.method ?: ''].join('|')
}.join('\n')
String resultHash = MessageDigest.getInstance('SHA-256')
    .digest((gridHash + '\n' + canonical).getBytes('UTF-8'))
    .collect { String.format('%02x', it & 0xff) }.join()
String manifestProcessingHash = (manifest.processingHash ?: manifest.profileHash ?: '').toString()

boolean approveForTest = 'true'.equalsIgnoreCase(System.getProperty('tma.finalApproveForTest', 'false'))
if (finalApprovalFile.isFile()) {
    try {
        def existing = json.fromJson(finalApprovalFile.getText('UTF-8'), Map.class)
        String existingProcessingHash =
            (existing.processingHash ?: existing.profileHash ?: '').toString()
        if (existing.status == 'APPROVED' && existing.gridHash == gridHash &&
                existing.orientationResultHash == resultHash &&
                existingProcessingHash == manifestProcessingHash &&
                (existing.approvalMode == 'human' ||
                    (existing.approvalMode == 'automated_integration_test' && approveForTest))) {
            System.setProperty('tma.final.status', 'APPROVED')
            println "Final orientation already approved; result hash ${resultHash}"
            return
        }
    } catch (Throwable ignored) {}
}

boolean allReviewed = false
boolean epidermisConfirmed = false
boolean regionsConfirmed = false
boolean fullResolutionConfirmed = false
boolean approve = false
String note = ''
if (approveForTest) {
    allReviewed = true
    epidermisConfirmed = true
    regionsConfirmed = true
    fullResolutionConfirmed = true
    approve = true
    note = 'Automated integration test approval'
} else {
    String finalMessage = "Positions: ${manifest.coreCount as int}\n" +
        "Missing: ${manifest.missing as int}\n" +
        "Needs review: ${manifest.review as int}\n" +
        "Region flags: ${(manifest.regionReview ?: 0) as int}\n\n" +
        'Before approving, open REPORT.html and check every non-missing core.\n' +
        'Confirm that the tissue direction, selected region, and final crop are correct.\n\n' +
        'Click OK to approve these results and create the selected output package.\n' +
        'Click Cancel to return to the report or add corrections.'
    approve = Dialogs.showConfirmDialog('CoreAlign | Approve rotated cores', finalMessage)
    if (!approve) {
        System.setProperty('tma.final.status', 'CANCELLED')
        return
    }
    allReviewed = true
    epidermisConfirmed = true
    regionsConfirmed = true
    fullResolutionConfirmed = true
    note = 'Approved in the simplified CoreAlign dialog after REPORT.html review'
}
if (!allReviewed || !epidermisConfirmed || !regionsConfirmed ||
        !fullResolutionConfirmed || !approve) {
    System.setProperty('tma.final.status', 'REJECTED')
    Dialogs.showWarningNotification('Orientation not approved',
        'All final-review confirmations are required. Add orientation/crop overrides where needed and resume the pipeline.')
    return
}

def writeAtomic = { File target, String content ->
    File tmp = new File(target.getParentFile(), target.getName() + '.tmp-' + UUID.randomUUID())
    tmp.setText(content, 'UTF-8')
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
    } catch (Throwable ignored) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
String approvedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
def finalApproval = [schemaVersion: 1, status: 'APPROVED',
    approvalMode: approveForTest ? 'automated_integration_test' : 'human', approvedAt: approvedAt,
    imageName: imageName, gridHash: gridHash, orientationResultHash: resultHash,
    profileHash: manifest.profileHash ?: '',
    processingHash: manifestProcessingHash,
    outputHash: manifest.outputHash ?: '',
    algorithmVersion: manifest.algorithmVersion, coreCount: manifest.coreCount,
    missingCount: manifest.missing, automatedReviewFlags: manifest.review,
    failedOrMissing: manifest.failedOrMissing,
    regionReviewFlags: manifest.regionReview ?: 0,
    postRotationReviewFlags: manifest.postRotationReview ?: 0,
    rotationThenCrop: manifest.rotationThenCrop == true,
    exportDownsample: manifest.exportDownsample,
    note: note,
    runDirectory: runDir.getAbsolutePath()]
writeAtomic(finalApprovalFile, json.toJson(finalApproval) + '\n')
eventsFile.withWriterAppend('UTF-8') { it.println(compactJson.toJson([
    event: 'FINAL_ORIENTATION_APPROVED', timestamp: approvedAt,
    gridHash: gridHash, orientationResultHash: resultHash, note: note])) }
System.setProperty('tma.final.status', 'APPROVED')
println "FINAL APPROVED orientation result hash: ${resultHash}"
if (!approveForTest)
    Dialogs.showInfoNotification('TMA orientation approved',
        "Final approval saved for result ${resultHash.substring(0, 12)}")
