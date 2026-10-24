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
for f in os.listdir(folder):
    if 'chia' in f.lower() and f.endswith('.docx'):
        path = os.path.join(folder, f)
        print("====================================")
        print("FILE:", f)
        print("====================================")
        lines = read_docx(path)
        capture = False
        for l in lines:
            if 'người 3' in l.lower() or 'nguoi 3' in l.lower():
                capture = True
            elif 'người 4' in l.lower() or 'nguoi 4' in l.lower():
                capture = False
            if capture:
                print(l)
