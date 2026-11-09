/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import java.util.Locale;

/**
 *
 * <ul>
 * </ul>
 */
final class SoundOutput {

    static final int RATE = 48_000;
    static final int START_BUFFER_MS = 30;
    static final int GROW_MS = 10;
    static final int MAX_BUFFER_MS = 120;
    /** Z_Cam: a new output (style change, restart) starts at the buffer size the last one had to grow to (capped), not back at the minimum. */
    static final int CARRY_MAX_MS = 80;
    private static volatile int learnedFrames;
    static final int FRAME_BYTES = 8;

    private final AudioTrack track;
    private final String address;
    private final int capacityFrames;
    private final AudioTimestamp stamp = new AudioTimestamp();
    private int bufferFrames;
    private int underruns;
    private long written;
    private volatile float latencyMs = -1f;
    private volatile String grew = "";
    private volatile int lastError;
    private final long openedNs = System.nanoTime();
    /** How long this output has been open, for the log (a track that dies every few seconds shows up here). */
    long ageMs() {
        return (System.nanoTime() - openedNs) / 1_000_000L;
    }
    /** The negative code of the last failed write (-6 = dead object), for the log. */
    int lastError() {
        return lastError;
    }

    private SoundOutput(AudioTrack track, String address, int capacityFrames) {
        this.track = track;
        this.address = address;
        this.capacityFrames = capacityFrames;
    }

    static SoundOutput open(Context context, String address) {
        return open(context, address, START_BUFFER_MS);
    }

    /** [startMs]: the buffer to start with (both speakers at once start bigger: one thread feeds two buses that drift against each other). */
    static SoundOutput open(Context context, String address, int startMs) {
        return open(context, address, startMs, AudioAttributes.USAGE_MEDIA);
    }

    /**
     * [usage]: Z_Cam gives the exterior stream a different usage than the cabin one. Two tracks of the same usage share one routing group in
     * the car's audio policy, and (log 2026-11-05) opening or restarting one invalidated the other, so they kept killing each other.
     */
    static SoundOutput open(Context context, String address, int startMs, int usage) {
        return open(context, address, startMs, usage, true);
    }

    /** [play] false: build and route the track but do not start it ({@link #start()} later), so two outputs can be opened first and started together. */
    static SoundOutput open(Context context, String address, int startMs, int usage, boolean play) {
        AudioDeviceInfo device = CarSpeakers.find(context, address);
        if (device == null) {
            throw new IllegalStateException("no output " + address);
        }
        int maxFrames = RATE * MAX_BUFFER_MS / 1000;
        int minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        int bytes = Math.max(minBytes > 0 ? minBytes : 0, maxFrames * FRAME_BYTES);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(usage == AudioAttributes.USAGE_MEDIA ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(bytes)
                    .build();
        } catch (RuntimeException e) {
            throw new IllegalStateException("AudioTrack build failed: " + e, e);
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AudioTrack not initialized");
        }
        if (!track.setPreferredDevice(device)) {
            track.release();
            throw new IllegalStateException("setPreferredDevice refused " + address);
        }
        SoundOutput out = new SoundOutput(track, address, bytes / FRAME_BYTES);
        out.bufferFrames = track.setBufferSizeInFrames(Math.min(out.capacityFrames,
                Math.max(RATE * startMs / 1000, Math.min(learnedFrames, RATE * CARRY_MAX_MS / 1000))));
        if (play) {
            out.start();
        }
        return out;
    }

    /** Start a track that was opened with play = false. */
    void start() {
        try {
            track.play();
        } catch (IllegalStateException e) {
            track.release();
            throw new IllegalStateException("play failed: " + e, e);
        }
    }

    /** Floats of the current block already accepted by {@link #offer}. */
    private int pending;

    /**
     * Z_Cam: non-blocking write, so two outputs (cabin + exterior, two buses on their own clocks) never wait on each other: one blocked in write()
     * let the other one run dry (buffers grew to 100 ms and still crackled). Call again with the same block until it returns 1.
     *
     * @return 1 the whole block is in, 0 not yet, -1 failed (the track is dead; see {@link #lastError()})
     */
    int offer(float[] lr, int frames) {
        int total = 2 * frames;
        int done;
        try {
            done = track.write(lr, pending, total - pending, AudioTrack.WRITE_NON_BLOCKING);
        } catch (IllegalStateException e) {
            pending = 0;
            return -1;
        }
        if (done < 0) {
            lastError = done;
            pending = 0;
            return -1;
        }
        pending += done;
        written += done / 2;
        if (pending >= total) {
            pending = 0;
            return 1;
        }
        return 0;
    }

    boolean write(float[] lr, int frames) {
        int done;
        try {
            done = track.write(lr, 0, 2 * frames, AudioTrack.WRITE_BLOCKING);
        } catch (IllegalStateException e) {
            return false;   // track invalidated (e.g. after a car alert): let the caller reopen it
        }
        if (done < 0) {
            lastError = done;
            return false;
        }
        written += done / 2;
        return true;
    }

    boolean watch() {
        boolean grown = false;
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try { track.play(); } catch (IllegalStateException ignored) { /* write will fail and trigger a reopen */ }
        }
        int count = track.getUnderrunCount();
        if (count > underruns) {
            int fresh = count - underruns;
            underruns = count;
            int limit = Math.min(capacityFrames, RATE * MAX_BUFFER_MS / 1000);
            if (bufferFrames < limit) {
                int before = bufferFrames;
                bufferFrames = track.setBufferSizeInFrames(Math.min(limit, bufferFrames + RATE * GROW_MS * (fresh >= 2 ? 2 : 1) / 1000));
                if (bufferFrames > learnedFrames) learnedFrames = bufferFrames;
                grown = bufferFrames > before;
                grew = before + "->" + bufferFrames;
            }
        }
        if (track.getTimestamp(stamp)) {
            long ageNs = System.nanoTime() - stamp.nanoTime;
            latencyMs = (written - stamp.framePosition) * 1000f / RATE - ageNs / 1_000_000f;
        }
        return grown;
    }

    void close() {
        try {
            track.pause();
            track.flush();
            track.stop();
        } catch (IllegalStateException ignored) {
        }
        track.release();
    }

    String address() {
        return address;
    }

    int underruns() {
        return underruns;
    }

    String describe() {
        return String.format(Locale.US, "%s %d Hz stereo float, %s, buffer %d frames (%.0f ms, cap %d), underruns %d%s, latency %s",
                address, RATE, performance(), bufferFrames, bufferFrames * 1000f / RATE, capacityFrames, underruns,
                grew.isEmpty() ? "" : " (grew " + grew + ")",
                latencyMs < 0f ? "?" : String.format(Locale.US, "%.0f ms", latencyMs));
    }

    private String performance() {
        try {
            switch (track.getPerformanceMode()) {
                case AudioTrack.PERFORMANCE_MODE_LOW_LATENCY:
                    return "low-latency";
                case AudioTrack.PERFORMANCE_MODE_POWER_SAVING:
                    return "power-saving";
                default:
                    return "normal (no low-latency)";
            }
        } catch (IllegalStateException e) {
            return "released";
        }
    }

    float latencyMs() {
        return latencyMs;
    }
}
