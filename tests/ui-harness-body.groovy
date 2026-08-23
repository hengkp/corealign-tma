import javafx.embed.swing.SwingFXUtils
import javafx.stage.Window
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch

String OUT = System.getProperty('harness.out', '/tmp')
List<String> failures = []
def snapshot = { Window window, String path ->
    try {
        def image = window.getScene().snapshot(null)
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), 'png', new File(path))
        println "SNAPSHOT ${path}"
    } catch (Throwable e) { failures << "snapshot failed: ${e.getMessage()}" }
}

CountDownLatch started = new CountDownLatch(1)
Platform.startup({ started.countDown() })
started.await()
Platform.setImplicitExit(false)

// ---- 1. the setup dialog ----------------------------------------------------
Thread dialogThread = Thread.start {
    Map choice = CoreAlignSetup.show([headline: 'Find the cores on this slide',
        detail: 'CoreAlign measures the array, the core size, and every core position, ' +
            'then shows you the result to check.',
        primaryLabel: 'Start', tissue: 'skin', output: 'presentation'])
    println "SETUP_RESULT: ${choice}"
    if (choice.start != true) failures << 'setup did not return start'
    if (choice.tissue != 'other') failures << "tissue toggle not applied: ${choice.tissue}"
    if (choice.output != 'presentation') failures << "output lost: ${choice.output}"
}
Thread.sleep(2000)
CountDownLatch dialogDone = new CountDownLatch(1)
Platform.runLater({
    try {
        def window = Window.getWindows().find { it.getScene()?.getRoot()?.lookup('.dialog-pane') != null }
        if (window == null) { failures << 'setup dialog not showing'; return }
        snapshot(window, "${OUT}/setup-dialog.png")
        def root = window.getScene().getRoot()
        // flip the tissue toggle, to prove the segmented control reports its value
        def other = root.lookupAll('.toggle-button').find { it.getText() == 'Other tissue' }
        if (other == null) { failures << 'no tissue toggle'; return }
        other.setSelected(true)
        snapshot(window, "${OUT}/setup-dialog-other.png")
        def start = root.lookupAll('.button').find { it.getText() == 'Start' }
        if (start == null) { failures << 'no Start button'; return }
        start.fire()
    } finally { dialogDone.countDown() }
})
dialogDone.await()
dialogThread.join(5000)

// ---- 2. the gate window -----------------------------------------------------
CoreAlignGateWindow.open('grid', 'Check the detected cores',
    '117 cores found, 9 positions empty',
    'Look at the grid image in the report. If a core was missed, draw an ellipse over ' +
    'it in QuPath and name it "TMA correction", then continue.',
    'Grid is correct', { -> println 'OPEN_REPORT_CLICKED' })
Thread.sleep(1500)
CountDownLatch gateDone = new CountDownLatch(1)
Platform.runLater({
    try {
        def gate = Window.getWindows().find { it instanceof Stage && ((Stage) it).getTitle() == 'CoreAlign' }
        if (gate == null) { failures << 'gate window not showing'; return }
        snapshot(gate, "${OUT}/gate-window.png")
        def button = gate.getScene().getRoot().lookupAll('.button').find { it.getText() == 'Grid is correct' }
        if (button == null) { failures << 'no continue button'; return }
        button.fire()
    } finally { gateDone.countDown() }
})
gateDone.await()
Thread.sleep(300)
String taken = CoreAlignGateWindow.take()
println "GATE_DECISION: ${taken}"
if (taken != 'continue') failures << "gate decision not delivered: '${taken}'"
if (!CoreAlignGateWindow.take().isEmpty()) failures << 'gate decision delivered twice'
CoreAlignGateWindow.close()
Thread.sleep(300)

if (failures.isEmpty()) println 'COREALIGN_UI_HARNESS_PASSED'
else println "COREALIGN_UI_HARNESS_FAILED: ${failures}"
Platform.exit()
