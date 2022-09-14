/*
 *
 *  * Copyright (c) Xenoss
 *  * Unauthorized copying of this file, via any medium is strictly prohibited
 *  * Proprietary and confidential
 *
 *
 */

package oap.http.pnio;

import lombok.extern.slf4j.Slf4j;
import net.openhft.affinity.Affinity;

import java.util.concurrent.BlockingQueue;

@Slf4j
class RequestTaskCpuRunner<WorkflowState> implements Runnable {
    private final BlockingQueue<PnioExchange<WorkflowState>> queue;
    private final int cpu;
    volatile boolean done = false;
    private Thread thread;

    RequestTaskCpuRunner( BlockingQueue<PnioExchange<WorkflowState>> queue, int cpu ) {
        this.queue = queue;
        this.cpu = cpu;
    }

    @Override
    public void run() {
        this.thread = Thread.currentThread();
        if( cpu >= 0 )
            Affinity.setAffinity( cpu );
        Thread.currentThread().setPriority( Thread.MAX_PRIORITY );

        while( !done ) {
            try {
                PnioExchange<WorkflowState> taskState = queue.take();
                try {
                    taskState.runTasks( true );

                    taskState.completeFuture();
                } catch( Throwable e ) {
                    taskState.completeWithFail( e );
                }
            } catch( InterruptedException e ) {
                done = true;
            }
        }
    }

    public void interrupt() {
        done = true;
        thread.interrupt();
    }
}
