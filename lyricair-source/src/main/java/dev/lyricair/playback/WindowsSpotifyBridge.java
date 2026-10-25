package dev.lyricair.playback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyricair.LyricAirClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.Base64;

public final class WindowsSpotifyBridge implements AutoCloseable {
    public record Artwork(String trackKey, byte[] png) {}
    private volatile TrackSnapshot snapshot;
    private volatile Artwork artwork;
    private volatile String status = "Starting Spotify connection...";
    private volatile boolean running;
    private volatile Process process;
    private volatile Thread worker;

    public TrackSnapshot snapshot() { return snapshot; }
    public Artwork artwork() { return artwork; }
    public String status() { return status; }

    public synchronized void start(Path directory) {
        close();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            running = true;
            status = "Connecting to Spotify desktop...";
            worker = new Thread(this::mprisLoop, "lyricair-spotify");
            worker.setDaemon(true);
            worker.start();
            return;
        }
        if (!os.startsWith("windows")) {
            status = "Spotify desktop sync needs Windows 10/11 or Linux. Demo still works.";
            return;
        }
        try {
            Files.createDirectories(directory);
            Path script = directory.resolve("spotify-media.ps1");
            try (InputStream input = WindowsSpotifyBridge.class.getResourceAsStream("/bridge/spotify-media.ps1")) {
                if (input == null) throw new IOException("Missing embedded Spotify bridge");
                Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
            }
            running = true;
            status = "Connecting to Spotify desktop...";
            worker = new Thread(() -> readLoop(script), "lyricair-spotify");
            worker.setDaemon(true);
            worker.start();
        } catch (IOException e) {
            status = "Could not start Spotify bridge. See Minecraft log.";
            LyricAirClient.LOGGER.warn("Could not extract Spotify bridge", e);
        }
    }

    private void readLoop(Path script) {
        Thread owner = Thread.currentThread();
        while (running && !owner.isInterrupted()) {
            Process child = null;
            try {
                String windows = System.getenv("SystemRoot");
                Path powershell = Path.of(windows == null ? "C:\\Windows" : windows,
                        "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
                child = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString(),
                        "-ParentPid", Long.toString(ProcessHandle.current().pid()))
                        .redirectErrorStream(true).start();
                synchronized (this) {
                    if (worker != owner || !running) { child.destroyForcibly(); return; }
                    process = child;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!owner.isInterrupted() && (line = reader.readLine()) != null) {
                        synchronized (this) {
                            if (worker == owner && running && !owner.isInterrupted()) accept(line);
                        }
                    }
                }
                if (!owner.isInterrupted()) {
                    snapshot = null;
                    status = "Spotify bridge stopped. Reconnecting...";
                }
            } catch (IOException e) {
                if (!owner.isInterrupted()) {
                    snapshot = null;
                    status = "Spotify connection failed. Retrying...";
                    LyricAirClient.LOGGER.warn("Spotify bridge process failed", e);
                }
            } finally {
                if (child != null) child.destroyForcibly();
            }
            try { Thread.sleep(5_000); }
            catch (InterruptedException e) { owner.interrupt(); }
        }
    }

    // Linux: poll the MPRIS D-Bus interface of Spotify through busctl (part of systemd, no extra packages).
    private static final String MPRIS_PLAYER = "org.mpris.MediaPlayer2.Player";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

    private void mprisLoop() {
        Thread owner = Thread.currentThread();
        String coverUrl = "";
        while (running && !owner.isInterrupted()) {
            try {
                JsonObject props = mprisProperties();
                synchronized (this) {
                    if (worker != owner || !running) return;
                    if (props == null) {
                        snapshot = null;
                        artwork = null;
                        status = "Open Spotify desktop and play a song.";
                    } else {
                        JsonObject meta = props.getAsJsonObject("Metadata").getAsJsonObject("data");
                        String title = mprisText(meta, "xesam:title");
                        if (title.isEmpty()) {
                            snapshot = null;
                            status = "Waiting for Spotify track information.";
                        } else {
                            boolean playing = "Playing".equals(props.getAsJsonObject("PlaybackStatus").get("data").getAsString());
                            double rate = props.has("Rate") ? props.getAsJsonObject("Rate").get("data").getAsDouble() : 1;
                            if (!Double.isFinite(rate) || rate <= 0 || rate > 4) rate = 1;
                            long durationMs = meta.has("mpris:length") ? Math.max(0, meta.getAsJsonObject("mpris:length").get("data").getAsLong() / 1000) : 0;
                            long positionMs = Math.max(0, props.getAsJsonObject("Position").get("data").getAsLong() / 1000);
                            snapshot = new TrackSnapshot(title, mprisText(meta, "xesam:artist"), mprisText(meta, "xesam:album"),
                                    durationMs, positionMs, playing, rate, System.nanoTime());
                            status = playing ? "Connected to Spotify" : "Spotify paused";
                            String url = mprisText(meta, "mpris:artUrl");
                            if (artwork != null && !artwork.trackKey().equals(snapshot.key())) artwork = null;
                            if (!url.equals(coverUrl) || (artwork == null && !url.isEmpty())) {
                                coverUrl = url;
                                byte[] image = fetchCover(url);
                                if (image != null) artwork = new Artwork(snapshot.key(), image);
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!owner.isInterrupted()) {
                    snapshot = null;
                    status = "Spotify connection failed. Retrying...";
                    LyricAirClient.LOGGER.debug("MPRIS poll failed", e);
                }
            } catch (InterruptedException e) { owner.interrupt(); return; }
            try { Thread.sleep(1_000); }
            catch (InterruptedException e) { owner.interrupt(); }
        }
    }

    /** Returns the Player properties of Spotify, or null when Spotify is not running. */
    private static JsonObject mprisProperties() throws IOException, InterruptedException {
        Process child = new ProcessBuilder("busctl", "--user", "--json=short", "call", "org.mpris.MediaPlayer2.spotify",
                "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties", "GetAll", "s", MPRIS_PLAYER)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String output = new String(child.getInputStream().readNBytes(1_000_000), StandardCharsets.UTF_8);
            if (!child.waitFor(5, TimeUnit.SECONDS)) return null;
            if (child.exitValue() != 0 || output.isBlank()) return null;
            return JsonParser.parseString(output).getAsJsonObject().getAsJsonArray("data").get(0).getAsJsonObject();
        } finally { child.destroyForcibly(); }
    }

    private static String mprisText(JsonObject meta, String key) {
        if (!meta.has(key)) return "";
        var data = meta.getAsJsonObject(key).get("data");
        if (data.isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (var item : data.getAsJsonArray()) joined.append(joined.isEmpty() ? "" : ", ").append(item.getAsString());
            return joined.toString();
        }
        return data.getAsString();
    }

    private static byte[] fetchCover(String url) {
        if (!url.startsWith("https://")) return null;
        try {
            HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 && response.body().length < 500_000 ? response.body() : null;
        } catch (IOException | RuntimeException e) {
            LyricAirClient.LOGGER.debug("Could not download cover art", e);
            return null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    private void accept(String line) {
        if (!line.startsWith("{") || line.length() > 1_000_000) return;
        try {
            JsonObject message = JsonParser.parseString(line).getAsJsonObject();
            if ("playback".equals(message.get("type").getAsString())) {
                long latencyMs = Math.clamp(System.currentTimeMillis() - message.get("sampledAtMs").getAsLong(), 0, 2_000);
                double rate = message.get("playbackRate").getAsDouble();
                if (!Double.isFinite(rate) || rate <= 0 || rate > 4) rate = 1;
                snapshot = new TrackSnapshot(string(message, "title"), string(message, "artist"), string(message, "album"),
                        Math.max(0, message.get("durationMs").getAsLong()), Math.max(0, message.get("positionMs").getAsLong()),
                        message.get("playing").getAsBoolean(), rate, System.nanoTime() - latencyMs * 1_000_000L);
                if (artwork != null && !artwork.trackKey().equals(snapshot.key())) artwork = null;
                String cover = string(message, "coverPng");
                if (!cover.isEmpty() && cover.length() < 500_000) {
                    byte[] png = Base64.getDecoder().decode(cover);
                    artwork = new Artwork(snapshot.key(), png);
                }
                status = snapshot.playing() ? "Connected to Spotify" : "Spotify paused";
            } else {
                snapshot = null;
                artwork = null;
                status = string(message, "message");
            }
        } catch (RuntimeException e) { LyricAirClient.LOGGER.debug("Ignoring malformed media bridge sample", e); }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    @Override public synchronized void close() {
        running = false;
        if (worker != null) worker.interrupt();
        if (process != null) process.destroyForcibly();
        worker = null;
        process = null;
        snapshot = null;
        artwork = null;
    }
}

