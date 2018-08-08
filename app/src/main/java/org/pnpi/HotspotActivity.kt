package org.pnpi

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.agera.Repositories
import com.google.android.agera.Updatable
import org.pnpi.protocol.Command
import org.pnpi.protocol.Hotspot
import org.pnpi.protocol.Protocol
import org.pnpi.protocol.ScanResult

fun wifiSignalGrade(dBm: Int): Int =
        when {
            dBm < -80 -> 1
            dBm < -73 -> 2
            dBm < -67 -> 3
            else      -> 4
        }

fun wifiSignalResourceId(open: Boolean, grade: Int): Int =
        when (grade) {
            1 -> when (open) {
                true -> R.drawable.ic_signal_wifi_1_bar_black_24dp
                false -> R.drawable.ic_signal_wifi_1_bar_lock_black_24dp
            }
            2 -> when (open) {
                true -> R.drawable.ic_signal_wifi_2_bar_black_24dp
                false -> R.drawable.ic_signal_wifi_2_bar_lock_black_24dp
            }
            3 -> when (open) {
                true -> R.drawable.ic_signal_wifi_3_bar_black_24dp
                false -> R.drawable.ic_signal_wifi_3_bar_lock_black_24dp
            }
            else -> when (open) {
                true -> R.drawable.ic_signal_wifi_4_bar_black_24dp
                false -> R.drawable.ic_signal_wifi_4_bar_lock_black_24dp
            }
        }

fun Intent.putParam(p: HotspotActivity.Param) {
    with (this) {
        putExtra("interfaceName", p.interfaceName)
        putExtra("connectedSsid", p.connectedSsid)
    }
}

// The "useless" function argument makes it easy to overload this function name
// by other activities using this pattern.
fun Intent.getParam(overload: HotspotActivity.Param): HotspotActivity.Param {
    return HotspotActivity.Param(
            this.getStringExtra("interfaceName"),
            this.getStringExtra("connectedSsid"))
}

fun Intent.putResultData(r: HotspotActivity.ResultData) {
    with (this) {
        putExtra("interfaceName", r.interfaceName)
        putExtra("ssid", r.ssid)
        putExtra("passphrase", r.passphrase)
    }
}

fun Intent.getResultData(overload: HotspotActivity.ResultData): HotspotActivity.ResultData {
    return HotspotActivity.ResultData(
            this.getStringExtra("interfaceName"),
            this.getStringExtra("ssid"),
            this.getStringExtra("passphrase"))
}

class HotspotActivity : AppCompatActivity() {
    companion object {
        const val RESULT_END_CONTACT = Activity.RESULT_FIRST_USER
    }

    data class Param(
            val interfaceName: String = "",
            val connectedSsid: String = "")

    data class ResultData(
            val interfaceName: String = "",
            val ssid: String = "",
            val passphrase: String = "")

    private val waiting = Repositories.mutableRepository(true)
    private val someHotspots = Repositories.mutableRepository(false)
    private val mainReactor = Reactor()

    private lateinit var param: Param

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hotspot)

        setSupportActionBar(findViewById(R.id.Toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        class PickCountryFragment : DialogFragment() {
            override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
                val cs = Available.countriesSorted
                return AlertDialog.Builder(activity)
                        .setTitle(R.string.pick_country_dialog_brief_title)
                        .setItems(cs.map { it.name }.toTypedArray(), { dialog, which ->
                            AccessoryChannel.opened?.send(Command("country", cs[which].code))
                        })
                        .create()
            }
        }

        findViewById<View>(R.id.Countries).setOnClickListener { view ->
            PickCountryFragment().show(fragmentManager, "PickCountryDialog")
        }

        findViewById<ListView>(R.id.Hotspots).setAdapter(hotspotAdapter)

        param = intent.getParam(Param())

        mainReactor.run {
            link(waiting, visibilityFollows(this@HotspotActivity, R.id.Progress, waiting) {
                if (it) View.VISIBLE else View.INVISIBLE
            })

            link(waiting, Updatable {
                supportActionBar?.title =
                        if (waiting.get()) "Scanning"
                        else               "Pick a Hotspot"
            })

            fun messageVisibilityValue(waiting: Boolean, someHotspots: Boolean) =
                    if (!waiting && !someHotspots) View.VISIBLE else View.INVISIBLE

            val messageVisibility = Repositories.repositoryWithInitialValue(View.INVISIBLE)
                    .observe(waiting, someHotspots)
                    .onUpdatesPerLoop()
                    .getFrom(waiting)
                    .mergeIn(someHotspots, pairer())
                    .thenTransform { messageVisibilityValue(it.first, it.second) }
                    .compile()

            link(messageVisibility, visibilityFollows(this@HotspotActivity, R.id.Message, messageVisibility))
        }
    }

    private val hotspotAdapter = HotspotAdapter()

    inner class HotspotAdapter : BaseAdapter() {
        var hotspots: List<Hotspot> = listOf()

        override fun getCount(): Int = hotspots.size

        override fun getItem(pos: Int): Hotspot = hotspots[pos]

        override fun getItemId(pos: Int): Long = hotspots[pos].hashCode().toLong()

        override fun getView(pos: Int, convertView: View?, container: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.view_hotspot, container, false)
            val h = getItem(pos)

            v.findViewById<TextView>(R.id.ssid).text = h.ssid

            if (h.ssid == param.connectedSsid) {
                v.isClickable = false
                v.findViewById<View>(R.id.connected).visibility = View.VISIBLE
                v.findViewById<View>(R.id.signal).visibility = View.INVISIBLE
            }
            else {
                v.isClickable = true
                v.findViewById<View>(R.id.connected).visibility = View.INVISIBLE
                v.findViewById<View>(R.id.signal).visibility = View.VISIBLE

                v.findViewById<ImageView>(R.id.signal).setImageResource(
                        wifiSignalResourceId(h.open, wifiSignalGrade(h.signal)))

                if (h.open) {
                    v.setOnClickListener { view ->
                        class OpenNetworkFragment : DialogFragment() {
                            override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
                                return AlertDialog.Builder(activity)
                                        .setMessage(R.string.open_network)
                                        .setTitle("Connect to ${h.ssid}")
                                        .setPositiveButton("Connect", { dialog, which ->
                                            setResult(Activity.RESULT_OK, Intent().apply {
                                                putResultData(ResultData(param.interfaceName, h.ssid, ""))
                                            })
                                            finish()
                                        })
                                        .setNegativeButton("Cancel", { dialog, which ->
                                        })
                                        .create()
                            }
                        }
                        OpenNetworkFragment().show(fragmentManager, "OpenNetworkDialog")
                    }
                }
                else {
                    v.setOnClickListener { view ->
                        class PassphraseFragment : DialogFragment() {
                            override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
                                val dialogView = activity.layoutInflater.inflate(R.layout.dialog_passphrase, null)
                                return AlertDialog.Builder(activity)
                                        .setView(dialogView)
                                        .setTitle("Connect to ${h.ssid}")
                                        .setPositiveButton("Connect", { dialog, which ->
                                            setResult(Activity.RESULT_OK, Intent().apply {
                                                putResultData(ResultData(
                                                        param.interfaceName,
                                                        h.ssid,
                                                        dialogView.findViewById<EditText>(R.id.passphrase)
                                                                .text.toString()))
                                            })
                                            finish()
                                        })
                                        .setNegativeButton("Cancel", { dialog, which ->
                                        })
                                        .create()
                            }
                        }
                        PassphraseFragment().show(fragmentManager, "PassphraseDialog")
                    }
                }
            }

            return v
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_STATE -> {
                    if (!intent.getBooleanExtra("connected", false)) {
                        setResult(RESULT_END_CONTACT)
                        finish()
                    }
                }
            }
        }
    }

    private val protocolHandler = Handler { msg ->
        when (msg.what) {
            Protocol.Event.NO_CONTACT -> {
            }
            Protocol.Event.IN_CONTACT -> {
            }
            Protocol.Event.END_CONTACT -> {
                setResult(RESULT_END_CONTACT)
                finish()
            }
            Protocol.Event.SCAN_RESULT -> {
                waiting.accept(false)

                val hs = (msg.obj as ScanResult).hotspots
                hotspotAdapter.hotspots = hs.sortedByDescending { it.signal }
                hotspotAdapter.notifyDataSetChanged()

                someHotspots.accept(!hs.isEmpty())
            }
        }
        true
    }

    override fun onStart() {
        super.onStart()

        AccessoryChannel.opened?.run {
            addHandler(protocolHandler)
            send(Command("scan", "start"))
        }

        mainReactor.activate()
        registerReceiver(receiver,
                IntentFilter().apply {
                    addAction(ACTION_USB_STATE)
                }
        )
    }

    override fun onStop() {
        AccessoryChannel.opened?.run {
            send(Command("scan", "stop"))
            removeHandler(protocolHandler)
        }

        unregisterReceiver(receiver)
        mainReactor.deactivate()
        super.onStop()
    }
}
