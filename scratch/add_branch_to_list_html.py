with open('src/main/resources/templates/xray/list.html', 'r', encoding='utf-8') as f:
    text = f.read()

target = """                        <div class="filter-field">
                            <label for="filterStatus">Trạng thái đối chiếu</label>"""

branch_html = """                        <div class="filter-field">
                            <label for="filterBranch">Cơ sở hoạt động</label>
                            <select id="filterBranch">
                                <option th:if="${isAdmin}" value="">Tất cả cơ sở</option>
                                <option th:each="b : ${branches}" th:value="${b.branchId}" th:text="${b.branchName}" th:selected="${b.branchId == currentBranchId}"></option>
                            </select>
                        </div>
                        <div class="filter-field">
                            <label for="filterStatus">Trạng thái đối chiếu</label>"""

text_norm = text.replace("\r\n", "\n")
target_norm = target.replace("\r\n", "\n")

if target_norm in text_norm:
    text_norm = text_norm.replace(target_norm, branch_html)
    with open('src/main/resources/templates/xray/list.html', 'w', encoding='utf-8') as f:
        f.write(text_norm)
    print("SUCCESS")
else:
    print("NOT FOUND")
