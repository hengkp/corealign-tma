/**
 * STEP 7 - Build an analysis-ready QuPath project from approved rotated cores.
 *
 * Quantitative image entries are created only from rotated multichannel
 * OME-TIFF files. Presentation PNG files are intentionally excluded.
 */

import static qupath.lib.scripting.QP.*

import com.google.gson.GsonBuilder
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.projects.ProjectIO
import qupath.lib.projects.Projects

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat

def json = new GsonBuilder().setPrettyPrinting().create()
def imageData = getCurrentImageData()
if (imageData == null) {
    System.setProperty('tma.analysisProject.status', 'BLOCKED_NO_IMAGE')
    Dialogs.showErrorMessage('CoreAlign analysis project', 'No image is open.')
    return
}

def server = imageData.getServer()
File workflowDir = null
try {
    def fileUri = server.getURIs().find { it != null && it.getScheme() == 'file' }
    if (fileUri != null) workflowDir = new File(fileUri).getParentFile()
} catch (Throwable ignored) {}
if (workflowDir == null) {
    System.setProperty('tma.analysisProject.status', 'BLOCKED_NO_WORKFLOW_FOLDER')
    println 'Analysis project skipped: the local slide folder could not be resolved.'
    return
}

String imageName = server.getMetadata().getName() ?: 'image'
String imageStem = imageName.replaceAll(/(?i)\.ome\.tif+$/, '')
    .replaceAll(/[^A-Za-z0-9._-]+/, '_')
if (imageStem.isEmpty()) imageStem = 'image'

File exportBase = new File(workflowDir, 'tma_auto_orient_export')
File latestPointer = new File(exportBase, 'LATEST_FINAL_RUN.txt')
File runDir = System.getProperty('tma.orientation.runDir', '').trim() ?
    new File(System.getProperty('tma.orientation.runDir')) :
    (latestPointer.isFile() ? new File(latestPointer.getText('UTF-8').trim()) : null)
if (runDir == null || !runDir.isDirectory()) {
    System.setProperty('tma.analysisProject.status', 'BLOCKED_NO_ORIENTATION')
    println 'Analysis project skipped: no completed orientation run was found.'
    return
}

File stateDir = new File(new File(workflowDir, 'tma_pipeline_state'), imageStem)
File finalApprovalFile = new File(stateDir, 'final_orientation_approval.json')
if (!finalApprovalFile.isFile()) {
    System.setProperty('tma.analysisProject.status', 'WAITING_FOR_FINAL_APPROVAL')
    println 'Analysis project deferred until final human orientation approval.'
    return
}
def finalApproval
try { finalApproval = json.fromJson(finalApprovalFile.getText('UTF-8'), Map.class) }
catch (Throwable approvalError) {
    System.setProperty('tma.analysisProject.status', 'BLOCKED_INVALID_APPROVAL')
    println "Analysis project skipped: ${approvalError.getMessage()}"
    return
}
boolean acceptedApproval = finalApproval.status == 'APPROVED' &&
    (finalApproval.approvalMode == 'human' ||
        'true'.equalsIgnoreCase(System.getProperty('tma.finalApproveForTest', 'false')))
if (!acceptedApproval || finalApproval.runDirectory?.toString() != runDir.getAbsolutePath()) {
    System.setProperty('tma.analysisProject.status', 'WAITING_FOR_FINAL_APPROVAL')
    println 'Analysis project deferred: the current run is not covered by final approval.'
    return
}

File resultsFile = new File(runDir, 'orientation_results.csv')
if (!resultsFile.isFile()) {
    System.setProperty('tma.analysisProject.status', 'BLOCKED_NO_RESULTS')
    println "Analysis project skipped: ${resultsFile.getAbsolutePath()} is missing."
    return
}

def parseCsvLine = { String line ->
    def values = []
    StringBuilder field = new StringBuilder()
    boolean quoted = false
    for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i)
        if (quoted) {
            if (ch == ('"' as char) && i + 1 < line.length() &&
                    line.charAt(i + 1) == ('"' as char)) {
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

def lines = resultsFile.readLines('UTF-8').findAll { !it.trim().isEmpty() }
if (lines.size() < 2) {
    System.setProperty('tma.analysisProject.status', 'BLOCKED_NO_RESULTS')
    println 'Analysis project skipped: orientation results contain no core records.'
    return
}
def headers = parseCsvLine(lines[0])
def records = lines.drop(1).collect { line ->
    def values = parseCsvLine(line)
    def row = [:]
    headers.eachWithIndex { header, i -> row[header] = i < values.size() ? values[i] : '' }
    return row
}
def analysisRecords = records.findAll { it.status != 'missing' }
def unavailable = analysisRecords.findAll { row ->
    String relativePath = row.rotated_multichannel_ome_tif?.toString() ?: ''
    relativePath.isEmpty() || !new File(runDir, relativePath).isFile()
}

def writeAtomic = { File target, String content ->
    target.getParentFile()?.mkdirs()
    File tmp = new File(target.getParentFile(), target.getName() + '.tmp')
    tmp.setText(content, 'UTF-8')
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
    } catch (Throwable ignored) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

File projectDir = new File(runDir, 'qupath_analysis_project')
File statusFile = new File(runDir, 'qupath_analysis_project_status.json')
if (!unavailable.isEmpty()) {
    def status = [schemaVersion: 1, status: 'RESEARCH_OUTPUT_REQUIRED',
        image: imageName, runDirectory: runDir.getAbsolutePath(),
        requiredMultichannelCoreCount: analysisRecords.size(),
        availableMultichannelCoreCount: analysisRecords.size() - unavailable.size(),
        unavailableCores: unavailable.collect { it.core },
        note: 'Choose Research package and run CoreAlign.groovy again. Existing grid, rotation, and crop checkpoints will be reused; only missing multichannel OME-TIFF files will be exported.']
    writeAtomic(statusFile, json.toJson(status) + '\n')
    System.setProperty('tma.analysisProject.status', 'RESEARCH_OUTPUT_REQUIRED')
    System.setProperty('tma.analysisProject.statusPath', statusFile.getAbsolutePath())
    println "Analysis project not created yet: ${unavailable.size()} multichannel OME-TIFF core file(s) are unavailable."
    println 'Choose Research package and run CoreAlign again; detection and orientation checkpoints will be reused.'
    return
}

if (!projectDir.mkdirs() && !projectDir.isDirectory()) {
    System.setProperty('tma.analysisProject.status', 'BLOCKED_CREATE_FOLDER')
    println "Analysis project skipped: could not create ${projectDir.getAbsolutePath()}"
    return
}

File projectFile = new File(projectDir, 'project.qpproj')
def project = projectFile.isFile() ?
    ProjectIO.loadProject(projectFile, BufferedImage.class) :
    Projects.createProject(projectDir, BufferedImage.class)
def existingByCore = project.getImageList().findAll {
    !(it.getMetadataValue('CoreAlign core') ?: '').isEmpty()
}.collectEntries { [(it.getMetadataValue('CoreAlign core')): it] }

int added = 0
int updated = 0
def importErrors = []
analysisRecords.sort { a, b ->
    int indexA = (a.index ?: '0') as int
    int indexB = (b.index ?: '0') as int
    return indexA <=> indexB
}.eachWithIndex { row, listIndex ->
    String coreName = row.core?.toString() ?: String.format(Locale.US, 'C%03d', listIndex + 1)
    File omeFile = new File(runDir, row.rotated_multichannel_ome_tif.toString())
    def entry = existingByCore[coreName]
    try {
        if (entry == null) {
            def support = ImageServerProvider.getPreferredUriImageSupport(
                BufferedImage.class, omeFile.toURI().toString())
            def builders = support?.getBuilders() ?: []
            if (builders.isEmpty()) throw new IOException('No QuPath image reader accepted this file')
            entry = project.addImage(builders[0])
            existingByCore[coreName] = entry
            added++
        } else {
            updated++
        }
        int sourceIndex = (row.index ?: (listIndex + 1).toString()) as int
        int sourceRow = (row.row ?: '0') as int
        int sourceCol = (row.col ?: '0') as int
        String safeCore = coreName.replaceAll(/[^A-Za-z0-9._-]+/, '_')
        entry.setImageName(String.format(Locale.US, '%03d_R%02d_C%02d_%s',
            sourceIndex, sourceRow, sourceCol, safeCore))
        entry.putMetadataValue('CoreAlign core', coreName)
        entry.putMetadataValue('CoreAlign row', sourceRow.toString())
        entry.putMetadataValue('CoreAlign column', sourceCol.toString())
        entry.putMetadataValue('CoreAlign source slide', imageName)
        entry.putMetadataValue('CoreAlign QC status', row.status?.toString() ?: '')
        entry.putMetadataValue('CoreAlign confidence', row.confidence?.toString() ?: '')
        entry.putMetadataValue('CoreAlign rotate to top deg',
            row.rotate_to_top_deg?.toString() ?: '')
        entry.putMetadataValue('CoreAlign grid hash', row.approved_grid_hash?.toString() ?: '')
        entry.putMetadataValue('CoreAlign orientation result hash',
            finalApproval.orientationResultHash?.toString() ?: '')
        entry.putMetadataValue('CoreAlign image type', 'rotated_multichannel_ome_tiff')
        entry.getTags().add('CoreAlign')
        entry.getTags().add(String.format(Locale.US, 'Row %02d', sourceRow))
        entry.getTags().add('QC ' + (row.status?.toString() ?: 'unknown'))
        entry.setDescription('Rotated first, then cropped from the original multichannel slide. ' +
            'Use for downstream QuPath analysis after human approval.')
    } catch (Throwable importError) {
        importErrors << [core: coreName, file: omeFile.getAbsolutePath(),
            error: importError.getMessage()]
        println "WARNING: Could not add ${coreName} to the analysis project: ${importError.getMessage()}"
    }
}

project.getMetadata().put('CoreAlign source slide', imageName)
project.getMetadata().put('CoreAlign run directory', runDir.getAbsolutePath())
project.getMetadata().put('CoreAlign grid hash', finalApproval.gridHash?.toString() ?: '')
project.getMetadata().put('CoreAlign orientation result hash',
    finalApproval.orientationResultHash?.toString() ?: '')
project.syncChanges()
if (!projectFile.isFile()) {
    def createdProjects = projectDir.listFiles()?.findAll {
        it.isFile() && it.getName().endsWith('.qpproj')
    } ?: []
    if (!createdProjects.isEmpty()) projectFile = createdProjects.sort { it.getName() }[0]
}

String completedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
String projectStatus = importErrors.isEmpty() ? 'READY' : 'INCOMPLETE'
def manifest = [schemaVersion: 1, status: projectStatus, createdAt: completedAt,
    image: imageName, projectFile: projectFile.getAbsolutePath(),
    runDirectory: runDir.getAbsolutePath(), entryCount: project.getImageList().size(),
    expectedCoreCount: analysisRecords.size(), addedThisRun: added,
    updatedThisRun: updated, importErrors: importErrors,
    imageType: 'rotated_multichannel_ome_tiff',
    quantitativeAnalysisReady: importErrors.isEmpty(),
    ordering: 'row_major_by_entry_name',
    metadataFields: ['CoreAlign core', 'CoreAlign row', 'CoreAlign column',
        'CoreAlign source slide', 'CoreAlign QC status', 'CoreAlign confidence',
        'CoreAlign rotate to top deg', 'CoreAlign grid hash',
        'CoreAlign orientation result hash', 'CoreAlign image type']]
writeAtomic(new File(projectDir, 'corealign_project_manifest.json'),
    json.toJson(manifest) + '\n')
writeAtomic(statusFile, json.toJson(manifest) + '\n')

System.setProperty('tma.analysisProject.status', projectStatus)
System.setProperty('tma.analysisProject.path', projectFile.getAbsolutePath())
System.setProperty('tma.analysisProject.statusPath', statusFile.getAbsolutePath())
println "QuPath analysis project: ${projectFile.getAbsolutePath()}"
println "Analysis entries: ${project.getImageList().size()}; added ${added}; updated ${updated}; errors ${importErrors.size()}"
