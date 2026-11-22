(function () {
  'use strict';

  const DATA_URL = '/js/odontogram/odontogram-data.json';
  const TeethWidth = 40;
  const TeethHeight = 90;
  const TeethCircle = 40;
  const CANVAS_SCALE = 2;
  const MAX_UNDO = 40;

  let VT_DATA = null;
  let DataPromise = null;
  let AssetsPreloaded = false;
  let EventsBound = false;

  let TeethChart_Type = 1; // 1 = ADULT (32), 2 = CHILD (20)
  let DefaultClassification = 1; // 1 = Hiện trạng, 2 = Kế hoạch
  let LastClickedToothIdx = null;
  let ReadOnlyMode = false;

  let TeethCollection = [];
  let TeethCollkv = {};
  let Teethpaths = {};
  let TeethSelected = {};
  let CurrentDisease = [];
  let CurrentChildDisease = [];

  const UndoStack = [];
  const RedoStack = [];
  let PaintMode = false;
  let ActivePaintDiseaseId = null;
  let EraseMode = false;
  let SuppressNextChartClick = false;
  let Dirty = false;

  const PLAN_PROCEDURE_IDS = new Set([
    '105', '106', '107', '108', '101', '102', '103', '112', '113',
    '50', '73', '51', '32', '63', '29',
    '53', '61',
    '11', '10', '12', '9', '36',
    '75', '71', '8'
  ]);

  const MISSING_IDS = new Set(['17', '30', '57', '104']);
  const PROBLEM_IDS = new Set([
    '6', '7', '15', '19', '20', '21', '22', '25', '28', '37', '38',
    '39', '40', '41', '42', '54', '55', '56', '58', '59', '62', '64',
    '66', '69', '70', '72', '75', '77', '109', '110', '111', '114', '115'
  ]);

  const SUMMARY_CATEGORIES = [
    { label: 'Sâu răng', empty: 'Không có răng sâu.', ids: new Set(['28', '39', '40', '41', '42', '20', '66']) },
    { label: 'Trám & Phục hồi', empty: 'Chưa có răng trám.', ids: new Set(['9', '10', '11', '12', '21', '22', '27', '35', '36', '65', '106', '107']) },
    { label: 'Điều trị nội nha & Chốt', empty: 'Chưa có răng điều trị tủy.', ids: new Set(['52', '53', '60', '61', '72', '112', '113']) },
    { label: 'Phục hình & Implant', empty: 'Không có phục hình.', ids: new Set(['29', '32', '50', '51', '63', '73', '101', '102', '103', '108', '111']) },
    { label: 'Chỉnh nha & Sai lệch vị trí', empty: 'Không có ghi nhận chỉnh nha.', ids: new Set(['105', '109', '110', '14', '33', '34', '44', '45', '46', '47', '48', '49', '68', '74', '76']) },
    { label: 'Mòn / Gãy / Tổn thương thân & chân răng', empty: 'Không có ghi nhận mòn, gãy hoặc đổi màu.', ids: new Set(['19', '25', '37', '54', '55', '58', '62', '64', '69', '70', '77', '114', '115']) },
    { label: 'Tình trạng nha chu & Nướu', empty: 'Nha chu khỏe mạnh.', ids: new Set(['6', '7', '15', '38', '59', '71']) },
    { label: 'Mất răng / Nhổ răng / Ghi nhận khác', empty: 'Không có răng mất hoặc chỉ định nhổ.', ids: new Set(['17', '30', '31', '56', '57', '75', '104', '5', '8']) }
  ];

  const ImageCache = new Map();

  function preloadImage(src) {
    if (!src) return Promise.resolve(null);
    if (ImageCache.has(src)) {
      const cached = ImageCache.get(src);
      if (cached instanceof Promise) return cached;
      return Promise.resolve(cached);
    }
    const p = new Promise((resolve) => {
      const img = new Image();
      img.onload = () => {
        ImageCache.set(src, img);
        resolve(img);
      };
      img.onerror = () => {
        ImageCache.set(src, null);
        resolve(null);
      };
      img.src = src;
    });
    ImageCache.set(src, p);
    return p;
  }

  async function preloadAllAssets() {
    if (AssetsPreloaded || !VT_DATA) return;
    const urls = new Set([VT_DATA.MatrangUri]);
    Object.values(VT_DATA.AdultTeethSvg || {}).forEach(u => urls.add(u));
    Object.values(VT_DATA.ChildTeethSvg || {}).forEach(u => urls.add(u));
    Object.values(VT_DATA.OverlayUris || {}).forEach(u => urls.add(u));
    await Promise.all(Array.from(urls).map(preloadImage));
    AssetsPreloaded = true;
  }

  async function loadDataOnce() {
    if (VT_DATA) return VT_DATA;
    if (!DataPromise) {
      DataPromise = fetch(DATA_URL, { credentials: 'same-origin' })
        .then(res => {
          if (!res.ok) throw new Error('Không tải được dữ liệu danh mục sơ đồ răng.');
          return res.json();
        })
        .then(json => {
          VT_DATA = json;
          return VT_DATA;
        });
    }
    return DataPromise;
  }

  function isEditorVisible() {
    const view = document.getElementById('odonEditorView');
    return !!(view && !view.classList.contains('d-none') && view.offsetParent !== null);
  }

  function getActiveDiseaseArray() {
    return TeethChart_Type === 1 ? CurrentDisease : CurrentChildDisease;
  }

  function captureSnapshot() {
    return {
      chartType: TeethChart_Type,
      adult: CurrentDisease.map(x => ({ ...x })),
      child: CurrentChildDisease.map(x => ({ ...x }))
    };
  }

  function pushUndoState() {
    Dirty = true;
    UndoStack.push(captureSnapshot());
    if (UndoStack.length > MAX_UNDO) UndoStack.shift();
    RedoStack.length = 0;
    updateUndoBtn();
  }

  function updateUndoBtn() {
    const btnU = document.getElementById('btnUndo');
    const btnR = document.getElementById('btnRedo');
    if (btnU) btnU.disabled = ReadOnlyMode || UndoStack.length === 0;
    if (btnR) btnR.disabled = ReadOnlyMode || RedoStack.length === 0;
  }

  function restoreSnapshot(snap) {
    CurrentDisease = snap.adult;
    CurrentChildDisease = snap.child;
    if (snap.chartType !== TeethChart_Type) {
      TeethChart_Type = snap.chartType;
      syncChartTypeButtons();
      buildToothChart();
    } else {
      TeethCollection.forEach(t => Dencan_Draw1Teeth(t.ID));
      Dendis_RenderDisease();
      refreshSelectionChip();
      updateModeUI();
    }
    updateUndoBtn();
  }

  function performUndo() {
    if (ReadOnlyMode || !UndoStack.length) return;
    RedoStack.push(captureSnapshot());
    restoreSnapshot(UndoStack.pop());
  }

  function performRedo() {
    if (ReadOnlyMode || !RedoStack.length) return;
    UndoStack.push(captureSnapshot());
    restoreSnapshot(RedoStack.pop());
  }

  function filterSidebarItems() {
    const searchInput = document.getElementById('Dissearch_Input');
    const q = searchInput ? searchInput.value.trim().toLowerCase() : '';
    const isPlan = DefaultClassification === 2;
    const col1 = document.getElementById('descoslap1');
    const col2 = document.getElementById('descoslap2');
    if (q && col1 && col2) {
      col1.classList.add('show');
      col2.classList.add('show');
    }

    document.querySelectorAll('#Candisease .diseaseitem').forEach(li => {
      const did = String(li.getAttribute('data_id'));
      const txt = li.textContent.toLowerCase();
      const matchPlan = !isPlan || PLAN_PROCEDURE_IDS.has(did);
      const matchSearch = q === '' || txt.includes(q);
      li.classList.toggle('d-none', !(matchPlan && matchSearch));
    });

    document.querySelectorAll('#Candisease_Short .shortitem').forEach(a => {
      const did = String(a.getAttribute('data_id'));
      const matchPlan = !isPlan || PLAN_PROCEDURE_IDS.has(did);
      a.classList.toggle('d-none', !matchPlan);
    });
  }

  function setPaintDisease(diseaseId) {
    if (ReadOnlyMode) return;
    if (EraseMode) exitEraseMode();
    if (PaintMode && String(ActivePaintDiseaseId) === String(diseaseId)) {
      exitPaintMode();
      return;
    }
    PaintMode = true;
    ActivePaintDiseaseId = String(diseaseId);
    updatePaintUI();
    updateModeUI();
  }

  function exitPaintMode() {
    PaintMode = false;
    ActivePaintDiseaseId = null;
    updatePaintUI();
    updateModeUI();
  }

  function toggleEraseMode() {
    if (ReadOnlyMode) return;
    if (EraseMode) {
      exitEraseMode();
    } else {
      if (PaintMode) exitPaintMode();
      EraseMode = true;
      updateEraseUI();
    }
  }

  function exitEraseMode() {
    EraseMode = false;
    updateEraseUI();
  }

  function updateEraseUI() {
    const btn = document.getElementById('btnCornerClear');
    const icon = document.getElementById('btnCornerClearIcon');
    const txt = document.getElementById('btnCornerClearText');
    const upDown = document.getElementById('Canteeth_UpDown');
    const selTeethCount = new Set(Object.values(TeethSelected).map(v => String(v.Teeth))).size;
    const isDeselect = !EraseMode && selTeethCount > 0;
    if (upDown) upDown.classList.toggle('eraser-cursor', EraseMode && !ReadOnlyMode);
    if (btn) btn.classList.toggle('eraser-active', EraseMode && !ReadOnlyMode);
    if (icon) icon.className = isDeselect ? 'bi bi-x-lg' : 'bi bi-eraser';
    if (txt) {
      txt.textContent = isDeselect ? `Bỏ chọn (${selTeethCount})` : 'Xóa nhanh';
    }
  }

  function updatePaintUI() {
    const upDown = document.getElementById('Canteeth_UpDown');
    if (upDown) upDown.classList.toggle('paint-cursor', !ReadOnlyMode && PaintMode && !!ActivePaintDiseaseId);
    document.querySelectorAll('#Candisease .diseaseitem').forEach(li => {
      li.classList.toggle('paint-armed', !ReadOnlyMode && PaintMode && li.getAttribute('data_id') === String(ActivePaintDiseaseId));
    });
  }

  function applyDiseaseToSingleTarget(toothId, part, diseaseid) {
    if (ReadOnlyMode || !VT_DATA) return;
    const dis = VT_DATA.DiseaseList[String(diseaseid)];
    if (!dis) return;
    let targetPart = part || 'root';
    if (dis.Type == 1 && !['face', 'root'].includes(targetPart)) targetPart = 'root';
    else if (dis.Type == 2 && ['face', 'root'].includes(targetPart)) targetPart = 'c';

    const arr = getActiveDiseaseArray();
    const exists = arr.some(
      d => String(d.Teethid) === String(toothId)
        && String(d.Diseaseid) === String(diseaseid)
        && (dis.Type == 1 ? true : d.Part === targetPart)
    );
    if (exists) return;
    pushUndoState();
    arr.push({
      Diseaseid: String(diseaseid),
      Teethid: String(toothId),
      Type: dis.Type,
      Part: targetPart,
      Treat: 'treatment for teeth',
      Note: '',
      Classification: DefaultClassification
    });
    Dencan_Draw1Teeth(toothId);
    Dendis_RenderDisease();
    updateModeUI();
  }

  function clearToothDiseases(teethId) {
    if (ReadOnlyMode) return;
    const arr = getActiveDiseaseArray();
    const hasAny = arr.some(d => String(d.Teethid) === String(teethId));
    if (!hasAny) return;
    pushUndoState();
    const filtered = arr.filter(d => String(d.Teethid) !== String(teethId));
    if (TeethChart_Type === 1) CurrentDisease = filtered;
    else CurrentChildDisease = filtered;
    Dencan_Draw1Teeth(teethId);
    Dendis_RenderDisease();
    refreshSelectionChip();
    updateModeUI();
    hideToothTooltip();
    hideToothContextMenu();
  }

  function clearSelectedTeethDiseases() {
    if (ReadOnlyMode) return;
    const selIds = new Set(Object.values(TeethSelected).map(v => String(v.Teeth)));
    if (!selIds.size) return;
    const arr = getActiveDiseaseArray();
    const hasAny = arr.some(d => selIds.has(String(d.Teethid)));
    if (!hasAny) return;
    pushUndoState();
    const filtered = arr.filter(d => !selIds.has(String(d.Teethid)));
    if (TeethChart_Type === 1) CurrentDisease = filtered;
    else CurrentChildDisease = filtered;
    selIds.forEach(tid => Dencan_Draw1Teeth(tid));
    Dendis_RenderDisease();
    refreshSelectionChip();
    updateModeUI();
  }

  function hideToothContextMenu() {
    const menu = document.getElementById('toothContextMenu');
    if (menu) {
      menu.style.display = 'none';
      menu.setAttribute('aria-hidden', 'true');
    }
  }

  function showToothContextMenu(evt, tooth) {
    hideToothTooltip();
    if (ReadOnlyMode) return;
    const menu = document.getElementById('toothContextMenu');
    if (!menu || !tooth || !VT_DATA) return;
    const arr = getActiveDiseaseArray();
    const entries = [];
    arr.forEach((rec, idx) => {
      if (String(rec.Teethid) === String(tooth.ID)) entries.push({ rec, idx });
    });

    if (!entries.length) {
      menu.innerHTML = `
        <div class="ctx-header">
          <span>Răng ${tooth.TeethName}</span>
          <span class="text-xs fw-normal text-secondary">Trống</span>
        </div>
        <div class="px-2 py-2 text-xs text-secondary">Răng này chưa có tình trạng nào.</div>
      `;
    } else {
      const rowsHtml = entries.map(({ rec, idx }) => {
        const dis = VT_DATA.DiseaseList[String(rec.Diseaseid)];
        const name = dis ? dis.Name : rec.Diseaseid;
        const partLbl = Dendis_RenderPathName(rec.Part, tooth.ID);
        const isPlan = Number(rec.Classification) === 2;
        return `
          <div class="ctx-row">
            <div class="d-flex align-items-center gap-2 text-truncate">
              <span class="${isPlan ? 'ctx-dot-plan' : 'ctx-dot-solid'}" title="${isPlan ? 'Kế hoạch' : 'Hiện trạng'}"></span>
              <div class="text-truncate">
                <div class="fw-semibold text-truncate">${name}</div>
                ${partLbl ? `<div class="text-secondary" style="font-size:0.68rem;">${partLbl} • ${isPlan ? 'Kế hoạch' : 'Hiện trạng'}</div>` : ''}
              </div>
            </div>
            <div class="d-flex align-items-center gap-1 flex-shrink-0">
              ${isPlan ? `<button type="button" class="ctx-icon-btn ctx-done" data-ctx-done="${idx}" title="Hoàn thành: chuyển sang Hiện trạng"><i class="bi bi-check2"></i></button>` : ''}
              <button type="button" class="ctx-icon-btn ctx-del" data-ctx-del="${idx}" title="Xóa tình trạng này"><i class="bi bi-x-lg"></i></button>
            </div>
          </div>
        `;
      }).join('');

      menu.innerHTML = `
        <div class="ctx-header">
          <span>Răng ${tooth.TeethName}</span>
          <span class="badge bg-light text-secondary border">${entries.length} mục</span>
        </div>
        ${rowsHtml}
        <button type="button" class="ctx-clear-all" data-ctx-clear-all="${tooth.ID}">
          <i class="bi bi-trash"></i>
          <span>Xóa tất cả trên răng ${tooth.TeethName}</span>
        </button>
      `;

      menu.querySelectorAll('[data-ctx-del]').forEach(btn => {
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          const i = Number(btn.dataset.ctxDel);
          const curArr = getActiveDiseaseArray();
          if (!curArr[i]) return;
          pushUndoState();
          curArr.splice(i, 1);
          Dencan_Draw1Teeth(tooth.ID);
          Dendis_RenderDisease();
          refreshSelectionChip();
          updateModeUI();
          if (curArr.some(d => String(d.Teethid) === String(tooth.ID))) {
            showToothContextMenu(evt, tooth);
          } else {
            hideToothContextMenu();
          }
        });
      });

      menu.querySelectorAll('[data-ctx-done]').forEach(btn => {
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          const i = Number(btn.dataset.ctxDone);
          const curArr = getActiveDiseaseArray();
          if (!curArr[i]) return;
          pushUndoState();
          curArr[i].Classification = 1;
          Dencan_Draw1Teeth(tooth.ID);
          Dendis_RenderDisease();
          updateModeUI();
          showToothContextMenu(evt, tooth);
        });
      });

      menu.querySelector('[data-ctx-clear-all]')?.addEventListener('click', (e) => {
        e.stopPropagation();
        clearToothDiseases(tooth.ID);
      });
    }

    menu.style.display = 'block';
    menu.setAttribute('aria-hidden', 'false');
    const rect = menu.getBoundingClientRect();
    let left = evt.clientX;
    let top = evt.clientY;
    if (left + rect.width > window.innerWidth - 10) left = window.innerWidth - rect.width - 10;
    if (top + rect.height > window.innerHeight - 10) top = window.innerHeight - rect.height - 10;
    menu.style.left = Math.max(8, left) + 'px';
    menu.style.top = Math.max(8, top) + 'px';
  }

  function formatToothLabel(toothObj) {
    return toothObj ? toothObj.TeethName : '';
  }

  function Dencan_GetImageInver(_index) {
    if (TeethChart_Type == 1) {
      return !(_index >= 1 && _index <= 8);
    }
    return !(_index >= 1 && _index <= 5);
  }

  function DendisR_DetectType(toothObj) {
    if (toothObj.RType) return toothObj.RType;
    const eFace = toothObj.E_Face;
    if (eFace == 7) return 'r3u';
    if (eFace == 5) return 'r1u';
    if (eFace == 13) return 'r3d';
    return 'r1d';
  }

  function Dencan_GetImage(imageid, typesign, teethId) {
    const toothObj = TeethCollkv[String(teethId)];
    if (!toothObj || !VT_DATA) return '';
    const _image = VT_DATA.ImageCollection[String(imageid)];
    if (!_image) return '';
    const isInver = Dencan_GetImageInver(toothObj.Index);
    switch (typesign) {
      case 'r1u': return !isInver ? _image.r1u : _image.r1uR;
      case 'r3u': return !isInver ? _image.r3u : _image.r3uR;
      case 'r1d': return !isInver ? _image.r1d : _image.r1dR;
      case 'r3d': return !isInver ? _image.r3d : _image.r3dR;
      case 'circle': return _image.circle;
      default: return '';
    }
  }

  function Canele_Pattern(color, size) {
    const r = document.createElement('canvas');
    const i = r.getContext('2d');
    r.width = size;
    r.height = size;
    i.fillStyle = 'transparent';
    i.fillRect(0, 0, r.width, r.height);
    i.strokeStyle = color;
    i.lineWidth = 1;
    i.beginPath();
    i.moveTo(0, 0);
    i.lineTo(size, size);
    i.stroke();
    return r;
  }

  function createWearPattern(linkName) {
    const c = document.createElement('canvas');
    c.width = 8;
    c.height = 8;
    const ctx = c.getContext('2d');
    ctx.strokeStyle = linkName === 'pattern_5' ? '#dc2626' : '#ea0606';
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    ctx.moveTo(0, 4);
    ctx.lineTo(8, 4);
    if (linkName === 'pattern_5') {
      ctx.moveTo(4, 0);
      ctx.lineTo(4, 8);
    }
    ctx.stroke();
    if (linkName === 'pattern_5') {
      ctx.fillStyle = '#ea0606';
      ctx.beginPath();
      ctx.arc(4, 4, 1.5, 0, Math.PI * 2);
      ctx.fill();
    }
    return c;
  }

  function Can_DrawSelected(ctx, path2d) {
    ctx.save();
    const patCanvas = Canele_Pattern('green', 3);
    const pattern = ctx.createPattern(patCanvas, 'repeat');
    ctx.fillStyle = pattern;
    ctx.fill(path2d);
    ctx.restore();
  }

  function getToothSvgUri(toothObj) {
    if (TeethChart_Type === 1) return VT_DATA.AdultTeethSvg[toothObj.ImageExtension];
    return VT_DATA.ChildTeethSvg[toothObj.ImageExtension];
  }

  function getToothPathsMap(toothObj) {
    if (TeethChart_Type === 1) return VT_DATA.AdultTeethPaths[toothObj.ImageExtension] || {};
    return VT_DATA.ChildTeethPaths[toothObj.ImageExtension] || {};
  }

  function resetToothBase(teethId) {
    const toothObj = TeethCollkv[String(teethId)];
    if (!toothObj || !VT_DATA) return;

    const textEl = document.getElementById('teethfacetext_' + teethId);
    if (textEl) {
      textEl.innerHTML = '';
      textEl.style.color = '';
      textEl.style.backgroundColor = '';
    }

    const faceCanvas = document.getElementById('teethface_' + teethId);
    const circleCanvas = document.getElementById('teethcircle_' + teethId);
    if (faceCanvas) {
      const ctx = faceCanvas.getContext('2d');
      ctx.setTransform(CANVAS_SCALE, 0, 0, CANVAS_SCALE, 0, 0);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.clearRect(0, 0, TeethWidth, TeethHeight);
      const img = ImageCache.get(getToothSvgUri(toothObj));
      if (img && img.complete) {
        ctx.drawImage(img, 0, 0, TeethWidth, TeethHeight);
      }
    }
    if (circleCanvas) {
      const ctx = circleCanvas.getContext('2d');
      ctx.setTransform(CANVAS_SCALE, 0, 0, CANVAS_SCALE, 0, 0);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.clearRect(0, 0, TeethCircle, TeethCircle);
      const img = ImageCache.get(VT_DATA.MatrangUri);
      if (img && img.complete) {
        ctx.drawImage(img, 0, 0, TeethCircle, TeethCircle);
      }
    }
  }

  function collectDrawOpsForRecord(teethId, part, datadisease, typedisease) {
    const ops = [];
    let isallowdraw = 0;
    if (part === 'face' || part === 'root') {
      if (typedisease == 1) isallowdraw = 1;
    } else {
      if (typedisease == 2) isallowdraw = 1;
    }
    if (isallowdraw !== 1 || !Array.isArray(datadisease)) return ops;

    if (typedisease == 2) {
      if (datadisease[0] && datadisease[0].data) {
        ops.push({ part, iscircle: 1, items: datadisease[0].data });
      }
    } else {
      for (let i = 0; i < datadisease.length; i++) {
        const area = datadisease[i].area;
        const data = datadisease[i].data;
        if (area === 'rootface') {
          for (let j = 0; j < data.length; j++) {
            ops.push({ part: data[j].area, iscircle: 0, items: data[j].data });
          }
        } else if (['l', 't', 'r', 'b', 'c'].includes(area)) {
          ops.push({ part: area, iscircle: 1, items: data });
        }
      }
    }
    return ops;
  }

  function executeDrawOp(teethId, op, passType, classification) {
    const objid = (op.iscircle === 1 ? 'teethcircle_' : 'teethface_') + teethId + '_' + op.part;
    const canvasid = (op.iscircle === 1 ? 'teethcircle_' : 'teethface_') + teethId;
    const canvas = document.getElementById(canvasid);
    if (!canvas || !Teethpaths[objid]) return;
    const ctx = canvas.getContext('2d');
    const _2d = Teethpaths[objid].path;
    const _typedesign = Teethpaths[objid].type;
    const toothObj = TeethCollkv[String(teethId)];

    for (let xx = 0; xx < op.items.length; xx++) {
      const item = op.items[xx];
      if (item.having !== '1') continue;
      if (passType === 'bg' && (item.type === 'color' || item.type === 'pattern')) {
        ctx.save();
        if (classification == 2) ctx.setLineDash([3, 2]);
        if (item.type === 'color') {
          const canpattern = Canele_Pattern(item.fill, 4);
          const pattern = ctx.createPattern(canpattern, 'repeat');
          ctx.fillStyle = pattern;
          ctx.strokeStyle = classification == 2 ? '#d97706' : (item.stroke || '#495059');
          ctx.lineWidth = classification == 2 ? 1.6 : 1;
          ctx.fill(_2d);
          ctx.stroke(_2d);
        } else {
          const wearCanvas = createWearPattern(item.patternlink);
          const pattern = ctx.createPattern(wearCanvas, 'repeat');
          ctx.fillStyle = pattern;
          ctx.fill(_2d);
          if (classification == 2) {
            ctx.strokeStyle = '#d97706';
            ctx.lineWidth = 1.6;
            ctx.stroke(_2d);
          }
        }
        ctx.restore();
      } else if (passType === 'fg' && item.type === 'image') {
        const imgName = Dencan_GetImage(item.imageid, _typedesign, teethId);
        const imgSrc = VT_DATA.OverlayUris[imgName];
        const img = ImageCache.get(imgSrc);
        if (!img || !img.complete) continue;

        ctx.save();
        if (_typedesign === 'circle') {
          switch (op.part) {
            case 'l': ctx.drawImage(img, 0, 13, 14, 14); break;
            case 't': ctx.drawImage(img, 13, 0, 14, 14); break;
            case 'r': ctx.drawImage(img, 23, 13, 14, 14); break;
            case 'b': ctx.drawImage(img, 13, 23, 14, 14); break;
            case 'c': ctx.drawImage(img, 13, 13, 14, 14); break;
          }
        } else {
          const invert = toothObj ? Dencan_GetImageInver(toothObj.Index) : false;
          const skipFlip = [
            'implant_U', 'implant_D', 'x', 'v', 'gach-tren', 'gach-duoi',
            'mon-co-rang-duoi', 'mon-co-rang-tren', 'chot-cuatren-tai-pk',
            'chot-duoi-tai-phong-kham', 'chot-tren-phong-kham-khac', 'chot-duoi-phong-kham-khac',
            'lunglay-cuatren', 'lunglay-cuaduoi', 'caovoi-cuatren', 'caovoi-cuoiduoi',
            'viemtuy-cuatren', 'viemtuy-cuaduoi', 'rangsu-cuatren', 'rangsu-cuaduoi',
            'chenchuc-cuatren', 'chenchuc-cuaduoi', 'moncorang-cuatren', 'moncorang-cuoiduoi',
            'rang-da-nho-tren', 'rang-da-nho-duoi', 'rang-ngam-tren', 'rang-ngam-duoi',
            'thua_1_U', 'thua_3_U', 'thua_1_D', 'thua_3_D', 'du_1_U', 'du_3_U', 'du_1_D', 'du_3_D',
            'dichuyen-phai', 'dichuyen-trai', 'nghien-phai', 'nghien-trai',
            'nghien-ra-ngoai', 'nghien-trong', 'rol_left_U', 'rol_left_D', 'rol_right_U', 'rol_right_D',
            'braces_U', 'braces_D', 'prepared_U', 'prepared_D', 'tempcrown_U', 'tempcrown_D',
            'inlay_U', 'inlay_D', 'socket_U', 'socket_D', 'sealant_U', 'sealant_D',
            'tempfill_U', 'tempfill_D', 'denture_U', 'denture_D', 'extrusion_U', 'extrusion_D',
            'intrusion_U', 'intrusion_D', 'crownleak_U', 'crownleak_D', 'medendo_U', 'medendo_D',
            'apico_U', 'apico_D', 'resorp_U', 'resorp_D', 'discolor_U', 'discolor_D'
          ].includes(imgName);
          if (invert && !skipFlip) {
            ctx.translate(TeethWidth, 0);
            ctx.scale(-1, 1);
          }
          ctx.drawImage(img, 0, 0, TeethWidth, TeethHeight);
        }
        ctx.restore();
        if (classification == 2) {
          ctx.save();
          ctx.setLineDash([3, 2]);
          ctx.strokeStyle = '#d97706';
          ctx.lineWidth = 1.6;
          ctx.stroke(_2d);
          ctx.restore();
        }
      } else if (passType === 'fg' && item.type === 'text') {
        const textEl = document.getElementById('teethfacetext_' + teethId);
        if (textEl) {
          textEl.textContent = item.content;
          textEl.style.color = item.stroke || '#ffffff';
          textEl.style.backgroundColor = item.fill || '#0c82e8';
          textEl.style.border = classification == 2 ? '1.5px dashed #d97706' : 'none';
        }
      }
    }
  }

  function Dencan_Draw1Teeth(teethId) {
    resetToothBase(teethId);
    const textEl = document.getElementById('teethfacetext_' + teethId);
    if (textEl) textEl.style.border = 'none';
    const records = getActiveDiseaseArray().filter(d => String(d.Teethid) === String(teethId));
    const allOps = [];
    for (const rec of records) {
      const dis = VT_DATA?.DiseaseList?.[String(rec.Diseaseid)];
      if (!dis) continue;
      const ops = collectDrawOpsForRecord(teethId, rec.Part || 'root', dis.Data.data, dis.Type);
      for (const op of ops) {
        allOps.push({ op, cls: rec.Classification });
      }
    }
    for (const entry of allOps) executeDrawOp(teethId, entry.op, 'bg', entry.cls);
    for (const entry of allOps) executeDrawOp(teethId, entry.op, 'fg', entry.cls);

    if (!ReadOnlyMode) {
      for (const val of Object.values(TeethSelected)) {
        if (String(val.Teeth) !== String(teethId)) continue;
        const isCircle = ['l', 't', 'r', 'b', 'c'].includes(val.Part);
        const objid = (isCircle ? 'teethcircle_' : 'teethface_') + teethId + '_' + val.Part;
        const canvasid = (isCircle ? 'teethcircle_' : 'teethface_') + teethId;
        const canvas = document.getElementById(canvasid);
        if (canvas && Teethpaths[objid]) {
          Can_DrawSelected(canvas.getContext('2d'), Teethpaths[objid].path);
        }
      }
    }
    updateToothSelectionUI(teethId);
    renderBridgeConnectors();
  }

  function updateToothSelectionUI(teethId) {
    const el = document.querySelector(`.df-odon-scope .teethElement[data-tooth-id="${teethId}"]`);
    if (!el) return;
    const selParts = ReadOnlyMode ? [] : Object.values(TeethSelected).filter(v => String(v.Teeth) === String(teethId));
    const hasSel = selParts.length > 0;
    const hasCircleSel = selParts.some(v => ['l', 't', 'r', 'b', 'c'].includes(v.Part));
    const recs = getActiveDiseaseArray().filter(d => String(d.Teethid) === String(teethId));
    el.classList.toggle('tooth-has-selected', hasSel);
    el.classList.toggle('tooth-sel-circle', hasCircleSel);
    el.classList.toggle('tooth-has-disease', recs.length > 0);
    el.classList.toggle('tooth-has-plan', recs.some(r => Number(r.Classification) === 2));
    el.classList.toggle('tooth-has-problem', recs.some(r => PROBLEM_IDS.has(String(r.Diseaseid))));
  }

  function updateModeUI() {
    const upDown = document.getElementById('Canteeth_UpDown');
    const isPlan = DefaultClassification === 2;
    if (upDown) upDown.classList.toggle('mode-plan', isPlan);
    filterSidebarItems();

    const tabS = document.getElementById('infoTabStatus');
    const tabP = document.getElementById('infoTabPlan');
    if (tabS && tabP) {
      tabS.classList.toggle('active-status', !isPlan);
      tabS.setAttribute('aria-selected', String(!isPlan));
      tabP.classList.toggle('active-plan', isPlan);
      tabP.setAttribute('aria-selected', String(isPlan));
    }
    Dendis_RenderDisease();
  }

  function refreshSelectionChip() {
    updateEraseUI();
  }

  function renderBridgeConnectors() {
    document.querySelectorAll('.df-odon-scope .bridge-overlay-svg').forEach(el => el.remove());
    const allRecords = getActiveDiseaseArray();
    const bridgeRecords = allRecords.filter(d => String(d.Diseaseid) === '63');
    const braceRecords = allRecords.filter(d => String(d.Diseaseid) === '105');
    if (bridgeRecords.length < 2 && braceRecords.length < 2) return;

    const bridgeIds = new Set(bridgeRecords.map(r => String(r.Teethid)));
    const braceIds = new Set(braceRecords.map(r => String(r.Teethid)));

    ['U', 'D'].forEach(arch => {
      const archContainer = document.getElementById(arch === 'U' ? 'Canteeth_Up' : 'Canteeth_Down');
      if (!archContainer) return;
      const els = Array.from(archContainer.querySelectorAll('.teethElement'));
      const archRect = archContainer.getBoundingClientRect();
      if (!archRect.width) return;

      const svgNS = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(svgNS, 'svg');
      svg.setAttribute('class', 'bridge-overlay-svg');
      svg.setAttribute('viewBox', `0 0 ${archRect.width} ${archRect.height}`);
      let hasDrawing = false;

      if (bridgeRecords.length >= 2) {
        const segments = [];
        let run = [];
        let gap = 0;
        els.forEach((el) => {
          if (bridgeIds.has(el.dataset.toothId)) {
            run.push(el);
            gap = 0;
          } else if (run.length > 0 && gap < 2) {
            gap++;
          } else {
            if (run.length >= 2) segments.push(run);
            run = [];
            gap = 0;
          }
        });
        if (run.length >= 2) segments.push(run);

        segments.forEach(group => {
          const firstFace = group[0].querySelector('.canvasTeethFace').getBoundingClientRect();
          const lastFace = group[group.length - 1].querySelector('.canvasTeethFace').getBoundingClientRect();
          const x1 = firstFace.left - archRect.left + 4;
          const x2 = lastFace.right - archRect.left - 4;
          const y = arch === 'U'
            ? (firstFace.top - archRect.top + 64)
            : (firstFace.top - archRect.top + 16);

          const isPlannedBridge = group.some(el =>
            bridgeRecords.some(r => String(r.Teethid) === el.dataset.toothId && Number(r.Classification) === 2)
          );
          const rect = document.createElementNS(svgNS, 'rect');
          rect.setAttribute('x', x1);
          rect.setAttribute('y', y);
          rect.setAttribute('width', Math.max(10, x2 - x1));
          rect.setAttribute('height', '10');
          rect.setAttribute('rx', '4');
          rect.setAttribute('fill', isPlannedBridge ? 'rgba(245, 158, 11, 0.16)' : 'rgba(0, 80, 232, 0.16)');
          rect.setAttribute('stroke', isPlannedBridge ? '#d97706' : '#0050E8');
          rect.setAttribute('stroke-width', '1.8');
          if (isPlannedBridge) rect.setAttribute('stroke-dasharray', '5 3');
          svg.appendChild(rect);
          hasDrawing = true;
        });
      }

      if (braceRecords.length >= 2) {
        const bracedEls = els.filter(el => braceIds.has(el.dataset.toothId));
        if (bracedEls.length >= 2) {
          const firstFace = bracedEls[0].querySelector('.canvasTeethFace').getBoundingClientRect();
          const lastFace = bracedEls[bracedEls.length - 1].querySelector('.canvasTeethFace').getBoundingClientRect();
          const x1 = firstFace.left - archRect.left + 12;
          const x2 = lastFace.right - archRect.left - 12;
          const y = arch === 'U'
            ? (firstFace.top - archRect.top + 68)
            : (firstFace.top - archRect.top + 22);
          const isPlannedBrace = bracedEls.some(el =>
            braceRecords.some(r => String(r.Teethid) === el.dataset.toothId && Number(r.Classification) === 2)
          );
          const line = document.createElementNS(svgNS, 'line');
          line.setAttribute('x1', x1);
          line.setAttribute('y1', y);
          line.setAttribute('x2', x2);
          line.setAttribute('y2', y);
          line.setAttribute('stroke', isPlannedBrace ? '#d97706' : '#0284c7');
          line.setAttribute('stroke-width', '2.2');
          if (isPlannedBrace) line.setAttribute('stroke-dasharray', '5 3');
          svg.appendChild(line);
          hasDrawing = true;
        }
      }

      if (hasDrawing) archContainer.appendChild(svg);
    });
  }

  function Dendis_RenderPathName(part, teethId) {
    if (!part) return '';
    if (part === 'root') return 'Chân răng';
    if (part === 'face') return 'Thân răng';
    if (part === 'c') return 'Mặt nhai';

    const toothObj = TeethCollkv[String(teethId)];
    const q = toothObj && toothObj.TeethName ? toothObj.TeethName[0] : '1';
    const isUpper = (q === '1' || q === '2' || q === '5' || q === '6');
    const isRight = (q === '1' || q === '4' || q === '5' || q === '8');

    if (part === 't') return isUpper ? 'Mặt ngoài' : 'Mặt trong';
    if (part === 'b') return isUpper ? 'Mặt trong' : 'Mặt ngoài';
    if (part === 'l') return isRight ? 'Mặt xa' : 'Mặt gần';
    if (part === 'r') return isRight ? 'Mặt gần' : 'Mặt xa';
    return '';
  }

  function Dendis_RenderDisease() {
    const allList = getActiveDiseaseArray();
    const isPlanView = DefaultClassification === 2;
    const statusEntries = [];
    const planEntries = [];
    allList.forEach((it, idx) => {
      if (Number(it.Classification) === 2) planEntries.push({ it, idx });
      else statusEntries.push({ it, idx });
    });

    const cntS = document.getElementById('infoTabStatusCount');
    const cntP = document.getElementById('infoTabPlanCount');
    if (cntS) cntS.textContent = String(statusEntries.length);
    if (cntP) cntP.textContent = String(planEntries.length);

    const activeEntries = isPlanView ? planEntries : statusEntries;
    const headlineEl = document.getElementById('raoInfoHeadline');
    const colTitleEl = document.getElementById('raoJawColTitle');
    const upperEl = document.getElementById('raoUpperJawList');
    const lowerEl = document.getElementById('raoLowerJawList');
    const summaryEl = document.getElementById('raoSummaryList');
    if (!headlineEl || !upperEl || !lowerEl || !summaryEl) return;

    const byTooth = new Map();
    for (const { it } of activeEntries) {
      const tid = String(it.Teethid);
      if (!byTooth.has(tid)) byTooth.set(tid, []);
      byTooth.get(tid).push(it);
    }

    const missingToothIds = new Set();
    TeethCollection.forEach(t => {
      const recs = allList.filter(r => String(r.Teethid) === String(t.ID) && Number(r.Classification) !== 2);
      if (recs.some(r => MISSING_IDS.has(String(r.Diseaseid)))) {
        missingToothIds.add(String(t.ID));
      }
    });

    const totalTeeth = TeethCollection.length;
    const missingCount = missingToothIds.size;
    const presentCount = totalTeeth - missingCount;
    const dentitionLabel = TeethChart_Type === 1 ? 'răng vĩnh viễn' : 'răng sữa';

    if (isPlanView) {
      const plannedTeethCount = byTooth.size;
      const inlineCompleteBtn = (!ReadOnlyMode && planEntries.length > 0)
        ? ' <button type="button" class="btn btn-sm btn-outline-success py-0 px-2 ms-2" id="btnCompleteAllInline" style="font-size:0.72rem;"><i class="bi bi-check2 me-1"></i>Chuyển tất cả sang Hiện trạng</button>'
        : '';
      headlineEl.innerHTML = `<span style="color:#b45309;">Kế hoạch điều trị:</span> Đang có <strong>${planEntries.length}</strong> thủ thuật dự kiến trên <strong>${plannedTeethCount}</strong> răng.${inlineCompleteBtn}`;
      document.getElementById('btnCompleteAllInline')?.addEventListener('click', () => {
        if (ReadOnlyMode) return;
        const arr = getActiveDiseaseArray();
        if (!arr.some(d => Number(d.Classification) === 2)) return;
        pushUndoState();
        arr.forEach(d => { if (Number(d.Classification) === 2) d.Classification = 1; });
        TeethCollection.forEach(t => Dencan_Draw1Teeth(t.ID));
        Dendis_RenderDisease();
      });
    } else {
      headlineEl.textContent = `Hiện trạng: Sơ đồ hiển thị ${presentCount} ${dentitionLabel} hiện có và ${missingCount} răng mất / đã nhổ.`;
    }

    if (colTitleEl) {
      colTitleEl.textContent = isPlanView
        ? (TeethChart_Type === 1 ? 'Răng có kế hoạch điều trị (FDI — Người lớn)' : 'Răng có kế hoạch điều trị (FDI — Trẻ em)')
        : (TeethChart_Type === 1 ? 'Răng vĩnh viễn (FDI)' : 'Răng sữa (FDI)');
    }

    const renderJawRow = (arch) => {
      const teeth = TeethCollection.filter(t => t.UpDown === arch);
      return teeth.map(t => {
        const tid = String(t.ID);
        const recs = byTooth.get(tid) || [];
        let cls = 'rao-tooth-num';
        if (!isPlanView && missingToothIds.has(tid)) cls += ' is-missing';
        else if (isPlanView && recs.length > 0) cls += ' has-treatment';
        else if (!isPlanView && recs.some(r => PROBLEM_IDS.has(String(r.Diseaseid)))) cls += ' has-problem';
        else if (recs.length > 0) cls += ' has-treatment';
        const styleAttr = (isPlanView && recs.length > 0) ? ' style="color:#d97706;border-bottom:1.5px dashed #d97706;"' : '';
        return `<span class="${cls}"${styleAttr} data-info-tooth="${tid}" title="Click để chọn răng ${t.TeethName} trên sơ đồ">${t.TeethName}</span>`;
      }).join(', ');
    };

    upperEl.innerHTML = renderJawRow('U');
    lowerEl.innerHTML = renderJawRow('D');

    summaryEl.querySelectorAll('[data-info-tooth]').forEach(s => s.remove());
    document.querySelectorAll('.df-odon-scope [data-info-tooth]').forEach(span => {
      span.addEventListener('click', () => {
        if (ReadOnlyMode) return;
        const tid = span.dataset.infoTooth;
        const hasFace = !!TeethSelected[`face-${tid}`];
        const hasRoot = !!TeethSelected[`root-${tid}`];
        if (hasFace || hasRoot) {
          delete TeethSelected[`face-${tid}`];
          delete TeethSelected[`root-${tid}`];
        } else {
          TeethSelected[`face-${tid}`] = { Teeth: String(tid), Part: 'face' };
          TeethSelected[`root-${tid}`] = { Teeth: String(tid), Part: 'root' };
        }
        Dencan_Draw1Teeth(tid);
        refreshSelectionChip();
      });
    });

    summaryEl.innerHTML = SUMMARY_CATEGORIES.map(cat => {
      const matched = activeEntries.filter(({ it }) => cat.ids.has(String(it.Diseaseid)));
      if (!matched.length) {
        return `<div class="rao-summary-row"><span class="cat-lbl">${cat.label}:</span><span class="cat-empty">${cat.empty}</span></div>`;
      }
      const pills = matched.map(({ it, idx }) => {
        const toothObj = TeethCollkv[String(it.Teethid)] || { TeethName: it.Teethid };
        const dis = VT_DATA?.DiseaseList?.[String(it.Diseaseid)];
        const dName = dis ? dis.Name : it.Diseaseid;
        const pName = Dendis_RenderPathName(it.Part, it.Teethid);
        const isProb = !isPlanView && PROBLEM_IDS.has(String(it.Diseaseid));
        const numCls = isPlanView ? 'fw-bold' : (isProb ? 'text-danger fw-bold fst-italic' : 'text-primary fw-bold');
        const numStyle = isPlanView ? ' style="color:#b45309;"' : '';
        const actionBtns = ReadOnlyMode ? '' : (
          (isPlanView
            ? `<button type="button" class="rao-swap-btn to-status" data-swap-idx="${idx}" title="Hoàn thành: chuyển sang Hiện trạng"><i class="bi bi-check-circle-fill"></i></button>`
            : `<button type="button" class="rao-swap-btn to-plan" data-swap-idx="${idx}" title="Chuyển sang Kế hoạch"><i class="bi bi-arrow-left-right"></i></button>`) +
          `<button type="button" class="rao-swap-btn del-item" data-del-idx="${idx}" title="Xóa mục này"><i class="bi bi-x-lg"></i></button>`
        );
        return `<span class="rao-item-pill ${isPlanView ? 'is-plan' : ''}"><span class="${numCls}"${numStyle}>${toothObj.TeethName}</span><span>${dName}${pName && it.Part !== 'root' && it.Part !== 'face' ? ' - ' + pName : ''}</span>${actionBtns}</span>`;
      }).join('');
      return `<div class="rao-summary-row"><span class="cat-lbl">${cat.label}:</span>${pills}</div>`;
    }).join('');

    summaryEl.querySelectorAll('[data-swap-idx]').forEach(btn => {
      btn.addEventListener('click', () => {
        if (ReadOnlyMode) return;
        const i = Number(btn.dataset.swapIdx);
        const arr = getActiveDiseaseArray();
        if (!arr[i]) return;
        pushUndoState();
        arr[i].Classification = Number(arr[i].Classification) === 2 ? 1 : 2;
        Dencan_Draw1Teeth(arr[i].Teethid);
        Dendis_RenderDisease();
      });
    });

    summaryEl.querySelectorAll('[data-del-idx]').forEach(btn => {
      btn.addEventListener('click', () => {
        if (ReadOnlyMode) return;
        const i = Number(btn.dataset.delIdx);
        const arr = getActiveDiseaseArray();
        if (!arr[i]) return;
        pushUndoState();
        const rem = arr.splice(i, 1)[0];
        Dencan_Draw1Teeth(rem.Teethid);
        Dendis_RenderDisease();
        refreshSelectionChip();
      });
    });
  }

  function Dentist_AddDisease(diseaseid) {
    if (ReadOnlyMode || !VT_DATA) return;
    const dis = VT_DATA.DiseaseList[String(diseaseid)];
    if (!dis) return;
    const selectedEntries = Object.entries(TeethSelected);
    if (PaintMode || !selectedEntries.length) {
      setPaintDisease(diseaseid);
      return;
    }

    pushUndoState();
    const arr = getActiveDiseaseArray();
    const updatedTeeth = new Set();
    const processedKey = new Set();

    for (const [selKey, selVal] of selectedEntries) {
      let targetPart = selVal.Part;
      if (dis.Type == 1 && !['face', 'root'].includes(targetPart)) {
        targetPart = 'root';
      } else if (dis.Type == 2 && ['face', 'root'].includes(targetPart)) {
        targetPart = 'c';
      }

      const dedupeKey = `${selVal.Teeth}-${targetPart}-${diseaseid}`;
      if (!processedKey.has(dedupeKey)) {
        processedKey.add(dedupeKey);
        const exists = arr.some(
          d => String(d.Teethid) === String(selVal.Teeth)
            && String(d.Diseaseid) === String(diseaseid)
            && (dis.Type == 1 ? true : d.Part === targetPart)
        );
        if (!exists) {
          arr.push({
            Diseaseid: String(diseaseid),
            Teethid: String(selVal.Teeth),
            Type: dis.Type,
            Part: targetPart,
            Treat: 'treatment for teeth',
            Note: '',
            Classification: DefaultClassification
          });
        }
      }
      updatedTeeth.add(selVal.Teeth);
      delete TeethSelected[selKey];
    }

    Dendis_RenderDisease();
    refreshSelectionChip();
    updatedTeeth.forEach(tid => Dencan_Draw1Teeth(tid));
    updateModeUI();
  }

  function buildSidebar() {
    if (!VT_DATA) return;
    const largeContainer = document.getElementById('Candisease');
    const shortContainer = document.getElementById('Candisease_Short');
    if (!largeContainer || !shortContainer) return;

    const cats = [
      { id: '1', title: 'Răng', icon: VT_DATA.CatIcons['1'], collapseId: 'descoslap1', expanded: true },
      { id: '2', title: 'Mặt nhai', icon: VT_DATA.CatIcons['2'], collapseId: 'descoslap2', expanded: false }
    ];

    largeContainer.innerHTML = cats.map(cat => {
      const ids = VT_DATA.CatOrder[cat.id] || [];
      const itemsHtml = ids.map(did => {
        const d = VT_DATA.DiseaseList[String(did)];
        if (!d) return '';
        return `
          <li id="desease_${d.ID}" data_type="${d.Type}" data_id="${d.ID}" tabindex="0" role="button"
              aria-label="${d.Name} - ${d.DescriptionTip}"
              class="diseaseitem mb-0 pb-0 cursor-pointer list-group-item border-0 justify-content-between ps-2 border-radius-lg">
            <div class="d-flex align-items-center py-1">
              <div class="me-3 text-center flex-shrink-0">
                <img src="${d.IconUri}" style="height:30px;width:30px;object-fit:contain;" alt="${d.Name}">
              </div>
              <div class="d-flex flex-column">
                <h6 class="mb-0 fw-bold text-dark text-sm">${d.Name}${d.IsNew ? '<span class="badge-new-cond">Mới</span>' : ''}</h6>
                <span class="text-sm text-secondary">${d.DescriptionTip}</span>
              </div>
            </div>
            <hr class="horizontal dark mt-2 mb-1">
          </li>
        `;
      }).join('');

      return `
        <div class="accordion" id="Listdes${cat.id}">
          <div class="accordion-item mb-1">
            <h6 class="accordion-header" id="Listhes${cat.id}">
              <button class="accordion-button text-sm fw-normal text-primary p-1 py-2 border-bottom ${cat.expanded ? '' : 'collapsed'}"
                      type="button" data-bs-toggle="collapse" data-bs-target="#${cat.collapseId}"
                      aria-expanded="${cat.expanded}" aria-controls="${cat.collapseId}">
                <img class="border-radius-lg border-1 bg-gray-300 avatar avatar-sm p-2 me-2" src="${cat.icon}" alt="${cat.title}">
                ${cat.title}
              </button>
            </h6>
            <div id="${cat.collapseId}" class="accordion-collapse collapse ${cat.expanded ? 'show' : ''}" aria-labelledby="Listhes${cat.id}">
              <ul class="pt-2 ps-0 mb-0">${itemsHtml}</ul>
            </div>
          </div>
        </div>
      `;
    }).join('');

    const allOrderedIds = [...(VT_DATA.CatOrder['1'] || []), ...(VT_DATA.CatOrder['2'] || [])];
    shortContainer.innerHTML = allOrderedIds.map(did => {
      const d = VT_DATA.DiseaseList[String(did)];
      if (!d) return '';
      return `
        <a data_type="${d.Type}" data_id="${d.ID}" title="${d.Name} (${d.DescriptionTip})" tabindex="0" role="button"
           class="col-auto diseaseitem shortitem m-1 p-1 cursor-pointer border rounded-2 bg-white">
          <img style="height:28px;width:28px;object-fit:contain;" src="${d.IconUri}" alt="${d.Name}">
        </a>
      `;
    }).join('');

    document.querySelectorAll('.df-odon-scope .diseaseitem').forEach(el => {
      el.addEventListener('click', () => Dentist_AddDisease(el.getAttribute('data_id')));
      el.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          Dentist_AddDisease(el.getAttribute('data_id'));
        }
      });
    });
  }

  function showToothTooltip(evt, tooth, hoverPart) {
    const tip = document.getElementById('toothTooltip');
    if (!tip || !tooth || !VT_DATA) return;
    const recs = getActiveDiseaseArray().filter(d => String(d.Teethid) === String(tooth.ID));
    const surfaceText = hoverPart ? Dendis_RenderPathName(hoverPart, tooth.ID) : '';
    const condNames = Array.from(new Set(recs.map(r => {
      const dis = VT_DATA.DiseaseList[String(r.Diseaseid)];
      return dis ? dis.Name : r.Diseaseid;
    })));
    const headSummary = condNames.length ? `Răng ${tooth.TeethName} — ${condNames.join(', ')}` : `Răng ${tooth.TeethName}`;

    let bodyHtml = '';
    if (recs.length) {
      bodyHtml = recs.map(r => {
        const dis = VT_DATA.DiseaseList[String(r.Diseaseid)];
        const name = dis ? dis.Name : r.Diseaseid;
        const isPlan = Number(r.Classification) === 2;
        const badge = isPlan
          ? '<span class="tt-badge-plan">Kế hoạch</span>'
          : '<span class="tt-badge-solid">Hiện trạng</span>';
        const partLbl = Dendis_RenderPathName(r.Part, tooth.ID);
        return `<div class="tt-item">${badge}<span>${name}${partLbl ? ' (' + partLbl + ')' : ''}</span></div>`;
      }).join('') + (ReadOnlyMode ? '' : '<div class="tt-hint">Chuột phải để xóa từng tình trạng</div>');
    } else {
      bodyHtml = '<div style="color:#94a3b8;">Chưa ghi nhận tình trạng</div>';
    }

    tip.innerHTML = `
      <div class="tt-head"><span>${headSummary}</span></div>
      ${surfaceText ? `<div class="tt-surface mb-1">Vùng đang trỏ: <strong>${surfaceText}</strong></div>` : ''}
      ${bodyHtml}
    `;
    tip.style.display = 'block';
    tip.setAttribute('aria-hidden', 'false');

    const pad = 14;
    let left = evt.clientX + pad;
    let top = evt.clientY + pad;
    const rect = tip.getBoundingClientRect();
    if (left + rect.width > window.innerWidth - 8) left = evt.clientX - rect.width - pad;
    if (top + rect.height > window.innerHeight - 8) top = evt.clientY - rect.height - pad;
    tip.style.left = Math.max(8, left) + 'px';
    tip.style.top = Math.max(8, top) + 'px';
  }

  function hideToothTooltip() {
    const tip = document.getElementById('toothTooltip');
    if (tip) {
      tip.style.display = 'none';
      tip.setAttribute('aria-hidden', 'true');
    }
  }

  function createToothElementHtml(tooth, isFirst) {
    const label = formatToothLabel(tooth);
    if (tooth.UpDown === 'U') {
      return `
        <div class="teethElement mx-2 text-center position-relative ${isFirst ? 'tooth-first' : ''}" data-tooth-id="${tooth.ID}">
          <div id="teethfacetext_${tooth.ID}" class="teeth_text d-flex justify-content-center align-items-center text-sm fw-bold rounded-2"></div>
          <div class="teeth_part_root_face">
            <canvas data-id="${tooth.ID}" data-name="${tooth.TeethName}" id="teethface_${tooth.ID}" width="80" height="180"
                    class="canvasTeethFace" role="img" tabindex="0" aria-label="Răng ${label} thân và chân răng"></canvas>
          </div>
          <div class="teeth_nameTeeth text-primary fw-bold text-sm cursor-pointer" data-tooth-select="${tooth.ID}" title="Click chọn cả răng ${label} (Shift để chọn dải)">${label}</div>
          <div style="height:10px;"></div>
          <div class="teeth_part_circle">
            <canvas data-id="${tooth.ID}" id="teethcircle_${tooth.ID}" width="80" height="80"
                    class="canvasTeethCircle" role="img" tabindex="0" aria-label="Răng ${label} mặt nhai"></canvas>
          </div>
          <div class="gum"></div>
        </div>
      `;
    }
    return `
      <div class="teethElement mx-2 text-center position-relative ${isFirst ? 'tooth-first' : ''}" data-tooth-id="${tooth.ID}">
        <div class="gum"></div>
        <div class="teeth_part_circle">
          <canvas data-id="${tooth.ID}" id="teethcircle_${tooth.ID}" width="80" height="80"
                  class="canvasTeethCircle" role="img" tabindex="0" aria-label="Răng ${label} mặt nhai"></canvas>
        </div>
        <div style="height:10px;"></div>
        <div class="teeth_nameTeeth text-primary fw-bold text-sm cursor-pointer" data-tooth-select="${tooth.ID}" title="Click chọn cả răng ${label} (Shift để chọn dải)">${label}</div>
        <div class="teeth_part_root_face">
          <canvas data-id="${tooth.ID}" data-name="${tooth.TeethName}" id="teethface_${tooth.ID}" width="80" height="180"
                  class="canvasTeethFace" role="img" tabindex="0" aria-label="Răng ${label} thân và chân răng"></canvas>
        </div>
        <div id="teethfacetext_${tooth.ID}" class="teeth_text d-flex justify-content-center align-items-center text-sm fw-bold rounded-2"></div>
      </div>
    `;
  }

  function togglePartSelection(part, toothId) {
    if (ReadOnlyMode || SuppressNextChartClick) return;
    if (EraseMode) {
      clearToothDiseases(toothId);
      return;
    }
    if (PaintMode && ActivePaintDiseaseId) {
      applyDiseaseToSingleTarget(toothId, part, ActivePaintDiseaseId);
      return;
    }
    const key = `${part}-${toothId}`;
    if (TeethSelected[key]) delete TeethSelected[key];
    else TeethSelected[key] = { Teeth: String(toothId), Part: part };
    Dencan_Draw1Teeth(toothId);
    refreshSelectionChip();
  }

  function buildToothChart() {
    if (!VT_DATA) return;
    TeethCollection = TeethChart_Type === 1 ? VT_DATA.AdultCollection : VT_DATA.ChildCollection;
    TeethCollkv = {};
    Teethpaths = {};
    TeethSelected = {};

    const upTeeth = TeethCollection.filter(t => t.UpDown === 'U');
    const downTeeth = TeethCollection.filter(t => t.UpDown === 'D');

    const upDownBox = document.getElementById('Canteeth_UpDown');
    const upEl = document.getElementById('Canteeth_Up');
    const downEl = document.getElementById('Canteeth_Down');
    if (!upDownBox || !upEl || !downEl) return;

    upDownBox.style.width = TeethChart_Type === 1 ? '848px' : '540px';
    upEl.innerHTML = upTeeth.map((t, i) => createToothElementHtml(t, i === 0)).join('');
    downEl.innerHTML = downTeeth.map((t, i) => createToothElementHtml(t, i === 0)).join('');

    TeethCollection.forEach((tooth) => {
      TeethCollkv[String(tooth.ID)] = tooth;
      const rtype = DendisR_DetectType(tooth);
      const pathDefs = getToothPathsMap(tooth);

      ['root', 'face'].forEach(part => {
        if (pathDefs[part]) {
          Teethpaths[`teethface_${tooth.ID}_${part}`] = {
            path: new Path2D(pathDefs[part]),
            type: rtype
          };
        }
      });

      ['l', 't', 'r', 'b', 'c'].forEach(part => {
        if (VT_DATA.MatrangPaths[part]) {
          Teethpaths[`teethcircle_${tooth.ID}_${part}`] = {
            path: new Path2D(VT_DATA.MatrangPaths[part]),
            type: 'circle'
          };
        }
      });

      const faceCanvas = document.getElementById('teethface_' + tooth.ID);
      if (faceCanvas) {
        faceCanvas.addEventListener('click', (evt) => {
          if (ReadOnlyMode) return;
          const rect = faceCanvas.getBoundingClientRect();
          const x = (evt.clientX - rect.left) * (TeethWidth / rect.width);
          const y = (evt.clientY - rect.top) * (TeethHeight / rect.height);
          const ctx = faceCanvas.getContext('2d');
          for (const part of ['face', 'root']) {
            const entry = Teethpaths[`teethface_${tooth.ID}_${part}`];
            if (entry && ctx.isPointInPath(entry.path, x * CANVAS_SCALE, y * CANVAS_SCALE)) {
              togglePartSelection(part, tooth.ID);
              return;
            }
          }
          togglePartSelection(y > 45 && tooth.UpDown === 'U' ? 'face' : (y < 45 && tooth.UpDown === 'D' ? 'face' : 'root'), tooth.ID);
        });
        faceCanvas.addEventListener('mousemove', (evt) => {
          const rect = faceCanvas.getBoundingClientRect();
          const x = (evt.clientX - rect.left) * (TeethWidth / rect.width);
          const y = (evt.clientY - rect.top) * (TeethHeight / rect.height);
          const ctx = faceCanvas.getContext('2d');
          let hoverPart = '';
          for (const part of ['face', 'root']) {
            const entry = Teethpaths[`teethface_${tooth.ID}_${part}`];
            if (entry && ctx.isPointInPath(entry.path, x * CANVAS_SCALE, y * CANVAS_SCALE)) {
              hoverPart = part;
              break;
            }
          }
          showToothTooltip(evt, tooth, hoverPart);
        });
        faceCanvas.addEventListener('mouseleave', hideToothTooltip);
      }

      const circleCanvas = document.getElementById('teethcircle_' + tooth.ID);
      if (circleCanvas) {
        circleCanvas.addEventListener('click', (evt) => {
          if (ReadOnlyMode) return;
          const rect = circleCanvas.getBoundingClientRect();
          const x = (evt.clientX - rect.left) * (TeethCircle / rect.width);
          const y = (evt.clientY - rect.top) * (TeethCircle / rect.height);
          const ctx = circleCanvas.getContext('2d');
          for (const part of ['c', 't', 'r', 'b', 'l']) {
            const entry = Teethpaths[`teethcircle_${tooth.ID}_${part}`];
            if (entry && ctx.isPointInPath(entry.path, x * CANVAS_SCALE, y * CANVAS_SCALE)) {
              togglePartSelection(part, tooth.ID);
              return;
            }
          }
        });
        circleCanvas.addEventListener('mousemove', (evt) => {
          const rect = circleCanvas.getBoundingClientRect();
          const x = (evt.clientX - rect.left) * (TeethCircle / rect.width);
          const y = (evt.clientY - rect.top) * (TeethCircle / rect.height);
          const ctx = circleCanvas.getContext('2d');
          let hoverPart = '';
          for (const part of ['c', 't', 'r', 'b', 'l']) {
            const entry = Teethpaths[`teethcircle_${tooth.ID}_${part}`];
            if (entry && ctx.isPointInPath(entry.path, x * CANVAS_SCALE, y * CANVAS_SCALE)) {
              hoverPart = part;
              break;
            }
          }
          showToothTooltip(evt, tooth, hoverPart);
        });
        circleCanvas.addEventListener('mouseleave', hideToothTooltip);
      }

      const toothEl = document.querySelector(`.df-odon-scope .teethElement[data-tooth-id="${tooth.ID}"]`);
      if (toothEl) {
        toothEl.addEventListener('contextmenu', (evt) => {
          evt.preventDefault();
          showToothContextMenu(evt, tooth);
        });
        toothEl.addEventListener('mousemove', (evt) => {
          if (evt.target && (evt.target.classList.contains('canvasTeethFace') || evt.target.classList.contains('canvasTeethCircle'))) return;
          showToothTooltip(evt, tooth, '');
        });
        toothEl.addEventListener('mouseleave', hideToothTooltip);
      }

      const lbl = document.querySelector(`.df-odon-scope .teeth_nameTeeth[data-tooth-select="${tooth.ID}"]`);
      if (lbl) {
        lbl.addEventListener('click', (evt) => {
          if (ReadOnlyMode) return;
          if (EraseMode) {
            clearToothDiseases(tooth.ID);
            return;
          }
          if (PaintMode && ActivePaintDiseaseId) {
            applyDiseaseToSingleTarget(tooth.ID, 'root', ActivePaintDiseaseId);
            return;
          }
          if (evt.shiftKey && LastClickedToothIdx !== null) {
            const sameArch = TeethCollection.filter(t => t.UpDown === tooth.UpDown);
            const idxA = sameArch.findIndex(t => t.ID === LastClickedToothIdx);
            const idxB = sameArch.findIndex(t => t.ID === tooth.ID);
            if (idxA >= 0 && idxB >= 0) {
              const start = Math.min(idxA, idxB);
              const end = Math.max(idxA, idxB);
              for (let k = start; k <= end; k++) {
                const tid = sameArch[k].ID;
                TeethSelected[`face-${tid}`] = { Teeth: String(tid), Part: 'face' };
                TeethSelected[`root-${tid}`] = { Teeth: String(tid), Part: 'root' };
                Dencan_Draw1Teeth(tid);
              }
              refreshSelectionChip();
              return;
            }
          }
          LastClickedToothIdx = tooth.ID;
          const hasFace = !!TeethSelected[`face-${tooth.ID}`];
          const hasRoot = !!TeethSelected[`root-${tooth.ID}`];
          if (hasFace || hasRoot) {
            delete TeethSelected[`face-${tooth.ID}`];
            delete TeethSelected[`root-${tooth.ID}`];
          } else {
            TeethSelected[`face-${tooth.ID}`] = { Teeth: String(tooth.ID), Part: 'face' };
            TeethSelected[`root-${tooth.ID}`] = { Teeth: String(tooth.ID), Part: 'root' };
          }
          Dencan_Draw1Teeth(tooth.ID);
          refreshSelectionChip();
        });
      }

      Dencan_Draw1Teeth(tooth.ID);
    });

    Dendis_RenderDisease();
    refreshSelectionChip();
    updateModeUI();
  }

  function exportOdontogramPng() {
    const upTeeth = TeethCollection.filter(t => t.UpDown === 'U');
    const downTeeth = TeethCollection.filter(t => t.UpDown === 'D');
    const cols = Math.max(upTeeth.length, downTeeth.length);
    if (!cols) return;
    const cellW = 50;
    const padX = 28;
    const headerH = 52;
    const archH = 195;
    const gapY = 26;
    const scale = 2;
    const W = padX * 2 + cols * cellW;
    const H = headerH + archH * 2 + gapY + 24;

    const out = document.createElement('canvas');
    out.width = W * scale;
    out.height = H * scale;
    const ctx = out.getContext('2d');
    ctx.scale(scale, scale);

    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, W, H);

    ctx.fillStyle = '#1e293b';
    ctx.font = 'bold 15px Inter, Arial, sans-serif';
    const title = TeethChart_Type === 1 ? 'SƠ ĐỒ RĂNG FDI — NGƯỜI LỚN (32 RĂNG)' : 'SƠ ĐỒ RĂNG FDI — TRẺ EM (20 RĂNG)';
    ctx.fillText(title, padX, 24);
    ctx.fillStyle = '#64748b';
    ctx.font = '11px Inter, Arial, sans-serif';
    ctx.fillText(`Ngày xuất: ${new Date().toLocaleString('vi-VN')}  •  Xanh nét liền: Hiện trạng  •  Cam nét đứt: Kế hoạch`, padX, 41);

    const drawArch = (teethArr, startY, isUpper) => {
      teethArr.forEach((tooth, idx) => {
        const x = padX + idx * cellW + 5;
        const faceCanvas = document.getElementById('teethface_' + tooth.ID);
        const circleCanvas = document.getElementById('teethcircle_' + tooth.ID);
        const textEl = document.getElementById('teethfacetext_' + tooth.ID);
        const badgeTxt = textEl ? textEl.textContent.trim() : '';

        if (isUpper) {
          if (badgeTxt) {
            ctx.fillStyle = textEl.style.backgroundColor || '#0c82e8';
            ctx.fillRect(x + 4, startY + 2, 32, 18);
            ctx.fillStyle = textEl.style.color || '#ffffff';
            ctx.font = 'bold 11px Inter, Arial, sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText(badgeTxt, x + 20, startY + 15);
          }
          if (faceCanvas) ctx.drawImage(faceCanvas, x, startY + 24, 40, 90);
          ctx.fillStyle = '#0c82e8';
          ctx.font = 'bold 12px Inter, Arial, sans-serif';
          ctx.textAlign = 'center';
          ctx.fillText(tooth.TeethName, x + 20, startY + 130);
          if (circleCanvas) ctx.drawImage(circleCanvas, x, startY + 140, 40, 40);
        } else {
          if (circleCanvas) ctx.drawImage(circleCanvas, x, startY + 4, 40, 40);
          ctx.fillStyle = '#0c82e8';
          ctx.font = 'bold 12px Inter, Arial, sans-serif';
          ctx.textAlign = 'center';
          ctx.fillText(tooth.TeethName, x + 20, startY + 60);
          if (faceCanvas) ctx.drawImage(faceCanvas, x, startY + 68, 40, 90);
          if (badgeTxt) {
            ctx.fillStyle = textEl.style.backgroundColor || '#0c82e8';
            ctx.fillRect(x + 4, startY + 162, 32, 18);
            ctx.fillStyle = textEl.style.color || '#ffffff';
            ctx.font = 'bold 11px Inter, Arial, sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText(badgeTxt, x + 20, startY + 175);
          }
        }
      });
      ctx.textAlign = 'left';
    };

    drawArch(upTeeth, headerH, true);
    ctx.strokeStyle = '#e2e8f0';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(padX, headerH + archH + gapY / 2);
    ctx.lineTo(W - padX, headerH + archH + gapY / 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(W / 2, headerH + 20);
    ctx.lineTo(W / 2, headerH + archH * 2 + gapY - 20);
    ctx.stroke();
    drawArch(downTeeth, headerH + archH + gapY, false);

    const link = document.createElement('a');
    link.download = `so-do-rang-FDI-${new Date().toISOString().slice(0, 10)}.png`;
    link.href = out.toDataURL('image/png');
    link.click();
  }

  function buildExportPayload() {
    const list = getActiveDiseaseArray();
    return {
      chartType: TeethChart_Type === 1 ? 'ADULT' : 'CHILD',
      notation: 'FDI',
      updatedAt: new Date().toISOString(),
      note: document.getElementById('Canteeth_Note')?.value || '',
      teeth: list.map(item => {
        const toothObj = TeethCollkv[String(item.Teethid)] || { TeethName: String(item.Teethid) };
        const dis = VT_DATA?.DiseaseList?.[String(item.Diseaseid)];
        return {
          toothFdi: toothObj.TeethName,
          toothId: String(item.Teethid),
          part: item.Part || 'root',
          partLabel: Dendis_RenderPathName(item.Part, item.Teethid),
          conditionId: String(item.Diseaseid),
          conditionName: dis ? dis.Name : String(item.Diseaseid),
          mode: Number(item.Classification) === 2 ? 'PLAN' : 'EXISTING',
          modeLabel: Number(item.Classification) === 2 ? 'Kế hoạch' : 'Hiện trạng'
        };
      })
    };
  }

  function syncChartTypeButtons() {
    document.querySelectorAll('.df-odon-scope [data-chart-type]').forEach(b => {
      b.classList.toggle('active', Number(b.dataset.chartType) === TeethChart_Type);
    });
  }

  function syncClassificationMode(modeVal) {
    DefaultClassification = Number(modeVal);
    document.querySelectorAll('.df-odon-scope [data-default-class]').forEach(b => {
      b.classList.remove('active', 'active-plan');
      if (Number(b.dataset.defaultClass) === DefaultClassification) {
        b.classList.add(DefaultClassification === 2 ? 'active-plan' : 'active');
      }
    });
    updateModeUI();
  }

  function initEvents() {
    if (EventsBound) return;
    EventsBound = true;

    const searchInput = document.getElementById('Dissearch_Input');
    searchInput?.addEventListener('input', filterSidebarItems);
    document.getElementById('btnClearSearch')?.addEventListener('click', () => {
      if (!searchInput) return;
      searchInput.value = '';
      searchInput.dispatchEvent(new Event('input'));
      searchInput.focus();
    });

    document.querySelectorAll('.df-odon-scope [data-chart-type]').forEach(btn => {
      btn.addEventListener('click', () => {
        if (ReadOnlyMode) return;
        const nextType = Number(btn.dataset.chartType);
        if (nextType !== TeethChart_Type) Dirty = true;
        TeethChart_Type = nextType;
        syncChartTypeButtons();
        buildToothChart();
      });
    });

    document.querySelectorAll('.df-odon-scope [data-default-class]').forEach(btn => {
      btn.addEventListener('click', () => syncClassificationMode(btn.dataset.defaultClass));
    });
    document.querySelectorAll('.df-odon-scope [data-info-tab]').forEach(btn => {
      btn.addEventListener('click', () => syncClassificationMode(btn.dataset.infoTab));
    });

    document.querySelectorAll('.df-odon-scope [data-quad]').forEach(btn => {
      btn.addEventListener('click', () => {
        if (ReadOnlyMode) return;
        const qNum = btn.dataset.quad;
        const validPrefixes = TeethChart_Type === 1 ? [qNum] : [String(Number(qNum) + 4)];
        TeethCollection.filter(t => validPrefixes.includes(t.TeethName[0])).forEach(t => {
          TeethSelected[`face-${t.ID}`] = { Teeth: String(t.ID), Part: 'face' };
          TeethSelected[`root-${t.ID}`] = { Teeth: String(t.ID), Part: 'root' };
          Dencan_Draw1Teeth(t.ID);
        });
        refreshSelectionChip();
      });
    });

    const teethArea = document.getElementById('Canteeth_TeethArea');
    const dragBox = document.getElementById('dragSelectBox');
    if (teethArea && dragBox) {
      let isMouseDown = false;
      let isDragging = false;
      let startX = 0;
      let startY = 0;
      let hideTimer = null;
      let draggedToothIds = new Set();

      teethArea.addEventListener('mousedown', (evt) => {
        if (ReadOnlyMode || evt.button !== 0) return;
        isMouseDown = true;
        isDragging = false;
        startX = evt.clientX;
        startY = evt.clientY;
        draggedToothIds = new Set();
        if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
      });

      document.addEventListener('mousemove', (evt) => {
        if (!isMouseDown || ReadOnlyMode) return;
        const dx = evt.clientX - startX;
        const dy = evt.clientY - startY;
        if (!isDragging && Math.hypot(dx, dy) > 6) {
          isDragging = true;
          hideToothTooltip();
          dragBox.style.display = 'block';
          dragBox.style.opacity = '1';
        }
        if (!isDragging) return;

        evt.preventDefault();
        const left = Math.min(startX, evt.clientX);
        const top = Math.min(startY, evt.clientY);
        const width = Math.abs(dx);
        const height = Math.abs(dy);
        dragBox.style.left = left + 'px';
        dragBox.style.top = top + 'px';
        dragBox.style.width = width + 'px';
        dragBox.style.height = height + 'px';

        const boxRect = { left, top, right: left + width, bottom: top + height };
        document.querySelectorAll('.df-odon-scope #Canteeth_UpDown .teethElement').forEach(el => {
          const r = el.getBoundingClientRect();
          const hit = !(r.right < boxRect.left || r.left > boxRect.right || r.bottom < boxRect.top || r.top > boxRect.bottom);
          const tid = el.dataset.toothId;
          if (hit && !draggedToothIds.has(tid)) {
            draggedToothIds.add(tid);
            if (EraseMode) clearToothDiseases(tid);
            else if (PaintMode && ActivePaintDiseaseId) applyDiseaseToSingleTarget(tid, 'root', ActivePaintDiseaseId);
            else {
              TeethSelected[`face-${tid}`] = { Teeth: String(tid), Part: 'face' };
              TeethSelected[`root-${tid}`] = { Teeth: String(tid), Part: 'root' };
              Dencan_Draw1Teeth(tid);
              refreshSelectionChip();
            }
          }
        });
      });

      document.addEventListener('mouseup', () => {
        if (!isMouseDown) return;
        isMouseDown = false;
        if (isDragging) {
          isDragging = false;
          SuppressNextChartClick = true;
          setTimeout(() => { SuppressNextChartClick = false; }, 40);
          dragBox.style.opacity = '0';
          hideTimer = setTimeout(() => {
            dragBox.style.display = 'none';
            dragBox.style.width = '0px';
            dragBox.style.height = '0px';
          }, 115);
        }
      });
    }

    document.getElementById('btnCornerClear')?.addEventListener('click', () => {
      if (ReadOnlyMode) return;
      const selIds = Array.from(new Set(Object.values(TeethSelected).map(v => v.Teeth)));
      if (selIds.length > 0 && !EraseMode) {
        TeethSelected = {};
        selIds.forEach(id => Dencan_Draw1Teeth(id));
        refreshSelectionChip();
      } else {
        toggleEraseMode();
      }
    });

    document.getElementById('btnUndo')?.addEventListener('click', performUndo);
    document.getElementById('btnRedo')?.addEventListener('click', performRedo);
    document.getElementById('btnExportPng')?.addEventListener('click', exportOdontogramPng);

    document.addEventListener('click', (e) => {
      const menu = document.getElementById('toothContextMenu');
      if (menu && menu.style.display === 'block' && !menu.contains(e.target)) {
        hideToothContextMenu();
      }
    });
    window.addEventListener('scroll', hideToothContextMenu, true);

    document.addEventListener('keydown', (e) => {
      if (!isEditorVisible()) return;
      const tag = (e.target && e.target.tagName) ? e.target.tagName.toLowerCase() : '';
      if (tag === 'input' || tag === 'textarea' || tag === 'select') return;
      if (e.key === 'Escape') {
        hideToothContextMenu();
        if (PaintMode) exitPaintMode();
        if (EraseMode) exitEraseMode();
        const ids = Array.from(new Set(Object.values(TeethSelected).map(v => v.Teeth)));
        if (ids.length) {
          TeethSelected = {};
          ids.forEach(id => Dencan_Draw1Teeth(id));
          refreshSelectionChip();
        }
        return;
      }
      if (ReadOnlyMode) return;
      if (e.key === 'Delete' || e.key === 'Backspace') {
        if (Object.keys(TeethSelected).length > 0) {
          e.preventDefault();
          clearSelectedTeethDiseases();
        }
        return;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
        e.preventDefault();
        performUndo();
      } else if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'y' || (e.shiftKey && e.key.toLowerCase() === 'z'))) {
        e.preventDefault();
        performRedo();
      }
    });

    document.getElementById('btnExportJsonTop')?.addEventListener('click', () => {
      const pre = document.getElementById('jsonExportPre');
      const status = document.getElementById('jsonCopyStatus');
      if (pre) pre.textContent = JSON.stringify(buildExportPayload(), null, 2);
      if (status) status.classList.add('d-none');
      const modalEl = document.getElementById('jsonExportModal');
      if (modalEl && window.bootstrap) {
        bootstrap.Modal.getOrCreateInstance(modalEl).show();
      }
    });

    document.getElementById('btnCopyJson')?.addEventListener('click', async () => {
      const pre = document.getElementById('jsonExportPre');
      const status = document.getElementById('jsonCopyStatus');
      if (!pre) return;
      try {
        await navigator.clipboard.writeText(pre.textContent);
      } catch {
        const ta = document.createElement('textarea');
        ta.value = pre.textContent;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        ta.remove();
      }
      if (status) status.classList.remove('d-none');
    });

    document.getElementById('btnSelectUpper')?.addEventListener('click', () => {
      if (ReadOnlyMode) return;
      TeethCollection.filter(t => t.UpDown === 'U').forEach(t => {
        TeethSelected[`face-${t.ID}`] = { Teeth: String(t.ID), Part: 'face' };
        TeethSelected[`root-${t.ID}`] = { Teeth: String(t.ID), Part: 'root' };
        Dencan_Draw1Teeth(t.ID);
      });
      refreshSelectionChip();
    });

    document.getElementById('btnSelectLower')?.addEventListener('click', () => {
      if (ReadOnlyMode) return;
      TeethCollection.filter(t => t.UpDown === 'D').forEach(t => {
        TeethSelected[`face-${t.ID}`] = { Teeth: String(t.ID), Part: 'face' };
        TeethSelected[`root-${t.ID}`] = { Teeth: String(t.ID), Part: 'root' };
        Dencan_Draw1Teeth(t.ID);
      });
      refreshSelectionChip();
    });

    document.getElementById('btnClearSel')?.addEventListener('click', () => {
      const ids = Array.from(new Set(Object.values(TeethSelected).map(v => v.Teeth)));
      TeethSelected = {};
      ids.forEach(id => Dencan_Draw1Teeth(id));
      refreshSelectionChip();
    });

    document.getElementById('btnResetAll')?.addEventListener('click', () => {
      if (ReadOnlyMode) return;
      const arr = getActiveDiseaseArray();
      if (arr.length) pushUndoState();
      if (TeethChart_Type === 1) CurrentDisease = [];
      else CurrentChildDisease = [];
      TeethSelected = {};
      TeethCollection.forEach(t => Dencan_Draw1Teeth(t.ID));
      Dendis_RenderDisease();
      refreshSelectionChip();
      updateModeUI();
    });

    document.getElementById('Canteeth_Note')?.addEventListener('input', () => {
      Dirty = true;
    });
  }

  function setReadOnly(flag) {
    ReadOnlyMode = !!flag;
    const scopeEl = document.querySelector('.df-odon-scope');
    if (scopeEl) scopeEl.classList.toggle('is-readonly', ReadOnlyMode);
    const noteEl = document.getElementById('Canteeth_Note');
    if (noteEl) {
      noteEl.readOnly = ReadOnlyMode;
      noteEl.disabled = false;
    }
    if (ReadOnlyMode) {
      PaintMode = false;
      ActivePaintDiseaseId = null;
      EraseMode = false;
      TeethSelected = {};
    }
    updateUndoBtn();
    updatePaintUI();
    updateEraseUI();
  }

  async function ensureReady() {
    await loadDataOnce();
    if (!document.querySelector('#Candisease .diseaseitem')) {
      buildSidebar();
    }
    await preloadAllAssets();
    initEvents();
  }

  function loadState(opts) {
    const o = opts || {};
    TeethChart_Type = String(o.chartType || 'ADULT').toUpperCase() === 'CHILD' ? 2 : 1;
    DefaultClassification = 1;
    PaintMode = false;
    ActivePaintDiseaseId = null;
    EraseMode = false;
    TeethSelected = {};
    UndoStack.length = 0;
    RedoStack.length = 0;

    if (Array.isArray(o.adultDiseases) || Array.isArray(o.childDiseases)) {
      CurrentDisease = Array.isArray(o.adultDiseases) ? o.adultDiseases.map(x => ({ ...x })) : [];
      CurrentChildDisease = Array.isArray(o.childDiseases) ? o.childDiseases.map(x => ({ ...x })) : [];
    } else if (Array.isArray(o.diseases)) {
      if (TeethChart_Type === 2) {
        CurrentChildDisease = o.diseases.map(x => ({ ...x }));
        CurrentDisease = [];
      } else {
        CurrentDisease = o.diseases.map(x => ({ ...x }));
        CurrentChildDisease = [];
      }
    } else {
      CurrentDisease = [];
      CurrentChildDisease = [];
    }

    const noteEl = document.getElementById('Canteeth_Note');
    if (noteEl) noteEl.value = o.note || '';

    const searchInput = document.getElementById('Dissearch_Input');
    if (searchInput) searchInput.value = '';

    setReadOnly(!!o.readOnly);
    syncChartTypeButtons();
    syncClassificationMode(1);
    buildToothChart();
    Dirty = false;
  }

  function getPayload() {
    const activeArr = getActiveDiseaseArray();
    const statusCount = activeArr.filter(d => Number(d.Classification) !== 2).length;
    const planCount = activeArr.filter(d => Number(d.Classification) === 2).length;
    const affectedTeethCount = new Set(activeArr.map(d => String(d.Teethid))).size;
    const note = (document.getElementById('Canteeth_Note')?.value || '').trim();
    const chartType = TeethChart_Type === 2 ? 'CHILD' : 'ADULT';

    const payload = {
      version: 1,
      chartType: chartType,
      notation: 'FDI',
      diseases: activeArr.map(d => ({ ...d })),
      adultDiseases: CurrentDisease.map(d => ({ ...d })),
      childDiseases: CurrentChildDisease.map(d => ({ ...d })),
      summary: {
        statusCount: statusCount,
        planCount: planCount,
        affectedTeethCount: affectedTeethCount
      }
    };

    return {
      chartType: chartType,
      note: note,
      statusCount: statusCount,
      planCount: planCount,
      affectedTeethCount: affectedTeethCount,
      payloadJson: JSON.stringify(payload)
    };
  }

  window.OdontogramEngine = {
    ensureReady: ensureReady,
    loadState: loadState,
    getPayload: getPayload,
    setReadOnly: setReadOnly,
    redraw: function () {
      if (TeethCollection.length) {
        TeethCollection.forEach(t => Dencan_Draw1Teeth(t.ID));
      }
    },
    isDirty: function () { return Dirty; },
    markClean: function () { Dirty = false; }
  };
})();
