import { createServer } from 'node:http';
import { loadConfiguration } from './configuration';
import { createHandler } from './router';
const configuration = loadConfiguration();
createServer(createHandler(configuration)).listen(configuration.port, '0.0.0.0');
