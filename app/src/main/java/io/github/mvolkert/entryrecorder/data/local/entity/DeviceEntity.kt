package io.github.mvolkert.entryrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.data.model.SipMode

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val deviceType: DeviceType = DeviceType.TWO_N_VERSO,
    val ipAddress: String,
    val httpPort: Int = 80,
    val httpsPort: Int = 443,
    val useHttps: Boolean = false,
    val rtspPort: Int = 554,
    val rtspPath: String = "/live.sdp",
    val username: String = "admin",
    val password: String = "2n",
    
    // SIP Configuration
    val sipMode: SipMode = SipMode.PEER_TO_PEER,
    val sipLocalPort: Int = 5060,
    val sipServerHost: String? = null,
    val sipServerPort: Int? = 5060,
    val sipUser: String? = null,
    val sipPassword: String? = null,
    
    // Recording & Trigger Configuration
    val recordOnMotion: Boolean = true,
    val recordOnRing: Boolean = true,
    val recordOnNoise: Boolean = true,
    // Motion is analyzed locally in-app from the video stream instead of relying on the device's own detection
    val recordOnMotionOnDevice: Boolean = false,
    val motionPostRecordSeconds: Int = 20,
    val noisePostRecordSeconds: Int = 20,
    val ringRecordSeconds: Int = 60,
    val isEnabled: Boolean = true
) {
    val httpBaseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            val port = if (useHttps) httpsPort else httpPort
            return "$scheme://$ipAddress:$port"
        }

    val rtspStreamUrl: String
        get() {
            val authPart = if (username.isNotBlank() && password.isNotBlank()) {
                "$username:$password@"
            } else ""
            val cleanPath = if (rtspPath.startsWith("/")) rtspPath else "/$rtspPath"
            return "rtsp://$authPart$ipAddress:$rtspPort$cleanPath"
        }

    val snapshotUrl: String
        get() = "$httpBaseUrl/api/camera/snapshot?width=1280&height=720"
}
