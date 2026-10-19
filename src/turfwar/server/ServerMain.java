package turfwar.server;

import turfwar.net.Protocol;
import turfwar.net.TWService;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.io.IOException;
import java.rmi.server.ExportException;


/**
 * Entry point of the server jar. 
 * Usage: java -jar TurfWarServer.jar <port>
 * Creates the RMI registry on the given port and binds one TWServiceImplementation as "TWService". 
 * That object holds the match state, orders player actions with a single lock, keeps the round clock and sends every change to every client.
 */
public final class ServerMain {
   
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar TurfWarServer.jar <port>");
            System.exit(1);
        }
        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("The port must be a number from 1 to 65535.");
            System.exit(1);
            return;
        }
        if (port < 1 || port > 65535) {
            System.err.println("The port must be a number from 1 to 65535.");
            System.exit(1);
        }
        
        Protocol.applyJvmDefaults(); // step 7: set the RMI timeouts before any RMI is used
        try{
            // the registry listens on the given port, clients look up "TWService" 
            Registry registry = LocateRegistry.createRegistry(port);
            TWService service = new TWServiceImplementation();
            registry.rebind(Protocol.SERVICE_NAME, service);
        } catch (ExportException e) {
            // createRegistry fails if another program already uses the port
            System.err.println("Could not start: port " + port + " is already in use. Choose another port.");
            System.exit(1);
            return;
        } catch (IOException e ) { // include RemoteException
            System.err.println(" Could not start the server: " + e.getMessage());
            System.exit(1);
            return; 
        }
        System.out.println("Turf War server running on port " + port + ".");
        System.out.println("Waiting for a host.");
    }

}