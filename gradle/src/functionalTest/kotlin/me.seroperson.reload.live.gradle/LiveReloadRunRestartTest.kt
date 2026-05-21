package me.seroperson.reload.live.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class LiveReloadRunRestartTest : LiveReloadTestBase() {
    @field:TempDir lateinit var projectDir: File

    private val appCode by lazy {
        val kotlinSources = projectDir.resolve("src/main/kotlin")
        kotlinSources.mkdirs()
        kotlinSources.resolve("App.kt")
    }
    private val buildFile by lazy { projectDir.resolve("build.gradle.kts") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }

    @Test
    fun `liveReloadRun restarts proxy after previous run stopped`() {
        settingsFile.writeText(SETTINGS_CONTENT)
        buildFile.writeText(BUILD_CONTENT)
        appCode.writeText(APP_CODE)

        val runner = initGradleRunner(":liveReloadRun", projectDir)

        val firstRunRunning = AtomicBoolean(true)
        val firstRun =
            Thread {
                try {
                    runner.build()
                } catch (_: InterruptedException) {
                    println("First liveReloadRun interrupted")
                } finally {
                    firstRunRunning.set(false)
                }
            }
        firstRun.start()

        assertTrue(
            runUntil(firstRunRunning, "http://localhost:9000/greet", 200, "Hello World"),
            "Proxy should respond during the first liveReloadRun",
        )

        firstRun.interrupt()
        firstRun.join(30_000)

        val secondRunRunning = AtomicBoolean(true)
        val secondRun =
            Thread {
                try {
                    val result = runner.build()
                    assertFalse(
                        result.task(":liveReloadRun")?.outcome == TaskOutcome.UP_TO_DATE,
                        "Second liveReloadRun should execute, not be skipped as UP-TO-DATE",
                    )
                } finally {
                    secondRunRunning.set(false)
                }
            }
        secondRun.start()

        assertTrue(
            runUntil(secondRunRunning, "http://localhost:9000/greet", 200, "Hello World"),
            "Proxy should respond again after restarting liveReloadRun in the same daemon",
        )

        secondRun.interrupt()
        secondRun.join(30_000)
    }

    companion object {
        const val SETTINGS_CONTENT = ""
        const val BUILD_CONTENT =
            """
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    application
    id("me.seroperson.reload.live.gradle")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.javalin:javalin:6.7.0")
}

liveReload { settings = mapOf("live.reload.http.port" to "8081") }

application { mainClass = "AppKt" }
"""
        const val APP_CODE =
            """
import io.javalin.Javalin

fun main() {
    val server =
        Javalin.create()
            .get("/greet") { it.result("Hello World") }
            .get("/health") { it.status(200) }
    try {
        server.start(8081)
        Thread.currentThread().join()
    } catch (_: InterruptedException) {
        server.stop()
    }
}
"""
    }
}
