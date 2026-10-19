package turfwar.net;

import java.io.Serializable;
/**
 * step 4
 * Data Transfer Object: one changed square, sent over RMI.
 * The scaffold's Square is not Serializable, so this copy is marshalled instead.
 * The client unmarshals it and turns it back into a Square.
 */
public final class SquareChange implements Serializable {
    // version number, so server and client agree on the class format
    private static final long serialVersionUID = 1L; 
    public final int col;
    public final int row;
    public final String ownerId;
    public final boolean scorched; // step 5-3
    // used for neutral or owned squares
    public SquareChange(int col, int row, String ownerId) {
        this.col = col;
        this.row = row;
        this.ownerId = ownerId;
        this.scorched = false;
    }

    // used for scorched squares
    public SquareChange(int col, int row, String ownerId, boolean scorched) {
        this.col = col;
        this.row = row;
        this.ownerId =ownerId;
        this.scorched = scorched;
    }

    
}
