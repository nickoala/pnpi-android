# Plug n Pi

<a href='http://play.google.com/store/apps/details?id=org.pnpi&pcampaignid=MKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img align='right' width='333' alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png'/></a>

1. USB-connect a Raspberry Pi and a Mobile Phone

2. The Phone's app allows you to:
   - connect Raspberry Pi to a WiFi hotspot
   - look up Raspberry Pi's IP addresses
   - enable Raspberry Pi's SSH and VNC server

   In short, everything you need to gain network access to your Raspberry Pi,
   except the terminal emulator and VNC client.

3. Use your favorite terminal emulator (PuTTY?) or VNC client (RealVNC?)
   to enter the Pi

---------------------------------------------

Plug n Pi's software consists of two parts:

1. On the Pi side, there is the [USB server](https://github.com/nickoala/pnpi).

2. On the Phone side is this Android app, the USB client. It has been tested on
   the following devices:

   |          Model         | Android version | API level |
   |:----------------------:|:---------------:|:---------:|
   | Samsung Galaxy Express |           4.1.2 |        16 |
   | ASUS Fonepad           |           4.1.2 |        16 |
   | Samsung Galaxy S4      |           5.0.1 |        21 |
   | Samsung Tab A          |           7.1.1 |        25 |

See the **[Plug n Pi Server](https://github.com/nickoala/pnpi)** page for more
info.
