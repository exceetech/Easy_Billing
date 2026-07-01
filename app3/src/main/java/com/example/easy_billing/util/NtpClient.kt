package com.example.easy_billing.util

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal SNTP (Simple Network Time Protocol, RFC 4330) client.
 *
 * Fetches the true current instant from an internet time server so the app no
 * longer trusts the (user-settable) device clock for stamping bills. Returns the
 * corrected epoch-millis, or null if no server could be reached.
 *
 * This is intentionally dependency-free (raw UDP) so we don't add a library.
 * It computes the clock offset the proper NTP way (using originate/receive/
 * transmit/destination timestamps) rather than naively trusting the transmit
 * time, so network latency doesn't skew the result.
 */
object NtpClient {

    private val HOSTS = listOf(
        "time.google.com",
        "time.cloudflare.com",
        "pool.ntp.org",
    )

    private const val NTP_PORT = 123
    private const val NTP_PACKET_SIZE = 48
    private const val TIMEOUT_MS = 1500

    // Seconds between 1900-01-01 (NTP epoch) and 1970-01-01 (Unix epoch).
    private const val NTP_TO_UNIX_SECONDS = 2_208_988_800L

    /**
     * @return the true current time in epoch-millis, or null on total failure.
     * Safe to call from a background/IO coroutine; performs blocking network IO.
     */
    fun fetch(): Long? {
        for (host in HOSTS) {
            try {
                return query(host)
            } catch (_: Exception) {
                // try the next host
            }
        }
        return null
    }

    private fun query(host: String): Long {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val address = InetAddress.getByName(host)
            val buf = ByteArray(NTP_PACKET_SIZE)

            // LI = 0, VN = 3, Mode = 3 (client)  →  0b00011011 = 0x1B
            buf[0] = 0x1B

            val request = DatagramPacket(buf, buf.size, address, NTP_PORT)

            val originateMs = System.currentTimeMillis()  // T1 (device send)
            socket.send(request)

            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            val destinationMs = System.currentTimeMillis() // T4 (device recv)

            // T2 = server receive timestamp (offset 32), T3 = server transmit (offset 40)
            val receiveMs = readTimestamp(buf, 32)
            val transmitMs = readTimestamp(buf, 40)

            // NTP clock offset = ((T2 - T1) + (T3 - T4)) / 2
            val offset = ((receiveMs - originateMs) + (transmitMs - destinationMs)) / 2

            // The corrected "now" = the device's destination time + clock offset.
            return destinationMs + offset
        }
    }

    /** Read a 64-bit NTP timestamp (32-bit seconds + 32-bit fraction) as epoch-millis. */
    private fun readTimestamp(buf: ByteArray, offset: Int): Long {
        val seconds = readUInt32(buf, offset)
        val fraction = readUInt32(buf, offset + 4)
        val unixSeconds = seconds - NTP_TO_UNIX_SECONDS
        val millisFraction = (fraction * 1000L) / 0x1_0000_0000L
        return unixSeconds * 1000L + millisFraction
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
                ((buf[offset + 1].toLong() and 0xFF) shl 16) or
                ((buf[offset + 2].toLong() and 0xFF) shl 8) or
                (buf[offset + 3].toLong() and 0xFF)
    }
}
