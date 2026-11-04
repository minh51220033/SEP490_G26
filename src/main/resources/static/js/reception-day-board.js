document.addEventListener('DOMContentLoaded', async () => {
    const date = document.getElementById('dayDate');
    const branch = document.getElementById('dayBranch');
    const search = document.getElementById('daySearch');
    const board = document.getElementById('dayBoard');
    const summary = document.getElementById('daySummary');
    const error = document.getElementById('dayBoardError');
    const params = new URLSearchParams(location.search);
    const local = new Date();
    date.value = params.get('date') || `${local.getFullYear()}-${String(local.getMonth() + 1).padStart(2, '0')}-${String(local.getDate()).padStart(2, '0')}`;
    const todayIso = `${local.getFullYear()}-${String(local.getMonth() + 1).padStart(2, '0')}-${String(local.getDate()).padStart(2, '0')}`;
    const names = {PENDING:'Chờ xác nhận',CONFIRMED:'Đã xác nhận',CHECKED_IN:'Đã đến',NO_SHOW:'Không đến',CANCELLED:'Đã hủy',COMPLETED:'Hoàn thành'};
    const statusBadges = {
        PENDING: 'badge bg-warning-subtle text-warning-emphasis border border-warning',
        CONFIRMED: 'badge bg-primary-subtle text-primary border border-primary-subtle',
        CHECKED_IN: 'badge bg-success-subtle text-success border border-success-subtle',
        COMPLETED: 'badge bg-success-subtle text-success border border-success-subtle',
        NO_SHOW: 'badge bg-danger-subtle text-danger border border-danger-subtle',
        CANCELLED: 'badge bg-secondary-subtle text-secondary border border-secondary-subtle'
    };

    async function get(path) {
        const response = await fetch('/reception/appointments/api' + path, {credentials:'same-origin'});
        if (!response.ok) throw new Error('Không tải được lịch hẹn. Vui lòng thử lại.');
        return response.json();
    }
    function node(tag, className, value) {
        const element = document.createElement(tag);
        if (className) element.className = className;
        if (value != null) element.textContent = value;
        return element;
    }
    let appointments = [];
    let branchIds = [];
    let assignments = new Map();
    function render() {
        const term = search.value.trim().toLocaleLowerCase('vi');
        const rows = appointments.filter(a => !term || [a.patientName,a.patientCode,a.patientPhone].some(value => (value || '').toLocaleLowerCase('vi').includes(term)));
        board.replaceChildren(); summary.replaceChildren();
        for (const [label, count] of [['Tổng lịch', rows.length], ['Chờ xác nhận', rows.filter(a => a.status === 'PENDING').length], ['Đã đến', rows.filter(a => a.status === 'CHECKED_IN').length], ['Không đến', rows.filter(a => a.status === 'NO_SHOW').length]]) summary.append(node('span', '', `${label}: ${count}`));
        if (!rows.length) {
            const empty = node('div', 'day-empty');
            empty.append(node('p', 'mb-2 text-muted', 'Không có lịch hẹn nào phù hợp trong ngày này.'));
            const newApptBtn = node('a', 'btn btn-primary btn-sm', '+ Tạo lịch hẹn mới');
            newApptBtn.href = `/reception/appointments/new?date=${encodeURIComponent(date.value)}${branch.value ? '&branchId=' + encodeURIComponent(branch.value) : ''}`;
            empty.append(newApptBtn);
            board.append(empty);
            return;
        }
        const groups = new Map();
        rows.forEach(a => {const assigned = assignments.get(a.id); const doctor = assigned?.doctorName || a.doctorName || 'Chưa phân bác sĩ';
            const key = `${doctor} · ${assigned?.chairName || 'Chưa phân ghế'}`;
            if (!groups.has(key)) groups.set(key, []); groups.get(key).push(a);});
        for (const [doctor, items] of groups) {
            const lane = node('section','doctor-lane'); const header = node('header'); header.append(node('h2','',doctor),node('small','',`${items.length} lịch`)); lane.append(header);
            for (const session of ['MORNING','AFTERNOON','EVENING','ANY']) {
                const block = node('div','session-block'); block.append(node('h3','',({MORNING:'Buổi sáng',AFTERNOON:'Buổi chiều',EVENING:'Buổi tối',ANY:'Chưa chốt buổi'})[session]));
                const sessionItems = items.filter(a => a.session === session);
                if (!sessionItems.length) block.append(node('small','text-muted','Chưa có lịch'));
                sessionItems.sort((a,b) => (a.expectedTime || '99:99').localeCompare(b.expectedTime || '99:99'));
                sessionItems.forEach(a => {
                    const card = node('a','appointment-card');
                    card.href = `/reception/appointments/${encodeURIComponent(a.id)}`;
                    card.dataset.status = a.status;

                    let isLate = false;
                    let lateMinutes = 0;
                    if (a.expectedTime && (a.status === 'PENDING' || a.status === 'CONFIRMED') && date.value === todayIso) {
                        const parts = a.expectedTime.split(':').map(Number);
                        const now = new Date();
                        const sched = new Date();
                        sched.setHours(parts[0], parts[1], 0, 0);
                        const diff = Math.floor((now - sched) / 60000);
                        if (diff >= 15) {
                            isLate = true;
                            lateMinutes = diff;
                        }
                    }
                    if (isLate) card.classList.add('is-late');

                    card.append(
                        node('strong','',`${a.expectedTime ? a.expectedTime.slice(0,5) : 'Chưa chốt giờ'} · ${a.patientName || a.patientCode}`),
                        node('small','',`${a.patientCode || ''} · ${a.serviceName || 'Chưa chọn dịch vụ'} · ${a.durationMinutes || 30} phút`)
                    );
                    const badgeClass = statusBadges[a.status] || 'badge bg-light text-dark border';
                    const badgeEl = node('span', badgeClass, names[a.status] || a.status);
                    if (isLate) {
                        const badgeWrap = node('div', 'd-flex align-items-center gap-1 mt-1');
                        badgeWrap.append(badgeEl, node('span', 'badge bg-danger text-white', `Trễ ${lateMinutes}p`));
                        card.append(badgeWrap);
                    } else {
                        card.append(badgeEl);
                    }
                    block.append(card);
                });
                lane.append(block);
            }
            board.append(lane);
        }
    }
    async function load() {
        error.hidden = true;
        try {
            const query = new URLSearchParams({start:date.value,end:date.value});
            if (branch.value) query.set('branchId',branch.value);
            appointments = await get('/calendar?' + query);
            const ids = branch.value ? [branch.value] : branchIds;
            const maps = await Promise.all(ids.map(async id => {
                const response = await fetch(`/reception/appointments/day/assignments?branchId=${encodeURIComponent(id)}&date=${encodeURIComponent(date.value)}`, {credentials:'same-origin'});
                if (!response.ok) throw new Error('Không tải được thông tin ghế và bác sĩ.');
                return response.json();
            }));
            assignments = new Map(maps.flat().map(item => [item.appointmentId, item]));
            render();
        } catch (cause) {
            error.textContent = cause.message;
            error.hidden = false;
            board.replaceChildren();
        }
    }
    try {
        const options = await get('/options');
        branchIds = (options.branches || []).map(item => item.id);
        branch.append(node('option','','Tất cả chi nhánh'));
        branch.options[0].value = '';
        (options.branches || []).forEach(item => {
            const option = node('option','',item.name);
            option.value = item.id;
            branch.append(option);
        });
        if (params.get('branchId')) branch.value = params.get('branchId');
        await load();
    } catch (cause) {
        error.textContent = cause.message;
        error.hidden = false;
    }
    date.addEventListener('change',load);
    branch.addEventListener('change',load);
    search.addEventListener('input',render);
});
