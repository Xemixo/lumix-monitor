package com.patloch.lumixmonitor

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var liveView: ImageView
    private lateinit var statusText: TextView

    private val USER_AGENT = "Panasonic/LumixSync/1.0"
    private val APP_ID = "com.panasonic.avc.cng.imageapp"
    private val JPEG_OFFSET = 254

    private var cameraIp = "192.168.54.1"
    private var isMonitoring = false

    private var client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        liveView = findViewById(R.id.liveView)
        statusText = findViewById(R.id.statusText)
        vlogBtn = findViewById(R.id.vlogBtn)
        vlogBtn.setOnClickListener {
            vlogEnabled = !vlogEnabled
            vlogBtn.setBackgroundColor(
                if (vlogEnabled) 0xCC8800FF.toInt() else 0x88000000.toInt()
            )
            Log.d("LUMIX", "V-Log normalizace: $vlogEnabled")
        }

        setupFullScreen()
        bindToWifiAndStart()
        falseColorBtn = findViewById(R.id.falseColorBtn)
        falseColorBtn.setOnClickListener {
            falseColorEnabled = !falseColorEnabled
            falseColorBtn.setBackgroundColor(
                if (falseColorEnabled) 0xCCFF4400.toInt() else 0x88000000.toInt()
            )
            Log.d("LUMIX", "False color: $falseColorEnabled")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupFullScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private suspend fun updateStatus(message: String) {
        withContext(Dispatchers.Main) {
            statusText.text = message
            statusText.visibility = View.VISIBLE
        }
    }

    private fun updateStatusSync(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Wi-Fi binding
    // -------------------------------------------------------------------------

    private fun bindToWifiAndStart() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        updateStatusSync("Hledám Wi-Fi foťáku...")

        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val gateway = linkProperties
                        ?.routes
                        ?.firstOrNull { it.isDefaultRoute }
                        ?.gateway
                        ?.hostAddress

                    if (gateway != null) {
                        cameraIp = gateway
                        Log.d("LUMIX", "Camera IP: $cameraIp")
                    }

                    client = client.newBuilder()
                        .socketFactory(network.socketFactory)
                        .build()

                    runOnUiThread { startMonitoring() }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        updateStatusSync("Wi-Fi odpojeno")
                        isMonitoring = false
                    }
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // Hlavní smyčka
    // -------------------------------------------------------------------------
// Do proměnných třídy přidej:
    private var currentJpegOffset = 254
    private var falseColorEnabled = false
    private lateinit var falseColorBtn: TextView
    private var vlogEnabled = false
    private lateinit var vlogBtn: TextView
    // False color paleta - standard cinema mapování jasu
    private fun applyFalseColor(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // V-Log: černá = 7.3%, bílá = 80% → normalizujeme na 0–1
        val vlogBlack = 0.073f
        val vlogWhite = 0.800f

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            var lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f

            // Pokud je V-Log, roztáhneme rozsah na 0–1
            if (vlogEnabled) {
                lum = (lum - vlogBlack) / (vlogWhite - vlogBlack)
                lum = lum.coerceIn(0f, 1f)
            }

            pixels[i] = when {
                lum < 0.04f -> 0xFF000000.toInt()  // Černá  - clipped shadows
                lum < 0.10f -> 0xFF0000FF.toInt()  // Modrá  - podexponováno
                lum < 0.18f -> 0xFF004400.toInt()  // Tmavě zelená
                lum < 0.45f -> 0xFF00AA00.toInt()  // Zelená - správná expozice
                lum < 0.55f -> 0xFF00FF00.toInt()  // Světle zelená - střední šedá
                lum < 0.70f -> 0xFF00AA00.toInt()  // Zelená
                lum < 0.78f -> 0xFFFFFF00.toInt()  // Žlutá  - skin tones
                lum < 0.85f -> 0xFFFF8800.toInt()  // Oranžová
                lum < 0.95f -> 0xFFFF0000.toInt()  // Červená - přeexponováno
                else        -> 0xFFFFFFFF.toInt()  // Bílá   - clipped highlights
            }
        }

        val result = android.graphics.Bitmap.createBitmap(width, height, bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    private fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        lifecycleScope.launch(Dispatchers.IO) {
            while (isMonitoring) {
                try {
                    updateStatus("Připojuji se...")

                    // Přepneme do rec módu pokud je potřeba
                    val state = getState()
                    Log.d("LUMIX", "Aktuální mód: $state")

                    if (state != "rec") {
                        updateStatus("Přepínám do rec módu...")
                        sendCommand("mode=camcmd&value=recmode")
                        delay(1500)

                        val newState = getState()
                        Log.d("LUMIX", "Mód po přepnutí: $newState")
                        if (newState != "rec") {
                            updateStatus("Nepodařilo se přepnout do rec módu, zkouším znovu...")
                            delay(2000)
                            continue
                        }
                    }

                    // Spustíme UDP stream
                    updateStatus("Spouštím stream...")
                    sendCommand("mode=startstream&value=49152")
                    delay(300)

                    // Blokuje dokud stream neskončí (timeout nebo chyba)
                    receiveUdpStream()

                    Log.d("LUMIX", "Stream skončil, restartuji za 500ms...")
                    delay(500)

                } catch (e: Exception) {
                    Log.e("LUMIX", "Chyba: ${e.message}")
                    updateStatus("Chyba: ${e.message}")
                    delay(2000)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // UDP příjem obrazu
    // -------------------------------------------------------------------------

    private suspend fun receiveUdpStream() {
        val socket = java.net.DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(49152))
            soTimeout = 5000
        }

        Log.d("LUMIX", "UDP socket otevřen")

        val buffer = ByteArray(65536)
        val packet = java.net.DatagramPacket(buffer, buffer.size)

        // Keepalive - pouze getstate
        val keepaliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(2000)
                val state = getFullState()
                Log.v("LUMIX", "Keepalive: cammode=${state.cammode}, recording=${state.recording}")
            }
        }

        try {
            while (isMonitoring) {
                try {
                    socket.receive(packet)
                    val length = packet.length
                    if (length <= currentJpegOffset) continue
                    val d = packet.data
                    if (d[currentJpegOffset].toInt() and 0xFF != 0xFF ||
                        d[currentJpegOffset + 1].toInt() and 0xFF != 0xD8) {
                        // Offset se změnil - scanujeme a zapamatujeme nový
                        var found = -1
                        for (i in 0 until minOf(length - 1, 512)) {
                            if (d[i].toInt() and 0xFF == 0xFF &&
                                d[i + 1].toInt() and 0xFF == 0xD8) {
                                found = i
                                break
                            }
                        }
                        if (found == -1) continue
                        currentJpegOffset = found
                        Log.d("LUMIX", "JPEG offset změněn na: $currentJpegOffset")
                    }

                    val bitmap = BitmapFactory.decodeByteArray(d, currentJpegOffset, length - currentJpegOffset)
                    if (bitmap != null) {
                        val displayBitmap = if (falseColorEnabled) applyFalseColor(bitmap) else bitmap
                        withContext(Dispatchers.Main) {
                            liveView.setImageBitmap(displayBitmap)
                            statusText.visibility = View.GONE
                        }
                    }

                } catch (e: java.net.SocketTimeoutException) {
                    // Zjistíme jestli foťák nahrává
                    val state = getFullState()
                    Log.w("LUMIX", "UDP timeout - stav: cammode=${state.cammode}, recording=${state.recording}")

                    if (state.recording) {
                        // Foťák nahrává - stream je pozastaven, jen čekáme
                        Log.d("LUMIX", "Nahrávání probíhá, čekám...")
                        updateStatus("Nahrávám...")
                        // Čekáme ve smyčce dokud nahrávání neskončí
                        while (isMonitoring) {
                            delay(500)
                            val s = getFullState()
                            if (!s.recording) {
                                Log.d("LUMIX", "Nahrávání skončilo, obnovuji stream")
                                updateStatus("Spouštím stream...")
                                sendCommand("mode=startstream&value=49152")
                                delay(300)
                                break // Vrátíme se do receive smyčky
                            }
                        }
                    } else {
                        // Skutečný výpadek - vyskočíme a restartujeme
                        Log.w("LUMIX", "Skutečný výpadek streamu")
                        break
                    }
                }
            }
        } finally {
            keepaliveJob.cancel()
            socket.close()
            Log.d("LUMIX", "UDP socket uzavřen")
        }
    }
    data class CameraState(
        val cammode: String,
        val recording: Boolean
    )

    // -------------------------------------------------------------------------
    // Pomocné funkce
    // -------------------------------------------------------------------------

    private fun getFullState(): CameraState {
        return try {
            val req = Request.Builder()
                .url("http://$cameraIp/cam.cgi?mode=getstate")
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", APP_ID)
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()

            val cammode = Regex("<cammode>(.*?)</cammode>")
                .find(body)?.groupValues?.get(1) ?: "unknown"

            // Správný tag pro nahrávání je <rec>on</rec>
            val rec = Regex("<rec>(.*?)</rec>")
                .find(body)?.groupValues?.get(1) ?: "off"
            val recording = rec == "on"

            Log.v("LUMIX", "Keepalive: cammode=$cammode, recording=$recording")
            CameraState(cammode = cammode, recording = recording)
        } catch (e: Exception) {
            Log.e("LUMIX", "getFullState error: ${e.message}")
            CameraState(cammode = "unknown", recording = false)
        }
    }
    private fun getState(): String = getFullState().cammode
    private fun sendCommandWithResponse(params: String): String {
        return try {
            val req = Request.Builder()
                .url("http://$cameraIp/cam.cgi?$params")
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", APP_ID)
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            Log.d("LUMIX", "CMD [$params] → $body")
            body
        } catch (e: Exception) {
            Log.e("LUMIX", "CMD error: ${e.message}")
            ""
        }
    }

    private fun sendCommand(params: String) {
        try {
            val req = Request.Builder()
                .url("http://$cameraIp/cam.cgi?$params")
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", APP_ID)
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.d("LUMIX", "CMD [$params] → $body")
            resp.close()
        } catch (e: Exception) {
            Log.e("LUMIX", "CMD error [$params]: ${e.message}")
        }
    }
}