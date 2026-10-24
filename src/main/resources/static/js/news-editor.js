/* CV-DCMS - Form tạo / sửa bài viết: upload ảnh đại diện + CKEditor 5 */
document.addEventListener("DOMContentLoaded", function () {
    const form = document.getElementById("newsForm");
    if (!form) return;

    const uploadUrl = form.dataset.uploadUrl || "/admin/news/upload/image";

    // 1. ẢNH ĐẠI DIỆN
    const fileInput = document.getElementById("thumbnailFile");
    const dropZone = document.getElementById("thumbnailDrop");
    const dropText = document.getElementById("thumbnailDropText");
    const urlInput = document.getElementById("thumbnail");
    const previewBox = document.getElementById("thumbnailPreview");
    const previewImg = document.getElementById("thumbnailPreviewImage");
    const removeBtn = document.getElementById("thumbnailRemove");

    function showPreview(url) {
        if (url) {
            previewImg.src = url;
            previewBox.hidden = false;
        } else {
            previewImg.removeAttribute("src");
            previewBox.hidden = true;
        }
    }

    function uploadFile(file) {
        if (!file) return;
        const defaultText = "Nhấn để chọn ảnh hoặc kéo thả vào đây";
        dropText.textContent = "Đang tải ảnh lên...";

        const formData = new FormData();
        formData.append("upload", file);

        fetch(uploadUrl, { method: "POST", body: formData })
            .then(res => res.ok ? res.json() : res.json().then(err => { throw err; }))
            .then(data => {
                if (data.url) {
                    urlInput.value = data.url;
                    showPreview(data.url);
                    dropText.textContent = "Tải ảnh thành công - nhấn để đổi ảnh khác";
                } else {
                    dropText.textContent = defaultText;
                }
            })
            .catch(error => {
                console.error("Lỗi tải ảnh:", error);
                alert(error?.error?.message || "Không thể tải ảnh lên");
                dropText.textContent = "Tải thất bại - nhấn để thử lại";
            });
    }

    if (dropZone && fileInput) {
        dropZone.addEventListener("click", () => fileInput.click());
        dropZone.addEventListener("keydown", e => {
            if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fileInput.click(); }
        });
        dropZone.addEventListener("dragover", e => { e.preventDefault(); dropZone.style.borderColor = "var(--brand-600)"; });
        dropZone.addEventListener("dragleave", () => { dropZone.style.borderColor = ""; });
        dropZone.addEventListener("drop", e => {
            e.preventDefault();
            dropZone.style.borderColor = "";
            uploadFile(e.dataTransfer.files[0]);
        });
        fileInput.addEventListener("change", function () { uploadFile(this.files[0]); this.value = ""; });
    }

    if (urlInput) {
        urlInput.addEventListener("change", () => showPreview(urlInput.value.trim()));
    }

    if (removeBtn) {
        removeBtn.addEventListener("click", () => {
            urlInput.value = "";
            showPreview("");
        });
    }

    // 2. ĐẾM KÝ TỰ
    document.querySelectorAll("[data-counter]").forEach(field => {
        const counter = document.getElementById(field.dataset.counter);
        if (!counter) return;
        const update = () => { counter.textContent = field.value.length + "/" + field.maxLength; };
        field.addEventListener("input", update);
        update();
    });

    // 3. CKEDITOR 5
    const contentTextarea = document.getElementById("content");
    if (typeof CKEDITOR === "undefined" || !contentTextarea) return;

    const {
        ClassicEditor, Essentials, Paragraph, Heading, Bold, Italic,
        Underline, Font, Link, List, Alignment, BlockQuote,
        Image, ImageUpload, ImageToolbar, ImageCaption, ImageStyle, ImageResize,
        SimpleUploadAdapter, Table, TableToolbar
    } = CKEDITOR;

    let editorInstance;

    ClassicEditor
        .create(document.querySelector("#editor"), {
            licenseKey: "GPL",
            plugins: [
                Essentials, Paragraph, Heading, Bold, Italic, Underline,
                Font, Link, List, Alignment, BlockQuote,
                Image, ImageUpload, ImageToolbar, ImageCaption, ImageStyle, ImageResize,
                SimpleUploadAdapter, Table, TableToolbar
            ],
            toolbar: [
                "undo", "redo", "|", "heading", "|", "bold", "italic", "underline", "|",
                "fontSize", "fontColor", "|", "alignment", "|",
                "bulletedList", "numberedList", "|",
                "link", "insertImage", "insertTable", "blockQuote"
            ],
            image: {
                toolbar: [
                    "imageStyle:inline", "imageStyle:block", "imageStyle:side", "|",
                    "toggleImageCaption", "imageTextAlternative"
                ]
            },
            table: { contentToolbar: ["tableColumn", "tableRow", "mergeTableCells"] },
            simpleUpload: { uploadUrl: uploadUrl },
            placeholder: "Nhập nội dung bài viết. Bạn có thể kéo thả ảnh trực tiếp vào đây...",
            initialData: contentTextarea.value || ""
        })
        .then(editor => {
            editorInstance = editor;
            editor.model.document.on("change:data", () => {
                contentTextarea.value = editor.getData();
            });
        })
        .catch(error => console.error("Lỗi CKEditor:", error));

    form.addEventListener("submit", function (e) {
        if (editorInstance) contentTextarea.value = editorInstance.getData();
        if (!contentTextarea.value.trim()) {
            e.preventDefault();
            alert("Vui lòng nhập nội dung bài viết.");
            editorInstance?.editing.view.focus();
        }
    });
});
