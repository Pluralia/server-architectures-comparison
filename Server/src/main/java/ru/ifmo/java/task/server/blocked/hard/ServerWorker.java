package ru.ifmo.java.task.server.blocked.hard;

import ru.ifmo.java.task.Constants;
import ru.ifmo.java.task.Lib;
import ru.ifmo.java.task.protocol.Protocol.Request;
import ru.ifmo.java.task.protocol.Protocol.Response;
import ru.ifmo.java.task.server.ServerStat.*;
import ru.ifmo.java.task.server.ServerStat.ClientStat.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerWorker {
    private final BlockedHardServer blockedHardServer;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    private final ClientStat clientStat;


    private final ExecutorService pool;

    private final ExecutorService outputExecutor = Executors.newSingleThreadExecutor();

    public ServerWorker(BlockedHardServer blockedHardServer, Socket socket, ClientStat clientStat, ExecutorService pool) throws IOException {
        this.blockedHardServer = blockedHardServer;

        this.socket = socket;
        input = socket.getInputStream();
        output = socket.getOutputStream();

        this.clientStat = clientStat;

        this.pool = pool;

        Thread inputThread = new Thread(initInputThread());
        inputThread.setDaemon(true);
        inputThread.start();
    }

    private Runnable initInputThread() {
        return () -> {
            try {
                while (!Thread.interrupted()) {
                    RequestData requestData = clientStat.registerRequest();
                    requestData.startClient = System.currentTimeMillis();

                    pool.submit(initTask(getRequest(), requestData));
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                close();
            }
       };
    }

    private void close() {
        try {
            socket.close();
            outputExecutor.shutdown();
            blockedHardServer.stop();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Runnable initTask(Request request, RequestData requestData) {
        return () -> {
            Response response = processRequest(request, requestData);
            outputExecutor.submit(initOutputExecutor(response, requestData));
        };
    }

    private Runnable initOutputExecutor(Response response, RequestData requestData) {
        return () -> {
            try {
                sendResponse(response, requestData);
            } catch(IOException e) {
                e.printStackTrace();
            } finally {
                close();
            }
        };
    }

    private Request getRequest() throws IOException {
        byte[] protoBuf = Lib.receive(input);
        Request request = Request.parseFrom(protoBuf);

        assert request != null;
        return request;
    }

    private Response processRequest(Request request, RequestData requestData) {
        requestData.startTask = System.currentTimeMillis();
        List<Integer> sortedList = Constants.SORT.apply(request.getElemList());
        requestData.taskTime = System.currentTimeMillis() - requestData.startTask;

        return Response.newBuilder()
                .setSize(request.getSize())
                .addAllElem(sortedList)
                .build();
    }

    private void sendResponse(Response response, RequestData requestData) throws IOException {
        int packageSize = response.getSerializedSize();
        output.write(ByteBuffer.allocate(Constants.INT_SIZE).putInt(packageSize).array());
        response.writeTo(output);

        requestData.clientTime = System.currentTimeMillis() - requestData.startClient;
    }
}
