package com.uddernetworks.holysheet.socket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.uddernetworks.holysheet.DocStore;
import com.uddernetworks.holysheet.socket.payload.BasicPayload;
import com.uddernetworks.holysheet.socket.payload.ErrorPayload;
import com.uddernetworks.holysheet.socket.payload.ListRequest;
import com.uddernetworks.holysheet.socket.payload.ListResponse;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public class SocketCommunication {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketCommunication.class);

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(PayloadType.class, new PayloadTypeAdapter())
            .create();

    private static final int PORT = 4567;

    private final DocStore docStore;
    private ServerSocket serverSocket;

    private List<BiConsumer<Socket, String>> receivers = Collections.synchronizedList(new ArrayList<>());

    public SocketCommunication(DocStore docStore) {
        this.docStore = docStore;
    }

    public void start() {
        LOGGER.info("Starting payload on port {}...", PORT);

        try {
            serverSocket = new ServerSocket(PORT);

            while (true) {
                var socket = serverSocket.accept();
                CompletableFuture.runAsync(() -> {
                    try {
                        LOGGER.info("Got client");

                        var in = new Scanner(socket.getInputStream());

                        for (String line; (line = in.nextLine()) != null; ) {
                            final var input = line;
                            receivers.forEach(consumer -> consumer.accept(socket, input));
                            handleRequest(socket, input);
                        }
                    } catch (IOException e) {
                        LOGGER.error("An error occurred while reading/writing to socket", e);
                    }
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOGGER.error("Error closing payload", e);
            }
        }));
    }

    private void handleRequest(Socket socket, String input) {
        CompletableFuture.runAsync(() -> {
            var basicPayload = GSON.fromJson(input, BasicPayload.class);
            try {
                if (basicPayload.getCode() < 1) {
                    LOGGER.error("Unsuccessful request with code {}: {}\nJson: {}", basicPayload.getCode(), basicPayload.getMessage(), input);
                    return;
                }

                var type = basicPayload.getType();
                var state = basicPayload.getState();

                if (!type.isReceivable()) {
                    LOGGER.error("Received unreceivable payload type: {}", type.name());
                    sendData(socket, GSON.toJson(new ErrorPayload("Received unreceivable payload type: " + type.name(), state, ExceptionUtils.getStackTrace(new RuntimeException()))));
                    return;
                }

                switch (type) {
                    case LIST_REQUEST:
                        var listRequest = GSON.fromJson(input, ListRequest.class);

                        LOGGER.info("Got list request. Query: {}", listRequest.getQuery());

                        var listResponse = new ListResponse(1, "Success", state, Collections.singletonList(
                                new ListResponse.ListItem("test.txt", 123, 6, System.currentTimeMillis(), "abcdefg")));
                        sendData(socket, listResponse);
                        break;
                    default:
                        LOGGER.error("Unsupported type: {}", basicPayload.getType().name());
                        break;
                }
            } catch (Exception e) { // Catching Exception only for error reporting back to GUI/Other client
                LOGGER.error("Exception while parsing client received data", e);
                sendData(socket, GSON.toJson(new ErrorPayload(e.getMessage(), basicPayload.getState(), ExceptionUtils.getStackTrace(e))));
            }
        }).exceptionally(t -> {
            LOGGER.error("Exception while parsing client received data", t);
            return null;
        });
    }

    public void addReceiver(BiConsumer<Socket, String> receiver) {
        receivers.add(receiver);
    }

    public static void sendDataAsync(Socket socket, String data) {
        CompletableFuture.runAsync(() -> sendData(socket, data));
    }

    public static void sendData(Socket socket, Object object) {
        sendData(socket, GSON.toJson(object));
    }

    public static void sendData(Socket socket, String data) {
        try {
            var out = socket.getOutputStream();
            out.write(data.getBytes());
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
