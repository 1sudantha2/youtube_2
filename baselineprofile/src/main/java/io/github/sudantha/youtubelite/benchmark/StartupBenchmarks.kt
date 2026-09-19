package io.github.sudantha.youtubelite.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val packageName = "io.github.sudantha.youtubelite.debug"

    @Test
    fun startupWithoutProfile() = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(StartupTimingMetric()),
        startupMode = StartupMode.COLD,
        iterations = 5,
        compilationMode = CompilationMode.Full(BaselineProfileMode.NONE)
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun startupWithProfile() = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(StartupTimingMetric()),
        startupMode = StartupMode.COLD,
        iterations = 5,
        compilationMode = CompilationMode.Full(BaselineProfileMode.Require)
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun scrollHome() = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(FrameTimingMetric()),
        startupMode = StartupMode.COLD,
        iterations = 5,
        compilationMode = CompilationMode.Full(BaselineProfileMode.Require)
    ) {
        startActivityAndWait()
        uiAutomator {
            val display = displaySize
            repeat(20) {
                swipe(display.x / 2, display.y * 3 / 4, display.x / 2, display.y / 3, 800)
            }
        }
    }
}
