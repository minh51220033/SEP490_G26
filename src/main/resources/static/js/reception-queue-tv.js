(function (root) {
    const UNASSIGNED = 'Chưa phân công';

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    function callEventKey(number) {
        if (!number) return null;
        const identity = number.ticketId || number.number;
        return identity ? `${identity}_${number.callTime || 0}` : null;
    }

    function hasValidRoomAndChair(number) {
        if (!number) return false;
        const room = (number.room || '').trim();
        const chair = (number.chair || '').trim();
        return Boolean(room && chair && room !== UNASSIGNED && chair !== UNASSIGNED);
    }

    function buildAnnouncementText(number) {
        if (!hasValidRoomAndChair(number)) return null;
        const ticketNo = String(number.number || '').trim();
        const patientName = String(number.patientFullName || '').trim();
        const room = String(number.room).trim();
        const chair = String(number.chair).trim();
        if (patientName && patientName !== '—') {
            return `Xin mời bệnh nhân số ${ticketNo}, ${patientName}, vào ${room}, ${chair}. Xin mời!`;
        }
        return `Xin mời bệnh nhân số ${ticketNo}, vào ${room}, ${chair}. Xin mời!`;
    }

    function pickVietnameseVoice(voices) {
        if (!Array.isArray(voices) || voices.length === 0) return null;
        return voices.find(v => String(v.lang || '').toLowerCase() === 'vi-vn')
            || voices.find(v => String(v.lang || '').toLowerCase().startsWith('vi'))
            || null;
    }

    function createCallDeduplicator(storage, storageKey) {
        const seen = new Set();
        try {
            if (storage && storageKey) {
                const raw = storage.getItem(storageKey);
                if (raw) {
                    const parsed = JSON.parse(raw);
                    if (Array.isArray(parsed)) {
                        parsed.forEach(k => { if (k) seen.add(String(k)); });
                    }
                }
            }
        } catch (_) {}

        function persist() {
            try {
                if (storage && storageKey) {
                    const recent = Array.from(seen).slice(-200);
                    storage.setItem(storageKey, JSON.stringify(recent));
                }
            } catch (_) {}
        }

        return {
            has(key) {
                return Boolean(key && seen.has(key));
            },
            markSeen(calledNumbers) {
                if (!Array.isArray(calledNumbers)) return;
                let changed = false;
                calledNumbers.forEach(n => {
                    const k = callEventKey(n);
                    if (k && !seen.has(k)) {
                        seen.add(k);
                        changed = true;
                    }
                });
                if (changed) persist();
            },
            collectNewValidCalls(calledNumbers) {
                if (!Array.isArray(calledNumbers)) return [];
                const fresh = calledNumbers
                    .map(n => ({ number: n, key: callEventKey(n) }))
                    .filter(item => item.key && !seen.has(item.key) && hasValidRoomAndChair(item.number));
                fresh.sort((a, b) => (a.number.callTime || 0) - (b.number.callTime || 0));
                if (fresh.length > 0) {
                    fresh.forEach(item => seen.add(item.key));
                    persist();
                }
                return fresh;
            }
        };
    }

    root.TvQueueHelpers = {
        callEventKey,
        hasValidRoomAndChair,
        buildAnnouncementText,
        pickVietnameseVoice,
        createCallDeduplicator
    };

    if (typeof document === 'undefined') return;

    document.addEventListener('DOMContentLoaded', () => {
        const branchId = document.body.dataset.branch;
        if (!branchId) return;

        const branchEl = document.getElementById('branch');
        const tableBody = document.querySelector('#tvListTable tbody');
        const soundToggle = document.getElementById('soundToggle');
        const soundText = document.getElementById('soundText');
        const soundIcon = document.getElementById('soundIcon');
        const fsBtn = document.getElementById('fullscreenToggle');

        const callingCount = document.getElementById('tvCallingCount');
        const examiningCount = document.getElementById('tvExaminingCount');
        const waitingCount = document.getElementById('tvWaitingCount');

        let speechEnabled = false;
        const deduplicator = createCallDeduplicator(
            typeof window !== 'undefined' ? window.sessionStorage : null,
            `cvdcms_tv_seen_calls_${branchId}`
        );
        let isInitialLoad = true;
        let failCount = 0;
        const speechQueue = [];
        let speaking = false;
        let audioContext;
        let refreshInFlight = false;

        let serverOffsetMs = 0;
        let lastSuccessTimeStr = null;

        function updateClock() {
            const timeEl = document.getElementById('tvClockTime');
            const dateEl = document.getElementById('tvClockDate');
            const now = new Date(Date.now() + serverOffsetMs);
            const timeStr = now.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
            const dateStr = now.toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
            if (timeEl) timeEl.textContent = timeStr;
            if (dateEl) dateEl.textContent = dateStr;
        }

        function setConnectionStatus(online) {
            const pill = document.getElementById('tvLivePill');
            const text = document.getElementById('tvLiveText');
            if (!pill || !text) return;
            if (online) {
                pill.style.background = '';
                pill.style.color = '';
                text.textContent = 'TRỰC TIẾP';
            } else {
                pill.style.background = '#fef2f2';
                pill.style.color = '#b91c1c';
                text.textContent = lastSuccessTimeStr
                    ? `MẤT KẾT NỐI · ${lastSuccessTimeStr}`
                    : 'MẤT KẾT NỐI';
            }
        }
        updateClock();
        setInterval(updateClock, 1000);

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

        if ('speechSynthesis' in window && typeof window.speechSynthesis.getVoices === 'function') {
            window.speechSynthesis.getVoices();
        }

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
            if (!text || !speechEnabled || !('speechSynthesis' in window)) return;
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
                const viVoice = pickVietnameseVoice(window.speechSynthesis.getVoices());
                if (viVoice) utterance.voice = viVoice;
                utterance.rate = 0.94;
                utterance.pitch = 1.02;
                utterance.onend = utterance.onerror = () => {
                    speaking = false;
                    playNext();
                };
                setTimeout(() => {
                    if (speechEnabled) window.speechSynthesis.speak(utterance);
                    else speaking = false;
                }, 480);
            } catch (_) {
                speaking = false;
                playNext();
            }
        }

        function doctorTagClass(assignmentType) {
            if (assignmentType === 'BOOKED_DOCTOR') return 'booked';
            if (assignmentType === 'ASSIGNED_DOCTOR') return 'assigned';
            if (assignmentType === 'WAITING_DOCTOR') return 'waiting-doc';
            return '';
        }

        function renderStatusBadge(status) {
            if (status === 'CALLED') {
                return `<span class="tv-status-badge calling" style="margin-left: 0;"><i class="bi bi-megaphone-fill"></i> Đang gọi</span>`;
            }
            if (status === 'IN_SERVICE') {
                return `<span class="tv-status-badge examining" style="margin-left: 0;"><i class="bi bi-heart-pulse-fill"></i> Đang khám</span>`;
            }
            if (status === 'PAUSED') {
                return `<span class="tv-status-badge" style="background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; margin-left: 0;">Tạm hoãn</span>`;
            }
            return `<span class="tv-status-badge" style="background: #f1f5f9; color: #475569; margin-left: 0;">Đang chờ</span>`;
        }

        function formatMmSs(totalSec) {
            const s = Math.max(0, Math.floor(totalSec || 0));
            const m = Math.floor(s / 60);
            const r = s % 60;
            return `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`;
        }

        function computeLivePhase(progress, nowMs) {
            if (!progress || !progress.actualStartTimeEpochMs) return null;
            const prepSec = Math.max(0, (progress.prepMinutes || 0) * 60);
            const examSec = Math.max(60, (progress.examMinutes || 30) * 60);
            const cleanSec = Math.max(0, (progress.cleaningMinutes || 0) * 60);
            const totalSec = Math.max(1, prepSec + examSec + cleanSec);
            const elapsedSec = Math.max(0, Math.floor((nowMs - progress.actualStartTimeEpochMs) / 1000));

            let phase = 'EXAM';
            let phaseLabel = 'Đang khám';
            let phaseRem = 0;
            let overtime = false;
            let overtimeSec = 0;

            if (prepSec > 0 && elapsedSec < prepSec) {
                phase = 'PREP';
                phaseLabel = 'Đang chuẩn bị';
                phaseRem = prepSec - elapsedSec;
            } else if (elapsedSec < prepSec + examSec) {
                phase = 'EXAM';
                phaseLabel = 'Đang khám';
                phaseRem = (prepSec + examSec) - elapsedSec;
            } else if (cleanSec > 0 && elapsedSec < totalSec) {
                phase = 'CLEANING';
                phaseLabel = 'Đang vệ sinh';
                phaseRem = totalSec - elapsedSec;
            } else {
                phase = 'OVERTIME';
                phaseLabel = 'Đã vượt thời gian dự kiến';
                phaseRem = 0;
                overtime = true;
                overtimeSec = Math.max(0, elapsedSec - totalSec);
            }

            const pct = Math.min(100, Math.round((elapsedSec * 100) / totalSec));
            return {
                phase,
                phaseLabel,
                phaseRem,
                elapsedSec,
                totalSec,
                totalMinutes: Math.round(totalSec / 60),
                overtime,
                overtimeSec,
                pct
            };
        }

        function renderPhaseStatusHtml(progress, nowMs) {
            const live = computeLivePhase(progress, nowMs);
            if (!live) return renderStatusBadge('IN_SERVICE');
            const timerText = live.overtime ? `+${formatMmSs(live.overtimeSec)}` : formatMmSs(live.phaseRem);
            const iconCls = live.phase === 'PREP'
                ? 'bi-hourglass-split'
                : (live.phase === 'CLEANING' ? 'bi-stars' : (live.overtime ? 'bi-exclamation-triangle-fill' : 'bi-heart-pulse-fill'));
            const badgeStyle = live.overtime
                ? 'background:#fef3c7;color:#b45309;border:1px solid #fcd34d;margin-left:0;'
                : (live.phase === 'CLEANING'
                    ? 'background:#e0f2fe;color:#0369a1;border:1px solid #bae6fd;margin-left:0;'
                    : (live.phase === 'PREP'
                        ? 'background:#ede9fe;color:#5b21b6;border:1px solid #ddd6fe;margin-left:0;'
                        : 'margin-left:0;'));
            const badgeCls = (!live.overtime && live.phase === 'EXAM') ? 'tv-status-badge examining' : 'tv-status-badge';
            return `
                <div class="tv-phase-compact ${live.overtime ? 'is-overtime' : ''}">
                    <span class="${badgeCls}" style="${badgeStyle}">
                        <i class="bi ${iconCls}"></i> ${escapeHtml(live.phaseLabel.toUpperCase())}
                    </span>
                    <div class="tv-phase-timer-compact">${escapeHtml(timerText)}</div>
                    <div class="tv-phase-bar-track"><div class="tv-phase-bar-fill" style="width:${live.pct}%"></div></div>
                </div>
            `;
        }

        let latestQueueList = [];
        let latestCleaningChairs = [];

        function renderCleaningStrip(cleaningChairs, nowMs) {
            const el = document.getElementById('tvCleaningSection');
            if (!el) return;
            if (!Array.isArray(cleaningChairs) || cleaningChairs.length === 0) {
                el.style.display = 'none';
                el.innerHTML = '';
                return;
            }
            const items = cleaningChairs.map(c => {
                const elapsed = Math.max(0, Math.floor((nowMs - c.startedAtEpochMs) / 1000));
                const total = Math.max(60, (c.durationMinutes || 5) * 60);
                const rem = Math.max(0, total - elapsed);
                const txt = elapsed >= total ? `Vệ sinh quá ${formatMmSs(elapsed - total)}` : `Còn ${formatMmSs(rem)}`;
                return `<span class="badge bg-info-subtle text-info-emphasis border border-info-subtle px-2 py-1">
                    ${escapeHtml(c.chairName)}${c.roomName ? ' (' + escapeHtml(c.roomName) + ')' : ''} · ${txt}
                </span>`;
            }).join('');
            el.innerHTML = `<i class="bi bi-stars"></i><span>Ghế đang vệ sinh sau khám:</span>${items}`;
            el.style.display = 'flex';
        }

        function tickLiveCountdowns() {
            const nowMs = Date.now() + serverOffsetMs;
            latestQueueList.forEach(row => {
                if (row.status === 'IN_SERVICE' && row.phaseProgress) {
                    const holder = document.querySelector(`[data-phase-ticket="${CSS.escape(String(row.ticketNumber || row.stt))}"]`);
                    if (holder) {
                        holder.innerHTML = renderPhaseStatusHtml(row.phaseProgress, nowMs);
                    }
                }
            });
            renderCleaningStrip(latestCleaningChairs, nowMs);
        }

        function renderUnifiedTable(queueList) {
            latestQueueList = Array.isArray(queueList) ? queueList : [];
            const nowMs = Date.now() + serverOffsetMs;
            const calledRows = queueList.filter(r => r.status === 'CALLED');
            const examiningRows = queueList.filter(r => r.status === 'IN_SERVICE');
            const waitingRows = queueList.filter(r => r.status !== 'CALLED' && r.status !== 'IN_SERVICE');

            if (callingCount) callingCount.textContent = `${calledRows.length} đang gọi`;
            if (examiningCount) examiningCount.textContent = `${examiningRows.length} đang khám`;
            if (waitingCount) waitingCount.textContent = `${waitingRows.length} đang chờ`;

            if (!tableBody) return;
            if (queueList.length === 0) {
                tableBody.innerHTML = `
                    <tr id="tvEmptyRow">
                        <td colspan="6">
                            <div class="tv-empty-box">
                                <i class="bi bi-person-lines-fill"></i>
                                <div style="font-size: 18px; font-weight: 700; color: #475569;">Hiện tại chưa có bệnh nhân trong hàng đợi</div>
                                <div style="font-size: 14px; margin-top: 4px;">Quý khách vui lòng liên hệ quầy Tiếp đón &amp; Lễ tân để nhận phiếu thứ tự.</div>
                            </div>
                        </td>
                    </tr>
                `;
                return;
            }

            tableBody.innerHTML = queueList.map(row => {
                const rowClass = row.status === 'CALLED'
                    ? 'tv-row-calling'
                    : (row.status === 'IN_SERVICE' ? 'tv-row-examining' : '');
                const docName = escapeHtml(row.doctorDisplay || 'Chưa chỉ định');
                const docLabel = escapeHtml(row.doctorStatusLabel || 'Chưa chỉ định');
                const tagCls = doctorTagClass(row.doctorAssignmentType);
                const isActive = row.isCallingOrExam === true || row.status === 'CALLED' || row.status === 'IN_SERVICE';
                const roomText = isActive ? (row.roomName || UNASSIGNED) : UNASSIGNED;
                const chairText = isActive ? (row.chairName || UNASSIGNED) : UNASSIGNED;
                const roomCls = (isActive && roomText !== UNASSIGNED) ? 'fw-bold text-dark' : 'cell-dash';
                const chairCls = (isActive && chairText !== UNASSIGNED) ? 'fw-bold text-dark' : 'cell-dash';

                const statusCellHtml = (row.status === 'IN_SERVICE' && row.phaseProgress)
                    ? `<div data-phase-ticket="${escapeHtml(String(row.ticketNumber || row.stt))}">${renderPhaseStatusHtml(row.phaseProgress, nowMs)}</div>`
                    : renderStatusBadge(row.status);

                return `
                    <tr class="${rowClass}">
                        <td class="cell-center">
                            <span class="tv-badge-stt">${escapeHtml(row.ticketNumber || row.stt)}</span>
                        </td>
                        <td style="text-align: left; padding-left: 20px; font-size: clamp(18px, 1.4vw, 24px); font-weight: 800; color: var(--tv-text-main); overflow-wrap: anywhere;">
                            ${escapeHtml(row.patientFullName || '—')}
                        </td>
                        <td style="text-align: left; padding-left: 16px;">
                            <div class="tv-doctor-name">${docName}</div>
                            <span class="tv-doctor-tag ${tagCls}">${docLabel}</span>
                        </td>
                        <td class="cell-center">
                            ${statusCellHtml}
                        </td>
                        <td class="cell-center ${roomCls}">${escapeHtml(roomText)}</td>
                        <td class="cell-center ${chairCls}">${escapeHtml(chairText)}</td>
                    </tr>
                `;
            }).join('');
        }

        function announceNewCalls(calledNumbers) {
            const freshCalls = deduplicator.collectNewValidCalls(calledNumbers);
            freshCalls.forEach(({ number }) => {
                const phrase = buildAnnouncementText(number);
                if (phrase) speak(phrase);
            });
        }

        async function refresh() {
            if (refreshInFlight) return;
            refreshInFlight = true;
            try {
                const url = branchId ? `/reception/queue/tv/${encodeURIComponent(branchId)}/data` : '/reception/queue/tv/data';
                const response = await fetch(url, { cache: 'no-store' });
                if (!response.ok) {
                    failCount++;
                    setConnectionStatus(false);
                    return;
                }
                failCount = 0;
                const data = await response.json();
                if (typeof data.serverNowEpochMs === 'number' && data.serverNowEpochMs > 0) {
                    serverOffsetMs = data.serverNowEpochMs - Date.now();
                }
                lastSuccessTimeStr = new Date(Date.now() + serverOffsetMs)
                    .toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                setConnectionStatus(true);
                if (branchEl && data.branch) branchEl.textContent = data.branch;

                if (Array.isArray(data.queueList)) {
                    renderUnifiedTable(data.queueList);
                }
                latestCleaningChairs = Array.isArray(data.cleaningChairs) ? data.cleaningChairs : [];
                renderCleaningStrip(latestCleaningChairs, Date.now() + serverOffsetMs);

                const calledNumbers = Array.isArray(data.called) ? data.called : [];
                if (isInitialLoad) {
                    deduplicator.markSeen(calledNumbers);
                    isInitialLoad = false;
                } else {
                    announceNewCalls(calledNumbers);
                }
            } catch (_) {
                failCount++;
                setConnectionStatus(false);
            } finally {
                refreshInFlight = false;
            }
        }

        function connectStompIfAvailable() {
            if (typeof window.SockJS === 'undefined' || typeof window.Stomp === 'undefined') return;
            try {
                const socket = new window.SockJS('/ws-chat');
                const client = window.Stomp.over(socket);
                client.debug = null;
                client.connect({}, () => {
                    client.subscribe(`/topic/queue/branch/${branchId}`, () => {
                        refresh();
                    });
                }, () => {
                    setTimeout(connectStompIfAvailable, 5000);
                });
            } catch (_) {}
        }

        refresh();
        setInterval(refresh, 2000);
        setInterval(tickLiveCountdowns, 1000);
        connectStompIfAvailable();
    });
})(typeof globalThis !== 'undefined' ? globalThis : window);
