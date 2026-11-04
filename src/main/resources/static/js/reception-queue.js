document.addEventListener('DOMContentLoaded', () => {
    let board = document.getElementById('queueRows');
    const filter = document.getElementById('doctorFilter');
    const branch = document.getElementById('queueConfig')?.textContent.trim();
    if (!board || !filter || !branch) return;

    function applyFilter() {
        board.querySelectorAll('[data-doctor]').forEach(item => {
            item.hidden = Boolean(filter.value) && item.dataset.doctor !== filter.value;
        });
    }
    filter.addEventListener('change', applyFilter);

    async function refresh() {
        if (board.querySelector('details[open]') || board.contains(document.activeElement)) return;
        try {
            const response = await fetch('/reception/queue/rows?branchId=' + encodeURIComponent(branch), {cache: 'no-store'});
            if (!response.ok) return;
            const html = await response.text();
            const parsed = new DOMParser().parseFromString(html, 'text/html');
            const fresh = parsed.getElementById('queueRows');
            if (fresh) {
                board.replaceWith(fresh);
                board = fresh;
                applyFilter();
            }
        } catch (_) {
            return;
        }
    }
    setInterval(refresh, 10000);
});
