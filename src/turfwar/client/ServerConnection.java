package turfwar.client;

import turfwar.net.Protocol;
import turfwar.net.TWService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.rmi.NotBoundException;

import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
/** step 7
 * To connect to the server for client.
 * It checks that the server is reachable, and that it is a Turf War server.
 */
public class ServerConnection {
    private ServerConnection() {}

    public static TWService open(String address, int port) throws IOException {
        // 1. check TCP connection is answered within 2.5 s 
        // check the computer's addresses leads to the server, so the server can call back 
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(address, port), Protocol.CONNECT_TIMEOUT_MS);
            if (System.getProperty("java.rmi.server.hostname") == null) {
                System.setProperty("java.rmi.server.hostname", probe.getLocalAddress().getHostAddress());
            }
        } catch (UnknownHostException e) {
            throw new IOException("The server address \"" + address + "\" could not be found");
        } catch (IOException e) {
            throw new IOException("No server could be reached at " + address + ":" + port
                + ". Check the address and the port.");
        }
        // 2. RMI lookup: look up TWService in the server's RMI registry
        Remote remote;
        try {
            Registry registry = LocateRegistry.getRegistry(address, port);
            remote = registry.lookup(Protocol.SERVICE_NAME);
        } catch (NotBoundException | IOException e) {
            throw new IOException(address + ":" + port + " answered, but is not Turf War server. Check port again.");
        }
        // 3. check the registered object is a Turf War server
        if (!(remote instanceof TWService)) {
            throw new IOException("The program at " + address + ":" + port + " is not a Turf War server.");
        }
        return (TWService) remote;
        }
    }
    

