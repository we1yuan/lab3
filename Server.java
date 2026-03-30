

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.io.File;

public class Server {
    public static void main(String[] args) throws IOException {
        // socket to send and recieve datagrams
        DatagramSocket socket = new DatagramSocket(8080);
        socket.setSoTimeout(5000);

        // recieve buffer
        byte[] buffer = new byte[65535];

        // local ip address
//       InetAddress ipAddress = InetAddress.getLocalHost();
InetAddress ipAddress = java.net.InetAddress.getByName("10.0.0.2");

        // Server flags
        boolean sending = false;
        boolean quiting = false;
        boolean invalidID = false;

        // File data
        String[] fileData = new String[100];
        fileData[0] = "";
        int segmentToSend = 0;
        int lastSegement = 0;

        // validation numbers
        int sequenceNumber = 0;
        int connectionID = 0;

        // payload size
        int payloadSize = 0;

        int retransmissions = 0;
        long startTime = 0;
        long endTime = 0;
        long fileSize = 0;

        // server runs until client sends quit
        while (true) {
            while (!sending) {
                // clear buffer
                buffer = new byte[65535];

                // create datagram shell
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

                // receive the datagram
                try {
                    socket.receive(datagram);




                    // convert bytes to received packet
                    Packet recievedPacket = Packet.stringToPacket(Packet.byteToString(buffer));

                    // if message is a request, load the file to be sent
                    if (recievedPacket.messageType.equals("REQUEST")) {
                        File loadedFile = new File(recievedPacket.payload);
                        // check that requested file is valid
                        if (!loadedFile.canRead()) {
                            fileData[0] = "";
                        } else {
                            System.out.println("Server recieved a valid REQUEST packet.");
                            connectionID = recievedPacket.connectionID;
                            sequenceNumber = recievedPacket.sequenceNumber;
                            payloadSize = recievedPacket.payloadSize;

                            byte[] fileBytes = Files.readAllBytes(loadedFile.toPath());
                            fileSize = fileBytes.length;

                            // splits file bytes according to payload size
                            int i = 0;
                            int k = 0;
                            while (i < fileBytes.length) {
                                byte[] segmentBytes = new byte[payloadSize];
                                int j = 0;
                                while (j + 1 < payloadSize && i < fileBytes.length) {
                                    segmentBytes[j] = fileBytes[i];
                                    j++;
                                    i++;
                                }
                                fileData[k] = Packet.byteToString(segmentBytes);
                                lastSegement = k;
                                k++;
                            }
                        }
                    }
/*
                    // if message is a acknowledgement, handle it
                    if (recievedPacket.messageType.equals("ACK")) {
                        if (recievedPacket.connectionID == connectionID
                                && recievedPacket.sequenceNumber == sequenceNumber) {
                            // send next segment
                            sequenceNumber = (sequenceNumber + 1) % 2;
                            segmentToSend++;
                            System.out.println("Server recieved a valid ACK packet.");
                        } else if (recievedPacket.connectionID != connectionID) {
                            // set error flag
                            invalidID = true;
                        } else {
                            // else, resend last packet
                            System.out.println("Server recieved an invalid ACK packet, resending last packet.");
                        }
                    }
                } 
*/              
                    
if (recievedPacket.messageType.equals("ACK")) {
    if (recievedPacket.connectionID == connectionID &&
        recievedPacket.sequenceNumber == sequenceNumber) {

        System.out.println("Server recieved a valid ACK packet.");

        if (segmentToSend == lastSegement) {
            // 最后一个 ACK 收到后
            endTime = System.currentTimeMillis();
            System.out.println("Transfer time(ms): " + (endTime - startTime) + "ms");
            System.out.println("Retransmissions: " + retransmissions);

            
            quiting = true;   // 最后一个 DATA 已被确认
            break;
        }

        sequenceNumber = (sequenceNumber + 1) % 2;
        segmentToSend++;
    } else if (recievedPacket.connectionID != connectionID) {
        invalidID = true;
    } else {
        System.out.println("Server recieved an invalid ACK packet, resending last packet.");
    }
}
                } 
                    catch (Exception e) {
                    // timeout
                    if (segmentToSend == lastSegement) {
                        // last packet has been sent already, terminate connection

                            endTime = System.currentTimeMillis();
                            System.out.println("Transfer time(ms): " + (endTime - startTime));
                            System.out.println("Retransmissions: " + retransmissions);

        
long transferTime = endTime - startTime;
        if (transferTime > 0) {
            double throughput = (fileSize * 1000.0) / transferTime;   // Bytes/s
            System.out.println("Throughput(Bytes/s): " + throughput);
            System.out.println("Throughput(KB/s): " + (throughput / 1024.0));
        } else {
            System.out.println("Throughput(Bytes/s): 0");
        }
                        
                        quiting = true;
                    } else {
                        // else, resend last packet
                        retransmissions++;
                        System.out.println("Server timed out, resending last packet.");
                    }
                }

                // swap mode
                sending = true;
            }
            while (sending) {
                // clear buffer
                buffer = new byte[65535];

                // create packet, assume packet failed until proven otherwise
                Packet packet = new Packet(connectionID, sequenceNumber, "ERROR", payloadSize,
                        "ERROR: File failed to load.",
                        0);
                if (invalidID) {
                    packet.payload = "ERROR: Invalid connection ID.";
                    invalidID = false;
                } else if (fileData[0] != null && !fileData[0].isEmpty()) {
                    // send ERROR otherwise
                    packet.messageType = "DATA";
                    packet.payload = fileData[segmentToSend];
                    if (segmentToSend == lastSegement) {
                        packet.last = 1;
                    }
                }

                // load packet as bytes
                buffer = Packet.packetToString(packet).getBytes();

                // create datagram
     DatagramPacket datagram = new DatagramPacket(buffer, buffer.length, ipAddress, 8081);
// DatagramPacket datagram = new DatagramPacket(buffer, buffer.length, ipAddress, clientPort);

                if (packet.messageType.equals("DATA") && segmentToSend == 0 && startTime == 0) {
                    startTime = System.currentTimeMillis();
                }

                
                // send datagram
                socket.send(datagram);
                if (packet.last == 0)
                    System.out.println("Server sent a " + packet.messageType + " packet.");
                else if (!quiting)
                    System.out.println("Server sent the last DATA packet.");

                // swap mode
                sending = false;
            }
            // end complete loop if broken.
            if (quiting)
                break;
        }
        System.out.println("Connection terminated.");
        socket.close();
    }
}
