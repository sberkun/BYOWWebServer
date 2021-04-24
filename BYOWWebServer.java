package byow.Networking;

import edu.princeton.cs.introcs.StdDraw;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Queue;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/*
Most of the websockets logic is from the following MDN page:
https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_a_WebSocket_server_in_Java
*/

public class BYOWWebServer {
    private static final String CANVAS_FILE = ".server_canvas.png";
    private static final Path CANVAS_PATH =
            Paths.get(System.getProperty("user.dir"), CANVAS_FILE);
    private int port;
    private ServerSocket server;
    private InputStream in;
    private OutputStream out;
    private Queue<Character> inputs;
    private boolean readyForFrame; //because ngrok can't keep up sometimes

    public BYOWWebServer(int port) throws IOException {
        setupServer(port);
    }

    public boolean clientHasKeyTyped() {
        getClientInputs();
        return inputs.size() > 0;
    }

    public char clientNextKeyTyped() {
        getClientInputs();
        return inputs.poll();
    }

    public void sendCanvas() {
        getClientInputs();
        if (!readyForFrame) {
            return;
        }
        try {
            StdDraw.save(CANVAS_FILE);
            byte[] contents = Files.readAllBytes(CANVAS_PATH);
            sendOutput(contents, true);
            readyForFrame = false;
        } catch (IOException ex) {
            System.out.println("IO EXCEPTION CAUGHT: " + ex.getMessage());
            restartServer();
        }
    }
    public void stopConnection() {
        System.out.println("Stopping connection");
        try {
            in.close();
            out.close();
            server.close();
        } catch (IOException ex) {
            System.out.println("IO EXCEPTION CAUGHT WHEN STOPPING: " + ex.getMessage());
        }
    }

    private void getClientInputs() {
        try {
            while (in.available() > 0) {
                String nextInput = readInput();
                if (nextInput.length() == 1) {
                    inputs.add(nextInput.charAt(0));
                } else if (nextInput.equals("READY")) {
                    readyForFrame = true;
                } else {
                    throw new IllegalArgumentException(
                            "Unexpected message from websocket: " + nextInput);
                }
            }
        } catch (IOException ex) {
            System.out.println("IO EXCEPTION CAUGHT " + ex.getMessage());
            restartServer();
        }
    }

    private void setupServer(int p) throws IOException {
        port = p;
        server = new ServerSocket(port);
        System.out.println("Server has started on port " + port
                + "\r\nWaiting for a connection...");
        Socket client = server.accept();
        System.out.println("A client connected.");
        in = client.getInputStream();
        out = client.getOutputStream();
        doWebsocketHandshake();
        inputs = new ArrayDeque<>();
        readyForFrame = true;
    }

    private void restartServer() {
        stopConnection();
        try {
            setupServer(port);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not restart server: " + ex.getMessage());
        }
    }

    private void doWebsocketHandshake() throws IOException {
        Scanner s = new Scanner(in, StandardCharsets.UTF_8);
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("SHA-1 not supported :(");
        }
        String data = s.useDelimiter("\\r\\n\\r\\n").next();
        Matcher get = Pattern.compile("^GET").matcher(data);
        Matcher match = Pattern.compile("Sec-Web[Ss]ocket-Key: (.*)").matcher(data);
        if (get.find() && match.find()) {
            String magic = match.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Sec-WebSocket-Accept: "
                    + Base64.getEncoder().encodeToString(
                    sha1.digest(magic.getBytes(StandardCharsets.UTF_8)))
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            out.write(response, 0, response.length);
        } else {
            throw new IllegalArgumentException(
                    "Received message other than websocket handshake:\n" + data);
        }
    }

    private String readInput() throws IOException {
        //https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_WebSocket_servers
        //looking at "exchanging data frames"
        //I'm a retard so I'm hardcoding this to only do short text input for now
        int opcode = in.read(); //actually contains fin + rsv + opcode
        if (opcode != 129) {
            throw new IOException("unexpected opcode: " + opcode);
        }
        int payloadLen = in.read() - 128; //minus 128 because mask is always 1
        if (payloadLen < 0 || payloadLen >= 126) {
            throw new IOException("long payload: " + payloadLen);
        }
        byte[] key = {(byte) in.read(), (byte) in.read(), (byte) in.read(), (byte) in.read()};
        byte[] decoded = new byte[payloadLen];
        for (int a = 0; a < payloadLen; a++) {
            decoded[a] = (byte) (in.read() ^ key[a & 0x3]);
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private void sendOutput(String data) throws IOException {
        sendOutput(data.getBytes(StandardCharsets.UTF_8), false);
    }

    private void sendOutput(byte[] data, boolean rawData) throws IOException {
        //https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_WebSocket_servers
        //sending it over as a blob
        byte[] frameStart = new byte[10];
        frameStart[0] = (byte) (rawData ? 130 : 129); //FIN + OPCODE
        int lenFS; //number of bytes in start of frame
        if (data.length >= (1 << 16)) {
            lenFS = 10;
            frameStart[1] = (byte) 127;
            for (int a = 0; a < 4; a++) { //java ints are 32 bits, more shifts leads to errors
                frameStart[lenFS - 1 - a] = (byte) ((data.length >> (a * 8)) & 0xFF);
            }
        } else if (data.length >= 126) {
            lenFS = 4;
            frameStart[1] = (byte) 126;
            for (int a = 0; a < lenFS - 2; a++) {
                frameStart[lenFS - 1 - a] = (byte) ((data.length >> (a * 8)) & 0xFF);
            }
        } else {
            lenFS = 2;
            frameStart[1] = (byte) data.length;
        }
        out.write(frameStart, 0, lenFS);
        out.write(data);
    }
}
