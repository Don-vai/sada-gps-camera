package com.regon.sadagpscamera

import android.Manifest
import android.app.Dialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Environment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.graphics.RectF
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var locationText: TextView
    private lateinit var captureButton: Button
    private lateinit var flipButton: Button
    private lateinit var flashButton: Button
    private lateinit var adView: AdView
    private lateinit var miniMapView: MapView
    private lateinit var addressExecutor: ExecutorService
    private lateinit var imageProcessingExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var flashEnabled = false
    private var mapMarker: Marker? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isCapturing = false
    private var currentLocation: Location? = null
    private var currentAddress = "Address unavailable"

    private val locationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (
                result[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)
            ) {
                startCamera()
            } else {
                toast("Camera permission zaroori hai")
            }

            if (
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                updateLocation()
            } else {
                locationText.text = "GPS permission nahi mili"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addressExecutor = Executors.newSingleThreadExecutor()
        imageProcessingExecutor = Executors.newSingleThreadExecutor()
        Configuration.getInstance().userAgentValue = packageName

        createUI()
        requestAppPermissions()

        try {
            System.loadLibrary("sada-native")
        } catch (_: UnsatisfiedLinkError) {
            // Native library optional hai.
        }

        MobileAds.initialize(this) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    adView.loadAd(AdRequest.Builder().build())
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // --------------------------------------------------
    // UI
    // --------------------------------------------------

    private fun createUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = TextView(this).apply {
            text = "GPS CAMERA"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        )

        val cameraFrame = FrameLayout(this)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        cameraFrame.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        miniMapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(20.5937, 78.9629))
        }

        cameraFrame.addView(
            miniMapView,
            FrameLayout.LayoutParams(
                dp(130),
                dp(150),
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, dp(10), dp(10), 0)
            }
        )

        root.addView(
            cameraFrame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        locationText = TextView(this).apply {
            text = "GPS location mil rahi hai..."
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        root.addView(
            locationText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            )
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(1), dp(3), dp(1))
        }

        captureButton = Button(this).apply {
            text = "TAKE PHOTO"
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            isEnabled = false
            setOnClickListener { takePhoto() }
        }

        flipButton = Button(this).apply {
            text = "FLIP CAMERA"
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            isEnabled = false
            setOnClickListener { flipCamera() }
        }

        flashButton = Button(this).apply {
            text = "FLASH ON"
            textSize = 9f
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            isEnabled = false
            setOnClickListener { toggleFlash() }
        }

        val galleryButton = Button(this).apply {
            text = "OPEN GALLERY"
            textSize = 9f
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            setOnClickListener { openGalleryFolder() }
        }

        fun buttonParams() = LinearLayout.LayoutParams(
            0,
            dp(44),
            1f
        ).apply {
            setMargins(dp(1), 0, dp(1), 0)
        }

        buttons.addView(captureButton, buttonParams())
        buttons.addView(flipButton, buttonParams())
        buttons.addView(flashButton, buttonParams())
        buttons.addView(galleryButton, buttonParams())

        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        adView = AdView(this).apply {
            adUnitId = "ca-app-pub-7597829209651046/3947878005"
            setAdSize(AdSize.BANNER)
        }

        root.addView(
            adView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )

        setContentView(root)
    }

    // --------------------------------------------------
    // PERMISSIONS
    // --------------------------------------------------

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.distinct().filterNot {
            hasPermission(it)
        }

        if (missing.isEmpty()) {
            startCamera()
            updateLocation()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // --------------------------------------------------
    // CAMERA
    // --------------------------------------------------

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (isFinishing || isDestroyed) return

        captureButton.isEnabled = false
        flipButton.isEnabled = false

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            if (isFinishing || isDestroyed) return@addListener

            try {
                val provider = future.get()

                val selector =
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                if (!provider.hasCamera(selector)) {
                    toast("Selected camera available nahi hai")
                    return@addListener
                }

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val capture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .setTargetRotation(
                        previewView.display?.rotation ?: Surface.ROTATION_0
                    )
                    .build()

                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(this, selector, preview, capture)

                cameraProvider = provider
                activeCamera = boundCamera
                imageCapture = capture
                flashEnabled = false
                try {
                    boundCamera.cameraControl.enableTorch(false)
                } catch (_: Exception) { }
                flashButton.isEnabled = boundCamera.cameraInfo.hasFlashUnit()
                flashButton.text = "FLASH ON"
                captureButton.isEnabled = true
                flipButton.isEnabled = true

                flipButton.text =
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        "FRONT CAMERA"
                    } else {
                        "BACK CAMERA"
                    }

            } catch (e: Exception) {
                toast("Camera start nahi hua: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleFlash() {
        val camera = activeCamera
        if (camera == null || !camera.cameraInfo.hasFlashUnit()) {
            toast("Is camera mein flashlight supported nahi hai")
            flashButton.isEnabled = false
            return
        }

        val newState = !flashEnabled
        try {
            camera.cameraControl.enableTorch(newState)
                .addListener({
                    runOnUiThread {
                        flashEnabled = newState
                        flashButton.text = if (flashEnabled) "FLASH OFF" else "FLASH ON"
                    }
                }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            toast("Flash control nahi ho saka: ${e.message}")
        }
    }

    private fun flipCamera() {
        if (isCapturing) return

        val nextFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        val nextSelector =
            if (nextFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

        try {
            if (
                cameraProvider != null &&
                !cameraProvider!!.hasCamera(nextSelector)
            ) {
                toast("Ye camera available nahi hai")
                return
            }
        } catch (_: Exception) {
            toast("Camera check nahi ho saka")
            return
        }

        lensFacing = nextFacing
        startCamera()
    }

    // --------------------------------------------------
    // GPS
    // --------------------------------------------------

    private fun updateLocation() {
        if (
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            locationText.text = "GPS permission nahi mili"
            return
        }

        try {
            locationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (isFinishing || isDestroyed) return@addOnSuccessListener

                    if (location == null) {
                        locationText.text =
                            "Location nahi mili. GPS on karke try karo."
                        return@addOnSuccessListener
                    }

                    currentLocation = location
                    currentAddress = "Finding address..."
                    showLocation()
                    resolveAddress(location)
                }
                .addOnFailureListener {
                    locationText.text = "GPS location nahi mil saki"
                }
        } catch (_: SecurityException) {
            locationText.text = "GPS permission check karo"
        }
    }

    private fun showLocation() {
        val location = currentLocation ?: return

        locationText.text =
            "$currentAddress\nLat: ${location.latitude}  Lon: ${location.longitude}"

        val point = GeoPoint(location.latitude, location.longitude)
        miniMapView.controller.setCenter(point)

        if (mapMarker == null) {
            mapMarker = Marker(miniMapView).apply {
                title = "Current GPS Location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            miniMapView.overlays.add(mapMarker)
        }

        mapMarker?.position = point
        miniMapView.invalidate()
    }

    private fun resolveAddress(location: Location) {
        if (!Geocoder.isPresent()) {
            currentAddress = "Address unavailable"
            showLocation()
            return
        }

        val geocoder = Geocoder(this, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    applyAddress(addresses, location)
                                }
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            runOnUiThread {
                                currentAddress = "Address unavailable"
                                if (!isFinishing && !isDestroyed) showLocation()
                            }
                        }
                    }
                )
            } catch (_: Exception) {
                currentAddress = "Address unavailable"
                showLocation()
            }
        } else {
            addressExecutor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1
                    )

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            applyAddress(addresses.orEmpty(), location)
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        currentAddress = "Address unavailable"
                        if (!isFinishing && !isDestroyed) showLocation()
                    }
                }
            }
        }
    }

    private fun applyAddress(
        addresses: List<Address>,
        location: Location
    ) {
        val address = addresses.firstOrNull()

        currentAddress = address?.let {
            listOfNotNull(
                it.subLocality,
                it.locality,
                it.subAdminArea,
                it.adminArea,
                it.countryName
            )
                .map { part -> part.trim() }
                .filter { part -> part.isNotEmpty() }
                .distinct()
                .joinToString(", ")
        }.orEmpty().ifBlank {
            "Address unavailable"
        }

        showLocation()
    }

    // --------------------------------------------------
    // TAKE PHOTO
    // --------------------------------------------------

    private fun takePhoto() {
        if (isCapturing) return

        val capture = imageCapture ?: run {
            toast("Camera abhi ready nahi hai")
            return
        }

        if (!hasPermission(Manifest.permission.CAMERA)) {
            toast("Camera permission zaroori hai")
            return
        }

        isCapturing = true
        captureButton.isEnabled = false
        flipButton.isEnabled = false
        flashButton.isEnabled = false

        // View ko main thread par snapshot karo; heavy bitmap work background mein hoga.
        val mapSnapshot = captureMapSnapshot()
        val file = File(cacheDir, "gps_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            imageProcessingExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    var original: Bitmap? = null
                    var corrected: Bitmap? = null
                    var watermarked: Bitmap? = null

                    try {
                        original = decodePhotoForProcessing(file)
                            ?: throw IllegalStateException("Photo decode nahi hui")
                        corrected = correctPhotoOrientation(original, file)
                        watermarked = addWatermark(corrected, mapSnapshot)
                        saveToGallery(watermarked)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                toast("Photo Pictures/GPS Camera mein save ho gayi!")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                toast("Photo error: ${e.message ?: "Unknown error"}")
                            }
                        }
                    } finally {
                        if (watermarked != null && watermarked !== corrected && watermarked !== original) {
                            watermarked.recycle()
                        }
                        if (corrected != null && corrected !== original) corrected.recycle()
                        original?.recycle()
                        mapSnapshot?.recycle()
                        file.delete()

                        runOnUiThread {
                            isCapturing = false
                            if (!isFinishing && !isDestroyed) {
                                captureButton.isEnabled = imageCapture != null
                                flipButton.isEnabled = cameraProvider != null
                                flashButton.isEnabled = activeCamera?.cameraInfo?.hasFlashUnit() == true
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mapSnapshot?.recycle()
                    file.delete()
                    runOnUiThread {
                        isCapturing = false
                        captureButton.isEnabled = imageCapture != null
                        flipButton.isEnabled = cameraProvider != null
                        flashButton.isEnabled = activeCamera?.cameraInfo?.hasFlashUnit() == true
                        toast("Photo nahi li ja saki: ${exception.message}")
                    }
                }
            }
        )
    }

    private fun captureMapSnapshot(): Bitmap? {
        return try {
            if (miniMapView.width <= 0 || miniMapView.height <= 0) return null
            Bitmap.createBitmap(
                miniMapView.width,
                miniMapView.height,
                Bitmap.Config.ARGB_8888
            ).also { bitmap ->
                miniMapView.draw(Canvas(bitmap))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePhotoForProcessing(file: File): Bitmap? {
        // Bahut high-resolution photos ko processing ke liye limit karna delay aur RAM use kam karta hai.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxDimension = 2560
        while (
            bounds.outWidth / (sampleSize * 2) >= maxDimension &&
            bounds.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }
        // Portrait photos may only have one dimension above the target.
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension * 1.5) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun correctPhotoOrientation(
        source: Bitmap,
        file: File
    ): Bitmap {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(90f)
            }
        }

        if (matrix.isIdentity) return source

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }

    // --------------------------------------------------
    // WATERMARK AND MINI MAP
    // --------------------------------------------------

    private fun addWatermark(source: Bitmap, mapSnapshot: Bitmap?): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Bitmap create nahi hua")

        val canvas = Canvas(result)
        val width = result.width.toFloat()
        val height = result.height.toFloat()
        val padding = (width * 0.025f).coerceAtLeast(8f)
        val textSize = (width * 0.023f).coerceAtLeast(12f)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 0, 0, 0)
        }

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val topHeight = textSize + padding * 1.5f

        canvas.drawRect(0f, 0f, width, topHeight, background)
        canvas.drawText(
            "DEVELOPED BY SADA REGON",
            width / 2f,
            padding + textSize * 0.85f,
            brandPaint
        )

        val mapWidth = (width * 0.29f).toInt()
        val mapHeight = (height * 0.19f).toInt()
        val mapLeft = width - mapWidth - padding
        val mapTop = height - mapHeight - padding
        val textMaxWidth = (mapLeft - padding * 2).coerceAtLeast(1f)

        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val location = currentLocation
        val date = SimpleDateFormat(
            "dd MMM yyyy, hh:mm:ss a",
            Locale.getDefault()
        ).format(Date())

        val lines = mutableListOf<String>()
        lines.addAll(wrapText(currentAddress, detailPaint, textMaxWidth))

        if (location != null) {
            lines.add("Lat: ${location.latitude}")
            lines.add("Lon: ${location.longitude}")
        } else {
            lines.add("GPS unavailable")
        }

        lines.add(date)

        val lineHeight = textSize * 1.4f
        val maxLines = ((mapHeight - padding) / lineHeight)
            .toInt().coerceAtLeast(2)

        val detailsTop = (height - mapHeight - padding).coerceAtLeast(topHeight)

        canvas.drawRect(0f, detailsTop, width, height, background)

        lines.take(maxLines).forEachIndexed { index, line ->
            val y = detailsTop + padding + textSize + index * lineHeight
            if (y < height - padding) {
                canvas.drawText(line, padding, y, detailPaint)
            }
        }

        try {
            if (mapSnapshot != null && mapWidth > 0 && mapHeight > 0) {
                val destination = RectF(
                    mapLeft,
                    mapTop,
                    mapLeft + mapWidth,
                    mapTop + mapHeight
                )
                canvas.drawBitmap(
                    mapSnapshot,
                    null,
                    destination,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )

                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = (width * 0.003f).coerceAtLeast(2f)
                }
                canvas.drawRect(destination, border)
            }
        } catch (_: Exception) {
            // Map render fail hone par bhi photo save hogi.
        }

        return result
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)

        val result = mutableListOf<String>()
        var current = ""

        for (word in text.split(" ")) {
            val candidate = if (current.isEmpty()) word else "$current $word"

            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) result.add(current)
                current = word
            }
        }

        if (current.isNotEmpty()) result.add(current)
        return result
    }

    // --------------------------------------------------
    // SAVE PHOTO
    // --------------------------------------------------

    @Suppress("DEPRECATION")
    private fun saveToGallery(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            throw SecurityException("Storage permission nahi mili")
        }

        val filename = "GPS_Camera_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GPS Camera/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val gpsFolder = File(picturesDir, "GPS Camera")
                if (!gpsFolder.exists() && !gpsFolder.mkdirs()) {
                    throw IllegalStateException("GPS Camera folder create nahi hua")
                }
                put(MediaStore.Images.Media.DATA, File(gpsFolder, filename).absolutePath)
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Gallery mein photo save nahi hui")

        try {
            val stream = contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Output stream unavailable")
            stream.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) {
                    throw IllegalStateException("JPEG save failed")
                }
                it.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, completed, null, null)
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    // --------------------------------------------------
    // OPEN GALLERY - UPDATED
    // --------------------------------------------------

    private fun openGalleryFolder() {
        val dialog = Dialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = TextView(this).apply {
            text = "GPS CAMERA PHOTOS"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val scrollView = ScrollView(this)
        val photoList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // ScrollView ko ek hi child chahiye.
        scrollView.addView(photoList)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val backButton = Button(this).apply {
            text = "BACK TO CAMERA"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }

        root.addView(
            backButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        dialog.setContentView(root)
        dialog.setCancelable(true)

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DISPLAY_NAME
            )
        } else {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME
            )
        }

        var photoCount = 0

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->

                val idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media._ID
                )

                val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                }
                val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val path = if (pathColumn >= 0) cursor.getString(pathColumn).orEmpty() else ""
                    val filename = if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else ""
                    val normalizedPath = path.replace('\\', '/').trimEnd('/')
                    // Android 9 DATA mein full file path hota hai; Android 10+ RELATIVE_PATH mein folder path.
                    val folderPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        normalizedPath
                    } else {
                        normalizedPath.substringBeforeLast('/', normalizedPath)
                    }

                    val inGpsFolder = folderPath.endsWith(
                        "Pictures/GPS Camera", ignoreCase = true
                    )
                    // Pehle Pictures root mein save hui app photos bhi gallery mein dikhaye.
                    val oldAppPhotoInPictures = filename.startsWith("GPS_Camera_", ignoreCase = true) &&
                        folderPath.endsWith("Pictures", ignoreCase = true)
                    if (!inGpsFolder && !oldAppPhotoInPictures) continue

                    val id = cursor.getLong(idColumn)
                    val imageUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    val imageView = ImageView(this).apply {
                        setImageURI(imageUri)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "Saved GPS Camera photo"
                        setBackgroundColor(Color.rgb(25, 25, 25))
                    }

                    photoList.addView(
                        imageView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(12)
                        }
                    )

                    val divider = View(this).apply {
                        setBackgroundColor(Color.DKGRAY)
                    }

                    photoList.addView(
                        divider,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(1)
                        )
                    )

                    photoCount++
                }
            }

            if (photoCount == 0) {
                val emptyText = TextView(this).apply {
                    text = "GPS Camera folder mein koi photo nahi mili.\n\nPehle TAKE PHOTO dabao aur save confirmation check karo."
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(30), dp(16), dp(30))
                }

                photoList.addView(
                    emptyText,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        } catch (e: Exception) {
            val errorText = TextView(this).apply {
                text = "Gallery error: ${e.message ?: "Unknown error"}"
                textSize = 14f
                setTextColor(Color.RED)
                setPadding(dp(16), dp(20), dp(16), dp(20))
            }

            photoList.addView(
                errorText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // --------------------------------------------------
    // LIFECYCLE
    // --------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (::miniMapView.isInitialized) miniMapView.onResume()
    }

    override fun onPause() {
        if (::miniMapView.isInitialized) miniMapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()

        if (::addressExecutor.isInitialized) addressExecutor.shutdown()
        if (::imageProcessingExecutor.isInitialized) imageProcessingExecutor.shutdown()

        if (::adView.isInitialized) {
            adView.destroy()
        }

        if (::miniMapView.isInitialized) {
            miniMapView.onDetach()
        }

        super.onDestroy()
    }
}
