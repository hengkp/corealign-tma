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
import java.text.SimpleDateFormat

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
            exportDownsample: 1.0d, previewMaxPixels: 900, parallelWorkers: 2, cropScale: 1.05d,
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
def presentationConfig = activeProfile.presentation instanceof Map ?
    activeProfile.presentation : [:]
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
 'saveRotatedMultichannelOmeTiff', 'parallelWorkers'].each {
    orientationProcessingConfig.remove(it)
}
def detectionIdentity = [grid: gridConfig, detection: detectionConfig]
def processingIdentity = [grid: gridConfig, detection: detectionConfig,
    orientation: orientationProcessingConfig]
def outputIdentity = [
    presentationRendererVersion: 'slide-color-2.0',
    saveFullResolutionPng: orientationConfig.saveFullResolutionPng != false,
    saveNativeOmeTiff: orientationConfig.saveNativeOmeTiff == true,
    saveRotatedMultichannelOmeTiff:
        orientationConfig.saveRotatedMultichannelOmeTiff == true,
    presentationRendering: presentationConfig
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
    'tma.orientation.parallelWorkers': orientationConfig.parallelWorkers,
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
    'tma.orientation.overrideClassName': orientationConfig.overrideClassName,
    'tma.presentation.channelTokens': presentationConfig.channelTokens,
    'tma.presentation.lowPercentile': presentationConfig.lowPercentile,
    'tma.presentation.highPercentile': presentationConfig.highPercentile,
    'tma.presentation.gamma': presentationConfig.gamma,
    'tma.presentation.maxChannels': presentationConfig.maxChannels,
    'tma.presentation.rendererVersion': 'slide-color-2.0'
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
     'saveRotatedMultichannelOmeTiff', 'parallelWorkers'].each {
        savedOrientation.remove(it)
    }
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
6ZxpJfYBAA==
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
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
AA==
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
    def presentationRendering = orientationReport.presentationRendering instanceof Map ?
        orientationReport.presentationRendering : [:]
    def presentationChannels = presentationRendering.channels instanceof List ?
        presentationRendering.channels : []
    String presentationChannelNames = presentationChannels.collect {
        it instanceof Map ? it.channel?.toString() : null
    }.findAll { it }.join(', ')
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
    [[hasPng, 'Presentation PNG', 'Rotated color images with one shared slide-level display range.', 'results/png/', 'folder'],
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
    html.append('</div>')
    if (!presentationChannelNames.isEmpty()) {
        html.append('<div class="section-block notice">').append(projectIcon('image'))
            .append('<div><strong>PNG channels: ')
            .append(projectHtmlEscape(presentationChannelNames))
            .append('</strong><p>The same slide-level display ranges are used for every core. Use these PNG files for review and presentation. Use OME-TIFF files for quantitative intensity analysis.</p></div></div>')
    }
    html.append('<div class="section-block notice">').append(projectIcon('folder')).append('<div><strong>Keep the work folder</strong><p>It contains resumable checkpoints. Do not delete it when changing from a presentation package to a research package.</p></div></div></section>')

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
     'display_ranges.json', 'workflow_summary.txt'].each { name ->
        if (publishFile(new File(runDir, name), new File(resultsTablesDir, name))) published++
    }
    def layoutManifest = [schemaVersion: 1, image: imageName,
        updatedAt: new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()),
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
        String completedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
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
