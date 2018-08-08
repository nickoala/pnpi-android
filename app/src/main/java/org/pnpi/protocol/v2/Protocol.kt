package org.pnpi.protocol.v2

import android.os.AsyncTask
import org.json.JSONArray
import org.json.JSONObject
import org.pnpi.protocol.*
import org.pnpi.protocol.Protocol
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

val produceInetAddress = fun (s: String) =
        InetAddress.getByName(s)

val produceNetworkInterface = fun (js: JSONObject) =
        NetworkInterface(
                js.getString("name"),
                produceSetOf(js.optJSONArray("ip"), produceInetAddress),
                js.optString("ssid") ?: "",
                js.getBoolean("wifi"))

val produceService = fun (js: JSONObject) =
        Service(js.getString("name"), js.getBoolean("running"))

val produceHotspot = fun (js: JSONObject) =
        Hotspot(js.getString("ssid"), js.getBoolean("open"), js.getInt("signal"))

val produceCountry = fun (js: JSONObject) =
        Country(js.getString("code"), js.getString("name"))

inline fun <reified S, T> produceSetOf(array: JSONArray?, element: (S) -> T): Set<T> {
    val set = mutableSetOf<T>()

    if (array == null)
        return set

    for (i in 0 until array.length()) {
        when (S::class) {
            String::class -> set.add(element(array.getString(i) as S))
            JSONObject::class -> set.add(element(array.getJSONObject(i) as S))
            else -> throw IllegalArgumentException("Unsupported class: ${S::class}")
        }
    }
    return set
}

fun produceHostStates(js: JSONObject) =
        HostStates(
                produceSetOf(js.getJSONArray("interfaces"), produceNetworkInterface),
                produceSetOf(js.getJSONArray("services"), produceService),
                js.getString("wifi_country_code"))

fun produceHostStatesChange(js: JSONObject) =
        HostStatesChange(
                produceSetOf(js.optJSONArray("interfaces"), produceNetworkInterface),
                produceSetOf(js.optJSONArray("services"), produceService),
                js.getString("wifi_country_code"))

fun produceHostChoices(js: JSONObject) =
        HostChoices(produceSetOf(js.getJSONArray("countries"), produceCountry))

fun produceScanResult(js: JSONObject) =
        ScanResult(produceSetOf(js.getJSONArray("hotspots"), produceHotspot))

fun toBytes(c: Command): ByteArray =
        JSONObject().apply {
            put("action", c.action)
            put("args", JSONArray(c.args))
        }.toString().toByteArray(Charsets.UTF_8)


class Protocol : org.pnpi.protocol.Protocol() {
    private var monitorStarted = false
    private var scanStarted = false

    private fun patience(): Long =
            when {
                monitorStarted -> 4500
                scanStarted -> 15500  // Scan result may be delayed by one period
                                      // because server does not trust the first empty result.
                                      // So, it should include two scan periods, plus margin.
                else -> 0
            }

    private val contactMonitor = ContactMonitor()

    override fun send(c: Command) {
        when (c.action) {
            "monitor" -> when (c.args[0]) {
                "start" -> monitorStarted = true
                "stop" -> monitorStarted = false
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
            fun readObject(): Any {
                val header = ByteArray(2)
                input.fill(header)

                val len = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getShort()

                if (len < 0)
                    throw IllegalStateException("Message length negative")

                val body = ByteArray(len.toInt())
                input.fill(body)

                val js = JSONObject(String(body))

                if (js.length() == 0)
                    return HostNoChange()

                return when (js.getString("type")) {
                    "choices" -> produceHostChoices(js)
                    "states" -> produceHostStates(js)
                    "change" -> produceHostStatesChange(js)
                    "scan" -> produceScanResult(js)
                    else -> throw IllegalStateException("JSON object of unrecognized type: ${js.getString("type")}")
                }
            }

            try {
                contactMonitor.start()
                send(Command("monitor", "start"))

                while (true) {
                    val j = readObject()
                    contactMonitor.poke()

                    when (j) {
                        is HostNoChange -> {}

                        is HostChoices ->
                            tellHandlers(Protocol.Event.HOST_CHOICES, j)

                        is HostStates ->
                            tellHandlers(Protocol.Event.HOST_STATES, j)

                        is HostStatesChange ->
                            tellHandlers(Protocol.Event.HOST_STATES_CHANGE, j)

                        is ScanResult ->
                            tellHandlers(Protocol.Event.SCAN_RESULT, j)
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
