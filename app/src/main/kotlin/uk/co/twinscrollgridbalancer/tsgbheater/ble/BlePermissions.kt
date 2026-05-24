package uk.co.twinscrollgridbalancer.tsgbheater.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

// minSdk is 31 so the legacy ACCESS_FINE_LOCATION dance isn't needed —
// BLUETOOTH_SCAN / BLUETOOTH_CONNECT cover every code path. CAMERA is here
// for the QR-bind flow on the Scan tab.
object BlePermissions {

    val SCAN_AND_CONNECT: List<String> = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    val FOR_QR_BIND: List<String> = SCAN_AND_CONNECT + Manifest.permission.CAMERA

    fun allGranted(ctx: Context, perms: List<String>): Boolean = perms.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(ctx: Context, perms: List<String>): List<String> = perms.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
}
