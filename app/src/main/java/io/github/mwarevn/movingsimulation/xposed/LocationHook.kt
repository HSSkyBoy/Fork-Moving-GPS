package io.github.mwarevn.movingsimulation.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.utils.PrefManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import kotlin.math.cos

object LocationHook {

    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    var bearing: Float = 0F
    var speed: Float = 0F
    private const val pi = 3.14159265359
    private var accuracy: Float = 0.0f
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    private var mLastUpdated: Long = 0

    // CRITICAL: Cache last valid lat/lng to prevent GPS loss
    private var lastValidLat: Double = 45.0000
    private var lastValidLng: Double = 0.0000
    private var lastErrorLogTime: Long = 0

    @JvmStatic
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    // PERFORMANCE FIX: Track registered listeners for continuous updates
    private val registeredListeners = Collections.synchronizedList(mutableListOf<android.location.LocationListener>())
    private val registeredCallbacks = Collections.synchronizedList(mutableListOf<com.google.android.gms.location.LocationCallback>())
    
    // PERFORMANCE FIX: Use SINGLE global Handler for all periodic updates (prevents memory leak)
    private var globalUpdateHandler: android.os.Handler? = null
    private var globalUpdateRunnable: Runnable? = null
    private var isPeriodicUpdateRunning = false
    private val UPDATE_INTERVAL_MS = 2000L  // Update every 2 seconds (reduced from 1 second to save CPU)
    private var lastLocationUpdateTime = 0L
    private val LOCATION_CACHE_DURATION_MS = 500L  // Cache location for 500ms to avoid excessive updates

    // Safe context access - may be null during early app initialization
    private val context: Context? by lazy { 
        try {
            AndroidAppHelper.currentApplication() as? Context
        } catch (e: Throwable) {
            null
        }
    }

    private fun updateLocation() {
        try {
            // CRITICAL: Always reload settings to get latest values
            var currentLat = settings.getLat
            var currentLng = settings.getLng
            
            // CRITICAL: If lat/lng are default values, use cached valid values
            if (currentLat == 45.0 && currentLng == 0.0) {
                // Use cached values if available
                if (lastValidLat != 45.0 || lastValidLng != 0.0) {
                    currentLat = lastValidLat
                    currentLng = lastValidLng
                    // Log warning only once per 10 seconds to avoid spam
                    val now = System.currentTimeMillis()
                    if (now - lastErrorLogTime > 10000L) {
                        XposedBridge.log("LocationHook: WARNING - Using default lat/lng from settings! Using cached values: lat=${String.format("%.6f", currentLat)} lng=${String.format("%.6f", currentLng)}")
                        lastErrorLogTime = now
                    }
                } else {
                    // No valid cached values - this is a real error
                    val now = System.currentTimeMillis()
                    if (now - lastErrorLogTime > 10000L) {
                        XposedBridge.log("LocationHook: ERROR - No valid lat/lng configured! Please set location in app. Using default values will cause GPS loss.")
                        lastErrorLogTime = now
                    }
                    // Still use default values but don't update cache
                    return
                }
            } else {
                // Valid values - update cache
                lastValidLat = currentLat
                lastValidLng = currentLng
            }
            
            mLastUpdated = System.currentTimeMillis()
            val x = (rand.nextInt(50) - 15).toDouble()
            val y = (rand.nextInt(50) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * currentLat / 180.0))
            newlat =
                if (settings.isRandomPosition) currentLat + (dlat * 180.0 / pi) else currentLat
            newlng =
                if (settings.isRandomPosition) currentLng + (dlng * 180.0 / pi) else currentLng
            
            // Update cache with calculated values
            if (newlat != 45.0 || newlng != 0.0) {
                lastValidLat = newlat
                lastValidLng = newlng
            }
            
            accuracy = settings.accuracy!!.toFloat()
            
            // Get synchronized bearing and speed from SharedPreferences (cross-process)
            // CRITICAL: RouteSimulator updates SharedPreferences, Xposed hooks read from it
            val syncedBearing = settings.getSyncedBearing
            val syncedSpeed = settings.getSyncedActualSpeed
            
            // Check if navigation is actually running (syncedSpeed > 0 means RouteSimulator is active)
            val isNavigationActive = syncedSpeed > 0.01f || syncedBearing > 0.01f
            
            bearing = if (settings.isStarted && isNavigationActive) {
                // When GPS is active AND navigation is running, use synced bearing from RouteSimulator
                syncedBearing
            } else if (settings.isStarted) {
                // GPS is active but navigation not started yet - use user-set bearing
                settings.getBearing
            } else {
                // GPS is off - use user-set bearing
                settings.getBearing
            }
            
            // Get actual speed from RouteSimulator via SharedPreferences
            speed = if (settings.isStarted && isNavigationActive && syncedSpeed >= 0f) {
                // When GPS is active AND navigation is running, use actual simulation speed
                syncedSpeed * 1000f / 3600f  // Convert km/h to m/s
            } else if (settings.isStarted) {
                // GPS is active but navigation not started yet - use user-set speed
                settings.getSpeed
            } else {
                // GPS is off - use user-set speed
                settings.getSpeed
            }
            
            // PERFORMANCE FIX: Reduced logging - only log errors and warnings
            // Removed excessive debug logging that was causing performance issues

        } catch (e: Exception) {
            // CRITICAL: Log errors instead of silently failing
            XposedBridge.log("LocationHook: ERROR in updateLocation() - ${e.message}")
            e.printStackTrace()
            // Don't update values on error - keep previous values
        }
    }
    
    /**
     * CRITICAL HELPER: Set Location fields with proper internal flags
     * This ensures Google Maps displays speed/bearing correctly
     */
    private fun setLocationFieldsWithFlags(location: Location) {
        try {
            // Set hasSpeed and hasBearing flags using reflection
            val locationClass = Location::class.java
            val setFieldsMaskMethod = locationClass.getDeclaredMethod("setFieldsMask", Int::class.javaPrimitiveType)
            setFieldsMaskMethod.isAccessible = true
            
            // Location field masks (from Android source code)
            val HAS_LAT_LONG_BIT = 1
            val HAS_ALTITUDE_BIT = 2
            val HAS_SPEED_BIT = 4
            val HAS_BEARING_BIT = 8
            val HAS_ACCURACY_BIT = 16
            
            val fieldsMask = HAS_LAT_LONG_BIT or HAS_ALTITUDE_BIT or HAS_SPEED_BIT or 
                            HAS_BEARING_BIT or HAS_ACCURACY_BIT
            setFieldsMaskMethod.invoke(location, fieldsMask)
        } catch (e: Throwable) {
            // Flags setting failed - Location object might still work without explicit flags
            // on some Android versions
        }
    }

    // thêm helper để thêm package vào ignore list (an toàn)
    @JvmStatic
    fun addIgnoredPackage(pkg: String) {
        if (!ignorePkg.contains(pkg)) ignorePkg.add(pkg)
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // PERFORMANCE FIX: Reduced logging - only log on first hook or errors
        if (lpparam.packageName == "com.google.android.apps.maps" || lpparam.packageName.contains("bank")) {
            XposedBridge.log("LocationHook: initHooks() called for package=${lpparam.packageName}, GPS started=${settings.isStarted}")
        }

        // Hook system server ONLY if explicitly enabled
        // This is DANGEROUS and should only be used when necessary
        if (lpparam.packageName == "android") {
            if (!settings.isStarted || !settings.isHookedSystem) {
                // Don't hook system unless explicitly enabled in settings
                XposedBridge.log("GPS Setter: Skipping system hook (disabled or not started)")
                return
            }

            XposedBridge.log("GPS Setter: Hooking system server (WARNING: This can be risky!)")
            hookSystemServer(lpparam)
            return
        }

        // For all other apps: Hook at application level (SAFE)
        // This works for most apps and doesn't risk bootloop
        hookApplicationLevel(lpparam)
    }

    /**
     * Hook system server - ONLY when explicitly enabled
     * This is RISKY and can cause bootloop if not careful
     */
    @SuppressLint("NewApi")
    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Note: updateLocation() is called in each hook method for real-time bearing
            // No need to pre-call here

            if (Build.VERSION.SDK_INT < 34) {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.LocationManagerService",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass, "getLastLocation",
                    LocationRequest::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // CRITICAL: Force update to get FRESH bearing and speed
                            updateLocation()
                            
                            val location = Location(LocationManager.GPS_PROVIDER)
                            // CRITICAL FIX: ALWAYS use fresh timestamps (not cached)
                            location.time = System.currentTimeMillis()
                            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            
                            // CRITICAL: Set speed and bearing
                            location.speed = speed
                            location.bearing = bearing
                            
                            // Ensure accuracy is valid (5-20m is realistic for GPS)
                            location.accuracy = accuracy.coerceIn(5f, 20f)
                            location.speedAccuracyMetersPerSecond = 0.5F
                            location.bearingAccuracyDegrees = 10f
                            
                            // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                            setLocationFieldsWithFlags(location)
                            
                            // Add GPS extras for better validity
                            val extras = android.os.Bundle()
                            extras.putInt("satellites", 12)
                            location.extras = extras
                            
                            param.result = location
                        }
                    }
                )

                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.returnType == Boolean::class.java) {
                        if (method.name == "addGnssBatchingCallback" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = false
                                    }
                                }
                            )
                        }
                    }
                }

                XposedHelpers.findAndHookMethod(
                    "com.android.server.LocationManagerService.Receiver", // com.android.server.LocationManagerService\$Receiver
                    lpparam.classLoader,
                    "callLocationChangedLocked",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // CRITICAL: Force update to get FRESH bearing and speed
                            updateLocation()
                            
                            lateinit var location: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                            } else {
                                val originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }
                            
                            // CRITICAL: ALWAYS use fresh timestamps (not cached from originLocation)
                            location.time = System.currentTimeMillis()
                            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.accuracy = accuracy.coerceIn(5f, 20f)
                            location.bearing = bearing
                            location.speed = speed
                            location.speedAccuracyMetersPerSecond = 0.5F
                            location.bearingAccuracyDegrees = 10f
                            
                            // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                            setLocationFieldsWithFlags(location)
                            
                            // Add GPS extras for better validity
                            val extras = android.os.Bundle()
                            extras.putInt("satellites", 12)
                            location.extras = extras
                            
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}, bearing: ${location.bearing}, speed: ${location.speed}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
                        }
                    }
                )
            } else {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.location.LocationManagerService",
                    lpparam.classLoader
                )
                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                        // params: String::class.java, LastLocationRequest::class.java, String::class.java, String::class.java
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    // CRITICAL: Force update to get FRESH bearing and speed
                                    updateLocation()
                                    
                                    val location = Location(LocationManager.GPS_PROVIDER)
                                    // CRITICAL: ALWAYS use fresh timestamps
                                    location.time = System.currentTimeMillis()
                                    location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                    
                                    location.latitude = newlat
                                    location.longitude = newlng
                                    location.altitude = 0.0
                                    location.speed = speed
                                    location.bearing = bearing
                                    
                                    location.accuracy = accuracy.coerceIn(5f, 20f)
                                    location.speedAccuracyMetersPerSecond = 0.5F
                                    location.bearingAccuracyDegrees = 10f
                                    
                                    // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                                    setLocationFieldsWithFlags(location)
                                    
                                    // Add GPS extras for better validity
                                    val extras = android.os.Bundle()
                                    extras.putInt("satellites", 12)
                                    location.extras = extras
                                    
                                    param.result = location
                                }
                            }
                        )
                    } else if (method.returnType == Void::class.java) {
                        if (method.name == "startGnssBatch" ||
                            method.name == "addGnssAntennaInfoListener" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = null
                                    }
                                }
                            )
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass,
                    "injectLocation",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // CRITICAL: Force update to get FRESH bearing and speed
                            updateLocation()
                            
                            lateinit var location: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                            } else {
                                val originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }
                            
                            // CRITICAL: ALWAYS use fresh timestamps (not cached from originLocation)
                            location.time = System.currentTimeMillis()
                            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.accuracy = accuracy.coerceIn(5f, 20f)
                            location.bearing = bearing
                            location.speed = speed
                            location.speedAccuracyMetersPerSecond = 0.5F
                            location.bearingAccuracyDegrees = 10f
                            
                            // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                            setLocationFieldsWithFlags(location)
                            
                            // Add GPS extras for better validity
                            val extras = android.os.Bundle()
                            extras.putInt("satellites", 12)
                            location.extras = extras
                            
                            // Optimize: Remove excessive logging from Android 14+ hook
                            // XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}, bearing: ${location.bearing}, speed: ${location.speed}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: System hook failed: ${e.message}")
        }
    }

    /**
     * Hook application level - SAFE approach
     * This hooks Location API at app level without touching system
     * OPTIMIZED: Only hook essential methods to prevent app freeze
     */
    private fun hookApplicationLevel(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Skip our own app and fused location provider
            if (ignorePkg.contains(lpparam.packageName)) {
                XposedBridge.log("LocationHook: Skipping package ${lpparam.packageName} (in ignore list)")
                return
            }
            
            // CRITICAL: Always initialize hooks, but they will only work when GPS is started
            // This ensures hooks are ready when GPS is enabled
            XposedBridge.log("LocationHook: Initializing hooks for package ${lpparam.packageName} (GPS started=${settings.isStarted})")

            // PERFORMANCE FIX: Longer interval to reduce overhead
            val interval = 1000L  // Increased from 300ms to 1000ms to reduce CPU usage

            // Hook only essential Location methods
            val LocationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )

            // Core location methods - MUST hook
            hookLocationMethod(LocationClass, "getLatitude", interval, lpparam) { 
                updateLocation()
                // CRITICAL: Validate lat is not default value
                if (newlat == 45.0 && newlng == 0.0) {
                    XposedBridge.log("LocationHook: ERROR - getLatitude() returning default value! This will cause GPS loss!")
                }
                // PERFORMANCE FIX: Removed excessive logging
                newlat 
            }
            hookLocationMethod(LocationClass, "getLongitude", interval, lpparam) { 
                updateLocation()
                // CRITICAL: Validate lng is not default value
                if (newlat == 45.0 && newlng == 0.0) {
                    XposedBridge.log("LocationHook: ERROR - getLongitude() returning default value! This will cause GPS loss!")
                }
                // PERFORMANCE FIX: Removed excessive logging
                newlng 
            }
            
            // CRITICAL: Hook getBearing() and getSpeed() - Google Maps reads these!
            hookLocationMethod(LocationClass, "getBearing", interval, lpparam) { 
                // Always update to get fresh bearing
                updateLocation()
                // Use the bearing that was calculated in updateLocation()
                // (it already handles navigation vs settings fallback)
                
                // PERFORMANCE FIX: Removed excessive logging
                bearing
            }
            hookLocationMethod(LocationClass, "getSpeed", interval, lpparam) { 
                // Always update to get fresh speed
                updateLocation()
                // Use the speed that was calculated in updateLocation()
                // (it already handles navigation vs settings fallback)
                
                // PERFORMANCE FIX: Removed excessive logging
                speed
            }
            
            // Optional methods - only if needed
            try {
                hookLocationMethod(LocationClass, "getAccuracy", interval, lpparam) { accuracy }
            } catch (e: Throwable) { /* Ignore if method doesn't exist */ }

            // CRITICAL: Hook Location constructor to ensure ALL Location objects have fake bearing
            // Google Maps may create Location objects directly without calling getBearing()
            try {
                XposedHelpers.findAndHookConstructor(
                    LocationClass,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                
                                val location = param.thisObject as Location
                                
                                // Update location data
                                updateLocation()
                                
                                // Set fake bearing and speed on newly created Location object
                                location.bearing = bearing
                                location.bearingAccuracyDegrees = 10f
                                location.speed = speed
                                location.speedAccuracyMetersPerSecond = 0.5F
                                
                                // Set flags
                                setLocationFieldsWithFlags(location)
                                
                // PERFORMANCE FIX: Removed excessive logging
                            } catch (e: Throwable) {
                                // Silently fail
                            }
                        }
                    }
                )
                XposedBridge.log("LocationHook: Successfully hooked Location constructor for package ${lpparam.packageName}")
            } catch (e: Throwable) {
                XposedBridge.log("LocationHook: ERROR hooking Location constructor: ${e.message}")
            }

            // Hook Location.set() to modify location objects
            try {
                XposedHelpers.findAndHookMethod(
                    LocationClass,
                    "set",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                            
                            // CRITICAL: ALWAYS update to get fresh bearing/speed (no interval check)
                                updateLocation()
                            
                            // CRITICAL: Validate lat/lng before modifying location
                            if (newlat == 45.0 && newlng == 0.0) {
                                XposedBridge.log("LocationHook: ERROR - Location.set() using default lat/lng! GPS will be lost!")
                                // Don't modify location if values are invalid
                                return
                            }
                            
                            try {
                                val originLocation = param.args[0] as? Location ?: return
                                val location = Location(originLocation.provider)
                                
                                // CRITICAL: ALWAYS use fresh timestamps (not cached)
                                location.time = System.currentTimeMillis()
                                location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                
                                location.latitude = newlat
                                location.longitude = newlng
                                location.altitude = 0.0
                                location.accuracy = accuracy.coerceIn(5f, 20f)
                                location.bearing = bearing
                                location.bearingAccuracyDegrees = 10f
                                location.speed = speed
                                location.speedAccuracyMetersPerSecond = 0.5F
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters ?: 5f
                                
                                // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                                setLocationFieldsWithFlags(location)
                                
                                // Add GPS extras for better validity
                                val extras = android.os.Bundle()
                                extras.putInt("satellites", 12)
                                location.extras = extras
                                
                                try {
                                    HiddenApiBypass.invoke(
                                        location.javaClass, location, "setIsFromMockProvider", false
                                    )
                                } catch (e: Exception) { /* Ignore */ }
                                
                                param.args[0] = location
                            } catch (e: Throwable) { /* Ignore */ }
                        }
                    }
                )
            } catch (e: Throwable) { /* Ignore */ }

            // Hook LocationManager.getLastKnownLocation()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager",
                    lpparam.classLoader,
                    "getLastKnownLocation",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                            
                            // CRITICAL: ALWAYS update to get fresh bearing/speed (no interval check)
                                updateLocation()
                            
                            // CRITICAL: Validate lat/lng before creating location
                            if (newlat == 45.0 && newlng == 0.0) {
                                XposedBridge.log("LocationHook: ERROR - getLastKnownLocation() using default lat/lng! GPS will be lost!")
                                // Return null instead of invalid location
                                param.result = null
                                return
                            }
                            
                            try {
                                val provider = param.args[0] as String
                                val location = Location(provider)
                                // CRITICAL: ALWAYS use fresh timestamps
                                location.time = System.currentTimeMillis()
                                location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                
                                location.latitude = newlat
                                location.longitude = newlng
                                location.altitude = 0.0
                                location.accuracy = accuracy.coerceIn(5f, 20f)
                                location.bearing = bearing
                                location.bearingAccuracyDegrees = 10f
                                location.speed = speed
                                location.speedAccuracyMetersPerSecond = 0.5F
                                
                                // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                                setLocationFieldsWithFlags(location)
                                
                                // Add GPS extras for better validity
                                val extras = android.os.Bundle()
                                extras.putInt("satellites", 12)
                                location.extras = extras
                                
                                try {
                                    HiddenApiBypass.invoke(
                                        location.javaClass, location, "setIsFromMockProvider", false
                                    )
                                } catch (e: Exception) { /* Ignore */ }
                                
                                param.result = location
                            } catch (e: Throwable) { /* Ignore */ }
                        }
                    }
                )
            } catch (e: Throwable) { /* Ignore */ }

            // CRITICAL: Hook LocationManager.requestLocationUpdates() for continuous updates
            // This is THE MOST IMPORTANT hook for Google Maps and navigation apps
            try {
                val locationManagerClass = XposedHelpers.findClass(
                    "android.location.LocationManager",
                    lpparam.classLoader
                )
                
                // Hook requestLocationUpdates with LocationListener
                try {
                    XposedBridge.log("LocationHook: Attempting to hook LocationManager.requestLocationUpdates() for package ${lpparam.packageName}")
                    XposedHelpers.findAndHookMethod(
                        locationManagerClass,
                        "requestLocationUpdates",
                        String::class.java,
                        Long::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        android.location.LocationListener::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                    
                                    val provider = param.args[0] as? String ?: "gps"
                                    val minTime = param.args[1] as? Long ?: 0L
                                    val minDistance = param.args[2] as? Float ?: 0f
                                    val listener = param.args[3] as? android.location.LocationListener
                                    
                                    // PERFORMANCE FIX: Removed excessive logging
                                    
                                    if (listener != null) {
                                        // Wrap listener to inject fake locations continuously
                                        val wrappedListener = object : android.location.LocationListener {
                                            override fun onLocationChanged(location: Location) {
                                                try {
                                                    // CRITICAL: Update location data to get fresh bearing/speed
                                                    // This will calculate bearing/speed with proper fallback logic
                                                    updateLocation()
                                                    
                                                    // CRITICAL: Validate lat/lng before creating location
                                                    if (newlat == 45.0 && newlng == 0.0) {
                                                        XposedBridge.log("LocationHook: ERROR - onLocationChanged() using default lat/lng! GPS will be lost!")
                                                        // Don't inject fake location if values are invalid
                                                        listener.onLocationChanged(location)
                                                        return
                                                    }
                                                    
                                                    // Create fake location
                                                    val fakeLocation = Location(location.provider)
                                                    fakeLocation.time = System.currentTimeMillis()
                                                    fakeLocation.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                    fakeLocation.latitude = newlat
                                                    fakeLocation.longitude = newlng
                                                    fakeLocation.altitude = 0.0
                                                    fakeLocation.accuracy = accuracy.coerceIn(5f, 20f)
                                                    
                                                    // Use bearing/speed calculated by updateLocation() (already handles fallback)
                                                    fakeLocation.bearing = bearing
                                                    fakeLocation.bearingAccuracyDegrees = 10f
                                                    fakeLocation.speed = speed
                                                    fakeLocation.speedAccuracyMetersPerSecond = 0.5F
                                                    
                                                    // CRITICAL: Set flags
                                                    setLocationFieldsWithFlags(fakeLocation)
                                                    
                // PERFORMANCE FIX: Removed excessive logging
                                                    
                                                    // Add extras
                                                    val extras = android.os.Bundle()
                                                    extras.putInt("satellites", 12)
                                                    fakeLocation.extras = extras
                                                    
                                                    try {
                                                        HiddenApiBypass.invoke(
                                                            fakeLocation.javaClass, fakeLocation, "setIsFromMockProvider", false
                                                        )
                                                    } catch (e: Exception) { /* Ignore */ }
                                                    
                                                    // Call original listener with fake location
                                                    listener.onLocationChanged(fakeLocation)
                                                } catch (e: Throwable) {
                                                    // Fallback: call original
                                                    try {
                                                        listener.onLocationChanged(location)
                                                    } catch (e2: Throwable) { /* Ignore */ }
                                                }
                                            }
                                            
                                            override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle) {
                                                try {
                                                    listener.onStatusChanged(provider, status, extras)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderEnabled(provider: String) {
                                                try {
                                                    listener.onProviderEnabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderDisabled(provider: String) {
                                                try {
                                                    listener.onProviderDisabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                        }
                                        
                                        // Replace listener
                                        param.args[3] = wrappedListener
                                        
                                        // CRITICAL: Start periodic location updates to prevent GPS loss
                                        // This ensures continuous location updates even if system doesn't provide them
                                        val updateInterval = minTime.coerceAtMost(1000L)
                                        startPeriodicLocationUpdates(wrappedListener, updateInterval)
                                        
                                        // PERFORMANCE FIX: Removed excessive logging
                                    } else {
                                        XposedBridge.log("LocationHook: WARNING - LocationListener is null in requestLocationUpdates()")
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("LocationHook: ERROR in requestLocationUpdates hook: ${e.message}")
                                }
                            }
                        }
                    )
                    XposedBridge.log("LocationHook: Successfully hooked LocationManager.requestLocationUpdates(LocationListener) for package ${lpparam.packageName}")
                } catch (e: Throwable) {
                    XposedBridge.log("LocationHook: ERROR hooking requestLocationUpdates(LocationListener) for package ${lpparam.packageName}: ${e.message}")
                }
                
                // Also hook with LocationRequest (Android 8.0+)
                try {
                    XposedHelpers.findAndHookMethod(
                        locationManagerClass,
                        "requestLocationUpdates",
                        android.location.LocationRequest::class.java,
                        android.location.LocationListener::class.java,
                        android.os.Looper::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                    
                                    val locationRequest = param.args[0] as? android.location.LocationRequest
                                    val listener = param.args[1] as? android.location.LocationListener
                                    if (listener != null) {
                                        // Same wrapping logic as above
                                        val wrappedListener = object : android.location.LocationListener {
                                            override fun onLocationChanged(location: Location) {
                                                try {
                                                    // CRITICAL: Update location data to get fresh bearing/speed
                                                    // This will calculate bearing/speed with proper fallback logic
                                                    updateLocation()
                                                    
                                                    // CRITICAL: Validate lat/lng before creating location
                                                    if (newlat == 45.0 && newlng == 0.0) {
                                                        XposedBridge.log("LocationHook: ERROR - onLocationChanged(LocationRequest) using default lat/lng! GPS will be lost!")
                                                        listener.onLocationChanged(location)
                                                        return
                                                    }
                                                    
                                                    val fakeLocation = Location(location.provider)
                                                    fakeLocation.time = System.currentTimeMillis()
                                                    fakeLocation.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                    fakeLocation.latitude = newlat
                                                    fakeLocation.longitude = newlng
                                                    fakeLocation.altitude = 0.0
                                                    fakeLocation.accuracy = accuracy.coerceIn(5f, 20f)
                                                    
                                                    // Use bearing/speed calculated by updateLocation() (already handles fallback)
                                                    fakeLocation.bearing = bearing
                                                    fakeLocation.bearingAccuracyDegrees = 10f
                                                    fakeLocation.speed = speed
                                                    fakeLocation.speedAccuracyMetersPerSecond = 0.5F
                                                    
                                                    setLocationFieldsWithFlags(fakeLocation)
                                                    
                                                    val extras = android.os.Bundle()
                                                    extras.putInt("satellites", 12)
                                                    fakeLocation.extras = extras
                                                    
                                                    try {
                                                        HiddenApiBypass.invoke(
                                                            fakeLocation.javaClass, fakeLocation, "setIsFromMockProvider", false
                                                        )
                                                    } catch (e: Exception) { /* Ignore */ }
                                                    
                                                    listener.onLocationChanged(fakeLocation)
                                                } catch (e: Throwable) {
                                                    try {
                                                        listener.onLocationChanged(location)
                                                    } catch (e2: Throwable) { /* Ignore */ }
                                                }
                                            }
                                            
                                            override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle) {
                                                try {
                                                    listener.onStatusChanged(provider, status, extras)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderEnabled(provider: String) {
                                                try {
                                                    listener.onProviderEnabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderDisabled(provider: String) {
                                                try {
                                                    listener.onProviderDisabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                        }
                                        
                                        param.args[1] = wrappedListener
                                        
                                        // CRITICAL FIX: Start periodic location updates to prevent GPS loss
                                        // This ensures continuous location updates even if system doesn't provide them
                                        val minTime = try {
                                            XposedHelpers.getLongField(locationRequest, "mIntervalMillis")
                                        } catch (e: Throwable) {
                                            1000L
                                        }
                                        val updateInterval = minTime.coerceAtMost(1000L)
                                        startPeriodicLocationUpdates(wrappedListener, updateInterval)
                                        
                                        // PERFORMANCE FIX: Removed excessive logging
                                    }
                                } catch (e: Throwable) {
                                    // Silently fail
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    // May not exist on older Android versions
                }
                
            } catch (e: Throwable) {
                XposedBridge.log("LocationHook: Failed to hook requestLocationUpdates: ${e.message}")
            }

            // Hook Google Play Services - only if available
            try {
                hookFusedLocationProvider(lpparam)
            } catch (e: Throwable) { /* Not all apps have Google Play Services */ }
            
        } catch (e: Throwable) {
            // Silently fail - don't block app
        }
    }
    
    /**
     * Helper to hook location getter methods
     */
    private fun hookLocationMethod(
        locationClass: Class<*>,
        methodName: String,
        interval: Long,
        lpparam: XC_LoadPackage.LoadPackageParam,
        getValue: () -> Any
    ) {
        try {
            var hooked = false
            for (method in locationClass.declaredMethods) {
                if (method.name == methodName) {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                
                                // PERFORMANCE FIX: Only update location if cache expired
                                val now = System.currentTimeMillis()
                                if (now - lastLocationUpdateTime > LOCATION_CACHE_DURATION_MS) {
                                    updateLocation()
                                    lastLocationUpdateTime = now
                                }
                                param.result = getValue()
                            }
                        }
                    )
                    hooked = true
                    XposedBridge.log("LocationHook: Successfully hooked Location.$methodName() for package ${lpparam.packageName}")
                }
            }
            if (!hooked) {
                XposedBridge.log("LocationHook: WARNING - Could not find Location.$methodName() for package ${lpparam.packageName}")
            }
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: ERROR hooking Location.$methodName() for package ${lpparam.packageName}: ${e.message}")
        }
    }

    /**
     * Hook Google Play Services FusedLocationProvider
     * Many modern apps (especially banking apps) use this instead of LocationManager
     * CRITICAL: Hook both getLastLocation() and requestLocationUpdates()
     */
    private fun hookFusedLocationProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (ignorePkg.contains(lpparam.packageName)) return
            
            // Try to find FusedLocationProviderClient
            val fusedLocationClass = XposedHelpers.findClassIfExists(
                "com.google.android.gms.location.FusedLocationProviderClient",
                lpparam.classLoader
            ) ?: return

            XposedBridge.log("LocationHook: Found FusedLocationProviderClient for package ${lpparam.packageName}")
            
            // CRITICAL: Always hook, but only inject when settings.isStarted

            // Hook getLastLocation() - returns Task<Location>
            // We hook Task.addOnSuccessListener() to inject fake location
            try {
                val taskClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.tasks.Task",
                    lpparam.classLoader
                ) ?: return
                
                // Hook Task.addOnSuccessListener() to intercept location results
                XposedHelpers.findAndHookMethod(
                    taskClass,
                    "addOnSuccessListener",
                    com.google.android.gms.tasks.OnSuccessListener::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                
                                val listener = param.args[0] as? com.google.android.gms.tasks.OnSuccessListener<*>
                                if (listener == null) return
                                
                                // Check if this is a Location Task by checking listener type
                                // More robust check: look at method signatures and class names
                                try {
                                    val listenerClassName = listener.javaClass.name
                                    val listenerString = listener.toString()
                                    
                                    // Check if this listener is for Location
                                    val isLocationListener = listenerClassName.contains("Location") ||
                                            listenerString.contains("Location") ||
                                            listener.javaClass.genericInterfaces.any { 
                                                it.toString().contains("Location") 
                                            }
                                    
                                    if (isLocationListener) {
                                        XposedBridge.log("LocationHook: Detected Location Task OnSuccessListener for package ${lpparam.packageName}")
                                        // Wrap the OnSuccessListener to inject fake location
                                        val wrappedListener = com.google.android.gms.tasks.OnSuccessListener<Location> { location ->
                                            try {
                                                updateLocation()
                                                
                                                if (newlat == 45.0 && newlng == 0.0) {
                                                    XposedBridge.log("LocationHook: ERROR - FusedLocationProvider getLastLocation using default lat/lng!")
                                                    @Suppress("UNCHECKED_CAST")
                                                    (listener as com.google.android.gms.tasks.OnSuccessListener<Location>).onSuccess(location)
                                                    return@OnSuccessListener
                                                }
                                                
                                                val fakeLocation = Location(location?.provider ?: "gps")
                                                fakeLocation.time = System.currentTimeMillis()
                                                fakeLocation.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                fakeLocation.latitude = newlat
                                                fakeLocation.longitude = newlng
                                                fakeLocation.altitude = 0.0
                                                fakeLocation.accuracy = accuracy.coerceIn(5f, 20f)
                                                fakeLocation.bearing = bearing
                                                fakeLocation.bearingAccuracyDegrees = 10f
                                                fakeLocation.speed = speed
                                                fakeLocation.speedAccuracyMetersPerSecond = 0.5F
                                                setLocationFieldsWithFlags(fakeLocation)
                                                
                                                val extras = android.os.Bundle()
                                                extras.putInt("satellites", 12)
                                                fakeLocation.extras = extras
                                                
                                                try {
                                                    HiddenApiBypass.invoke(
                                                        fakeLocation.javaClass, fakeLocation, "setIsFromMockProvider", false
                                                    )
                                                } catch (e: Exception) { /* Ignore */ }
                                                
                                                @Suppress("UNCHECKED_CAST")
                                                (listener as com.google.android.gms.tasks.OnSuccessListener<Location>).onSuccess(fakeLocation)
                                                
                                                // PERFORMANCE FIX: Removed excessive logging
                                            } catch (e: Throwable) {
                                                XposedBridge.log("LocationHook: Error in FusedLocationProvider getLastLocation wrapper: ${e.message}")
                                                try {
                                                    @Suppress("UNCHECKED_CAST")
                                                    (listener as com.google.android.gms.tasks.OnSuccessListener<Location>).onSuccess(location)
                                                } catch (e2: Throwable) { /* Ignore */ }
                                            }
                                        }
                                        
                                        // Replace listener
                                        param.args[0] = wrappedListener
                                        
                                        // PERFORMANCE FIX: Removed excessive logging
                                    }
                                } catch (e: Throwable) {
                                    // Not a Location Task, ignore
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("LocationHook: Error hooking Task.addOnSuccessListener: ${e.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("LocationHook: Successfully hooked Task.addOnSuccessListener() for FusedLocationProvider in package ${lpparam.packageName}")
            } catch (e: Throwable) {
                XposedBridge.log("LocationHook: Failed to hook Task.addOnSuccessListener() for FusedLocationProvider: ${e.message}")
            }

            // Hook requestLocationUpdates() with LocationRequest and LocationCallback
            try {
                val locationRequestClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.location.LocationRequest",
                    lpparam.classLoader
                )
                val locationCallbackClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.location.LocationCallback",
                    lpparam.classLoader
                )
                val looperClass = android.os.Looper::class.java
                
                if (locationRequestClass != null && locationCallbackClass != null) {
                XposedHelpers.findAndHookMethod(
                    fusedLocationClass,
                        "requestLocationUpdates",
                        locationRequestClass,
                        locationCallbackClass,
                        looperClass,
                    object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                    
                                    val locationRequest = param.args[0]
                                    val locationCallback = param.args[1] as? com.google.android.gms.location.LocationCallback
                                    
                                    // PERFORMANCE FIX: Removed excessive logging
                                    
                                    if (locationCallback != null) {
                                        // Wrap LocationCallback to inject fake locations
                                        val wrappedCallback = object : com.google.android.gms.location.LocationCallback() {
                                            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                                                try {
                                                    updateLocation()
                                                    
                                                    if (newlat == 45.0 && newlng == 0.0) {
                                                        XposedBridge.log("LocationHook: ERROR - FusedLocationProvider onLocationResult using default lat/lng!")
                                                        locationCallback.onLocationResult(locationResult)
                                                        return
                                                    }
                                                    
                                                    // Create fake LocationResult
                                                    val fakeLocation = Location("gps")
                                                    fakeLocation.time = System.currentTimeMillis()
                                                    fakeLocation.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                    fakeLocation.latitude = newlat
                                                    fakeLocation.longitude = newlng
                                                    fakeLocation.altitude = 0.0
                                                    fakeLocation.accuracy = accuracy.coerceIn(5f, 20f)
                                                    fakeLocation.bearing = bearing
                                                    fakeLocation.bearingAccuracyDegrees = 10f
                                                    fakeLocation.speed = speed
                                                    fakeLocation.speedAccuracyMetersPerSecond = 0.5F
                                                    setLocationFieldsWithFlags(fakeLocation)
                                                    
                                                    val extras = android.os.Bundle()
                                                    extras.putInt("satellites", 12)
                                                    fakeLocation.extras = extras
                                                    
                                                    try {
                                                        HiddenApiBypass.invoke(
                                                            fakeLocation.javaClass, fakeLocation, "setIsFromMockProvider", false
                                                        )
                                                    } catch (e: Exception) { /* Ignore */ }
                                                    
                                                    // Create LocationResult with fake location
                                                    val fakeLocationResult = com.google.android.gms.location.LocationResult.create(listOf(fakeLocation))
                                                    
                                                    locationCallback.onLocationResult(fakeLocationResult)
                                                    
                                                // PERFORMANCE FIX: Removed excessive logging
                                                } catch (e: Throwable) {
                                                    XposedBridge.log("LocationHook: Error in FusedLocationProvider LocationCallback wrapper: ${e.message}")
                                                    locationCallback.onLocationResult(locationResult)
                                                }
                                            }
                                        }
                                        
                                        // Replace callback
                                        param.args[1] = wrappedCallback
                                        
                                        // Start periodic updates
                                        val minInterval = try {
                                            XposedHelpers.getLongField(locationRequest, "mIntervalMillis")
                                        } catch (e: Throwable) {
                                            1000L
                                        }
                                        startPeriodicFusedLocationUpdates(wrappedCallback, minInterval.coerceAtMost(1000L))
                                        
                                        // PERFORMANCE FIX: Removed excessive logging
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("LocationHook: Error hooking FusedLocationProvider requestLocationUpdates: ${e.message}")
                                }
                            }
                        }
                    )
                    XposedBridge.log("LocationHook: Successfully hooked FusedLocationProvider.requestLocationUpdates() for package ${lpparam.packageName}")
                }
            } catch (e: Throwable) {
                XposedBridge.log("LocationHook: Failed to hook FusedLocationProvider.requestLocationUpdates(): ${e.message}")
            }
            
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: FusedLocationProvider not available for package ${lpparam.packageName}: ${e.message}")
        }
    }
    
    /**
     * PERFORMANCE FIX: Start periodic location updates for FusedLocationProvider using global handler
     */
    private fun startPeriodicFusedLocationUpdates(callback: com.google.android.gms.location.LocationCallback, interval: Long) {
        try {
            // Add callback to tracked list
            synchronized(registeredCallbacks) {
                if (!registeredCallbacks.contains(callback)) {
                    registeredCallbacks.add(callback)
                }
            }
            
            // Start global periodic update if not already running
            startGlobalPeriodicUpdates()
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: Failed to register callback: ${e.message}")
        }
    }

    /**
     * PERFORMANCE FIX: Start periodic location updates using SINGLE global Handler
     * This prevents memory leaks and reduces CPU usage
     */
    private fun startPeriodicLocationUpdates(listener: android.location.LocationListener, interval: Long) {
        try {
            // Add listener to tracked list
            synchronized(registeredListeners) {
                if (!registeredListeners.contains(listener)) {
                    registeredListeners.add(listener)
                }
            }
            
            // Start global periodic update if not already running
            startGlobalPeriodicUpdates()
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: Failed to register listener: ${e.message}")
        }
    }
    
    /**
     * PERFORMANCE FIX: Start SINGLE global periodic update handler
     * This updates ALL listeners at once, preventing multiple handlers from running
     */
    private fun startGlobalPeriodicUpdates() {
        synchronized(this) {
            if (isPeriodicUpdateRunning) return
            
            // Create handler on main looper if not exists
            if (globalUpdateHandler == null) {
                globalUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
            }
            
            // Create single runnable for all updates
            globalUpdateRunnable = object : Runnable {
                override fun run() {
                    try {
                        // Stop if GPS is disabled
                        if (!settings.isStarted) {
                            stopGlobalPeriodicUpdates()
                            return
                        }
                        
                        // Check if we have any listeners
                        val hasListeners = synchronized(registeredListeners) {
                            registeredListeners.isNotEmpty()
                        }
                        val hasCallbacks = synchronized(registeredCallbacks) {
                            registeredCallbacks.isNotEmpty()
                        }
                        
                        if (!hasListeners && !hasCallbacks) {
                            stopGlobalPeriodicUpdates()
                            return
                        }
                        
                        // Update location (cached for 500ms to avoid excessive updates)
                        val now = System.currentTimeMillis()
                        if (now - lastLocationUpdateTime > LOCATION_CACHE_DURATION_MS) {
                            updateLocation()
                            lastLocationUpdateTime = now
                        }
                        
                        // Validate lat/lng
                        if (newlat == 45.0 && newlng == 0.0) {
                            // Retry later
                            globalUpdateHandler?.postDelayed(this, UPDATE_INTERVAL_MS)
                            return
                        }
                        
                        // Create fake location once
                        val fakeLocation = createFakeLocation()
                        
                        // Update all LocationListeners
                        // PERFORMANCE FIX: Only create copy if we have listeners
                        synchronized(registeredListeners) {
                            if (registeredListeners.isNotEmpty()) {
                                val listenersCopy = ArrayList(registeredListeners)
                                listenersCopy.forEach { listener ->
                                    try {
                                        listener.onLocationChanged(fakeLocation)
                                    } catch (e: Throwable) {
                                        // Remove dead listener to prevent memory leak
                                        registeredListeners.remove(listener)
                                    }
                                }
                            }
                        }
                        
                        // Update all LocationCallbacks
                        // PERFORMANCE FIX: Create LocationResult once and reuse (if location doesn't change)
                        synchronized(registeredCallbacks) {
                            if (registeredCallbacks.isNotEmpty()) {
                                val callbacksCopy = ArrayList(registeredCallbacks)
                                val fakeLocationResult = com.google.android.gms.location.LocationResult.create(listOf(fakeLocation))
                                callbacksCopy.forEach { callback ->
                                    try {
                                        callback.onLocationResult(fakeLocationResult)
                                    } catch (e: Throwable) {
                                        // Remove dead callback to prevent memory leak
                                        registeredCallbacks.remove(callback)
                                    }
                                }
                            }
                        }
                        
                        // Schedule next update
                        globalUpdateHandler?.postDelayed(this, UPDATE_INTERVAL_MS)
                    } catch (e: Throwable) {
                        XposedBridge.log("LocationHook: Error in global periodic update: ${e.message}")
                        // Retry later
                        globalUpdateHandler?.postDelayed(this, UPDATE_INTERVAL_MS)
                    }
                }
            }
            
            isPeriodicUpdateRunning = true
            globalUpdateHandler?.post(globalUpdateRunnable!!)
        }
    }
    
    /**
     * PERFORMANCE FIX: Stop global periodic updates
     * Also cleanup listeners/callbacks to prevent memory leaks
     */
    private fun stopGlobalPeriodicUpdates() {
        synchronized(this) {
            if (!isPeriodicUpdateRunning) return
            
            globalUpdateHandler?.removeCallbacks(globalUpdateRunnable!!)
            isPeriodicUpdateRunning = false
            
            // MEMORY LEAK FIX: Clear listeners/callbacks when GPS stops
            // This prevents holding references to dead app contexts
            synchronized(registeredListeners) {
                registeredListeners.clear()
            }
            synchronized(registeredCallbacks) {
                registeredCallbacks.clear()
            }
        }
    }
    
    /**
     * Helper to create fake location (reused to avoid creating multiple objects)
     */
    private fun createFakeLocation(): Location {
        val fakeLocation = Location("gps")
        fakeLocation.time = System.currentTimeMillis()
        fakeLocation.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        fakeLocation.latitude = newlat
        fakeLocation.longitude = newlng
        fakeLocation.altitude = 0.0
        fakeLocation.accuracy = accuracy.coerceIn(5f, 20f)
        fakeLocation.bearing = bearing
        fakeLocation.bearingAccuracyDegrees = 10f
        fakeLocation.speed = speed
        fakeLocation.speedAccuracyMetersPerSecond = 0.5F
        setLocationFieldsWithFlags(fakeLocation)
        
        val extras = android.os.Bundle()
        extras.putInt("satellites", 12)
        fakeLocation.extras = extras
        
        try {
            HiddenApiBypass.invoke(
                fakeLocation.javaClass, fakeLocation, "setIsFromMockProvider", false
            )
        } catch (e: Exception) { /* Ignore */ }
        
        return fakeLocation
    }

    /**
     * REMOVED: hookTaskResult and hookLocationCallback
     * These methods were causing app freezes by hooking too deep into Google Play Services
     * Our simpler Location hooks are sufficient
     */
}
