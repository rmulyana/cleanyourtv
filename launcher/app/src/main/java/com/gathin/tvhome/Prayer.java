package com.gathin.tvhome;

import java.time.LocalDate;

/**
 * Prayer times computed on the TV, no network: the praytimes.org sun formulas with the Indonesian
 * Kemenag parameters (Subuh at 20°, Isya at 18°, Shafi'i Ashar, elevation dip for Maghrib) and its
 * ihtiyath of 2 minutes, rounded up to the minute like the published tables.
 */
final class Prayer {
    static final String[] NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final double IHTIYATH_MIN = 2;
    /** Per-prayer correction in minutes on top of the ihtiyath, calibrated on Kemenag's 2026 Bandung tables (all within 1 min). */
    private static final double[] EXTRA_MIN = {0, 1, 0, 1, 0};
    private static final double TERBIT_EXTRA_MIN = -1;   // calibrated like EXTRA_MIN

    private Prayer() {}

    /** Index of Terbit (sunrise) after the five prayers in {@link #minutes}. Imsak is Subuh minus 10 minutes. */
    static final int TERBIT = 5;
    static final int IMSAK_BEFORE_SUBUH_MIN = 10;

    /** Minutes after local midnight for Subuh, Dzuhur, Ashar, Maghrib, Isya, then Terbit. */
    static int[] minutes(LocalDate date, double lat, double lon, double elevationM, double tzHours) {
        double d = date.toEpochDay() + 2440587.5 - 2451545.0 - lon / 360.0;   // days from J2000 at local noon
        double g = rad(357.529 + 0.98560028 * d);
        double q = 280.459 + 0.98564736 * d;
        double L = rad(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double e = rad(23.439 - 0.00000036 * d);
        double ra = fix(Math.toDegrees(Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L))) / 15.0, 24);
        double decl = Math.asin(Math.sin(e) * Math.sin(L));
        double eqt = q / 15.0 - ra;
        eqt -= 24 * Math.round(eqt / 24);
        double transit = 12 + tzHours - lon / 15.0 - eqt;
        double phi = rad(lat);
        double maghribAngle = 0.833 + 0.0347 * Math.sqrt(Math.max(0, elevationM));
        double asrAngle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.abs(phi - decl)))));
        double[] h = {
                transit - hourAngle(20, phi, decl),
                transit,
                transit + hourAngle(asrAngle, phi, decl),
                transit + hourAngle(maghribAngle, phi, decl),
                transit + hourAngle(18, phi, decl),
        };
        int[] out = new int[h.length + 1];
        for (int i = 0; i < h.length; i++) out[i] = (int) Math.ceil(h[i] * 60 + IHTIYATH_MIN + EXTRA_MIN[i] - 1e-9);
        // Terbit: sunrise with the same horizon dip, the ihtiyath taken off (an end time, not a start time).
        out[h.length] = (int) Math.floor((transit - hourAngle(maghribAngle, phi, decl)) * 60 - IHTIYATH_MIN + TERBIT_EXTRA_MIN + 1e-9);
        return out;
    }

    /** Hours between transit and the sun reaching {@code angle} degrees below the horizon. */
    private static double hourAngle(double angle, double phi, double decl) {
        double c = (-Math.sin(rad(angle)) - Math.sin(decl) * Math.sin(phi)) / (Math.cos(decl) * Math.cos(phi));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c)))) / 15.0;
    }

    private static double rad(double deg) {
        return Math.toRadians(deg);
    }

    private static double fix(double a, double b) {
        a = a - b * Math.floor(a / b);
        return a < 0 ? a + b : a;
    }
}
