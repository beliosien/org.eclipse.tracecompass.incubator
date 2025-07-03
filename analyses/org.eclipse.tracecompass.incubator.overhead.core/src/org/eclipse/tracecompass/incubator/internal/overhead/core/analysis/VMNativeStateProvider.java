package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystemBuilder;
import org.eclipse.tracecompass.tmf.core.event.ITmfEvent;
import org.eclipse.tracecompass.tmf.core.statesystem.AbstractTmfStateProvider;
import org.eclipse.tracecompass.tmf.core.statesystem.ITmfStateProvider;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.experiment.TmfExperiment;

/**
 * State provider for VM/Native comparison analysis
 *
 * @author Francois Belias
 */
public class VMNativeStateProvider extends AbstractTmfStateProvider{
    private static final int VERSION = 1;
    private static final String ID = "org.eclipse.tracecompass.incubator.vm.state.provider"; //$NON-NLS-1$

    /** State system attributes */
    public static final String NATIVE_ROOT = "Native"; //$NON-NLS-1$
    public static final String VM_ROOT = "VM";  //$NON-NLS-1$
    public static final String SYNC_POINTS = "SyncPoints"; //$NON-NLS-1$
    public static final String PERFORMANCE_DELTA = "PerformanceDelta"; //$NON-NLS-1$

    private final Map<String, TraceContext> traceContexts = new HashMap<>();
    private final List<SyncPoint> syncPoints = new ArrayList<>();
    private final AtomicLong eventCounter = new AtomicLong(0);

    private static final String WORKLOAD_UST_PROVIDER = "workload_provider"; //$NON-NLS-1$

    /**
     * Constructor
     *
     * @param experiment : the experiment contains all the traces
     */
    public VMNativeStateProvider(@NonNull TmfExperiment experiment) {
        super(experiment, ID);
        initializeTraceContexts(experiment);
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    private void initializeTraceContexts(TmfExperiment experiment) {
        for (ITmfTrace trace: experiment.getTraces()) {
            String traceName = trace.getName();
            TraceType type  = determineTraceType(traceName);

            TraceContext context = new TraceContext(trace, type);
            traceContexts.put(traceName, context);
        }
    }

    private static TraceType determineTraceType(String traceName) {
        // Corrected typo: "native"
        if (traceName.toLowerCase().contains("native")) { //$NON-NLS-1$
            return TraceType.NATIVE;
        } else if (traceName.toLowerCase().contains("guest")) { //$NON-NLS-1$
            return TraceType.VM_GUEST;
        } else if (traceName.toLowerCase().contains("host")) { //$NON-NLS-1$
            return TraceType.VM_HOST;
        }
        return TraceType.UNKNOWN;
    }

    @Override
    public @NonNull ITmfStateProvider getNewInstance() {
        return new VMNativeStateProvider((TmfExperiment) getTrace());
    }

    @Override
    protected void eventHandle(@NonNull ITmfEvent event) {
        ITmfStateSystemBuilder ss = getStateSystemBuilder();
        if (ss == null) {
            return;
        }

        long eventCount = eventCounter.incrementAndGet();
        if (eventCount % 10000 == 0) {
            // Progress update every 10k events (optionnel)
        }

        try {
            String traceName = event.getTrace().getName();
            TraceContext context = traceContexts.get(traceName);

            if (context == null) {
                return;
            }

            if (isWorkloadEvent(event)) {
                handleWorkloadEvent(event, context, ss);
            } else if (isKernelEvent(event)) {
                handleKernelEvent(event, context, ss);
            }

        } catch (Exception e) {
            System.err.println("Error processing event: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static boolean isWorkloadEvent(ITmfEvent event) {
        String eventName = event.getType().getName();
        return eventName.startsWith(WORKLOAD_UST_PROVIDER + ":"); //$NON-NLS-1$
    }

    private static boolean isKernelEvent(ITmfEvent event) {
        String eventName = event.getType().getName();
        return eventName.startsWith("syscall_") || //$NON-NLS-1$
                eventName.startsWith("sched_") || //$NON-NLS-1$
                eventName.startsWith("irq_") || //$NON-NLS-1$
                eventName.startsWith("mm_"); //$NON-NLS-1$
    }

    private void handleWorkloadEvent(ITmfEvent event, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            String eventName = event.getType().getName();
            long timestamp = event.getTimestamp().toNanos();

            String[] parts = eventName.split(":"); //$NON-NLS-1$
            if (parts.length != 2) {
                return;
            }

            String workloadEventType = parts[1];

            // Get event data
            Integer dataValue = null;
            if (event.getContent().getField("iteration_count") != null) { //$NON-NLS-1$
                Object val = event.getContent().getField("iteration_count").getValue(); //$NON-NLS-1$
                if (val instanceof Integer) {
                    dataValue = (Integer) val;
                }
            } else if (event.getContent().getField("iteration") != null) { //$NON-NLS-1$
                Object val = event.getContent().getField("iteration").getValue(); //$NON-NLS-1$
                if (val instanceof Integer) {
                    dataValue = (Integer) val;
                }
            } else if (event.getContent().getField("alloc_count") != null) { //$NON-NLS-1$
                Object val = event.getContent().getField("alloc_count").getValue(); //$NON-NLS-1$
                if (val instanceof Integer) {
                    dataValue = (Integer) val;
                }
            }

            SyncPoint syncPoint = new SyncPoint(workloadEventType, timestamp, context.type, dataValue);
            syncPoints.add(syncPoint);

            String attributePath = getWorkloadAttributePath(context.type, workloadEventType);
            int attribute = ss.getQuarkAbsoluteAndAdd(attributePath);

            String stateValue = createStateValue(timestamp, dataValue);
            ss.modifyAttribute(timestamp, stateValue, attribute);

            if (isPhaseMarker(workloadEventType)) {
                handlePhaseTransition(workloadEventType, timestamp, context, ss);
            }
        } catch (Exception e) {
            System.err.println("Error handling workload event: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void handleKernelEvent(ITmfEvent event, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            String eventName = event.getType().getName();

            if (eventName.startsWith("syscall_entry_")) { //$NON-NLS-1$
                handleSyscallEntry(event, context, ss);
            } else if (eventName.startsWith("syscall_exit_")) { //$NON-NLS-1$
                handleSyscallExit(event, context, ss);
            } else if (eventName.startsWith("sched_switch")) { //$NON-NLS-1$
                handleSchedulerSwitch(event, context, ss);
            }
        } catch (Exception e) {
            System.err.println("Error handling kernel event: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void handleSyscallEntry(ITmfEvent event, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            String syscallName = extractSyscallName(event);
            if (isRelevantSyscall(syscallName)) {
                long timestamp = event.getTimestamp().toNanos();
                String attributePath = getSyscallAttributePath(context.type, syscallName);
                int attribute = ss.getQuarkAbsoluteAndAdd(attributePath);
                ss.modifyAttribute(timestamp, "ENTRY", attribute); //$NON-NLS-1$
            }
        } catch (Exception e) {
            System.err.println("Error in handleSyscallEntry: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void handleSyscallExit(ITmfEvent event, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            String syscallName = extractSyscallName(event);
            if (isRelevantSyscall(syscallName)) {
                long timestamp = event.getTimestamp().toNanos();
                String attributePath = getSyscallAttributePath(context.type, syscallName);
                int attribute = ss.getQuarkAbsoluteAndAdd(attributePath);
                ss.modifyAttribute(timestamp, "EXIT", attribute); //$NON-NLS-1$
            }
        } catch (Exception e) {
            System.err.println("Error in handleSyscallExit: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void handleSchedulerSwitch(ITmfEvent event, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            long timestamp = event.getTimestamp().toNanos();
            String attributePath = getSchedulerAttributePath(context.type);
            int attribute = ss.getQuarkAbsoluteAndAdd(attributePath);
            ss.modifyAttribute(timestamp, "SWITCH", attribute); //$NON-NLS-1$
        } catch (Exception e) {
            System.err.println("Error in handleSchedulerSwitch: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static String extractSyscallName(ITmfEvent event) {
        String eventName = event.getType().getName();
        if (eventName.startsWith("syscall_entry_")) { //$NON-NLS-1$
            return eventName.substring("syscall_entry_".length()); //$NON-NLS-1$
        } else if (eventName.startsWith("syscall_exit_")) { //$NON-NLS-1$
            return eventName.substring("syscall_exit_".length()); //$NON-NLS-1$
        }
        return eventName;
    }

    private static boolean isRelevantSyscall(String syscallName) {
        return syscallName.equals("write") || //$NON-NLS-1$
                syscallName.equals("read") || //$NON-NLS-1$
                syscallName.equals("mmap") || //$NON-NLS-1$
                syscallName.equals("munmap") || //$NON-NLS-1$
                syscallName.equals("brk") || //$NON-NLS-1$
                syscallName.equals("open") || //$NON-NLS-1$
                syscallName.equals("close"); //$NON-NLS-1$
    }

    private static String getWorkloadAttributePath(TraceType type, String eventType) {
        String root = (type == TraceType.NATIVE) ? NATIVE_ROOT : VM_ROOT;
        return root + "/Workload/" + eventType; //$NON-NLS-1$
    }

    private static String getSyscallAttributePath(TraceType type, String syscallName) {
        String root = (type == TraceType.NATIVE) ? NATIVE_ROOT : VM_ROOT;
        return root + "/Syscalls/" + syscallName; //$NON-NLS-1$
    }

    private static String getSchedulerAttributePath(TraceType type) {
        String root = (type == TraceType.NATIVE) ? NATIVE_ROOT : VM_ROOT;
        return root + "/Scheduler/switches"; //$NON-NLS-1$
    }

    private static boolean isPhaseMarker(String eventType) {
        return eventType.endsWith("_start") || eventType.endsWith("_end"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void handlePhaseTransition(String eventType, long timestamp, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            String phase = extractPhase(eventType);
            String attributePath = getPhaseAttributePath(context.type, phase);
            int attribute = ss.getQuarkAbsoluteAndAdd(attributePath);

            String state = eventType.endsWith("_start") ? "ACTIVE": "INACTIVE"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ss.modifyAttribute(timestamp, state, attribute);
        } catch (Exception e) {
            System.err.println("Error in handlePhaseTransition: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static String extractPhase(String eventType) {
        if (eventType.startsWith("compute")) { //$NON-NLS-1$
            return "compute"; //$NON-NLS-1$
        }
        if (eventType.startsWith("memory")) { //$NON-NLS-1$
            return "memory"; //$NON-NLS-1$
        }
        if (eventType.startsWith("io")) { //$NON-NLS-1$
            return "io"; //$NON-NLS-1$
        }
        return "unknown"; //$NON-NLS-1$
    }

    private static String getPhaseAttributePath(TraceType type, String phase) {
        String root = (type == TraceType.NATIVE) ? NATIVE_ROOT : VM_ROOT;
        return root + "/Phases/" + phase; //$NON-NLS-1$
    }

    private static String createStateValue(long timestamp, Integer data) {
        return timestamp + (data != null ? ":" + data : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public void done() {
        correlateSyncPoints();
        super.done();
    }

    private void correlateSyncPoints() {
        // Group sync points by event type and data value
        Map<String, List<SyncPoint>> syncPointGroups = new HashMap<>();

        for (SyncPoint point : syncPoints) {
            String key = point.eventType + ":" + point.dataValue; //$NON-NLS-1$
            syncPointGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(point);
        }

        ITmfStateSystemBuilder ss = getStateSystemBuilder();
        if (ss == null) {
            return;
        }

        // 1. Récupère les timestamps de workload_start natif et VM
        Long nativeWorkloadStart = null;
        Long vmWorkloadStart = null;
        for (SyncPoint point : syncPoints) {
            if ("workload_start".equals(point.eventType)) { //$NON-NLS-1$
                if (point.traceType == TraceType.NATIVE) {
                    nativeWorkloadStart = point.timestamp;
                } else if (point.traceType == TraceType.VM_GUEST) {
                    vmWorkloadStart = point.timestamp;
                }
            }
        }
        if (nativeWorkloadStart == null || vmWorkloadStart == null) {
            System.err.println("workload_start missing for native or VM."); //$NON-NLS-1$
            return;
        }

        try {
            // Crée la racine "SyncPoints"
            int syncPointsRoot = ss.getQuarkAbsoluteAndAdd(SYNC_POINTS);

            for (Map.Entry<String, List<SyncPoint>> entry : syncPointGroups.entrySet()) {
                List<SyncPoint> points = entry.getValue();

                SyncPoint nativePoint = null;
                SyncPoint vmPoint = null;

                for (SyncPoint point : points) {
                    if (point.traceType == TraceType.NATIVE) {
                        nativePoint = point;
                    } else if (point.traceType == TraceType.VM_GUEST) {
                        vmPoint = point;
                    }
                }

                if (nativePoint != null && vmPoint != null) {
                    // Correction : calcul du temps écoulé depuis workload_start pour chaque trace
                    long nativeElapsed = nativePoint.timestamp - nativeWorkloadStart;
                    long vmElapsed = vmPoint.timestamp - vmWorkloadStart;
                    long deltaTime = vmElapsed - nativeElapsed;
                    double deltaPercent = nativeElapsed == 0 ? 0.0 : ((double) deltaTime / (double) nativeElapsed) * 100.0;

                    // Crée le sous-attribut sous "SyncPoints"
                    int attribute = ss.getQuarkRelativeAndAdd(syncPointsRoot, entry.getKey());
                    String correlationData = String.format("native:%d,vm:%d,delta:%d,percent:%.2f", //$NON-NLS-1$
                            nativeElapsed, vmElapsed, deltaTime, deltaPercent);

                    // maybe use this formula instead max(vmElapsed, nativeElapsed) + workload_start natif/VM pour le timestamp global
                    long correlationTime = Math.max(nativePoint.timestamp, vmPoint.timestamp);
                    ss.modifyAttribute(correlationTime, correlationData, attribute);
                }
            }
        } catch (Exception e) {
            System.err.println("Error correlating sync points: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Context information for each trace
     */
    private static class TraceContext {
        final ITmfTrace trace;
        final TraceType type;

        TraceContext(ITmfTrace trace, TraceType type) {
            this.trace = trace;
            this.type =  type;
        }
    }

    /**
     * Represent a synchronization point in the trace
     */
    private static class SyncPoint {
        final String eventType;
        final long timestamp;
        final TraceType traceType;
        final Integer dataValue;

        SyncPoint(String eventType, long timestamp, TraceType traceType, Integer dataValue) {
            this.eventType = eventType;
            this.timestamp = timestamp;
            this.traceType = traceType;
            this.dataValue = dataValue;
        }
    }

    /*
     * Types of traces in the experiment
     */
    private enum TraceType{
        NATIVE,
        VM_GUEST,
        VM_HOST,
        UNKNOWN
    }
}