const fs = require('fs');
const readline = require('readline');
const jtl = process.argv[2];
const rl = readline.createInterface({ input: fs.createReadStream(jtl) });
const stats = {
  private: { send: 0, recvOk: 0, recvFail: 0, noConn: 0, closeFrame: 0 },
  group: { send: 0, recvOk: 0, recvFail: 0, noConn: 0, closeFrame: 0 }
};
let total = 0;
rl.on('line', line => {
  total++;
  if (total === 1) return;
  const f = line.split(',');
  if (f.length < 8) return;
  const label = f[2];
  const success = f[7] === 'true';
  const msg = f[4] || '';
  function countRecv(bucket) {
    if (success) bucket.recvOk++;
    else {
      bucket.recvFail++;
      if (msg.includes('no connection')) bucket.noConn++;
      if (msg.includes('close')) bucket.closeFrame++;
    }
  }
  if (label === 'Recv Private Msg') {
    countRecv(stats.private);
  } else if (label === 'Send Private Msg' && success) {
    stats.private.send++;
  } else if (label === 'Recv Group Msg') {
    countRecv(stats.group);
  } else if (label === 'Send Group Msg' && success) {
    stats.group.send++;
  }
});
rl.on('close', () => {
  console.log(`Total samples: ${total}`);
  console.log(`Private: Send=${stats.private.send}, Recv OK=${stats.private.recvOk}, Recv Fail=${stats.private.recvFail}, NoConn=${stats.private.noConn}, CloseFrame=${stats.private.closeFrame}`);
  console.log(`Group:   Send=${stats.group.send}, Recv OK=${stats.group.recvOk}, Recv Fail=${stats.group.recvFail}, NoConn=${stats.group.noConn}, CloseFrame=${stats.group.closeFrame}`);
});
