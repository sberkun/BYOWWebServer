package byow.Networking;

import edu.princeton.cs.introcs.StdDraw;

import java.io.ByteArrayOutputStream;
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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Queue;
import java.util.Scanner;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/*
Most of the websockets logic is from the following MDN page:
https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_a_WebSocket_server_in_Java
*/

public class BYOWWebServer {
    private static final String CANVAS_FILE = ".server_canvas.png";
    private static final String CWD = System.getProperty("user.dir");
    private static final Path CANVAS_PATH = Paths.get(CWD, CANVAS_FILE);
    private static final Path HTML_PATH = Paths.get(CWD, "byow", "Networking", "index.html");
    private final int port;
    private final ServerSocket server;
    private InputStream in;
    private OutputStream out;
    private Queue<Character> inputs;
    /** We don't send the next frame over until the client has confirmed
     *  that they received the last one. This is because we rely on image differences,
     *  so client missing frame potentially puts everything out of wack. */
    private boolean readyForFrame;
    private BufferedImage queuedFrame;
    /** We only send over the differences of frames. cachedFrame is the last frame
     *  sent, and cachedColors is its color array. */
    private BufferedImage cachedFrame;
    private int[] cachedColors;

    public BYOWWebServer(int port) throws IOException {
        this.port = port;
        server = new ServerSocket(port);
        System.out.println("Server has started on port " + port);
        setupServer();
    }

    public boolean clientHasKeyTyped() {
        getClientInputs();
        sendQueuedFrame();
        return !inputs.isEmpty();
    }

    public char clientNextKeyTyped() {
        getClientInputs();
        sendQueuedFrame();
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Client does not have next key typed");
        }
        return inputs.poll();
    }

    public void sendCanvas() {
        getClientInputs();
        try {
            StdDraw.save(CANVAS_FILE);
            queuedFrame = ImageIO.read(CANVAS_PATH.toFile());
            sendQueuedFrame();
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

    private void sendQueuedFrame() {
        if (!readyForFrame || queuedFrame == null) {
            return;
        }
        try {
            cacheImageDifference(queuedFrame);
            byte[] contents = bufferedImageToPngBytes(cachedFrame);
            cachedFrame = queuedFrame;
            sendOutput(contents, true);
            readyForFrame = false;
            queuedFrame = null;
        } catch (IOException ex) {
            System.out.println("IO EXCEPTION CAUGHT: " + ex.getMessage());
            restartServer();
        }
    }

    private void cacheImageDifference(BufferedImage newFrame) {
        int W = newFrame.getWidth();
        int H = newFrame.getHeight();
        if (cachedFrame == null || cachedFrame.getWidth() != W || cachedFrame.getHeight() != H) {
            cachedFrame = newFrame;
            cachedColors = newFrame.getRGB(0, 0, W, H, null, 0, W);
            return;
        }
        int[] diffColors = new int[W * H];
        int[] newColors = newFrame.getRGB(0, 0, W, H, null, 0, W);
        for (int a = 0; a < W * H; a++) {
            if (newColors[a] != cachedColors[a]) {
                diffColors[a] = newColors[a]; //else 0, which is transparent
            }
        }
        cachedFrame.setRGB(0, 0, W, H, diffColors, 0, W);
        cachedColors = newColors;
    }

    private byte[] bufferedImageToPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ImageIO.write(img, "png", outStream);
        return outStream.toByteArray();
    }

    private void getClientInputs() {
        try {
            while (in.available() > 0) {
                String nextInput = readInput();
                if (nextInput.length() == 1) {
                    inputs.add(nextInput.charAt(0));
                } else if (nextInput.equals("READY")) {
                    readyForFrame = true;
                } else if (nextInput.equals("EXIT")) {
                    System.out.println("Client unloaded");
                    restartServer();
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

    private void setupServer() throws IOException {
        System.out.println("Waiting for a connection...");
        while (true) {
            Socket client = server.accept();
            System.out.println("A client connected...");
            in = client.getInputStream();
            out = client.getOutputStream();
            if (doWebsocketHandshake()) {
                break;
            }
            in.close();
            out.close();
        }
        System.out.println("Websocket connection established!");
        inputs = new ArrayDeque<>();
        readyForFrame = true;
        queuedFrame = null;
        cachedFrame = null;
        cachedColors = null;
    }

    private void restartServer() {
        try {
            in.close();
            out.close();
            setupServer();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not restart server: " + ex.getMessage());
        }
    }

    private boolean doWebsocketHandshake() throws IOException {
        Scanner s = new Scanner(in, StandardCharsets.UTF_8);
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("SHA-1 not supported :(");
        }
        String data = s.useDelimiter("\\r\\n\\r\\n").next();
        boolean get = Pattern.compile("^GET").matcher(data).find();
        Matcher getRoot = Pattern.compile("^GET /(index\\.html)? ").matcher(data);
        Matcher match = Pattern.compile("Sec-Web[Ss]ocket-Key: (.*)").matcher(data);

        if (get && match.find()) {
            String magic = match.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            byte[] response = httpResponse(
                   "HTTP/1.1 101 Switching Protocols",
                    "Connection: Upgrade",
                    "Upgrade: websocket",
                    "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(
                            sha1.digest(magic.getBytes(StandardCharsets.UTF_8)))
            );
            out.write(response, 0, response.length);
            return true;
        } else if (get && getRoot.find()) {
            String message = Files.readString(HTML_PATH);
            byte[] response = httpResponse(
                    "HTTP/1.1 200 OK",
                    "Server: BYOWWebServer",
                    "Content-Type: text/html",
                    dateLine(),
                    "Content-Length: " + message.length(),
                    "", message, ""
            );
            out.write(response, 0, response.length);
            return false;
        } else {
            System.out.println("Unknown HTTP message: " + data.substring(0, data.indexOf('\r')));
            String message = "Not found :(";
            byte[] response = httpResponse(
                    "HTTP/1.1 404 Not Found",
                    "Connection: close",
                    "Content-Type: text/plain",
                    dateLine(),
                    "Content-Length: " + message.length(),
                    "", message, "" //idk why the extra extra line at the end is necessary
            );
            out.write(response, 0, response.length);
            return false;
        }
    }

    private static byte[] httpResponse(String... lines) {
        StringBuilder res = new StringBuilder();
        for (String line : lines) {
            res.append(line).append("\r\n");
        }
        res.append("\r\n");
        return res.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String dateLine() {
        SimpleDateFormat formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        return "Date: " + formatter.format(new Date());
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
