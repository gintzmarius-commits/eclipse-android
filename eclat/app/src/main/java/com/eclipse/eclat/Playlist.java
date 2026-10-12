package com.eclipse.eclat;

import org.json.JSONArray;

/** What the player plays: the list chosen in the interface, and where it is. */
final class Playlist {
    static JSONArray items = new JSONArray();
    static int index;

    private Playlist() {
    }

    static synchronized void set(JSONArray list, int at) {
        items = list;
        index = Math.max(0, Math.min(at, list.length() - 1));
    }
}
