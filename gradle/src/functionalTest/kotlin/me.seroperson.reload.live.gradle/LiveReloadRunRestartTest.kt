package me.seroperson.reload.live.gradle

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

    /**
     * Exercises the deployment-handle restart path within a single BuildSession: continuous build
     * stops the handle, recompiles, and re-runs [LiveReloadRun] against the same registry entry. Two
     * separate TestKit [GradleRunner.build] calls each get a fresh [DeploymentRegistry] and would not
     * cover this path.
     */
    @Test
    fun `liveReloadRun restarts proxy after continuous rebuild stops deployment`() {
        settingsFile.writeText(SETTINGS_CONTENT)
        buildFile.writeText(BUILD_CONTENT)
        appCode.writeText(APP_CODE_1)

        val runner = initGradleRunner(":liveReloadRun", projectDir)
        runner.withArguments(runner.arguments + "--continuous")

        val isBuildRunning = AtomicBoolean(true)
        val runThread =
            Thread {
                try {
                    runner.build()
                } catch (_: InterruptedException) {
                    println("liveReloadRun interrupted")
                } finally {
                    isBuildRunning.set(false)
                }
            }
        runThread.start()

        assertTrue(
            runUntil(isBuildRunning, "http://localhost:9000/greet", 200, "Hello World"),
            "Proxy should serve the initial greeting",
        )

        appCode.writeText(APP_CODE_2)

        assertTrue(
            runUntil(isBuildRunning, "http://localhost:9000/greet", 200, "World Hello"),
            "Proxy should serve the recompiled greeting after continuous rebuild restarts the handle",
        )

        runThread.interrupt()
        runThread.join(30_000)
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
        const val APP_CODE_1 =
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
        const val APP_CODE_2 =
            """
import io.javalin.Javalin

fun main() {
    val server =
        Javalin.create()
            .get("/greet") { it.result("World Hello") }
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
