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
import android.media.AudioTrack;

final class OutputProbe {

    private static volatile String result = "";
    private static boolean started;

    private OutputProbe() {
    }

    static synchronized void runOnce(Context context, String address) {
        if (started) {
            return;
        }
        started = true;
        result = "probing";
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> result = probe(app, address), "DriveSound-OutputProbe");
        thread.start();
    }

    static String result() {
        return result;
    }

    private static String probe(Context context, String address) {
        AudioDeviceInfo device = CarSpeakers.find(context, address);
        if (device == null) {
            return "no " + address;
        }
        int channels = 2;
        for (int c : device.getChannelCounts()) {
            channels = Math.max(channels, c);
        }
        int[] counts = channels > 2 ? new int[] {2, channels} : new int[] {2};
        StringBuilder sb = new StringBuilder();
        for (int encoding : new int[] {AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT}) {
            for (int n : counts) {
                sb.append(sb.length() == 0 ? "" : "; ")
                        .append(encoding == AudioFormat.ENCODING_PCM_FLOAT ? "float" : "pcm16").append('x').append(n).append(' ')
                        .append(tryOne(device, encoding, n));
            }
        }
        return sb.toString();
    }

    private static String tryOne(AudioDeviceInfo device, int encoding, int channels) {
        AudioTrack track = null;
        try {
            AudioFormat.Builder format = new AudioFormat.Builder().setEncoding(encoding).setSampleRate(SoundOutput.RATE);
            if (channels == 2) {
                format.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO);
            } else {
                format.setChannelIndexMask((1 << channels) - 1);
            }
            boolean isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT;
            int frames = SoundOutput.RATE * 40 / 1000;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(format.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(frames * channels * (isFloat ? 4 : 2))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return "not initialized";
            }
            track.setPreferredDevice(device);
            track.play();
            int samples = frames / 2 * channels;
            if (isFloat) {
                track.write(new float[samples], 0, samples, AudioTrack.WRITE_NON_BLOCKING);
            } else {
                track.write(new short[samples], 0, samples, AudioTrack.WRITE_NON_BLOCKING);
            }
            Thread.sleep(60);
            AudioDeviceInfo routed = track.getRoutedDevice();
            return mode(track.getPerformanceMode()) + ", buffer " + track.getBufferSizeInFrames() + " of "
                    + track.getBufferCapacityInFrames() + " frames" + (routed == null ? "" : ", on " + routed.getAddress());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (RuntimeException e) {
            return "failed (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                } catch (IllegalStateException ignored) {
                }
                track.release();
            }
        }
    }

    private static String mode(int mode) {
        switch (mode) {
            case AudioTrack.PERFORMANCE_MODE_LOW_LATENCY:
                return "low-latency";
            case AudioTrack.PERFORMANCE_MODE_POWER_SAVING:
                return "power-saving";
            default:
                return "normal";
        }
    }
}
