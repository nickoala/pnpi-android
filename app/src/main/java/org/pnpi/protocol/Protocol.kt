package org.pnpi.protocol

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import com.google.android.agera.Repositories
import com.google.android.agera.Updatable
import org.pnpi.Reactor
import java.io.InputStream
import java.io.OutputStream
import java.lang.IllegalArgumentException
import java.net.InetAddress
import java.util.*

data class NetworkInterface(
        val name: String,
        val ips: Set<InetAddress>,
        val ssid: String,
        val wifi: Boolean)

data class Service(
        val name: String,
        val running: Boolean)

data class HostStates(
        val interfaces: Set<NetworkInterface>,
        val services: Set<Service>)

data class Hotspot(
        val ssid: String,
        val open: Boolean,
        val signal: Int)

data class Command(
        val action: String,
        val args: List<String> = listOf()) {

    constructor(action: String, vararg args: String) : this(action, args.toList())
}

class SendTask(val output: OutputStream, val transform: (Command) -> ByteArray)
        : AsyncTask<Command, Void, Boolean>() {
    override fun doInBackground(vararg cs: Command?): Boolean {
        try {
            cs.forEach {
                it?.let {
                    output.write(transform(it))
                }
            }
        }
        catch (x: Exception) {
        }
        finally {
            return true
        }
    }
}

abstract class Protocol {
    companion object {
        private val supportedVersionSet = setOf("1")

        fun versionIsSupported(version: String): Boolean =
                supportedVersionSet.contains(version)

        fun get(version: String): Protocol =
                when (version) {
                    "1" -> org.pnpi.protocol.v1.Protocol()
                    else -> throw IllegalArgumentException("Invalid protocol version")
                }
    }

    lateinit var input: InputStream
    lateinit var output: OutputStream

    fun setStreams(i: InputStream, o: OutputStream) {
        input = i
        output = o
    }

    private val lock = Any()
    var handlers: Set<Handler> = setOf()
        get() {
            synchronized (lock) {
                return field
            }
        }
        set(value) {
            synchronized (lock) {
                field = value
            }
        }

    fun addHandler(h: Handler) {
        handlers = handlers.union(setOf(h))
    }

    fun removeHandler(h: Handler) {
        handlers = handlers.filter { it != h }.toSet()
    }

    fun tellHandlers(what: Int) {
        handlers.forEach {
            it.tell(what)
        }
    }

    fun tellHandlers(what: Int, obj: Any) {
        handlers.forEach {
            it.tell(what, obj)
        }
    }

    abstract fun send(c: Command)
    abstract fun start()

    object Event {
        const val NO_CONTACT = 1
        const val IN_CONTACT = 2
        const val EXCEPTION = 3
        const val END_CONTACT = 4
        const val RAW = 101
        const val HOST_STATES = 201
        const val NETWORK_INTERFACE = 202
        const val SERVICE = 203
        const val HOTSPOTS = 204
    }

    inner class ContactMonitor {
        private val waitDuration = Repositories.mutableRepository<Long>(0)
        private val renewTrigger = Repositories.mutableRepository(false)
        private val inContact = Repositories.mutableRepository(false)

        private val reactor = Reactor()
        private val timer = Timer()
        private var timeoutTask: TimerTask? = null

        init {
            reactor.run {
                link(inContact, Updatable {
                    if (inContact.get()) {
                        tellHandlers(Event.IN_CONTACT)
                    }
                    else {
                        tellHandlers(Event.NO_CONTACT)
                    }
                }, update = false)

                link(renewTrigger, Updatable {
                    renewTimeout()
                }, update = false)

                link(waitDuration, Updatable {
                    val d = waitDuration.get()
                    if (d > 0) {
                        renewTimeout()
                    }
                    else {
                        cancelTimeout()
                    }
                }, update = false)
            }
        }

        private fun cancelTimeout() {
            timeoutTask?.cancel()
            timeoutTask = null
            timer.purge()
        }

        private fun renewTimeout() {
            cancelTimeout()

            val d = waitDuration.get()
            if (d > 0) {
                timeoutTask = object : TimerTask() {
                    override fun run() {
                        handler.post {
                            inContact.accept(false)
                        }
                    }
                }
                timer.schedule(timeoutTask, d)
            }
        }

        private val handler = Handler(Looper.getMainLooper())

        fun patience(millis: Long) {
            handler.post {
                waitDuration.accept(millis)
            }
        }

        fun poke() {
            handler.post {
                renewTrigger.accept(!renewTrigger.get())
                inContact.accept(true)
            }
        }

        fun start() {
            handler.post {
                reactor.activate()
            }
        }

        fun stop() {
            handler.post {
                cancelTimeout()
                timer.cancel()
                reactor.deactivate()
            }
        }
    }
}

fun InputStream.fill(buf: ByteArray) {
    var j = 0
    while (j < buf.size) {
        j += this.read(buf, j, buf.size-j)
    }
}

fun Handler.tell(what: Int) {
    this.obtainMessage(what).sendToTarget()
}

fun Handler.tell(what: Int, obj: Any) {
    this.obtainMessage(what, obj).sendToTarget()
}
