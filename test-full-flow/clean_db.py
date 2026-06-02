import subprocess, shlex

MAIN_CMD = """docker exec welink-mysql-main mysql -uroot -p123456 welink -e "SET FOREIGN_KEY_CHECKS=0; DELETE FROM user; DELETE FROM group_info; DELETE FROM group_member; DELETE FROM conversation; DELETE FROM read_cursor; SET FOREIGN_KEY_CHECKS=1; SELECT 'main ok' AS st;" """

print("Cleaning main DB...")
subprocess.run(MAIN_CMD, shell=True)

for i in range(8):
    cnt = f"welink-mysql-shard-{i}"
    db = f"welink_msg_0{i}"
    print(f"Cleaning {db}...")
    
    result = subprocess.run(
        f'docker exec {cnt} mysql -uroot -p123456 -N -e "SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema=\'{db}\' AND table_type=\'BASE TABLE\'"',
        shell=True, capture_output=True, text=True
    )
    tables = [t.strip() for t in result.stdout.strip().split('\n') if t.strip()]
    
    for t in tables:
        subprocess.run(
            f'docker exec {cnt} mysql -uroot -p123456 {db} -e "SET FOREIGN_KEY_CHECKS=0; DELETE FROM {t}; SET FOREIGN_KEY_CHECKS=1;"',
            shell=True, capture_output=True
        )
    print(f"  {db}: {len(tables)} tables cleaned")

print("All databases cleaned")
