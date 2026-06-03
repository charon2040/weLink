const fs = require('fs');
const readline = require('readline');
const jtl = process.argv[2];
const rl = readline.createInterface({ input: fs.createReadStream(jtl) });
let total = 0;
const errors = {};
rl.on('line', line => {
  total++;
  if (total === 1) return;
  const f = line.split(',');
  if (f.length < 8) return;
  if (f[2] === 'Recv Private Msg' && f[7] === 'false') {
    const key = (f[3] || '').substring(0, 60);
    errors[key] = (errors[key] || 0) + 1;
  }
});
rl.on('close', () => {
  console.log('Error types:');
  Object.entries(errors).sort((a,b) => b[1]-a[1]).forEach(([k,v]) => console.log(`  ${v}: ${k}`));
});
