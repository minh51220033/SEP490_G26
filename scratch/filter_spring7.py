with open('target/all_m2_jars.txt', 'r', encoding='utf-8') as f:
    jars = f.read().split(';')

# Remove spring 7.x jars so Spring 6.1.8 is strictly used
filtered = [j for j in jars if '7.0.9' not in j and '7.0.' not in j and 'spring-test-7' not in j]
print('Original jars:', len(jars), 'Filtered jars:', len(filtered))

with open('target/all_m2_jars.txt', 'w', encoding='utf-8') as f:
    f.write(';'.join(filtered))

with open('target/java_args.txt', 'w', encoding='utf-8') as f:
    f.write('-cp\n')
    f.write('"' + ';'.join(filtered).replace('\\', '/') + '"\n')

print("UPDATED java_args.txt")
