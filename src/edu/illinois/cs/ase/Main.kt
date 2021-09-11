package edu.illinois.cs.ase

import com.google.common.util.concurrent.SimpleTimeLimiter
import com.google.gson.*
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonWriter
import java.io.*
import java.math.BigInteger
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

object Main {
    private const val CACHE_SIZE = 100
    private const val SCREEN_INTERVAL = 50

    private val ENABLE_MINICAP = System.getenv("SKIP_MINICAP") == null
    private val CTRL_PORT = System.getenv("CTRL_PORT")?.toInt() ?: 55555
    private val MINICAP_PORT = System.getenv("MINICAP_PORT")?.toInt() ?: 1313
    private val XPATHS_TO_KILL = System.getenv("XPATH_BLKLST")?.let { parseAsJsonArray(it) }
    private val VIEW_XPATHS_TO_KILL = XPATHS_TO_KILL?.filterIsInstance<JsonArray>()
    private val SCREEN_HASHES_TO_KILL = XPATHS_TO_KILL?.filterIsInstance<JsonPrimitive>()?.filter { it.isString }
    private val RETAIN_CRASH_HANDLER = System.getenv("RETAIN_CRASH_HANDLER") == "1"
    private val APP_RESTART_COMMAND = System.getenv("APP_RESTART_COMMAND")
    private val DEV_RESTART_COMMAND = System.getenv("DEV_RESTART_COMMAND")
    private val WATCHDOG_INTV = System.getenv("WATCHDOG_INTV")?.toLong()
    private val DONT_KILL_AFTER = System.getenv("DONT_KILL_AFTER")?.toLong()

    private var posCacheLast = 0
    private val screenHistory = Array(CACHE_SIZE) { Screenshot() }
    private val tsStart = System.currentTimeMillis()

    private var outputDir: File? = null
    private var screenOutputDir: File? = null
    private var inMinicap: InputStream? = null
    private var inController: InputStream? = null
    private var outController: OutputStream? = null

    private fun printConfig() {
        println(">>>>> CURRENT CONFIG BEGIN <<<<<")
        println("tsStart = $tsStart")
        println("ENABLE_MINICAP = $ENABLE_MINICAP")
        println("CTRL_PORT = $CTRL_PORT")
        println("MINICAP_PORT = $MINICAP_PORT")
        println("XPATHS_TO_KILL = $XPATHS_TO_KILL")
        println("VIEW_XPATHS_TO_KILL = $VIEW_XPATHS_TO_KILL")
        println("SCREEN_HASHES_TO_KILL = $SCREEN_HASHES_TO_KILL")
        println("RETAIN_CRASH_HANDLER = $RETAIN_CRASH_HANDLER")
        println("APP_RESTART_COMMAND = $APP_RESTART_COMMAND")
        println("DEV_RESTART_COMMAND = $DEV_RESTART_COMMAND")
        println("WATCHDOG_INTV = $WATCHDOG_INTV")
        println("DONT_KILL_AFTER = $DONT_KILL_AFTER")
        println("outputDir = $outputDir")
        println("screenOutputDir = $screenOutputDir")
        println(">>>>> CURRENT CONFIG END <<<<<")
    }

    @Suppress("UnstableApiUsage")
    val timeLimiter: SimpleTimeLimiter = SimpleTimeLimiter.create(Executors.newCachedThreadPool())

    @Throws(IOException::class)
    private fun readBytes(inStream: InputStream, n: Int): ByteArray = ByteArray(n).also {
        var i = 0
        while (i < it.size) i += inStream.read(it, i, it.size - i)
    }

    private fun readUInt32FromQuadBytes(data: ByteArray, offset: Int): Long {
        var ret = 0L
        for (i in (offset + 3) downTo offset) ret = ret shl 8 or (data[i].toLong() and 0xFF)
        return ret
    }

    private fun initMiniCap(): InputStream {
        println("Starting a new connection to Minicap..")
        for (i in 1..10) {
            try {
                return Socket("127.0.0.1", MINICAP_PORT).getInputStream().also {
                    var b = it.read()  // version
                    if (b == -1) throw IOException()
                    println("Minicap version = $b")
                    b = it.read()  // banner length
                    if (b == -1) throw IOException()
                    println("Minicap banner length = $b")
                    readBytes(it, b - 2)
                    inMinicap = it
                }
            } catch (e: IOException) {
                System.err.println("Unable to connect to Minicap at port $MINICAP_PORT: ${e.message}")
                try {
                    Thread.sleep(1000)
                } catch (ex: InterruptedException) {
                }
            }
        }
        throw RuntimeException("Unable to connect to Minicap after multiple attempts. Give up.")
    }

    // Even when the on-device controller server is not running, new socket connections through `adb forward` will
    // NOT fail immediately: they will close right after establishment. To avoid flooding the system with frequent
    // new connections caused this way, we have to make sure that each re-connection happens after some time since
    // the previous one.
    private val lastInitControllerTime = AtomicLong()

    private fun initController(): Pair<InputStream, OutputStream> {
        inController?.apply {
            println("Closing existing connection to in-app controller..")
            try {
                close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        println("Starting a new connection to in-app controller..")
        for (i in 1..100) {  // Each retry happens after at least one second.
            val tsSleep = System.currentTimeMillis() - lastInitControllerTime.get() - 1000
            if (tsSleep < 0) try {
                Thread.sleep(-tsSleep)
            } catch (ex: InterruptedException) {
            }
            try {
                lastInitControllerTime.set(System.currentTimeMillis())
                return Socket("127.0.0.1", CTRL_PORT).run {
                    Pair(getInputStream().also { inController = it }, getOutputStream().also { outController = it })
                }
            } catch (e: IOException) {
                System.err.println("Unable to connect to in-app controller at port $CTRL_PORT: ${e.message}")
            }
        }
        throw RuntimeException("Unable to connect to in-app controller after multiple attempts. Give up.")
    }

    private val threadMinicap = Thread {
        var inMinicap: InputStream? = null
        while (!Thread.interrupted()) {
            if (inMinicap == null) inMinicap = initMiniCap()
            try {
                val bInfo = readBytes(inMinicap, 12)
                val size: Long = readUInt32FromQuadBytes(bInfo, 0)
                if (size > 0x7FFFFFFFL) {
                    System.err.println("Screenshot too large: $size")
                    exitProcess(1)
                }
                val screen = readBytes(inMinicap, size.toInt())
                var ts: Long = (readUInt32FromQuadBytes(bInfo, 8) shl 32) + readUInt32FromQuadBytes(bInfo, 4)
                // System.err.println("Screen $ts, size = $size");
                ts /= SCREEN_INTERVAL  // At most one frame per SCREEN_INTERVAL millisecond(s)
                synchronized(screenHistory) {
                    if (screenHistory[posCacheLast].timestamp < ts) {
                        posCacheLast = (posCacheLast + 1) % CACHE_SIZE
                        screenHistory[posCacheLast].apply {
                            timestamp = ts
                            data = screen
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                println("Closing existing connection to Minicap..")
                try {
                    inMinicap.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
                inMinicap = null
            }
        }
    }

    private fun addIsSource(screen: JsonObject?, target: Int): Boolean {
        if ((screen?.get("hash") ?: return false).asInt == target) {
            screen.addProperty("is_source", true)
            println("$target @ ${screen["bound"].asString}")
            return true
        }
        for (ch in screen["ch"]?.asJsonArray ?: return false)
            if (addIsSource(ch.asJsonObject, target)) return true
        return false
    }

    private fun parseAsJsonArray(line: String): JsonArray? =
            try {
                JsonParser.parseString(line).asJsonArray
            } catch (e: Exception) {  // can be caused by abrupt app stops
                System.err.println("Unable to parse JSON string: $line")
                null
            }

    private fun removeEmptyChildren(root: JsonObject): JsonObject {
        val chs = root.get("ch") ?: return root
        if (chs !is JsonArray) return root
        val f = JsonArray()
        for (ch in chs) if (ch is JsonObject && ch.has("hash")) f.add(removeEmptyChildren(ch))
        root.add("ch", f)
        return root
    }

    private fun gatherChildAttrs(root: JsonElement, attrName: String): Set<JsonElement> {
        val ret = HashSet<JsonElement>()
        if (root !is JsonObject) return ret
        root[attrName]?.also { ret.add(it) }
        val chs = root["ch"]
        if (chs is JsonArray) chs.flatMapTo(ret) { gatherChildAttrs(it, attrName) }
        return ret
    }

    private fun validateAttrs(target: JsonObject, ref: JsonObject): Boolean {
        for ((p, v) in ref.entrySet()) {
            if ("_pos" == p) continue
            if (p.startsWith("ch_")) {
                val gathered = gatherChildAttrs(target, p.substring(3))
                // System.err.println(gathered);
                for (check in v.asJsonArray)
                    if (!gathered.contains(check)) return false
                continue
            }
            if (target[p] != v) return false
        }
        return true
    }

    // See `hash_layout()` from `get-source-xpath.py` for reference.
    private fun hashLayout(layout: JsonObject): String? {
        for (field in listOf("bound", "act_id")) if (!layout.has(field)) return null
        if (layout["act_id"].asString == "unknown" && layout["focus"]?.asBoolean != true) return null
        val rootBound = parsePos(layout["bound"].asString) ?: return null
        val checkChildBounds = rootBound.height > 0 && rootBound.width > 0
        // if (screen.has("vis")) screen.remove("vis")

        fun traverse(layout: JsonElement, pool: ArrayList<String>) {
            if (layout !is JsonObject) return
            if (layout.has("vis") && layout["vis"].asInt != 0) return
            val boundString = layout["bound"]?.asString ?: return
            if (checkChildBounds) {
                val bound = parsePos(boundString) ?: return
                // Require the child to intersect with parent
                if (bound.top >= rootBound.top + rootBound.height
                        || bound.left >= rootBound.left + rootBound.width
                        || rootBound.top >= bound.top + bound.height
                        || rootBound.left >= bound.left + bound.width) return
            }
            pool.apply {
                add("[")
                add(layout["id"]?.asString ?: "-1")
                add(layout["class"].asString)
                layout["ch"]?.asJsonArray?.forEach { traverse(it, this) }
                add("]")
            }
        }

        val pool = arrayListOf(layout["act_id"]?.asString ?: "?")
        traverse(layout, pool)
        return pool.joinToString("").md5()
    }

    data class Rect(val top: Int, val left: Int, val height: Int, val width: Int)

    // [0,0][9,9]
    private fun parsePos(p: String): Rect? {
        if (p.length < 10 || p.first() != '[' || p.last() != ']') return null
        try {
            val pos1 = p.indexOf(',')
            if (pos1 < 2) return null
            val left = p.substring(1, pos1).toInt()
            val pos2 = p.indexOf(']', pos1 + 1)
            if (pos2 < 4 || p[pos2 + 1] != '[') return null
            val top = p.substring(pos1 + 1, pos2).toInt()
            val pos3 = p.indexOf(',', pos2 + 2)
            if (pos3 < 7) return null
            val right = p.substring(pos2 + 2, pos3).toInt()
            val bottom = p.substring(pos3 + 1, p.length - 1).toInt()
            return Rect(top, left, bottom - top, right - left)
        } catch (e: Exception) {
            return null
        }
    }

    private fun String.md5(): String =
            BigInteger(1, MessageDigest.getInstance("MD5").digest(toByteArray()))
                    .toString(16).padStart(32, '0')

    private fun findViewsToKill(screen: JsonElement): List<Int> {
        val views = ArrayList<Int>()
        if (XPATHS_TO_KILL?.isEmpty() != false) return views
        if (screen !is JsonObject) return views
        val prunedScreen = removeEmptyChildren(screen)
        when {
            XPATHS_TO_KILL[0] is JsonObject -> findViewToKill(prunedScreen, XPATHS_TO_KILL)?.also { views.add(it) }
            SCREEN_HASHES_TO_KILL?.any { hashLayout(prunedScreen) == it.asString } == true -> views.add(-1)
            else -> VIEW_XPATHS_TO_KILL?.mapNotNullTo(views) { findViewToKill(prunedScreen, it) }
        }
        return views
    }

    private fun findViewToKill(screen: JsonElement, xpath: JsonArray): Int? {
        if (screen !is JsonObject) return null
        val attrs = xpath[0]
        if (attrs !is JsonObject) return null
        return if (validateAttrs(screen, attrs)) findViewToKill(screen, xpath, 1) else null
    }

    private fun findViewToKill(elem: JsonObject, xpath: JsonArray, xpos: Int): Int? {
        if (xpath.size() <= xpos) return if (elem["en"]?.asBoolean == true) elem["hash"].asInt else null
        val attrs = xpath[xpos]
        if (attrs !is JsonObject) return null
        val xChildPos = attrs["_pos"]?.asInt
        val chs = elem.getAsJsonArray("ch")?.filterIsInstance<JsonObject>() ?: return null
        if (xChildPos == null) {
            for (ch in chs)
                if (validateAttrs(ch, attrs)) return findViewToKill(ch, xpath, xpos + 1)
        } else {
            if (xChildPos < chs.size && validateAttrs(chs[xChildPos], attrs))
                return findViewToKill(chs[xChildPos], xpath, xpos + 1)
        }
        return null
    }

    private fun restartApp() {
        APP_RESTART_COMMAND ?: return
        println("Restarting the app")
        runCommand(arrayOf("sh", "-c", APP_RESTART_COMMAND))
    }

    private val tsLastAction = AtomicLong()
    private fun updateLastActionTime() {
        tsLastAction.set(System.currentTimeMillis())
    }

    private val threadWatchDog = Thread {
        WATCHDOG_INTV ?: return@Thread
        updateLastActionTime()
        var ctCont = 0
        while (!Thread.interrupted()) {
            try {
                Thread.sleep(WATCHDOG_INTV)
            } catch (unused: InterruptedException) {
                break
            }
            if (System.currentTimeMillis() - tsLastAction.get() >= WATCHDOG_INTV) {
                if (DEV_RESTART_COMMAND != null && ++ctCont == 3) {
                    println("Watchdog: device seems dead.. Restarting now..")
                    runCommand(arrayOf("sh", "-c", DEV_RESTART_COMMAND), 90)
                    ctCont = 0
                } else {
                    println("Watchdog: app and/or tool seem dead..")
                    restartApp()
                }
            } else {
                ctCont = 0
            }
        }
    }

    private val timerAutoCapture = Timer()
    private val threadController = Thread {
        var dataIn: BufferedReader? = null
        var dataOut: PrintWriter? = null
        var lastActionView = -1
        var lastActionType = -1
        var lastTimerTask: MyTimerTask? = null
        while (!Thread.interrupted()) {
            try {
                var line = dataIn?.readLine()
                if (line == null) {
                    val (inController, outController) = initController()
                    dataIn = BufferedReader(InputStreamReader(inController))
                    dataOut = PrintWriter(outController, true).apply {
                        println("info")
                        if (!RETAIN_CRASH_HANDLER) println("crash_expose")
                        println("a11y_event_min_intv 50")
                        if (XPATHS_TO_KILL != null) {
                            // Unsafe, but reduces performance issues.
                            println("cap_on_main_thread_off")
                            println("run")
                        }
                        println("trace")
                    }
                    try {
                        @Suppress("UnstableApiUsage")
                        val info = timeLimiter.callWithTimeout(dataIn::readLine, 5, TimeUnit.SECONDS)
                        if (info == null) {
                            println("Connection seems to have failed..")
                            continue
                        }
                        val jInfo = JsonParser.parseString(info)
                        if (jInfo is JsonObject) {
                            val startTime = jInfo.get("st")?.asLong
                            val currTime = jInfo.get("ct")?.asLong
                            if (startTime != null && currTime != null) {
                                val tsDiff = System.currentTimeMillis() - currTime
                                println("Connected! startTime = $startTime, tsDiff = $tsDiff")
                                lastActionView = -1
                                continue
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    dataIn = null
                    continue
                }
                if (!line.startsWith("[")) continue
                if (XPATHS_TO_KILL != null && line.startsWith("[{")) {
                    println("Got UI from `run` command")
                    if (DONT_KILL_AFTER != null && System.currentTimeMillis() - tsStart > DONT_KILL_AFTER) continue
                    for (screen in parseAsJsonArray(line) ?: continue)
                        for (viewHashToKill in findViewsToKill(screen)) {
                            if (viewHashToKill < 0) {
                                restartApp()
                                break
                            }
                            println("Disabling $viewHashToKill")
                            dataOut?.println("act dis $viewHashToKill")
                            // break
                        }
                    continue
                }
                val pos = line.indexOf(']')
                if (pos <= 1) continue
                val ts = line.substring(1, pos).toLong()
                line = line.substring(pos + 1)
                if (line.startsWith("[SC]")) {
                    if (XPATHS_TO_KILL == null || lastTimerTask?.isExecuted?.get() == false) continue
                    if (DONT_KILL_AFTER == null || System.currentTimeMillis() - tsStart <= DONT_KILL_AFTER) {
                        lastTimerTask = MyTimerTask { dataOut?.println("run") }
                        timerAutoCapture.schedule(lastTimerTask, 100)
                    }
                } else if (line.startsWith("[BK]")) {
                    updateLastActionTime()
                    lastActionView = -1
                    lastActionType = 100
                    println("Back")
                } else if (line.startsWith("[VA]")) {
                    updateLastActionTime()
                    val info = line.substring(4).split("/".toRegex()).toTypedArray()
                    lastActionView = info[0].toInt()
                    lastActionType = info[1].toInt()
                    println("Action on $lastActionView, type ${info[1]}" + if (info.size <= 2) "" else ", extra ${info[2]}")
                } else if (line.startsWith("[{")) {
                    if (lastActionType == -1) {
                        println("Got UI, but not from an action")
                        continue
                    }
                    var actionScreen: JsonObject? = null
                    val screens = parseAsJsonArray(line)?.filterIsInstance<JsonObject>() ?: continue
                    if (lastActionView >= 0) {
                        for (screen in screens) if (addIsSource(screen, lastActionView)) {
                            actionScreen = screen
                            break
                        }
                    }
                    if (actionScreen == null) {
                        // Source view is unknown.. Just return the focused window.
                        // Note that screens are sorted as lately-added to early-added.
                        for (screen in screens) {
                            if (screen["focus"]?.asBoolean == true) {
                                actionScreen = screen
                                break
                            }
                        }
                        // Nothing found.. Just return the most recently-added window.
                        if (actionScreen == null) actionScreen = screens[0]
                    }
                    actionScreen.addProperty("ua_type", lastActionType)
                    actionScreen.get("act_id")?.asString?.also { println("Act = $it") }
                    lastActionType = -1
                    JsonWriter(FileWriter(File(outputDir, "$ts.json"))).use { writer ->
                        writer.isLenient = true
                        Streams.write(actionScreen, writer)
                    }
                    if (ENABLE_MINICAP) {
                        synchronized(screenHistory) {
                            val tsKey = ts / SCREEN_INTERVAL
                            var sc: Screenshot = screenHistory[0]  // Init value does not matter
                            var l = posCacheLast + 1
                            var r = posCacheLast + CACHE_SIZE
                            while (l < r) {
                                val mid = (l + r) / 2
                                sc = screenHistory[mid % CACHE_SIZE]
                                if (sc.timestamp < tsKey) l = mid + 1 else r = mid
                            }
                            val (screenTs, screenData) = sc
                            if (screenTs > 0L && screenData != null) {
                                println("Screenshot delta = ${(screenTs - tsKey) * SCREEN_INTERVAL}")
                                Files.write(File(screenOutputDir ?: outputDir, "$ts.jpg").toPath(), screenData)
                            }
                        }
                    }
                } else {
                    System.err.println("Unknown reply: $line")
                }
            } catch (e: IOException) {
                dataIn = null
                dataOut = null
                e.printStackTrace()
            }
        }
    }

    private fun File.ensureIsDir() {
        if (!isDirectory) {
            System.err.println("$absolutePath is not a dir.")
            exitProcess(1)
        }
    }

    private fun runCommand(cmd: Array<String>, timeout: Long = 10, workingDir: File? = null) {
        val process = ProcessBuilder(*cmd)
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroy()
            System.err.println("Execution timed out: ${cmd.joinToString(" ")}")
        } else if (process.exitValue() != 0) {
            System.err.println("Execution failed with code ${process.exitValue()}: ${cmd.joinToString(" ")}")
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("At lease one argument is needed.")
            exitProcess(100)
        }
        if (System.getenv("MODE_CHECK_XPATH") != null) {
            println("hash = " + findViewsToKill(JsonParser.parseReader(FileReader(args[0]))))
            exitProcess(0)
        }
        outputDir = File(args[0]).apply { ensureIsDir() }
        if (args.size >= 2) screenOutputDir = File(args[1]).apply { ensureIsDir() }
        printConfig()
        if (ENABLE_MINICAP) threadMinicap.start()
        threadController.start()
        if (WATCHDOG_INTV != null && WATCHDOG_INTV > 0) threadWatchDog.start()
        try {
            threadController.join()
        } catch (e: InterruptedException) {
            // e.printStackTrace();
            println("Forcibly tearing down connections..")
        }
        threadWatchDog.interrupt()
        inController?.close()
        outController?.close()
        threadMinicap.interrupt()
        inMinicap?.close()
    }

    @Suppress("ArrayInDataClass")
    private data class Screenshot(var timestamp: Long = 0, var data: ByteArray? = null)

    private class MyTimerTask(val action: (() -> Unit)) : TimerTask() {
        val isExecuted = AtomicBoolean(false)

        override fun run() {
            isExecuted.set(true)
            action()
        }
    }

}

fun JsonArray.isEmpty(): Boolean = size() == 0
