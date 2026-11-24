package dev.tinkoprof.nofontshell.telemetry

import dev.tinkoprof.nofontshell.config.NoFontShellConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

data class BlockedFontEvent(
    val timestamp: Instant,
    val stage: String,
    val source: String,
    val packId: String,
    val resourceId: String,
    val action: NoFontShellConfig.Action,
    val report: ThreatReport
) {
    fun formattedTime(): String = TIME_FMT.format(timestamp)

    companion object {
        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}

object NoFontShellStats {
    private const val MAX_HISTORY = 50

    private val packsScanned = AtomicInteger(0)
    private val fontsScanned = AtomicInteger(0)
    private val fontsBlocked = AtomicInteger(0)
    private val packsBlocked = AtomicInteger(0)
    private val events = ConcurrentLinkedDeque<BlockedFontEvent>()

    @JvmStatic
    fun recordPackScanned() {
        packsScanned.incrementAndGet()
    }

    @JvmStatic
    fun recordFontScanned() {
        fontsScanned.incrementAndGet()
    }

    @JvmStatic
    fun recordPackBlocked(
        stage: String,
        source: String,
        packId: String,
        report: ThreatReport
    ) {
        packsBlocked.incrementAndGet()
        pushEvent(
            BlockedFontEvent(
                timestamp = Instant.now(),
                stage = stage,
                source = source,
                packId = packId,
                resourceId = "<pack>",
                action = NoFontShellConfig.Action.BLOCK_PACK,
                report = report
            )
        )
    }

    @JvmStatic
    fun recordFontFlagged(
        stage: String,
        source: String,
        packId: String,
        resourceId: String,
        action: NoFontShellConfig.Action,
        report: ThreatReport
    ) {
        if (action != NoFontShellConfig.Action.LOG_ONLY) {
            fontsBlocked.incrementAndGet()
        }
        pushEvent(
            BlockedFontEvent(
                timestamp = Instant.now(),
                stage = stage,
                source = source,
                packId = packId,
                resourceId = resourceId,
                action = action,
                report = report
            )
        )
    }

    private fun pushEvent(event: BlockedFontEvent) {
        events.addFirst(event)
        while (events.size > MAX_HISTORY) {
            events.pollLast()
        }
    }

    @JvmStatic
    fun scannedPacks(): Int = packsScanned.get()

    @JvmStatic
    fun scannedFonts(): Int = fontsScanned.get()

    @JvmStatic
    fun blockedFonts(): Int = fontsBlocked.get()

    @JvmStatic
    fun blockedPacks(): Int = packsBlocked.get()

    @JvmStatic
    fun recentEvents(): List<BlockedFontEvent> = events.toList()
}
