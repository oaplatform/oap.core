package oap.http.pnio;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import oap.concurrent.Executors;
import oap.io.Closeables;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;

public class PnioWorkers<WorkflowState> implements AutoCloseable {
    public final ExecutorService pool;
    public final PnioWorker<WorkflowState>[] workers;
    private final ArrayBlockingQueue<PnioTask<WorkflowState>> queue;
//    public AtomicInteger counter = new AtomicInteger( 0 );

    public PnioWorkers( int threads, int maxQueueSize ) {
        pool = Executors.newFixedThreadPool( threads > 0 ? threads : Runtime.getRuntime().availableProcessors(),
            new ThreadFactoryBuilder().setNameFormat( "PNIO - CPU-%d" ).build() );

        workers = new PnioWorker[threads];

        queue = new ArrayBlockingQueue<>( maxQueueSize );

        for( int i = 0; i < threads; i++ ) {
            PnioWorker<WorkflowState> pnioWorker = new PnioWorker<>( queue );
            pool.execute( pnioWorker );
            workers[i] = pnioWorker;
        }
    }

    public boolean register( PnioExchange<WorkflowState> pnioExchange, PnioTask<WorkflowState> task ) {
//        int hash = counter.incrementAndGet() % workers.length;

        if( !queue.offer( task ) ) {
            pnioExchange.completeWithRejected();
            pnioExchange.response();

            return false;
        }

        return true;
    }

    @Override
    public void close() {
        pool.shutdown();

        for( PnioWorker<WorkflowState> worker : workers ) {
            worker.interrupt();
        }

        Closeables.close( pool );
    }
}
