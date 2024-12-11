package oap.http.pnio;

import javax.annotation.Nullable;
import java.nio.BufferOverflowException;
import java.util.concurrent.ArrayBlockingQueue;

public class PnioWorker<WorkflowState> implements Runnable {
    public final ArrayBlockingQueue<PnioTask<WorkflowState>> queue;

    public boolean done = false;
    @Nullable
    public Thread thread;

    public PnioWorker( int maxQueueSize ) {
        this( new ArrayBlockingQueue<>( maxQueueSize ) );
    }

    public PnioWorker( ArrayBlockingQueue<PnioTask<WorkflowState>> queue ) {
        this.queue = queue;
    }

    @Override
    public void run() {
        thread = Thread.currentThread();

        while( !done && !thread.isInterrupted() ) {
            try {
                PnioTask<WorkflowState> task = queue.take();
                try {
                    task.pnioExchange.process();
                } catch( BufferOverflowException e ) {
                    task.pnioExchange.completeWithBufferOverflow( false );
                    task.pnioExchange.response();
                } catch( Throwable t ) {
                    task.pnioExchange.completeWithFail( t );
                    if( !task.pnioExchange.oapExchange.isResponseStarted() ) {
                        task.pnioExchange.response();
                    }
                }
            } catch( InterruptedException t ) {
                done = true;
            }
        }
    }

    public void interrupt() {
        if( thread != null ) {
            thread.interrupt();
        }
    }
}
