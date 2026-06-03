const http = require('http');
const fs = require('fs');

function post(path, body, headers) {
  return new Promise((resolve, reject) => {
    const opts = {
      hostname: 'localhost', port: 3000, path: path, method: 'POST',
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
      hostname: 'localhost', port: 3000, path: path,
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
    // Test through Vite proxy (port 3000)
    const login1 = await post('/api/v1/auth/login', { username: 'perf_user_1', password: 'perf123456' });
    const data1 = JSON.parse(login1.body);
    out.push('Login via proxy: code=' + data1.code);
    if (data1.code !== 200) { out.push('Login failed: ' + data1.message); throw new Error('login failed'); }
    const token1 = data1.data.accessToken;

    // Friend list via proxy
    const friends = await get('/api/v1/friend/list', token1);
    out.push('Friend list via proxy: ' + friends.body.substring(0, 2000));

    // Group list via proxy
    const groups = await get('/api/v1/group/list', token1);
    out.push('Group list via proxy: ' + groups.body.substring(0, 2000));

  } catch (e) {
    out.push('Error: ' + e.message + '\n' + e.stack);
  }
  fs.writeFileSync('G:/WeLink/jmeter/scripts/proxy-debug.txt', out.join('\n\n'));
}

main();
