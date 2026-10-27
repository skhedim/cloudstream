package com.kloudstream.desktop

import com.kloudstream.desktop.extensions.DexTranslator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DexTranslatorTest {
    @Test fun aNewTranslationOnlyReplacesThatArchivesOlderOnes() {
        val root = Files.createTempDirectory("translations").toFile()
        val current = File(root, "frembed-fedcba9876543210").apply { mkdirs() }
        val older = File(root, "frembed-0123456789abcdef").apply { mkdirs() }
        // Another extension whose key starts the same way, still loaded.
        val neighbour = File(root, "frembed-fr-0123456789abcdef").apply { mkdirs() }
        DexTranslator.removeOlder(File(root, "frembed.cs3"), root, current)
        assertTrue(current.exists())
        assertFalse(older.exists())
        assertTrue(neighbour.exists())
    }
}
