package io.github.mwarevn.movingsimulation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.R
import io.github.mwarevn.movingsimulation.network.OsrmClient
import io.github.mwarevn.movingsimulation.network.RoutingService
import io.github.mwarevn.movingsimulation.network.VehicleType
import io.github.mwarevn.movingsimulation.utils.NavigationForegroundService
import io.github.mwarevn.movingsimulation.utils.PolylineUtils
import io.github.mwarevn.movingsimulation.utils.PrefManager
import io.github.mwarevn.movingsimulation.utils.RouteSimulator
import io.github.mwarevn.movingsimulation.utils.ext.showToast
import kotlinx.coroutines.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.*

/**
 * Clean redesigned MapActivity with Google Maps-like UX
 */
class MapActivity : BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener, GoogleMap.OnMarkerDragListener {

    // ========== Constants & Configuration ==========
    companion object {
        // Permission request codes
        private const val LOCATION_PERMISSION_REQUEST_CODE = 99

        // Route colors
        const val ROUTE_COLOR = "#006eff" // Blue route line
        const val ROUTE_WIDTH = 18f // Route line width
        const val COMPLETED_ROUTE_COLOR = "#909090" // Gray completed path

        // Fake location (stationary) circle colors - Orange Red
        const val FAKE_LOCATION_STROKE_COLOR = 0xFFFF4500.toInt() // Orange Red stroke (solid)
        const val FAKE_LOCATION_FILL_COLOR = 0x50FF4500.toInt()   // Semi-transparent orange red fill
        const val FAKE_LOCATION_CENTER_COLOR = 0xFFFF4500.toInt() // Solid orange red center dot

        // Circle dimensions
        const val CIRCLE_RADIUS = 25.0              // Outer circle radius in meters (increased from 20.0)
        const val CIRCLE_STROKE_WIDTH = 4f          // Outer circle border width (increased from 3f)
        const val CIRCLE_Z_INDEX = 128f             // Outer circle Z-index
        const val CENTER_DOT_Z_INDEX = 129f         // Center dot Z-index (higher = on top)
    }

    // Map & Markers
    private lateinit var mMap: GoogleMap
    private var destMarker: Marker? = null
    private var startMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // Center dot marker for fake location circle
    private var fakeLocationCenterDot: Marker? = null

    // Route simulation
    private var routeSimulator: RouteSimulator? = null
    private var routePoints: List<LatLng> = emptyList()
    private var isDriving = false
    private var isPaused = false
    private var currentSpeed = 52.0 // Default motorbike speed

    // Distance tracking
    private var totalRouteDistanceKm = 0.0 // Total route distance in kilometers
    private var traveledDistanceKm = 0.0 // Distance traveled so far in kilometers
    private var lastDistancePosition: LatLng? = null // Last position for distance calculation

    // Movement tracking
    private var currentPositionMarker: Marker? = null
    private var fakeLocationMarker: Marker? = null // Marker for fake GPS location (persistent)
    private var fakeLocationCircle: Circle? = null // Circle to mark fake GPS location
    private var completedPolyline: Polyline? = null
    // Accumulated points for the completed (traversed) route to keep it aligned with the moving circle
    private val completedPathPoints: MutableList<LatLng> = mutableListOf()
    private var currentPositionIndex = 0
    private var isGpsSet = false // Track GPS state locally for immediate UI update
    private var currentFakeLocationPos: LatLng? = null // Store current fake location for quick use

    // Joystick position monitoring
    private var lastJoystickLat = 0.0
    private var lastJoystickLon = 0.0
    private var isActivityVisible = false // Track if activity is visible
    
    // CRITICAL FIX: Use separate scope for RouteSimulator to continue running in background
    // This scope will only be cancelled when Activity is destroyed, not when paused
    // Use Dispatchers.Default for CPU-bound work, ensures it runs even when app is backgrounded
    private val navigationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        // Listen for GPS position changes from joystick
        if (key == "latitude" || key == "longitude") {
            if (!isDriving && isGpsSet) {
                val currentLat = PrefManager.getLat
                val currentLon = PrefManager.getLng

                // Check if position changed
                val latDiff = Math.abs(currentLat - lastJoystickLat)
                val lonDiff = Math.abs(currentLon - lastJoystickLon)

                if (latDiff > 0.0000001 || lonDiff > 0.0000001) {
                    lastJoystickLat = currentLat
                    lastJoystickLon = currentLon

                    val newPosition = LatLng(currentLat, currentLon)

                    // Update fake location marker on map (even if activity is not visible)
                    // Map updates will be queued and applied when activity becomes visible
                    lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            if (fakeLocationCircle != null && fakeLocationCenterDot != null) {
                                fakeLocationCircle?.center = newPosition
                                fakeLocationCenterDot?.position = newPosition
                                currentFakeLocationPos = newPosition
                                android.util.Log.d("MapActivity", "Joystick updated GPS marker to: $newPosition (visible: $isActivityVisible)")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MapActivity", "Error updating marker from joystick: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // Camera follow throttling - Optimized for better performance
    private var lastCameraUpdateTime = 0L
    private val CAMERA_UPDATE_INTERVAL_MS = 1000L // Increased from 500ms to 1000ms for better performance

    // Camera follow mode (only relevant during navigation)
    private var isCameraFollowing = true

    // GPS stability improvements
    private var lastGpsUpdateTime = 0L
    private var previousLocation: LatLng? = null
    private var currentNavigationPosition: LatLng? = null // Track current position during navigation

    // Background state management
    private var wasNavigatingBeforeBackground = false
    private var backgroundNavigationState: NavigationState? = null

    data class NavigationState(
        val routePoints: List<LatLng>,
        val currentSpeed: Double,
        val isPaused: Boolean,
        val currentPositionIndex: Int,
        val completedPathPoints: List<LatLng>
    )

    // UI State
    private enum class AppMode {
        SEARCH,      // Initial: searching for destination
        ROUTE_PLAN,  // Planning route: selecting start point
        NAVIGATION   // Navigating along route
    }
    private var currentMode = AppMode.SEARCH
    private var hasSelectedStartPoint = false // Track if start point was clicked (not dragged)

    // Search jobs
    private var searchJob: Job? = null

    // Route loading state
    private var isLoadingRoute = false
    private var routeLoadError: String? = null

    // Route cache to avoid redundant API calls when swapping
    private data class RouteCacheKey(
        val startLat: Double,
        val startLng: Double,
        val destLat: Double,
        val destLng: Double,
        val vehicleType: VehicleType
    ) {
        companion object {
            // Helper to create key from markers
            fun fromMarkers(start: LatLng, dest: LatLng, vehicleType: VehicleType): RouteCacheKey {
                return RouteCacheKey(
                    startLat = start.latitude,
                    startLng = start.longitude,
                    destLat = dest.latitude,
                    destLng = dest.longitude,
                    vehicleType = vehicleType
                )
            }
        }
    }

    private data class RouteCacheEntry(
        val routePoints: List<LatLng>,
        val serviceName: String,
        val isFallback: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val routeCache = mutableMapOf<RouteCacheKey, RouteCacheEntry>()
    private val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes cache

    // Routing service (hybrid MapBox + OSRM)
    // Always get fresh instance with latest API key from preferences
    private val routingService: RoutingService
        get() {
            val apiKey = PrefManager.mapBoxApiKey
            return OsrmClient.createRoutingService(mapBoxApiKey = apiKey)
        }

    // Current vehicle type (can be changed by user later)
    private var currentVehicleType: VehicleType
        get() = VehicleType.fromString(PrefManager.vehicleType)
        set(value) { PrefManager.vehicleType = value.name }

    /**
     * Create a circular bitmap icon for center dot that maintains size at all zoom levels
     * Similar to Google Maps real location icon with white border
     */
    private fun createCenterDotIcon(color: Int, sizePx: Int = 48): BitmapDescriptor {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        // Draw white border (outer circle)
        val borderPaint = Paint().apply {
            this.color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
        canvas.drawCircle(radius, radius, radius, borderPaint)

        // Draw colored center (inner circle)
        val centerPaint = Paint().apply {
            this.color = color
            isAntiAlias = true
        }
        val innerRadius = radius * 0.75f // 75% of outer radius
        canvas.drawCircle(radius, radius, innerRadius, centerPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * Calculate bearing between two lat/lng points
     * Returns bearing in degrees (0-360)
     */
    private fun calculateBearing(start: LatLng, end: LatLng): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val deltaLng = Math.toRadians(end.longitude - start.longitude)

        val y = Math.sin(deltaLng) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLng)

        var bearing = Math.toDegrees(Math.atan2(y, x))
        if (bearing < 0) {
            bearing += 360.0
        }
        return bearing.toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure GPS settings
        // io.github.mwarevn.movingsimulation.utils.PrefManager.isRandomPosition = false
        // io.github.mwarevn.movingsimulation.utils.PrefManager.isSystemHooked = false
        // io.github.mwarevn.movingsimulation.utils.PrefManager.accuracy = "15"
    }

    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment.getMapAsync(this)
    }

    override fun getActivityInstance() = this

    override fun hasMarker() = false

    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        // Not used in new design
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Configure map
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
            // Get current location
            getCurrentLocation()
        } else {
            // Request permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // IMPORTANT: Request POST_NOTIFICATIONS permission for Android 13+ (required for foreground service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    LOCATION_PERMISSION_REQUEST_CODE + 1 // Use different request code
                )
            }
        }

        mMap.uiSettings.apply {
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
            isCompassEnabled = false  // Enable built-in compass
        }

        mMap.setOnMapClickListener(this)
        mMap.setOnMarkerDragListener(this)

        // Track camera bearing for joystick screen-relative movement
        mMap.setOnCameraMoveListener {
            val bearing = mMap.cameraPosition.bearing
            PrefManager.cameraBearing = bearing
        }

        setupButtons()
        setupSearchBoxes()

        // Restore fake location marker if it was set before app was closed
        restoreFakeLocationMarker()
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val locationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            locationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    lat = location.latitude
                    lon = location.longitude
                    // Move camera to current location
                    val currentLatLng = LatLng(lat, lon)
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                } else {
                    // Fallback to default location (Hanoi)
                    lat = 21.0285
                    lon = 105.8542
                    showToast(getString(R.string.error_location_unavailable_using_default))
                }
            }
        } catch (e: Exception) {
            // Fallback to default location
            lat = 21.0285
            lon = 105.8542
            showToast(getString(R.string.error_location_fetch, e.message))
        }
    }

    override fun onMapClick(position: LatLng) {
        when (currentMode) {
            AppMode.SEARCH -> {
                // Always allow clicking to set/change destination in SEARCH mode
                setDestinationMarker(position)
                if (destMarker != null) {
                    // showToast("Destination selected. Press 'Get Directions' to plan route")
                }
            }
            AppMode.ROUTE_PLAN -> {
                if (!hasSelectedStartPoint && destMarker != null) {
                    // First click in PLAN mode - set start point
                    setStartMarkerWithSelection(position)
                    // Button visibility will be updated in setStartMarker()
                } else if (hasSelectedStartPoint) {
                    // After first click - only allow drag/drop for fine-tuning
                    // User can see the marker, no need for toast
                } else {
                    showToast(getString(R.string.instruction_select_dest_first))
                }
            }
            AppMode.NAVIGATION -> {
                // Prevent any map interactions during navigation
                showToast(getString(R.string.error_cannot_change_dest_moving))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSearchBoxes() {
        // Destination search (always visible)
        // Add TextWatcher to show/hide clear icon
        binding.destinationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Show clear icon only when there's text
                if (s.isNullOrEmpty()) {
                    binding.destinationSearch.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                } else {
                    // Use custom smaller icon
                    binding.destinationSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0, 0, R.drawable.ic_clear_text, 0
                    )
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Handle click on clear icon
        binding.destinationSearch.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val editText = v as android.widget.EditText
                if (editText.text.isNotEmpty()) {
                    // Check if click is on the drawableEnd (clear icon)
                    val drawableEnd = editText.compoundDrawables[2]
                    if (drawableEnd != null) {
                        val clickX = event.x.toInt()
                        val drawableWidth = drawableEnd.intrinsicWidth
                        val drawableStart = editText.width - editText.paddingEnd - drawableWidth

                        if (clickX >= drawableStart) {
                            // Clicked on clear icon - clear destination
                            clearDestinationMarker()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }

        // Search only when user presses Enter/Done on keyboard
        binding.destinationSearch.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString().trim()
            if (text.isNotEmpty()) {
                lifecycleScope.launch {
                    searchLocation(text) { latLng ->
                        setDestinationMarker(latLng)
                    }
                }
            }
            true
        }

        // Start location search (shown in route mode)
        // Add TextWatcher to show/hide clear icon
        binding.startSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Show clear icon only when there's text
                if (s.isNullOrEmpty()) {
                    binding.startSearch.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                } else {
                    // Use custom smaller icon
                    binding.startSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0, 0, R.drawable.ic_clear_text, 0
                    )
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Handle click on clear icon
        binding.startSearch.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val editText = v as android.widget.EditText
                if (editText.text.isNotEmpty()) {
                    // Check if click is on the drawableEnd (clear icon)
                    val drawableEnd = editText.compoundDrawables[2]
                    if (drawableEnd != null) {
                        val clickX = event.x.toInt()
                        val drawableWidth = drawableEnd.intrinsicWidth
                        val drawableStart = editText.width - editText.paddingEnd - drawableWidth

                        if (clickX >= drawableStart) {
                            // Clicked on clear icon - clear start marker and text
                            startMarker?.remove()
                            startMarker = null
                            hasSelectedStartPoint = false
                            editText.text.clear()
                            updateSwapButtonVisibility()
                            updateUseCurrentLocationButtonVisibility()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }

        // Search only when user presses Enter/Done on keyboard
        binding.startSearch.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString().trim()
            if (text.isNotEmpty()) {
                lifecycleScope.launch {
                    searchLocation(text) { latLng ->
                        setStartMarkerWithSelection(latLng)
                    }
                }
            }
            true
        }
    }

    override fun setupButtons() {
        // Floating buttons are always visible by default in layout
        // Initialize set/unset button icon based on GPS state
        isGpsSet = viewModel.isStarted
        updateSetLocationButton()

        // Main action button (Get Directions / Start)
        binding.actionButton.setOnClickListener {
            when (currentMode) {
                AppMode.SEARCH -> {
                    // "Get Directions" button: enter route planning mode
                    if (destMarker != null) {
                        enterRoutePlanMode()
                    } else {
                        showToast("Please select a destination first")
                    }
                }
                AppMode.ROUTE_PLAN -> {
                    if (startMarker != null && destMarker != null) {
                        // Both markers set - start navigation
                        startNavigation()
                    } else if (destMarker != null && startMarker == null) {
                        // Only destination set - remind to pick start point
                        showToast(getString(R.string.error_select_start_on_map))
                    } else {
                        showToast(getString(R.string.error_select_start_and_dest))
                    }
                }
                AppMode.NAVIGATION -> {
                    // Stop navigation
                    stopNavigation()
                }
            }
        }

        // Get my location button
        binding.getlocation.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(lat, lon), 15f
                ))
            }
        }

        // Get fake location button
        binding.getFakeLocation.setOnClickListener {
            if (currentFakeLocationPos != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    currentFakeLocationPos!!, 15f
                ))
            }
        }

        // Set/Unset location button
        binding.setLocationButton.setOnClickListener {
            // Toggle GPS state
            android.util.Log.d("MapActivity", "Set/Unset clicked - isGpsSet: $isGpsSet")

            if (isGpsSet) {
                // Unset GPS - remove circle to avoid confusion
                android.util.Log.d("MapActivity", "Unsetting GPS")

                // Remove fake location marker, circle AND center dot FIRST (before updating state)
                fakeLocationMarker?.remove()
                fakeLocationMarker = null
                fakeLocationCircle?.remove()
                fakeLocationCircle = null
                fakeLocationCenterDot?.remove()
                fakeLocationCenterDot = null

                // Then update state
                isGpsSet = false
                currentFakeLocationPos = null
                viewModel.update(false, 0.0, 0.0)
                updateSetLocationButton()
            } else {
                // Set GPS to destination marker if exists, otherwise current map center
                val targetPosition = destMarker?.position ?: mMap.cameraPosition.target
                android.util.Log.d("MapActivity", "Setting GPS to: ${targetPosition.latitude}, ${targetPosition.longitude}")
                isGpsSet = true
                currentFakeLocationPos = targetPosition
                viewModel.update(true, targetPosition.latitude, targetPosition.longitude)
                updateSetLocationButton()
                // Show fake location circle
                updateFakeLocationMarker(targetPosition)
            }
        }

        // Replace Location Button - quickly change fake location without unset/set
        binding.replaceLocationButton.setOnClickListener {
            val targetPosition = destMarker?.position ?: mMap.cameraPosition.target
            android.util.Log.d("MapActivity", "Replacing GPS location to: ${targetPosition.latitude}, ${targetPosition.longitude}")

            // Update fake location directly
            currentFakeLocationPos = targetPosition
            viewModel.update(true, targetPosition.latitude, targetPosition.longitude)

            // Update visual marker
            updateFakeLocationMarker(targetPosition)

            // Update button visibility
            updateReplaceLocationButtonVisibility()

            showToast(getString(R.string.msg_fake_gps_replaced))
        }

        // Camera Follow Toggle Button (only works during navigation)
        binding.cameraFollowToggle.setOnClickListener {
            if (isDriving) {
                isCameraFollowing = !isCameraFollowing
                updateCameraFollowButton()
//                if (isCameraFollowing) {
//                    showToast("Camera follows location")
//                } else {
//                    showToast("Free camera")
//                }
            }
        }

        // Speed slider control
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val newSpeed = value.toDouble()
                // Prevent 0 speed during navigation to avoid stopping
                val actualSpeed = if (isDriving && newSpeed <= 0) 1.0 else newSpeed

                currentSpeed = actualSpeed
                updateSpeedLabel(actualSpeed)

                android.util.Log.d("MapActivity", "Speed changed to: $actualSpeed km/h (isDriving: $isDriving, isPaused: $isPaused)")

                // Update saved speed in SpeedSyncManager (preserves during curve reduction)
                try {
                    io.github.mwarevn.movingsimulation.utils.SpeedSyncManager.setSavedSpeed(actualSpeed.toFloat())
                } catch (e: Exception) {
                    // Silently fail if SpeedSyncManager not available
                }

                // Update speed in real-time if driving and not paused
                if (isDriving && !isPaused && routeSimulator != null) {
                    routeSimulator?.setSpeedKmh(actualSpeed)
                    android.util.Log.d("MapActivity", "RouteSimulator speed updated to: $actualSpeed km/h")
                }
                }
            }

        // Auto curve speed checkbox
        binding.autoCurveSpeedCheckbox.setOnCheckedChangeListener { _, isChecked ->
            try {
                PrefManager.autoCurveSpeed = isChecked
                android.util.Log.d("MapActivity", "Auto curve speed: $isChecked")
            } catch (e: Exception) {
                android.util.Log.e("MapActivity", "Error setting auto curve speed: ${e.message}")
            }
        }
        // Load initial state
        try {
            binding.autoCurveSpeedCheckbox.isChecked = PrefManager.autoCurveSpeed
        } catch (e: Exception) {
            // Default to true if error
            binding.autoCurveSpeedCheckbox.isChecked = true
        }

        // Pause button
        binding.pauseButton.setOnClickListener {
            if (isDriving && !isPaused) {
                // CRITICAL FIX: Force update completed path before pausing
                // This ensures the route is drawn up to the pause point
                forceUpdateCompletedPath()
                
                isPaused = true
                routeSimulator?.pause()
                binding.pauseButton.visibility = View.GONE
                binding.resumeButton.visibility = View.VISIBLE
                binding.stopButton.visibility = View.VISIBLE
                // showToast("Paused")
            }
        }

        // Resume button
        binding.resumeButton.setOnClickListener {
            if (isDriving && isPaused) {
                isPaused = false
                routeSimulator?.resume()
                // Ensure current speed is applied when resuming
                routeSimulator?.setSpeedKmh(currentSpeed)
                // Sync saved speed with SpeedSyncManager
                try {
                    io.github.mwarevn.movingsimulation.utils.SpeedSyncManager.setSavedSpeed(currentSpeed.toFloat())
                    updateSpeedLabel(currentSpeed)
                } catch (e: Exception) {
                    // Silently fail
                }
                binding.pauseButton.visibility = View.VISIBLE
                binding.resumeButton.visibility = View.GONE
                binding.stopButton.visibility = View.GONE
                // showToast("Resuming movement")
            }
        }

        // Stop button (shown when paused)
        binding.stopButton.setOnClickListener {
            if (isDriving && isPaused) {
                onStopNavigationEarly()
            }
        }

        // Completion action buttons
        binding.completedFinishButton.setOnClickListener {
            // Finish: clear route and markers, keep fake location circle
            onFinishNavigation()
        }

        binding.completedRestartButton.setOnClickListener {
            // Restart: resume navigation from current position
            onRestartNavigation()
        }

        // Quick use current fake location as start point
        binding.useCurrentLocationContainer.setOnClickListener {
            if (currentFakeLocationPos != null) {
                setStartMarkerWithSelection(currentFakeLocationPos!!)
                // Update button visibility (will hide since start point now matches fake GPS)
                updateUseCurrentLocationButtonVisibility()
                // showToast("Current location selected as start point")
            }
        }

        // Route error retry button
        binding.routeRetryButton.setOnClickListener {
            retryDrawRoute()
        }

        // Route error cancel button
        binding.routeErrorCancelButton.setOnClickListener {
            cancelRoutePlan()
        }

        // Cancel route button (next to action button)
        // Dual purpose: clear destination in SEARCH mode, cancel route in ROUTE_PLAN mode
        binding.cancelRouteButton.setOnClickListener {
            when (currentMode) {
                AppMode.SEARCH -> {
                    // In SEARCH mode: clear destination marker
                    clearDestinationMarker()
                }
                AppMode.ROUTE_PLAN -> {
                    // In ROUTE_PLAN mode: cancel route planning
                    cancelRoutePlan()
                }
                else -> {
                    // Should not happen, but handle gracefully
                    cancelRoutePlan()
                }
            }
        }

        // Swap locations button (exchange start and destination)
        binding.swapLocationsButton.setOnClickListener {
            swapStartAndDestination()
        }

        // Navigation controls toggle (collapse/expand)
        binding.navControlsToggle.setOnClickListener {
            toggleNavigationControls()
        }
        
        // Restore saved state
        restoreNavigationControlsState()

        android.util.Log.d("MapActivity", "========== setupButtons() END ==========")
    }

    private suspend fun searchLocation(query: String, onFound: (LatLng) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                // IMPORTANT FIX: Check if Geocoder is available
                if (!Geocoder.isPresent()) {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.error_geocoding_unavailable))
                    }
                    return@withContext
                }
                
                val geocoder = Geocoder(this@MapActivity, Locale.getDefault())
                // IMPORTANT FIX: Add timeout to prevent hanging
                val addresses = withTimeout(5000L) { // 5 second timeout
                    geocoder.getFromLocationName(query, 1)
                }

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)

                withContext(Dispatchers.Main) {
                    onFound(latLng)
                }
            } else {
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.error_location_not_found_query, query))
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            withContext(Dispatchers.Main) {
                showToast(getString(R.string.error_search_timeout))
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                showToast(getString(R.string.no_internet))
            }
        } catch (e: Exception) {
            android.util.Log.e("MapActivity", "Geocoding error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                val errorMsg = e.message ?: getString(R.string.error_unknown)
                showToast(getString(R.string.error_search_failed, errorMsg))
            }
        }
    }

    /**
     * Get full address string from coordinates (reverse geocoding)
     * Returns complete address with all available components
     * For named places, includes both name and street address for clarity
     */
    private suspend fun getAddressFromLocation(latLng: LatLng): String {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MapActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]

                    // Build full address from available components
                    val addressParts = mutableListOf<String>()

                    // 1. Feature name (if it's a named place, not just coordinates)
                    val featureName = address.featureName?.takeIf {
                        it.isNotBlank() && !it.matches(Regex("^[\\d,\\.\\s]+$"))
                    }

                    // 2. Street address (house number + street name)
                    val streetAddress = buildString {
                        address.subThoroughfare?.let { append("$it ") } // House number
                        address.thoroughfare?.let { append(it) } // Street name
                    }.trim()

                    // If we have both feature name and street address, combine them
                    // e.g., "Moc Huong, 89 Le Duc Tho" or "Sunsquare, 11/66/132 Cau Dien"
                    if (!featureName.isNullOrBlank() && streetAddress.isNotBlank()) {
                        addressParts.add("$featureName, $streetAddress")
                    } else {
                        // Add them separately if only one exists
                        featureName?.let { addressParts.add(it) }
                        if (streetAddress.isNotBlank()) {
                            addressParts.add(streetAddress)
                        }
                    }

                    // 3. Sub-locality (Ward)
                    address.subLocality?.let {
                        if (it.isNotBlank()) addressParts.add(it)
                    }

                    // 4. Locality (District)
                    address.locality?.let {
                        if (it.isNotBlank() && it != address.subLocality) addressParts.add(it)
                    }

                    // 5. Administrative area (Province/City)
                    address.adminArea?.let {
                        if (it.isNotBlank()) addressParts.add(it)
                    }

                    // 6. Country (if available)
                    address.countryName?.let {
                        if (it.isNotBlank()) addressParts.add(it)
                    }

                    // Join all parts with comma separator
                    if (addressParts.isNotEmpty()) {
                        return@withContext addressParts.joinToString(", ")
                    }
                }

                // Fallback: Coordinates
                "${String.format("%.6f", latLng.latitude)}, ${String.format("%.6f", latLng.longitude)}"
            } catch (e: Exception) {
                android.util.Log.e("MapActivity", "Error getting address: ${e.message}")
                // Fallback: Coordinates
                "${String.format("%.6f", latLng.latitude)}, ${String.format("%.6f", latLng.longitude)}"
            }
        }
    }

    private fun setDestinationMarker(position: LatLng) {
        // Remove old marker
        destMarker?.remove()

        // Add new marker - draggable in both SEARCH and ROUTE_PLAN mode
        destMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Destination")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .draggable(currentMode == AppMode.SEARCH || currentMode == AppMode.ROUTE_PLAN) // Draggable in SEARCH and ROUTE_PLAN modes
        )

        // Move camera
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))

        // Get address and fill into search box
        lifecycleScope.launch {
            val address = getAddressFromLocation(position)
            binding.destinationSearch.setText(address)
        }

        // Show "Get Directions" button AND close button in SEARCH mode
        if (currentMode == AppMode.SEARCH) {
            binding.actionButton.apply {
                text = "Get Directions"
                visibility = View.VISIBLE
                setIconResource(R.drawable.ic_navigation)
            }
            // Show close button to allow user to clear destination without routing
            binding.cancelRouteButton.apply {
                text = "✕"  // X character
                visibility = View.VISIBLE
            }
        }

        // Stay in SEARCH mode - only "Get Directions" button switches to PLAN mode
        // This allows user to keep clicking to change destination

        // If in route planning mode and destination changes, clear route
        if (routePolyline != null) {
            routePolyline?.remove()
            routePolyline = null
        }

        // Update swap button visibility
        updateSwapButtonVisibility()

        // Update replace location button visibility
        updateReplaceLocationButtonVisibility()
    }

    private fun clearDestinationMarker() {
        // Clear destination marker and reset to clean state
        destMarker?.remove()
        destMarker = null
        binding.destinationSearch.text.clear()

        // If in ROUTE_PLAN mode, need to reset everything back to SEARCH mode
        if (currentMode == AppMode.ROUTE_PLAN) {
            // Clear start marker and route
            startMarker?.remove()
            startMarker = null
            routePolyline?.remove()
            routePolyline = null
            routePoints = emptyList()
            hasSelectedStartPoint = false

            // Clear start search
            binding.startSearch.text.clear()

            // Hide route plan UI
            binding.startSearchContainer.visibility = View.GONE
            binding.useCurrentLocationContainer.visibility = View.GONE
            binding.routeLoadingCard.visibility = View.GONE
            binding.routeErrorCard.visibility = View.GONE

            // Reset to SEARCH mode
            currentMode = AppMode.SEARCH

            // Update markers to non-draggable
            updateMarkersDraggableState()
        }

        // Hide action buttons
        binding.actionButton.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE

        // Update swap button (will hide since destMarker is null)
        updateSwapButtonVisibility()

        // Update replace location button visibility
        updateReplaceLocationButtonVisibility()
    }

    private fun setStartMarker(position: LatLng) {
        // Remove old marker
        startMarker?.remove()

        // Add new marker - only draggable in ROUTE_PLAN mode
        startMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Start Point")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .draggable(currentMode == AppMode.ROUTE_PLAN) // Only draggable in route planning mode
        )

        // Get address and fill into start search box
        lifecycleScope.launch {
            val address = getAddressFromLocation(position)
            binding.startSearch.setText(address)
        }

        // Move camera to show both markers
        if (destMarker != null) {
            val bounds = LatLngBounds.builder()
                .include(position)
                .include(destMarker!!.position)
                .build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))

            // Draw route
            drawRoute()
        }

        // Update swap button visibility
        updateSwapButtonVisibility()

        // Update "use current location" button visibility
        updateUseCurrentLocationButtonVisibility()
    }

    /**
     * Set start marker and mark that user has selected start point
     */
    private fun setStartMarkerWithSelection(position: LatLng) {
        setStartMarker(position)
        hasSelectedStartPoint = true
    }

    /**
     * Update swap button visibility based on marker states
     */
    private fun updateSwapButtonVisibility() {
        // Show swap button only when both markers exist and not during navigation
        val shouldShowSwap = startMarker != null && destMarker != null && currentMode != AppMode.NAVIGATION
        binding.swapButtonContainer.visibility = if (shouldShowSwap) View.VISIBLE else View.GONE
    }

    private fun enterRoutePlanMode() {
        currentMode = AppMode.ROUTE_PLAN
        hasSelectedStartPoint = false // Reset start point selection state

        // Show start location input
        binding.startSearchContainer.visibility = View.VISIBLE
        binding.startSearch.requestFocus()

        // Update cancel button text for ROUTE_PLAN mode
        binding.cancelRouteButton.apply {
            text = "Cancel"
            visibility = View.GONE // Hide until route is drawn
        }

        // Update marker draggable state (now draggable in ROUTE_PLAN mode)
        updateMarkersDraggableState()

        // Update quick use current location button visibility
        updateUseCurrentLocationButtonVisibility()

        // Update replace location button visibility (hide in ROUTE_PLAN mode)
        updateReplaceLocationButtonVisibility()

        // User can see the UI change - no need for toast
    }

    /**
     * Update visibility of "use current location" button based on:
     * - GPS is set (has fake location)
     * - Start point not selected OR selected but different from fake GPS location
     */
    private fun updateUseCurrentLocationButtonVisibility() {
        if (currentMode != AppMode.ROUTE_PLAN) {
            binding.useCurrentLocationContainer.visibility = View.GONE
            return
        }

        if (isGpsSet && currentFakeLocationPos != null) {
            // Check if start marker exists and matches fake GPS location
            val startPos = startMarker?.position
            val isSameLocation = startPos != null &&
                Math.abs(startPos.latitude - currentFakeLocationPos!!.latitude) < 0.0001 &&
                Math.abs(startPos.longitude - currentFakeLocationPos!!.longitude) < 0.0001

            // Show button only if start point not selected or different from fake GPS
            if (startPos == null || !isSameLocation) {
                binding.useCurrentLocationContainer.visibility = View.VISIBLE
                binding.useCurrentLocationText.text = getString(
                    R.string.use_current_location_fmt,
                    String.format("%.4f", currentFakeLocationPos!!.latitude),
                    String.format("%.4f", currentFakeLocationPos!!.longitude)
                )
            } else {
                binding.useCurrentLocationContainer.visibility = View.GONE
            }
        } else {
            binding.useCurrentLocationContainer.visibility = View.GONE
        }
    }

    /**
     * Update marker draggable state based on current mode
     * Destination marker is draggable in SEARCH and ROUTE_PLAN modes
     * Start marker is only draggable in ROUTE_PLAN mode
     */
    private fun updateMarkersDraggableState() {
        val isDestDraggable = currentMode == AppMode.SEARCH || currentMode == AppMode.ROUTE_PLAN
        val isStartDraggable = currentMode == AppMode.ROUTE_PLAN

        destMarker?.let { marker ->
            // Remove and re-add marker with new draggable state
            val position = marker.position
            marker.remove()
            destMarker = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("Destination")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .draggable(isDestDraggable)
            )
        }

        startMarker?.let { marker ->
            // Remove and re-add marker with new draggable state
            val position = marker.position
            marker.remove()
            startMarker = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("Start Point")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .draggable(isStartDraggable)
            )
        }
    }

    /**
     * Swap start and destination markers
     */
    private fun swapStartAndDestination() {
        if (startMarker == null || destMarker == null) {
            showToast("Both points are required to swap")
            return
        }

        // Store current positions
        val startPos = startMarker!!.position
        val destPos = destMarker!!.position

        // Store current search texts
        val startText = binding.startSearch.text.toString()
        val destText = binding.destinationSearch.text.toString()

        // Remove old markers
        startMarker?.remove()
        destMarker?.remove()

        // Create new markers with swapped positions - only draggable in ROUTE_PLAN mode
        destMarker = mMap.addMarker(
            MarkerOptions()
                .position(startPos)
                .title("Destination")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .draggable(currentMode == AppMode.ROUTE_PLAN)
        )

        startMarker = mMap.addMarker(
            MarkerOptions()
                .position(destPos)
                .title("Start Point")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .draggable(currentMode == AppMode.ROUTE_PLAN)
        )

        // Swap search texts
        binding.destinationSearch.setText(startText)
        binding.startSearch.setText(destText)

        // Redraw route with new positions
        if (currentMode == AppMode.ROUTE_PLAN) {
            drawRoute()
        }

        // showToast(getString(R.string.msg_swapped_locations))
    }

    private fun drawRoute() {
        if (startMarker == null || destMarker == null) return

        // Show loading state
        isLoadingRoute = true
        routeLoadError = null
        binding.actionButton.apply {
            visibility = View.GONE
        }
        binding.routeLoadingCard.visibility = View.VISIBLE
        binding.routeLoadingProgressText.text = getString(R.string.status_finding_route)

        lifecycleScope.launch {
            try {
                val start = startMarker!!.position
                val dest = destMarker!!.position

                // Create cache key
                val cacheKey = RouteCacheKey.fromMarkers(start, dest, currentVehicleType)

                // Check cache first
                val cachedEntry = routeCache[cacheKey]
                val isCacheValid = cachedEntry != null &&
                    (System.currentTimeMillis() - cachedEntry.timestamp) < CACHE_EXPIRY_MS

                val result = if (isCacheValid && cachedEntry != null) {
                    // Use cached route
                    android.util.Log.d("MapActivity", "✅ Using cached route")

                    // Use the cached route
                    routePoints = cachedEntry.routePoints

                    // Show notification about cached route (only log, no toast)
                    android.util.Log.d("MapActivity", "💾 Using cached route from ${cachedEntry.serviceName}")

                    // Return null to skip API call
                    null
                } else {
                    // Call API for new route
                    android.util.Log.d("MapActivity", "🌐 Fetching route from API")

                    val apiResult = routingService.getRoute(
                        startLat = start.latitude,
                        startLng = start.longitude,
                        endLat = dest.latitude,
                        endLng = dest.longitude,
                        vehicleType = currentVehicleType
                    )

                    if (apiResult != null) {
                        // Use the first (best) route
                        routePoints = apiResult.routes[0]

                        // Cache the route
                        routeCache[cacheKey] = RouteCacheEntry(
                            routePoints = routePoints,
                            serviceName = apiResult.serviceName,
                            isFallback = apiResult.isFallback
                        )

                        // Clean old cache entries (keep only last 10)
                        if (routeCache.size > 10) {
                            val oldestKey = routeCache.entries
                                .minByOrNull { it.value.timestamp }
                                ?.key
                            oldestKey?.let { routeCache.remove(it) }
                        }

                        // Log success (no toast)
                        android.util.Log.d("MapActivity", "✓ Route found via ${apiResult.serviceName}")
                    }

                    apiResult
                }

                if (result == null && !isCacheValid) {
                    // Handle error - all services failed and no cache
                    routeLoadError = getString(R.string.error_route_not_found)
                    isLoadingRoute = false
                    showRouteErrorUI()
                    return@launch
                }

                // Draw polyline
                routePolyline?.remove()
                routePolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(routePoints)
                        .color(Color.parseColor(ROUTE_COLOR))
                        .width(ROUTE_WIDTH)
                )

                // Hide loading, show action button
                isLoadingRoute = false
                routeLoadError = null
                binding.routeLoadingCard.visibility = View.GONE

                // Update button to "Start" with cancel button
                binding.actionButton.apply {
                    text = "Start"
                    setIconResource(R.drawable.ic_navigation)
                    visibility = View.VISIBLE
                }
                binding.cancelRouteButton.apply {
                    text = "Cancel"
                    visibility = View.VISIBLE
                }

                // showToast("Route ready. Press Start to move")

            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // CRITICAL FIX: Detailed error messages
                routeLoadError = getString(R.string.error_timeout)
                isLoadingRoute = false
                showRouteErrorUI()
            } catch (e: retrofit2.HttpException) {
                // CRITICAL FIX: HTTP error codes
                val code = e.code()
                routeLoadError = when (code) {
                    401 -> getString(R.string.error_api_key_invalid)
                    429 -> getString(R.string.error_api_limit_exceeded)
                    500, 502, 503 -> getString(R.string.error_server)
                    else -> getString(R.string.error_connection_code, code)
                }
                isLoadingRoute = false
                showRouteErrorUI()
            } catch (e: java.net.UnknownHostException) {
                routeLoadError = getString(R.string.error_no_network)
                isLoadingRoute = false
                showRouteErrorUI()
            } catch (e: java.net.SocketTimeoutException) {
                routeLoadError = getString(R.string.error_connection_slow)
                isLoadingRoute = false
                showRouteErrorUI()
            } catch (e: Exception) {
                // CRITICAL FIX: Better error logging
                android.util.Log.e("MapActivity", "Unexpected route error: ${e.message}", e)
                routeLoadError = getString(R.string.error_unknown_cause, e.message ?: "Please try again")
                isLoadingRoute = false
                showRouteErrorUI()
            }
        }
    }

    private fun showRouteErrorUI() {
        // IMPORTANT FIX: Ensure all UI states are consistent
        isLoadingRoute = false
        binding.routeLoadingCard.visibility = View.GONE
        binding.routeErrorCard.visibility = View.VISIBLE
        binding.routeErrorText.text = routeLoadError ?: getString(R.string.error_unknown)
        
        // Reset button states based on current mode
        when (currentMode) {
            AppMode.ROUTE_PLAN -> {
                binding.actionButton.visibility = View.GONE
                binding.cancelRouteButton.visibility = View.VISIBLE
            }
            else -> {
                binding.actionButton.visibility = View.GONE
                binding.cancelRouteButton.visibility = View.GONE
            }
        }
    }

    private fun hideRouteErrorUI() {
        binding.routeErrorCard.visibility = View.GONE
        routeLoadError = null
    }

    private fun retryDrawRoute() {
        hideRouteErrorUI()
        drawRoute()
    }

    private fun cancelRoutePlan() {
        // Stop loading if in progress
        isLoadingRoute = false
        routeLoadError = null

        // Hide error and loading cards
        binding.routeLoadingCard.visibility = View.GONE
        binding.routeErrorCard.visibility = View.GONE

        // Clear route
        routePolyline?.remove()
        routePolyline = null
        routePoints = emptyList()

        // Clear start marker but keep destination marker
        startMarker?.remove()
        startMarker = null

        // Clear start search text
        binding.startSearch.text.clear()

        // Reset to search mode
        currentMode = AppMode.SEARCH
        hasSelectedStartPoint = false

        // Update markers to be non-draggable in search mode
        updateMarkersDraggableState()

        // Hide search containers
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE

        // Show "Get Directions" button again with destination marker still visible
        binding.actionButton.apply {
            text = "Get Directions"
            setIconResource(R.drawable.ic_navigation)
            visibility = View.VISIBLE
        }

        // Update cancel button text for SEARCH mode (show X to clear destination)
        binding.cancelRouteButton.apply {
            text = "✕"
            visibility = View.VISIBLE
        }

        // Show search card
        binding.searchCard.visibility = View.VISIBLE

        // Update swap button visibility (will hide since startMarker is null)
        updateSwapButtonVisibility()

        // showToast("Route planning cancelled")
    }

    private fun startNavigation() {
        // Basic validation only
        when {
            routePoints.isEmpty() -> {
                showToast(getString(R.string.error_no_route_data))
                return
            }
            routePoints.size < 2 -> {
                showToast(getString(R.string.error_invalid_route))
                return
            }
        }

        isDriving = true
        isPaused = false
        currentMode = AppMode.NAVIGATION
        currentPositionIndex = 0
        // Reset completed path accumulation at the start of navigation
        // CRITICAL FIX: Use firstOrNull to prevent crash
        completedPathPoints.clear()
        routePoints.firstOrNull()?.let { completedPathPoints.add(it) }

        // Update markers to be non-draggable during navigation
        updateMarkersDraggableState()

        // Clear old fake location marker, circle AND centre dot when starting navigation
        // because the fake location is now moving along the route
        fakeLocationMarker?.remove()
        fakeLocationMarker = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null
        currentFakeLocationPos = null

        // Hide action button and show navigation controls
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.VISIBLE
        
        // Hide cancel route button during navigation
        binding.cancelRouteButton.visibility = View.GONE

        // Update navigation addresses
        updateNavigationAddresses()

        // Hide swap button during navigation
        binding.swapButtonContainer.visibility = View.GONE

        // Reset pause/resume buttons
        binding.pauseButton.visibility = View.VISIBLE
        binding.resumeButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE

        // Set initial speed and update label
        currentSpeed = binding.speedSlider.value.toDouble()
        binding.speedLabel.text = "${currentSpeed.toInt()} km/h"

        // Initialize distance tracking
        totalRouteDistanceKm = calculateTotalRouteDistance(routePoints)
        traveledDistanceKm = 0.0
        lastDistancePosition = null
        updateDistanceLabel()

        android.util.Log.d("MapActivity", "Navigation started with speed: $currentSpeed km/h, total distance: $totalRouteDistanceKm km")
        
        // CRITICAL FIX: Start foreground service to prevent Android from killing app
        // This ensures navigation continues even when app is in background
        try {
            NavigationForegroundService.start(this, currentSpeed, totalRouteDistanceKm)
            android.util.Log.d("MapActivity", "Foreground service started")
        } catch (e: Exception) {
            android.util.Log.e("MapActivity", "Failed to start foreground service: ${e.message}", e)
            // Continue navigation even if service fails to start
        }

        // Hide search inputs
        binding.searchCard.visibility = View.GONE

        // Show camera follow toggle and set to follow mode by default
        isCameraFollowing = true
        binding.cameraFollowToggle.visibility = View.VISIBLE
        updateCameraFollowButton()

        // Hide getFakeLocation button during navigation (user has camera toggle instead)
        binding.getFakeLocation.visibility = View.GONE

        // Hide replace location button during navigation
        binding.replaceLocationButton.visibility = View.GONE

        // Hide set/unset location button during navigation
        binding.setLocationButton.visibility = View.GONE

        // Create fake location circle to show current GPS position during navigation
        // CRITICAL FIX: Use firstOrNull to prevent crash
        val startPos = routePoints.firstOrNull() ?: run {
            showToast(getString(R.string.error_start_point_not_found))
            isDriving = false
            return
        }
        fakeLocationCircle = createStationaryLocationCircle(startPos)

        // CRITICAL FIX: Use navigationScope instead of lifecycleScope to continue running in background
        // lifecycleScope cancels when Activity pauses, causing navigation to stop
        // navigationScope only cancels when Activity is destroyed, allowing GPS to continue updating
        routeSimulator = RouteSimulator(
            points = routePoints,
            speedKmh = currentSpeed,
            updateIntervalMs = 300L, // Optimized: 300ms for better performance (was 100ms)
            scope = navigationScope // FIXED: Use navigationScope to continue in background
        )

       // showToast("🏍️ Bắt đầu di chuyển với tốc độ ${currentSpeed.toInt()} km/h")

        routeSimulator?.start(
            onPosition = { position ->
                // CRITICAL FIX: Always update GPS location even when app is in background
                // This ensures GPS continues updating for other apps (banking, Grab, etc.)
                val bearing = previousLocation?.let { prev ->
                    calculateBearing(prev, position)
                } ?: 0f

                // Calculate speed in m/s from current speedKmh
                val speedMs = (currentSpeed * 1000.0 / 3600.0).toFloat()

                // CRITICAL: Update GPS location immediately (even in background)
                // This is essential for other apps to receive GPS updates
                viewModel.update(
                    start = true,
                    la = position.latitude,
                    ln = position.longitude,
                    bearing = bearing,
                    speed = speedMs
                )
                
                // CRITICAL FIX: Update navigation state even when background
                // This ensures traveledDistanceKm and previousLocation are always updated
                val currentTime = System.currentTimeMillis()
                
                // Update previousLocation for bearing calculation (always, even in background)
                previousLocation = position
                
                // Update traveled distance (always, even in background)
                // Use synchronized access to avoid race conditions
                synchronized(this@MapActivity) {
                    lastDistancePosition?.let { lastPos ->
                        val distanceMeters = distanceBetween(lastPos, position)
                        // Only add distance if movement is significant (> 0.5m) and reasonable (< 100m)
                        if (distanceMeters > 0.5 && distanceMeters < 100) {
                            traveledDistanceKm += distanceMeters / 1000.0
                        }
                    }
                    lastDistancePosition = position
                }
                
                // Track current navigation position (always, even in background)
                currentNavigationPosition = position
                
                // CRITICAL FIX: Update foreground service notification ALWAYS (even in background)
                // This must run from background thread, not UI thread
                try {
                    NavigationForegroundService.updateNotification(
                        this@MapActivity,
                        currentSpeed,
                        totalRouteDistanceKm,
                        traveledDistanceKm
                    )
                } catch (e: Exception) {
                    android.util.Log.e("MapActivity", "Failed to update notification: ${e.message}", e)
                }
                
                // Log GPS data (reduced frequency when background)
                val timeDiff = if (lastGpsUpdateTime > 0) currentTime - lastGpsUpdateTime else 0
                if (BuildConfig.DEBUG && (isActivityVisible || currentPositionIndex % 10 == 0)) {
                    android.util.Log.d("GPS_AntiDetect", "GPS: lat=${position.latitude}, lng=${position.longitude}, bearing=${bearing}°, interval=${timeDiff}ms, visible=$isActivityVisible")
                }
                lastGpsUpdateTime = currentTime
                
                // PERFORMANCE FIX: Only update UI if activity is visible
                // Skip UI updates when app is in background to save CPU/GPU
                if (!isActivityVisible) {
                    // All critical updates done above, just return to skip UI rendering
                    return@start
                }
                
                // UI updates only when app is visible
                runOnUiThread {
                    // Update distance label on UI
                    updateDistanceLabel()
                    
                    // Update speed label to show control/actual speeds with curve reduction
                    try {
                        val actualSpeed = io.github.mwarevn.movingsimulation.utils.SpeedSyncManager.getActualSpeed()
                        if (actualSpeed > 0.01f && currentSpeed > 0) {
                            binding.speedLabel.text = "${currentSpeed.toInt()} / ${actualSpeed.toInt()} km/h"
                        }
                    } catch (e: Exception) {
                        // Silently fail if SpeedSyncManager not available
                    }

                    // Update fake location circle to show current GPS position
                    fakeLocationCircle?.center = position
                    fakeLocationCenterDot?.position = position
                    // Let Google Maps blue dot handle position visualization

                    // Update completed path (gray polyline)
                    updateCompletedPath(position)

                    // Move camera to follow with throttling (only if follow mode is enabled)
                    if (isCameraFollowing) {
                        if (currentTime - lastCameraUpdateTime >= CAMERA_UPDATE_INTERVAL_MS) {
                            mMap.animateCamera(CameraUpdateFactory.newLatLng(position))
                            lastCameraUpdateTime = currentTime
                        }
                    }

                    // Store current position for next bearing calculation
                    previousLocation = position
                    currentPositionIndex++
                }
            },
            onComplete = {
                runOnUiThread {
                    onNavigationComplete()
                }
            }
        )
    }

    private fun updateNavigationAddresses() {
        lifecycleScope.launch {
            try {
                // Get start and destination positions
                val startPos = startMarker?.position
                val destPos = destMarker?.position

                if (startPos != null && destPos != null) {
                    // Get addresses in background with timeout
                    val fromAddress = withContext(Dispatchers.IO) {
                        withTimeout(5000L) { // 5 second timeout
                            getAddressFromLocation(startPos)
                        }
                    }

                    val toAddress = withContext(Dispatchers.IO) {
                        withTimeout(5000L) { // 5 second timeout
                            getAddressFromLocation(destPos)
                        }
                    }

                    // Update UI on main thread
                    binding.navFromAddress.text = "• $fromAddress"
                    binding.navToAddress.text = "• $toAddress"
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("MapActivity", "Timeout getting navigation addresses")
                // Show coordinates as fallback
                val startPos = startMarker?.position
                val destPos = destMarker?.position
                binding.navFromAddress.text = "• ${String.format("%.6f, %.6f", startPos?.latitude ?: 0.0, startPos?.longitude ?: 0.0)}"
                binding.navToAddress.text = "• ${String.format("%.6f, %.6f", destPos?.latitude ?: 0.0, destPos?.longitude ?: 0.0)}"
            } catch (e: Exception) {
                android.util.Log.e("MapActivity", "Error getting navigation addresses", e)
                // Show coordinates as fallback
                val startPos = startMarker?.position
                val destPos = destMarker?.position
                binding.navFromAddress.text = "• ${String.format("%.6f, %.6f", startPos?.latitude ?: 0.0, startPos?.longitude ?: 0.0)}"
                binding.navToAddress.text = "• ${String.format("%.6f, %.6f", destPos?.latitude ?: 0.0, destPos?.longitude ?: 0.0)}"
            }
        }
    }

    private fun stopNavigation() {
        routeSimulator?.stop()
        routeSimulator = null
        isDriving = false
        isPaused = false

        // CRITICAL FIX: Stop foreground service when navigation stops
        try {
            NavigationForegroundService.stop(this)
            android.util.Log.d("MapActivity", "Foreground service stopped")
        } catch (e: Exception) {
            android.util.Log.e("MapActivity", "Failed to stop foreground service: ${e.message}", e)
        }

        // Keep GPS at current position (don't reset)
        // Remove current position circle
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

    // Remove completed path
        completedPolyline?.remove()
        completedPolyline = null
    completedPathPoints.clear()

        // Show search again and hide navigation controls
        binding.searchCard.visibility = View.VISIBLE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE

        // Restore getFakeLocation button visibility if GPS is set
        if (isGpsSet) {
            binding.getFakeLocation.visibility = View.VISIBLE
        }

        // Restore set/unset location button visibility
        binding.setLocationButton.visibility = View.VISIBLE

        binding.actionButton.visibility = View.VISIBLE
        binding.actionButton.apply {
            text = "Stop"
            setIconResource(R.drawable.ic_stop)
        }

        // Reset to search mode
        resetToSearchMode()

        // showToast("Has stopped moving")
    }

    private fun updateSetLocationButton() {
        // Update icon based on local GPS state with distinct icons
        if (isGpsSet) {
            // GPS is set -> show gray pin with X (to unset)
            binding.setLocationButton.setImageResource(R.drawable.ic_location_off)
            // Show getFakeLocation button when GPS is set
            binding.getFakeLocation.visibility = View.VISIBLE
        } else {
            // GPS is not set -> show green pin with checkmark (to set)
            binding.setLocationButton.setImageResource(R.drawable.ic_location_on)
            // Hide getFakeLocation button when GPS is not set
            binding.getFakeLocation.visibility = View.GONE
        }

        // Update replace button visibility whenever set button state changes
        updateReplaceLocationButtonVisibility()
    }
    
    /**
     * Update speed label to show control speed and actual speed (after curve reduction)
     * Format: "108 / 86 km/h" (control / actual)
     */
    private fun updateSpeedLabel(controlSpeed: Double) {
        try {
            // Get actual speed after curve reduction from SpeedSyncManager
            val actualSpeed = io.github.mwarevn.movingsimulation.utils.SpeedSyncManager.getActualSpeed()
            val displayText = if (actualSpeed > 0.01f) {
                // Show "control / actual" when driving
                "${controlSpeed.toInt()} / ${actualSpeed.toInt()} km/h"
            } else {
                // Show just control speed when not driving
                "${controlSpeed.toInt()} km/h"
            }
            binding.speedLabel.text = displayText
        } catch (e: Exception) {
            // Fallback to showing just control speed
            binding.speedLabel.text = "${controlSpeed.toInt()} km/h"
        }
    }

    private fun updateReplaceLocationButtonVisibility() {
        // Show replace button only in SEARCH mode when:
        // 1. GPS is already set (isGpsSet == true)
        // 2. There is a destination marker
        // 3. The marked position is different from current fake location

        val shouldShow = currentMode == AppMode.SEARCH &&
                        isGpsSet &&
                        destMarker?.position != null &&
                        currentFakeLocationPos != null &&
                        destMarker?.position != currentFakeLocationPos

        binding.replaceLocationButton.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun updateFakeLocationMarker(position: LatLng) {
        // Remove old marker and circle if exists
        fakeLocationMarker?.remove()
        fakeLocationCircle?.remove()
        fakeLocationCenterDot?.remove()

        // Create stationary location circle (cyan with center dot)
        fakeLocationCircle = createStationaryLocationCircle(position)
    }

    private fun updateCompletedPath(currentPosition: LatLng) {
        val last = completedPathPoints.lastOrNull()
        
        if (last == null) {
            // First point - just add it
            completedPathPoints.add(currentPosition)
        } else {
            val distance = distanceBetween(last, currentPosition)
            
            // CRITICAL FIX: If distance is too large (> 50m), it means app was in background
            // Interpolate missing points from the original route to fill the gap
            if (distance > 50.0 && routePoints.isNotEmpty()) {
                // Find the segment in routePoints that contains both last and current positions
                val interpolatedPoints = interpolateRouteSegment(last, currentPosition, routePoints)
                if (interpolatedPoints.isNotEmpty()) {
                    // Add interpolated points (skip first as it's the same as 'last')
                    completedPathPoints.addAll(interpolatedPoints.drop(1))
                } else {
                    // Fallback: Add current position if interpolation fails
                    completedPathPoints.add(currentPosition)
                }
            } else if (distance >= 5.0) {
                // Normal case: Add point if distance >= 5m
                completedPathPoints.add(currentPosition)
            }
            // If distance < 5m, skip adding point (optimization)
        }

        // Draw or update polyline incrementally
        // FIX: Update polyline when we have at least 2 points (to draw a line)
        // Optimize: Only update every 3rd point during navigation to reduce rendering overhead
        // But always update when we have exactly 2 points (initial draw) or when size changes significantly
        val shouldUpdate = when {
            completedPathPoints.size == 2 -> true  // CRITICAL: Always draw when we have 2 points
            completedPathPoints.size > 1 && completedPathPoints.size % 3 == 0 -> true  // Update every 3rd point
            else -> false
        }
        
        if (shouldUpdate) {
            if (completedPolyline == null) {
                completedPolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(completedPathPoints)
                        .color(Color.parseColor(COMPLETED_ROUTE_COLOR))
                        .width(ROUTE_WIDTH + 1)
                )
            } else {
                completedPolyline?.points = ArrayList(completedPathPoints)
            }
        }
    }
    
    /**
     * Interpolate route segment between two points
     * Used to fill gaps when app was in background and missed route points
     */
    private fun interpolateRouteSegment(from: LatLng, to: LatLng, route: List<LatLng>): List<LatLng> {
        if (route.isEmpty()) return emptyList()
        
        // Find the segment in route that contains 'from' and 'to'
        var fromIdx = -1
        var toIdx = -1
        var minFromDist = Double.MAX_VALUE
        var minToDist = Double.MAX_VALUE
        
        // Find closest points in route to 'from' and 'to'
        for (i in route.indices) {
            val distFrom = distanceBetween(from, route[i])
            val distTo = distanceBetween(to, route[i])
            
            if (distFrom < minFromDist) {
                minFromDist = distFrom
                fromIdx = i
            }
            if (distTo < minToDist) {
                minToDist = distTo
                toIdx = i
            }
        }
        
        // If we found valid indices and 'to' is after 'from' in the route
        if (fromIdx >= 0 && toIdx >= 0 && toIdx > fromIdx) {
            val interpolated = mutableListOf<LatLng>()
            
            // Add all points between fromIdx and toIdx (inclusive)
            for (i in fromIdx..toIdx) {
                interpolated.add(route[i])
            }
            
            // If 'to' is not exactly at toIdx, add it at the end
            if (distanceBetween(to, route[toIdx]) > 5.0) {
                interpolated.add(to)
            }
            
            return interpolated
        }
        
        // Fallback: If we can't find the segment, create a simple interpolation
        // This happens if route doesn't contain the exact points
        val interpolated = mutableListOf<LatLng>()
        val steps = (distanceBetween(from, to) / 10.0).toInt().coerceAtLeast(2).coerceAtMost(50) // Max 50 points
        
        for (i in 0..steps) {
            val fraction = i.toDouble() / steps
            val lat = from.latitude + (to.latitude - from.latitude) * fraction
            val lng = from.longitude + (to.longitude - from.longitude) * fraction
            interpolated.add(LatLng(lat, lng))
        }
        
        return interpolated
    }
    
    /**
     * Force update completed route polyline (call when navigation stops/pauses)
     * This ensures the final route is drawn even if point count is not divisible by 3
     */
    private fun forceUpdateCompletedPath() {
        if (completedPathPoints.size >= 2) {
            if (completedPolyline == null) {
                completedPolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(completedPathPoints)
                        .color(Color.parseColor(COMPLETED_ROUTE_COLOR))
                        .width(ROUTE_WIDTH + 1)
                )
            } else {
                completedPolyline?.points = ArrayList(completedPathPoints)
            }
        }
    }

    private fun updateCameraFollowButton() {
        if (isCameraFollowing) {
            // Show camera following icon
            binding.cameraFollowToggle.setImageResource(R.drawable.ic_camera_follow)
        } else {
            // Show free move camera icon
            binding.cameraFollowToggle.setImageResource(R.drawable.ic_camera_free)
        }
    }

    private fun distanceBetween(p1: LatLng, p2: LatLng): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return 6371000 * c // Return distance in meters
    }

    /**
     * Calculate total route distance in kilometers
     */
    /**
     * CRITICAL FIX: Validate route points
     * Checks for valid coordinates, no duplicates, and reasonable values
     */
    private fun isValidRoute(points: List<LatLng>): Boolean {
        if (points.isEmpty()) return false
        
        // Check all coordinates are valid
        val allValid = points.all { point ->
            point.latitude in -90.0..90.0 && 
            point.longitude in -180.0..180.0 &&
            !point.latitude.isNaN() && 
            !point.longitude.isNaN() &&
            !point.latitude.isInfinite() &&
            !point.longitude.isInfinite()
        }
        
        if (!allValid) return false
        
        // Check for duplicate points (within 1 meter tolerance)
        val distinctPoints = points.distinctBy { point ->
            "${point.latitude.toInt()},${point.longitude.toInt()}"
        }
        
        // Allow some duplicates but not all points being the same
        return distinctPoints.size >= 2
    }

    private fun calculateTotalRouteDistance(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0

        var totalMeters = 0.0
        for (i in 0 until points.size - 1) {
            totalMeters += distanceBetween(points[i], points[i + 1])
        }

        return totalMeters / 1000.0 // Convert to kilometers
    }

    /**
     * Update distance label with current progress
     */
    private fun updateDistanceLabel() {
        val traveledStr = String.format("%.2f", traveledDistanceKm)
        val totalStr = String.format("%.2f", totalRouteDistanceKm)
        binding.distanceLabel.text = "${traveledStr}km/${totalStr}km"
    }

    /**
     * Update traveled distance based on current position
     */
    private fun updateTraveledDistance(currentPosition: LatLng) {
        lastDistancePosition?.let { lastPos ->
            val distanceMeters = distanceBetween(lastPos, currentPosition)
            // Only add distance if movement is significant (> 0.5m) and reasonable (< 100m)
            // This filters out GPS jitter and prevents huge jumps
            if (distanceMeters > 0.5 && distanceMeters < 100) {
                traveledDistanceKm += distanceMeters / 1000.0
                updateDistanceLabel()
            }
        }
        lastDistancePosition = currentPosition
    }

    /**
     * Helper function to create a fake location circle marker with center dot
     * @param center The center position of the circle
     * @param strokeColor Stroke color
     * @param fillColor Fill color
     * @param centerColor Center dot color
     * @param radius Radius in meters (default: from companion object)
     * @param showCenterDot Whether to show the center dot (default: true)
     */
    private fun createFakeLocationCircle(
        center: LatLng,
        strokeColor: Int,
        fillColor: Int,
        centerColor: Int,
        radius: Double = CIRCLE_RADIUS,
        showCenterDot: Boolean = true
    ): Circle {
        // Remove old center dot if exists
        fakeLocationCenterDot?.remove()

        // Create center dot marker if requested (maintains size at all zoom levels)
        if (showCenterDot) {
            fakeLocationCenterDot = mMap.addMarker(
                MarkerOptions()
                    .position(center)
                    .icon(createCenterDotIcon(centerColor))
                    .anchor(0.5f, 0.5f) // Center the icon on the position
                    .zIndex(CENTER_DOT_Z_INDEX)
            )
        }

        // Create outer circle
        return mMap.addCircle(
            CircleOptions()
                .center(center)
                .radius(radius)
                .strokeColor(strokeColor)
                .fillColor(fillColor)
                .strokeWidth(CIRCLE_STROKE_WIDTH)
                .zIndex(CIRCLE_Z_INDEX)
        )
    }

    /**
     * Helper: Create stationary fake location circle (Orange Red)
     */
    private fun createStationaryLocationCircle(center: LatLng): Circle {
        return createFakeLocationCircle(
            center = center,
            strokeColor = FAKE_LOCATION_STROKE_COLOR,
            fillColor = FAKE_LOCATION_FILL_COLOR,
            centerColor = FAKE_LOCATION_CENTER_COLOR
        )
    }

    private fun onNavigationComplete() {
        isDriving = false
        isPaused = false

        // CRITICAL FIX: Stop foreground service when navigation completes
        try {
            NavigationForegroundService.stop(this)
            android.util.Log.d("MapActivity", "Foreground service stopped (navigation complete)")
        } catch (e: Exception) {
            android.util.Log.e("MapActivity", "Failed to stop foreground service: ${e.message}", e)
        }

        // Reset speed to default for next trip
        binding.speedSlider.value = 52f
        currentSpeed = 52.0
        binding.speedLabel.text = "52 km/h"

        // Hide navigation controls
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE

        // Restore getFakeLocation button since navigation completed
        binding.getFakeLocation.visibility = View.VISIBLE

        // Restore set/unset location button since navigation completed
        binding.setLocationButton.visibility = View.VISIBLE

        // Remove current position circle (but will show completion actions)
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

        // Clear marker titles
        startMarker?.title = ""
        destMarker?.title = ""

        // Keep GPS at destination and show fake location marker
        val destinationPos = routePoints.lastOrNull() ?: destMarker?.position
        if (destinationPos != null) {
            isGpsSet = true
            currentFakeLocationPos = destinationPos
            viewModel.update(true, destinationPos.latitude, destinationPos.longitude)
            updateFakeLocationMarker(destinationPos)
            updateSetLocationButton()
        }

        // Show completion action bar instead of dialog
        binding.completionActionsCard.visibility = View.VISIBLE
        // showToast(getString(R.string.msg_arrived))
    }

    private fun resetToSearchMode() {
        // Clear all markers and routes
        destMarker?.remove()
        destMarker = null
        startMarker?.remove()
        startMarker = null
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
        completedPathPoints.clear()
        currentPositionMarker?.remove()
        currentPositionMarker = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null
        fakeLocationMarker?.remove()
        fakeLocationMarker = null
        routePoints = emptyList()

        // Reset speed to default for next trip
        binding.speedSlider.value = 52f
        currentSpeed = 52.0
        binding.speedLabel.text = "52 km/h"

        // Reset UI
        currentMode = AppMode.SEARCH
        hasSelectedStartPoint = false // Reset start point selection state

        // Update markers to be draggable again (though cleared above)
        updateMarkersDraggableState()

        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.destinationSearch.text.clear()
        binding.startSearch.text.clear()
        binding.searchCard.visibility = View.VISIBLE

        // Restore getFakeLocation button visibility based on GPS state
        binding.getFakeLocation.visibility = if (isGpsSet) View.VISIBLE else View.GONE

        // Update replace location button visibility (hide in SEARCH mode when no marker)
        updateReplaceLocationButtonVisibility()

        routeSimulator?.stop()
        routeSimulator = null
        isPaused = false
    }

    private fun restoreFakeLocationMarker() {
        // Check if fake location was set in previous session
        if (viewModel.isStarted) {
            val lat = viewModel.getLat
            val lng = viewModel.getLng

            if (lat != 0.0 && lng != 0.0) {
                val position = LatLng(lat, lng)
                isGpsSet = true
                currentFakeLocationPos = position

                // Show circle marker at saved position
                updateFakeLocationMarker(position)
                updateSetLocationButton()

                // Initialize last joystick position
                lastJoystickLat = lat
                lastJoystickLon = lng

                android.util.Log.d("MapActivity", "Restored fake location from previous session: $lat, $lng")
                // showToast(getString(R.string.restored_prev_location_fmt, String.format("%.4f", lat), String.format("%.4f", lng)))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // CRITICAL: Proper cleanup to prevent memory leaks
        routeSimulator?.stop()
        routeSimulator = null
        
        // CRITICAL FIX: Cancel navigationScope when Activity is destroyed
        // This ensures RouteSimulator stops and prevents memory leaks
        navigationScope.cancel()
        
        searchJob?.cancel()
        searchJob = null

        // Unregister preference listener
        try {
            PrefManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (e: Exception) {
            android.util.Log.e("MapActivity", "Error unregistering pref listener: ${e.message}")
        }

        // Clear route cache to free memory
        routeCache.clear()

        // Clear polylines and markers to free GPU memory
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

        android.util.Log.d("MapActivity", "Activity destroyed - all resources cleaned up")
    }

    override fun onPause() {
        super.onPause()

        isActivityVisible = false

        // Keep preference listener active even when paused
        // This allows joystick updates to continue updating the map in background
        android.util.Log.d("MapActivity", "Activity paused, keeping listener active for background updates")

        // CRITICAL FIX: Ensure navigation continues in background
        if (isDriving && !isPaused) {
            // Save navigation state when going to background
            wasNavigatingBeforeBackground = true
            backgroundNavigationState = NavigationState(
                routePoints = routePoints,
                currentSpeed = currentSpeed,
                isPaused = isPaused,
                currentPositionIndex = currentPositionIndex,
                completedPathPoints = ArrayList(completedPathPoints)
            )
            android.util.Log.d("MapActivity", "Saved navigation state to background")
            
            // CRITICAL: Ensure RouteSimulator is NOT paused when app goes to background
            // Only pause if user explicitly clicks pause button
            if (routeSimulator != null) {
                // Verify RouteSimulator is still running
                val isRunning = routeSimulator?.isRunning() ?: false
                android.util.Log.d("MapActivity", "RouteSimulator running state: $isRunning (should be true)")
                
                // Force resume if somehow paused (shouldn't happen, but safety check)
                if (!isRunning && !isPaused) {
                    android.util.Log.w("MapActivity", "RouteSimulator was paused unexpectedly, resuming...")
                    routeSimulator?.resume()
                }
            }
        }
        
        // Don't pause route simulator when going to background during navigation
        // Let it continue running for seamless background GPS simulation
        android.util.Log.d("MapActivity", "Navigation will continue in background (isDriving=$isDriving, isPaused=$isPaused)")
    }

    /**
     * Toggle navigation controls between collapsed and expanded state
     */
    private fun toggleNavigationControls() {
        val isCurrentlyExpanded = PrefManager.navControlsExpanded
        val newExpandedState = !isCurrentlyExpanded
        
        // Save new state
        PrefManager.navControlsExpanded = newExpandedState
        
        // Animate the transition
        animateNavigationControls(newExpandedState)
    }
    
    /**
     * Animate navigation controls collapse/expand
     */
    private fun animateNavigationControls(expanded: Boolean) {
        val expandableContent = binding.navControlsExpandable
        val toggleButton = binding.navControlsToggle
        
        if (expanded) {
            // Expand animation
            expandableContent.visibility = View.VISIBLE
            expandableContent.alpha = 0f
            expandableContent.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
            
            // Rotate toggle button icon (point up when expanded)
            toggleButton.animate()
                .rotation(0f)
                .setDuration(200)
                .start()
            toggleButton.setImageResource(R.drawable.ic_expand_less)
        } else {
            // Collapse animation
            expandableContent.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    expandableContent.visibility = View.GONE
                }
                .start()
            
            // Rotate toggle button icon (point down when collapsed)
            toggleButton.animate()
                .rotation(180f)
                .setDuration(200)
                .start()
            toggleButton.setImageResource(R.drawable.ic_expand_more)
        }
    }
    
    /**
     * Restore navigation controls state from preferences
     */
    private fun restoreNavigationControlsState() {
        val isExpanded = PrefManager.navControlsExpanded
        
        // Set initial state without animation
        if (isExpanded) {
            binding.navControlsExpandable.visibility = View.VISIBLE
            binding.navControlsExpandable.alpha = 1f
            binding.navControlsToggle.setImageResource(R.drawable.ic_expand_less)
            binding.navControlsToggle.rotation = 0f
        } else {
            binding.navControlsExpandable.visibility = View.GONE
            binding.navControlsExpandable.alpha = 0f
            binding.navControlsToggle.setImageResource(R.drawable.ic_expand_more)
            binding.navControlsToggle.rotation = 180f
        }
    }

    // IMPORTANT FIX: Handle permission request result
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                    mMap.isMyLocationEnabled = true
                    getCurrentLocation()
                }
            } else {
                // Permission denied
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )) {
                    // User denied but not permanently - show explanation
                    showToast(getString(R.string.permission_location_rationale))
                } else {
                    // User denied permanently - guide to settings
                    showToast(getString(R.string.permission_location_settings))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        isActivityVisible = true

        // Register preference change listener for joystick updates
        // Unregister first to avoid duplicate registration
        try {
            PrefManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        PrefManager.sharedPreferences.registerOnSharedPreferenceChangeListener(prefChangeListener)
        android.util.Log.d("MapActivity", "Registered preference change listener for joystick")

        // Sync current position from PrefManager in case it changed while paused
        if (isGpsSet && !isDriving) {
            val currentLat = PrefManager.getLat
            val currentLon = PrefManager.getLng
            if (currentLat != lastJoystickLat || currentLon != lastJoystickLon) {
                lastJoystickLat = currentLat
                lastJoystickLon = currentLon
                val newPosition = LatLng(currentLat, currentLon)
                if (fakeLocationCircle != null && fakeLocationCenterDot != null) {
                    fakeLocationCircle?.center = newPosition
                    fakeLocationCenterDot?.position = newPosition
                    currentFakeLocationPos = newPosition
                    android.util.Log.d("MapActivity", "Synced GPS marker position on resume: $newPosition")
                }
            }
        }

        // Restore navigation state when returning from background
        if (wasNavigatingBeforeBackground && backgroundNavigationState != null) {
            val savedState = backgroundNavigationState!!

            // Restore navigation variables
            routePoints = savedState.routePoints
            currentSpeed = savedState.currentSpeed
            isPaused = savedState.isPaused
            currentPositionIndex = savedState.currentPositionIndex
            completedPathPoints.clear()
            completedPathPoints.addAll(savedState.completedPathPoints)

            // Ensure UI is in sync with current navigation state
            updateNavigationUI()
            android.util.Log.d("MapActivity", "Restored navigation state from background")

            // Clear the saved state
            wasNavigatingBeforeBackground = false
            backgroundNavigationState = null
        } else if (isDriving && !isPaused) {
            // Regular UI refresh for ongoing navigation
            updateNavigationUI()
            
            // CRITICAL FIX: Force update completed path when resuming
            // This ensures route is drawn correctly after returning from background
            if (currentNavigationPosition != null) {
                forceUpdateCompletedPath()
            }
        }
    }

    /**
     * Update navigation UI when returning from background
     */
    private fun updateNavigationUI() {
        if (isDriving) {
            // Ensure navigation controls are visible
            binding.navigationControlsCard.visibility = View.VISIBLE
            binding.searchCard.visibility = View.GONE
            binding.cameraFollowToggle.visibility = View.VISIBLE
            binding.setLocationButton.visibility = View.GONE
            binding.getFakeLocation.visibility = View.GONE

            // Update pause/resume button states
            if (isPaused) {
                binding.pauseButton.visibility = View.GONE
                binding.resumeButton.visibility = View.VISIBLE
                binding.stopButton.visibility = View.VISIBLE
            } else {
                binding.pauseButton.visibility = View.VISIBLE
                binding.resumeButton.visibility = View.GONE
                binding.stopButton.visibility = View.GONE
            }

            // Update speed label
            binding.speedLabel.text = "${currentSpeed.toInt()} km/h"

            // Restore fake location circle if missing
            if (fakeLocationCircle == null && currentNavigationPosition != null) {
                fakeLocationCircle = createStationaryLocationCircle(currentNavigationPosition!!)
                android.util.Log.d("MapActivity", "Restored fake location circle at: ${currentNavigationPosition!!.latitude}, ${currentNavigationPosition!!.longitude}")
            }

            // Restore completed path polyline if missing
            if (completedPolyline == null && completedPathPoints.size > 1) {
                completedPolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(completedPathPoints)
                        .color(Color.parseColor(COMPLETED_ROUTE_COLOR))
                        .width(ROUTE_WIDTH + 1)
                )
                android.util.Log.d("MapActivity", "Restored completed path polyline")
            }
        }
    }

    // Marker Drag Listener implementations
    override fun onMarkerDrag(marker: Marker) {
        // Prevent dragging during navigation
        if (currentMode == AppMode.NAVIGATION) {
            return
        }
        // Don't update route while dragging to reduce server load
        // Route will be updated when drag ends
    }

    override fun onMarkerDragEnd(marker: Marker) {
        // Prevent dragging during navigation
        if (currentMode == AppMode.NAVIGATION) {
            showToast(getString(R.string.error_cannot_change_dest_moving))
            return
        }

        // Update address in search box when drag ends
        lifecycleScope.launch {
            val address = getAddressFromLocation(marker.position)
            when (marker) {
                destMarker -> {
                    binding.destinationSearch.setText(address)
                    // Update replace button visibility when destination marker is moved
                    updateReplaceLocationButtonVisibility()
                }
                startMarker -> {
                    binding.startSearch.setText(address)
                    // Update "use current location" button visibility when start marker is dragged
                    updateUseCurrentLocationButtonVisibility()
                }
            }
        }

        // Update route only when drag ends to reduce server calls
        // Only in ROUTE_PLAN mode
        if (currentMode == AppMode.ROUTE_PLAN && startMarker != null && destMarker != null) {
            drawRoute()
        }
    }

    override fun onMarkerDragStart(marker: Marker) {
        // Allow dragging in both SEARCH and ROUTE_PLAN modes
        if (currentMode == AppMode.NAVIGATION) {
            showToast(getString(R.string.error_cannot_change_dest_moving))
            return
        }

        // In SEARCH mode, only allow dragging destination marker
        if (currentMode == AppMode.SEARCH && marker != destMarker) {
            return
        }

        // Add vibration feedback when drag starts
        val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Vibrate for 100ms with amplitude 200
                vibrator.vibrate(VibrationEffect.createOneShot(100, 200))
            } else {
                // For older Android versions
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }
    }

    private fun onFinishNavigation() {
        // CRITICAL FIX: Force update completed path before clearing
        // This ensures the final route is drawn even if point count is not divisible by 3
        forceUpdateCompletedPath()
        
        // Hide completion action bar
        binding.completionActionsCard.visibility = View.GONE

        // Reset speed to default for next trip
        binding.speedSlider.value = 52f
        currentSpeed = 52.0
        binding.speedLabel.text = "52 km/h"

        // Clear route and route-related markers, but keep fake location circle and marker
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
    completedPathPoints.clear()
        currentPositionMarker?.remove()
        currentPositionMarker = null
        startMarker?.remove()
        startMarker = null
        destMarker?.remove()
        destMarker = null
        routePoints = emptyList()

        // Clear search text
        binding.destinationSearch.text.clear()
        binding.startSearch.text.clear()

        // Reset UI to search mode but keep GPS set and fake location marker
        currentMode = AppMode.SEARCH
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE

        // Restore getFakeLocation button since GPS is still set at destination
        binding.getFakeLocation.visibility = View.VISIBLE

        // Stop the route simulator
        routeSimulator?.stop()

        // Completion reached - no need for toast
    }

    private fun onRestartNavigation() {
        // Hide completion action bar
        binding.completionActionsCard.visibility = View.GONE

        // Reset navigation state to allow restart
        isDriving = false
        isPaused = false
    currentPositionIndex = 0
    completedPathPoints.clear()

        // Clear old circle and center dot before restarting
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

        // Re-create route simulator with same route
        if (routePoints.isNotEmpty()) {
            // Show navigation controls again
            binding.navigationControlsCard.visibility = View.VISIBLE
            binding.pauseButton.visibility = View.VISIBLE
            binding.resumeButton.visibility = View.GONE
            binding.stopButton.visibility = View.GONE
            binding.cancelRouteButton.visibility = View.GONE

            // Show camera follow toggle and reset to follow mode
            isCameraFollowing = true
            binding.cameraFollowToggle.visibility = View.VISIBLE
            updateCameraFollowButton()

            // Hide getFakeLocation button during navigation restart
            binding.getFakeLocation.visibility = View.GONE

            // Ensure speed label shows current speed
            binding.speedLabel.text = "${currentSpeed.toInt()} km/h"

            // Create fake location circle at start point to show GPS position
            val startPos = routePoints.firstOrNull()
            if (startPos != null) {
                fakeLocationCircle = createStationaryLocationCircle(startPos)
            }

            // CRITICAL FIX: Use lifecycleScope instead of GlobalScope
            routeSimulator = RouteSimulator(
                points = routePoints,
                speedKmh = currentSpeed,
                updateIntervalMs = 300L, // Optimized: 300ms for better performance
                scope = lifecycleScope // FIXED: Proper lifecycle management
            )

            routeSimulator?.start(
                onPosition = { position ->
                    runOnUiThread {
                        val currentTime = System.currentTimeMillis()

                        // Calculate bearing for GPS metadata
                        val bearing = previousLocation?.let { prev ->
                            calculateBearing(prev, position)
                        } ?: 0f

                        // Calculate speed in m/s from current speedKmh
                        val speedMs = (currentSpeed * 1000.0 / 3600.0).toFloat()

                        // Update GPS location with bearing and speed for smooth rendering
                        viewModel.update(
                            start = true,
                            la = position.latitude,
                            ln = position.longitude,
                            bearing = bearing,
                            speed = speedMs
                        )

                        // Log GPS data with timing variance for debugging
                        val timeDiff = if (lastGpsUpdateTime > 0) currentTime - lastGpsUpdateTime else 0
                        // Optimize: Log only every 5th update in debug mode
                        if (BuildConfig.DEBUG && currentPositionIndex % 5 == 0) {
                            android.util.Log.d("GPS_AntiDetect", "GPS: lat=${position.latitude}, lng=${position.longitude}, bearing=${bearing}°, interval=${timeDiff}ms")
                        }
                        lastGpsUpdateTime = currentTime

                        // Track current navigation position for pause/stop functionality
                        currentNavigationPosition = position

                        // Update traveled distance
                        updateTraveledDistance(position)
                        
                        // Update speed label to show control/actual speeds with curve reduction
                        try {
                            val actualSpeed = io.github.mwarevn.movingsimulation.utils.SpeedSyncManager.getActualSpeed()
                            if (actualSpeed > 0.01f && currentSpeed > 0) {
                                binding.speedLabel.text = "${currentSpeed.toInt()} / ${actualSpeed.toInt()} km/h"
                            }
                        } catch (e: Exception) {
                            // Silently fail if SpeedSyncManager not available
                        }

                        // Update fake location circle to show current GPS position
                        fakeLocationCircle?.center = position
                        fakeLocationCenterDot?.position = position
                        // Let Google Maps blue dot handle position visualization

                        // Update completed path (gray polyline)
                        updateCompletedPath(position)

                        // Move camera to follow with throttling (only if follow mode is enabled)
                        if (isCameraFollowing) {
                            if (currentTime - lastCameraUpdateTime >= CAMERA_UPDATE_INTERVAL_MS) {
                                mMap.animateCamera(CameraUpdateFactory.newLatLng(position))
                                lastCameraUpdateTime = currentTime
                            }
                        }

                        // Store current position for next bearing calculation
                        previousLocation = position
                        currentPositionIndex++
                    }
                },
                onComplete = {
                    runOnUiThread {
                        onNavigationComplete()
                    }
                }
            )

            isDriving = true
            currentMode = AppMode.NAVIGATION

            // Navigation restarted - no need for toast
        } else {
            showToast(getString(R.string.error_restart_lost_data))
        }
    }

    private fun onStopNavigationEarly() {
        // Stop the route simulator
        routeSimulator?.stop()
        routeSimulator = null
        isDriving = false
        isPaused = false

        // CRITICAL FIX: Stop foreground service when navigation stops early
        try {
            NavigationForegroundService.stop(this)
            android.util.Log.d("MapActivity", "Foreground service stopped (navigation stopped early)")
        } catch (e: Exception) {
            android.util.Log.e("MapActivity", "Failed to stop foreground service: ${e.message}", e)
        }

        // Reset speed to default for next trip
        binding.speedSlider.value = 52f
        currentSpeed = 52.0
        binding.speedLabel.text = "52 km/h"

        // Get the current position from tracked navigation position
        val currentPosition = currentNavigationPosition

        // Clear route and route-related markers
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
    completedPathPoints.clear()
        currentPositionMarker?.remove()
        currentPositionMarker = null
        startMarker?.remove()
        startMarker = null
        destMarker?.remove()
        destMarker = null
        routePoints = emptyList()

        // Clear search text
        binding.destinationSearch.text.clear()
        binding.startSearch.text.clear()

        // Hide navigation controls
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE
        binding.pauseButton.visibility = View.VISIBLE
        binding.resumeButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE

        // Set GPS at current paused position
        if (currentPosition != null) {
            isGpsSet = true
            currentFakeLocationPos = currentPosition
            viewModel.update(true, currentPosition.latitude, currentPosition.longitude)

            // Clear old circle and center dot before creating new ones
            fakeLocationCircle?.remove()
            fakeLocationCircle = null
            fakeLocationCenterDot?.remove()
            fakeLocationCenterDot = null

            // Create fake location circle at pause position (since navigation stopped)
            fakeLocationCircle = createStationaryLocationCircle(currentPosition)

            updateSetLocationButton()
        }

        // Reset UI to search mode
        currentMode = AppMode.SEARCH
        binding.actionButton.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE

        // Restore getFakeLocation button since GPS is now set at pause position
        binding.getFakeLocation.visibility = View.VISIBLE

        // Restore set/unset location button since navigation stopped
        binding.setLocationButton.visibility = View.VISIBLE

        // Navigation stopped - no need for toast
        }
    }
}