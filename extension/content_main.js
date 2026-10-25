(() => {
  const NativeDate = Date;
  const NativeFormat = Intl.DateTimeFormat;
  const dateMethods = Object.fromEntries(Object.getOwnPropertyNames(NativeDate.prototype)
    .filter(key => typeof NativeDate.prototype[key] === 'function').map(key => [key, NativeDate.prototype[key]]));
  const nativeParse = NativeDate.parse;
  const nativeUTC = NativeDate.UTC;
  const nativeUA = navigator.userAgent;
  const nativeAppVersion = navigator.appVersion;
  const nativeLanguage = navigator.language;
  const nativeLanguages = navigator.languages;
  let profile = null;
  let wallFormatter;
  let nameFormatter;
  const timeEnabled = () => profile?.enabled && profile.enableTimezoneLocale;
  const locationEnabled = () => profile?.enabled && profile.enableLocationSpoof;
  const uaEnabled = () => profile?.enabled && profile.enableUaReduction;
  const call = (name, date, ...args) => Reflect.apply(dateMethods[name], date, args);
  function replace(object, key, value) {
    Object.defineProperty(object, key, { ...Object.getOwnPropertyDescriptor(object, key), value });
  }

  // Wall-clock arithmetic uses saved UTC methods, never the overwritten local methods.
  function wallTime(timestamp) {
    if (!Number.isFinite(timestamp)) return NaN;
    const parts = Object.fromEntries(wallFormatter.formatToParts(timestamp)
      .filter(part => part.type !== 'literal').map(part => [part.type, part.value]));
    const date = new NativeDate(0);
    call('setUTCFullYear', date, parts.era === 'BC' ? 1 - Number(parts.year) : Number(parts.year), Number(parts.month) - 1, Number(parts.day));
    call('setUTCHours', date, Number(parts.hour), Number(parts.minute), Number(parts.second),
      call('getUTCMilliseconds', new NativeDate(timestamp)));
    return call('getTime', date);
  }
  function offsetAt(timestamp) { return (timestamp - wallTime(timestamp)) / 60000; }
  function instantFromWall(wall) {
    if (!Number.isFinite(wall)) return NaN;
    // Sample both sides of a transition. Repeated times choose the earlier instant;
    // missing times advance by the gap, matching Date's compatible disambiguation.
    const offsets = new Set([-2, -1, 0, 1, 2].map(days => offsetAt(wall + days * 86400000)));
    const candidates = [...offsets].map(offset => wall + offset * 60000);
    const exact = candidates.filter(timestamp => wallTime(timestamp) === wall);
    if (exact.length) return Math.min(...exact);
    return candidates.filter(timestamp => wallTime(timestamp) > wall)
      .sort((a, b) => wallTime(a) - wallTime(b))[0];
  }
  function parseLocal(text) {
    const isoLocal = /^([+-]\d{6}|\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)(?::(\d\d)(?:\.(\d{1,3}))?)?$/.exec(text);
    if (isoLocal) {
      const parsed = nativeParse(`${text}Z`);
      return instantFromWall(parsed);
    }
    const parsed = nativeParse(text);
    // ISO date-only strings are UTC; explicit offsets and named zones retain native parsing.
    if (!Number.isFinite(parsed) || /^(?:\d{4}|[+-]\d{6})(?:-\d\d(?:-\d\d)?)?$/.test(text.trim()) ||
        /(?:[zZ]|[+-]\d\d:?\d\d)\s*$/.test(text) || /\b(?:GMT|UTC|[ECMP][SD]T)\b/i.test(text)) return parsed;
    const date = new NativeDate(parsed);
    const wall = new NativeDate(0);
    call('setUTCFullYear', wall, call('getFullYear', date), call('getMonth', date), call('getDate', date));
    call('setUTCHours', wall, call('getHours', date), call('getMinutes', date), call('getSeconds', date), call('getMilliseconds', date));
    return instantFromWall(call('getTime', wall));
  }
  for (const component of ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds']) {
    replace(NativeDate.prototype, `get${component}`, function () {
      if (!timeEnabled()) return call(`get${component}`, this);
      return call(`getUTC${component}`, new NativeDate(wallTime(call('getTime', this))));
    });
  }
  replace(NativeDate.prototype, 'getYear', function () {
    return timeEnabled() ? this.getFullYear() - 1900 : call('getYear', this);
  });
  replace(NativeDate.prototype, 'getTimezoneOffset', function () {
    return timeEnabled() ? offsetAt(call('getTime', this)) : call('getTimezoneOffset', this);
  });
  for (const component of ['FullYear', 'Month', 'Date', 'Hours', 'Minutes', 'Seconds', 'Milliseconds']) {
    replace(NativeDate.prototype, `set${component}`, function (...args) {
      if (!timeEnabled()) return call(`set${component}`, this, ...args);
      const timestamp = call('getTime', this);
      const wall = new NativeDate(wallTime(timestamp));
      if (component === 'FullYear' && Number.isNaN(timestamp)) call('setTime', wall, 0);
      call(`setUTC${component}`, wall, ...args);
      return call('setTime', this, instantFromWall(call('getTime', wall)));
    });
  }
  replace(NativeDate.prototype, 'setYear', function (year) {
    if (!timeEnabled()) return call('setYear', this, year);
    let value = +year;
    if (value >= 0 && value <= 99) value = Math.trunc(value) + 1900;
    return this.setFullYear(value);
  });
  function dateString(date, includeDate, includeTime) {
    const timestamp = call('getTime', date);
    if (!Number.isFinite(timestamp)) return 'Invalid Date';
    const wall = new NativeDate(wallTime(timestamp));
    const day = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][call('getUTCDay', wall)];
    const month = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][call('getUTCMonth', wall)];
    const pad = number => String(number).padStart(2, '0');
    const datePart = `${day} ${month} ${pad(call('getUTCDate', wall))} ${String(call('getUTCFullYear', wall)).padStart(4, '0')}`;
    const offset = offsetAt(timestamp);
    const minutes = Math.abs(Math.trunc(offset));
    const zone = nameFormatter.formatToParts(timestamp).find(part => part.type === 'timeZoneName').value;
    const timePart = `${pad(call('getUTCHours', wall))}:${pad(call('getUTCMinutes', wall))}:${pad(call('getUTCSeconds', wall))} GMT${offset <= 0 ? '+' : '-'}${pad(Math.floor(minutes / 60))}${pad(minutes % 60)} (${zone})`;
    return [includeDate ? datePart : '', includeTime ? timePart : ''].filter(Boolean).join(' ');
  }
  for (const [method, date, time] of [['toString', true, true], ['toDateString', true, false], ['toTimeString', false, true]]) {
    replace(NativeDate.prototype, method, function () {
      return timeEnabled() ? dateString(this, date, time) : call(method, this);
    });
  }
  function formatOptions(options) {
    if (options === null) throw new TypeError('Cannot convert undefined or null to object');
    const result = options === undefined ? {} : Object.create(Object(options));
    if (result.timeZone === undefined) Object.defineProperty(result, 'timeZone', { value: profile.timezone, enumerable: true });
    return result;
  }
  for (const method of ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString']) {
    replace(NativeDate.prototype, method, function (locales, options) {
      return timeEnabled()
        ? call(method, this, locales === undefined ? profile.locale : locales, formatOptions(options))
        : call(method, this, ...arguments);
    });
  }
  const WrappedDate = new Proxy(NativeDate, {
    apply(target, receiver, args) {
      return timeEnabled() ? dateString(new NativeDate(), true, true) : Reflect.apply(target, receiver, args);
    },
    construct(target, args, newTarget) {
      if (timeEnabled()) {
        if (args.length >= 2) args = [instantFromWall(nativeUTC(...args))];
        else if (typeof args[0] === 'string') args = [parseLocal(args[0])];
      }
      return Reflect.construct(target, args, newTarget);
    }
  });
  replace(NativeDate, 'parse', function (text) {
    return timeEnabled() ? parseLocal(String(text)) : nativeParse(text);
  });
  replace(NativeDate.prototype, 'constructor', WrappedDate);
  window.Date = WrappedDate;
  window.Intl.DateTimeFormat = new Proxy(NativeFormat, {
    apply(target, receiver, args) {
      if (timeEnabled()) return new NativeFormat(args[0] === undefined ? profile.locale : args[0], formatOptions(args[1]));
      return Reflect.apply(target, receiver, args);
    },
    construct(target, args, newTarget) {
      if (timeEnabled()) args = [args[0] === undefined ? profile.locale : args[0], formatOptions(args[1])];
      return Reflect.construct(target, args, newTarget);
    }
  });
  const navigatorProto = Object.getPrototypeOf(navigator);
  for (const [key, getter] of Object.entries({
    language: () => timeEnabled() ? profile.locale : nativeLanguage,
    languages: () => timeEnabled() ? Object.freeze([...profile.languages]) : nativeLanguages,
    userAgent: () => uaEnabled() ? reduceUA(nativeUA) : nativeUA,
    appVersion: () => uaEnabled() ? reduceUA(nativeAppVersion) : nativeAppVersion
  })) Object.defineProperty(navigatorProto, key, {
    ...Object.getOwnPropertyDescriptor(navigatorProto, key), get: getter
  });
  function reduceUA(value) {
    return value.replace(/\(Linux; Android [^)]*\)/, '(Linux; Android 10; K)')
      .replace(/((?:Chrome|Chromium|Edg|OPR)\/\d+)\.\d+\.\d+\.\d+/g, '$1.0.0.0');
  }
  if (navigator.userAgentData) {
    const dataProto = Object.getPrototypeOf(navigator.userAgentData);
    const originalEntropy = dataProto.getHighEntropyValues;
    replace(dataProto, 'getHighEntropyValues', async function (...args) {
      const result = await Reflect.apply(originalEntropy, this, args);
      if (!uaEnabled()) return result;
      if ('uaFullVersion' in result) result.uaFullVersion = `${result.uaFullVersion.split('.')[0]}.0.0.0`;
      if (result.fullVersionList) result.fullVersionList = result.fullVersionList.map(item => ({ ...item, version: `${item.version.split('.')[0]}.0.0.0` }));
      if ('model' in result) result.model = /Android/.test(nativeUA) ? 'K' : '';
      if ('platformVersion' in result) result.platformVersion = /Android/.test(nativeUA) ? '10.0.0' : '';
      return result;
    });
  }

  const geo = navigator.geolocation;
  const watches = new Map();
  let watchId = 1;
  let restartWatches = () => {};
  if (geo) {
    const proto = Object.getPrototypeOf(geo);
    const original = { get: proto.getCurrentPosition, watch: proto.watchPosition, clear: proto.clearWatch };
    function position() {
      const coords = {
        latitude: profile.latitude, longitude: profile.longitude, accuracy: 5000,
        altitude: null, altitudeAccuracy: null, heading: null, speed: null,
        toJSON() { const { toJSON, ...data } = this; return data; }
      };
      return { coords, timestamp: NativeDate.now(), toJSON() { return { coords: coords.toJSON(), timestamp: this.timestamp }; } };
    }
    replace(proto, 'getCurrentPosition', function (success, error, options) {
      if (!locationEnabled()) return Reflect.apply(original.get, this, arguments);
      if (typeof success !== 'function') throw new TypeError('A position callback is required');
      setTimeout(() => {
        if (locationEnabled()) success(position());
        else Reflect.apply(original.get, this, [success, error, options]);
      }, 0);
    });
    function stop(record) {
      clearTimeout(record.first);
      clearInterval(record.interval);
      if (record.native !== undefined) Reflect.apply(original.clear, geo, [record.native]);
      record.native = undefined;
    }
    function start(record) {
      if (!locationEnabled()) {
        record.native = Reflect.apply(original.watch, geo, [record.success, record.error, record.options]);
      } else {
        record.first = setTimeout(() => record.success(position()), 0);
        record.interval = setInterval(() => record.success(position()), 10000);
      }
    }
    replace(proto, 'watchPosition', function (success, error, options) {
      if (typeof success !== 'function') throw new TypeError('A position callback is required');
      const id = watchId++;
      const record = { success, error, options };
      watches.set(id, record);
      start(record);
      return id;
    });
    replace(proto, 'clearWatch', function (id) {
      const record = watches.get(Number(id));
      if (record) { stop(record); watches.delete(Number(id)); }
    });
    restartWatches = () => { for (const record of watches.values()) { stop(record); start(record); } };
  }

  window.addEventListener('__IM_NOT_DRAGON_PROFILE__', event => {
    try {
      const updated = JSON.parse(event.detail);
      const nextWall = new NativeFormat('en-US-u-ca-gregory-nu-latn', {
        timeZone: updated.timezone, era: 'short', year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
      });
      const nextName = new NativeFormat('en-US', { timeZone: updated.timezone, timeZoneName: 'long' });
      profile = updated;
      wallFormatter = nextWall;
      nameFormatter = nextName;
      restartWatches();
    } catch (error) { console.error('[ImNotDragon] Invalid profile:', error.message); }
  });
  window.dispatchEvent(new Event('__IM_NOT_DRAGON_READY__'));
})();
