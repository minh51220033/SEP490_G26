with open('src/main/java/com/cvdcms/entity/NanopixXraySyncLog.java', 'r', encoding='utf-8') as f:
    text = f.read()

target = """    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_visit_id")
    private Visit matchedVisit;"""

replacement = """    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_visit_id")
    private Visit matchedVisit;

    /**
     * Co so / Chi nhanh thuc hien chup X-quang hoac lien ket.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;"""

text_normalized = text.replace("\r\n", "\n")
target_norm = target.replace("\r\n", "\n")

if target_norm in text_normalized:
    text_normalized = text_normalized.replace(target_norm, replacement)
    with open('src/main/java/com/cvdcms/entity/NanopixXraySyncLog.java', 'w', encoding='utf-8') as f:
        f.write(text_normalized)
    print("SUCCESS")
else:
    print("NOT FOUND")
