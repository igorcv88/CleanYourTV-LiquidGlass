package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An Apple aerial looping behind Home, as on tvOS. The video goes to a SurfaceView, which the TV composes
 * as a hardware overlay: no GPU work per frame for the launcher. Above it lie a cover that fades out once
 * the first frame is up (no flash of black or a frozen frame) and two scrims that keep the clock and the
 * icons readable; the lower one deepens while focus is in the app grid, where tvOS blurs the shelf away.
 *
 * The decoder is released whenever Home is not in front: TVs have one or two hardware decoders, and an app
 * opened from Home must never find them taken. The position is kept, so the flight picks up where it left.
 */
final class AerialBackdrop implements SurfaceHolder.Callback {
    private static final String TAG = "TvHome.Aerial";

    /** Scenic Glass Home is on (set by MainActivity before attach): lighter scrims, see the constructor. */
    static boolean scenic;

    /** Not final: cycling swaps in the next aerial. */
    private File file;
    private final ImageView cover;
    /**
     * Back from an app, the player is built again and its first frame takes a second or two: the cover then
     * shows the frame the flight stopped on (a small still, read on the worker when Home went away) instead
     * of black, and fades into the video at the same keyframe. Scenic only; null until the first pause.
     */
    private File posterFor;
    private int posterGeneration;
    /**
     * The still is copied from the screen as Home leaves (PixelCopy, the frost's own path), not decoded from
     * the file: by then the app being opened holds the TV's video decoder, and a decode of the file either
     * failed or finished too late, so Home still came back black. One reused bitmap, a quarter of the screen.
     */
    private Bitmap posterCopy;
    /** Release the player at the latest this long after Home leaves, if the copy has not answered. */
    private static final long POSTER_COPY_TIMEOUT_MS = 250L;
    /** How long the TV's video plane takes to show a frame after Android reports it rendered. */
    static final long VIDEO_PLANE_MS = 1200L;
    private final View gridDim;
    private final SharedPreferences prefs;
    private MediaPlayer player;
    private File preparedFor, preparedFile;
    private boolean preparedVideoOnly;
    private boolean preparing;
    private volatile boolean externalAudioPlaying;
    /** Appearance > "Lighter glass with music" (opt-in): a smaller frost while music plays over the aerial. */
    private static volatile boolean lightFrostWithMusic;

    static void setLightFrostWithMusic(boolean value) {
        lightFrostWithMusic = value;
    }
    /** Spotify can briefly report no active session at a track boundary; do not revive audio then. */
    private static final long AUDIO_END_GRACE_MS = 3500L;
    /** Read by the background remux cancellation callback, mutated only on the main thread. */
    private volatile int prepareGeneration;
    private SurfaceView video;
    private SurfaceHolder surface;
    private volatile boolean resumed;
    private boolean dimmed;
    /**
     * The glass follows the flight: a small frame (a sixth of the screen, already the frost's size) is copied
     * and blurred on a worker (Blur.Stream, two reused bitmaps), and the next copy starts as soon as one
     * is on screen, up to this rate. Never a live GPU blur, a UI-thread pixel loop or an allocation per sample.
     */
    // The rate adapts to the TV: it starts at about 15 a second (at 10 the frost visibly stepped behind the
    // video), and a TV that turns a sample around quickly climbs to 20, 30 or 40 a second (frameMsFor), so a
    // strong set follows the flight closely while a weak one keeps its CPU and RenderThread for the music.
    // Where CPU is short while music plays, Appearance > "Lighter glass with music" also shrinks the sample.
    private static final long GLASS_FRAME_MS = 66L;
    /** Smoothed time from copy to frost on screen, ms (-1 until the first sample), and the interval it sets. */
    private float tookAvg = -1f;
    private long frameMs = GLASS_FRAME_MS;
    /** After a refused copy (the surface between frames, a seek): try again a little later. */
    private static final long GLASS_RETRY_MS = 500L;
    private static final ExecutorService GLASS_WORKER = Executors.newSingleThreadExecutor();
    /** Video-only remux copies encoded samples once; it never decodes or re-encodes the wallpaper. */
    private static final ExecutorService VIDEO_WORKER = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The grace before "music ended" is running; repeated paused updates must not restart it. */
    private boolean musicEndPending;
    private final Runnable musicEnded = () -> {
        musicEndPending = false;
        externalAudioPlaying = false;
        // A remux may have been deferred while Spotify was playing; try again when the mixer is idle.
        if (!preparedVideoOnly) preparedFor = null;
        start();
    };
    /** A copy or its blur is under way: the next sample waits for it rather than queueing. */
    private boolean sampling;
    private boolean sampleFailureLogged;
    private long sampleStarted;
    private int statSamples;
    private long statTotalMs;
    private volatile long copyMs, blurMs;
    private Bitmap snapshot;
    private final Blur.Stream glassStream = new Blur.Stream();
    private final Runnable refreshGlass = this::sampleGlass;

    private static final String PREFS = "aerial_backdrop", CHOSEN = "file", POSITION = "position";

    /**
     * The aerials on the TV, as {file, title}: the titles from aerials.json next to the videos (tools put it
     * there with them), then any other .mov or .mp4 in the folder under its file name. For the Background menu.
     */
    static java.util.List<String[]> catalog(Context context) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        File dir = context.getExternalFilesDir("aerials");
        if (dir == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        File json = new File(dir, "aerials.json");
        if (json.isFile()) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(json.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);   // written by PowerShell, with a BOM
                org.json.JSONArray a = new org.json.JSONArray(text);
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.getJSONObject(i);
                    String f = o.optString("file");
                    if (f.isEmpty() || !new File(dir, f).canRead() || !seen.add(f)) continue;
                    out.add(new String[]{f, o.optString("title", f)});
                }
            } catch (Exception e) {
                Log.w(TAG, "cannot read aerials.json", e);
            }
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".mov") || n.endsWith(".mp4") || n.endsWith(".webm"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                if (seen.add(f.getName())) out.add(new String[]{f.getName(), f.getName().replaceAll("\\.\\w+$", "").replace('_', ' ')});
            }
        }
        return out;
    }

    /** The Background menu's "Cycle through all": each aerial plays once, then the next, round the catalog. */
    static final String CYCLE = "*cycle*";
    /** The clips explicitly downloaded for the Brazil collection, without mixing other aerial themes. */
    static final String CYCLE_BRAZIL = "*brazil*";
    /** While cycling: the aerial playing now, so Home picks up the rotation where it left it. */
    private static final String CYCLE_FILE = "cycle_file";

    /**
     * The aerial picked in the Background menu while it is still on the TV, CYCLE when cycling, else the
     * configured one.
     */
    static String chosen(Context context, String configured) {
        String f = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHOSEN, null);
        if (CYCLE.equals(f) || CYCLE_BRAZIL.equals(f)) return f;
        File dir = context.getExternalFilesDir("aerials");
        return f != null && dir != null && new File(dir, f).canRead() ? f : configured;
    }

    /** Remembers the Background menu's pick (a file, or CYCLE); the new flight starts from its beginning. */
    static void choose(Context context, String file) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHOSEN, file).remove(POSITION).apply();
    }

    /**
     * Null when the configured aerial is not on the TV: Home then keeps its wallpaper or plain colour. fileName
     * CYCLE starts the rotation at the aerial it last reached.
     */
    static AerialBackdrop attach(Context context, FrameLayout root, int index, String fileName, int coverColor) {
        if (fileName == null || fileName.isEmpty()) return null;
        File dir = context.getExternalFilesDir("aerials");
        if (dir == null) return null;
        boolean cycle = CYCLE.equals(fileName) || CYCLE_BRAZIL.equals(fileName);
        boolean brazilOnly = CYCLE_BRAZIL.equals(fileName);
        if (cycle) {
            java.util.List<String[]> all = catalog(context);
            if (brazilOnly) all.removeIf(v -> !v[0].startsWith("br_"));
            if (all.isEmpty()) return null;
            String at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CYCLE_FILE, null);
            fileName = at != null && (!brazilOnly || at.startsWith("br_")) && new File(dir, at).canRead()
                    ? at : all.get(0)[0];
        }
        File f = new File(dir, fileName);
        if (!f.canRead()) return null;
        return new AerialBackdrop(context, root, index, f, coverColor, cycle, brazilOnly);
    }

    private final Context context;
    private final boolean cycle;
    private final boolean brazilOnly;

    /** Cycling: the aerial after the one playing, in catalog order, wrapping round. */
    private File nextInCycle() {
        java.util.List<String[]> all = catalog(context);
        if (brazilOnly) all.removeIf(v -> !v[0].startsWith("br_"));
        if (all.isEmpty()) return file;
        int i = 0;
        for (int k = 0; k < all.size(); k++) if (all.get(k)[0].equals(file.getName())) i = k + 1;
        return new File(file.getParentFile(), all.get(i % all.size())[0]);
    }

    /** One aerial finished while cycling: fade to black, swap in the next, and the first frame fades it back in. */
    private void advance() {
        File next = nextInCycle();
        Log.i(TAG, "cycle: " + file.getName() + " -> " + next.getName());
        release();
        prefs.edit().putString(CYCLE_FILE, next.getName()).remove(POSITION).apply();
        cover.animate().cancel();
        clearPoster();
        cover.animate().setStartDelay(0).alpha(1f).setDuration(300).withEndAction(() -> {
            file = next;
            preparedFor = null;
            preparedFile = null;
            preparedVideoOnly = false;
            // Ignore a remux for the previous aerial if cycling advanced while it was preparing.
            prepareGeneration++;
            preparing = false;
            start();
        }).start();
    }

    private AerialBackdrop(Context context, FrameLayout root, int index, File file, int coverColor,
                           boolean cycle, boolean brazilOnly) {
        this.file = file;
        this.context = context.getApplicationContext();
        this.cycle = cycle;
        this.brazilOnly = brazilOnly;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        video = new SurfaceView(context);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        video.getHolder().addCallback(this);
        root.addView(video, index, full);

        // Top: a light shade under the clock and pills. Bottom: deeper, under the dock and the grid.
        // Scenic Glass keeps the film open: a faint shade for the header text and a light one under the dock,
        // never the deep bottom sheet the classic grid needed.
        View top = new View(context);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                scenic ? new int[]{0x4D000000, 0x12000000, 0x00000000} : new int[]{0x8C000000, 0x26000000, 0x00000000}));
        root.addView(top, index + 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (context.getResources().getDisplayMetrics().heightPixels * (scenic ? 0.28f : 0.42f))));
        View bottom = new View(context);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                scenic ? new int[]{0x40000000, 0x10000000, 0x00000000} : new int[]{0xB3000000, 0x40000000, 0x00000000}));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (context.getResources().getDisplayMetrics().heightPixels * (scenic ? 0.36f : 0.62f)));
        lp.gravity = android.view.Gravity.BOTTOM;
        root.addView(bottom, index + 2, lp);
        for (View v : new View[]{top, bottom}) {
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            v.forceHasOverlappingRendering(false);
        }

        gridDim = new View(context);
        gridDim.setBackgroundColor(scenic ? 0x6605070C : 0x9E05070C);
        gridDim.setAlpha(0f);
        gridDim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(gridDim, index + 3, full);

        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);   // as the video: scaled to fill, cropped
        cover.setBackgroundColor(coverColor);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(cover, index + 4, full);
    }

    /** Focus in the grid below the dock: dim the aerial so the icons own the screen, as tvOS does. */
    void setGridFocused(boolean inGrid) {
        if (inGrid == dimmed) return;
        dimmed = inGrid;
        gridDim.animate().cancel();
        gridDim.animate().alpha(inGrid ? 1f : 0f).setDuration(inGrid ? 380 : 300).start();
    }

    void resume() {
        resumed = true;
        if (posterFor != null && !posterFor.equals(file)) clearPoster();   // another flight now: black, as before
        if (player != null) {
            // Back within the copy's moment: the player was never released and its frames still flow, so no
            // first-frame event will lift the cover. Lift it now and restart the frost.
            cover.animate().cancel();
            cover.animate().setStartDelay(0).alpha(0f).setDuration(200).start();
            if (scenic) main.postDelayed(refreshGlass, 100);
            return;
        }
        start();
    }

    /** A silent aerial may run beside Spotify; an unstrippable one must yield its audio track. */
    void setExternalAudioPlaying(boolean playing) {
        if (!playing) {
            // Sessions report often while paused: restarting the grace on each report kept it from ever
            // ending, so the aerial believed music played forever.
            if (externalAudioPlaying && !musicEndPending) {
                musicEndPending = true;
                main.postDelayed(musicEnded, AUDIO_END_GRACE_MS);
            }
            return;
        }
        main.removeCallbacks(musicEnded);
        musicEndPending = false;
        if (externalAudioPlaying) return;
        externalAudioPlaying = true;
        if (!ScenicAudioPolicy.allowAerial(true, preparedVideoOnly)) {
            release();
            clearPoster();
            cover.animate().cancel();
            cover.setAlpha(1f);
        } else {
            start();
        }
    }

    /** A video with its original audio track cannot coexist with Spotify on this TV. */
    boolean needsArtworkFallback() {
        return externalAudioPlaying && preparedFor != null && preparedFor.equals(file) && !preparedVideoOnly;
    }

    void pause() {
        resumed = false;
        cancelPreparation();
        final MediaPlayer playing = player;
        if (playing == null || !copyPoster(playing)) {
            release();
            if (playing != null) capturePoster();   // no copy possible: decode the still from the file
        }
        cover.animate().cancel();
        cover.setAlpha(1f);
    }

    /**
     * Copies the flight's current frame for the cover, then releases the player (the copy needs its last
     * frame on the surface). False when no copy can start; the caller then releases at once.
     */
    private boolean copyPoster(MediaPlayer playing) {
        if (!scenic || surface == null || video == null || video.getWidth() == 0 || video.getHeight() == 0) return false;
        int w = Math.max(32, video.getWidth() / 2), h = Math.max(18, video.getHeight() / 2);
        if (posterCopy == null || posterCopy.getWidth() != w || posterCopy.getHeight() != h) {
            try {
                posterCopy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                return false;
            }
        }
        final Bitmap target = posterCopy;
        final File flight = file;
        final int generation = ++posterGeneration;
        final Runnable releaseLate = () -> { if (!resumed && player == playing) release(); };
        try {
            PixelCopy.request(video, target, result -> {
                main.removeCallbacks(releaseLate);
                releaseLate.run();
                if (generation != posterGeneration) return;
                if (result == PixelCopy.SUCCESS && flight.equals(file)) {
                    posterFor = flight;
                    cover.setImageDrawable(null);   // the same bitmap again: make the view take its new pixels
                    cover.setImageBitmap(target);
                } else if (!resumed) {
                    capturePoster();
                }
            }, main);
        } catch (IllegalArgumentException e) {
            return false;
        }
        main.postDelayed(releaseLate, POSTER_COPY_TIMEOUT_MS);
        return true;
    }

    /** Stop disk activity as soon as Home goes away; restart only after a new resume/surface. */
    private void cancelPreparation() {
        if (!preparing) return;
        prepareGeneration++;
        preparing = false;
    }

    private void start() {
        if (!resumed || surface == null || player != null) return;
        // The classic upstream Home keeps its original playback and startup cost.
        if (!scenic && (preparedFor == null || !preparedFor.equals(file))) {
            preparedFor = file;
            preparedFile = file;
            preparedVideoOnly = false;
        }
        if (preparedFor != null && preparedFor.equals(file)
                && (preparedFile == null || !preparedFile.canRead())) preparedFor = null;
        if (preparedFor == null || !preparedFor.equals(file)) {
            if (preparing) return;
            preparing = true;
            final int generation = ++prepareGeneration;
            final File source = file;
            File cacheRoot = context.getExternalCacheDir();
            final File cache = cacheRoot == null ? null : new File(cacheRoot, "aerial-video-only");
            VIDEO_WORKER.execute(() -> {
                AerialVideoOnly.Result result = AerialVideoOnly.prepare(source, cache,
                        () -> externalAudioPlaying || !resumed || generation != prepareGeneration);
                main.post(() -> {
                    if (generation != prepareGeneration || !file.equals(source)) return;
                    preparing = false;
                    preparedFor = source;
                    preparedFile = result.file;
                    preparedVideoOnly = result.videoOnly;
                    Log.i(TAG, "aerial prepared; verified video-only=" + preparedVideoOnly);
                    start();
                });
            });
            return;
        }
        // If a container cannot be remuxed or storage is low, keep Spotify's audio pipeline unshared.
        if (!ScenicAudioPolicy.allowAerial(externalAudioPlaying, preparedVideoOnly)) return;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(preparedFile.getPath());
            mp.setDisplay(surface);
            // Harmless for video-only media; never rely on this to suppress an actual audio track.
            mp.setVolume(0f, 0f);
            // One aerial loops; cycling plays each through once and then moves on.
            mp.setLooping(!cycle);
            if (cycle) mp.setOnCompletionListener(p -> {
                if (player == p) advance();
            });
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                int at = resumeAtMs(prefs.getInt(POSITION, 0), p.getDuration());
                if (at > 0) p.seekTo(at, MediaPlayer.SEEK_CLOSEST_SYNC);
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == p) {
                    // The TV shows video on its own hardware plane, which comes up about a second after
                    // Android reports the first frame (screen captures already show the video; the panel
                    // is still black). Lifting the cover at the report showed that black between the still
                    // and the flight, so the cover waits for the plane first.
                    cover.animate().cancel();
                    cover.animate().alpha(0f).setStartDelay(VIDEO_PLANE_MS)
                            .setDuration(cover.getDrawable() != null ? 500 : 300).start();
                    if (scenic) main.postDelayed(refreshGlass, 300);
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "playback error " + what + "/" + extra + " for " + file.getName());
                release();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + file, e);
            release();
        }
    }

    /** The frame the flight stopped on, for the cover while Home comes back: see {@link #cover}. */
    private void capturePoster() {
        final File source = preparedFile != null && preparedFile.canRead() ? preparedFile : file;
        if (!scenic || source == null || android.os.Build.VERSION.SDK_INT < 27) return;
        final File flight = file;
        final int atMs = prefs.getInt(POSITION, 0);
        final int generation = ++posterGeneration;
        GLASS_WORKER.execute(() -> {
            Bitmap still = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(source.getPath());
                // As start() resumes: from the beginning when the flight was in its last two seconds.
                long durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                long atUs = resumeAtMs(atMs, durationMs) * 1000L;
                // The keyframe the player resumes from (SEEK_CLOSEST_SYNC), small: a second on screen at most.
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
                    params.setPreferredConfig(Bitmap.Config.RGB_565);
                    still = r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 540, params);
                } else {
                    still = r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 540);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "no resume still for " + flight.getName() + ": " + e);
            } finally {
                try { r.release(); } catch (Exception ignored) { /* already released */ }
            }
            final Bitmap frame = still;
            main.post(() -> {
                if (generation != posterGeneration || frame == null || !flight.equals(file)) return;
                posterFor = flight;
                cover.setImageBitmap(frame);
            });
        });
    }

    /** Where playback resumes: the saved position, or the start when it was within two seconds of the end. */
    static int resumeAtMs(int savedMs, long durationMs) {
        return savedMs > 0 && savedMs < durationMs - 2000L ? savedMs : 0;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void clearPoster() {
        posterGeneration++;
        posterFor = null;
        cover.setImageDrawable(null);
    }

    private void release() {
        main.removeCallbacks(refreshGlass);
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            if (mp.isPlaying()) prefs.edit().putInt("position", mp.getCurrentPosition()).apply();
        } catch (IllegalStateException ignored) {
            // still preparing: keep the previous position
        }
        mp.release();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder;
        start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        cancelPreparation();
        release();
    }

    /** Gives every glass surface a scene-aligned, frosted copy of the aerial behind it. PixelCopy is
     * asynchronous and the blur runs on GLASS_WORKER: nothing heavy on the UI thread, no capture per frame. */
    private void sampleGlass() {
        if (!scenic || !resumed || player == null || surface == null) return;
        main.removeCallbacks(refreshGlass);
        if (sampling || video.getWidth() == 0 || video.getHeight() == 0) {
            main.postDelayed(refreshGlass, GLASS_RETRY_MS);
            return;
        }
        // Twice the frost's size: Blur.Stream halves it with filtering (see Blur.MOTION_SIDE).
        final boolean music = lightFrostWithMusic && externalAudioPlaying;
        final int side = music ? Blur.MUSIC_SIDE : Blur.MOTION_SIDE;
        final int radius = music ? Blur.MUSIC_RADIUS : Blur.MOTION_RADIUS;
        float k = 2f * side / (float) Math.max(video.getWidth(), video.getHeight());
        int w = Math.max(32, Math.round(video.getWidth() * k)), h = Math.max(18, Math.round(video.getHeight() * k));
        if (snapshot == null || snapshot.getWidth() != w || snapshot.getHeight() != h) {
            try {
                snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError ignored) { return; }
        }
        final Bitmap target = snapshot;
        sampling = true;
        sampleStarted = android.os.SystemClock.uptimeMillis();
        try {
            PixelCopy.request(video, target, result -> {
                if (result != PixelCopy.SUCCESS || !resumed) {
                    if (result != PixelCopy.SUCCESS && !sampleFailureLogged) {
                        sampleFailureLogged = true;
                        Log.w(TAG, "glass sample: PixelCopy result " + result);
                    }
                    sampling = false;
                    if (resumed) main.postDelayed(refreshGlass, GLASS_RETRY_MS);
                    return;
                }
                copyMs += android.os.SystemClock.uptimeMillis() - sampleStarted;
                GLASS_WORKER.execute(() -> {
                    long b0 = android.os.SystemClock.uptimeMillis();
                    Bitmap frost = glassStream.blur(target, side, radius);
                    blurMs += android.os.SystemClock.uptimeMillis() - b0;
                    float lum = glassStream.luminance();
                    int[] hues = glassStream.palette();
                    Bitmap wide = glassStream.wide();
                    main.post(() -> glassSampled(frost, lum, hues, wide));
                });
            }, main);
        } catch (IllegalArgumentException e) {
            sampling = false;
            main.postDelayed(refreshGlass, GLASS_RETRY_MS);
        }
    }

    /** On the main thread: show the new frost and start the next copy, keeping to GLASS_FRAME_MS. */
    private void glassSampled(Bitmap frost, float lum, int[] hues, Bitmap wide) {
        sampling = false;
        if (!resumed || !scenic || player == null) return;
        Scene.setStill(frost, lum, hues, wide);
        long took = android.os.SystemClock.uptimeMillis() - sampleStarted;
        statTotalMs += took;
        tookAvg = tookAvg < 0f ? took : tookAvg * 0.9f + took * 0.1f;
        frameMs = frameMsFor(tookAvg);
        // One line per Home start: what the frost costs on this TV, for bug reports. Never repeated.
        if (statSamples >= 0 && ++statSamples == 120) {
            Log.i(TAG, "glass samples: " + statSamples + " at " + (statTotalMs / statSamples) + " ms each, copy "
                    + (copyMs / statSamples) + " ms, blur " + (blurMs / statSamples) + " ms, every " + frameMs + " ms");
            copyMs = 0;
            blurMs = 0;
            statSamples = -1;
        }
        main.removeCallbacks(refreshGlass);
        main.postDelayed(refreshGlass, Math.max(8L, frameMs - took));
    }

    /**
     * The frost's interval for a TV that takes {@code tookMs} (smoothed) from copy to frost on screen: a sample
     * may take at most about half its interval, so the copies, which hold the RenderThread, never crowd the
     * frames Home itself needs. A slow or busy TV (music decoding beside it) falls back to GLASS_FRAME_MS.
     */
    static long frameMsFor(float tookMs) {
        if (tookMs <= 12f) return 25L;
        if (tookMs <= 16f) return 33L;
        if (tookMs <= 25f) return 50L;
        return GLASS_FRAME_MS;
    }
}
