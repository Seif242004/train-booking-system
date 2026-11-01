import { Client, type StompSubscription } from '@stomp/stompjs';
import { getFreshToken } from '../auth/keycloak';
import { config } from '../config';
import type { SeatEvent, UserEvent } from './liveTypes';

export interface LiveHandlers {
  /** A change of the availability of the trip. */
  onSeatEvent?: (event: SeatEvent) => void;
  /** An event for this user only (the hold ended). */
  onUserEvent?: (event: UserEvent) => void;
  /** The connection is open. {@code again} is true after a reconnect: events may have been missed, so reload everything (FR-R4). */
  onConnected?: (again: boolean) => void;
  onDisconnected?: () => void;
}

/** ws://host/ws for the Gateway (the WebSocket is a separate connection from the HTTP calls). */
const WS_URL = `${config.gatewayUrl.replace(/^http/, 'ws')}/ws`;

/**
 * Opens the live connection for a trip through the Gateway (STOMP over WebSocket) and returns a function that closes it.
 * The token goes in a WebSocket subprotocol, never in the URL (the URL ends up in logs); a fresh token is fetched for every
 * (re)connection. If the connection drops, it reconnects by itself and reports it, so the page can load the full state again.
 */
export function connectLive(tripId: number, handlers: LiveHandlers): () => void {
  let token = '';
  let connectedBefore = false;
  const subscriptions: StompSubscription[] = [];

  const client = new Client({
    webSocketFactory: () => new WebSocket(WS_URL, ['v12.stomp', `access_token.${token}`]),
    beforeConnect: async () => {
      token = await getFreshToken();
    },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      subscriptions.push(client.subscribe(`/topic/trips/${tripId}`, (message) => handlers.onSeatEvent?.(JSON.parse(message.body) as SeatEvent)));
      subscriptions.push(client.subscribe('/user/queue/holds', (message) => handlers.onUserEvent?.(JSON.parse(message.body) as UserEvent)));
      handlers.onConnected?.(connectedBefore);
      connectedBefore = true;
    },
    onWebSocketClose: () => {
      subscriptions.length = 0;
      handlers.onDisconnected?.();
    },
  });
  client.activate();
  return () => void client.deactivate();
}
