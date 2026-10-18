package com.regon.sadagpscamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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

    private external fun getNativeMessage(): String

    private lateinit var previewView: PreviewView
    private lateinit var locationText: TextView
    private lateinit var captureButton: Button
    private lateinit var flipButton: Button
    private lateinit var adView: AdView
    private lateinit var miniMapView: MapView
    private lateinit var addressExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
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
        ) { permissions ->

            val cameraGranted =
                permissions[Manifest.permission.CAMERA] == true ||
                    hasPermission(Manifest.permission.CAMERA)

            if (cameraGranted) {
                startCamera()
            } else {
                toast("Camera permission zaroori hai")
            }

            val locationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (locationGranted) {
                updateLocation()
            } else {
                locationText.text = "GPS permission nahi mili"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addressExecutor = Executors.newSingleThreadExecutor()

        Configuration.getInstance().userAgentValue = packageName

        createUI()

        try {
            System.loadLibrary("sada-native")
            getNativeMessage()
        } catch (_: Exception) {
            // Native library is optional for the camera UI.
        }

        requestAppPermissions()

        try {
            MobileAds.initialize(this) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        adView.loadAd(AdRequest.Builder().build())
                    }
                }
            }
        } catch (_: Exception) {
            // Camera remains usable if ads fail to initialize.
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
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
            setPadding(dp(8), dp(3), dp(8), dp(3))
            maxLines = 1
        }

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        )

        // Camera preview with mini map in the corner.
        val cameraFrame = FrameLayout(this)

        previewView = PreviewView(this).apply {
            implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
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
            contentDescription = "Current GPS location map"
        }

        val mapParams = FrameLayout.LayoutParams(
            dp(130),
            dp(150),
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(10), dp(10), 0)
        }

        cameraFrame.addView(miniMapView, mapParams)

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
            ellipsize = android.text.TextUtils.TruncateAt.END
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

        val mapButton = Button(this).apply {
            text = "OPEN MAP"
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            setOnClickListener { openMap() }
        }

        fun buttonParams() = LinearLayout.LayoutParams(
            0,
            dp(44),
            1f
        ).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }

        buttons.addView(captureButton, buttonParams())
        buttons.addView(flipButton, buttonParams())
        buttons.addView(mapButton, buttonParams())

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

        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            if (isFinishing || isDestroyed) return@addListener

            try {
                val provider = providerFuture.get()

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

                val rotation =
                    previewView.display?.rotation ?: Surface.ROTATION_0

                val capture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .setTargetRotation(rotation)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    capture
                )

                cameraProvider = provider
                imageCapture = capture

                captureButton.isEnabled = true
                flipButton.isEnabled = true

                flipButton.text =
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        "FRONT CAMERA"
                    } else {
                        "BACK CAMERA"
                    }

            } catch (e: Exception) {
                toast("Camera start nahi hua: ${e.message ?: "Unknown error"}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        if (isCapturing) {
            toast("Photo save ho rahi hai, wait karo")
            return
        }

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
            val provider = cameraProvider
            if (provider != null && !provider.hasCamera(nextSelector)) {
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
    // GPS AND MINI MAP
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

                    if (location != null) {
                        currentLocation = location
                        showLocation()
                        resolveAddress(location)
                    } else {
                        locationText.text =
                            "Location nahi mili. GPS on karke try karo."
                    }
                }
                .addOnFailureListener {
                    if (!isFinishing && !isDestroyed) {
                        locationText.text = "GPS location nahi mil saki"
                    }
                }
        } catch (_: SecurityException) {
            locationText.text = "GPS permission check karo"
        }
    }

    private fun showLocation() {
        val location = currentLocation ?: return

        locationText.text =
            "$currentAddress\nLat: ${location.latitude}  Lon: ${location.longitude}"

        updateMiniMap(location)
    }

    private fun updateMiniMap(location: Location) {
        if (!::miniMapView.isInitialized) return

        val point = GeoPoint(location.latitude, location.longitude)

        miniMapView.controller.setCenter(point)

        if (mapMarker == null) {
            mapMarker = Marker(miniMapView).apply {
                title = "Current GPS Location"
                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
                )
            }
            miniMapView.overlays.add(mapMarker)
        }

        mapMarker?.position = point
        miniMapView.invalidate()
    }

    // --------------------------------------------------
    // ADDRESS LOOKUP
    // --------------------------------------------------

    private fun resolveAddress(location: Location) {
        val prefs = getSharedPreferences("gps_camera_cache", MODE_PRIVATE)

        val cachedLat = prefs.getString("lat", null)?.toDoubleOrNull()
        val cachedLon = prefs.getString("lon", null)?.toDoubleOrNull()
        val cachedAddress = prefs.getString("address", null)

        val cacheIsNearby =
            cachedLat != null &&
                cachedLon != null &&
                !cachedAddress.isNullOrBlank() &&
                distanceMeters(
                    location.latitude,
                    location.longitude,
                    cachedLat,
                    cachedLon
                ) < 500.0

        if (cacheIsNearby) {
            currentAddress = cachedAddress!!
            showLocation()
        } else {
            currentAddress = "Finding address..."
            showLocation()
        }

        if (!Geocoder.isPresent()) {
            if (!cacheIsNearby) {
                currentAddress = "Address unavailable"
                showLocation()
            }
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
                                    applyAddressResult(addresses, location)
                                }
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            runOnUiThread {
                                if (
                                    !isFinishing &&
                                    !isDestroyed &&
                                    currentAddress == "Finding address..."
                                ) {
                                    currentAddress = "Address unavailable"
                                    showLocation()
                                }
                            }
                        }
                    }
                )
            } catch (_: Exception) {
                if (!cacheIsNearby) {
                    currentAddress = "Address unavailable"
                    showLocation()
                }
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
                            applyAddressResult(addresses.orEmpty(), location)
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (
                            !isFinishing &&
                            !isDestroyed &&
                            currentAddress == "Finding address..."
                        ) {
                            currentAddress = "Address unavailable"
                            showLocation()
                        }
                    }
                }
            }
        }
    }

    private fun applyAddressResult(
        addresses: List<Address>,
        location: Location
    ) {
        val address = addresses.firstOrNull()

        val formatted = address?.let {
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
        }

        if (!formatted.isNullOrBlank()) {
            currentAddress = formatted

            getSharedPreferences("gps_camera_cache", MODE_PRIVATE)
                .edit()
                .putString("lat", location.latitude.toString())
                .putString("lon", location.longitude.toString())
                .putString("address", formatted)
                .apply()
        } else if (currentAddress == "Finding address...") {
            currentAddress = "Address unavailable"
        }

        showLocation()
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    // --------------------------------------------------
    // TAKE PHOTO
    // --------------------------------------------------

    private fun takePhoto() {
        if (isCapturing) return

        val capture = imageCapture
        if (capture == null) {
            toast("Camera abhi ready nahi hai")
            return
        }

        if (!hasPermission(Manifest.permission.CAMERA)) {
            toast("Camera permission zaroori hai")
            requestAppPermissions()
            return
        }

        isCapturing = true
        captureButton.isEnabled = false
        flipButton.isEnabled = false

        val photoFile = File(
            cacheDir,
            "gps_${System.currentTimeMillis()}.jpg"
        )

        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        try {
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {

                    override fun onImageSaved(
                        output: ImageCapture.OutputFileResults
                    ) {
                        var original: Bitmap? = null
                        var corrected: Bitmap? = null
                        var watermarked: Bitmap? = null

                        try {
                            original = BitmapFactory.decodeFile(
                                photoFile.absolutePath
                            ) ?: throw IllegalStateException(
                                "Photo decode nahi hui"
                            )

                            corrected = correctPhotoOrientation(
                                original,
                                photoFile
                            )

                            // Wait for map layout/rendering before drawing it.
                            if (::miniMapView.isInitialized) {
                                miniMapView.invalidate()
                            }

                            watermarked = addWatermark(corrected)
                            saveToGallery(watermarked)

                        } catch (e: Exception) {
                            toast(
                                "Photo error: ${e.message ?: "Unknown error"}"
                            )
                        } finally {
                            if (
                                watermarked != null &&
                                watermarked !== corrected &&
                                watermarked !== original
                            ) {
                                watermarked.recycle()
                            }

                            if (
                                corrected != null &&
                                corrected !== original
                            ) {
                                corrected.recycle()
                            }

                            original?.recycle()
                            photoFile.delete()

                            isCapturing = false

                            if (!isFinishing && !isDestroyed) {
                                captureButton.isEnabled = imageCapture != null
                                flipButton.isEnabled = cameraProvider != null
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        photoFile.delete()
                        isCapturing = false

                        if (!isFinishing && !isDestroyed) {
                            captureButton.isEnabled = imageCapture != null
                            flipButton.isEnabled = cameraProvider != null
                            toast(
                                "Photo nahi li ja saki: " +
                                    (exception.message ?: "Unknown error")
                            )
                        }
                    }
                }
            )
        } catch (e: Exception) {
            photoFile.delete()
            isCapturing = false
            captureButton.isEnabled = imageCapture != null
            flipButton.isEnabled = cameraProvider != null
            toast("Capture error: ${e.message ?: "Unknown error"}")
        }
    }

    // --------------------------------------------------
    // EXIF ORIENTATION CORRECTION
    // --------------------------------------------------

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
            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.postRotate(90f)

            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.postRotate(180f)

            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.postRotate(270f)

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
    // WATERMARK + MAP ON SAVED PHOTO
    // --------------------------------------------------

    private fun addWatermark(source: Bitmap): Bitmap {
        val result = source.copy(
            Bitmap.Config.ARGB_8888,
            true
        ) ?: throw IllegalStateException("Bitmap create nahi hua")

        val canvas = Canvas(result)
        val width = result.width.toFloat()
        val height = result.height.toFloat()

        val padding = (width * 0.025f).coerceAtLeast(8f)
        val brandSize = (width * 0.023f).coerceAtLeast(12f)
        val textSize = (width * 0.023f).coerceAtLeast(11f)
        val lineHeight = textSize * 1.45f

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 0, 0, 0)
        }

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = brandSize
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            textAlign = Paint.Align.CENTER
        }

        // Top branding.
        val topHeight = brandSize + padding * 1.5f

        canvas.drawRect(
            0f,
            0f,
            width,
            topHeight,
            backgroundPaint
        )

        canvas.drawText(
            "DEVELOPED BY SADA REGON",
            width / 2f,
            padding + brandSize * 0.85f,
            brandPaint
        )

        val location = currentLocation
        val date = SimpleDateFormat(
            "dd MMM yyyy, hh:mm:ss a",
            Locale.getDefault()
        ).format(Date())

        // Reserve space on the right for the map.
        val mapWidth = (width * 0.29f).toInt()
        val mapHeight = (height * 0.19f).toInt()
        val mapGap = padding

        val mapLeft = width - mapWidth - padding
        val mapTop = height - mapHeight - padding
        val textMaxWidth = (mapLeft - padding * 2).coerceAtLeast(1f)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }

        val detailLines = mutableListOf<String>()

        val address = currentAddress
        if (address.isNotBlank()) {
            detailLines.addAll(
                wrapText(address, textPaint, textMaxWidth)
            )
        }

        if (location != null) {
            detailLines.add("Lat: ${location.latitude}")
            detailLines.add("Lon: ${location.longitude}")
        } else {
            detailLines.add("GPS unavailable")
        }

        detailLines.add(date)

        val maximumLines = (
            (mapHeight - padding).toFloat() / lineHeight
        ).toInt().coerceAtLeast(2)

        val visibleLines = detailLines.take(maximumLines)

        val detailsTop = (height - mapHeight - padding).coerceAtLeast(topHeight)

        canvas.drawRect(
            0f,
            detailsTop,
            width,
            height,
            backgroundPaint
        )

        visibleLines.forEachIndexed { index, line ->
            val y = detailsTop + padding + textSize + index * lineHeight

            if (y < height - padding) {
                canvas.drawText(
                    line,
                    padding,
                    y,
                    textPaint
                )
            }
        }

        // Render the mini map into the saved image.
        if (
            ::miniMapView.isInitialized &&
            miniMapView.width > 0 &&
            miniMapView.height > 0 &&
            mapWidth > 0 &&
            mapHeight > 0
        ) {
            var mapBitmap: Bitmap? = null

            try {
                mapBitmap = Bitmap.createBitmap(
                    mapWidth,
                    mapHeight,
                    Bitmap.Config.ARGB_8888
                )

                val mapCanvas = Canvas(mapBitmap)

                mapCanvas.scale(
                    mapWidth.toFloat() / miniMapView.width,
                    mapHeight.toFloat() / miniMapView.height
                )

                miniMapView.draw(mapCanvas)

                canvas.drawBitmap(
                    mapBitmap,
                    mapLeft,
                    mapTop,
                    Paint(Paint.ANTI_ALIAS_FLAG)
                )

                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = (width * 0.003f).coerceAtLeast(2f)
                }

                canvas.drawRect(
                    mapLeft,
                    mapTop,
                    mapLeft + mapWidth,
                    mapTop + mapHeight,
                    borderPaint
                )
            } catch (_: Exception) {
                // Keep the photo even if map rendering fails.
            } finally {
                mapBitmap?.recycle()
            }
        }

        return result
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        if (paint.measureText(text) <= maxWidth) {
            return listOf(text)
        }

        val result = mutableListOf<String>()
        var current = ""

        for (word in text.split(" ")) {
            val candidate = if (current.isEmpty()) {
                word
            } else {
                "$current $word"
            }

            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) result.add(current)
                current = word

                while (
                    current.isNotEmpty() &&
                    paint.measureText(current) > maxWidth
                ) {
                    var cut = current.length

                    while (
                        cut > 1 &&
                        paint.measureText(current.substring(0, cut)) > maxWidth
                    ) {
                        cut--
                    }

                    result.add(current.substring(0, cut))
                    current = current.substring(cut)
                }
            }
        }

        if (current.isNotEmpty()) result.add(current)

        return result
    }

    // --------------------------------------------------
    // SAVE TO GALLERY
    // --------------------------------------------------

    private fun saveToGallery(bitmap: Bitmap) {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            throw SecurityException("Storage permission nahi mili")
        }

        val filename = "GPS_Camera_${System.currentTimeMillis()}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/GPS Camera"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Gallery mein photo save nahi hui")

        try {
            val stream = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Output stream unavailable")

            stream.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) {
                    throw IllegalStateException("JPEG save failed")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }

                contentResolver.update(uri, completed, null, null)
            }

            toast("Photo Gallery mein save ho gayi!")

        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    // --------------------------------------------------
    // OPEN MAP APP
    // --------------------------------------------------

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

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Map app available nahi hai")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    // --------------------------------------------------
    // MAP LIFECYCLE
    // --------------------------------------------------

    override fun onResume() {
        super.onResume()

        if (::miniMapView.isInitialized) {
            miniMapView.onResume()
        }
    }

    override fun onPause() {
        if (::miniMapView.isInitialized) {
            miniMapView.onPause()
        }

        super.onPause()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()

        if (::addressExecutor.isInitialized) {
            addressExecutor.shutdown()
        }

        if (::adView.isInitialized) {
            adView.destroy()
        }

        if (::miniMapView.isInitialized) {
            miniMapView.onDetach()
        }

        super.onDestroy()
    }
}
