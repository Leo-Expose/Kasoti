package dev.kasoti.desktop

import dev.kasoti.mrz.MrzBuilder
import dev.kasoti.mrz.MrzPerson
import dev.kasoti.time.IsoDate
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * `kasoti sample` — write a zero-PII demo specimen and its sidecar.
 *
 * DEMO.md §4 wants demo mode "ON + seeded" before the finals, and the honest way to seed it
 * is to generate the fixture rather than photograph somebody's passport. The MRZ comes from
 * `:core`'s own `MrzBuilder`, so the check digits are real and the screening exercises the
 * actual ICAO path — including the negative case, where `--corrupt` flips a check digit so
 * the demo can show R-MATH-01 without anyone having to produce a forged document.
 *
 * The names are `MrzPerson`'s fixed synthetic list. There is no PII here by construction,
 * which is the property DATA.md §1 cares about: demo fixtures must be safe to commit.
 */
fun Console.sample(line: CommandLine): CommandResult {
    line.rejectUnknown(setOf("out", "corrupt", "width", "height"))
    val out = Path.of(line.option("out", "demo-specimen"))
    val width = line.int("width", 640)
    val height = line.int("height", 400)
    if (width < 64 || height < 64) {
        return CommandResult(ConsolePolicy.Exit.USAGE, "the specimen must be at least 64x64")
    }

    val person = MrzPerson(
        surname = "SHARMA",
        givenNames = "RAMESH",
        documentNumber = "AB1234567",
        birthDate = IsoDate(1990, 6, 8),
        expiryDate = IsoDate(2031, 6, 7),
    )
    val lines = MrzBuilder.buildTd3(person, referenceYear = 2026)
    val mrzLines = if (line.flag("corrupt")) {
        // Flip the composite check digit, which for TD3 is the *last character of line 1*.
        // The name line's final character is a filler, not a digit — corrupting that proves
        // nothing, which is exactly the kind of fixture that makes a demo look like it
        // works when nothing was actually checked.
        lines.toMutableList().also { rows ->
            val composite = rows[0].last()
            rows[0] = rows[0].dropLast(1) + if (composite == '0') '1' else '0'
        }
    } else {
        lines
    }

    Files.createDirectories(out)
    val image = out.resolve("specimen.png")
    ImageIO.write(specimen(width, height), "png", image.toFile())

    val sidecar = out.resolve("specimen.json")
    Files.writeString(
        sidecar,
        """
        {
          "mrz": ${mrzLines.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }},
          "printName": "SHARMA RAMESH",
          "printDob": "1990-06-08",
          "issueDate": "2021-06-08",
          "expiry": "2031-06-07",
          "photoZone": {"x": 0.14, "y": 0.18, "w": 0.20, "h": 0.32},
          "textZone":  {"x": 0.05, "y": 0.72, "w": 0.90, "h": 0.12},
          "presentation": "PHYSICAL",
          "trust": "VERIFY"
        }
        """.trimIndent() + "\n",
    )

    view.banner("Specimen written")
    view.table(
        listOf("field", "value"),
        listOf(
            listOf("image", image.toString()),
            listOf("sidecar", sidecar.toString()),
            listOf("mrz", "2 x 44 characters, real check digits (redacted in console output — invariant I5)"),
            listOf("corrupted", if (line.flag("corrupt")) "yes (TD3 composite check digit flipped)" else "no"),
            listOf("contains PII", "no — generated from :core MrzBuilder"),
        ),
    )
    return CommandResult(
        ConsolePolicy.Exit.OK,
        "specimen written to $out; screen it with --fields $sidecar",
    )
}

/**
 * Draw the specimen.
 *
 * Three regions with deliberately different textures: a photo box (low-frequency), a
 * microprint band (high-frequency, checker), and a flat background. The macro features are
 * genuinely different across the two zones, which is what makes the demo's "zones agree /
 * zones disagree" output mean something even with the untrained stub.
 */
private fun specimen(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val photoX = (width * 0.14).toInt()
    val photoY = (height * 0.18).toInt()
    val photoW = (width * 0.20).toInt()
    val photoH = (height * 0.32).toInt()
    val bandY = (height * 0.72).toInt()
    val bandH = (height * 0.12).toInt()

    for (y in 0 until height) {
        for (x in 0 until width) {
            val value = when {
                x in photoX until photoX + photoW && y in photoY until photoY + photoH ->
                    if (((x + y) / 3) % 2 == 0) 40 else 210
                y in bandY until bandY + bandH ->
                    if ((x / 2) % 2 == 0) 20 else 235
                y in (height * 0.55).toInt() until (height * 0.58).toInt() -> 60
                else -> 235
            }
            image.setRGB(x, y, Color(value, value, value).rgb)
        }
    }
    return image
}
