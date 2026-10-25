package io.github.imnotdragon;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.os.LocaleList;
import android.system.Os;
import android.telephony.TelephonyManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewCompat;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.ZipFile;

public final class ViaHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final Set<Class<?>> hookedClients = Collections.synchronizedSet(new HashSet<>());
    private static String modulePath;
    private static Profile profile;
    private static String script;
    private static boolean proxySet;
    private static boolean timeZoneMonitorHooked;
    private static Context appContext;
    private static String activeProxyUrl;
    private static int activeProxyPort;

    private static native void configureNetwork(int port, boolean enableStrictProxy, String logPath);

    public static void logNet(String msg) {
        android.util.Log.i("ImNotDragon-Net", msg);
        XposedBridge.log("ImNotDragon-Net: " + msg);
    }

    @Override
    public void initZygote(StartupParam p) {
        modulePath = p.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        boolean isVia = "mark.via".equals(p.packageName);
        boolean isChrome = "com.android.chrome".equals(p.packageName);
        boolean isClaude = "com.anthropic.claude".equals(p.packageName);
        if (!isVia && !isChrome && !isClaude) return;

        install(p, isVia, isChrome, isClaude);
    }

    private static void resolveActiveProxy(Context ctx, Profile prof) {
        activeProxyUrl = null;
        activeProxyPort = 0;

        if (ctx != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                ProxyInfo pi = cm != null ? cm.getDefaultProxy() : null;
                if (pi != null && pi.getHost() != null && !pi.getHost().isEmpty() && pi.getPort() > 0) {
                    activeProxyPort = pi.getPort();
                    activeProxyUrl = "http://" + pi.getHost().trim() + ":" + activeProxyPort;
                    return;
                }
            } catch (Throwable ignored) {}
        }

        String sysHost = System.getProperty("http.proxyHost");
        String sysPortStr = System.getProperty("http.proxyPort");
        if (sysHost != null && !sysHost.isEmpty() && sysPortStr != null) {
            try {
                int p = Integer.parseInt(sysPortStr.trim());
                if (p > 0 && p <= 65535) {
                    activeProxyPort = p;
                    activeProxyUrl = "http://" + sysHost.trim() + ":" + p;
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        if (prof.proxy != null && !prof.proxy.trim().isEmpty() && !"auto".equalsIgnoreCase(prof.proxy.trim())) {
            try {
                URI u = URI.create(prof.proxy.trim());
                if (u.getHost() != null && u.getPort() > 0) {
                    activeProxyPort = u.getPort();
                    activeProxyUrl = u.toString();
                }
            } catch (Throwable ignored) {}
        }
    }

    private void install(XC_LoadPackage.LoadPackageParam p, boolean isVia, boolean isChrome, boolean isClaude) throws Throwable {
        XSharedPreferences prefs = new XSharedPreferences("io.github.imnotdragon", Profile.PREFS);
        prefs.reload();
        profile = new Profile(prefs);
        resolveActiveProxy(null, profile);

        String logPath = "/data/data/" + p.packageName + "/cache/dragon_net.log";

        // Core network leak protection (UDP/WebRTC/IPv6 bypass block) is always loaded and enforced.
        try {
            System.loadLibrary("dragon_network");
            int nativeLockPort = (!isClaude && profile.enableProxyOverride) ? activeProxyPort : 0;
            configureNetwork(nativeLockPort, !isClaude && profile.enableProxyOverride, logPath);
        } catch (Throwable t) {
            logNet("Native network hook init error: " + t);
        }

        final LocaleList locales = new LocaleList(Locale.US);
        final TimeZone zone = TimeZone.getTimeZone(profile.zone);

        if (profile.enableTimezoneLocale) {
            try {
                Os.setenv("TZ", profile.zone, true);
            } catch (Throwable ignored) {}
            Locale.setDefault(Locale.US);
            LocaleList.setDefault(locales);
            TimeZone.setDefault(zone);

            XposedBridge.hookAllMethods(Locale.class, "getDefault", XC_MethodReplacement.returnConstant(Locale.US));
            XposedBridge.hookAllMethods(LocaleList.class, "getDefault", XC_MethodReplacement.returnConstant(locales));
            XposedBridge.hookAllMethods(LocaleList.class, "getAdjustedDefault", XC_MethodReplacement.returnConstant(locales));
            XposedBridge.hookAllMethods(TimeZone.class, "getDefault", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam x) { x.setResult(zone.clone()); }
            });
            XposedBridge.hookAllMethods(android.icu.util.TimeZone.class, "getDefault", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam x) {
                    x.setResult(android.icu.util.TimeZone.getTimeZone(profile.zone));
                }
            });
        }

        if (profile.enableLocationSpoof) {
            XposedBridge.hookAllMethods(Location.class, "getLatitude", XC_MethodReplacement.returnConstant(profile.latitude));
            XposedBridge.hookAllMethods(Location.class, "getLongitude", XC_MethodReplacement.returnConstant(profile.longitude));
        }

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam x) {
                Context c = (Context) x.args[0];
                appContext = c;
                resolveActiveProxy(c, profile);
                int nativeLockPort = (!isClaude && profile.enableProxyOverride) ? activeProxyPort : 0;
                try {
                    configureNetwork(nativeLockPort, !isClaude && profile.enableProxyOverride, null);
                } catch (Throwable ignored) {}

                if (profile.enableTimezoneLocale) {
                    Configuration config = new Configuration(c.getResources().getConfiguration());
                    config.setLocales(locales);
                    c.getResources().updateConfiguration(config, c.getResources().getDisplayMetrics());
                }
                if (isChrome) {
                    if (profile.enableTimezoneLocale) {
                        hookTimeZoneMonitor(c.getClassLoader());
                    }
                    injectChromeSwitches(c.getClassLoader(), profile);
                }
            }
        });

        if (isVia) {
            installViaHooks(p);
        }
        if (isChrome) {
            installChromeHooks(p);
        }
        if (isClaude) {
            installClaudeHooks(p);
        }

        logNet("Attached to " + p.processName + " (core=ALWAYS_ON, tz=" + profile.enableTimezoneLocale
                + ", carrier=" + profile.enableCarrierSpoof + ", loc=" + profile.enableLocationSpoof
                + ", ua=" + profile.enableUaReduction + ", proxyOverride=" + profile.enableProxyOverride + ")");
    }

    private void installViaHooks(XC_LoadPackage.LoadPackageParam p) {
        try (ZipFile zip = new ZipFile(modulePath);
             InputStream in = zip.getInputStream(zip.getEntry("assets/browser.js"))) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("__PROFILE__", profile.json());
        } catch (Throwable t) {
            XposedBridge.log("ImNotDragon: error loading browser.js: " + t);
        }

        if (profile.enableTimezoneLocale) {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewFactory", null, "getProvider", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam x) throws Throwable {
                    if (timeZoneMonitorHooked) return;
                    ClassLoader loader = x.getResult().getClass().getClassLoader();
                    hookTimeZoneMonitor(loader);
                }
            });
        }

        if (profile.enableUaReduction) {
            XposedHelpers.findAndHookMethod(WebView.class, "setWebContentsDebuggingEnabled", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) { x.args[0] = false; }
            });
        }

        XposedBridge.hookAllConstructors(WebView.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam x) throws Throwable {
                WebView view = (WebView) x.thisObject;
                if (XposedHelpers.getAdditionalInstanceField(view, "imnotdragon.script") != null) return;
                if (script != null && (profile.enableTimezoneLocale || profile.enableLocationSpoof)) {
                    WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton("*"));
                    XposedHelpers.setAdditionalInstanceField(view, "imnotdragon.script", true);
                }
                if (profile.enableLocationSpoof) {
                    view.getSettings().setGeolocationEnabled(false);
                }
                if (profile.enableProxyOverride && !proxySet && activeProxyUrl != null) {
                    ProxyConfig config = new ProxyConfig.Builder().addProxyRule(activeProxyUrl).removeImplicitRules().build();
                    ProxyController.getInstance().setProxyOverride(config, Runnable::run, () ->
                            logNet("[PROXY-ROUTE] Via browser proxy override applied: " + activeProxyUrl));
                    proxySet = true;
                } else if (!proxySet) {
                    logNet("[PROXY-ROUTE] Via browser following TUN/system routing (detectedProxy=" + activeProxyUrl + ")");
                    proxySet = true;
                }
            }
        });

        XposedBridge.hookAllMethods(WebChromeClient.class, "onPermissionRequest", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam x) throws Throwable {
                PermissionRequest request = (PermissionRequest) x.args[0];
                if (request != null) {
                    request.grant(request.getResources());
                    x.setResult(null);
                }
            }
        });

        XposedHelpers.findAndHookMethod(WebView.class, "setWebChromeClient", WebChromeClient.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam x) throws Throwable {
                WebChromeClient client = (WebChromeClient) x.args[0];
                if (client != null && client.getClass() != WebChromeClient.class) {
                    Class<?> clientClass = client.getClass();
                    if (hookedClients.add(clientClass)) {
                        try {
                            XposedBridge.hookAllMethods(clientClass, "onPermissionRequest", new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                    PermissionRequest req = (PermissionRequest) p.args[0];
                                    if (req != null) {
                                        req.grant(req.getResources());
                                        p.setResult(null);
                                    }
                                }
                            });
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                }
            }
        });

        if (profile.enableUaReduction || profile.enableLocationSpoof) {
            if (profile.enableUaReduction) {
                XposedBridge.hookAllMethods(WebSettings.class, "getDefaultUserAgent", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam x) {
                        if (x.getResult() instanceof String) x.setResult(reduceUserAgent((String) x.getResult()));
                    }
                });
            }

            XposedHelpers.findAndHookMethod(WebView.class, "getSettings", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam x) {
                    Object settings = x.getResult();
                    Class<?> type = settings.getClass();
                    if (XposedHelpers.getAdditionalStaticField(type, "imnotdragon.settings") != null) return;
                    XposedHelpers.setAdditionalStaticField(type, "imnotdragon.settings", true);
                    if (profile.enableLocationSpoof) {
                        XposedHelpers.findAndHookMethod(type, "setGeolocationEnabled", boolean.class, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam y) { y.args[0] = false; }
                        });
                    }
                    if (profile.enableUaReduction) {
                        XposedHelpers.findAndHookMethod(type, "setUserAgentString", String.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam y) {
                                if (y.args[0] != null) y.args[0] = reduceUserAgent((String) y.args[0]);
                            }
                        });
                    }
                }
            });
        }
    }

    private void installChromeHooks(XC_LoadPackage.LoadPackageParam p) {
        if (profile.enableTimezoneLocale) {
            hookTimeZoneMonitor(p.classLoader);
        }
        hookChromeCommandLine(p.classLoader, profile);
    }

    private void installClaudeHooks(XC_LoadPackage.LoadPackageParam p) {
        if (profile.enableCarrierSpoof) {
            installClaudeTelephonyHooks(profile);
            installClaudeNetworkInterfaceHooks();
            installClaudeIdentityHooks(p, profile);
        }
    }

    private static void installClaudeTelephonyHooks(Profile prof) {
        String iso = (prof.country != null && !prof.country.isEmpty()) ? prof.country.toLowerCase(Locale.ROOT) : "us";
        String mccmnc = "310410";
        String carrier = "AT&T";
        switch (iso) {
            case "jp": mccmnc = "44010"; carrier = "NTT DOCOMO"; break;
            case "sg": mccmnc = "52501"; carrier = "Singtel"; break;
            case "gb": mccmnc = "23415"; carrier = "Vodafone UK"; break;
            case "de": mccmnc = "26201"; carrier = "Telekom.de"; break;
            case "fr": mccmnc = "20801"; carrier = "Orange F"; break;
            case "nl": mccmnc = "20408"; carrier = "KPN"; break;
            case "kr": mccmnc = "45005"; carrier = "SKTelecom"; break;
            case "tw": mccmnc = "46692"; carrier = "Chunghwa"; break;
            case "au": mccmnc = "50501"; carrier = "Telstra"; break;
            case "ca": mccmnc = "302720"; carrier = "Rogers"; break;
        }
        XposedBridge.hookAllMethods(TelephonyManager.class, "getSimCountryIso", XC_MethodReplacement.returnConstant(iso));
        XposedBridge.hookAllMethods(TelephonyManager.class, "getNetworkCountryIso", XC_MethodReplacement.returnConstant(iso));
        XposedBridge.hookAllMethods(TelephonyManager.class, "getSimOperator", XC_MethodReplacement.returnConstant(mccmnc));
        XposedBridge.hookAllMethods(TelephonyManager.class, "getNetworkOperator", XC_MethodReplacement.returnConstant(mccmnc));
        XposedBridge.hookAllMethods(TelephonyManager.class, "getSimOperatorName", XC_MethodReplacement.returnConstant(carrier));
        XposedBridge.hookAllMethods(TelephonyManager.class, "getNetworkOperatorName", XC_MethodReplacement.returnConstant(carrier));
        XposedBridge.hookAllMethods(TelephonyManager.class, "getPhoneType", XC_MethodReplacement.returnConstant(TelephonyManager.PHONE_TYPE_GSM));
    }

    private static void installClaudeNetworkInterfaceHooks() {
        XposedBridge.hookAllMethods(NetworkInterface.class, "getNetworkInterfaces", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                boolean isTelemetry = false;
                for (StackTraceElement el : stack) {
                    String cls = el.getClassName();
                    if (cls.contains("n87") || cls.contains("sift") || cls.contains("Sift") || cls.contains("MobileEvents")) {
                        isTelemetry = true;
                        break;
                    }
                }
                if (!isTelemetry) return;

                Enumeration<NetworkInterface> en = (Enumeration<NetworkInterface>) param.getResult();
                if (en != null) {
                    List<NetworkInterface> list = new ArrayList<>();
                    while (en.hasMoreElements()) {
                        NetworkInterface nif = en.nextElement();
                        if (nif.isLoopback()) {
                            list.add(nif);
                        }
                    }
                    param.setResult(Collections.enumeration(list));
                }
            }
        });
    }

    private static void installClaudeIdentityHooks(XC_LoadPackage.LoadPackageParam p, Profile profile) {
        final String cleanSegmentId = UUID.nameUUIDFromBytes(
                ("imnotdragon-segment:" + profile.ip + ":" + profile.zone).getBytes(StandardCharsets.UTF_8)).toString();

        try {
            Class<?> prefsImpl = Class.forName("android.app.SharedPreferencesImpl", false, p.classLoader);
            XposedHelpers.findAndHookMethod(prefsImpl, "getString", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("segment.device.id".equals(key) || "segment.anonymousId".equals(key)) {
                        param.setResult(cleanSegmentId);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(prefsImpl, "contains", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("segment.device.id".equals(key) || "segment.anonymousId".equals(key)) {
                        param.setResult(true);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(prefsImpl, "getAll", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Map<String, Object> map = (Map<String, Object>) param.getResult();
                    if (map != null) {
                        Map<String, Object> copy = new HashMap<>(map);
                        if (copy.containsKey("segment.device.id")) copy.put("segment.device.id", cleanSegmentId);
                        if (copy.containsKey("segment.anonymousId")) copy.put("segment.anonymousId", cleanSegmentId);
                        param.setResult(copy);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ImNotDragon: SharedPreferences hook error: " + t);
        }
    }

    private static void hookChromeCommandLine(ClassLoader loader, Profile profile) {
        if (loader == null) return;
        try {
            Class<?> cl = Class.forName("org.chromium.base.CommandLine", false, loader);
            for (Method m : cl.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType() == void.class &&
                    Arrays.equals(m.getParameterTypes(), new Class<?>[]{String[].class})) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String[] original = (String[]) param.args[0];
                            List<String> list = new ArrayList<>();
                            if (original != null && original.length > 0) {
                                list.addAll(Arrays.asList(original));
                            } else {
                                list.add("_");
                            }
                            // Core WebRTC policy always enforced
                            list.add("--webrtc-ip-handling-policy=disable_non_proxied_udp");
                            if (profile.enableProxyOverride && activeProxyUrl != null) {
                                list.add("--proxy-server=" + activeProxyUrl);
                                list.add("--proxy-bypass-list=<-loopback>");
                            }
                            if (profile.enableTimezoneLocale) {
                                list.add("--lang=en-US");
                            }
                            param.args[0] = list.toArray(new String[0]);
                        }
                    });
                }
            }
            injectChromeSwitches(loader, profile);
        } catch (Throwable t) {
            XposedBridge.log("ImNotDragon: hookChromeCommandLine error: " + t);
        }
    }

    private static void injectChromeSwitches(ClassLoader loader, Profile profile) {
        if (loader == null) return;
        try {
            Class<?> cl = Class.forName("org.chromium.base.CommandLine", false, loader);
            Method appendMethod = null;
            for (Method m : cl.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType() == void.class &&
                    Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class, String.class})) {
                    appendMethod = m;
                    break;
                }
            }
            if (appendMethod == null) return;
            appendMethod.setAccessible(true);

            for (Field f : cl.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && cl.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object inst = f.get(null);
                    if (inst != null) {
                        appendMethod.invoke(inst, "webrtc-ip-handling-policy", "disable_non_proxied_udp");
                        if (profile.enableProxyOverride && activeProxyUrl != null) {
                            appendMethod.invoke(inst, "proxy-server", activeProxyUrl);
                            appendMethod.invoke(inst, "proxy-bypass-list", "<-loopback>");
                        }
                        if (profile.enableTimezoneLocale) {
                            appendMethod.invoke(inst, "lang", "en-US");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookTimeZoneMonitor(ClassLoader loader) {
        if (timeZoneMonitorHooked || loader == null) return;
        try {
            Class<?> monitor = Class.forName("org.chromium.device.time_zone_monitor.TimeZoneMonitor", false, loader);
            XposedHelpers.findAndHookMethod(monitor, "getInstance", long.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam y) throws Throwable {
                    Object instance = y.getResult();
                    for (Field f : monitor.getDeclaredFields()) {
                        if (!BroadcastReceiver.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        BroadcastReceiver receiver = (BroadcastReceiver) f.get(instance);
                        XposedHelpers.findAndHookMethod(receiver.getClass(), "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam z) {
                                Intent event = (Intent) z.args[1];
                                if (Intent.ACTION_TIMEZONE_CHANGED.equals(event.getAction())) {
                                    z.args[1] = new Intent(event).putExtra(Intent.EXTRA_TIMEZONE, profile.zone);
                                }
                            }
                        });
                        receiver.onReceive(appContext, new Intent(Intent.ACTION_TIMEZONE_CHANGED)
                                .putExtra(Intent.EXTRA_TIMEZONE, profile.zone));
                    }
                }
            });
            timeZoneMonitorHooked = true;
        } catch (Throwable ignored) {
        }
    }

    private static String reduceUserAgent(String ua) {
        return ua.replaceAll("\\(Linux; Android [^)]*\\)", "(Linux; Android 10; K)")
                .replaceAll("Chrome/(\\d+)\\.\\d+\\.\\d+\\.\\d+", "Chrome/$1.0.0.0");
    }
}
