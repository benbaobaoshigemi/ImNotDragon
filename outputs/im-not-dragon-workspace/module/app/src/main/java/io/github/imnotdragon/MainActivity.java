package io.github.imnotdragon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.TimeZone;

public final class MainActivity extends Activity {
    private static final int COLOR_BG = Color.parseColor("#F5F7FA");
    private static final int COLOR_CARD = Color.parseColor("#FFFFFF");
    private static final int COLOR_BORDER = Color.parseColor("#E2E8F0");
    private static final int COLOR_DIVIDER = Color.parseColor("#F1F5F9");
    private static final int COLOR_INPUT_BG = Color.parseColor("#F8FAFC");
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#0F172A");
    private static final int COLOR_TEXT_SECONDARY = Color.parseColor("#64748B");
    private static final int COLOR_ACCENT = Color.parseColor("#2563EB");
    private static final int COLOR_ACCENT_SOFT = Color.parseColor("#EFF6FF");
    private static final int COLOR_LOCKED_TEXT = Color.parseColor("#059669");
    private static final int COLOR_LOCKED_BG = Color.parseColor("#ECFDF5");

    private EditText proxyInput, zoneInput, latInput, lonInput;
    private Switch swTimezone, swCarrier, swLocation, swUa, swProxyOverride;
    private TextView nodeBadge, statusText;
    private SharedPreferences prefs;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupSystemBars();

        try {
            prefs = getSharedPreferences(Profile.PREFS, MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            prefs = getSharedPreferences(Profile.PREFS, MODE_PRIVATE);
        }
        Profile p = new Profile(prefs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(root);

        // 1. Header
        root.addView(buildHeader());

        // 2. Always-on Core Card
        root.addView(sectionLabel("核心防护"));
        root.addView(buildCoreCard());

        // 3. Granular Disguise Card
        LinearLayout.LayoutParams disguiseLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        disguiseLabelLp.topMargin = dp(16);
        disguiseLabelLp.bottomMargin = dp(8);
        TextView disguiseLabel = sectionLabel("环境伪装");
        disguiseLabel.setLayoutParams(disguiseLabelLp);
        root.addView(disguiseLabel);
        root.addView(buildDisguiseCard(p));

        // 4. Node & Region Profile Card
        root.addView(buildNodeSectionHeader(p));
        root.addView(buildNodeCard(p));

        // 5. Action Bar & Status
        root.addView(buildActionBar());

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setTextColor(COLOR_TEXT_SECONDARY);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(10);
        statusText.setLayoutParams(statusLp);
        root.addView(statusText);

        setContentView(scroll);
    }

    @SuppressWarnings("deprecation")
    private void setupSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_BG);
        int flags = window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        header.setLayoutParams(lp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("I’m not dragon");
        title.setTextSize(23);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleRow.addView(title);

        TextView badge = pillBadge("v1.7", COLOR_ACCENT_SOFT, COLOR_ACCENT);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(10);
        badge.setLayoutParams(badgeLp);
        titleRow.addView(badge);
        header.addView(titleRow);

        LinearLayout scopeRow = new LinearLayout(this);
        scopeRow.setOrientation(LinearLayout.HORIZONTAL);
        scopeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scopeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scopeLp.topMargin = dp(6);
        scopeRow.setLayoutParams(scopeLp);

        String[] targets = {"Via", "Chrome", "Claude"};
        for (int i = 0; i < targets.length; i++) {
            TextView chip = pillBadge(targets[i], Color.parseColor("#E2E8F0"), Color.parseColor("#475569"));
            if (i > 0) {
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                chipLp.leftMargin = dp(6);
                chip.setLayoutParams(chipLp);
            }
            scopeRow.addView(chip);
        }
        header.addView(scopeRow);
        return header;
    }

    private View buildCoreCard() {
        LinearLayout card = cardContainer();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText("WebRTC 与 IPv6 防泄漏");
        t.setTextSize(14);
        t.setTextColor(COLOR_TEXT_PRIMARY);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText("UDP 路由探测阻断 · 原生公网 IPv6 防旁路");
        s.setTextSize(11);
        s.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(2);
        s.setLayoutParams(sLp);
        textCol.addView(s);

        row.addView(textCol);

        TextView lockedBadge = pillBadge("常驻开启", COLOR_LOCKED_BG, COLOR_LOCKED_TEXT);
        lockedBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.addView(lockedBadge);

        card.addView(row);
        return card;
    }

    private View buildDisguiseCard(Profile p) {
        LinearLayout card = cardContainer();

        swTimezone = createSwitch(p.enableTimezoneLocale);
        card.addView(switchRow("时区与语言", "IANA 时区对齐 · en-US 语言环境", swTimezone));
        card.addView(divider());

        swCarrier = createSwitch(p.enableCarrierSpoof);
        card.addView(switchRow("运营商与网卡", "SIM 归属地伪装 · 隐藏虚拟网卡接口", swCarrier));
        card.addView(divider());

        swLocation = createSwitch(p.enableLocationSpoof);
        card.addView(switchRow("虚拟地理定位", "拦截硬件 GPS · 注入出口近似坐标", swLocation));
        card.addView(divider());

        swUa = createSwitch(p.enableUaReduction);
        card.addView(switchRow("浏览器特征降噪", "精简机型 UA · 隐藏调试特征", swUa));
        card.addView(divider());

        swProxyOverride = createSwitch(p.enableProxyOverride);
        card.addView(switchRow("浏览器代理接管", "强制 WebView 走本地代理 · 阻断本地 DNS", swProxyOverride));

        return card;
    }

    private View buildNodeSectionHeader(Profile p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText("节点参数");
        label.setTextSize(13);
        label.setTextColor(COLOR_TEXT_SECONDARY);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        row.addView(label);

        nodeBadge = new TextView(this);
        nodeBadge.setText(formatNodeBadge(p.ip, p.country));
        nodeBadge.setTextSize(12);
        nodeBadge.setTextColor(COLOR_TEXT_SECONDARY);
        nodeBadge.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        badgeLp.leftMargin = dp(8);
        nodeBadge.setLayoutParams(badgeLp);
        row.addView(nodeBadge);

        TextView syncBtn = new TextView(this);
        syncBtn.setText("同步当前出口");
        syncBtn.setTextSize(12);
        syncBtn.setTextColor(COLOR_ACCENT);
        syncBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        syncBtn.setPadding(dp(12), dp(5), dp(12), dp(5));
        syncBtn.setBackground(roundedRect(COLOR_ACCENT_SOFT, dp(999), 0, 0));
        syncBtn.setOnClickListener(v -> updateFromProxy(syncBtn));
        row.addView(syncBtn);

        return row;
    }

    private View buildNodeCard(Profile p) {
        LinearLayout card = cardContainer();
        card.setPadding(dp(16), dp(12), dp(16), dp(14));

        proxyInput = createInput(p.proxy, "auto");
        card.addView(labeledInput("代理地址 (auto 自动适配系统 VPN / TUN)", proxyInput, 0));

        zoneInput = createInput(p.zone, "America/Los_Angeles");
        card.addView(labeledInput("时区 (IANA)", zoneInput, dp(10)));

        LinearLayout coordRow = new LinearLayout(this);
        coordRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams coordLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        coordLp.topMargin = dp(10);
        coordRow.setLayoutParams(coordLp);

        latInput = createInput(Double.toString(p.latitude), "45.5946");
        latInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        View latBox = labeledInput("纬度", latInput, 0);
        LinearLayout.LayoutParams latLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        latLp.rightMargin = dp(6);
        latBox.setLayoutParams(latLp);
        coordRow.addView(latBox);

        lonInput = createInput(Double.toString(p.longitude), "-121.1787");
        lonInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        View lonBox = labeledInput("经度", lonInput, 0);
        LinearLayout.LayoutParams lonLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lonLp.leftMargin = dp(6);
        lonBox.setLayoutParams(lonLp);
        coordRow.addView(lonBox);

        card.addView(coordRow);
        return card;
    }

    private View buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        bar.setLayoutParams(lp);

        Button logBtn = new Button(this);
        logBtn.setText("运行日志");
        logBtn.setAllCaps(false);
        logBtn.setTextSize(14);
        logBtn.setTextColor(COLOR_TEXT_PRIMARY);
        logBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        logBtn.setBackground(roundedRect(COLOR_CARD, dp(12), dp(1), COLOR_BORDER));
        logBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        logLp.rightMargin = dp(10);
        logBtn.setLayoutParams(logLp);
        logBtn.setOnClickListener(v -> showRecentLogs());
        bar.addView(logBtn);

        Button saveBtn = new Button(this);
        saveBtn.setText("保存配置");
        saveBtn.setAllCaps(false);
        saveBtn.setTextSize(14);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        saveBtn.setBackground(roundedRect(COLOR_TEXT_PRIMARY, dp(12), 0, 0));
        saveBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(44), 1.8f);
        saveBtn.setLayoutParams(saveLp);
        saveBtn.setOnClickListener(v -> save());
        bar.addView(saveBtn);

        return bar;
    }

    private Switch createSwitch(boolean checked) {
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        sw.setThumbTintList(new ColorStateList(states, new int[]{COLOR_ACCENT, Color.parseColor("#94A3B8")}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{Color.parseColor("#93C5FD"), Color.parseColor("#CBD5E1")}));
        return sw;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(COLOR_TEXT_SECONDARY);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(COLOR_CARD, dp(14), dp(1), COLOR_BORDER));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View switchRow(String titleText, String subText, Switch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(14), dp(11));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextSize(14);
        t.setTextColor(COLOR_TEXT_PRIMARY);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(subText);
        s.setTextSize(11);
        s.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(1);
        s.setLayoutParams(sLp);
        textCol.addView(s);

        row.addView(textCol);
        row.addView(toggle);
        return row;
    }

    private EditText createInput(String value, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(13);
        e.setTextColor(COLOR_TEXT_PRIMARY);
        e.setHintTextColor(Color.parseColor("#94A3B8"));
        e.setTypeface(Typeface.MONOSPACE);
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        e.setBackground(roundedRect(COLOR_INPUT_BG, dp(10), dp(1), COLOR_BORDER));
        return e;
    }

    private View labeledInput(String labelText, EditText input, int topMargin) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        box.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(11);
        label.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.bottomMargin = dp(4);
        label.setLayoutParams(lLp);
        box.addView(label);
        box.addView(input);
        return box;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView pillBadge(String text, int bgColor, int textColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(textColor);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setPadding(dp(8), dp(2), dp(8), dp(2));
        tv.setBackground(roundedRect(bgColor, dp(999), 0, 0));
        return tv;
    }

    private GradientDrawable roundedRect(int fillColor, int radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    private String formatNodeBadge(String ip, String country) {
        if (ip == null || ip.isEmpty() || "auto".equalsIgnoreCase(ip)) {
            return "未同步";
        }
        String c = (country != null && !country.isEmpty()) ? country.toUpperCase(Locale.ROOT) : "US";
        return ip + " · " + c;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void save() {
        try {
            double lat = Double.parseDouble(latInput.getText().toString().trim());
            double lon = Double.parseDouble(lonInput.getText().toString().trim());
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalArgumentException("经纬度超出有效范围");
            }
            String proxyRaw = proxyInput.getText().toString().trim();
            String savedProxy;
            if (proxyRaw.isEmpty() || "auto".equalsIgnoreCase(proxyRaw)) {
                savedProxy = "auto";
            } else {
                URI u = URI.create(proxyRaw);
                if (!"http".equals(u.getScheme()) || u.getHost() == null || u.getPort() < 1 || u.getPort() > 65535) {
                    throw new IllegalArgumentException("请输入 auto 或有效 HTTP 代理地址");
                }
                savedProxy = u.toString();
            }
            String tz = zoneInput.getText().toString().trim();
            if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(tz)) {
                throw new IllegalArgumentException("无效的 IANA 时区名称");
            }

            String country = prefs.getString("country", Profile.inferCountryFromZone(tz));

            prefs.edit()
                    .putString("proxy", savedProxy)
                    .putString("zone", tz)
                    .putString("country", country)
                    .putString("latitude", Double.toString(lat))
                    .putString("longitude", Double.toString(lon))
                    .putBoolean("enable_timezone_locale", swTimezone.isChecked())
                    .putBoolean("enable_carrier_spoof", swCarrier.isChecked())
                    .putBoolean("enable_location_spoof", swLocation.isChecked())
                    .putBoolean("enable_ua_reduction", swUa.isChecked())
                    .putBoolean("enable_proxy_override", swProxyOverride.isChecked())
                    .commit();

            try {
                File prefsFile = new File(getApplicationInfo().dataDir, "shared_prefs/" + Profile.PREFS + ".xml");
                if (prefsFile.exists()) {
                    prefsFile.setReadable(true, false);
                }
            } catch (Throwable ignored) {}

            proxyInput.setText(savedProxy);
            statusText.setTextColor(COLOR_ACCENT);
            statusText.setText("配置已保存，重启目标应用生效");
        } catch (IllegalArgumentException e) {
            statusText.setTextColor(Color.parseColor("#DC2626"));
            statusText.setText(e.getMessage());
        }
    }

    private void updateFromProxy(TextView button) {
        final String address = proxyInput.getText().toString().trim();
        button.setEnabled(false);
        button.setText("同步中…");
        statusText.setTextColor(COLOR_TEXT_SECONDARY);
        statusText.setText("正在查询当前出口节点…");

        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL("https://ipinfo.io/json");
                if (!address.isEmpty() && !"auto".equalsIgnoreCase(address)) {
                    URI u = URI.create(address);
                    Proxy px = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(u.getHost(), u.getPort()));
                    try {
                        c = (HttpURLConnection) url.openConnection(px);
                        c.setConnectTimeout(5000);
                        c.setReadTimeout(8000);
                        c.connect();
                    } catch (Exception fallbackEx) {
                        if (c != null) c.disconnect();
                        c = (HttpURLConnection) url.openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                    }
                } else {
                    c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                }
                if (c.getResponseCode() != 200) {
                    throw new java.io.IOException("HTTP " + c.getResponseCode());
                }
                JSONObject j = new JSONObject(new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                String[] loc = j.getString("loc").split(",");
                String tz = j.getString("timezone");
                String ip = j.getString("ip");
                String country = j.optString("country", Profile.inferCountryFromZone(tz)).toLowerCase(Locale.ROOT);
                String city = j.optString("city") + ", " + j.optString("country");

                runOnUiThread(() -> {
                    latInput.setText(loc[0]);
                    lonInput.setText(loc[1]);
                    zoneInput.setText(tz);
                    prefs.edit().putString("ip", ip).putString("country", country).commit();
                    nodeBadge.setText(formatNodeBadge(ip, country));
                    save();
                    statusText.setTextColor(COLOR_ACCENT);
                    statusText.setText("已同步出口 " + ip + " (" + city + ")，重启目标应用生效");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setTextColor(Color.parseColor("#DC2626"));
                    statusText.setText("同步失败：" + e.getMessage());
                });
            } finally {
                if (c != null) c.disconnect();
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("同步当前出口");
                });
            }
        }, "proxy-location").start();
    }

    private void showRecentLogs() {
        StringBuilder sb = new StringBuilder();
        String[] pkgs = {"com.anthropic.claude", "mark.via", "com.android.chrome"};

        for (String pkg : pkgs) {
            File f = new File("/data/data/" + pkg + "/cache/dragon_net.log");
            if (f.exists() && f.canRead()) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    String content = new String(bytes, StandardCharsets.UTF_8).trim();
                    if (!content.isEmpty()) {
                        sb.append("=== ").append(pkg).append(" ===\n").append(content).append("\n\n");
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (sb.length() == 0) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "su", "-c",
                        "for p in com.anthropic.claude mark.via com.android.chrome; do " +
                        "f=/data/data/$p/cache/dragon_net.log; " +
                        "if [ -f $f ]; then echo \"=== $p ===\"; tail -n 30 $f; echo \"\"; fi; done"
                });
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
            } catch (Throwable ignored) {}
        }

        if (sb.length() == 0) {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-s", "ImNotDragon-Net:V", "-t", "60"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
            } catch (Throwable ignored) {}
        }

        if (sb.length() == 0) {
            sb.append("暂无日志记录。\n启动目标应用后日志将写入各应用缓存目录 cache/dragon_net.log。");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("运行日志");
        TextView tv = new TextView(this);
        tv.setText(sb.toString().trim());
        tv.setPadding(dp(20), dp(14), dp(20), dp(14));
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        builder.setView(sv);
        builder.setPositiveButton("关闭", null);
        builder.show();
    }
}
