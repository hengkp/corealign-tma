/** Restore the last human-approved TMA grid after a QuPath restart/crash. */

import com.google.gson.GsonBuilder
import qupath.lib.common.ColorTools
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.DefaultTMAGrid

import java.security.MessageDigest

def imageData = getCurrentImageData()
def json = new GsonBuilder().setPrettyPrinting().create()
if (imageData == null) {
    Dialogs.showErrorMessage('Restore approved TMA grid', 'No image is open.')
    return
}
def server = imageData.getServer()
def hierarchy = imageData.getHierarchy()

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
File approvalFile = new File(new File(new File(base, 'tma_pipeline_state'), imageStem), 'approved_grid.json')
if (!approvalFile.isFile()) {
    Dialogs.showErrorMessage('Restore approved TMA grid',
        "No approved checkpoint exists for this image:\n${approvalFile.getAbsolutePath()}")
    return
}

def approval
try { approval = json.fromJson(approvalFile.getText('UTF-8'), Map.class) }
catch (Throwable e) {
    Dialogs.showErrorMessage('Restore approved TMA grid', "Could not read approval checkpoint:\n${e.getMessage()}")
    return
}
if (approval.status != 'APPROVED' || approval.imageName != imageName ||
        approval.imageWidth as long != server.getWidth() || approval.imageHeight as long != server.getHeight()) {
    Dialogs.showErrorMessage('Restore approved TMA grid',
        'The approval checkpoint does not match the currently open image.')
    return
}

int colorDetected = ColorTools.packRGB(0, 255, 255)
int colorMissing = ColorTools.packRGB(255, 48, 48)
def coreList = []
approval.cores.each { r ->
    def core = PathObjects.createTMACoreObject(r.centerX as double, r.centerY as double,
        r.diameter as double, r.missing as boolean)
    core.setName(r.name as String)
    core.setColor((r.missing as boolean) ? colorMissing : colorDetected)
    core.setLocked(false)
    if (r.detectionSource != null)
        core.putMetadataValue('Detection source', r.detectionSource.toString())
    if (r.detectionConfidence != null)
        core.putMetadataValue('Detection confidence', r.detectionConfidence.toString())
    if (approval.detectionAlgorithmVersion != null)
        core.putMetadataValue('Detection algorithm version', approval.detectionAlgorithmVersion.toString())
    if (r.correctionSignature != null && !r.correctionSignature.toString().isEmpty())
        core.putMetadataValue('Correction annotation signature', r.correctionSignature.toString())
    core.putMetadataValue('Restored from approved hash', approval.gridHash.toString())
    coreList << core
}
if (coreList.size() != (approval.gridWidth as int) * (approval.gridHeight as int)) {
    Dialogs.showErrorMessage('Restore approved TMA grid', 'Checkpoint core count is inconsistent with grid dimensions.')
    return
}
def restored = DefaultTMAGrid.create(coreList, approval.gridWidth as int)
hierarchy.setTMAGrid(restored)
hierarchy.getSelectionModel().clearSelection()
try { fireHierarchyUpdate() } catch (Throwable ignored) {}
System.setProperty('tma.review.status', 'APPROVED')
println "Restored approved grid ${approval.gridHash} from ${approvalFile.getAbsolutePath()}"
Dialogs.showInfoNotification('Restore approved TMA grid',
    "Restored ${coreList.size()} cores from approved checkpoint ${approval.gridHash.toString().substring(0, 12)}")
