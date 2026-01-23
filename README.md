# Fork Moving GPS

[![Stars](https://img.shields.io/github/stars/HSSkyBoy/Fork-Moving-GPS)](https://github.com/HSSkyBoy/Fork-Moving-GPS/stargazers)
[![GitHub](https://img.shields.io/github/downloads/HSSkyBoy/Fork-Moving-GPS/total?label=GitHub&logo=GitHub)](https://github.com/HSSkyBoy/Fork-Moving-GPS/releases)
[![release](https://img.shields.io/github/v/release/HSSkyBoy/Fork-Moving-GPS)](https://github.com/HSSkyBoy/Fork-Moving-GPS/releases)
[![build](https://img.shields.io/github/actions/workflow/status/HSSkyBoy/Fork-Moving-GPS/apk.yml)](https://github.com/HSSkyBoy/Fork-Moving-GPS/actions/workflows/apk.yml)
[![license](https://img.shields.io/github/license/HSSkyBoy/Fork-Moving-GPS)](https://github.com/HSSkyBoy/Fork-Moving-GPS/blob/master/LICENSE)
[![issues](https://img.shields.io/github/issues/HSSkyBoy/Fork-Moving-GPS)](https://github.com/HSSkyBoy/Fork-Moving-GPS/issues)

A GPS setter based on the Xposed framework. This fork is the module to achieve support for Android 15+ with its sources available.

## Releases

<table>
    <tr>
        <th>Version</th>
        <th>app-full-*.apk</th>
        <th>app-foss-*.apk</th>
    </tr>
    <tr>
        <th>Maps Library</th>
        <td>GMS (com.google.android.gms:play-services-maps)</td>
        <td>MapLibre (org.maplibre.gl:android-sdk)</td>
    </tr>
    <tr>
        <th>Fused Location</th>
        <td>GMS (com.google.android.gms:play-services-location)</td>
        <td>microG (org.microg.gms:play-services-location)</td>
    </tr>
    <tr>
        <th>Distribution</th>
        <td>
            <a href="https://github.com/hsskyboy/forkmovinggps/releases">
                <img
                    src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/refs/heads/main/badge_github.png"
                    alt="Get it on GitHub" width="200" />
            </a>
        </td>
        <td>
            <a href="https://f-droid.org/packages/io.github.mwarevn.forkmovinggps">
                <img
                    src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
                    alt="Get it on F-Droid" width="200" />
            </a>
        </td>
    </tr>
</table>

<!--
[<img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/refs/heads/main/badge_github.png" alt="Get it on GitHub" height="80">](https://github.com/HSSkyBoy/Fork-Moving-GPS/releases)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">]()
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="80">]()
-->

## Motivation

An increasing number of apps are abusing the location permission for tracking purposes, preventing the user from using the app without granting the permission. Traditionally on Android, modifying the response from android server is done using the mock location provider - however, the availability of this feature is device dependent. Additionally, some apps have started explicitly checking for signals regarding whether the location provided is reliable. This module aims to mitigate this by providing an ability to either,

1. hook the app itself to modify the location it receives, or
2. hook the system server if the app explicitly checks for whether itself is being hooked

Specifically, in the case of hooking just the app, it intercepts [`android.location.Location`](https://developer.android.com/reference/android/location/Location) and [`android.location.LocationManager`](https://developer.android.com/reference/android/location/LocationManager) methods including

-   [`getLatitude()`](<https://developer.android.com/reference/android/location/Location#getLatitude()>)
-   [`getLongitude()`](<https://developer.android.com/reference/android/location/Location#getLongitude()>)
-   [`getAccuracy()`](<https://developer.android.com/reference/android/location/Location#getAccuracy()>)
-   [`getLastKnownLocation(java.lang.String)`](<https://developer.android.com/reference/android/location/LocationManager#getLastKnownLocation(java.lang.String)>)

## Compatibility

-   Android 8.1+ (tested up to Android 16 Beta 2)
-   Rooted devices with Xposed framework installed (e.g. LSPosed)
-   Unrooted devices with LSPatch (with manually embedded specified location)

## Features

-   🚗 **Moving Simulation** - Simulate realistic GPS movement along routes with adjustable speed (1-120 km/h)
-   🗺️ **Route Planning** - Plan routes with multiple waypoints and follow them automatically
-   ⏯️ **Playback Controls** - Pause, resume, and stop navigation with real-time progress tracking
-   📊 **Distance & Time Tracking** - Monitor traveled distance and remaining route in real-time
-   ✨ Supports system server location APIs introduced in Android 14+
-   🍀 Supports a fully FLOSS build flavor - including all underlying dependencies
-   🖲️ Allows adjusting location on the fly via an on-screen joystick overlay with screen-relative movement
-   🎯 Quick replace location button - change fake GPS location instantly without unset/set
-   🎨 Enhanced fake location indicator with larger, more visible markers
-   👆 Drag & drop support for destination marker in search mode for precise positioning
-   🔄 Real-time GPS position updates even when app is in background
-   📍 Joystick movements follow screen orientation, not compass direction
-   🎉 Features custom designed resource bundles with updated dependent libraries
-   🎲 Allows using a live updating random location in the vicinity of the set point
-   🔥 Compatible with latest Material Design

## Demo

<video loop src='https://github.com/user-attachments/assets/388ac05c-db56-42ce-9253-2f1855800a5c' alt="demo" width="200" style="display: block; margin: auto;"></video> <!-- https://github.com/hsskyboy/forkmovinggps/releases/download/v0.0.1/0.mp4 -->

## Credits

-   [Android1500](https://github.com/Android1500/GpsSetter) for the original GpsSetter targeting Android 8.1 to 13
-   [MapLibre](https://github.com/maplibre/maplibre-native) for the mapping library
-   [microG](https://github.com/microg/GmsCore) for the FOSS implementation of Google Mobile Services
