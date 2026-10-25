// Shared configuration for the service worker and popup.
globalThis.DragonProfile = (() => {
  const defaults = {
    enabled: true, autoSync: false,
    enableTimezoneLocale: true, enableLocationSpoof: true,
    enableUaReduction: true, enableProxyOverride: false,
    timezone: 'America/Los_Angeles', locale: 'en-US', languages: ['en-US', 'en'],
    latitude: 37.7749, longitude: -122.4194,
    proxy: 'auto', ip: '', city: '', region: '', country: '', countryCode: '',
    lastSync: 0, syncError: ''
  };
  function normalize(value = {}) {
    const result = { ...defaults };
    for (const key of Object.keys(defaults)) {
      if (value[key] !== undefined) result[key] = value[key];
    }
    for (const key of ['enabled', 'autoSync', 'enableTimezoneLocale', 'enableLocationSpoof', 'enableUaReduction', 'enableProxyOverride']) {
      if (typeof result[key] !== 'boolean') throw new Error('无效的功能开关');
    }
    result.timezone = new Intl.DateTimeFormat('en-US', { timeZone: result.timezone }).resolvedOptions().timeZone;
    result.locale = 'en-US';
    result.languages = ['en-US', 'en'];
    for (const [key, limit] of [['latitude', 90], ['longitude', 180]]) {
      if (result[key] === '' || result[key] === null) throw new Error('请填写经纬度');
      result[key] = Number(result[key]);
      if (!Number.isFinite(result[key]) || Math.abs(result[key]) > limit) throw new Error('经纬度超出有效范围');
    }
    result.proxy = String(result.proxy).trim() || 'auto';
    if (result.proxy !== 'auto') parseProxy(result.proxy);
    if (result.enableProxyOverride && result.proxy === 'auto') throw new Error('开启代理接管前，请填写代理地址');
    return result;
  }
  function parseProxy(value) {
    let url;
    try { url = new URL(value); } catch { throw new Error('代理地址格式：http://127.0.0.1:7890'); }
    const scheme = url.protocol.slice(0, -1);
    if (!['http', 'https', 'socks5'].includes(scheme) || !url.hostname ||
        url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
      throw new Error('请使用不含账号密码的 HTTP、HTTPS 或 SOCKS5 代理地址');
    }
    const port = Number(url.port || (scheme === 'https' ? 443 : scheme === 'http' ? 80 : 1080));
    return { scheme, host: url.hostname.replace(/^\[|\]$/g, ''), port };
  }
  function reduceUA(value) {
    return value.replace(/\(Linux; Android [^)]*\)/, '(Linux; Android 10; K)')
      .replace(/((?:Chrome|Chromium|Edg|OPR)\/\d+)\.\d+\.\d+\.\d+/g, '$1.0.0.0');
  }
  return { defaults, normalize, parseProxy, reduceUA };
})();
