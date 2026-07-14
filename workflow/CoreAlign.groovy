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
import javafx.application.Platform
import qupath.lib.gui.QuPathGUI
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets

import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Loopback-only bridge that lets REPORT.html save validated orientation
 * edits beside itself while QuPath remains open. Browsers cannot write beside
 * a local HTML file silently, so QuPath owns the fixed destination path.
 */
class CoreAlignCorrectionBridge {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024
    private static final long IDLE_TIMEOUT_MS = 12L * 60L * 60L * 1000L
    private static ServerSocket activeSocket
    private static Thread activeThread

    static synchronized Map start(File targetFile, File configFile, String expectedProfile,
            String expectedImage, String expectedRun, Set<String> allowedCores) {
        stop()
        try {
            byte[] tokenBytes = new byte[24]
            new SecureRandom().nextBytes(tokenBytes)
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
            ServerSocket serverSocket = new ServerSocket(0, 16,
                InetAddress.getByName('127.0.0.1'))
            serverSocket.setSoTimeout(60000)
            activeSocket = serverSocket
            Set<String> normalizedCores = allowedCores.collect {
                it == null ? '' : it.toString().trim().toLowerCase(Locale.ROOT)
            }.findAll { !it.isEmpty() } as Set<String>
            long startedAt = System.currentTimeMillis()
            Thread thread = new Thread({
                long lastRequestAt = startedAt
                try {
                    while (!serverSocket.isClosed()) {
                        try {
                            Socket client = serverSocket.accept()
                            lastRequestAt = System.currentTimeMillis()
                            handle(client, token, targetFile, configFile, expectedProfile,
                                expectedImage, expectedRun, normalizedCores)
                        } catch (SocketTimeoutException ignored) {
                            if (System.currentTimeMillis() - lastRequestAt > IDLE_TIMEOUT_MS)
                                break
                        } catch (Throwable requestError) {
                            if (!serverSocket.isClosed())
                                println "WARNING: CoreAlign auto-save request failed: ${requestError.getMessage()}"
                        }
                    }
                } finally {
                    try { serverSocket.close() } catch (Throwable ignored) {}
                }
            }, 'CoreAlign orientation correction auto-save')
            thread.setDaemon(true)
            activeThread = thread
            thread.start()
            String root = "http://127.0.0.1:${serverSocket.getLocalPort()}/corealign"
            return [available: true,
                endpoint: "${root}/save?token=${token}",
                openEndpoint: "${root}/open?token=${token}",
                outputEndpoint: "${root}/output?token=${token}"]
        } catch (Throwable startError) {
            stop()
            println "WARNING: CoreAlign correction auto-save is unavailable: ${startError.getMessage()}"
            return [available: false, endpoint: '', error: startError.getMessage()]
        }
    }

    static synchronized void stop() {
        try { activeSocket?.close() } catch (Throwable ignored) {}
        activeSocket = null
        activeThread = null
    }

    static void runSelfTest() {
        File folder = Files.createTempDirectory('corealign-autosave-test-').toFile()
        File target = new File(folder, 'corealign-review-corrections.json')
        File config = new File(folder, 'corealign.config.json')
        try {
            config.setText(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson([
                schemaVersion: 2, activeProfile: 'automatic', customValue: 'keep',
                profiles: [automatic: [
                    orientation: [saveFullResolutionPng: true, saveNativeOmeTiff: false,
                        saveRotatedMultichannelOmeTiff: false]]]]) + '\n', 'UTF-8')
            Map bridge = start(target, config, 'automatic', 'test.ome.tif', 'test-run',
                ['1-a'] as Set<String>)
            if (!bridge.available) throw new IOException('Auto-save bridge did not start')
            if (!bridge.openEndpoint) throw new IOException('QuPath focus endpoint was not created')
            if (!bridge.outputEndpoint) throw new IOException('Output mode endpoint was not created')
            def preflight = new URL(bridge.endpoint.toString()).openConnection()
            preflight.setRequestMethod('OPTIONS')
            preflight.setConnectTimeout(3000)
            preflight.setReadTimeout(3000)
            preflight.setRequestProperty('Origin', 'null')
            preflight.setRequestProperty('Access-Control-Request-Method', 'POST')
            preflight.setRequestProperty('Access-Control-Request-Private-Network', 'true')
            if (preflight.getResponseCode() != 204 ||
                    preflight.getHeaderField('Access-Control-Allow-Origin') != '*' ||
                    preflight.getHeaderField('Access-Control-Allow-Private-Network') != 'true')
                throw new IOException('Browser preflight was not accepted')
            Map payload = [schemaVersion: 1, image: 'test.ome.tif', baseRun: 'test-run',
                corrections: [[core: '1-A', rotationAdjustmentDeg: 17.4d]]]
            byte[] body = new Gson().toJson(payload).getBytes(StandardCharsets.UTF_8)
            def connection = new URL(bridge.endpoint.toString()).openConnection()
            connection.setRequestMethod('POST')
            connection.setDoOutput(true)
            connection.setConnectTimeout(3000)
            connection.setReadTimeout(3000)
            connection.setRequestProperty('Content-Type', 'text/plain;charset=UTF-8')
            connection.getOutputStream().withCloseable { it.write(body) }
            if (connection.getResponseCode() != 200)
                throw new IOException("Auto-save returned HTTP ${connection.getResponseCode()}")
            connection.getInputStream().withCloseable { it.readAllBytes() }
            if (!target.isFile()) throw new IOException('Correction file was not written')
            Map saved = new Gson().fromJson(target.getText('UTF-8'), Map.class)
            if (saved.image != 'test.ome.tif' || saved.baseRun != 'test-run' ||
                    !(saved.corrections instanceof List) || saved.corrections.size() != 1 ||
                    saved.corrections[0].core != '1-A' ||
                    Math.abs((saved.corrections[0].rotationAdjustmentDeg as Number).doubleValue() - 17.4d) > 0.001d)
                throw new IOException('Saved correction content is incorrect')
            byte[] outputBody = new Gson().toJson([mode: 'research'])
                .getBytes(StandardCharsets.UTF_8)
            def outputConnection = new URL(bridge.outputEndpoint.toString()).openConnection()
            outputConnection.setRequestMethod('POST')
            outputConnection.setDoOutput(true)
            outputConnection.setConnectTimeout(3000)
            outputConnection.setReadTimeout(3000)
            outputConnection.setRequestProperty('Content-Type', 'text/plain;charset=UTF-8')
            outputConnection.getOutputStream().withCloseable { it.write(outputBody) }
            if (outputConnection.getResponseCode() != 200)
                throw new IOException("Output mode returned HTTP ${outputConnection.getResponseCode()}")
            outputConnection.getInputStream().withCloseable { it.readAllBytes() }
            Map savedConfig = new Gson().fromJson(config.getText('UTF-8'), Map.class)
            if (savedConfig.customValue != 'keep' ||
                    savedConfig.profiles.automatic.orientation.saveRotatedMultichannelOmeTiff != true)
                throw new IOException('Research output mode was not saved')
            byte[] presentationBody = new Gson().toJson([mode: 'presentation'])
                .getBytes(StandardCharsets.UTF_8)
            def presentationConnection = new URL(bridge.outputEndpoint.toString()).openConnection()
            presentationConnection.setRequestMethod('POST')
            presentationConnection.setDoOutput(true)
            presentationConnection.setConnectTimeout(3000)
            presentationConnection.setReadTimeout(3000)
            presentationConnection.setRequestProperty('Content-Type', 'text/plain;charset=UTF-8')
            presentationConnection.getOutputStream().withCloseable { it.write(presentationBody) }
            if (presentationConnection.getResponseCode() != 200)
                throw new IOException("Presentation mode returned HTTP ${presentationConnection.getResponseCode()}")
            presentationConnection.getInputStream().withCloseable { it.readAllBytes() }
            Map presentationConfig = new Gson().fromJson(config.getText('UTF-8'), Map.class)
            if (presentationConfig.customValue != 'keep' ||
                    presentationConfig.profiles.automatic.orientation.saveRotatedMultichannelOmeTiff != false)
                throw new IOException('Presentation output mode was not saved')
            println 'COREALIGN_AUTOSAVE_SELF_TEST_PASSED'
        } finally {
            stop()
            try { Files.deleteIfExists(target.toPath()) } catch (Throwable ignored) {}
            try { Files.deleteIfExists(config.toPath()) } catch (Throwable ignored) {}
            try { Files.deleteIfExists(folder.toPath()) } catch (Throwable ignored) {}
        }
    }

    private static void handle(Socket client, String token, File targetFile, File configFile,
            String expectedProfile, String expectedImage, String expectedRun,
            Set<String> allowedCores) {
        client.setSoTimeout(5000)
        try {
            InputStream input = new BufferedInputStream(client.getInputStream())
            String requestLine = readAsciiLine(input, 8192)
            if (requestLine == null) return
            String[] requestParts = requestLine.split(' ', 3)
            if (requestParts.length < 2) {
                respond(client, 400, 'Bad Request', [ok: false, error: 'Invalid request'])
                return
            }
            String method = requestParts[0]
            String target = requestParts[1]
            Map<String, String> headers = [:]
            int headerBytes = requestLine.length()
            while (true) {
                String line = readAsciiLine(input, 8192)
                if (line == null || line.isEmpty()) break
                headerBytes += line.length()
                if (headerBytes > 65536) {
                    respond(client, 431, 'Request Header Fields Too Large',
                        [ok: false, error: 'Request headers are too large'])
                    return
                }
                int colon = line.indexOf(':')
                if (colon > 0)
                    headers[line.substring(0, colon).trim().toLowerCase(Locale.ROOT)] =
                        line.substring(colon + 1).trim()
            }
            String saveTarget = "/corealign/save?token=${token}"
            String openTarget = "/corealign/open?token=${token}"
            String outputTarget = "/corealign/output?token=${token}"
            if (target != saveTarget && target != openTarget && target != outputTarget) {
                respond(client, 403, 'Forbidden', [ok: false, error: 'Invalid save token'])
                return
            }
            if (method == 'OPTIONS') {
                respond(client, 204, 'No Content', null)
                return
            }
            if (method != 'POST') {
                respond(client, 405, 'Method Not Allowed',
                    [ok: false, error: 'POST is required'])
                return
            }
            if (target == openTarget) {
                boolean opened = bringQuPathToFront()
                respond(client, opened ? 200 : 409, opened ? 'OK' : 'Conflict',
                    [ok: opened, action: opened ? 'QuPath focused' : 'QuPath window unavailable'])
                return
            }
            int length
            try { length = Integer.parseInt(headers['content-length'] ?: '-1') }
            catch (Throwable ignored) { length = -1 }
            if (length < 0 || length > MAX_REQUEST_BYTES) {
                respond(client, 413, 'Content Too Large',
                    [ok: false, error: 'Invalid correction size'])
                return
            }
            byte[] body = input.readNBytes(length)
            if (body.length != length) {
                respond(client, 400, 'Bad Request',
                    [ok: false, error: 'Incomplete correction data'])
                return
            }
            Map payload
            try {
                payload = new Gson().fromJson(
                    new String(body, StandardCharsets.UTF_8), Map.class) ?: [:]
            } catch (Throwable ignored) {
                respond(client, 400, 'Bad Request',
                    [ok: false, error: 'Invalid correction data'])
                return
            }
            if (target == outputTarget) {
                String mode = payload.mode?.toString()?.trim()?.toLowerCase(Locale.ROOT) ?: ''
                if (!(mode in ['presentation', 'research'])) {
                    respond(client, 400, 'Bad Request', [ok: false, error: 'Invalid output mode'])
                    return
                }
                try {
                    saveOutputMode(configFile, expectedProfile, mode)
                    respond(client, 200, 'OK', [ok: true, mode: mode,
                        profile: expectedProfile, savedAs: configFile.getName()])
                } catch (Throwable configError) {
                    respond(client, 409, 'Conflict', [ok: false,
                        error: configError.getMessage() ?: 'Could not update config'])
                }
                return
            }
            if (payload.image?.toString() != expectedImage ||
                    payload.baseRun?.toString() != expectedRun ||
                    !(payload.corrections instanceof List) ||
                    payload.corrections.size() > allowedCores.size()) {
                respond(client, 409, 'Conflict',
                    [ok: false, error: 'This report does not match the current QuPath run'])
                return
            }
            List cleanCorrections = []
            Set<String> seen = [] as Set<String>
            for (def correction : payload.corrections) {
                String core = correction?.core?.toString()?.trim() ?: ''
                String key = core.toLowerCase(Locale.ROOT)
                def rawAngle = correction?.rotationAdjustmentDeg
                double angle
                try { angle = (rawAngle as Number).doubleValue() }
                catch (Throwable ignored) { angle = Double.NaN }
                if (core.isEmpty() || core.length() > 64 ||
                        !allowedCores.contains(key) || seen.contains(key) ||
                        !Double.isFinite(angle) || angle < -180d || angle > 180d) {
                    respond(client, 400, 'Bad Request',
                        [ok: false, error: 'A correction is invalid'])
                    return
                }
                seen.add(key)
                cleanCorrections << [core: core,
                    rotationAdjustmentDeg: Math.round(angle * 10d) / 10d]
            }
            Map cleanPayload = [schemaVersion: 1, image: expectedImage,
                baseRun: expectedRun,
                createdAt: java.time.OffsetDateTime.now().toString(),
                corrections: cleanCorrections]
            targetFile.getParentFile()?.mkdirs()
            File temporary = new File(targetFile.getParentFile(),
                ".${targetFile.getName()}.tmp")
            temporary.setText(new Gson().toJson(cleanPayload) + '\n', 'UTF-8')
            try {
                Files.move(temporary.toPath(), targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (Throwable atomicMoveError) {
                Files.move(temporary.toPath(), targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
            respond(client, 200, 'OK', [ok: true,
                savedAs: targetFile.getName(), count: cleanCorrections.size()])
        } finally {
            try { client.close() } catch (Throwable ignored) {}
        }
    }

    private static String readAsciiLine(InputStream input, int maximumBytes) {
        ByteArrayOutputStream line = new ByteArrayOutputStream()
        int previous = -1
        while (line.size() <= maximumBytes) {
            int current = input.read()
            if (current < 0) return line.size() == 0 ? null :
                new String(line.toByteArray(), StandardCharsets.US_ASCII)
            if (previous == 13 && current == 10) {
                byte[] bytes = line.toByteArray()
                return new String(bytes, 0, Math.max(0, bytes.length - 1),
                    StandardCharsets.US_ASCII)
            }
            line.write(current)
            previous = current
        }
        throw new IOException('HTTP line is too long')
    }

    private static boolean bringQuPathToFront() {
        try {
            def gui = QuPathGUI.getInstance()
            if (gui == null || gui.getStage() == null) return false
            Platform.runLater {
                def stage = gui.getStage()
                stage.setIconified(false)
                stage.show()
                stage.toFront()
                stage.requestFocus()
            }
            return true
        } catch (Throwable focusError) {
            println "WARNING: Could not focus QuPath: ${focusError.getMessage()}"
            return false
        }
    }

    private static void saveOutputMode(File configFile, String expectedProfile, String mode) {
        if (configFile == null || !configFile.isFile())
            throw new IOException('corealign.config.json is not available')
        def prettyJson = new com.google.gson.GsonBuilder().setPrettyPrinting().create()
        Map root = prettyJson.fromJson(configFile.getText('UTF-8'), Map.class) ?: [:]
        if (!(root.profiles instanceof Map) ||
                !(root.profiles[expectedProfile] instanceof Map))
            throw new IOException('The current config profile is invalid')
        Map profile = root.profiles[expectedProfile] as Map
        if (!(profile.orientation instanceof Map)) profile.orientation = [:]
        Map orientation = profile.orientation as Map
        orientation.saveFullResolutionPng = true
        orientation.saveNativeOmeTiff = false
        orientation.saveRotatedMultichannelOmeTiff = mode == 'research'
        File temporary = new File(configFile.getParentFile(),
            ".${configFile.getName()}.tmp")
        temporary.setText(prettyJson.toJson(root) + '\n', 'UTF-8')
        try {
            Files.move(temporary.toPath(), configFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (Throwable atomicMoveError) {
            Files.move(temporary.toPath(), configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static void respond(Socket client, int status, String reason, Map payload) {
        byte[] body = payload == null ? new byte[0] :
            new Gson().toJson(payload).getBytes(StandardCharsets.UTF_8)
        String headers = "HTTP/1.1 ${status} ${reason}\r\n" +
            'Content-Type: application/json; charset=utf-8\r\n' +
            'Access-Control-Allow-Origin: *\r\n' +
            'Access-Control-Allow-Methods: POST, OPTIONS\r\n' +
            'Access-Control-Allow-Headers: Content-Type\r\n' +
            'Access-Control-Allow-Private-Network: true\r\n' +
            'Cache-Control: no-store\r\n' +
            'Connection: close\r\n' +
            "Content-Length: ${body.length}\r\n\r\n"
        OutputStream output = client.getOutputStream()
        output.write(headers.getBytes(StandardCharsets.US_ASCII))
        if (body.length > 0) output.write(body)
        output.flush()
    }
}

// Generated synthetic microscopy placeholder. Embedded to keep the production
// workflow self-contained while also making empty TMA positions visually clear.
String NO_CORE_PLACEHOLDER_JPEG_BASE64 = '''
/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAA
A6ABAAMAAAABAAEAAKACAAQAAAABAAACAKADAAQAAAABAAACAAAAAAD/wAARCAIAAgADASIAAhEB
AxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9
AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6
Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ip
qrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEB
AQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJB
UQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RV
VldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6
wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9sAQwABAQEBAQECAQECAwICAgME
AwMDAwQFBAQEBAQFBgUFBQUFBQYGBgYGBgYGBwcHBwcHCQkJCQkKCgoKCgoKCgoK/9sAQwEBAgIC
AgIEAgIECgcGBwoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoK
CgoK/90ABAAg/9oADAMBAAIRAxEAPwD/AD/6KKKACiiigAopaOnFACUUUtACUUUUAFFFFABRRRQA
UUUUAHtRS/SkoAXPakopaAEooooAKKKKACiiigAopcUlAC+1JRRQAUUUUAFFFFABRRRQAdaKWkoA
KKKKACiiigAooooAKKKKACl7c0lLQAe1JS0lAC0DikooAKKKKACiiigYUUUUCF96KSloASiiigAo
oooA/9D/AD/6KKKAFFJRRQAUUoo6UAFGCOtH1pKACl60lFABRRRQAUUvGKAKAEpRSUtACUYopaAE
oo6UUAFFLSUAFFFFABSjikpc0ABz3o7UUdqADjFJRRQAUUUGgAooooAKKKKAF6Ud6Sg0AFFAooAK
KXtSUAFLSUUAFFFFACmj3pKKACilxjpSUAFFFFABRRRQAUUUUAFH0oooAKKKKACiiigApcUdKSgD
/9H/AD/6KKX2oASl+lGKPrQAlFFLzQAlLSUUAFKaKSgApaPaigBKKKKAA0uKKT60AL2pKKKADiil
pKACilooASilFJQAtJRRQMX60lFFAgpcUlFAC9eaSil5oAM0GiigBKKKKAAUtHtSUAFL9KSl5NAC
UuKSigBaSl9qBQAUlFL9KACiik60ALRRQKAEpe9HXikoAKKKKACiiigYtJRRQIKKUUGgBKKKKBi0
AZ4FJR9KBH//0v8AP/paO9JQAUUUUAL70ZpKKACiiigBe1H1pKKACiiigBaSiigAooooAKKKKACi
iigAooooAXmkoooAKKKKAClopKAFzSUUUAFFFFABRRRQAZopaSgBevWkpaSgAooooAKKKKACiiig
AooooAKKKKAFyaSl6UlABRRRQAUUUUAFFFFABRzRRQAtJRRQAUUUUAFFFFAH/9P/AD/6KKKACiii
gAopRRQAlFFFAwooooEFFFFABRRRQAUUppKACiijFABRRRQAUUUUAFFHNFABRg0UUAFLSUe9ABRR
RQAe9FFFAC0lFFABRRRQAUtFA60AJRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFA
AaKKKAF96SiigBetJRRQAUUUUAf/1P8AP/ooooAXikoooAKXFJS0AJRRRQAUUUUAH1ooooAKKKKA
CiiigAooooAKKKX6UAJRxRRQAUUUUAFFFFABS4pKdhhxQA2ipBGx6CpEtpmOAuc07MCvRV8abdnk
IfWpl0i8PCoSarlYroyqK1X0m7XlkxULafdDqh6Zx3o5WF0UKKsvazRjLA1AUdeoxSsxjfel6UYI
pKkAooooAKKKKACiiigAooooAKKKKACilpKACiiigAopcYpKAClpKKACiiigBTSUUtABSUUUAFFF
FAH/1f8AP/ooooAKKKKAF4o96SlNACUUfSloASiiigApQPWkoxQAUUUfSgAooooAKKKKACiiigAo
oooAKXvgU9Ync4UZrc0/w/fX8gjiTO7pVJN7CbSV2zBCMTVuGxuJz+7Wvrr4Y/smfELx1H/aENp5
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
tQkLyuTk9e9c880kn3yTms3ieXSkrfn9/wDkarB8+teV/wAvu/zueoeMfif4j8W6lPqusXct1cXD
F5JJXZ2YnuSxJNeazXk0zbnYn61UJJ60leZKbk7s9iMYwVooUnPNJRRWRYUUvvSUAFFFFAC0lFFA
BRRRQAUc0UUAFFFGTQAUUUUAFFGKKACiiigAooooA//R/wA/+iiigAopeMUlABS8UYpKACiiigAo
oooAKKKKACiiigA560UUtACUUvIpKACiiigAooooAKXNJRQAGiiloAMnpUiylaj+lFMC8l/cRfcY
ir0Wu30R4c/nWFRT5mKy6nWf8JXqZA3SNn6mrC+MdUHHmHn3ri6K055Ecsex2h8Y6qy481h+NVG8
T6k3zGQ/nXLUUueQckexutr18xLGQ5qo+pXD/eYms2ip5mXZImeZ3POSM1EWJpKUY71NygpKKKQg
ooooAKKKKACl9qSigAoope1ACUUUUAFFFFABRRRQAUvtSUUAFFFFAC0lFFABRRRQAUUUUAf/0v8A
P/ooooAWkoooAKKOlFABQKKKACiil7UDEo470vvSUCCiiigAooooAXikpaSgAooooAKKKKACiiig
AooooAU0lLSUDFx2pKKKBBRRRQAUUUYzQAUtJRQAUopKKACijpRQAUUUfWgAooooAKKKKACloFJQ
AUUUUAFFAGeKKACiiigAooooAKKKKACiiigAooooAKKKKAP/0/8AP/ooooAKOKKKADr1opaSgAoo
ooAKWkooAKMk9aXBpKACil9zSUAFLSUUAFFFFABRRRQAUUUUAFFHWigYUvSkooEFFL7UlABRRRQA
UUUUAFLSUUAFFFFABRSjrzSUAFFFFABRRRQAtJRRQAUUUUAFL0oxjrSUAL3pKU0lABRS4pKACiii
gAoopc0AHSkpSDQKAA9c0lLikoGFFFFAgooooA//1P8AP/ooooAKKKKAFxR0NJmigAopT1oFAxKW
kooELxSUUvFABSUtJQAUUUUAFFFFAC0vB6cUme1JQMWkoooELSUUUALQOtJS0ABx9aKSl7UAJRRR
QAtJRRQAtJRRQAUYI60UtACUUpHekoAWkpfpRnNAwpKKWgQhooooAXNHXOKSigBenWjnpSUUAKPW
koooGL2pOlFFAgopc0lABRRxRQMKX8KSigQuc0lLRQMSl+tBGKSgQUUUpoA//9X/AD/6KKKACiii
gAooooAKKKKACiiigAooooAKKKBQAUUUUAFFLSUAFFFFABRRRQAUUUUAFFFFABRRRQAUUtJQAUUU
UAFFFLQAlFFFAC0lKKSgYUUUtAhKKKKACiilz2oASiiigBfakopTjtQAYpKMUUAFFFLzQAlFFFAB
RRRQAUUUUAFFFFABRRRQAUUUUAf/1v8AP/opcHOKSgAooooAUUcUn1ooAKKXrSUAFFFFABRRRQAU
UUUAFLntRijBoAPpSUuKSgAooowetABRRRQAUUUAZ4oAXkUCjtRg0AHNJS96KBhSUUUCClpKXFAC
UUopKACig0UDClpMGj6UAFFFFAgoopeaAEooooAKKKKAClPFJRQAtJSmkoAKKKKACilxSUAFFFFA
BRS0lABRS9qOvFACUUUUAFFFFAH/2Q==
'''

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
if (binding.hasVariable('args') && args != null &&
        args.collect { it?.toString() }.contains('autosave-self-test')) {
    CoreAlignCorrectionBridge.runSelfTest()
    return
}
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
// Stable project layout.
//
// Only qc/, results/, and qupath/ contain files that a researcher normally
// opens. All resumable engine state is kept under work/. Older tma_* folders
// remain readable so an existing run can be upgraded without starting again.
// -------------------------------------------------------------------------
File gridQcDir = new File(workflowDir, 'qc/01-grid')
File orientationQcDir = new File(workflowDir, 'qc/02-orientation')
File resultsPngDir = new File(workflowDir, 'results/png')
File resultsOmeDir = new File(workflowDir, 'results/ome-tiff')
File resultsTablesDir = new File(workflowDir, 'results/tables')
File resultsPresentationDir = new File(workflowDir, 'results/presentation')
File qupathProjectDir = new File(workflowDir, 'qupath')
File workDir = new File(workflowDir, 'work')
File stateDir = new File(new File(workDir, 'state'), imageStem)
File runBaseDir = new File(workDir, 'runs')
File legacyStateDir = new File(new File(workflowDir, 'tma_pipeline_state'), imageStem)
File legacyRunBaseDir = new File(workflowDir, 'tma_auto_orient_export')

[gridQcDir, orientationQcDir, resultsPngDir, resultsOmeDir,
 resultsTablesDir, resultsPresentationDir, qupathProjectDir, stateDir,
 runBaseDir].each { folder ->
    if (!folder.mkdirs() && !folder.isDirectory())
        throw new IOException("Could not create CoreAlign folder: ${folder.getAbsolutePath()}")
}

// State files are small. Copy them once into the new work/state location,
// leaving the legacy source untouched as a safety backup.
if (legacyStateDir.isDirectory()) {
    legacyStateDir.eachFileRecurse { source ->
        if (!source.isFile()) return
        String relative = legacyStateDir.toPath().relativize(source.toPath()).toString()
        File target = new File(stateDir, relative)
        if (!target.isFile()) {
            target.getParentFile()?.mkdirs()
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}

System.setProperty('corealign.project.root', workflowDir.getAbsolutePath())
System.setProperty('corealign.qc.gridDir', gridQcDir.getAbsolutePath())
System.setProperty('corealign.qc.orientationDir', orientationQcDir.getAbsolutePath())
System.setProperty('corealign.results.pngDir', resultsPngDir.getAbsolutePath())
System.setProperty('corealign.results.omeDir', resultsOmeDir.getAbsolutePath())
System.setProperty('corealign.results.tablesDir', resultsTablesDir.getAbsolutePath())
System.setProperty('corealign.results.presentationDir', resultsPresentationDir.getAbsolutePath())
System.setProperty('corealign.qupath.projectDir', qupathProjectDir.getAbsolutePath())
System.setProperty('corealign.work.stateDir', stateDir.getAbsolutePath())
System.setProperty('corealign.work.runBaseDir', runBaseDir.getAbsolutePath())
System.setProperty('corealign.legacy.runBaseDir', legacyRunBaseDir.getAbsolutePath())

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
def sha256Text = { String value -> MessageDigest.getInstance('SHA-256')
    .digest(value.getBytes('UTF-8'))
    .collect { String.format('%02x', it & 0xff) }.join() }
String profileHash = sha256Text(profileCanonical)

// Keep computation and delivery identities separate.  Export-only switches
// must never invalidate an approved grid or accepted per-core rotation.
def orientationProcessingConfig = new LinkedHashMap(orientationConfig)
['saveFullResolutionPng', 'saveNativeOmeTiff',
 'saveRotatedMultichannelOmeTiff'].each { orientationProcessingConfig.remove(it) }
def detectionIdentity = [grid: gridConfig, detection: detectionConfig]
def processingIdentity = [grid: gridConfig, detection: detectionConfig,
    orientation: orientationProcessingConfig]
def outputIdentity = [
    saveFullResolutionPng: orientationConfig.saveFullResolutionPng != false,
    saveNativeOmeTiff: orientationConfig.saveNativeOmeTiff == true,
    saveRotatedMultichannelOmeTiff:
        orientationConfig.saveRotatedMultichannelOmeTiff == true
]
String detectionConfigHash = sha256Text(configJson.toJson(detectionIdentity))
String processingHash = sha256Text(configJson.toJson(processingIdentity))
String outputHash = sha256Text(configJson.toJson(outputIdentity))
System.setProperty('tma.config.path', configFile.getAbsolutePath())
System.setProperty('tma.config.profile', profileName)
System.setProperty('tma.config.profileHash', profileHash)
System.setProperty('tma.config.detectionHash', detectionConfigHash)
System.setProperty('tma.config.processingHash', processingHash)
System.setProperty('tma.config.outputHash', outputHash)

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
File profileStateDir = new File(stateDir, 'config')
profileStateDir.mkdirs()
def processingHashForSavedProfile = { Map savedProfile ->
    def savedGrid = savedProfile.grid instanceof Map ?
        new LinkedHashMap(savedProfile.grid as Map) : [:]
    def savedDetection = savedProfile.detection instanceof Map ?
        new LinkedHashMap(savedProfile.detection as Map) : [:]
    def savedOrientation = savedProfile.orientation instanceof Map ?
        new LinkedHashMap(savedProfile.orientation as Map) : [:]
    savedGrid.rows = ((savedGrid.rows ?: 18) as Number).intValue()
    savedGrid.columns = ((savedGrid.columns ?: 7) as Number).intValue()
    savedGrid.coreDiameterMM = ((savedGrid.coreDiameterMM ?: 0.6d) as Number).doubleValue()
    if (savedGrid.autoDetectGeometry == null) savedGrid.autoDetectGeometry = true
    if (savedGrid.autoEstimateCoreDiameter == null) savedGrid.autoEstimateCoreDiameter = true
    if (savedGrid.autoInferLayout == null) savedGrid.autoInferLayout = true
    ['saveFullResolutionPng', 'saveNativeOmeTiff',
     'saveRotatedMultichannelOmeTiff'].each { savedOrientation.remove(it) }
    return sha256Text(configJson.toJson([grid: savedGrid, detection: savedDetection,
        orientation: savedOrientation]))
}
def compatibleLegacyProfileHashes = [profileHash]
(profileStateDir.listFiles() ?: [] as File[]).findAll {
    it.isFile() && it.getName().endsWith('.json')
}.each { savedConfigFile ->
    try {
        String savedCanonical = savedConfigFile.getText('UTF-8').trim()
        def savedProfile = configJson.fromJson(savedCanonical, Map.class)
        if (savedProfile instanceof Map &&
                processingHashForSavedProfile(savedProfile) == processingHash)
            compatibleLegacyProfileHashes << sha256Text(savedCanonical)
    } catch (Throwable ignored) {}
}
System.setProperty('tma.config.compatibleLegacyProfileHashes',
    compatibleLegacyProfileHashes.unique().join(','))
new File(profileStateDir, "${profileName}_${profileHash.take(12)}.json")
    .setText(profileCanonical + '\n', 'UTF-8')
println "TMA config: ${configFile.getName()} | profile ${profileName} | processing ${processingHash.take(12)} | output ${outputHash.take(12)} | grid ${configuredRows}x${configuredColumns}"
if ('true'.equalsIgnoreCase(System.getProperty('tma.configValidateOnly', 'false'))) {
    println 'TMA_CONFIG_VALIDATION_OK'
    return
}
def existingGridForPreflight = imageData.getHierarchy().getTMAGrid()
boolean needsDetectionPreflight = existingGridForPreflight == null ||
    existingGridForPreflight.getTMACoreList().isEmpty() ||
    !existingGridForPreflight.getTMACoreList().any { !it.isMissing() }
if (!ALL_IN_ONE_INTEGRATION_TEST && !STOP_AFTER_DETECTION) {
    File runSummaryGridApproval = new File(stateDir, 'approved_grid.json')
    File runSummaryOrientationReport = new File(orientationQcDir, 'run_report.json')
    File runSummaryFinalApproval = new File(stateDir, 'final_orientation_approval.json')
    File runSummaryCorrections = new File(workflowDir, 'corealign-review-corrections.json')
    boolean hasApprovedGrid = runSummaryGridApproval.isFile()
    boolean hasOrientationResult = runSummaryOrientationReport.isFile()
    boolean hasFinalApproval = runSummaryFinalApproval.isFile()
    boolean hasSavedCorrections = runSummaryCorrections.isFile() &&
        runSummaryCorrections.length() > 20L
    boolean researchOutput = orientationConfig.saveRotatedMultichannelOmeTiff == true

    String runType
    String runWork
    String runResult
    if (needsDetectionPreflight && !hasApprovedGrid) {
        runType = 'First run: detect the TMA grid'
        runWork = '1. Find the array, rows, columns, core size, and core positions automatically.\n' +
            '2. Create a whole-slide Grid QC image.\n' +
            '3. Pause so you can check the detected circles.'
        runResult = 'REPORT.html and qc/01-grid/. No rotation starts until the grid is checked.'
    } else if (!hasApprovedGrid) {
        runType = 'Grid review: check and approve the circles'
        runWork = '1. Keep the current TMA grid.\n' +
            '2. Apply any TMA correction or missing annotations.\n' +
            '3. Refresh Grid QC and ask you to approve the exact grid.'
        runResult = 'If approved, CoreAlign starts rotate then crop. If cancelled, the grid stays editable.'
    } else if (!hasOrientationResult) {
        runType = 'Core processing: rotate, then crop'
        runWork = '1. Reuse the approved grid.\n' +
            '2. Rotate each individual core before cropping it.\n' +
            '3. Save progress after every core so the run can resume safely.'
        runResult = 'Per-core previews, QC tables, and the selected image package.'
    } else if (!hasFinalApproval || hasSavedCorrections) {
        runType = hasSavedCorrections ?
            'Resume: apply saved review changes' :
            'Orientation review: confirm the rotated cores'
        runWork = '1. Reuse accepted detection, rotation, and crop checkpoints.\n' +
            '2. Recalculate only unfinished or changed cores.\n' +
            '3. Refresh REPORT.html and ask for final approval.'
        runResult = 'Accepted cores are not processed again.'
    } else {
        runType = researchOutput ?
            'Update results: create the research package' :
            'Update results: refresh presentation files'
        runWork = '1. Reuse the approved grid and accepted core transforms.\n' +
            '2. Create only missing files for the selected output.\n' +
            '3. Refresh REPORT.html and the ordered QuPath project when requested.'
        runResult = 'No redetection or reorientation unless the slide or a saved correction changed.'
    }

    String outputSummary = researchOutput ?
        'Research: full-resolution PNG, rotated multichannel OME-TIFF, and QuPath project.' :
        'Presentation: full-resolution PNG images. You can switch to Research later.'
    String geometrySummary = autoDetectGeometry ?
        'Automatic. No row count, column count, or core diameter is required.' :
        "From config: ${configuredRows} rows, ${configuredColumns} columns, ${configuredCoreMM} mm cores."
    String runSummaryMessage = "${runType}\n\n" +
        "What CoreAlign will do\n${runWork}\n\n" +
        "What you will get\n${runResult}\n\n" +
        "Output\n${outputSummary}\n\n" +
        "Geometry\n${geometrySummary}\n\n" +
        'Click OK to start this run. Click Cancel to change nothing.'
    if (!Dialogs.showConfirmDialog('CoreAlign | Run summary', runSummaryMessage)) {
        println 'RUN_CANCELLED: no image processing was started.'
        return
    }
}

def step1 = new EmbeddedWorkflowScript(name: '01_build_tma_grid.groovy', payload: '''
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
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
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
lGKK3NynDVk7I09bOMNt4/8Fq4Jk9tLAAQA=
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
H4sIAAAAAAACE+19a3fbOJLod/8KxCc9EhOJfiTpSavzOI7jJN52bLftTjrr+OrQIiUxlkiFpGxr
E9/ffusBgABIyYq7Z2fP2dtnxqFIoFAAqgpVqEJh7cGDFfFAHJ/sHIpHoi3eTcdB0o6TdjGM2qM0
nYgsuoyjq5bopVkW9Yo4TUSQhCKYTLL0MhiJtC8CcfJ+SwyyOPQBGMLbT7MxfJvmUQd/CrHhi90k
nwAAAZBFbxYka1kU6ooEsxgHXfzR/doTh/tvRT9Lx+K4iCZQncFs+uIkBZQmo6AXQbtXWZoM1sZx
ngMswDBqiTALruBLDu2PAGqSFgEh3YuSAluER8CAwcF/sluiABjTiNDojYI8j/uztSQYRyIuxCpi
WfZ/1RdiKwypI0WQDaLChBYJqlak9N1AgF5fDaNETFKAfz4CZCN/4OvKTjPicfs1NvUxLobptIA+
cWMEqCW2oamtUTxIRD/O8kKMg0lu9yzOeiNCJBBZej6FMv24KGAIRkFRxDB+gEeMLUEjuziLxjiK
qyDXkOIkjzKseB4VVxF0oLhKRRgVgKcsnrcQaJTxDxEAgHwY9/FznPBQaGhJdF1QU3EyEPkohZ5N
i3QMg9SDKZuJnEcuS68A5nk0ymG+x0EMZIe9jUI5YI+IFMZBdgGYEwn1IpjxIpsCjOA8h+lWxJBY
0xAFGU4qzHQ5VLUzTrAloqs8V9UPPEsM6bEvjqZIXnEu8l4WTwoY2W09oTwuwDijOApbApgnDhkn
LD9Mr5IWs4ExWOkkyoIizcQY5y+6hrq9uMAeEv9FMCV9nC4cMfgaJXkML9Mshu4z7Bz4R7HlyTBS
FXHe0iyME5i2nDlaHL/bam8++VkMg3zIcwgtI8tMizwOuZHfp4dBMURgeQQDQMQDYGeiFxBjZYzP
OUxgJKsHfaSMQPQyhAt9ARToa5AViNnayko8nqRZASiN/UGaDkYw1jmAfgt/Fn17NY1HYZSpIl+n
E8DNH8Xn/mAa+zwFMEf+74c71zWFAOQYIG2nozQ7SdNRXlMmPf8Cs5f72OsDfl5QahjDdGW94cx/
HfWD6agAcnkLtFlTZTKaDoCx/EmQAdHBCGEb8nEvzouaKlk0QDLyj+jfo+jrFAZRD96X4DK49uNx
MIji1N/Ff3cPzI9+cFX4W6PJMNhOx8T7UeXzqyCPe8dFll5Uv9E4Vd6+SZOi8vIoSmBeYOjfAfvn
lc+EpP9q2u8DvYSEqlUmgQ70Y5jpN/Anr/90XADRBlm4nU5mBxMkdatcHvWmWVzM/PdApgD/dTyI
chvRAgSRfwxvRtFr4II3uGbBcELvka8/Hhz99mbv4GP3w87R8e7BvnguGvkFrozjoD0EHmxv+OuN
lTDqiy9AivA5ia6EQZVND5AoDrOoKGaHABIpEd71sggaa3pUEyhwEvSK/7ABwEeJxPbB0dHO9gk0
393e2zo+RiTsVaKhir7fOvqt+373+Hh3/61d2BRXjRVABMDuHRx1X++cAOyd11CuZAGgx97F0dtX
zfWW2HzyhP54RiXZQn0dqvD4Kf7frCN7Ma+lp9jUJvx5ug6jkk5hZRQ48NsH+292X+/sb+90T94d
7Ry/O9hDCOv+P5+EKzR8REcwdwG8hpVxewqjkhS76i2MY9wXTaMUjPF0NPLEtxUUrq/jYJQOch9l
706WpZmklWZDqyWs/TRaorGfcnMorEEqJ37DIyAwv9MsWbkhhGCdvARZ97zEzAe8jumtnHItItxS
79QHWZDaf16WxzJSnMiOcQnuk/j+nTUwLoXaAUoRILg43xlPCoB6h26vCPkfdl9/hREA/olhdQY+
pCVvfaN7jlTfVSqcP8jS9HLGukllqKh/sAKko8sIJA4wJ44ZY4csL87hpeCO0csim8nP+B/WhkUM
RS5P/CH/aHq6CA6PKnJPjtA//qFq4SChQG966mNZE/+TzVdK8xPSGDwXKaIq27yB9a/oDUXzZAiK
Cw6MAGUFlz8Y9ZsVhRIDtqmw2j/VRxBggAXTFDb9x9FuDg33Y1itv6GaYvQsJjSPe8NoDDhhGw0U
kw1xY4FFJBCs6rYeaRA91B34aHTT6OGSvbwxZlqY3RUvy0aOZ6CVjH2eOdBwgDobYCtk/jAdRw0P
uI3VjDaQXBvUGhDZDU90CB6SjxR5oNwRr++j1mYO1PuoCEISAPgLP0uOsWuUnGO+9wH42OYbp6EG
8a2WvPQLzJQxfLIASTtlazRqrjVfxt5nH7r32S/i/sP7a9BJyRZWudP/s9X+z6D9X+vtX/xu++wh
lus2DEGGDZm4ma0rxIiHQL8qJGctHHhU2Um19q/S7MJX1STz66r6webbJs4Wcv0knkSjOIm6BAAn
UWNGs7B1DtWmRcSc5HmMpDIj6YeBqMICgCuFleUKrrUNWdnQYJet3zXq+L38UoECVWoaLQBCdVkq
dqmsWTm6BFZZhIIeGy5JnRjJSb2nyvnjixCkJTAvsHP5Ns7hL0ihNPuLInx1O52OQgGGkGAdhClE
9FPUVjqfk/vfdKOV+bpZrRPh/XHxCAW3kKv2pWi/EMwVfp/UqeZeClZd5P9xDMPwk/+oD0vppSd4
tYQhpNow/2B6t19QC5KpcvjC70v50WiADKCXIHy5XNNESzRWG+KhyBVDNeE3NLu6CsLjIX5Uq/Qw
QDMHm5atWRicz4ro9EyEpDFCKUuDxKHZTWCgEoQvLaaG53PpJmMHZV4BkLzZ+OPkTftpw7Ow5KJg
fYxGuDx9c0as8dP65jXgDfL9H2L9ut+H4fK/pDGqhXLcgiRN0Fp+yyrCNzFQuONnpDQcvtPVqzgs
hs/vfxsgRlj4I76A2fw+jOLBsDA/vaM3ONNnBGlQ1SSioDfE3YhdUO6voVXecIlV26r9LMVlCz8i
iKODXWMFkYSCWL9Hs2YcXDehPI1YOk3CXGLYEtZbhVwJiDv57Jk4jVu6LZb04mUHSKVF1KmAb+Pu
TxqHfwKMlrUi1pX6hKX4S+hJ8HH+nlXopnfG09H4LgW4teYRYrLAZxRVPGdXYIxEW0U6jns0YyQt
eEOnpaiwB6YUYKDGk7+6i7EWE6W2VIwnpuCpr9cy4MlxAp7woW4bmeaPP3Zf+xmYVOkYH9VQw2e0
Yk7AVGpK9ICjJFXXqGZksPljkLdNrFqkLD504+WLqv3mH+0c7m2Btr/z5+7xCRgZ9kTVVNg6OXi/
u919f/BhZwk17F+DpCIBY5oPk8G8mbZsXmlQ/BumWw9FZdqp1AQMRU8rrffkXoJPnWMtBIjgcP8t
MBkANLiywGEn1HYPdq57EQ1ZcxWMB9zPpfqZZT7A6pOJ+99cXNVy8/+JyyQu0GSiJNxBHYIo630w
Yd1DkRD9AP1yjJtrmkrcLY7m6gz+a79/3w7Dxknj3bvOeNzJ8z///HPVU4sQ1ntN+xReCZlUHP8K
VoCPNJNbhI9e5Mgm8Se41TFKmsbWBvQZ/2kSEM/T/aFtV5zwgz71Jz3/opkBJ16JVHivbLBtrNL0
XhpkvXBaFAjSIFS7vFu3fMN3bijOT4LBIApVI1rSF8HA0XdQVjSNAWlCcbmSedIiMYvjdlHT7Me8
8hKtnh99nQajfJcQ3gbdHeTGwEPbB6dnL72KMnrr+SjmgzjJsYD9SesfQLCoFigzi7qXp9OsFyky
t4ZT8waXcSw01Cqg/kWL1UfJJpOpLvMBdarmhaHzQdGXxk/ZbbkO32KkStV1HIVxkJTqZ64wRokn
3xgGIr+xbEIe2Nek0Pj7wb7Wv/KUnCXPVaVS0Wuy+uOhbnfjYzk5S7hXhshwVT+P/yuy5y8RP4lN
RGgD9GAudZr4UC2ML5ub3pno6EFuVj+Lttg4Q824WtETa2LTXw9xXNbWBLD0aGb6GQomYMv7p3wp
uS/EUUQbP0TUw4hNj4somuQITfpx1mAEpmNgpwwMDdGPryP2942Di4h8VtLNgdDRrZSN4wQ0zri3
FofReJKi1uFLystItUXxtJVlwYw009p9L96AxE/b6QjrqFKGHkxdvtzwH6PHZABWIfqfpugD2/D/
+eRaTIIQe8YuDpGPUAmFEiMk0QzK/LJ+jTOOG/0IqQdCP8sF9CWeDKFDI+VVHKNLJAMTEKhxJq6G
uIrDxAKX4bgF4jImf6AYgECXPjaEF4RfAnRbcrcBi51rHBaoMkIvD3aIfUrjeADDR87NHvsw5bTl
KfvlEFo2TbQrzRpXqZJHOdiZ4XaWTg65228CtDph5LCj4UrJyguKSnaYBFke8XNTE2bd5kMxDnya
mJ4LDC03bBiNp1tYegE+2sjYABpvyZ+gmm/6G/h7Xk1JPTDqwIkhEhUYz7Tqrq/wZNxiC6EUcc0G
lCOWVVTuBEqz2rCQ2HdWnQnc+64KVmU0s8OtNL6UCJWSslE6jXG8FX2LPo+4vX0qgRl7jPf4Vc0G
WT22NcTABX9oz/Resw76C7EOMzq36XKgEARNe3Ce10Jqz6cC8QzdDevr6xuhPUcLrFw5f+kofB2z
N+8v2boSHAg8A5wJ/MF8dlyrG5yyA0zcAM7wbUr3lJSk/LLk4Kox3RJV07llYgtmNKgdkVrMqEm0
KEmnsu12z1LAdEnyGOmi/MvzlqGeCqi9tHcRhYox5S/vds3OBcBdkvqGqw01WRTIWnbHK1rNLRzZ
cjh8wa7aAlGmIlPy0/gM96gZGRMxLeEePgRFgHbKXdEHDKfMHOmZsl3cyrEpw0HUwsutl06svHRi
YRFzzvuAv3aC/TEJyfxYZp4Nu6h5SkYG6IPbB0c73e3do+29nePu0c7x7n/uvG7gXs4UP7vdKwe6
shB15g7tGWMvLR6xeiQ56v43F/wNFIpytZTL0JwcY3Puf1s0s5sLZ/ZGayirSq2VzSjuy6WIyslp
tAVi/Ju4B0aauSrdlAoqdUfLKnYpkWSrkVm1X0vZdWO0GCP14AKM+75SnqVjVLcMmcYKedPtgNwk
b7oVGCDwbhXSxuY6rg08IBjuA9bwPiJ2DubH65j3bmmD9GzOYh4YK7lE+Jz3guWSdnhwvHuy+2Gn
u7v/Znd/9+RTyWMVYOct8cXcIiVPDi79X6w1xdwdvYaWAmt4DZELC9b5vG8VSLO5kD4tgPTJgCQ7
rjUn/C0VqfwrGDCA7APE+CE2Bk8zz9wUxc7KMYvzN6DvAk8jBJy4uTPz7Bm1uqKppZhNcLv7eBL0
UDhqYpkLQlGNU1ErDRWALhU9APJ6rKIMcCJfzaTz77RzdqsKaFY5bdbtTqP7+A8QW9KyRrEcC2mC
mcYWBrAlGLOoo/OkScFyBCzYHLhrGIz6V8HMis9DEwHBNaFem/BBiSklD9gS+zx27UQNHkYQTqQd
J6O4emTrKU89QosxpO0Nhs+pmMKgD4we6ZhCitk0bD5oOe6xqAP7KSyDDvNfEV46LUYg8gWqlGNs
vLQJQRJAVY6PS3KMbCvQ6gFA0CPy74RrKgiPJ4SdO4jqo2vTL3V6dnoGgKGN65Z+5Zj9RslAGpjy
1aOz08fsEsG9yibZBWgI/Ar/PBOP4J+HD80NQF2qx6V6XKqHpYLT7Oy0h7PN6PBPXZU+PzrTWwfw
0+AlDXgSX6YFA+dHaoAebVSwMHLSUXqF4QxYoIpmpj4BC29YnapEDmg9mhClSmcecJXxXjanv3oG
AtnKHGBuJUABpHvU/kWr3WUoiA6lkGDvSew9N4BCzTNvlwcStlVGvaTvCgmnhHoNZQBSGQpREbUx
WPFkeyiobosWYdA3Jo7HmjhkPSCQtecKYP2EzaU+NTwZrjByYHBHL06mUc34SEWTsM7O6sbodqyZ
pNvPFawHZk+cAbNcZafB6TrSewsqbKiHTXwAJsz1DMpdx35cbJGs2ZOihnYdA9zbtjbv1Ctj906+
kvtqgPzPVboyBEDC0euuFHh0ZhUU2TD/0y1UKfGptoRCSO595pYLVdVGsj3lfQu9cZj7IFut3yBn
7dH6cXrBjsAsiocwwfjvAxP+tVv009yis/mE48hBHmFJOSWsyTyC0WNyfk27pCzimwymRR3wnIKz
OQU/WfuqCK+kE6xURivhPx1xet2BUi0xg39mihbl2mRS4jgNI2iBBhyniHqejkzKpCLupk9JgYor
9AhAy1TFvwY+AemsfmzgWKGjrXy1ia+gudKOmanKM7PyrFp5pitTXdVFuaAfK26ary07W19ORXTR
Q3MdEatdZ20a4oKDFtkIPoqfyr1afG0OgrXJUtmAmC0oAIqs05+PaXYhVT5rD9nG2jOrvOdp44l6
xO41vRPaV4cqnlcEVNNusnSoqho1u39Ok1xSN4WuNzQywymJJxtnw8+QuyEZE4pmNGlWItEiFi3l
ivwBoLxaq8QUDGA+6J8TQ06YtocpHezypbBQdM9CsSNy3jzBTnYWGhpnFQmhK75X7h1pKZgjZwwU
qLOqhrjxagEF4a1QtCZj+HdKuG0HKc9pqTct0n5f7ssYuD8sLfFHaDs/MHFquSbMAzBtNjblfgqT
5aiIeDfYQrs0y2uxffZcIXRjjxPPjmHWqQbUqvrC3G3fVLRErzLcHHBYTFVDxJ88CT0ztsdlVdWU
2uVQ/qkT8hW+SbOt8nwPymIwWxz3Kr633KVQRJpiesTGuM9EOwhNKP5/BcVsNl8+u3eqQzLPvObn
8KHXbspXZw+hgPV9rWRzCY9GvAzdM5DK+AQHzdHq/W+q/ACGa9Lc8G7a7rtNx2y8WbV3FUqrU3tx
f4tmTd2OZ6v0ivGoXscyWnUV0MnUaHfwcBVIerCQh2kIQ6c+dPHYFG5USfO7Q2b2WVXhIwlqieMl
ZhB7ZovFeQuoZKiAtk+SZOEmiSo7W1D2k+GdRTOAF7znor3xoztDyqkTwbSEd9tGqgm2k7oG7h3U
rq1WQdRFnlfWWSemnlYIokdntTAnoMUKztw1IrBXBgV0zgoRzOpLz+bGDM5ZDSxWCAXvIbmqrp4B
2mCyYv55GsPKSzXrsREGH43ySLfDML0SdmhQ/nmajiKQ5r1Rmkc7STodDGXrKGprhPjP0m+lKk6B
uc7jwTSd5hTjTm20GcKLWgib66EV+mG0/I9/WPBe6i4pGaA7bLI9uTsMvmfVoStpwmJ8rH8GWrPB
6XKTbi++XIbb/+fy8NJsWeGFRfrrfJ5YpNT+Xbyhqf5Xm9TrrPXFNHtXWpLU0cXtyi52+TZyKm6n
IYnwAi1BLf+uyrFM0cX0TMWVnlJu5B7HgyQArDAEr0Ly2ptsErGpvQQ92U0VUIattGqO/HkYJY/n
/brqvB8MXkOGw3cxliTKGvYWDDeJoZ8t2c6CyOm50dJmSLQOVsRzztvVISidUrbjyQq9Y/dSTfxC
NQxJ5AqyjgH7gUA9209lRjsACm6kA4j+IBfHUeFMb+4e1CsJQjrW5bktaogFUuk/M1tt6imOi1bl
/CfFkFglamgAD5Boar23YB7KUL96QoUGdMyf2rZH7bBmAmV3TBeHOuShQW9Nw7jgjQT7A51gKbcY
1Jiq3TGHVWSA4fM6ScDcp+Q9F1BCTdXTGzztDWAOtlJk0CEJLq10mtWfiXUcevPVCzUIbMiYmkal
a8+emcdvxsGkPqBONO5/q7VSDC69aXBKB53ywBfvMREApdXglA+04mcqBYVyGFH+Czp+H+Jh/zJV
RT6FYQayblTzTzT8VcdMr1FtUN5Id/IyIspaunXX//yB9V5X+rTEwk+kxgeDTxTlcDCEMZlntkFO
pV9LFUQj2LbhLIOqAjWzQH1aCtSnelBybbQW/BLjBwb2D43my/egDBDYtTVK+oEKJlJBqIiH8oj0
cGIx+hMpTbn2KL/G+ZQzuFAEItOqAhfgF/hD5eIclnqgtatg1pIJRKiemWMEAxUpTYhMHiKzKCh4
KnOISnBipgyJKdkE7qNThClFaGKT8bX0HnJuB1gI8kjBu0K/pkgTRgI+TXMoHF1G2UwiQPlGEHZA
dhU0fw4V+n3cGIZ6fhniK5ObkMgyeYF7CnO8DZ3D9B44UfdMDsGVpSmLa8GjVBVTcstPrCnJA72W
3k2gbDoy4zoMUC7tvKhT4v65XgbMVbvhuhVhfI4xgYcWrMRHVTP0gTY33eo7SWjGFWiAD3WNliNg
24a9uguqDAbhSqrY6nPsh+Um1B6I2MaSHZ0xKrOMB/yo96KpqClzYFGHcFqNQX3OouDC8AreWCq3
Vf5e9fx3BVuzAqL6wsQfXrTbbnW9i0XJXZSMiyn0u1JQ6pXorJdhDdb6fku1Iq2vVK1gRJ0RXpUC
9FbHAzJgr1JKsRv6EhDnjsa8Bch0JEJ28zcVR5Lm3nREkVnzFwJ9Hi+LantqVsDleX4I1/ZP64/C
RsulPluyX1M8QCkiXioM54YAdZZdK3uzH4L9aSFsZ0UKjeAtqwW9DfyUnJfV2Jp1f/1JiC05n4zY
VLJYxnzS6bb41B5YmL1ZSyPUMvFR7j4N0Io+LenNKcFxpna/7AwoHTe7SS0cK2K0Pl5UTkjLrFqF
VQ0dfU0ZuMgAonM1jZYzD40hZnMDqzqBFRSWeMsoVN90Ipkfa5KAhlFS1+wGhkxTI/y0DOR6yw7P
JDZapvprHPu5O9jSYGzNsdFJ8/7BBlT8FK/YmunlCl5BvCp7yAekGzMC4OepC5UUIjRSir7JpWDx
ZTVFUKdiYt6I+98UZ9yseoYcXTL3h2vw0Rlt3hFypCCv7x0tZ0unL29EdFzCum1noyX44U+Aea1+
fOqgcChBazLYDTtzKKul5vK93KayZrKEJUu91vtVspzawGqplaujHs5kePU9Z5SqWXr+9iBra3/i
OBpx67Sjj6f+gMAy/bb5L43LlvR23N06PNzbNcOy3VFhzc9k0rxSyI3C3uINDybjGmg30nQuQa7K
w28fOCMfHr7Jpj2QBAEaA5dBFgdJwUny6LAbWk1aY+c8G5xAxF9RB9Rqj6Rx/qcgC/WGhx2D4G4b
cIWrIMNjYnm5c0JtlT9DJZQ/RFkuN6PuuL9WyvdgNEizuBiOxSVDbfC2zjS5SNKrpLH8RptZ5caf
JjGgX2Y408hvqQZlL3BX2u2Y8hnLc5CV7xjpAjiO8YRhg/hsAfjnqqBkj3JecLeGUmuMyZwM5w8K
UOP9bxU0blZ1ei7nwCEdKDJTdZmZN+hjHTINQoaSeaxx4g40r5EygV65n6ahhAZGXdsPalud33u1
OygXhFzzDxM57gSE8RgDc1M6Wfh1CoICCi/fNgwUeYh+dE8xnCLzAaeq3UiCYO3k1m3dctoDLksO
9VczWdZ0qgtzS1idSr7UUQ5AeTf+RTQ7jgqZVOqejY4pyp1hfTUKkos1Xbzcg2M6ssEoToFBWrFs
w3WyXS3b2DJg1UHZ0gg8u/XUGoWMpnFdbjKnD2Q53f8W4wJ+g6lCQYkXAGjV2P00wk1vKpZO3SEy
MlOqboXyKKXegYWS6om2YMukX4rUuJD1qZo+xukUU7iRf4Ky/Mm0p5yl45wOuKzakdhfeDq+AE4x
/GNvIpCpWXBECU/El7P5GXFwaHroLaQqlREiv2BvVvf9k5P3bpEjEBCtM8c2VeTQ0uODi6GUC7Jb
6sswuIzISTaaCYPUSReT43djZXoi4wWzlzUsWtEGhiJYc3PrJYWYgLDH+FhjddPA5p2ArRhNWrIs
eZ6wcuZ2qcYMc8k5ZWsfsK05W2sNRN2R2qVP07JlHeSsIsidy7qTyp4uhquPVLi7ag+kUVomlXmB
Thj4Ppubx9NuYZRedfUq2jXGqgzqlvP6HHX+vDeNuv00LUjna9iw+HNYwjPQleXM0WVdyrBRYtcy
KWm7ZRyEveqUcTQvQCC9nBODAyS6bhwuxFhXt54TkuPUmGfQqC3CToU5WsYUdIznlpOHo6Nzdshx
6agH6cz9tUHRsyjoZGPqFLxaqfEXraDWgUKqIQ/y2TXkItq24K0YO6TlWVNCVuu+LITMQjdrrlqi
TxZxTgSwFaNQew1QXikVRevucmeErEHW2e6xHm8QiIMCf69vUjol2AiMQss0WF0pVXfMpqVy85Ck
0BmDV/R3P5DJdYgsWxRmgmFXGOPNZNC97k6u1fMMn2VfW3WsZLyUsy6zGfKUU6Yy7pv0un5VPtcK
Sqdf2VHawgR+za8+4gZE95VDiPH3SAYKwDOTr+e8+GTw0ldf4a3LaKw91QbjrH9KOtVhB62G5+kR
k1nXjIxrTZ3esWV0x9yQUeaI9JS8w0zlz2WiwKaVb48tak4dsjW6Cmao+vaBDoc0+VfDdBS1+ahb
aTj8vs0H48gzxU2UOTx8BPUejPx4QuteGVTAp/9kind0XGGu3YDTvcsjelkkGY1S7WMhhIburLwQ
mI+Eb2+4kpcIZFE2TSjrCKgwvHuiUrcwtvQ6B2R8I+EHp8js/VAy0a89SuxRZhK1sonOSSIqb39o
1GcMJWrpmakylWvvEEYDVBnOGtvAGcH84zAkR29fQeem4/MkiEe+2C0w3SUsN+wkVHlXesMgSaKR
ghbS+cIZZ8W/TDHLyigN6Fw+ewv7oylKnh4tcrIyuyvH0TjNZuwotHO9MQUfyBmpJDeufsek9X6Z
1foDvEURQamvoFsnqlfNJYJebpyFtGymqu9L/QsGGox2GfXtJlKxdEX9qaKLt+p0cEw49JROaHt2
2kgOOYa2rPz2cstL59wsIao8aiamNmbrLfzfcmg5i6MxEbIwoBEyZiqg2jzoDGTzxqQKTXQ5ndI9
jwRRToi52OkSEKZVMqBA3mEbAZ5vdWkwC5IBntd9lSGeUA9EUfsc2PaCd7FHAjTOCdaUV2dwtAfn
+dGudEXo5xEo5gVLKpBJdPoW82gq8XAFco+Eg/Tyn0M7A4re93VUDY8PDaVFFy1n5IwB95zKPOi3
1rbnBqqfnslSh/F1REmdqpVk7vr1loloy2rYkEdA+25ZqzUa4ndxXqSDLBhL6YffYF04K3tFxcoU
Qfo90STdYFPXV+6FP4qSAXyS2uOjJ+vwn+xznd1fUxc/PHxuNOd66ydYWg8X1zX9tVQGHdgGmk2u
9OKF2PjZk6lo5zB+WfSpLinblClsnczs2BRY6I8q4dn2eJ9iwbOHD2vKqGwldacFyyk5/OWXJ9Cn
nx+X8s+YKiOliR0ypmbSnDLjOEsvAqlrAkIT+pdf1s3zFRRyPx1PR8TVmiqsSZX5jXFi+fGZALKS
P6oBCQY4nGxnqKjSWTWCoaz04rnZvbr4AWvQzM4T8KpfnoIebvG6Ww5jkmlvMQPZ/NRc7DTefPwE
Dfw1AyfPqwU2Hgd0K8STp+EP8kz1sPwyfEIHhGp4wyo0sAo9rS1zTgeqSyYprUus7J6e2nzyhE+B
0btJetWs4UUYPh5NygtlDPea4Ppeyxo4g2AHf73RwQ83ev7XGz3/4UadyUUUMrTucCq/i+YAn5/i
47lBwrYyd06rsanMwV+rSHOJ1ceq4J98OsQTByddWMEYWbsV9Oots7hZ/WuZewmVRa6i6tgtamGq
7Ig7Lvqq+p2WfXvg00VDbqHZcppdftjJTkd2UACkCvo2CybDuKfMjq8DmhHzpqOmfe+R/9vOp+7W
/snu1t7u1rGVc9gp+GFr74+dsmj3YN9sg2NRsL/8hKcrN56CjHzq6WL9eDQ6wmAYJo+FQ6Erheq6
CscsaIkloLSMi1PU+bZrY9SYWNaMI8CuHm5VnRlVJaHU1lXUwbIe7LKCUq4r0mzSK89cXZ6Yq8tj
y3xxcd1YZ8NExsjy8PN1WDT+xvVYzZqW1kHtKZHhk619z1s0l+qOJfnnyVNX8+MDfpi/AR6e1brR
6Nu8JDR07A/TL8AD7i4+05uN9K6qZtDhDe2ywFYfmPuTmG+i6sVQFc8XVkQE5lSW1LgHo9esrAiB
G//2AEjNyeNPqW8XVvzEFWfLVDy/a4vntS16tWpR/SQtnKB6sijndQnS+BvnuCkb95at/r9tlpeX
IlpsWNICL9fjm3fwoXEcJPkxLBuYFRDf+K8O9l6X6JaSaN2QeBtPas7iVwTfU5Z7nrFsF2kBBgaG
CcogN4orXv/Bw5C3XAgyxwn4t3j0lo9cM0P4h26PZdZyuq8yx+42ZfCk48qzK3p142cYrCT+kVXS
rN7B6awR/+QL8oxE3+SntZE1a9G1eo/hz8bmE4q11V/wwyNccR6tV/3QrDMb6RTrUHNcyJ36bYE7
Xeyi8LCuiNmgZbuC3AOw59G7Dbwn05hX4MwJOSBOr5Stj0Ig5rYst+s6s4Wc92FYQQQrzGorzOZX
COsqhEahem2CyImYDBPbtoT+/TaLosR68wrDV+HFxmPPgoqK5AHY+03MeQRDDv+rb5UgWZ9QtC+q
it8l11ZzIq6qmJJVW/7WjfPDctjg+RFZe7dUmkHBx77eoilFM9JUinE/K6Wrg90nhzpNELksyPPQ
IjzVxWY3ymHR5Rp4ccqqcVOLdhz9ACgXBkPepquylgBg3G+mkOrll1WAxpWjS/cOry2TkOx7bpp6
wEojobacOSBuUZkVhNPtGQEK5ftt48biOvdpeVsl+yelv3Q6Tth/Os9vqs4N1PtQpbu01qkqfXk8
UHht8mcVR//fsUCW10aVASL/vvWTm6uG7SwdF3O3Znn8UBl2IjgWJR2z91WJRGpykNUGgcjbxWwI
KkijNrZNBW3UZEq3dWtJhp27raA2sAXRIZqSj+0wEEfRl4W2a2NJzszMCYplnz3DQXe+GEyrAwjw
xKYMIcBHihvAB8ms5OTn3xhZUHdd25UbWVC++mS+UmPqcQOKu+3Zk6054+K1ql+2jciECgwjdmBB
YEJpHpgBClrKt2qGzT05Ivl+ECURXfuxVdzhpieN/rwrn0q5+ztmcDzN8W7ZQMY4d5A98LrIaQ7s
sLd1snN80j3a+bC787H79ggP7ZQbyyWaHfOHvCC0Y92ZalRT0cMd4zzoQJu2nVpz14y0kqnXO1bo
UUvFJXWsACUjmsqMMSrB2Xr2CWr2nToFv1WTAyCXJxLwhPLRNKkcYDDCyigk53eMUJG4m+FGLSMq
s2M8l/VVrFJHP8khA5LsmLEt9g4YqNSH2HW9ilejL8x+acIEcu2U+klNJR76jiEjzuZQPqojLbpI
XV0WpkiPbqTTnFN7pESSH9Jd9/ft7s6fhwdHJ3Ss5JbOLz9Hagp+nCDUCC8cXnmGRUbU5O4NOUwX
vhqSRmsRLOc4zB6HAplxSXT+5fdtjDlfAOdmte7eHYUDkZ7aVtKNfdw62t/df9sRZbILFRw1WgIP
CZZVBr7QltBww7NgdPByaxmlNe9eIVqtBv6kLNzgS5dtYK8t4T5YDqheERjsinHK3kDOW1GbCvIS
YhDLJxzj0sBceo3qrWzz2rUBYEfoSCnejqTbGGGIymxL3neMeROxCMU1mvcsUyZ+vCOSo3XLfCuV
M2jVa9fTUbglQQF84lcMbCOOtdoYyEtD1eWAuCE18emCu3LpqWJswPd5eaGY363Dw6ODD8DSiLJZ
RjE4ljJj94zkByTljCqxeQG4dbP3gkoLzxHN/+hAbJog1XDhCTzqJImUhvj+vbr/uLBeMAVJimtq
N8akCHyRWhf5iYbLIT0HJd1Es7a7Bk+Uceq1AOaOmMlWzxcwnVfX82r358C9DTULxsSUHzr989y6
btec6jV8r/dRb72MkJjTZgPFfbesBsweKAk0dziin05xSeCKDMJfMeRsiGc3SnaReUTLy70ViMZz
6J++UvySz0cy1T9vrFgNdZyTX/qMFyreubiuPxiGh8nQ0XH/m6mO6etyWsIJ+lbq2eqKcetOGWWN
SMyJ0DZqvAPVSURSj7r/rdSkjNNiL0UjSZMITxmX328MIB+1rnX/m9K26uurrzdzkBbbxx804kp4
VtZiDk+vw/WH6eVo5z8oYcEinYppamuv+2rvYPu3BcqUHkhDIa1RRKWGs8TF9Uq+ifMR5U4wr7A/
UVdMYtikcUqXcaCAXXlnyXmkKb7zOfmctO2JVvdzt0XDu8Hvq8alyDZJfU5umRrPYZ61NbGPccIi
yC/IYad7ZGUtmiYyXdIQQzxDfX2mvG2Sr8RM6erGMV60wlmQ8ohTfU3pHLbK2pS3OHeRHII0SVQI
uWzykqKH/SXPni9NSUbyAGn8He38/sfu0WLaOjwAbX1B1WX1djxX7/Tlgc9bbHez8gz7yza9zizD
WymrFMA7X1Wsau2NRpULdpN+up8WcR/PyWFwsXklnHEWQObxM7nh9pPu5ftm7skzMKSOS3oztfHf
t32xm+QTPHu7LIXRVaQJfiwwphkHO+dDCoOATiPQdadRUt4NO0rTi1zh5Ztc1zSH1ZakKEVXPyfS
lLEtBiwNPOgyoaN4l9qwzlqnKEAmRymTLsnoT3R1quzhaSH3VktdWilUimeMhlC1XzE24cw29DcF
c0tpb8LQ3gR1VMmNxorMOPxtxUkoVRvHKpGWRaSAxRTjWnri2ai8srSqpZJIJVf1fVc60ga7Ssox
hqHMRUmxVzGoUmT2YZINYES8Tq+Fh02i60maFSrxoMoVp9qEVhpuG9vDqHchT1xkxlEuVQdlm4yi
B/hv2ZhkMUlIVwGOYgB48BvTJQxjAMi6UPkolyJQwXW28ejBCBPCyaQGMjMdcY5fbrnXTLjJ6/I9
v2o6yOkx/C5kuSpujZYzrbYH+p7bvhv3sbxg39rf3tnb03JcW2wLdYWy1q0ivBS5eJKrUz1lmneB
jMrcRY2zutz1tWE1KmkuM6RB/39RTZ2Ok9xRFA7VMuPor5+T92qlcbjsc7IfRWEOekGPLprmka/o
rS7TNdg4IIELWhEdX8rxJGMA0lpdJxnwqSYKVGCyQVcyaiCZTHnjsFnDYVxmWkEnAK0LK4mppJCn
e0ox9Sylb8TD3oDZgCR/QPntmGPJ7+CyYcmCQK5lxtJbeA7vExcRrGvYKc30ku0MuTuf1SzuknaW
1DZ5NaOEBZhpyiIaz5bUqiWlxuFuO6zb8KYEH1KDDVyCiCM1et+/V6U2nfmu8Oy/Vp/XhW9lURex
TuVNi8amQ39rFHxpItnajdbzcZVQo9OoaXXRGBnZxg3uAA37ivJRFESXIRgBlOSzRsZfYSpQLKgF
jN9wwlhqgFJ6JCgqPqVTOtYlSbZUb0jFIs0+RgMF6JY0Ib/h6ie2kLqbC6fOc6OSf5Q+9KU80Yu8
0HM80Au8z/9duRj+Dm/z7U2V2SDdRHFuj+uLLLiM/ZZk5kujqGe8msgA08vc5sP+N6QzuLOn/G/z
kt/BQ36bV7zOs6QpoVP38qzMlq821fn+t4q3lR1naXbRH6VX+v3Hg6Pf3mCSjQ87R8dgS7dWLOe9
uyHdmf+pJfcO9SZmp2Zj0wFfbsF2FmzsciXtLNa7lS1j/ebt7I67Wf1y8e62SmNpgWJHc/nM3/Su
f52/mT5KZ3P1tHBZRLmeq6cRWis1PmtHiSzL3O7Cnue+lpNUqprODseKu4TWbXXc4mYu0azXDco1
v7Uyx+vsJILM5/i8pXtYSa+VMytpg+lPsn3D6kvpG5brXpqFC5Jr6O93Sa5x9yAxRYpOlJjqtUq4
kam1uILmaWYm3Mh8FOko4GTgjJFwI6uExdQk3JAfzNCYMgUHtzAnFOaWQJjlg2Ds7BxGSAHPdTkC
ZuhLrV5LTv9SpMzbpK7fD1yx1gGLUW6NxbA037u4akpngHqpt7aHnYp/RhemXRGy7LCQ63OtuA1M
I0TtWC2xB+nsxlf2HXmxIi3b562XGrT9fHqe8/RRGLp344uDLIavgUyYO4MBvEKFeQ2Tjowjf9Vb
+X9Jdwnqk6YAAA==
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
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
942j8d59u6b8DWzyzTEQEAAA
''')
def step5 = new EmbeddedWorkflowScript(name: '05_finalize_orientation_review.groovy', payload: '''
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
Szk+/B+feAKgDCUAAA==
''')
def step6 = new EmbeddedWorkflowScript(name: '06_export_presentation_package.groovy', payload: '''
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
''')

def step7 = new EmbeddedWorkflowScript(name: '07_build_qupath_analysis_project.groovy', payload: '''
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
QwQxAAA=
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

def publishFile = { File source, File target ->
    if (source == null || !source.isFile()) return false
    target.getParentFile()?.mkdirs()
    try { Files.deleteIfExists(target.toPath()) } catch (Throwable ignored) {}
    try {
        Files.createLink(target.toPath(), source.toPath())
    } catch (Throwable linkError) {
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    return target.isFile()
}
def publishFolder
publishFolder = { File sourceDir, File targetDir ->
    if (sourceDir == null || !sourceDir.isDirectory()) return 0
    int count = 0
    sourceDir.eachFileRecurse { source ->
        if (!source.isFile()) return
        String relative = sourceDir.toPath().relativize(source.toPath()).toString()
        if (publishFile(source, new File(targetDir, relative))) count++
    }
    return count
}
def projectHtmlEscape = { value ->
    (value == null ? '' : value.toString())
        .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
        .replace('"', '&quot;').replace("'", '&#39;')
}
def projectIcon = { String name ->
    def paths = [
        back: 'M7.82843 10.9999H20V12.9999H7.82843L13.1924 18.3638L11.7782 19.778L4 11.9999L11.7782 4.22168L13.1924 5.63589L7.82843 10.9999Z',
        forward: 'M16.1716 10.9999L10.8076 5.63589L12.2218 4.22168L20 11.9999L12.2218 19.778L10.8076 18.3638L16.1716 12.9999H4V10.9999H16.1716Z',
        home: 'M13 19H19V9.97815L12 4.53371L5 9.97815V19H11V13H13V19ZM21 20C21 20.5523 20.5523 21 20 21H4C3.44772 21 3 20.5523 3 20V9.48907C3 9.18048 3.14247 8.88917 3.38606 8.69972L11.3861 2.47749C11.7472 2.19663 12.2528 2.19663 12.6139 2.47749L20.6139 8.69972C20.8575 8.88917 21 9.18048 21 9.48907V20Z',
        sun: 'M12 18C8.68629 18 6 15.3137 6 12C6 8.68629 8.68629 6 12 6C15.3137 6 18 8.68629 18 12C18 15.3137 15.3137 18 12 18ZM12 16C14.2091 16 16 14.2091 16 12C16 9.79086 14.2091 8 12 8C9.79086 8 8 9.79086 8 12C8 14.2091 9.79086 16 12 16ZM11 1H13V4H11V1ZM11 20H13V23H11V20ZM3.51472 4.92893L4.92893 3.51472L7.05025 5.63604L5.63604 7.05025L3.51472 4.92893ZM16.9497 18.364L18.364 16.9497L20.4853 19.0711L19.0711 20.4853L16.9497 18.364ZM19.0711 3.51472L20.4853 4.92893L18.364 7.05025L16.9497 5.63604L19.0711 3.51472ZM5.63604 16.9497L7.05025 18.364L4.92893 20.4853L3.51472 19.0711L5.63604 16.9497ZM23 11V13H20V11H23ZM4 11V13H1V11H4Z',
        moon: 'M10 6C10 10.4183 13.5817 14 18 14C19.4386 14 20.7885 13.6203 21.9549 12.9556C21.4738 18.0302 17.2005 22 12 22C6.47715 22 2 17.5228 2 12C2 6.79948 5.9698 2.52616 11.0444 2.04507C10.3797 3.21152 10 4.56142 10 6ZM4 12C4 16.4183 7.58172 20 12 20C14.9654 20 17.5757 18.3788 18.9571 15.9546C18.6407 15.9848 18.3214 16 18 16C12.4772 16 8 11.5228 8 6C8 5.67863 8.01524 5.35933 8.04536 5.04293C5.62119 6.42426 4 9.03458 4 12Z',
        microscope: 'M13.1962 2.26797L16.4462 7.89714C16.7223 8.37543 16.5584 8.98702 16.0801 9.26316L14.7806 10.0123L15.7811 11.7452L14.049 12.7452L13.0485 11.0123L11.75 11.7632C11.2717 12.0393 10.6601 11.8754 10.384 11.3971L8.5462 8.21466C6.49383 8.83736 5 10.7442 5 13C5 13.6254 5.1148 14.2239 5.32447 14.7757C6.0992 14.284 7.01643 14 8 14C9.68408 14 11.1737 14.8326 12.0797 16.1086L19.7681 11.6704L20.7681 13.4025L12.8898 17.951C12.962 18.2893 13 18.6402 13 19C13 19.3427 12.9655 19.6774 12.8999 20.0007L21 20V22L4.00054 22.0012C3.3723 21.1654 3 20.1262 3 19C3 17.9928 3.29782 17.0551 3.81021 16.2703C3.29276 15.2948 3 14.1816 3 13C3 10.0047 4.88131 7.44881 7.52677 6.44948L7.13397 5.76797C6.58169 4.81139 6.90944 3.58821 7.86603 3.03592L10.4641 1.53592C11.4207 0.983638 12.6439 1.31139 13.1962 2.26797ZM8 16C6.34315 16 5 17.3432 5 19C5 19.3506 5.06014 19.6872 5.17067 19.9999H10.8293C10.9399 19.6872 11 19.3506 11 19C11 17.3432 9.65685 16 8 16ZM11.4641 3.26797L8.86602 4.76797L11.616 9.53111L14.2141 8.03111L11.4641 3.26797Z',
        check: 'M4 12C4 7.58172 7.58172 4 12 4C16.4183 4 20 7.58172 20 12C20 16.4183 16.4183 20 12 20C7.58172 20 4 16.4183 4 12ZM12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2ZM17.4571 9.45711L16.0429 8.04289L11 13.0858L8.20711 10.2929L6.79289 11.7071L11 15.9142L17.4571 9.45711Z',
        warning: 'M12 22C6.47715 22 2 17.5228 2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22ZM12 20C16.4183 20 20 16.4183 20 12C20 7.58172 16.4183 4 12 4C7.58172 4 4 7.58172 4 12C4 16.4183 7.58172 20 12 20ZM11 15H13V17H11V15ZM11 7H13V13H11V7Z',
        help: 'M12 22C6.47715 22 2 17.5228 2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22ZM12 20C16.4183 20 20 16.4183 20 12C20 7.58172 16.4183 4 12 4C7.58172 4 4 7.58172 4 12C4 16.4183 7.58172 20 12 20ZM11 16H13V18H11V16ZM12 6C14.2091 6 16 7.79086 16 10C16 11.8638 14.7252 13.4299 13 13.874V14H11V12H12C13.1046 12 14 11.1046 14 10C14 8.89543 13.1046 8 12 8C10.8954 8 10 8.89543 10 10H8C8 7.79086 9.79086 6 12 6Z',
        image: 'M19.5761 14.5764L15.7067 10.707C15.3162 10.3164 14.683 10.3164 14.2925 10.707L6.86484 18.1346C5.11358 16.6671 4 14.4636 4 12C4 7.58172 7.58172 4 12 4C16.4183 4 20 7.58172 20 12C20 12.9014 19.8509 13.7679 19.5761 14.5764ZM8.58927 19.2386L14.9996 12.8283L18.6379 16.4666C17.1992 18.6003 14.7613 19.9998 11.9996 19.9998C10.7785 19.9998 9.62345 19.7268 8.58927 19.2386ZM12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22ZM11 10C11 11.1046 10.1046 12 9 12C7.89543 12 7 11.1046 7 10C7 8.89543 7.89543 8 9 8C10.1046 8 11 8.89543 11 10Z',
        folder: 'M3 21C2.44772 21 2 20.5523 2 20V4C2 3.44772 2.44772 3 3 3H10.4142L12.4142 5H20C20.5523 5 21 5.44772 21 6V9H19V7H11.5858L9.58579 5H4V16.998L5.5 11H22.5L20.1894 20.2425C20.0781 20.6877 19.6781 21 19.2192 21H3ZM19.9384 13H7.06155L5.56155 19H18.4384L19.9384 13Z',
        chart: 'M11 7H13V17H11V7ZM15 11H17V17H15V11ZM7 13H9V17H7V13ZM15 4H5V20H19V8H15V4ZM3 2.9918C3 2.44405 3.44749 2 3.9985 2H16L20.9997 7L21 20.9925C21 21.5489 20.5551 22 20.0066 22H3.9934C3.44476 22 3 21.5447 3 21.0082V2.9918Z',
        external: 'M10 6V8H5V19H16V14H18V20C18 20.5523 17.5523 21 17 21H4C3.44772 21 3 20.5523 3 20V7C3 6.44772 3.44772 6 4 6H10ZM21 3V11H19L18.9999 6.413L11.2071 14.2071L9.79289 12.7929L17.5849 5H13V3H21Z'
    ]
    String path = paths[name] ?: paths.folder
    return '<svg class="icon" aria-hidden="true" viewBox="0 0 24 24" fill="currentColor"><path d="' + path + '"></path></svg>'
}
def readProjectJson = { File file ->
    if (!file.isFile()) return [:]
    try { return configJson.fromJson(file.getText('UTF-8'), Map.class) ?: [:] }
    catch (Throwable ignored) { return [:] }
}
def asProjectInt = { value ->
    try { return value == null ? 0 : (value as Number).intValue() }
    catch (Throwable ignored) { return 0 }
}
def asProjectDecimal = { value, int digits ->
    try { return String.format(Locale.US, "%.${digits}f", (value as Number).doubleValue()) }
    catch (Throwable ignored) { return '0' }
}
def removeLegacyWorkflowHtml = {
    Set<String> oldNames = ['review.html', 'run_report.html', 'completion_report.html'] as Set
    ['START-HERE.html', 'PROJECT-README.txt', 'READ-ME-FIRST.md'].each { oldName ->
        try { Files.deleteIfExists(new File(workflowDir, oldName).toPath()) }
        catch (Throwable ignored) {}
    }
    [orientationQcDir, runBaseDir, legacyRunBaseDir].each { root ->
        if (!root.isDirectory()) return
        root.eachFileRecurse { file ->
            if (file.isFile() && (oldNames.contains(file.getName()) ||
                    file.getName() == 'LATEST_RUN_REPORT.txt')) {
                try { Files.deleteIfExists(file.toPath()) } catch (Throwable ignored) {}
            }
        }
    }
}
def writeProjectIndex = { File runDir = null ->
    File noCorePlaceholderFile = new File(orientationQcDir, 'no-core-placeholder.jpg')
    try {
        byte[] noCorePlaceholderBytes = Base64.getMimeDecoder().decode(
            NO_CORE_PLACEHOLDER_JPEG_BASE64)
        if (!noCorePlaceholderFile.isFile() ||
                noCorePlaceholderFile.length() != noCorePlaceholderBytes.length) {
            noCorePlaceholderFile.setBytes(noCorePlaceholderBytes)
        }
    } catch (Throwable placeholderError) {
        println "WARNING: Could not prepare the no-core placeholder: ${placeholderError.getMessage()}"
    }
    String gridLink = new File(gridQcDir, "${imageStem}_grid_qc_latest.png").isFile() ?
        "qc/01-grid/${imageStem}_grid_qc_latest.png" :
        "qc/01-grid/${imageStem}_grid_qc.png"
    boolean hasGridQc = new File(workflowDir, gridLink).isFile()
    File gridJsonFile = new File(gridQcDir, "${imageStem}_grid_qc_latest.json")
    File orientationJsonFile = new File(orientationQcDir, 'run_report.json')
    File completionJsonFile = new File(orientationQcDir, 'completion_report.json')
    def gridReport = readProjectJson(gridJsonFile)
    def orientationReport = readProjectJson(orientationJsonFile)
    def completionReport = readProjectJson(completionJsonFile)
    boolean hasOrientation = !orientationReport.isEmpty()
    boolean hasCompletion = !completionReport.isEmpty()
    boolean hasProject = new File(qupathProjectDir, 'project.qpproj').isFile()
    boolean hasContactSheet = new File(orientationQcDir, 'orientation_contact_sheet.png').isFile()
    boolean hasResultsCsv = new File(resultsTablesDir, 'orientation_results.csv').isFile()
    boolean hasReviewCsv = new File(resultsTablesDir, 'orientation_review_queue.csv').isFile()
    boolean hasPng = (resultsPngDir.listFiles()?.any { it.isFile() } ?: false)
    boolean hasOme = (resultsOmeDir.listFiles()?.any { it.isFile() } ?: false)
    boolean gridReviewPending = 'true'.equalsIgnoreCase(
        System.getProperty('corealign.dashboard.gridReviewPending', 'false'))
    def counts = orientationReport.counts instanceof Map ? orientationReport.counts : [:]
    def coreRecords = orientationReport.cores instanceof List ? orientationReport.cores : []
    int positionCount = asProjectInt(counts.positions)
    int okCountForPage = asProjectInt(counts.ok)
    int reviewCountForPage = asProjectInt(counts.needsReview ?: counts.review)
    int missingCountForPage = asProjectInt(counts.missing)
    int reusedCountForPage = asProjectInt(counts.resumedFromCheckpoint)
    int gridWidthForPage = asProjectInt(gridReport.gridWidth)
    int gridHeightForPage = asProjectInt(gridReport.gridHeight)
    int gridPresentForPage = asProjectInt(gridReport.present)
    int gridMissingForPage = asProjectInt(gridReport.missing)
    int gridCorrectedForPage = asProjectInt(gridReport.humanCorrectedTotal)
    int gridAutomaticForPage = Math.max(0, gridPresentForPage - gridCorrectedForPage)
    int gridReviewQueueForPage = asProjectInt(gridReport.reviewQueueCount)
    String orientationReviewKey = (orientationReport.startedAt ?: orientationReport.gridHash ?: 'pending').toString()
    Set<String> correctionCoreNames = coreRecords.collect {
        it?.core?.toString()?.trim()?.toLowerCase(Locale.ROOT)
    }.findAll { it } as Set<String>
    Map correctionBridge = configFile.isFile() ?
        CoreAlignCorrectionBridge.start(
            new File(workflowDir, 'corealign-review-corrections.json'),
            configFile, profileName, imageName, orientationReviewKey,
            correctionCoreNames) :
        [available: false, endpoint: '', openEndpoint: '', outputEndpoint: '']
    if (!configFile.isFile()) CoreAlignCorrectionBridge.stop()
    String correctionAutoSaveUrl = correctionBridge.endpoint?.toString() ?: ''
    String correctionOpenQuPathUrl = correctionBridge.openEndpoint?.toString() ?: ''
    String outputModeUrl = correctionBridge.outputEndpoint?.toString() ?: ''
    String currentOutputMode = orientationConfig.saveRotatedMultichannelOmeTiff == true ?
        'research' : 'presentation'
    if (!correctionAutoSaveUrl.isEmpty())
        println 'Orientation correction auto-save is ready beside REPORT.html.'
    int dashboardPositionCount = positionCount > 0 ? positionCount :
        (gridWidthForPage > 0 && gridHeightForPage > 0 ? gridWidthForPage * gridHeightForPage : 0)
    String currentStage = hasCompletion ? 'Complete and human approved' :
        gridReviewPending ? 'Grid ready for review' :
        hasOrientation ? 'Orientation ready for review' :
        hasGridQc ? 'Grid ready for review' : 'Ready to run'
    String stageTone = hasCompletion ? 'success' :
        (gridReviewPending || hasOrientation) ? 'warning' : 'neutral'
    String stageIcon = hasCompletion ? projectIcon('check') :
        (gridReviewPending || hasOrientation) ? projectIcon('warning') : projectIcon('microscope')
    String primaryTarget = hasCompletion ? 'results' : gridReviewPending ? 'grid' : hasOrientation ? 'orientation' :
        hasGridQc ? 'grid' : 'help'
    String primaryLabel = hasCompletion ? 'Open results' : gridReviewPending ?
        'Review grid QC' : hasOrientation ?
        'Review flagged cores' : hasGridQc ? 'Review grid QC' : 'How to run'
    String nextAction = hasCompletion ?
        'Use the prepared images or open the ordered QuPath project for analysis.' :
        gridReviewPending ?
            'Check every detected circle and missing position in QuPath, then run the same script again.' :
        hasOrientation ?
            'Check the flagged cores below. Confirm correct cores and edit only the wrong angles.' :
        hasGridQc ?
            'Check every detected circle and missing position in QuPath, then run the same script again.' :
            'Open the slide in QuPath and run CoreAlign.groovy. No configuration is required for automatic mode.'

    StringBuilder html = new StringBuilder(64000)
    html.append('''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><meta name="theme-color" content="#ffffff"><title>CoreAlign | Report</title><script>try{var savedTheme=localStorage.getItem("corealign-theme");if(savedTheme){document.documentElement.dataset.theme=savedTheme;}}catch(e){}</script><style>
:root{color-scheme:light;--bg:#ffffff;--surface:#f7f8ff;--surface-strong:#e9edff;--fg:#171842;--muted:#5b617b;--border:#dfe3f1;--accent:#4262ff;--accent-deep:#2e49d7;--accent-ink:#ffffff;--warning:#946200;--warning-bg:#fff4c5;--danger:#c83f58;--danger-bg:#ffe5e9;--success:#087c6c;--success-bg:#def8f2;--purple:#8055df;--purple-bg:#efe8ff;--cyan:#00a6d9;--cyan-bg:#dff7ff;--coral:#ec6972;--coral-bg:#ffe5e6;--yellow:#ffd02f;--yellow-bg:#fff6c9;--shadow:0 1px 2px rgb(41 53 119/.04),0 12px 34px rgb(41 53 119/.09);--radius-sm:10px;--radius-md:16px;--radius-lg:22px;--header:72px}
html[data-theme="dark"]{color-scheme:dark;--bg:#070b21;--surface:#0e1533;--surface-strong:#18234f;--fg:#f7f8ff;--muted:#aeb7d7;--border:#2b3762;--accent:#7188ff;--accent-deep:#a6b4ff;--accent-ink:#ffffff;--warning:#ffd56b;--warning-bg:#453913;--danger:#ff8195;--danger-bg:#48202d;--success:#65e0cc;--success-bg:#153b3c;--purple:#b69cff;--purple-bg:#31275c;--cyan:#55d7ff;--cyan-bg:#12374b;--coral:#ff8e91;--coral-bg:#4a2330;--yellow:#ffd75a;--yellow-bg:#473b14;--shadow:0 14px 40px rgb(0 0 0/.28)}
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--bg);color:var(--fg);font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;font-size:16px;line-height:1.55}.icon{width:20px;height:20px;flex:0 0 auto}.skip{position:fixed;left:16px;top:-80px;z-index:100;padding:12px 16px;border-radius:8px;background:var(--fg);color:var(--bg);font-weight:700}.skip:focus{top:12px}.topbar{position:sticky;top:0;z-index:40;min-height:var(--header);display:flex;align-items:center;gap:24px;padding:12px clamp(16px,4vw,48px);border-bottom:1px solid var(--border);background:color-mix(in oklch,var(--bg) 92%,transparent);backdrop-filter:blur(18px)}.brand{display:flex;align-items:center;gap:10px;min-width:max-content;font-size:17px;font-weight:750;letter-spacing:-.02em}.brand-mark{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;background:var(--fg);color:var(--bg)}.brand-mark .icon{width:19px;height:19px}.nav{display:flex;gap:4px;overflow-x:auto;scrollbar-width:none}.nav::-webkit-scrollbar{display:none}.crumb-separator{display:grid;place-items:center;color:var(--border)}.crumb-separator .icon{width:15px;height:15px}.nav button,.icon-button,.help-button{min-height:44px;border:1px solid transparent;border-radius:999px;background:transparent;color:var(--muted);font:inherit;font-size:14px;font-weight:650;cursor:pointer}.nav button{padding:8px 14px;white-space:nowrap}.nav button[aria-current="page"]{background:var(--surface-strong);color:var(--fg)}.header-actions{display:flex;align-items:center;gap:8px;margin-left:auto}.help-button{display:inline-flex;align-items:center;gap:7px;padding:8px 12px;border-color:var(--border);color:var(--fg)}.help-button[aria-current="page"]{background:var(--surface-strong)}.icon-button{display:grid;place-items:center;width:44px;padding:0;border-color:var(--border);color:var(--fg)}.moon-icon{display:none}html[data-theme="dark"] .sun-icon{display:none}html[data-theme="dark"] .moon-icon{display:block}.shell{width:min(100%,1280px);margin:auto;padding:clamp(24px,5vw,64px) clamp(16px,4vw,48px) 96px}.panel{display:none;animation:panel-in .22s ease-out}.panel.is-active{display:block}.hero{position:relative;overflow:hidden;display:grid;gap:32px;grid-template-columns:minmax(0,1.5fr) minmax(260px,.75fr);padding:clamp(28px,5vw,56px);border:1px solid var(--border);border-radius:24px;background:linear-gradient(135deg,var(--surface) 0%,var(--bg) 62%);box-shadow:var(--shadow)}.hero:after{content:"";position:absolute;right:-80px;top:-120px;width:320px;height:320px;border-radius:50%;background:radial-gradient(circle,var(--accent) 0%,transparent 68%);opacity:.14;pointer-events:none}.eyebrow,.status-badge{display:inline-flex;align-items:center;gap:8px;width:max-content;font-size:12px;font-weight:750;letter-spacing:.07em;text-transform:uppercase}.status-badge{padding:7px 11px;border-radius:999px}.status-badge.success{background:var(--success-bg);color:var(--success)}.status-badge.warning{background:var(--warning-bg);color:var(--warning)}.status-badge.neutral{background:var(--surface-strong);color:var(--muted)}.status-badge .icon{width:16px;height:16px}h1,h2,h3{margin:0;letter-spacing:-.025em;line-height:1.16}h1{margin-top:16px;font-size:clamp(2rem,1.35rem + 3vw,3.55rem);max-width:16ch}h2{font-size:clamp(1.55rem,1.3rem + 1vw,2.15rem)}h3{font-size:1.1rem}.lede{max-width:62ch;margin:16px 0 0;color:var(--muted);font-size:clamp(1rem,.96rem + .25vw,1.12rem)}.hero-side{position:relative;z-index:1;align-self:end;padding:24px;border-radius:16px;background:var(--fg);color:var(--bg)}.hero-side .eyebrow{color:var(--accent)}.hero-side p{margin:10px 0 20px;line-height:1.5}.primary,.secondary,.text-link,.control-button{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:44px;border-radius:999px;padding:10px 18px;font:inherit;font-size:14px;font-weight:750;text-decoration:none;cursor:pointer}.primary{border:1px solid var(--accent);background:var(--accent);color:var(--accent-ink)}.secondary,.control-button{border:1px solid var(--border);background:var(--bg);color:var(--fg)}.text-link{min-height:auto;padding:0;border:0;background:transparent;color:var(--fg)}.primary .icon,.secondary .icon,.text-link .icon,.control-button .icon{width:18px;height:18px}.section-head{display:flex;align-items:end;justify-content:space-between;gap:24px;margin:0 0 24px}.section-head p{max-width:65ch;margin:8px 0 0;color:var(--muted)}.section-block{margin-top:48px}.metric-grid,.file-grid,.grid-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,170px),1fr));gap:16px}.metric,.file-card,.notice{border:1px solid var(--border);border-radius:var(--radius-md);background:var(--bg)}.metric{padding:20px}.metric strong{display:block;font-size:clamp(1.65rem,1.4rem + 1vw,2.25rem);line-height:1.1}.metric span{display:block;margin-top:6px;color:var(--muted);font-size:14px}.file-card{display:flex;flex-direction:column;padding:24px}.step-number{display:grid;place-items:center;width:36px;height:36px;margin-bottom:20px;border-radius:10px;background:var(--surface-strong);color:var(--fg);font-weight:800}.file-card p{margin:8px 0 20px;color:var(--muted)}.file-card a{margin-top:auto;align-self:flex-start}.output-mode-control{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:10px 20px;padding:20px;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--surface)}.output-mode-copy{display:flex;flex-direction:column;gap:3px}.output-mode-copy span,.output-mode-status{color:var(--muted);font-size:14px}.mode-toggle{display:grid;grid-template-columns:1fr 1fr;min-width:260px;padding:4px;border:1px solid var(--border);border-radius:999px;background:var(--bg)}.mode-toggle button{min-height:40px;padding:8px 14px;border:0;border-radius:999px;background:transparent;color:var(--muted);font:inherit;font-size:14px;font-weight:750;cursor:pointer}.mode-toggle button[aria-pressed="true"]{background:var(--fg);color:var(--bg)}.mode-toggle button:disabled{cursor:wait;opacity:.65}.output-mode-status{grid-column:1/-1;margin:0}.notice{display:flex;gap:14px;padding:18px 20px}.notice .icon{color:var(--accent-deep);margin-top:2px}.notice p{margin:0;color:var(--muted)}.legend{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 16px}.legend span{display:inline-flex;align-items:center;gap:8px;padding:7px 11px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--muted);font-size:13px;font-weight:700}.legend i{width:10px;height:10px;border-radius:50%}.legend .auto i{background:rgb(0,235,230)}.legend .corrected i{background:rgb(80,240,125)}.legend .missing i{background:rgb(255,70,80)}.qc-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:12px}.zoom-controls{display:flex;align-items:center;gap:7px}.zoom-controls .control-button{min-width:44px;padding:8px 12px}.zoom-value{min-width:52px;text-align:center;color:var(--muted);font-size:13px;font-weight:750}.image-viewport{overflow:auto;max-height:72vh;border:1px solid var(--border);border-radius:var(--radius-lg);background:oklch(.12 0 0);cursor:grab;touch-action:pan-x pan-y}.image-viewport.is-panning{cursor:grabbing;user-select:none}.image-viewport img{display:block;width:100%;height:auto;max-width:none}.media-card{overflow:hidden;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--surface)}.media-card img{display:block;width:100%;height:auto;max-height:720px;object-fit:contain;background:oklch(.12 0 0)}.media-caption{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 20px}.media-caption p{margin:0;color:var(--muted)}.filter-tools{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.filter-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.filterbar{display:flex;align-items:center;gap:8px;overflow-x:auto;padding-bottom:4px}.filterbar button,.preview-toggle button{min-height:44px;padding:8px 15px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--muted);font:inherit;font-weight:700;cursor:pointer;white-space:nowrap}.filterbar button[data-filter="ok"]{border-color:var(--success);background:var(--success-bg);color:var(--success)}.filterbar button[data-filter="missing"]{border-color:var(--danger);background:var(--danger-bg);color:var(--danger)}.filterbar button[data-filter="review"]{border-color:var(--warning);background:var(--warning-bg);color:var(--warning)}.filterbar button[data-filter="changes"]{border-color:var(--purple);background:var(--purple-bg);color:var(--purple)}.filterbar button[aria-pressed="true"]{border-color:var(--fg);background:var(--fg);color:var(--bg)}.filterbar button[data-filter="ok"][aria-pressed="true"]{border-color:var(--success);background:var(--success);color:var(--bg)}.filterbar button[data-filter="missing"][aria-pressed="true"]{border-color:var(--danger);background:var(--danger);color:white}.filterbar button[data-filter="review"][aria-pressed="true"]{border-color:var(--warning);background:var(--warning);color:var(--accent-ink)}.filterbar button[data-filter="changes"][aria-pressed="true"]{border-color:var(--purple);background:var(--purple);color:white}.preview-toggle button[aria-pressed="true"]{border-color:var(--fg);background:var(--fg);color:var(--bg)}.confirm-all-pass{border-color:var(--success);background:var(--success);color:var(--bg)}.confirm-all-pass:disabled{cursor:default;opacity:.6}.core-search{min-height:44px;width:min(100%,250px);padding:9px 14px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--fg);font:inherit}.core-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,230px),1fr));gap:16px;margin-top:24px}.core-card{overflow:hidden;border:1px solid var(--border);border-top:4px solid var(--success);border-radius:var(--radius-md);background:var(--bg);box-shadow:var(--shadow)}.core-card[data-status="review"],.core-card[data-status="uncertain"]{border-top-color:var(--warning)}.core-card[data-status="missing"],.core-card[data-status="no_tissue"],.core-card[data-status="processing_error"],.core-card[data-status="export_error"]{border-top-color:var(--danger)}.core-card[data-confirmed="true"]{border-top-color:var(--success)}.core-card[data-has-change="true"]{border-top-color:var(--purple)}.core-card[hidden]{display:none}.core-image{display:block;aspect-ratio:1/1;background:oklch(.12 0 0)}.core-image img{display:block;width:100%;height:100%;object-fit:contain}.core-image img[hidden]{display:none}.core-placeholder{display:grid;place-items:center;width:100%;height:100%;color:oklch(.72 0 0)}.core-placeholder .icon{width:36px;height:36px}.core-body{padding:16px}.core-title{display:flex;align-items:center;justify-content:space-between;gap:8px}.core-title strong{font-size:16px}.core-status{font-size:11px;font-weight:800;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}.core-card[data-confirmed="true"] .core-status{color:var(--success)}.core-card[data-has-change="true"] .core-status{color:var(--purple)}.preview-toggle{display:flex;gap:6px;margin:12px 0 0}.preview-toggle button{min-height:34px;padding:5px 10px;font-size:12px}.core-meta{margin:10px 0 0;color:var(--muted);font-size:13px;line-height:1.55}.help-list{display:grid;gap:16px;counter-reset:help}.help-item{position:relative;padding:24px 24px 24px 72px;border-left:2px solid var(--border)}.help-item:before{counter-increment:help;content:counter(help);position:absolute;left:20px;top:22px;display:grid;place-items:center;width:32px;height:32px;border-radius:50%;background:var(--fg);color:var(--bg);font-weight:800}.help-item p{margin:8px 0 0;max-width:68ch;color:var(--muted)}.footer{margin-top:64px;padding-top:24px;border-top:1px solid var(--border);color:var(--muted);font-size:13px}.path{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;word-break:break-all}.empty{padding:32px;border:1px dashed var(--border);border-radius:var(--radius-md);color:var(--muted);text-align:center}.empty .icon{width:32px;height:32px;margin-bottom:8px}.saved-modal{width:min(calc(100% - 32px),420px);padding:0;border:1px solid var(--border);border-radius:20px;background:var(--bg);color:var(--fg);box-shadow:0 24px 80px rgb(0 0 0/.28)}.saved-modal::backdrop{background:rgb(5 15 12/.46);backdrop-filter:blur(3px)}.saved-modal-body{padding:28px;text-align:center}.saved-modal-mark{display:grid;place-items:center;width:52px;height:52px;margin:0 auto 16px;border-radius:50%;background:var(--success-bg);color:var(--success)}.saved-modal p{margin:10px 0 22px;color:var(--muted)}.nav button:hover,.help-button:hover,.icon-button:hover,.secondary:hover,.control-button:hover,.filterbar button:hover,.preview-toggle button:hover{background:var(--surface-strong);color:var(--fg)}.primary:hover{filter:brightness(.96);transform:translateY(-1px)}button:active,a:active{transform:translateY(1px)}button:focus-visible,a:focus-visible,input:focus-visible{outline:3px solid color-mix(in oklch,var(--accent-deep) 70%,white);outline-offset:3px}.panel,.core-card,.primary{transition:opacity .2s ease,transform .2s ease,background-color .2s ease,border-color .2s ease}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@keyframes panel-in{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
.core-image>a{display:block;width:100%;height:100%}.core-image>a[hidden]{display:none}
.image-viewport{height:min(72vh,820px);max-height:none}.image-viewport img{width:auto;margin:0 auto}
.search-control{position:relative;width:min(100%,380px)}.search-control .core-search{width:100%;min-height:48px;padding:10px 72px 10px 16px;font-size:16px}.search-clear{position:absolute;right:6px;top:50%;min-height:36px;padding:5px 12px;border:0;border-radius:999px;background:var(--surface-strong);color:var(--fg);font:inherit;font-size:13px;font-weight:750;cursor:pointer;transform:translateY(-50%)}.search-clear[hidden]{display:none}.core-image{overflow:hidden;cursor:default}.core-image>a{min-width:100%;min-height:100%}.core-image img{transition:transform .15s ease}.card-actions,.card-badges,.card-action-buttons{display:flex;align-items:center;gap:8px}.card-actions{justify-content:space-between;gap:10px;margin-top:12px}.card-action-buttons{justify-content:flex-end}.edit-button,.confirm-button{min-height:38px;padding:6px 14px}.confirm-button{border-color:var(--accent);background:var(--accent);color:var(--accent-ink)}.change-badge,.confirmed-badge{display:none;padding:5px 9px;border-radius:999px;font-size:11px;font-weight:800;letter-spacing:.05em;text-transform:uppercase}.change-badge{background:var(--purple-bg);color:var(--purple)}.confirmed-badge{background:var(--success-bg);color:var(--success)}.core-card[data-has-change="true"]{border-color:var(--purple)}.core-card[data-has-change="true"] .change-badge,.core-card[data-confirmed="true"] .confirmed-badge{display:inline-flex}.core-card[data-confirmed="true"] .confirm-button{border-color:var(--border);background:var(--surface-strong);color:var(--muted)}.edit-panel{display:none;gap:10px;margin-top:14px;padding-top:14px;border-top:1px solid var(--border)}.core-card.is-editing .edit-panel{display:grid}.core-card.is-editing .card-action-buttons{display:none}.rotation-editor label{display:flex;align-items:center;justify-content:space-between;gap:10px;font-size:13px;font-weight:750}.rotation-editor input{width:100%;accent-color:var(--purple)}.edit-actions{display:flex;justify-content:flex-end;gap:7px;flex-wrap:wrap}.edit-actions .control-button{min-height:38px;padding:6px 13px}.edit-actions [data-edit-confirm]{border-color:var(--purple);background:var(--purple);color:white}.change-bar{position:sticky;z-index:20;bottom:16px;display:flex;align-items:center;justify-content:space-between;gap:16px;margin:28px auto 0;padding:14px 16px;width:min(100%,780px);border:1px solid var(--purple);border-radius:16px;background:var(--bg);box-shadow:0 16px 42px rgb(13 34 29/.18)}.change-bar[hidden]{display:none}.change-copy{display:grid;gap:2px}.change-bar strong{font-size:14px}.change-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.autosave-status{color:var(--success);font-size:13px;font-weight:750}.autosave-status[data-state="saving"]{color:var(--muted)}.autosave-status[data-state="error"]{color:var(--danger)}
body{background-image:radial-gradient(circle at 5% 12%,var(--yellow-bg) 0,transparent 18rem),radial-gradient(circle at 95% 74%,var(--cyan-bg) 0,transparent 22rem);background-attachment:fixed}.topbar{border-bottom-color:color-mix(in srgb,var(--accent) 20%,var(--border));box-shadow:0 5px 22px rgb(48 67 154/.06)}.brand-mark{background:var(--accent);color:white;box-shadow:0 8px 20px color-mix(in srgb,var(--accent) 30%,transparent)}.crumb-separator{color:color-mix(in srgb,var(--accent) 46%,var(--border))}.nav button[aria-current="page"]{background:var(--accent);color:white}.help-button{background:var(--surface)}.hero{border-radius:28px;background:linear-gradient(125deg,var(--surface) 0%,var(--cyan-bg) 58%,var(--yellow-bg) 100%);box-shadow:var(--shadow)}.hero:after{background:radial-gradient(circle,var(--coral) 0%,transparent 68%);opacity:.18}.hero-side{background:linear-gradient(145deg,#253fd2,var(--accent) 55%,var(--cyan));color:white;box-shadow:0 18px 45px color-mix(in srgb,var(--accent) 26%,transparent)}.hero-side .eyebrow{color:var(--yellow)}.primary{box-shadow:0 8px 20px color-mix(in srgb,var(--accent) 23%,transparent)}.metric{position:relative;overflow:hidden;border-top:4px solid var(--accent);background:var(--bg);box-shadow:var(--shadow)}.metric:nth-child(4n+2){border-top-color:var(--success)}.metric:nth-child(4n+3){border-top-color:var(--warning)}.metric:nth-child(4n+4){border-top-color:var(--coral)}.file-card{border-top:4px solid var(--accent);background:var(--bg);box-shadow:var(--shadow)}.file-card:nth-child(4n+2){border-top-color:var(--success)}.file-card:nth-child(4n+3){border-top-color:var(--warning)}.file-card:nth-child(4n+4){border-top-color:var(--purple)}.step-number{background:var(--accent);color:white}.output-mode-control{border-color:color-mix(in srgb,var(--accent) 35%,var(--border));background:linear-gradient(115deg,var(--surface),var(--cyan-bg))}.mode-toggle button[aria-pressed="true"]{background:var(--accent);color:white;box-shadow:0 5px 14px color-mix(in srgb,var(--accent) 28%,transparent)}.notice{background:var(--cyan-bg)}.media-card,.image-viewport{box-shadow:var(--shadow)}.core-card{background:var(--bg)}.core-body{background:linear-gradient(180deg,var(--bg),var(--surface))}.help-item{border:1px solid var(--border);border-left:5px solid var(--accent);border-radius:var(--radius-md);background:var(--bg);box-shadow:var(--shadow)}.help-item:nth-child(2){border-left-color:var(--coral)}.help-item:nth-child(3){border-left-color:var(--success)}.help-item:nth-child(4){border-left-color:var(--purple)}.help-item:before{background:var(--accent);color:white}.help-item:nth-child(2):before{background:var(--coral)}.help-item:nth-child(3):before{background:var(--success)}.help-item:nth-child(4):before{background:var(--purple)}.saved-modal{background:var(--bg)}.change-bar{border-color:var(--purple);background:color-mix(in srgb,var(--bg) 94%,transparent);backdrop-filter:blur(18px)}
@media(max-width:980px){.topbar{align-items:flex-start;flex-wrap:wrap;gap:8px}.nav{order:3;width:100%}.header-actions{margin-left:auto}.help-button span{display:none}.hero{grid-template-columns:1fr}.hero-side{max-width:none}.section-head{align-items:flex-start;flex-direction:column}}
@media(max-width:600px){:root{--header:116px}.brand-name{display:none}.shell{padding-top:24px}.topbar{padding-inline:12px}.nav button{padding-inline:12px}.hero{padding:24px;border-radius:16px}.metric-grid,.grid-summary{grid-template-columns:repeat(2,1fr)}.metric{padding:16px}.media-caption,.filter-tools,.change-bar{align-items:stretch;flex-direction:column}.filter-actions,.core-search,.change-actions,.change-actions button{width:100%}.output-mode-control{grid-template-columns:1fr}.mode-toggle{min-width:0;width:100%}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation-duration:.01ms!important;animation-iteration-count:1!important;transition-duration:.01ms!important;scroll-behavior:auto!important}}
</style></head><body><a class="skip" href="#main">Skip to content</a><header class="topbar"><div class="brand"><span class="brand-mark">''')
    html.append(projectIcon('microscope')).append('</span><span class="brand-name">CoreAlign</span></div>')
    html.append('''<nav class="nav" aria-label="Project sections"><button type="button" data-nav="overview" aria-current="page">Overview</button><span class="crumb-separator" aria-hidden="true">''')
    html.append(projectIcon('forward')).append('</span><button type="button" data-nav="grid">Grid QC</button><span class="crumb-separator" aria-hidden="true">')
    html.append(projectIcon('forward')).append('</span><button type="button" data-nav="orientation">Orientation QC</button><span class="crumb-separator" aria-hidden="true">')
    html.append(projectIcon('forward')).append('</span><button type="button" data-nav="results">Results</button></nav><div class="header-actions"><button class="help-button" type="button" data-nav="help">')
    html.append(projectIcon('help')).append('<span>Help</span></button><button class="icon-button" type="button" id="themeToggle" aria-label="Switch color theme" title="Switch color theme"><span class="moon-icon">')
    html.append(projectIcon('moon')).append('</span><span class="sun-icon">').append(projectIcon('sun')).append('</span></button></div></header>')
    html.append('<main class="shell" id="main" tabindex="-1"><section class="panel is-active" id="overview" data-panel><div class="hero"><div><div class="status-badge ')
        .append(stageTone).append('">').append(stageIcon).append(projectHtmlEscape(currentStage)).append('</div><h1>CoreAlign quality-control report</h1><p class="lede">')
        .append(projectHtmlEscape(imageName)).append(' has one comprehensive view of detection, orientation changes, and available results.</p></div><aside class="hero-side"><div class="eyebrow">Current action</div><p>')
        .append(projectHtmlEscape(nextAction)).append('</p><button class="primary" type="button" data-nav="')
        .append(primaryTarget).append('">').append(projectHtmlEscape(primaryLabel)).append(projectIcon('forward')).append('</button></aside></div>')
    html.append('<div class="section-block metric-grid">')
    [[dashboardPositionCount, 'TMA positions'], [okCountForPage, 'Automatic QC pass'],
     [reviewCountForPage, 'Needs review'], [missingCountForPage, 'Missing'],
     [reusedCountForPage, 'Reused checkpoints']].each { metric ->
        html.append('<div class="metric"><strong>').append(metric[0]).append('</strong><span>')
            .append(projectHtmlEscape(metric[1])).append('</span></div>')
    }
    html.append('</div><div class="section-block notice">').append(projectIcon('check'))
        .append('<div><strong>Comprehensive project report</strong><p>Use the sections above to inspect current QC evidence and available outputs. Technical PNG, CSV, JSON, OME-TIFF, and checkpoint files remain linked to this report.</p></div></div>')
    html.append('</section>')

    html.append('<section class="panel" id="grid" data-panel><div class="section-head"><div><div class="eyebrow">Detection report</div><h2>Grid QC</h2><p>Confirm that every circle covers one core and that row-column assignments follow the slide.</p></div>')
    if (hasGridQc) html.append('<a class="secondary" href="').append(projectHtmlEscape(gridLink)).append('" target="_blank" rel="noopener">Open full image ').append(projectIcon('external')).append('</a>')
    html.append('</div>')
    if (hasGridQc) {
        html.append('<div class="grid-summary">')
        [[gridWidthForPage > 0 && gridHeightForPage > 0 ? "${gridHeightForPage} x ${gridWidthForPage}" : 'Available', 'Rows x columns'],
         [gridPresentForPage, 'Present'], [gridMissingForPage, 'Missing'],
         [gridCorrectedForPage, 'Human corrected'], [gridReviewQueueForPage, 'Review queue']].each { metric ->
            html.append('<div class="metric"><strong>').append(projectHtmlEscape(metric[0])).append('</strong><span>')
                .append(projectHtmlEscape(metric[1])).append('</span></div>')
        }
        html.append('</div><div class="section-block"><div class="qc-toolbar"><div class="legend" aria-label="Grid annotation legend"><span class="auto"><i></i>Automatic ')
            .append(gridAutomaticForPage).append('</span><span class="corrected"><i></i>Human corrected ')
            .append(gridCorrectedForPage).append('</span><span class="missing"><i></i>Missing ')
            .append(gridMissingForPage).append('</span></div><div class="zoom-controls" aria-label="Grid image controls"><button class="control-button" type="button" id="gridZoomOut" aria-label="Zoom out">-</button><span class="zoom-value" id="gridZoomValue">Fit</span><button class="control-button" type="button" id="gridZoomIn" aria-label="Zoom in">+</button><button class="control-button" type="button" id="gridZoomReset">Reset</button></div></div><div class="image-viewport" id="gridViewport"><img id="gridImage" src="')
            .append(projectHtmlEscape(gridLink)).append('" alt="Latest whole-slide TMA grid quality-control image without overlay text"></div><div class="media-caption"><p>Zoom with the controls, then drag the image to inspect every row. Labels and counts stay outside the TMA area.</p></div></div>')
    } else {
        html.append('<div class="empty">').append(projectIcon('image')).append('<h3>No grid QC yet</h3><p>Run CoreAlign.groovy in QuPath to create the first detection overview.</p></div>')
    }
    html.append('<div class="section-block notice">').append(projectIcon('warning')).append('<div><strong>After correcting circles</strong><p>Draw or adjust TMA correction annotations in QuPath, then run the same script again. CoreAlign refreshes this overview and keeps the accepted grid state.</p></div></div>')
    html.append('</section>')

    html.append('<section class="panel" id="orientation" data-panel data-review-key="').append(projectHtmlEscape(orientationReviewKey)).append('" data-image-name="').append(projectHtmlEscape(imageName)).append('" data-auto-save-url="').append(projectHtmlEscape(correctionAutoSaveUrl)).append('" data-open-qupath-url="').append(projectHtmlEscape(correctionOpenQuPathUrl)).append('"><div class="section-head"><div><div class="eyebrow">Rotation report</div><h2>Orientation QC</h2><p>Confirm correct rotations. Edit only the wrong ones.</p></div>')
    if (hasContactSheet) html.append('<a class="secondary" href="qc/02-orientation/orientation_contact_sheet.png" target="_blank" rel="noopener">Contact sheet ').append(projectIcon('external')).append('</a>')
    html.append('</div>')
    html.append('<div class="notice">').append(projectIcon('warning')).append('<div><strong>Confirm or edit</strong><p>Correct? Click Confirm. Wrong? Click Edit, set the angle, then Confirm. Changes save automatically. Open QuPath and run CoreAlign again.</p></div></div>')
    if (hasOrientation && !coreRecords.isEmpty()) {
        html.append('<div class="filter-tools section-block"><div class="filter-actions"><div class="filterbar" role="group" aria-label="Filter core review cards"><button type="button" data-filter="all" aria-pressed="')
            .append(reviewCountForPage > 0 ? 'false' : 'true').append('">All cores</button><button type="button" data-filter="ok" aria-pressed="false">QC pass</button><button type="button" data-filter="missing" aria-pressed="false">Missing</button><button type="button" data-filter="review" aria-pressed="')
            .append(reviewCountForPage > 0 ? 'true' : 'false').append('">Needs review</button><button type="button" data-filter="changes" aria-pressed="false">Changes</button></div><button class="control-button confirm-all-pass" type="button" id="confirmAllPass">Confirm all QC pass</button></div><label class="search-control"><span class="sr-only">Find a core by row and column</span><input class="core-search" id="coreSearch" type="search" placeholder="Find core, for example 4-C"><button class="search-clear" type="button" id="coreSearchClear" hidden>Clear</button></label></div><div class="core-grid" id="coreGrid">')
        coreRecords.each { record ->
            String status = (record.status ?: 'unknown').toString()
            String filterStatus = status in ['review', 'uncertain'] ? 'review' :
                status in ['missing', 'no_tissue', 'processing_error', 'export_error'] ? 'missing' : 'ok'
            String displayStatus = filterStatus == 'review' ? 'Needs review' :
                filterStatus == 'missing' ? 'Missing' : 'QC pass'
            String preview = record.rotatedPreview?.toString() ?: ''
            String sourcePreview = record.unrotatedPreview?.toString() ?: ''
            boolean previewAvailable = !preview.isEmpty() &&
                new File(workflowDir, preview).isFile()
            boolean sourcePreviewAvailable = !sourcePreview.isEmpty() &&
                new File(workflowDir, sourcePreview).isFile()
            String coreNameForSearch = record.core?.toString() ?: ''
            html.append('<article class="core-card" data-status="').append(projectHtmlEscape(status))
                .append('" data-original-status="').append(displayStatus).append('" data-filter-status="').append(filterStatus).append('" data-core-name="')
                .append(projectHtmlEscape(coreNameForSearch.toLowerCase())).append('" data-has-change="false" data-confirmed="false" data-applied-adjustment="')
                .append(asProjectDecimal(record.webRotationAdjustmentDeg, 1)).append('"')
            boolean initiallyHidden = reviewCountForPage > 0 ? filterStatus != 'review' : false
            if (initiallyHidden) html.append(' hidden')
            html.append('>')
            if (previewAvailable) {
                html.append('<div class="core-image"><a data-preview="rotated" href="').append(projectHtmlEscape(preview)).append('" target="_blank" rel="noopener"><img loading="lazy" src="')
                    .append(projectHtmlEscape(preview)).append('" alt="Rotated preview for TMA core ').append(projectHtmlEscape(record.core)).append('"></a>')
                if (sourcePreviewAvailable) html.append('<a data-preview="source" href="').append(projectHtmlEscape(sourcePreview)).append('" target="_blank" rel="noopener" hidden><img loading="lazy" src="')
                    .append(projectHtmlEscape(sourcePreview)).append('" alt="Before rotation preview for TMA core ').append(projectHtmlEscape(record.core)).append('"></a>')
                html.append('</div>')
            } else if (noCorePlaceholderFile.isFile()) {
                html.append('<div class="core-image"><a data-preview="missing" href="qc/02-orientation/no-core-placeholder.jpg" target="_blank" rel="noopener"><img loading="lazy" src="qc/02-orientation/no-core-placeholder.jpg" alt="Synthetic empty placeholder for missing TMA core ')
                    .append(projectHtmlEscape(record.core)).append('"></a></div>')
            } else {
                html.append('<div class="core-image"><div class="core-placeholder">').append(projectIcon('image')).append('<span class="sr-only">No core image available</span></div></div>')
            }
            html.append('<div class="core-body"><div class="core-title"><strong>').append(projectHtmlEscape(record.core))
                .append('</strong><span class="core-status">').append(displayStatus).append('</span></div>')
            if (previewAvailable && sourcePreviewAvailable) html.append('<div class="preview-toggle" role="group" aria-label="Preview for core ').append(projectHtmlEscape(record.core)).append('"><button type="button" data-core-view="rotated" aria-pressed="true">Rotated</button><button type="button" data-core-view="source" aria-pressed="false">Before</button></div>')
            html.append('<p class="core-meta">Confidence ')
                .append(asProjectDecimal(record.confidence, 3)).append('<br>Rotate ')
                .append(asProjectDecimal(record.rotateToTopDeg, 1)).append(' deg. Residual ')
                .append(asProjectDecimal(record.postRotationResidualDeg, 1)).append(' deg.<br>Region ')
                .append(projectHtmlEscape(record.regionStatus ?: 'unknown'))
            if (record.reasons instanceof List && !record.reasons.isEmpty())
                html.append('<br>').append(projectHtmlEscape(record.reasons.join(', ')))
            html.append('</p>')
            if (previewAvailable && filterStatus != 'missing') {
                html.append('<div class="card-actions"><div class="card-badges"><span class="change-badge">Changed</span><span class="confirmed-badge">Confirmed</span></div><div class="card-action-buttons"><button class="control-button confirm-button" type="button" data-card-confirm>Confirm</button><button class="control-button edit-button" type="button" data-edit>Edit</button></div></div><div class="edit-panel"><div class="rotation-editor"><label>Rotation <output data-rotation-value>0 deg</output></label><input type="range" min="-180" max="180" step="1" value="0" data-rotation-adjust aria-label="Rotation adjustment for core ')
                    .append(projectHtmlEscape(record.core)).append('"></div><div class="edit-actions"><button class="control-button" type="button" data-edit-reset>Reset</button><button class="control-button" type="button" data-edit-cancel>Cancel</button><button class="control-button" type="button" data-edit-confirm>Confirm</button></div></div>')
            }
            html.append('</div></article>')
        }
        html.append('</div><div class="change-bar" id="changeBar" hidden><div class="change-copy"><strong id="changeCount">0 angle changes</strong><span class="autosave-status" id="autosaveStatus" data-state="ready">Saved. Go to QuPath and run CoreAlign again.</span></div><div class="change-actions"><button class="primary" type="button" id="openQuPath">Open QuPath</button><button class="primary" type="button" id="downloadChanges" hidden>Save changes</button></div></div>')
    } else if (hasContactSheet) {
        html.append('<figure class="media-card"><a href="qc/02-orientation/orientation_contact_sheet.png" target="_blank" rel="noopener"><img src="qc/02-orientation/orientation_contact_sheet.png" alt="TMA orientation contact sheet"></a><figcaption class="media-caption"><p>The contact sheet is available. Run the updated CoreAlign once to add interactive per-core filters here.</p></figcaption></figure>')
    } else {
        html.append('<div class="empty">').append(projectIcon('image')).append('<h3>No orientation results yet</h3><p>Approve the grid and run CoreAlign.groovy again.</p></div>')
    }
    html.append('</section>')

    html.append('<section class="panel" id="results" data-panel data-output-mode="')
        .append(currentOutputMode).append('" data-output-mode-url="')
        .append(projectHtmlEscape(outputModeUrl)).append('"><div class="section-head"><div><div class="eyebrow">Output report</div><h2>Results</h2><p>Choose files by purpose. PNG is for presentation. Multichannel OME-TIFF and the QuPath project are for research analysis.</p></div></div>')
    html.append('<div class="output-mode-control"><div class="output-mode-copy"><strong>Output package</strong><span>Switch to Research, then run CoreAlign again to create multichannel OME-TIFF files.</span></div><div class="mode-toggle" role="group" aria-label="Output package"><button type="button" data-output-mode-choice="presentation" aria-pressed="')
        .append(currentOutputMode == 'presentation' ? 'true' : 'false')
        .append('">Presentation</button><button type="button" data-output-mode-choice="research" aria-pressed="')
        .append(currentOutputMode == 'research' ? 'true' : 'false')
        .append('">Research</button></div><p class="output-mode-status" id="outputModeStatus">')
        .append(currentOutputMode == 'research' ?
            'Research mode is active.' : 'Presentation mode is active.')
        .append('</p></div><div class="file-grid section-block">')
    [[hasPng, 'Presentation PNG', 'Rotated and cropped color images ready for slides.', 'results/png/', 'folder'],
     [hasOme, 'Research OME-TIFF', 'Original-quality multichannel images using the accepted transform.', 'results/ome-tiff/', 'folder'],
     [hasResultsCsv, 'Results table', 'Per-core angles, confidence, status, and output paths.', 'results/tables/orientation_results.csv', 'chart'],
     [hasReviewCsv, 'Review queue', 'Only the cores that require focused human review.', 'results/tables/orientation_review_queue.csv', 'chart'],
     [hasProject, 'QuPath project', 'Ordered, analysis-ready core entries after final approval.', 'qupath/project.qpproj', 'microscope']].each { fileCard ->
        html.append('<article class="file-card"><div class="step-number">').append(projectIcon(fileCard[4].toString())).append('</div><h3>')
            .append(projectHtmlEscape(fileCard[1])).append('</h3><p>').append(projectHtmlEscape(fileCard[2])).append('</p>')
        if (fileCard[0]) html.append('<a class="text-link" href="').append(projectHtmlEscape(fileCard[3])).append('" target="_blank" rel="noopener">Open ').append(projectIcon('external')).append('</a>')
        else html.append('<span class="core-status">Not created yet</span>')
        html.append('</article>')
    }
    html.append('</div><div class="section-block notice">').append(projectIcon('folder')).append('<div><strong>Keep the work folder</strong><p>It contains resumable checkpoints. Do not delete it when changing from a presentation package to a research package.</p></div></div></section>')

    html.append('<section class="panel" id="help" data-panel><div class="section-head"><div><div class="eyebrow">Quick guide</div><h2>Run, review, continue</h2><p>The workflow always uses the same script and the same REPORT.html dashboard.</p></div></div><div class="help-list"><article class="help-item"><h3>Open the slide</h3><p>Keep the slide, CoreAlign.groovy, and optional corealign.config.json in this project folder. Open the slide in QuPath.</p></article><article class="help-item"><h3>Run CoreAlign.groovy</h3><p>Detection, rotation, cropping, exports, and safe resume are handled automatically. Stop points are intentional human review gates.</p></article><article class="help-item"><h3>Use this dashboard</h3><p>Open REPORT.html after every run. Use the menu at the top. Images open in a new tab, so this report stays available.</p></article><article class="help-item"><h3>Correct only what changed</h3><p>Use Edit for rotation. Use QuPath annotations for detection or crop corrections. Then run the same script again. Accepted cores are reused.</p></article></div><div class="section-block notice">').append(projectIcon('check')).append('<div><strong>No report filenames to remember</strong><p>REPORT.html is the only workflow HTML. Machine-readable audit files stay in JSON and CSV format.</p></div></div></section>')
    html.append('<dialog class="saved-modal" id="savedModal" aria-labelledby="savedModalTitle"><div class="saved-modal-body"><div class="saved-modal-mark">').append(projectIcon('check')).append('</div><h3 id="savedModalTitle">Saved</h3><p id="savedModalText">Your changes are saved beside REPORT.html.</p><button class="primary" type="button" id="savedModalClose">Continue</button></div></dialog>')
    html.append('<footer class="footer"><p>Project folder: <span class="path">').append(projectHtmlEscape(workflowDir.getAbsolutePath())).append('</span></p>')
    if (runDir != null) html.append('<p>Latest internal run: <span class="path">').append(projectHtmlEscape(runDir.getAbsolutePath())).append('</span></p>')
    html.append('<p>CoreAlign keeps the current quality-control evidence, review status, and result links together in this project report.</p></footer></main>')
    html.append('''<script>(function(){
var order=["overview","grid","orientation","results","help"];
function showPanel(id,push){if(order.indexOf(id)<0){id="overview";}document.querySelectorAll("[data-panel]").forEach(function(panel){panel.classList.toggle("is-active",panel.id===id);});document.querySelectorAll("[data-nav]").forEach(function(button){if(button.dataset.nav===id){button.setAttribute("aria-current","page");}else{button.removeAttribute("aria-current");}});if(push){history.pushState({section:id},"","#"+id);}document.getElementById("main").focus({preventScroll:true});window.scrollTo({top:0,behavior:"smooth"});if(id==="grid"){setTimeout(fitGridImage,0);}}
document.querySelectorAll("[data-nav]").forEach(function(button){button.addEventListener("click",function(){showPanel(button.dataset.nav,true);});});
window.addEventListener("popstate",function(event){showPanel(event.state&&event.state.section?event.state.section:(location.hash.slice(1)||"overview"),false);});var initial=location.hash.slice(1)||"overview";history.replaceState({section:initial},"",location.hash||"#"+initial);showPanel(initial,false);
var pressedFilter=document.querySelector("[data-filter][aria-pressed=true]"),activeCoreFilter=pressedFilter?pressedFilter.dataset.filter:"all",coreSearch=document.getElementById("coreSearch"),coreSearchClear=document.getElementById("coreSearchClear");
function applyCoreFilters(){var query=coreSearch?coreSearch.value.trim().toLowerCase():"";document.querySelectorAll(".core-card").forEach(function(card){var confirmed=card.dataset.confirmed==="true",statusOk=activeCoreFilter==="all"||(activeCoreFilter==="changes"?card.dataset.hasChange==="true":activeCoreFilter==="review"?card.dataset.filterStatus==="review"&&!confirmed:activeCoreFilter==="ok"?card.dataset.filterStatus==="ok"||(card.dataset.filterStatus==="review"&&confirmed):card.dataset.filterStatus===activeCoreFilter),nameOk=!query||(card.dataset.coreName||"").indexOf(query)>=0;card.hidden=!(statusOk&&nameOk);});}
function updateSearchClear(){if(coreSearchClear){coreSearchClear.hidden=!(coreSearch&&coreSearch.value.length>0);}}
document.querySelectorAll("[data-filter]").forEach(function(button){button.addEventListener("click",function(){activeCoreFilter=button.dataset.filter;document.querySelectorAll("[data-filter]").forEach(function(other){other.setAttribute("aria-pressed",other===button?"true":"false");});applyCoreFilters();});});if(coreSearch){coreSearch.addEventListener("input",function(){updateSearchClear();applyCoreFilters();});}if(coreSearchClear){coreSearchClear.addEventListener("click",function(){coreSearch.value="";updateSearchClear();applyCoreFilters();coreSearch.focus();});}updateSearchClear();
document.querySelectorAll("[data-core-view]").forEach(function(button){button.addEventListener("click",function(){var card=button.closest(".core-card"),view=button.dataset.coreView;card.querySelectorAll("[data-core-view]").forEach(function(other){other.setAttribute("aria-pressed",other===button?"true":"false");});card.querySelectorAll("[data-preview]").forEach(function(preview){preview.hidden=preview.dataset.preview!==view;});});});
var orientationSection=document.getElementById("orientation"),correctionAutoSaveUrl=orientationSection?orientationSection.dataset.autoSaveUrl||"":"",correctionOpenQuPathUrl=orientationSection?orientationSection.dataset.openQupathUrl||"":"",reviewStorageKey="corealign-rotation-edits:"+location.pathname+":"+(orientationSection?orientationSection.dataset.reviewKey:"pending"),reviewState={angles:{},confirmed:{}};try{var savedReview=JSON.parse(localStorage.getItem(reviewStorageKey)||"{}");reviewState.angles=savedReview.angles||{};reviewState.confirmed=savedReview.confirmed||{};}catch(e){reviewState={angles:{},confirmed:{}};}
function saveReviewState(){try{localStorage.setItem(reviewStorageKey,JSON.stringify(reviewState));}catch(e){}}
function showRotationPreview(card,value){var applied=Number(card.dataset.appliedAdjustment||0),angle=Math.max(-180,Math.min(180,Math.round(Number(value)||0))),delta=angle-applied,input=card.querySelector("[data-rotation-adjust]"),output=card.querySelector("[data-rotation-value]"),image=card.querySelector('[data-preview="rotated"] img');card.dataset.draftRotation=String(angle);if(input){input.value=String(angle);}if(output){output.textContent=(angle>0?"+":"")+angle+" deg";}if(image){image.style.transform="rotate("+delta+"deg)";}}
var autoSaveTimer=0,autoSaveModalPending=false,autoSaveStatus=document.getElementById("autosaveStatus"),downloadChanges=document.getElementById("downloadChanges"),openQuPath=document.getElementById("openQuPath"),confirmAllPass=document.getElementById("confirmAllPass"),savedModal=document.getElementById("savedModal"),savedModalTitle=document.getElementById("savedModalTitle"),savedModalText=document.getElementById("savedModalText"),savedModalClose=document.getElementById("savedModalClose");
function showSavedModal(title,message){if(!savedModal){return;}if(savedModalTitle){savedModalTitle.textContent=title;}if(savedModalText){savedModalText.textContent=message;}if(typeof savedModal.showModal==="function"){if(!savedModal.open){savedModal.showModal();}}else{savedModal.setAttribute("open","");}}
function closeSavedModal(){if(!savedModal){return;}if(typeof savedModal.close==="function"&&savedModal.open){savedModal.close();}else{savedModal.removeAttribute("open");}}
if(savedModalClose){savedModalClose.addEventListener("click",closeSavedModal);}
function setAutoSaveStatus(message,state,showFallback){if(autoSaveStatus){autoSaveStatus.textContent=message;autoSaveStatus.dataset.state=state||"ready";}if(downloadChanges){downloadChanges.hidden=!showFallback;}}
function updateChangeBar(){var count=document.querySelectorAll('.core-card[data-has-change="true"]').length,confirmedCount=document.querySelectorAll('.core-card[data-confirmed="true"]').length,hasReview=count>0||confirmedCount>0,bar=document.getElementById("changeBar"),label=document.getElementById("changeCount");if(bar){bar.hidden=!hasReview;}if(label){label.textContent=count>0?count+" angle change"+(count===1?"":"s"):"Review saved";}if(openQuPath){openQuPath.hidden=!correctionOpenQuPathUrl;}if(hasReview&&autoSaveStatus&&autoSaveStatus.dataset.state==="ready"){setAutoSaveStatus(count>0?(correctionAutoSaveUrl?"Saved. Go to QuPath and run CoreAlign again.":"Open QuPath and run CoreAlign once to enable auto-save."):"Go to QuPath and run CoreAlign again.",count>0&&!correctionAutoSaveUrl?"error":"ready",count>0&&!correctionAutoSaveUrl);}}
function refreshCardStatus(card){var status=card.querySelector(".core-status");if(status){status.textContent=card.dataset.hasChange==="true"?"Changes":card.dataset.confirmed==="true"?"QC pass":card.dataset.originalStatus||"Unknown";}}
function updateCardChange(card){var applied=Number(card.dataset.appliedAdjustment||0),committed=Number(card.dataset.manualRotation||0),changed=Math.abs(committed-applied)>=.05;card.dataset.hasChange=changed?"true":"false";refreshCardStatus(card);updateChangeBar();}
function setCardConfirmed(card,confirmed){var button=card.querySelector("[data-card-confirm]");card.dataset.confirmed=confirmed?"true":"false";if(button){button.textContent=confirmed?"Undo":"Confirm";button.setAttribute("aria-pressed",confirmed?"true":"false");button.setAttribute("aria-label",confirmed?"Undo confirmation":"Confirm this core");}refreshCardStatus(card);}
function updateConfirmAllPass(){if(!confirmAllPass){return;}var remaining=Array.from(document.querySelectorAll('.core-card[data-filter-status="ok"]')).filter(function(card){return card.dataset.confirmed!=="true";}).length;confirmAllPass.disabled=remaining===0;confirmAllPass.textContent=remaining===0?"QC pass confirmed":"Confirm all QC pass";}
function undoCard(card,key){var applied=Number(card.dataset.appliedAdjustment||0),hadChange=card.dataset.hasChange==="true";card.dataset.manualRotation=String(applied);showRotationPreview(card,applied);delete reviewState.angles[key];delete reviewState.confirmed[key];setCardConfirmed(card,false);saveReviewState();updateCardChange(card);updateConfirmAllPass();applyCoreFilters();if(hadChange){queueAutoSaveCorrections(true);}}
document.querySelectorAll(".core-card").forEach(function(card){var key=card.dataset.coreName||"",applied=Number(card.dataset.appliedAdjustment||0),savedAngle=Number(reviewState.angles[key]),committed=Number.isFinite(savedAngle)?savedAngle:applied;card.dataset.manualRotation=String(committed);showRotationPreview(card,committed);updateCardChange(card);setCardConfirmed(card,reviewState.confirmed[key]===true);var cardConfirm=card.querySelector("[data-card-confirm]"),edit=card.querySelector("[data-edit]"),slider=card.querySelector("[data-rotation-adjust]"),reset=card.querySelector("[data-edit-reset]"),cancel=card.querySelector("[data-edit-cancel]"),confirm=card.querySelector("[data-edit-confirm]");if(cardConfirm){cardConfirm.addEventListener("click",function(){if(card.dataset.confirmed==="true"){undoCard(card,key);return;}reviewState.confirmed[key]=true;setCardConfirmed(card,true);saveReviewState();updateConfirmAllPass();updateChangeBar();applyCoreFilters();showSavedModal("Saved","This core is confirmed in this report.");});}if(edit){edit.addEventListener("click",function(){card.dataset.editStart=card.dataset.manualRotation;showRotationPreview(card,card.dataset.manualRotation);card.classList.add("is-editing");});}if(slider){slider.addEventListener("input",function(){showRotationPreview(card,slider.value);});}if(reset){reset.addEventListener("click",function(){showRotationPreview(card,applied);});}if(cancel){cancel.addEventListener("click",function(){showRotationPreview(card,card.dataset.editStart||card.dataset.manualRotation);card.classList.remove("is-editing");});}if(confirm){confirm.addEventListener("click",function(){var angle=Number(card.dataset.draftRotation||0);card.dataset.manualRotation=String(angle);if(Math.abs(angle-applied)>=.05){reviewState.angles[key]=angle;}else{delete reviewState.angles[key];}reviewState.confirmed[key]=true;setCardConfirmed(card,true);saveReviewState();updateCardChange(card);updateConfirmAllPass();applyCoreFilters();card.classList.remove("is-editing");queueAutoSaveCorrections(true);});}});updateChangeBar();updateConfirmAllPass();applyCoreFilters();
if(confirmAllPass){confirmAllPass.addEventListener("click",function(){document.querySelectorAll('.core-card[data-filter-status="ok"]').forEach(function(card){var key=card.dataset.coreName||"";reviewState.confirmed[key]=true;setCardConfirmed(card,true);});saveReviewState();updateConfirmAllPass();updateChangeBar();applyCoreFilters();showSavedModal("Saved","All QC pass cores are confirmed in this report.");});}
function reviewCorrectionsPayload(){var corrections=[];document.querySelectorAll(".core-card").forEach(function(card){var title=card.querySelector(".core-title strong"),angle=Number(card.dataset.manualRotation||0);if(Math.abs(angle)>=.05){corrections.push({core:title?title.textContent:"",rotationAdjustmentDeg:angle});}});return {schemaVersion:1,image:orientationSection?orientationSection.dataset.imageName:"",baseRun:orientationSection?orientationSection.dataset.reviewKey:"",createdAt:new Date().toISOString(),corrections:corrections};}
async function autoSaveCorrections(showModal){if(!correctionAutoSaveUrl){setAutoSaveStatus("Open QuPath and run CoreAlign once to enable auto-save.","error",true);return false;}setAutoSaveStatus("Saving...","saving",false);try{var response=await fetch(correctionAutoSaveUrl,{method:"POST",headers:{"Content-Type":"text/plain;charset=UTF-8"},body:JSON.stringify(reviewCorrectionsPayload()),cache:"no-store"});if(!response.ok){throw new Error("Save failed");}setAutoSaveStatus("Saved. Go to QuPath and run CoreAlign again.","ready",false);if(showModal){showSavedModal("Saved","Your changes are saved beside REPORT.html.");}return true;}catch(error){setAutoSaveStatus("Could not auto-save. Use Save changes.","error",true);return false;}}
function queueAutoSaveCorrections(showModal){autoSaveModalPending=autoSaveModalPending||showModal===true;if(autoSaveTimer){clearTimeout(autoSaveTimer);}autoSaveTimer=setTimeout(function(){var shouldShow=autoSaveModalPending;autoSaveModalPending=false;autoSaveCorrections(shouldShow);},80);}
async function saveCorrectionFile(){var newline=String.fromCharCode(10),text=JSON.stringify(reviewCorrectionsPayload(),null,2)+newline,blob=new Blob([text],{type:"application/json"});if(window.showSaveFilePicker){try{var handle=await window.showSaveFilePicker({suggestedName:"corealign-review-corrections.json",types:[{description:"CoreAlign review corrections",accept:{"application/json":[".json"]}}]});var writable=await handle.createWritable();await writable.write(blob);await writable.close();setAutoSaveStatus("Saved with your browser.","ready",false);showSavedModal("Saved","Keep the correction file beside REPORT.html.");return;}catch(error){if(error&&error.name==="AbortError"){return;}}}var url=URL.createObjectURL(blob),link=document.createElement("a");link.href=url;link.download="corealign-review-corrections.json";link.click();setTimeout(function(){URL.revokeObjectURL(url);},0);setAutoSaveStatus("Downloaded. Keep the file beside REPORT.html.","error",true);showSavedModal("Downloaded","Move the correction file beside REPORT.html.");}
async function focusQuPath(){if(!correctionOpenQuPathUrl){setAutoSaveStatus("Open QuPath from your applications.","error",true);return;}try{var response=await fetch(correctionOpenQuPathUrl,{method:"POST",headers:{"Content-Type":"text/plain;charset=UTF-8"},body:"focus",cache:"no-store"});if(!response.ok){throw new Error("Open failed");}setAutoSaveStatus("QuPath is open. Run CoreAlign again.","ready",false);}catch(error){setAutoSaveStatus("Open QuPath from your applications.","error",true);}}
var resultsSection=document.getElementById("results"),outputModeUrl=resultsSection?resultsSection.dataset.outputModeUrl||"":"",outputModeStatus=document.getElementById("outputModeStatus"),outputModeButtons=Array.from(document.querySelectorAll("[data-output-mode-choice]"));
async function setOutputMode(mode){if(!resultsSection||mode===resultsSection.dataset.outputMode){return;}outputModeButtons.forEach(function(button){button.disabled=true;});if(outputModeStatus){outputModeStatus.textContent="Saving output mode...";}if(!outputModeUrl){if(outputModeStatus){outputModeStatus.textContent="Open QuPath and run CoreAlign once, then try again.";}outputModeButtons.forEach(function(button){button.disabled=false;});return;}try{var response=await fetch(outputModeUrl,{method:"POST",headers:{"Content-Type":"text/plain;charset=UTF-8"},body:JSON.stringify({mode:mode}),cache:"no-store"});if(!response.ok){throw new Error("Config update failed");}resultsSection.dataset.outputMode=mode;outputModeButtons.forEach(function(button){button.setAttribute("aria-pressed",button.dataset.outputModeChoice===mode?"true":"false");});if(outputModeStatus){outputModeStatus.textContent=mode==="research"?"Research saved. Run CoreAlign again to create OME-TIFF files.":"Presentation saved. Run CoreAlign again to update the package.";}showSavedModal("Saved",mode==="research"?"Research mode is saved in corealign.config.json. Run CoreAlign again.":"Presentation mode is saved in corealign.config.json. Run CoreAlign again.");}catch(error){if(outputModeStatus){outputModeStatus.textContent="Could not update the config. Keep QuPath open and try again.";}}finally{outputModeButtons.forEach(function(button){button.disabled=false;});}}
outputModeButtons.forEach(function(button){button.addEventListener("click",function(){setOutputMode(button.dataset.outputModeChoice);});});
if(downloadChanges){downloadChanges.addEventListener("click",saveCorrectionFile);}if(openQuPath){openQuPath.addEventListener("click",focusQuPath);}if(document.querySelector('.core-card[data-has-change="true"]')){queueAutoSaveCorrections(false);}
var gridImage=document.getElementById("gridImage"),gridViewport=document.getElementById("gridViewport"),gridZoomValue=document.getElementById("gridZoomValue"),gridZoom=1;function fitGridImage(){if(!gridImage||!gridViewport||!gridImage.naturalWidth||!gridImage.naturalHeight){return;}var fit=Math.min(gridViewport.clientWidth/gridImage.naturalWidth,gridViewport.clientHeight/gridImage.naturalHeight);gridImage.style.width=Math.max(1,Math.floor(gridImage.naturalWidth*fit*gridZoom))+"px";gridImage.style.height="auto";if(gridZoomValue){gridZoomValue.textContent=gridZoom===1?"Fit":Math.round(gridZoom*100)+"%";}}function setGridZoom(value,resetScroll){gridZoom=Math.max(1,Math.min(4,value));fitGridImage();if(resetScroll&&gridViewport){gridViewport.scrollTo(0,0);}}var zoomIn=document.getElementById("gridZoomIn"),zoomOut=document.getElementById("gridZoomOut"),zoomReset=document.getElementById("gridZoomReset");if(zoomIn){zoomIn.addEventListener("click",function(){setGridZoom(gridZoom+.25,false);});}if(zoomOut){zoomOut.addEventListener("click",function(){setGridZoom(gridZoom-.25,false);});}if(zoomReset){zoomReset.addEventListener("click",function(){setGridZoom(1,true);});}if(gridImage){gridImage.addEventListener("load",function(){setGridZoom(1,true);});}window.addEventListener("resize",function(){if(gridZoom===1){fitGridImage();}});if(gridViewport){var panning=false,startX=0,startY=0,startLeft=0,startTop=0;gridViewport.addEventListener("pointerdown",function(event){if(event.button!==0){return;}panning=true;startX=event.clientX;startY=event.clientY;startLeft=gridViewport.scrollLeft;startTop=gridViewport.scrollTop;gridViewport.classList.add("is-panning");gridViewport.setPointerCapture(event.pointerId);});gridViewport.addEventListener("pointermove",function(event){if(!panning){return;}gridViewport.scrollLeft=startLeft-(event.clientX-startX);gridViewport.scrollTop=startTop-(event.clientY-startY);});function stopPan(){panning=false;gridViewport.classList.remove("is-panning");}gridViewport.addEventListener("pointerup",stopPan);gridViewport.addEventListener("pointercancel",stopPan);}
var themeToggle=document.getElementById("themeToggle");function updateThemeControl(){var current=document.documentElement.dataset.theme==="dark"?"dark":"light";themeToggle.setAttribute("aria-label","Current theme: "+(current==="dark"?"Dark":"Light"));themeToggle.setAttribute("title","Current theme: "+(current==="dark"?"Dark":"Light")+". Click to switch.");}updateThemeControl();themeToggle.addEventListener("click",function(){var current=document.documentElement.dataset.theme;var next=current==="dark"?"light":"dark";document.documentElement.dataset.theme=next;try{localStorage.setItem("corealign-theme",next);}catch(e){}updateThemeControl();});
})();</script></body></html>''')
    new File(workflowDir, 'REPORT.html').setText(html.toString(), 'UTF-8')
}
def publishCurrentRun = {
    String runPath = System.getProperty('tma.orientation.runDir', '').trim()
    if (runPath.isEmpty()) {
        writeProjectIndex(null)
        return
    }
    File runDir = new File(runPath)
    if (!runDir.isDirectory()) {
        writeProjectIndex(null)
        return
    }

    int published = 0
    ['orientation_contact_sheet.png', 'run_report.json',
     'orientation_review_queue.csv', 'completion_report.json'].each { name ->
        if (publishFile(new File(runDir, name), new File(orientationQcDir, name))) published++
    }
    published += publishFolder(new File(runDir, 'rotated_previews'),
        new File(orientationQcDir, 'rotated_previews'))
    published += publishFolder(new File(runDir, 'unrotated_previews'),
        new File(orientationQcDir, 'unrotated_previews'))
    published += publishFolder(new File(runDir, 'rotated_fullres'), resultsPngDir)
    published += publishFolder(new File(runDir, 'rotated_multichannel_ome'), resultsOmeDir)
    ['orientation_results.csv', 'orientation_review_queue.csv', 'run_manifest.json',
     'workflow_summary.txt'].each { name ->
        if (publishFile(new File(runDir, name), new File(resultsTablesDir, name))) published++
    }
    def layoutManifest = [schemaVersion: 1, image: imageName,
        updatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"),
        internalRunDirectory: runDir.getAbsolutePath(),
        folders: [gridQc: 'qc/01-grid', orientationQc: 'qc/02-orientation',
            presentationPng: 'results/png', researchOmeTiff: 'results/ome-tiff',
            tables: 'results/tables', presentation: 'results/presentation',
            qupathProject: 'qupath', resumableWork: 'work']]
    new File(resultsTablesDir, 'project_layout.json')
        .setText(configJson.toJson(layoutManifest) + '\n', 'UTF-8')
    removeLegacyWorkflowHtml()
    writeProjectIndex(runDir)
    println "Published ${published} easy-to-find project file(s) under qc/ and results/."
}
removeLegacyWorkflowHtml()
writeProjectIndex(null)

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
    File reportDir = gridQcDir
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
        File reportDir = gridQcDir
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
    File reportDir = gridQcDir
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
File approvalFile = new File(stateDir, 'approved_grid.json')
def savedApproval = null
if (approvalFile.isFile()) {
    try { savedApproval = new Gson().fromJson(approvalFile.getText('UTF-8'), Map.class) }
    catch (Throwable ignored) {}
}
boolean approvalUsesCurrentDetector = savedApproval != null &&
    savedApproval.detectionAlgorithmVersion == REQUIRED_DETECTION_ALGORITHM_VERSION &&
    (savedApproval.approvalMode == 'human' ||
        (savedApproval.detectionConfigHash != null &&
            savedApproval.detectionConfigHash == detectionConfigHash) ||
        (savedApproval.detectionConfigHash == null &&
            (savedApproval.profileHash == null || savedApproval.profileHash == profileHash)))
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
    writeProjectIndex(null)
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
        writeProjectIndex(null)
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
        println 'Inspect the live grid and qc/01-grid output. Add TMA correction / TMA mark missing annotations where needed, then run this one-click script again.'
        Dialogs.showInfoNotification('CoreAlign review required',
            'Detection is complete. Inspect/correct the grid, then run this script again to approve and start/resume orientation.')
        if (!ALL_IN_ONE_INTEGRATION_TEST) return
    }
}

if (!validateGridStructure('before_grid_approval')) return
if (!validateDetectionAgainstTechnicalReference('before_grid_approval')) return

System.clearProperty('tma.review.status')
System.setProperty('corealign.dashboard.gridReviewPending', 'true')
writeProjectIndex(null)
runWorkflowScript(step3)
if (System.getProperty('tma.review.status', '') != 'APPROVED') {
    writeProjectIndex(null)
    println 'Pipeline stopped safely: the current grid was not approved.'
    return
}
System.clearProperty('corealign.dashboard.gridReviewPending')
writeProjectIndex(null)

System.clearProperty('tma.orientation.processedThisRun')
System.clearProperty('tma.orientation.exportOnlyThisRun')
System.clearProperty('tma.orientation.reportPath')
System.clearProperty('tma.orientation.reportJsonPath')
System.clearProperty('tma.orientation.runDir')
runWorkflowScript(step2)
if (System.getProperty('tma.orientation.status', '') != 'COMPLETE') return
publishCurrentRun()
int processedThisRun = System.getProperty('tma.orientation.processedThisRun', '0') as int
int exportOnlyThisRun = System.getProperty('tma.orientation.exportOnlyThisRun', '0') as int
if (processedThisRun > 0) {
    println "=== PAUSED AT FINAL ORIENTATION REVIEW GATE (${processedThisRun} cores changed) ==="
    String reportPath = System.getProperty('tma.orientation.reportPath', '')
    String elapsed = System.getProperty('tma.orientation.elapsed', 'unknown')
    String total = System.getProperty('tma.orientation.totalCount', '0')
    String ok = System.getProperty('tma.orientation.okCount', '0')
    String review = System.getProperty('tma.orientation.reviewCount', '0')
    String missing = System.getProperty('tma.orientation.missingCount', '0')
    Dialogs.showMessageDialog('CoreAlign run finished: review required',
        "Orientation processing finished successfully.\n" +
        "This is a planned review pause, not an error.\n\n" +
        "Positions: ${total}\nAutomatic QC pass: ${ok}\n" +
        "Needs review: ${review}\nMissing: ${missing}\nDuration: ${elapsed}\n\n" +
        "Project dashboard:\n${reportPath}\n\n" +
        'Inspect the flagged cores, make any corrections in QuPath, then run CoreAlign.groovy again. ' +
        'Accepted checkpoints are preserved, so completed cores will not be processed again.')
    if (!ALL_IN_ONE_INTEGRATION_TEST) return
}
if (processedThisRun == 0 && exportOnlyThisRun > 0) {
    println "Research-package upgrade reused all accepted core transforms; ${exportOnlyThisRun} core export(s) were added without redetection or reorientation."
    Dialogs.showInfoNotification('CoreAlign research package updated',
        "Added requested research files for ${exportOnlyThisRun} core(s). Detection, rotation and crop checkpoints were reused.")
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
System.clearProperty('tma.analysisProject.status')
System.clearProperty('tma.analysisProject.path')
runWorkflowScript(step7)
String analysisProjectStatus = System.getProperty('tma.analysisProject.status', 'UNKNOWN')
String analysisProjectPath = System.getProperty('tma.analysisProject.path', '')
println "QuPath analysis project status: ${analysisProjectStatus}"
String presentationStatus = System.getProperty('tma.presentation.status', 'UNKNOWN')
File completionDashboardFile = new File(workflowDir, 'REPORT.html')
String completedRunDir = System.getProperty('tma.orientation.runDir', '')
if (!completedRunDir.isEmpty()) {
    File completionDir = new File(completedRunDir)
    if (completionDir.isDirectory()) {
        File completionJsonFile = new File(completionDir, 'completion_report.json')
        File finalApprovalFileForReport = new File(stateDir, 'final_orientation_approval.json')
        def finalApprovalForReport = finalApprovalFileForReport.isFile() ?
            configJson.fromJson(finalApprovalFileForReport.getText('UTF-8'), Map.class) : [:]
        String completedAt = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
        def completionReport = [schemaVersion: 1, status: 'COMPLETE_HUMAN_APPROVED',
            completedAt: completedAt, image: imageName, profile: profileName,
            runDirectory: completionDir.getAbsolutePath(),
            projectDashboard: completionDashboardFile.getAbsolutePath(),
            counts: [positions: System.getProperty('tma.orientation.totalCount', '0') as int,
                automaticQcPass: System.getProperty('tma.orientation.okCount', '0') as int,
                reviewFlags: System.getProperty('tma.orientation.reviewCount', '0') as int,
                missing: System.getProperty('tma.orientation.missingCount', '0') as int],
            finalApproval: [status: finalApprovalForReport.status,
                approvalMode: finalApprovalForReport.approvalMode,
                approvedAt: finalApprovalForReport.approvedAt,
                gridHash: finalApprovalForReport.gridHash,
                orientationResultHash: finalApprovalForReport.orientationResultHash],
            presentationStatus: presentationStatus,
            analysisProject: [status: analysisProjectStatus,
                projectFile: analysisProjectPath ?: null,
                statusFile: System.getProperty('tma.analysisProject.statusPath', '') ?: null]]
        completionJsonFile.setText(configJson.toJson(completionReport) + '\n', 'UTF-8')
        System.setProperty('tma.completion.reportPath', completionDashboardFile.getAbsolutePath())
        println "Completion data: ${completionJsonFile.getAbsolutePath()}"
    }
}
publishCurrentRun()
println "Project dashboard: ${completionDashboardFile.getAbsolutePath()}"
if (ALL_IN_ONE_INTEGRATION_TEST)
    println '=== CoreAlign all-in-one INTEGRATION TEST COMPLETE; approvals are test-only ==='
else
    println '=== CoreAlign COMPLETE; grid and orientation are human-approved ==='

if (!ALL_IN_ONE_INTEGRATION_TEST) {
    String reportPath = completionDashboardFile.getAbsolutePath()
    String projectMessage = analysisProjectStatus == 'READY' ?
        "Analysis-ready QuPath project:\n${analysisProjectPath}\n\n" +
            'Open it with File > Project > Open project. Core entries are ordered by row and column and include QC metadata.' :
        analysisProjectStatus == 'RESEARCH_OUTPUT_REQUIRED' ?
            'Analysis project: not created yet because this run contains presentation images only.\n' +
            'Choose Research package in the Config Builder and run this same script again. ' +
            'Detection, rotation, and crop checkpoints will be reused.' :
            "Analysis project status: ${analysisProjectStatus}"
    Dialogs.showMessageDialog('CoreAlign complete',
        "The approved workflow finished successfully.\n\n" +
        "Project dashboard:\n${reportPath}\n\n${projectMessage}")
}
