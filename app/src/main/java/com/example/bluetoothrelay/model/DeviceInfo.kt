import android.net.wifi.p2p.WifiP2pDevice

data class DeviceInfo(
    val address: String,
    val name: String?,
    val isConnected: Boolean = false
) {
    companion object {
        fun fromWifiP2pDevice(device: WifiP2pDevice): DeviceInfo {
            return DeviceInfo(
                address = device.deviceAddress,
                name = device.deviceName,
                isConnected = device.status == WifiP2pDevice.CONNECTED
            )
        }
    }
}