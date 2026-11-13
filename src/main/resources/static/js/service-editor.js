/* CV-DCMS - Quản lý Dịch vụ & Bảng giá: Upload ảnh dịch vụ + CKEditor 5 cho Mô tả dịch vụ */
document.addEventListener("DOMContentLoaded", function () {
    "use strict";

    const form = document.getElementById("serviceFormElement") || document.getElementById("createServiceForm");
    if (!form) return;

    const uploadUrl = form.dataset.uploadUrl || "/admin/services/upload/image";
    const MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    const ALLOWED_TYPES = ["image/jpeg", "image/jpg", "image/png", "image/webp"];
    const ALLOWED_EXT_REGEX = /\.(jpe?g|png|webp)$/i;

    // =========================================================================
    // 1. ĐỒNG BỘ LOẠI DỊCH VỤ THEO NHÓM DỊCH VỤ
    // =========================================================================
    const categorySelect = document.getElementById("categoryId");
    const serviceTypeSelect = document.getElementById("serviceType");
    if (categorySelect && serviceTypeSelect) {
        categorySelect.addEventListener("change", function () {
            const selectedOption = categorySelect.options[categorySelect.selectedIndex];
            if (selectedOption && selectedOption.dataset.categoryType) {
                serviceTypeSelect.value = selectedOption.dataset.categoryType;
            }
        });
    }

    // =========================================================================
    // 2. QUẢN LÝ HÌNH ẢNH DỊCH VỤ (CHỌN, XEM TRƯỚC, THAY ĐỔI, XÓA)
    // =========================================================================
    const fileInput = document.getElementById("serviceImageFile");
    const urlInput = document.getElementById("imageUrl");
    const dropZone = document.getElementById("serviceImageDropZone");
    const placeholderBox = document.getElementById("serviceImagePlaceholder");
    const previewBox = document.getElementById("serviceImagePreviewBox");
    const previewImg = document.getElementById("serviceImagePreview");
    const selectBtn = document.getElementById("selectServiceImageBtn");
    const changeBtn = document.getElementById("changeServiceImageBtn");
    const removeBtn = document.getElementById("removeServiceImageBtn");
    const errorAlert = document.getElementById("serviceImageUploadError");
    const statusText = document.getElementById("serviceImageUploadStatus");

    function showError(msg) {
        if (!errorAlert) return;
        if (msg) {
            errorAlert.textContent = msg;
            errorAlert.hidden = false;
        } else {
            errorAlert.textContent = "";
            errorAlert.hidden = true;
        }
    }

    function setUploadingState(isUploading) {
        if (statusText) {
            statusText.hidden = !isUploading;
        }
        if (selectBtn) selectBtn.disabled = isUploading;
        if (changeBtn) changeBtn.disabled = isUploading;
        if (removeBtn) removeBtn.disabled = isUploading;
    }

    function syncImageUI(imageUrl) {
        const cleanUrl = (imageUrl || "").trim();
        if (cleanUrl) {
            if (previewImg) {
                previewImg.src = cleanUrl;
            }
            if (previewBox) previewBox.hidden = false;
            if (placeholderBox) placeholderBox.hidden = true;
        } else {
            if (previewImg) {
                previewImg.removeAttribute("src");
            }
            if (previewBox) previewBox.hidden = true;
            if (placeholderBox) placeholderBox.hidden = false;
        }
    }

    function uploadServiceImageFile(file) {
        if (!file) return;
        showError("");

        if (!ALLOWED_TYPES.includes((file.type || "").toLowerCase()) || !ALLOWED_EXT_REGEX.test(file.name || "")) {
            showError("Định dạng tệp không hợp lệ. Chỉ chấp nhận ảnh JPG, JPEG, PNG hoặc WEBP.");
            return;
        }

        if (file.size > MAX_FILE_SIZE) {
            showError("Dung lượng ảnh vượt quá giới hạn cho phép (tối đa 5MB).");
            return;
        }

        setUploadingState(true);

        const formData = new FormData();
        formData.append("file", file);

        fetch(uploadUrl, {
            method: "POST",
            body: formData,
            credentials: "same-origin"
        })
            .then(function (res) {
                return res.json().then(function (data) {
                    if (!res.ok) {
                        throw data;
                    }
                    return data;
                });
            })
            .then(function (data) {
                setUploadingState(false);
                if (data && data.url) {
                    if (urlInput) urlInput.value = data.url;
                    syncImageUI(data.url);
                } else {
                    showError("Không nhận được đường dẫn ảnh từ máy chủ.");
                }
            })
            .catch(function (err) {
                setUploadingState(false);
                const msg = (err && err.error && err.error.message)
                    || (err && err.message)
                    || "Không thể tải ảnh dịch vụ lên. Vui lòng kiểm tra tệp và thử lại.";
                showError(msg);
            });
    }

    // Khởi tạo hiển thị ảnh hiện tại khi mở form
    if (urlInput) {
        syncImageUI(urlInput.value);
    }

    if (previewImg) {
        previewImg.addEventListener("error", function () {
            if (previewImg.getAttribute("src")) {
                if (previewBox) previewBox.hidden = true;
                if (placeholderBox) placeholderBox.hidden = false;
            }
        });
    }

    if (selectBtn && fileInput) {
        selectBtn.addEventListener("click", function (e) {
            e.preventDefault();
            e.stopPropagation();
            fileInput.click();
        });
    }

    if (changeBtn && fileInput) {
        changeBtn.addEventListener("click", function (e) {
            e.preventDefault();
            e.stopPropagation();
            fileInput.click();
        });
    }

    if (removeBtn) {
        removeBtn.addEventListener("click", function (e) {
            e.preventDefault();
            e.stopPropagation();
            if (urlInput) urlInput.value = "";
            if (fileInput) fileInput.value = "";
            showError("");
            syncImageUI("");
        });
    }

    if (dropZone && fileInput) {
        dropZone.addEventListener("click", function (e) {
            if (e.target.closest("button")) return;
            fileInput.click();
        });
        dropZone.addEventListener("keydown", function (e) {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                fileInput.click();
            }
        });
        dropZone.addEventListener("dragover", function (e) {
            e.preventDefault();
            dropZone.classList.add("is-dragover");
        });
        dropZone.addEventListener("dragleave", function () {
            dropZone.classList.remove("is-dragover");
        });
        dropZone.addEventListener("drop", function (e) {
            e.preventDefault();
            dropZone.classList.remove("is-dragover");
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                uploadServiceImageFile(e.dataTransfer.files[0]);
            }
        });
    }

    if (fileInput) {
        fileInput.addEventListener("change", function () {
            if (this.files && this.files.length > 0) {
                uploadServiceImageFile(this.files[0]);
            }
            this.value = "";
        });
    }

    // =========================================================================
    // 3. TÍCH HỢP CKEDITOR 5 CHO TRƯỜNG MÔ TẢ DỊCH VỤ
    // =========================================================================
    const descriptionTextarea = document.getElementById("description");
    const editorContainer = document.getElementById("serviceDescriptionEditor");

    if (!descriptionTextarea || !editorContainer) return;

    if (typeof CKEDITOR === "undefined") {
        // Fallback an toàn nếu CDN CKEditor không tải được
        descriptionTextarea.hidden = false;
        editorContainer.hidden = true;
        return;
    }

    const {
        ClassicEditor, Essentials, Paragraph, Heading, Bold, Italic,
        Underline, Link, List, Alignment, BlockQuote,
        Image, ImageUpload, ImageToolbar, ImageCaption, ImageStyle, ImageResize,
        SimpleUploadAdapter, Table, TableToolbar
    } = CKEDITOR;

    let editorInstance = null;

    ClassicEditor
        .create(editorContainer, {
            licenseKey: "GPL",
            plugins: [
                Essentials, Paragraph, Heading, Bold, Italic, Underline,
                Link, List, Alignment, BlockQuote,
                Image, ImageUpload, ImageToolbar, ImageCaption, ImageStyle, ImageResize,
                SimpleUploadAdapter, Table, TableToolbar
            ],
            toolbar: [
                "undo", "redo", "|",
                "heading", "|",
                "bold", "italic", "underline", "|",
                "alignment", "|",
                "bulletedList", "numberedList", "|",
                "link", "insertImage", "insertTable", "blockQuote"
            ],
            image: {
                toolbar: [
                    "imageStyle:inline", "imageStyle:block", "imageStyle:side", "|",
                    "toggleImageCaption", "imageTextAlternative"
                ]
            },
            table: {
                contentToolbar: ["tableColumn", "tableRow", "mergeTableCells"]
            },
            simpleUpload: {
                uploadUrl: uploadUrl
            },
            placeholder: "Nhập nội dung giới thiệu dịch vụ, quy trình thực hiện, lưu ý chuyên môn...",
            initialData: descriptionTextarea.value || ""
        })
        .then(function (editor) {
            editorInstance = editor;
            descriptionTextarea.hidden = true;
            editor.model.document.on("change:data", function () {
                descriptionTextarea.value = editor.getData();
            });
        })
        .catch(function (error) {
            console.error("Không thể khởi tạo CKEditor cho mô tả dịch vụ:", error);
            descriptionTextarea.hidden = false;
            editorContainer.hidden = true;
        });

    form.addEventListener("submit", function () {
        if (editorInstance) {
            descriptionTextarea.value = editorInstance.getData();
        }
    });
});
