package com.custommenu.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MediaScanner {
    public static List<File> scan(File folder) {
        List<File> out = new ArrayList<>();
        if (folder == null || !folder.isDirectory()) return out;
        File[] files = folder.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && MediaKind.detect(f.getName()) != MediaKind.UNKNOWN) {
                out.add(f);
            }
        }
        out.sort(Comparator.comparing(File::getName));
        return out;
    }
}
