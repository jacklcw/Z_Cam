/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

final class Biquad {
    private float b0 = 1f;
    private float b1;
    private float b2;
    private float a1;
    private float a2;
    private float z1;
    private float z2;

    static Biquad peaking(float hz, float db, float q, float rate) {
        Biquad b = new Biquad();
        b.setPeaking(hz, db, q, rate);
        return b;
    }

    void setPeaking(float hz, float db, float q, float rate) {
        double a = Math.pow(10.0, db / 40.0);
        double w0 = 2.0 * Math.PI * hz / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha / a;
        set((1.0 + alpha * a) / a0, -2.0 * cos / a0, (1.0 - alpha * a) / a0, -2.0 * cos / a0, (1.0 - alpha / a) / a0);
    }

    static Biquad highpass(float hz, float q, float rate) {
        Biquad b = new Biquad();
        double w0 = 2.0 * Math.PI * hz / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        b.set((1.0 + cos) / 2.0 / a0, -(1.0 + cos) / a0, (1.0 + cos) / 2.0 / a0, -2.0 * cos / a0, (1.0 - alpha) / a0);
        return b;
    }

    static Biquad lowpass(float hz, float q, float rate) {
        Biquad b = new Biquad();
        b.setLowpass(hz, q, rate);
        return b;
    }

    static Biquad lowShelf(float hz, float db, float rate) {
        Biquad b = new Biquad();
        double a = Math.pow(10.0, db / 40.0);
        double w0 = 2.0 * Math.PI * hz / rate;
        double cos = Math.cos(w0);
        double sa = 2.0 * Math.sqrt(a) * Math.sin(w0) / 2.0 * Math.sqrt(2.0);
        double a0 = (a + 1.0) + (a - 1.0) * cos + sa;
        b.set(a * ((a + 1.0) - (a - 1.0) * cos + sa) / a0, 2.0 * a * ((a - 1.0) - (a + 1.0) * cos) / a0,
                a * ((a + 1.0) - (a - 1.0) * cos - sa) / a0, -2.0 * ((a - 1.0) + (a + 1.0) * cos) / a0,
                ((a + 1.0) + (a - 1.0) * cos - sa) / a0);
        return b;
    }

    static Biquad bandpass(float hz, float q, float rate) {
        Biquad b = new Biquad();
        b.setBandpass(hz, q, rate);
        return b;
    }

    void setBandpass(float hz, float q, float rate) {
        double w0 = 2.0 * Math.PI * Math.min(hz, rate * 0.45f) / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        set(alpha / a0, 0.0, -alpha / a0, -2.0 * cos / a0, (1.0 - alpha) / a0);
    }

    void setLowpass(float hz, float q, float rate) {
        double w0 = 2.0 * Math.PI * Math.min(hz, rate * 0.45f) / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        set((1.0 - cos) / 2.0 / a0, (1.0 - cos) / a0, (1.0 - cos) / 2.0 / a0, -2.0 * cos / a0, (1.0 - alpha) / a0);
    }

    private void set(double nb0, double nb1, double nb2, double na1, double na2) {
        b0 = (float) nb0;
        b1 = (float) nb1;
        b2 = (float) nb2;
        a1 = (float) na1;
        a2 = (float) na2;
    }

    float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
}
