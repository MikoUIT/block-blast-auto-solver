#!/usr/bin/env node
/**
 * Block Blast Solver — local report receiver.
 *
 * The app POSTs a JSON report (device info, logs, base64 snapshots) here; this writes it out as
 * plain files under reports/<timestamp>/ so the developer can read them straight off disk. No token,
 * no internet, no GitHub limits — the phone just needs to reach this PC on the same Wi-Fi.
 *
 *   node tools/report-server.js                 # listens on 0.0.0.0:8791
 *   node tools/report-server.js --port 9000
 *
 * JSON body shape:
 *   { note, deviceInfo, log, snapshots: [ { label, plainJpeg, markedJpeg } ] }
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const args = process.argv.slice(2);
const portArg = args.indexOf('--port');
// 8791 by default: 8787/8789 are taken by a local dev proxy on this machine (it binds 0.0.0.0 and
// swallows loopback requests). 8788 hosts the APK server.
const PORT = portArg >= 0 ? Number(args[portArg + 1]) : 8791;

const ROOT = path.resolve(__dirname, '..');
const OUT_DIR = path.join(ROOT, 'reports');

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

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

/** Writes one report into its own folder and returns the relative path. */
function writeReport(body) {
  const folderName = stamp();
  const folder = path.join(OUT_DIR, folderName);
  fs.mkdirSync(folder, { recursive: true });

  // Markdown report for quick reading.
  const md = [
    `# Отчёт ${folderName}`,
    '',
    body.note ? `## Комментарий\n${body.note}\n` : '',
    '## Окружение',
    body.deviceInfo || '(нет)',
    '',
    `## Снимки (${(body.snapshots || []).length})`,
    (body.snapshots || []).map((s, i) => {
      const views = s.views || [];
      const imgs = views.map((v) =>
        `**${v.name}**\n\n![${v.name}${i}](./snapshots/${i + 1}-${v.name}.jpg)\n`
      ).join('\n');
      return `### ${i + 1}. ${s.label}\n\n${imgs}`;
    }).join('\n'),
    '',
    '## Логи',
    '```',
    body.log || '(пусто)',
    '```',
    '',
  ].join('\n');
  fs.writeFileSync(path.join(folder, 'report.md'), md, 'utf8');

  // Also dump the raw JSON so nothing is lost in the markdown rendering.
  fs.writeFileSync(
    path.join(folder, 'report.json'),
    JSON.stringify({ note: body.note, deviceInfo: body.deviceInfo, log: body.log }, null, 2),
    'utf8',
  );

  // Snapshots as real JPEGs — one file per view, named after the view (colour, grey, binary, ...).
  const snaps = body.snapshots || [];
  if (snaps.length) {
    const snapDir = path.join(folder, 'snapshots');
    fs.mkdirSync(snapDir, { recursive: true });
    snaps.forEach((s, i) => {
      for (const v of s.views || []) {
        if (!v.jpeg) continue;
        const safe = String(v.name || 'view').replace(/[^a-z0-9_-]/gi, '_');
        fs.writeFileSync(path.join(snapDir, `${i + 1}-${safe}.jpg`), Buffer.from(v.jpeg, 'base64'));
      }
    });
  }

  return path.relative(ROOT, folder).replace(/\\/g, '/');
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      // Hard ceiling so a runaway client cannot exhaust memory.
      if (size > 80 * 1024 * 1024) {
        reject(new Error('payload too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (req.method === 'POST' && url.pathname === '/report') {
    try {
      const raw = await readBody(req);
      const body = JSON.parse(raw);
      const folder = writeReport(body);
      const snaps = (body.snapshots || []).length;
      console.log(`[${new Date().toLocaleTimeString()}] отчёт сохранён: ${folder} (снимков ${snaps})`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, folder }));
    } catch (t) {
      console.error('ошибка приёма отчёта:', t.message);
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: t.message }));
    }
    return;
  }

  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Block Blast Solver — приёмник отчётов. POST /report');
    return;
  }

  res.writeHead(404);
  res.end('not found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('Block Blast Solver — приёмник отчётов запущен');
  console.log(`  папка: ${OUT_DIR}`);
  console.log('  адрес для приложения (захардкожен в LocalServerSender):');
  for (const ip of localAddresses()) console.log(`    http://${ip}:${PORT}/report`);
  console.log('Остановить: Ctrl+C');
});
