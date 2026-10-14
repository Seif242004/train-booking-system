import { createApp } from './app.js';
import { config } from './config.js';

createApp().listen(config.port, config.host, () => {
  console.log(JSON.stringify({
    level: 'info',
    msg: 'Gateway listening',
    url: `http://${config.host}:${config.port}`,
    issuer: config.keycloakIssuer,
    corsOrigin: config.corsOrigin,
  }));
});
