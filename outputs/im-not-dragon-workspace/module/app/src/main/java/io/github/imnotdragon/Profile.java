package io.github.imnotdragon;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

final class Profile {
    static final String PREFS = "profile";
    final String zone, proxy, ip, country;
    final double latitude, longitude;
    final boolean enableTimezoneLocale;
    final boolean enableCarrierSpoof;
    final boolean enableLocationSpoof;
    final boolean enableUaReduction;
    final boolean enableProxyOverride;

    Profile(SharedPreferences p) {
        zone = p.getString("zone", "America/Los_Angeles");
        proxy = p.getString("proxy", "auto");
        ip = p.getString("ip", "auto");
        country = p.getString("country", inferCountryFromZone(zone));
        latitude = Double.parseDouble(p.getString("latitude", "45.5946"));
        longitude = Double.parseDouble(p.getString("longitude", "-121.1787"));

        enableTimezoneLocale = p.getBoolean("enable_timezone_locale", true);
        enableCarrierSpoof = p.getBoolean("enable_carrier_spoof", true);
        enableLocationSpoof = p.getBoolean("enable_location_spoof", true);
        enableUaReduction = p.getBoolean("enable_ua_reduction", true);
        enableProxyOverride = p.getBoolean("enable_proxy_override", true);
    }

    static String inferCountryFromZone(String tz) {
        if (tz == null) return "us";
        if (tz.startsWith("Asia/Tokyo")) return "jp";
        if (tz.startsWith("Asia/Singapore")) return "sg";
        if (tz.startsWith("Asia/Seoul")) return "kr";
        if (tz.startsWith("Asia/Taipei")) return "tw";
        if (tz.startsWith("Europe/London")) return "gb";
        if (tz.startsWith("Europe/Berlin") || tz.startsWith("Europe/Frankfurt")) return "de";
        if (tz.startsWith("Europe/Paris")) return "fr";
        if (tz.startsWith("Europe/Amsterdam")) return "nl";
        if (tz.startsWith("Australia/")) return "au";
        if (tz.startsWith("America/Toronto") || tz.startsWith("America/Vancouver")) return "ca";
        return "us";
    }

    String json() {
        try {
            return new JSONObject()
                    .put("zone", zone)
                    .put("country", country)
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("enableTimezoneLocale", enableTimezoneLocale)
                    .put("enableLocationSpoof", enableLocationSpoof)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
