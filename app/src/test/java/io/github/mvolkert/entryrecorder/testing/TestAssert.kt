package io.github.mvolkert.entryrecorder.testing

import org.junit.Assert

/**
 * The handful of assertions the ViewModel tests use, in the argument order the kotlin.test flavour has
 * (condition first, message last). JUnit 4 is the only assertion library on the classpath and the plan
 * adds no dependency, so these three thin wrappers keep the call sites readable instead of inverting
 * every message argument to fit `Assert`'s (message, condition) order.
 */
fun assertTrue(condition: Boolean, message: String = "expected true") =
    Assert.assertTrue(message, condition)

fun assertFalse(condition: Boolean, message: String = "expected false") =
    Assert.assertFalse(message, condition)

/** Message-last flavour of [Assert.assertEquals]; boxed numbers compare through `equals`, so `77L == 77` holds. */
fun assertEquals(expected: Any?, actual: Any?, message: String = "") =
    Assert.assertEquals(message, expected, actual)

/** [assertNull] under a name that fits the message-last call style of the rest of this file. */
fun assertNone(value: Any?, message: String = "expected null") = Assert.assertNull(message, value)

/** Asserts the value is a [T] and returns it, so the test can keep reading fields off the typed result. */
inline fun <reified T : Any> expectIs(value: Any?, message: String = "expected ${T::class.simpleName}"): T {
    assertTrue(value is T, "$message, was: $value")
    return value as T
}
