// The package is part of the service's name in the manifest and doubles as the notification channel id.
@file:Suppress("ktlint:standard:package-name")

package com.equimaps.capacitor_background_geolocation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.getcapacitor.JSObject
import com.getcapacitor.Logger
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginException
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

@CapacitorPlugin(
    name = "BackgroundGeolocation",
    permissions = [
        Permission(
            strings = [
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ],
            alias = "location"
        )
    ]
)
public class BackgroundGeolocation : Plugin() {
    private var service: BackgroundGeolocationService.LocalBinder? = null
    private var stoppedWithoutPermissions = false

    private fun fetchLastLocation(call: PluginCall) {
        try {
            LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener(activity) { location: Location? ->
                if (location != null) {
                    call.resolve(formatLocation(location))
                }
            }
        } catch (ignore: SecurityException) {
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public fun addWatcher(call: PluginCall) {
        val service = service ?: throw PluginException("Service not running.")
        call.keepAlive = true

        if (getPermissionState("location") != PermissionState.GRANTED) {
            if (call.getBoolean("requestPermissions", true) != false) {
                requestPermissionForAlias("location", call, "locationPermissionsCallback")
            } else {
                call.reject("Permission denied.", "NOT_AUTHORIZED")
            }
        } else if (!isLocationEnabled(context)) {
            call.reject("Location services disabled.", "NOT_AUTHORIZED")
        }
        // The watcher is added whatever the outcome above, so that it starts reporting as soon as the
        // permission is granted or location services are switched on.
        if (call.getBoolean("stale", false) == true) {
            fetchLastLocation(call)
        }
        var backgroundNotification: Notification? = null
        val backgroundMessage = call.getString("backgroundMessage")

        if (backgroundMessage != null) {
            // The channel decides the importance; the priority is still set because it always was.
            @Suppress("DEPRECATION")
            val builder = Notification.Builder(context, BackgroundGeolocationService.PACKAGE_NAME)
                .setContentTitle(call.getString("backgroundTitle", "Using your location"))
                .setContentText(backgroundMessage)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setWhen(System.currentTimeMillis())

            try {
                val name = getAppString("capacitor_background_geolocation_notification_icon", "mipmap/ic_launcher")
                // Trailing empty parts are dropped, as String.split does in Java.
                val parts = name.orEmpty().split("/").dropLastWhile { it.isEmpty() }
                // It is actually necessary to set a valid icon for the notification to behave
                // correctly when tapped. If there is no icon specified, tapping it will open the
                // app's settings, rather than bringing the application to the foreground.
                builder.setSmallIcon(getAppResourceIdentifier(parts[1], parts[0]))
            } catch (e: Exception) {
                Logger.error("Could not set notification icon", e)
            }

            try {
                val color = getAppString("capacitor_background_geolocation_notification_color", null)
                if (color != null) {
                    builder.setColor(Color.parseColor(color))
                }
            } catch (e: Exception) {
                Logger.error("Could not set notification color", e)
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            backgroundNotification = builder.build()
        }
        service.addWatcher(call.callbackId, backgroundNotification, call.getFloat("distanceFilter", 0f) ?: 0f)
    }

    @PermissionCallback
    private fun locationPermissionsCallback(call: PluginCall) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            throw PluginException("User denied location permission", "NOT_AUTHORIZED")
        }
        if (call.getBoolean("stale", false) == true) {
            fetchLastLocation(call)
        }
        service?.let {
            it.onPermissionsGranted()
            // The handleOnResume method will now be called, and we don't need it to call
            // service.onPermissionsGranted again so we reset this flag.
            stoppedWithoutPermissions = false
        }
    }

    @PluginMethod
    public fun removeWatcher(call: PluginCall) {
        val callbackId = call.getString("id") ?: throw PluginException("Missing id.")
        // Without a bound service this was a NullPointerException in the Java implementation as well.
        service!!.removeWatcher(callbackId)
        bridge.getSavedCall(callbackId)?.release(bridge)
        call.resolve()
    }

    @PluginMethod
    public fun openSettings(call: PluginCall) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", context.packageName, null)
        context.startActivity(intent)
        call.resolve()
    }

    // Receives messages from the service.
    private inner class ServiceReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val call = bridge.getSavedCall(intent?.getStringExtra("id")) ?: return

            @Suppress("DEPRECATION")
            val location = intent?.getParcelableExtra<Location>("location")
            if (location != null) {
                call.resolve(formatLocation(location))
            } else {
                Logger.debug("No locations received")
            }
        }
    }

    // Gets the identifier of the app's resource by name, returning 0 if not found.
    @SuppressLint("DiscouragedApi")
    private fun getAppResourceIdentifier(name: String, defType: String): Int =
        context.resources.getIdentifier(name, defType, context.packageName)

    // Gets a string from the app's strings.xml file, resorting to a fallback if it is not defined.
    private fun getAppString(name: String, fallback: String?): String? {
        val id = getAppResourceIdentifier(name, "string")
        return if (id == 0) fallback else context.getString(id)
    }

    override fun load() {
        super.load()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            BackgroundGeolocationService.PACKAGE_NAME,
            getAppString("capacitor_background_geolocation_notification_channel_name", "Background Tracking"),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)

        context.bindService(
            Intent(context, BackgroundGeolocationService::class.java),
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = binder as BackgroundGeolocationService.LocalBinder?
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            },
            Context.BIND_AUTO_CREATE
        )

        LocalBroadcastManager.getInstance(context).registerReceiver(
            ServiceReceiver(),
            IntentFilter(BackgroundGeolocationService.ACTION_BROADCAST)
        )
    }

    override fun handleOnResume() {
        if (stoppedWithoutPermissions && getPermissionState("location") == PermissionState.GRANTED) {
            service?.onPermissionsGranted()
        }
        super.handleOnResume()
    }

    override fun handleOnPause() {
        stoppedWithoutPermissions = getPermissionState("location") != PermissionState.GRANTED
        super.handleOnPause()
    }

    override fun handleOnDestroy() {
        service?.stopService()
        super.handleOnDestroy()
    }

    private companion object {
        // Checks if device-wide location services are disabled
        private fun isLocationEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
            return lm != null && lm.isLocationEnabled
        }

        private fun formatLocation(location: Location): JSObject {
            val obj = JSObject()
            obj.put("latitude", location.latitude)
            obj.put("longitude", location.longitude)
            // The docs state that all Location objects have an accuracy, but then why is there a
            // hasAccuracy method? Better safe than sorry.
            obj.put("accuracy", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
            obj.put("altitude", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
            if (location.hasVerticalAccuracy()) {
                // Java widened this float to a double, which serializes differently from a Float.
                obj.put("altitudeAccuracy", location.verticalAccuracyMeters.toDouble())
            } else {
                obj.put("altitudeAccuracy", JSONObject.NULL)
            }
            // In addition to mocking locations in development, Android allows the
            // installation of apps which have the power to simulate location
            // readings in other apps.
            obj.put("simulated", location.isMock)
            obj.put("speed", if (location.hasSpeed()) location.speed else JSONObject.NULL)
            obj.put("bearing", if (location.hasBearing()) location.bearing else JSONObject.NULL)
            obj.put("time", location.time)
            return obj
        }
    }
}
