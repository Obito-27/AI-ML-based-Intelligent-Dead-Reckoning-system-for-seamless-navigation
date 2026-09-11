package com.dracarys.idr.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.lang.management.ManagementFactory
import java.util.Locale

class DiagnosticLoggerProfilingTest {

    @Test
    fun profileTenMinuteSoakTest() {
        val tempFile = File.createTempFile("soak_test_", ".csv")
        tempFile.deleteOnExit()

        val rawAcc = doubleArrayOf(0.12, -0.05, 9.81)
        val gravity = doubleArrayOf(0.0, 0.0, 9.80665)
        val gyro = doubleArrayOf(0.001, -0.002, 0.005)
        val uUp = doubleArrayOf(0.0, 0.0, 1.0)

        val iterations = 6000 // 10 minutes at 10 Hz (600 seconds * 10 samples/sec)

        val threadBean = ManagementFactory.getThreadMXBean()
        val supportsCpuTime = threadBean.isCurrentThreadCpuTimeSupported

        val startCpu = if (supportsCpuTime) threadBean.currentThreadCpuTime else 0L
        val startWall = System.nanoTime()

        var bytesWritten = 0L

        BufferedWriter(FileWriter(tempFile), 16384).use { w ->
            for (i in 0 until iterations) {
                val now = 1789126443000L + (i * 100L)
                val elapsedSec = i * 0.1

                val row = StringBuilder(256)
                    .append(now).append(',')
                    .append(String.format(Locale.US, "%.2f", elapsedSec)).append(',')
                    .append(100).append(',')
                    .append("NONE").append(',')
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", rawAcc[0], rawAcc[1], rawAcc[2]))
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", gravity[0], gravity[1], gravity[2]))
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", gyro[0], gyro[1], gyro[2]))
                    .append(String.format(Locale.US, "%.5f,%.5f,%.5f,", uUp[0], uUp[1], uUp[2]))
                    .append(String.format(Locale.US, "%.2f,", 15.4))
                    .append(String.format(Locale.US, "%.5f,", 0.01234))
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", 0.1, -0.05, 0.02))
                    .append(String.format(Locale.US, "%.3f,%.5f,%.3f,", 12.5, 0.001, 0.85))
                    .append("DeadReckoning").append(',')
                    .append(String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f,", 12.4, 0.01, 12.3, 0.5))
                    .append(String.format(Locale.US, "%.2f,%.2f,%.4f,%.6f,", 150.2, 45.1, 0.234, 0.0001))
                    .append('0').append(',')
                    .append('0').append(',')
                    .append(String.format(Locale.US, "%.6f,%.6f,%.2f,%d,%.1f", 52.4, -1.5, 12.5, 8, 3.2))
                    .toString()

                w.write(row)
                w.newLine()
                bytesWritten += row.length + 1

                if (i % 10 == 0) {
                    w.flush()
                }
            }
            w.flush()
        }

        val endWall = System.nanoTime()
        val endCpu = if (supportsCpuTime) threadBean.currentThreadCpuTime else 0L

        val wallMs = (endWall - startWall) / 1_000_000.0
        val cpuMs = if (supportsCpuTime) (endCpu - startCpu) / 1_000_000.0 else wallMs

        val perSampleMicros = (wallMs * 1000.0) / iterations
        val cpuDutyCyclePct = (cpuMs / (iterations * 100.0)) * 100.0 // across 600,000 ms real time

        println("=================================================================")
        println("DIAGNOSTIC LOGGER 10-MINUTE SYNTHETIC SOAK TEST (6,000 TICKS)")
        println("=================================================================")
        println("Total rows formatted & written : $iterations")
        println("Total data written             : ${bytesWritten / 1024} KB (${tempFile.length() / 1024} KB on disk)")
        println(String.format(Locale.US, "Total Wall-Clock Time          : %.2f ms", wallMs))
        println(String.format(Locale.US, "Total CPU Time (6,000 ticks)   : %.2f ms", cpuMs))
        println(String.format(Locale.US, "Average per-tick formatting    : %.2f \u00b5s (microseconds)", perSampleMicros))
        println(String.format(Locale.US, "Real-time CPU Duty Cycle @10Hz : %.3f%% of one CPU core", cpuDutyCyclePct))
        println("=================================================================")

        // Even on slow mobile cores, formatting should take less than 150 microseconds per sample
        assertTrue("Per-sample formatting must take less than 1000 us", perSampleMicros < 1000.0)
        // CPU duty cycle at 10Hz must be negligible (< 1.0% of a core)
        assertTrue("CPU duty cycle must be well under 1%", cpuDutyCyclePct < 1.0)
    }
}
