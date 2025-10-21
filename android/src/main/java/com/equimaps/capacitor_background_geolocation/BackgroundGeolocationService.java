package com.equimaps.capacitor_background_geolocation;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.getcapacitor.Logger;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.util.HashSet;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

// A bound and started service that is promoted to a foreground service
// (showing a persistent notification) when the first background watcher is
// added, and demoted when the last background watcher is removed.
public class BackgroundGeolocationService extends Service {
    static final String ACTION_BROADCAST = (
            BackgroundGeolocationService.class.getPackage().getName() + ".broadcast"
    );
    private final IBinder binder = new LocalBinder();

    // Must be unique for this application.
    private static final int NOTIFICATION_ID = 28351;

    private class Watcher {
        public String id;
        public FusedLocationProviderClient client;
        public LocationRequest locationRequest;
        public LocationCallback locationCallback;
        public Notification backgroundNotification;
        public Handler handler;
        public Runnable fallbackTask;
        public CancellationTokenSource[] ctsHolder;
    }
    private HashSet<Watcher> watchers = new HashSet<Watcher>();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // Some devices allow a foreground service to outlive the application's main
    // activity, leading to nasty crashes as reported in issue #59. If we learn
    // that the application has been killed, all watchers are stopped and the
    // service is terminated immediately.
    @Override
    public boolean onUnbind(Intent intent) {
        for (Watcher watcher : watchers) {
            watcher.client.removeLocationUpdates(watcher.locationCallback);
        }
        watchers = new HashSet<Watcher>();
        stopSelf();
        return false;
    }

    Notification getNotification() {
        for (Watcher watcher : watchers) {
            if (watcher.backgroundNotification != null) {
                return watcher.backgroundNotification;
            }
        }
        return null;
    }

    // Handles requests from the activity.
    public class LocalBinder extends Binder {
        void addWatcher(
                final String id,
                Notification backgroundNotification,
                float distanceFilter
        ) {
            FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(
                BackgroundGeolocationService.this
            );

            LocationRequest locationRequest = new LocationRequest();
            // GPS wakes up ONLY when device moves
            locationRequest.setSmallestDisplacement(distanceFilter);
            // Check for movement every 5s
            locationRequest.setInterval(5000);
            // Immediate updates on fast movement
            locationRequest.setFastestInterval(3000);
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            // Fallback 
            final Handler handler = new Handler(Looper.getMainLooper());
            // 60 seconds fallback for stationary scenarios
            final long FALLBACK_INTERVAL = 60000;
            final CancellationTokenSource[] ctsHolder = new CancellationTokenSource[1];
            final String watcherId = id; // Para closure

            final Runnable fallbackTask = new Runnable() {
                @Override
                public void run() {
                    Logger.debug("[LocationService] fallback timer disparou");
                    
                    // Cancel previous request
                    if (ctsHolder[0] != null) {
                        ctsHolder[0].cancel();
                    }
                    ctsHolder[0] = new CancellationTokenSource();

                    try {
                        // Force FRESH GPS location (not cache)
                        client.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY, 
                            ctsHolder[0].getToken()
                        ).addOnSuccessListener(location -> {
                            if (location != null) {
                                Logger.debug("[LocationService] fallback enviou localização: " +
                                    location.getLatitude() + "," + location.getLongitude() +
                                    " | Speed: " + (location.hasSpeed() ? location.getSpeed() : "NULL"));
                                
                                Intent intent = new Intent(ACTION_BROADCAST);
                                intent.putExtra("location", location);
                                intent.putExtra("id", watcherId);
                                LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
                            }
                        }).addOnFailureListener(e -> {
                            Logger.debug("[LocationService] Fallback failed: " + e.getMessage());
                        });
                    } catch (SecurityException ignored) {}

                    // Schedule next fallback
                    handler.postDelayed(this, FALLBACK_INTERVAL);
                }
            };

            // Location Callback (distance filter)
            LocationCallback callback = new LocationCallback() {
                private Location lastLocation = null;

                @Override
                public void onLocationResult(LocationResult locationResult) {
                    Location location = locationResult.getLastLocation();
                    if (location == null) return;

                    if (lastLocation != null) {
                        float distance = location.distanceTo(lastLocation);
                        Logger.debug("[LocationService] distanceFilter enviou update: " + 
                            (int)distance + "m >= " + distanceFilter + "m");
                    }

                    lastLocation = new Location(location);
                    
                    Logger.debug("[LocationService] DistanceFilter update: " +
                        location.getLatitude() + "," + location.getLongitude() +
                        " | Speed: " + (location.hasSpeed() ? location.getSpeed() : "NULL") +
                        " | Accuracy: " + location.getAccuracy() + "m");

                    // Broadcast to JavaScript
                    Intent intent = new Intent(ACTION_BROADCAST);
                    intent.putExtra("location", location);
                    intent.putExtra("id", id);
                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

                    // Restart fallback timer on movement
                    handler.removeCallbacks(fallbackTask);
                    handler.postDelayed(fallbackTask, FALLBACK_INTERVAL);
                }

                @Override
                public void onLocationAvailability(LocationAvailability availability) {
                    if (!availability.isLocationAvailable()) {
                        Logger.debug("[LocationService] Location not available");
                    }
                }
            };

            // Watcher configuration
            Watcher watcher = new Watcher();
            watcher.id = id;
            watcher.client = client;
            watcher.locationRequest = locationRequest;
            watcher.locationCallback = callback;
            watcher.backgroundNotification = backgroundNotification;
            
            // Store reference to clear
            watcher.handler = handler;
            watcher.fallbackTask = fallbackTask;
            watcher.ctsHolder = ctsHolder;
            
            watchers.add(watcher);

            // According to Android Studio, this method can throw a Security Exception if
            // permissions are not yet granted. Rather than check the permissions, which is fiddly,
            // we simply ignore the exception.
            try {
                // Start location updates (ignores SecurityException)
                watcher.client.requestLocationUpdates(
                        watcher.locationRequest,
                        watcher.locationCallback,
                        null
                );
            } catch (SecurityException ignore) {}

            // Promote the service to the foreground if necessary.
            // Ideally we would only call 'startForeground' if the service is not already
            // foregrounded. Unfortunately, 'getForegroundServiceType' was only introduced
            // in API level 29 and seems to behave weirdly, as reported in #120. However,
            // it appears that 'startForeground' is idempotent, so we just call it repeatedly
            // each time a background watcher is added.

            // Start foreground service
            if (backgroundNotification != null) {
                try {
                    // This method has been known to fail due to weird
                    // permission bugs, so we prevent any exceptions from
                    // crashing the app. See issue #86.
                    startForeground(NOTIFICATION_ID, backgroundNotification);
                } catch (Exception exception) {
                    Logger.error("Failed to foreground service", exception);
                }
            }

            // Start fallback
            handler.postDelayed(fallbackTask, FALLBACK_INTERVAL);
        }

        // Remove watcher and cleanup resources
        void removeWatcher(String id) {
            for (Watcher watcher : watchers) {
                if (watcher.id.equals(id)) {
                    watcher.client.removeLocationUpdates(watcher.locationCallback);
                    
                    // Clear fallback and token
                    if (watcher.handler != null) {
                        watcher.handler.removeCallbacksAndMessages(null);
                    }
                    if (watcher.ctsHolder != null && watcher.ctsHolder[0] != null) {
                        watcher.ctsHolder[0].cancel();
                    }
                    
                    watchers.remove(watcher);
                    if (getNotification() == null) {
                        stopForeground(true);
                    }
                    return;
                }
            }
        }

        // Restart watchers after permissions granted
        void onPermissionsGranted() {
            // If permissions were granted while the app was in the background, for example in
            // the Settings app, the watchers need restarting.
            for (Watcher watcher : watchers) {
                watcher.client.removeLocationUpdates(watcher.locationCallback);
                watcher.client.requestLocationUpdates(
                        watcher.locationRequest,
                        watcher.locationCallback,
                        null
                );
            }
        }

        void stopService() {
            BackgroundGeolocationService.this.stopSelf();
        }
    }
}
