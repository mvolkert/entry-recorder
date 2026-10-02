        let allRecordings = [];
        let allDevices = [];
        let activeRecordingsByDevice = {};

        // Auth is mandatory on every endpoint. API calls carry the key as the X-API-Key header;
        // media loaded by <img>/<video>/<a href> cannot send a header, so those URLs get the
        // `api_key` query parameter the server also accepts. The key is kept in localStorage and
        // asked for once per browser (also accepted from ?api_key= in the page URL).
        const API_KEY_STORAGE = 'entryRecorderApiKey';
        // Set after a retry still fails, so the 5s poll does not re-prompt endlessly.
        let authLocked = false;

        function getApiKey(forcePrompt = false) {
            if (forcePrompt) {
                localStorage.removeItem(API_KEY_STORAGE);
                authLocked = false;
            }
            if (authLocked) return '';
            let key = localStorage.getItem(API_KEY_STORAGE) || '';
            if (!key && !forcePrompt) {
                key = new URLSearchParams(location.search).get('api_key') || '';
                if (key) localStorage.setItem(API_KEY_STORAGE, key);
            }
            if (!key) {
                key = (window.prompt(
                    'Entry Recorder Server – API key required.\n' +
                    'The key is the API_KEY from the server .env, or the generated one printed in\n' +
                    'the server log and stored in data/.api_key:',
                    ''
                ) || '').trim();
                if (key) localStorage.setItem(API_KEY_STORAGE, key);
            }
            return key;
        }

        function resetApiKey() {
            getApiKey(true);
            loadData();
        }

        function apiUrl(path) {
            if (!path) return path;
            const sep = path.includes('?') ? '&' : '?';
            return `${path}${sep}api_key=${encodeURIComponent(getApiKey())}`;
        }

        async function apiFetch(path, options = {}) {
            const build = () => {
                const headers = new Headers(options.headers || {});
                headers.set('X-API-Key', getApiKey());
                return { ...options, headers };
            };
            let res = await fetch(path, build());
            // A stale/changed key on the server: re-ask once instead of silently showing empty data.
            if (res.status === 401 && !authLocked) {
                getApiKey(true);
                res = await fetch(path, build());
                if (res.status === 401) {
                    authLocked = true;
                    localStorage.removeItem(API_KEY_STORAGE);
                    alert('The API key was rejected by the server. Click 🔑 in the header to enter it again.');
                }
            }
            return res;
        }

        function formatBytes(bytes) {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        }

        function formatTimestamp(ms) {
            const date = new Date(ms);
            return date.toLocaleString();
        }

        async function loadData() {
            try {
                const statusRes = await apiFetch('/api/status');
                if (!statusRes.ok) return;
                const status = await statusRes.json();

                document.getElementById('stat-active').textContent = status.active_recordings_count;
                document.getElementById('stat-total').textContent = status.total_recordings_count;
                document.getElementById('stat-storage').textContent = formatBytes(status.total_storage_bytes);
                document.getElementById('stat-version').textContent = 'v' + status.version;

                // Render active
                const activeSec = document.getElementById('active-recordings-section');
                const activeList = document.getElementById('active-recordings-list');
                activeRecordingsByDevice = {};
                if (status.active_recordings && status.active_recordings.length > 0) {
                    activeSec.style.display = 'block';
                    status.active_recordings.forEach(rec => { activeRecordingsByDevice[rec.device_id] = rec; });
                    activeList.innerHTML = status.active_recordings.map(rec => `
                        <div class="active-card">
                            <div>
                                <span class="recording-indicator"></span>
                                <strong>${rec.device_name}</strong> &bull; ${rec.event_type} (${rec.elapsed_seconds}s / max ${rec.max_duration_seconds}s)
                            </div>
                            <button class="btn btn-danger" onclick="stopRecording(${rec.device_id})">Stop</button>
                        </div>
                    `).join('');
                } else {
                    activeSec.style.display = 'none';
                    activeList.innerHTML = '';
                }

                renderLiveCameras();

                // Load recordings
                const recRes = await apiFetch('/api/recordings?limit=200');
                if (!recRes.ok) return;
                allRecordings = await recRes.json();
                filterRecordings();
            } catch (err) {
                console.error("Failed to load server data", err);
            }
        }

        function filterRecordings() {
            const query = document.getElementById('filter-search').value.toLowerCase();
            const eventType = document.getElementById('filter-event').value;

            const filtered = allRecordings.filter(rec => {
                const matchesQuery = !query || rec.device_name.toLowerCase().includes(query) || (rec.note && rec.note.toLowerCase().includes(query));
                const matchesEvent = !eventType || rec.event_type === eventType;
                return matchesQuery && matchesEvent;
            });

            renderRecordings(filtered);
        }

        function renderRecordings(recordings) {
            const grid = document.getElementById('recordings-grid');
            if (recordings.length === 0) {
                grid.innerHTML = '<div class="empty-state">No recordings found.</div>';
                return;
            }

            grid.innerHTML = recordings.map(rec => `
                <div class="video-card">
                    <div class="thumb-container" onclick="openPlayerModal(${rec.id})">
                        <img class="thumb-img" src="${apiUrl(rec.thumbnail_url)}" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'320\' height=\'180\' fill=\'%231e293b\'><rect width=\'320\' height=\'180\'/><text x=\'50%\' y=\'50%\' fill=\'%2364748b\' dominant-baseline=\'middle\' text-anchor=\'middle\'>No Thumbnail</text></svg>'">
                        <div class="play-overlay">▶</div>
                        <span class="event-badge badge-${rec.event_type}">${rec.event_type}</span>
                        <span class="duration-badge">${rec.duration_seconds}s</span>
                    </div>
                    <div class="card-details">
                        <div class="card-title">${rec.device_name}</div>
                        <div class="card-meta">
                            <span>📅 ${formatTimestamp(rec.timestamp)}</span>
                            <span>💾 ${formatBytes(rec.file_size_bytes)}</span>
                        </div>
                        ${rec.note ? `<div style="font-size:0.8rem; color:#cbd5e1;">${rec.note}</div>` : ''}
                    </div>
                    <div class="card-actions">
                        <button class="icon-btn ${rec.is_protected ? 'active' : ''}" onclick="toggleProtect(${rec.id}, ${!rec.is_protected})" title="Protect from Auto-Cleanup">
                            ${rec.is_protected ? '⭐ Protected' : '☆ Protect'}
                        </button>
                        <div style="display: flex; gap: 0.25rem;">
                            <a class="icon-btn" href="${apiUrl(rec.video_url)}" download title="Download Video">⬇️</a>
                            <button class="icon-btn danger" onclick="deleteRecording(${rec.id})" title="Delete Recording">🗑️</button>
                        </div>
                    </div>
                </div>
            `).join('');
        }

        let currentVideoUrl = null;

        function showPlaybackFallback() {
            const video = document.getElementById('video-element');
            const warn = document.getElementById('playback-warn');
            const link = document.getElementById('playback-download-link');
            if (currentVideoUrl && link) link.href = currentVideoUrl;
            if (video) video.style.display = 'none';
            if (warn) warn.style.display = 'block';
        }

        function onVideoElementError() {
            // Playback failed (codec/container not supported or missing file): show the download fallback.
            const video = document.getElementById('video-element');
            const detail = document.getElementById('playback-warn-detail');
            // MediaError codes make a container/codec rejection distinguishable from a network failure.
            const code = video && video.error ? video.error.code : 0;
            const codeNames = { 1: 'aborted', 2: 'network', 3: 'decode', 4: 'source not supported' };
            if (detail && code) detail.textContent = ` (player error ${code}: ${codeNames[code] || 'unknown'})`;
            showPlaybackFallback();
        }

        async function openPlayerModal(recId) {
            const rec = allRecordings.find(r => r.id === recId);
            if (!rec) return;

            document.getElementById('modal-title').textContent = `${rec.device_name} - ${rec.event_type}`;
            document.getElementById('modal-meta').textContent = `${formatTimestamp(rec.timestamp)} &bull; ${rec.duration_seconds}s &bull; ${formatBytes(rec.file_size_bytes)}`;
            const video = document.getElementById('video-element');
            const warn = document.getElementById('playback-warn');
            const detail = document.getElementById('playback-warn-detail');
            currentVideoUrl = apiUrl(rec.video_url);
            warn.style.display = 'none';
            if (detail) detail.textContent = '';
            video.style.display = 'block';
            video.onerror = onVideoElementError;
            // The <video> tag cannot report an HTTP status, so verify availability first with an
            // authenticated HEAD; a 401/404 must not be presented as "browser cannot play this file".
            let probe;
            try {
                probe = await apiFetch(rec.video_url, { method: 'HEAD' });
            } catch (err) {
                probe = null;
            }
            document.getElementById('player-modal').classList.add('open');
            // Only an authoritative rejection hides the player; any other status (a proxy error, an
            // unsupported method) still lets <video> try, so a real container failure reports its own code.
            const rejected = probe && (probe.status === 401 || probe.status === 403 || probe.status === 404);
            if (!probe || rejected) {
                video.onerror = null;
                if (detail) detail.textContent = probe ? ` (server returned HTTP ${probe.status})` : ' (server unreachable)';
                showPlaybackFallback();
                return;
            }
            video.src = currentVideoUrl;
        }

        function closePlayerModal() {
            const video = document.getElementById('video-element');
            video.onerror = null;
            video.pause();
            video.src = '';
            video.style.display = 'block';
            document.getElementById('playback-warn').style.display = 'none';
            document.getElementById('playback-warn-detail').textContent = '';
            currentVideoUrl = null;
            document.getElementById('player-modal').classList.remove('open');
        }

        function openTriggerModal() {
            document.getElementById('trigger-modal').classList.add('open');
        }

        function closeTriggerModal() {
            document.getElementById('trigger-modal').classList.remove('open');
        }

        async function submitTriggerRecording(e) {
            e.preventDefault();
            const payload = {
                device_id: parseInt(document.getElementById('trig-device-id').value),
                device_name: document.getElementById('trig-device-name').value,
                source_mode: document.getElementById('trig-source-mode').value,
                rtsp_url: document.getElementById('trig-rtsp').value || null,
                snapshot_url: document.getElementById('trig-snapshot').value || null,
                username: document.getElementById('trig-username').value || null,
                password: document.getElementById('trig-password').value || null,
                event_type: document.getElementById('trig-event').value,
                duration_seconds: parseInt(document.getElementById('trig-duration').value)
            };

            const res = await apiFetch('/api/recordings/start', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (res.ok) {
                closeTriggerModal();
                loadData();
            } else {
                let errorMsg = 'Unknown error';
                try {
                    const err = await res.json();
                    if (typeof err.detail === 'string') {
                        errorMsg = err.detail;
                    } else if (Array.isArray(err.detail)) {
                        errorMsg = err.detail.map(d => `${d.loc ? d.loc.join('.') + ': ' : ''}${d.msg}`).join('\n');
                    } else if (err.detail) {
                        errorMsg = JSON.stringify(err.detail);
                    } else if (err.message) {
                        errorMsg = err.message;
                    }
                } catch (e) {
                    errorMsg = await res.text();
                }
                alert('Failed to start recording:\n' + errorMsg);
            }
        }

        async function stopRecording(deviceId) {
            await apiFetch('/api/recordings/stop', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ device_id: deviceId })
            });
            setTimeout(loadData, 500);
        }

        async function toggleProtect(id, protectState) {
            await apiFetch(`/api/recordings/${id}/protect?is_protected=${protectState}`, { method: 'POST' });
            loadData();
        }

        async function deleteRecording(id) {
            if (!confirm('Are you sure you want to delete this recording?')) return;
            await apiFetch(`/api/recordings/${id}`, { method: 'DELETE' });
            loadData();
        }

        async function triggerCleanup() {
            const res = await apiFetch('/api/cleanup', { method: 'POST' });
            const data = await res.json();
            alert(`Cleanup finished: deleted ${data.deleted_count} files, freed ${formatBytes(data.freed_bytes)}.`);
            loadData();
        }

        async function loadDevices() {
            try {
                const res = await apiFetch('/api/devices');
                if (!res.ok) return;
                allDevices = await res.json();
                renderLiveCameras();
            } catch (err) {
                console.error("Failed to load devices", err);
            }
        }

        let liveCamerasSignature = null;

        function renderLiveCameras() {
            const grid = document.getElementById('live-cameras-grid');
            if (allDevices.length === 0) {
                liveCamerasSignature = null;
                grid.innerHTML = '<div class="empty-state">No cameras added yet. Click "Add Camera" to start a live view.</div>';
                return;
            }

            // Rebuilding the cards would recreate every MJPEG <img> and reconnect each upstream stream
            // on the 5 s poll. Only a changed device set or API key rebuilds; recording state is patched
            // in place, and a dead stream is recovered by clicking it (reloadLiveStream).
            const signature = `${getApiKey()}|${allDevices.map(d => `${d.id}:${d.name}:${d.live_mode}:${d.live_url}`).join(';')}`;
            if (liveCamerasSignature === signature && grid.querySelector('.live-card')) {
                updateLiveCardStates();
                return;
            }
            liveCamerasSignature = signature;

            grid.innerHTML = allDevices.map(dev => {
                const isRecording = !!activeRecordingsByDevice[dev.id];
                return `
                <div class="live-card" id="live-card-${dev.id}">
                    <div class="live-card-header">
                        <div class="live-name"><span class="live-indicator"></span> ${dev.name}</div>
                        <div style="display: flex; gap: 0.25rem;">
                            <button class="icon-btn" onclick="openEditDeviceModal(${dev.id})" title="Edit Camera">✏️</button>
                            <button class="icon-btn danger" onclick="removeDevice(${dev.id})" title="Remove Camera">🗑️</button>
                        </div>
                    </div>
                    <div class="live-video-container">
                        <img class="live-img" id="live-img-${dev.id}" src="${apiUrl(dev.live_url)}" alt="${dev.name} live view" style="cursor: pointer;" title="Click to reconnect the stream" onload="this.style.opacity=1;" onerror="this.style.opacity=0.3;" onclick="reloadLiveStream(${dev.id})">
                    </div>
                    <div class="live-card-footer">
                        <span style="font-size:0.8rem; color: var(--text-muted);" id="live-status-${dev.id}">${isRecording ? 'Recording…' : 'Live'}</span>
                        <div style="display: flex; gap: 0.5rem;">
                            <button class="rec-toggle-btn" style="background-color: var(--card-border);" onclick="toggleFullscreen(${dev.id})" title="Fullscreen">⛶</button>
                            <button class="rec-toggle-btn${isRecording ? ' recording' : ''}" id="live-rec-btn-${dev.id}" onclick="toggleDeviceRecording(${dev.id})" title="${isRecording ? 'Stop Recording' : 'Start Recording'}">
                                ${isRecording ? '⏹' : '⏺'}
                            </button>
                        </div>
                    </div>
                </div>
            `;
            }).join('');
        }

        function updateLiveCardStates() {
            allDevices.forEach(dev => {
                const isRecording = !!activeRecordingsByDevice[dev.id];
                const status = document.getElementById(`live-status-${dev.id}`);
                const btn = document.getElementById(`live-rec-btn-${dev.id}`);
                if (status) status.textContent = isRecording ? 'Recording…' : 'Live';
                if (btn) {
                    btn.classList.toggle('recording', isRecording);
                    btn.title = isRecording ? 'Stop Recording' : 'Start Recording';
                    btn.textContent = isRecording ? '⏹' : '⏺';
                }
            });
        }

        function reloadLiveStream(deviceId) {
            const dev = allDevices.find(d => d.id === deviceId);
            const img = document.getElementById(`live-img-${deviceId}`);
            if (!dev || !img) return;
            // A fresh timestamp defeats any cached response and forces a new stream request.
            img.style.opacity = '1';
            img.src = `${apiUrl(dev.live_url)}&_=${Date.now()}`;
        }

        function toggleFullscreen(deviceId) {
            const img = document.getElementById(`live-img-${deviceId}`);
            if (!img) return;
            if (img.requestFullscreen) {
                img.requestFullscreen();
            } else if (img.webkitRequestFullscreen) {
                img.webkitRequestFullscreen();
            }
        }

        function openAddDeviceModal() {
            document.getElementById('add-device-modal').classList.add('open');
        }

        function closeAddDeviceModal() {
            document.getElementById('add-device-modal').classList.remove('open');
        }

        async function submitAddDevice(e) {
            e.preventDefault();
            const rtspUrl = document.getElementById('dev-rtsp').value || null;
            const snapshotUrl = document.getElementById('dev-snapshot').value || null;
            if (!rtspUrl && !snapshotUrl) {
                alert('Please provide at least an RTSP or a Snapshot URL.');
                return;
            }
            const payload = {
                name: document.getElementById('dev-name').value,
                rtsp_url: rtspUrl,
                snapshot_url: snapshotUrl,
                username: document.getElementById('dev-username').value || null,
                password: document.getElementById('dev-password').value || null,
                live_mode: document.getElementById('dev-live-mode').value
            };

            const res = await apiFetch('/api/devices', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (res.ok) {
                closeAddDeviceModal();
                e.target.reset();
                loadDevices();
            } else {
                const err = await res.json();
                alert('Failed to add camera: ' + (err.detail || 'Unknown error'));
            }
        }

        function openEditDeviceModal(id) {
            const dev = allDevices.find(d => d.id === id);
            if (!dev) return;
            document.getElementById('edit-dev-id').value = dev.id;
            document.getElementById('edit-dev-name').value = dev.name;
            document.getElementById('edit-dev-live-mode').value = dev.live_mode || 'rtsp';
            document.getElementById('edit-dev-rtsp').value = dev.rtsp_url || '';
            document.getElementById('edit-dev-snapshot').value = dev.snapshot_url || '';
            document.getElementById('edit-dev-username').value = dev.username || '';
            // The API no longer returns the stored password; leave the field blank so submitting
            // without typing one keeps the existing credential (blank = keep, handled server-side).
            document.getElementById('edit-dev-password').value = '';
            document.getElementById('edit-device-modal').classList.add('open');
        }

        function closeEditDeviceModal() {
            document.getElementById('edit-device-modal').classList.remove('open');
        }

        async function submitEditDevice(e) {
            e.preventDefault();
            const id = document.getElementById('edit-dev-id').value;
            const rtspUrl = document.getElementById('edit-dev-rtsp').value || null;
            const snapshotUrl = document.getElementById('edit-dev-snapshot').value || null;
            if (!rtspUrl && !snapshotUrl) {
                alert('Please provide at least an RTSP or a Snapshot URL.');
                return;
            }
            const payload = {
                name: document.getElementById('edit-dev-name').value,
                rtsp_url: rtspUrl,
                snapshot_url: snapshotUrl,
                username: document.getElementById('edit-dev-username').value || null,
                password: document.getElementById('edit-dev-password').value || null,
                live_mode: document.getElementById('edit-dev-live-mode').value
            };

            const res = await apiFetch(`/api/devices/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (res.ok) {
                closeEditDeviceModal();
                loadDevices();
            } else {
                const err = await res.json();
                alert('Failed to update camera: ' + (err.detail || 'Unknown error'));
            }
        }

        async function removeDevice(id) {
            if (!confirm('Remove this camera from the live view?')) return;
            await apiFetch(`/api/devices/${id}`, { method: 'DELETE' });
            loadDevices();
        }

        async function toggleDeviceRecording(deviceId) {
            const dev = allDevices.find(d => d.id === deviceId);
            if (!dev) return;

            if (activeRecordingsByDevice[deviceId]) {
                await stopRecording(deviceId);
                return;
            }

            const payload = {
                device_id: dev.id,
                device_name: dev.name,
                source_mode: dev.live_mode || 'auto',
                rtsp_url: dev.rtsp_url,
                snapshot_url: dev.snapshot_url,
                username: dev.username,
                password: dev.password,
                event_type: 'MANUAL',
                duration_seconds: 3600
            };

            const res = await apiFetch('/api/recordings/start', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (!res.ok) {
                let errorMsg = 'Unknown error';
                try {
                    const err = await res.json();
                    if (typeof err.detail === 'string') {
                        errorMsg = err.detail;
                    } else if (Array.isArray(err.detail)) {
                        errorMsg = err.detail.map(d => `${d.loc ? d.loc.join('.') + ': ' : ''}${d.msg}`).join('\n');
                    } else if (err.detail) {
                        errorMsg = JSON.stringify(err.detail);
                    } else if (err.message) {
                        errorMsg = err.message;
                    }
                } catch (e) {
                    errorMsg = await res.text();
                }
                alert('Failed to start recording:\n' + errorMsg);
                return;
            }

            setTimeout(loadData, 500);
        }

        // Auto load and poll
        loadData();
        loadDevices();
        setInterval(loadData, 5000);
    
