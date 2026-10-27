import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// KloudStream for Windows and Linux: Compose Desktop UI over the shared aggregation engine,
// CloudStream extensions (.cs3, Android DEX translated to JVM classes) and a bundled mpv.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// `-Pkloudstream.os=windows` resolves Windows natives (Skia) instead of the build machine's,
// so WSL can stage a Windows distribution (tools/desktop/package-windows.sh).
val targetOs = providers.gradleProperty("kloudstream.os").orNull

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }

        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":library"))
            implementation(libs.bundles.compose)
            implementation(
                when (targetOs) {
                    "windows" -> compose.desktop.windows_x64
                    "linux" -> compose.desktop.linux_x64
                    else -> compose.desktop.currentOs
                }
            ) {
                // compose.desktop.* brings material 2; the app uses material 3 only.
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.jackson.module.kotlin)
            implementation(libs.nicehttp)
            implementation(libs.jsoup)
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.java)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            // Extensions ship Android DEX bytecode; it is translated to JVM classes when installed.
            implementation("de.femtopedia.dex2jar:dex-translator:2.4.38")
        }

        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    // Skia loads its native library through System.load.
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcom.sun.security.enableAIAcaIssuers=true")
}

tasks.withType<Test>().configureEach {
    // Live extension tests download real extensions and query their sites: opt in with -Pkloudstream.live.
    systemProperty("kloudstream.live", providers.gradleProperty("kloudstream.live").orNull ?: "false")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}

compose.desktop {
    application {
        mainClass = "com.kloudstream.desktop.MainKt"
    }
}

// Runtime classpath copied next to the app jar, for jpackage on Windows (see tools/desktop).
val stageLibs by tasks.registering(Sync::class) {
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath")) {
        // Jars of different groups can share a file name (androidx and JetBrains Compose runtime).
        eachFile {
            val parts = file.path.replace('\\', '/').split('/')
            val cache = parts.indexOf("files-2.1")
            if (cache >= 0 && parts.size > cache + 1) name = parts[cache + 1] + "-" + name
        }
    }
    into(layout.buildDirectory.dir("stage/lib"))
}
