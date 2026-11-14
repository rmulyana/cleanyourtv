package com.gathin.tvhome;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;

/**
 * The Islamic calendar panel and the verse panel (config "schedule": {"islamic": true}): the next sunnah fasts,
 * the next two Islamic holidays, and a verse in Arabic with its translation that changes daily. All offline;
 * Hijri dates follow {@link SchedulePanel#hijri} including "hijri_adjust".
 */
final class IslamicDay {
    private IslamicDay() {
    }

    /** {day, month} in the Hijri calendar, or null outside Java's Umm al-Qura table. */
    static int[] hijri(LocalDate date, int adjustDays) {
        try {
            HijrahDate h = HijrahDate.from(date.plusDays(adjustDays));
            return new int[]{h.get(ChronoField.DAY_OF_MONTH), h.get(ChronoField.MONTH_OF_YEAR)};
        } catch (Exception e) {
            return null;
        }
    }

    /** Ramadan (already obligatory), Idul Fitri, Idul Adha and the tasyrik days: no sunnah fast. */
    static boolean noSunnahFast(LocalDate date, int adjustDays) {
        int[] h = hijri(date, adjustDays);
        if (h == null) return true;
        return h[1] == 9 || (h[1] == 10 && h[0] == 1) || (h[1] == 12 && h[0] >= 10 && h[0] <= 13);
    }

    /** The next Monday or Thursday fast from today (today included), or null. */
    static LocalDate nextMondayThursday(LocalDate today, int adjustDays) {
        for (int i = 0; i < 40; i++) {
            LocalDate d = today.plusDays(i);
            if (mondayThursday(d, adjustDays)) return d;
        }
        return null;
    }

    /** First and last day of the next (or current) Ayyamul Bidh, 13–15 of the Hijri month; null if none found. */
    static LocalDate[] nextAyyamulBidh(LocalDate today, int adjustDays) {
        LocalDate start = null, end = null;
        for (int i = 0; i < 70; i++) {
            LocalDate d = today.plusDays(i);
            if (ayyamulBidh(d, adjustDays)) {
                if (start == null) start = d;
                end = d;
            } else if (start != null) {
                break;
            }
        }
        return start == null ? null : new LocalDate[]{start, end};
    }

    static final class Holiday {
        final String name;
        final LocalDate date;

        Holiday(String name, LocalDate date) {
            this.name = name;
            this.date = date;
        }
    }

    private static final int[][] ISLAMIC_DAYS = {{1, 1}, {3, 12}, {7, 27}, {9, 1}, {10, 1}, {12, 10}};   // {month, day}
    private static final String[] ISLAMIC_NAMES = {"Tahun Baru Islam", "Maulid Nabi", "Isra Mi'raj",
            "Awal Ramadan", "Idul Fitri", "Idul Adha"};
    /** The next {@code count} Islamic holidays from today (today included); fewer near the end of the calendar table. */
    static java.util.List<Holiday> nextIslamicHolidays(LocalDate today, int adjustDays, int count) {
        java.util.List<Holiday> found = new java.util.ArrayList<>();
        for (int i = 0; i < 400 && found.size() < count; i++) {
            LocalDate d = today.plusDays(i);
            if (hijri(d, adjustDays) == null) break;
            String name = holiday(d, adjustDays);
            if (name != null) found.add(new Holiday(name, d));
        }
        return found;
    }

    /** "hari ini", "besok", or "Sen, 16 Nov". */
    static String when(LocalDate d, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, d);
        if (days == 0) return "hari ini";
        if (days == 1) return "besok";
        return SchedulePanel.DAY.format(d).replace(".", "").replace(",  ", ", ");
    }

    /** "Isra Mi'raj · Sab, 16 Jan · 63 hari lagi"; today and tomorrow say so instead of counting. */
    static String holidayLine(Holiday h, LocalDate today) {
        if (h == null) return "";
        long days = ChronoUnit.DAYS.between(today, h.date);
        String w = when(h.date, today);
        return h.name + " · " + (days <= 1 ? w : w + " · " + days + " hari lagi");
    }

    /** "Sen, 23 – Rab, 25 Nov" style range for Ayyamul Bidh, collapsing the month when shared. */
    static String rangeLine(LocalDate[] r, LocalDate today) {
        if (r == null) return "";
        if (!r[0].isAfter(today)) return "hari ini s/d " + when(r[1], today);
        String a = when(r[0], today), b = when(r[1], today);
        return a.equals(b) ? a : a + " – " + b;
    }

    /**
     * Short whole verses: Arabic (Tanzil "quran-simple") and the Kemenag translation, both from api.alquran.cloud,
     * the reference, and the surah and verse as "SSSAAA" for the recitation file ({@link #audioUrl}).
     */
    static final String[][] VERSES = {
            {"فَإِنَّ مَعَ الْعُسْرِ يُسْرًا",
                    "Karena sesungguhnya sesudah kesulitan itu ada kemudahan.", "QS. Al-Insyirah: 5", "094005"},
            {"فَإِذَا فَرَغْتَ فَانْصَبْ",
                    "Maka apabila kamu telah selesai (dari sesuatu urusan), kerjakanlah dengan sungguh-sungguh (urusan) yang lain.", "QS. Al-Insyirah: 7", "094007"},
            {"وَإِلَىٰ رَبِّكَ فَارْغَبْ",
                    "Dan hanya kepada Tuhanmulah hendaknya kamu berharap.", "QS. Al-Insyirah: 8", "094008"},
            {"قُلْ هُوَ اللَّهُ أَحَدٌ",
                    "Katakanlah: \"Dialah Allah, Yang Maha Esa.\"", "QS. Al-Ikhlas: 1", "112001"},
            {"اللَّهُ الصَّمَدُ",
                    "Allah adalah Tuhan yang bergantung kepada-Nya segala sesuatu.", "QS. Al-Ikhlas: 2", "112002"},
            {"لَمْ يَلِدْ وَلَمْ يُولَدْ",
                    "Dia tiada beranak dan tidak pula diperanakkan.", "QS. Al-Ikhlas: 3", "112003"},
            {"فَبِأَيِّ آلَاءِ رَبِّكُمَا تُكَذِّبَانِ",
                    "Maka nikmat Tuhan kamu yang manakah yang kamu dustakan?", "QS. Ar-Rahman: 13", "055013"},
            {"وَلَلْآخِرَةُ خَيْرٌ لَكَ مِنَ الْأُولَىٰ",
                    "Dan sesungguhnya hari kemudian itu lebih baik bagimu daripada yang sekarang (permulaan).", "QS. Ad-Duha: 4", "093004"},
            {"وَلَسَوْفَ يُعْطِيكَ رَبُّكَ فَتَرْضَىٰ",
                    "Dan kelak Tuhanmu pasti memberikan karunia-Nya kepadamu, lalu (hati) kamu menjadi puas.", "QS. Ad-Duha: 5", "093005"},
            {"وَأَمَّا بِنِعْمَةِ رَبِّكَ فَحَدِّثْ",
                    "Dan terhadap nikmat Tuhanmu, maka hendaklah kamu siarkan.", "QS. Ad-Duha: 11", "093011"},
            {"الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ",
                    "Segala puji bagi Allah, Tuhan semesta alam.", "QS. Al-Fatihah: 2", "001002"},
            {"إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ",
                    "Hanya Engkaulah yang kami sembah, dan hanya kepada Engkaulah kami meminta pertolongan.", "QS. Al-Fatihah: 5", "001005"},
            {"اهْدِنَا الصِّرَاطَ الْمُسْتَقِيمَ",
                    "Tunjukilah kami jalan yang lurus.", "QS. Al-Fatihah: 6", "001006"},
            {"إِنَّا أَعْطَيْنَاكَ الْكَوْثَرَ",
                    "Sesungguhnya Kami telah memberikan kepadamu nikmat yang banyak.", "QS. Al-Kausar: 1", "108001"},
            {"سَبِّحِ اسْمَ رَبِّكَ الْأَعْلَى",
                    "Sucikanlah nama Tuhanmu Yang Maha Tinggi.", "QS. Al-A'la: 1", "087001"},
            {"قَدْ أَفْلَحَ مَنْ تَزَكَّىٰ",
                    "Sesungguhnya beruntunglah orang yang membersihkan diri (dengan beriman).", "QS. Al-A'la: 14", "087014"},
            {"وَالْآخِرَةُ خَيْرٌ وَأَبْقَىٰ",
                    "Sedang kehidupan akhirat adalah lebih baik dan lebih kekal.", "QS. Al-A'la: 17", "087017"},
            {"اقْرَأْ بِاسْمِ رَبِّكَ الَّذِي خَلَقَ",
                    "Bacalah dengan (menyebut) nama Tuhanmu Yang menciptakan.", "QS. Al-'Alaq: 1", "096001"},
            {"عَلَّمَ الْإِنْسَانَ مَا لَمْ يَعْلَمْ",
                    "Dia mengajar kepada manusia apa yang tidak diketahuinya.", "QS. Al-'Alaq: 5", "096005"},
            {"فَمَنْ يَعْمَلْ مِثْقَالَ ذَرَّةٍ خَيْرًا يَرَهُ",
                    "Barangsiapa yang mengerjakan kebaikan seberat dzarrahpun, niscaya dia akan melihat (balasan)nya.", "QS. Az-Zalzalah: 7", "099007"},
            {"وَمَنْ يَعْمَلْ مِثْقَالَ ذَرَّةٍ شَرًّا يَرَهُ",
                    "Dan barangsiapa yang mengerjakan kejahatan sebesar dzarrahpun, niscaya dia akan melihat (balasan)nya pula.", "QS. Az-Zalzalah: 8", "099008"},
            {"يَا أَيُّهَا الَّذِينَ آمَنُوا اذْكُرُوا اللَّهَ ذِكْرًا كَثِيرًا",
                    "Hai orang-orang yang beriman, berzikirlah (dengan menyebut nama) Allah, zikir yang sebanyak-banyaknya.", "QS. Al-Ahzab: 41", "033041"},
            {"وَسَبِّحُوهُ بُكْرَةً وَأَصِيلًا",
                    "Dan bertasbihlah kepada-Nya diwaktu pagi dan petang.", "QS. Al-Ahzab: 42", "033042"},
            {"وَمَا خَلَقْتُ الْجِنَّ وَالْإِنْسَ إِلَّا لِيَعْبُدُونِ",
                    "Dan aku tidak menciptakan jin dan manusia melainkan supaya mereka mengabdi kepada-Ku.", "QS. Az-Zariyat: 56", "051056"},
            {"فَاصْبِرْ صَبْرًا جَمِيلًا",
                    "Maka bersabarlah kamu dengan sabar yang baik.", "QS. Al-Ma'arij: 5", "070005"},
            {"وَلِرَبِّكَ فَاصْبِرْ",
                    "Dan untuk (memenuhi perintah) Tuhanmu, bersabarlah.", "QS. Al-Muddassir: 7", "074007"},
            {"يَا أَيَّتُهَا النَّفْسُ الْمُطْمَئِنَّةُ",
                    "Hai jiwa yang tenang.", "QS. Al-Fajr: 27", "089027"},
            {"ارْجِعِي إِلَىٰ رَبِّكِ رَاضِيَةً مَرْضِيَّةً",
                    "Kembalilah kepada Tuhanmu dengan hati yang puas lagi diridhai-Nya.", "QS. Al-Fajr: 28", "089028"},
            {"قَدْ أَفْلَحَ الْمُؤْمِنُونَ",
                    "Sesungguhnya beruntunglah orang-orang yang beriman.", "QS. Al-Mu'minun: 1", "023001"},
            {"وَاذْكُرِ اسْمَ رَبِّكَ وَتَبَتَّلْ إِلَيْهِ تَبْتِيلًا",
                    "Sebutlah nama Tuhanmu, dan beribadatlah kepada-Nya dengan penuh ketekunan.", "QS. Al-Muzzammil: 8", "073008"},
    };

    /** Today's verse: one per day, cycling through the list. */
    static String[] verse(LocalDate today) {
        return VERSES[verseIndex(today)];
    }

    static int verseIndex(LocalDate today) {
        return (int) Math.floorMod(today.toEpochDay(), (long) VERSES.length);
    }

    /** The 114 surah names (quran.com, Indonesian spelling); SURAH[n - 1] is surah n. */
    static final String[] SURAH = {
            "Al-Fatihah", "Al-Baqarah", "Ali 'Imran", "An-Nisa", "Al-Ma'idah", "Al-An'am", "Al-A'raf", "Al-Anfal",
            "At-Tawbah", "Yunus", "Hud", "Yusuf", "Ar-Ra'd", "Ibrahim", "Al-Hijr", "An-Nahl", "Al-Isra", "Al-Kahf",
            "Maryam", "Taha", "Al-Anbya", "Al-Hajj", "Al-Mu'minun", "An-Nur", "Al-Furqan", "Ash-Shu'ara", "An-Naml",
            "Al-Qasas", "Al-'Ankabut", "Ar-Rum", "Luqman", "As-Sajdah", "Al-Ahzab", "Saba", "Fatir", "Ya-Sin",
            "As-Saffat", "Sad", "Az-Zumar", "Ghafir", "Fussilat", "Ash-Shuraa", "Az-Zukhruf", "Ad-Dukhan", "Al-Jathiyah",
            "Al-Ahqaf", "Muhammad", "Al-Fath", "Al-Hujurat", "Qaf", "Adh-Dhariyat", "At-Tur", "An-Najm", "Al-Qamar",
            "Ar-Rahman", "Al-Waqi'ah", "Al-Hadid", "Al-Mujadila", "Al-Hashr", "Al-Mumtahanah", "As-Saf", "Al-Jumu'ah",
            "Al-Munafiqun", "At-Taghabun", "At-Talaq", "At-Tahrim", "Al-Mulk", "Al-Qalam", "Al-Haqqah", "Al-Ma'arij",
            "Nuh", "Al-Jinn", "Al-Muzzammil", "Al-Muddaththir", "Al-Qiyamah", "Al-Insan", "Al-Mursalat", "An-Naba",
            "An-Nazi'at", "'Abasa", "At-Takwir", "Al-Infitar", "Al-Mutaffifin", "Al-Inshiqaq", "Al-Buruj", "At-Tariq",
            "Al-A'la", "Al-Ghashiyah", "Al-Fajr", "Al-Balad", "Ash-Shams", "Al-Layl", "Ad-Duhaa", "Ash-Sharh", "At-Tin",
            "Al-'Alaq", "Al-Qadr", "Al-Bayyinah", "Az-Zalzalah", "Al-'Adiyat", "Al-Qari'ah", "At-Takathur", "Al-'Asr",
            "Al-Humazah", "Al-Fil", "Quraysh", "Al-Ma'un", "Al-Kawthar", "Al-Kafirun", "An-Nasr", "Al-Masad", "Al-Ikhlas",
            "Al-Falaq", "An-Nas"
    };

    /**
     * Mishary Alafasy reciting the whole surah {@code n} (1–114), from mp3quran.net (~1–120 MB). Not the alquran.cloud
     * CDN: its files open with a 590 KB cover-art tag and Android's player on the TCL never got past preparing.
     */
    static String surahAudioUrl(int n) {
        return String.format(java.util.Locale.ROOT, "https://cdn.mp3quran.net/audio/mishary-alafasy/r1/%03d.mp3", n);
    }

    /** Mishary Alafasy's recitation of the verse, from everyayah.com (about 100 KB). */
    static String audioUrl(String[] verse) {
        return "https://everyayah.com/data/Alafasy_128kbps/" + verse[3] + ".mp3";
    }

    // ---- the calendar window ----------------------------------------------------------------------------

    /** The Islamic holiday on this day, or null. */
    static String holiday(LocalDate date, int adjustDays) {
        int[] h = hijri(date, adjustDays);
        if (h == null) return null;
        for (int k = 0; k < ISLAMIC_DAYS.length; k++) {
            if (ISLAMIC_DAYS[k][0] == h[1] && ISLAMIC_DAYS[k][1] == h[0]) return ISLAMIC_NAMES[k];
        }
        return null;
    }

    static boolean mondayThursday(LocalDate date, int adjustDays) {
        DayOfWeek w = date.getDayOfWeek();
        return (w == DayOfWeek.MONDAY || w == DayOfWeek.THURSDAY) && !noSunnahFast(date, adjustDays);
    }

    static boolean ayyamulBidh(LocalDate date, int adjustDays) {
        int[] h = hijri(date, adjustDays);
        return h != null && h[0] >= 13 && h[0] <= 15 && !noSunnahFast(date, adjustDays);
    }

    /** What falls on the day, for the calendar window's detail line; empty when nothing does. */
    static java.util.List<String> events(LocalDate date, int adjustDays) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String h = holiday(date, adjustDays);
        if (h != null) out.add(h);
        if (mondayThursday(date, adjustDays)) out.add("Puasa Senin/Kamis");
        if (ayyamulBidh(date, adjustDays)) out.add("Puasa Ayyamul Bidh");
        return out;
    }

    /** A day cell's marks: "●" Monday/Thursday fast, "◆" Ayyamul Bidh, "■" Islamic holiday, in that order. */
    static String marks(LocalDate date, int adjustDays) {
        return (mondayThursday(date, adjustDays) ? "●" : "") + (ayyamulBidh(date, adjustDays) ? "◆" : "")
                + (holiday(date, adjustDays) != null ? "■" : "");
    }
}
