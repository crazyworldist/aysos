package com.example.araudio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.View
import android.widget.FrameLayout
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import java.io.InputStream

class MainActivity : ComponentActivity() {

    private lateinit var arSceneView: ArSceneView
    private lateinit var effectLayer: FrameLayout
    private lateinit var flashView: View
    private lateinit var sweepView: View
    private var selectedImageUri: Uri? = null
    private var selectedAudioUri: Uri? = null
    private var targetBitmap: Bitmap? = null
    private val targetImageName = "targetImage"
    private var sessionConfigured = false
    private var isTrackingTarget = false

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
        targetBitmap = uri?.let { loadBitmapFromUri(it, maxSize = 1024) }
        sessionConfigured = false
        configureSessionIfReady()
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedAudioUri = uri
        if (uri != null) preparePlayer(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        effectLayer = findViewById(R.id.effectLayer)
        flashView = findViewById(R.id.flashView)
        sweepView = findViewById(R.id.sweepView)

        findViewById<android.widget.Button>(R.id.btnPickImage).setOnClickListener {
            pickImage.launch("image/*")
        }
        findViewById<android.widget.Button>(R.id.btnPickAudio).setOnClickListener {
            pickAudio.launch("audio/*")
        }

        ensureCameraPermission()

        // Frame update callback: detect augmented images
        arSceneView.onArFrame = { arFrame ->
            val frame = arFrame.frame
            // Start once when available and not yet configured
            if (!sessionConfigured) configureSessionIfReady()

            val updated = frame.getUpdatedTrackables(AugmentedImage::class.java)
            for (img in updated) {
                if (img.name == targetImageName) {
                    when (img.trackingState) {
                        TrackingState.TRACKING -> {
                            if (!isTrackingTarget) {
                                isTrackingTarget = true
                                startAudio()
                            }
                        }
                        TrackingState.PAUSED, TrackingState.STOPPED -> {
                            if (isTrackingTarget) {
                                isTrackingTarget = false
                                stopAudio()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    private fun preparePlayer(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(this)
            .build()
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.repeatMode = Player.REPEAT_MODE_OFF
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    effectLayer.visibility = View.GONE
                }
            }
        })
    }

    private fun startAudio() {
        // 200-400ms’lik kısa bir flaş ve ardından sweep animasyonu ile sesi başlat
        effectLayer.visibility = View.VISIBLE
        flashView.alpha = 0f
        flashView.animate()
            .alpha(1f)
            .setDuration(120)
            .withEndAction {
                flashView.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        startSweep()
                        player?.playWhenReady = true
                        player?.play()
                        startProgressAnimation()
                    }
                    .start()
            }
            .start()
    }

    private fun stopAudio() {
        player?.pause()
        stopProgressAnimation()
    }

    private fun startProgressAnimation() {
        stopProgressAnimation()
        progressJob = lifecycleScope.launch {
            // Ses ilerlemesine göre süpürme pozisyonunu güncelle
            val p = player ?: return@launch
            val total = p.duration.takeIf { it > 0 } ?: return@launch
            while (p.isPlaying) {
                val pos = p.currentPosition.coerceAtLeast(0)
                updateSweepByProgress(pos.toFloat() / total.toFloat())
                delay(16)
            }
            effectLayer.visibility = View.GONE
        }
    }

    private fun stopProgressAnimation() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun startSweep() {
        sweepView.alpha = 1f
        updateSweepByProgress(0f)
    }

    private fun updateSweepByProgress(progress: Float) {
        val parentWidth = effectLayer.width.takeIf { it > 0 } ?: return
        val sweepWidth = sweepView.width
        val maxX = parentWidth - sweepWidth
        val x = (progress.coerceIn(0f, 1f) * maxX)
        sweepView.translationX = x
    }

    private fun loadBitmapFromUri(uri: Uri, maxSize: Int = 1024): Bitmap? {
        return try {
            contentResolver.openInputStream(uri).use { input: InputStream? ->
                if (input == null) return null
                var bmp = BitmapFactory.decodeStream(input) ?: return null
                // Scale down if too large
                if (bmp.width > maxSize || bmp.height > maxSize) {
                    val scale = maxOf(bmp.width.toFloat() / maxSize, bmp.height.toFloat() / maxSize)
                    val newW = (bmp.width / scale).toInt()
                    val newH = (bmp.height / scale).toInt()
                    bmp = Bitmap.createScaledBitmap(bmp, newW, newH, true)
                }
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun configureSessionIfReady() {
        val session = arSceneView.session ?: return
        val bmp = targetBitmap ?: return
        try {
            val db = AugmentedImageDatabase(session)
            db.addImage(targetImageName, bmp)
            val config = Config(session).apply {
                augmentedImageDatabase = db
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            session.configure(config)
            sessionConfigured = true
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}


