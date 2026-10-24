import sys, os, zipfile, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

def read_docx(path):
    with zipfile.ZipFile(path) as z:
        tree = ET.fromstring(z.read('word/document.xml'))
        paras = []
        for p in tree.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p'):
            t = ''.join([n.text for n in p.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t') if n.text])
            if t.strip():
                paras.append(t.strip())
        return paras

folder = r'C:\Users\PC\Downloads'
for f in os.listdir(folder):
    if 'chi tiết' in f.lower() and f.endswith('.docx'):
        path = os.path.join(folder, f)
        print("FOUND FILE:", f)
        lines = read_docx(path)
        is_p3 = False
        current_dot = "Bắt đầu"
        for l in lines:
            if "Đợt" in l or "Giai đoạn" in l:
                current_dot = l
            if "Người 3" in l:
                is_p3 = True
                print(f"\n[{current_dot}] -> {l}")
            elif any(f"Người {x}" in l for x in [1, 2, 4, 5]) and is_p3:
                is_p3 = False
            elif is_p3:
                print("   ", l)
        break
