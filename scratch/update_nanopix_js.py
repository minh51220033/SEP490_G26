with open('src/main/resources/static/js/nanopix-xray.js', 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Update apiRequest
old_api = """    async function apiRequest(url, options = {}) {
        const res = await fetch(url, {
            headers: {
                "Accept": "application/json",
                ...(options.headers || {})
            },
            ...options
        });

        if (!res.ok) {
            let errorMsg = `Lỗi yêu cầu (${res.status})`;
            try {
                const data = await res.json();
                errorMsg = data.message || data.error || errorMsg;
            } catch (_) {
                errorMsg = await res.text() || errorMsg;
            }
            throw new Error(errorMsg);
        }
        return res.json();
    }"""

new_api = """    async function apiRequest(url, options = {}) {
        const res = await fetch(url, {
            credentials: "same-origin",
            headers: {
                "Accept": "application/json",
                ...(options.headers || {})
            },
            ...options
        });

        if (!res.ok) {
            let errorMsg = `Lỗi yêu cầu (${res.status})`;
            try {
                const data = await res.json();
                errorMsg = data.message || data.error || errorMsg;
            } catch (_) {
                try {
                    const text = await res.text();
                    if (text) errorMsg = text;
                } catch (_) {}
            }
            if (res.status === 401) {
                errorMsg = "Phiên làm việc đã hết hạn hoặc chưa đăng nhập. Vui lòng đăng nhập lại.";
            } else if (res.status === 403) {
                errorMsg = "Bạn không có quyền truy cập hoặc thao tác trên cơ sở này.";
            }
            throw new Error(errorMsg);
        }
        return res.json();
    }"""

# 2. Add branchId to loadXrays
old_load = """        if (state.fromDate) params.append("fromDate", state.fromDate);
        if (state.toDate) params.append("toDate", state.toDate);"""

new_load = """        if (state.fromDate) params.append("fromDate", state.fromDate);
        if (state.toDate) params.append("toDate", state.toDate);
        const branchSelect = $("filterBranch");
        if (branchSelect && branchSelect.value) {
            params.append("branchId", branchSelect.value);
        }"""

# 3. Add listener for filterBranch
old_listener = """        $("searchBtn")?.addEventListener("click", () => {
            state.page = 0;
            loadXrays();
        });"""

new_listener = """        $("filterBranch")?.addEventListener("change", () => {
            state.page = 0;
            loadXrays();
            loadUnmatched();
            loadStats();
        });

        $("searchBtn")?.addEventListener("click", () => {
            state.page = 0;
            loadXrays();
        });"""

text_norm = text.replace("\r\n", "\n")
text_norm = text_norm.replace(old_api.replace("\r\n", "\n"), new_api)
text_norm = text_norm.replace(old_load.replace("\r\n", "\n"), new_load)
text_norm = text_norm.replace(old_listener.replace("\r\n", "\n"), new_listener)

with open('src/main/resources/static/js/nanopix-xray.js', 'w', encoding='utf-8') as f:
    f.write(text_norm)
print("SUCCESS JS")
