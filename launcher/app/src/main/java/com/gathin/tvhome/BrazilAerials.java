package com.gathin.tvhome;

import android.content.Context;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** User-initiated HD/4K licensed WebM clips from Wikimedia Commons; no network traffic during Home playback. */
final class BrazilAerials {
    private static final int MAX_VIDEO_BYTES = 180 * 1024 * 1024;

    interface Progress { void onBytes(long received, long total); }

    static final class Clip {
        final String title, url, credit, source, region;
        final int page, width, height;
        final long size;

        Clip(String title, String url, String credit, String source, String region,
             int page, int width, int height, long size) {
            this.title = title;
            this.url = url;
            this.credit = credit;
            this.source = source;
            this.region = region;
            this.page = page;
            this.width = width;
            this.height = height;
            this.size = size;
        }
    }

    static List<Clip> browse(String region) throws Exception {
        String category = "Category:Drone videos from Brazil";
        if ("minas".equals(region)) category = "Category:Drone videos from Minas Gerais";
        String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=categorymembers&gcmtype=file&gcmlimit=50&gcmtitle="
                + java.net.URLEncoder.encode(category, "UTF-8")
                + "&prop=videoinfo&viprop=url%7Csize%7Cdimensions%7Cmime%7Cextmetadata%7Cderivatives";
        byte[] body = request(url, 2 * 1024 * 1024);
        JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
        JSONArray pages = root.optJSONObject("query") == null ? null : root.optJSONObject("query").optJSONArray("pages");
        List<Clip> result = new ArrayList<>();
        if (pages == null) return result;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.optJSONObject(i);
            JSONArray videos = page == null ? null : page.optJSONArray("videoinfo");
            JSONObject info = videos == null ? null : videos.optJSONObject(0);
            if (info == null || !"video/webm".equals(info.optString("mime"))) continue;
            JSONObject meta = info.optJSONObject("extmetadata");
            String license = meta == null ? "" : field(meta, "LicenseShortName");
            if (!(license.startsWith("CC BY") || license.startsWith("CC0")
                    || license.startsWith("Public domain") || license.startsWith("PD"))) continue;
            String artist = meta == null ? "" : Html.fromHtml(field(meta, "Artist"), Html.FROM_HTML_MODE_LEGACY).toString().trim();
            if (artist.length() > 150) artist = artist.substring(0, 150);
            String credit = (artist.isEmpty() ? "Wikimedia Commons contributor" : artist) + " · " + license;
            String original = info.optString("url", "");
            int width = info.optInt("width"), height = info.optInt("height");
            long size = info.optLong("size");
            String chosen = original;
            int chosenW = width, chosenH = height;
            // Prefer the actual 4K clip when it fits a bounded TV download; otherwise a 1080p transcode.
            if (size > MAX_VIDEO_BYTES || !safe(original)) {
                chosen = "";
                chosenW = 0;
                chosenH = 0;
                JSONArray derivatives = info.optJSONArray("derivatives");
                if (derivatives != null) for (int j = 0; j < derivatives.length(); j++) {
                    JSONObject d = derivatives.optJSONObject(j);
                    if (d == null || !d.optString("type", "").startsWith("video/webm")) continue;
                    int w = d.optInt("width"), h = d.optInt("height");
                    String candidate = d.optString("src", "");
                    if (w > chosenW && w <= 1920 && h >= 720 && safe(candidate)) {
                        chosen = candidate; chosenW = w; chosenH = h;
                    }
                }
                size = 0; // The API does not guarantee a transcode's size; capped during streaming.
            }
            if (!safe(chosen) || chosenW < 1280 || chosenH < 720) continue;
            String title = page.optString("title", "Drone video").replaceFirst("^File:", "")
                    .replaceFirst("(?i)\\.webm$", "").replace('_', ' ');
            String source = info.optString("descriptionurl", "");
            if (!source.startsWith("https://commons.wikimedia.org/wiki/File:")) continue;
            result.add(new Clip(title, chosen, credit, source, region,
                    page.optInt("pageid"), chosenW, chosenH, size));
        }
        return result;
    }

    static File save(Context context, Clip clip, Progress progress) throws Exception {
        if (!safe(clip.url)) throw new java.io.IOException("Unrecognised download host");
        File dir = context.getExternalFilesDir("aerials");
        if (dir == null) throw new java.io.IOException("Aerial folder unavailable");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create aerial folder");
        File target = new File(dir, "br_" + clip.page + ".webm");
        if (!target.exists()) {
            File partial = new File(dir, "br_" + clip.page + ".partial");
            HttpURLConnection connection = connect(clip.url);
            try {
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new java.io.IOException("HTTP " + connection.getResponseCode());
                if (connection.getContentLength() > MAX_VIDEO_BYTES) throw new java.io.IOException("Clip exceeds 180 MiB");
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count = 0, read, lastReport = 0;
                    int expected = connection.getContentLength();
                    while ((read = in.read(buffer)) != -1) {
                        count += read;
                        if (count > MAX_VIDEO_BYTES) throw new java.io.IOException("Clip exceeds 180 MiB");
                        out.write(buffer, 0, read);
                        if (progress != null && count - lastReport >= 2 * 1024 * 1024) {
                            lastReport = count;
                            progress.onBytes(count, expected);
                        }
                    }
                    out.getFD().sync();
                }
                try (java.io.FileInputStream test = new java.io.FileInputStream(partial)) {
                    if (test.read() != 0x1A || test.read() != 0x45 || test.read() != 0xDF || test.read() != 0xA3)
                        throw new java.io.IOException("File is not a WebM video");
                }
                if (!partial.renameTo(target)) throw new java.io.IOException("Cannot finish video download");
            } finally {
                connection.disconnect();
                partial.delete(); // Retain only complete, verified clips.
            }
        }
        File catalog = new File(dir, "aerials.json");
        JSONArray records = new JSONArray();
        if (catalog.isFile()) {
            if (catalog.length() >= 128 * 1024) throw new java.io.IOException("Existing aerials.json is too large to update safely");
            String previous = new String(java.nio.file.Files.readAllBytes(catalog.toPath()), StandardCharsets.UTF_8);
            if (previous.startsWith("\uFEFF")) previous = previous.substring(1); // PowerShell's UTF-8 BOM.
            try { records = new JSONArray(previous); }
            catch (Exception ignored) {
                throw new java.io.IOException("Existing aerials.json needs repair; original was left untouched");
            }
        }
        for (int i = 0; i < records.length(); i++) {
            JSONObject old = records.optJSONObject(i);
            if (old != null && target.getName().equals(old.optString("file"))) return target;
        }
        JSONObject entry = new JSONObject();
        entry.put("file", target.getName());
        entry.put("title", "Brazil · " + clip.title);
        entry.put("credit", clip.credit);
        entry.put("source", clip.source);
        records.put(entry);
        File tmp = new File(dir, "aerials.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(records.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(catalog)) throw new java.io.IOException("Cannot update aerial index");
        return target;
    }

    private static String field(JSONObject metadata, String key) {
        JSONObject f = metadata.optJSONObject(key);
        return f == null ? "" : f.optString("value", "");
    }

    private static boolean safe(String url) {
        try {
            URL u = new URL(url);
            return "https".equals(u.getProtocol())
                    && (u.getHost().equals("upload.wikimedia.org") || u.getHost().endsWith(".wikimedia.org"));
        } catch (Exception ignored) { return false; }
    }

    private static HttpURLConnection connect(String url) throws Exception {
        if (!safe(url)) throw new java.io.IOException("Unrecognised host");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(9000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "PersonalTVHome/3.3 (+https://github.com/igorcv88/CleanYourTV-LiquidGlass)");
        return c;
    }

    private static byte[] request(String url, int limit) throws Exception {
        HttpURLConnection c = connect(url);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > limit) throw new java.io.IOException("Video catalog too large");
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private BrazilAerials() { }
}
