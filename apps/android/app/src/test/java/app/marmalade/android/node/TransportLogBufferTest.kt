package app.marmalade.android.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [TransportLogBuffer]. The class wraps an
 * [ArrayDeque] + StateFlow + logcat mirror; only the ring-buffer behavior
 * and the StateFlow emission are testable in pure-JVM land (logcat is a
 * no-op on the test JVM, which is fine — we're not testing
 * `android.util.Log`).
 */
class TransportLogBufferTest {

  @Test
  fun `add appends and emits via entries flow`() {
    val buffer = TransportLogBuffer(capacity = 10)

    buffer.add(TransportLogLevel.INFO, "first message")
    buffer.add(TransportLogLevel.WARN, "second message")

    val entries = buffer.entries.value
    assertEquals(2, entries.size)
    assertEquals("first message", entries[0].message)
    assertEquals(TransportLogLevel.INFO, entries[0].level)
    assertEquals("second message", entries[1].message)
    assertEquals(TransportLogLevel.WARN, entries[1].level)
  }

  @Test
  fun `ring buffer drops oldest at capacity`() {
    val buffer = TransportLogBuffer(capacity = 3)

    buffer.add(TransportLogLevel.INFO, "one")
    buffer.add(TransportLogLevel.INFO, "two")
    buffer.add(TransportLogLevel.INFO, "three")
    buffer.add(TransportLogLevel.INFO, "four")

    val entries = buffer.entries.value
    assertEquals(3, entries.size)
    assertEquals("two", entries[0].message)
    assertEquals("three", entries[1].message)
    assertEquals("four", entries[2].message)
  }

  @Test
  fun `clear empties the buffer and emits empty list`() {
    val buffer = TransportLogBuffer(capacity = 10)
    buffer.add(TransportLogLevel.INFO, "one")
    buffer.add(TransportLogLevel.INFO, "two")
    assertEquals(2, buffer.entries.value.size)

    buffer.clear()

    assertTrue("entries should be empty after clear", buffer.entries.value.isEmpty())
  }

  @Test
  fun `optional fields default to null and verbose to false`() {
    val buffer = TransportLogBuffer()
    buffer.add(TransportLogLevel.DEBUG, "simple entry")

    val entry = buffer.entries.value.single()
    assertEquals("simple entry", entry.message)
    assertEquals(false, entry.isVerbose)
    assertNull(entry.runId)
    assertNull(entry.source)
  }

  @Test
  fun `verbose runId and source are preserved`() {
    val buffer = TransportLogBuffer()
    buffer.add(
      level = TransportLogLevel.DEBUG,
      message = "event tool.complete",
      verbose = true,
      runId = "run-abc",
      source = "event",
    )

    val entry = buffer.entries.value.single()
    assertEquals(true, entry.isVerbose)
    assertEquals("run-abc", entry.runId)
    assertEquals("event", entry.source)
  }

  @Test
  fun `concurrent adds preserve count without crashing`() {
    val buffer = TransportLogBuffer(capacity = 1000)
    val threadCount = 8
    val perThread = 100
    val threads = (0 until threadCount).map { t ->
      Thread {
        repeat(perThread) { i -> buffer.add(TransportLogLevel.INFO, "t$t:$i") }
      }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    assertEquals(threadCount * perThread, buffer.entries.value.size)
  }
}
