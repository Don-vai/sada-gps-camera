
package com.regon.sadagpscamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.location.LocationServices

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var previewView: PreviewView
    private lateinit var locationText: TextView
    private lateinit var captureButton: Button
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var adView: AdView

    private var currentLocation: Location? = null

    private val locationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val cameraGranted =
                permissions[Manifest.permission.CAMERA] == true

            if (cameraGranted) {
                startCamera()
            } else {
                toast("Camera permission zaroori hai")
            }

            if (
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                updateLocation()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        createUI()
        requestAppPermissions()

        MobileAds.initialize(this) {
            runOnUiThread {
                adView.loadAd(AdRequest.Builder().build())
            }
        }
    }

    private fun createUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = TextView(this).apply {
            text = "SADA GPS CAMERA"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 16, 12, 12)
        }

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        locationText = TextView(this).apply {
            text = "GPS location mil rahi hai..."
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }

        root.addView(
            locationText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }

        captureButton = Button(this).apply {
            text = "TAKE PHOTO"
            setOnClickListener { takePhoto() }
        }

        val mapButton = Button(this).apply {
            text = "OPEN MAP"
            setOnClickListener { openMap() }
        }

        buttons.addView(
            captureButton,
            LinearLayout.LayoutParams(0, 56, 1f)
        )

        buttons.addView(
            mapButton,
            LinearLayout.LayoutParams(0, 56, 1f)
        )

        root.addView(buttons)

        adView = AdView(this).apply {
            adUnitId = "ca-app-pub-3940256099942544/9214589741"
            setAdSize(AdSize.BANNER)
        }

        root.addView(
            adView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun requestAppPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startCamera()
            updateLocation()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .build()

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )

            } catch (e: Exception) {
                toast("Camera start nahi hua: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateLocation() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            locationText.text = "GPS permission nahi mili"
            return
        }

        try {
            locationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        currentLocation = location

                        locationText.text =
                            "Lat: ${location.latitude}\n" +
                            "Lon: ${location.longitude}"
                    } else {
                        locationText.text =
                            "Location abhi available nahi hai"
                    }
                }
                .addOnFailureListener {
                    locationText.text = "GPS location nahi mil saki"
                }
        } catch (_: SecurityException) {
            locationText.text = "GPS permission check karo"
        }
    }

    private fun takePhoto() {
        if (!::imageCapture.isInitialized) {
            toast("Camera abhi ready nahi hai")
            return
        }

        captureButton.isEnabled = false

        val photoFile = File(
            cacheDir,
            "sada_${System.currentTimeMillis()}.jpg"
        )

        val options = ImageCapture.OutputFileOptions.Builder(
            photoFile
        ).build()

        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults
                ) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(
                            photoFile.absolutePath
                        )

                        if (bitmap == null) {
                            toast("Photo process nahi ho saki")
                            return
                        }

                        val watermarked = addWatermark(bitmap)
                        saveToGallery(watermarked)

                        if (watermarked !== bitmap) {
                            watermarked.recycle()
                        }

                        bitmap.recycle()
                        photoFile.delete()

                    } catch (e: Exception) {
                        toast("Photo save error: ${e.message}")
                    } finally {
                        captureButton.isEnabled = true
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    toast("Photo nahi li ja saki")
                }
            }
        )
    }

    private fun addWatermark(source: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val location = currentLocation
        val date = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date())

        val gps = if (location != null) {
            "Lat: ${location.latitude}\nLon: ${location.longitude}"
        } else {
            "GPS location unavailable"
        }

        val lines = listOf(
            "SADA GPS CAMERA",
            gps,
            date
        )

        val padding = result.width * 0.035f
        val textSize = (result.width * 0.038f).coerceAtLeast(18f)
        val lineHeight = textSize * 1.5f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val boxHeight = padding * 2 + lineHeight * lines.size
        val top = result.height - boxHeight

        val backgroundPaint = Paint().apply {
            color = Color.argb(165, 0, 0, 0)
        }

        canvas.drawRect(
            0f,
            top,
            result.width.toFloat(),
            result.height.toFloat(),
            backgroundPaint
        )

        lines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                padding,
                top + padding + textSize + index * lineHeight,
                paint
            )
        }

        return result
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val filename = "SADA_GPS_${System.currentTimeMillis()}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/SADA GPS Camera"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri: Uri? = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        if (uri == null) {
            toast("Gallery mein photo save nahi hui")
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw IllegalStateException("JPEG save failed")
                }
            } ?: throw IllegalStateException("Output stream unavailable")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }

                contentResolver.update(uri, completed, null, null)
            }

            toast("Photo Gallery mein save ho gayi!")

        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            toast("Save error: ${e.message}")
        }
    }

    private fun openMap() {
        val location = currentLocation

        if (location == null) {
            updateLocation()
            toast("GPS location ka intezar karo")
            return
        }

        val uri = Uri.parse(
            "geo:${location.latitude},${location.longitude}" +
            "?q=${location.latitude},${location.longitude}"
        )

        val intent = Intent(Intent.ACTION_VIEW, uri)

        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("Map app available nahi hai")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }

        if (::adView.isInitialized) {
            adView.destroy()
        }

        super.onDestroy()
    }
}
