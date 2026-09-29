// Off-browser unit check of the Web UI auth helpers extracted from index.html.
// Runs the real inline script in a vm context with DOM/fetch stubs, then asserts behaviour.
// From the repo root:
//   node server/tests/webui_auth_harness.mjs server/entry_recorder_server/static/index.html
// Prints one PASS/FAIL line per check and exits non-zero if any failed. Needs Node 18+.
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const htmlPath = process.argv[2];
const html = readFileSync(htmlPath, 'utf8');
// Drop the trailing auto-load/poll block: it would fire real requests against the stubs.
let script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).join('\n;\n');
script = script.replace(/\/\/ Auto load and poll[\s\S]*$/, '');
if (!script) { console.error('no inline script'); process.exit(1); }

const elements = new Map();
const makeEl = () => ({
    textContent: '', innerHTML: '', value: '', src: '', style: {}, href: '',
    error: null, onerror: null,
    classList: { add() {}, remove() {} },
    pause() {},
});
// Stable per-id stubs so the checks can read back what the modal wrote.
const byId = id => {
    if (!elements.has(id)) elements.set(id, makeEl());
    return elements.get(id);
};

const state = { prompts: [], alerts: [], requests: [], responses: [], nextPromptValue: '' };

const storage = new Map();
const context = {
    console,
    setInterval: () => 0,
    setTimeout: () => 0,
    Headers,
    URLSearchParams,
    localStorage: {
        getItem: k => (storage.has(k) ? storage.get(k) : null),
        setItem: (k, v) => storage.set(k, String(v)),
        removeItem: k => storage.delete(k),
    },
    location: { search: '' },
    alert: msg => state.alerts.push(msg),
    confirm: () => false,
    document: { getElementById: byId, addEventListener() {}, querySelectorAll: () => [] },
    fetch: async (path, opts) => {
        state.requests.push({ path, opts });
        const plan = state.responses.shift() || { status: 200, body: {} };
        return {
            ok: plan.status >= 200 && plan.status < 300,
            status: plan.status,
            headers: new Headers(plan.headers || {}),
            json: async () => plan.body,
            text: async () => JSON.stringify(plan.body),
        };
    },
};
context.window = context;
context.window.prompt = (msg, def) => {
    state.prompts.push(msg);
    return state.nextPromptValue ?? def;
};

vm.createContext(context);
vm.runInContext(script, context);

const results = [];
const check = (name, cond, extra = '') => results.push(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? ' — ' + extra : ''}`);

// 1. media URLs get the key as a query parameter
state.nextPromptValue = 's3cret key';
const q = context.apiUrl('/api/recordings/7/video');
check('prompt asked once for the key', state.prompts.length === 1, `prompts=${state.prompts.length}`);
check('key persisted in localStorage', storage.get('entryRecorderApiKey') === 's3cret key');
check('relative media URL gets ?api_key=', q === '/api/recordings/7/video?api_key=s3cret%20key', q);
const amp = context.apiUrl('/api/recordings/7/protect?is_protected=true');
check('existing query uses &api_key=', amp === '/api/recordings/7/protect?is_protected=true&api_key=s3cret%20key', amp);
check('null URL passes through', context.apiUrl(null) === null);

// 2. API calls carry X-API-Key and no query param
state.requests.length = 0;
await context.apiFetch('/api/status', { method: 'GET' });
const hdr = new Headers(state.requests[0].opts.headers);
check('X-API-Key header sent', hdr.get('X-API-Key') === 's3cret key', hdr.get('X-API-Key'));
check('path untouched by apiFetch', state.requests[0].path === '/api/status');
state.requests.length = 0;
await context.apiFetch('/api/devices', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
const hdr2 = new Headers(state.requests[0].opts.headers);
check('POST keeps Content-Type + adds key', hdr2.get('content-type') === 'application/json' && hdr2.get('X-API-Key') === 's3cret key');

// 3. a 401 re-prompts once and retries
storage.set('entryRecorderApiKey', 'stale');
state.prompts.length = 0;
state.requests.length = 0;
state.responses.push({ status: 401 }, { status: 200, body: { ok: 1 } });
state.nextPromptValue = 'newkey';
const retryRes = await context.apiFetch('/api/recordings?limit=200');
check('401 then retry succeeds', retryRes.status === 200 && state.requests.length === 2, `requests=${state.requests.length}`);
check('re-prompt happened', state.prompts.length === 1);
check('new key stored', storage.get('entryRecorderApiKey') === 'newkey');

// 4. two consecutive 401s lock auth and stop prompting
storage.set('entryRecorderApiKey', 'bad');
state.prompts.length = 0;
state.alerts.length = 0;
state.requests.length = 0;
state.responses.push({ status: 401 }, { status: 401 });
await context.apiFetch('/api/status');
check('rejected key alerts the user', state.alerts.length === 1, state.alerts[0] || '');
state.requests.length = 0;
state.prompts.length = 0;
await context.apiFetch('/api/status');
check('locked state does not re-prompt', state.prompts.length === 0 && state.requests.length === 1);
check('locked state clears the key', storage.get('entryRecorderApiKey') === undefined || storage.get('entryRecorderApiKey') === '');

// 5. 🔑 reset unlocks and re-asks
// resetApiKey() fires its own loadData(); let it settle on a harmless queue first.
const settle = () => new Promise(r => setTimeout(r, 25));
state.responses.push({ status: 200, body: {} }, { status: 200, body: [] });
context.resetApiKey();
await settle();
check('reset clears the lock', state.prompts.length === 1, `prompts=${state.prompts.length}`);

// 6. end-to-end render: loadData() must inject the key into every media URL of the cards
storage.delete('entryRecorderApiKey');
state.nextPromptValue = 'k1';
state.alerts.length = 0;
state.requests.length = 0;
const rec = {
    id: 7, device_name: 'Cam', event_type: 'MANUAL', timestamp: 1, duration_seconds: 5,
    file_size_bytes: 1024, video_url: '/api/recordings/7/video',
    thumbnail_url: '/api/recordings/7/thumbnail', note: null, is_protected: false,
};
state.responses.push({ status: 200, body: { version: '1.0.0', active_recordings_count: 0, total_recordings_count: 1, total_storage_bytes: 1, active_recordings: [] } });
state.responses.push({ status: 200, body: [rec] });
await vm.runInContext('loadData()', context);
check('loadData used the stored key', state.alerts.length === 0, state.alerts[0] || '');
const gridHtml = byId('recordings-grid').innerHTML;
check('thumbnail img carries api_key', gridHtml.includes('src="/api/recordings/7/thumbnail?api_key=k1"'));
check('download link carries api_key', gridHtml.includes('href="/api/recordings/7/video?api_key=k1"'));

// 6b. live cameras: the MJPEG <img> URL must be authenticated too
state.responses.push({ status: 200, body: [{ id: 3, name: 'Door', live_url: '/api/live/3/mjpeg', live_mode: 'rtsp', rtsp_url: null, snapshot_url: null, username: null, password: null }] });
await vm.runInContext('loadDevices()', context);
const liveHtml = byId('live-cameras-grid').innerHTML;
check('live img carries api_key', liveHtml.includes('src="/api/live/3/mjpeg?api_key=k1"'), liveHtml.slice(liveHtml.indexOf('live-img'), liveHtml.indexOf('live-img') + 90));

// 7. playback probe: an available file wires <video> with the keyed URL, no fallback
state.responses.push({ status: 200 });
await vm.runInContext('openPlayerModal(7)', context);
check('inline <video> gets the keyed URL', byId('video-element').src === '/api/recordings/7/video?api_key=k1', byId('video-element').src);
check('fallback stays hidden on success', byId('playback-warn').style.display === 'none');

// 8. an auth/file rejection is reported as a server error, never as "browser cannot play this"
state.responses.push({ status: 404 });
await vm.runInContext('openPlayerModal(7)', context);
const detail404 = byId('playback-warn-detail').textContent;
check('HTTP 404 shows the fallback', byId('playback-warn').style.display === 'block' && detail404.includes('404'), detail404);
check('404 is not labelled a player error', !detail404.includes('player error'));

// 9. an unexpected probe status (eg HEAD not permitted) must still attempt playback
state.responses.push({ status: 405 });
byId('video-element').src = '';
await vm.runInContext('openPlayerModal(7)', context);
check('405 probe still attempts inline playback', byId('video-element').src === '/api/recordings/7/video?api_key=k1', byId('video-element').src);

// 10. a genuine container rejection reports its MediaError code
byId('video-element').error = { code: 4 };
vm.runInContext('onVideoElementError()', context);
const detailCodec = byId('playback-warn-detail').textContent;
check('container failure reports player error 4', detailCodec.includes('player error 4: source not supported'), detailCodec);
check('download link points at the keyed URL', byId('playback-download-link').href === '/api/recordings/7/video?api_key=k1', byId('playback-download-link').href);

console.log(results.join('\n'));
const failed = results.filter(r => r.startsWith('FAIL')).length;
console.log(`\n${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
