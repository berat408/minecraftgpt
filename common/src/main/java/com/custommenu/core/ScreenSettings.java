package com.custommenu.core;
public class ScreenSettings {
    public boolean enabled = false;      // varsayılan kapalı
    public boolean showMedia = false;
    public boolean playSound = false;
    public boolean loop = true;
    public boolean useVideoAudio = false; // videonun kendi sesini çal
    public String mediaPath = "";
    public String audioPath = "";
    public float volume = 1.0f;
    public String fit = "fit";           // fit | fill | stretch
    public float posX = 0.5f, posY = 0.5f;
}
