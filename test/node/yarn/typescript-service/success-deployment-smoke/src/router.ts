import { IncomingMessage, ServerResponse } from 'node:http';
import { Configuration } from './configuration';
import { ZodError } from 'zod';
import { summarize } from './service';
export function createHandler(configuration: Configuration) {
  return (request: IncomingMessage, response: ServerResponse) => {
    const url = new URL(request.url ?? '/', 'http://localhost');
    let status = configuration.status;
    let type = 'text/plain; charset=utf-8';
    let body = configuration.label;
    if (status !== 503 && (url.pathname === '/api/summary' || configuration.mode === 'json')) {
      try {
        body = JSON.stringify(summarize(url.pathname === '/api/summary' ? url.searchParams.get('values') : null));
        type = 'application/json; charset=utf-8';
      } catch (error) {
        if (!(error instanceof ZodError)) throw error;
        status = 400; body = 'invalid-values';
      }
    }
    response.writeHead(status, {'content-type': type, 'content-length': Buffer.byteLength(body)});
    response.end(body);
  };
}
