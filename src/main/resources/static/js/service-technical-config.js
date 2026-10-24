(function () {
    "use strict";

    const form = document.getElementById(
        "serviceTechnicalConfigForm"
    );

    if (!form || form.dataset.paraclinical !== "true") {
        return;
    }

    const jsonField = document.getElementById(
        "resultTemplateJson"
    );

    const rowsContainer = document.getElementById(
        "resultParameterRows"
    );

    const rowTemplate = document.getElementById(
        "resultParameterRowTemplate"
    );

    const addButton = document.getElementById(
        "addResultParameter"
    );

    const saveButton = document.getElementById(
        "saveTechnicalConfig"
    );

    const emptyMessage = document.getElementById(
        "emptyResultParameters"
    );

    const errorBox = document.getElementById(
        "technicalConfigError"
    );

    const allowedTypes = new Set([
        "TEXT",
        "NUMBER",
        "BOOLEAN"
    ]);

    let templateData = { parameters: [] };

    function showError(message) {
        errorBox.textContent = message;
        errorBox.hidden = false;
        errorBox.scrollIntoView({
            behavior: "smooth",
            block: "center"
        });
    }

    function clearError() {
        errorBox.textContent = "";
        errorBox.hidden = true;
    }

    function updateEmptyMessage() {
        emptyMessage.hidden =
            rowsContainer.children.length > 0;
    }

    function addParameterRow(parameter = {}) {
        const row = rowTemplate.content
            .firstElementChild.cloneNode(true);

        row.querySelector('[data-field="key"]').value =
            parameter.key || "";

        row.querySelector('[data-field="label"]').value =
            parameter.label || "";

        row.querySelector('[data-field="type"]').value =
            parameter.type || "TEXT";

        row.querySelector('[data-field="unit"]').value =
            parameter.unit || "";

        row.querySelector(
            '[data-field="referenceRange"]'
        ).value = parameter.referenceRange || "";

        row.querySelector(
            '[data-field="required"]'
        ).checked = parameter.required === true;

        row.querySelector(
            "[data-remove-parameter]"
        ).addEventListener("click", function () {
            row.remove();
            clearError();
            updateEmptyMessage();
        });

        rowsContainer.appendChild(row);
        updateEmptyMessage();
    }

    function isValidSavedParameter(parameter) {
        return parameter !== null
            && typeof parameter === "object"
            && typeof parameter.key === "string"
            && typeof parameter.label === "string"
            && allowedTypes.has(parameter.type)
            && typeof parameter.required === "boolean"
            && (
                parameter.unit == null
                || typeof parameter.unit === "string"
            )
            && (
                parameter.referenceRange == null
                || typeof parameter.referenceRange === "string"
            );
    }

    // Khôi phục dữ liệu đã lưu hoặc dữ liệu trả về khi có lỗi.
    const existingJson = jsonField.value.trim();

    if (existingJson) {
        try {
            const parsed = JSON.parse(existingJson);

            if (
                parsed === null
                || typeof parsed !== "object"
                || Array.isArray(parsed)
                || !Array.isArray(parsed.parameters)
                || !parsed.parameters.every(isValidSavedParameter)
            ) {
                throw new Error("Unsupported result template");
            }

            templateData = parsed;

            parsed.parameters.forEach(function (parameter) {
                addParameterRow(parameter);
            });
        } catch (error) {
            // Không ghi đè mẫu cũ nếu không đọc được.
            addButton.disabled = true;
            saveButton.disabled = true;

            showError(
                "Không đọc được mẫu kết quả hiện tại. "
                + "Cần kiểm tra dữ liệu đã lưu trước khi cập nhật."
            );
        }
    }

    updateEmptyMessage();

    addButton.addEventListener("click", function () {
        clearError();
        addParameterRow();

        rowsContainer.lastElementChild
            .querySelector('[data-field="key"]')
            .focus();
    });

    form.addEventListener("submit", function (event) {
        clearError();

        const parameters = [];
        const usedKeys = new Set();

        const rows = rowsContainer.querySelectorAll("tr");

        for (const row of rows) {
            const keyInput = row.querySelector(
                '[data-field="key"]'
            );

            const labelInput = row.querySelector(
                '[data-field="label"]'
            );

            const key = keyInput.value.trim();
            const label = labelInput.value.trim();

            if (!/^[A-Za-z][A-Za-z0-9_]{0,49}$/.test(key)) {
                event.preventDefault();

                showError(
                    "Mã thông số phải bắt đầu bằng chữ không dấu, "
                    + "chỉ chứa chữ, số hoặc dấu _, tối đa 50 ký tự."
                );

                keyInput.focus();
                return;
            }

            if (!label) {
                event.preventDefault();
                showError("Vui lòng nhập tên thông số.");
                labelInput.focus();
                return;
            }

            const normalizedKey = key.toLowerCase();

            if (usedKeys.has(normalizedKey)) {
                event.preventDefault();

                showError(
                    "Mã thông số bị trùng: " + key
                );

                keyInput.focus();
                return;
            }

            usedKeys.add(normalizedKey);

            parameters.push({
                key: key,
                label: label,

                type: row.querySelector(
                    '[data-field="type"]'
                ).value,

                unit: row.querySelector(
                    '[data-field="unit"]'
                ).value.trim(),

                referenceRange: row.querySelector(
                    '[data-field="referenceRange"]'
                ).value.trim(),

                required: row.querySelector(
                    '[data-field="required"]'
                ).checked
            });
        }

        if (parameters.length === 0) {
            jsonField.value = "";
        } else {
            templateData.parameters = parameters;
            jsonField.value = JSON.stringify(templateData);
        }
    });
})();