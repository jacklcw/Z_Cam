/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

public final class CarSpeakers {

    public static final String CABIN_MEDIA = "BUS00_MEDIA";
    public static final String OUTSIDE_NOTIFY = "BUS12_OUTER_NOTIFY";
    public static final String OUTSIDE_PLAYBACK = "BUS11_OUTER_SPEAKER_PLAYBACK";

    private CarSpeakers() {
    }

    public static AudioDeviceInfo cabin(Context context) {
        return find(context, CABIN_MEDIA);
    }

    static String exteriorAddress(String route) {
        return "bus11".equals(route) ? OUTSIDE_PLAYBACK : OUTSIDE_NOTIFY;
    }

    public static AudioDeviceInfo find(Context context, String address) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return null;
        }
        AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int[] types = new int[devices.length];
        String[] addresses = new String[devices.length];
        for (int i = 0; i < devices.length; i++) {
            types[i] = devices[i].getType();
            addresses[i] = devices[i].getAddress();
        }
        int index = indexOf(types, addresses, address);
        return index < 0 ? null : devices[index];
    }

    static int indexOf(int[] types, String[] addresses, String address) {
        if (address == null) {
            return -1;
        }
        for (int i = 0; i < types.length && i < addresses.length; i++) {
            if (types[i] == AudioDeviceInfo.TYPE_BUS && address.equals(addresses[i])) {
                return i;
            }
        }
        return -1;
    }
}
