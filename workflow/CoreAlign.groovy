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
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import javafx.stage.Stage
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
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
 * Loopback save bridge for REPORT.html. All supported browsers can save while
 * QuPath is open. AppHub and browsers with folder access also save directly.
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
                outputEndpoint: "${root}/output?token=${token}",
                gateEndpoint: "${root}/gate?token=${token}"]
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

    // ---------------------------------------------------------------------
    // Review gates.
    //
    // The dashboard's primary button posts a decision here, so the reviewer
    // finishes in the browser and the SAME QuPath run continues. Gate state is
    // static on purpose: REPORT.html is rewritten (which restarts the socket
    // through start(), and start() calls stop() first) immediately before a
    // gate opens, so gate state must not be tied to the socket lifecycle.
    // ---------------------------------------------------------------------
    private static volatile String openGateId = ''
    private static volatile String gateDecision = ''

    /** Arm a gate. Any decision left over from an earlier gate is discarded. */
    static void openGate(String gateId) {
        gateDecision = ''
        openGateId = gateId == null ? '' : gateId.trim()
    }

    /** The gate currently accepting a decision, or an empty string. */
    static String currentGate() { return openGateId }

    /** Consume a pending decision ('continue', 'cancel') or return ''. */
    static String takeGateDecision() {
        String decision = gateDecision
        if (!decision.isEmpty()) gateDecision = ''
        return decision
    }

    /** Disarm the gate so a stale report tab cannot advance a later run. */
    static void closeGate() {
        openGateId = ''
        gateDecision = ''
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
            openGate('grid')
            if (currentGate() != 'grid') throw new IOException('Gate did not arm')
            byte[] gateBody = new Gson().toJson([gate: 'grid', decision: 'continue'])
                .getBytes(StandardCharsets.UTF_8)
            def gateConnection = new URL(bridge.gateEndpoint.toString()).openConnection()
            gateConnection.setRequestMethod('POST')
            gateConnection.setDoOutput(true)
            gateConnection.setConnectTimeout(3000)
            gateConnection.setReadTimeout(3000)
            gateConnection.setRequestProperty('Content-Type', 'text/plain;charset=UTF-8')
            gateConnection.getOutputStream().withCloseable { it.write(gateBody) }
            if (gateConnection.getResponseCode() != 200)
                throw new IOException("Gate returned HTTP ${gateConnection.getResponseCode()}")
            gateConnection.getInputStream().withCloseable { it.readAllBytes() }
            if (takeGateDecision() != 'continue')
                throw new IOException('Gate decision was not delivered')
            if (!takeGateDecision().isEmpty())
                throw new IOException('Gate decision was delivered twice')
            closeGate()
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
            String gateTarget = "/corealign/gate?token=${token}"
            if (target != saveTarget && target != openTarget &&
                    target != outputTarget && target != gateTarget) {
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
            if (target == gateTarget) {
                String gate = payload.gate?.toString()?.trim() ?: ''
                String decision = payload.decision?.toString()?.trim()
                    ?.toLowerCase(Locale.ROOT) ?: ''
                if (!(decision in ['continue', 'cancel'])) {
                    respond(client, 400, 'Bad Request',
                        [ok: false, error: 'Invalid review decision'])
                    return
                }
                if (openGateId.isEmpty() || gate != openGateId) {
                    respond(client, 409, 'Conflict', [ok: false,
                        error: 'CoreAlign is not waiting for this review step'])
                    return
                }
                gateDecision = decision
                respond(client, 200, 'OK',
                    [ok: true, gate: gate, decision: decision])
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


/**
 * The whole setup surface: two choices and one Start button.
 *
 * Everything CoreAlign can infer, it infers. Rows, columns, core diameter and
 * array position are measured from the slide, so the only questions left are
 * the two a person actually has to answer: what tissue this is, and which files
 * they want at the end. The dialog is shown once per run and is the only place
 * a decision is asked for before processing starts.
 */
class CoreAlignSetup {

    static final String ACCENT = '#4262ff'

    /**
     * @param state headline/detail/primary label plus the current tissue and
     *              output choice.
     * @return the same map with 'start' set, and the chosen tissue/output.
     */
    static Map show(Map state) {
        Callable<Map> body = { -> build(state) } as Callable<Map>
        FutureTask<Map> task = new FutureTask<Map>(body)
        if (Platform.isFxApplicationThread()) task.run()
        else Platform.runLater(task)
        try {
            return task.get()
        } catch (Throwable dialogError) {
            println "WARNING: CoreAlign setup dialog failed: ${dialogError.getMessage()}"
            return [start: true, tissue: state.tissue, output: state.output,
                fallback: true]
        }
    }

    private static Map build(Map state) {
        Dialog<ButtonType> dialog = new Dialog<ButtonType>()
        dialog.setTitle('CoreAlign')
        dialog.setHeaderText(null)
        dialog.setGraphic(null)
        dialog.initOwner(ownerWindow())

        Label eyebrow = new Label('CoreAlign')
        eyebrow.setStyle("-fx-font-size:11px;-fx-font-weight:800;-fx-text-fill:${ACCENT};")
        Label headline = new Label(state.headline?.toString() ?: 'Prepare this TMA slide')
        headline.setStyle('-fx-font-size:21px;-fx-font-weight:800;')
        headline.setWrapText(true)
        Label detail = new Label(state.detail?.toString() ?: '')
        detail.setWrapText(true)
        detail.setStyle('-fx-opacity:0.74;')
        detail.setMaxWidth(430)

        def tissue = segmented('tissue', state.tissue?.toString() ?: 'skin',
            [['skin', 'Skin', 'Epidermis kept at the top'],
             ['other', 'Other tissue', 'Strongest outer edge kept at the top']])
        def output = segmented('output', state.output?.toString() ?: 'presentation',
            [['presentation', 'Images', 'Full-resolution PNG'],
             ['research', 'Images + research files', 'PNG, OME-TIFF, QuPath project']])

        Label footnote = new Label(
            'Nothing is exported until you have seen the result and approved it.')
        footnote.setWrapText(true)
        footnote.setStyle('-fx-opacity:0.62;-fx-font-size:12px;')
        footnote.setMaxWidth(430)

        VBox content = new VBox(6d, eyebrow, headline, detail,
            spacer(10), row('Tissue', tissue.node), row('Results', output.node),
            spacer(6), footnote)
        content.setPadding(new Insets(4, 4, 4, 4))
        content.setPrefWidth(460)

        ButtonType startType = new ButtonType(
            state.primaryLabel?.toString() ?: 'Start', ButtonBar.ButtonData.OK_DONE)
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, startType)
        dialog.getDialogPane().setContent(content)
        Button startButton = (Button) dialog.getDialogPane().lookupButton(startType)
        startButton.setDefaultButton(true)
        startButton.setStyle(
            "-fx-background-color:${ACCENT};-fx-text-fill:white;-fx-font-weight:800;" +
            '-fx-background-radius:999;-fx-padding:8 22 8 22;')

        def result = dialog.showAndWait()
        boolean started = result.isPresent() && result.get() == startType
        return [start: started, tissue: tissue.value(), output: output.value()]
    }

    private static Object ownerWindow() {
        try { return QuPathGUI.getInstance()?.getStage() } catch (Throwable ignored) { return null }
    }

    private static Region spacer(double height) {
        Region region = new Region()
        region.setMinHeight(height)
        return region
    }

    private static HBox row(String label, javafx.scene.Node control) {
        Label name = new Label(label)
        name.setMinWidth(72d)
        name.setStyle('-fx-font-weight:700;-fx-opacity:0.86;')
        HBox box = new HBox(12d, name, control)
        box.setAlignment(Pos.CENTER_LEFT)
        box.setPadding(new Insets(2, 0, 2, 0))
        return box
    }

    /** A two-option segmented control. One option is always selected. */
    private static def segmented(String id, String selected, List<List<String>> options) {
        ToggleGroup group = new ToggleGroup()
        HBox box = new HBox(0d)
        box.setStyle('-fx-border-color:#c9cede;-fx-border-radius:999;-fx-background-radius:999;')
        box.setPadding(new Insets(3, 3, 3, 3))
        options.eachWithIndex { option, index ->
            ToggleButton button = new ToggleButton(option[1])
            button.setUserData(option[0])
            button.setToggleGroup(group)
            button.setTooltip(new javafx.scene.control.Tooltip(option[2]))
            button.setFocusTraversable(false)
            if (option[0] == selected) button.setSelected(true)
            HBox.setHgrow(button, Priority.ALWAYS)
            box.getChildren().add(button)
        }
        def paint = {
            group.getToggles().each { toggle ->
                ToggleButton button = (ToggleButton) toggle
                button.setStyle(button.isSelected() ?
                    "-fx-background-color:${ACCENT};-fx-text-fill:white;-fx-font-weight:700;" +
                        '-fx-background-radius:999;-fx-padding:7 16 7 16;' :
                    '-fx-background-color:transparent;-fx-font-weight:600;' +
                        '-fx-background-radius:999;-fx-padding:7 16 7 16;')
            }
        }
        paint()
        group.selectedToggleProperty().addListener({ observable, was, now ->
            // A segmented control must never end up with nothing selected.
            if (now == null && was != null) { group.selectToggle(was); return }
            paint()
        })
        return [node: box, value: { ->
            def toggle = group.getSelectedToggle()
            return toggle == null ? selected : toggle.getUserData().toString()
        }]
    }
}

/**
 * The small window shown while CoreAlign waits at a review gate.
 *
 * It is deliberately not modal: the reviewer has to be able to pan the slide,
 * draw a correction annotation, and use the report in the browser while it is
 * open. It carries the same two actions as the report, so a reviewer who never
 * leaves QuPath still finishes the run in one click and never has to find the
 * script editor again.
 */
class CoreAlignGateWindow {

    private static Stage stage
    private static volatile String decision = ''

    static void open(String gateId, String title, String summary, String detail,
            String primaryLabel, Closure openReport) {
        decision = ''
        Platform.runLater({
            try {
                close()
                Label heading = new Label(title)
                heading.setStyle('-fx-font-size:16px;-fx-font-weight:800;')
                Label counts = new Label(summary)
                counts.setStyle("-fx-font-size:12px;-fx-font-weight:700;-fx-text-fill:${CoreAlignSetup.ACCENT};")
                Label body = new Label(detail)
                body.setWrapText(true)
                body.setMaxWidth(340)
                body.setStyle('-fx-opacity:0.74;-fx-font-size:12px;')

                Button reportButton = new Button('Open report')
                reportButton.setOnAction({ if (openReport != null) openReport.call() })
                Button stopButton = new Button('Stop here')
                stopButton.setOnAction({ decision = 'cancel' })
                Button continueButton = new Button(primaryLabel)
                continueButton.setDefaultButton(true)
                continueButton.setStyle(
                    "-fx-background-color:${CoreAlignSetup.ACCENT};-fx-text-fill:white;" +
                    '-fx-font-weight:800;-fx-background-radius:999;-fx-padding:7 18 7 18;')
                continueButton.setOnAction({ decision = 'continue' })

                Region push = new Region()
                HBox.setHgrow(push, Priority.ALWAYS)
                HBox actions = new HBox(8d, reportButton, push, stopButton, continueButton)
                actions.setAlignment(Pos.CENTER_LEFT)

                VBox root = new VBox(8d, counts, heading, body, actions)
                root.setPadding(new Insets(18, 18, 18, 18))
                root.setStyle('-fx-background-color:-fx-background;')

                Stage window = new Stage()
                window.setTitle('CoreAlign')
                window.setScene(new Scene(root))
                window.setAlwaysOnTop(true)
                window.setResizable(false)
                window.setOnCloseRequest({ event -> event.consume() })
                try { window.initOwner(QuPathGUI.getInstance()?.getStage()) }
                catch (Throwable ignored) {}
                stage = window
                window.show()
            } catch (Throwable windowError) {
                println "WARNING: CoreAlign review window failed: ${windowError.getMessage()}"
            }
        })
    }

    /** Consume a decision made in this window, or return ''. */
    static String take() {
        String value = decision
        if (!value.isEmpty()) decision = ''
        return value
    }

    static void close() {
        Stage window = stage
        stage = null
        decision = ''
        if (window == null) return
        if (Platform.isFxApplicationThread()) { try { window.hide() } catch (Throwable ignored) {} }
        else Platform.runLater({ try { window.hide() } catch (Throwable ignored) {} })
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
                        'Review the grid in REPORT.html, then continue there.')
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
// Headless mode: no JavaFX and no browser on this machine, because the run is a Slurm job and
// the person is somewhere else entirely. Every choice arrives in the config file, and every
// review is answered over the loopback bridge by whatever is driving the run.
//
// QuPath's own Dialogs API is only half safe without a GUI, which is why this flag exists rather
// than a try/catch: showErrorMessage and the notifications degrade to log lines, but
// showMessageDialog and showConfirmDialog throw ExceptionInInitializerError on the first
// javafx.scene.control.Label. A dialog that cannot be shown must become a clear refusal, never
// an exception in the middle of a slide.
boolean HEADLESS = 'true'.equalsIgnoreCase(System.getProperty('corealign.headless', 'false'))
if (HEADLESS) {
    System.setProperty('corealign.headless', 'true')
    println 'COREALIGN_HEADLESS: no dialogs will be shown; reviews are answered over the bridge.'
}
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
            exportDownsample: 1.0d, previewMaxPixels: 900, parallelWorkers: 0, cropScale: 1.05d,
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


// -------------------------------------------------------------------------
// The one setup step.
//
// Two choices and one button, shown once per run. Everything the slide can
// answer for itself (rows, columns, core diameter, array position) is measured
// rather than asked for, so this is the only prompt before processing starts.
// The Config Builder website remains available for unusual channel names, but
// no one needs it for a normal slide any more.
// -------------------------------------------------------------------------
boolean reportOnlyLaunch = Boolean.parseBoolean(
    System.getProperty('corealign.reportOnly', 'false'))
boolean headlessLaunch = HEADLESS || ALL_IN_ONE_INTEGRATION_TEST || STOP_AFTER_DETECTION ||
    reportOnlyLaunch ||
    'true'.equalsIgnoreCase(System.getProperty('tma.configValidateOnly', 'false')) ||
    'true'.equalsIgnoreCase(System.getProperty('corealign.skipSetupDialog', 'false'))
if (!headlessLaunch) {
    Map launchRoot
    try { launchRoot = configJson.fromJson(configFile.getText('UTF-8'), Map.class) ?: [:] }
    catch (Throwable ignored) { launchRoot = [:] }
    String launchProfileName = launchRoot.activeProfile?.toString() ?: 'automatic'
    Map launchProfile = (launchRoot.profiles instanceof Map &&
        launchRoot.profiles[launchProfileName] instanceof Map) ?
        launchRoot.profiles[launchProfileName] as Map : null
    if (launchProfile != null) {
        if (!(launchProfile.orientation instanceof Map)) launchProfile.orientation = [:]
        Map launchOrientation = launchProfile.orientation as Map
        String launchAlgorithm = launchOrientation.algorithmVersion?.toString() ?: ''
        String currentTissue = launchAlgorithm.startsWith('skin-epidermis') ? 'skin' : 'other'
        String currentOutput = launchOrientation.saveRotatedMultichannelOmeTiff == true ?
            'research' : 'presentation'

        boolean hasApprovedGridForLaunch = new File(stateDir, 'approved_grid.json').isFile()
        boolean hasOrientationForLaunch =
            new File(orientationQcDir, 'run_report.json').isFile()
        boolean hasFinalApprovalForLaunch =
            new File(stateDir, 'final_orientation_approval.json').isFile()
        def liveGridForLaunch = imageData.getHierarchy().getTMAGrid()
        boolean hasLiveGridForLaunch = liveGridForLaunch != null &&
            liveGridForLaunch.getTMACoreList().any { !it.isMissing() }

        String launchHeadline
        String launchDetail
        String launchPrimary
        if (!hasApprovedGridForLaunch && !hasLiveGridForLaunch) {
            launchHeadline = 'Find the cores on this slide'
            launchDetail = 'CoreAlign measures the array, the core size, and every core ' +
                'position, then shows you the result to check.'
            launchPrimary = 'Start'
        } else if (!hasApprovedGridForLaunch) {
            launchHeadline = 'Check the grid, then rotate and crop'
            launchDetail = 'The detected grid is ready. Approve it and CoreAlign rotates ' +
                'each core before cropping it.'
            launchPrimary = 'Continue'
        } else if (!hasOrientationForLaunch) {
            launchHeadline = 'Rotate and crop each core'
            launchDetail = 'The approved grid is reused. Progress is saved after every ' +
                'core, so an interrupted run picks up where it stopped.'
            launchPrimary = 'Continue'
        } else if (!hasFinalApprovalForLaunch) {
            launchHeadline = 'Finish the review'
            launchDetail = 'Accepted cores are reused. Only cores you changed are ' +
                'processed again.'
            launchPrimary = 'Continue'
        } else {
            launchHeadline = 'Update the result files'
            launchDetail = 'The approved grid and accepted rotations are reused. Only ' +
                'missing files are created.'
            launchPrimary = 'Continue'
        }

        Map launchChoice = CoreAlignSetup.show([headline: launchHeadline,
            detail: launchDetail, primaryLabel: launchPrimary,
            tissue: currentTissue, output: currentOutput])
        if (launchChoice.start != true) {
            println 'RUN_CANCELLED: no image processing was started.'
            return
        }
        String chosenTissue = launchChoice.tissue?.toString() ?: currentTissue
        String chosenOutput = launchChoice.output?.toString() ?: currentOutput
        if (chosenTissue != currentTissue || chosenOutput != currentOutput) {
            launchOrientation.algorithmVersion = chosenTissue == 'skin' ?
                'skin-epidermis-orient-3.7-rotated-multichannel' :
                'generic-peripheral-orient-3.7-rotated-multichannel'
            launchOrientation.overrideClassName = chosenTissue == 'skin' ?
                'Epidermis override' : 'Orientation override'
            launchOrientation.saveFullResolutionPng = true
            launchOrientation.saveNativeOmeTiff = false
            launchOrientation.saveRotatedMultichannelOmeTiff = chosenOutput == 'research'
            launchProfile.description = chosenTissue == 'skin' ?
                'Automatic skin TMA with the epidermis at the top' :
                'Automatic TMA with a consistent tissue edge at the top'
            configFile.setText(configJson.toJson(launchRoot) + '\n', 'UTF-8')
            println "CoreAlign setup: tissue ${chosenTissue}, output ${chosenOutput}"
        }
    }
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
    channelIndices: orientationConfig.channelIndices,
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

// setProp leaves a property untouched when the config omits the key, so a second run
// in the same QuPath session would keep the first run's channel subset while both
// identity hashes said every channel. Clear the ones that mean "nothing selected".
System.clearProperty('tma.orientation.channelIndices')
def setProp = { String key, value ->
    if (value == null) return
    String propertyValue
    if (value instanceof List) {
        propertyValue = value.collect { item ->
            if (item instanceof Number) {
                double listNumericValue = ((Number) item).doubleValue()
                Double.isFinite(listNumericValue) && listNumericValue == Math.rint(listNumericValue) ?
                    Long.toString(((Number) item).longValue()) : item.toString()
            } else {
                item.toString()
            }
        }.join(',')
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
    'tma.orientation.channelIndices': orientationConfig.channelIndices,
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
boolean reportOnly = Boolean.parseBoolean(System.getProperty('corealign.reportOnly', 'false'))
def existingGridForPreflight = imageData.getHierarchy().getTMAGrid()
boolean needsDetectionPreflight = existingGridForPreflight == null ||
    existingGridForPreflight.getTMACoreList().isEmpty() ||
    !existingGridForPreflight.getTMACoreList().any { !it.isMissing() }
// The two review gates below are the only stops in a run. Everything the old
// multi-run flow explained in a summary dialog before each pass is now either
// automatic or shown in context on REPORT.html, next to the image it describes.

def step1 = new EmbeddedWorkflowScript(name: '01_build_tma_grid.groovy', payload: '''
H4sIAAAAAAACE+29bXIbSZIo+J+nyKLVNAAJgEhWqbqaEiWDSFCiNb8aoKrEp+bSEkCSyCKARGUm
SGLUMOtfe4C1MZv/7wRra7YXmDnA3qFPsv4R3xkJgKrqee+tbVm3mMiM8PCI8PBw9/Bwf/Hs2Ubw
LLg4aQWdKEzTcHIbjaNJHlwkySj4x9//Lejm0TTY3g0Oojzq50E/SaMseB6EWRbfToLbNB4E1TR5
qMOXUQ1gIbjObJIF8ST4y+w8zIfBVvOPQXgbxpMsD/JhFISjNAoH88YoCQfRIDg7aTcujg4Pm6L2
eTyNRvEkCqpZPJ6OouCgfdHevwjGySCq7WKJINhuBkc3QRiMwvQ2ClLADDCHonEWZNEIfkaDejDL
4EX+Kkig0fQhhl/hLE+g2X6YR4QJ9LvJAHeaQbefxtM8QNwy+ppGt3EyCcIcGhpG4f08GCQPkyxE
pES175rBu1k8ggphMEnScTiK/xW6NJn1RzCcLw5a50fBNE1+AYwQ1E2S0ggGAxpNeCXgwH+tYBxB
Z6ByMmm0Ds1q0CvoyyBIJqN5kCeAWNafRS8yaCsYxxl+yuHPTGL1fTM4y7NZIx9CyWFC6E0GwU08
gad+MpnQADX6yXiaTHC2e6OklwXVKOwPgz1oJiIsawLcy2ZwGI/yKM1Ewd48iB6nBIS7E8KgicI/
NIP90Syj0pdBH6CnSTxAckDEk4dGD1DJROE/NoN2lsdjmI8suAXY4QjpaDaeUE2AUceZmECPAXko
QxgClOAhBsKC+Ye5HKkhHIV5HvejIEuCbBjfAHovetg9qJDB3MHIAgEDqjD70Xiaz4PkPkpH4Vyi
8yOgQ+81RQMeo1GGHYThG42gw6LlTRz4eHK7KUaASFthAqQDQ5/B5DVi7Mc0AdygMq2XaZLFcu5p
uUTZbJTvBhFgMxekIUcWSmYBEmE/n8Hg8DTXGQkogJRvrz8Y6EH0CIi9CrI8hb4R7rBWAdrgVZCn
M6AhgbvChAvBnI7ifpxjgTC9iwYSwTNcQLT+MrH+/vH3/x58zLBKDPMHkJBV3EXAKh6G0FHoCHcV
KFesduQHuKiGcZSGaX84l8DfYZdvQhgB+I7rl5ch0C1M2iQJpmEajmFM0mAQh6Pktgm8CbqRzHoj
JO4EV+ckekBQMJXJLK8DC8iD7oezn69bBz+1TvfbB9cHR63js/dA29D/CIqHKSGTJ9MguaFHbhaR
erGxAXwnSfPg19kU+FdzFPea09HsFhhYU2GTNc/l4zGMgafK7SxuMlQYoOZfztuPnkJA+7dR1jzC
PwdhHnqKJD3kA9hePjzj5yWl9Pge8KgCj3sPU+Gpwvwta3bobyf6dRYVOpJMo0n/vpnDdpA1z+DH
/k+4NWRqjJL0ttmbA71G/USW5j/XSMDNkzBXZX8J78NH7nKccJ+PzsyPzfAhb74Ls7jfhRm+iwrf
9pNRkhbeHiaTvPCyE8FCSGHoP8AKzAqfCYvmu9nNTQQLg3BReGZ5CHzE2zVmUlYPnz25Wjy+Be7e
h5obQPpB/+YWuouraC/4EojHu2hel8834WjUC/t3QeNN0J0Dbx03b6P8PAVwaT6vUlFZphYsJNAj
YH0uRBgLE9wGMqscuM4XYFn5LJ0EUCm6jVIk9CyCH9VVDQJtcAPVWg0bR4iwwQKfrl4MgTOFvZHi
PzXdjkJisaEQPqBFXcB5wK+Xos1VGWt+/q9E/B1KSy7aPXgZhZMC3gLOO/7MKIsfT8MZ2t8QDbKI
dHR2et06fn/WObr4cHL9U7vThTeAlyKwaiUfh00tfISj2ySFrWT8EzA0eFGpE4aV7C6eNKBog4s2
dpo7DSHTNFj4qNQ2Nl68CPb29lAW64aTGLbN/jDq39F2hRxViHe00LAgDVYsGR2gBb3cn8EWNskV
+6vWNuKboGqU2gNhagT78BfC7IC2gKyJu0M7TZP0BHZZKFutWCJspR5UThPRNOwpuO5AJoJ/CTMp
cRoy6k2cZnkTeqVnSExvFqW4ne1p1HF6uvQW0CUKCHH2uSDNXfwYjfZBFuylIY6zLDYMQfQaAQ/P
rOInUR4OqPP4a59LZXalU9hqsJYBowk7Poq6QHZxjjWxTBUpGWeGxFLYGh+GcR41QA7pjZAGxSyS
5HMfRw+wqaIMAVv+TZqMgzR8kE2AUARgiOFKwQXeJ3moBYYBFAfRBASt9CFMhZSZRmMQ9UFSy0hW
HbCAMIuzYZM6lBJf7o7iQfSXfue2R+vG4sOIRT3AXTWYULfFwqHaIYqWUAfKYJ879LvK84bM7YG+
4Uv8/HM8yIfVWj0YWq8/RPHtMMf3E3j/AKLDUAEg+RTenuD2N44nVV3tdDZ+h1+pImLWRBEcliJX
BsLlyq+D7Zpc5oCnQh43CAT9+Ype3cAKyT9fBffhaEaTC2KMeDm5UviwvkFqmEQqfKxuAwZN+DyI
76s7O1vwn8ACVYyq7AfU2HrFT6+5Y/zr+XO5oIjcVQe71FZW3aoH8D+YhGGdytcFjjVVR+LO2Fm4
Y+s1xrQfxaPqJHgRVJmH14ze1K4UMES3n8xou9rSTciexNyNGPowwT/P90wwRkcUYowuVGO0P8dX
VhmcKS7xBpTTrRuJ1mdC4vnzK1lT1VpsmHUFroBVDdUpoG+jKEkYszweNVspyt/NDGSDKjdAw0q1
9UiK3W2UIOUKPNQ0Q3FFh9xqI9iuW50xhhuE4smgqgvWgLC3mi+3BrBZXLkNjuPBgGbun9Hmn156
2xzCsvtntfinH+0mebG9fh18Jo1oVxAyrttdXr2f8c3VW2NPDd7uBpuCAQfffqGF8zzYXmzayMBc
7eI/derQrl6VOIlQvrk14E81u142DSdGaS6nfooJaTDkKo0V/eAObuOQcvcWit9QJ5txRmorMCIf
1xEs/wLk6Qlxn8ognMa4RQ6TqD/McnwUhfARuPqv+BeUozSpXCk4/WGSRcgvuVG0JrRGI2De+Fvy
aPxPyCQ4xqJ0E5+NcYbH4+QhSvfDLKrqpWBh2gwnKN/l+AMFXwKBqw0NSVV6LaU2PR6MozEgCrbA
HuiB0YcRB+gh8DcEXg2bODlBmAWSU73eg9e9wmshJ8qxEA3m4V1U/bFmsXVQvxOQRbLcx9lFPbJn
FEdwOUemESWiRsRgYRTZs81UsDw+qm645UhosraXqPEnoE2iryZRojEETJQr+bS9yRitoeWQJCbN
AIyVADyAFoZVk1iAYuZyWbxgxI1ZVuuCm3i9h/wdV06RT9OCtDCZJg9VWbOhKr5A5vLjS8Bvq/nD
zqC0rTd6wgHFmvULWqjS9NdEk87GspC7PZDNFIVHKQ3gK0Ex6w0ziUAo8pkSzM7Llwaf2VrBWaE0
jD/wHKs/xiAzhqJb3Bgsq+0fasHfjN8/4k/6ZXQR+UgykyvCkvqqTNzWu+bF5Xn7+uj04rrz/h0j
ALWbGch9799Za4Jx4hdccAqsJh9Ngk20fgR/2S+ThKW4uwsMX6xKS7AmNrZo/pLAQCJbrC02TTUO
8EE9TJD2Qfuw9fH44vr86FP7+Lp79N/a1ydH+52z0y6rYUI1JTUMReqmMH2RxtAFYfIk7sOgZxWk
NqA+CXc2Po/S80cAUt7CBinEhJsuTqoJ61DB22Xo7WoZDeog62mBigBzMHBxA4a9WKYoOxgsQXhB
yt43VVmYpDEA8be/SX0+zk7DU/nd/nA0gR0IZlR9lU2rif+51Tk9On2/G7BWMQzJnjjLCF8iGOyq
VNJe4ZnGbIw717dfypFeBP/xf49fTB+VLb9BZvhImrGbm0/pP6pYLTyUYEVbEmUjw11OkiaadOM+
aAjVPuyXDdj+YHuM8/geLfQAgE4ZYrTsj8egew9h+cC2BqssemTpPENlKwvG4ZzORD7wtl8PDjqt
vwBnuDhrnHfO6kGU95uuznmcJHfZqVgqhoWDVoXYseQrZAf0HqSoSsW3yYs1M9H7OMsiNLPmWymZ
FD6QcIJvtdBgfIWBRJmlUItFmcLrbJ4n5cBoLjy4SVGpJgwE2IMjMUOk25kqO+3wP8f58Ij26y80
cHVg3mLsWGjxDjeNJVC1CR/4aoxko2gcDTCW6cM4tEOjy6YuSqsAuZxhqAhRO14YhQ7iMRIXLHO7
pFCkF8F//rv1XmrSi2D6aIAhnhHgykAwYjHIpWOUk8aOoIrM1xg2VqwXNebK+oOJ66nDwfXRCUhT
Ys1gfWMAF9iQ8dtg9WYrn+P8KlgsaptsoFU2ddcK9EF+EMYaefbYyrt5mOI2p83xZDQaseHtJBlE
Izb3dEUVtuwDHGk2lLDYOn+eJvcxmtP20BaYRcQ53ea+EYz+D39wMQF+2VLGG9BzPCXQmHJ2BN++
sU1u1K+UDFb+ChuGaIcHgYdp2EdWkBGBvEOZIhPkg0KF8VYSDwhZagVWtanAJT6o7fmogBCI0lFT
mNEms/VyAGOgdUUPqm+8CEDd8qoSFauueikqq2X/jR9XQ84ShF6RNGIY4JDf50kSZOMQJjxPgh6f
aN/g/CND4MOdV7wvI3uOc9qymDU0KyS0wNSenB20r8UJ+15QsY75Y02Fxhl7FR5hp0Ez3qBW0UDa
n466F7DfIhjrbBAFnQq3tv+hdXraPu5e054FBU/Nk3K1kOmg29OOqn7S7rxvHyCAE+vQnM9HNaAq
W6vd6q3jY6yL6qsqCm314kk0cIruf+xenJ1g6f1ZlidjoOtp1I9v5jDiqIRUbBN8++B6/7jV7V6f
tk7aWAvngk5yq5I71cRY9EcgcCC7ObuhnTXp/WIdbWhtUEiavV8kz97HqtXaW231ZRl7pVhmbsJA
KUIyXyhb/g5ZyUEgStLGTRpF8mg2IwP+ixdY7gLIjH0dglHYCx6S9O4GtctsmMxGAzpkD7M7osZE
wEICzWeTKDCOT8m6PKQty3tWi1XwsJZo4QGdAMxDXxxxOvclYueD36binSWHv+KkxhC/8RyhNbgP
J/1owGcLlTozWM2IWx8vzsQKuX7fPjtpX3QuvcDQu4TXz/sogV6mcwCGfXBgtWGZnLQu2tf7Z502
Ygcg251SkNJDYh8mEnCk4fMD7iBqYm1cSwp24RqnPwC8g2jyGpJ7cQF2p33Y7rRhKFX3r4/P9v9M
66+Ichqh/gYDepz072A5FcbzY1fziuv3naOD64+nx21YMh0Y4dbp++P2dbd9TGsp2GNjSaEREGPb
gr2gevdxAmSRdSSLkvyy2PZFB5bz9enZqZTKLUS8/YGxyPLTZCJWgtmsfxaOTmG4uGPHrcuzjxfw
BuT+k3PoGDLK0okGjSZKj4mUC5Dbn87POhcMFXRZH4joEU+h/9JXdcWeTEh1263O/ofrg7OfT7st
RKWoj9qE0Y1QbDlQDk8A9YfvUTuzwHba7/HQ8bx1cICDiATdXQVZbHfhYADDiESNFLdjgj6BAaO1
cdhp8anm4VnnmuYOCFvPlL8NUOEucM6iAc4R7vk5HW3C9vv9y3UbMUiE25OEuKpNTSmF1v+4tHUY
zXcfj44P1m2vRR54kWriIqEjP0GXW83vzAFtfQKls9vFObK6S5N4DpPW7vzUXtlo+HjCjkO6zXOY
PhR1qMmtH+wm9an0xREQP2nASB7bdGC1hcr+BhqscDA67Z+O2j/DyAPXOrtoYa2uRpEXElT97gfZ
wP7ZT+1O6337+uID4P/hDEauu98iwoaJ3pLF4Nv+R3+hH1Whw7Ozi/MO2plOWp33gM0hjNEZsuTt
5vc75pzB1n70/hTQ1FXkgBLM7e9WlEZ2b1fRuOKYqSr77VPYFa7PDg+7baeRly+9NYBHsARSrLNt
dJZHm0alrBMvl5YtduG7H02ERIXSDmwDbXoaQBHwuvvxnBidBX5r60dnOqns8jk1IIsd0Q/7pUsq
orQP+g/WwHc/tDrO3HYvUOSwW/jBIiBabFCWyNczlD9a1CBLU7nueWvfXMGFDvMCBxL+sxf01ksW
PNGRD83DWvLng3l0uNsTjkPmhg7vYXlv/ygcEpKRtxj7bWLJP8qCWl45OSmxf9qFiIsQE8H6aTIV
u8QhsJskLQPhlkNkm3/aEmAA/W5/GNFZlOMII7vH39G4uw07UT34rtlEVxDR26W1udcaQKsevKsH
+wxAaAZCxUCl/8/RfJk/jlHSPJRzTWlkdaJK+7qCdg5RDYH6wT7FleBtQXfaFedQhSqgUFrlUVkq
LdwnpcgqLzSmXVvZ49Gk0gJpGIKlY2GWRbKqVMyea8lkmSwwMOWXH5uSJnqjWdqNb8fSVlFa3yqo
NvKdlwKOcrDu9sPliCR5NruwSrNIIDGCDf3dKOmtwkcUawljhsJoS2IEe/RacLiYA+clDZDw0kHv
IFAKYH1qy1FWYtjSJYSDqlBIjUNiQ9utxnkNicenMf/hD8KZybJFgY6KcoJ0Okek9vH4X6NDzOsZ
MScqCgPVEV3wor+epA7YfOPVJd9S99aWW3j5qBM4RLZePHgr7d6zNSTimtYV0mLH0a78kQ4+1BAI
g/yyaRZW2ODN3vLxZJKJDM2ohE6ELzJ6+dF0cgWSIc3J3LI+X0BrI/Oj7KfZ4EE8ztryToK0kboF
hfTa6vejaR6yq6m2pvrwd2yhJSh5aore4riiI1u1JsbSAlPo+FqA2PHlS/BNjGZd0aeq9ExYMigl
0PGnMlLuiaVkWDiXVJN2UahFK88s7hts7/i9CbbM5qpVb6lGyajVTO8yX0305LAOvNdThsThjtl5
OpzpROQwsIRYZGdKZ8L5Xhwop4AzWtvud4eM3hiOFWtMufYG8DAkDwJrMiNz8EAnPtA68ROH8ZtV
47jCpvNfONpqJJ80/msssN99khzzRo3drp9imYOt0b82cM8sOdkQXNTUP2RzvBuxBrLGoLE4yprI
6uHbWAQgQkbBb+hjGQn/T9nbDZpNn+BiHuqRLV46GlmXmQyXvCYoVvvDJO5HqkS1Mhb6yYGUJwN+
Y7FZfFUPPhtaZt0ehCv0KcPm7HqVD8kDGv+jDJdnnA3lTU0+R7JRAxXUwEtoqpUOjmw1x5t+eJk1
Cx5B0MG7beigUYMSLIBVHIzZH3N7i73RKqezcS9K8X4YzZS4v7YWIjhXiMg+68ToMTwZ8E07bB4/
P6F5oVmvwIAFfQsJR7Wu7NPVU/EqqE5nk/6QDukZKbM4lB6PHQS3mtsDVhLqfK7FABTABt9SxVNG
vCeKFyZzuoG1ElOPBl/Zh5dAo/QW0XPLQJFHcZlWHYp4xvM7Rvd8FPbxTmWc9skD8V9xTQrogQtn
BflbFgOgNTwGIxXVal6VgkVg2hVsI0HZIoDZh6HDy2RBOJoOoQG8WpbqxlbgqOwWmgz9aKqCvyOa
fau9FZhahozMZit99db2I3VtH4C7ZWyou9aOumXOqLvGirLu/TyMgcDVEXGekP+UInClTzt9ZIOG
ORsFW4Y4TJYeXnzPpYrn3WEji5A100lxvWAycTD8qO6FxzcaH43yXsAtrVqCrlHFngfLjOIWxYXo
XXp0XoRrj5yBNAwaQORk+or7KvQsOwzi9g5eoC/tOIRf1teVfIH5K6NYeR/OQB4EHRFhBL3oBisq
s84avCt3jToVZeYJMvHKLkMI4v18L3vdYbzI3MfH+UAYg3j8IqOLguyZ8YpuF0ABvC8NxNYvcq0C
ooaFCbE8iSdMxMgL4YXxmRAsXvBfNoydCCdShAcgn5QIpzic8J3uG2Fjwt2M9HED9kq8tUWL8A4f
bbz15/XwZoxfbnkQp2gSPrzxtqNy23GueA/ElUTzaqJCn1/y7UThlNeQoTT+IIJoQCdYFDNcrMUN
RFOA5DJ8Q9BioT+hW7wQymqmZKlrmNKJKE6ykiVZLi1OEo0s7tj1dUVn8lRdSxARUDzW/ZWACnKC
6q8y0q8aJr15q86vXVdvqsLpzWODXwXD2e4EEkW7uIbj7CYSF3tLMRGyTOOrxrTA+BmSayFfBcZm
0AyjYB1fBcThpQzFtoyvAmFyOVHfsoivrG9wG6U3On7lXQ5LgwsO3Uvx7+IV35D99guuqwVIlN9+
wSUD74kP4S9zESyC8fiVN7rFpmI0vO4dzdF05FL+gW3T0w5219lkQFdxJ4nhtqfj4mSJ5EUP8WgE
YPgIHw3vFeueY6npxb4EowbGxgP457df1tBuebRW67WLACMIReFAhuwApeomvp3h9WTPuItuCdfH
XtQPOR5QMELlHd39YBLzSAy47PI6tvOS3kv7eDCMRlOYUPe29JQNR+iBvJbZfVEjJ36aI3Sqwyvb
D2ksQhdZ3pWiE4aZY7lhrdyo5qMvZ14HykWcuvX7znFdb+CeOTXJNo166O/y9V0vWALde1RuwYKZ
fi0D9arhxGspY7zrWAzKU9VDZuOweKG/mEgsat4xMheOtSyKg7cW/ftoRFE/y10G7TM/Wp/q/V1A
mmf2hnEK4NdYIm+4r37XxGBKyeheuQALtoeypwxBMUkmrZuvvrCx/mUX4qCTZoadyxBitRLeVGo1
GwFxo4PObk03TI2gWVzfbA3eBtWtZvO159YEIUNmvGDXqm3dUSi0AwOBKHuEmj1XnZYUUArL1xXT
Cru8EVDRV7awuvPrt4cmA9kglTYuiBh3q7/Ym+My+2phi1Z30U4TOzpc4fLKqyDT8gWvAdy4QsNX
3dimf+sYqXuopqQj/1s72krhrm5lZTebQfchRmf1PAk2TUf8TTQmbbLVYrNZsTdboRc5t2dd3EsH
xJhYyTvMurg8HsIJn5a6Mnkzm47ivFqpV2r2BVFgCGM8fjWcHUDKYNQYmrzmbV6ak+3Fg0ftucOT
hHCI+5zdcAvRr7NwlB2RKz8xGLoZ5gSlQEBvKCRFSfeR0wwe9bEu9rt4V7JvG6Yq337BxhYVEkGY
leMNs+wunk4x/J0bkMDbtn8VPS2Yz304Au5vo4c7J1pIBiadGDSCe4O+2la0juFlMJcz8J2zkn4s
vS5mXE3rcITGfsJ3aI0WrSCP1JSPV65qSG543zeDY7SyCpkz05scG2bpYyvlq5so01Aohep2szlx
qVgHwViIEGJk0fVCoMEl7mKDcYk764njJYZNDsoqWo8shPQfqzcPwxjvGJEHu8OOOEwMadnVKp+D
ViutCtpuYZBSoK3nQfUx+Jdg5wcnJEDWa+Kt2TTHO+NVKkyQ7FKIyKMKqvMDBjrYLtz2fwxe4xrr
pVF45wkPAy3pkVQ0SD5n2PniaKogbxlbIcQI8i9LeNiu4KbvTGx1gpu8NVPwSjQJ8rPRoIWA8EFS
1hDl7VhagQ+tlAlExf/6uv8kAetFKSm42h8lGSh1WS1YUfkrW8baaH7V9gkKsCkiByHTmfM1aMtn
j2ZMhvbAaF3mDV783RxFk9t8KMICiUtW5OKuwxKN48lPAOgQfzTPz7pHF0c/YXyDw6PTo4tLMmbq
76ft9y3re2kYCKP5QkAIEQ+JNr88NCMhURQkqI1I1SRq987nN4RTTWJ277B7ev16T8AQnVbuOPhV
xbGavIsp/MzOyx+MaBfDWMVHoVgXWOhqwxOahFpqUENfNQ4UEismsV17UGBwDMYLAw6ZITLwHiqP
l2hURxvBa6iqkhkXA/vyGdq4ev7cGKZRAos7RyVNzIHA0OxkNhuj4CCjX3lDfWCLolei+HPgmxi6
LKYIHbqxh3cKkoZP75jC6Cb/lg54FmX5OxoZp/WcW89167k9ptDQ8z1uP7fpClHwh8diDA8xFCuN
SQOg2FUPRVWbxVIXoLVc9jgvRrTCPlK5FyZQ+fWQLiXz0DWoHM7pw6FbENcK4G9cNgaU4BfABVI4
rJnP3tWCu8sXPdL3r4whzq2IL2LB0NqDvUsW0x5tJqFBu0SA8uYucEsMkgzb5XnC4SAKPErFzpya
MfRAkjKi0FGMuWJ0OWOVAMQktXjci4Bv8Lgx50rXIsVsc6K1reJMb5AICFeQXF3Gg+89ca+2Jb+l
7xR5TYcGZFHbXP5O7DGuxD6vmiGIeGPOR5yOqVz9onUnshfW+AyNXhnBk4SxooPWmFBFGAehepom
g1k/suOIs2jYkMK4Pi9tBu1Qn0wjRIqcHGKA6t4c45VjzCZNIRzfEi2/GNy7l6JVjoM84yEj2mIA
QRiCMI9klMZwcK4jkCNhZem9oqdBph7TR/2og7WmD/pxWJeIHg0eVTRHmhFnQpjS0kdjzubeEnOj
xINdgmIOpg9GgaGvwNAoML79GTlHem/c9Te/ftBfLZcsUruEQGjsNA/EmbWmRb3ccggYOubWG1K9
uX4x99V7BE4BTbwhtGvcfepBQ7YpwD9HiFTsQ40HgbrSkE1o7DHCCeNDT6RHEkh+/0bUxF8PGNyS
noYU5lIhm+OVc9rHj0AduA1BZrydofdV+xHtqxh2YlOEmAYa1Z4KYglgkNZZnsUDtm7HHKvkce/b
L+njoh7M8WEODw/48AAPQ3wYLjZrOjQoQ4d+WgGtmxzx/WgCsvSkH1XFRHLokzpRcgwkHGNwZCDa
eKghQq/FzONyYKhV0UytGJIUiq8fklR99Ucm1WKTFaBUMnfkBL6wcvJ7b3ZTFk9Uxjf1hTVVfeIg
Sfvai8RYwLadA00OfGtDhhg1iNUG4wvLVzRATJBDiROe1EMwSpXGw4fwPoxHHOhgogknIJZX5QCO
2YLRqsnITDZOwjTTj9ePv9ePMbzSTSHaXiaPOQsbcxWK1ylI5cvB1wTO01slAPJGM+Xd8kvBECdQ
wz3FjAgnNijYyAnpWqEeEhgKv8915DqqnP0KWypCs6ssvAHtxLb4GYHtEkgYwl0axV0cyAT2oXjy
aZcWIP+4hB/zK7FLnoYk1XDY+cBy1WkGFIeCK4EYSeG8Gr15g+N6vU+T5H6O0ud9Mpoh3SC8PEnu
gjF7+GBURxS8ZnkkA1Zb4bJeycjQMn9BNoynmcCljtCyRGQYyYKciXTC+JL5FlkfR2rB3b2fTOeC
ojHXQ0DxwDHCxTBCUOowj4a6QToodInzoPCGfCt6T45PRTmP+Az/GdaNQItjS0/lFxwVcaCEJoQg
LyihQU1wDvhVHRLN7/90/d3O4b7g9nZwEiMnQHM640B2GSmwoBzmdQJe85aWPeLcIlyaEHRNeVat
6DFHpxxqIcNKMvTJDdLByEQNPjbRmBAZlhiDrL5vqFQogUqFkgFhHSZJTnyJbY48ixTtBY/TOS8J
J+tAaCGdH5owiJ5Eog7MnCGdyjCEPexPtxwcBP0kRXYUCtkGWyCCm1L8DGJpQB9hkEXoOwwjg80L
ssaUIhgBbt54oO0CnW5BLhpQipKmcUnyHbk1mdTCi9BLLWwOkeSCtJCl/RW0cEJ5T4SJ5IRJR8aV
YWrK7gwQuhL7hno/ySjZxS86m4z7lS91cmc/X9kBt8mUThu0Qo1sXQKY5/PaFA4DVKchteQgczTM
yshqwxTeoz3vYj6Nqly4jgPdzPGFGbAWyAm2OG7DhFqnUYWZODm/ft+uWRaOybEcV0Xb+4q0sdlu
zssGAfAccDxZNO3J0a0H34t57tbscMp6rDiIrhSt6K1h1i2OrQJeWkfthoQWhgl4JR5fy26JF8X9
0QwgzpK+iS7u41WqCd3av+5etC6uW512qxjKlWGUbKXiWumntds4bh9e1MrAXK4N5uLs3A+l97A2
jJ+PDi4+lEAZrg3lQ/vo/QdPl3jlYahtGsBdnoviqSCRxqMZadSmE7PNrVpJ/fl69bdL6o9J4MB/
yRgmnkFZgtEsRB03Kl1SQbbRimeshCG6t6+Wy0KerUnF7pYrRF00teYgjUaRHSKbqNRZXaquOxzF
6p9LuUkZM7iyxX11IVsKzrHaY32iH1GGNFjJGKkXtDfqXXbN/amvDQ39uX+ePJIvOoEfdLVIlNzk
F4LrylcU6agTDuJZJlxRDRNFSu+LdqPv64Y1gRvB6IYFWDWtyj1uWTGvPTa2/iOQE7do1ts2RXcm
04JBo492gULV+eom574m51aTw5Im574miSs9op8z9Pe5OEFTjGaOH+bGB0ya8UAiKVoVoAw+1rzB
6qxjgR1SYWluDEstv9HK83HCuVl6qEL3WIcWt8KB2AzpRL7kGldOwfsYpC3yCigrq3evOStzI7Sn
9Ib4UDyIwDI0CKO5q0QO6Bt8wvjPMFb9uWeHfBRtoPWm94AP/j2RTjSpnUdfIHayhz4abT0WdkQo
8gzLPUfEnuE/b2DsS8KpazWV1EesgPkQHr366p6xFtGAcC+TcRRWNk7T5xFC6z1QX65EIreC0qll
QRF2Xwl83GF83SUHtb2goWzFQlLsS1qgvB0HaHqoOvbtTMYgeCUeXwsSE7+dmUYvIUKePl4hgQtK
Em8840iINDkAcM15O51lw2pmu6D6c6cIIeP4E9E+bVr03Nimmx7HKH30hvz+kt4XjBmz8RFHDULl
A348Wr/m4pd7bv8NY+r19zDs8HJdKrkDe5dMq0WRTBWW1kn5GwiIx76EGInMxGjLSjQFPCXqVUn1
YlWH5mRvaMHLUtJ7oPdQKxZ8NApiCgOm6LUXbRnjWH/lifQ2tmsEzvNz8+BXvn3E17j8i5/m+Gle
+IRDTjyJSK+mKHD06Cv2hqmypojTV2wuoF3WFN06XRfFGNplTZG0rxjysW3imTVzSalJeW5atC1U
QSNwF6Ixlb5ac9nWsKytApHInixvzKxWlpcI6ZxmVtl6CkQu1rmyJew/8ukpzPsL+lteci5KzktK
so3k7OYGE3HuGYbDqtka7jd0mFp8+byQoUK1jDuiU0u89NhjmdPzsLwIyOoJ466SfVh4vnCP75RQ
x3EjRYxKWxTnJt7ojcXld+aOQ4ULX9FiUdjylutRrMMQj6Al5ilBmg2XwNXlhQFqzFzCuPTCUCVw
TdV96qOcuF3zx7KSc7OkR4w35+Sgu2v9tgpf+XOo3PAxukocuFKS7PWSR6J9qteU2iA9wyiKfE7o
KrCjBAaj5tyseWnUvFxWcxRxcjaMCWqZyjcsPZkItsHFkegJtrlcngtQz0Q39HiSnryi/tytb8wH
uS4e0BIwUlbpYWkE5nDxitFd11/VMJigibiphEPh7uxzGevlBk++0Cmp/n6c9mejMO3OppSI9Z+g
UCpvJNQvHJMpeocZGuP/99U8rw7mqF62TKokaBZgXsHf16gMwoMtMz9BC2I56RX8fY0KJzyUZpz6
KkUHoEIPQTNxRCqUPNXE1/ys2xCyPFYRKiIOI445G/1DFN69uEnDWzw3ErdoMr7rgpm+phgFHxN9
zSZ4+pXqHPNBhNFmJv2IAqp/nIziOz5rEI7Gsrj2Hanz3Wbh9kEIzSbxDZ4Q4rHULfm7kEsJpmzH
01i6Fx14TkvEKZw6xwhHWSLOSCh/ALR8iwsToYkgKuoiEiB/z1HcRVAOTqGO/4e+53Gf4stzQned
Md20IznLfm2D0krTkUs+NqNweMGTof3/TGNNpqE10U+m7nlpqp6GUf5xmy26xBbq0sY+57f0+L8u
L0KjC8r0Lit6bXAivx5bovN9YuWOsXA/XrJ65/kohlrNPtvRH2uv5MjrbJbwG7+41S+d6pf1YM7V
L53q9KVUyDNzsLoZLSThCI3mE0hgLGcEmVBdLuUrS/iypG6SwDJY+ShJZSslIyEUWfKQEIVKpCBH
1DG0pMxQjtTzc3g21B/5XJPOEu/DaaMXYjiY7cZB0B9hNHq8WADI48WlAJ0h4aNINiyYbfWusV1j
/o4n0+GU83KcoMKSJj2Awa4Sd41xFE4yzsMhYGM60z7dI83oOovcRKpR87ZZ5yQ0wPMR3kOcDwMV
j6uBW8w0EGFukjSr4bVNnIJMYilP2dF3gqNkUCAv5v/84t38mNGGjvMZN3eNufydyVwnKhOwGYUT
qWhiU9Bn0e5u8Pmqbja8qzzUt66uVO07XO3kDUdwti3mgRc6jXZneEfsRTBx9RGjyXFZm5MrM+/r
EZS/jVLY25J0wAfX6HUn3q6RLZKqcfpGvndjJiqmj3UjNapIuCePo5WpXWTjDAFp913vCi8RhVmw
T5Vwjs09AHOj0jT/JHAXr9dAXdQTuSedZmW/rli/U0nQq0DXdSVD1IJpGKcZNaOvWuONqMY2FWXg
RksN/aOxfWW45gnKm6iLQnpqAM6Zb3awypJu4mfRVQlBTJQ6MTEnS5Yx5svdvW5DozOh2ZnQPrqU
5XtG+Z5ZvmeXF/TrkMdtrw5N1nj4B1EG1I3hWsRBqIck+GoIipxhGkdW4vU7Fi1oUGrGnZGeWVrf
HFFvl4yvLiRGWcPicTZGvTDauqwMB0To8JItJmzlrzx6rfIyQR8FdFBiBVe1rmZwi/OjwePyyyEF
r0FhjjcBWN0nl03UZIwBMApfucKLjaQjTxgVC4oPiRTGGPAw2+BUQR5KvZD9JVW23OCu1c9ndJDg
DCKaXokE+5STqhdB7++QZ0/wQfEHJEZ0AFTbTRbwPodRFwWocYRxGDOHf81U5Ezx7s6cdJIgTOq8
e0oOXwT+OZSjxT6YDqszXEC5LaO8dftIejrR3VC+snlnXtnsI4sXIPpXFIv5LSMAv14YX3ZlIPYN
7+YlHpwNjH/U5Tztygcps6Ck8mcpWqCMYNxYwOSuMd6DCPnCw1MkhDsCuX1QFAv4DwhmRxSx0pAR
fmLBqFRSkN9/s7zgUTF8ggK391XiAle1hAY64+SRMw45KQiAyB1u3pZRdK5Jp0jqPmK+KxCzuoAE
1LfDeaVjwzIq390VrPd8W2dKt+3E2FsHLXSklvhuh0BNu9gw9twAsUqJNm84SBQ2jSnGHVk9c8QO
Hs/Po+QKkYQeQSWEQLK6U2gYYyH8Wkj4Xdw/xPw5oyw2h1f89FoSMf+2B10Gvu9Tfj0dEH+p5zm3
uixx+32wlBOZFwqt02jnBP6A73wKyaFwDdY+2JSY/sKY/sJE9ksRR1PFllJE2Muq96gnifn75crj
A4cKN57TCczw7p6B5eCV7M8vxo5m728SjOLB6CaFtWri/j7PAR4fvwpCYxskyIuS07xvZE26XQFT
/qZ4K3L1hrR6U/oagnjaFrVsm7L7vf50KxsAzioPjZ5lVvLpSe1hv/gPjGAX6uKZgRYBhMhKOwvI
FByG2WC0ayhha3DI36SEyZ6i8iWfS5Uunn3Vk8L8O0rZ/josHz4JmfSVfKau8XPR4SkZDbg8d5uL
mboEI/eZy11x40dGtBILN1lf+BXjG1GxwFzLxG8/iy0j/qIAqzDWBO0XjCzEVdwdZ+c24UvZ6Die
oHk/jW7TKMNwa2SvHD/D3bOHVwxkVvgtUl1B7YrvI7zUMkLGNZcCVQJ16QYQSG9s3JGCVBiISAsU
tyMQQQ9G1OxhnJ/dR6mOnPMFITomlbklJFEoD8f/3r5pvJ70iwt7ngkmKpKvIGAMX/NK3QfmEgvf
rWBxR/d1sOORQ8L7W5a17FBaaE8GEZfrCwnMAOZKYV9MIQXGDqEuHN1kMpUNmYNkm7S1PZuePhmP
O8sVvmmZoEXWZRycOtEL30G+2iiYfl9pQ+8r0bZw9xG/d+TvR1OMFBtsNElQZp2geMaFGwyaf5pl
x8DC1ASpXZkg4CRtR43tLSvs4FjY92Hj1cbaydQf6mlMcdgkGpcWGpc4i9SQFoxEDAAyy4pKNQP8
xpJJxvIxWeV7fJUIKfzXWcjqiqlpiHq/mlev7pX6QHoUugKywF+8xG5dU3mq0C7kZn2uQ5ZoZVNh
cMY9dvr8K1mUPZ9qxrnPCmF7taC9RMiWkUM8snVNCNcDU7r2iNc1KV+LmRlHgzicyBuYXWGrxmmS
W70REqFP4rLaTsp83rXerBDAUF/WLODlq/jmpozvbfMCpgbFWJctZYpCRp7GZIQUj7YVjqXXN8LJ
ixsG9jhwGCN9WEpqVMDoh7wbiO8/F4NBcHEmFfZvqSmfjCwc8/2eYxahbdskCcrmOuiZP0IJFCVo
Ga5OIqOVGDoGQMtmaM4JYembkB6u+97Koj5OG4Zls0QdCaW03/uMJFhA1OIo5D9qkecp7LWgA5zi
Nb5ekqLOgZfUadD4Vo0xauKCmzFy+MLa73wcZMJtLN+HTVBl9Cj0oJV6m0+It+GXyPMxdu6XkoNM
8/iUhwJGvJkm+ScjvAYsDvHtl8I3L7S5C+1yCbTLVdAsZ8fCKW7R21OuXFTxpA5ak+M8KNELxfjH
2SGayCLyycKoomKaXzOYIvWJApbcQ5S0q3axqiiDd9VfYi5IQawxZiZvPcaZsK/ui3P6wpaHPpPn
2ltiIuhacl8PxxW74NczXFIEDW5KC6FoNhNHu0bBejDFAJRs2KwrIzaeNrA9j+JLk2/NLs3S1cb/
Qhy+bAS2v7bbaC6fpfcgi8iTXXQhAi3jFqOLY9CZ0TjJcnIYstJvNAPB5RqSHCQ83m3Nc+SQr+dn
nJmNAypwOga6MwYDLX18KIUPqOgS1jSN7inXE7XPiGJEEnKh6kUUYY/OwcmriQIuE5emnjQtgTXG
EydThmKqJk/gly8H+gjfJW+xmN8WPmDN716ihkGzqMiIDt7FKSJPoBtbQyBToHhV85855dJ2S3Fa
DQuPYhcKC5UVVxmvqlxL0G2tCETV/bxlBXyT2JmR97SXBQ2H6A8N6w+UstZ1INrWW7/RWUM5pcEW
VxgH5rGpWG57RTx8V1myUZJn5ZGzOHTUACQkQtiJSsnIYmQshOI9USOs1Wp3bVcUrxa0/HhSTkOv
PQNq6XLC+eNngoPBZhRAa6cQhPiM3ZZ2dT7CAlFoABTyRFKFVH0pOUyWi6NI3H/Nznq08mJTVgXU
auwW2K+Ew1YVmiu4+Vv9twDpVbFEoWIgVaPRhjrc2NYXBvwuRmJZ0h93XZq/TOcjvVL1s3Gld7c4
30owD2d5wgE3SZHkgEa0jVvbtxWhS8TGwgQR6bLwWKqAFcFIhQuTKZe77VZn/8P1wdnPp90WJlq2
U2uc+jJWwIgi5o0RusoiO5VZ8DhOSpibuaW+/TLIMECujgp1S+Y6M5xZlbHl4E88n/yvFjKg2/bP
D/WSuMo27WH8koOuxREEZB7aF9BmjVbRjtwIAMOmiOdkBnmpyvd1KvHAf4Z12YbVrojdsWfHLFUg
sEU7uUohaoYRMKS0ZQZRszYHcUtmLzA6aH6X6Rww1bgclvMjurPLNYX2WHzj7MkmALqATeNqgcdh
3frO2F9BQBlgABcyYbIKYjJHkUqZt1mCsLDJsYVkJyKUaVgYtFn/knkLxEiSvPHtF1PfWQRp+MCf
N3UeqQIEYNUoi3gzvKjoWCZGHI07T5LgJnogmatB3t/U0isUb0a4WoxYQ8ENitUUI8uIIS950+Ou
WAZz+fCw6y6J4W5hVSSzFLlR5QGISwbgwrax3cqVezOXTMRG360g1H1SmTJje5svLT63igtpJRxo
anzGXKfTfo8ZcM9bBweY9hXz4nbNOr+SG3VxK3tk/rBtE+OvjztLSv/pT07puR/23A97vrOktAvb
8P5WO9OvdPceRsEuuWN6VjmTih16Xqgy9wGf+4DPS4F/qFOPBHBF+9VHNBg/biPNq5miNGRSUv7b
34LqHAvN7UKkd0j2ufZKwZQ/ES8VEgj+Z1wcRkPoyQ7NoOs6NCLGisCLITEA084okypU5OFRW1yR
4DSLw3lG1ykstYw5VQhj8wDDlIkwaZRlCNO0iFyIFOFqMueqmH8NtTQO4TNoBhd4kWQc3gF/xYY4
cmnwkKR3Nxiuyri5IiL6UYQrlXaWjljvomiaufmcFJIh6oRZeKNHjbJdNpU8I3u6b+TXQpHmnyHB
6D39B6IAv0Tj27/kqHLwKDKSI1pSGqgSDvRLrRGnyhvKW8+JlJGXXRwdt6/Pjz61j40gTRpLsryb
F2+xuWc2eaKIWg5ULFedtYgHGhcNEQNNINEQxcvThOOVx75GHCsK/k8Wy54qW203HXUlN7TQctmK
1FAjIOjDkwQqdY1MCjvhoxB2qLQV3wTrsZyz83LgVDLIXzFjsz3GdPvHgV8+ArQN6YhybBbdiR9w
8Zg7FJaTN0Kdl/oqhAtlSCvMB+bSB+ayBEyYofBnLkxCry7g14rKnr4pZRUsOIJFv87iexCTJ7kx
qMJVzVhVFqLymrkQcTVM4yuImtt0p9B493pPzfgf/iD79BqTWb1Ew7BlF/Dg9QazgLAJ2fOVgcuf
7h0ajwj6gze7F26muPj7LGbS2pfX8ILZRIVh3eVcxF75WDEIHWzxVTDLpEJHTD6LooGRkc89zliY
FljuUql86BLw18ze4mmHKIVO/zNOUjyN/ObjFBVPTcHG0xC6cej79gt+Kz9OKYE2XwJt7oWmT8Oe
dqoi6xmHK/KVe8Yi3v8eRy226UiQuME+ikK9omHc8H5wdh5pQz4XhlPf8U2xmWe0hS21z7lnPMoX
Xl7vVQtVGN1BxptlM4pgB1s98Et4AP6pMyVHGAgXpGdl1xcXc4Wop4324Q3i6EicHM63hWSKggAF
Z5WQFI8J7ynuJ15qJt+8wS8hnh6Ie9KDWSrZCAa+N6FPw8w270uQAzlmhrkGNgbfkILihZuHPSO0
6/9oz5mCTXmRfS2RvvosmI3Po5SsJdtbW1tOdIhfd16uJpcdh1x+/eMalf7oVALyx5w7bAWsIogG
tu7ZOAvD4lhKgXnKMCeOabS4ybwIvvvBFZAtWysds5BGqMBqZ1GKxiIZudZMhYeI7lHNjOEWTEcg
/pEj3J41Sxi+vLn9krdP4z3smy+Zd+iEaZ7pNDZfowcEFLDc2Fh7E0WbEieCauIlyjCvVv6l+d1N
pW5iVcO0wcGmMRibwtzk2W7NXdbArawZXaS20DmIv1Hj5lW2l4oFD2FGSeMUCP9uX1my21PCzXmW
R+NmFuWgMcDcAuur5OOwKcIoz5tOenFrxIxkXLWnQ9tXY2INUAGoQNxoV+ck/W3psU7CeLIrVAnM
iwh8tqGChf+T0mMVck+zflhz02CfUP5r/sh8WNMNacbt7sXRSeuiTfY2TJB5AmU7lIO30z5sd9qn
++3r9+0zeN25vD4+2/+zziwq5S41pqjfE1ctU/qdIKluRe1M6s/e/JFIM5QETbudguFYTlYuVW6T
l6sR8gLp3ZsK3BB5+ZDHLGByLNEZ5YbiPxa0DnP2XHDP5J4DjFjsQ/bE7pudRcbEkBbB9DGokpEM
s7NZZyzexL6kwC5L+VvIGsknUrAyMabJwKWFjAJPi6JG/ujO2VG1GPyLLv+TPYfO/rJP5NTIXSkU
njuFL5cVfnAKC4MSiKRCxyipN3TqSTtTacUnj6uhv4eTian9qKlKUF+Bj8WBMxVrHDwoioX2RXCX
TyVF58Wil07RkDLKwIygfkshMjIU4GEg8QUlmIEZ4OfX9Awfh2XXYsr67nGEWDs/OyxStGGpE0e2
GyPtl7UmttlNx+sb5J5OMYX2YZJiNnJ5CAp6Xrtz/b5zdHB93Lo8+3gBb667R2g8vEaOSyzSl144
eKu6dgJVOu2fjto/w9fW6enZRQtNeN3rw7PONbXCoA1VQElMnPux4KcgbOvCDo8NEOM+7LTYOoiQ
LzofuxfQIiJfs0Us2Js8XV9CrEIc5OO38lHT1iqo/64Y5F+lhoHtOo8eVdxXIwuNDzGvnYN3gZWz
rrqE+XF5B4Qx2T9udbvXp7DNLSoWdWGCUrKfC+/izVUrvJCEHKDVhR/6U5e0HjUM0F4WadAT+Dwe
337a9XABb8nLXQ8T8ET3k7FTzh93TXqMC9y0HlhvJa/0wEQRWI/dLnnZFkvxgQmN566VB9kbS1Bn
Dl+D3fgI6ZDOZp9ASEQgBtHURcYRFJa//bJ0gSxAM40GdL7FOVuDMLiF91Le5lMnnUFJSCSCDAtX
Kswk8cUN193V0F3jU53+XPKfn/nPB1VSJLhlntqlebCDaK7c/W1Sl+X99E4I6T1Jbf6FQpduoUtP
oZ/dQubpkVnwg1vQOkXSJfUYQIWKGmc985XlOdnluZdy3vE69Qh3E//I6OrNR++oGAXm3hExCjx4
R8IoMFw2AEa5zCaMRXFNHbhJ46q4vjQ8zNxNGeOwnzJpHHZJ5o1D7GXqOERU+gkJY9M5SOmcoymU
EgD6tRYcWvHyHbkOssyQCCMUh1QyASpXpkoWRANYg2EaTSp50B/F0ylMerUPmjGt/VHjj+yO2hjH
GS7aTJy/1pqFm/SGf4N1x3LpQed6h53Wug4Hn5wAcUxAxvm/U/7SU/5ySfmfTWuRyGFI7QpGgoIx
ptksqf7Bqf5BVBd86INZvXDAc3ODtHQfMVEd6ANLeXKnXxWOnabTNHnkivpAlzv0ohwyHfQy3stK
2fdLik0tOwh2eMWyTnpPibkPz3h0Vx0Pe4XsA09iR/KwlJsRGn5S+4C4FM2FqTyDGup+NgSphbWS
t5ucYVUHvZT5AatADbS5aDdDRtTa/paeUpeiW9fEK4gQRpP/rDimtoZQN8n6x23zYRH857+L5yGr
4XoAKeHrskGsbZpD8+comgYorRcCiRqDhedOpvMGsLf+zGJtOjPaK5A28DRA5+fLptjljGKaorci
5ucjr/veKJzcgRRMN5Gt0PUUqBPklHQugnme86G9OjlHGVqUrGHCmUlUZFk2BErx7h7aFxvxTMDH
wtBkIrAwwsRJKbbkbCU7zYAS9eFVA4zDypDi4B//+/8hj+OSmdAKOEeON23N9ZQs6tXVFpzaMp5S
sEQoL1WjmWd0ENPFT4cyTImVNxDN3eT97ZPMcpGXUNhXRTimi3gcncSjUZwV5aCv93ktTNg7MV10
452Kwg4vqiwMXjNowNIBRvDtl3I0YQ+hviyCcba5XBSz2g+yO7GpFxBQbme1Mn71XZNIy/A2qYqc
lMXwvQWyT3yErrxTNrzRZ0V5v1uw1TcbLyR+rLiQ7PzbL/xtgbHwQ2LnNsRFzV4Z3zeNk0Z9RmGx
33xrPUr6Wv/lUm2txGt3HZrZYoJxusq5LZFD09lI1aJFylQKImFY869+06l67bX9G/2tvT7XjpM1
fMBxtrhEwXXJrRM+FuqY9px1nLMpDk7RacajKfCws0bM9vZ31syq06qBmOAqTgNO82fStREuKArw
zG0sYHt6/I//yyHlVjABljwIDlowwDJHO96JoxMaUMUxFBKv/mmYZhFtq6iY4yklWULmTRNeBw+g
QLnoR8zJMKgqKkg3oxkqGH06vxNn8joLsnChxLs+6HtjAqRorfBe5Jan63UhRoXls/w8TSa3mACe
bFPyBBEElbxpcX/hQo2HNCftznu0dH1onZ6CFMjnnvboBq/Xt0063k343zfW7fGqX2qqi+jmjizl
25hCpATMa9/5OpmugOAyGc+LVcE/xdlNCy2YKHt3yUIBfxByqxhwQet32aYqdRxZ9qLUAbKARa18
OynORsFHco1OrepQEelaOQaS85goaQZUtHGuzZCWBAmTpnK9CrtyEe45mBnW8R9866S0tBGD3V2Z
6ByEfiPrw0M3iK0tv9SjD/+Z7BuSBabIx1BS8MJcmF6nm06qH/JpgBWZgXDsY9xSINcgXqkEzHvI
rt1xXThHq3S4XShVs/YiG22fyMY6IupEkqvcFvQZHhRbnSmqH/bR1u/I+2z0fk9FdjWT83W9qhBa
rc+tyygdkAVdwvxaN0o/mD98jHBRkGvMHnk1y0KfPSYrbPLML62bqBZqkqwQ3uqUqRdSiDdgPgv2
z35qd1rv29cXHzrt7oez44Pr7n5LXLEsILICGEDY/1gOSnuBUL9fUC2Jp60vlGBvaAxliC+CR9aL
0PQhcTPtGV4suKiNg7fLBgb+3q5s33P0+Nlhlskvuw6d1l12iizhnAoW+UN9+cLaLb6yazzsWnRv
fRvuWsvA+pak8S1lGdMlxCtfuctiOSddmFZ8dtcVtgYixYLI4GtbNg3tYtf65R3dC00LRdPNkiE2
6hU4QH2jcLBZJPLdsg91D7Mu1PW+3tCHlm66T9qv3tG5wXE4T2b57+lwoD0I8FoJeRCsK+fXDCcE
6i6mhbADeRZ0CF933D3uADbx5BYqDZOHdpom6UmUZTDW1QreDu9EeD8ctvdKUZLcPBPXFFarhvJu
XeFAtoCeOofloxtW9UY8EagW9gwH4eZfJ3+d+KSfyv4w6t/t/nVS8X0M/vH3/w4dk6ZrIq5MX16k
C/HLqmrzvFIgq6h0bqLQ20/GvXgSDTZxTkipjTOhutaWAT1OHsgXPAI+HHPObzJ3bAYYE4GMlthb
H4DNQ3GrD4h9NgmSKeIWjq7H4WQGf3AyrhWTbd6mSXI/r4tDc74zOMcwx5RAKkMDT4QHDw2ERUmu
sn4aT/Pmpmt9RH/NElvcSzJQwbjm3BOVCR2NCXyU3k+SdBBPSIIlhzh0OK8V7RoiwEhBB7kxMlWV
JEVfX3opjKkRKamJmaus3/O6yohexpnMoB3mItVYl/hSCjUJ/UaUNGFsHBi3T8HAyyVGoK1naxi4
7CYuPU1cOk3Mf0MThqcKSq8GVJ3+xwLvVcHLT+CeBYdnZxfnnaPTi+uTVuc9sNLD1v7FWcdWH312
Z3eczRGWl+i/bjjNgZT3658ACe8ciZSQMoQXq8h0P9B5ha0USl0uoy0n9SaeHldN4nZvIcZWesqn
UYDT1gdPW5fFti6/uq0CxemUm9jVOmNRW5NsipaHhT9XOvK7sKmvi3NElTwe5cF9HAbn+60gmTh8
ULDZAejQ91ADdnN2XJEA8b98GOagR8e3E96dCG4FD7xGs7FM9gDMOQJAlwF7HsJejufD/PKTcHzg
9DDCidncqa27r1FIacl0GStMBC2Uhc6s8M6te7m07mVp3ezRSIo+n+vnRys9eul+YN7Bk3g2uDfe
JHQSHy6jrwkiHs/3AnE17hXhgr/5gtwrwkd9nxseq1jxBSy4d1xHPj6KR+sidBqSxw61haWde0R9
65aelVYQqzSwikhopn4958szz6hF+tfOFTgKx71BSOEzuPnn1JKVURf9dva3A5aH6DbMYyN8hK2/
irkFw4kdn2fa325RyT0jFjG2G7yRkYjVqL4VOQRA1tqpSlQaOAA0w7rgLnfqDY2hiiUtT17M0xVA
9me8GANETUgTxQP9/z9/f7GDsWw5vEUIsuMQGPG/glIZjppUdAhbzfaPW//xfwaASHw7i/M5BoFQ
N+6AZsNpRo5KqZDwQFz53ADI39cD/PfKugUnF61xRMSIoruOGKQNI5GQKv/GKP89sTr1qeHC8gJ4
HTRKITz3QtAWR82dqjAoNe+lqR28iUFQ0C/uFrhJphqv1RYwgv/4+7+pccIQICytw9C/gGE0ArNv
Olc6s47ym0kMmGg/mqhPGWlH4tN/zfKH9xTqVKxwQrTBq58we+7A5PKXsrwoQ+Wp7nMD/kK7UfsV
RNi6SdNjLyF1ncc1Ma+vhq53NcgfXxRW9dK4ulrRrAVvd3VUGT4RLd6qUbFP02jQSR4wAirOgy8a
atXvdG3vJjTsi7oKk+rg77jq6YbdEIP6iuWWytvq4MnHEjX7sHyKvrUZqVgCqieX4pN6YiJZcxKO
GK3tu7lHzHpXhj3Pwq9pJnYSwRphmbJTfONNsYHP6muIh/b51fPn5u0R9GQKoYA1riYmph6IwU7D
fMZHoyJ2aIg28BHFFSWHppAibyWTqMGePOFkkKH6iAdC+TDJLJepZJaPYnQGwFJ0zKsUVplrDF+i
69qU4uvAlIEwhL1/oYqYABGvfpj1wwFH0lGbQMjuWbBhNBBVFupENucRMNCHxIh6qumc8m3JgOlq
xgqD7MQoDbasOL04SnIdFe3oopGP02mUnvX7symsSmJtvsaXxYIkRVddcvZWlxeefUGbhcEsHs/G
nWgUY+iKdziExjLb8SRE9qMvYlbVbPDo/faOptpMhaQiOiAVMKHsFofYF81BV8AhxxAi6sVrX19q
GgNvqh3POR4QRaNHlISnlLMp7q8KyIL9+Zh62RRGZjC33YWZu/zbL86CE141tFA2LRugHi/RP4v/
NYzxfLNnp/bgETcL7+nSrjn+qfzvq3mg096aHPCfzwUX1qqfAPPCNfY7L3dz/9pPyEHabsla29vO
wl611Rl31nRUCwt8nWM2mGu/5t9d99Eb1dkGMNGHgXxBGJMGyYsEa3dQ1sMOviuGTdD2AbM5GxPh
XEpN71sJ0GWJ6LEfZdmBPmxyC/0uXMUWMazZw8R7tSKiThpQL5rP7SsBur2G1cSSs1ez2Ywywrjj
tXrY7aWpXOUQX3RJE5qQtwPlwCfvir6YclPYN+NZeAmGgkT8UAzx5C2MeSMpTMVbDnRRsl6sUBhK
v6WlgBqBB3Ix9BUfWOzb8Ti2KeRGtShjqlKGKdSmiq3m96zjW9P4XMR5fOYdMhfC9lZ5xA9nFp+x
TllIhaiiUKiTMWvF455ibjpE9uLl6ul5I2OWYKih0vnbKU62PSgMhsOJFKaBPv5RBhUphqkVB00q
/Abtv/LwKlD5yL79Elvb8aPxBju9KPjkbKJ5TkRct2oTCYi47sKQ+zaoTF6EFeA+Bb18C/Vyb+0a
7KGFRidOzgCfqk8gXR3KB2yN+CfueHvh4EAoeqV7AO7+DssAfXe8lUHwQQAsQRUlI9O7FFmzQ7Gl
kSu0/CaDmCiXqDWm+xWoH9ASqc8iPkWqSuJVcNeDKl2iNrGwZO2n1gYq6h+HID+2UnR6o/uq8rc4
VoZ/u/0hKD7uReZRaUVsFE+0Rr6KK2O+ZDkoehkQQWX/7PTw6KB9elF5IgjEvFJntcoND7M2EGEd
p2Azo98Ex4hb4xK2F6z3nOkJ49Y6eXf0/uPZx27ln47q8oN/vRjQBUAtCIxFxIbTBBEunkO/w3hu
ZjCmlctmOU/Z8fOUJcf+eEEcD0yCLE/oIomwSxKhU2yaYJrkmBybvLfjiTBhkptBM+iSsyBZKKR3
QJhygHB5/oIBhKZ8u1qaCMjMAO9u4pGyRswmQXgbxpNmpeS6DdnylHHuHVvndvXO4wzeozN0dXvo
3EGi78RLl/HXzeUH+s4l/rVsik8gdw2PwRgj9Zs5xe/BJZ627CogSVVqG2tsLLsG1aQRzCnN4Sjp
Y3TtMC/dO1Sq3SN0rMiM44cqXYDOk2A2adDLCCW1foROV1GK6UtjjqGBKo64G803CGuucT42rfMN
xzwfm/b5hm2gRz14NhGtG9kwUp3IClhIMRYuGe7Tx6U2e6zomOtloG9lrI9Na30szfV1bZ2PTfO8
LHB55Rzk/tAMhEGahzcaNC7Z6ke0xsG6hyB8jKKM7pbMJjEyLCwTpWgpNE5wZU6mbAbSHSy6HEN+
owmicQtcZSqTrCSpDK5I0RkeOlE2G+UldpUVZpRU6Wj2iYkZB6VX52S1PVxGFHFBNGkaSXRmWFz7
ushdq5/PwtGSPAubKnr8bTht6IgZ4gLNQEa3LcBc0ICSAYIFJBTF6dIyLwjpobjphrGdptEoHscT
dAGN0g51SlqIUmUZco+QitU+00A+aFOPcyNbubNNiTweLB81w9wHulXzNQ2PMUm+1mCI5Z1XoJg/
Gj4Et9AOBZtHNiUVD7G5iFz3Ed59YreC3lxGNjWoT5zF6fpNPDfFjbyX5EPMXGzECg1G0U3e6MUh
chISYdGEGZr+CLBjqhj5VOQV7YqjMKdA+PFjhMnMMvj3YQhMjaNAYFwEymhGNn8QJwyAAjkUKzi3
2BhPmO6oh3ixKpy/wGu+aTISF7aaRhQjrNoFJD4dA+J2LCPjY0eEyHbjDaccCjgVZAxPTohhXony
MoMmHetyHS0fWD8is5haiZ8M+4uE0sSekYhdk/TndgKTM5fXx9RswPWrteWQuMcFUEa8artV7Sza
tyxIxREu5zyf/OAJlTXgy0la3oDJIXV6dc1ExBoxi1GMWcpBjkm/falsqxpYzT7Zf5Ahgx04SkdX
554cK9gIL29WcIBeJKMoDZ1kWEZmvD++HNR14884Amptw42gzSib2yxlgKy7WXml7VMEam5s+2Jo
H8TrxdGWgbfc7YTfeyNrEUEwrktc0oxI1XJY0OJGXUL3MB3vmkDVal6nOar+WvWozPdNDIUbvMr8
LAYEAT7RIcyUTGKOktWjKNEIa1cBL+TzibCvFIqTFgWwvjy6ndP8irhPmXh3Go6jOnHfT5YwxWxv
qeRAlerOMhQVxRrYV/dX9zQ4a2UZddZYXR7Inns5DMlp+2lrzqrgaUGtO7OV0gVYREmtRGsMxKnH
590r+ywCt66scAAxmE1HcV/kidgqdXQOer74dJNURy/nITVC75P0Vw9cXmbV76+oz5RRPltymU3S
JlE2LLUtTDA06Tu/0yatxDc2u+OS4ot3QnwLlkfSOSEpdeeVRx1mCO1p8lCVKL2wUKoLh5XnXj6g
K/dlZS/Wdefav0UfBq31QOFAWVRV3A3kSPLAG+/78j3KlRS52/hKvbvytncXIcv4rOFKSP7imOw1
xgAqe0zJn6H+lZe16pLL/Yo1GDOfRJGHapsCz9VrhUuz2OXf2Bj1Vq2858/LsPnyFTVX7gVSXHGP
rVPruFryYLcUcuqrDau/kkdAt+tFRqGawmH5vHV15SCtGqHv29Z3+9hZnAJzUC7UZljc5FvaXwxA
qMMAVWy59Qe30QnI7Bx3RxbeEmXpsDrYQt9V42tfHJI6hVxu/itoinE+l1Qr5cuGZLsirxmaAzTD
lRnRGkbPnklXTYUsH3sVdnIMULHr2YH3Oeon7asqHbZmors+huqL5smbzW5x26lbPGe3hAMVIOIa
oiPyXR6iOg+NRJeGqa5HR7zXo+WJGUDihOqxOfB1VIZBm93V1EevYIh3NZ0UQap52NWPdTm5u/Kh
ICn1TQkppq1UXxb1i1HViqHbssHP1GyMYKhl9U3NlwAs0VPWAEdKsRcf0oSY4qXNwu6uXP+WhG/E
HCeFWlJq8O2XrInEu9hVM0jvrOlc1OXKwU+aUhZ1cwHhN5ti4DubauR1BCyipnKh5tJn2N9Gw37W
FCXsANBm5FRDEi6MBAhuPO6yncVGUaA1Ap1KWE39dcMjhpbUoG8bJUJlSR31XSGmVqYhNRZqqkLl
zF5a7ujvfmHvw0RMI/psShbGZ7XLUqHinms2FI2T+wiI1mjIsHPrAmZT3gLFZoxiqtNyR+85McE1
YfpGW3/dKIjbpZXsEk6eka4MKtu3FxXa9wqQaJFJZ70icnKvWLBlTehTm45fvrK9Brlm+Sl5CPoP
7U1ptoYRDYmXlBY3KZKKa8vjOxHwOBsnaBpEF8aHOGPbXtwXbhBkkqU7SWiVC+MJHlaMojDDS0oq
bZE00kq20AyCC3TipTBlI2CiDIeSC92zkRCajFJqT50uSmi/zvBALIHheRjGsBjEZGDCTD7GeLuJ
AadEaCg20UN7FCVqLBx9MVtmGKcN6PcwSWEKwpQgzaDL2TC+yYUlVYvruF+Q/RPaxgvB/WEc4S0t
CQ7TI7EgXhdWbT5avI9FhiedKAfjV6lTRjpxBHkcRofOG01+wgZfYjOHSSq8tBRTEkm43uo3u7Yy
XAR1hESGHgwWOMH5hCjGarVOc+Xb2czbebzFcbZ401nJkynLakvv3zdx3gknd9GgdXMTT/ikKKUX
bGt1DFcTSqmoPpvX1pRCCurDTq2Q98byfRvv62tls/En88e+9Wn/k3nnzDIZ99lk3IfmJvCnmC1O
Jgk2M0gZuH/uX9H4Oa5LgNpzmBn37Sd8+1goy4XNZJaFmvufZJnHcnfAQTSBFQ0EQD5lCLfBuPBP
a4CVQY4qoSV3O2r8abB8yEfJlO6HyQY+WQ18wttnBK7o2y6oFivTODQEMIZD9wcLsVjGmXVxzqSY
B2mprJqHMT1ghwUzi3BotDJqMsk3iAQEYs8VQn1b78dXRuxtfd+xVlApCMIuA6rrTu/qxzr2ahf/
KcjAzMgwc5DWz9zTJlrZi/9Rhx7iyNCJRkE6ZDGRIsbTnSQPE+HpsYvOYSH7KwTy/OcmDW/pWDLI
4lymmiY5WuZENqBlwKzxuDHKoAafkIvNiA7QUj60E9uBCBGKLJ/OqaiyfakD8zihOOXwrqrnaMdm
TaKiNJoUek5ZvCWpUeEmk9Zr33bwTLi6/u1v5dEKTCBv/EC2KfXbaiC4qN6U4fHdzsDTH5MwP6co
xUlC9yHoj7enmb9cEk5dvUKW1VepvaXYgUfzQgRQKcPtBSU2SWM5mYmAjJ6hzUNeunfOibssQEm5
iauRw6huw3b8fx5sB4sFRuE3SqjgqYiGkSOK+j5NRpQfDZHhOaFdlFe4uX9i6HNnRtQpjOTd0UNw
HCNNfwiz4Uk4dWvYFu8RBSnZMwbMvtjwGtmCVWOGt3yW1Hhj1UCZBeP4pRi7lhqzrjjQiO+K+wv8
Gc33tWJ9vO5MTZfX588kyth8a8uMqACQSk6u5JVuCsuGQfO3kNcrYq2m2/zS42PlySAj14nwx85r
5h5vz8nWlVjgfst1Xl5z+6ps5ZnLbT0UjP3wK9BYsojVyjWJvWEu46yyNO+MSQg647AxrW/xB2yt
27YcKYqWTLezc/u7JmBcLWNwPs7mB7KC06mBkl7e5hhVrpYcTYo+FHn7UsLwqxVLJpArNG5EdKJK
UZAhlbeDauyJ2GMFHxN3r+pKHumED3WxETvHnobEgtyMrrdT5Eejaq1cVHEFVs9GtVRGLdmc3DqP
K0/+d1ADEYLlVvP7H4uxpOcmDOt0DXf177YGdmDlEd4uRLRQqlGKfTBIQeWtk2I6QcUXMw8MAVJD
GjpQJW66UTrjAWi2Rs4wyzWl7OBSqk2FC2fVooCtRg65KI1D8eyxT/c3KF6yEidLjwEl0l8tzhf0
HwmRIrraU2UM0evX6sfK8yHab2RNI/ws3aKxmaZYEVdlZ3AGGCB1f5pBtnaY6qms9LlwS7eAlgjB
cVUK+Dgex2RnUyOz8+Ngw8tMrAttDV3buKlovqRn09HeDiI7SNh2ZvrDjJJ+OOqyemW+OtL6lfSs
4DRKnjN3OuZ4h+Zs55B/xYn+WmvjqevDxh6nQ3fRT7d9eSRPy4ZXi+Pj7fPFVeTuWTU2ErB0NA60
fmqlkDnBpfRPKJKz/3K6dRpfxMkKHuWDXuIphGFmHi3fBMDvjcFfVw1UwcVggOkFHp/qWwCtFi5G
Gkg81bdg19g3+3T8ZrgQCM+BpUqTc7+IdJiu2M15mfg9CTiwuV4un/t+PwIqp7Rhs/jSI/yvPZ8v
qNiqCeMA5LWmIEBPf6iVDtT6iC+WRDGXsljfOKgloOaxm3kqe1XG/tTR3p7ihFXXsFQPqszulGBD
01mzMyeQRZai+b5Ibm6yiJMhcAYEZeu/gZrBbKJM9er8L8QwQXbSA4la0zqqx3zvT9/gSBAh88t5
GKc67ICCfhfNuxHsfGIDNNTsvo/5fu5LTbCPYmb+yZTHLcjScnu1ZDptYzVh6BqrixZps7Df4N6X
ZsQSKJ/WgiLM9ssA7a+Lz7Mn4bf/6avArkJ4XfN1mQn7jTRhl0qPN3GOR3hPNmN7wRx5jNpmAz7T
thU01Cj7xhSw/vgjXXM2vxui6XZzZ2fgS1NQ2FZdNC1x3BJ2v3s5KHPIkioUoEU3zU2s6KY632C3
NCq/dOjCMCUOCaeoafkcVpbzxAqrRWQaTIH75ZXab3D4FZzc5h3F/Zb47+56RwBSlTZ59lURpOvK
o1BY5tNj+gS5tvpG4OXcBQUe6uG57XuPW4P4LKSItQ8rhPaPUA+K0rXxtWs72RrYLPG2e9JRiDT7
lxhdq2nNtUMcxtr3Wls1qml99VmKMG7UNooSmGlP50aaVNYs6ozXc6lTiPKOp4NTUQylW8nxqRAV
8Ivlitj3OyJadPH5cyrDzvSvtHeHU1wG3im6JcoAejBzbFRqCfLkyOEdro+5K5TvjLH5itMoCyM3
14vO6L6sgUawY+UIMf153BY2igtNl+Ih3/AvYF1MT2nR2avr99WwXL3c4ZBhpYrRKfASFpQv7/ui
9kpdeyu54WZPo7zdti7iwEd/Yc8Ruu+nR2kh/dPQI0P4jRiF9BgtDGm5LC/3SixGSYL3cDH0FfS2
qpp5XxjK+zVGDJOKoZFBXOfT9yvN9PXrcUlrwpaYcNcw2a4y0RYXHq8gC037HEedh/lTuv+WPkqX
F6tn1RJfFhk41u+HUntS1w3OTlLMIQr4oxjN7cAFz+6jVCTuKY6NecT3VhzXLGomRD0pXwtVy0s2
ZO7uUrD2lTe8gCS8tjogeAOVIKFrY3xdGKocw7vcHz07VZl13ToQR7uLeV5d9EeyTqX1LMDOG+w+
2VJf0rZRZGX7xpx5cSjKpBTytSrP45E2MQSYGI0nencZsqbXeu3ulDI1QSdih0PhhGgJU7LMQTRO
8kIBtJW5yXk8x2MyWZcEJpJVuVXdlFWWo5es+7MyhxaqP3grfCivMCxLAXUmIv0blvBCZZENYDmI
yzVAXJaBsDKJl0MZlGYpVYBkotMlQKhIGQCVvKocQEmeC1+6U7EXqqTuSr76EQ2c/sVVaM/JfIpJ
clrd7tH70/bBtQ7ef9hpUVL12hI81Og4w1UK9OCodaIg26Y2nlGMo5JG2sPcPBpYw3lbRQ3kYNWG
s0GPQ1PLJVZQ2TzNS3fpz4+75jEXQq4Hc/fd5VXJJSWAkl0klDXHkqKVTS1P6ExV42TUMLrqO0bp
mRCLl8ykOsaLmC8uLTlF5JtLXsc9Yw8zcucaWxppYrUVdS+dk4qrko3B3RzktuvsDBaowthUZcMw
W/LxsobOKyKUStXsRt3C029zo5vBe4IEDLlINRQUCKUMzqWEc+mBc1mAc+mDQwdExGnFnWWXCdfo
ZqXLEUtBXUpQlwVQl+uAWjNxkLmV6SQ/P+vHD0WDCne1LvCsO/ym7nBbb4YgaTWUeBxxdhoDYzUP
4rr+aXjqOTNzN7qlSYPWG30Tp8vfjtOKLEPr4SRp8YwPK5YiVYhr4EFRexBX7Slo6OVDXlFLvvqP
oar28DXMxf5s2deaN/VtGs2yKMP4/L35Ph9Fgh6TRV+XbsqzrUiWTlcz/oxbGFqF35Qescly2Axw
6DITsWnCP8Mqltt2YVgVf5nmzcey7FClw+yB8Hxj7dqXTu35k9svQig/UyRRm0fktRb9T1qfrrsf
Wh1HPOleYPIIj/DjPcktkkqezvwW+MWTTjAlMao+HyfJXdaJyOWmQHi+c4gqb+lmWjBMtyCzQ9uC
pLf+yoxiNij+XlsJixYBcxUb3Os9/130gmgJM6dEy/326UW7c312eNhtGzKrDwuHo73es4lBgWx/
Om/vX8CDA9SbDN4lgY1yNqGmcMnRWDLySxj22lsuZdjgvIKGvZSWCxt80TB2dssjX6uyHRu+p1ha
FCP9pS7tUpelZ4GywWJ6shV7Rmm6PJ8uWZrczO/6oekLCp532l0g1mvKJWwrQjVP50uSrVXtL293
2fChMa+7Y7HE8Y53hp5M7IPER1um86acs1d7MuEPUpquq97gcbDhiLnlS5JtLnLHnuKPTsKGn14T
b062JhMZqRHGgjbqmm99abVLXJnFnbRUjjJNNi4OyzJV7Mv80iIKJaCFSXJln4xc005HxeHBKxCi
qWWjpIkLp7h4wXfDKQwlJ13YdO1TnHhycMIXOX+b6YlhPdXwJGsb6aP91fXnEvMVt7+28YqLP8V0
JVJnf73hygTwVWYrBvCbjVYCzNearOzM0yq1dLC3pl3JzWWtk7Rjrt5VSd9BlDi3s4v/3kYuE5nu
x/Pzs47PyrX2OXbZDVb0BvVeYnViCaHBxO/L51zY/6b0ktvvYaX57Zaa32yt+f0tNl4riTbTNOw1
j8YNdwkuBXdpgrt0wF2uBw4Dh80wsGnavleJLKTNZD9O+7NRmIqloI0nBr/0yxyCW8oUzB/qBduJ
xSWecdoOP6wShuCXKAqdUadB5eKDU0fEQOKkQCLHupcnPA2mV7577Y4DRzZ6EuRSVeaNZ5C3BksW
MLIQe7TRx2bJ6Cy5mQHtHpkZrg1DVQGiY65adxGoVi6dVi69rcyf2Iptimk/xllOUkxqu6FQEMus
WmuGk3nwBbgBl1tmS5FlbP3qb38LzA+X2k1WnAkWDUCuxUUiaRtdjNkw7BUWEqtMH+vBWCKiG3NV
Uv9yfRyWwKiVsCMaQD0+TzbBbKy2pPBVH5dWjOXm5b7cKSvIla0LkPuedxGW3D6Eqdg1VmBpqUuj
1GUJGyenaWu7Kyt3aZUrhYdXGOolPaX7DN5vxh0HDuDpXwXhNLY49K7DzerlWh9rqbuGirsmBy+n
2YI4/US9/Um6ux8E6qYdQkPt3btkHCwv/jOodmqP5bq7fnviVbl6K88seQg2/CNj6YWeyx1Kn2Us
0B8M79IvGt9+qfaHIYh11Uqr0sTHVl6lWIP92kKtlT3Mi2HOvkgoCyI6fvNHLFtv0gES355YH07Z
Ju1k6FhyhbCgSb8Jtkoyj/CAkQddsd5C5YfgoPbk3DijFxi6aBrBPwB9NumNZpiMRPYliGRnNv23
fLVziddHkwKSlsfuF5/5Kl4xPuYap+4M3BMYk8E6ETEXG77gXDRodvA84VCZJ4TDq2CQct6Zb78Y
kfRMB8Zvvzhh9LTTIo22vk7kBgh7TxNCzQlHzPJcA3aCgWVg+PaOgiTjfBqQcHSsVAUi76scE1Db
CKbrSatjQrnXG1MOI9LnKJzIvPbPOno3vb44u3738QiUcMxyXdNOvDYlvfYgYaqz5fmNMKtRJ8J0
PpPbyElktHnG2Sm808xBw3qR9rGl1CAYQKaQtYVDfPlSFW1SEpFYJoDF2ll4E+VzeJRmiSqlhXV6
tyD/RapAqxOddicJKPYppTr2N9aZTYJkmlM8netxOJnBH+zNtTJHNG/TJLmfo9Z1H0eMUH+OeZFB
wxtFmcqw3MC0Rjkin/XTeJo3N93IRk42lR+bIrTclHOjGDlVQmGFROuEN1lNM+iIwp+MnBE6l00/
GYvAdBQLyPHjpYGSrAzY/B266twkeCfegEbqpr4VD7OZ5VE4wJhC2SSckusuOocGlKYatYeBnZnD
yttymZZdcEhd38l5IbCueVfN2CrZAnP1ViR4KXG6VbDnmS/gCbyVsajmFte1fFwvl7qMXqY1Na1/
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
''')
def step2 = new EmbeddedWorkflowScript(name: '02_auto_orient_epidermis.groovy', payload: '''
H4sIAAAAAAACE+y9+3rbRrI4+L+eAtZmhqRNUhfHjkNb9tIUZfFEt5BybB9Hhx9EQhIiEmAAUBLH
0X77EPsu+/8+yj7JVlXfGw2Qcpw5Z347+WYsEOiuru6urq5bV288frzmPfYGp90Tb9treO15Fjfi
JAyizEuvw8g7PWx7ozgJUi+NvWAWjoNkGqbeLA6jLPXms1s/GTcBBELpzyPPv8iCxPOp3mUSjr0r
P/XOgyDyRkngZ8HYA6A/z0/87KrpvV144+DCn0+yupddBV46SsJZhqDSLJ6lXnjhRbE3T/3zSaAg
YvOAEKBYR6SS+HZjFE/m08gD7KIszBaySHITSOw+XPmZF2beOA7SFr7wvK2m1w8uwgh6h80nwWUY
R1584QX+6AoQHYc34XjuT2gE8HcwCyJsY7JoMgjbTa+bZuEUesZg8CGCOuk8ufBHAfSAfvrR2Mv8
6BJq87pPsXV/nMJoJXHmZ9B2I/UvAu8WWopvvYsknhJMmI/LMEKQEwDOa38PtbEWtQs9E5XCJGWj
iSOOg4gQLqi61SEO6FnTG/g3AGYSp+kkSFPvYj6ZNGD4YFARKe/k6F3diwDBm8A7Puw2Tnt7ezDy
8wR6h23UGSD4Dwb9Jgxu0zo0EGX+CIjoKggAH/be2z89PKh7ncEvdRqPUZiM5hM/4QTh/dzx+se9
VMzYoR8hrvFNkMC8B3zSdhP/FoYsC6MFAIn4yG2M48wDXLU5ACJI4QHn02cziG2mAVJB6o0mfpoy
iHECvZsGXhZ7611ZVzS7jtPUSOYIG0ESjTa9U5wYXsTDxmECOLwLAEhzIht10eNRnElCbOAYy9Hz
fKiGQNjvDX8SXkZTonforvf73EcqZ/MxC+8CJC5/skjDtCnAIXYwzAABcBhz8uZDBaUvYT3B0pGE
RVBSKD31YXlmCdAtrjkEt7G2Fk5ncYLtznDVTsLz5iieTuOo2YkncXIax5PUUSY+/y0YZWkTZ/aY
PZeUoukIWOkOTU2+LFufabM39S+Dk4kfBSWF+vS3H/w+D9LMVS4Om0hsjk8hwk+bNF1J2jxN/CiF
6ZgG4wG9ejsPJ0AkxTVvkzDDmvE0aMKCOVkk/jQcf6C3cjgn8SgUA4lNhSOoOWAPe7B04mRhFEUM
fBipaZD5zd4h/Dv2M99ZJJ5n2O5peHHBG3WVko1C0Y+HB7xpURRxv5tOmtN4HEyaQTSfps3dEKgw
hWE9TrT+u0qeIEGdLmYF4GZJOA2RfqFknNJTL8qCS214YGSal3EMlNq8TGGI3sE/YuBFmd/8G/+O
DXoYM7LoHesfm/5t1nzrp+FokCXxdZD7RhSce7sHzCv38l3iz67CUbq9m/vUx10hCaPLfdwVc58J
webb+cVFkARjQrOgTPsCdyNJcMezXLnLAMbFKmYUimAkLkIYtT34J3V/GsBGNIaNuxPPFscz5AlG
uTQYzYFsFs1D2A0Ard3wUltDVCQL7rLmAN5Mgl3Yg/aIpIwSsHVMgLwjAJXgntfxJxPkKaWFunfQ
NFB+ura2seE1vtV/COx4FiQ+gG5cJEEgxI6U82rgz5vN5950yoSezqLT20OBo/lt0ViDZr3RxSUQ
IxCLt+N98fjjdbCoi+cLGKlzf3TtNV57g0WaBbAQguwENtogyRZVKirK1Lx7ARQWUA4ikKMObg33
6CxZQKkkyOZJ5PFV15z5SYpLsLqswWYWswaqtRo2jhBHfgbiUvX0CkQxEtVgu4Kdb1xT7Ugk7tck
wrvxHAvbOI/Z61K0WVWGNXv+ZyL+Fna8HNrn8DLwoxzeHM5b9pmhzH88DGfV/ilwsihdgXx0BCTZ
WQTUTGeTMKtW6pUarMTJBHZjgBtmTSg8rdYAhYP4Nkg6fhpUYdyAgUTj9mQCZR5BoTDtTmeAdk0f
H6CjgzC1qVHgYyFSqRSgkGEvRSVFATmKpXIcWzGxyyY3AgmXF9V7BNrBox3x8R6WKyPF9lH74NOg
NxjuHn84GrQPTw660DdJwdVKNvWbTGkiAaspxLHd+DZKfWSSlToI7JvjmgDZ/Xhy3D9dGWBwhzzT
ALdF4HCBn/S7v/S6H4aH7Y/Dk97H7sGAAcPByUHiMuahf0dbdAqQftzclHh1+scnw0GnvQwhFPsH
I19g8kz1rH982j7tHR8NB+9PqI8rQBOS6WA+w34qwN8jYLGy+t13CLff3esddQ+7R6fD7lH77UF3
lwHHReUAzcVA3DBRhO5GSAtjgJ4l80BhzWAPuu1+Z38llAnuIPCT0ZXC95k+EAwkzkoHkO32h4P9
3t7pcK/f7uAAeTtiOZS3AVPVgVdBMrgKL7I9kM3xIzS32Xy6mWvutDcYvO9Cq/13vaNVunAapuk8
OPQT0ASoD1vb7j6sSBkKaYNEtvIjw8m2c3y019vtHnVWAtwn8u3E0QUq+6OAxoFQFmQyaP/SHe69
PziABgbHB++JGFG9KqGSFBTgPVj2fan0nkSXkkYMyEdA3fAHZOYh6cFLoB6RmnY8DVAYrxDfTW2Q
tGS6u8PD9wenvc5+++ioe6A1IMikuBFmCBgfgkQTjq5AKQ4muRZBkOmQHcc3TRnS3BGDjpi0+HMU
oM6aBP64zvTGYEyKLGo2GbBl0CJRiQaGRCLSMSiV3mAyT6agoYI+jOrmb/E5fk+926sYOcvJ+5Ts
NfhtngYX84k3CsIJ7g4hU1DT8B+krcMzAoUdKh5RJ5ne6wNnnp4HCWiro2tmSPLHNz4QQdNrp9ck
ym2iEj/FkV0fByNUzaUVZQodAyawXkfYtyDJX2G7t6inD7L5OIzJMvQSmhkH6XUWz4RVIgUhdeLB
Jo2WmhQVICRijsttmF2BuuX5KDheNokh97s/v+8OcEZP2v32wQHM5ofj/k/dfilj9hPocDD5ECfX
oDsiXTP23v6l3TtAPjeEIUQIh6htTv276lYdTW4ZKGUoQvBH2LJBsg5J1gapAvS7FORp2BoZsPen
xy6sdJjsOYyqW8/rdusNb4tDcgAp6ferHZiaNwXNt4jCnSg83a6XgK0RWaOt420YN5gSkioTFVPD
cZaRfLIrJGcPLWzMbhikxL09mL4ZzGCa+YsU5jcJ/QnM44VXda73P/5YYdHWqEeOIdoiGQl27AFs
CWyfFHVPj3/qHnEKYRIeIxJu6+T7Llvd7HuFpCfBVXd7g5OD9qfhwfGH4Um3j3tOz8WsDXiT+PYk
SEZoNp0wXvpM21MEyP3eu/3VYV6Fl1cW0B9/fJGH+q59eNheAuvSn059AvHiGRd3RHXalNjY2evK
AAEk1WGjhgP2vLbGRVIBp9892u32YXv+BaaI9mVNRM2DS0jXD5JfYJHSPkxTXSHTbGOE5oTGdnOz
Ipv5uTMUAlppSwyMzRV+H50wga1vN+tVovkI9pCkAbwsCxrnE5DmG1vUNB/ok+PB6VDKY6fHB91+
G7bZ4W73XflWO4vTrM9lslNg3gky2d0Ad8StbSV3GuBxOnog5dCvUkanAQcxoZehXo4mO4C+LYft
FFY8iAZ92Mp7BwAWADq0pRzwLEgz3Ob2wgnAZQuE6wZyz4VledprHzDo2A7AfmS3pzQb6iuKhp3T
41IO7keXaMweBGi7w978sC2n4vg9CoD93tG7YQ8otl8+/sCRgqQPw9ADsk2I/L/XpDINGD2uCOx4
zoYEaEQDxgXG033gSfvHB7uryHgZyYygWQUp7O1jIeVtWvLvA8W7IsFO9PqnlWHF1xac7RecZgf7
3e7pUDEx50SSz+KU8a7t7U29JmyFwKr3l1U+8M+DyX4AjDBDze97BgLQPwXZf8hA4YL50Ns9Bc76
sQQed6QMECwslw/hOLvCSdzc3Nx0gu0dLeufATKMeE+3RE93u3tt2NSG7/q9XRjyHHdFJ1yTedyI
ytXKgt0ONh9gyYIp7MGbt+3OT9DR032gVYK5TKJFue+2LT07e9xS8YFJWu+gdSXVcm5x3O/J/bR9
8A5+nu4fFjN0s7VL+JldTW2GDvJcQ3qSuE+08bT5Q4PLw42pJm4rft953++jbnrSPwZO0h3utwf7
JcxrhIR6CVtMjJbZfT+94rt6HlynOxjgml8ZIsp+aJQmoNQrF3K5pmCeTmCqVmuGCU8PbWK3e9rt
MOkHl/SqfRoHWUDa79L2UMrqHB+eAEmg7HrQfdfufDLKdAecDpe0OYqnM6AU4DMHwaU/WpyomQqQ
/N3tCyPUEutamT0NVApvEGTUld9SULh2vCi49TQ/CIj6KWIdZBmgBUuXLIVN5miHjQs9AN5tcA4b
YsLGjd4wOPjoMkBW0GlJHkckIXTONZM4RjZWaVZqfHHIMg3GtkHwEU2kTUS2wqbAaDx9u8CtGdr/
3DoTFGGUINfIEbpid2ANkAyeQx+GiFCv1bwvyjAsB5y3yswEAAaRaaIO+B/w4IAGXT8N7mCG3p/u
NV5AB0H3mDFvpJrFQiRlS2+YA+eNZrF9I+b4TQs7I2DxTolq2sCBQgs6CGxZoAKj/bSmdYtjwWrp
lZqkr3/x1CvdZCr+06ywO1pRat6J9BvT7psDiL0AaDXnDH+GL2dGQ0Z9ZaJVTzMk3wlo7R+CcxEs
oA/NJPbHwbjlfffF1WATLQfV2j1T9q0yYppxyqDMOrP65s3Dcni7SRIn+uAr5Nr9I+DALa+HpmSy
XEQ3sA7GS9rkeGvg8SP3rkmccpQmekdiPp8EsksLDYa4KOignYP2YACa6iHu+hURsuNVGb8MxrWK
a6MEzSRfUdsaQXWR9TgD3e31Bd82aqqoiXHIkVdNwjYMu37XrFKyI4uoCgoDwBFE3pOPy9D2SDRN
PrgZNGEf55tiDI7GEArorXFh6+C4Pzz+CWCr6IfmDOST/ru31U0QGLfgn60tKZthcSYKu6tsP3sG
5V9sSnsPq7LX7h0UVPgeKvywif/XK4j+uyttvYBKP8L/obnat3eukosdQ2vms7/CZUq8ddfPfOgc
LJwO8xb3xFvU0YAfaaWYE0es4d3Qn8SXKYjmMVt9YuVV9HA3mHIktKOYNYdmI9gSoyZ6jllckTQs
UZAV7Iaafw2WJd9M0zL9FBh8NifDTf/9EfISADKJgYaTeTTI/AQWazs7TJUoxD3jp+E0OAwnkzCt
SrLXq/BN3fbIV9cX8F/j8LAxHldOK/v7rem0laYfP35cr/E4kCrWwxpVE4VajY09i/NigS/Qihxk
5GDsbZXt9akootegL1chqPjJ6Gph198XH/gMPnJvtJpU9Pe/L9uUH5kIMEbLYmVANpI8WdBGnreX
s3IPFDuYrxTtzFZJicP9S2/deyIRXQ/lbpGRkfq7L6tgeN9k20LplsB9rtzmf/4bUk5aAFZYwfh8
8UrYGNbSYJiSqkSIhxow3DvO2vQTRQcQX3gzoL3xsr1ojOFGULq62Wy+ysPh23jNBHAdzLIcBOVl
djB2ozBfpaaX9zWao//+d3x85RUiwuWT5jwKf58HmgWJtzCYn8Nib48oEG/He5THNE+5jt4AxeYG
iZaDoxlBtnKx4eotClCr6mSm6SQg76IXUdKDAycfpdHs85lW6xyBcinkwQSnVXoQwd17UD/gnXZS
Qn7o7ml/20NR8CpImDMqmWMAQNoy2RmLF74Ieewu2XDrYmwTis1FLxeLjqSNrY3BkWKwVUn0WqfC
I0bBpCzANYDPC+hpMLqmiGnpPzvH2FXyuHnA5mfkkoIxh+1hOgPA6FVSKFVS7//5v71peIc8BN9m
t7F34YcThrY/A0XtBoN6sR10iw0ApPcUMUDA55N4dM1DQOcRg72Ov/jewqKq0flFfpIpCcbYFwYY
0FEdWGcernB6+cGYeDJLcVspfNw3PjJDGHxdE9y2sgNbNGG53TJizlXoLpSoyPLrxFtbq3LOdVVR
hiymWBvxvvfu2NP+vTe704oKcvWqohkHT7ivKSz0z9DmkiVrt+OlVKgFZA29hpn97svI0R46RMvw
EeuomtbWne2ktG6wU6Oi7ozMjtyznX/sz8Le+A719TN6ISfn0EcfpfEtuQT1Zmy/epcEQWS/fDuZ
B/IdveSeDBnXZHnAdObOi3ZMPxgT2hHh+lUcjK7SrD5O/N/rQFhAwvAH/00XWVyn+mGdg+FmCtmx
VTCQhZ04jGbj+tW2f1efPXtafxFfjS/r1+HzH+pRcrENuG3Vp9PZVv2afB5RfbTA6CX2PAPV/xr/
bcCf6ySrB7ORP60HjZE/vsLo0nognjjaSTBeBWE2NStj+01Qu8SJXxE5IpKO7djMjxoHTdbrMErb
0YJK62FmjPNS8FnGGufGEPEdhQ+K9SejTM1h5eDhcqx6048WWiia94gFm+m7usft6YARC0Wr8dgx
U8Q6iOPr9IhRnY2xHalndbDKemUsklpRE115/uSrGrFWAjVjsA00N6ElHjbb4A4j6aha+PBx1ril
PT5Us6ZbYAQrevXKC+VLjZXI9/eFsOXAMOg4cwVjIJeVgYKD+dnYcAb4TZHRFpKBjs5ctQZBRJdc
mz8p8dXJv/MvVQWdqctn47PG4LVfRhHF7tUPVYB2TYGpXFf2nlmR2hmo53whbOy2T3qC/kGvmkfj
pofOIiRACoAFFQ1+glATNdp7oiQP+SYPpicdCs1KTkRdRupibh8VEzqq+yAYIpBqxb+oQL9ylIxz
VjAGaipJYRqVqkoIwzGXGrii7RtralOj1TBmj2MjinNiMEtLasm3JepJGjFraoTkqKuENBauKCcT
pRcxckqJ0Efqc5idefe6SKhMiVfBZAYS+JTaIViOEVwC9tsfVGDhSBcxqnB/hTWNu3V2Q9If55PJ
mnKhaN+Zre2E/dCYtvgsIqWBdwlHEZb3UQ8QH2uaDV1rNFeePaEyQhHnzMGzdl8Ww81WjQ5WbzLv
FEKn3fsk9PLmoff9HqioZCAwY8DJQEB2LlCASCGFNioIqKK5LhANAVx02xpj4W7jxbTu8p4WuCRW
7a27NZdzbw5aWfMqngbo6aqwAMfG6WG7wSLM0crNt3Ay1IGKNvVWM6gpEyyrxMfwjz8UJHGkQK18
RlQlLXFyEtDeeBUqXfFaqp/uOgZqWgsKG1DbJ/4oaE8m1Y3qm7D2K57X+7WZhRdPvttgTvjlFT//
V7vxn37jH5uNH5vDxtkTrDisWKOh8zodJO8M00FoAt7CvrHCNCof7W2cXDdBrecVuWAvKyvKqFNw
2dAHZXvIBPChmHOE3T6nyOeADx436WKgw8PREdVsZIqxmoWzAPbtYEhVkTblMBVjJ+wetlNbNA+w
hQVjSDEr3DH9zTn2Pm0j6V9yWEw4po4vSKCPz38zDkQpeZCJ9fBdLAHyaaEnV62DpWzGBke7g3Q7
IkLsvO7DMfp2GGCocxyyIxHtaLwbAuQsSGxkSEGOkd9zDPrHPY4ADygbkUwah2SqBHDwOP5oFVnk
i3wyiyT+rYaCDGLmld6iWJpy61jdM94Ks5ihd34e3bUAszo0DX8XdR1+S/9RVwed8KAFno83cHms
neU5k9rixJ/ONrdorDj+N2LAkGPdeK+8TQzwFOjgD+3ra3bsSHzdEl/57xvRDst5ACJq3x+HPj+r
xhv0RYO3V7hwqz4Ghzdo4E56Nfj8ZMfDIFNKAEAvzdKvPa1sw1mWo+MLdOjA+2m8G6A25UaG/x6L
OcxE6apf09sfE7ZbL9gojRHZp8/lKMlCrz1VpGEU4biN5ZSkN4QQMLJ5YMwFvZCuTF6PR5LwTRr7
QuW0EA4JIFWGicp6pYZbsf6qnn/1K7DHmr0Coa73BIrxXQ9hAWddR4hP8KPeq1T06iqbTrrpyJ8F
X9E5QU2ujpErQqLyd0Tl70DTLyuuz6/o8yRzf31NXy8LvlIv//77PHZ/X6+s4/f/7emP+Jn3mnlU
n9rLi8+WcLgexBgv23w/AAB/az7FMzo3wrnGimw9CMKWASG98refPdetP8bgny+y4POZN6Yj3Ujs
+hFv5E09HoRUrQz22w0ABfIBK80mjfgXAEllyJTBv1hRTWkyMa/8bXP7DtAFKfvv3ubdxQWGwP0W
h5H0ZdKhjXYWT8MRdYJ2d1CjLzGNCO8RUix6C8S+Q19tobo5vR6HiXA+MTDTmS4ouOvVNXjcDwV0
3oS6DVwI79/3dpuJH43jKT4K9g2f0fNPsWQcPZgcPkKO3ZFO5zenIJxUsWoWM9FGNq5e5A/rN/vd
k4N2pzvsfuwNTntH7+prZrBXrkL79Piw1xkeHv/Sfcge/E2RVPFLxPb8KI5CIGOM56WJvtR37gkl
5dnxPq/f4t65892XS5wPLMw30/s/rmj/1D+JHfV+/Ywg0QfQbtBlzswkOaMOiq6WUUdJDpQlxxAd
/oKNH/9j3X31yvsc1mWrnPjIsFQXvKWaF1hq5vQXFPyEBdVHhXyNtximhyHFC1drZ2xJVv6o1DRD
Jl/hhCsvgBuGYH5T/zqQcmfeKI+eQToeUyotSgAUs8k5PwNAlb9CgiwEWTPlypk8siRSklAnLiax
nyHHBH1XJieY6bQKbDFlljTa3TBzFno2zUN1VXhfY28AYpxUEV5zEkSX2ZW34W1vUjw/QwkNlFXy
pAKQzZfw55WnFccXIHZgI3p3CVHvhix1mf85PDMsFCi7bdYYrkBnN5aBGt8bFjlLwKPvKWiLQr5A
7JhpUnUMu7qpHd5jlVgoRcMTx/oSXANV+2MNRLhZzdxL1PgBGnXC4TM0eobRCWwiJPHFWTqX51Fy
86ZLHvq4g/yxmRd12UBCB34BQHv4o3lyPOjRMcDe0V7vqHf6SS/o36mCR913bUfBpRP65MlDpvIV
YVcTON7kZhpxqgnM7Lmm1yDFMhi891U+oFRJTnH0NiThHcQA8Q6G9Iold8B9FF9QoTNTJ4oucfVQ
Sw1q6KvGAYueh5FFZUBZDC9FU5zyqlU+XrzRGqwswgWpS1WqaZwX+/IZ2jh78kQbJgrJy+KM3Gga
hnon0/kU44mgI4V9oxZ5r3hxWLghIEPN8nmlxm7fSkgKfu4dDij5DzflFJ2DuPWWxsjCI2N4ZAqP
zBxdaBKwIUwyk8IQGVoaKMmE0TxQGxXhiqfe2eg0AIpZdY9XPU8C/1p+os5Aa5nou9ai6Bv2lspt
6EDFV2yzygexQeVwdm/37IK4agD/x4qiASX4BXCBKPZq+rNz3fiJHXgvR/3GeK1GPrMi2o39khbp
E68qim9IzHSKBLSIUgVDw+ijwe9zPwl0XQANA9I0IR9TClsSGqxMEiK5HhCDxanZFjS6w5HEQ/Ib
HjvWKYovnMUXRcVvzeJ4rL+KBVWJq/ISMPx3aHowyBPp5U7+vJMUrjjZwq5zhXUW8ufCUecO5uIW
phpDgWqEOQUzNXhTDOwTgERF9muEOoU0NTjkW2trv2WduLJeX9WUHYplocMj8npWOn5SR2pcqWXC
rmuTWfdg6mHKbw3AgJeKtEKSYfCrvEHTshSyGCqoU+eZ/z62ECp7/tTyFnqDLe1Z2o8oBpC7hMKY
hV4Yec0YdJS1X/FEPa+V41W3zPlpxuOJSUjv0++qSVKskB5eppOT+irjy+S2hTwAOY3ikqCepEal
I+Ag+JJXE+IC5nJgWxt7E50Zn8/nF67PooslUaYMg3t5XsdQORRaAxrwFHezTTbbIPmCUgAN5zSQ
cz8lqzl61HLCaxVq0NFXsVC1inhCv7TSjz8+y9dK0ehgEHnQ+BHkMoLWkNioes4tMbfJmwIPIKDL
Ozpzzjcg/oNJw00f1n6VIHEuk/4O8qrKZkFCJEDSUAVORr3SBAIHBwfwupLTvzx3E77OcG8VbbuI
l39aQrmC6qARGhpj8VVpqUmnu0nGFDZSUke4281a52SUK6zEYwFqa8ySxIOhATlSG3B1BeMmC3kl
+kdBoLl5wQdTlCfURA36UVyHYgYEfFhJqq6p6NHwTDQ5RfZfvDQCiW3QNQ6Cahkl9AbzMNcMoffQ
v3MvKgDOF5XhObgsrEAtuaqcF1ZBpGQNyWYVNzOItcr4ivGuefrpBNWW02H/3VtLD12w9YvbLQjp
i7yQDlo4FFkA4d7mV/8dq40b/C38ya9+wSIQyhNty5fQTeGBKZDbz55phn9a6ty7geON7GCDpkQX
9gXAywcCpPlgIC8LQJ4/ECTOF4N47oCICVxT2BXfva2ybb+aoNa+9bzm/QFEic8v8PF8Fb61seGd
aBlMKDD9546XiKSlQCmT8BxDPYPJAlMzpZT/CfhieM5Sc/AsmZSVCeku4YHpTe8kSBp01lB4fFh5
ZJMpBWFd0kjA5xBWH4iR1944JKqLMoQG8jCPkfcTlmEKE9QFmFscszgBdpgn6iZMWdLq6cxPQjwL
fR5kt5RUHFNasRRU2sHFdBTz867BLIUe4bPg3P55fBO8VNl/5KEusm5TeiyKxueB+jJ9FM93zQUX
xBRl8wixhy6ls4m/aFwmeEq1yaKn2bsT2F0AENqHaGo+o+Wp5VVw/ivEVVveZzoCuP2cTuedndWN
grg2oV+yLJ0XfI4VfnhulyU6lSW//xHTV3wPJb/ftksuAkyyYALd3kTI39tFRwtfg/mMn3DcfvrC
LphQWj0N4IsX+P+zs7UzfUQGGSZpInuZFnfE+BuetsQzsirToSFv4A4uCV6+Rvm9zoX1DW/r2eam
VE6IB3KY/a8UwmWDeeRIRtuktgUGuWad8rmFkwNZIR+LF04hWXz8oBXsFwrNosS+s3ROFBFFjrTi
H4CXSTC5km+5kC1FrnwrSuque654RlMWF3BpnzLkboncmblToZTNtht6emXixt6aG1AeR1MEl11X
j/t1glO3EMwJzBPaFR2btVnPNN8rexbJq3IsYWt/Br/dWb70/WOpjL9S6wXZxJ6gdLY1rudEcB1P
+E/D1Eoe5kA1nflRbp0LtQIGUaugsxByl6Abp8Wng/EhI0oTP5zVEUgL/zHxxhZaqtkJiR+qcSBR
xKxF/54JZ0He9aBtlEbqgPzR0sGELgtgnTA3WODHMmiZnT/OFjNuSaXIVLsVVwYBXAEy+V9uNTrW
m4ObbtVXOM8nlqshnIqUwvuM8p4/e/b0mbCpm84eZtfNzLODMmU8j1l6CwVAvKDXzA229VwJl2Zb
BOzVjvfCe+NxoYtP7Cy+rW6bKwm3RKxQI8eDwHBJ+GU5lzEHXbKZb0O3m8a2pwhXHwNBqvo7abUB
ZZfSQOzmNmCKXy/JRpiPyC8rzE0bucTNLLzRp5BifSR4yK3RtWqYNc14ehH+IaJHYcv0Jyll4Ago
zJ6dunEkFWEKO0wqKXcMAwD7Z1Awovutcz8ODJi7hWGgRRU/cs0IP2uESYDIN72zI5DnL+5NHcE5
q0BbvJJl9nS2aAXiupkWl1GCsafnQRRB88CZSkji3rsNSDnIxAENPOwJGIYjZRa8xfSq55QUdtxc
58chluCrhatH8lCVY2KLjmCJOdZz39S04RJAZUB30WjzggqbOGpfuHAhq+CalZAnYmmEVqU4Wf0R
ls6dLiHSKj1TpedPIDglhDi6ilN26o09KbKUJKrrnNo5SvKa0UBo9tDiI1AlUyEBNjNQ36pbNcFj
iqYjzHhN1jx6rKG0DwwfO1I9b5KsofzHsGnAaz/3WtlpgRGHsAUG9rEf58hxf/Yr76k90stGW1/2
ss2HLXxZzThXVL6QiGTGJTyhqDG9zufNs7WCPcbZOs1lcc5fU3MWkooz72uNJyPmVY7iiJM7r8yi
fDDtrhMRQUzwQ3dOzKTOXrSYsIaDaN9YWj8MDE9urAkB4pvdXzfyT57UPeeAkNuQSaWEDYXH8LRk
vAOEoSqRkN1afINfQCHygJ1hIcJs6dI81MKQQrSPshS3k+AmmHC5tLLysMptxcPD7GL07nFR8t8S
//uXPH7guy8iWokKoHiuVug9S8BilCCNQSuyrlvtOYb/POP9Sm6upX4pHnExD/SAC9MnJeIUCp1W
zFiRuoo4508x7apkSywhSC3vyNJnWe8VLn6cFL26GWJHMyEpE0qg29CtQ+cJIB+Nx0JObOeUkzJs
tbLc/caEdD6IX+Pd4ijKoHgcG2EHrnK4LGgFsZIxKzknmAbgFblJtseOEA22+2lN6fUaotoG+gpe
jF14pqD3EEFJBUoBqJvZu2uu+nTFSDxPRZCI7c8TAQzx9DyMCENZ4wlvPGdeZ1FdV2Qzd33EHMhV
LPT6NVnJWXCxq+Q7reSLsoIYjoLlCkpMQS9lzdpW/yrh81jvFR58xc1ArKLHvJ84D2Icau4m3hU1
8c7RxNZXNfG2qIm3jia2H9IEmzAEz4dLOTF454QngyFiVBbE4ym3rmjF6fX48w4vxmz5ZYh5frvS
gseFKjFny/TCsUrl4OfJ2qQuVshN1iaRyJIvigviVLNyjhKzwL923gUBzwwh+8W7OgfrsOqdJ7g9
RkFqGKGA63xvWTPQBAOMiYoEd7Nqg+79AeoSw6jBFu5AZ7zp9vfb9TwhG21Jj5yG3WPF6PiwqxAt
GhPyq26OazULkcu/GJF3qyJy/hcj8nYVRNiy4eu1wF2prVbNxalvtgwKe1FzOzN1Tx9PFcudlpOF
SprVEAZToHWhzcswHbwzBEGlWRJHdBJGFOGbnz9KYhiI0WKEghNdhUP3L4w9um0B77+ZzRMgcrx1
wUtj8oxK7VcAG2PqqWzB71VNvcBPFyi3MkdmIC+PYbYUFuGeMvfh7yOuDAhNqsSgoBIgcHuULbVx
VdCE+QAl0ELGof5J+f7YyB+raxHffSm5CgOEfmkPErkTLXSNHHFkLeEnh/CgVk0J+j+PWJ7dfzVJ
X0rrkia/TqS3hu2fIMz/ryOpszg0U3oWosefE9hfq3nFHc34RdYyUdIp2nwLwcSW7g0M8rNFtiNW
lkkxeINbXo6R4NT4sDcNWYepGnpAIeLKT4AW7FsYPrDiviW3KtYwbk3Pt0u2Jtaw2p7kb9qi6Nc3
FSq/cqNjp9AwlQEBbrM4mjxDS5NRXS0tOm2tYsTvehi6JyPGe1PjkF1RvwCkxhDrHv8tuODyLqur
kklGwhFgURbiA2d5lzgwlK26Sv823x60Oz+JbxchXsg3yoQjfp4ZWPHf5mE6gmhcx1w1L2du/tT9
hMh2+yfHB+SyqHtWiV/aB++7Zpnh217nPfz/IY2wLQ5PZ7obYN+HP79vH/ROPz0EcvsI7y/qtQfF
wGWR4fGRAM2oqSqJhFMHkYUoM078W0kEIrxFJZqBArDxx3ayPI1kMRnBSrTqoE12SBCPBZzc6Zs0
nTkouKOOldZflBOvYAuOQxGMyRBeK56L4DUWBTXuitibiSOvWvfuOG6LJfVEX2TFRa22fFGz3Lb0
73/L+lUIlBKbjieLfbwjsxD1Uz6tSpKUm/6a5QAGSQj2pZmIWBSXzuLExjwKkR2FoFS6LK2ckNAp
4ztCQ/qeiTy8dG7W66LsJCqgqsDvy1ShgyisYtRNSqJAym65pfBH7H0dC0Uaiik79wOgqDXr6k3Y
qkH0jkC9EKGLFP/4HmbwBRXEp63nFJ7ImwIIE1BnQGpJQUWiBMBJkM5Qmoaun4cZUM4Mz9/xGE+8
ZD7IknCksh0zFes8HM3h/zhkQTKLJ/x2TnI5i0HhwZosuVBTnel3X1Lq4hIsnxNDvZ4XH5fucTof
qbOT/2OQc2Bs9ftRoJ//MR9fBnrqZ3ZPJPYB1k98gZqG1xZz22AJe+2sz3sHx+3Tp9tIQRFohzyA
HKBLuDiTYlJgfjhJkPwOQ39FIjjF/uCUwWw0WKhCyA7QUPJnysAM0xgb0JOAImlHEv/vmy+ee1O8
JwCjYHkbXJdNwzupY9W9fwRJLENxKeN3NBag2VpgchGhAkRDyCT+pAn7DcyAnOvgtpLyUKXgAj34
Ip43pmtmPV9G2groKgc1p042OC822BC9ZJECYljXOe3HGYUREJWBiO5PJjgzl0E0D6FDCwGchHUo
02DptuXwsztcOYp16/pXgJp6l3OMQA4pPWTTyLFC5Cijk1xJylTkkp2uRBx6CNP3L2RNDdaOV6HO
V+zSW89Lim89r8hIhUcEGjV4qqWE3gzjmGgj6IHifOlP2snlnK7AvhsFlKLBFKjX+QL19HvNVHh0
6WSJBNUS1fv1vOZrrGtdR3a7vZRBQDMf8nCxSIWiMa0vzSWVTUEJ2ioZDdSOAzUUlQGL7+bd5DF4
Vz7mRKfDmCzUT1wkah5Uy/VMSh9Xrq85c8KDBBw6AahJMbhHfCySGG6FjLCmoptWEHgs8J+KwF+t
AH7hBC/lFZzRcCTcmwP2a8/HOzQXjDRkaDbQ4sfDA15Ev6OrJ+IUval42BGAueTC6qp4RqonSqMY
Q3tPb7daYanmN9kFzM5SlJhD21FUsg5nHVoUKYJmT0Ww2VeZsP44wSsczJ/Nj586/3laUpu4EHGE
N55cjE0mG7SsN3i9cyGgAYgqHyljn4gb5uc52bHdJTU/fXXN/3TW3FpWreOsJpbrstqnyxql8FVH
5bfhZTdCeaT6lrHu5l77YNDFeg8KKB0xw80IeJbAGX6Z9hu9dXHjxW5VZPlvbba++zK6XycZelQr
q0WkujzG9/PozCDsZWC5QU6G7BYOqQR0r7gAv6qbMQFYq3h3/Qd6V3VlT2LFsXGBfR/lVRA9qgKz
mqMwopHANN0E4yq/QFTuDzTDqmgnnmK4Y0p7wz8m4XllpRm12hvbbMJKZGkERChZe8drX4AqEMiL
XWi3ZMYgwQxL1Hh5pg6ZFZdZOE9waH5vP512h+/67U/AIByf3w/2j/unVMA0C2MOMJjsgTimw1vY
AjDbekm0VrO9r08WYmG8vD2zSuFOYxZBhmH5Rf07JAsae9nk9rNn0CjFm5uJKkBzO77oc3vzZt4e
vMqyE7MjbhUiuaDYGijHvFYEQR7t0SHmXAY5XEvPYEprbJmIpVnMWSIBEDNA7VaTkz9YbaCMFO2C
sVkM4z43CFw5FnegPHggsbi1OI5nVbl06vmPnMp1A57+H5opMrVX9JhGbqJp1uIp9FAJF5RAr0jC
eezxP+b6cAStXFygMqnTZdF8I8TCKTcxLZpzEhLrXJh7AnOXg8OM+ahEsinlqzE/YK5zvQzDOzeG
tsWfQ/58d+YuKtNDvvI2MUMl+/FaX/tFzXB7O1/0T54UFir1PmgN1VnJWs0J6d75lgjjM5vfJ0/I
54DEUBPd4okPCztPGl2tBAyHo0d65KHdlyxEsUPBRshSOQIboOaKzjBojPS1kImKTwx898UpHN8z
H1GA6qICeM/tFNW0BkoaaOP61XlSNx2HY3aKIMwcyib586UJjow3ISluuAuQqQZtgsJo4WxAXLSF
93exk8B1bqxBkwCakNKreD5hWJwHsFnP6UwErobf5z5dIzZiHRY3zJIxb6KLLYaQMZqQ0XIVqcI4
1a2PrbgLGfV//T3LVgXvYbbkKSBMdLEXJyeJuBvZaZ7nuyyTb/XEaZbR+tWOVgyat23T+neZZQwK
rRVmFGErj0PUAk4si/6alpapsLDpFRJ6ekFCPqaoWv17bGQGEQEBq0CQQ2CC+POexAeZ5f+VnWBO
xwAbowcY/ruwHqZ4cgMXvQrOSefJhT8S4bjMSs9inzYwAGfEHfbc2Y4sCYFNQgr6odv32BUk5zjn
frJoet5Jp00i0ETcJKhay5DHRdlLZvWFhQ0b/xo3386CZBbgpYXziZ8IfNDqz07eMP8Bv++EZ/H0
MmARyVjdkUK2/9MrMnEmPvYCb72ixng/E5+uGYSORKC2JZjjYRrfkB2V91tBx+t7Ga8A/tO/PH/L
+3gy8h38Irk8X8XVLNM5XZ4X5nJin1ZMh2NEFhaGyFCgVmGhlcTqh6UwETIF74xI3lFz5zORe7iI
69zbc+cpsTd7R7lzkQa8qEAosrPk8quosWThESqPrRbrCRN7bolAcnT1asDBE02OsqoZsbqMThh1
64k7ZZsv6NCukdezqpBF5rrZfLE9Nk4gE1I6NMfpd4k4Boq8eGEC4Dl8ZA/U2WldybZPTXM7qzCk
8hPk+Gah3tSUhZpaeeVtGZn26aID3QuAJzvY4uc6Bn8dqTyz7HsHFmlmZ4pkn9AuzPIIsN+f+O8H
rAGRVnBBJITn/5+NRde+YqEIcFjwTgN3p4OTuVrvYJrHWGyM1Av/vGZT9Jil9nEffigneIRrEv3r
HZsOXeoFK8KoHYS/oKAATYZD8RDz8UR1u6DMJ8ph6Cpz744Nw4BzjRBeeU83a7nrMwRZ0KUXApkN
HWmz2EIW++QoRkyH7w0DK9mxK+LYdF6wLC3amkDzDyWlT+hc+uet+uZZ/XOD/dmsb9G/DfyzVWf/
0o8G+9Wgn2fyzmKeiJlO8qEXUEvOLPgE7TOD+VQuD9hPP+o/iheK3m2+ZjCjnPm2ZBsxS9JCydUv
yZFVTNSPFIWC0mzw51cWZyxYOML9KJChqONEnFAd64GhOlp3Yi2P8YjLY6MvzgoLwUvGeGBlSQVO
yKJ3CzkAlvngPj8gAm5Bd53HnVT+EWMEG/bewhKFOM8/MepCDxSD/Fg9aMldtjGbi8mINnhQo6Vf
MxrGrBF3Lck8QLBoSS5R5222+F9zZBS1A2NhP4zvRPuYO1HAxs3VXY54U3VRXM5mTAx5dS56a5uI
U2LEQkvHhfwqGPEbAZFZyWpmiQUr8amoxIgIlC3tEdEef75b6Kuco8ojoWdGAgtt05o1MWsu4OXc
IWfNBX1VOWix9Sf4gZMFbWj4jyoBOFkl2G6nwVg4YSy00cZ2NnbUCLwkuNabO/ONwfITn7ywhC/W
Na7FCdORWCFaVk2ZpaiKtVAoWFB+Ze3XE+97FqaLbdO/ZtKYiT89H/t44UmVYfCEGqtxV3G+6LYq
2sgXRZoTIIG2toLG82Li4rpZG28HIkp4JuOJffi2Xd2WqNc91ScdRIQkwS4vSmF70yGa5WRM3yhO
i4vdELVTXuaMU5j4QlROKZgzTl0UYY50cIMzFuHw3izkBU5fOG7R3UvWegP+NYTvNJj5PP2QPrU3
EuQNA4kDbPFGkr50WH4UpjHokLOFHgouZqJhZ7XiU1kD0I4MROw8gKj8pLCygUFwAxpxxEiYt28y
nw3vhZYYTpxBjS5y1Tab39O0S4jI7Z4SbYgi2tA9ZiENmk2ReDu8V2NiDRUjN/uOrKpGeBEQXCTC
QUUcUuZndKhWQxpE1+Ofhp3jo73ebveo08ULEuPripZ0oGqW5sdvzBrMIIh3KlbmUC7BQ0QVM3c0
a7zFkaizTrTYn7qGUUt7VmoS27v3YNHikLUMvWXDq/Kru7hUL4T7uu5svorHmGHw8nwoNvXhbIRJ
EeVSEmPd0sa9nr+9+YRoouUZtHHGLUaD4BIDpIRZBCplV8Ek9Cc8hokiRaGDEZ2Zp2NccYTJKpse
WaDHMci4PstsGaDt2TeKoW1nykJPz+d4RTmaZHzvchKfYxPUaIPsTsoiQwGFlCgzCxIUIsYBFEw9
ECmhlxMWsEXHxxozcW1JmE4IXQwn5Dl7TLNOR2D0b7vOv+06/wJ2HViID7Hq/Pj9/yyrzupGHVUK
uvxvu8+/7T4G+sbafL1jLAu63r0Ay/85diNF3zchrFw6YOmmcSjy+zxwpb2hUBq58xoXb6ENid1q
32BBDPAvPvE/+MIsuxBlG6oIq7Yly0oaT4OAZ32kJzpjiU/5/A+PcC7wE5lieFfZi4JUEFeBT7BR
oAknRlQIDcNnfM2iABCM/GjAtueeXbmmMwld+ldKcap04vROf62/N3Rl7a5TwvwVoe20WdF8sD5g
UejDmtMeBAWb8GMc3lRv81sroYWwGt7CTDOvJ54h5qIsJKSMLJxLasSW60scBtSsQUlKmY69eEmD
QC8x/Ql94Z/wN31fqO8Ld/jONaOUaxiaF/DHHRlDopBEGkj38/VZnWlrhDYQKLxx8oLoToTHwNNr
lKbwcSFfLvDlVQGnlG3THiP4ZXTnbAlpOQLGgkcMBLlFeITZHYKjFSnimC6qjsKVI1jM0JQRZ15b
247OGlYkmOoNNvF1bjWCKedv7BrMZmQpnkgXvDwzAaGSOLrLZT5hRia78mJhVF5Q5UW+Mq00JLF8
Uwu7sNtos7rhxsx7+nAjzgMNOU5jjm1Kctl2cq0Z9gZWg5l7fhyDRrvJEgBr9gdhM8A9CUvnuGHO
FnK1mMVZVdpi6sr4UmoPMaJbYrq9i80ijOgm5bV5ohsG+OtNfF1gXNgea4OtbXxoECbQLUHUeHV4
IO4OD0bMaIW3idMruk/cCvyDmWwx8xYfqpZ4kGYW8WbbrOm7FW5tIFvac50NRov9ObOkEE2Pzl9B
qSQRvH+KpcXQKsAcsIwgbLRlHgPt+H4aAF/g/gytpkqM8QgTY6R0QVtN4y4KNptB5l+ybWgcdjsJ
/D72lXZo2SDP+StIUpllZBkFXIQw5ajrnN2obBZzYrEbsujw1ZDQ6FzH587Mv1FlzetvLb6h1V04
6+pvV7EmSoBkGpt8y94wlW9V/JlmWIbxxobX5tjhqeV/BD5XWIXliBljvAs/UQeChZ2I7lmJLzJ1
ZhQvPWFBR+yAMSwqT6T18XyA4DMLFYHyQTQGheacXXZyetj2ZvNodNUUwHZjila8nAd0/HMCFSew
XEVgUEinf9k50kstmRJarZrGeUQ6ETibBPzijDC91qdEkT5eeNbcglnRkt/maZNKPXs2pqhBa6bp
2w/fj7/GTC8IdeGkVPHVTd0Lg0T/agu/Y0kVWPwdC+gbegB8mDlhlVWNLeVHbnvtEmO80dYGpaF8
9lXGeM3ObhrlGfra7qn1odhKz2pp8kQBHfzbbG+Z7VciigIjvoyPHMoNmdnzVdt5u741UfW8UV/R
bz3PfySbajne1Yv5VSv/ylGa8a+W/ULrf46Hthzv1J0N3FTfFX0U7O/fxvp/G+v/amP9Euv8s/Gf
Crr8cfO/0Tz/wtAmc7GXyrp+IpKtPdS8jiCAM7nrw4cPIjxohcgzIpuoEyajSZC/4ft/pP1eTGpi
mB1yFv18sjyo8brcvM/GwTKy/5VWfzaHf3k0pzYC7M5KLkgzEXkZ+q6bJnLMAS2EVghgvtOCRnGf
oKS8T1RyvWppOBwKiU/ZNUvbuQA27n8vHEy1JmCoglvX93x8mrtcPj7NKne/nBFqArFaeniTs7Qr
GQxCO3skilvuFV7wlbdNplnFG4Ab8Re8/yIazsmflnpjGNTl7ph8OSPKTqJTGGXnLPGg0CVlnSR5
6ogdev/aGCRyGeqgSAN6+mz5QDr0FDU9roAhfj5IWApFJaP1x7B4flhFt5GtPzbgPljtuAFZ+OZ/
HbWjWPUQv+umNiFZ01CEzVRMOXqpEB3qYvNyIfqBGYOFMExpocOiC7Z5FmezCs8MXVBFKkCHVEzW
FiaJ+Yhx6JxIxzBB/njaGwzed4en+/3uYP/4YHc46LQPugYO0MpAxEhrMjyB4CdNS/LQ6sVYTlo9
KIsB5jlwqShd5M77LbLiK+GJlZe9coiaspBxvoff6cUryqhjfYQcsBhGCOiHZ0z23YZN/Ctk128g
s+YWVyWKh2xZVOQK44HN2gLTxVS1nPDtmd4RGZcCJEanndlU849Hw0G3c3rcH5yZVdgsrVKFZ/hA
1VxPYJwv5Za+dTm4TDAfzKe2bA6vDJG6TBA3qrPfS0T7fyGB/MHy+DJxXDj3CyRvY7ossU8YllUs
FF/74zuQVdWyzCHFKny1xE4TvFxop3lfTW4Xo4vSgBDf80MZcYn+BUn0Gt9j/bUZW4FEXzBIxJhQ
2lki0usB1s/R0yrkVgufho1P7WtEdjHUK0jtYrjLBPcyJeLeNeCvvOP3p93+EA+fD3tHR90+yto4
E/oHetQo3DW3QgTTJK4xSFzju/yaYUVfYfov9gi92tbSlJ/0cmsoNVMKw+YaJxwOqBZmZRwXyTLz
radIZeo7gZY/cXcxu4cBz9dhpPxB6GGCXTCcXQUJ7p/cBivin5uexycd08RakOI5JjW68icXLMSZ
ZZefLOo8Tgh2L5ATUTLdbZ/0sMwMJJH4coEszwYmMpfe4kF4EIxIRhMXjrIkqVg3HFF+jUl823Su
SbLAalz+KXnfyQCF9umI/ilkMcZ++Dk9w7nUQdpIdxgN4VVJWt5/LsBN/ZSuEZnOR1d8gGbBCHOG
8ChwG9p5GKG30w7t5g5/dNpN/AyHfBqnmJ91GvgwjzBmV/GtDWviJzzNLee30o0Ypizn7e0VXtOK
lyirzAV07wNmNDAH19jz+bDYIRyCsQgpDrgKkm7h6DG5AIBpC/veSBG3REEXO/KD1HRND1bzLoG/
EvcX2DrkNxHAzN9cFIOpOknCqc8SUYtrVFqYeplmT89Ygb/P55PrRj5BhJ4hmNNPgulu5dQ2aGpF
DVjW76NJeA2MzktDYjwUZgE9wrwUAloaYErOOkuIg3VpLUYeMgigndE8uQnGGvn4ycjjXuwsnlEu
HbydJRXwkNg4g/H82SyJ7yhZB5DyFXT+H+jznjTdlpTXMMtsGxUvdvBgIXvF1/tr4QTNhUwxJ5Yy
oNBG5bCN5Oss9DqfltQBVDrcqsJbMM0mVtGFLPqprOgNB9khOwvvSq4QB9ZZqEKuyLBvYXtx2l9I
2Nl8lhdJeNuMKjuYEp1l09ONLnKd6rl29JF2HrpdYsl5vll0WndVi87zZ+76I96LkwDkpQxH3urd
ax7t8CafXU84rlG/ROHdrMluTtlGKm6pe+pzza9uWEJfuYltXoawkH/lMdxgbZmmIZzfMonzwXYr
/b8/ZcMyQxr/lD3LcQ79G9i2vsrOxbwAQ+Tc0so1ZINbOVti4nbp+YNpHGdXD1HzV6ihQuV5nDzQ
jywCP50KMcOnPRrZAeYGsyj+fqur8WvOQOzG9xSJveN97wzFJoxJDsdbLq/h/5oQ/Tf1w3nYX96Y
SFcX8uCk6jUFrm6TJqN+1Bw2TuzYkx1b2kwxL8LttUPy4uUtMcxV/lZqaNqHeyu6g80qynE7GkIb
9rl+nQRYYYWNUdglsDEbmd4esfWadeuUMd+sjt6suw5d+kaF2D0XVh390jjvnu6Dr9YcrQXInFRl
gvU5p5bpX8WxYlS+nwGPPltzbHCwTn2KWFWdYmHKW9vjfASj3A9U6YaBIO4I8pujG1LXyRuGtdv+
3JGT/HJO3N1BU9pkmtJzomazNzWTOaR/KVvg0omio/zw5Qh5QxV38xIOtGxOcgSvDbwK/tGWQ5Xf
YqpPBBKH1o0n5iw91rDRlw7FkrA458388G0tGz4yBEjEXgskESIoYRxwauffTsXqSVdcNwhowAPc
9Sb0MrMfntH1B+7FlOaWETrNzGUkluXqMHJLETQLIG0uICmsuQvAEZmtymhpCxgmderSY+5YwNUo
C6/l45fbt/7Cee5ypWWABeXBWf88hb2pQc1p5wx0zwOsW93WM7bEc6AEuiXEIA2JprkxGejLCnl/
uXFoonhozSHVGjXGb3kg6DNiRtp8IodiNwUrTGpqEcUzMpsEIrIVb7C2E1OpIRNXU+Q2fI6UgCaI
Pj+tJGlsc0ljOydpEEokZUjEVhI2tAUd07QZmJgzZyOp6llHLtSMMEUZScOsrM0NlZFnspnBRpsh
5fNSAi9XXNhkOOdTvaytuRzeYsqYPXhDI+7HLpNqob7hqxDsr1Er/gVd4txwM5wx4XB44U8meL1U
RaS56AeYIp1MM2E0DkFZxAu7uCGJnwUII8pj7nuXSYi2oom6C20aj8OLBc+mjPDIfIPWHyzLU2FE
dMRhIqqjuUheg8wuwLoIQFsGFfsqTl56/jyLp7CAR3THGis+jVkUN9qq8BwECNg+XYVCJuCRL29G
8+djPHLIclwk1LkOkO07dovZgvz9wiXO0Orc1e03C+0Wzdvd0IeqgIO4LdMsi9jsGOUee53+8Ynm
PKdjwP3uO8yb2+/u9Y66h92j02H3qP32oLubNyri2S0NOTzBpWGG/R/AfLR0FKyjXBoJMSONZqek
OpU6H9nBVXiRndxx26SDyAYw0Phd66FGXzilQ9A/K+o8NieaHbopaUDX11W13mg9MQeND9Cg2+53
9tnoKXzaR+2DT4PeYLh7/OFo0D48OeiuGE/B0Gny2/WMwAohXRZGu6rQCBBItlVWqUdVVYcbF2sG
mPL4AaYKkmRtxmNrqBYGZltljOAS3YxFBkc18LDb8qrsXsOPyEcdo5qHs9DhLGw4n1aAA0ITcuB5
mlsoNBglAPKn1oW4oXvnU4drHtDFArd1fMKvV3g14R2+w7P98PiJHv+8056P0rdz2/PpWykThhra
x+r5q3NiqKgbkFqyJRkiR44sF2mZTz0t86Tz+VLZ3+E33oL6UsyaEr/hN36xq3+yqn/CUP+XYqb1
6p+MQwD3uROo7BA5i9Ck1l8x9NjPT+znp7+Yb2+afJstuiGXLh7MvRk0FSlHGyO6/YbKcWWpW+RD
HBMbMTkHys4pc2PQWCEzW8YBBLSFDe0Th/bpAdBowWgINtSor9kLVWtZFTMCT1Mcw1UCYhQ3G/Aq
zg3ssP1x2OlisvzhYL+3dzrc67c7mDPfOEZJrXaY3LLDcXgtYauLFrRiqLHxcrY/y5gtxfOfYC82
eK3HJnSj2kKrtmD9LqkmRsz44Iie/oA7By2gBltAT7ytFaaXVd7nlT+xyp8eVBnJXl/0HB/hO9o3
43H5wtQr6EtUhylnmcdKHrb773r8Vu0cHDx9U0ghUkRUnMEis/Ra826Plp20c+8ES1Vpo5kNFpcE
w5yzQ3KTFpv6vA/Q2tWfydBFW8/SCRr4qYbTK0+KyDm1SqJj8UHUskA1W7O5sFoPjA0rQtf5sHiq
r7kYsK7B2hqbxX2pU/ZZScF61S+NAdNOC3oZDv9QcWOpljW+1X9rFI2CMSbBhEnE3P0eoZ6Hrj2Q
4AN2dcS3a5S0r2zqv0OND2SvMEjw2t8FSqynh218DSIry96EgSqUuIkO2MGXTswDKvE7viBjdT++
5eWM17wwVsfQAN7kI54YAPPU8He8aRyLgzAFiVmzgmsxdoWlWeJrlh7iEKaU7vX17gUXFv0oqs+u
KVG908rhX13Ul5covU/FWX6siHcpWbWE8H/vJTg6d/kSHO494DdJ16V0jPD4gBaAVGnTg8S8iE/U
PdOLQK+sItjPMzU0lEv5Q5hd9aJxgKk08G0d5FEttTKpVnSPuj7EMIdy3PDCKYfDDuNZQ5ExShTO
H/NEr2Ho/U2Cc4W57rB4kgTYkehojY/B5+TMPm+G4qKsM+J1qHE+KJ9HrjgmQQs6YVc3m81Xskkt
IwlvPMzOWEzTvWmQh23RhshJTEIkhEyIiNpyiDQhBqLaogHu/choUk/YouYI9oVJfJk2QZW4/eDT
zVtHsbohq1ppz7O4wWKckNYrphi7TusZ714OEDYj9O++GEjdE0tj36En82lkFEHc7l/yS+gDZkBK
gQMugF4xTOkuS3ye76K5Lm4jvfeCSRrQCLQPDo4/DNtHR8en7O6jPXjztt35afihd7p//B4vxOzt
2ozA4HntKIpZBNfxORomUu1mR21i6BpjvCDt+KIagiQMs7PbPe12Tru7w85BezAYHrUPu3paGnR6
QEW/7p3bKfmJu2JKV7ovNOzQltWOxmKjrvqOaynPS8qf51fUojNF69fIx7QUr3ZeA4DmyMyCxrdl
KvmIxeTRM1pTfcx7Iard2SqZxi13u3vt9wdsoEEwOBiYjPIollySXULe9Pb8yQS5Jxo7MbXJd18Y
G2LuoXuv8t0Xx9DeV4CUxFSlRFZYzmr8HgOmZhNfUltzXdALowGd5rtJEieHQZriFVYlxF6BTsxT
yhgj+3Lrp7w/v0a/RhUtY0WlP4+8za0hWbuGwMOHZG+9TOL4ZuFdhEmKOaCuAuz8VUDQMFwviLxk
HrG4vXSUhLOsadimUfpgOjCOVW49f02/jMmJk9UGXnV63e40pcIB9DYI4Bgok0k1KV49Qh3Pdwkv
ALub4d3ZN+QEHAW0uVCU82QS3wIXiKMJhR+zK7vuQCBmGEt7dhh5dLvJUxKV2hHdnBWMN5IAKGEE
D1O2YxFydP/gJb8ADJjXFZFSGN34k3DsZ/CBwcWwRmKy4ide4Sdv8hPDPlhA56Z00zSwriCBKanA
lDe1wNAmk0wrda/y9uC48xMMbvvkpH/8S/sA5Omf3/f63V0+LCtPovf//p//l8QTRvP3eQgitD61
dNfYpWDQ7DZEILGrOYiXDTFyuWlcJ9p9ymX4IYxXAnM4xGgmXidPzAQE55Al20bCpqzdrV+j774Y
g5e7WPl+3SIHZHSiyhq7hlH2csf7LYXRxLDa/4CHqg36FHaLauX96V7jRYWOcs343fPAs3JXN4rK
NMp/bjJ7RzCTPTWpXzWXnP4kXvpUduSFlmi2J7qVg1Iw3NQguzWcNZofaq794XTu+3SyLr3yt589
B9YfxRHG7JMqwGVQUBl5BTJv40a4FydtNTf8SuOC68rZzaLeG1CvqH5lTRhbBMaHOEO0O8vlJxTT
Ha/CxhbWidAHZCGF/47qC0h8soDEF0s4kecgq2YVEs8xjAkTxNN+b3dRXEHprs+k9VIA8gbKHAQ5
KvGY8K7Quq2AYKcCICqYtLPShLXvT9IeXUXa8dOgyqn40qZivoCh56dBmiEF093qlVrNPshUVYhI
Jt5BlfuSRldpcLl4ydJ60I/O+34f/Wtsm0GBjUwJ74b77cF+Te+eZw5IAbwiPFRF7l01KtjN2Mhb
dQTSQIJ7vYMuwxX/M/cHTsF/jpkwQWa/ffTuKzcF5silLc7aDyj4Pwm4iumN44DtC1PijRjnLzdU
xVXyO0SHAWl5XI7HUbr/NWrzuqgK5xfnG3JvRkHlPi84sGx22ZXYmWk/Th60D/mXfhg1vaOYi/i3
eEwGdn8mTsA+5xI7SHvBtjSpA2Yrg7+ph9Yl0IQANFRKYfGRx3kOjewGExBVYGtKb0OacHHtQ4RT
SiedxpTQrwV18eIHdKinAeob3gwkXkoGFcxTLoH4o1Ewy8hDfjlhbnruSOdCIUrJQI0ga5McJEQZ
JNC0KZgyYNYjjCmynTPyz2L46zoJd7qDAR6pQypm9HHc78E3pj+1D97Bz9P9w+Ev3f6Abpztfjw5
7p9qNt265kRnEPpC/Rq8P6HC9O2s+RuQULXyB7CYZjo/TwlXvMJ7a5tZmOJ5thsm3DpBghXTBN8C
H4MPDPj6d1+IoYKIN72naR8qysvBvR+yBQFltEHB3Q/h04SkGUg5lIssKGnbqxy0T7uD0+Fe7wgl
tfdHzewuqwjT2HQGSxiIQoEiW5cN/0zM0CS49EcL+IDwUfqB0i5OjQQMksAlXg6NNZoJqwIoIZ+o
1JoAb1rlvCcHNa8YsG5TOUef5UMOUq14BOQNTjrUvHSHG4U9HvlSZtLc/KC+emViD6uXui4xZ0QE
2CqulVbQhAhv6TCUNhgkUpY3SDo/WmL4YFmqO7XNv2naAFelaUc1ymvDD41Y1D5Tg2JIr3yOa0WQ
Dv0ovIBx3VPn+c1JZAMCpDOc8qJNFJ4r+YMkehVzzFCKyjeX04BsO4VZJSe1OyCWye65JoTcqObu
QPQA2jKhFwmHzt3fqupPLoGRZFfTX4AmyM+zU84oi+BaPW6q3cYhX+jMuUhMWQ1okWSk/9c5PjyB
3rwFmeag+67d+WSION1BU6S0rZaCcYyeJkG9aWYx44FcAajUHEeJRH5pa0qLstfLjUMnX2dJaYg6
7L3r09S1vD5sv8wcIBr0YH+ehLhVAxl994WBV4rLPZkf4O1snjVoG57PLhN/HDTXnY1ybuDMqn9f
cibeyUW4ZdrL6bDT8JIF0xpKrNHpD+3+EdBTy1M6JEwnqupGj+3eScgozZnNWEqlMMnSrjhj4prF
5CR/TtCGFIyHvFjK91IWbOBPiqrNo4KKF0Di/SBd1hwWAwnMam2vvLJq1Kwe+RgxdjwNiioyHW/I
yg3jacArcnCHmEoU5dwomJRAEY1PteIaMLXTFQEw9kI2OX6CKQI66Y29a8hKmmoyRKl3kqVNXq05
Sm8AEG29sP/JiW5Or/HuWbZb6O+t7VdXXB9pM27W1z+UABi0f+kO994foP1scHzwnhjyydE7gqGo
woStvf9a0HnacaO/t3pTR8CS4M/xYXd42tvbIzA6jZkNGF+WgSahHFTKw/cHpz1UKY+6B3pDBud5
VEifJgbFxcom3KBXE6D5yQTyp2zbiuONQKLOAs7ggNdNxihXziP4Q0Yzxe2XWCclWz0mUC2vrK4q
3dbjrsnk3NJ1Z63kSZA0yEbNVJEW5xrccNRI5zN87d2ChBrfSlcChjmz4xovQfW8jVhaDmgip7nd
v+SgPKCDBtHBd19cdAgF+Ux7OgvKVSulMeiZYLmCrfw86qBYRwFK1zRU9LuqS1Y/dzTnA+iaxwfH
/eHxT5x9T8bHOrgVvHnKiadHvUh/Hgu4MXx7JKXohWOCxUurH7nifP/WYJtCo9E1fYkYNbgsttvr
CxuZs46O1M7/4W1U34S1//p1/KTxud34T7/xj7Mn6nzhdxtCbbIHMK8wqvFMAvSjiIG0a9ZJvrHi
IvpUBV1zuYaEew/DsDH4oo1ark4c3s8d3ql0XeQFFFEwHImHT3eYUX7p4x7TR22xuOrw6x6DRN/v
7XYLxp1ZSN1UAJpbfBDfBglZYmtKgHbANIuiUKxxmEMrCIi53YjjmCMihpWvNWQGx/8NY0YWob9i
4IoArzJ6xBkdQ+gYJGsYcTxECf1cyuhOyzhZ1zMYzVN5CEVdgGPnMcXXmJwe71PapVfNk+NBj1hv
72ivd9Q7/UTl7Unmt53D2Bn3nZNcGTKeJKfMDoqPKUw4DrFAh6ck+OgotsgX+5QvRgG+MV055b5a
PV5QtrzcN+xzLnrXPPy47b3ShgikA3yzI4b3sXrYar7YzGW14GP++a4F+NW9BfyBKYKRaeE/Z7my
YiLG284gIM7LsaRgRkgVHY12iDLEfgIcDYm6/hBS0eqextcBCyZlcEz6loQVwScM23ORsLmIabU9
cLFJRGp63DAFq0Rj7h3fYTgYB9VdXKfFyn2zJaFQ+P/raqiygeenqF+zGwSLlsg25kCtFRxrSa2Q
at7Rt8ggU+7WrHvGW+GrdKSzQ2CvVMs8ySFvQ0OIDoNa5+/KF29KscD477dbyN86gveEmd54KOVf
EKsL+lCcjHHhdVgoIIbhNNNFNLpK4giz11EY6+czW88/iEfXXM9nixLoV6kwmtx1G1PKPtwaT9r9
9sEBCPEfjvs/dfsDENcu4HX7l3bvAI9EDjsn7+El/JsKL17VrckCbS5XEWp6/LhXlY4yqWfw86fT
eBzUKKTcq9LN8sAE8Sg5jMdE89/V0EShjRkMAlqWiU9TdKK2RafnfGwYE36L0VpBwpd4eo7u8iAa
Vz8r/FjkQh0eME4WH3DOK2SuuWU/9bgNcZ5zyCLSh3fDGVWyXi/otR5FjxHnY7uW9XrBXyPnHY55
GGAOEsXjKyczf6Gi5/WXDC6Fy1twpBYxJB/lcBxcVqSNapjF8L+ZeHkbnA8TLl4O/fFv8zSjo0z0
WYGcxWmmysG009lmu5SJKAvWH17wExH4SnVNN12xSH4dUM6EOJxFWh9yr5VRYkiu2gJg3DRoA7Ne
5yyCwyy8MOapwNhnl2NGgaFS8WWj2Gt0Fg+RHPCt8OQzdymaHJiCKlyydXTJcgqv/CoPwWMUMd9c
DceXaznQnpzeVBMW7oL7RZMWRp2/prQH+BbA4h9YHXX7Et6pn21BUXGkh2jwI1Qq+PSpVgRh5Kg6
EnW0d2KpWIAYxmwpDIioZK2n8kNHpULQQfKP+rGTQjQDI8P9bnBpQiJCOI1P4xl9KgACa6zPZ70t
VxhUQOGOTp8VVcRVJ2r2+ZoraugpzWCuw/jazGhQE/Od8nFzDCxbkbKksJPKF3z5MWrBcItg7AJj
2Vdldb6A7NfSWuqebKcls2Ascpa0GrN+sKaEKU/JRuXrzJBLYG0pB5nQNOg2BJA6wyDlOkHavBUn
NEhiF4cC+FkNOrnReI1XzSZ0QEq+bbE/Zx4zAMEGf9prHww7x33YgruD05oMjTcb5L+0EPgqXbGa
BFZAX4UMABQoQED3egen3b53LwMlJrDgxmjx6fDj4rjr/ubf+M15Fk6QynhgUtPPYLWPmm3604MF
dQk78iaTa5iQQubf1WUhJhl0WJaVL5gENVnoQgDPv0LvmzK/jTiNzV6zsWW3ytpamzAg5kaFlWky
+qke4LHxoPl+AKy587fNp7CfQAN4alJiAou6w6Kc2KFC43f6doF9+OxWDo3DwYXMQTuEb0ZBlNSo
Gli8aSalbMfK+SajJbhah4EDUZgF1aIGQVtZBfslOEulprH1YlMdxMQTn/yFlX2vCJkit2tIoZZ0
cecSdPlCFyELUJoZpkQdPW1UIR4yi6cklcsgnpacBVHphuxkKlQpnjbNe3/U4WLx2TiDrZ8f5QW0
V+b9szw2bUfzYLMgbvUld4asdIQ1C4QUyWz3peFBUpNbvAJxAQ7/lrKIGL4Q7cP+fJ3xEwTA/6ob
n/+L2dU3Gz82h42zJxsAaiiDGWzjK7o4eKffMAuqyl1m25Gqyni0PBuLftO65V0R8EAlPV+KgGx8
lQQwLOmbTH+9h9kS1bizEMnAmwLZele03YG6fREn2pXF4hgtXpM98uecOgCW+kTh7anwUM0oJzBP
U+TnXGieP8HiC8zAPpbZqgOKzBRJjdhxD5alCKMqhfDX9Lp3sE3g0ERxQ9hatQ5JeCOfTrUkojNp
hn/hTZioyE2umuktkeV5TK7MyUJe9IwnU0QMbpywcAnMQ2+d+IVpnYTnQcLSbGNaqFSelRIp/QEa
qhleivc/gBwB44UxhWns+eNxSHtGnU7BsJNS5MmBNUkpmSIcJe8qHINo15R0dAFz2lFTqlnqiIkL
WsLDlMZSlAFh9MlYAe4opGKC1U4G53ilbsky96+cjTIXc2YhvHK8mQG1TyYFOlHH3zSZlcF5YwoP
WWRhUB0VZ6RXXz1oq2pUcsSz/9lQLid8TS61zH6il6oH6vhGYf+EJ8mRYLtkSIqC4/KJpAuG3Im4
ooh9f8wITZFtCX4sKuYNV2iM0DY8pcEW8lAs5IorhM8iqDeG1rkUorM35yAGToAFIXWnxNr0eRDt
cCXLRV+5skLTchXOxQ1ZtUVDOvnoTOLhAAU2Tog5e7S2WIT2gUOJ6hqltXAQW8cqVyuKnFSVi84d
5ZfEMlCcsWh5ERzzWQDkUQkhu2Inc0xeYlGUmzwvnSGMPm34ubhDd+whSgsNLiIAbVxM6GDUhQ+9
G1PoITsAjALQPdqgrQZcEYe5m0Flcrs+rVBD+ClP/qRiMGTykaKMgfWyLFC8Pc1g58oDVZ77ScA4
0xPdWnMm2ahn2eIAnFmU70/CNqbUM0vYXZRX/FRYUQ6Yu7oQtwrra4NZdYOwDW8OXdOEKaajDJyD
1b5hyWos/Mz5K0dRL7sUS5MIvm70JNFoxmqENGQrTe0UQEyObJvLxX7tgkNDrOMEqK97a+nRgtPr
NPmqM94tiqjpQRmacixOOTVtDYau/NExYKlTxfDWaitn6rR35VK6IYSuFrM4q1bNQdHa1hOl1R3y
nzFw7nqLWhmRlXRcZ0CsY+RVkr1zpJQeqdRzbNYxW4TzmoDRIldy4S45VlYGs7zI3eWslYjslWNT
QVb5pRNKkaPnqQHeWc3npGFZzFraVZYjypxj16zqeWqsWiJecC6CBZdbIZiXiBkhHmZ4UCaAec4o
oiKx6wIbw2TA7Oh2ILgKv3bU4gHUdiUVV13WkrtuPmDaAUOa8e3aejS0In1eX4zgHgbJVik48lcc
6+9w9Jox2lDDC30Q3f4Au83C+OdvgIBkn3mZYsm9ZWvLb6wpkjSMtUSd6/tjx0WsBb4jvOrghSzL
yZ8TXz/A5WNF0vIZd30z3ThltUuKSKIoacGaPaMkZaCAJhZpCPB5umQVtKR/vTy3P8XFxj/T5jai
DbHOudfqGy2zphq8HIOdUu56WP/uS1G85L0uZa/XNBF/qflVJZyKl6IWF6D1V2AkiII3OZCmsB1t
IqS0zM6fa4L1ZwNXUDJ6u1XNzxm7tmnHZ3031k48G54ibb50LJ3WMhemuQnPYVsoWLj9mYXyRAFE
S1Iq6KfhLdI7mvd8KCPQuhEyAtofb7/QIXK/7olsArY7Tm9THIKXDS055q4Oy4d1LZpTDsedPja6
rCVeqtACsfjredqsu4mhrnu2HGOovjtO45/wrJwoGZ/0PnYPQLxwn8g3YkD+EJuPMCNxz7vhvskb
zI6CYJx+SNiFHfmCY54eQZlU1DFIZTYMUmkCpqM0HZawwgnx99EJ20/KCrHYmONossDER+wwsCol
9yVhmyHDAmP6ive6zdorGJ5n39biPFOm5lmZjdlp9hP7zh9/QI9mOdtS3vQ3M612ZMWfWYa31ax2
s29m/5utYvirlRjeB/6NFCRoHKWx1ZAxygbkjR1usqy5I5I8jNakMLKsbj8vmLjQtgSXNcdljAxo
Zxkz/HZMMW9j1JjkA5ljfjKczHJFJpiD5maKdQfN1PMTWy9wOBTNn1E8x3Mf4C+aPchRNPurPESz
b+Aamj3AJzT7pzmDpA/VwNSSJ3YsAcPh2akWTSKx1DxAx1J1eTNmbjfG7Gv8F7muFjknZt/AwTFb
xbPh8lLkNWHaCMkaxN5o9Cd1Y1WGXTJVYDd15hOXVbWk50vq2wq2gmHdUL4EjlDJVX32RuviWX6E
NE0dK8qf+RAs6ZcpVOCJaRR8LYntUg4fTdu3hYB89o9cfdMkkNv7V4DgMhxIOObHB+DjBmd9XQGe
ZZGQkOT71XvoMlyUrr0CwWGVFqUy4MxiUibCAzmxs/aCxQu2ffz+9OT9KbHsHECHlA9w5Nt+gMf1
g4Rn4zE68Mg9Bj93hkIogNZ3u/1uXwg2+d3Grd4U770FuXmKhwVYdq6LBUCsPfyROwOhI/lSTvMC
SLm3j3YcWapKHb5qcPqBP17R6UsJKSlfKM9sqoWLOXy+jjaW+X1VbBKn1XJNTY9icm06y+xb7g2q
MDHSir4iI/8k28fKrmLXrE4NMiBqVio8g1irFYPP+bNWvu28vLTtPHLHqkhJOJ8QSU/PnR8TyxSr
3VzHjaiFiElP4Fi5AaErL3KXomzRWcliQIU33OWWYO6StxHOi9mJVW55KwVMt76NFkWAPz0EsGnM
xqQc8FS14LIDPm7EeIx/HAVRJh3AKa5tANQRX05GflVrqi6GSDwsiqHPRn4OLh1J9ZPFnwA74od6
c7C7xhGdrwXP7ow2gRdBNm5BXK0JyW81NqqdkG6pOdnBEtb85CzN8owQsaDTGCS/JAjSqlXTFqxr
6P2sbFTgX6n0WzVc8jSrxTLWwCTulNWXpdqw8Pu4r1pw/veVauMRYd+BBPpf/MlqGBA7nmgQ7l96
55wQYZQ1Ol15fGWdFUZWlXWOKSDTf/e2Icgap92i8NVn3ai4yqSbFYrwY2sCMDMXx8p46dVWwMoo
7sZpvZg96AkPPls0Ube4R91Y7nXFss6001OhCt26dzZLNmiV5MB5Y8tyTVm72fgrvMYr+4+jeKgf
uZV4V86cUEv3eB7PDsLxRcAueZj4ySUmBAgwdTploQpmIXyYhBTVzyfjZRk0DJvXo+a98yQG/RjW
iJw8nmCZzR2GyZfBEyvdA7yAVeMMed4B4ok3jvhIoXSGAW/vmCcXeO1EGTggQm5vT3w8YACo+BGP
yffOMe20RrEgw4TT+bS5nAxsVg+6ncUG4I2x/OC3JNcyhI8p62VKV00H0TyM8GwCHo3Ds410riOd
X1yEI6QJ+JJCm9FlGUAxjvJmD2MYcdZhgNvebeBfyz6UwaPDJ5jujeTPOTIHjoWTeICUE+hC6ZSz
gyq4ctmhlhRp6RxPTEUNXMhNlDFxLClrZhkom0TwTDnL5+0D8PDySp9u/YBMETwfpYUNUBMn4UXA
0nPyeeVnPGAg5GHjBmb8xGudiikIeZ42myRhmoTzaLWcsittBs4b2msOK7AdVqfh0/KqBotfGcHl
+wI7cvc9ZjF5Y66WFlMLCxsQaWz1NaiZP/URXqJi2gljdALSjg1aW2ROd1yauHepjAdqRlXDO7/x
1mqljVAuGx37194PdDgSr3LTbbp03iG+rtSW4mxVwxwWdOVkYcX7h0zYmyYlYphNAiaZ98P0WhnW
y2fLCFzU8dNvxlw6XO5h+VbdvnekJC41MOeIqiGuNmWhlkghSYFU5jSz5A93ua0sZTioj0/KDQBZ
LKosOerrypPEEm7ym2M1M4I0LaibielUJl2fvblVIFu5/Y81Zxp1FtBQYvbQkXN4RAv7xABr1gm9
Jd02kQO5BKIySzggfloNIlkH0byOvTq5049zb9Xp6vYae5OgOFbVYn7zA+Ae1+TyfMDTqCp7h4Fw
gbUDK8vUL3h6lwdwtRkqCm5dDrF8gtnSelWAGIOskJORhQUlOQ7OYip2pn3jhxOfuZLzdl/ur6eR
BTlHSBKw/b3Ufr4yZuHkeHA6lKSM/vbeaZelVh/UtGpPnhSaT+2ushc9HHjHcMql7hrZMtbhmiaj
6a+YKjEJv48IXbLt+BO8VYkb96ta21ruAVeQ1g+bm7WyFsrtaRyDYjWOF9CvqmIMu65/kpdQ6QfL
XVxb4SMkl3PgStdr7qZ1unN6kPQzAnrii1V2G4HKCvtNueuzSI5SGNWKB8T2FqCPJx/pWHKLQxlu
r3ashXZ6fACrDOTk4W733TKoau3ijbGvd0oX7dei+Np7/ow2vWI6WHUL10igtrZcYCFfjkZjtdXC
05fLIaX+Hk3yWsnVYbjzsRZGuvKaIuC1tKZxK/yWqxcOyQ20w37QSMhfylXMi4zbVWTKgiyBCUBD
GSqj11F8GzUxz4Jjd8DLrZBVkM4K8EAomgBOjVsk/AT9mC8JNLIoeT2g55/D0ABsJzjjrqw0wDx+
Gbv1i2UmuE3CDIqgsg2Kw3Vzzc0dzW385xG7SOwh2/nvo/fFG7pswLlJOKdt6R4PfGv51qc1rHY+
Z3ur7IZGs65e9pfviF/XWREpwfdFx05pjb+7nfzGWSo/lbXW/xZtyWw+0GBwLAJkdzyawt5xE6lX
nWzijde9ysnRu0rdOHq1BDbHNgfa7KiCzA6BuTWuRwa2S3xKjktjqGUl/np4I4blxV8vaZh35Wvb
fXCrZm/tQ0f5C2XIb8DPbRUDFL2wzjc9CJgxwxhDpFGQU0zKVVBk4SyP2BZdYlK0veHy4ddmm0x1
l71clafifyUqktnE6pxmKbex+rACj7VQKTasf0PVw4mdBrcQiQdoJi6yMrmHMT95tqTOaS5pYAl3
MqBfLIMql5iOeq3guF8u/2rpgsuBl6vYedzwIaDvyxerPA9Qvk6tvMlFaxQVdBYxOLCSd5cZRoq0
Fwnsow5pU9NXw+nlBzxcLpssps9c62RW0pDlmuVSZD6VILP/1cgsHoKMY4ih/VUado/cx7pnIP+p
qF1M0n3YPVkk/jQcU/xh0hQ5qVO6MbrYLs5zUFRlk7ytuj5i2nMJJJVcOK1uFZoDqOgkTtMJZdec
YtgjhmVWS4rz6NP0ZOJHflJW8hy7XVaAOM0gwPSkVeN0eNntojkuJtenCUJPZ2Z+mQTRJVlRXvPT
/oV8hoGu5Q4j51JBE3/JdYGlEX0ov3GfISpnPkuSs38pHj13azQzfWfMcXXJ/maGQZVuvCuJCfWS
E/1LdiFHB2pLRISCM+VFqb1x3ssdSYW4P4hA8rI/GqkMoVy9MOSGEpvQo5wQIEHwNbW0smuEXQHM
bos1xZLQ4Rinvy3ncCP8dJNkCX7Fxq4Sc1ytzOn8Z7x55XHZGFPojsa2z7nKonSfCV25VsPHQYhD
zdMWP/EqeMHCE69qFJcx2HRSYB6RucgL8HPFPr26wgQ6QpYUvkMG9sGRS0uilYI7tHuhuc0MT3Im
vHCea7FOSDjTWJQdRymoX1bGncliJbZTcQfnd/v9435LV59FFrbzeUZWPLwM9jacYJxMlIXRPMDS
FjkVheJvbHi7nADYLbIsLL/peb0LAj4HOYYuBE+8IEIKTlmb4tKPmT+6lg4NgHYdBDNmthTZX1lm
JX7lIRoMiUzq5B1lVkg9d6wXA8VIaCwtK7sJErHD4iB1EZ6/z4MU+RhenXLh411Ka7ovHs/BYFJS
x+mBMnUb6zgUq9Kz0BeOcvby/rNq0VcrPvdry7SXvNC0mjhVK6X8rxObVhNyXJNQsvmWzV1JteK+
fjvxYSWRYc0WHSXxs+NKIo+AeWnfo0ISdx2DXULzbhrPjShvNH+tUL7Br6K4ogbLqcXV/FeSy0NI
xqRq15ku5zzWig62GVqBY9t2QssTTwQwuY3whLK7P+j8mxOcStpQwlbNeStt9FEpNTqm85GTPJ24
Sg2ygBVa5FVApoBkAZmCrLjpbtmtfT2c21UfrUK8jlFahXBN9JFwLXrBnmtTzn9KHaKop/Z+6Mr9
klN7V1kIBYshVyZ/uLAgassdwpOLw3I6qPPBVW4/dlnE1NpKFrIHRlGtPJErTN2D4+3KzW+OQLyV
g+5sS8S3CbrT3bTfIOJOdwd8g3C7+xUmt8xp9Ged8F/tiP8WvpKvcMh/Sz/R1zvmv0XnV/NiF59u
XuLJd3nSi/FeyTe+sn/8AUj3/zTKRU73BxjgyuPdMzT3kHugd9wVZoxqRbnIk+AC5J8rvDxdpPBC
3NxQ/5wnPG8VeVD1Ym4jbm77J/inl4TxFvqovxXHSf5bndP58OFVYmP/MoaD876ah7oQhZU91y7e
4Wz/Ia2v5Nl+lO+zzQP2Sk8VLeUBLEwGkcF7FtD9hQLMcjbwZx3rf4ELXbAE4UL7tzf8397wf3vD
/1JvOPGoP2mc+EqWxWDL674rZWT417nRBc95gMK6guf9X94X/ujhzvAVp10EdOp27WVE8O397N/I
x67ca6RvN46PDj6ZTjZ2h7gXZql2aaA4HyKuC2yaoawOf6+yczm9vit7YN0u4kqXwJPrjrmBrfZK
XcfOQRbuZAeg1Z3KyzyYwn3Y4O5DSvpwgT5M5tt86a0XoLduHImQE6IlKrv1U2bShh1nXO4Ktbz1
umOUXe7AUzqrjGG0ulvcZTmgTY2NTOaP/cxno8zGVy1ycaWzeWepuvSZ8vOqe3ni2xb+o5ecYEHt
Jnbz7vWW6xoa8w529+VZovrozn4JFfQsXUIYa8mnunWP0IDHBbiCOzRqqdj1Ovp9UuYVMqtdIOW4
yikHaeV7nnJXvbc865BZwZlpffr0++Dz9cW+UKsvvRi5VfhF1S0IfWkVfdCpSo38SolU7XiNlTOn
ppI2NKpQn0XEh3n0yk00Qglq6VYRbTSYSaOlWTzydbnm03JoVPY0jmXZnNakrTQhw7QMoScHy9qz
WsW7o07dFMnQEg96V4mrBWPuP2l5jxxhDxpp2w6XVv5VPedeFMs6t0e98SoiwzFmf6owWCIUaK1g
y2rZL9jlBmusq8htU+/VK/6YTxZJOZQdPiF9V83nsjZcpDpferTjCGPKAVKJnF0wYaztsVnTDTcj
/eLGzyn8nPo8H2rL265L6C35VC8IC+vIkvl39VJ3WH76zPL+5BKWQnY1lYitmGG+pXLN20iLTKgt
Zx7Uoj7maugZw81KKltty5Wr1ixclJK2VZZwNn+9uEz53TJ/WtcZYnL5dtYikZpJXLuwzvfYdWLr
C/ivcXjYGI8rp5X9/dZ02krTjx8/rtfEjWNYD2tU7cygcvNoFZ0y/PrtQIX5tVSUn8mxRYDfqhm7
H7zD/OmdZvUt5czuOXKcFv97ZjjAH1k8S8/yppZ3M+AyuYvDuR3QpFe1MxDMRtblI3V2NUkWWxeT
ULznr5EmbZem/iXmuGLuX6XuIf2unPlXNbHKla/sgj7Gg8Y89Rk9Y0J9wACGMYxGCeUcakfjd0HG
tTmqKBvtoTx3Q74v3YYo7Vcv6t5Ju98+OOgeDD8c93+C9axfjKkQ4EG2UYaGmGYa/oObT1SRvzmb
RTOKNqLpIhrB8EdQf+xVZxiRCJJ2enMQj67tkS+OQ0gjf5ZexfmMbiZ4vk8CXFmDX3bXThJ/cRCm
mSqT13xFnWaKrowvnl/3zr3Ga6/qN0lPwWVFlPZqB16eWy/z8HQiVj1nRMzRgN90k45o2jJpOUiY
AypUmcvIdz6j++Q5CK8z+IUfprdoWG+jiHZdCqLR/rHKp4gL/xKtiEzV5QR0v8GaNWnsHvlxiLVS
SWvrfJ2AAopUahOw99rboksp8gT7Gm+z/LKm4lmC0TwjXtTlj2kT6GMvvENpEQMkTkC2ybXAZsWk
T3aHJO5xqWcuFtBHJ8EIKQhTvC2AhKz4HtZyM52fT8Osmp9Ddvcc55a4/qsEyEVjOfq4jTG/WyF5
EF2qIiRTrn/3heDTlR5KS8dNgb0nQifK0KpahKFlsF/ByJQEMHBRPqDoHpdTx5/QuYZXx+e/wTi+
rjnIjI98M/Ch+5iNFNGpihG6Z9bCiT5fativ5hlawjmiQFZ6Nk99Ijnw3CyuMD8PmZd/2nzcy3XE
7KFau9rOvWZaPkH5ycJpoJk/9ZXNYJBtioQ/NJAZcNlFQS89vCmoiFvrSs7XMl/kDbATxte0V5JC
w+CN6DeRCFduwsj7TNdj5xOfnAEkumSXBXYuBbWjDrrwmjgSdj3OjBoSu4beAMObY7BakzbavG2u
2a4Ig1+ZKntMemRJXXmFBr+1h9Wzle0SAHZRC1JO5y4B5YiRlLDWNja8n/CsBabM/XmOriLvKgwS
tKouvOmcUS4ep+CZXpJwhsdFkPs3vRM8a0GhcmkdIRmGbVTdOJ7e1F/Q+RKgJkz5Aix/UqeDJ9gg
41wp2ncWHiAPitW4uUbX/wa32vr5eYS679ma6CLnOIngNiwfPbtdE/6kn6uJRfn/X3FH2tu2kf2u
X8EGWVBqKdZ20mxX2aRQZDkWIh+V5WYDwxAYibKE6AopxTEM//d9x8xwhhxSVOzuFmhgkTNvhnO8
+6g7+9d6hWcibtAqHZ/1h9M66571BmcftPJSZrv0qdI6iXKnuV3FHUh6sODoNMTPo2anm9BQ+hYs
p9rC+VZp1jUbs5MulEoRLLqOQh1iVOxcYRpnloTwbmf0F9dJTTAJZFs5xGwpb0sh+0rWJWUKTXtn
HTizFKLTns2mqziEJ1UYWiiXzaSkqXrflqLpsuOn4o6JHtqikZb/kYsIGmuJvhyG4wCEQfqdtqpy
iWB0H9XOtfis5mIhbgc/r8KHk99bcrxb2aKQAC0ppds/aeodgKRFRPYenlk76QcmM825MDXAXLG5
YXyopGRgeuyvNmu05kVhcwZHjY4lnByegV4TrlRv2F3Y+RmdvSgEBiQccV6scICJubkK/Xi5XBOL
7JYDqq8NH1maYKZs2O7QWPIHaHCwLFqA2s7AlYWAlSGwJVjpWya/j/yMBSE30V3eCLyalAF9ucrA
N80LOwNP9C9uUucxsmdUTsV3pVE68HBwAIsUEV+HfGN21kCIkL+QEQp6SS4JkFBFiMuDrKExRBkN
RFa8mc8E3eHuQbxh/QMJ0rUUOzyf8Yq2rcegePtLFMs+qKkxtJk8ejhz7j3LASs4WI+Y9o+PZM5Y
58WNE5xzch8x5UcMZc65TyTaGQttJUHIU2A+ZsI/Ms4WJkRoIH5KX3ld8al4TT8YjQTBrKY71Cq8
JuNpFB7LDpekn6luZYWQL60/1X8IDDVBbDXwnxY2sbxDVnoJVZxRGtnVq26wShqYi/gbUEdDBZyj
NpMiZK3CTgnIhP65CTdhyfGw/eArdrAMmgKXN7hRE8UqF8L0hIjFlvctoqXuNWDL/iDkLsNqUQCS
jvVPamp++BWY/LiqWGcKLK2ieJaTKkIvJFCUNQIuR7ILPWGyTKaTLJOSbn5KmPkCmdxL2PxrI3gv
2rpUWuNcuaGSYrIfuwy1Cm8QeuDFPZm90VgVoRCoYDkYlCGl7XHUXJ9QqOhdvA7n/nATRXBY+9N5
eDIFgh/LLuEsAOI/uqBSTrHhV9v1dPfYagZ4HceDBYvEAwys2t/bI7ePWkUU3TZ7iXu0i7mO1jRj
s0tPRlxbmUgOY1yTYxOHqENVkVu4x7pmFfdSHqMr4SHQYWeiKr5LScnoXuSIZg3q7C8M1yISKcQb
+psrg0c3n8VD+CtpPUNHJHoMf3lU8UT8xj+vxU3QPyzOxUii1YCSz8Y+WrdSiCgDx7SBsQtWyn4u
vKmitFH3sHNx3m1+yrHoxsPlCpV4lBNX+CrMlyN6NAmA/AzoDc9VvIcVOA8jFE2naAGVA3TPPg7O
271W+7Tf6ba5KS6Ore1x5/1xpvFNMJ8HSZv3zZOTJr+RzsYN29HhJsAq46T7qN+BjeTMvlxKJlit
ZlPMGbx0MD3vHafhjJZzZz3Bakf4gT4/xKrO1AeZ62/TGDCBLHOE2iA9UNsj7hxQK3zamr2A5wkv
4ruV68RASZYDuhuHG5Wh/t6hq70GzDOTF1ucb3oxWW4ivBd6Azjk69H0W/XFqz3hu0xN59PFhit9
VQ1w/3CwYbcmu73SO8UKmaT6vNrrVjSVPaMIebe7WNUv9C8v4CD/Y+9g1FD/ANqmKXtyOp4coiYJ
xWZxEiym4zBe514OaIMppqmRvBqpvnbnEWmmP2/2+p1md9A667UH/fZFH91z5EP8TS46WEy+2+63
xZGOJX5sGNgycUmRrzR0xj1NzNxI/fbk70Zq/6tmO+HbsItHZa7jSUmHk50cTUo5mHCWGw5/bBi/
so78oseOzjbKXkOcTyOlbvekSvsI7nZLWaobhqbb+HjNYcyq1RZbXMJbTGu+/NKQan/PkT54mvbf
0xmFhv6D+7PX71l0whxLIzEwCPQsn+vKf6zgh0zUmeCh0KNPNzBI4pDwo40sd+pZeMxGHt+pr82h
CjNpZIO2PaVRB+S8wKi0BinuDdQOlHw6xFl/CVeU/SJ5psg4HnysDKr9EivGjogibOwc1yYv94Yg
FtI1sT8djxvW7BfXFQ3j5FQhx96lslYYsHQiwi5YU0o9crWN7u5I2v+nZLoMidZpZYPd+q9N+cuk
DybTo71MCOvTy8VoLwB+M56E4ZOLxigkEODWcoZ0F/E2/fnW2QMqpX42DO+dnMjAYTidadWbTFRI
Rc3UcL3lLfEGqc4pY+WvTlWKO2qSGpA+U+yL43a7P8DTQHqR5HN+1pq9dVpngMVb/QE3x4j0j53D
Phym/0jdpw5UfWKqW+d0kJy7VDjgeLZcRtX8ceB79JVLPknocpXOlWtg4uI7I6DsKIK8pvRxcA7q
1M3BW+HgMsEdHG2GzEs+v1ef8LD67j8TUiC2PUZxRn3fL2LRus13gBOOiZ1huMwAvduMx2guJBON
fUU9bSN/5iE8s5/f/3TeHnSALvfev6tV3kfBajIdxgeHTnwjZyOMOPIdCJfxDdpYFBY6hg+oGr9i
/0P7E4Jt987PukSXPSfV4q9m97Jtthm863Q7p+1mTw7BZhz61/943Om36QVGufTQjLTnOXviG7Vq
P9oTWeRHwjuC/SE5k/5wL4JFjBGEY2BC8YkPqKkDM90/QFWMbmv9COwGiY2olvCcqWS5yZgKCzUF
BlhtgXoToSVwKtlo7XrIBt/3UHGub5l6dYevokjuGz2nhXA+k52YbIym8RaQAX4bLxoszP7v8M+/
9mqaNbbQjmv033+1h5WaPOfgRT4EqT/ROx68/A2GfgX/YD/9xQFA++03/F8kZMtu8rtus/WhJl+q
jf4OPe/kVmsnu68C+djiK2IWthhrsebn/EYLgEcrfjUjVCTgTDMODoX9CypZyrw+KPToIcTJ7dbQ
JsDKnt6cRsmBtgVQ396a+JqVOyZ8PGw4LTuEyaQAgiqZVQjiOyb8gYP9i6N9bh3mpuTJA3vHO0zt
c5fpOJkUdYRTMoqCW0aCME8PxoezcufBgCBZTjxL5dLC7LLk28f7vrPRLRiNyLYmzs0D4vuhzhqg
vS09QBmTm35R+PrXtOcgai+/hHSA3wXxdCh+v/T3xjXVDldJ3CVY4QOPV/pAP25152XqZ4lR9o1R
7Djbfp1heNt99ky6VyuFKfDrhBX6mbJuOrQZjK4ennl8KP+ZHhp1fq+sgNBaBSBsNt7aA7n6k9JX
NdnP1wpD+1F4UzSHFy9R34EzAEZ4GaPaV1H8UlYKcdAGTPxWqLGvGOHaF4J5MNN+UHNVskINV6sk
odDUMcX+5BatMc47qscakuWxi/Rw4p+cHT8Bij1dYDWwYMSXeoMZP1HmpHpfVM8L0/LWx8EQxajj
/kmXnbbQPjy7De4oRy/CAgkfLXP1aAmf2GujlOpP1vMZCumYo2CNZcvXDLNOIGCwBbr9kMf0CtD3
NJ78HeYyaSoIYvRSe5N2B4vUi6vrJAxsd0tKTYukY4joepr1NzEuW9qrlMnzFjNMTR/BpWTH3MDR
c4QX+XY9vY3G9u0/cPHT6yGUpAIqXHxpUaFDKleopFZS136pQKw/Lzu99qErtaA9AowicaEOVVwS
XYNKT9KdhOUJbvM5bFMYre+qLuLcAF1kfHFnfLwzrlKd4SMYyZKeAwbXblaiuuVJq+hxu+kiCbRK
lk6+Wa5W4egiGIcY259oj+ZMbq1aX7XjbBQQ1iVnjRpkcq1El3G40eHIdzW22E2FMQglqWrsxJsh
PkOl0x0jIXR/BnH01lkFnCfBKBCIpoTJZh7Iw++7uyt7/7+K3TxNc57WXKhpH6c519uU056T+RsA
Xal4kqyGeKtyNolkzlPSFiloc5SzSXh3sZK2vAJ7ZyX2TopsERD4dQiLuU1nXBh9X0qHnO7cB1Yn
CoA3o2DNAqR+LQ+8RaHa2K5P/QGd6hPEz6ruJzy9xWYInF1Uv50A01VfLuqfZ8HwizbFEppV+hJl
LG5k7dB5BhjNcnpmVkYVIBgsMlOE2RCXsQ2VfNSBKfSdyzgktkmmvxFWVGxqWEcDQId38TT2Zbwp
4x68sIAegJ4Afl5GgN6ZjhVNmSnEMZCYhkbWinoIZvaCZbcCLtZMNzBbo/NPQzo0FTdXzkPUJeVM
VNTV2K4dd88spqmt4ko9KdH9KG1BSQAZnFhuSvM/pBHGOh5qj+D1tiwQaE8pGrg4M/gfJXN8F81Q
nEx5VM+Z19GCwHeeh/ux2ekjzT3CoIjOKfAox5cnzdNB8xwI8l/NLjF+sJrtZq91LGmv4vrS13T3
8ZFBETExdGcF/8ZVUj6HDos+LONQDJ1gVqT91mCOOHOshm9TbhMUcyoRhf32O63JEqRip5eqmkIO
FijeJQ7awU0wpSrKKiOSNd+WGP92up4A6kCEEMKYwLKxOh9nCbORRkiFfiTVjyilieGtlXgdRXoU
4BWnKWKlhCeSE/mYnojTEuGLWeKJEGWyu2QSAWiKCC+dPMgQskpmNsiVXtIR/iStNEzJsxrVWL58
SAztGguVtygio5MIU/KcUotkJolIL1j+IqU+o+yCpRMRpR2vzdb5OYgif3sWor91g2yfJZF/I1F0
W5Bh1f06/HXvoK7J+5R1UPXRwz8yCBv/U+lVtfFk2qCdBpSdLCMaB3ARfgcxMFzFxfLdldtZxCs8
muu0oFcnlCcYDbj5Giq7cs9W4UJXBPl6nlyX+G+COJtSbSWxsiw6UtK7ZYS0SlZjWhIfFLAfKHFt
6IAGCIyxrwm9pyM6/wbk6293jO98pykzuiXZFmKFrjkVH3xKJWM7NzUDGes5v05s53pvpQ5geeBd
EIesSug2cbEHYpXW39duTWLPfLZLDcEe71uBX54aA/ijEAXFEl7xpFfdzOdBdJerDJHC+UA05DEq
WjcfKcd5JBN4RFX3sn9U/93FIIPVrcR1q1tf6EyrLsawBZv1sm4ozxiiCPPSmj/rsKT//L6MrC/j
4HQAgkihyUEXZ21Nzz5gOyHi2hpIkQyBKUHM1vCIK6sB6VRC7fN7JeraegiBmG3pmhD+/F6Xhq09
SS5W0jD1MURj+5doKkVnPAtuxBqlRFRb33PAwnUV6av0gApIjsBqA8VpJ52R5gP1/D7jBfXgoIEF
TqjCmCsRdyy57loWNrBaSv9ExiKpLpJlPAAtLGBgDJsXoXLwF3NxqHW1nMWmLEVHmzQhrc7ze6nX
sS8zR4GTx+xQ00rgUid6CesqS30Ee9pGqHFQOShNLUX+ynItPsUC6pDs2gsJitTk+GUUqIO+E0nQ
DiUwV+/Qlq8H9CSGQn0673HFboNoQXchxMbI3sRi/dQoD6/FS2B0NvOF8R5HkvN7yGx3dr9cEU/A
u295v+87RMeQUOVZOHxLvwNkxJHCRVg2EKjZXThDFabUjEbx2tLt2QsfiyEGIKNGgrWG3biNlosb
z0GLG7yK5xiGH6hIYRl2T8H6SchoTFms43DtDDFiGPEMOugju+8+v5fx54NWt3lxMThtnrQfqKom
0uE6ignsu02h/L7l7Lz0ZdVG3fyQnuxCnycOP8LBW72z84FtBs6/iZ/Ahm9dEHjQpk5jAMqa4kLS
a1XckWdqmZ1to5ESxZYdfta6+Iuy6CQqiCJCkbJRG5qO/H7ilFFglCMGtCgwcgG4lA4KQ+xA1BsB
Ivm8DDCFmGkMSI+qSq5IZQXjfYEghZwuxvzVNmtLb1PtkepcVOwy79Ynk7SUJ9Dne2QoQFIj50y9
jnHidTwwq/wBxkWAHypF5Sztg4sCZXZhWhAYnBMtqPKV3bKkxZW4ixYhZyJqBoVqHG06wBBKq7b7
5s0bB0UH5wAI8wI4wzdvXPU2n59Kmph8VGIrMNkmaQz41coiKXPANo5Iem8XMkDJ3AzGx87yvCYR
1MLhJJ1yWRx9oHKk/3VieShB6V8Lq4NJ2IvoubZzGWSYcULQdrkIGeb305FhDiLM75yDCBFKvsCk
A7hgMYLmq8koVncL3a083s3UrAJgDCBDIwjQtNqlnNjZCiy/kAIe8z/wkW7exMllvouRyi5iLOtf
Qd4UkmyuqUGNLMzjsW4eX88DXxP8fJWlY8eVLwc9fYdgHOu1MrJ3lAGcuWyul2MO3Bk0HwVcSgzB
LzgXu4DD4yFBppUdPwyZDE9u/kEoB4YC6GipXC9lct557QTJwTl9+dHVV8jcNYKNdoakEynXM2za
ux83NtmrVCt5tvwaJ1zQg6nfJhlGD6fBbHkDiztZ3n5kUex0uZ6Op0MG5jYTtYyDahqk/prmTciU
4ShL/Fn0EeKUxrgKVp5mAuxQMq8HUhfcCLY/Rj7fSCyoz7WzGC+feqIYWx/KViy3rQKiwVp2DqLa
PLfKfwFHpLqyhQ8CAA==
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
AAA=
''')
def step4 = new EmbeddedWorkflowScript(name: '04_restore_approved_grid.groovy', payload: '''
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
AA==
''')
def step5 = new EmbeddedWorkflowScript(name: '05_finalize_orientation_review.groovy', payload: '''
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
n5P6c/ZXL9RDwX8BRT3AAfYoAAA=
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
def projectPathString = { value ->
    if (value == null) return ''
    if (value instanceof Map && value.strings instanceof List &&
            value.values instanceof List) {
        List strings = value.strings as List
        List values = value.values as List
        StringBuilder restored = new StringBuilder()
        if (!strings.isEmpty()) restored.append(strings[0]?.toString() ?: '')
        values.eachWithIndex { item, index ->
            restored.append(item?.toString() ?: '')
            if (index + 1 < strings.size())
                restored.append(strings[index + 1]?.toString() ?: '')
        }
        return restored.toString()
    }
    return value.toString()
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
    Map correctionBridge = configFile.isFile() && !reportOnly ?
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
    // A gate is open only while the run is actually blocked on this reviewer.
    // Everything the gate bar claims is recomputed here, so a stale tab left
    // open from an earlier run shows no button at all.
    String gateEndpointUrl = correctionBridge.gateEndpoint?.toString() ?: ''
    // Publish the loopback endpoints so a headless controller can answer a gate and save angle
    // corrections without parsing REPORT.html for them.
    System.setProperty('corealign.gate.endpoint', gateEndpointUrl)
    System.setProperty('corealign.save.endpoint', correctionAutoSaveUrl)
    String openGateId = System.getProperty('corealign.dashboard.gate', '').trim()
    if (gateEndpointUrl.isEmpty()) openGateId = ''
    int gatePresentCount = 0
    int gateMissingCount = 0
    try {
        gatePresentCount = Integer.parseInt(
            System.getProperty('corealign.dashboard.gatePresent', '0'))
        gateMissingCount = Integer.parseInt(
            System.getProperty('corealign.dashboard.gateMissing', '0'))
    } catch (Throwable ignored) {}
    String gateTitle = ''
    String gateSummary = ''
    String gateButtonLabel = ''
    String gatePanel = ''
    if (openGateId == 'grid') {
        gateTitle = 'Check the detected cores'
        gateSummary = "${gatePresentCount} cores found, ${gateMissingCount} positions empty. " +
            'Missed a core? Draw an ellipse over it in QuPath and name it "TMA correction".'
        gateButtonLabel = 'Grid is correct'
        gatePanel = 'grid'
    } else if (openGateId == 'orientation') {
        gateTitle = 'Check the rotated cores'
        gateSummary = "${okCountForPage} passed automatic QC, ${reviewCountForPage} need a look. " +
            'Use Edit to change an angle. Approving creates the result files.'
        gateButtonLabel = 'Approve and finish'
        gatePanel = 'orientation'
    }
    boolean gateOpen = !openGateId.isEmpty() && !gateTitle.isEmpty()

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
        gridReviewPending || hasGridQc && !hasOrientation ?
            'Check every detected circle and missing position, then use the button at the top of this page.' :
        hasOrientation ?
            'Check the flagged cores below. Confirm correct cores and edit only the wrong angles.' :
            'Open the slide in QuPath and run CoreAlign.groovy. No configuration is required.'

    StringBuilder html = new StringBuilder(64000)
    html.append('''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><meta name="theme-color" content="#ffffff"><title>CoreAlign | Report</title><script>try{var savedTheme=localStorage.getItem("corealign-theme");if(savedTheme){document.documentElement.dataset.theme=savedTheme;}}catch(e){}</script><style>
:root{color-scheme:light;--bg:#ffffff;--surface:#f8f9fa;--surface-strong:#eef2f7;--fg:#202124;--muted:#5f6368;--border:#dadce0;--accent:#4285f4;--accent-deep:#1967d2;--accent-ink:#ffffff;--warning:#fbbc04;--warning-text:#8a5a00;--warning-bg:#fff8d8;--danger:#ff6b62;--danger-text:#c5221f;--danger-bg:#fff0ef;--success:#5bcb75;--success-text:#137333;--success-bg:#ecf9ef;--purple:#4285f4;--purple-bg:#e8f0fe;--cyan:#4285f4;--cyan-bg:#e8f0fe;--coral:#ff6b62;--coral-bg:#fff0ef;--yellow:#fbbc04;--yellow-bg:#fff8d8;--shadow:0 1px 2px rgb(60 64 67/.12);--radius-sm:6px;--radius-md:8px;--radius-lg:10px;--header:72px}
html[data-theme="dark"]{color-scheme:dark;--bg:#171717;--surface:#202020;--surface-strong:#2c2c2c;--fg:#f7f7f5;--muted:#c6c6c3;--border:#5a5a5a;--accent:#4c8dff;--accent-deep:#75a7ff;--accent-ink:#111827;--warning:#ffd400;--warning-text:#ffe45e;--warning-bg:#423500;--danger:#ff5b57;--danger-text:#ff8580;--danger-bg:#481817;--success:#32d66b;--success-text:#63eb91;--success-bg:#123a22;--purple:#a970ff;--purple-bg:#2d1752;--cyan:#20c5ff;--cyan-bg:#10384a;--coral:#ff5b57;--coral-bg:#481817;--yellow:#ffd400;--yellow-bg:#423500;--shadow:0 1px 2px rgb(0 0 0/.55)}
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--bg);color:var(--fg);font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;font-size:16px;line-height:1.55}.icon{width:20px;height:20px;flex:0 0 auto}.skip{position:fixed;left:16px;top:-80px;z-index:100;padding:12px 16px;border-radius:8px;background:var(--fg);color:var(--bg);font-weight:700}.skip:focus{top:12px}.topbar{position:sticky;top:0;z-index:40;min-height:var(--header);display:flex;align-items:center;gap:24px;padding:12px clamp(16px,4vw,48px);border-bottom:1px solid var(--border);background:color-mix(in oklch,var(--bg) 92%,transparent);backdrop-filter:blur(18px)}.brand{display:flex;align-items:center;gap:10px;min-width:max-content;font-size:17px;font-weight:750;letter-spacing:-.02em}.brand-mark{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;background:var(--fg);color:var(--bg)}.brand-mark .icon{width:19px;height:19px}.nav{display:flex;gap:4px;overflow-x:auto;scrollbar-width:none}.nav::-webkit-scrollbar{display:none}.crumb-separator{display:grid;place-items:center;color:var(--border)}.crumb-separator .icon{width:15px;height:15px}.nav button,.icon-button,.help-button{min-height:44px;border:1px solid transparent;border-radius:999px;background:transparent;color:var(--muted);font:inherit;font-size:14px;font-weight:650;cursor:pointer}.nav button{padding:8px 14px;white-space:nowrap}.nav button[aria-current="page"]{background:var(--surface-strong);color:var(--fg)}.header-actions{display:flex;align-items:center;gap:8px;margin-left:auto}.help-button{display:inline-flex;align-items:center;gap:7px;padding:8px 12px;border-color:var(--border);color:var(--fg)}.help-button[aria-current="page"]{background:var(--surface-strong)}.icon-button{display:grid;place-items:center;width:44px;padding:0;border-color:var(--border);color:var(--fg)}.moon-icon{display:none}html[data-theme="dark"] .sun-icon{display:none}html[data-theme="dark"] .moon-icon{display:block}.shell{width:min(100%,1280px);margin:auto;padding:clamp(24px,5vw,64px) clamp(16px,4vw,48px) 96px}.panel{display:none;animation:panel-in .22s ease-out}.panel.is-active{display:block}.hero{position:relative;overflow:hidden;display:grid;gap:32px;grid-template-columns:minmax(0,1.5fr) minmax(260px,.75fr);padding:clamp(28px,5vw,56px);border:1px solid var(--border);border-radius:24px;background:linear-gradient(135deg,var(--surface) 0%,var(--bg) 62%);box-shadow:var(--shadow)}.hero:after{content:"";position:absolute;right:-80px;top:-120px;width:320px;height:320px;border-radius:50%;background:radial-gradient(circle,var(--accent) 0%,transparent 68%);opacity:.14;pointer-events:none}.eyebrow,.status-badge{display:inline-flex;align-items:center;gap:8px;width:max-content;font-size:12px;font-weight:750;letter-spacing:.07em;text-transform:uppercase}.status-badge{padding:7px 11px;border-radius:999px}.status-badge.success{background:var(--success-bg);color:var(--success)}.status-badge.warning{background:var(--warning-bg);color:var(--warning)}.status-badge.neutral{background:var(--surface-strong);color:var(--muted)}.status-badge .icon{width:16px;height:16px}h1,h2,h3{margin:0;letter-spacing:-.025em;line-height:1.16}h1{margin-top:16px;font-size:clamp(2rem,1.35rem + 3vw,3.55rem);max-width:16ch}h2{font-size:clamp(1.55rem,1.3rem + 1vw,2.15rem)}h3{font-size:1.1rem}.lede{max-width:62ch;margin:16px 0 0;color:var(--muted);font-size:clamp(1rem,.96rem + .25vw,1.12rem)}.hero-side{position:relative;z-index:1;align-self:end;padding:24px;border-radius:16px;background:var(--fg);color:var(--bg)}.hero-side .eyebrow{color:var(--accent)}.hero-side p{margin:10px 0 20px;line-height:1.5}.primary,.secondary,.text-link,.control-button{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:44px;border-radius:999px;padding:10px 18px;font:inherit;font-size:14px;font-weight:750;text-decoration:none;cursor:pointer}.primary{border:1px solid var(--accent);background:var(--accent);color:var(--accent-ink)}.secondary,.control-button{border:1px solid var(--border);background:var(--bg);color:var(--fg)}.text-link{min-height:auto;padding:0;border:0;background:transparent;color:var(--fg)}.primary .icon,.secondary .icon,.text-link .icon,.control-button .icon{width:18px;height:18px}.section-head{display:flex;align-items:end;justify-content:space-between;gap:24px;margin:0 0 24px}.section-head p{max-width:65ch;margin:8px 0 0;color:var(--muted)}.section-block{margin-top:48px}.metric-grid,.file-grid,.grid-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,170px),1fr));gap:16px}.metric,.file-card,.notice{border:1px solid var(--border);border-radius:var(--radius-md);background:var(--bg)}.metric{padding:20px}.metric strong{display:block;font-size:clamp(1.65rem,1.4rem + 1vw,2.25rem);line-height:1.1}.metric span{display:block;margin-top:6px;color:var(--muted);font-size:14px}.file-card{display:flex;flex-direction:column;padding:24px}.step-number{display:grid;place-items:center;width:36px;height:36px;margin-bottom:20px;border-radius:10px;background:var(--surface-strong);color:var(--fg);font-weight:800}.file-card p{margin:8px 0 20px;color:var(--muted)}.file-card a{margin-top:auto;align-self:flex-start}.output-mode-control{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:10px 20px;padding:20px;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--surface)}.output-mode-copy{display:flex;flex-direction:column;gap:3px}.output-mode-copy span,.output-mode-status{color:var(--muted);font-size:14px}.mode-toggle{display:grid;grid-template-columns:1fr 1fr;min-width:260px;padding:4px;border:1px solid var(--border);border-radius:999px;background:var(--bg)}.mode-toggle button{min-height:40px;padding:8px 14px;border:0;border-radius:999px;background:transparent;color:var(--muted);font:inherit;font-size:14px;font-weight:750;cursor:pointer}.mode-toggle button[aria-pressed="true"]{background:var(--fg);color:var(--bg)}.mode-toggle button:disabled{cursor:wait;opacity:.65}.output-mode-status{grid-column:1/-1;margin:0}.notice{display:flex;gap:14px;padding:18px 20px}.notice .icon{color:var(--accent-deep);margin-top:2px}.notice p{margin:0;color:var(--muted)}.legend{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 16px}.legend span{display:inline-flex;align-items:center;gap:8px;padding:7px 11px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--muted);font-size:13px;font-weight:700}.legend i{width:10px;height:10px;border-radius:50%}.legend .auto i{background:rgb(0,235,230)}.legend .corrected i{background:rgb(80,240,125)}.legend .missing i{background:rgb(255,70,80)}.qc-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:12px}.zoom-controls{display:flex;align-items:center;gap:7px}.zoom-controls .control-button{min-width:44px;padding:8px 12px}.zoom-value{min-width:52px;text-align:center;color:var(--muted);font-size:13px;font-weight:750}.image-viewport{overflow:auto;max-height:72vh;border:1px solid var(--border);border-radius:var(--radius-lg);background:oklch(.12 0 0);cursor:grab;touch-action:pan-x pan-y}.image-viewport.is-panning{cursor:grabbing;user-select:none}.image-viewport img{display:block;width:100%;height:auto;max-width:none}.media-card{overflow:hidden;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--surface)}.media-card img{display:block;width:100%;height:auto;max-height:720px;object-fit:contain;background:oklch(.12 0 0)}.media-caption{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 20px}.media-caption p{margin:0;color:var(--muted)}.filter-tools{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.filter-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.filterbar{display:flex;align-items:center;gap:8px;overflow-x:auto;padding-bottom:4px}.filterbar button,.preview-toggle button{min-height:44px;padding:8px 15px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--muted);font:inherit;font-weight:700;cursor:pointer;white-space:nowrap}.filterbar button[data-filter="ok"]{border-color:var(--success);background:var(--success-bg);color:var(--success)}.filterbar button[data-filter="missing"]{border-color:var(--danger);background:var(--danger-bg);color:var(--danger)}.filterbar button[data-filter="review"]{border-color:var(--warning);background:var(--warning-bg);color:var(--warning)}.filterbar button[data-filter="changes"]{border-color:var(--purple);background:var(--purple-bg);color:var(--purple)}.filterbar button[aria-pressed="true"]{border-color:var(--fg);background:var(--fg);color:var(--bg)}.filterbar button[data-filter="ok"][aria-pressed="true"]{border-color:var(--success);background:var(--success);color:var(--bg)}.filterbar button[data-filter="missing"][aria-pressed="true"]{border-color:var(--danger);background:var(--danger);color:white}.filterbar button[data-filter="review"][aria-pressed="true"]{border-color:var(--warning);background:var(--warning);color:var(--accent-ink)}.filterbar button[data-filter="changes"][aria-pressed="true"]{border-color:var(--purple);background:var(--purple);color:white}.preview-toggle button[aria-pressed="true"]{border-color:var(--fg);background:var(--fg);color:var(--bg)}.confirm-all-pass{border-color:var(--success);background:var(--success);color:var(--bg)}.confirm-all-pass:disabled{cursor:default;opacity:.6}.core-search{min-height:44px;width:min(100%,250px);padding:9px 14px;border:1px solid var(--border);border-radius:999px;background:var(--bg);color:var(--fg);font:inherit}.core-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,230px),1fr));gap:16px;margin-top:24px}.core-card{overflow:hidden;border:1px solid var(--border);border-top:4px solid var(--success);border-radius:var(--radius-md);background:var(--bg);box-shadow:var(--shadow)}.core-card[data-status="review"],.core-card[data-status="uncertain"]{border-top-color:var(--warning)}.core-card[data-status="missing"],.core-card[data-status="no_tissue"],.core-card[data-status="processing_error"],.core-card[data-status="export_error"]{border-top-color:var(--danger)}.core-card[data-confirmed="true"]{border-top-color:var(--success)}.core-card[data-has-change="true"]{border-top-color:var(--purple)}.core-card[hidden]{display:none}.core-image{display:block;aspect-ratio:1/1;background:oklch(.12 0 0)}.core-image img{display:block;width:100%;height:100%;object-fit:contain}.core-image img[hidden]{display:none}.core-placeholder{display:grid;place-items:center;width:100%;height:100%;color:oklch(.72 0 0)}.core-placeholder .icon{width:36px;height:36px}.core-body{padding:16px}.core-title{display:flex;align-items:center;justify-content:space-between;gap:8px}.core-title strong{font-size:16px}.core-status{font-size:11px;font-weight:800;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}.core-card[data-confirmed="true"] .core-status{color:var(--success)}.core-card[data-has-change="true"] .core-status{color:var(--purple)}.preview-toggle{display:flex;gap:6px;margin:12px 0 0}.preview-toggle button{min-height:34px;padding:5px 10px;font-size:12px}.core-meta{margin:10px 0 0;color:var(--muted);font-size:13px;line-height:1.55}.help-list{display:grid;gap:16px;counter-reset:help}.help-item{position:relative;padding:24px 24px 24px 72px;border-left:2px solid var(--border)}.help-item:before{counter-increment:help;content:counter(help);position:absolute;left:20px;top:22px;display:grid;place-items:center;width:32px;height:32px;border-radius:50%;background:var(--fg);color:var(--bg);font-weight:800}.help-item p{margin:8px 0 0;max-width:68ch;color:var(--muted)}.footer{margin-top:64px;padding-top:24px;border-top:1px solid var(--border);color:var(--muted);font-size:13px}.path{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;word-break:break-all}.empty{padding:32px;border:1px dashed var(--border);border-radius:var(--radius-md);color:var(--muted);text-align:center}.empty .icon{width:32px;height:32px;margin-bottom:8px}.saved-modal{width:min(calc(100% - 32px),420px);padding:0;border:1px solid var(--border);border-radius:20px;background:var(--bg);color:var(--fg);box-shadow:0 24px 80px rgb(0 0 0/.28)}.saved-modal::backdrop{background:rgb(5 15 12/.46);backdrop-filter:blur(3px)}.saved-modal-body{padding:28px;text-align:center}.saved-modal-mark{display:grid;place-items:center;width:52px;height:52px;margin:0 auto 16px;border-radius:50%;background:var(--success-bg);color:var(--success)}.saved-modal p{margin:10px 0 22px;color:var(--muted)}.nav button:hover,.help-button:hover,.icon-button:hover,.secondary:hover,.control-button:hover,.filterbar button:hover,.preview-toggle button:hover{background:var(--surface-strong);color:var(--fg)}.primary:hover{filter:brightness(.96);transform:translateY(-1px)}button:active,a:active{transform:translateY(1px)}button:focus-visible,a:focus-visible,input:focus-visible{outline:3px solid color-mix(in oklch,var(--accent-deep) 70%,white);outline-offset:3px}.panel,.core-card,.primary{transition:opacity .2s ease,transform .2s ease,background-color .2s ease,border-color .2s ease}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@keyframes panel-in{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
.core-image>a{display:block;width:100%;height:100%}.core-image>a[hidden]{display:none}
.image-viewport{height:min(72vh,820px);max-height:none}.image-viewport img{width:auto;margin:0 auto}
.search-control{position:relative;width:min(100%,380px)}.search-control .core-search{width:100%;min-height:48px;padding:10px 72px 10px 16px;font-size:16px}.search-clear{position:absolute;right:6px;top:50%;min-height:36px;padding:5px 12px;border:0;border-radius:999px;background:var(--surface-strong);color:var(--fg);font:inherit;font-size:13px;font-weight:750;cursor:pointer;transform:translateY(-50%)}.search-clear[hidden]{display:none}.core-image{overflow:hidden;cursor:default}.core-image>a{min-width:100%;min-height:100%}.core-image img{transition:transform .15s ease}.card-actions,.card-badges,.card-action-buttons{display:flex;align-items:center;gap:8px}.card-actions{justify-content:space-between;gap:10px;margin-top:12px}.card-action-buttons{justify-content:flex-end}.edit-button,.confirm-button{min-height:38px;padding:6px 14px}.confirm-button{border-color:var(--accent);background:var(--accent);color:var(--accent-ink)}.change-badge,.confirmed-badge{display:none;padding:5px 9px;border-radius:999px;font-size:11px;font-weight:800;letter-spacing:.05em;text-transform:uppercase}.change-badge{background:var(--purple-bg);color:var(--purple)}.confirmed-badge{background:var(--success-bg);color:var(--success)}.core-card[data-has-change="true"]{border-color:var(--purple)}.core-card[data-has-change="true"] .change-badge,.core-card[data-confirmed="true"] .confirmed-badge{display:inline-flex}.core-card[data-confirmed="true"] .confirm-button{border-color:var(--border);background:var(--surface-strong);color:var(--muted)}.edit-panel{display:none;gap:10px;margin-top:14px;padding-top:14px;border-top:1px solid var(--border)}.core-card.is-editing .edit-panel{display:grid}.core-card.is-editing .card-action-buttons{display:none}.rotation-editor label{display:flex;align-items:center;justify-content:space-between;gap:10px;font-size:13px;font-weight:750}.rotation-editor input{width:100%;accent-color:var(--purple)}.edit-actions{display:flex;justify-content:flex-end;gap:7px;flex-wrap:wrap}.edit-actions .control-button{min-height:38px;padding:6px 13px}.edit-actions [data-edit-confirm]{border-color:var(--purple);background:var(--purple);color:white}.change-bar{position:sticky;z-index:20;top:calc(var(--header) + 12px);display:flex;align-items:center;justify-content:space-between;gap:16px;margin:16px 0 24px;padding:14px 16px;width:100%;border:1px solid var(--success);border-radius:16px;background:var(--bg);box-shadow:0 12px 32px rgb(13 34 29/.14)}.change-bar[hidden],.change-actions button[hidden]{display:none}.change-bar[data-state="action"]{border-color:var(--warning);background:var(--warning-bg)}.change-bar[data-state="error"]{border-color:var(--danger);background:var(--danger-bg)}.change-copy{display:grid;gap:2px}.change-bar strong{font-size:15px}.change-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.autosave-status{color:var(--success-text);font-size:13px;font-weight:750}.autosave-status[data-state="saving"]{color:var(--muted)}.autosave-status[data-state="action"]{color:var(--warning-text)}.autosave-status[data-state="error"]{color:var(--danger-text)}
body{background-image:radial-gradient(circle at 5% 12%,var(--yellow-bg) 0,transparent 18rem),radial-gradient(circle at 95% 74%,var(--cyan-bg) 0,transparent 22rem);background-attachment:fixed}.topbar{border-bottom-color:color-mix(in srgb,var(--accent) 20%,var(--border));box-shadow:0 5px 22px rgb(48 67 154/.06)}.brand-mark{background:var(--accent);color:white;box-shadow:0 8px 20px color-mix(in srgb,var(--accent) 30%,transparent)}.crumb-separator{color:color-mix(in srgb,var(--accent) 46%,var(--border))}.nav button[aria-current="page"]{background:var(--accent);color:white}.help-button{background:var(--surface)}.hero{border-radius:28px;background:linear-gradient(125deg,var(--surface) 0%,var(--cyan-bg) 58%,var(--yellow-bg) 100%);box-shadow:var(--shadow)}.hero:after{background:radial-gradient(circle,var(--coral) 0%,transparent 68%);opacity:.18}.hero-side{background:linear-gradient(145deg,#253fd2,var(--accent) 55%,var(--cyan));color:white;box-shadow:0 18px 45px color-mix(in srgb,var(--accent) 26%,transparent)}.hero-side .eyebrow{color:var(--yellow)}.primary{box-shadow:0 8px 20px color-mix(in srgb,var(--accent) 23%,transparent)}.metric{position:relative;overflow:hidden;border-top:4px solid var(--accent);background:var(--bg);box-shadow:var(--shadow)}.metric:nth-child(4n+2){border-top-color:var(--success)}.metric:nth-child(4n+3){border-top-color:var(--warning)}.metric:nth-child(4n+4){border-top-color:var(--coral)}.file-card{border-top:4px solid var(--accent);background:var(--bg);box-shadow:var(--shadow)}.file-card:nth-child(4n+2){border-top-color:var(--success)}.file-card:nth-child(4n+3){border-top-color:var(--warning)}.file-card:nth-child(4n+4){border-top-color:var(--purple)}.step-number{background:var(--accent);color:white}.output-mode-control{border-color:color-mix(in srgb,var(--accent) 35%,var(--border));background:linear-gradient(115deg,var(--surface),var(--cyan-bg))}.mode-toggle button[aria-pressed="true"]{background:var(--accent);color:white;box-shadow:0 5px 14px color-mix(in srgb,var(--accent) 28%,transparent)}.notice{background:var(--cyan-bg)}.media-card,.image-viewport{box-shadow:var(--shadow)}.core-card{background:var(--bg)}.core-body{background:linear-gradient(180deg,var(--bg),var(--surface))}.help-item{border:1px solid var(--border);border-left:5px solid var(--accent);border-radius:var(--radius-md);background:var(--bg);box-shadow:var(--shadow)}.help-item:nth-child(2){border-left-color:var(--coral)}.help-item:nth-child(3){border-left-color:var(--success)}.help-item:nth-child(4){border-left-color:var(--purple)}.help-item:before{background:var(--accent);color:white}.help-item:nth-child(2):before{background:var(--coral)}.help-item:nth-child(3):before{background:var(--success)}.help-item:nth-child(4):before{background:var(--purple)}.saved-modal{background:var(--bg)}.change-bar{border-color:var(--purple);background:color-mix(in srgb,var(--bg) 94%,transparent);backdrop-filter:blur(18px)}
/* Bright editorial report */
body{background:var(--bg);background-image:none}.topbar{position:sticky;border-bottom-color:var(--border);background:var(--bg);box-shadow:none;backdrop-filter:none}.topbar:after{content:"";position:absolute;right:0;bottom:-1px;left:0;height:3px;background:linear-gradient(90deg,var(--accent) 0 25%,var(--yellow) 25% 50%,var(--danger) 50% 75%,var(--success) 75% 100%)}.brand-mark{border:1px solid var(--fg);border-radius:6px;background:var(--accent);color:white;box-shadow:none}.crumb-separator{color:var(--border)}.nav button,.icon-button,.help-button{border-radius:6px}.nav button[aria-current="page"]{background:var(--fg);color:var(--bg)}.help-button{background:var(--bg)}.shell{width:min(100%,1320px)}.hero{padding:clamp(28px,4vw,48px);border-color:var(--border);border-top:6px solid var(--accent);border-radius:8px;background:var(--bg);box-shadow:none}.hero:after{display:none}.hero-side{border:1px solid var(--warning);border-radius:6px;background:var(--warning-bg);color:var(--fg);box-shadow:none}.hero-side .eyebrow{color:var(--warning)}.primary,.secondary,.text-link,.control-button{border-radius:6px}.primary{box-shadow:none}.section-block{margin-top:42px}.metric-grid,.file-grid,.grid-summary{gap:10px}.metric,.file-card,.notice{border-radius:8px}.metric{border-top:5px solid var(--accent);background:var(--bg);box-shadow:none}.metric:nth-child(4n+2){border-top-color:var(--yellow)}.metric:nth-child(4n+3){border-top-color:var(--danger)}.metric:nth-child(4n+4){border-top-color:var(--success)}.file-card{border-top:5px solid var(--accent);background:var(--bg);box-shadow:none}.file-card:nth-child(4n+2){border-top-color:var(--yellow)}.file-card:nth-child(4n+3){border-top-color:var(--danger)}.file-card:nth-child(4n+4){border-top-color:var(--success)}.step-number{border-radius:5px;background:var(--accent);color:white}.output-mode-control{border-color:var(--border);border-left:5px solid var(--success);border-radius:8px;background:var(--bg)}.mode-toggle{border-radius:6px;background:var(--surface)}.mode-toggle button{border-radius:4px}.mode-toggle button[aria-pressed="true"]{background:var(--fg);color:var(--bg);box-shadow:none}.notice{border-left:5px solid var(--accent);background:var(--cyan-bg)}.legend span,.filterbar button,.preview-toggle button,.core-search,.search-clear{border-radius:6px}.image-viewport,.media-card{border-radius:8px;box-shadow:none}.core-card{border-radius:8px;background:var(--bg);box-shadow:none}.core-body{background:var(--bg)}.change-badge,.confirmed-badge{border-radius:4px}.edit-actions .control-button,.edit-actions [data-edit-confirm]{border-radius:5px}.change-bar{border-radius:8px;background:var(--bg);box-shadow:0 3px 12px rgb(15 15 15/.12);backdrop-filter:none}.help-list{gap:10px}.help-item{border:1px solid var(--border);border-left:5px solid var(--accent);border-radius:8px;background:var(--bg);box-shadow:none}.help-item:nth-child(2){border-left-color:var(--yellow)}.help-item:nth-child(3){border-left-color:var(--danger)}.help-item:nth-child(4){border-left-color:var(--success)}.help-item:before{border-radius:5px;background:var(--accent);color:white}.help-item:nth-child(2):before{background:var(--yellow);color:#191919}.help-item:nth-child(3):before{background:var(--danger)}.help-item:nth-child(4):before{background:var(--success)}.saved-modal{border-radius:8px;background:var(--bg)}.saved-modal::backdrop{background:rgb(15 15 15/.48);backdrop-filter:none}.saved-modal-mark{border-radius:6px}
.metric:nth-child(4n+2),.file-card:nth-child(4n+2){border-top-color:var(--success)}.metric:nth-child(4n+3),.file-card:nth-child(4n+3){border-top-color:var(--warning)}.metric:nth-child(4n+4),.file-card:nth-child(4n+4){border-top-color:var(--danger)}
.action-modal-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:22px}.action-modal-actions button{width:100%}.preview-toggle{display:grid;grid-template-columns:1fr 1fr;gap:0;width:min(100%,180px);margin:12px auto 0;padding:3px;border:1px solid var(--border);border-radius:999px;background:var(--surface)}.preview-toggle button{min-height:34px;padding:5px 12px;border:0;border-radius:999px;background:transparent;color:var(--muted);font-size:12px}.preview-toggle button[aria-pressed="true"]{background:var(--fg);color:var(--bg)}.edit-actions{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px;width:100%}.edit-actions .control-button{min-width:0;width:100%;padding:6px 8px}
.core-card{border:2px solid var(--success);border-top-width:5px}.core-card[data-filter-status="review"]{border-color:var(--warning)}.core-card[data-filter-status="missing"]{border-color:var(--danger)}.core-card[data-filter-status="ok"],.core-card[data-confirmed="true"]{border-color:var(--success)}.core-card[data-has-change="true"]{border-color:var(--purple)}.core-status{font-weight:850;letter-spacing:.07em}.core-card[data-filter-status="ok"] .core-status,.core-card[data-confirmed="true"] .core-status{color:var(--success)}.core-card[data-filter-status="missing"] .core-status{color:var(--danger)}.core-card[data-filter-status="review"] .core-status{color:var(--warning)}.core-card[data-has-change="true"] .core-status{color:var(--purple)}.core-meta{display:grid;gap:2px;line-height:1.45}.core-meta span{display:block}.confidence{font-weight:800}.confidence-high{color:var(--success)}.confidence-medium{color:var(--warning)}.confidence-low{color:var(--danger)}.card-actions{justify-content:flex-end}.card-badges{display:none!important}.card-action-buttons{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));width:100%}.card-action-buttons .control-button{width:100%;min-width:0}
.filterbar button[data-filter="ok"]{color:var(--success-text)}.filterbar button[data-filter="missing"]{color:var(--danger-text)}.filterbar button[data-filter="review"]{color:var(--warning-text)}.filterbar button[data-filter="ok"][aria-pressed="true"]{color:#10351b}.filterbar button[data-filter="missing"][aria-pressed="true"]{color:#5c1612}.filterbar button[data-filter="review"][aria-pressed="true"]{color:#4a3600}.core-card[data-filter-status="ok"] .core-status,.core-card[data-confirmed="true"] .core-status,.confidence-high{color:var(--success-text)}.core-card[data-filter-status="missing"] .core-status,.confidence-low{color:var(--danger-text)}.core-card[data-filter-status="review"] .core-status,.confidence-medium{color:var(--warning-text)}.core-card[data-confirmed="true"] .confirm-button{border-color:var(--warning);background:var(--warning-bg);color:var(--warning-text)}
@media(max-width:980px){.topbar{align-items:flex-start;flex-wrap:wrap;gap:8px}.nav{order:3;width:100%}.header-actions{margin-left:auto}.help-button span{display:none}.hero{grid-template-columns:1fr}.hero-side{max-width:none}.section-head{align-items:flex-start;flex-direction:column}}
@media(max-width:600px){:root{--header:116px}.brand-name{display:none}.shell{padding-top:24px}.topbar{padding-inline:12px}.nav button{padding-inline:12px}.hero{padding:24px;border-radius:16px}.metric-grid,.grid-summary{grid-template-columns:repeat(2,1fr)}.metric{padding:16px}.media-caption,.filter-tools,.change-bar{align-items:stretch;flex-direction:column}.filter-actions,.core-search,.change-actions,.change-actions button{width:100%}.output-mode-control{grid-template-columns:1fr}.mode-toggle{min-width:0;width:100%}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation-duration:.01ms!important;animation-iteration-count:1!important;transition-duration:.01ms!important;scroll-behavior:auto!important}}
.gatebar{position:sticky;top:var(--header);z-index:35;display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:12px 24px;padding:16px clamp(16px,4vw,48px);border-bottom:1px solid var(--border);background:var(--warning-bg);color:var(--fg)}.gatebar[data-state="sent"]{background:var(--success-bg)}.gatebar[data-state="error"]{background:var(--danger-bg)}.gate-copy{display:grid;gap:2px;min-width:0}.gate-kicker{display:inline-flex;align-items:center;gap:7px;font-size:12px;font-weight:800;letter-spacing:.06em;text-transform:uppercase;color:var(--warning-text)}.gatebar[data-state="sent"] .gate-kicker{color:var(--success-text)}.gatebar[data-state="error"] .gate-kicker{color:var(--danger-text)}.gate-kicker .icon{width:16px;height:16px}.gate-copy strong{font-size:17px;letter-spacing:-.01em}.gate-copy span{color:var(--muted);font-size:14px}.gate-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.gate-actions .primary{min-height:48px;padding:12px 24px;font-size:15px}.gate-actions .primary:disabled{opacity:.55;cursor:default}@media(max-width:720px){.gatebar{grid-template-columns:1fr}.gate-actions{width:100%}.gate-actions .primary{flex:1}}
</style></head><body><a class="skip" href="#main">Skip to content</a><header class="topbar"><div class="brand"><span class="brand-mark">''')
    html.append(projectIcon('microscope')).append('</span><span class="brand-name">CoreAlign</span></div>')
    html.append('''<nav class="nav" aria-label="Project sections"><button type="button" data-nav="overview" aria-current="page">Overview</button><span class="crumb-separator" aria-hidden="true">''')
    html.append(projectIcon('forward')).append('</span><button type="button" data-nav="grid">Grid QC</button><span class="crumb-separator" aria-hidden="true">')
    html.append(projectIcon('forward')).append('</span><button type="button" data-nav="orientation">Orientation QC</button><span class="crumb-separator" aria-hidden="true">')
    html.append(projectIcon('forward')).append('</span><button type="button" data-nav="results">Results</button></nav><div class="header-actions"><button class="help-button" type="button" data-nav="help">')
    html.append(projectIcon('help')).append('<span>Help</span></button><button class="icon-button" type="button" id="themeToggle" aria-label="Switch color theme" title="Switch color theme"><span class="moon-icon">')
    html.append(projectIcon('moon')).append('</span><span class="sun-icon">').append(projectIcon('sun')).append('</span></button></div></header>')
    if (gateOpen) {
        html.append('<div class="gatebar" id="gateBar" data-gate="')
            .append(projectHtmlEscape(openGateId)).append('" data-panel-target="')
            .append(projectHtmlEscape(gatePanel))
            .append('" data-state="waiting" role="status"><div class="gate-copy"><span class="gate-kicker">')
            .append(projectIcon('warning'))
            .append('QuPath is waiting for you</span><strong id="gateTitle">')
            .append(projectHtmlEscape(gateTitle)).append('</strong><span id="gateSummary">')
            .append(projectHtmlEscape(gateSummary))
            .append('</span></div><div class="gate-actions"><button class="secondary" type="button" data-nav="')
            .append(projectHtmlEscape(gatePanel)).append('">Look at the images</button>')
            .append('<button class="primary" type="button" id="gateContinue" data-endpoint="')
            .append(projectHtmlEscape(gateEndpointUrl)).append('">')
            .append(projectHtmlEscape(gateButtonLabel)).append(projectIcon('forward'))
            .append('</button></div></div>')
    }
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
    html.append('<div class="section-block notice">').append(projectIcon('warning')).append('<div><strong>After correcting circles</strong><p>Draw or adjust TMA correction annotations in QuPath, then use the button at the top of this page. CoreAlign applies them, refreshes this overview, and asks you to look once more.</p></div></div>')
    html.append('</section>')

    html.append('<section class="panel" id="orientation" data-panel data-review-key="').append(projectHtmlEscape(orientationReviewKey)).append('" data-image-name="').append(projectHtmlEscape(imageName)).append('" data-profile-name="').append(projectHtmlEscape(profileName)).append('" data-auto-save-url="').append(projectHtmlEscape(correctionAutoSaveUrl)).append('" data-open-qupath-url="').append(projectHtmlEscape(correctionOpenQuPathUrl)).append('"><div class="section-head"><div><div class="eyebrow">Rotation report</div><h2>Orientation QC</h2><p>Confirm correct rotations. Edit only the wrong ones.</p></div>')
    if (hasContactSheet) html.append('<a class="secondary" href="qc/02-orientation/orientation_contact_sheet.png" target="_blank" rel="noopener">Contact sheet ').append(projectIcon('external')).append('</a>')
    html.append('</div>')
    html.append('<div class="notice">').append(projectIcon('warning')).append('<div><strong>Review each core</strong><p id="reviewHelpText">Correct? Click Confirm. Wrong? Click Edit, adjust the angle, then Update.</p></div></div>')
    if (hasOrientation && !coreRecords.isEmpty()) {
        html.append('<div class="change-bar" id="changeBar" data-state="ready"><div class="change-copy"><strong id="changeCount">No angle changes</strong><span class="autosave-status" id="autosaveStatus" data-state="ready">Confirm more cores, or edit a wrong rotation.</span></div><div class="change-actions"><button class="secondary" type="button" id="downloadChanges" hidden>Save changes</button><button class="primary" type="button" id="openQuPath" hidden>Go to QuPath</button></div></div>')
        html.append('<div class="filter-tools section-block"><div class="filter-actions"><div class="filterbar" role="group" aria-label="Filter core review cards"><button type="button" data-filter="all" aria-pressed="')
            .append(reviewCountForPage > 0 ? 'false' : 'true').append('">All cores</button><button type="button" data-filter="ok" aria-pressed="false">QC pass</button><button type="button" data-filter="missing" aria-pressed="false">Missing</button><button type="button" data-filter="review" aria-pressed="')
            .append(reviewCountForPage > 0 ? 'true' : 'false').append('">Needs review</button><button type="button" data-filter="changes" aria-pressed="false">Changes</button></div><button class="control-button confirm-all-pass" type="button" id="confirmAllPass">Confirm all QC pass</button></div><label class="search-control"><span class="sr-only">Find a core by row and column</span><input class="core-search" id="coreSearch" type="search" placeholder="Find core, for example 4-C"><button class="search-clear" type="button" id="coreSearchClear" hidden>Clear</button></label></div><div class="core-grid" id="coreGrid">')
        coreRecords.each { record ->
            String status = (record.status ?: 'unknown').toString()
            String filterStatus = status in ['review', 'uncertain'] ? 'review' :
                status in ['missing', 'no_tissue', 'processing_error', 'export_error'] ? 'missing' : 'ok'
            String displayStatus = filterStatus == 'review' ? 'Needs review' :
                filterStatus == 'missing' ? 'Missing' : 'QC pass'
            double confidenceValue = record.confidence instanceof Number ?
                ((Number) record.confidence).doubleValue() : 0.0d
            String confidenceLevel = confidenceValue >= 0.70d ? 'high' :
                confidenceValue >= 0.40d ? 'medium' : 'low'
            String preview = projectPathString(record.rotatedPreview)
            String sourcePreview = projectPathString(record.unrotatedPreview)
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
            html.append('<div class="core-meta"><span class="confidence confidence-')
                .append(confidenceLevel).append('">Confidence ')
                .append(asProjectDecimal(record.confidence, 3)).append('</span><span>Rotation ')
                .append(asProjectDecimal(record.rotateToTopDeg, 1)).append(' deg.</span><span>Residual ')
                .append(asProjectDecimal(record.postRotationResidualDeg, 1)).append(' deg.</span></div>')
            if (previewAvailable && filterStatus != 'missing') {
                html.append('<div class="card-actions"><div class="card-action-buttons"><button class="control-button edit-button" type="button" data-edit>Edit</button><button class="control-button confirm-button" type="button" data-card-confirm>Confirm</button></div></div><div class="edit-panel"><div class="rotation-editor"><label>Rotation <output data-rotation-value>0 deg</output></label><input type="range" min="-180" max="180" step="1" value="0" data-rotation-adjust aria-label="Rotation adjustment for core ')
                    .append(projectHtmlEscape(record.core)).append('"></div><div class="edit-actions"><button class="control-button" type="button" data-edit-reset>Reset</button><button class="control-button" type="button" data-edit-cancel>Cancel</button><button class="control-button" type="button" data-edit-confirm>Update</button></div></div>')
            }
            html.append('</div></article>')
        }
        html.append('</div>')
    } else if (hasContactSheet) {
        html.append('<figure class="media-card"><a href="qc/02-orientation/orientation_contact_sheet.png" target="_blank" rel="noopener"><img src="qc/02-orientation/orientation_contact_sheet.png" alt="TMA orientation contact sheet"></a><figcaption class="media-caption"><p>The contact sheet is available. Run the updated CoreAlign once to add interactive per-core filters here.</p></figcaption></figure>')
    } else {
        html.append('<div class="empty">').append(projectIcon('image')).append('<h3>No orientation results yet</h3><p>Approve the grid and run CoreAlign.groovy again.</p></div>')
    }
    html.append('</section>')

    html.append('<section class="panel" id="results" data-panel data-output-mode="')
        .append(currentOutputMode).append('" data-profile-name="')
        .append(projectHtmlEscape(profileName)).append('" data-output-mode-url="')
        .append(projectHtmlEscape(outputModeUrl)).append('"><div class="section-head"><div><div class="eyebrow">Output report</div><h2>Results</h2><p>Choose files by purpose. PNG is for presentation. Multichannel OME-TIFF and the QuPath project are for research analysis.</p></div></div>')
    html.append('<div class="output-mode-control"><div class="output-mode-copy"><strong>Output package</strong><span>Research adds multichannel OME-TIFF files and a QuPath project. Switching after a finished run reuses every accepted core.</span></div><div class="mode-toggle" role="group" aria-label="Output package"><button type="button" data-output-mode-choice="presentation" aria-pressed="')
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

    html.append('<section class="panel" id="help" data-panel><div class="section-head"><div><div class="eyebrow">Quick guide</div><h2>Four things to know</h2><p>One run from start to finish. CoreAlign waits for you twice and continues from your answer.</p></div></div><div class="help-list"><article class="help-item"><h3>Run the script once</h3><p>Put the slide and CoreAlign.groovy in one folder, open the slide in QuPath, and run the script. Pick tissue and results, then press Start.</p></article><article class="help-item"><h3>Answer where you are</h3><p>When CoreAlign needs you, a bar appears at the top of this page and a small window appears in QuPath. Either one continues the run.</p></article><article class="help-item"><h3>Fix only what is wrong</h3><p>For a missed core, draw an ellipse in QuPath and name it TMA correction. For a wrong angle, use Edit on the core card. Everything you already accepted is kept.</p></article><article class="help-item"><h3>Nothing is lost</h3><p>Progress is saved after every core. If QuPath closes, run the script again and it picks up from the last saved core.</p></article></div><div class="section-block notice">').append(projectIcon('check')).append('<div><strong>No report filenames to remember</strong><p>REPORT.html is the only page you need. Machine-readable audit files stay in JSON and CSV beside it.</p></div></div></section>')
    html.append('<dialog class="saved-modal" id="savedModal" aria-labelledby="savedModalTitle"><div class="saved-modal-body"><div class="saved-modal-mark">').append(projectIcon('check')).append('</div><h3 id="savedModalTitle">Saved</h3><p id="savedModalText">Your changes are saved beside REPORT.html.</p><button class="primary" type="button" id="savedModalClose">Continue</button></div></dialog>')
    html.append('<dialog class="saved-modal action-modal" id="actionModal" aria-labelledby="actionModalTitle"><div class="saved-modal-body"><div class="saved-modal-mark">').append(projectIcon('warning')).append('</div><h3 id="actionModalTitle">Confirm change</h3><p id="actionModalText">Save this change?</p><div class="action-modal-actions"><button class="secondary" type="button" id="actionModalCancel">Cancel</button><button class="primary" type="button" id="actionModalConfirm">Continue</button></div></div></dialog>')
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
var orientationSection=document.getElementById("orientation"),correctionAutoSaveUrl=orientationSection?orientationSection.dataset.autoSaveUrl||"":"",correctionOpenQuPathUrl=orientationSection?orientationSection.dataset.openQupathUrl||"":"",reviewStorageKey="corealign-rotation-edits:"+location.pathname+":"+(orientationSection?orientationSection.dataset.reviewKey:"pending"),reviewState={angles:{},confirmed:{}},projectFolderHandle=null,correctionBridgeReady=false,correctionsDirty=false,saveFallbackRequired=false,corealignAppHub=window.corealignAppHub&&typeof window.corealignAppHub.saveFile==="function"?window.corealignAppHub:null,reviewHelpText=document.getElementById("reviewHelpText");try{var savedReview=JSON.parse(localStorage.getItem(reviewStorageKey)||"{}");reviewState.angles=savedReview.angles||{};reviewState.confirmed=savedReview.confirmed||{};}catch(e){reviewState={angles:{},confirmed:{}};}
function openFolderHandleDb(){return new Promise(function(resolve,reject){try{var request=indexedDB.open("corealign-report",1);request.onupgradeneeded=function(){if(!request.result.objectStoreNames.contains("handles")){request.result.createObjectStore("handles");}};request.onsuccess=function(){resolve(request.result);};request.onerror=function(){reject(request.error);};}catch(error){reject(error);}});}
async function rememberProjectFolder(handle){try{var db=await openFolderHandleDb();await new Promise(function(resolve,reject){var tx=db.transaction("handles","readwrite");tx.objectStore("handles").put(handle,"project-folder");tx.oncomplete=resolve;tx.onerror=function(){reject(tx.error);};});db.close();}catch(error){}}
async function restoreProjectFolder(){try{var db=await openFolderHandleDb(),handle=await new Promise(function(resolve,reject){var tx=db.transaction("handles","readonly"),request=tx.objectStore("handles").get("project-folder");request.onsuccess=function(){resolve(request.result||null);};request.onerror=function(){reject(request.error);};});db.close();return handle;}catch(error){return null;}}
async function folderPermission(handle,requestAccess){if(!handle){return false;}if(!handle.queryPermission){return true;}var options={mode:"readwrite"},state=await handle.queryPermission(options);if(state==="granted"){return true;}if(requestAccess&&handle.requestPermission){state=await handle.requestPermission(options);}return state==="granted";}
async function ensureProjectFolder(requestAccess){if(corealignAppHub){return "apphub";}if(!projectFolderHandle){projectFolderHandle=await restoreProjectFolder();}if(projectFolderHandle&&await folderPermission(projectFolderHandle,requestAccess)){return projectFolderHandle;}if(!requestAccess||!window.showDirectoryPicker){return null;}var handle=await window.showDirectoryPicker({mode:"readwrite",id:"corealign-project",startIn:"downloads"});try{await handle.getFileHandle("REPORT.html");}catch(error){throw new Error("The selected folder does not contain REPORT.html");}if(!await folderPermission(handle,true)){throw new Error("Folder access was not granted");}projectFolderHandle=handle;await rememberProjectFolder(handle);return handle;}
async function readProjectText(name,requestAccess){if(corealignAppHub){return await corealignAppHub.readFile(name);}var handle=await ensureProjectFolder(requestAccess);if(!handle||handle==="apphub"){throw new Error("Project folder is not connected");}var fileHandle=await handle.getFileHandle(name);return await (await fileHandle.getFile()).text();}
async function writeProjectText(name,text,requestAccess){if(corealignAppHub){await corealignAppHub.saveFile(name,text);return true;}var handle=await ensureProjectFolder(requestAccess);if(!handle||handle==="apphub"){return false;}var fileHandle=await handle.getFileHandle(name,{create:true}),writable=await fileHandle.createWritable();await writable.write(text);await writable.close();return true;}
function saveReviewState(){try{localStorage.setItem(reviewStorageKey,JSON.stringify(reviewState));}catch(e){}}
function showRotationPreview(card,value){var applied=Number(card.dataset.appliedAdjustment||0),angle=Math.max(-180,Math.min(180,Math.round(Number(value)||0))),delta=angle-applied,input=card.querySelector("[data-rotation-adjust]"),output=card.querySelector("[data-rotation-value]"),image=card.querySelector('[data-preview="rotated"] img');card.dataset.draftRotation=String(angle);if(input){input.value=String(angle);}if(output){output.textContent=(angle>0?"+":"")+angle+" deg";}if(image){var r=delta*Math.PI/180,fit=1/(Math.abs(Math.cos(r))+Math.abs(Math.sin(r)));image.style.transform="rotate("+delta+"deg) scale("+fit.toFixed(4)+")";}}
var autoSaveTimer=0,autoSaveModalPending=false,autoSaveStatus=document.getElementById("autosaveStatus"),downloadChanges=document.getElementById("downloadChanges"),openQuPath=document.getElementById("openQuPath"),confirmAllPass=document.getElementById("confirmAllPass"),savedModal=document.getElementById("savedModal"),savedModalTitle=document.getElementById("savedModalTitle"),savedModalText=document.getElementById("savedModalText"),savedModalClose=document.getElementById("savedModalClose"),actionModal=document.getElementById("actionModal"),actionModalTitle=document.getElementById("actionModalTitle"),actionModalText=document.getElementById("actionModalText"),actionModalCancel=document.getElementById("actionModalCancel"),actionModalConfirm=document.getElementById("actionModalConfirm"),actionModalResolve=null;
function showSavedModal(title,message){if(!savedModal){return;}if(savedModalTitle){savedModalTitle.textContent=title;}if(savedModalText){savedModalText.textContent=message;}if(typeof savedModal.showModal==="function"){if(!savedModal.open){savedModal.showModal();}}else{savedModal.setAttribute("open","");}}
function closeSavedModal(){if(!savedModal){return;}if(typeof savedModal.close==="function"&&savedModal.open){savedModal.close();}else{savedModal.removeAttribute("open");}}
if(savedModalClose){savedModalClose.addEventListener("click",closeSavedModal);}
function closeActionModal(accepted){if(!actionModal){return;}if(typeof actionModal.close==="function"&&actionModal.open){actionModal.close();}else{actionModal.removeAttribute("open");}var resolve=actionModalResolve;actionModalResolve=null;if(resolve){resolve(accepted===true);}}
function confirmAction(title,message,confirmLabel){if(!actionModal){return Promise.resolve(window.confirm(message));}if(actionModalTitle){actionModalTitle.textContent=title;}if(actionModalText){actionModalText.textContent=message;}if(actionModalConfirm){actionModalConfirm.textContent=confirmLabel||"Continue";}return new Promise(function(resolve){actionModalResolve=resolve;if(typeof actionModal.showModal==="function"){if(!actionModal.open){actionModal.showModal();}}else{actionModal.setAttribute("open","");}});}
if(actionModalCancel){actionModalCancel.addEventListener("click",function(){closeActionModal(false);});}if(actionModalConfirm){actionModalConfirm.addEventListener("click",function(){closeActionModal(true);});}if(actionModal){actionModal.addEventListener("cancel",function(event){event.preventDefault();closeActionModal(false);});}
function updateReviewHelp(){if(!reviewHelpText){return;}if(saveFallbackRequired){reviewHelpText.textContent=window.showDirectoryPicker?"Correct? Click Confirm. Wrong? Click Edit, adjust the angle, then Update. Use Choose project folder above to save.":"Correct? Click Confirm. Wrong? Click Edit, adjust the angle, then Update. Use Download changes above when finished.";}else if(corealignAppHub){reviewHelpText.textContent="Correct? Click Confirm. Wrong? Click Edit, adjust the angle, then Update. AppHub saves angle changes in this project.";}else if(correctionAutoSaveUrl){reviewHelpText.textContent="Correct? Click Confirm. Wrong? Click Edit, adjust the angle, then Update. Angle changes save through QuPath.";}else if(window.showDirectoryPicker){reviewHelpText.textContent="Correct? Click Confirm. Wrong? Click Edit, adjust the angle, then Update. Choose this project folder once when asked to save.";}else{reviewHelpText.textContent="Correct? Click Confirm. Wrong? Click Edit, adjust the angle, then Update. Download one correction file when you finish.";}}
function setAutoSaveStatus(message,state,showFallback){var actualState=state||"ready",bar=document.getElementById("changeBar");if(autoSaveStatus){autoSaveStatus.textContent=message;autoSaveStatus.dataset.state=actualState;}if(bar){bar.dataset.state=actualState;}if(downloadChanges){downloadChanges.hidden=!showFallback;downloadChanges.textContent=window.showDirectoryPicker?"Choose project folder":"Download changes";}}
function updateChangeBar(){var count=document.querySelectorAll('.core-card[data-has-change="true"]').length,bar=document.getElementById("changeBar"),label=document.getElementById("changeCount"),saving=autoSaveStatus&&autoSaveStatus.dataset.state==="saving";if(!bar){return;}bar.hidden=false;if(label){label.textContent=count>0?count+" angle change"+(count===1?"":"s"):correctionsDirty?"Rotation reset":"No angle changes";}if(openQuPath){openQuPath.hidden=correctionsDirty||count===0||!correctionOpenQuPathUrl;}if(saving){return;}if(!correctionsDirty){setAutoSaveStatus(count>0?"Saved. Approve at the top of this page when you finish reviewing.":"Nothing to reprocess unless you change an angle.","ready",false);return;}if(!saveFallbackRequired&&(corealignAppHub||correctionBridgeReady||projectFolderHandle||correctionAutoSaveUrl)){setAutoSaveStatus("Saving angle changes to this project...","saving",false);return;}setAutoSaveStatus(window.showDirectoryPicker?"Select the folder that contains REPORT.html once.":"Download one correction file when you finish reviewing.","action",true);}
function refreshCardStatus(card){var status=card.querySelector(".core-status");if(status){status.textContent=card.dataset.hasChange==="true"?"Changes":card.dataset.confirmed==="true"?"QC pass":card.dataset.originalStatus||"Unknown";}}
function updateCardChange(card){var applied=Number(card.dataset.appliedAdjustment||0),committed=Number(card.dataset.manualRotation||0),changed=Math.abs(committed-applied)>=.05;card.dataset.hasChange=changed?"true":"false";refreshCardStatus(card);updateChangeBar();}
function setCardConfirmed(card,confirmed){var button=card.querySelector("[data-card-confirm]");card.dataset.confirmed=confirmed?"true":"false";if(button){button.textContent=confirmed?"Undo":"Confirm";button.setAttribute("aria-pressed",confirmed?"true":"false");button.setAttribute("aria-label",confirmed?"Undo confirmation":"Confirm this core");}refreshCardStatus(card);}
function updateConfirmAllPass(){if(!confirmAllPass){return;}var remaining=Array.from(document.querySelectorAll('.core-card[data-filter-status="ok"]')).filter(function(card){return card.dataset.confirmed!=="true";}).length;confirmAllPass.disabled=remaining===0;confirmAllPass.textContent=remaining===0?"QC pass confirmed":"Confirm all QC pass";}
function undoCard(card,key){var applied=Number(card.dataset.appliedAdjustment||0),hadChange=card.dataset.hasChange==="true";card.dataset.manualRotation=String(applied);showRotationPreview(card,applied);delete reviewState.angles[key];delete reviewState.confirmed[key];setCardConfirmed(card,false);saveReviewState();if(hadChange){correctionsDirty=true;}updateCardChange(card);updateConfirmAllPass();applyCoreFilters();if(hadChange){autoSaveCorrections(false);}}
document.querySelectorAll(".core-card").forEach(function(card){var key=card.dataset.coreName||"",applied=Number(card.dataset.appliedAdjustment||0),savedAngle=Number(reviewState.angles[key]),committed=Number.isFinite(savedAngle)?savedAngle:applied;card.dataset.manualRotation=String(committed);showRotationPreview(card,committed);updateCardChange(card);setCardConfirmed(card,reviewState.confirmed[key]===true);var cardConfirm=card.querySelector("[data-card-confirm]"),edit=card.querySelector("[data-edit]"),slider=card.querySelector("[data-rotation-adjust]"),reset=card.querySelector("[data-edit-reset]"),cancel=card.querySelector("[data-edit-cancel]"),confirm=card.querySelector("[data-edit-confirm]");if(cardConfirm){cardConfirm.addEventListener("click",function(){if(card.dataset.confirmed==="true"){undoCard(card,key);return;}reviewState.confirmed[key]=true;setCardConfirmed(card,true);saveReviewState();updateConfirmAllPass();updateChangeBar();applyCoreFilters();showSavedModal("Checked","This core is confirmed. You only need to rerun CoreAlign after changing an angle.");});}if(edit){edit.addEventListener("click",function(){card.dataset.editStart=card.dataset.manualRotation;showRotationPreview(card,card.dataset.manualRotation);card.classList.add("is-editing");});}if(slider){slider.addEventListener("input",function(){showRotationPreview(card,slider.value);});}if(reset){reset.addEventListener("click",function(){showRotationPreview(card,applied);});}if(cancel){cancel.addEventListener("click",function(){showRotationPreview(card,card.dataset.editStart||card.dataset.manualRotation);card.classList.remove("is-editing");});}if(confirm){confirm.addEventListener("click",function(){var angle=Number(card.dataset.draftRotation||0);card.dataset.manualRotation=String(angle);if(Math.abs(angle-applied)>=.05){reviewState.angles[key]=angle;}else{delete reviewState.angles[key];}reviewState.confirmed[key]=true;correctionsDirty=true;setCardConfirmed(card,true);saveReviewState();updateCardChange(card);updateConfirmAllPass();applyCoreFilters();card.classList.remove("is-editing");autoSaveCorrections(false);});}});correctionsDirty=document.querySelector('.core-card[data-has-change="true"]')!==null;updateReviewHelp();updateChangeBar();updateConfirmAllPass();applyCoreFilters();
if(confirmAllPass){confirmAllPass.addEventListener("click",function(){document.querySelectorAll('.core-card[data-filter-status="ok"]').forEach(function(card){var key=card.dataset.coreName||"";reviewState.confirmed[key]=true;setCardConfirmed(card,true);});saveReviewState();updateConfirmAllPass();updateChangeBar();applyCoreFilters();showSavedModal("Saved","All QC pass cores are confirmed in this report.");});}
function reviewCorrectionsPayload(){var corrections=[];document.querySelectorAll(".core-card").forEach(function(card){var title=card.querySelector(".core-title strong"),angle=Number(card.dataset.manualRotation||0);if(Math.abs(angle)>=.05){corrections.push({core:title?title.textContent:"",rotationAdjustmentDeg:angle});}});return {schemaVersion:1,image:orientationSection?orientationSection.dataset.imageName:"",baseRun:orientationSection?orientationSection.dataset.reviewKey:"",createdAt:new Date().toISOString(),corrections:corrections};}
async function autoSaveCorrections(requestFolder){saveFallbackRequired=false;setAutoSaveStatus("Saving angle changes...","saving",false);var text=JSON.stringify(reviewCorrectionsPayload(),null,2)+String.fromCharCode(10),saved=false;try{if(corealignAppHub){saved=await writeProjectText("corealign-review-corrections.json",text,false);}}catch(error){}if(!saved&&correctionAutoSaveUrl){try{var response=await fetch(correctionAutoSaveUrl,{method:"POST",headers:{"Content-Type":"text/plain;charset=UTF-8"},body:text,cache:"no-store"});if(response.ok){saved=true;correctionBridgeReady=true;}}catch(error){correctionBridgeReady=false;}}if(!saved&&projectFolderHandle){try{saved=await writeProjectText("corealign-review-corrections.json",text,false);}catch(error){projectFolderHandle=null;}}if(!saved&&requestFolder===true&&window.showDirectoryPicker){try{saved=await writeProjectText("corealign-review-corrections.json",text,true);}catch(error){if(error&&error.name!=="AbortError"){showSavedModal("Choose your CoreAlign project folder","Select the folder that contains REPORT.html, CoreAlign.groovy, and corealign.config.json.");}}}if(saved){correctionsDirty=false;saveFallbackRequired=false;updateReviewHelp();setAutoSaveStatus("Saved. Approve at the top of this page when you finish reviewing.","ready",false);updateChangeBar();if(requestFolder===true){showSavedModal("Angle changes saved","Keep reviewing. Approve at the top of this page when you are finished and CoreAlign reprocesses only these cores.");}return true;}saveFallbackRequired=true;updateReviewHelp();setAutoSaveStatus(window.showDirectoryPicker?"Select the folder that contains REPORT.html once.":"Download one correction file when you finish reviewing.","action",true);updateChangeBar();return false;}
function queueAutoSaveCorrections(showModal){autoSaveModalPending=autoSaveModalPending||showModal===true;if(autoSaveTimer){clearTimeout(autoSaveTimer);}autoSaveTimer=setTimeout(function(){var shouldShow=autoSaveModalPending;autoSaveModalPending=false;autoSaveCorrections(shouldShow);},80);}
async function saveCorrectionFile(){if(await autoSaveCorrections(true)){return;}if(window.showDirectoryPicker){return;}var text=JSON.stringify(reviewCorrectionsPayload(),null,2)+String.fromCharCode(10),blob=new Blob([text],{type:"application/json"}),url=URL.createObjectURL(blob),link=document.createElement("a");link.href=url;link.download="corealign-review-corrections.json";link.click();setTimeout(function(){URL.revokeObjectURL(url);},0);setAutoSaveStatus("Downloaded. Put the file beside REPORT.html, then approve at the top of this page.","action",true);showSavedModal("Correction file downloaded","Move corealign-review-corrections.json into the folder that contains REPORT.html, replace the older file, then approve at the top of this page.");}
async function focusQuPath(){if(!correctionOpenQuPathUrl){setAutoSaveStatus("Open QuPath from your applications.","error",true);return;}try{var response=await fetch(correctionOpenQuPathUrl,{method:"POST",headers:{"Content-Type":"text/plain;charset=UTF-8"},body:"focus",cache:"no-store"});if(!response.ok){throw new Error("Open failed");}setAutoSaveStatus("QuPath is in front. Draw your correction there, then continue here.","ready",false);}catch(error){setAutoSaveStatus("Open QuPath from your applications.","error",true);}}
var resultsSection=document.getElementById("results"),outputModeUrl=resultsSection?resultsSection.dataset.outputModeUrl||"":"",outputModeStatus=document.getElementById("outputModeStatus"),outputModeButtons=Array.from(document.querySelectorAll("[data-output-mode-choice]"));
async function saveOutputModeToProject(mode,requestAccess){var text=await readProjectText("corealign.config.json",requestAccess),root=JSON.parse(text),profileName=resultsSection?resultsSection.dataset.profileName||root.activeProfile||"automatic":"automatic";if(!root.profiles||!root.profiles[profileName]){throw new Error("The current config profile is invalid");}var profile=root.profiles[profileName];if(!profile.orientation){profile.orientation={};}profile.orientation.saveFullResolutionPng=true;profile.orientation.saveNativeOmeTiff=false;profile.orientation.saveRotatedMultichannelOmeTiff=mode==="research";return await writeProjectText("corealign.config.json",JSON.stringify(root,null,2)+String.fromCharCode(10),requestAccess);}
async function setOutputMode(mode){if(!resultsSection||mode===resultsSection.dataset.outputMode){return;}outputModeButtons.forEach(function(button){button.disabled=true;});if(outputModeStatus){outputModeStatus.textContent="Saving output mode...";}var saved=false;if(corealignAppHub){try{saved=await saveOutputModeToProject(mode,false);}catch(error){saved=false;}}if(!saved&&outputModeUrl){try{var response=await fetch(outputModeUrl,{method:"POST",headers:{"Content-Type":"text/plain;charset=UTF-8"},body:JSON.stringify({mode:mode}),cache:"no-store"});saved=response.ok;}catch(error){saved=false;}}if(!saved&&(projectFolderHandle||window.showDirectoryPicker)){try{saved=await saveOutputModeToProject(mode,true);}catch(error){saved=false;}}if(saved){resultsSection.dataset.outputMode=mode;outputModeButtons.forEach(function(button){button.setAttribute("aria-pressed",button.dataset.outputModeChoice===mode?"true":"false");});if(outputModeStatus){outputModeStatus.textContent=mode==="research"?"Research saved. It applies to this run and every run after it.":"Presentation saved. It applies to this run and every run after it.";}showSavedModal(mode==="research"?"Research selected":"Presentation selected",mode==="research"?"Saved. Multichannel OME-TIFF files and a QuPath project are created when this run finishes.":"Saved. Full-resolution PNG images are created when this run finishes.");}else{if(outputModeStatus){outputModeStatus.textContent=window.showDirectoryPicker?"Choose the folder that contains REPORT.html and try again.":"Open this report from AppHub, or keep QuPath open while changing the package.";}showSavedModal("Output mode was not saved",window.showDirectoryPicker?"Select the CoreAlign project folder that contains REPORT.html and corealign.config.json.":"This saves directly while QuPath is open, or when the report is opened in AppHub. Keep QuPath open and try again.");}outputModeButtons.forEach(function(button){button.disabled=false;});}
async function requestOutputMode(mode){if(!resultsSection||mode===resultsSection.dataset.outputMode){return;}var research=mode==="research",accepted=await confirmAction(research?"Switch to Research?":"Switch to Presentation?",research?"This saves Research in corealign.config.json. After saving, return to QuPath and run CoreAlign.groovy again to create multichannel OME-TIFF files.":"This saves Presentation in corealign.config.json. After saving, return to QuPath and run CoreAlign.groovy again to update the PNG package.",research?"Save Research":"Save Presentation");if(accepted){setOutputMode(mode);}}
outputModeButtons.forEach(function(button){button.addEventListener("click",function(){requestOutputMode(button.dataset.outputModeChoice);});});
if(downloadChanges){downloadChanges.addEventListener("click",saveCorrectionFile);}if(openQuPath){openQuPath.addEventListener("click",focusQuPath);}restoreProjectFolder().then(function(handle){projectFolderHandle=handle;updateChangeBar();if((handle||correctionAutoSaveUrl)&&document.querySelector('.core-card[data-has-change="true"]')){queueAutoSaveCorrections(false);}});if(corealignAppHub&&document.querySelector('.core-card[data-has-change="true"]')){queueAutoSaveCorrections(false);}
var gridImage=document.getElementById("gridImage"),gridViewport=document.getElementById("gridViewport"),gridZoomValue=document.getElementById("gridZoomValue"),gridZoom=1;function fitGridImage(){if(!gridImage||!gridViewport||!gridImage.naturalWidth||!gridImage.naturalHeight){return;}var fit=Math.min(gridViewport.clientWidth/gridImage.naturalWidth,gridViewport.clientHeight/gridImage.naturalHeight);gridImage.style.width=Math.max(1,Math.floor(gridImage.naturalWidth*fit*gridZoom))+"px";gridImage.style.height="auto";if(gridZoomValue){gridZoomValue.textContent=gridZoom===1?"Fit":Math.round(gridZoom*100)+"%";}}function setGridZoom(value,resetScroll){gridZoom=Math.max(1,Math.min(4,value));fitGridImage();if(resetScroll&&gridViewport){gridViewport.scrollTo(0,0);}}var zoomIn=document.getElementById("gridZoomIn"),zoomOut=document.getElementById("gridZoomOut"),zoomReset=document.getElementById("gridZoomReset");if(zoomIn){zoomIn.addEventListener("click",function(){setGridZoom(gridZoom+.25,false);});}if(zoomOut){zoomOut.addEventListener("click",function(){setGridZoom(gridZoom-.25,false);});}if(zoomReset){zoomReset.addEventListener("click",function(){setGridZoom(1,true);});}if(gridImage){gridImage.addEventListener("load",function(){setGridZoom(1,true);});}window.addEventListener("resize",function(){if(gridZoom===1){fitGridImage();}});if(gridViewport){var panning=false,startX=0,startY=0,startLeft=0,startTop=0;gridViewport.addEventListener("pointerdown",function(event){if(event.button!==0){return;}panning=true;startX=event.clientX;startY=event.clientY;startLeft=gridViewport.scrollLeft;startTop=gridViewport.scrollTop;gridViewport.classList.add("is-panning");gridViewport.setPointerCapture(event.pointerId);});gridViewport.addEventListener("pointermove",function(event){if(!panning){return;}gridViewport.scrollLeft=startLeft-(event.clientX-startX);gridViewport.scrollTop=startTop-(event.clientY-startY);});function stopPan(){panning=false;gridViewport.classList.remove("is-panning");}gridViewport.addEventListener("pointerup",stopPan);gridViewport.addEventListener("pointercancel",stopPan);}
var gateBar=document.getElementById("gateBar"),gateContinue=document.getElementById("gateContinue");function setGateState(state,title,summary){if(!gateBar){return;}gateBar.dataset.state=state;var t=document.getElementById("gateTitle"),su=document.getElementById("gateSummary");if(t&&title){t.textContent=title;}if(su&&summary){su.textContent=summary;}}if(gateBar&&gateContinue){var gatePanel=gateBar.dataset.panelTarget;if(gatePanel&&!location.hash){showPanel(gatePanel,true);}gateContinue.addEventListener("click",async function(){var endpoint=gateContinue.dataset.endpoint,gate=gateBar.dataset.gate;if(!endpoint){setGateState("error","Continue in QuPath","This report is not connected to a running CoreAlign. Use the Continue button in the CoreAlign window.");return;}gateContinue.disabled=true;setGateState("waiting","Sending your decision...","");try{var response=await fetch(endpoint,{method:"POST",headers:{"Content-Type":"text/plain;charset=UTF-8"},body:JSON.stringify({gate:gate,decision:"continue"}),cache:"no-store"});if(response.ok){setGateState("sent","CoreAlign is working on it","You can leave this tab open. It refreshes the next time CoreAlign writes the report.");gateContinue.textContent="Sent";return;}setGateState("error","That step is no longer waiting","CoreAlign has already moved on, or this report belongs to an earlier run. Reload this page.");}catch(error){setGateState("error","Could not reach QuPath","Keep QuPath open and try again, or use the Continue button in the CoreAlign window.");}gateContinue.disabled=false;});}
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

// -------------------------------------------------------------------------
// Review gates.
//
// A gate is the only place a run stops. It opens REPORT.html, arms the
// loopback bridge, and waits. The reviewer answers either in the browser or in
// the small CoreAlign window, and the SAME run continues from that answer, so
// nobody has to return to the script editor and press Run again.
// -------------------------------------------------------------------------
Set<String> reportTabsOpened = [] as Set<String>
def openReportInBrowser = { boolean force ->
    File report = new File(workflowDir, 'REPORT.html')
    if (!report.isFile()) return false
    if (HEADLESS) {
        // There is no browser on a compute node, and the reviewer is not sitting at it.
        if (force || reportTabsOpened.add(report.getAbsolutePath()))
            println "COREALIGN_REPORT: ${report.getAbsolutePath()}"
        return false
    }
    if (!force && !reportTabsOpened.add(report.getAbsolutePath())) return true
    String os = System.getProperty('os.name', '').toLowerCase(Locale.ROOT)
    List<List<String>> attempts = []
    if (os.contains('mac')) {
        attempts << ['open', report.getAbsolutePath()]
    } else if (os.contains('win')) {
        attempts << ['rundll32', 'url.dll,FileProtocolHandler', report.getAbsolutePath()]
    } else {
        // The AppHub image ships a browser; a bare QuPath container may not.
        attempts << ['xdg-open', report.getAbsolutePath()]
        attempts << ['firefox', report.toURI().toString()]
        attempts << ['chromium', report.toURI().toString()]
    }
    for (List<String> command : attempts) {
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start()
            return true
        } catch (Throwable ignored) {}
    }
    println "Open this file to review: ${report.getAbsolutePath()}"
    return false
}

// A gate that is open is also written to work/state/<image>/gate.json, so anything driving a
// headless run knows what is being asked, what the numbers are, and where to post the answer,
// without scraping the log.
File gateStateFile = new File(stateDir, 'gate.json')
def writeGateState = { Map state ->
    try {
        if (state == null) { gateStateFile.delete(); return }
        File temporary = new File(stateDir, '.gate.json.tmp')
        temporary.setText(configJson.toJson(state) + '\n', 'UTF-8')
        Files.move(temporary.toPath(), gateStateFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING)
    } catch (Throwable gateStateError) {
        println "WARNING: could not write gate state: ${gateStateError.getMessage()}"
    }
}

def awaitReviewGate = { String gateId, String title, String summary, String detail,
        String primaryLabel ->
    if (ALL_IN_ONE_INTEGRATION_TEST) return 'continue'
    File report = new File(workflowDir, 'REPORT.html')
    openReportInBrowser(false)
    CoreAlignCorrectionBridge.openGate(gateId)
    if (!HEADLESS)
        CoreAlignGateWindow.open(gateId, title, summary, detail, primaryLabel,
            { -> openReportInBrowser(true) })
    writeGateState([schemaVersion: 1, gate: gateId, title: title, summary: summary,
        detail: detail, primaryLabel: primaryLabel, image: imageName,
        report: report.getAbsolutePath(),
        endpoint: System.getProperty('corealign.gate.endpoint', ''),
        openedAt: new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())])
    println "=== WAITING FOR REVIEW: ${title} ==="
    println "COREALIGN_GATE_OPEN: ${gateId} | ${summary}"
    if (!HEADLESS)
        println "Answer in REPORT.html or in the CoreAlign window: ${report.getAbsolutePath()}"
    long deadline = System.currentTimeMillis() + (12L * 60L * 60L * 1000L)
    String decision = ''
    try {
        while (decision.isEmpty()) {
            decision = CoreAlignCorrectionBridge.takeGateDecision()
            if (decision.isEmpty() && !HEADLESS) decision = CoreAlignGateWindow.take()
            if (!decision.isEmpty()) break
            if (System.currentTimeMillis() > deadline) {
                println 'REVIEW_TIMEOUT: no decision was made within 12 hours.'
                decision = 'cancel'
                break
            }
            Thread.sleep(350L)
        }
    } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt()
        decision = 'cancel'
    } finally {
        CoreAlignCorrectionBridge.closeGate()
        if (!HEADLESS) CoreAlignGateWindow.close()
        writeGateState(null)
    }
    println "COREALIGN_GATE_CLOSED: ${gateId} -> ${decision}"
    return decision
}

removeLegacyWorkflowHtml()
writeProjectIndex(null)
if (reportOnly) {
    println 'COREALIGN_REPORT_REFRESHED'
    return
}

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
    println 'A newer detector rebuilt the grid; the refreshed QC is ready for review.'
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
    }
}

if (!validateGridStructure('before_grid_approval')) return
if (!validateDetectionAgainstTechnicalReference('before_grid_approval')) return

// Gate 1: the grid.
//
// The loop exists because CoreAlign deliberately refuses to approve a grid in
// the same pass that changed it: after corrections are applied the reviewer has
// to see the updated circles first. That used to mean another manual run. Now
// the QC image is refreshed and the same gate simply opens again.
boolean gridApproved = false

// Do not ask again for a grid a person already approved.
//
// The approval is bound to the grid hash, so this skips only when the circles are byte for
// byte the ones that were signed off. Drawing a correction changes the hash and brings the
// gate straight back. Three things must all hold, and each one is a way the reviewer could
// otherwise be skipped past something they have not seen:
//   1. the approval was given by a person, never by an integration test
//   2. it names this exact grid, under the current detector
//   3. no correction annotation is waiting to be applied
def approvalGridSignature = { g ->
    g == null ? '' : hashText(canonicalGrid(g))
}
def correctionIsPending = { ->
    def hierarchy = imageData.getHierarchy()
    def gridNow = hierarchy.getTMAGrid()
    if (gridNow == null) return false
    Set<String> applied = gridNow.getTMACoreList().collect { core ->
        try { return core.getMetadataString('Correction annotation signature') }
        catch (Throwable ignored) { return null }
    }.findAll { it != null && !it.trim().isEmpty() } as Set<String>
    def tagged = { obj ->
        String cls = ''
        try { cls = obj.getPathClass()?.toString() ?: '' } catch (Throwable ignored) {}
        String nm = obj.getName() ?: ''
        ['TMA correction', 'TMA mark missing'].any { tag ->
            cls.trim().equalsIgnoreCase(tag) ||
                nm.toLowerCase(Locale.ROOT).contains(tag.toLowerCase(Locale.ROOT))
        }
    }
    // Same signature formula step 3 uses, so "already applied" means the same thing here.
    return hierarchy.getAnnotationObjects().any { ann ->
        if (ann.getROI() == null || !tagged(ann)) return false
        String action = (ann.getName() ?: '').toLowerCase(Locale.ROOT).contains('mark missing') ?
            'mark_missing' : 'replace_center'
        String signature = [ann.getID(), action, fmt3(ann.getROI().getCentroidX()),
            fmt3(ann.getROI().getCentroidY())].join('|')
        return !applied.contains(signature)
    }
}
if (savedApproval != null && savedApproval.status == 'APPROVED' &&
        savedApproval.approvalMode == 'human' && approvalUsesCurrentDetector) {
    String gridNowHash = approvalGridSignature(imageData.getHierarchy().getTMAGrid())
    if (!gridNowHash.isEmpty() && savedApproval.gridHash == gridNowHash) {
        if (correctionIsPending()) {
            println 'A correction is waiting to be applied, so the grid still needs review.'
        } else {
            println "Grid ${gridNowHash.take(12)} was already approved by a person on " +
                "${savedApproval.approvedAt ?: 'an earlier run'}; not asking again."
            gridApproved = true
        }
    }
}
// What the Studio page needs to draw the grid editor at this gate.
//
// The rich QC JSON is written by step 3, which by definition has not run yet the first
// time this gate opens: on a fresh slide the page would have no image, no circles and no
// numbers, which is exactly the state a first-time user meets. Step 1 has already written
// the overlay PNG and the coordinates; what is missing is the mapping from slide pixels to
// overlay pixels and the hash of the grid on screen. Write those here, using the same
// canonical hash step 3 and the approval file use, so a correction made against this grid
// can be matched to it later.
def pngSize = { File file ->
    // IHDR is the first chunk of every PNG: 8 byte signature, 4 length, 4 type, then
    // width and height as big-endian 32 bit integers.
    try {
        byte[] head = new byte[24]
        int read = 0
        file.withInputStream { input -> read = input.read(head) }
        if (read < 24) return null
        int w = ((head[16] & 0xff) << 24) | ((head[17] & 0xff) << 16) |
                ((head[18] & 0xff) << 8) | (head[19] & 0xff)
        int h = ((head[20] & 0xff) << 24) | ((head[21] & 0xff) << 16) |
                ((head[22] & 0xff) << 8) | (head[23] & 0xff)
        return (w > 0 && h > 0) ? [w, h] : null
    } catch (Throwable ignored) { return null }
}
def writeGridGeometry = { ->
    try {
        def g = imageData.getHierarchy().getTMAGrid()
        if (g == null) return
        File overlay = new File(gridQcDir, "${imageStem}_grid_qc_latest.png")
        if (!overlay.isFile()) overlay = new File(gridQcDir, "${imageStem}_grid_qc.png")
        if (!overlay.isFile()) return
        def size = pngSize(overlay)
        if (size == null) return
        def slideServer = imageData.getServer()
        def rows = []
        int cols = g.getGridWidth()
        g.getTMACoreList().eachWithIndex { core, i ->
            def roi = core.getROI()
            rows << [index: i + 1, row: i.intdiv(cols) + 1, column: i % cols + 1,
                core: core.getName() ?: '', centerX: roi.getCentroidX(),
                centerY: roi.getCentroidY(),
                diameter: Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()),
                missing: core.isMissing()]
        }
        int present = rows.count { !it.missing }
        def payload = [schemaVersion: 1, status: 'GATE_GRID',
            image: imageName, gridWidth: cols, gridHeight: g.getGridHeight(),
            coreCount: rows.size(), present: present, missing: rows.size() - present,
            gridHash: hashText(canonicalGrid(g)),
            overlayPng: overlay.getName(),
            overviewWidth: size[0], overviewHeight: size[1],
            slideWidth: slideServer.getWidth(), slideHeight: slideServer.getHeight(),
            cores: rows]
        File target = new File(gridQcDir, "${imageStem}_grid_geometry.json")
        File temporary = new File(gridQcDir, ".${target.getName()}.tmp")
        temporary.setText(new Gson().toJson(payload) + '\n', 'UTF-8')
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (Throwable geometryError) {
        println "WARNING: could not write the grid geometry: ${geometryError.getMessage()}"
    }
}

while (!gridApproved) {
    writeGridGeometry()
    def gridForGate = imageData.getHierarchy().getTMAGrid()
    int gatePresent = gridForGate == null ? 0 :
        gridForGate.getTMACoreList().count { !it.isMissing() }
    int gateMissing = gridForGate == null ? 0 :
        gridForGate.getTMACoreList().size() - gatePresent
    System.setProperty('corealign.dashboard.gridReviewPending', 'true')
    System.setProperty('corealign.dashboard.gate', 'grid')
    System.setProperty('corealign.dashboard.gatePresent', gatePresent.toString())
    System.setProperty('corealign.dashboard.gateMissing', gateMissing.toString())
    writeProjectIndex(null)
    String gridDecision = awaitReviewGate('grid', 'Check the detected cores',
        "${gatePresent} cores found, ${gateMissing} positions empty",
        'Look at the grid image in the report. If a core was missed, draw an ' +
        'ellipse over it in QuPath and name it "TMA correction", then continue.',
        'Grid is correct')
    System.clearProperty('corealign.dashboard.gate')
    if (gridDecision != 'continue') {
        writeProjectIndex(null)
        println 'Pipeline stopped safely: the grid review was not completed.'
        return
    }
    System.clearProperty('tma.review.status')
    System.setProperty('corealign.gate.gridApproved', 'true')
    try { runWorkflowScript(step3) }
    finally { System.clearProperty('corealign.gate.gridApproved') }
    String reviewStatus = System.getProperty('tma.review.status', '')
    if (reviewStatus == 'APPROVED') {
        gridApproved = true
    } else if (reviewStatus == 'CORRECTION_REVIEW_REQUIRED') {
        println 'Corrections applied; showing the refreshed grid for a second look.'
    } else {
        writeProjectIndex(null)
        println "Pipeline stopped safely: the current grid was not approved (${reviewStatus})."
        return
    }
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
File reviewCorrectionsFile = new File(workflowDir, 'corealign-review-corrections.json')

// Whether the rotated cores still need a person to look at them.
//
// This used to be "did this run process any core", which is wrong for the normal way
// Studio is used: press Start, review, press Start again. The second run resumes every
// core from its checkpoint, so nothing is processed, the gate never opened, and step 5
// then refused because a headless run has no other way to ask. The run stopped with
// results on disk and no way to approve them. Ask the same question step 5 asks instead:
// is there a human approval for this exact grid already.
boolean orientationAlreadyApproved = false
File finalApprovalFile = new File(stateDir, 'final_orientation_approval.json')
if (finalApprovalFile.isFile()) {
    try {
        def existingFinal = new Gson().fromJson(finalApprovalFile.getText('UTF-8'), Map.class)
        String approvedGridHash = savedApproval?.gridHash?.toString() ?: ''
        orientationAlreadyApproved = existingFinal != null &&
            existingFinal.status == 'APPROVED' &&
            existingFinal.approvalMode == 'human' &&
            !approvedGridHash.isEmpty() &&
            existingFinal.gridHash?.toString() == approvedGridHash
    } catch (Throwable ignored) { orientationAlreadyApproved = false }
}
if (orientationAlreadyApproved && processedThisRun == 0)
    println 'The rotated cores were already approved by a person; not asking again.'

// Gate 2: the rotated cores.
//
// The loop lets a reviewer fix angles and see the corrected crops without
// leaving the browser. Saving an angle writes the corrections file through the
// same loopback bridge; CoreAlign notices the newer file, reprocesses only the
// cores that changed, and opens the gate again on the updated result.
if (processedThisRun > 0 || !orientationAlreadyApproved) {
    long correctionsStamp = reviewCorrectionsFile.isFile() ?
        reviewCorrectionsFile.lastModified() : 0L
    while (true) {
        String total = System.getProperty('tma.orientation.totalCount', '0')
        String ok = System.getProperty('tma.orientation.okCount', '0')
        String review = System.getProperty('tma.orientation.reviewCount', '0')
        String missing = System.getProperty('tma.orientation.missingCount', '0')
        String elapsed = System.getProperty('tma.orientation.elapsed', 'unknown')
        println "=== ORIENTATION REVIEW: ${total} positions, ${ok} passed automatic QC, " +
            "${review} flagged, ${missing} missing, ${elapsed} ==="
        System.setProperty('corealign.dashboard.gate', 'orientation')
        writeProjectIndex(new File(System.getProperty('tma.orientation.runDir', '')))
        String orientationDecision = awaitReviewGate('orientation',
            'Check the rotated cores',
            "${ok} passed automatic QC, ${review} need a look",
            'Confirm the cores that look right. For a wrong one use Edit, set the ' +
            'angle, and Update. Then approve to create the result files.',
            'Approve and finish')
        System.clearProperty('corealign.dashboard.gate')
        if (orientationDecision != 'continue') {
            println 'Pipeline stopped safely: the orientation review was not completed.'
            return
        }
        long updatedStamp = reviewCorrectionsFile.isFile() ?
            reviewCorrectionsFile.lastModified() : 0L
        if (updatedStamp <= correctionsStamp) break
        correctionsStamp = updatedStamp
        println 'Angle corrections were saved; reprocessing only the cores that changed.'
        System.clearProperty('tma.orientation.processedThisRun')
        System.clearProperty('tma.orientation.exportOnlyThisRun')
        runWorkflowScript(step2)
        if (System.getProperty('tma.orientation.status', '') != 'COMPLETE') return
        publishCurrentRun()
    }
}
if (processedThisRun == 0 && exportOnlyThisRun > 0) {
    println "Research-package upgrade reused all accepted core transforms; ${exportOnlyThisRun} core export(s) were added without redetection or reorientation."
    Dialogs.showInfoNotification('CoreAlign research package updated',
        "Added requested research files for ${exportOnlyThisRun} core(s). Detection, rotation and crop checkpoints were reused.")
}

System.clearProperty('tma.final.status')
if (processedThisRun > 0 || !orientationAlreadyApproved)
    System.setProperty('corealign.gate.finalApproved', 'true')
try { runWorkflowScript(step5) }
finally { System.clearProperty('corealign.gate.finalApproved') }
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
    // The report is the result. Open it and say one thing, rather than
    // restating the run in a dialog the reviewer has to read and dismiss.
    openReportInBrowser(true)
    String finishedLine = analysisProjectStatus == 'READY' ?
        'Images, tables, and an ordered QuPath project are ready. Open the project ' +
            'with File > Project > Open project.' :
        'Your images and tables are ready. To add multichannel OME-TIFF files and a ' +
            'QuPath project later, switch Results to Research in the report and run ' +
            'CoreAlign once more. Nothing is processed twice.'
    Dialogs.showInfoNotification('CoreAlign finished',
        "${finishedLine} The report is open in your browser.")
    println "Results: ${completionDashboardFile.getAbsolutePath()}"
}
