const http = require('http');
const fs = require('fs');

const token = 'eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJwZXJmX3VzZXJfMCIsInVzZXJJZCI6MTEwMDA0LCJpYXQiOjE3ODA0MjU3MDUsImV4cCI6MTc4MDQzMjkwNX0.N41x1HxP5AhzSj_2vElrNra8KIbrNrmzuxvLeIhkP5V1Ie1-n-rvSBczjzRpvlNH';

function httpGet(path) {
  return new Promise((resolve, reject) => {
    const req = http.get({
      hostname: 'localhost',
      port: 8080,
      path: path,
      headers: { 'Authorization': 'Bearer ' + token }
    }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    req.setTimeout(5000, () => { req.destroy(); reject(new Error('timeout')); });
  });
}

async function main() {
  const results = [];
  try {
    const r1 = await httpGet('/api/v1/auth/user/search?username=perf_user_0');
    results.push('Search perf_user_0: ' + r1.status + ' ' + r1.body.substring(0, 300));
  } catch (e) {
    results.push('Search error: ' + e.message);
  }

  try {
    const r2 = await httpGet('/api/v1/auth/user/110004');
    results.push('Get user 110004: ' + r2.status + ' ' + r2.body.substring(0, 300));
  } catch (e) {
    results.push('Get user error: ' + e.message);
  }

  try {
    const r3 = await httpGet('/api/v1/auth/user/search?username=nonexistent');
    results.push('Search nonexistent: ' + r3.status + ' ' + r3.body.substring(0, 300));
  } catch (e) {
    results.push('Search nonexistent error: ' + e.message);
  }

  fs.writeFileSync('G:/WeLink/jmeter/scripts/test-search-result.txt', results.join('\n'));
}

main();
