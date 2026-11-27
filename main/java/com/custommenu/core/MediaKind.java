package com.custommenu.core;

import java.util.Locale;

public enum MediaKind {
    IMAGE,
    GIF,
    VIDEO,
    AUDIO,
    UNKNOWN;

    public static MediaKind detect(String name) {
        if (name == null) return UNKNOWN;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) return IMAGE;
        if (n.endsWith(".gif")) return GIF;
        if (n.endsWith(".mp4")) return VIDEO;
        if (n.endsWith(".mp3")) return AUDIO;
        return UNKNOWN;
    }
}
