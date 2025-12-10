package com.terminox.agent.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import javax.imageio.ImageIO

/**
 * Generates QR codes for device pairing.
 *
 * Creates QR codes containing pairing session data that can be
 * scanned by the mobile app to initiate secure pairing.
 *
 * ## Features
 * - ASCII art QR codes for terminal display
 * - PNG image generation for GUI/file export
 * - Configurable QR code size
 * - Embedded JSON payload with session info
 */
class QrPairingGenerator(
    private val agentName: String,
    private val agentUrl: String
) {
    private val logger = LoggerFactory.getLogger(QrPairingGenerator::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val qrCodeWriter = QRCodeWriter()

    /**
     * Generates a QR code for a pairing session.
     *
     * @param session The pairing session to encode
     * @return QrCodeResult with ASCII and optional PNG data
     */
    fun generateQrCode(session: PairingSession): QrCodeResult {
        val qrData = QrPairingData(
            version = 1,
            agentPublicKey = session.agentPublicKeyBase64,
            agentFingerprint = session.agentFingerprint,
            agentUrl = agentUrl,
            agentName = agentName,
            sessionId = session.sessionId,
            expiresAt = session.expiresAt
        )

        val jsonPayload = json.encodeToString(qrData)
        logger.debug("QR payload size: ${jsonPayload.length} bytes")

        val asciiQrCode = generateAsciiQrCode(jsonPayload)
        val pngData = generatePngQrCode(jsonPayload)

        return QrCodeResult(
            sessionId = session.sessionId,
            asciiQrCode = asciiQrCode,
            pngData = pngData,
            payload = jsonPayload
        )
    }

    /**
     * Generates an ASCII art QR code for terminal display.
     *
     * Uses Unicode block characters for better resolution:
     * - █ (full block) for both top and bottom black
     * - ▀ (upper half) for top black, bottom white
     * - ▄ (lower half) for top white, bottom black
     * - (space) for both white
     */
    fun generateAsciiQrCode(data: String, margin: Int = 1): String {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to margin
        )

        val bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 1, 1, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height

        val builder = StringBuilder()

        // Add top border
        builder.appendLine()

        // Process two rows at a time for better aspect ratio
        for (y in 0 until height step 2) {
            builder.append("  ") // Left margin for terminal
            for (x in 0 until width) {
                val top = bitMatrix.get(x, y)
                val bottom = if (y + 1 < height) bitMatrix.get(x, y + 1) else false

                builder.append(
                    when {
                        top && bottom -> "█"
                        top && !bottom -> "▀"
                        !top && bottom -> "▄"
                        else -> " "
                    }
                )
            }
            builder.appendLine()
        }

        builder.appendLine()

        return builder.toString()
    }

    /**
     * Generates a PNG image of the QR code.
     *
     * @param data The data to encode
     * @param size The size in pixels (width and height)
     * @return PNG image data as ByteArray
     */
    fun generatePngQrCode(data: String, size: Int = DEFAULT_QR_SIZE): ByteArray {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2
        )

        val bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, size, size, hints)

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (bitMatrix.get(x, y)) BLACK else WHITE)
            }
        }

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Saves a QR code PNG to a file.
     */
    fun saveQrCodeToFile(data: String, file: File, size: Int = DEFAULT_QR_SIZE) {
        val pngData = generatePngQrCode(data, size)
        file.writeBytes(pngData)
        logger.info("Saved QR code to ${file.absolutePath}")
    }

    /**
     * Generates a pairing display for terminal output.
     */
    fun generatePairingDisplay(
        session: PairingSession,
        qrCode: QrCodeResult,
        verificationCode: String? = null
    ): String {
        return buildString {
            appendLine()
            appendLine("╔════════════════════════════════════════════════════════════════╗")
            appendLine("║                    DEVICE PAIRING                              ║")
            appendLine("╠════════════════════════════════════════════════════════════════╣")
            appendLine("║                                                                ║")
            appendLine("║  Scan this QR code with the Terminox mobile app:               ║")
            appendLine("║                                                                ║")

            // Add QR code with proper indentation
            qrCode.asciiQrCode.lines().forEach { line ->
                appendLine("║${line.padEnd(64)}║")
            }

            appendLine("║                                                                ║")
            appendLine("╠════════════════════════════════════════════════════════════════╣")
            appendLine("║  Session ID: ${session.sessionId.take(8)}...".padEnd(65) + "║")
            appendLine("║  Device: ${session.deviceName}".padEnd(65) + "║")

            if (verificationCode != null) {
                appendLine("╠════════════════════════════════════════════════════════════════╣")
                appendLine("║                                                                ║")
                appendLine("║  VERIFICATION CODE:                                            ║")
                appendLine("║                                                                ║")
                appendLine("║           ╔══════════════════════════════╗                     ║")
                appendLine("║           ║        $verificationCode        ║                     ║")
                appendLine("║           ╚══════════════════════════════╝                     ║")
                appendLine("║                                                                ║")
                appendLine("║  Verify this code matches the code shown on your mobile       ║")
                appendLine("║  device, then confirm on both devices.                        ║")
            }

            appendLine("║                                                                ║")
            appendLine("╠════════════════════════════════════════════════════════════════╣")

            val expiresIn = (session.expiresAt - System.currentTimeMillis()) / 1000
            val minutes = expiresIn / 60
            val seconds = expiresIn % 60
            appendLine("║  Expires in: ${minutes}m ${seconds}s".padEnd(65) + "║")

            appendLine("╚════════════════════════════════════════════════════════════════╝")
            appendLine()
        }
    }

    companion object {
        private const val DEFAULT_QR_SIZE = 256
        private const val BLACK = 0x000000
        private const val WHITE = 0xFFFFFF
    }
}

/**
 * Result of QR code generation.
 */
data class QrCodeResult(
    /** Pairing session ID */
    val sessionId: String,

    /** ASCII art representation for terminal */
    val asciiQrCode: String,

    /** PNG image data */
    val pngData: ByteArray,

    /** Raw JSON payload */
    val payload: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QrCodeResult
        return sessionId == other.sessionId &&
                asciiQrCode == other.asciiQrCode &&
                pngData.contentEquals(other.pngData) &&
                payload == other.payload
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + asciiQrCode.hashCode()
        result = 31 * result + pngData.contentHashCode()
        result = 31 * result + payload.hashCode()
        return result
    }

    /**
     * Gets the PNG data as Base64 string.
     */
    fun pngAsBase64(): String = Base64.getEncoder().encodeToString(pngData)
}
