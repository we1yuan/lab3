package lab3;

public class Packet {
    public int connectionID;
    public int sequenceNumber;
    public String messageType;
    public int payloadSize;
    public String payload;
    public int last;

    public Packet(int connectionID, int sequenceNumber, String messageType, int payloadSize, String payload, int last) {
        this.connectionID = connectionID;
        this.sequenceNumber = sequenceNumber;
        this.messageType = messageType;
        this.payloadSize = payloadSize;
        this.payload = payload;
        this.last = last;
    }

    // there is no json parsing in Java without external libraries so I had to make
    // my own.
    public static String packetToString(Packet packet) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(packet.connectionID + ";")
                .append(packet.sequenceNumber + ";")
                .append(packet.messageType + ";")
                .append(packet.payloadSize + ";")
                .append(packet.payload + ";")
                .append(packet.last + ";");
        return stringBuilder.toString();
    }

    public static Packet stringToPacket(String string) {
        String[] substrings = string.split(";");
        Packet packet = new Packet(Integer.parseInt(substrings[0]), Integer.parseInt(substrings[1]),
                substrings[2], Integer.parseInt(substrings[3]), substrings[4], Integer.parseInt(substrings[5]));
        return packet;
    }

    public static String byteToString(byte[] bytes) {
        // null check
        if (bytes == null)
            return null;
        StringBuilder stringBuilder = new StringBuilder();
        // iterates through bytes, casting into characters
        // stops when hits empty byte
        for (int i = 0; bytes[i] != 0; i++) {
            stringBuilder.append((char) bytes[i]);
        }
        return stringBuilder.toString();
    }
}
