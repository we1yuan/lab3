import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class Server {
    private static final int SERVER_PORT = 8080;
    private static final int BUFFER_SIZE = 65535;
    private static final int SOCKET_TIMEOUT_MS = 20000;

    public static void main(String[] args) throws IOException {
        DatagramSocket socket = new DatagramSocket(SERVER_PORT);
        socket.setSoTimeout(0); // block while waiting for the first REQUEST

        System.out.println("Server is listening on UDP port " + SERVER_PORT + ".");

        while (true) {
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket requestDatagram = new DatagramPacket(buffer, buffer.length);
            socket.receive(requestDatagram);

            Packet requestPacket;
            try {
                requestPacket = Packet.stringToPacket(Packet.byteToString(requestDatagram.getData()));
            } catch (Exception e) {
                System.out.println("Server received a malformed packet. Packet discarded.");
                continue;
            }

            if (!"REQUEST".equals(requestPacket.messageType)) {
                System.out.println("Server received a non-REQUEST packet while idle. Packet discarded.");
                continue;
            }

            InetAddress clientAddress = requestDatagram.getAddress();
            int clientPort = requestDatagram.getPort();
            int connectionID = requestPacket.connectionID;
            int sequenceNumber = requestPacket.sequenceNumber;
            int payloadSize = requestPacket.payloadSize;

            System.out.println("Server recieved a valid REQUEST packet.");

            if (payloadSize <= 0) {
                Packet errorPacket = new Packet(connectionID, sequenceNumber, "ERROR", 0,
                        "ERROR: Invalid payload size.", 0);
                sendPacket(socket, errorPacket, clientAddress, clientPort);
                System.out.println("Server sent a ERROR packet.");
                continue;
            }

            File loadedFile = new File(requestPacket.payload);
            if (!loadedFile.canRead()) {
                Packet errorPacket = new Packet(connectionID, sequenceNumber, "ERROR", payloadSize,
                        "ERROR: File failed to load.", 0);
                sendPacket(socket, errorPacket, clientAddress, clientPort);
                System.out.println("Server sent a ERROR packet.");
                continue;
            }

            byte[] fileBytes = Files.readAllBytes(loadedFile.toPath());
            List<String> fileData = splitFileIntoSegments(fileBytes, payloadSize);
            int lastSegment = fileData.size() - 1;
            int segmentToSend = 0;
            int retransmissions = 0;
            long startTime = 0;
            long endTime = 0;

            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            while (true) {
                Packet dataPacket = new Packet(
                        connectionID,
                        sequenceNumber,
                        "DATA",
                        payloadSize,
                        fileData.get(segmentToSend),
                        segmentToSend == lastSegment ? 1 : 0);

                if (segmentToSend == 0 && startTime == 0) {
                    startTime = System.currentTimeMillis();
                }

                sendPacket(socket, dataPacket, clientAddress, clientPort);
                if (dataPacket.last == 0) {
                    System.out.println("Server sent a DATA packet.");
                } else {
                    System.out.println("Server sent the last DATA packet.");
                }

                boolean resendCurrentSegment = false;

                try {
                    byte[] ackBuffer = new byte[BUFFER_SIZE];
                    DatagramPacket ackDatagram = new DatagramPacket(ackBuffer, ackBuffer.length);
                    socket.receive(ackDatagram);

                    Packet receivedPacket = Packet.stringToPacket(Packet.byteToString(ackDatagram.getData()));

                    if (!"ACK".equals(receivedPacket.messageType)) {
                        System.out.println("Server recieved a non-ACK packet, resending last packet.");
                        resendCurrentSegment = true;
                    } else if (receivedPacket.connectionID != connectionID) {
                        System.out.println("Server recieved an invalid connection ID, resending last packet.");
                        resendCurrentSegment = true;
                    } else if (receivedPacket.sequenceNumber != sequenceNumber) {
                        System.out.println("Server recieved an invalid ACK packet, resending last packet.");
                        resendCurrentSegment = true;
                    } else {
                        System.out.println("Server recieved a valid ACK packet.");

                        if (segmentToSend == lastSegment) {
                            endTime = System.currentTimeMillis();
                            System.out.println("Transfer time(ms): " + (endTime - startTime));
                            System.out.println("Retransmissions: " + retransmissions);
                            System.out.println("Connection terminated.");
                            socket.close();
                            return;
                        }

                        sequenceNumber = (sequenceNumber + 1) % 2;
                        segmentToSend++;
                    }
                } catch (Exception e) {
                    retransmissions++;
                    System.out.println("Server timed out, resending last packet.");
                    resendCurrentSegment = true;
                }

                if (resendCurrentSegment) {
                    continue;
                }
            }
        }
    }

    private static void sendPacket(DatagramSocket socket, Packet packet, InetAddress address, int port)
            throws IOException {
        byte[] buffer = Packet.packetToString(packet).getBytes();
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length, address, port);
        socket.send(datagram);
    }

    private static List<String> splitFileIntoSegments(byte[] fileBytes, int payloadSize) {
        List<String> segments = new ArrayList<>();

        if (fileBytes.length == 0) {
            segments.add("");
            return segments;
        }

        for (int i = 0; i < fileBytes.length; i += payloadSize) {
            int length = Math.min(payloadSize, fileBytes.length - i);
            byte[] segmentBytes = new byte[length];
            System.arraycopy(fileBytes, i, segmentBytes, 0, length);
            segments.add(Packet.byteToString(segmentBytes));
        }

        return segments;
    }
}
