package org.pnpi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.widget.TextView
import android.widget.Toast
import org.pnpi.protocol.Command
import org.pnpi.protocol.Protocol
import org.pnpi.protocol.fill
import org.pnpi.protocol.tell
import org.pnpi.protocol.v2.toBytes
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private class ProtocolRaw : Protocol() {
    override fun send(c: Command) {

        class Sender : AsyncTask<Command, Void, Boolean>() {
            override fun doInBackground(vararg cs: Command?): Boolean {
                try {
                    cs.forEach {
                        it?.let {
                            output.write(toBytes(it))
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

        Sender().execute(c)
    }

    override fun start() {

        fun readRawData(): ByteArray {
            val header = ByteArray(2)
            input.fill(header)

            val len = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getShort()

            if (len < 0)
                throw IllegalStateException("Message length negative")

            val body = ByteArray(len.toInt())
            input.fill(body)

            return body
        }

        Thread {
            try {
                send(Command("monitor", listOf("start")))

                while (true) {
                    val bytes = readRawData()
                    handlers.forEach {
                        it.tell(Protocol.Event.RAW, bytes)
                    }
                }
            } catch (x: Exception) {
                handlers.forEach {
                    it.tell(Protocol.Event.EXCEPTION, x)
                }
            } finally {
                handlers.forEach {
                    it.tell(Protocol.Event.END_CONTACT)
                }
            }
        }.start()
    }
}

class StreamTestActivity : AppCompatActivity() {

    private lateinit var accessoryFilter: AccessoryFilter
    private var accessoryChannel: AccessoryChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_test)
        setSupportActionBar(findViewById(R.id.Toolbar))

        accessoryFilter = AccessoryFilter(resources, R.xml.accessory_filter)
    }

    override fun onResume() {
        super.onResume()

        Toast.makeText(this, "Resuming", Toast.LENGTH_SHORT).show()

        val display = findViewById<TextView>(R.id.textView)
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (manager.accessoryList == null || manager.accessoryList.isEmpty()) {
            display.text = "No accessory found"
            return
        }

        val acc = manager.accessoryList[0]

        if (!accessoryFilter.match(acc)) {
            display.text = "Accessory is unrecognized"
            return
        }

//        if (!Protocol.versionIsSupported(acc.version)) {
//            display.text = "Accessory version is not supported"
//            return
//        }

        if (!manager.hasPermission(acc)) {
            display.text = "You did not grant permission"
            return
        }

        if (accessoryChannel == null) {
            val fd = manager.openAccessory(acc)
            if (fd == null) {
                display.text = "Cannot open accessory"
                return
            }

            accessoryChannel = AccessoryChannel.open(ProtocolRaw(), fd)

            display.text = "Streams opened"

            var n = 0
            val handler = Handler { msg ->
                when (msg.what) {
                    Protocol.Event.EXCEPTION -> {
                        val x = msg.obj as Exception
                        display.text = x.toString()
                    }
                    Protocol.Event.NO_CONTACT -> {
                    }
                    Protocol.Event.IN_CONTACT -> {
                    }
                    Protocol.Event.END_CONTACT -> {
                        accessoryChannel?.close()
                        accessoryChannel = null
                        // Must close. Otherwise, accessory mode switch may fail on re-plugged.

                        Toast.makeText(this@StreamTestActivity, "End contact", Toast.LENGTH_SHORT).show()
                    }
                    Protocol.Event.RAW -> {
                        val bs = msg.obj as ByteArray
                        n++
                        display.text = n.toString() + ". " + String(bs)
                    }
                }
                true
            }

            accessoryChannel?.let {
                it.addHandler(handler)
                it.start()
            }
        }
        else {
            accessoryChannel?.let {
                it.send(Command("monitor", "start"))
            }
        }
    }

    private val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_STATE -> {
                    Toast.makeText(this@StreamTestActivity,
                            if (intent.getBooleanExtra("connected", false)) "Plugged in" else "No plugged",
                            Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver,
                IntentFilter().apply {
                    addAction(ACTION_USB_STATE)})
    }

    override fun onStop() {
        Toast.makeText(this, "Stopping", Toast.LENGTH_SHORT).show()

// If I do:
//        accessoryChannel?.close()
//        accessoryChannel = null
// Server side: Stream never closed. Keeps sending. At some point, USB write blocks, buffer fills up, and
//              entire server blocks indefinitely.
// Client side: On re-opening app, stream opens successfully, but receives nothing. Proceed no further.

// If I do this:
//        usbChannel?.let {
//            it.protocol.send(Command("exit"), it.output)
//        }
// Server side: Terminates. Re-open accessory mode successfully, waiting for client to sent initial command.
// Client side: Stream never close, thread never stopped. On re-open, cannot open accessory.

// If I do this:
        accessoryChannel?.let {
            it.send(Command("monitor", listOf("stop")))
        }
// Remember: Should not re-init usbChannel in onResume(), if usbChannel is present.
// Client side: After pressing 'Home' to leave app and coming back in, continue update.
//              After pressing 'Back' to exit app and coming back in, cannot open accessory.

        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        Toast.makeText(this, "Destroying", Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }
}
