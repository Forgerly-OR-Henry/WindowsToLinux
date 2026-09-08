declare function require(name: string): any;
declare const process: { env: Record<string, string | undefined> };

const http = require("node:http");
const status: number = 200;
const marker: string = "typescript-live-ok";
const port = Number(process.env.PORT);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");

http.createServer((_request: unknown, response: { writeHead(code: number): void; end(body: string): void }) => {
  response.writeHead(status);
  response.end(marker);
}).listen(port, "0.0.0.0");
