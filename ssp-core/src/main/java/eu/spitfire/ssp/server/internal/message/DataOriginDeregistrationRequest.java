package eu.spitfire.ssp.server.internal.message;

import com.google.common.util.concurrent.SettableFuture;
import eu.spitfire.ssp.backend.generic.DataOrigin;

/**
 * Created by olli on 16.04.14.
 */
public class DataOriginDeregistrationRequest<I, D extends DataOrigin<I>> {

    private D dataOrigin;
    private SettableFuture<Void> deregistrationFuture;

    public DataOriginDeregistrationRequest(D dataOrigin, SettableFuture<Void> deregistrationFuture) {
        this.dataOrigin = dataOrigin;
        this.deregistrationFuture = deregistrationFuture;
    }

    public D getDataOrigin() {
        return dataOrigin;
    }

    public SettableFuture<Void> getDeregistrationFuture() {
        return deregistrationFuture;
    }
}
