document.addEventListener('click', async (event) => {
    const button = event.target.closest('[data-default-id]');
    if (!button) return;
    button.disabled = true;
    try {
        const response = await fetch(`/portal/patients/${encodeURIComponent(button.dataset.defaultId)}/default`, {
            method: 'POST', credentials: 'same-origin'
        });
        if (!response.ok) throw new Error('Không thể chọn hồ sơ mặc định.');
        window.location.reload();
    } catch (error) {
        window.alert(error.message);
        button.disabled = false;
    }
});
