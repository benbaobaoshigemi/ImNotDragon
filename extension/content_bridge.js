(() => {
  function publish(profile) {
    if (!profile) return;
    const pageProfile = Object.fromEntries([
      'enabled', 'enableTimezoneLocale', 'enableLocationSpoof', 'enableUaReduction',
      'timezone', 'locale', 'languages', 'latitude', 'longitude'
    ].map(key => [key, profile[key] ?? DragonProfile.defaults[key]]));
    window.dispatchEvent(new CustomEvent('__IM_NOT_DRAGON_PROFILE__', { detail: JSON.stringify(pageProfile) }));
  }
  async function publishSaved() {
    const { profile } = await chrome.storage.local.get('profile');
    publish(profile || DragonProfile.defaults);
  }
  window.addEventListener('__IM_NOT_DRAGON_READY__', () => {
    publishSaved().catch(error => console.error('[ImNotDragon]', error.message));
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.profile) publish(changes.profile.newValue || DragonProfile.defaults);
  });
  publishSaved().catch(error => console.error('[ImNotDragon]', error.message));
})();
