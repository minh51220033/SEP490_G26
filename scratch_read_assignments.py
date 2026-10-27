import sys, os, zipfile, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

def read_docx(path):
    try:
        with zipfile.ZipFile(path) as z:
            tree = ET.fromstring(z.read('word/document.xml'))
            paras = []
            for p in tree.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p'):
                t = ''.join([n.text for n in p.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t') if n.text])
                if t.strip():
                    paras.append(t.strip())
            return paras
    except Exception as e:
        return [str(e)]

folder = r'C:\Users\PC\Downloads'
for fname in os.listdir(folder):
    if fname.endswith('.docx') and any(k in fname.lower() for k in ['chia', 'feature', 'nguoi', 'phan cong']):
        fpath = os.path.join(folder, fname)
        print(f"=== FILE: {fname} ===")
        content = read_docx(fpath)
        for line in content:
            if any(k in line.lower() for k in ['người 3', 'nguoi 3', 'thành viên 3', 'role 2', 'bác sĩ', 'bac si', 'bác si', 'person 3']):
                print("  *", line)
