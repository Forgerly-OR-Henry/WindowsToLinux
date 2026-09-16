const { createServer } = require('node:http');
const { loadConfiguration } = require('./src/configuration');
const { createHandler } = require('./src/router');
const configuration = loadConfiguration();
createServer(createHandler(configuration)).listen(configuration.port, '0.0.0.0');
