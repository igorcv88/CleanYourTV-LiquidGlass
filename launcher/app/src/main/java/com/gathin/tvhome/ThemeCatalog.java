package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The 16 official CleanYourTV 3.3 themes and the optional Liquid Glass layout. */
final class ThemeCatalog {
    static final String SELECTED_KEY = "selected_builtin_theme";
    private static final String TAG = "ThemeCatalog";
    private static final String BACKUP = "theme-picker-backup";
    private static final String[] IDS = {
            "aurora", "blocky", "cinematic", "default", "dussehra", "glass",
            "halloween", "minimal", "nature-calm", "red-rain", "retro", "ruska",
            "scenes", "six-columns", "storm", "sweden", "scenic-glass"
    };
    private static final String[] LABELS = {
            "Aurora", "Blocky", "Cinematic", "Default", "Dussehra", "Glass",
            "Halloween", "Minimal", "Nature Calm", "Red Rain", "Retro", "Ruska",
            "Scenes", "Six Columns", "Storm", "Sweden", "Liquid Glass"
    };

    static List<String> ids() {
        return Collections.unmodifiableList(Arrays.asList(IDS));
    }

    static String label(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return LABELS[i];
        return id;
    }

    static String selected(Context context) {
        String chosen = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(SELECTED_KEY, "");
        File dir = Config.dir(context);
        return chosen.isEmpty() && (dir == null || !new File(dir, Config.FILE_NAME).isFile())
                ? "default" : chosen;
    }

    private static String asset(String id, String file) {
        return id + "/" + file;
    }

    /** Apply a built-in theme using the same external files that tools/theme.sh manages.
     * This runs on a worker: even slow TV flash must never stall a D-pad frame.
     * The original user-provided theme is copied once to theme-picker-backup/ before overwriting it.
     */
    static void apply(Context context, String id) throws IOException {
        if (!ids().contains(id)) throw new IOException("Unknown theme: " + id);
        File dir = Config.dir(context);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs()))
            throw new IOException("Theme storage unavailable");

        final byte[] json;
        try (InputStream input = context.getAssets().open(asset(id, "config.json"))) {
            json = readSmall(input);
        }
        try {
            JSONObject parsed = new JSONObject(new String(json, StandardCharsets.UTF_8));
            if (!parsed.keys().hasNext()) throw new IOException("Empty theme configuration");
        } catch (org.json.JSONException e) {
            throw new IOException("Invalid theme configuration", e);
        }

        File targetConfig = new File(dir, Config.FILE_NAME);
        File targetWallpaper = new File(dir, Config.WALLPAPER_NAME);
        File targetCollection = new File(dir, "wallpapers");
        File stageConfig = new File(dir, ".theme-config-next");
        File stageWallpaper = new File(dir, ".theme-wallpaper-next");
        File stageCollection = new File(dir, ".theme-wallpapers-next");
        File previousConfig = new File(dir, ".theme-config-previous");
        File previousWallpaper = new File(dir, ".theme-wallpaper-previous");
        File previousCollection = new File(dir, ".theme-wallpapers-previous");
        File backups = new File(dir, BACKUP);
        if (!backups.isDirectory() && !backups.mkdirs()) throw new IOException("Cannot save theme backup");
        File originalConfig = new File(backups, Config.FILE_NAME);
        File originalWallpaper = new File(backups, Config.WALLPAPER_NAME);
        File originalCollection = new File(backups, "wallpapers");

        // Preserve the first external theme (possibly a user-made one). Do not modify tools, add-ons,
        // user wallpapers/ or other configuration files.
        if (targetConfig.isFile() && !originalConfig.exists()) copy(targetConfig, originalConfig);
        if (targetWallpaper.isFile() && !originalWallpaper.exists()) copy(targetWallpaper, originalWallpaper);
        if (targetCollection.isDirectory() && !originalCollection.exists()) copyCollection(targetCollection, originalCollection);
        try (OutputStream out = new FileOutputStream(stageConfig)) { out.write(json); }
        // Reuse a shipped photographic wallpaper rather than duplicate the photo inside the APK.
        // It comes with Nature Calm's CC BY-SA attribution, included in both theme folders.
        String wallpaperAsset = "scenic-glass".equals(id)
                ? "nature-calm/wallpapers/001.jpg" : asset(id, Config.WALLPAPER_NAME);
        boolean hasWallpaper = "scenic-glass".equals(id)
                || Arrays.asList(context.getAssets().list(id)).contains(Config.WALLPAPER_NAME);
        if (hasWallpaper) {
            try (InputStream in = context.getAssets().open(wallpaperAsset);
                 OutputStream out = new FileOutputStream(stageWallpaper)) { pump(in, out); }
        }
        // Multi-image themes (Scenes, Nature Calm, Storm, etc.) retain their exact rotation collection and
        // captions; neither ADB nor a network connection is needed after the APK has been installed.
        deleteCollection(stageCollection);
        String[] bundled = context.getAssets().list(asset(id, "wallpapers"));
        boolean hasCollection = bundled != null && bundled.length > 0;
        if (hasCollection) {
            if (!stageCollection.mkdir()) throw new IOException("Cannot stage wallpaper collection");
            for (String file : bundled) {
                if (!ThemeFiles.collectionImage(file) && !WallpaperCaptions.FILE_NAME.equals(file))
                    throw new IOException("Unexpected wallpaper filename: " + file);
                try (InputStream in = context.getAssets().open(asset(id, "wallpapers/" + file));
                     OutputStream out = new FileOutputStream(new File(stageCollection, file))) { pump(in, out); }
            }
        }
        // Do not leave the previous theme's wallpaper behind when this one has none.
        try {
            Files.deleteIfExists(previousConfig.toPath());
            Files.deleteIfExists(previousWallpaper.toPath());
            deleteCollection(previousCollection);
            ThemeSwap.apply(
                    new File[]{targetConfig, targetWallpaper, targetCollection},
                    new File[]{previousConfig, previousWallpaper, previousCollection},
                    new File[]{stageConfig, hasWallpaper ? stageWallpaper : null,
                            hasCollection ? stageCollection : null},
                    new ThemeSwap.Filesystem() {
                        @Override public void move(File from, File to) throws IOException {
                            ThemeCatalog.move(from, to);
                        }
                        @Override public void delete(File file) throws IOException {
                            if (file.isDirectory()) deleteCollection(file);
                            else Files.deleteIfExists(file.toPath());
                        }
                    });
        } catch (IOException failure) {
            for (Throwable suppressed : failure.getSuppressed()) {
                Log.e(TAG, "Theme rollback failed", suppressed);
            }
            throw failure;
        } finally {
            Files.deleteIfExists(stageConfig.toPath());
            Files.deleteIfExists(stageWallpaper.toPath());
            deleteCollection(stageCollection);
        }
        // Clean only temporary files created by our own previous selection; the initial backup remains.
        Files.deleteIfExists(previousConfig.toPath());
        Files.deleteIfExists(previousWallpaper.toPath());
        deleteCollection(previousCollection);
        try (OutputStream out = new FileOutputStream(new File(dir, "theme_revision"))) {
            out.write(Long.toHexString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
        }
        SharedPreferences p = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        p.edit().putString(SELECTED_KEY, id)
                .putLong(Config.SETTINGS_REVISION_KEY, System.currentTimeMillis()).commit();
    }

    private static byte[] readSmall(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > 64 * 1024) throw new IOException("Theme config too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            pump(in, out);
        }
    }

    private static void copyCollection(File source, File target) throws IOException {
        if (!target.mkdir()) throw new IOException("Cannot create collection backup");
        File[] contents = source.listFiles();
        if (contents == null) throw new IOException("Cannot read collection backup");
        for (File file : contents) {
            if (file.isFile() && (ThemeFiles.collectionImage(file.getName())
                    || WallpaperCaptions.FILE_NAME.equals(file.getName()))) {
                copy(file, new File(target, file.getName()));
            }
        }
    }

    /** Only temporary directories created by this picker. Never use this on a user's root/theme folder. */
    private static void deleteCollection(File staged) throws IOException {
        if (!staged.exists()) return;
        File[] contents = staged.listFiles();
        if (contents == null) throw new IOException("Cannot inspect staged collection");
        for (File file : contents) {
            if (!file.isFile() || !file.delete()) throw new IOException("Cannot clear staged image");
        }
        if (!staged.delete()) throw new IOException("Cannot clear staged collection");
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] bytes = new byte[16 * 1024];
        for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
    }

    private static void move(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private ThemeCatalog() {}
}
