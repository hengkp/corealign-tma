/**
 * Compare two orientation run directories core by core.
 *
 * Step 2 writes one rotated multichannel OME-TIFF per core with a Bio-Formats writer built
 * inside the worker. That is the one-instance-per-thread arrangement Bio-Formats asks for,
 * but the only proof that a parallel run writes the same files as a serial one is to write
 * the same approved grid both ways and compare every plane of every file. This script is
 * that comparison. It runs inside QuPath, because QuPath bundles Bio-Formats:
 *
 *   QuPath -D compare.serial=/path/to/work/runs/<serial run> \
 *          -D compare.parallel=/path/to/work/runs/<parallel run> \
 *          script tests/compare-orientation-runs.groovy
 *
 * It prints one line per difference and ends with COMPARE_OK or COMPARE_FAILED. Three things
 * are compared, and all three have to agree:
 *   1. rotated_multichannel_ome/<core>.ome.tif   size, channel count, pixel type, and the
 *                                                 bytes of every plane
 *   2. checkpoints/<core>.json                    status, angle, rotateRad, residual
 *   3. rotated_fullres and unrotated_fullres PNGs  decoded pixels
 * The OME-TIFF files themselves are not compared as bytes: the writer stamps a UUID into the
 * OME-XML header, so two correct files never match that way.
 */
import com.google.gson.Gson
import loci.formats.ImageReader
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.security.MessageDigest

File serial = new File(System.getProperty('compare.serial', ''))
File parallel = new File(System.getProperty('compare.parallel', ''))
if (!serial.isDirectory() || !parallel.isDirectory()) {
    println "COMPARE_FAILED: pass -D compare.serial=<dir> -D compare.parallel=<dir>"
    return
}
List<String> problems = []
int omeCompared = 0, planesCompared = 0, checkpointsCompared = 0, pngsCompared = 0

def sha = { byte[] bytes ->
    MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
}

// 1. OME-TIFF planes
File serialOme = new File(serial, 'rotated_multichannel_ome')
File parallelOme = new File(parallel, 'rotated_multichannel_ome')
def serialFiles = (serialOme.listFiles() ?: new File[0]).findAll { it.getName().endsWith('.ome.tif') }.sort { it.getName() }
def parallelNames = (parallelOme.listFiles() ?: new File[0]).collect { it.getName() } as Set
if (serialFiles.isEmpty()) problems << "no OME-TIFF files under ${serialOme}"
serialFiles.each { File a ->
    File b = new File(parallelOme, a.getName())
    if (!parallelNames.contains(a.getName())) { problems << "${a.getName()}: missing from the parallel run"; return }
    def ra = new ImageReader(), rb = new ImageReader()
    try {
        ra.setId(a.getAbsolutePath()); rb.setId(b.getAbsolutePath())
        if (ra.getSizeX() != rb.getSizeX() || ra.getSizeY() != rb.getSizeY() ||
                ra.getSizeC() != rb.getSizeC() || ra.getPixelType() != rb.getPixelType() ||
                ra.getImageCount() != rb.getImageCount()) {
            problems << "${a.getName()}: shape differs (${ra.getSizeX()}x${ra.getSizeY()}x${ra.getSizeC()} type ${ra.getPixelType()} vs ${rb.getSizeX()}x${rb.getSizeY()}x${rb.getSizeC()} type ${rb.getPixelType()})"
            return
        }
        for (int i = 0; i < ra.getImageCount(); i++) {
            byte[] pa = ra.openBytes(i), pb = rb.openBytes(i)
            planesCompared++
            if (!Arrays.equals(pa, pb)) problems << "${a.getName()}: plane ${i} differs (${sha(pa).take(12)} vs ${sha(pb).take(12)})"
        }
        omeCompared++
    } catch (Throwable error) {
        problems << "${a.getName()}: could not read (${error.getMessage()})"
    } finally {
        try { ra.close() } catch (Throwable ignored) {}
        try { rb.close() } catch (Throwable ignored) {}
    }
}
parallelNames.findAll { it.endsWith('.ome.tif') && !serialFiles.any { f -> f.getName() == it } }.each {
    problems << "${it}: only in the parallel run"
}

// 2. checkpoints
def gson = new Gson()
File serialCp = new File(serial, 'checkpoints'), parallelCp = new File(parallel, 'checkpoints')
(serialCp.listFiles() ?: new File[0]).findAll { it.getName().endsWith('.json') }.sort { it.getName() }.each { File a ->
    File b = new File(parallelCp, a.getName())
    if (!b.isFile()) { problems << "${a.getName()}: checkpoint missing from the parallel run"; return }
    Map ca = gson.fromJson(a.getText('UTF-8'), Map.class), cb = gson.fromJson(b.getText('UTF-8'), Map.class)
    def near = { x, y -> Math.abs(((x ?: 0d) as double) - ((y ?: 0d) as double)) <= 1e-9 }
    if (ca.result?.status != cb.result?.status) problems << "${a.getName()}: status ${ca.result?.status} vs ${cb.result?.status}"
    if (!near(ca.result?.angle, cb.result?.angle)) problems << "${a.getName()}: angle ${ca.result?.angle} vs ${cb.result?.angle}"
    if (!near(ca.rotateRad, cb.rotateRad)) problems << "${a.getName()}: rotateRad ${ca.rotateRad} vs ${cb.rotateRad}"
    if (!near(ca.postRotationResidualDeg, cb.postRotationResidualDeg)) problems << "${a.getName()}: residual ${ca.postRotationResidualDeg} vs ${cb.postRotationResidualDeg}"
    if (ca.coreSignature != cb.coreSignature) problems << "${a.getName()}: coreSignature differs"
    checkpointsCompared++
}

// 3. PNG exports, decoded so an encoder detail cannot masquerade as a pixel difference
['rotated_fullres', 'unrotated_fullres'].each { String folder ->
    File da = new File(serial, folder), db = new File(parallel, folder)
    (da.listFiles() ?: new File[0]).findAll { it.getName().endsWith('.png') }.sort { it.getName() }.each { File a ->
        File b = new File(db, a.getName())
        if (!b.isFile()) { problems << "${folder}/${a.getName()}: missing from the parallel run"; return }
        BufferedImage ia = ImageIO.read(a), ib = ImageIO.read(b)
        if (ia == null || ib == null) { problems << "${folder}/${a.getName()}: could not decode"; return }
        if (ia.getWidth() != ib.getWidth() || ia.getHeight() != ib.getHeight()) {
            problems << "${folder}/${a.getName()}: size ${ia.getWidth()}x${ia.getHeight()} vs ${ib.getWidth()}x${ib.getHeight()}"; return
        }
        int[] ra = ia.getRGB(0, 0, ia.getWidth(), ia.getHeight(), null, 0, ia.getWidth())
        int[] rb = ib.getRGB(0, 0, ib.getWidth(), ib.getHeight(), null, 0, ib.getWidth())
        if (!Arrays.equals(ra, rb)) problems << "${folder}/${a.getName()}: pixels differ"
        pngsCompared++
    }
}

println "Compared ${omeCompared} OME-TIFF files (${planesCompared} planes), ${checkpointsCompared} checkpoints, ${pngsCompared} PNGs"
problems.each { println "DIFF ${it}" }
println(problems.isEmpty() && omeCompared > 0 ? 'COMPARE_OK' : "COMPARE_FAILED: ${problems.size()} difference(s)")
