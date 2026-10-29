import os
import re

pattern = re.compile(r'session\.getAttribute\(["\']([^"\']+)["\']\)')
keys = set()
for root, dirs, files in os.walk('src/main/java'):
    for f in files:
        if f.endswith('.java'):
            path = os.path.join(root, f)
            with open(path, 'r', encoding='utf-8', errors='ignore') as fp:
                for match in pattern.finditer(fp.read()):
                    keys.add((match.group(1), f))
for k, f in sorted(keys):
    print(f'{k:25} in {f}')
