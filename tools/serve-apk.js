#!/usr/bin/env node
/**
 * Block Blast Solver — tiny APK delivery server.
 *
 * Serves the newest debug APK plus a one-tap install page, so a phone on the same Wi-Fi can update
 * without Telegram, cables, or adb. Zero dependencies, matching how LO likes his tools.
 *
 *   node tools/serve-apk.js            # serves on 0.0.0.0:8788
 *   node tools/serve-apk.js --port 9000
 *
 * Drop new builds into the served folder (publish.bat does it) and refresh the page on the phone.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const args = process.argv.slice(2);
const portArg = args.indexOf('--port');
// 8788 by default: 8787 is commonly taken by a local dev proxy on this machine.
const PORT = portArg >= 0 ? Number(args[portArg + 1]) : 8788;

const ROOT = path.resolve(__dirname, '..');
const DIST = path.join(ROOT, 'dist');

function localAddresses() {
  const out = [];
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) out.push(net.address);
    }
  }
  return out;
}

function newestApk() {
  if (!fs.existsSync(DIST)) return null;
  const apks = fs.readdirSync(DIST)
    .filter((f) => f.toLowerCase().endsWith('.apk'))
    .map((f) => ({ name: f, mtime: fs.statSync(path.join(DIST, f)).mtimeMs, size: fs.statSync(path.join(DIST, f)).size }))
    .sort((a, b) => b.mtime - a.mtime);
  return apks[0] || null;
}

function installPage(host, apk) {
  const version = apk ? new Date(apk.mtime).toLocaleString('ru-RU') : '—';
  const sizeMb = apk ? (apk.size / 1048576).toFixed(1) : '—';
  const apkName = apk ? apk.name : 'app-debug.apk';
  return `<!doctype html>
<html lang="ru"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Block Blast Solver — установка</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, sans-serif; background: #1B1B1F; color: #fff;
         display: flex; min-height: 100vh; align-items: center; justify-content: center; padding: 24px; }
  .card { background: #26262B; border-radius: 18px; padding: 28px; max-width: 460px; width: 100%;
          box-shadow: 0 20px 60px rgba(0,0,0,.4); }
  h1 { margin: 0 0 4px; font-size: 22px; }
  .sub { color: #9a9aa5; font-size: 13px; margin-bottom: 24px; }
  .meta { display: flex; gap: 16px; font-size: 13px; color: #b6b6c0; margin-bottom: 24px; }
  .meta b { color: #fff; display: block; font-size: 15px; }
  a.install { display: block; text-align: center; background: #7C5CFF; color: #fff; text-decoration: none;
              padding: 16px; border-radius: 12px; font-size: 17px; font-weight: 600; }
  a.install:active { background: #6a4de0; }
  .hint { margin-top: 18px; font-size: 12px; color: #7d7d88; line-height: 1.5; }
  .warn { margin-top: 14px; padding: 12px; border-radius: 10px; background: #3a2b12; color: #ffcf7a; font-size: 12px; }
</style></head>
<body>
  <div class="card">
    <h1>Block Blast Solver</h1>
    <div class="sub">Свежая debug-сборка для установки</div>
    <div class="meta">
      <div><b>${version}</b>собрано</div>
      <div><b>${sizeMb} МБ</b>размер</div>
    </div>
    <a class="install" href="/apk/${encodeURIComponent(apkName)}">Скачать и установить</a>
    <div class="hint">
      Если уже стоит предыдущая версия — установщик предложит обновление, данные сохранятся.
      Нужно разрешить установку из этого источника в настройках браузера.
    </div>
    <div class="warn">Обновление заменит подпись, если APK пересобран. Если телефон ругается на конфликт подписи —
      удали старое приложение и поставь заново.</div>
    <div class="hint" style="margin-top:10px">Адрес: http://${host}:${PORT}</div>
  </div>
</body></html>`;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const host = req.headers.host?.split(':')[0] || 'localhost';

  if (url.pathname === '/' || url.pathname === '/index.html') {
    const apk = newestApk();
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(installPage(host, apk));
    return;
  }

  if (url.pathname.startsWith('/apk/')) {
    const name = decodeURIComponent(url.pathname.slice('/apk/'.length));
    const file = path.join(DIST, path.basename(name));
    if (!fs.existsSync(file)) {
      res.writeHead(404); res.end('APK not found'); return;
    }
    const stat = fs.statSync(file);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': stat.size,
      'Content-Disposition': `attachment; filename="${path.basename(file)}"`,
      'Cache-Control': 'no-store',
    });
    fs.createReadStream(file).pipe(res);
    return;
  }

  if (url.pathname === '/version.json') {
    const apk = newestApk();
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(apk ? {
      name: apk.name,
      mtime: new Date(apk.mtime).toISOString(),
      size: apk.size,
    } : {}));
    return;
  }

  res.writeHead(404); res.end('Not found');
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = localAddresses();
  console.log('Block Blast Solver — раздача APK запущена');
  console.log(`  папка:   ${DIST}`);
  const apk = newestApk();
  console.log(apk ? `  APK:     ${apk.name} (${(apk.size / 1048576).toFixed(1)} МБ)` : '  APK:     пока нет — собери через publish.bat');
  console.log('  адреса для телефона:');
  for (const ip of ips) console.log(`    http://${ip}:${PORT}`);
  console.log('Остановить: Ctrl+C');
});
