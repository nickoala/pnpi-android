package org.pnpi

import android.content.res.Resources
import android.hardware.usb.UsbAccessory
import org.xmlpull.v1.XmlPullParser

class AccessoryFilter(resources: Resources, id: Int) {

    data class AccessoryAttributes(val manufacturer: String, val model: String) {
        fun match(acc: UsbAccessory): Boolean {
            if (manufacturer != acc.manufacturer) return false
            if (model != acc.model) return false
            return true
        }
    }

    private val recognizedAccessorySet: Set<AccessoryAttributes>

    init {
        val set = mutableSetOf<AccessoryAttributes>()
        val parser = resources.getXml(id)
        try {
            var evt = parser.getEventType()
            while (evt != XmlPullParser.END_DOCUMENT) {
                if (evt == XmlPullParser.START_TAG && parser.getName() == "usb-accessory") {
                    var manufacturer = ""
                    var model = ""

                    for (i in 0 until parser.getAttributeCount()) {
                        when (parser.getAttributeName(i)) {
                            "manufacturer" -> manufacturer = parser.getAttributeValue(i)
                            "model" -> model = parser.getAttributeValue(i)
                        }
                    }
                    set.add(AccessoryAttributes(manufacturer, model))
                }
                evt = parser.next()
            }
        }
        finally {
            parser.close()
        }
        recognizedAccessorySet = set.toSet()
    }

    fun match(acc: UsbAccessory): Boolean =
            recognizedAccessorySet.count { it.match(acc) } > 0
}
