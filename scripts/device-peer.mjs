// Independent browser peer for the debug-only CaptureProbeActivity; binds localhost only.
// npm install playwright@1.56.1 ws@8.18.3, then set TOUCHBACK_TEST_NODE_MODULES.
import {createRequire} from 'node:module';
import {resolve, dirname} from 'node:path';
import {readFile, mkdir, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import http from 'node:http';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const contract = JSON.parse(await readFile(resolve(root, 'protocol/touchback-contract.lock.json'), 'utf8'));
if (!/^[a-f0-9]{64}$/.test(contract.fingerprint)) throw new Error('Invalid locked contract fingerprint');
const require = createRequire(process.env.TOUCHBACK_TEST_NODE_MODULES ? resolve(process.env.TOUCHBACK_TEST_NODE_MODULES, '../package.json') : import.meta.url);
const {WebSocketServer} = require('ws');
const {chromium} = require('playwright');
const signals = new WebSocketServer({host:'127.0.0.1', port:18888});
const clients = new Set();
const localWebcast = process.env.NEARCAST_LOCAL_WEBCAST_FIXTURE === '1';
let browserSocket, androidSocket;
const context = {roomId:'local-room', userId:'local-sender', hostUserId:'local-host'};
signals.on('connection', (socket, request) => {
  clients.add(socket);
  if (localWebcast) {
    const receiver = new URL(request.url, 'http://localhost').searchParams.get('role') === 'receiver';
    if (receiver) browserSocket = socket; else androidSocket = socket;
    socket.on('message', bytes => {
      const message = JSON.parse(bytes.toString());
      if (!receiver && message.type === 'join') {
        socket.send(JSON.stringify(message.pairCode === 'TEST' ? {type:'joined', ...context, RoomName:'Owned local WebCast fixture'} : {type:'join_failed', reason:'invalid_code'}));
        return;
      }
      if (!receiver && ['offer','answer','ice-candidate'].includes(message.type)) {
        if (message.roomId !== context.roomId) throw new Error('Wrong local room');
        browserSocket?.send(JSON.stringify({type:message.type, ...message[message.type === 'ice-candidate' ? 'candidate' : message.type]}));
      } else if (receiver && ['answer','ice-candidate'].includes(message.type)) {
        const field = message.type === 'ice-candidate' ? 'candidate' : message.type;
        androidSocket?.send(JSON.stringify({type:message.type, ...context, [field]:field === 'candidate' ? message.candidate : {type:message.type, sdp:message.sdp}}));
      }
    });
    socket.on('close', () => clients.delete(socket));
    return;
  }
  socket.on('message', bytes => { for (const peer of clients) if (peer !== socket && peer.readyState === 1) peer.send(bytes.toString()); });
  socket.on('close', () => clients.delete(socket));
  if (clients.size >= 2) for (const peer of clients) peer.send(JSON.stringify({type:'state',state:'Ready'}));
});
let page;
const server = http.createServer(async (request, response) => {
  try {
    response.setHeader('Content-Type', 'application/json');
    if (request.url === '/state') {
      const value = await page.evaluate(() => peer.snapshot());
      response.end(JSON.stringify(value)); return;
    }
    if (request.url === '/command' && request.method === 'POST') {
      let body = ''; for await (const chunk of request) { body += chunk; if (body.length > 8192) throw new Error('Command too large'); }
      const value = await page.evaluate(command => peer.command(command), JSON.parse(body));
      response.end(JSON.stringify(value)); return;
    }
    if (request.url === '/evidence') {
      const evidence = await page.evaluate(() => peer.snapshot());
      await mkdir(resolve(root, 'build/device-evidence'), {recursive:true});
      await writeFile(resolve(root, 'build/device-evidence/peer.json'), JSON.stringify(evidence, null, 2));
      response.end(JSON.stringify(evidence)); return;
    }
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end((await readFile(resolve(root, 'scripts/device-peer.html'), 'utf8'))
      .replace("'__NEARCAST_CONTRACT_FINGERPRINT__'", JSON.stringify(contract.fingerprint)));
  } catch (error) { response.statusCode = 500; response.end(JSON.stringify({error:error.message})); }
});
await new Promise(resolve => server.listen(18889, '127.0.0.1', resolve));
const browser = await chromium.launch({channel:process.env.BROWSER_CHANNEL ?? 'msedge', headless:true,
  args:['--autoplay-policy=no-user-gesture-required']});
page = await browser.newPage();
page.on('pageerror', error => console.error('Browser peer:', error.message));
await page.goto('http://127.0.0.1:18889/' + (localWebcast ? '?webcast=1' : ''));
console.log('Device peer ready: ws 18888, HTTP 18889. Use adb reverse tcp:18888 tcp:18888.');
async function shutdown() { await browser.close(); signals.close(); server.close(); process.exit(0); }
process.on('SIGINT', shutdown); process.on('SIGTERM', shutdown);
