package com.gathin.tvhome;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;

/**
 * The Islamic calendar panel and the verse panel (config "schedule": {"islamic": true}): the next sunnah fasts,
 * the next Islamic and national holidays, and a verse and one of Asmaul Husna that change daily. All offline;
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
            DayOfWeek w = d.getDayOfWeek();
            if ((w == DayOfWeek.MONDAY || w == DayOfWeek.THURSDAY) && !noSunnahFast(d, adjustDays)) return d;
        }
        return null;
    }

    /** First and last day of the next (or current) Ayyamul Bidh, 13–15 of the Hijri month; null if none found. */
    static LocalDate[] nextAyyamulBidh(LocalDate today, int adjustDays) {
        LocalDate start = null, end = null;
        for (int i = 0; i < 70; i++) {
            LocalDate d = today.plusDays(i);
            int[] h = hijri(d, adjustDays);
            boolean bidh = h != null && h[0] >= 13 && h[0] <= 15 && !noSunnahFast(d, adjustDays);
            if (bidh) {
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
    // shortcut: only fixed-date national holidays; Imlek, Nyepi, Waisak, Wafat Isa move each year, add a table if wanted.
    private static final MonthDay[] NATIONAL_DAYS = {MonthDay.of(1, 1), MonthDay.of(5, 1), MonthDay.of(6, 1),
            MonthDay.of(8, 17), MonthDay.of(12, 25)};
    private static final String[] NATIONAL_NAMES = {"Tahun Baru", "Hari Buruh", "Hari Lahir Pancasila",
            "Hari Kemerdekaan", "Natal"};

    /** The next Islamic holiday from today (today included), or null. */
    static Holiday nextIslamicHoliday(LocalDate today, int adjustDays) {
        for (int i = 0; i < 400; i++) {
            LocalDate d = today.plusDays(i);
            int[] h = hijri(d, adjustDays);
            if (h == null) return null;
            for (int k = 0; k < ISLAMIC_DAYS.length; k++) {
                if (ISLAMIC_DAYS[k][0] == h[1] && ISLAMIC_DAYS[k][1] == h[0]) return new Holiday(ISLAMIC_NAMES[k], d);
            }
        }
        return null;
    }

    /** The next fixed national holiday from today (today included). */
    static Holiday nextNationalHoliday(LocalDate today) {
        for (int i = 0; i < 370; i++) {
            LocalDate d = today.plusDays(i);
            MonthDay md = MonthDay.from(d);
            for (int k = 0; k < NATIONAL_DAYS.length; k++) {
                if (NATIONAL_DAYS[k].equals(md)) return new Holiday(NATIONAL_NAMES[k], d);
            }
        }
        return null;
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

    /** Short verses, Indonesian translation (Kemenag wording, abridged to the cited verse). */
    static final String[][] VERSES = {
            {"Maka sesungguhnya bersama kesulitan ada kemudahan.", "QS. Al-Insyirah: 5"},
            {"Allah tidak membebani seseorang melainkan sesuai dengan kesanggupannya.", "QS. Al-Baqarah: 286"},
            {"Ingatlah, hanya dengan mengingat Allah hati menjadi tenteram.", "QS. Ar-Ra'd: 28"},
            {"Sesungguhnya jika kamu bersyukur, niscaya Aku akan menambah (nikmat) kepadamu.", "QS. Ibrahim: 7"},
            {"Maka ingatlah kepada-Ku, Aku pun akan ingat kepadamu.", "QS. Al-Baqarah: 152"},
            {"Berdoalah kepada-Ku, niscaya akan Aku perkenankan bagimu.", "QS. Gafir: 60"},
            {"Dan barang siapa bertawakal kepada Allah, niscaya Allah akan mencukupkan (keperluan)nya.", "QS. At-Talaq: 3"},
            {"Barang siapa bertakwa kepada Allah, niscaya Dia akan membukakan jalan keluar baginya.", "QS. At-Talaq: 2"},
            {"Janganlah kamu berputus asa dari rahmat Allah.", "QS. Az-Zumar: 53"},
            {"Dan hendaklah berbuat baik kepada ibu bapak.", "QS. Al-Isra': 23"},
            {"Sesungguhnya salat itu mencegah dari (perbuatan) keji dan mungkar.", "QS. Al-'Ankabut: 45"},
            {"Janganlah kamu (merasa) lemah, dan jangan (pula) bersedih hati.", "QS. Ali 'Imran: 139"},
            {"Janganlah engkau bersedih, sesungguhnya Allah bersama kita.", "QS. At-Taubah: 40"},
            {"Sesungguhnya yang paling mulia di antara kamu di sisi Allah ialah orang yang paling bertakwa.", "QS. Al-Hujurat: 13"},
            {"Dan mohonlah pertolongan (kepada Allah) dengan sabar dan salat.", "QS. Al-Baqarah: 45"},
            {"Dan sungguh, kelak Tuhanmu pasti memberikan karunia-Nya kepadamu, sehingga engkau menjadi puas.", "QS. Ad-Duha: 5"},
            {"Dan terhadap nikmat Tuhanmu, hendaklah engkau nyatakan (dengan bersyukur).", "QS. Ad-Duha: 11"},
            {"Maka nikmat Tuhanmu yang manakah yang kamu dustakan?", "QS. Ar-Rahman: 13"},
            {"Katakanlah (Muhammad), Dialah Allah, Yang Maha Esa.", "QS. Al-Ikhlas: 1"},
            {"Demi masa. Sungguh, manusia berada dalam kerugian.", "QS. Al-'Asr: 1-2"},
            {"Tolaklah (kejahatan itu) dengan cara yang lebih baik.", "QS. Fussilat: 34"},
            {"Sesungguhnya rahmat Allah sangat dekat kepada orang yang berbuat kebaikan.", "QS. Al-A'raf: 56"},
            {"Boleh jadi kamu membenci sesuatu, padahal itu baik bagimu.", "QS. Al-Baqarah: 216"},
            {"Sesungguhnya beserta kesulitan itu ada kemudahan.", "QS. Al-Insyirah: 6"},
            {"Sungguh, Allah menyukai orang-orang yang berbuat baik.", "QS. Al-Baqarah: 195"},
            {"Wahai orang-orang yang beriman! Ingatlah kepada Allah, dengan mengingat (nama-Nya) sebanyak-banyaknya.", "QS. Al-Ahzab: 41"},
            {"Sungguh, Allah mencintai orang yang bertawakal.", "QS. Ali 'Imran: 159"},
            {"Yang menciptakan mati dan hidup, untuk menguji kamu, siapa di antara kamu yang lebih baik amalnya.", "QS. Al-Mulk: 2"},
            {"Dan apabila hamba-hamba-Ku bertanya kepadamu tentang Aku, maka sesungguhnya Aku dekat.", "QS. Al-Baqarah: 186"},
            {"Sungguh, Allah beserta orang-orang yang sabar.", "QS. Al-Baqarah: 153"},
    };

    /** Asmaul Husna in the usual order, with the common Indonesian meaning. */
    static final String[] ASMA = {
            "Ar-Rahman (Maha Pengasih)", "Ar-Rahim (Maha Penyayang)", "Al-Malik (Maha Merajai)",
            "Al-Quddus (Maha Suci)", "As-Salam (Maha Memberi Kesejahteraan)", "Al-Mu'min (Maha Memberi Keamanan)",
            "Al-Muhaimin (Maha Pemelihara)", "Al-'Aziz (Maha Perkasa)", "Al-Jabbar (Maha Kuasa)",
            "Al-Mutakabbir (Maha Megah)", "Al-Khaliq (Maha Pencipta)", "Al-Bari' (Maha Mengadakan)",
            "Al-Mushawwir (Maha Membentuk Rupa)", "Al-Ghaffar (Maha Pengampun)", "Al-Qahhar (Maha Memaksa)",
            "Al-Wahhab (Maha Pemberi Karunia)", "Ar-Razzaq (Maha Pemberi Rezeki)", "Al-Fattah (Maha Pembuka Rahmat)",
            "Al-'Alim (Maha Mengetahui)", "Al-Qabidh (Maha Menyempitkan)", "Al-Basith (Maha Melapangkan)",
            "Al-Khafidh (Maha Merendahkan)", "Ar-Rafi' (Maha Meninggikan)", "Al-Mu'izz (Maha Memuliakan)",
            "Al-Mudzill (Maha Menghinakan)", "As-Sami' (Maha Mendengar)", "Al-Bashir (Maha Melihat)",
            "Al-Hakam (Maha Menetapkan)", "Al-'Adl (Maha Adil)", "Al-Lathif (Maha Lembut)",
            "Al-Khabir (Maha Mengenal)", "Al-Halim (Maha Penyantun)", "Al-'Azhim (Maha Agung)",
            "Al-Ghafur (Maha Pengampun)", "Asy-Syakur (Maha Pembalas Budi)", "Al-'Aliy (Maha Tinggi)",
            "Al-Kabir (Maha Besar)", "Al-Hafizh (Maha Memelihara)", "Al-Muqit (Maha Pemberi Kecukupan)",
            "Al-Hasib (Maha Membuat Perhitungan)", "Al-Jalil (Maha Luhur)", "Al-Karim (Maha Pemurah)",
            "Ar-Raqib (Maha Mengawasi)", "Al-Mujib (Maha Mengabulkan)", "Al-Wasi' (Maha Luas)",
            "Al-Hakim (Maha Bijaksana)", "Al-Wadud (Maha Mengasihi)", "Al-Majid (Maha Mulia)",
            "Al-Ba'its (Maha Membangkitkan)", "Asy-Syahid (Maha Menyaksikan)", "Al-Haqq (Maha Benar)",
            "Al-Wakil (Maha Memelihara Penyerahan)", "Al-Qawiyy (Maha Kuat)", "Al-Matin (Maha Kokoh)",
            "Al-Waliyy (Maha Melindungi)", "Al-Hamid (Maha Terpuji)", "Al-Muhshi (Maha Menghitung)",
            "Al-Mubdi' (Maha Memulai)", "Al-Mu'id (Maha Mengembalikan)", "Al-Muhyi (Maha Menghidupkan)",
            "Al-Mumit (Maha Mematikan)", "Al-Hayy (Maha Hidup)", "Al-Qayyum (Maha Berdiri Sendiri)",
            "Al-Wajid (Maha Penemu)", "Al-Maajid (Maha Mulia)", "Al-Wahid (Maha Tunggal)",
            "Al-Ahad (Maha Esa)", "As-Shamad (Maha Dibutuhkan)", "Al-Qadir (Maha Menentukan)",
            "Al-Muqtadir (Maha Berkuasa)", "Al-Muqaddim (Maha Mendahulukan)", "Al-Mu'akhkhir (Maha Mengakhirkan)",
            "Al-Awwal (Maha Awal)", "Al-Akhir (Maha Akhir)", "Azh-Zhahir (Maha Nyata)",
            "Al-Bathin (Maha Gaib)", "Al-Wali (Maha Memerintah)", "Al-Muta'ali (Maha Tinggi)",
            "Al-Barr (Maha Penderma)", "At-Tawwab (Maha Penerima Tobat)", "Al-Muntaqim (Maha Pemberi Balasan)",
            "Al-'Afuww (Maha Pemaaf)", "Ar-Ra'uf (Maha Pengasuh)", "Malikul Mulk (Pemilik Kerajaan)",
            "Dzul Jalali wal Ikram (Pemilik Kebesaran dan Kemuliaan)", "Al-Muqsith (Maha Pemberi Keadilan)",
            "Al-Jami' (Maha Mengumpulkan)", "Al-Ghaniyy (Maha Kaya)", "Al-Mughni (Maha Pemberi Kekayaan)",
            "Al-Mani' (Maha Mencegah)", "Adh-Dharr (Maha Pemberi Derita)", "An-Nafi' (Maha Pemberi Manfaat)",
            "An-Nur (Maha Bercahaya)", "Al-Hadi (Maha Pemberi Petunjuk)", "Al-Badi' (Maha Pencipta)",
            "Al-Baqi (Maha Kekal)", "Al-Warits (Maha Pewaris)", "Ar-Rasyid (Maha Pandai)",
            "As-Shabur (Maha Sabar)",
    };

    /** Today's verse: one per day, cycling through the list. */
    static String[] verse(LocalDate today) {
        return VERSES[(int) Math.floorMod(today.toEpochDay(), (long) VERSES.length)];
    }

    /** Today's name of Asmaul Husna, in order, one per day. */
    static String asma(LocalDate today) {
        return ASMA[(int) Math.floorMod(today.toEpochDay(), (long) ASMA.length)];
    }
}
