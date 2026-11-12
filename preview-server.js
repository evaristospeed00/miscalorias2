const { execFile, spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const http2 = require('node:http2');
const os = require('node:os');
const path = require('node:path');

const adb = process.env.MISCALORIAS_PREVIEW_ADB;
const serial = process.env.MISCALORIAS_PREVIEW_SERIAL;
const port = Number(process.env.MISCALORIAS_PREVIEW_PORT || 8765);
const grpcPort = Number(process.env.MISCALORIAS_PREVIEW_GRPC_PORT || 8554);
const grpcToken = process.env.MISCALORIAS_PREVIEW_GRPC_TOKEN;
const screenWidth = Number(process.env.MISCALORIAS_PREVIEW_WIDTH || 720);
const screenHeight = Number(process.env.MISCALORIAS_PREVIEW_HEIGHT || 1600);
const maxPhotoBytes = 20 * 1024 * 1024;
const page = fs.readFileSync(path.join(__dirname, 'preview', 'index.html'));

if (!adb || !serial || !grpcToken) {
  throw new Error('Faltan las variables locales de ADB o gRPC para la vista previa.');
}

function runAdb(args, callback) {
  execFile(adb, ['-s', serial, ...args], { encoding: 'buffer', timeout: 10000, maxBuffer: 16 * 1024 * 1024 }, callback);
}

const grpcPath = '/android.emulation.control.EmulatorController/streamScreenshot';
const frameBoundary = 'miscalorias-frame';
const streamClients = new Set();
let grpcClient;
let screenshotStream;
let screenshotRetry;
let grpcReady = false;
let lastFrame;
let inputShell;

function encodeVarint(value) {
  const bytes = [];
  while (value > 127) {
    bytes.push((value & 127) | 128);
    value = Math.floor(value / 128);
  }
  bytes.push(value);
  return Buffer.from(bytes);
}

function encodeVarintField(field, value) {
  return Buffer.concat([encodeVarint(field * 8), encodeVarint(value)]);
}

function encodeGrpcFrame(message) {
  const header = Buffer.alloc(5);
  header.writeUInt32BE(message.length, 1);
  return Buffer.concat([header, message]);
}

function readVarint(buffer, offset) {
  let value = 0;
  let multiplier = 1;
  for (let index = offset; index < buffer.length; index += 1) {
    const byte = buffer[index];
    value += (byte & 127) * multiplier;
    if ((byte & 128) === 0) return { value, offset: index + 1 };
    multiplier *= 128;
  }
  return null;
}

function getImageBytes(message) {
  let offset = 0;
  while (offset < message.length) {
    const tag = readVarint(message, offset);
    if (!tag) return null;
    offset = tag.offset;
    const field = Math.floor(tag.value / 8);
    const wire = tag.value % 8;
    if (wire === 2) {
      const length = readVarint(message, offset);
      if (!length || length.value > message.length - length.offset) return null;
      offset = length.offset;
      const value = message.subarray(offset, offset + length.value);
      offset += length.value;
      if (field === 4) return value;
    } else if (wire === 0) {
      const value = readVarint(message, offset);
      if (!value) return null;
      offset = value.offset;
    } else if (wire === 1) {
      offset += 8;
    } else if (wire === 5) {
      offset += 4;
    } else {
      return null;
    }
  }
  return null;
}

function writeFrame(response, image) {
  response.write(`--${frameBoundary}\r\nContent-Type: image/png\r\nContent-Length: ${image.length}\r\n\r\n`);
  response.write(image);
  response.write('\r\n');
}

function publishFrame(image) {
  lastFrame = Buffer.from(image);
  grpcReady = true;
  for (const response of streamClients) {
    if (response.destroyed) {
      streamClients.delete(response);
    } else if (response.writableLength < 512 * 1024) {
      writeFrame(response, lastFrame);
    }
  }
}

function scheduleScreenshotRetry() {
  if (screenshotRetry || server.listening === false) return;
  screenshotRetry = setTimeout(() => {
    screenshotRetry = null;
    startScreenshotStream();
  }, 1000);
}

function startScreenshotStream() {
  if (screenshotStream) return;
  if (!grpcClient || grpcClient.destroyed || grpcClient.closed) {
    grpcClient = http2.connect(`http://127.0.0.1:${grpcPort}`);
    grpcClient.on('error', error => {
      grpcReady = false;
      console.error(`Emulator gRPC connection failed: ${error.message}`);
    });
    grpcClient.on('close', () => {
      grpcReady = false;
      grpcClient = null;
      screenshotStream = null;
      scheduleScreenshotRetry();
    });
  }

  const stream = grpcClient.request({
    ':method': 'POST',
    ':path': grpcPath,
    'content-type': 'application/grpc',
    'te': 'trailers',
    'authorization': `Bearer ${grpcToken}`
  });
  screenshotStream = stream;
  let buffer = Buffer.alloc(0);

  stream.on('response', headers => {
    if (headers[':status'] !== 200) {
      grpcReady = false;
      console.error(`Emulator gRPC returned HTTP ${headers[':status']}.`);
    }
  });
  stream.on('data', chunk => {
    buffer = buffer.length ? Buffer.concat([buffer, chunk]) : chunk;
    while (buffer.length >= 5) {
      const messageLength = buffer.readUInt32BE(1);
      if (messageLength > 32 * 1024 * 1024) {
        stream.close();
        console.error('Emulator gRPC returned an oversized screen frame.');
        return;
      }
      if (buffer.length < 5 + messageLength) break;
      if (buffer[0] === 0) {
        const image = getImageBytes(buffer.subarray(5, 5 + messageLength));
        if (image && image.length >= 8 && image.subarray(0, 8).toString('hex') === '89504e470d0a1a0a') {
          publishFrame(image);
        }
      }
      buffer = buffer.subarray(5 + messageLength);
    }
  });
  stream.on('trailers', headers => {
    if (headers['grpc-status'] !== '0') {
      grpcReady = false;
      console.error(`Emulator gRPC stream failed with status ${headers['grpc-status'] || 'unknown'}.`);
    }
  });
  stream.on('error', error => {
    grpcReady = false;
    if (screenshotStream === stream) screenshotStream = null;
    console.error(`Emulator screen stream failed: ${error.message}`);
    scheduleScreenshotRetry();
  });
  stream.on('end', () => {
    grpcReady = false;
    if (screenshotStream === stream) screenshotStream = null;
    scheduleScreenshotRetry();
  });

  const imageFormat = Buffer.concat([
    encodeVarintField(1, 0),
    encodeVarintField(3, screenWidth),
    encodeVarintField(4, screenHeight)
  ]);
  stream.end(encodeGrpcFrame(imageFormat));
}

function getInputShell() {
  if (inputShell && inputShell.exitCode === null && !inputShell.killed) return inputShell;
  const shell = spawn(adb, ['-s', serial, 'shell'], {
    windowsHide: true,
    stdio: ['pipe', 'ignore', 'pipe']
  });
  inputShell = shell;
  shell.stderr.on('data', chunk => {
    console.error(`Android input error: ${chunk.toString().trim()}`);
  });
  shell.on('error', error => {
    if (inputShell === shell) inputShell = null;
    console.error(`ADB input shell failed: ${error.message}`);
  });
  shell.on('close', code => {
    if (inputShell === shell) inputShell = null;
    if (code !== 0) console.error(`ADB input shell exited with code ${code}.`);
  });
  return shell;
}

function sendTouch(response, input) {
  const shell = getInputShell();
  const command = `input motionevent ${input.phase.toUpperCase()} ${input.x} ${input.y}\n`;
  shell.stdin.write(command, error => {
    if (response.destroyed) return;
    if (error) {
      sendJson(response, 503, { error: 'No se pudo enviar el movimiento al emulador.' });
      return;
    }
    sendJson(response, 200, { ok: true });
  });
}

function sendJson(response, status, value) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Cross-Origin-Resource-Policy': 'same-origin',
    'X-Content-Type-Options': 'nosniff'
  });
  response.end(JSON.stringify(value));
}

function readBody(request, callback) {
  let body = '';
  request.on('data', chunk => {
    body += chunk;
    if (body.length > 4096) request.destroy();
  });
  request.on('end', () => {
    try {
      callback(JSON.parse(body));
    } catch {
      callback(null);
    }
  });
}

function coordinate(value) {
  return Number.isInteger(value) && value >= 0 && value <= 10000;
}

function imageExtension(image) {
  if (image.length >= 3 && image[0] === 0xff && image[1] === 0xd8 && image[2] === 0xff) return 'jpg';
  if (image.length >= 8 && image.subarray(0, 8).toString('hex') === '89504e470d0a1a0a') return 'png';
  if (image.length >= 12 && image.toString('ascii', 0, 4) === 'RIFF' && image.toString('ascii', 8, 12) === 'WEBP') return 'webp';
  if (image.length >= 6 && ['GIF87a', 'GIF89a'].includes(image.toString('ascii', 0, 6))) return 'gif';
  return null;
}

function runAdbAsync(args) {
  return new Promise((resolve, reject) => {
    execFile(adb, ['-s', serial, ...args], {
      encoding: 'utf8',
      timeout: 60000,
      maxBuffer: 1024 * 1024,
      windowsHide: true
    }, (error, stdout, stderr) => {
      if (error) {
        reject(new Error(stderr.trim() || error.message));
        return;
      }
      resolve(stdout);
    });
  });
}

function receivePhoto(request, response) {
  const contentLength = Number(request.headers['content-length']);
  if (Number.isFinite(contentLength) && contentLength > maxPhotoBytes) {
    request.resume();
    sendJson(response, 413, { error: 'La foto supera el límite de 20 MB.' });
    return;
  }

  const chunks = [];
  let size = 0;
  let tooLarge = false;
  request.on('data', chunk => {
    size += chunk.length;
    if (size > maxPhotoBytes) {
      tooLarge = true;
      chunks.length = 0;
    } else if (!tooLarge) {
      chunks.push(chunk);
    }
  });
  request.on('end', async () => {
    if (tooLarge) {
      sendJson(response, 413, { error: 'La foto supera el límite de 20 MB.' });
      return;
    }
    const image = Buffer.concat(chunks, size);
    const extension = imageExtension(image);
    if (!extension) {
      sendJson(response, 415, { error: 'Elige una imagen JPG, PNG, WebP o GIF.' });
      return;
    }

    let tempDirectory;
    try {
      tempDirectory = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'miscalorias-preview-'));
      const filename = `miscalorias-${Date.now()}-${crypto.randomBytes(4).toString('hex')}.${extension}`;
      const localPath = path.join(tempDirectory, filename);
      const remotePath = `/sdcard/Pictures/Miscalorias/${filename}`;
      await fs.promises.writeFile(localPath, image);
      await runAdbAsync(['shell', 'mkdir', '-p', '/sdcard/Pictures/Miscalorias']);
      await runAdbAsync(['push', localPath, remotePath]);
      await runAdbAsync([
        'shell', 'am', 'broadcast',
        '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE',
        '-d', `file://${remotePath}`
      ]);
      sendJson(response, 200, { ok: true, filename });
    } catch (error) {
      console.error(`PC photo import failed: ${error.message}`);
      sendJson(response, 503, { error: 'No se pudo transferir la foto al emulador.' });
    } finally {
      if (tempDirectory) {
        try {
          await fs.promises.rm(tempDirectory, { recursive: true, force: true });
        } catch (error) {
          console.error(`Temporary photo cleanup failed: ${error.message}`);
        }
      }
    }
  });
  request.on('error', error => {
    console.error(`PC photo upload failed: ${error.message}`);
  });
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, `http://127.0.0.1:${port}`);
  if (request.method === 'GET' && url.pathname === '/') {
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' blob:; frame-ancestors 'none'",
      'Cross-Origin-Resource-Policy': 'same-origin',
      'X-Frame-Options': 'DENY',
      'X-Content-Type-Options': 'nosniff'
    });
    response.end(page);
    return;
  }
  if (request.method === 'GET' && url.pathname === '/health') {
    sendJson(response, 200, { ok: true, serial, streamReady: grpcReady });
    return;
  }
  if (request.method === 'GET' && url.pathname === '/stream') {
    response.writeHead(200, {
      'Content-Type': `multipart/x-mixed-replace; boundary=${frameBoundary}`,
      'Cache-Control': 'no-store',
      'Cross-Origin-Resource-Policy': 'same-origin',
      'X-Content-Type-Options': 'nosniff'
    });
    response.flushHeaders();
    streamClients.add(response);
    response.on('close', () => streamClients.delete(response));
    if (lastFrame) writeFrame(response, lastFrame);
    startScreenshotStream();
    return;
  }
  if (request.method === 'GET' && url.pathname === '/screen.png') {
    runAdb(['exec-out', 'screencap', '-p'], (error, image, stderr) => {
      if (error) {
        sendJson(response, 503, { error: String(stderr || error.message).trim() });
        return;
      }
      response.writeHead(200, {
        'Content-Type': 'image/png',
        'Cache-Control': 'no-store',
        'Cross-Origin-Resource-Policy': 'same-origin',
        'X-Content-Type-Options': 'nosniff'
      });
      response.end(image);
    });
    return;
  }
  if (request.method === 'POST' && url.pathname === '/upload') {
    receivePhoto(request, response);
    return;
  }
  if (request.method === 'POST' && url.pathname === '/input') {
    readBody(request, input => {
      if (!input || typeof input.type !== 'string') {
        sendJson(response, 400, { error: 'Entrada inválida.' });
        return;
      }
      if (
        input.type === 'touch' &&
        ['down', 'move', 'up'].includes(input.phase) &&
        Number.isInteger(input.x) && input.x >= 0 && input.x < screenWidth &&
        Number.isInteger(input.y) && input.y >= 0 && input.y < screenHeight
      ) {
        sendTouch(response, input);
        return;
      }
      let args;
      if (input.type === 'tap' && coordinate(input.x) && coordinate(input.y)) {
        args = ['shell', 'input', 'tap', String(input.x), String(input.y)];
      } else if (
        input.type === 'wheel' &&
        coordinate(input.x) && input.x < screenWidth &&
        coordinate(input.y) && input.y < screenHeight &&
        Number.isInteger(input.delta) && input.delta >= -5 && input.delta <= 5 && input.delta !== 0
      ) {
        args = ['shell', 'input', 'mouse', 'scroll', String(input.x), String(input.y), '--axis', `VSCROLL,${input.delta}`];
      } else if (
        input.type === 'swipe' &&
        coordinate(input.x1) && coordinate(input.y1) &&
        coordinate(input.x2) && coordinate(input.y2)
      ) {
        args = ['shell', 'input', 'swipe', String(input.x1), String(input.y1), String(input.x2), String(input.y2), '300'];
      } else if (input.type === 'key' && [3, 4, 62, 66, 67].includes(input.code)) {
        args = ['shell', 'input', 'keyevent', String(input.code)];
      } else if (input.type === 'text' && typeof input.text === 'string') {
        const text = input.text.normalize('NFD')
          .replace(/\p{Diacritic}/gu, '')
          .replace(/ /g, '%s')
          .replace(/[^a-zA-Z0-9%._@-]/g, '');
        if (!text) {
          sendJson(response, 400, { error: 'Texto no compatible.' });
          return;
        }
        args = ['shell', 'input', 'text', text];
      } else {
        sendJson(response, 400, { error: 'Acción no permitida.' });
        return;
      }
      runAdb(args, error => {
        if (error) {
          sendJson(response, 503, { error: 'No se pudo enviar la acción a ADB.' });
          return;
        }
        sendJson(response, 200, { ok: true });
      });
    });
    return;
  }
  response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  response.end('No encontrado');
});

server.listen(port, '127.0.0.1', () => {
  console.log(`MISCALORIAS preview listening on http://localhost:${port} (${serial})`);
  startScreenshotStream();
});

server.on('error', error => {
  console.error(error.message);
  process.exitCode = 1;
});
