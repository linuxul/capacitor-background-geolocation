// The package is part of the service's name in the manifest and doubles as the notification channel id.
@file:Suppress("ktlint:standard:package-name")

package com.equimaps.capacitor_background_geolocation

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.getcapacitor.Logger
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

// A bound and started service that is promoted to a foreground service
// (showing a persistent notification) when the first background watcher is
// added, and demoted when the last background watcher is removed.
public class BackgroundGeolocationService : Service() {
    private val binder: IBinder = LocalBinder()

    private class Watcher(
        val id: String?,
        val client: FusedLocationProviderClient,
        val locationRequest: LocationRequest,
        val locationCallback: LocationCallback,
        val backgroundNotification: Notification?
    )

    private var watchers = HashSet<Watcher>()

    override fun onBind(intent: Intent?): IBinder = binder

    // Some devices allow a foreground service to outlive the application's main
    // activity, leading to nasty crashes as reported in issue #59. If we learn
    // that the application has been killed, all watchers are stopped and the
    // service is terminated immediately.
    override fun onUnbind(intent: Intent?): Boolean {
        for (watcher in watchers) {
            watcher.client.removeLocationUpdates(watcher.locationCallback)
        }
        watchers = HashSet()
        stopSelf()
        return false
    }

    internal fun getNotification(): Notification? = watchers.firstNotNullOfOrNull { it.backgroundNotification }

    // Handles requests from the activity.
    public inner class LocalBinder : Binder() {
        internal fun addWatcher(id: String?, backgroundNotification: Notification?, distanceFilter: Float) {
            val client = LocationServices.getFusedLocationProviderClient(this@BackgroundGeolocationService)

            @Suppress("DEPRECATION")
            val locationRequest = LocationRequest().apply {
                maxWaitTime = 1000
                interval = 1000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                smallestDisplacement = distanceFilter
            }

            val callback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val intent = Intent(ACTION_BROADCAST)
                    intent.putExtra("location", locationResult.lastLocation)
                    intent.putExtra("id", id)
                    LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!availability.isLocationAvailable) {
                        Logger.debug("Location not available")
                    }
                }
            }

            val watcher = Watcher(id, client, locationRequest, callback, backgroundNotification)
            watchers.add(watcher)

            // According to Android Studio, this method can throw a Security Exception if
            // permissions are not yet granted. Rather than check the permissions, which is fiddly,
            // we simply ignore the exception.
            try {
                watcher.client.requestLocationUpdates(watcher.locationRequest, watcher.locationCallback, null)
            } catch (ignore: SecurityException) {
            }

            // Promote the service to the foreground if necessary.
            // Ideally we would only call 'startForeground' if the service is not already
            // foregrounded. Unfortunately, 'getForegroundServiceType' seems to behave weirdly, as
            // reported in #120. However, it appears that 'startForeground' is idempotent, so we
            // just call it repeatedly each time a background watcher is added.
            if (backgroundNotification != null) {
                try {
                    // This method has been known to fail due to weird
                    // permission bugs, so we prevent any exceptions from
                    // crashing the app. See issue #86.
                    startForeground(NOTIFICATION_ID, backgroundNotification)
                } catch (exception: Exception) {
                    Logger.error("Failed to foreground service", exception)
                }
            }
        }

        internal fun removeWatcher(id: String) {
            val watcher = watchers.firstOrNull { it.id == id } ?: return
            watcher.client.removeLocationUpdates(watcher.locationCallback)
            watchers.remove(watcher)
            if (getNotification() == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }

        internal fun onPermissionsGranted() {
            // If permissions were granted while the app was in the background, for example in
            // the Settings app, the watchers need restarting.
            for (watcher in watchers) {
                watcher.client.removeLocationUpdates(watcher.locationCallback)
                watcher.client.requestLocationUpdates(watcher.locationRequest, watcher.locationCallback, null)
            }
        }

        internal fun stopService() {
            this@BackgroundGeolocationService.stopSelf()
        }
    }

    internal companion object {
        // Also the id of the notification channel. Read from the class rather than written out so that it
        // keeps following the class when a shrinker repackages it, as it did in the Java implementation.
        internal val PACKAGE_NAME: String = BackgroundGeolocationService::class.java.name.substringBeforeLast('.')

        internal val ACTION_BROADCAST: String = "$PACKAGE_NAME.broadcast"

        // Must be unique for this application.
        private const val NOTIFICATION_ID = 28351
    }
}
