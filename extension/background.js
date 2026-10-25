importScripts('profile.js');

const { normalize, defaults, parseProxy, reduceUA } = DragonProfile;
let operations = Promise.resolve();
function serialize(task) {
  const operation = operations.then(task);
  operations = operation.catch(error => console.error('[ImNotDragon]', error.message));
  return operation;
}
async function readProfile() {
  const { profile } = await chrome.storage.local.get('profile');
  return normalize(profile || defaults);
}
const controllable = setting => ['controllable_by_this_extension', 'controlled_by_this_extension'].includes(setting.levelOfControl);

async function applySettings(profile) {
  const rtc = chrome.privacy.network.webRTCIPHandlingPolicy;
  const rtcState = await rtc.get({});
  if (profile.enabled) {
    if (controllable(rtcState)) await rtc.set({ value: 'disable_non_proxied_udp', scope: 'regular' });
  } else {
    await rtc.clear({ scope: 'regular' });
  }
  const proxyState = await chrome.proxy.settings.get({ incognito: false });
  if (profile.enabled && profile.enableProxyOverride) {
    if (!controllable(proxyState)) throw new Error('代理设置由其他扩展或浏览器策略控制');
    await chrome.proxy.settings.set({
      scope: 'regular', value: {
        mode: 'fixed_servers',
        rules: { singleProxy: parseProxy(profile.proxy), bypassList: ['<-loopback>'] }
      }
    });
  } else {
    await chrome.proxy.settings.clear({ scope: 'regular' });
  }
  const headers = [];
  if (profile.enabled && profile.enableTimezoneLocale) {
    headers.push({ header: 'accept-language', operation: 'set', value: 'en-US,en;q=0.9' });
  }
  if (profile.enabled && profile.enableUaReduction) {
    headers.push({ header: 'user-agent', operation: 'set', value: reduceUA(navigator.userAgent) });
    for (const header of ['sec-ch-ua-full-version', 'sec-ch-ua-full-version-list', 'sec-ch-ua-model', 'sec-ch-ua-platform-version']) {
      headers.push({ header, operation: 'remove' });
    }
  }
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: [1], addRules: headers.length ? [{
      id: 1, priority: 1,
      action: { type: 'modifyHeaders', requestHeaders: headers },
      condition: { urlFilter: '|http*', resourceTypes: [
        'main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'font', 'object',
        'xmlhttprequest', 'ping', 'csp_report', 'media', 'websocket', 'webtransport', 'other'
      ] }
    }] : []
  });
  if (profile.enabled && profile.autoSync) {
    if (!await chrome.alarms.get('sync-exit')) await chrome.alarms.create('sync-exit', { periodInMinutes: 5 });
  } else {
    await chrome.alarms.clear('sync-exit');
  }
}

async function status() {
  const [rtc, proxy] = await Promise.all([
    chrome.privacy.network.webRTCIPHandlingPolicy.get({}),
    chrome.proxy.settings.get({ incognito: false })
  ]);
  return {
    webrtc: rtc.value === 'disable_non_proxied_udp', webrtcControl: rtc.levelOfControl,
    proxyControl: proxy.levelOfControl, proxyMode: proxy.value.mode
  };
}
async function detectExit() {
  const response = await fetch('https://ipapi.co/json/', {
    cache: 'no-store', signal: AbortSignal.timeout(12000)
  });
  if (!response.ok) throw new Error(`出口查询失败（HTTP ${response.status}）`);
  const data = await response.json();
  if (data.error || typeof data.ip !== 'string' || !data.ip ||
      typeof data.timezone !== 'string' || !data.timezone ||
      !Number.isFinite(data.latitude) || !Number.isFinite(data.longitude)) {
    throw new Error('出口服务未返回完整的时区和定位信息');
  }
  const checked = normalize({ timezone: data.timezone, latitude: data.latitude, longitude: data.longitude });
  return {
    ip: data.ip, city: data.city || '', region: data.region || '',
    country: data.country_name || '', countryCode: data.country_code || '',
    timezone: checked.timezone, latitude: checked.latitude, longitude: checked.longitude,
    lastSync: Date.now(), syncError: ''
  };
}
async function syncExit() {
  const profile = await readProfile();
  try {
    const detected = await detectExit();
    const updated = normalize({ ...profile, ...detected });
    await chrome.storage.local.set({ profile: updated });
    return updated;
  } catch (error) {
    await chrome.storage.local.set({ profile: { ...profile, syncError: error.message } });
    throw error;
  }
}
async function initialize() {
  const profile = await readProfile();
  await applySettings(profile);
  await chrome.storage.local.set({ profile });
}
chrome.runtime.onInstalled.addListener(() => { serialize(initialize); });
chrome.runtime.onStartup.addListener(() => {
  serialize(async () => {
    await initialize();
    const profile = await readProfile();
    if (profile.enabled && profile.autoSync) await syncExit();
  });
});
chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === 'sync-exit') serialize(async () => {
    const profile = await readProfile();
    if (profile.enabled && profile.autoSync) await syncExit();
  });
});
chrome.runtime.onMessage.addListener((request, sender, respond) => {
  if (sender.id !== chrome.runtime.id) return;
  const action = request?.action;
  if (!['get_state', 'update_profile', 'sync_exit'].includes(action)) return;
  if (action !== 'get_state' && !sender.url?.startsWith(chrome.runtime.getURL(''))) return;
  const operation = action === 'get_state'
    ? operations.then(async () => ({ profile: await readProfile(), status: await status() }))
    : serialize(async () => {
      if (action === 'sync_exit') return { profile: await syncExit(), status: await status() };
      const previous = await readProfile();
      const profile = normalize({ ...previous, ...request.patch });
      try {
        await applySettings(profile);
        await chrome.storage.local.set({ profile });
      } catch (error) {
        await applySettings(previous);
        throw error;
      }
      return { profile, status: await status() };
    });
  operation.then(result => respond({ success: true, ...result }))
    .catch(error => respond({ success: false, error: error.message }));
  return true;
});
