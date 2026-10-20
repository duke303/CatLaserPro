package com.catlaser.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends pan/tilt/laser commands over UDP, in the same format the
 * M5Stick remote uses: "P<3 digits>T<3 digits>L<0|1>"
 * e.g. "P095T082L1". This is much faster than HTTP (no TCP handshake,
 * no DNS lookup per packet) and makes the ESP32 switch to manual
 * mode automatically.
 *
 * IMPORTANT: for UDP use the IP address if possible, not
 * "catlaser.local" -- hostname resolution can be unreliable or slow
 * depending on the network/router.
 */
class UdpController(private val portProvider: () -> Int = { 4210 }) {

    private var socket: DatagramSocket? = null

    private fun getSocket(): DatagramSocket {
        var s = socket
        if (s == null || s.isClosed) {
            s = DatagramSocket()
            socket = s
        }
        return s
    }

    suspend fun send(host: String, pan: Int, tilt: Int, laserOn: Boolean) = withContext(Dispatchers.IO) {
        try {
            val p = pan.coerceIn(0, 180)
            val t = tilt.coerceIn(0, 180)
            val msg = "P%03dT%03dL%d".format(p, t, if (laserOn) 1 else 0)
            val bytes = msg.toByteArray(Charsets.US_ASCII)
            val address = InetAddress.getByName(host.trim())
            val packet = DatagramPacket(bytes, bytes.size, address, portProvider())
            getSocket().send(packet)
        } catch (_: Exception) {
            // Occasional lost packets do not matter for a joystick --
            // the next touch-move event sends the next position anyway.
        }
    }

    fun close() {
        socket?.close()
        socket = null
    }
}
