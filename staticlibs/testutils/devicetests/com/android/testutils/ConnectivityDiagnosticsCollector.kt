/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.testutils

import android.Manifest.permission.NETWORK_SETTINGS
import android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import android.content.pm.PackageManager.FEATURE_WIFI
import android.device.collectors.BaseMetricListener
import android.device.collectors.DataRecord
import android.net.ConnectivityManager.NetworkCallback
import android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.SIM_STATE_UNKNOWN
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel.isAtLeastS
import com.android.testutils.com.android.testutils.getTargetModuleVersion
import java.io.ByteArrayOutputStream
import java.io.CharArrayWriter
import java.io.File
import java.io.FileReader
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Reader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertNull
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure

/**
 * A diagnostics collector that outputs diagnostics files as test artifacts.
 *
 * <p>Collects diagnostics automatically by default on non-local builds. Can be enabled/disabled
 * manually with:
 * ```
 * atest MyModule -- \
 *     --module-arg MyModule:instrumentation-arg:connectivity-diagnostics-on-failure:=false
 * ```
 */
class ConnectivityDiagnosticsCollector : BaseMetricListener() {
    companion object {
        private const val ARG_RUN_ON_FAILURE = "connectivity-diagnostics-on-failure"
        private const val COLLECTOR_DIR = "run_listeners/connectivity_diagnostics"
        private const val FILENAME_SUFFIX = "_conndiag.txt"
        private const val MAX_DUMPS = 20

        private val TAG = ConnectivityDiagnosticsCollector::class.simpleName
        @JvmStatic
        var instance: ConnectivityDiagnosticsCollector? = null
    }

    /**
     * Indicates tcpdump should be started and written to the diagnostics file on test case failure.
     */
    annotation class CollectTcpdumpOnFailure

    private class DumpThread(
        // Keep a reference to the ParcelFileDescriptor otherwise GC would close it
        private val fd: ParcelFileDescriptor,
        private val reader: Reader
    ) : Thread() {
        private val writer = CharArrayWriter()
        override fun run() {
            reader.copyTo(writer)
        }

        fun closeAndWriteTo(output: OutputStream?) {
            join()
            fd.close()
            if (output != null) {
                val outputWriter = OutputStreamWriter(output)
                outputWriter.write("--- tcpdump stopped at ${ZonedDateTime.now()} ---\n")
                writer.writeTo(outputWriter)
            }
        }
    }

    private data class TcpdumpRun(val pid: Int, val reader: DumpThread)

    private var failureHeader: String? = null

    // Accessed from the test listener methods which are synchronized by junit (see TestListener)
    private var tcpdumpRun: TcpdumpRun? = null
    private val buffer = ByteArrayOutputStream()
    private val failureHeaderExtras = mutableMapOf<String, Any>()
    private val collectorDir: File by lazy {
        createAndEmptyDirectory(COLLECTOR_DIR)
    }
    private val outputFiles = mutableSetOf<String>()
    private val cbHelper = NetworkCallbackHelper()
    private val networkCallback = MonitoringNetworkCallback()

    inner class MonitoringNetworkCallback : NetworkCallback() {
        val currentMobileDataNetworks = mutableMapOf<Network, NetworkCapabilities>()
        val currentVpnNetworks = mutableMapOf<Network, NetworkCapabilities>()
        val currentWifiNetworks = mutableMapOf<Network, NetworkCapabilities>()

        override fun onLost(network: Network) {
            currentWifiNetworks.remove(network)
            currentMobileDataNetworks.remove(network)
        }

        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            if (nc.hasTransport(TRANSPORT_VPN)) {
                currentVpnNetworks[network] = nc
            } else if (nc.hasTransport(TRANSPORT_WIFI)) {
                currentWifiNetworks[network] = nc
            } else if (nc.hasTransport(TRANSPORT_CELLULAR)) {
                currentMobileDataNetworks[network] = nc
            }
        }
    }

    override fun onSetUp() {
        assertNull(instance, "ConnectivityDiagnosticsCollectors were set up multiple times")
        instance = this
        TryTestConfig.swapDiagnosticsCollector { throwable ->
            if (runOnFailure(throwable)) {
                collectTestFailureDiagnostics(throwable)
            }
        }
    }

    override fun onCleanUp() {
        instance = null
    }

    override fun onTestRunStart(runData: DataRecord?, description: Description?) {
        runAsShell(NETWORK_SETTINGS) {
            cbHelper.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NET_CAPABILITY_INTERNET)
                    .addTransportType(TRANSPORT_WIFI)
                    .addTransportType(TRANSPORT_CELLULAR)
                    .build(),
                networkCallback
            )
        }
    }

    override fun onTestRunEnd(runData: DataRecord?, result: Result?) {
        // onTestRunEnd is called regardless of success/failure, and the Result contains summary of
        // run/failed/ignored... tests.
        cbHelper.unregisterAll()
    }

    override fun onTestFail(testData: DataRecord, description: Description, failure: Failure) {
        // TODO: find a way to disable this behavior only on local runs, to avoid slowing them down
        // when iterating on failing tests.
        if (!runOnFailure(failure.exception)) return
        if (outputFiles.size >= MAX_DUMPS) return
        Log.i(
            TAG,
            "Collecting diagnostics for test failure. Disable by running tests with: " +
                "atest MyModule -- " +
                "--module-arg MyModule:instrumentation-arg:$ARG_RUN_ON_FAILURE:=false"
        )
        collectTestFailureDiagnostics(failure.exception)

        val baseFilename = "${description.className}#${description.methodName}_failure"
        flushBufferToFileMetric(testData, baseFilename)
    }

    override fun onTestStart(testData: DataRecord, description: Description) {
        val tcpdumpAnn = description.annotations.firstOrNull { it is CollectTcpdumpOnFailure }
                as? CollectTcpdumpOnFailure
        if (tcpdumpAnn != null) {
            startTcpdumpForTestcaseIfSupported()
        }
    }

    private fun startTcpdumpForTestcaseIfSupported() {
        if (!DeviceInfoUtils.isDebuggable()) {
            Log.d(TAG, "Cannot start tcpdump, build is not debuggable")
            return
        }
        if (tcpdumpRun != null) {
            Log.e(TAG, "Cannot start tcpdump: it is already running")
            return
        }
        // executeShellCommand won't tokenize quoted arguments containing spaces (like pcap filters)
        // properly, so pass in the command in stdin instead of using sh -c 'command'
        val fds = instrumentation.uiAutomation.executeShellCommandRw("sh")

        val stdout = fds[0]
        val stdin = fds[1]
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { writer ->
            // Echo the current pid, and replace it (with exec) with the tcpdump process, so the
            // tcpdump pid is known.
            writer.write(
                "echo $$; exec su 0 tcpdump -n -i any -l -xx".encodeToByteArray()
            )
        }
        val reader = FileReader(stdout.fileDescriptor).buffered()
        val tcpdumpPid = Integer.parseInt(reader.readLine())
        val dumpThread = DumpThread(stdout, reader)
        dumpThread.start()
        tcpdumpRun = TcpdumpRun(tcpdumpPid, dumpThread)
    }

    private fun stopTcpdumpIfRunning(output: OutputStream?) {
        val run = tcpdumpRun ?: return
        // Send SIGTERM for graceful shutdown of tcpdump so that it can flush its output
        executeCommandBlocking("su 0 kill ${run.pid}")
        run.reader.closeAndWriteTo(output)
        tcpdumpRun = null
    }

    override fun onTestEnd(testData: DataRecord, description: Description) {
        // onTestFail is called before onTestEnd, so if the test failed tcpdump would already have
        // been stopped and output dumped. Here this stops tcpdump if the test succeeded, throwing
        // away its output.
        stopTcpdumpIfRunning(output = null)

        // Tests may call methods like collectDumpsysConnectivity to collect diagnostics at any time
        // during the run, for example to observe state at various points to investigate a flake
        // and compare passing/failing cases.
        // Flush the contents of the buffer to a file when the test ends, even when successful.
        if (buffer.size() == 0) return
        if (outputFiles.size >= MAX_DUMPS) return

        // Flush any data that the test added to the buffer for dumping
        val baseFilename = "${description.className}#${description.methodName}_testdump"
        flushBufferToFileMetric(testData, baseFilename)
    }

    private fun runOnFailure(exception: Throwable): Boolean {
        // Assumption failures (assumeTrue/assumeFalse) are not actual failures
        if (exception is AssumptionViolatedException) return false

        // Do not run on local builds (which have ro.build.version.incremental set to eng.username)
        // to avoid slowing down local runs.
        val enabledByDefault = !Build.VERSION.INCREMENTAL.startsWith("eng.")
        return argsBundle.getString(ARG_RUN_ON_FAILURE)?.toBooleanStrictOrNull() ?: enabledByDefault
    }

    private fun flushBufferToFileMetric(testData: DataRecord, baseFilename: String) {
        var filename = baseFilename
        // In case a method was run multiple times (typically retries), append a number
        var i = 2
        while (outputFiles.contains(filename)) {
            filename = baseFilename + "_$i"
            i++
        }
        val outFile = File(collectorDir, filename + FILENAME_SUFFIX)
        outputFiles.add(filename)
        getOutputStreamViaShell(outFile).use { fos ->
            failureHeader?.let {
                fos.write(it.toByteArray())
                fos.write("\n".toByteArray())
            }
            fos.write(buffer.toByteArray())
            stopTcpdumpIfRunning(fos)
        }
        failureHeader = null
        buffer.reset()
        val fileKey = "${ConnectivityDiagnosticsCollector::class.qualifiedName}_$filename"
        testData.addFileMetric(fileKey, outFile)
    }

    private fun maybeCollectFailureHeader() {
        if (failureHeader != null) {
            Log.i(TAG, "Connectivity diagnostics failure header already collected, skipping")
            return
        }

        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.context
        val pm = ctx.packageManager
        val hasWifi = pm.hasSystemFeature(FEATURE_WIFI)
        val hasMobileData = pm.hasSystemFeature(FEATURE_TELEPHONY)
        val tm = if (hasMobileData) ctx.getSystemService(TelephonyManager::class.java) else null
        // getAdoptedShellPermissions is S+. Optimistically assume that tests are not holding on
        // shell permissions during failure/cleanup on R.
        val canUseShell = !isAtLeastS() ||
                instr.uiAutomation.getAdoptedShellPermissions().isNullOrEmpty()
        val headerObj = JSONObject()
        failureHeaderExtras.forEach { (k, v) -> headerObj.put(k, v) }
        failureHeaderExtras.clear()
        if (canUseShell) {
            runAsShell(READ_PRIVILEGED_PHONE_STATE, NETWORK_SETTINGS) {
                headerObj.apply {
                    put("deviceSerial", Build.getSerial())
                    // The network callback filed on start cannot get the WifiInfo as it would need
                    // to keep NETWORK_SETTINGS permission throughout the test run. Try to
                    // obtain it while holding the permission at the end of the test.
                    val wifiInfo = networkCallback.currentWifiNetworks.keys.firstOrNull()?.let {
                        getWifiInfo(it)
                    }
                    put("ssid", wifiInfo?.ssid)
                    put("bssid", wifiInfo?.bssid)
                    put("simState", tm?.simState ?: SIM_STATE_UNKNOWN)
                    put("mccMnc", tm?.simOperator)
                }
            }
        } else {
            Log.w(
                TAG,
                "The test is still holding shell permissions, cannot collect privileged " +
                    "device info"
            )
            headerObj.put("shellPermissionsUnavailable", true)
        }
        failureHeader = headerObj.apply {
            put("targetModuleVersion", getTargetModuleVersion())
            put("time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now()))
            put(
                "wifiEnabled",
                hasWifi && ctx.getSystemService(WifiManager::class.java).isWifiEnabled
            )
            put("connectedWifiCount", networkCallback.currentWifiNetworks.size)
            put("validatedWifiCount", networkCallback.currentWifiNetworks.filterValues {
                it.hasCapability(NET_CAPABILITY_VALIDATED)
            }.size)
            put("mobileDataConnectivityPossible", tm?.isDataConnectivityPossible ?: false)
            put("connectedMobileDataCount", networkCallback.currentMobileDataNetworks.size)
            put("validatedMobileDataCount",
                networkCallback.currentMobileDataNetworks.filterValues {
                    it.hasCapability(NET_CAPABILITY_VALIDATED)
                }.size
            )
        }.toString()
    }

    private class WifiInfoCallback : NetworkCallback {
        private val network: Network
        val wifiInfoFuture = CompletableFuture<WifiInfo?>()
        constructor(network: Network) : super() {
            this.network = network
        }
        @RequiresApi(Build.VERSION_CODES.S)
        constructor(network: Network, flags: Int) : super(flags) {
            this.network = network
        }
        override fun onCapabilitiesChanged(net: Network, nc: NetworkCapabilities) {
            if (network == net) {
                wifiInfoFuture.complete(nc.transportInfo as? WifiInfo)
            }
        }
    }

    private fun getWifiInfo(network: Network): WifiInfo? {
        // Get the SSID via network callbacks, as the Networks are obtained via callbacks, and
        // synchronous calls (CM#getNetworkCapabilities) and callbacks should not be mixed.
        // A new callback needs to be filed and received while holding NETWORK_SETTINGS permission.
        val cb = if (isAtLeastS()) {
            WifiInfoCallback(network, FLAG_INCLUDE_LOCATION_INFO)
        } else {
            WifiInfoCallback(network)
        }
        cbHelper.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET).build(),
            cb
        )
        return try {
            cb.wifiInfoFuture.get(1L, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            null
        } finally {
            cbHelper.unregisterNetworkCallback(cb)
        }
    }

    /**
     * Add connectivity diagnostics to the test data dump.
     *
     * <p>This collects a set of diagnostics that are relevant to connectivity test failures.
     * <p>The dump will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectTestFailureDiagnostics(exceptionContext: Throwable? = null) {
        maybeCollectFailureHeader()
        collectDumpsysConnectivity(exceptionContext)
    }

    /**
     * Add dumpsys connectivity to the test data dump.
     *
     * <p>The dump will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectDumpsysConnectivity(exceptionContext: Throwable? = null) {
        collectDumpsys("connectivity --dump-priority HIGH", exceptionContext)
    }

    /**
     * Add a dumpsys to the test data dump.
     *
     * <p>The dump will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param dumpsysCmd The dumpsys command to run (for example "connectivity").
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectDumpsys(dumpsysCmd: String, exceptionContext: Throwable? = null) =
        collectCommandOutput("dumpsys $dumpsysCmd", exceptionContext = exceptionContext)

    /**
     * Add the output of a command to the test data dump.
     *
     * <p>The output will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param cmd The command to run. Stdout of the command will be collected.
     * @param shell The shell to run the command in, for example "sh".
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun collectCommandOutput(
        cmd: String,
        shell: String,
        exceptionContext: Throwable? = null
    ) = collectCommandOutput(cmd, exceptionContext) { c, outputProcessor ->
        runCommandInShell(c, shell, outputProcessor)
    }

    /**
     * Add the output of a command to the test data dump.
     *
     * <p>The output will be collected immediately, and exported to a test artifact file when the
     * test ends.
     *
     * <p>Note this does not support shell pipes, redirections, or quoted arguments. See the S+
     * overload if that is needed.
     * @param cmd The command to run. Stdout of the command will be collected.
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectCommandOutput(
        cmd: String,
        exceptionContext: Throwable? = null
    ) = collectCommandOutput(cmd, exceptionContext) { c, outputProcessor ->
        AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(c)
        ).use {
            outputProcessor(it)
        }
    }

    private fun collectCommandOutput(
        cmd: String,
        exceptionContext: Throwable? = null,
        commandRunner: (String, (InputStream) -> Unit) -> Unit
    ) {
        Log.i(TAG, "Collecting '$cmd' for test artifacts")
        PrintWriter(buffer).let {
            it.println("--- $cmd at ${ZonedDateTime.now()} ---")
            maybeWriteExceptionContext(it, exceptionContext)
            it.flush()
        }

        commandRunner(cmd) { stdout ->
            stdout.copyTo(buffer)
        }
    }

    /**
     * Add a key->value attribute to the failure data, to be written to the diagnostics file.
     *
     * <p>This is to be called by tests that know they will fail.
     */
    fun addFailureAttribute(key: String, value: Any) {
        failureHeaderExtras[key] = value
    }

    private fun maybeWriteExceptionContext(writer: PrintWriter, exceptionContext: Throwable?) {
        if (exceptionContext == null) return
        writer.println("At: ")
        exceptionContext.printStackTrace(writer)
    }
}
