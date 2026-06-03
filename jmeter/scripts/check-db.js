const mysql = require('mysql2/promise');
const fs = require('fs');

async function main() {
  const out = [];
  try {
    const conn = await mysql.createConnection({
      host: 'localhost', port: 3315, user: 'root', password: '123456', database: 'welink'
    });

    // Check friend_relation table
    const [rows1] = await conn.execute('SELECT COUNT(*) as cnt FROM friend_relation');
    out.push('friend_relation count: ' + JSON.stringify(rows1));

    const [rows2] = await conn.execute('SELECT * FROM friend_relation WHERE status = 1 LIMIT 20');
    out.push('friend_relation (status=1): ' + JSON.stringify(rows2));

    // Check user table
    const [rows3] = await conn.execute('SELECT id, username, nickname FROM user LIMIT 10');
    out.push('users: ' + JSON.stringify(rows3));

    // Check group_member
    const [rows4] = await conn.execute('SELECT COUNT(*) as cnt FROM group_member');
    out.push('group_member count: ' + JSON.stringify(rows4));

    const [rows5] = await conn.execute('SELECT * FROM group_member LIMIT 5');
    out.push('group_member sample: ' + JSON.stringify(rows5));

    await conn.end();
  } catch (e) {
    out.push('Error: ' + e.message);
  }
  fs.writeFileSync('G:/WeLink/jmeter/scripts/db-result.txt', out.join('\n\n'));
}

main();
