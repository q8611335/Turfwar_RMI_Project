package turfwar.server;
import turfwar.net.ClientCallback;

import java.rmi.RemoteException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** step 4-2
 * One outgoing mailbox per client. 
 * Calls to this client happen on its own thread in order. 
*/
public class ClientLink {
    // one message to deliver: queued by the server, delivered by this link's own thread
    public interface Message {
        public void deliver(ClientCallback callback) throws RemoteException;
    }
    
    public final String playerId;
    private final ClientCallback callback;
    // queue of messages for this client, emptied in order by this link's own thread
    private final BlockingQueue<Message> mailbox = new LinkedBlockingQueue<>();
    // poison pill: a special message that means "nothing more is coming, stop the thread"
    private static final Message STOP = c -> {}; // put last in the mailbox by close()
    private volatile boolean closed = false;

    // step 6
    private final Runnable onFailure;
    private volatile boolean dead = false; // the client stopped answering

    public ClientLink(String playerId, ClientCallback callback, Runnable onFailure) {
        this.playerId = playerId;
        this.callback = callback;
        this.onFailure = onFailure;
        Thread sender = new Thread(() -> deliverAll(), "to-" + playerId);
        sender.setDaemon(true); // a stuck client never keeps the server process alive
        sender.start();

    }
     // runs on this client's own thread: take one message at a time in order, until STOP
    private void deliverAll() {
        try {
            while (true) {
                Message message = mailbox.take(); // wait while the mailbox is empty
                if (message == STOP) { // everything queued before close() has been delivered
                    return; 
                }
                try {
                    message.deliver(callback);
                } catch (RemoteException e) {
                    System.out.println("Failed to reach client: " + playerId + ". " + e.getMessage());
                    dead = true;
                    onFailure.run(); // takes the server lock (this thread holds no lock, so no deadlock)
                    return; 
                } catch (RuntimeException e) {
                    // a bug in one message must not kill the thread and silence the client
                    System.out.println("Problem sending to " + playerId + ": " + e);
                }
                
            }
        } catch (InterruptedException e) {
            // take() requires this, nothing interrupts this thread
        }
    }

    // put a message in client's mailbox and return at once
    public void send(Message message) {
        if (dead || closed) {
            return;  // the player has gone, drop it
        }
        mailbox.add(message);
        }

    // step 6: stop this mailbox after any messages already in it are delivered
    public void close() {
        if (closed) {
            return; 
        }
        closed = true;
        mailbox.add(STOP); // the thread stops after all earlier messages sent
    }
}
