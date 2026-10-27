package com.kloudstream.desktop

import java.nio.file.Files

/** Every test of the JVM shares one temporary data folder (AppDirs is resolved once). */
object TestHome {
    init {
        if (System.getProperty("kloudstream.home").isNullOrBlank())
            System.setProperty("kloudstream.home", Files.createTempDirectory("kloudstream-test").toString())
    }
    fun ensure() = Unit
}
