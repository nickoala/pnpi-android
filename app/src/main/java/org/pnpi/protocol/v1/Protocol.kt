package org.pnpi.protocol.v1

import android.os.AsyncTask
import android.util.JsonReader
import org.pnpi.protocol.*
import org.pnpi.protocol.Protocol
import java.io.StringReader
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Report(
        val type: String,
        val interfaces: Set<NetworkInterface>,
        val services: Set<Service>,
        val hotspots: Set<Hotspot>)

class Protocol : org.pnpi.protocol.Protocol() {
    private var systemStarted = false
    private var scanStarted = false

    private fun patience(): Long =
            when {
                systemStarted -> 4500
                scanStarted -> 15500  // Scan result may be delayed by one period
                                      // because server does not trust the first empty result.
                                      // So, it should include two scan periods, plus margin.
                else -> 0
            }

    private val contactMonitor = ContactMonitor()

    override fun send(c: Command) {
        when (c.action) {
            "system" -> when (c.args[0]) {
                "start" -> systemStarted = true
                "stop" -> systemStarted = false
            }
            "scan" -> when (c.args[0]) {
                "start" -> scanStarted = true
                "stop" -> scanStarted = false
            }
        }

        contactMonitor.patience(patience())

        SendTask(output, { cmd -> toBytes(cmd) }).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, c)
        // Execute in serial, no need to control access to output stream.
    }

    override fun start() {
        Thread {
            fun readReport(): Report {
                val header = ByteArray(2)
                input.fill(header)

                val len = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getShort()

                if (len < 0)
                    throw IllegalStateException("Message length negative")

                val body = ByteArray(len.toInt())
                input.fill(body)

                return expectReport(JsonReader(StringReader(String(body))))
            }

            try {
                contactMonitor.start()
                send(Command("system", "start"))

                while (true) {
                    val report = readReport()
                    contactMonitor.poke()

                    when (report.type) {
                        "nochange" -> {}

                        "system" -> {
                            tellHandlers(Protocol.Event.HOST_STATES, HostStates(report.interfaces, report.services))
                        }

                        "change" -> {
                            report.interfaces.forEach {
                                tellHandlers(Protocol.Event.NETWORK_INTERFACE, it)
                            }
                            report.services.forEach {
                                tellHandlers(Protocol.Event.SERVICE, it)
                            }
                        }

                        "scan" -> {
                            tellHandlers(Protocol.Event.HOTSPOTS, report.hotspots)
                        }
                    }
                }
            } catch (x: Exception) {
                tellHandlers(Protocol.Event.EXCEPTION, x)
            } finally {
                contactMonitor.stop()
                tellHandlers(Protocol.Event.END_CONTACT)
            }
        }.start()
        // Thread is terminated by user closing the input stream.
    }
}
