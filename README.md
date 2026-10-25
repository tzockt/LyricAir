# LyricAir - Spotify Lyrics Mod for Minecraft

<img width="1911" height="1005" alt="Screenshot 2026-10-13 011613" src="https://github.com/user-attachments/assets/59af4eca-56ee-4259-ae6d-d5d00d16f658" />

Bring your music into Minecraft with LyricAir, a client-side Fabric mod that displays synchronized Spotify lyrics as floating 3D text or a customizable on-screen overlay.

Watch lyrics appear, levitate, and fade throughout your world. Keep them anchored in place or let them follow your view, with adjustable colors, text size, distance, movement, and particle effects. The 2D music overlay shows album artwork, song title, artist, playback progress, and upcoming lyrics.

Customize everything through a polished settings menu featuring smooth Inter fonts, live previews, color presets, RGB sliders, and draggable overlay placement.

## Platform support

- **Windows 10/11**: Spotify desktop playback is read through the Windows media session.
- **Linux**: Spotify desktop playback is read through MPRIS (D-Bus) using `busctl`, which ships with systemd. Spotify has to be reachable on the session bus; this works with the native and the Flatpak version. Minecraft launchers running in a sandbox need access to the session bus.
