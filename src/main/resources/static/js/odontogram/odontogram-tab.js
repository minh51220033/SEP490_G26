(function () {
  'use strict';

  let Records = [];
  let LoadedOnce = false;
  let CurrentEditingId = null;
  let PendingDeleteId = null;

  function getConfig() {
    const root = document.getElementById('odontogramTabRoot');
    if (!root) return null;
    return {
      root: root,
      patientId: root.dataset.patientId ? Number(root.dataset.patientId) : null,
      visitId: root.dataset.visitId ? Number(root.dataset.visitId) : null,
      visitCode: root.dataset.visitCode || '',
      readOnly: String(root.dataset.readOnly) === 'true',
      patientDob: root.dataset.patientDob || '',
      badgeId: root.dataset.badgeId || ''
    };
  }

  function getCsrfHeaders() {
    const headers = {};
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (tokenMeta && headerMeta) {
      headers[headerMeta.getAttribute('content')] = tokenMeta.getAttribute('content');
      return headers;
    }
    const xsrfMatch = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    if (xsrfMatch) {
      headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrfMatch[1]);
    }
    return headers;
  }

  function escapeHtml(val) {
    if (val === null || val === undefined) return '';
    return String(val)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function notify(message, isError) {
    if (typeof window.showToast === 'function') {
      window.showToast(message, !!isError);
      return;
    }
    const toastEl = document.getElementById('odonToast');
    const msgEl = document.getElementById('odonToastMsg');
    if (toastEl && msgEl && window.bootstrap) {
      msgEl.textContent = message;
      toastEl.classList.remove('bg-success', 'bg-danger');
      toastEl.classList.add(isError ? 'bg-danger' : 'bg-success');
      bootstrap.Toast.getOrCreateInstance(toastEl, { delay: 3200 }).show();
    } else if (isError) {
      alert(message);
    }
  }

  function computeDefaultChartType(dobStr) {
    if (!dobStr) return 'ADULT';
    const dob = new Date(dobStr);
    if (Number.isNaN(dob.getTime())) return 'ADULT';
    const now = new Date();
    let age = now.getFullYear() - dob.getFullYear();
    const m = now.getMonth() - dob.getMonth();
    if (m < 0 || (m === 0 && now.getDate() < dob.getDate())) age--;
    return age >= 0 && age < 12 ? 'CHILD' : 'ADULT';
  }

  function todayIsoDate() {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  function updateBadge(count) {
    const cfg = getConfig();
    if (!cfg || !cfg.badgeId) return;
    const badge = document.getElementById(cfg.badgeId);
    if (!badge) return;
    badge.textContent = String(count);
    if (badge.classList.contains('d-none') && count > 0) {
      badge.classList.remove('d-none');
    } else if (!badge.classList.contains('df-tab-count') && count === 0) {
      badge.classList.add('d-none');
    }
  }

  function setListVisibility(state) {
    // state: 'loading' | 'empty' | 'table'
    const loadingEl = document.getElementById('odonLoadingState');
    const emptyEl = document.getElementById('odonEmptyState');
    const tableWrap = document.getElementById('odonTableWrapper');
    const toolbarEl = document.getElementById('odonFilterToolbar');
    const topAddBtn = document.getElementById('btnOdonCreateTop');
    const cfg = getConfig();
    const isReadOnly = !!(cfg && cfg.readOnly);

    if (loadingEl) loadingEl.classList.toggle('d-none', state !== 'loading');
    if (emptyEl) emptyEl.classList.toggle('d-none', state !== 'empty');
    if (tableWrap) tableWrap.classList.toggle('d-none', state !== 'table');
    if (toolbarEl) toolbarEl.classList.toggle('d-none', state !== 'table');

    // Content UX rule: never show duplicate Add button when empty state already displays one
    if (topAddBtn) {
      topAddBtn.classList.toggle('d-none', isReadOnly || state !== 'table');
    }
  }

  function renderTable() {
    const cfg = getConfig();
    const tbody = document.getElementById('odonTableBody');
    const noMatchRow = document.getElementById('odonFilterEmptyRow');
    const countLabel = document.getElementById('odonResultCount');
    if (!tbody || !cfg) return;

    if (!Records.length) {
      tbody.innerHTML = '';
      setListVisibility('empty');
      updateBadge(0);
      return;
    }

    setListVisibility('table');
    updateBadge(Records.length);

    const q = (document.getElementById('odonSearchInput')?.value || '').trim().toLowerCase();
    const typeFilter = (document.getElementById('odonTypeFilter')?.value || 'ALL').toUpperCase();

    const filtered = Records.filter(rec => {
      if (typeFilter !== 'ALL' && String(rec.chartType || '').toUpperCase() !== typeFilter) {
        return false;
      }
      if (!q) return true;
      const hay = [
        rec.chartDateDisplay || '',
        rec.visitCode || '',
        rec.note || '',
        rec.chartType || '',
        rec.createdByName || '',
        rec.updatedByName || ''
      ].join(' ').toLowerCase();
      return hay.includes(q);
    });

    if (countLabel) {
      countLabel.textContent = `${filtered.length}/${Records.length} bản ghi`;
    }

    if (!filtered.length) {
      tbody.innerHTML = '';
      if (noMatchRow) noMatchRow.classList.remove('d-none');
      return;
    }

    if (noMatchRow) noMatchRow.classList.add('d-none');

    tbody.innerHTML = filtered.map((rec, index) => {
      const isChild = String(rec.chartType || '').toUpperCase() === 'CHILD';
      const typeClass = isChild ? 'child' : 'adult';
      const typeLabel = isChild ? 'CHILD' : 'ADULT';

      const summaryPills = [];
      if (rec.statusCount > 0) {
        summaryPills.push(`<span class="df-odon-meta-pill status">${rec.statusCount} hiện trạng</span>`);
      }
      if (rec.planCount > 0) {
        summaryPills.push(`<span class="df-odon-meta-pill plan">${rec.planCount} kế hoạch</span>`);
      }

      const noteHtml = rec.note
        ? `<div class="text-dark fw-medium">${escapeHtml(rec.note)}</div>`
        : '<span class="text-muted fst-italic">Không có ghi chú</span>';

      const visitHtml = rec.visitCode
        ? `<span class="dv-code-pill text-nowrap">${escapeHtml(rec.visitCode)}</span>`
        : '<span class="text-muted">—</span>';

      const creatorHtml = rec.createdByName
        ? `<div class="fw-medium text-dark">${escapeHtml(rec.createdByName)}</div>`
        : '<span class="text-muted">—</span>';

      const createdAtHtml = rec.createdAt
        ? `<span class="text-secondary text-nowrap">${escapeHtml(rec.createdAt)}</span>`
        : '<span class="text-muted">—</span>';

      let updaterHtml = '<span class="text-muted">—</span>';
      if (rec.updatedAt) {
        updaterHtml = `
          <div class="fw-medium text-dark">${escapeHtml(rec.updatedByName || '—')}</div>
          <div class="text-muted small text-nowrap">${escapeHtml(rec.updatedAt)}</div>
        `;
      }

      const actionsHtml = cfg.readOnly
        ? `<button type="button" class="df-odon-icon-btn" data-odon-edit="${rec.odontogramId}" title="Xem chi tiết sơ đồ răng">
             <i class="bi bi-eye"></i>
           </button>`
        : `<div class="d-inline-flex align-items-center gap-1">
             <button type="button" class="df-odon-icon-btn odon-write-only" data-odon-edit="${rec.odontogramId}" title="Chỉnh sửa sơ đồ răng">
               <i class="bi bi-pencil"></i>
             </button>
             <button type="button" class="df-odon-icon-btn delete odon-write-only" data-odon-delete="${rec.odontogramId}" title="Xóa bản ghi">
               <i class="bi bi-trash"></i>
             </button>
           </div>`;

      return `
        <tr>
          <td class="text-center text-muted fw-semibold">${index + 1}</td>
          <td>
            <a href="javascript:void(0)" class="df-odon-date-link" data-odon-edit="${rec.odontogramId}">
              ${escapeHtml(rec.chartDateDisplay || rec.chartDate || '—')}
            </a>
          </td>
          <td>${visitHtml}</td>
          <td style="min-width: 210px;">
            ${noteHtml}
            ${summaryPills.length ? `<div class="d-flex flex-wrap gap-1 mt-1">${summaryPills.join('')}</div>` : ''}
          </td>
          <td class="text-center">
            <span class="df-odon-type-badge ${typeClass}">${typeLabel}</span>
          </td>
          <td>${creatorHtml}</td>
          <td>${createdAtHtml}</td>
          <td>${updaterHtml}</td>
          <td class="text-center text-nowrap">${actionsHtml}</td>
        </tr>
      `;
    }).join('');

    tbody.querySelectorAll('[data-odon-edit]').forEach(btn => {
      btn.addEventListener('click', () => {
        const id = Number(btn.getAttribute('data-odon-edit'));
        if (id) openEditorForRecord(id);
      });
    });

    tbody.querySelectorAll('[data-odon-delete]').forEach(btn => {
      btn.addEventListener('click', () => {
        const id = Number(btn.getAttribute('data-odon-delete'));
        if (id) openDeleteModal(id);
      });
    });
  }

  async function loadList() {
    const cfg = getConfig();
    if (!cfg || !cfg.patientId) {
      Records = [];
      renderTable();
      return;
    }

    if (!LoadedOnce) setListVisibility('loading');
    try {
      const res = await fetch(`/api/patients/${cfg.patientId}/odontograms`, { credentials: 'same-origin' });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || json.success === false) {
        throw new Error(json.message || 'Không tải được danh sách sơ đồ tình trạng răng.');
      }
      Records = Array.isArray(json.data) ? json.data : [];
      LoadedOnce = true;
      renderTable();
    } catch (err) {
      Records = [];
      renderTable();
      const errEl = document.getElementById('odonListError');
      if (errEl) {
        errEl.textContent = err.message;
        errEl.classList.remove('d-none');
      }
    }
  }

  function showListView() {
    document.getElementById('odonEditorView')?.classList.add('d-none');
    document.getElementById('odonListView')?.classList.remove('d-none');
    document.getElementById('odonEditorError')?.classList.add('d-none');
    CurrentEditingId = null;
  }

  function showEditorView(titleText, chartDateVal, visitCodeLabel, readOnly) {
    document.getElementById('odonListView')?.classList.add('d-none');
    document.getElementById('odonEditorView')?.classList.remove('d-none');
    document.getElementById('odonEditorError')?.classList.add('d-none');

    const titleEl = document.getElementById('odonEditorTitle');
    if (titleEl) titleEl.textContent = titleText;

    const dateInput = document.getElementById('odonChartDateInput');
    if (dateInput) {
      dateInput.value = chartDateVal || todayIsoDate();
      dateInput.readOnly = !!readOnly;
    }

    const visitBadge = document.getElementById('odonEditorVisitBadge');
    if (visitBadge) {
      if (visitCodeLabel) {
        visitBadge.textContent = `Ca khám: ${visitCodeLabel}`;
        visitBadge.classList.remove('d-none');
      } else {
        visitBadge.classList.add('d-none');
      }
    }

    const saveBtn = document.getElementById('btnSaveOdontogram');
    if (saveBtn) saveBtn.classList.toggle('d-none', !!readOnly);
  }

  async function openCreateEditor() {
    const cfg = getConfig();
    if (!cfg || cfg.readOnly || !window.OdontogramEngine) return;
    CurrentEditingId = null;

    const defaultType = computeDefaultChartType(cfg.patientDob);
    showEditorView('Thêm sơ đồ tình trạng răng', todayIsoDate(), cfg.visitCode, false);

    try {
      await window.OdontogramEngine.ensureReady();
      window.OdontogramEngine.loadState({
        chartType: defaultType,
        note: '',
        diseases: [],
        readOnly: false
      });
    } catch (err) {
      showEditorError(err.message);
    }
  }

  async function openEditorForRecord(id) {
    const cfg = getConfig();
    if (!cfg || !window.OdontogramEngine) return;

    try {
      const res = await fetch(`/api/odontograms/${id}`, { credentials: 'same-origin' });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || json.success === false || !json.data) {
        throw new Error(json.message || 'Không tải được chi tiết sơ đồ răng.');
      }

      const rec = json.data;
      CurrentEditingId = rec.odontogramId;
      let parsed = {};
      try {
        parsed = rec.payloadJson ? JSON.parse(rec.payloadJson) : {};
      } catch {
        parsed = {};
      }

      const title = cfg.readOnly ? 'Chi tiết sơ đồ tình trạng răng' : 'Chỉnh sửa sơ đồ tình trạng răng';
      showEditorView(title, rec.chartDate || todayIsoDate(), rec.visitCode || '', cfg.readOnly);

      await window.OdontogramEngine.ensureReady();
      window.OdontogramEngine.loadState({
        chartType: rec.chartType || parsed.chartType || 'ADULT',
        note: rec.note || '',
        diseases: Array.isArray(parsed.diseases) ? parsed.diseases : [],
        adultDiseases: Array.isArray(parsed.adultDiseases) ? parsed.adultDiseases : null,
        childDiseases: Array.isArray(parsed.childDiseases) ? parsed.childDiseases : null,
        readOnly: cfg.readOnly
      });
    } catch (err) {
      notify(err.message || 'Không thể mở sơ đồ răng.', true);
    }
  }

  function showEditorError(msg) {
    const errEl = document.getElementById('odonEditorError');
    if (errEl) {
      errEl.textContent = msg;
      errEl.classList.remove('d-none');
    }
  }

  async function saveCurrentOdontogram() {
    const cfg = getConfig();
    if (!cfg || cfg.readOnly || !cfg.patientId || !window.OdontogramEngine) return;

    const chartDateInput = document.getElementById('odonChartDateInput');
    const chartDate = chartDateInput ? chartDateInput.value : todayIsoDate();
    if (!chartDate) {
      showEditorError('Vui lòng chọn ngày ghi nhận sơ đồ răng.');
      chartDateInput?.focus();
      return;
    }

    const enginePayload = window.OdontogramEngine.getPayload();
    const requestBody = {
      visitId: cfg.visitId || null,
      chartDate: chartDate,
      chartType: enginePayload.chartType,
      note: enginePayload.note,
      payloadJson: enginePayload.payloadJson,
      statusCount: enginePayload.statusCount,
      planCount: enginePayload.planCount,
      affectedTeethCount: enginePayload.affectedTeethCount
    };

    const btn = document.getElementById('btnSaveOdontogram');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.setAttribute('aria-busy', 'true');
      btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Đang lưu...';
    }

    try {
      const isUpdate = CurrentEditingId != null;
      const url = isUpdate
        ? `/api/odontograms/${CurrentEditingId}`
        : `/api/patients/${cfg.patientId}/odontograms`;
      const method = isUpdate ? 'PUT' : 'POST';

      const headers = Object.assign({ 'Content-Type': 'application/json' }, getCsrfHeaders());
      const res = await fetch(url, {
        method: method,
        credentials: 'same-origin',
        headers: headers,
        body: JSON.stringify(requestBody)
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || json.success === false) {
        throw new Error(json.message || 'Không thể lưu sơ đồ tình trạng răng.');
      }

      window.OdontogramEngine.markClean();
      notify(json.message || 'Đã lưu sơ đồ tình trạng răng thành công.', false);
      showListView();
      await loadList();
    } catch (err) {
      showEditorError(err.message);
    } finally {
      if (btn) {
        btn.removeAttribute('aria-busy');
        btn.innerHTML = origHtml;
      }
    }
  }

  function openDeleteModal(id) {
    PendingDeleteId = id;
    const modalEl = document.getElementById('odonDeleteConfirmModal');
    if (modalEl && window.bootstrap) {
      bootstrap.Modal.getOrCreateInstance(modalEl).show();
    } else if (window.confirm('Bạn có chắc chắn muốn xóa bản ghi tình trạng răng này?')) {
      confirmDeleteRecord();
    }
  }

  async function confirmDeleteRecord() {
    if (!PendingDeleteId) return;
    const id = PendingDeleteId;
    const btn = document.getElementById('btnOdonConfirmDelete');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Đang xóa...';
    }

    try {
      const res = await fetch(`/api/odontograms/${id}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: getCsrfHeaders()
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || json.success === false) {
        throw new Error(json.message || 'Không thể xóa bản ghi sơ đồ răng.');
      }
      const modalEl = document.getElementById('odonDeleteConfirmModal');
      if (modalEl && window.bootstrap) {
        bootstrap.Modal.getInstance(modalEl)?.hide();
      }
      PendingDeleteId = null;
      notify(json.message || 'Đã xóa bản ghi sơ đồ răng.', false);
      await loadList();
    } catch (err) {
      notify(err.message, true);
    } finally {
      if (btn) btn.innerHTML = origHtml;
    }
  }

  function hoistOverlayModalsToBody() {
    ['jsonExportModal', 'odonDeleteConfirmModal', 'dragSelectBox', 'toothTooltip', 'toothContextMenu', 'odonToastContainer']
      .forEach(id => {
        const el = document.getElementById(id);
        if (el && el.parentElement !== document.body) {
          document.body.appendChild(el);
        }
      });
  }

  function bindTabEvents() {
    hoistOverlayModalsToBody();

    document.getElementById('btnOdonCreateTop')?.addEventListener('click', openCreateEditor);
    document.getElementById('btnOdonCreateEmpty')?.addEventListener('click', openCreateEditor);
    document.getElementById('btnOdonBackToList')?.addEventListener('click', showListView);
    document.getElementById('btnOdonCancelEdit')?.addEventListener('click', showListView);
    document.getElementById('btnSaveOdontogram')?.addEventListener('click', saveCurrentOdontogram);
    document.getElementById('btnOdonConfirmDelete')?.addEventListener('click', confirmDeleteRecord);

    document.getElementById('odonSearchInput')?.addEventListener('input', renderTable);
    document.getElementById('odonTypeFilter')?.addEventListener('change', renderTable);

    // Preload list silently to synchronize tab badge count on page open
    loadList();
  }

  document.addEventListener('DOMContentLoaded', bindTabEvents);

  window.OdontogramTab = {
    onTabShown: function () {
      const editorView = document.getElementById('odonEditorView');
      if (editorView && !editorView.classList.contains('d-none')) {
        window.OdontogramEngine?.redraw();
      } else {
        loadList();
      }
    },
    openCreateEditor: openCreateEditor,
    reload: loadList
  };
})();
