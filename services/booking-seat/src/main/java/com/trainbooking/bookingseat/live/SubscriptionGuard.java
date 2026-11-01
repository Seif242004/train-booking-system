package com.trainbooking.bookingseat.live;

import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * What a connected client may do: subscribe to the events of one trip ({@code /topic/trips/{id}}) and to its own queue
 * ({@code /user/queue/holds}). It may not send anything: this connection only pushes events from the server to the browser,
 * so a SEND frame, or a subscription to any other destination, is an error and closes the connection.
 */
public class SubscriptionGuard implements ChannelInterceptor {

    private static final Pattern TRIP_TOPIC = Pattern.compile("/topic/trips/\\d{1,18}");
    private static final String USER_QUEUE = "/user/queue/holds";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.SEND == command) {
            throw new MessagingException("Clients cannot send messages on this connection");
        }
        if (StompCommand.SUBSCRIBE == command) {
            String destination = accessor.getDestination();
            if (destination == null || !(TRIP_TOPIC.matcher(destination).matches() || USER_QUEUE.equals(destination))) {
                throw new MessagingException("This destination cannot be subscribed to");
            }
        }
        return message;
    }
}
