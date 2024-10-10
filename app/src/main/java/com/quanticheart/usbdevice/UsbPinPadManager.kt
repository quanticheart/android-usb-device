package com.quanticheart.usbdevice

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.text.TextUtils
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class UsbPinPadManager {
    companion object {
        private const val ACTION_USB_PERMISSION = "ACTION_USB_PERMISSION"
        private const val REQ_PERMISSION_USB = 16

        private val mBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) {
                    context?.let { handleIntent(it, intent) }
                }
            }
        }

        fun unregisterBroadcast(activity: Activity) {
            activity.unregisterReceiver(mBroadcastReceiver)
        }

        fun registerBroadcast(activity: Activity) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            activity.registerReceiver(mBroadcastReceiver, filter)
        }

        private fun handleIntent(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED
                -> handleActionOnAttachDevice(context, intent)

                UsbManager.ACTION_USB_DEVICE_DETACHED
                -> handleActionOnDetachDevice(intent)

                ACTION_USB_PERMISSION
                -> handleActionUsbPermission(intent)

                Intent.ACTION_MAIN
                -> {
                    if (context is AppCompatActivity)
                        scanAttachedDevice(context)
                }

                else -> {
                    Log.e("Unknown intent", "action=" + intent.action)
                }
            }
        }

        private fun handleActionUsbPermission(intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val hasPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.e("Result", "hasPermission=$hasPermission\n    ${deviceName(device)}")
        }

        private fun handleActionOnDetachDevice(intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            Log.e("USB_DEVICE_DETACHED", deviceName(device))
        }

        private fun handleActionOnAttachDevice(context: Context, intent: Intent) {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            var hasPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (device != null) {
                hasPermission = hasPermission || manager.hasPermission(device)
                Log.e(
                    "USB_DEVICE_ATTACHED",
                    "hasPermission=$hasPermission\n    ${deviceName(device)}"
                )
                if (!hasPermission) {
                    if (context is AppCompatActivity)
                        requestUsbPermission(context, manager, device)
                } else {
                    Log.e("USB permission", "already has permission:\n    ${deviceName(device)}")
                }
            } else {
                Log.e("USB_DEVICE_ATTACHED", "device is null")
            }
        }


        fun verifyPermissions(activity: Activity) {
            scanDevice(activity)
            printHardware(activity)
        }

        private fun scanAttachedDevice(activity: Activity) {

            val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = manager.deviceList
            run loop@{
                devices.values.forEach {
                    if (isUVC(it)) {
                        if (!manager.hasPermission(it)) {
                            requestUsbPermission(activity, manager, it)
                            // XXX only request permission for first found device now
                            return@loop
                        } else {
                            Log.e(
                                "USB permission",
                                "already has permission:\n    ${deviceName(it)}"
                            )
                        }
                    }
                }
            }
            Log.e("SCAN", "finished")
        }

        private fun scanDevice(activity: Activity) {
            val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = manager.deviceList
            run loop@{
                devices.values.forEach {
                    val name = deviceName(it)
                    Log.v("PINPAD DEVICES", name)
                    if (name.lowercase().contains("pinpad")) {
//                    if (isPinPad(it)) {
                        printDevice(it)
                        requestUsbPermission(activity, manager, it)
                    }
                }
            }
        }

        /**
         * get device name of specific UsbDevice
         * return productName if it is available else return deviceName
         */
        private fun deviceName(device: UsbDevice?): String {
            var result = "device is null"
            if (device != null) {
                result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (!TextUtils.isEmpty(device.productName)) device.productName!! else device.deviceName
                } else {
                    device.deviceName
                }
            }
            return result
        }

        /**
         * request USB permission for specific device
         */
        private fun requestUsbPermission(
            activity: Activity,
            manager: UsbManager,
            device: UsbDevice
        ) {
            activity.runOnUiThread {
                var flags = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    activity,
                    REQ_PERMISSION_USB,
                    Intent(ACTION_USB_PERMISSION).setPackage(activity.packageName),
                    flags
                )
                manager.requestPermission(device, permissionIntent)
            }
        }

        /**
         * check whether the specific device or one of its interfaces is VIDEO class
         */
        private fun isPinPad(device: UsbDevice?): Boolean {
            var result = false
            if (device != null) {
                if (device.deviceClass == UsbConstants.USB_CLASS_COMM) {
                    result = true
                } else {
                    loop@ for (i in 0..device.interfaceCount) {
                        val iface = device.getInterface(i)
                        if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                            result = true
                            break@loop
                        }
                    }
                }
            }
            return result
        }

        /**
         * check whether the specific device or one of its interfaces is VIDEO class
         */
        private fun isUVC(device: UsbDevice?): Boolean {
            var result = false
            if (device != null) {
                if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) {
                    result = true
                } else {
                    loop@ for (i in 0..device.interfaceCount) {
                        val iface = device.getInterface(i)
                        if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                            result = true
                            break@loop
                        }
                    }
                }
            }
            return result
        }

        private fun printDevice(device: UsbDevice) {
            try {
                val msg = "    deviceName=${device.deviceName}\n" +
                        "    productName=${device.productName}\n" +
                        "    deviceId=${device.deviceId}\n" +
                        "    deviceClass=${device.deviceClass}\n" +
                        "    deviceProtocol=${device.deviceProtocol}\n" +
                        "    deviceSubclass=${device.deviceSubclass}\n" +
                        "    configurationCount=${device.configurationCount}\n" +
                        "    interfaceCount=${device.interfaceCount}\n" +
//                    "    serialNumber=${device.serialNumber}\n" +
                        "    vendorId=${device.vendorId}\n" +
                        "    productId=${device.productId}\n" +
                        "    manufacturerName=${device.manufacturerName}\n" +
                        "    version=${device.version}\n"
                Log.v("Device Pinpad Info", msg)
            } catch (e: Exception) {
                Log.e("ERROR", e.message ?: "Erro")
            }
        }

        private fun printHardware(activity: Activity) {
            try {
                // output information to screen
                var msg = "targetSDKVersion=${activity.applicationInfo.targetSdkVersion}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    msg = "${msg}\n    minSDKVersion=${activity.applicationInfo.minSdkVersion}"
                }
                msg = "${msg}\n    SDK_INT=${Build.VERSION.SDK_INT}\n" +
                        "    BOARD=${Build.BOARD}\n" +
                        "    BOOTLOADER=${Build.BOOTLOADER}\n" +
                        "    BRAND=${Build.BRAND}\n" +
                        "    DEVICE=${Build.DEVICE}\n" +
                        "    DISPLAY=${Build.DISPLAY}\n" +
                        "    HARDWARE=${Build.HARDWARE}\n" +
                        "    ID=${Build.ID}\n" +
                        "    MANUFACTURER=${Build.MANUFACTURER}\n" +
                        "    PRODUCT=${Build.PRODUCT}\n" +
                        "    TAGS=${Build.TAGS}\n" +
                        "    VERSION.MODEL=${Build.MODEL}\n"
                Log.v("Info", msg)
            } catch (e: Exception) {
                Log.e("ERROR", e.message ?: "Erro")
            }
        }
    }
}