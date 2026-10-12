#!/usr/bin/env python3
# Native window for Eclipse on Linux (GTK 3 + WebKitGTK).
import sys

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
try:
    gi.require_version("WebKit2", "4.1")
except ValueError:
    gi.require_version("WebKit2", "4.0")
from gi.repository import Gdk, GLib, Gtk, WebKit2  # noqa: E402

GLib.set_prgname("eclipse-stream")
GLib.set_application_name("Eclipse")

settings = Gtk.Settings.get_default()
if settings is not None:
    settings.set_property("gtk-application-prefer-dark-theme", False)

win = Gtk.Window(title="Eclipse")
win.set_default_size(960, 680)
win.set_size_request(720, 540)
win.set_position(Gtk.WindowPosition.CENTER)
win.set_icon_name("eclipse-stream")

view = WebKit2.WebView()
bg = Gdk.RGBA()
bg.parse("#f3f4f6")
view.set_background_color(bg)
s = view.get_settings()
s.set_enable_developer_extras(False)
s.set_hardware_acceleration_policy(WebKit2.HardwareAccelerationPolicy.ON_DEMAND)

# No right-click menu: it is an app, not a web page.
view.connect("context-menu", lambda *a: True)

view.load_uri(sys.argv[1])
win.add(view)
win.connect("destroy", Gtk.main_quit)
win.show_all()
print("eclipse-window-shown", flush=True)
Gtk.main()
