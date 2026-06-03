const ws = require('ws');
const t = 'eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJwZXJmX3VzZXJfMCIsInVzZXJJZCI6MTEwMDA0LCJpYXQiOjE3ODA0MjU3MDUsImV4cCI6MTc4MDQzMjkwNX0.N41x1HxP5AhzSj_2vElrNra8KIbrNrmzuxvLeIhkP5V1Ie1-n-rvSBczjzRpvlNH';
const c = new ws('ws://localhost:8081/ws');
c.on('open', () => {
  console.log('connected');
  c.send(JSON.stringify({type:'auth', token:t, deviceId:'wscat_test'}));
});
c.on('message', d => {
  console.log('msg:', d.toString());
});
c.on('close', (code, reason) => {
  console.log('closed:', code, reason.toString());
  process.exit(0);
});
c.on('error', e => {
  console.log('error:', e.message);
});
setTimeout(() => {
  console.log('still alive after 10s');
  c.close();
  process.exit(0);
}, 10000);
