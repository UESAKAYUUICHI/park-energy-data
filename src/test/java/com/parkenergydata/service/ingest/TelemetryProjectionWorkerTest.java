package com.parkenergydata.service.ingest;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class TelemetryProjectionWorkerTest {
    @Test void failedHistoryRemainsPendingAndDoesNotUpdateRealtime() {
        var outbox=mock(TelemetryOutboxRepository.class);
        var history=mock(TimeSeriesWriter.class);
        var realtime=mock(RealtimeCacheService.class);
        var item=new TelemetryOutboxRepository.Pending(1,"payload",0);
        var value=new TelemetryProjection(2L,Instant.EPOCH,List.of(),null);
        when(outbox.lockPending()).thenReturn(List.of(item));
        when(outbox.decode("payload")).thenReturn(value);
        doThrow(new IllegalStateException("offline")).when(history).writeDevicePoints(2L,Instant.EPOCH,List.of());
        new TelemetryProjectionWorker(outbox,history,realtime).projectPending();
        verify(outbox).retry(eq(item),any(IllegalStateException.class));
        verify(outbox,never()).complete(anyLong());
        verifyNoInteractions(realtime);
    }
    @Test void completeOnlyAfterBothProjectionsSucceed() {
        var outbox=mock(TelemetryOutboxRepository.class);
        var history=mock(TimeSeriesWriter.class);
        var realtime=mock(RealtimeCacheService.class);
        var item=new TelemetryOutboxRepository.Pending(1,"payload",0);
        var value=new TelemetryProjection(2L,Instant.EPOCH,List.of(),null);
        when(outbox.lockPending()).thenReturn(List.of(item));
        when(outbox.decode("payload")).thenReturn(value);
        new TelemetryProjectionWorker(outbox,history,realtime).projectPending();
        var order=inOrder(history,realtime,outbox);
        order.verify(history).writeDevicePoints(2L,Instant.EPOCH,List.of());
        order.verify(realtime).saveRealtime(null);
        order.verify(outbox).complete(1);
    }
}
