package org.pnpi

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.support.v7.app.AppCompatActivity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.agera.Repositories
import com.google.android.agera.Updatable
import org.pnpi.protocol.*
import java.lang.IllegalStateException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.*

const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

enum class PlugStatus {
    NEVER,
    PLUGGED,
    UNPLUGGED
}

enum class AccessoryStatus {
    NONE,
    UNRECOGNIZED,
    VERSION_UNSUPPORTED,
    PERMISSION_DENIED,
    OPEN_FAILED,
    OPENED,
    CLOSED
}

enum class ContactStatus {
    NONE,
    DIALING,
    TIMED_OUT,
    SILENT,
    COMMUNICATING,
    ENDED
}

enum class TroubleshootStatus {
    NONE,
    PLUG_UNDETECTED,
    ACCESSORY_UNDETECTED
}

class AccessoryChannel private constructor(
        private val protocol: Protocol,
        private val fd: ParcelFileDescriptor) {

    companion object {
        var opened: AccessoryChannel? = null

        fun open(p: Protocol, fd: ParcelFileDescriptor): AccessoryChannel {
            opened?.close()
            opened = AccessoryChannel(p, fd)
            opened?.let {
                return it
            } ?: throw IllegalStateException("Cannot return a null AccessoryChannel")
        }
    }

    private val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(fd)
    init {
        protocol.setStreams(input, output)
    }

    fun send(c: Command) = protocol.send(c)

    fun start() = protocol.start()

    fun addHandler(h: Handler) = protocol.addHandler(h)

    fun removeHandler(h: Handler) = protocol.removeHandler(h)

    fun close() {
        try { fd.close() }
        catch (x: Exception) {}

        try { input.close() }
        catch (x: Exception) {}

        try { output.close() }
        catch (x: Exception) {}

        opened = null
    }
}

class MainActivity : AppCompatActivity() {
    companion object {
        const val PICK_HOTSPOT_REQUEST = 1
    }

    private object Timeout {
        const val SERVICE_SWITCH = 5500L
        const val HOTSPOT_CONNECT = 12000L
        const val HOTSPOT_DISCONNECT = 5500L
        const val AWAIT_ACCESSORY = 30000L  // Give time for server to come up, when Pi is starting.
        const val AWAIT_DIALING = 8100L
        const val AWAIT_MORE_INFO = 9800L
    }

    private lateinit var accessoryFilter: AccessoryFilter

    private val plugStatus = Repositories.mutableRepository(PlugStatus.NEVER)
    private val accessoryStatus = Repositories.mutableRepository(AccessoryStatus.NONE)
    private val contactStatus = Repositories.mutableRepository(ContactStatus.NONE)
    private val troubleshootStatus = Repositories.mutableRepository(TroubleshootStatus.NONE)

    private val hostIsCommunicating = Repositories.repositoryWithInitialValue(false)
            .observe(contactStatus)
            .onUpdatesPerLoop()
            .getFrom(contactStatus)
            .thenTransform { it == ContactStatus.COMMUNICATING }
            .compile()

    private val expectingEndOfContact = Repositories.mutableRepository(false)

    private val actionIsAllowed = Repositories.repositoryWithInitialValue(false)
            .observe(hostIsCommunicating, expectingEndOfContact)
            .onUpdatesPerLoop()
            .getFrom(hostIsCommunicating)
            .mergeIn(expectingEndOfContact, pairer())
            .thenTransform { it.first && !it.second }
            .compile()

    private val accessoryComingUp = Repositories.mutableRepository(false)
    private var accessoryComingUpTimer: Timer? = null

    private var dialingTimer: Timer? = null

    private val mainReactor = Reactor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.Toolbar))

        accessoryFilter = AccessoryFilter(resources, R.xml.accessory_filter)

        // Power menu
        findViewById<View>(R.id.Power).setOnClickListener { view ->
            class ConfirmShutdownFragment : DialogFragment() {
                override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
                    return AlertDialog.Builder(activity)
                            .setMessage(R.string.confirm_halt)
                            .setTitle("Confirm")
                            .setPositiveButton("OK", { dialog, which ->
                                AccessoryChannel.opened?.send(Command("halt"))
                                expectEndOfContact()
                                toast(R.string.host_halting)
                            })
                            .setNegativeButton("Cancel", { dialog, which ->
                            })
                            .create()
                }
            }

            class ConfirmRebootFragment : DialogFragment() {
                override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
                    return AlertDialog.Builder(activity)
                            .setMessage(R.string.confirm_reboot)
                            .setTitle("Confirm")
                            .setPositiveButton("OK", { dialog, which ->
                                AccessoryChannel.opened?.send(Command("reboot"))
                                expectEndOfContact()
                                toast(R.string.host_rebooting)
                            })
                            .setNegativeButton("Cancel", { dialog, which ->
                            })
                            .create()
                }
            }

            PopupMenu(this@MainActivity, view).apply {
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.shutdown -> {
                            ConfirmShutdownFragment().show(fragmentManager, "ConfirmShutdownDialog")
                        }
                        R.id.reboot -> {
                            ConfirmRebootFragment().show(fragmentManager, "ConfirmRebootDialog")
                        }
                    }
                    true
                }
                menuInflater.inflate(R.menu.menu_power, menu)
            }.show()
        }

        mainReactor.run {
            // Control power button's visibility
            link(actionIsAllowed, visibilityFollows(this@MainActivity, R.id.Power, actionIsAllowed) {
                if (it) View.VISIBLE else View.GONE
            })

            fun isInProgress(comingUp: Boolean, c: ContactStatus) =
                    comingUp || c == ContactStatus.DIALING

            val inProgress = Repositories.repositoryWithInitialValue(false)
                    .observe(accessoryComingUp, contactStatus)
                    .onUpdatesPerLoop()
                    .getFrom(accessoryComingUp)
                    .mergeIn(contactStatus, pairer())
                    .thenTransform { isInProgress(it.first, it.second) }
                    .compile()

            // Progress bar visibility
            link(inProgress, visibilityFollows(this@MainActivity, R.id.Progress, inProgress) {
                if (it) View.VISIBLE else View.INVISIBLE
            })

            fun titleString(c: ContactStatus, progress: Boolean) =
                    when (progress) {
                        true -> "Connecting"
                        else -> when (c) {
                            ContactStatus.COMMUNICATING -> "Connected"
                            ContactStatus.SILENT -> "Lost Contact"
                            ContactStatus.ENDED -> "Disconnected"
                            else -> "Not Connected"
                        }
                    }

            val title = Repositories.repositoryWithInitialValue("")
                    .observe(contactStatus, inProgress)
                    .onUpdatesPerLoop()
                    .getFrom(contactStatus)
                    .mergeIn(inProgress, pairer())
                    .thenTransform { titleString(it.first, it.second) }
                    .compile()

            // Control title
            link(title, Updatable {
                supportActionBar?.title = title.get()
            })

            data class MessageGroupDescriptor(
                    val messageResid: Int,
                    val messageFontSizeSp: Float,
                    val troubleshootResid: Int,
                    val troubleshootOnClick: ((View) -> Unit)?
            )

            fun getDescriptor(p: PlugStatus, a: AccessoryStatus, t: TroubleshootStatus, c: ContactStatus):
                    MessageGroupDescriptor {
                when (t) {
                    TroubleshootStatus.PLUG_UNDETECTED -> return MessageGroupDescriptor(
                            R.string.check_cable, 18f, R.string.empty, null)

                    TroubleshootStatus.ACCESSORY_UNDETECTED -> return MessageGroupDescriptor(
                            R.string.restart_phone, 18f, R.string.empty, null)
                }

                when (p) {
                    PlugStatus.NEVER -> return MessageGroupDescriptor(
                            R.string.no_plug, 22f, R.string.pi_is_plugged, {
                        troubleshootStatus.accept(TroubleshootStatus.PLUG_UNDETECTED)
                    })

                    PlugStatus.UNPLUGGED -> return MessageGroupDescriptor(
                            R.string.no_plug, 22f, R.string.pi_is_plugged, {
                        troubleshootStatus.accept(TroubleshootStatus.PLUG_UNDETECTED)
                    })
                }

                when (a) {
                    AccessoryStatus.NONE -> return MessageGroupDescriptor(
                            R.string.accessory_none, 22f, R.string.server_is_enabled, {
                        troubleshootStatus.accept(TroubleshootStatus.ACCESSORY_UNDETECTED)
                    })
                    AccessoryStatus.UNRECOGNIZED -> return MessageGroupDescriptor(
                            R.string.accessory_unrecognized, 22f, R.string.empty, null)

                    AccessoryStatus.VERSION_UNSUPPORTED -> return MessageGroupDescriptor(
                            R.string.accessory_version_unsupported, 22f, R.string.empty, null)

                    AccessoryStatus.PERMISSION_DENIED -> return MessageGroupDescriptor(
                            R.string.accessory_permission_denied, 22f, R.string.empty, null)

                    AccessoryStatus.OPEN_FAILED -> return MessageGroupDescriptor(
                            R.string.accessory_open_failed, 22f, R.string.empty, null)
                }

                when (c) {
                    ContactStatus.TIMED_OUT -> return MessageGroupDescriptor(
                            R.string.dialing_timed_out, 22f, R.string.empty, null)
                }

                return MessageGroupDescriptor(R.string.empty, 22f, R.string.empty, null)
            }

            val messageGroup = Repositories.repositoryWithInitialValue(MessageGroupDescriptor(
                                                        R.string.empty, 22f, R.string.empty, null))
                    .observe(plugStatus, accessoryStatus, troubleshootStatus, contactStatus)
                    .onUpdatesPerLoop()
                    .getFrom(plugStatus)
                    .mergeIn(accessoryStatus, pairer())
                    .mergeIn(troubleshootStatus, tripler())
                    .mergeIn(contactStatus, pairer())
                    .thenTransform {
                        getDescriptor(it.first.first, it.first.second, it.first.third, it.second)
                    }
                    .compile()

            // Control message text
            link(messageGroup, Updatable {
                val desc = messageGroup.get()
                val tv = findViewById<TextView>(R.id.Message)

                tv.setText(desc.messageResid)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, desc.messageFontSizeSp)
            })

            // Control troubleshoot text and its behavior
            link(messageGroup, Updatable {
                val desc = messageGroup.get()
                val tv = findViewById<TextView>(R.id.Troubleshoot)

                tv.setText(desc.troubleshootResid)
                desc.troubleshootOnClick?.let {
                    tv.setOnClickListener(it)
                }
            })

            fun messageVisibilityValue(resid: Int, progress: Boolean) =
                    if (resid == R.string.empty || progress || hostMap != null) View.INVISIBLE else View.VISIBLE

            val messageVisibility = Repositories.repositoryWithInitialValue(View.INVISIBLE)
                    .observe(messageGroup, inProgress)
                    .onUpdatesPerLoop()
                    .getFrom(messageGroup)
                    .mergeIn(inProgress, pairer())
                    .thenTransform { messageVisibilityValue(it.first.messageResid, it.second) }
                    .compile()

            // Control message visibility
            link(messageVisibility, visibilityFollows(this@MainActivity, R.id.Message, messageVisibility))

            val troubleshootVisibility = Repositories.repositoryWithInitialValue(View.INVISIBLE)
                    .observe(messageGroup, inProgress)
                    .onUpdatesPerLoop()
                    .getFrom(messageGroup)
                    .mergeIn(inProgress, pairer())
                    .thenTransform { messageVisibilityValue(it.first.troubleshootResid, it.second) }
                    .compile()

            // Control troubleshoot visibility
            link(troubleshootVisibility, visibilityFollows(this@MainActivity, R.id.Troubleshoot, troubleshootVisibility))

            // Trigger checking accessory on plugged
            link(plugStatus, Updatable {
                if (plugStatus.get() == PlugStatus.PLUGGED) {
                    clearHistory()
                    checkAccessoryOpen()
                }
            })

            // Trigger closing channel on unplugged
            link(plugStatus, Updatable {
                if (plugStatus.get() == PlugStatus.UNPLUGGED) {
                    endContact()
                }
            })

            // Cancel timer when we get a definite state on accessory
            link(accessoryStatus, Updatable {
                if (accessoryStatus.get() != AccessoryStatus.NONE) {
                    cancelAccessoryComingUpTimer()
                }
            })

            // Cancel dialing timer when contact status changes
            link(contactStatus, Updatable {
                if (contactStatus.get() !in setOf(ContactStatus.DIALING, ContactStatus.TIMED_OUT)) {
                    cancelDialingTimer()
                }
            })
        }
    }

    private fun toast(text: String, duration: Int = Toast.LENGTH_LONG) {
        genericHandler.post {
            Toast.makeText(this@MainActivity, text, duration).show()
        }
    }

    private fun toast(resid: Int, duration: Int = Toast.LENGTH_LONG) {
        genericHandler.post {
            Toast.makeText(this@MainActivity, resid, duration).show()
        }
    }

    private fun startAccessoryComingUpTimer() {
        accessoryComingUp.accept(true)
        accessoryComingUpTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    genericHandler.post {
                        cancelAccessoryComingUpTimer()
                    }
                }
            }, Timeout.AWAIT_ACCESSORY)
        }
    }

    private fun cancelAccessoryComingUpTimer() {
        accessoryComingUp.accept(false)
        accessoryComingUpTimer?.cancel()
        accessoryComingUpTimer = null
    }

    private fun checkAccessoryOpen() {
        if (accessoryStatus.get() in setOf(AccessoryStatus.OPENED, AccessoryStatus.CLOSED))
            return

        cancelAccessoryComingUpTimer()
        startAccessoryComingUpTimer()

        troubleshootStatus.accept(TroubleshootStatus.NONE)
        contactStatus.accept(ContactStatus.NONE)
        expectingEndOfContact.accept(false)

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (manager.accessoryList == null || manager.accessoryList.isEmpty()) {
            accessoryStatus.accept(AccessoryStatus.NONE)
            return
        }

        val acc = manager.accessoryList[0]

        if (!accessoryFilter.match(acc)) {
            accessoryStatus.accept(AccessoryStatus.UNRECOGNIZED)
            return
        }

        if (!Protocol.versionIsSupported(acc.version)) {
            accessoryStatus.accept(AccessoryStatus.VERSION_UNSUPPORTED)
            return
        }

        if (!manager.hasPermission(acc)) {
            accessoryStatus.accept(AccessoryStatus.PERMISSION_DENIED)
            return
        }

        val fd = manager.openAccessory(acc)

        if (fd == null) {
            accessoryStatus.accept(AccessoryStatus.OPEN_FAILED)
            return
        }

        accessoryOpened(Protocol.get(acc.version), fd)
    }

    private fun closeAccessoryChannel() {
        AccessoryChannel.opened?.close()
    }

    private fun startDialing() {
        contactStatus.accept(ContactStatus.DIALING)
        dialingTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    genericHandler.post {
                        contactStatus.accept(ContactStatus.TIMED_OUT)
                        cancelDialingTimer()
                    }
                }
            }, Timeout.AWAIT_DIALING)
        }
    }

    private fun cancelDialingTimer() {
        dialingTimer?.cancel()
        dialingTimer = null
    }

    private fun accessoryOpened(p: Protocol, fd: ParcelFileDescriptor) {
        accessoryStatus.accept(AccessoryStatus.OPENED)
        troubleshootStatus.accept(TroubleshootStatus.NONE)

        cancelDialingTimer()
        startDialing()

        closeAccessoryChannel()

        AccessoryChannel.open(p, fd).apply {
            addHandler(protocolHandler)
            start()
        }
    }

    private fun expectEndOfContact() {
        expectingEndOfContact.accept(true)
        pendingChangeInterrupted()
    }

    private fun endContact() {
        accessoryStatus.accept(AccessoryStatus.CLOSED)
        contactStatus.accept(ContactStatus.ENDED)
        expectingEndOfContact.accept(false)
        troubleshootStatus.accept(TroubleshootStatus.NONE)

        closeAccessoryChannel()
        pendingChangeInterrupted()
    }

    private fun clearHistory() {
        accessoryStatus.accept(AccessoryStatus.NONE)
        contactStatus.accept(ContactStatus.NONE)
        expectingEndOfContact.accept(false)
        troubleshootStatus.accept(TroubleshootStatus.NONE)

        closeAccessoryChannel()
        pendingChangeInterrupted(clearHostDisplay())
    }

    private fun clearHostDisplay(): HostMap? {
        hostReactor?.deactivate()
        hostReactor = null

        findViewById<ViewGroup>(R.id.Host).removeAllViews()

        val old = hostMap
        hostMap = null
        return old
    }

    private fun <T> pendingChangeInterrupted(m: Map<String, Source<T>>) =
            m.values.forEach { it.pendingChangeInterrupted() }

    private fun pendingChangeInterrupted(m: HostMap?) {
        m?.let {
            pendingChangeInterrupted(it.interfaces)
            pendingChangeInterrupted(it.services)
        }
    }

    private fun pendingChangeInterrupted() {
        pendingChangeInterrupted(hostMap)
    }

    private enum class Change {
        PENDING, CONFIRMED, TIMED_OUT, INTERRUPTED
    }

    private inner class Source<T>(obj: T) {
        val base = Repositories.mutableRepository(obj)
        val change = Repositories.mutableRepository(Change.CONFIRMED)
        private var expect: ((T) -> Boolean)? = null
        private var timer: Timer? = null

        fun setupTriggers(r: Reactor) {
            // On base change during pending, check for expected update.
            r.link(base, Updatable {
                if (change.get() == Change.PENDING
                        && expect?.invoke(base.get()) == true) {
                    change.accept(Change.CONFIRMED)
                    expect = null
                }
            })

            // On leaving pending, clear timer and expectation function.
            r.link(change, Updatable {
                if (change.get() != Change.PENDING) {
                    timer?.cancel()
                    timer = null
                    expect = null
                }
            })
        }

        fun changePending(exp: (T) -> Boolean, onTimeout: (T) -> Unit, millis: Long) {
            change.accept(Change.PENDING)
            expect = exp

            timer?.cancel()
            timer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        genericHandler.post {
                            if (change.get() == Change.PENDING) {
                                change.accept(Change.TIMED_OUT)
                                onTimeout(base.get())
                            }
                        }
                    }
                }, millis)
            }
        }

        fun pendingChangeInterrupted() {
            if (change.get() == Change.PENDING) {
                change.accept(Change.INTERRUPTED)
            }
        }
    }

    private data class HostMap(
            val interfaces: Map<String, Source<NetworkInterface>>,
            val services: Map<String, Source<Service>>)

    private var hostMap: HostMap? = null
    private var hostReactor: Reactor? = null

    private val genericHandler = Handler()

    private val protocolHandler = Handler { msg ->
        when (msg.what) {
//            Protocol.Event.EXCEPTION -> {
//                val x = msg.obj as Exception
//                Toast.makeText(this@MainActivity, x.toString(), Toast.LENGTH_LONG).show()
//            }
            Protocol.Event.NO_CONTACT -> {
                contactStatus.accept(ContactStatus.SILENT)
            }
            Protocol.Event.IN_CONTACT -> {
                contactStatus.accept(ContactStatus.COMMUNICATING)
            }
            Protocol.Event.END_CONTACT -> {
                endContact()
            }
            Protocol.Event.HOST_STATES -> {
                contactStatus.accept(ContactStatus.COMMUNICATING)

                val oldHostMap = clearHostDisplay()
                hostReactor = Reactor()

                val host = msg.obj as HostStates
                val layout = findViewById<ViewGroup>(R.id.Host)

                fun propagateSource(n: NetworkInterface, map: Map<String, Source<NetworkInterface>>?) =
                        Pair(n.name, map?.get(n.name)?.apply { base.accept(n) } ?: Source(n))

                fun propagateSource(s: Service, map: Map<String, Source<Service>>?) =
                        Pair(s.name, map?.get(s.name)?.apply { base.accept(s) } ?: Source(s))

                fun makeView(s: Source<NetworkInterface>): View {
                    val v = layoutInflater.inflate(R.layout.view_network_interface, layout, false)

                    if (s.base.get().wifi) {
                        fun startHotspotActivity() {
                            startActivityForResult(
                                    Intent(this@MainActivity, HotspotActivity::class.java).apply {
                                        putParam(HotspotActivity.Param(
                                                s.base.get().name,
                                                s.base.get().ssid))
                                    }, PICK_HOTSPOT_REQUEST)
                        }

                        v.findViewById<Button>(R.id.connect).setOnClickListener { view ->
                            startHotspotActivity()
                        }

                        class ConfirmDisconnectFragment : DialogFragment() {
                            override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
                                return AlertDialog.Builder(activity)
                                        .setMessage("Disconnect from ${s.base.get().ssid}?")
                                        .setTitle("Confirm")
                                        .setPositiveButton("OK", { dialog, which ->
                                            AccessoryChannel.opened?.send(Command("disconnect", s.base.get().ssid))
                                            s.changePending({ networkInterface ->
                                                networkInterface.ssid == ""
                                                        && networkInterface.ips.isEmpty()
                                            }, { _ ->
                                                toast("Unable to disconnect")
                                            }, Timeout.HOTSPOT_DISCONNECT)
                                        })
                                        .setNegativeButton("Cancel", { dialog, which ->
                                        })
                                        .create()
                            }
                        }

                        v.findViewById<View>(R.id.ipgroup).setOnClickListener { view ->
                            ConfirmDisconnectFragment().show(fragmentManager, "ConfirmDisconnectDialog")
                        }
                    }

                    hostReactor?.run{
                        s.setupTriggers(this)

                        link(s.base, textFollows(v, R.id.name, s.base) {
                            it.name
                        })

                        fun pickPrimaryIP(s: Set<InetAddress>): Set<InetAddress> =
                                s.sortedWith(Comparator { a,b ->
                                    when {
                                        (a is Inet4Address && b !is Inet4Address) -> -1
                                        (a !is Inet4Address && b is Inet4Address) -> 1
                                        else -> 0
                                    }
                                }).take(1).toSet()

                        val primarySet = Repositories.repositoryWithInitialValue(setOf<InetAddress>())
                                .observe(s.base)
                                .onUpdatesPerLoop()
                                .getFrom(s.base)
                                .thenTransform { pickPrimaryIP(it.ips) }
                                .compile()

                        val secondarySet = Repositories.repositoryWithInitialValue(setOf<InetAddress>())
                                .observe(s.base)
                                .onUpdatesPerLoop()
                                .getFrom(s.base)
                                .thenTransform { it.ips.minus(pickPrimaryIP(it.ips)) }
                                .compile()

                        val primaryExists = Repositories.repositoryWithInitialValue(false)
                                .observe(primarySet)
                                .onUpdatesPerLoop()
                                .getFrom(primarySet)
                                .thenTransform { !it.isEmpty() }
                                .compile()

                        val moreInfoComingUp = Repositories.mutableRepository(false)

                        fun secondaryTextString(addrs: Set<InetAddress>, more: Boolean) =
                                if (!more || !addrs.isEmpty()) {
                                    addrs.map { it.hostAddress }.sorted().joinToString("\n")
                                }
                                else {
                                    "more info coming up"
                                }

                        val secondaryText = Repositories.repositoryWithInitialValue("")
                                .observe(secondarySet, moreInfoComingUp)
                                .onUpdatesPerLoop()
                                .getFrom(secondarySet)
                                .mergeIn(moreInfoComingUp, pairer())
                                .thenTransform { secondaryTextString(it.first, it.second) }
                                .compile()

                        // Trigger "more info coming up" when primary IP becomes present
                        link(primaryExists, Updatable {
                            if (primaryExists.get()) {
                                moreInfoComingUp.accept(true)

                                Timer().apply {
                                    schedule(object : TimerTask() {
                                        override fun run() {
                                            genericHandler.post {
                                                moreInfoComingUp.accept(false)
                                            }
                                        }
                                    }, Timeout.AWAIT_MORE_INFO)
                                }
                            }
                        })

                        link(primarySet, textFollows(v, R.id.primary, primarySet) {
                            it.firstOrNull()?.hostAddress ?: "No IP address"
                        })

                        link(secondaryText, textFollows(v, R.id.secondary, secondaryText))

                        link(primarySet, Updatable {
                            val ip = primarySet.get().firstOrNull()
                            val tv = v.findViewById<TextView>(R.id.primary)

                            if (ip is Inet6Address) {
                                // shrink font to avoid overlap with name
                                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                // same size as ssid and secondary
                            }
                            else {
                                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
                                // same size as name
                            }
                        })

                        link(s.base, textFollows(v, R.id.ssid, s.base) {
                            it.ssid
                        })

                        // Make IP display clickable
                        link(s.base, isClickableFollows(v, R.id.ipgroup, s.base) {
                            it.wifi && !it.ips.isEmpty()
                        })

                        // Disable IP display and button when action is not allowed
                        link(actionIsAllowed, isEnabledFollows(v, R.id.ipgroup, actionIsAllowed))
                        link(actionIsAllowed, isEnabledFollows(v, R.id.connect, actionIsAllowed))

                        // Progress bar, Connect button, IP display:
                        // Only one should be visible
                        fun visibleId(n: NetworkInterface, c: Change): Int =
                                when {
                                    c == Change.PENDING ->       R.id.progress
                                    n.wifi && n.ips.isEmpty() -> R.id.connect
                                    else ->                      R.id.ipgroup
                                }

                        val show = Repositories.repositoryWithInitialValue(0)
                                .observe(s.base, s.change)
                                .onUpdatesPerLoop()
                                .getFrom(s.base)
                                .mergeIn(s.change, pairer())
                                .thenTransform { visibleId(it.first, it.second) }
                                .compile()

                        link(show, visibilityFollows(v, R.id.progress, show) {
                            if (it == R.id.progress) View.VISIBLE else View.INVISIBLE
                        })

                        link(show, visibilityFollows(v, R.id.connect, show) {
                            if (it == R.id.connect) View.VISIBLE else View.INVISIBLE
                        })

                        link(show, visibilityFollows(v, R.id.ipgroup, show) {
                            if (it == R.id.ipgroup) View.VISIBLE else View.INVISIBLE
                        })
                    }

                    return v
                }

                fun makeView(s: Source<Service>): View {
                    val v = layoutInflater.inflate(R.layout.view_service, layout, false)

                    // On click, enter pending.
                    // Do not use OnCheckedChange because it gets triggered when switch is set
                    // programmatically, causing it to send commands unwittingly.
                    v.findViewById<Switch>(R.id.running).setOnClickListener { view ->
                        val checked = (view as Switch).isChecked

                        val switch = if (checked) "turn on" else "turn off"
                        val action = if (checked) "start" else "stop"

                        s.changePending({ service ->
                            service.running == checked
                        }, { service ->
                            toast("Unable to $switch ${service.name}")
                        }, Timeout.SERVICE_SWITCH)

                        AccessoryChannel.opened?.send(Command(action, s.base.get().name))
                    }

                    hostReactor?.run {
                        s.setupTriggers(this)

                        // Display name
                        link(s.base, textFollows(v, R.id.name, s.base) {
                            it.name
                        })

                        fun isChecked(i: Service, c: Change) =
                                if (c == Change.PENDING) !i.running else i.running

                        val checked = Repositories.repositoryWithInitialValue(false)
                                .observe(s.base, s.change)
                                .onUpdatesPerLoop()
                                .getFrom(s.base)
                                .mergeIn(s.change, pairer())
                                .thenTransform { isChecked(it.first, it.second) }
                                .compile()

                        link(checked, isCheckedFollows(v, R.id.running, checked))

                        // Non-clickable when pending
                        link(s.change, isClickableFollows(v, R.id.running, s.change) {
                            it != Change.PENDING
                        })

                        // Disable when action is not allowed
                        link(actionIsAllowed, isEnabledFollows(v, R.id.running, actionIsAllowed))
                    }

                    return v
                }

                // Form (name, source) pair. Re-use existing source if there is one.
                // This has the effect of propagating existing states, including timer and
                // and expectation function, which are important if change is pending.
                val ifs = host.interfaces.map {
                    propagateSource(it, oldHostMap?.interfaces)
                }

                // Form new map: interface name -> source
                val interfaceMap = ifs.map { it.first to it.second }.toMap()

                // Sort by interface name, display.
                ifs.sortedBy { it.first }.forEach {
                    layout.addView(makeView(it.second))
                }

                // Form (name, source) pair. Re-use existing source if there is one.
                val scs = host.services.map {
                    propagateSource(it, oldHostMap?.services)
                }

                // Form new map: service name -> source
                val serviceMap = scs.map { it.first to it.second }.toMap()

                // Sort by service name, display.
                scs.sortedBy { it.first }.forEach {
                    layout.addView(makeView(it.second))
                }

                // Interrupt those changes not in new sets
                oldHostMap?.let {
                    pendingChangeInterrupted(it.interfaces.minus(interfaceMap.keys))
                    pendingChangeInterrupted(it.services.minus(serviceMap.keys))
                }

                hostMap = HostMap(interfaceMap, serviceMap)
                hostReactor?.activate()
            }
            Protocol.Event.NETWORK_INTERFACE -> {
                contactStatus.accept(ContactStatus.COMMUNICATING)

                val n = msg.obj as NetworkInterface
                hostMap?.interfaces?.get(n.name)?.base?.accept(n)
            }
            Protocol.Event.SERVICE -> {
                contactStatus.accept(ContactStatus.COMMUNICATING)

                val s = msg.obj as Service
                hostMap?.services?.get(s.name)?.base?.accept(s)
            }
        }
        true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            PICK_HOTSPOT_REQUEST -> {
                when (resultCode) {
                    HotspotActivity.RESULT_END_CONTACT -> {
                        endContact()
                        // Ensure onResume() does not see an opened channel.
                    }
                    Activity.RESULT_OK -> {
                        data?.let {
                            val resultData = it.getResultData(HotspotActivity.ResultData())

                            hostMap?.interfaces
                                    ?.get(resultData.interfaceName)
                                    ?.changePending({ networkInterface ->
                                        networkInterface.ssid == resultData.ssid
                                                && !networkInterface.ips.isEmpty()
                                    }, { networkInterface ->
                                        if (networkInterface.ssid == "") {
                                            toast("Unable to connect ${resultData.ssid}")
                                        }
                                        else {
                                            toast("Unable to connect ${resultData.ssid}. Use ${networkInterface.ssid} instead.")
                                        }
                                    }, Timeout.HOTSPOT_CONNECT)

                            AccessoryChannel.opened?.send(Command(
                                    "connect", resultData.ssid, resultData.passphrase))
                        }
                    }
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_STATE -> {
                    if (intent.getBooleanExtra("connected", false)) {
                        plugStatus.accept(PlugStatus.PLUGGED)
                    }
                    else if (plugStatus.get() != PlugStatus.NEVER) {
                        plugStatus.accept(PlugStatus.UNPLUGGED)
                    }
                    // PlugStatus.NEVER remains NEVER
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mainReactor.activate()
        hostReactor?.activate()
        registerReceiver(receiver,
                IntentFilter().apply {
                    addAction(ACTION_USB_STATE)
                }
        )
    }

    override fun onStop() {
        AccessoryChannel.opened?.run {
            send(Command("system" , "stop"))
            removeHandler(protocolHandler)
        }

        cancelAccessoryComingUpTimer()
        cancelDialingTimer()
        pendingChangeInterrupted()

        unregisterReceiver(receiver)
        hostReactor?.deactivate()
        mainReactor.deactivate()
        super.onStop()
    }

    override fun onDestroy() {
        endContact()  // close channel
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        AccessoryChannel.opened?.run {
            addHandler(protocolHandler)
            send(Command("system", "start"))
        } ?: run {
            // When app is not open, plugging USB invokes it.
            // In this case, onResume() is called before USB-connected event is received
            // by BroadcastReceiver. Opening stream here causes a read failure. Stream should
            // be opened after USB-connected event is received. This guard ensures stream is
            // not opened before that.
            if (plugStatus.get() == PlugStatus.PLUGGED) {
                // However, trying to open stream here is still necessary for the case where
                // plugging USB while server is not running, and server is started afterward.
                // In this case, USB-connected event is not received again; only onResume()
                // is called. This is the only place to open stream.
                checkAccessoryOpen()
            }
        }
    }
}
