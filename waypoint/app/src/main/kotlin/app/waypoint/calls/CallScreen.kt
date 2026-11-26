package app.waypoint.calls

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import app.waypoint.MainActivity
import app.waypoint.graph

/** Where call audio goes, as the output button shows it. */
enum class AudioOutput(val label: String) {
    BLUETOOTH("Bluetooth"),
    EARPIECE("Phone"),
    SPEAKER("Speaker"),
    WIRED("Headphones"),
    OTHER("Output"),
    ;

    companion object {
        fun of(type: Int): AudioOutput = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> BLUETOOTH
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> WIRED
            else -> OTHER
        }
    }
}

/** One element of a screen, as an accessibility service sees it. */
data class ScreenNode(
    val className: String?,
    val id: String?,
    val desc: String?,
    val text: String?,
    val clickable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val bounds: String? = null,
    val children: List<ScreenNode> = emptyList(),
    /** The live element to press (an AccessibilityNodeInfo); null in tests. */
    val ref: Any? = null,
)

/**
 * Writes a screen out for the audio output report, masked: digits are dots, the caller's name and
 * Bluetooth device names ([private]) are cut to initials, and text that isn't on something you
 * can tap (names, the call timer) is cut to its first letter. Button labels stay readable.
 */
object CallScreenFormat {
    const val MAX_LINES = 160

    fun mask(s: String, private: Collection<String>): String {
        var out = s
        for (word in private.flatMap { it.split(Regex("\\s+")) }.filter { it.length >= 2 }.distinct()) {
            out = out.replace(Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE), word.take(1) + "…")
        }
        return out.replace(Regex("\\d"), "•")
    }

    fun initials(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { it.take(1) + "…" }

    /** The elements worth naming (tappable, or with an id, label or text), indented by depth. */
    fun lines(root: ScreenNode, private: Collection<String>): List<String> = buildList {
        fun walk(n: ScreenNode, depth: Int) {
            if (size >= MAX_LINES) return
            val shown = n.clickable || n.id != null || !n.desc.isNullOrBlank() || !n.text.isNullOrBlank()
            if (shown) {
                val text = n.text?.takeIf { it.isNotBlank() }?.let { if (n.clickable) mask(it, private) else mask(it.take(1), private) + "…" }
                add(
                    "  ".repeat(depth) + listOfNotNull(
                        n.className?.substringAfterLast('.') ?: "?",
                        n.id?.let { "#" + it.substringAfter(":id/") },
                        n.desc?.takeIf { it.isNotBlank() }?.let { "desc=\"${mask(it, private)}\"" },
                        text?.let { "text=\"$it\"" },
                        "TAPPABLE".takeIf { n.clickable },
                        "checked".takeIf { n.checked },
                        "selected".takeIf { n.selected },
                        n.bounds,
                    ).joinToString(" "),
                )
            }
            n.children.forEach { walk(it, if (shown) depth + 1 else depth) }
        }
        walk(root, 0)
        if (size >= MAX_LINES) add("… (cut at $MAX_LINES lines)")
    }
}

/**
 * Records WhatsApp's call screen while Waypoint switches the call's audio (what's on it, what's
 * pressed, where the audio goes), for the report shown when a switch fails. Nothing is recorded
 * outside a switch.
 */
object CallScreenCapture {
    private const val SESSION_MS = 60_000L
    private const val MAX_SCREENS = 10

    @Volatile private var until = 0L
    private var started = 0L
    private var private: List<String> = emptyList()
    private val screens = mutableListOf<Pair<String, String>>() // label to body
    private val events = mutableListOf<String>()
    private val counts = mutableMapOf<String, Int>()

    @Volatile var connected = false
        internal set

    val active: Boolean get() = SystemClock.elapsedRealtime() < until

    @Synchronized
    fun start(private: List<String>) {
        this.private = private
        started = SystemClock.elapsedRealtime()
        until = started + SESSION_MS
        screens.clear()
        events.clear()
        counts.clear()
    }

    @Synchronized
    fun stop() {
        until = 0L
    }

    private fun at() = "+%.1fs".format((SystemClock.elapsedRealtime() - started) / 1000.0)

    @Synchronized
    internal fun counted(type: String) {
        counts[type] = (counts[type] ?: 0) + 1
    }

    @Synchronized
    internal fun screen(label: String, root: ScreenNode) {
        val body = CallScreenFormat.lines(root, private).joinToString("\n")
        if (screens.any { it.second == body }) return
        if (screens.size >= MAX_SCREENS) return
        screens += "${at()} ${CallScreenFormat.mask(label, private)}" to body
    }

    /** [text] is masked; [plain] (positions, already-masked device lists) isn't. */
    @Synchronized
    internal fun note(text: String, plain: String = "") {
        events += "${at()} ${CallScreenFormat.mask(text, private)}$plain"
    }

    @Synchronized
    fun report(header: String): String = buildString {
        appendLine(header)
        appendLine("Reader: ${if (connected) "on" else "OFF"}; events from WhatsApp: " + counts.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" })
        appendLine()
        appendLine("What happened (taps in WhatsApp, output changes):")
        if (events.isEmpty()) appendLine("  nothing")
        events.forEach { appendLine("  $it") }
        appendLine()
        appendLine("WhatsApp screens seen: ${screens.size}")
        screens.forEachIndexed { i, (label, body) ->
            appendLine()
            appendLine("[${i + 1}] $label")
            appendLine(body)
        }
    }
}

/**
 * Presses WhatsApp's output button to move the call's audio ([OutputSwitch], decided by
 * [WhatsAppOutput]), then brings the map back. It only hears WhatsApp (see
 * res/xml/whatsapp_call_screen.xml), and only acts and records during a switch.
 */
class WhatsAppCallScreen : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val snapshot = Runnable { snapshotNow() }
    private val step = Runnable { switchStep() }

    // The switch in progress.
    private var requestId = 0L
    private var presses = 0
    private var outputAtPress: AudioOutput? = null
    private var lastPressMs = 0L

    override fun onServiceConnected() {
        CallScreenCapture.connected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        CallScreenCapture.connected = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() !in CallController.APPS.keys) return
        if (OutputSwitch.request != null) scheduleStep(250)
        if (!CallScreenCapture.active) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                CallScreenCapture.counted("window")
                CallScreenCapture.note("WhatsApp showed ${event.className}")
                schedule(300)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                CallScreenCapture.counted("change")
                schedule(600)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                CallScreenCapture.counted("tap")
                val source = event.source?.let { node(it, 0, intArrayOf(0), deep = false) }
                CallScreenCapture.note(
                    "Tapped: " + listOfNotNull(
                        (source?.className ?: event.className?.toString())?.substringAfterLast('.'),
                        source?.id?.let { "#" + it.substringAfter(":id/") },
                        (source?.desc ?: event.contentDescription?.toString())?.let { "desc=\"$it\"" },
                        event.text.joinToString(" ").takeIf { it.isNotBlank() }?.let { "text=\"$it\"" },
                    ).joinToString(" "),
                    source?.bounds?.let { " $it" }.orEmpty(),
                )
                schedule(500)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // A toast (notifications come as Notification objects): WhatsApp's messages.
                if (event.parcelableData !is android.app.Notification) {
                    CallScreenCapture.counted("message")
                    CallScreenCapture.note("WhatsApp said: \"${event.text.joinToString(" ")}\"")
                }
            }
            else -> Unit
        }
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(snapshot)
        main.postDelayed(snapshot, delayMs)
    }

    /** Every WhatsApp window on screen (the call screen, and any panel or dialog over it). */
    private fun whatsAppWindows(): List<Pair<String, AccessibilityNodeInfo>> = buildList {
        runCatching { windows }.getOrDefault(emptyList()).forEach { w ->
            w.root?.let { add("window \"${w.title ?: ""}\" type ${w.type}" to it) }
        }
        if (isEmpty()) rootInActiveWindow?.let { add("active window" to it) }
    }.filter { it.second.packageName?.toString() in CallController.APPS.keys }

    private fun snapshotNow() {
        if (!CallScreenCapture.active) return
        whatsAppWindows().forEach { (label, root) -> CallScreenCapture.screen(label, node(root, 0, intArrayOf(0), deep = true)) }
    }

    private fun scheduleStep(delayMs: Long) {
        main.removeCallbacks(step)
        main.postDelayed(step, delayMs)
    }

    /** One step of the switch: done, wait for WhatsApp, press something, or give up. */
    private fun switchStep() {
        val request = OutputSwitch.request ?: return
        if (request.id != requestId) {
            requestId = request.id
            presses = 0
            outputAtPress = null
        }
        val calls = applicationContext.graph.calls
        val outputs = calls.outputs
        val now = SystemClock.elapsedRealtime()
        val current = outputs.current()
        if (now - request.startedMs > OutputSwitch.TIMEOUT_MS) {
            end(request, reached = current == request.target, why = "WhatsApp didn't switch to ${request.target.label} in time")
            return
        }
        // A press takes a moment to move the audio.
        if (presses > 0 && current == outputAtPress && current != request.target && now - lastPressMs < PRESS_SETTLE_MS) {
            scheduleStep(150)
            return
        }
        val screens = whatsAppWindows().map { node(it.second, 0, intArrayOf(0), deep = true) }
        val next = WhatsAppOutput.next(
            screens, request.target, current, outputs.available(), presses,
            lastPressMovedAudio = presses > 0 && current != outputAtPress,
            deviceNames = outputs.bluetoothNames(),
        )
        when (next) {
            WhatsAppOutput.Step.Done -> end(request, reached = true, why = "Now on ${request.target.label}")
            WhatsAppOutput.Step.Wait -> scheduleStep(250)
            is WhatsAppOutput.Step.GiveUp -> end(request, reached = false, why = next.why)
            is WhatsAppOutput.Step.Press -> {
                val pressed = (next.node.ref as? AccessibilityNodeInfo)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                CallScreenCapture.note(
                    "Waypoint pressed ${next.why}: " + listOfNotNull(
                        next.node.id?.let { "#" + it.substringAfter(":id/") },
                        next.node.desc?.let { "desc=\"$it\"" },
                        next.node.text?.let { "text=\"$it\"" },
                    ).joinToString(" ") + if (pressed) "" else " (WhatsApp didn't take the press)",
                )
                if (!pressed) {
                    end(request, reached = false, why = "WhatsApp's ${next.why} didn't take the press")
                    return
                }
                presses++
                outputAtPress = current
                lastPressMs = now
                scheduleStep(PRESS_SETTLE_MS / 2)
            }
        }
    }

    private fun end(request: OutputSwitch.Request, reached: Boolean, why: String) {
        main.removeCallbacks(step)
        if (applicationContext.graph.calls.finishOutputSwitch(request.id, reached, why)) {
            // Back to the map (a running accessibility service may open its app's screen).
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun node(n: AccessibilityNodeInfo, depth: Int, count: IntArray, deep: Boolean): ScreenNode {
        count[0]++
        val r = Rect().also(n::getBoundsInScreen)
        val children = if (!deep || depth > 30) emptyList() else (0 until n.childCount).mapNotNull { i ->
            if (count[0] > 600) null else n.getChild(i)?.let { node(it, depth + 1, count, deep) }
        }
        return ScreenNode(
            className = n.className?.toString(),
            id = n.viewIdResourceName,
            desc = n.contentDescription?.toString(),
            text = n.text?.toString(),
            clickable = n.isClickable,
            checked = @Suppress("DEPRECATION") n.isChecked,
            selected = n.isSelected,
            bounds = "[${r.left},${r.top}][${r.right},${r.bottom}]",
            children = children,
            ref = n,
        )
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val PRESS_SETTLE_MS = 900L

        fun isOn(context: Context): Boolean {
            val me = ComponentName(context, WhatsAppCallScreen::class.java)
            return context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { ComponentName.unflattenFromString(it.id) == me }
        }
    }
}

/** The call's audio output now, and the outputs there are, for the button and the report. */
class AudioOutputs(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun current(): AudioOutput? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.communicationDevice?.let { AudioOutput.of(it.type) } else null

    /** The outputs the call can use now. */
    fun available(): Set<AudioOutput> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.availableCommunicationDevices.map { AudioOutput.of(it.type) }.toSet() else emptySet()

    /** The names of the Bluetooth devices the call can use (to find them in WhatsApp's list). */
    fun bluetoothNames(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audio.availableCommunicationDevices.filter { AudioOutput.of(it.type) == AudioOutput.BLUETOOTH }.map { it.productName.toString() }
    } else {
        emptyList()
    }

    /** Bluetooth device names, kept out of the report. */
    fun deviceNames(): List<String> =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.productName.toString() }.filter { it.isNotBlank() }

    fun describe(): String = buildString {
        append("mode=${modeName(audio.mode)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            append(", output=${audio.communicationDevice?.let(::device) ?: "none"}")
            append(", available: ${audio.availableCommunicationDevices.joinToString { device(it) }}")
        }
    }

    private fun device(d: AudioDeviceInfo) = "${AudioOutput.of(d.type).label} (type ${d.type}, \"${CallScreenFormat.initials(d.productName.toString())}\")"

    /** Notes each output change into the capture, while it runs. */
    fun watch(onChange: (AudioOutput?) -> Unit): () -> Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return {}
        val listener = AudioManager.OnCommunicationDeviceChangedListener { onChange(it?.let { d -> AudioOutput.of(d.type) }) }
        audio.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), listener)
        return { audio.removeOnCommunicationDeviceChangedListener(listener) }
    }

    private fun modeName(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> mode.toString()
    }
}
