package turfwar.net;

import turfwar.model.Config;

/**
 * Constants shared by the client and the server, 
 * both sides need before the first RMI call is made.
 */
public class Protocol {
    private Protocol() {}
    /** Name under which the server binds its remote object in the RMI registry. */
    public static final String SERVICE_NAME = "TWService";
    /** A client pings the server frequency. */
    public static final int PING_INTERVAL_MS = 1000;
    /** Either side treats the other as gone after no successful ping. */
    public static final int SESSION_TIMEOUT_MS = 4000;
    /** Longest time to wait for a TCP connection to open. */
    public static final int CONNECT_TIMEOUT_MS = 2500;
    /** Longest time to wait for the reply to one remote call. */
    public static final int CALL_TIMEOUT_MS = 8000;
    /** One colour per player, the palette size is the player limit. */
    public static final int MAX_PLAYERS = Config.COLOURS.length;
    // step 7
    /** run before any RMI class is loaded: RMI reads these properties only once. */
    public static void applyJvmDefaults() {
        // use IPv4 addresses so other computers can reach (6.3, two computers)
        System.setProperty("java.net.preferIPv4Stack", "true");
        // a remote call waits at most 8 s for reply, so no client hangs (B9)
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", Integer.toString(CALL_TIMEOUT_MS));
        // a new connection must say hello within 8 s, so a dead server gives a clear message (B11)
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", Integer.toString(CALL_TIMEOUT_MS));
    }
}
