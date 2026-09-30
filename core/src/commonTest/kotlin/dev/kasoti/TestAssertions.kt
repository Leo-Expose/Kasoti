package dev.kasoti

import kotlin.test.assertTrue

/**
 * Asserts two check digits differ.
 *
 * Written by hand rather than using `assertNotEquals` because null vs non-null is a real
 * distinction here: a `null` means "undecidable, therefore failed", which is a different
 * outcome from a wrong digit, and both must differ from the expected value.
 */
fun assertNotEqualsQuietly(actual: Char?, other: Char?) {
    assertTrue(actual != other, "expected different check digits, both were $actual")
}
