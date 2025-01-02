package oap.http.pnio;

public class PnioTask<WorkflowState> implements Runnable {
    public final PnioExchange<WorkflowState> pnioExchange;

    public PnioTask( PnioExchange<WorkflowState> pnioExchange ) {
        this.pnioExchange = pnioExchange;
    }

    @Override
    public void run() {
        pnioExchange.process();
    }
}
