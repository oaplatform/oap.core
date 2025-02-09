package oap.http;

import com.google.common.base.Preconditions;
import lombok.Getter;

@Getter
public class ParserCursor {

    private final int lowerBound;
    private final int upperBound;
    private int pos;

    public ParserCursor( int lowerBound, int upperBound ) {
        super();
        Preconditions.checkArgument( lowerBound >= 0, "lowerBound" );
        Preconditions.checkArgument( lowerBound <= upperBound, "lowerBound cannot be greater than upperBound" );
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.pos = lowerBound;
    }

    public void updatePos( int pos ) {
        if( pos < this.lowerBound ) {
            throw new IndexOutOfBoundsException( "pos: " + pos + " < lowerBound: " + this.lowerBound );
        }
        if( pos > this.upperBound ) {
            throw new IndexOutOfBoundsException( "pos: " + pos + " > upperBound: " + this.upperBound );
        }
        this.pos = pos;
    }

    public boolean atEnd() {
        return this.pos >= this.upperBound;
    }

    @Override
    public String toString() {
        return "[" + lowerBound + '>' + pos + '>' + upperBound + ']';
    }
}
