package com.regon.sadagpscamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import android.view.ViewGroup
import android.widget.Button
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
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.location.LocationServices
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
    private lateinit var addressExecutor: ExecutorService

    private lateinit var imageCapture: ImageCapture

    private var cameraProvider: ProcessCameraProvider? = null
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
                captureButton.isEnabled = false
                flipButton.isEnabled = false
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

        createUI()

        try {
            System.loadLibrary("sada-native")
            getNativeMessage()
        } catch (_: UnsatisfiedLinkError) {
            toast("C++ native engine load nahi hua")
        } catch (_: Exception) {
            toast("Native engine initialize nahi hua")
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
            // Ads unavailable hone par camera kaam kar sakta hai.
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    // --------------------------------------------------
    // USER INTERFACE
    // --------------------------------------------------

    private fun createUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = TextView(this).apply {
            text = "SADA GPS CAMERA"
            textSize = 22f
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
            implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
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
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(6, 10, 6, 10)
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
            setPadding(4, 6, 4, 6)
        }

        captureButton = Button(this).apply {
            text = "TAKE PHOTO"
            textSize = 10f
            isEnabled = false
            setOnClickListener {
                takePhoto()
            }
        }

        flipButton = Button(this).apply {
            text = "FLIP CAMERA"
            textSize = 10f
            isEnabled = false
            setOnClickListener {
                flipCamera()
            }
        }

        val mapButton = Button(this).apply {
            text = "OPEN MAP"
            textSize = 10f
            setOnClickListener {
                openMap()
            }
        }

        val buttonParams = LinearLayout.LayoutParams(
            0,
            56,
            1f
        ).apply {
            setMargins(2, 0, 2, 0)
        }

        buttons.addView(captureButton, buttonParams)
        buttons.addView(flipButton, buttonParams)
        buttons.addView(mapButton, buttonParams)

        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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
                ViewGroup.LayoutParams.WRAP_CONTENT
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
            permissions.add(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
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
    // CAMERA START / FRONT-BACK SWITCH
    // --------------------------------------------------

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (isFinishing || isDestroyed) return

        captureButton.isEnabled = false
        flipButton.isEnabled = false

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            if (isFinishing || isDestroyed) return@addListener

            try {
                val provider = providerFuture.get()
                cameraProvider = provider

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
                preview.setSurfaceProvider(
                    previewView.surfaceProvider
                )

                val newImageCapture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .build()

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    newImageCapture
                )

                imageCapture = newImageCapture

                captureButton.isEnabled = true
                flipButton.isEnabled = true

                flipButton.text =
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        "FRONT CAMERA"
                    } else {
                        "BACK CAMERA"
                    }

            } catch (e: Exception) {
                captureButton.isEnabled = false
                flipButton.isEnabled = false

                toast(
                    "Camera start nahi hua: " +
                        (e.message ?: "Unknown error")
                )
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        if (isCapturing) {
            toast("Photo save ho rahi hai, thoda wait karo")
            return
        }

        val provider = cameraProvider

        if (provider == null) {
            toast("Camera abhi ready nahi hai")
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
            if (!provider.hasCamera(nextSelector)) {
                toast("Ye camera device par available nahi hai")
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
    // GPS LOCATION
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

                    if (isFinishing || isDestroyed) {
                        return@addOnSuccessListener
                    }

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
            "$currentAddress\n" +
                "Lat: ${location.latitude}   " +
                "Lon: ${location.longitude}"
    }

    // --------------------------------------------------
    // ADDRESS LOOKUP
    // --------------------------------------------------

    private fun resolveAddress(location: Location) {
        val prefs = getSharedPreferences(
            "sada_gps_cache",
            MODE_PRIVATE
        )

        val cachedLat =
            prefs.getString("lat", null)?.toDoubleOrNull()
        val cachedLon =
            prefs.getString("lon", null)?.toDoubleOrNull()
        val cachedAddress =
            prefs.getString("address", null)

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
            currentAddress = "Finding area..."
            showLocation()
        }

        if (!Geocoder.isPresent()) {
            if (!cacheIsNearby) {
                currentAddress = "Address unavailable offline"
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

                        override fun onGeocode(
                            addresses: MutableList<Address>
                        ) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    applyAddressResult(
                                        addresses,
                                        location
                                    )
                                }
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            runOnUiThread {
                                if (
                                    !isFinishing &&
                                    !isDestroyed &&
                                    currentAddress == "Finding area..."
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
                            applyAddressResult(
                                addresses.orEmpty(),
                                location
                            )
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (
                            !isFinishing &&
                            !isDestroyed &&
                            currentAddress == "Finding area..."
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
        val item = addresses.firstOrNull()

        val formatted = item?.let { address ->
            listOfNotNull(
                address.subLocality,
                address.locality,
                address.subAdminArea,
                address.adminArea,
                address.countryName
            )
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
        }

        if (!formatted.isNullOrBlank()) {
            currentAddress = formatted

            getSharedPreferences(
                "sada_gps_cache",
                MODE_PRIVATE
            )
                .edit()
                .putString("lat", location.latitude.toString())
                .putString("lon", location.longitude.toString())
                .putString("address", formatted)
                .apply()

        } else if (currentAddress == "Finding area...") {
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

        Location.distanceBetween(
            lat1,
            lon1,
            lat2,
            lon2,
            result
        )

        return result[0].toDouble()
    }

    // --------------------------------------------------
    // TAKE PHOTO
    // --------------------------------------------------

    private fun takePhoto() {
        if (isCapturing) return

        if (!::imageCapture.isInitialized) {
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
            "sada_${System.currentTimeMillis()}.jpg"
        )

        val options =
            ImageCapture.OutputFileOptions.Builder(photoFile).build()

        try {
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {

                    override fun onImageSaved(
                        output: ImageCapture.OutputFileResults
                    ) {
                        var original: Bitmap? = null
                        var watermarked: Bitmap? = null

                        try {
                            original = BitmapFactory.decodeFile(
                                photoFile.absolutePath
                            ) ?: throw IllegalStateException(
                                "Photo decode nahi hui"
                            )

                            watermarked = addWatermark(original)
                            saveToGallery(watermarked)

                        } catch (e: Exception) {
                            toast(
                                "Photo error: " +
                                    (e.message ?: "Unknown error")
                            )
                        } finally {
                            if (
                                watermarked != null &&
                                watermarked !== original
                            ) {
                                watermarked.recycle()
                            }

                            original?.recycle()
                            photoFile.delete()
                            isCapturing = false

                            if (!isFinishing && !isDestroyed) {
                                captureButton.isEnabled =
                                    ::imageCapture.isInitialized
                                flipButton.isEnabled =
                                    cameraProvider != null
                            }
                        }
                    }

                    override fun onError(
                        exception: ImageCaptureException
                    ) {
                        photoFile.delete()
                        isCapturing = false

                        if (!isFinishing && !isDestroyed) {
                            captureButton.isEnabled =
                                ::imageCapture.isInitialized
                            flipButton.isEnabled =
                                cameraProvider != null

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

            captureButton.isEnabled =
                ::imageCapture.isInitialized
            flipButton.isEnabled =
                cameraProvider != null

            toast(
                "Capture error: " +
                    (e.message ?: "Unknown error")
            )
        }
    }

    // --------------------------------------------------
    // GPS WATERMARK
    // --------------------------------------------------

    private fun addWatermark(source: Bitmap): Bitmap {
        val result = source.copy(
            Bitmap.Config.ARGB_8888,
            true
        ) ?: throw IllegalStateException(
            "Watermark bitmap create nahi hua"
        )

        val canvas = Canvas(result)
        val location = currentLocation

        val date = SimpleDateFormat(
            "dd MMM yyyy, hh:mm:ss a",
            Locale.getDefault()
        ).format(Date())

        val gpsLines =
            if (location != null) {
                listOf(
                    "Lat: ${location.latitude}",
                    "Lon: ${location.longitude}"
                )
            } else {
                listOf("GPS location unavailable")
            }

        val lines = listOf(
            "SADA GPS CAMERA",
            currentAddress
        ) + gpsLines + date

        val padding = result.width * 0.025f
        val textSize =
            (result.width * 0.032f).coerceAtLeast(16f)
        val lineHeight = textSize * 1.45f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val maxTextWidth =
            (result.width - padding * 2).coerceAtLeast(1f)

        val safeLines = lines.map { line ->
            var value = line

            while (
                value.isNotEmpty() &&
                paint.measureText(value) > maxTextWidth
            ) {
                value = value.dropLast(1)
            }

            value
        }

        val boxHeight =
            padding * 2 + lineHeight * safeLines.size

        val top =
            (result.height - boxHeight).coerceAtLeast(0f)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 0, 0, 0)
        }

        canvas.drawRect(
            0f,
            top,
            result.width.toFloat(),
            result.height.toFloat(),
            backgroundPaint
        )

        safeLines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                padding,
                top + padding + textSize + index * lineHeight,
                paint
            )
        }

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
            throw SecurityException(
                "Storage permission nahi mili"
            )
        }

        val filename =
            "SADA_GPS_${System.currentTimeMillis()}.jpg"

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                filename
            )
            put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/jpeg"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/SADA GPS Camera"
                )
                put(
                    MediaStore.Images.Media.IS_PENDING,
                    1
                )
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException(
            "Gallery mein photo save nahi hui"
        )

        try {
            val stream = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException(
                    "Output stream unavailable"
                )

            stream.use {
                if (
                    !bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        95,
                        it
                    )
                ) {
                    throw IllegalStateException("JPEG save failed")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completed = ContentValues().apply {
                    put(
                        MediaStore.Images.Media.IS_PENDING,
                        0
                    )
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
    // OPEN MAP
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

    override fun onDestroy() {
        cameraProvider?.unbindAll()

        if (::addressExecutor.isInitialized) {
            addressExecutor.shutdown()
        }

        if (::adView.isInitialized) {
            adView.destroy()
        }

        super.onDestroy()
    }
}
