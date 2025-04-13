package com.example.ploypdetector

import android.animation.LayoutTransition
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.transition.TransitionManager
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    // Root + container layouts
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var connectLayout: View
    private lateinit var videoPageLayout: ConstraintLayout

    // Input + buttons on the IP page
    private lateinit var serverIpInput: EditText
    private lateinit var connectButton: Button
    private lateinit var returnToStreamButton: Button

    // Video streaming views
    private lateinit var videoView1: ImageView
    private lateinit var videoView2: ImageView
    private lateinit var pauseButton1: ImageButton
    private lateinit var pauseButton2: ImageButton
    private lateinit var fullscreenButton1: ImageButton
    private lateinit var fullscreenButton2: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var returnButton: ImageButton

    // Card containers for the two video streams
    private lateinit var videoCard1: CardView
    private lateinit var videoCard2: CardView

    // Streaming state variables
    private var isVideo1Playing = true
    private var isVideo2Playing = true
    private var isVideo1Fullscreen = false
    private var isVideo2Fullscreen = false
    private var isStreaming = false

    // Remember the current IP/URL base
    private var currentBaseUrl: String = ""

    // Coroutines for streaming
    private var streamJob1: Job? = null
    private var streamJob2: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // store original layout params for each video CardView (for fullscreen toggling)
    private var videoCard1OriginalParams: ConstraintLayout.LayoutParams? = null
    private var videoCard2OriginalParams: ConstraintLayout.LayoutParams? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupTransitions()
        setupConnectListener()
        setupClickListeners()

        // Save the original layout params of each card (for restoring after fullscreen)
        videoCard1OriginalParams = videoCard1.layoutParams as ConstraintLayout.LayoutParams
        videoCard2OriginalParams = videoCard2.layoutParams as ConstraintLayout.LayoutParams
    }

    /**
     * Find all views by ID and store them in fields.
     */
    private fun initializeViews() {
        // Root and container layouts
        rootLayout = findViewById(R.id.root_layout)
        connectLayout = findViewById(R.id.connectLayout)
        videoPageLayout = findViewById(R.id.videoPageLayout)

        // IP input + Buttons
        serverIpInput = findViewById(R.id.serverIpInput)
        connectButton = findViewById(R.id.connectButton)
        returnToStreamButton = findViewById(R.id.returnToStreamButton)

        // Video streaming controls
        videoView1 = findViewById(R.id.videoView1)
        videoView2 = findViewById(R.id.videoView2)
        pauseButton1 = findViewById(R.id.pauseButton1)
        pauseButton2 = findViewById(R.id.pauseButton2)
        fullscreenButton1 = findViewById(R.id.fullscreenButton1)
        fullscreenButton2 = findViewById(R.id.fullscreenButton2)
        refreshButton = findViewById(R.id.refreshButton)
        returnButton = findViewById(R.id.returnButton)

        // Card containers
        videoCard1 = findViewById(R.id.videoCard1)
        videoCard2 = findViewById(R.id.videoCard2)
    }

    /**
     * Enable smooth layout transitions on the root layout.
     */
    private fun setupTransitions() {
        rootLayout.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            // You can tweak this duration as you like
            setDuration(300L)
        }
    }

    /**
     * Handle the Connect button (IP input) to start streaming.
     */
    private fun setupConnectListener() {
        connectButton.setOnClickListener {
            val baseUrl = serverIpInput.text.toString().trim()
            if (baseUrl.isEmpty()) {
                Toast.makeText(this, "Please enter the server IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentBaseUrl = baseUrl
            slideToVideoPage()
            isStreaming = true
            // Start the two streams using the entered base URL
            startStream("http://$currentBaseUrl/original_feed", 1)
            startStream("http://$currentBaseUrl/video_feed", 2)
        }
    }

    /**
     * Slide from connect layout to video layout.
     */
    private fun slideToVideoPage() {
        // Animate changes only at root level
        TransitionManager.beginDelayedTransition(rootLayout)
        connectLayout.visibility = View.GONE
        videoPageLayout.visibility = View.VISIBLE
    }

    /**
     * Slide from video layout back to connect layout.
     */
    private fun slideToConnectPage() {
        TransitionManager.beginDelayedTransition(rootLayout)
        videoPageLayout.visibility = View.GONE
        connectLayout.visibility = View.VISIBLE
    }

    /**
     * Start streaming a MJPEG feed in a coroutine.
     */
    private fun startStream(url: String, streamNumber: Int) {
        val job = coroutineScope.launch(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.apply {
                    doInput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    // Expect MJPEG
                    setRequestProperty("Accept", "multipart/x-mixed-replace; boundary=--BoundaryString")
                }

                try {
                    connection.connect()
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Connection failed: ${connection.responseCode} ${connection.responseMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }

                    val stream = BufferedInputStream(connection.inputStream)
                    val imageBuffer = ByteArray(1024 * 1024) // 1 MB buffer

                    while (isStreaming) {
                        // Check if this particular stream is paused
                        val isPlaying = when (streamNumber) {
                            1 -> isVideo1Playing
                            2 -> isVideo2Playing
                            else -> false
                        }
                        if (!isPlaying) {
                            delay(100)
                            continue
                        }

                        try {
                            // Find JPEG start marker (0xFFD8)
                            var startMarkerFound = false
                            while (!startMarkerFound && isStreaming) {
                                val byte1 = stream.read()
                                if (byte1 == 0xFF) {
                                    val byte2 = stream.read()
                                    if (byte2 == 0xD8) {
                                        startMarkerFound = true
                                        imageBuffer[0] = 0xFF.toByte()
                                        imageBuffer[1] = 0xD8.toByte()
                                    }
                                }
                            }
                            if (!startMarkerFound) continue

                            // Read until JPEG end marker (0xFFD9)
                            var pos = 2
                            var endMarkerFound = false
                            while (!endMarkerFound && pos < imageBuffer.size - 1 && isStreaming) {
                                imageBuffer[pos] = stream.read().toByte()
                                if ((imageBuffer[pos - 1].toInt() and 0xFF) == 0xFF &&
                                    (imageBuffer[pos].toInt() and 0xFF) == 0xD9) {
                                    endMarkerFound = true
                                }
                                pos++
                            }

                            if (endMarkerFound) {
                                val bitmap = BitmapFactory.decodeByteArray(imageBuffer, 0, pos)
                                if (bitmap != null) {
                                    withContext(Dispatchers.Main) {
                                        when (streamNumber) {
                                            1 -> videoView1.setImageBitmap(bitmap)
                                            2 -> videoView2.setImageBitmap(bitmap)
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Failed to decode image for stream $streamNumber",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Stream $streamNumber read error: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            delay(1000)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Stream $streamNumber connection error: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to create connection for stream $streamNumber: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Keep track of the streaming jobs
        if (streamNumber == 1) {
            streamJob1 = job
        } else if (streamNumber == 2) {
            streamJob2 = job
        }
    }

    /**
     * Handle clicks for pause/play, fullscreen, refresh, return.
     */
    private fun setupClickListeners() {
        // Pause/Play for each stream
        pauseButton1.setOnClickListener {
            isVideo1Playing = !isVideo1Playing
            pauseButton1.setImageResource(
                if (isVideo1Playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
        pauseButton2.setOnClickListener {
            isVideo2Playing = !isVideo2Playing
            pauseButton2.setImageResource(
                if (isVideo2Playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }

        // Fullscreen toggles
        fullscreenButton1.setOnClickListener { toggleFullscreen(1) }
        fullscreenButton2.setOnClickListener { toggleFullscreen(2) }

        // Refresh streaming
        refreshButton.setOnClickListener { refreshStreams() }

        // Return button (Video -> IP page)
        returnButton.setOnClickListener {
            // Stop streaming and cancel jobs
            isStreaming = false
            streamJob1?.cancel()
            streamJob2?.cancel()
            // Show the IP input page
            slideToConnectPage()
        }

        // Return to Streaming (IP page -> Video page), even if no IP is set
        returnToStreamButton.setOnClickListener {
            slideToVideoPage()
            // If not streaming, start streaming with the last known IP
            if (!isStreaming) {
                isStreaming = true
                startStream("http://$currentBaseUrl/original_feed", 1)
                startStream("http://$currentBaseUrl/video_feed", 2)
            }
        }
    }

    /**
     * The changes for fullscreen, store the original LayoutParams, then reapply on exit.
     */
    private fun toggleFullscreen(videoNumber: Int) {
        // Animate changes only in the videoPageLayout
        TransitionManager.beginDelayedTransition(videoPageLayout)

        when (videoNumber) {
            1 -> {
                if (!isVideo1Fullscreen) {
                    // Go FULLSCREEN
                    val fullscreenParams = ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_PARENT,
                        ConstraintLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        topToTop = videoPageLayout.id
                        bottomToBottom = videoPageLayout.id
                        startToStart = videoPageLayout.id
                        endToEnd = videoPageLayout.id
                    }
                    videoCard1.layoutParams = fullscreenParams
                    videoCard2.visibility = View.GONE
                    fullscreenButton1.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                } else {
                    // RESTORE original layout
                    videoCard1OriginalParams?.let {
                        videoCard1.layoutParams = it
                    }
                    videoCard2.visibility = View.VISIBLE
                    fullscreenButton1.setImageResource(android.R.drawable.ic_menu_crop)
                }
                isVideo1Fullscreen = !isVideo1Fullscreen
            }
            2 -> {
                if (!isVideo2Fullscreen) {
                    // Go FULLSCREEN
                    val fullscreenParams = ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_PARENT,
                        ConstraintLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        topToTop = videoPageLayout.id
                        bottomToBottom = videoPageLayout.id
                        startToStart = videoPageLayout.id
                        endToEnd = videoPageLayout.id
                    }
                    videoCard2.layoutParams = fullscreenParams
                    videoCard1.visibility = View.GONE
                    fullscreenButton2.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                } else {
                    // RESTORE original layout
                    videoCard2OriginalParams?.let {
                        videoCard2.layoutParams = it
                    }
                    videoCard1.visibility = View.VISIBLE
                    fullscreenButton2.setImageResource(android.R.drawable.ic_menu_crop)
                }
                isVideo2Fullscreen = !isVideo2Fullscreen
            }
        }
    }

    /**
     * Refresh the streams by canceling and restarting.
     */
    private fun refreshStreams() {
        // Cancel current streaming jobs
        streamJob1?.cancel()
        streamJob2?.cancel()
        isStreaming = false
        // Slight delay before restarting
        coroutineScope.launch {
            delay(500)
            isStreaming = true
            startStream("http://$currentBaseUrl/original_feed", 1)
            startStream("http://$currentBaseUrl/video_feed", 2)
        }
    }

    /**
     * Clean up when the activity is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Clean up streaming
        isStreaming = false
        coroutineScope.cancel()
    }
}
