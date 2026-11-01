import { createApp } from './app.js';
import { config } from './config.js';
import { attachWebSocketProxy } from './wsproxy.js';

const server = createApp().listen(config.port, config.host, () => {
  console.log(JSON.stringify({
    level: 'info',
    msg: 'Gateway listening',
    url: `http://${config.host}:${config.port}`,
    issuer: config.keycloakIssuer,
    corsOrigin: config.corsOrigin,
  }));
});

// The one WebSocket route (/ws): the token is checked on the upgrade, then the socket is piped to Booking & Seat.
attachWebSocketProxy(server);
