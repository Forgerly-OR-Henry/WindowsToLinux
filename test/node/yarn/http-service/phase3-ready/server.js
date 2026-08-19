const http = require('http');
const status = 200;
http.createServer((request, response) => {
  response.writeHead(status, {'content-type': 'text/plain'});
  response.end('phase3-live-ok');
}).listen(Number(process.env.PORT), '0.0.0.0');
