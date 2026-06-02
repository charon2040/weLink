# Clean main DB
docker exec welink-mysql-main mysql -uroot -p123456 welink -e "
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM user;
DELETE FROM group_info;
DELETE FROM group_member;
DELETE FROM conversation;
DELETE FROM read_cursor;
SET FOREIGN_KEY_CHECKS=1;
SELECT 'main done' AS st;
"

# Clean all shard DBs
for i in $(seq 0 7); do
  CNT="welink-mysql-shard-$i"
  DB="welink_msg_0$i"
  docker exec $CNT mysql -uroot -p123456 -e "
    SELECT CONCAT('Cleaning ', '$DB') AS st;
  " 2>/dev/null
  # Truncate by dropping+recreating or just delete all
  # Message tables are month-partitioned, inbox/outbox are modulo 64 partitioned
  TABLES=$(docker exec $CNT mysql -uroot -p123456 -N -e "SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema='$DB' AND table_type='BASE TABLE'" 2>/dev/null)
  for T in $TABLES; do
    docker exec $CNT mysql -uroot -p123456 $DB -e "SET FOREIGN_KEY_CHECKS=0; DELETE FROM $T; SET FOREIGN_KEY_CHECKS=1;" 2>/dev/null
  done
  echo "  $DB cleaned"
done
echo "All databases cleaned"
