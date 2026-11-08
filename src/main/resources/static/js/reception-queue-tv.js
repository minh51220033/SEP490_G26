document.addEventListener('DOMContentLoaded', () => {
    const branchId = document.body.dataset.branch;
    if (!branchId) return;

    const branch = document.getElementById('branch');
    const called = document.getElementById('called');
    const serving = document.getElementById('serving');
    const waiting = document.getElementById('waiting');
    const next = document.getElementById('next');
    const soundToggle = document.getElementById('soundToggle');
    const soundText = document.getElementById('soundText');
    const soundIcon = document.getElementById('soundIcon');
    const fsBtn = document.getElementById('fullscreenToggle');

    let speechEnabled = false;
    // Keep every call event already seen during this TV session, rather than only
    // remembering the first item in the current response.  Several doctors can
    // call tickets between two polling cycles.
    const seenCallEventKeys = new Set();
    let isInitialLoad = true;
    let failCount = 0;
    const speechQueue = [];
    let speaking = false;
    let audioContext;

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    // Digital Clock
    function updateClock() {
        const timeEl = document.getElementById('tvClockTime');
        const dateEl = document.getElementById('tvClockDate');
        const now = new Date();
        const timeStr = now.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        const dateStr = now.toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
        if (timeEl) timeEl.textContent = timeStr;
        if (dateEl) dateEl.textContent = dateStr;
    }
    updateClock();
    setInterval(updateClock, 1000);

    // Fullscreen Toggle
    if (fsBtn) {
        fsBtn.addEventListener('click', () => {
            if (!document.fullscreenElement) {
                document.documentElement.requestFullscreen().catch(() => {});
                fsBtn.innerHTML = '<i class="bi bi-fullscreen-exit"></i><span>Thu nhỏ</span>';
            } else {
                document.exitFullscreen().catch(() => {});
                fsBtn.innerHTML = '<i class="bi bi-arrows-fullscreen"></i><span>Phóng to</span>';
            }
        });
    }

    // Audio / Speech Announcement Toggle
    if (soundToggle) {
        soundToggle.addEventListener('click', () => {
            speechEnabled = !speechEnabled;
            soundToggle.setAttribute('aria-pressed', String(speechEnabled));
            if (speechEnabled) {
                try {
                    audioContext ||= new (window.AudioContext || window.webkitAudioContext)();
                    audioContext.resume();
                } catch (_) { /* Fallback TTS */ }
                soundToggle.classList.add('is-active');
                if (soundIcon) {
                    soundIcon.className = 'bi bi-volume-up-fill';
                }
                if (soundText) soundText.textContent = 'Đang bật âm';
                speak('Hệ thống âm thanh gọi số phòng khám đã kích hoạt.');
            } else {
                soundToggle.classList.remove('is-active');
                if (soundIcon) {
                    soundIcon.className = 'bi bi-volume-mute-fill';
                }
                if (soundText) soundText.textContent = 'Bật âm thanh';
                speechQueue.length = 0;
                if ('speechSynthesis' in window) window.speechSynthesis.cancel();
                speaking = false;
            }
        });
    }

    function speak(text) {
        if (!speechEnabled || !('speechSynthesis' in window)) return;
        speechQueue.push(text);
        playNext();
    }

    function playNext() {
        if (speaking || !speechEnabled || !speechQueue.length) return;
        speaking = true;
        const text = speechQueue.shift();
        try {
            if (audioContext && audioContext.state === 'running') {
                const start = audioContext.currentTime;
                [523.25, 659.25].forEach((frequency, index) => {
                    const oscillator = audioContext.createOscillator();
                    const gain = audioContext.createGain();
                    oscillator.frequency.value = frequency;
                    gain.gain.setValueAtTime(0.0001, start + index * 0.22);
                    gain.gain.exponentialRampToValueAtTime(0.15, start + index * 0.22 + 0.025);
                    gain.gain.exponentialRampToValueAtTime(0.0001, start + index * 0.22 + 0.18);
                    oscillator.connect(gain).connect(audioContext.destination);
                    oscillator.start(start + index * 0.22);
                    oscillator.stop(start + index * 0.22 + 0.19);
                });
            }
            const utterance = new SpeechSynthesisUtterance(text);
            utterance.lang = 'vi-VN';
            utterance.rate = 0.92;
            utterance.pitch = 1.05;
            utterance.onend = utterance.onerror = () => { speaking = false; playNext(); };
            setTimeout(() => {
                if (speechEnabled) window.speechSynthesis.speak(utterance);
                else speaking = false;
            }, 500);
        } catch (_) { speaking = false; playNext(); }
    }

    function location(number) {
        return [number.room, number.chair].filter(Boolean).join(' · ') || 'Vui lòng chờ hướng dẫn';
    }

    function calledCard(number) {
        const card = document.createElement('div');
        card.className = 'called-card';

        const tag = document.createElement('div');
        tag.className = 'called-card-tag';
        tag.innerHTML = '<i class="bi bi-broadcast me-1"></i> Mời vào khám';

        const num = document.createElement('div');
        num.className = 'called-number';
        num.textContent = number.number;

        const info = document.createElement('div');
        info.className = 'called-info';

        const docRow = document.createElement('div');
        docRow.className = 'called-doc-row';
        docRow.innerHTML = `<i class="bi bi-person-badge-fill me-2 text-primary"></i> <span>${escapeHtml(number.doctor || 'BS. Phụ trách')}</span>`;

        const placeRow = document.createElement('div');
        placeRow.className = 'called-place-row';
        const placeText = [number.room ? `Phòng ${number.room}` : '', number.chair ? `Ghế ${number.chair}` : ''].filter(Boolean).join(' · ') || 'Theo hướng dẫn nhân viên tiếp đón';
        placeRow.innerHTML = `<i class="bi bi-geo-alt-fill me-2 text-warning"></i> <span>${escapeHtml(placeText)}</span>`;

        info.append(docRow, placeRow);
        card.append(tag, num, info);
        return card;
    }

    function servingCard(number) {
        const card = document.createElement('div');
        card.className = 'serving-card';

        const badge = document.createElement('div');
        badge.className = 'serving-num-badge';
        badge.textContent = number.number;

        const details = document.createElement('div');
        details.className = 'serving-details';

        const doc = document.createElement('span');
        doc.className = 'serving-doc';
        doc.innerHTML = `<i class="bi bi-person-fill me-1 text-success"></i> ${escapeHtml(number.doctor || 'BS. Phụ trách')}`;

        const place = document.createElement('span');
        place.className = 'serving-place';
        const placeText = [number.room ? `Phòng ${number.room}` : '', number.chair ? `Ghế ${number.chair}` : ''].filter(Boolean).join(' · ') || 'Tại ghế nha';
        place.innerHTML = `<i class="bi bi-geo-alt me-1"></i> ${escapeHtml(placeText)}`;

        details.append(doc, place);
        card.append(badge, details);
        return card;
    }

    function updateConnStatus(connected) {
        let connEl = document.getElementById('tvConnectionBadge');
        if (!connected) {
            if (!connEl) {
                connEl = document.createElement('div');
                connEl.id = 'tvConnectionBadge';
                connEl.style.cssText = 'position:fixed;bottom:24px;right:24px;background:rgba(239,68,68,0.92);color:#fff;padding:8px 18px;border-radius:999px;font-size:13px;font-weight:700;z-index:9999;box-shadow:0 6px 20px rgba(0,0,0,0.3);display:flex;align-items:center;gap:8px;';
                connEl.innerHTML = '<span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#fff;animation:tvPulse 1.5s infinite;"></span> Mất kết nối máy chủ · Đang tự kết nối lại...';
                document.body.appendChild(connEl);
            }
        } else {
            if (connEl) {
                connEl.remove();
            }
        }
    }

    function callEventKey(number) {
        if (!number) return null;
        const identity = number.ticketId || number.number;
        return identity ? `${identity}_${number.callTime || 0}` : null;
    }

    function announceNewCalls(calledNumbers) {
        const newCalls = calledNumbers
            .map(number => ({ number, key: callEventKey(number) }))
            .filter(event => event.key && !seenCallEventKeys.has(event.key));

        // The public board is newest-first.  Announcing oldest-first preserves
        // the real calling sequence when more than one event arrived while the
        // TV was waiting for its next poll (or reconnecting).
        newCalls.sort((left, right) => (left.number.callTime || 0) - (right.number.callTime || 0));
        newCalls.forEach(({ number, key }) => {
            seenCallEventKeys.add(key);
            const place = [number.room, number.chair].filter(Boolean).join(' ') || 'phòng khám';
            const doctor = number.doctor || 'bác sĩ phụ trách';
            speak(`Xin mời bệnh nhân số ${number.number}, vào ${place}, gặp ${doctor}.`);
        });
    }

    async function refresh() {
        try {
            const response = await fetch('/reception/queue/tv/' + encodeURIComponent(branchId) + '/data', { cache: 'no-store' });
            if (!response.ok) {
                failCount++;
                if (failCount >= 2) updateConnStatus(false);
                return;
            }
            failCount = 0;
            updateConnStatus(true);

            const data = await response.json();
            if (branch) branch.textContent = data.branch;
            if (waiting) waiting.textContent = data.waiting;

            // Live update Basic List Table (#tvBasicListTable tbody)
            const tvTableBody = document.querySelector('#tvBasicListTable tbody');
            if (tvTableBody && Array.isArray(data.queueList)) {
                if (data.queueList.length === 0) {
                    tvTableBody.innerHTML = '<tr><td colspan="5" class="tv-empty-row">Chưa có bệnh nhân trong danh sách gọi số.</td></tr>';
                } else {
                    tvTableBody.innerHTML = data.queueList.map(row => `
                        <tr class="${row.status === 'CALLED' ? 'is-called-row' : ''}">
                            <td>${row.orderIndex ?? row.stt ?? ''}</td>
                            <td><span class="tv-ticket-pill">${escapeHtml(row.ticketNumber || '—')}</span></td>
                            <td><span class="tv-code-pill">${escapeHtml(row.patientCode || '—')}</span></td>
                            <td class="fw-semibold">${escapeHtml(row.patientName || '—')}</td>
                            <td>${escapeHtml(row.phoneNumber || '—')}</td>
                        </tr>
                    `).join('');
                }
            }

            // Render Next List
            next.replaceChildren(...data.next.map(label => {
                const item = document.createElement('div');
                item.className = 'next-item-card';
                const [number, ...doctor] = label.split(' · ');
                const badge = document.createElement('span');
                badge.className = 'next-num-badge';
                badge.textContent = number;
                const name = document.createElement('span');
                name.className = 'next-doc-text';
                name.textContent = doctor.join(' · ') || 'Đang chuẩn bị';
                item.append(badge, name);
                return item;
            }));
            if (!data.next.length) {
                const emptyNext = document.createElement('div');
                emptyNext.className = 'text-muted text-center py-3';
                emptyNext.textContent = '—';
                next.append(emptyNext);
            }

            // Render Called List
            called.replaceChildren(...data.called.map(calledCard));
            if (!data.called.length) {
                const empty = document.createElement('div');
                empty.className = 'tv-empty-calling';
                empty.innerHTML = `
                    <i class="bi bi-bell-slash fs-1 d-block mb-2 opacity-50"></i>
                    <strong>Chưa có số được gọi</strong>
                    <span>Quý khách vui lòng chú ý lắng nghe loa và theo dõi màn hình khi đến lượt.</span>
                `;
                called.append(empty);
            }

            // Audio Call / Recall Detection - CV-R2-06.
            // Seed the first render silently.  On every following successful
            // poll, including one after a connection failure, process *all*
            // unseen call events.  This prevents both missed simultaneous calls
            // and repeated announcements after reconnecting.
            const calledNumbers = Array.isArray(data.called) ? data.called : [];
            if (isInitialLoad) {
                calledNumbers.map(callEventKey).filter(Boolean).forEach(key => seenCallEventKeys.add(key));
                isInitialLoad = false;
            } else {
                // Do not reset seenCallEventKeys after a failed poll: retaining
                // it makes recovery idempotent while still announcing every
                // call event that was not present before the disconnect.
                announceNewCalls(calledNumbers);
            }

            // Render Serving List
            serving.replaceChildren(...data.serving.map(servingCard));
            if (!data.serving.length) {
                const emptyServing = document.createElement('div');
                emptyServing.className = 'text-muted text-center py-4';
                emptyServing.innerHTML = '<em>Chưa có ca đang phục vụ tại ghế</em>';
                serving.append(emptyServing);
            }
        } catch (_) {
            failCount++;
            if (failCount >= 2) updateConnStatus(false);
        }
    }

    refresh();
    setInterval(refresh, 4000);
});
