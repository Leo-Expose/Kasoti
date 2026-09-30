package dev.kasoti.desktop.screen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sidecar JSON that accompanies a captured document image.
 *
 * The console reads a *file*, not a live camera, and it reads fields rather than guessing
 * them. Both are deliberate. The image on disk is reproducible — the same pixels can be
 * re-screened after a threshold change, which is what EVAL.md needs. And the fields come
 * from OCR, a card reader or a previous run rather than from this module: a console that
 * "helpfully" guesses a date of birth is a console that eventually asserts a document says
 * something it does not.
 *
 * Every field is optional. A screening with nothing but an image is legal and yields
 * GREY-shaped output — "we could not look properly" — which is the honest answer.
 */
class Sidecar(
    val mrzLines: List<String> = emptyList(),
    val printName: String = "",
    val printDob: String = "",
    val issueDate: String = "",
    val expiryDate: String = "",
    val qrRawBase64: String = "",
    val qrSignatureBase64: String = "",
    val qrFields: Map<String, String> = emptyMap(),
    val qrKeyId: String = "",
    val qrX509KeyBase64: String = "",
    val ocrLines: List<String> = emptyList(),
    val photoZone: RectSpec? = null,
    val textZone: RectSpec? = null,
    val presentation: String = "PHYSICAL",
    val trust: String = "VERIFY",
    val documentNumber: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
) {

    val hasQr: Boolean get() = qrRawBase64.isNotBlank() || qrFields.isNotEmpty()
    val hasSignedQr: Boolean get() = qrRawBase64.isNotBlank() && qrSignatureBase64.isNotBlank()

    /** A normalised rectangle in the sidecar, in frame fractions. */
    data class RectSpec(val x: Float, val y: Float, val width: Float, val height: Float)

    companion object {
        val EMPTY = Sidecar()

        fun load(path: Path): Sidecar = parse(Files.readString(path))

        fun parse(text: String): Sidecar {
            val root = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("sidecar is not a JSON object")
            return Sidecar(
                mrzLines = root.stringList("mrz"),
                printName = root.string("printName"),
                printDob = root.string("printDob"),
                issueDate = root.string("issueDate"),
                expiryDate = root.string("expiry"),
                qrRawBase64 = root.string("qrRawBase64"),
                qrSignatureBase64 = root.string("qrSignatureBase64"),
                qrFields = root.stringMap("qrFields"),
                qrKeyId = root.string("qrKeyId"),
                qrX509KeyBase64 = root.string("qrX509KeyBase64"),
                ocrLines = root.stringList("ocrLines"),
                photoZone = root.rect("photoZone"),
                textZone = root.rect("textZone"),
                presentation = root.string("presentation") ?: "PHYSICAL",
                trust = root.string("trust") ?: "VERIFY",
                documentNumber = root.string("documentNumber"),
                raw = root,
            )
        }

        private fun JsonObject.primitive(key: String): JsonPrimitive? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }

        private fun JsonObject.string(key: String): String =
            primitive(key)?.takeIf { it.isString }?.content ?: ""

        private fun JsonObject.stringList(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

        private fun JsonObject.stringMap(key: String): Map<String, String> =
            (this[key] as? JsonObject)
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
                ?.toMap()
                ?: emptyMap()

        private fun JsonObject.rect(key: String): RectSpec? {
            val obj = this[key] as? JsonObject ?: return null
            val x = obj.number("x") ?: return null
            val y = obj.number("y") ?: return null
            val w = obj.number("w") ?: return null
            val h = obj.number("h") ?: return null
            if (w <= 0f || h <= 0f) return null
            return RectSpec(x, y, w, h)
        }

        private fun JsonObject.number(key: String): Float? {
            val value = primitive(key) ?: return null
            return value.floatOrNull ?: value.content.toFloatOrNull()
        }
    }
}
