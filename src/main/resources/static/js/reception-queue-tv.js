document.addEventListener('DOMContentLoaded', () => {
    const branchId = document.body.dataset.branch;
    if (!branchId) return;

    const branch = document.getElementById('branch');
    const tableBody = document.querySelector('#tvListTable tbody');
    const soundToggle = document.getElementById('soundToggle');
    const soundText = document.getElementById('soundText');
    const soundIcon = document.getElementById('soundIcon');
    const fsBtn = document.getElementById('fullscreenToggle');

    let speechEnabled = false;
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
                fsBtn.innerHTML = '<i class="bi bi-arrows-fullscreen"></i><span>Toàn màn hình</span>';
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
                } catch (_) {}
                soundToggle.classList.add('is-active');
                if (soundIcon) soundIcon.className = 'bi bi-volume-up-fill';
                if (soundText) soundText.textContent = 'Đang bật âm';
                speak('Hệ thống âm thanh gọi số phòng khám đã kích hoạt.');
            } else {
                soundToggle.classList.remove('is-active');
                if (soundIcon) soundIcon.className = 'bi bi-volume-mute-fill';
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

    function callEventKey(number) {
        if (!number) return null;
        const identity = number.ticketId || number.number;
        return identity ? `${identity}_${number.callTime || 0}` : null;
    }

    function announceNewCalls(calledNumbers) {
        const newCalls = calledNumbers
            .map(number => ({ number, key: callEventKey(number) }))
            .filter(event => event.key && !seenCallEventKeys.has(event.key));

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
            const url = branchId ? `/reception/queue/tv/${encodeURIComponent(branchId)}/data` : '/reception/queue/tv/data';
            const response = await fetch(url, { cache: 'no-store' });
            if (!response.ok) {
                failCount++;
                return;
            }
            failCount = 0;
            const data = await response.json();
            if (branch && data.branch) branch.textContent = data.branch;

            // Render danh sách bệnh nhân gọi số (TV List Table)
            if (tableBody && Array.isArray(data.queueList)) {
                if (data.queueList.length === 0) {
                    tableBody.innerHTML = `
                        <tr id="tvEmptyRow">
                            <td colspan="6">
                                <div class="tv-empty-box">
                                    <i class="bi bi-person-lines-fill"></i>
                                    <div style="font-size: 18px; font-weight: 700; color: #475569;">Hiện tại chưa có bệnh nhân trong hàng chờ gọi số</div>
                                    <div style="font-size: 14px; margin-top: 4px;">Quý khách vui lòng liên hệ quầy Tiếp đón &amp; Lễ tân để nhận phiếu thứ tự.</div>
                                </div>
                            </td>
                        </tr>
                    `;
                } else {
                    tableBody.innerHTML = data.queueList.map(row => {
                        const isCallingOrExam = Boolean(row.isCallingOrExam);
                        const rowClass = isCallingOrExam ? 'tv-row-active' : '';
                        const roomText = isCallingOrExam ? escapeHtml(row.roomName || '—') : '—';
                        const chairText = isCallingOrExam ? escapeHtml(row.chairName || '—') : '—';
                        const roomClass = isCallingOrExam ? 'cell-room' : 'cell-dash';
                        const chairClass = isCallingOrExam ? 'cell-chair' : 'cell-dash';

                        let badgeHtml = '';
                        if (row.status === 'CALLED') {
                            badgeHtml = '<span class="tv-status-badge calling"><i class="bi bi-megaphone-fill me-1"></i>Đang gọi</span>';
                        } else if (row.status === 'IN_SERVICE') {
                            badgeHtml = '<span class="tv-status-badge examining"><i class="bi bi-heart-pulse-fill me-1"></i>Đang khám</span>';
                        }

                        return `
                            <tr class="${rowClass}">
                                <td class="cell-center">
                                    <span class="tv-badge-stt">${escapeHtml(row.ticketNumber || row.stt)}</span>
                                </td>
                                <td>
                                    <span class="tv-patient-name">${escapeHtml(row.patientName || '—')}</span>
                                    ${badgeHtml}
                                </td>
                                <td class="cell-center">${escapeHtml(row.dob || '—')}</td>
                                <td class="cell-center ${roomClass}">${roomText}</td>
                                <td class="cell-center ${chairClass}">${chairText}</td>
                                <td>${escapeHtml(row.doctorName || '—')}</td>
                            </tr>
                        `;
                    }).join('');
                }
            }

            // Xử lý phát loa TTS khi có ca được gọi
            const calledNumbers = Array.isArray(data.called) ? data.called : [];
            if (isInitialLoad) {
                calledNumbers.map(callEventKey).filter(Boolean).forEach(key => seenCallEventKeys.add(key));
                isInitialLoad = false;
            } else {
                announceNewCalls(calledNumbers);
            }

        } catch (_) {
            failCount++;
        }
    }

    refresh();
    setInterval(refresh, 2000);
});
