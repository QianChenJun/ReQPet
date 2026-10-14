package io.github.reqpet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskLedgerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a sibling base name writes its own file next to the runtime log`() {
        val directory = temporary.newFolder()
        RuntimeDiagnosticFileWriter(directory).use { writer ->
            writer.append("{\"event\":\"run_start\"}\n")
        }
        RuntimeDiagnosticFileWriter(directory, maxFileBytes = 1L * 1024 * 1024, rotatedFiles = 1, baseName = "ledger")
            .use { writer ->
                writer.append("10-14 10:32:17 task_settled kind=study story=6100_x\n")
            }
        assertEquals(1, File(directory, "runtime.log").readLines().size)
        assertEquals(
            "10-14 10:32:17 task_settled kind=study story=6100_x",
            File(directory, "ledger.log").readText().trim()
        )
        assertTrue(File(directory, ".ledger.lock").exists())
    }

    @Test
    fun `the ledger lock is per base name so the runtime writer can stay open`() {
        val directory = temporary.newFolder()
        RuntimeDiagnosticFileWriter(directory).use { runtime ->
            RuntimeDiagnosticFileWriter(directory, rotatedFiles = 1, baseName = "ledger").use { ledger ->
                ledger.append("10-14 10:32:17 task_started kind=study\n")
                assertThrows(RuntimeDiagnosticWriterBusy::class.java) {
                    RuntimeDiagnosticFileWriter(directory, rotatedFiles = 1, baseName = "ledger")
                }
                runtime.append("{\"event\":\"cycle_start\"}\n")
            }
        }
        assertEquals(1, File(directory, "runtime.log").readLines().size)
        assertEquals(1, File(directory, "ledger.log").readLines().size)
    }

    @Test
    fun `records must be newline terminated or the writer refuses them`() {
        val directory = temporary.newFolder()
        RuntimeDiagnosticFileWriter(directory, rotatedFiles = 1, baseName = "ledger").use { ledger ->
            assertThrows(IllegalArgumentException::class.java) { ledger.append("no trailing newline") }
            assertThrows(IllegalArgumentException::class.java) { ledger.append("two\nlines\n") }
        }
    }
}
