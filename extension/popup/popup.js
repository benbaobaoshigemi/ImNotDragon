const form = document.getElementById('settings');
const flags = ['enableTimezoneLocale', 'enableLocationSpoof', 'enableUaReduction', 'enableProxyOverride', 'autoSync'];
const fields = ['timezone', 'latitude', 'longitude', 'proxy'];
const byId = id => document.getElementById(id);
let current;
let busy = false;
let dirty = false;

function message(text, error = false) {
  byId('message').textContent = text;
  byId('message').classList.toggle('error', error);
}
async function send(action, extra = {}) {
  const result = await chrome.runtime.sendMessage({ action, ...extra });
  if (!result?.success) throw new Error(result?.error || '扩展后台未响应，请重新加载扩展');
  return result;
}
function setBusy(value) {
  busy = value;
  form.querySelectorAll('input, button').forEach(element => { element.disabled = value; });
}
function summary(profile) {
  byId('exit-ip').textContent = profile.ip || '尚未同步出口';
  byId('exit-region').textContent = [profile.city, profile.countryCode].filter(Boolean).join(' · ') || '手动配置';
  byId('last-sync').textContent = profile.lastSync ? `${new Date(profile.lastSync).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })} 已同步` : '';
}
function render(state, populate = true) {
  current = state.profile;
  if (populate) {
    for (const key of flags) byId(key).checked = current[key];
    for (const key of fields) byId(key).value = current[key];
    byId('enabled').checked = current.enabled;
    dirty = false;
  }
  byId('master-label').textContent = current.enabled ? '已开启' : '已暂停';
  byId('proxy-field').hidden = !byId('enableProxyOverride').checked;
  summary(current);
  const controlled = state.status.webrtcControl === 'controlled_by_this_extension';
  const protectedRTC = state.status.webrtc;
  byId('core-status').className = `badge${protectedRTC && current.enabled ? ' active' : current.enabled ? ' blocked' : ''}`;
  byId('core-status').textContent = !current.enabled ? '已暂停' : protectedRTC ? '已生效' : '未生效';
  byId('core-detail').textContent = !current.enabled ? '已释放本扩展对浏览器网络设置的控制。'
    : !protectedRTC ? 'WebRTC 策略未生效，请检查浏览器策略或其他扩展。'
    : controlled ? '浏览器策略已应用；IPv6 路由仍由 VPN 或代理管理。' : '当前策略由浏览器或其他扩展提供。';
}
function patchFromForm() {
  const candidate = DragonProfile.normalize({
    ...current,
    ...Object.fromEntries(flags.map(key => [key, byId(key).checked])),
    ...Object.fromEntries(fields.map(key => [key, byId(key).value])),
    enabled: byId('enabled').checked
  });
  // Only editable fields belong in a save; an alarm may have refreshed exit data.
  return Object.fromEntries([...flags, ...fields, 'enabled'].map(key => [key, candidate[key]]));
}
async function save() {
  const patch = patchFromForm();
  const state = await send('update_profile', { patch });
  render(state);
  return state;
}
form.addEventListener('input', event => {
  if (busy || !current || event.target.id === 'enabled') return;
  dirty = true;
  byId('proxy-field').hidden = !byId('enableProxyOverride').checked;
  message('有未保存的更改');
});
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || !form.reportValidity()) return;
  setBusy(true);
  try { await save(); message('配置已保存，刷新网页完整应用。'); }
  catch (error) { message(error.message, true); }
  finally { setBusy(false); }
});
byId('enabled').addEventListener('change', async () => {
  if (busy || !current) return;
  const enabled = byId('enabled').checked;
  setBusy(true);
  try {
    const state = await send('update_profile', { patch: { enabled } });
    render(state, !dirty);
    message(enabled ? '已开启，刷新网页完整应用。' : '已暂停，刷新网页恢复原始环境。');
  } catch (error) {
    byId('enabled').checked = current.enabled;
    message(error.message, true);
  } finally { setBusy(false); }
});
byId('sync').addEventListener('click', async () => {
  if (busy || !form.reportValidity()) return;
  setBusy(true);
  byId('sync').classList.add('loading');
  message('正在查询当前浏览器出口…');
  try {
    await save();
    render(await send('sync_exit'));
    message('出口已同步并保存，刷新网页完整应用。');
  } catch (error) { message(error.message, true); }
  finally { setBusy(false); byId('sync').classList.remove('loading'); }
});
chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== 'local' || !changes.profile || busy || !current) return;
  send('get_state').then(state => {
    render(state, !dirty);
    if (state.profile.syncError) message(state.profile.syncError, true);
  }).catch(error => message(error.message, true));
});
send('get_state').then(state => {
  render(state);
  setBusy(false);
  message(state.profile.syncError || '设置对所有可注入的网页生效。', Boolean(state.profile.syncError));
}).catch(error => message(error.message, true));
