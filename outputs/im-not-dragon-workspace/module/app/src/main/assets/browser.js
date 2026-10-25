(() => {
    'use strict';
    const profile = __PROFILE__;

    if (profile.enableTimezoneLocale !== false) {
        Object.defineProperty(Navigator.prototype, 'language', {get: () => 'en-US', configurable: false});
        const languages = Object.freeze(['en-US']);
        Object.defineProperty(Navigator.prototype, 'languages', {get: () => languages, configurable: false});
    }

    if (profile.enableLocationSpoof !== false) {
        const coords = Object.freeze({
            latitude: profile.latitude, longitude: profile.longitude,
            accuracy: 5000, altitude: null, altitudeAccuracy: null, heading: null, speed: null,
            toJSON() {return {latitude: this.latitude, longitude: this.longitude, accuracy: this.accuracy,
                altitude: null, altitudeAccuracy: null, heading: null, speed: null};}
        });
        const position = () => ({coords, timestamp: Date.now(), toJSON() {return {coords: coords.toJSON(), timestamp: this.timestamp};}});
        let nextId = 1;
        const watches = new Map();
        const geo = Object.freeze({
            getCurrentPosition(success) {
                if (typeof success !== 'function') throw new TypeError('A position callback is required');
                setTimeout(() => success(position()), 0);
            },
            watchPosition(success) {
                if (typeof success !== 'function') throw new TypeError('A position callback is required');
                const id = nextId++;
                const first = setTimeout(() => success(position()), 0);
                const interval = setInterval(() => success(position()), 10000);
                watches.set(id, [first, interval]); return id;
            },
            clearWatch(id) {
                const timers = watches.get(id);
                if (timers) {clearTimeout(timers[0]); clearInterval(timers[1]); watches.delete(id);}
            }
        });
        Object.defineProperty(Navigator.prototype, 'geolocation', {get: () => geo, configurable: false});
    }
})();
