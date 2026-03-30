import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class Server {
    private static final int DEFAULT_SERVER_PORT = 8080;
    private static final int BUFFER_SIZE = 65535;
    private static final int DEFAULT_TIMEOUT_MS = 1500;
    private static final int DEFAULT_FINAL_STATE_MS = 10000;

    private static class CompletedTransfer {
        InetAddress clientAddress;
        int clientPort;
        int connectionID;
        String fileName;
        int payloadSize;
        Packet finalPacket;
        long finishedAtMs;

        CompletedTransfer(InetAddress clientAddress, int clientPort, int connectionID,
                          String fileName, int payloadSize, Packet finalPacket, long finishedAtMs) {
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.connectionID = connectionID;
            this.fileName = fileName;
            this.payloadSize = payloadSize;
            this.finalPacket = finalPacket;
            this.finishedAtMs = finishedAtMs;
        }
    }

    public static void main(String[] args) throws IOException {
        int serverPort = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_SERVER_PORT;
        int socketTimeoutMs = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_TIMEOUT_MS;
        int finalStateMs = args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_FINAL_STATE_MS;

        DatagramSocket socket = new DatagramSocket(serverPort);
        socket.setSoTimeout(0);

        CompletedTransfer lastCompletedTransfer = null;

        System.out.println("Server is listening on UDP port " + serverPort + ".");
        System.out.println("DATA/ACK timeout(ms): " + socketTimeoutMs);
        System.out.println("Completed-transfer cache(ms): " + finalStateMs);

        while (true) {
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket incomingDatagram = new DatagramPacket(buffer, buffer.length);
            socket.receive(incomingDatagram);

            Packet incomingPacket;
            try {
                incomingPacket = Packet.stringToPacket(datagramToString(incomingDatagram));
            } catch (Exception e) {
                System.out.println("Server received a malformed packet. Packet discarded.");
                continue;
            }

            InetAddress senderAddress = incomingDatagram.getAddress();
            int senderPort = incomingDatagram.getPort();

            if (lastCompletedTransfer != null && System.currentTimeMillis() - lastCompletedTransfer.finishedAtMs > finalStateMs) {
                lastCompletedTransfer = null;
            }

            if (isDuplicateForCompletedTransfer(incomingPacket, senderAddress, senderPort, lastCompletedTransfer)) {
                sendPacket(socket, lastCompletedTransfer.finalPacket, senderAddress, senderPort);
                System.out.println("Server resent the cached final DATA packet for a duplicate "
                        + incomingPacket.messageType + " packet.");
                continue;
            }

            if (!"REQUEST".equals(incomingPacket.messageType)) {
                System.out.println("Server received a non-REQUEST packet while idle. Packet discarded.");
                continue;
            }

            int connectionID = incomingPacket.connectionID;
            int sequenceNumber = incomingPacket.sequenceNumber;
            int payloadSize = incomingPacket.payloadSize;
            String requestedFile = incomingPacket.payload;

            System.out.println("Server recieved a valid REQUEST packet.");
            System.out.println("Client: " + senderAddress.getHostAddress() + ":" + senderPort
                    + ", connectionID=" + connectionID + ", file=" + requestedFile);

            if (payloadSize <= 0) {
                Packet errorPacket = new Packet(connectionID, sequenceNumber, "ERROR", 0,
                        "ERROR: Invalid payload size.", 0);
                sendPacket(socket, errorPacket, senderAddress, senderPort);
                System.out.println("Server sent a ERROR packet.");
                continue;
            }

            File loadedFile = new File(requestedFile);
            if (!loadedFile.canRead()) {
                Packet errorPacket = new Packet(connectionID, sequenceNumber, "ERROR", payloadSize,
                        "ERROR: File failed to load.", 0);
                sendPacket(socket, errorPacket, senderAddress, senderPort);
                System.out.println("Server sent a ERROR packet.");
                continue;
            }

            byte[] fileBytes = Files.readAllBytes(loadedFile.toPath());
            List<String> fileData = splitFileIntoSegments(fileBytes, payloadSize);
            int lastSegment = fileData.size() - 1;
            int segmentToSend = 0;

            int retransmissions = 0;
            int timeoutEvents = 0;
            int duplicateAckEvents = 0;
            int duplicateRequestEvents = 0;
            int totalDataPacketsSent = 0;
            long startTime = 0;
            long endTime = 0;

            socket.setSoTimeout(socketTimeoutMs);

            while (true) {
                Packet dataPacket = new Packet(
                        connectionID,
                        sequenceNumber,
                        "DATA",
                        payloadSize,
                        fileData.get(segmentToSend),
                        segmentToSend == lastSegment ? 1 : 0);

                if (startTime == 0) {
                    startTime = System.currentTimeMillis();
                }

                sendPacket(socket, dataPacket, senderAddress, senderPort);
                totalDataPacketsSent++;

                if (dataPacket.last == 0) {
                    System.out.println("Server sent DATA seq=" + dataPacket.sequenceNumber
                            + " segment=" + segmentToSend + "/" + lastSegment + ".");
                } else {
                    System.out.println("Server sent the last DATA packet.");
                }

                while (true) {
                    try {
                        byte[] ackBuffer = new byte[BUFFER_SIZE];
                        DatagramPacket ackDatagram = new DatagramPacket(ackBuffer, ackBuffer.length);
                        socket.receive(ackDatagram);

                        Packet receivedPacket;
                        try {
                            receivedPacket = Packet.stringToPacket(datagramToString(ackDatagram));
                        } catch (Exception e) {
                            System.out.println("Server received a malformed packet while waiting for ACK. Packet ignored.");
                            continue;
                        }

                        InetAddress ackAddress = ackDatagram.getAddress();
                        int ackPort = ackDatagram.getPort();

                        if (!ackAddress.equals(senderAddress) || ackPort != senderPort) {
                            System.out.println("Server received a packet from another client while busy. Packet ignored.");
                            continue;
                        }

                        if ("REQUEST".equals(receivedPacket.messageType)
                                && receivedPacket.connectionID == connectionID) {
                            duplicateRequestEvents++;
                            retransmissions++;
                            System.out.println("Server received a duplicate REQUEST packet, resending current DATA packet.");
                            break;
                        }

                        if (!"ACK".equals(receivedPacket.messageType)) {
                            System.out.println("Server received a non-ACK packet while waiting for ACK. Packet ignored.");
                            continue;
                        }

                        if (receivedPacket.connectionID != connectionID) {
                            System.out.println("Server received an ACK with an invalid connection ID. Packet ignored.");
                            continue;
                        }

                        if (receivedPacket.sequenceNumber != sequenceNumber) {
                            duplicateAckEvents++;
                            retransmissions++;
                            System.out.println("Server received a duplicate/out-of-order ACK, resending current DATA packet.");
                            break;
                        }

                        System.out.println("Server recieved a valid ACK packet.");

                        if (segmentToSend == lastSegment) {
                            endTime = System.currentTimeMillis();
                            printTransferStats(fileBytes.length, fileData.size(), totalDataPacketsSent,
                                    retransmissions, timeoutEvents, duplicateAckEvents,
                                    duplicateRequestEvents, startTime, endTime);

                            lastCompletedTransfer = new CompletedTransfer(
                                    senderAddress,
                                    senderPort,
                                    connectionID,
                                    requestedFile,
                                    payloadSize,
                                    dataPacket,
                                    endTime);

                            socket.setSoTimeout(0);
                            System.out.println("Server kept final transfer state for " + finalStateMs
                                    + " ms in case a duplicate ACK/REQUEST arrives.");
                            break;
                        }

                        sequenceNumber = (sequenceNumber + 1) % 2;
                        segmentToSend++;
                        break;
                    } catch (SocketTimeoutException e) {
                        timeoutEvents++;
                        retransmissions++;
                        System.out.println("Server timed out after " + socketTimeoutMs
                                + " ms, resending current DATA packet.");
                        break;
                    }
                }

                if (segmentToSend == lastSegment && endTime != 0) {
                    break;
                }
            }
        }
    }

    private static void sendPacket(DatagramSocket socket, Packet packet, InetAddress address, int port)
            throws IOException {
        byte[] buffer = Packet.packetToString(packet).getBytes(StandardCharsets.ISO_8859_1);
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length, address, port);
        socket.send(datagram);
    }

    private static String datagramToString(DatagramPacket datagram) {
        return new String(datagram.getData(), 0, datagram.getLength(), StandardCharsets.ISO_8859_1);
    }

    private static List<String> splitFileIntoSegments(byte[] fileBytes, int payloadSize) {
        List<String> segments = new ArrayList<>();

        if (fileBytes.length == 0) {
            segments.add("");
            return segments;
        }

        for (int i = 0; i < fileBytes.length; i += payloadSize) {
            int length = Math.min(payloadSize, fileBytes.length - i);
            segments.add(new String(fileBytes, i, length, StandardCharsets.ISO_8859_1));
        }

        return segments;
    }

    private static boolean isDuplicateForCompletedTransfer(Packet packet, InetAddress address, int port,
                                                           CompletedTransfer completedTransfer) {
        if (completedTransfer == null) {
            return false;
        }

        if (!address.equals(completedTransfer.clientAddress) || port != completedTransfer.clientPort) {
            return false;
        }

        if (packet.connectionID != completedTransfer.connectionID) {
            return false;
        }

        if ("ACK".equals(packet.messageType) || "REQUEST".equals(packet.messageType)) {
            return true;
        }

        return false;
    }

    private static void printTransferStats(long fileBytes, int segmentCount, int totalDataPacketsSent,
                                           int retransmissions, int timeoutEvents, int duplicateAckEvents,
                                           int duplicateRequestEvents, long startTime, long endTime) {
        long transferTimeMs = Math.max(1, endTime - startTime);
        double throughputBps = (fileBytes * 1000.0) / transferTimeMs;

        System.out.println("Transfer complete.");
        System.out.println("Transfer time(ms): " + transferTimeMs);
        System.out.println("Retransmissions: " + retransmissions);
        System.out.println("Timeout events: " + timeoutEvents);
        System.out.println("Duplicate ACK events: " + duplicateAckEvents);
        System.out.println("Duplicate REQUEST events: " + duplicateRequestEvents);
        System.out.println("Approx throughput(B/s): " + String.format("%.2f", throughputBps));
        System.out.println("Original file size(bytes): " + fileBytes);
        System.out.println("Unique DATA segments: " + segmentCount);
        System.out.println("Total DATA packets sent (including retransmissions): " + totalDataPacketsSent);
    }
}
