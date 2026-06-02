with open(r'G:\WeLink\src\main\resources\sharding-config.yaml', 'r') as f:
    content = f.read()
old = '    maximumPoolSize: 80\n    minimumIdle: 5\n    connectionTimeout: 10000\n    idleTimeout: 600000\n    maxLifetime: 1800000\n    leakDetectionThreshold: 30000'
new = '    maximumPoolSize: 100\n    minimumIdle: 5\n    connectionTimeout: 30000\n    idleTimeout: 600000\n    maxLifetime: 1800000\n    leakDetectionThreshold: 60000'
content = content.replace(old, new)
with open(r'G:\WeLink\src\main\resources\sharding-config.yaml', 'w') as f:
    f.write(content)
print('80s:', content.count('maximumPoolSize: 80'))
print('100s:', content.count('maximumPoolSize: 100'))
print('200s:', content.count('maximumPoolSize: 200'))
