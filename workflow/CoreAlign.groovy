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
boolean reportOnly = Boolean.parseBoolean(System.getProperty('corealign.reportOnly', 'false'))
def existingGridForPreflight = imageData.getHierarchy().getTMAGrid()
boolean needsDetectionPreflight = existingGridForPreflight == null ||
    existingGridForPreflight.getTMACoreList().isEmpty() ||
    !existingGridForPreflight.getTMACoreList().any { !it.isMissing() }
// The two review gates below are the only stops in a run. Everything the old
// multi-run flow explained in a summary dialog before each pass is now either
// automatic or shown in context on REPORT.html, next to the image it describes.

def step1 = new EmbeddedWorkflowScript(name: '01_build_tma_grid.groovy', payload: '''
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
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
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
be+/rvRcln0EAgA=
''')
def step3 = new EmbeddedWorkflowScript(name: '03_review_correct_and_approve_grid.groovy', payload: '''
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
EtbLvgAA
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
        file.withInputStream { input -> if (input.read(head) < 24) return null }
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
        def server = imageData.getServer()
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
            slideWidth: server.getWidth(), slideHeight: server.getHeight(),
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
