const http = require('http');
const fs = require('fs');

function post(path, body, headers) {
  return new Promise((resolve, reject) => {
    const opts = {
      hostname: 'localhost', port: 8080, path: path, method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(headers || {}) }
    };
    const req = http.request(opts, res => {
      let d = ''; res.on('data', c => d += c); res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    req.on('error', reject);
    req.setTimeout(10000, () => { req.destroy(); reject(new Error('timeout')); });
    if (body) req.write(typeof body === 'string' ? body : JSON.stringify(body));
    req.end();
  });
}

function get(path, token) {
  return new Promise((resolve, reject) => {
    const req = http.get({
      hostname: 'localhost', port: 8080, path: path,
      headers: { 'Authorization': 'Bearer ' + token }
    }, res => {
      let d = ''; res.on('data', c => d += c); res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    req.on('error', reject);
    req.setTimeout(10000, () => { req.destroy(); reject(new Error('timeout')); });
  });
}

async function main() {
  const out = [];
  try {
    // Login as perf_user_1 (the user the frontend is logged in as)
    const login1 = await post('/api/v1/auth/login', { username: 'perf_user_1', password: 'perf123456' });
    const data1 = JSON.parse(login1.body);
    out.push('Login perf_user_1: code=' + data1.code);
    if (data1.code !== 200) { out.push('Login failed: ' + data1.message); throw new Error('login failed'); }
    const token1 = data1.data.accessToken;
    const userId1 = data1.data.userInfo.id;
    out.push('userId1=' + userId1 + ' type=' + typeof userId1);

    // Friend list
    const friends = await get('/api/v1/friend/list', token1);
    const friendData = JSON.parse(friends.body);
    out.push('Friend list: code=' + friendData.code + ' data length=' + (friendData.data ? friendData.data.length : 'null'));
    out.push('Friend list full: ' + friends.body.substring(0, 2000));

    // Group list
    const groups = await get('/api/v1/group/list', token1);
    const groupData = JSON.parse(groups.body);
    out.push('Group list: code=' + groupData.code + ' data length=' + (groupData.data ? groupData.data.length : 'null'));
    out.push('Group list full: ' + groups.body.substring(0, 2000));

    // Check friend_relation directly - search for user1's friends
    // Also check: what user ID is the frontend user?
    out.push('\n--- Checking friend IDs ---');
    const friendIds = friendData.data ? friendData.data.map(f => f.id + '(' + typeof f.id + ')') : [];
    out.push('Friend IDs: ' + friendIds.join(', '));

  } catch (e) {
    out.push('Error: ' + e.message + '\n' + e.stack);
  }
  fs.writeFileSync('G:/WeLink/jmeter/scripts/api-debug.txt', out.join('\n\n'));
}

main();
