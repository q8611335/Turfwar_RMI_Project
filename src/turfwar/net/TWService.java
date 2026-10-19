package turfwar.net;

import java.rmi.Remote;
import java.rmi.RemoteException;
import turfwar.model.Tool;
/**
 * Interface shared between clients and server (client -> server)
 */
public interface TWService extends Remote{ 
    // step 1, 6: client sends a heartbeat every second, so the server knows it still alive 
    public void ping(String playerId) throws RemoteException;

    // step 2, 7
    // first client create the match and become host
    public String hostMatch(String name, int roundSeconds, ClientCallback callback) throws RemoteException;
    // other client asks to join an existing match, and become a player
    public String requestJoin(String name, ClientCallback callback) throws RemoteException;

    // step 4: client paints one square
    public void paint(String playerId, int col, int row) throws RemoteException;

    // step 5
    // host only: clear the arena and start a new round
    public void startRound(String playerId) throws RemoteException;
    // client uses Line, Block or Wedge 
    public void usePowerUp(String playerId, Tool tool, int col, int row) throws RemoteException;
    // client uses the Bomb
    public void useBomb(String playerId, int col, int row) throws RemoteException;

    // step 6
    // client leaves the match (Game > Leave or window closed)
    public void leave(String playerId) throws RemoteException;
    // host only: approve or deny a join request
    public void respondToJoin(String playerId, String requestId, boolean approve) throws RemoteException;

    // step 8: host only: remove another player from the match
    public void kick(String playerId, String targetId) throws RemoteException;

    // step 9: client sends one of the fixed taunts 
    public void sendTaunt(String playerId, int tauntIndex) throws RemoteException;

    // step 11: host only: pause a running round, or resume a paused one
    public void pauseOrResume(String playerId) throws RemoteException;
}