with open('database/migrations/V8__ft12_nanopix_xray_management.sql', 'r', encoding='utf-8') as f:
    text = f.read()

target = """    matched_item_id BIGINT NULL,
    tooth_numbers VARCHAR(50) NULL,"""

replacement = """    matched_item_id BIGINT NULL,
    branch_id BIGINT NULL,
    tooth_numbers VARCHAR(50) NULL,"""

fk_target = """    CONSTRAINT fk_nanopix_item FOREIGN KEY (matched_item_id) REFERENCES paraclinical_order_items(order_item_id) ON DELETE SET NULL,"""

fk_replacement = """    CONSTRAINT fk_nanopix_item FOREIGN KEY (matched_item_id) REFERENCES paraclinical_order_items(order_item_id) ON DELETE SET NULL,
    CONSTRAINT fk_nanopix_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE SET NULL,"""

text_norm = text.replace("\r\n", "\n")
text_norm = text_norm.replace(target.replace("\r\n", "\n"), replacement)
text_norm = text_norm.replace(fk_target.replace("\r\n", "\n"), fk_replacement)

if "CREATE INDEX idx_nanopix_branch" not in text_norm:
    text_norm += "\nCREATE INDEX idx_nanopix_branch ON nanopix_xray_sync_logs(branch_id);\n"

with open('database/migrations/V8__ft12_nanopix_xray_management.sql', 'w', encoding='utf-8') as f:
    f.write(text_norm)
print("SUCCESS V8")
