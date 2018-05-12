package org.pnpi.protocol.v1

import android.util.JsonReader
import org.json.JSONArray
import org.json.JSONObject
import org.pnpi.protocol.Command
import org.pnpi.protocol.Hotspot
import org.pnpi.protocol.NetworkInterface
import org.pnpi.protocol.Service
import java.lang.IllegalStateException
import java.net.InetAddress

val expectService = fun(r: JsonReader): Service {
    var name: String? = null
    var running: Boolean? = null

    r.beginObject()
    while (r.hasNext()) {
        val key = r.nextName()
        when (key) {
            "name" -> name = r.nextString()
            "running" -> running = r.nextBoolean()
            else -> throw IllegalStateException("Expecting Service, invalid field name: $key")
        }
    }
    r.endObject()

    if (name == null)
        throw IllegalStateException("Expecting Service, absent field name: name")

    if (running == null)
        throw IllegalStateException("Expecting Service, absent field name: running")

    return Service(name, running)
}

val expectInetAddress = fun(r: JsonReader): InetAddress {
    return InetAddress.getByName(r.nextString())
}

val expectNetworkInterface = fun(r: JsonReader): NetworkInterface {
    var name: String? = null
    var ips: Collection<InetAddress>? = null
    var ssid: String? = null
    var wifi: Boolean? = null

    r.beginObject()
    while (r.hasNext()) {
        val key = r.nextName()
        when (key) {
            "name" -> name = r.nextString()
            "ip" -> ips = expectArray(mutableSetOf(), expectInetAddress)(r)
            "ssid" -> ssid = r.nextString()
            "wifi" -> wifi = r.nextBoolean()
            else -> throw IllegalStateException("Expecting NetworkInterface, invalid field name: $key")
        }
    }
    r.endObject()

    if (name == null)
        throw IllegalStateException("Expecting NetworkInterface, absent field name: name")

    if (wifi == null)
        throw IllegalStateException("Expecting NetworkInterface, absent field name: wifi")

    return NetworkInterface(name, ips as? Set<InetAddress> ?: setOf(), ssid ?: "", wifi)
}

val expectHotspot = fun(r: JsonReader): Hotspot {
    var ssid: String? = null
    var open: Boolean? = null
    var signal: Int? = null

    r.beginObject()
    while (r.hasNext()) {
        val key = r.nextName()
        when (key) {
            "ssid" -> ssid = r.nextString()
            "open" -> open = r.nextBoolean()
            "signal" -> signal = r.nextInt()
            else -> throw IllegalStateException("Expecting Hotspot, invalid field name: $key")
        }
    }
    r.endObject()

    if (ssid == null)
        throw IllegalStateException("Expecting Hotspot, absent field name: ssid")

    if (open == null)
        throw IllegalStateException("Expecting Hotspot, absent field name: open")

    if (signal == null)
        throw IllegalStateException("Expecting Hotspot, absent field name: signal")

    return Hotspot(ssid, open, signal)
}

fun <T> expectArray(accumulator: MutableCollection<T>, element: (JsonReader) -> T):
                (JsonReader) -> Collection<T> =
        fun(r: JsonReader): Collection<T> {
            r.beginArray()
            while (r.hasNext()) {
                accumulator.add(element(r))
            }
            r.endArray()
            return accumulator
        }

fun expectReport(r: JsonReader): Report {
    var type: String? = null
    var interfaces: Collection<NetworkInterface>? = null
    var services: Collection<Service>? = null
    var hotspots: Collection<Hotspot>? = null
    var empty = true

    try {
        r.beginObject()
        while (r.hasNext()) {
            empty = false
            val key = r.nextName()
            when (key) {
                "type" -> type = r.nextString()
                "interfaces" -> interfaces = expectArray(mutableSetOf(), expectNetworkInterface)(r)
                "services" -> services = expectArray(mutableSetOf(), expectService)(r)
                "hotspots" -> hotspots = expectArray(mutableSetOf(), expectHotspot)(r)
                else -> throw IllegalStateException("Expecting Report, invalid field name: $key")
            }
        }
        r.endObject()
    }
    finally {
        r.close()
    }

    if (empty)
        return Report("nochange", setOf(), setOf(), setOf())

    if (type == null)
        throw IllegalStateException("Expecting Report, absent field name: type")

    if (type !in arrayOf("system", "change", "scan"))
        throw IllegalStateException("Expecting Report, invalid type: $type")

    return Report(type,
            interfaces as? Set<NetworkInterface> ?: setOf(),
            services as? Set<Service> ?: setOf(),
            hotspots as? Set<Hotspot> ?: setOf())
}

fun toBytes(c: Command): ByteArray =
        JSONObject().apply {
            put("action", c.action)
            put("args", JSONArray(c.args))
        }.toString().toByteArray(Charsets.UTF_8)
