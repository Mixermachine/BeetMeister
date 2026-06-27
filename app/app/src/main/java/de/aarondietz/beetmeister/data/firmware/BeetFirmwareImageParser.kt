package de.aarondietz.beetmeister.data.firmware

import de.aarondietz.beetmeister.model.update.BeetFirmwareMetadata
import java.security.MessageDigest
import java.util.zip.CRC32

internal data class BeetFirmwareImagePackage(
    val metadata: BeetFirmwareMetadata,
    val imageBytes: ByteArray,
    val imageSize: Int,
    val sha256Hex: String,
)

internal object BeetFirmwareImageParser {
    private const val MAGIC = 0x544D5442
    private const val FORMAT_VERSION = 1
    private const val HEADER_SIZE = 12

    private const val TLV_PRODUCT_ID = 0x0001
    private const val TLV_HARDWARE_REV = 0x0002
    private const val TLV_FIRMWARE_VERSION = 0x0003
    private const val TLV_BUILD_LABEL = 0x0004
    private const val TLV_MAINTENANCE_PROTOCOL_VERSION = 0x0005
    private const val TLV_RUNTIME_PROTOCOL_VERSION = 0x0006
    private const val TLV_IMAGE_KIND = 0x0007
    private const val TLV_COMPATIBLE_HARDWARE_REV = 0x0008

    fun parseImage(imageBytes: ByteArray): BeetFirmwareImagePackage {
        val metadata = parseMetadata(imageBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(imageBytes)
        return BeetFirmwareImagePackage(
            metadata = metadata,
            imageBytes = imageBytes,
            imageSize = imageBytes.size,
            sha256Hex = digest.joinToString(separator = "") { "%02x".format(it) },
        )
    }

    fun parseMetadata(imageBytes: ByteArray): BeetFirmwareMetadata {
        var index = 0
        while (index + HEADER_SIZE <= imageBytes.size) {
            if (readU32Le(imageBytes, index) != MAGIC) {
                index += 1
                continue
            }
            val formatVersion = readU16Le(imageBytes, index + 4)
            val totalLength = readU16Le(imageBytes, index + 6)
            val crcExpected = readU32Le(imageBytes, index + 8)
            if (formatVersion != FORMAT_VERSION || totalLength < HEADER_SIZE || index + totalLength > imageBytes.size) {
                index += 1
                continue
            }
            val crc = CRC32().apply { update(imageBytes, index, 8) }.value.toInt()
            if (crc != crcExpected) {
                index += 1
                continue
            }
            return parseMetadataBlock(imageBytes, index, totalLength)
        }
        error("Missing BeetMeister maintenance metadata block.")
    }

    private fun parseMetadataBlock(imageBytes: ByteArray, start: Int, totalLength: Int): BeetFirmwareMetadata {
        var offset = start + HEADER_SIZE
        var productId: String? = null
        var hardwareRev: String? = null
        var firmwareVersion: String? = null
        var buildLabel: String? = null
        var maintenanceProtocolVersion: Int? = null
        var runtimeProtocolVersion: Int? = null
        var imageKind: String? = null
        val compatibleHardwareRevs = mutableListOf<String>()

        while (offset < start + totalLength) {
            check(offset + 4 <= start + totalLength) { "Malformed BeetMeister metadata entry." }
            val type = readU16Le(imageBytes, offset)
            val length = readU16Le(imageBytes, offset + 2)
            offset += 4
            check(offset + length <= start + totalLength) { "Malformed BeetMeister metadata value." }
            when (type) {
                TLV_PRODUCT_ID -> productId = readUtf8(imageBytes, offset, length)
                TLV_HARDWARE_REV -> hardwareRev = readUtf8(imageBytes, offset, length)
                TLV_FIRMWARE_VERSION -> firmwareVersion = readUtf8(imageBytes, offset, length)
                TLV_BUILD_LABEL -> buildLabel = readUtf8(imageBytes, offset, length)
                TLV_MAINTENANCE_PROTOCOL_VERSION -> maintenanceProtocolVersion = readU32Le(imageBytes, offset)
                TLV_RUNTIME_PROTOCOL_VERSION -> runtimeProtocolVersion = readU32Le(imageBytes, offset)
                TLV_IMAGE_KIND -> imageKind = readUtf8(imageBytes, offset, length)
                TLV_COMPATIBLE_HARDWARE_REV -> compatibleHardwareRevs += readUtf8(imageBytes, offset, length)
            }
            offset += length
        }

        return BeetFirmwareMetadata(
            productId = requireNotNull(productId) { "Missing firmware product ID." },
            hardwareRev = requireNotNull(hardwareRev) { "Missing firmware hardware revision." },
            firmwareVersion = requireNotNull(firmwareVersion) { "Missing firmware version." },
            buildLabel = requireNotNull(buildLabel) { "Missing firmware build label." },
            maintenanceProtocolVersion = requireNotNull(maintenanceProtocolVersion) { "Missing maintenance protocol version." },
            runtimeProtocolVersion = requireNotNull(runtimeProtocolVersion) { "Missing runtime protocol version." },
            imageKind = requireNotNull(imageKind) { "Missing firmware image kind." },
            compatibleHardwareRevs = compatibleHardwareRevs.toList(),
        )
    }

    private fun readUtf8(imageBytes: ByteArray, offset: Int, length: Int): String =
        imageBytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)

    private fun readU16Le(imageBytes: ByteArray, offset: Int): Int =
        (imageBytes[offset].toInt() and 0xFF) or
            ((imageBytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32Le(imageBytes: ByteArray, offset: Int): Int =
        (imageBytes[offset].toInt() and 0xFF) or
            ((imageBytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((imageBytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((imageBytes[offset + 3].toInt() and 0xFF) shl 24)
}
