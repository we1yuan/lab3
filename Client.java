

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;
import java.util.Scanner;

public class Client {
    public static void main(String args[]) throws IOException {
        // takes input from command line for datagram
        Scanner sc = new Scanner(System.in);

        // local ip address
        System.out.println("Enter the Server's IP Address:");
        InetAddress ipAddress = InetAddress.getByName(sc.nextLine());

        // socket to send and recieve datagrams
        DatagramSocket socket = new DatagramSocket(8081);
        socket.setSoTimeout(5000);

        // server port number
        System.out.println("Enter the Server's UDP Port:");
        Integer serverPortID = Integer.parseInt(sc.nextLine());

        // file name to be recieved
        System.out.println("Enter the File Name:");
        String fileName = sc.nextLine();

        // max payload size
        System.out.println("Maximum UDP payload size:");
        Integer payloadSize = Integer.parseInt(sc.nextLine());

        // byte buffer for datagram creation
        byte[] buffer = new byte[65535];

        // current validation numbers
        int sequenceNumber = 0;
        int connectionID = 0;

        // Client flags
        boolean sending = true;
        boolean quiting = false;
        boolean acknowledging = false;

        // client runs until user enters "quit"
        // client swaps mode after sending
        while (true) {
            while (sending) {
                // clear buffer
                buffer = new byte[65535];

                // create packet
                Packet input = new Packet(connectionID, sequenceNumber, "ACK", payloadSize, "", 0);
                if (!acknowledging) {
                    input.messageType = "REQUEST";
                    input.payload = fileName;
                    Random r = new Random();
                    connectionID = r.nextInt(1000);
                    input.connectionID = connectionID;
                    sequenceNumber = 0;
                    input.sequenceNumber = 0;
                }

                // push packet into buffer as bytes
                buffer = Packet.packetToString(input).getBytes();

                // create datagram
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length, ipAddress, serverPortID);

                // send datagram
                socket.send(datagram);
                System.out.println("Client sent a " + input.messageType + " packet.");
                // increment ACK sequence number
                if (acknowledging)
                    sequenceNumber = (sequenceNumber + 1) % 2;

                // swap mode
                sending = false;
            }

            // client swaps mode after recieving or timeout
            while (!sending) {
                // clear buffer
                buffer = new byte[65535];

                // create datagram shell
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

                try {
                    // receive the datagram (blocks until recieved)
                    socket.receive(datagram);

                    // convert bytes to received packet
                    Packet recievedPacket = Packet.stringToPacket(Packet.byteToString(buffer));

                    // print error message if applicable
                    if (recievedPacket.messageType.equals("ERROR")) {
                        System.out.println("Server sent: " + recievedPacket.payload);
                    }

                    // check connection ID and sequence number
                    if (connectionID != recievedPacket.connectionID) {
                        System.out.println("ERROR: Invalid connection ID. Packet discarded.");
                    } else if (sequenceNumber != recievedPacket.sequenceNumber) {
                        System.out.println("ERROR: Invalid sequence number. Packet discarded.");
                    } else {
                        // write data to output file
                        File outputFile = new File("output.txt");
                        // writer only appends after first data packet
                        FileWriter fileWriter = new FileWriter(outputFile, acknowledging);
                        fileWriter.write(recievedPacket.payload);
                        fileWriter.close();
                        System.out.println("Client recieved a valid DATA packet.");

                        // handle final packet
                        if (recievedPacket.last == 1) {
                            quiting = true;
                            System.out.println("Client recieved the last packet.");
                        }

                        // start acknowledging if last packet was request
                        acknowledging = true;
                    }
                } catch (Exception e) {
                    // timeout
                    System.out.println("Client timed out, resending last packet.");
                }
                // swap mode
                sending = true;
            }
            // end complete loop if broken.
            if (quiting)
                break;
        }
        System.out.println("Connection terminated.");
        sc.close();
        socket.close();
    }
}
