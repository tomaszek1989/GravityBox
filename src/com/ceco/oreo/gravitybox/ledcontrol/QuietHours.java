/*
 * Copyright (C) 2018 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ceco.oreo.gravitybox.ledcontrol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.ceco.oreo.gravitybox.ModLedControl;
import com.ceco.oreo.gravitybox.Utils;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;

public class QuietHours {
    public static final String PKG_WEARABLE_APP = "com.google.android.wearable.app";
    public enum Mode { ON, OFF, AUTO, WEAR };

    public static final class SystemSound {
        public static final String DIALPAD = "dialpad";
        public static final String TOUCH = "touch";
        public static final String SCREEN_LOCK = "screen_lock";
        public static final String CHARGER = "charger";
        public static final String RINGER = "ringer";
    }

    public static final class Range {
        public String id;
        public Set<String> days;
        public int startTime;
        public int endTime;

        private Range() { }

        public static Range parse(String value) {
            if (value == null || value.isEmpty())
                return createDefault();

            String[] buf = value.split("\\|");
            Range r = new Range();
            r.id = buf[0];
            r.days = new HashSet<String>(Arrays.asList(buf[1].split(",")));
            r.startTime = Integer.valueOf(buf[2]);
            r.endTime = Integer.valueOf(buf[3]);
            return r;
        }

        public static Range createDefault() {
            Range r = new Range();
            r.id = UUID.randomUUID().toString();
            r.days = new HashSet<String>(Arrays.asList("1","2","3","4","5","6","7"));
            r.startTime = 1380;
            r.endTime = 360;
            return r;
        }

        public String getValue() {
            String buf = id + "|";
            for (String day : days) {
                if (!buf.endsWith("|")) buf += ",";
                buf += day;
            }
            buf += "|" + String.valueOf(startTime);
            buf += "|" + String.valueOf(endTime);
            return buf;
        }

        public boolean endsNextDay() {
            return (endTime < startTime);
        }
    }

    private static final List<String> NOTIF_TEXT_FIELDS = new ArrayList<>(Arrays.asList(
            "android.title","android.text","android.subText","android.infoText",
            "android.summaryText","android.bigText"));

    public boolean uncLocked;
    public boolean enabled;
    public boolean muteLED;
    public boolean muteVibe;
    public Set<String> muteSystemSounds;
    public boolean showStatusbarIcon;
    public Mode mode;
    public boolean interactive;
    public boolean muteSystemVibe;
    public Set<String> ringerWhitelist;
    private Set<String> ranges;

    public QuietHours(Bundle prefs) {
        uncLocked = prefs.getBoolean(QuietHoursActivity.EXTRA_QH_LOCKED);
        enabled = prefs.getBoolean(QuietHoursActivity.EXTRA_QH_ENABLED);
        muteLED = prefs.getBoolean(QuietHoursActivity.EXTRA_QH_MUTE_LED);
        muteVibe = prefs.getBoolean(QuietHoursActivity.EXTRA_QH_MUTE_VIBE);
        muteSystemSounds = new HashSet<String>(prefs.getStringArrayList(QuietHoursActivity.EXTRA_QH_MUTE_SYSTEM_SOUNDS));
        showStatusbarIcon = prefs.getBoolean(QuietHoursActivity.EXTRA_QH_STATUSBAR_ICON);
        mode = Mode.valueOf(prefs.getString(QuietHoursActivity.EXTRA_QH_MODE));
        interactive = prefs.getBoolean(QuietHoursActivity.EXTRA_QH_INTERACTIVE);
        muteSystemVibe = prefs.getBoolean(QuietHoursActivity.EXTRA_QH_MUTE_SYSTEM_VIBE);
        ringerWhitelist = new HashSet<String>(prefs.getStringArrayList(QuietHoursActivity.EXTRA_QH_RINGER_WHITELIST));
        ranges = new HashSet<String>(prefs.getStringArrayList(QuietHoursActivity.EXTRA_QH_RANGES));
    }

    public QuietHours(SharedPreferences prefs) {
        uncLocked = prefs.getBoolean(QuietHoursActivity.PREF_KEY_QH_LOCKED, false);
        enabled = prefs.getBoolean(QuietHoursActivity.PREF_KEY_QH_ENABLED, false);
        muteLED = prefs.getBoolean(QuietHoursActivity.PREF_KEY_QH_MUTE_LED, false);
        muteVibe = prefs.getBoolean(QuietHoursActivity.PREF_KEY_QH_MUTE_VIBE, true);
        muteSystemSounds = prefs.getStringSet(QuietHoursActivity.PREF_KEY_QH_MUTE_SYSTEM_SOUNDS,
                new HashSet<String>());
        showStatusbarIcon = prefs.getBoolean(QuietHoursActivity.PREF_KEY_QH_STATUSBAR_ICON, true);
        mode = Mode.valueOf(prefs.getString(QuietHoursActivity.PREF_KEY_QH_MODE, "AUTO"));
        interactive = prefs.getBoolean(QuietHoursActivity.PREF_KEY_QH_INTERACTIVE, false);
        muteSystemVibe = prefs.getBoolean(QuietHoursActivity.PREF_KEY_MUTE_SYSTEM_VIBE, false);
        ringerWhitelist = prefs.getStringSet(QuietHoursActivity.PREF_KEY_QH_RINGER_WHITELIST,
                new HashSet<String>());
        ranges = prefs.getStringSet(QuietHoursActivity.PREF_KEY_QH_RANGES,
                new HashSet<String>());
    }

    public boolean quietHoursActive(LedSettings ls, Notification n, boolean userPresent) {
        if (uncLocked || !enabled) return false;

        if (mode == Mode.WEAR) {
            return true;
        }

        if (ls.getEnabled() && ls.getQhIgnore()) {
            boolean defaultIgnoreResult = (interactive && userPresent) ? !ls.getQhIgnoreInteractive() : false;
            if (ls.getQhIgnoreList() == null || ls.getQhIgnoreList().trim().isEmpty()) {
                if (ModLedControl.DEBUG) ModLedControl.log("QH ignored for all notifications");
                return defaultIgnoreResult;
            } else {
                List<CharSequence> notifTexts = getNotificationTexts(n);
                String[] keywords = ls.getQhIgnoreList().trim().split(",");
                boolean ignore = false;
                for (String kw : keywords) {
                    kw = kw.toLowerCase(Locale.getDefault());
                    ignore |= n.tickerText != null && n.tickerText.toString()
                            .toLowerCase(Locale.getDefault()).contains(kw);
                    for (CharSequence notifText : notifTexts) {
                        ignore |= notifText.toString().toLowerCase(Locale.getDefault()).contains(kw);
                    }
                }
                if (ModLedControl.DEBUG) ModLedControl.log("QH ignore list contains keyword?: " + ignore);
                return (ignore ? defaultIgnoreResult : (quietHoursActive() || (interactive && userPresent)));
            }
        } else {
            return (quietHoursActive() || (interactive && userPresent));
        }
    }

    public boolean quietHoursActive() {
        if (uncLocked || !enabled) return false;

        if (mode != Mode.AUTO) {
            return (mode == Mode.ON || mode == Mode.WEAR);
        }

        Calendar c = new GregorianCalendar();
        c.setTimeInMillis(System.currentTimeMillis());
        int curMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int curDay = c.get(Calendar.DAY_OF_WEEK);
        int prevDay = (curDay == 1 ? 7 : curDay - 1);

        for (String rangeValue : ranges) {
            Range range = Range.parse(rangeValue);
            boolean active = false;
            if (range.endsNextDay()) {
                active = (curMin >= range.startTime && range.days.contains(String.valueOf(curDay)) ||
                    (curMin < range.endTime && range.days.contains(String.valueOf(prevDay))));
            } else {
                active = range.days.contains(String.valueOf(curDay));
            }
            if (active && Utils.isTimeOfDayInRange(c.getTimeInMillis(), range.startTime, range.endTime)) {
                return true;
            }
        }

        return false;
    }

    public boolean isSystemSoundMuted(String systemSound) {
        return (muteSystemSounds.contains(systemSound) && quietHoursActive());
    }

    private List<CharSequence> getNotificationTexts(Notification notification) {
        List<CharSequence> texts = new ArrayList<>();

        for (String extra : NOTIF_TEXT_FIELDS) {
            CharSequence cs = notification.extras.getCharSequence(extra);
            if (cs != null) texts.add(cs);
        }

        if (ModLedControl.DEBUG) {
            for (CharSequence text : texts) {
                ModLedControl.log("Notif text: " + text);
            }
        }

        return texts;
    }
}
