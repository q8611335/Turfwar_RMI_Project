package turfwar.net;
import java.io.Serializable;

/**
 * step 2
 * Data Transfer Object: one player's details, sent over RMI.
 * RMI marshals every parameter with Java serialisation, so every parameter must be Serializable.
 * The scaffold's PlayerInfo is not Serializable, so this copy is marshalled instead.
 * The client unmarshals it and turns it back into a PlayerInfo (toInfo).
 */
public final class PlayerDTO implements Serializable {
    // version number, so server and client agree on the class format
    private static final long serialVersionUID = 1L; 
    public final String id;
    public final String name;
    public final int colourIndex;
    public final boolean host;
    public final int squares;

    public PlayerDTO(String id, String name, int colourIndex, boolean host, int squares) {
        this.id = id;
        this.name = name;
        this.colourIndex = colourIndex;
        this.host = host;
        this.squares = squares;
    }
    
}
