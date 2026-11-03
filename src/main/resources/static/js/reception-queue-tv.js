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

    let speechEnabled = false;
    let lastAnnouncedTicketId = null;
    let lastAnnouncedNumber = null;
    let failCount = 0;

    if (soundToggle) {
        soundToggle.addEventListener('click', () => {
            speechEnabled = !speechEnabled;
            if (speechEnabled) {
                soundToggle.classList.add('is-active');
                if (soundIcon) soundIcon.textContent = '🔊';
                if (soundText) soundText.textContent = 'Âm thanh: Đang bật';
                speak('Hệ thống âm thanh gọi số đã kích hoạt.');
            } else {
                soundToggle.classList.remove('is-active');
                if (soundIcon) soundIcon.textContent = '🔈';
                if (soundText) soundText.textContent = 'Bật âm thanh';
                if ('speechSynthesis' in window) window.speechSynthesis.cancel();
            }
        });
    }

    function speak(text) {
        if (!speechEnabled || !('speechSynthesis' in window)) return;
        try {
            window.speechSynthesis.cancel();
            const utterance = new SpeechSynthesisUtterance(text);
            utterance.lang = 'vi-VN';
            utterance.rate = 0.92;
            utterance.pitch = 1.05;
            window.speechSynthesis.speak(utterance);
        } catch (_) {}
    }

    function location(number) {
        return [number.room, number.chair].filter(Boolean).join(' · ') || 'Vui lòng chờ hướng dẫn';
    }

    function calledCard(number) {
        const card = document.createElement('div');
        card.className = 'called-item';
        const code = document.createElement('strong');
        code.textContent = number.number;
        const doctor = document.createElement('span');
        doctor.textContent = number.doctor;
        const place = document.createElement('small');
        place.textContent = location(number);
        card.append(code, doctor, place);
        return card;
    }

    function updateConnStatus(connected) {
        let connEl = document.getElementById('tvConnectionBadge');
        if (!connected) {
            if (!connEl) {
                connEl = document.createElement('div');
                connEl.id = 'tvConnectionBadge';
                connEl.style.cssText = 'position:fixed;bottom:16px;right:16px;background:rgba(239,68,68,0.92);color:#fff;padding:6px 14px;border-radius:999px;font-size:12px;font-weight:600;z-index:9999;box-shadow:0 4px 12px rgba(0,0,0,0.2);display:flex;align-items:center;gap:6px;';
                connEl.innerHTML = '<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#fff;animation:pulse 1.5s infinite;"></span> Mất kết nối máy chủ · Đang thử lại...';
                document.body.appendChild(connEl);
            }
        } else {
            if (connEl) {
                connEl.remove();
            }
        }
    }

    async function refresh() {
        try {
            const response = await fetch('/reception/queue/tv/' + encodeURIComponent(branchId) + '/data', {cache: 'no-store'});
            if (!response.ok) {
                failCount++;
                if (failCount >= 2) updateConnStatus(false);
                return;
            }
            failCount = 0;
            updateConnStatus(true);

            const data = await response.json();
            branch.textContent = data.branch;
            waiting.textContent = data.waiting;
            next.textContent = data.next.join('  ·  ') || '—';
            called.replaceChildren(...data.called.map(calledCard));
            if (!data.called.length) {
                const empty = document.createElement('div');
                empty.className = 'tv-empty';
                empty.textContent = 'Chưa có số được gọi';
                called.append(empty);
            } else {
                const latest = data.called[0];
                const isNewCall = latest.ticketId != null
                    ? (lastAnnouncedTicketId !== latest.ticketId)
                    : (lastAnnouncedNumber !== latest.number);

                if (latest && isNewCall) {
                    lastAnnouncedTicketId = latest.ticketId;
                    lastAnnouncedNumber = latest.number;
                    const place = [latest.room, latest.chair].filter(Boolean).join(' ') || 'phòng khám';
                    const doc = latest.doctor ? latest.doctor : 'bác sĩ phụ trách';
                    const announcement = `Xin mời bệnh nhân số ${latest.number}, vào ${place}, gặp ${doc}.`;
                    speak(announcement);
                }
            }
            serving.replaceChildren(...data.serving.map(number => {
                const item = document.createElement('div');
                item.className = 'serving-item';
                item.textContent = `${number.number} · ${number.doctor} · ${location(number)}`;
                return item;
            }));
            if (!data.serving.length) serving.textContent = '—';
        } catch (_) {
            failCount++;
            if (failCount >= 2) updateConnStatus(false);
        }
    }
    refresh();
    setInterval(refresh, 4000);
});
