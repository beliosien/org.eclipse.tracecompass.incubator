package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.tracecompass.tmf.core.event.ITmfEvent;
import org.eclipse.tracecompass.tmf.core.request.ITmfEventRequest;
import org.eclipse.tracecompass.tmf.core.request.TmfEventRequest;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;

/**
 *
 */
public class PhaseEventExtractor {

    /**
     *
     */
    public static class KernelEventInfo {
        public final String name;
        public final long timestamp;
        public final int pid;
        public final int tid;

        /**
         * @param name
         * @param timestamp
         * @param pid
         * @param tid
         */
        public KernelEventInfo(String name, long timestamp, int pid, int tid) {
            this.name = name;
            this.timestamp = timestamp;
            this.pid = pid;
            this.tid = tid;
        }
    }


        /**
         * @param trace
         * @return
         * @throws InterruptedException
         */
        public static List<ITmfEvent> getAllEvents(ITmfTrace trace) throws InterruptedException {
            List<ITmfEvent> events = new ArrayList<>();
            ITmfEventRequest request = new TmfEventRequest(
                ITmfEvent.class,
                0, // index
                ITmfEventRequest.ALL_DATA,
                ITmfEventRequest.ExecutionType.FOREGROUND
            ) {
                @Override
                public void handleData(ITmfEvent event) {
                    events.add(event);
                }
            };
            trace.sendRequest(request);
            request.waitForCompletion();
            return events;
        }

    /**
     * Extract all kernel events between two phase markers (start/end) in the native trace
     * @param trace The trace to analyze
     * @param start  Timestamp of phase start
     * @param end  Timestamp of phase end
     * @return List of kernelEventInfo
     * @throws InterruptedException
     */
    public static List<KernelEventInfo> extractKernelEventsBetween(ITmfTrace trace, long start, long end) throws InterruptedException {
        List<KernelEventInfo> result = new ArrayList<>();
        List<ITmfEvent> allEvents = getAllEvents(trace);
        for (ITmfEvent event: allEvents) {
            long ts = event.getTimestamp().toNanos();
            if (ts < start || ts > end) {
                continue;
            }

            // get kernel events only
            String name = event.getType().getName();
            if (isKernelEvent(event)) {
                Integer pid = getIntField(event, "pid"); //$NON-NLS-1$
                Integer tid = getIntField(event, "tid"); //$NON-NLS-1$
                result.add(new KernelEventInfo(name, ts, pid != null ? pid : -1, tid != null ? tid : -1));
            }
        }
        return result;

    }

    private static boolean isKernelEvent(ITmfEvent event) {
        String eventName = event.getType().getName();
        return eventName.startsWith("syscall_") || //$NON-NLS-1$
                eventName.startsWith("sched_") || //$NON-NLS-1$
                eventName.startsWith("irq_") || //$NON-NLS-1$
                eventName.startsWith("mm_"); //$NON-NLS-1$
    }

    private static Integer getIntField(ITmfEvent event, String fieldName) {
        Object obj = event.getContent().getField(fieldName);
        if (obj == null) {
            return null;
        }
        if (obj instanceof Integer) {
            return (Integer) obj;
        }
        if (obj instanceof Long) {
            return ((Long) obj).intValue();
        }
        if (obj instanceof String) {
            try {
                return Integer.parseInt((String)obj);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

}
