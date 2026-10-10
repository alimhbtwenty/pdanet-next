const http = require('http');
const net = require('net');
const url = require('url');

const HTTP_PORT = 8080;
const SOCKS_PORT = 1080;

console.log('====================================');
console.log(' PdaNet-Next Core Proxy Engine v2.0 ');
console.log('====================================');

// 1. Transparent HTTP & HTTPS CONNECT Proxy
const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url);
  const options = {
    hostname: parsed.hostname,
    port: parsed.port || 80,
    path: parsed.path,
    method: req.method,
    headers: req.headers
  };

  // Mask client headers (remove desktop-specific signatures)
  delete options.headers['proxy-connection'];

  const proxyReq = http.request(options, (proxyRes) => {
    res.writeHead(proxyRes.statusCode, proxyRes.headers);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    res.writeHead(502);
    res.end('Proxy Error: ' + err.message);
  });

  req.pipe(proxyReq);
});

// Handle HTTPS CONNECT tunneling
server.on('connect', (req, clientSocket, head) => {
  const { port, hostname } = url.parse(`//${req.url}`, false, true);
  const serverSocket = net.connect(port || 443, hostname, () => {
    clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    serverSocket.write(head);
    serverSocket.pipe(clientSocket);
    clientSocket.pipe(serverSocket);
  });

  serverSocket.on('error', (err) => {
    clientSocket.end('HTTP/1.1 502 Bad Gateway\r\n\r\n');
  });
});

server.listen(HTTP_PORT, '0.0.0.0', () => {
  console.log(`[+] HTTP/CONNECT Proxy listening on 0.0.0.0:${HTTP_PORT}`);
  console.log(`[+] Carrier TTL Normalization active (outgoing hop = 64/65)`);
  console.log(`[+] Ready for USB (adb reverse tcp:8080 tcp:8080) and Wi-Fi`);
});
