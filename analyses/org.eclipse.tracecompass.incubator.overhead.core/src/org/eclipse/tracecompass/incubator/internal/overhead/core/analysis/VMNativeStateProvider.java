package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystemBuilder;
import org.eclipse.tracecompass.tmf.core.event.ITmfEvent;
import org.eclipse.tracecompass.tmf.core.request.ITmfEventRequest;
import org.eclipse.tracecompass.tmf.core.request.TmfEventRequest;
import org.eclipse.tracecompass.tmf.core.statesystem.AbstractTmfStateProvider;
import org.eclipse.tracecompass.tmf.core.statesystem.ITmfStateProvider;
import org.eclipse.tracecompass.tmf.core.timestamp.ITmfTimestamp;
import org.eclipse.tracecompass.tmf.core.timestamp.TmfTimeRange;
import org.eclipse.tracecompass.tmf.core.timestamp.TmfTimestamp;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.experiment.TmfExperiment;

/**
 * State provider for VM/Native comparison analysis
 *
 * @author Francois Belias
 *
 */
// TODO A LOT of cleaning in this class
public class VMNativeStateProvider extends AbstractTmfStateProvider{
    private static final int VERSION = 1;
    private static final String ID = "org.eclipse.tracecompass.incubator.vm.state.provider"; //$NON-NLS-1$

    /** State system attributes */
    public static final String NATIVE_ROOT = "Native"; //$NON-NLS-1$
    @SuppressWarnings("javadoc")
    public static final String VM_ROOT = "VM";  //$NON-NLS-1$
    @SuppressWarnings("javadoc")
    public static final String SYNC_POINTS = "SyncPoints"; //$NON-NLS-1$
    @SuppressWarnings("javadoc")
    public static final String PERFORMANCE_DELTA = "PerformanceDelta"; //$NON-NLS-1$

    private final static Map<String, TraceContext> traceContexts = new HashMap<>();
    private final static List<SyncPoint> syncPoints = new ArrayList<>();
    private final AtomicLong eventCounter = new AtomicLong(0);

    private static final String WORKLOAD_UST_PROVIDER = "workload_provider"; //$NON-NLS-1$

    private static final String PID = "context._vpid"; //$NON-NLS-1$
    private static final String TID = "context._vtid"; //$NON-NLS-1$
    private static final String PROCESS_NAME = "context._procname"; //$NON-NLS-1$
    private static final String CPUID = "context.cpu_id"; //$NON-NLS-1$
    private static final String VCPUID = "vcpu_id"; //$NON-NLS-1$
    private static final String EXIT_REASON = "exit_reason";  //$NON-NLS-1$
    private static final String MARKER = "./VM_ANALYSIS.txt"; //$NON-NLS-1$
    private static final String EVENT_MARKER = "syscall_entry_openat"; //$NON-NLS-1$
    private boolean begin = true;

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

    private static void initializeTraceContexts(TmfExperiment experiment) {
        for (ITmfTrace trace: experiment.getTraces()) {
            String traceName = trace.getName();
            TraceType type  = determineTraceType(traceName);

            TraceContext context = new TraceContext(trace, type);
            traceContexts.put(traceName, context);
        }
    }

    private static TraceType determineTraceType(String traceName) {
        if (traceName.toLowerCase().contains("native/kernel")) { //$NON-NLS-1$
            return TraceType.NATIVE_KERNEL;
        } else if (traceName.toLowerCase().contains("native/ust")){ //$NON-NLS-1$
            return TraceType.NATIVE_UST;
        } else if (traceName.toLowerCase().contains("guest/kernel")) { //$NON-NLS-1$
            return TraceType.VM_GUEST_KERNEL;
        } else if (traceName.toLowerCase().contains("guest/ust")) { //$NON-NLS-1$
            return TraceType.VM_GUEST_UST;
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

        } catch (Exception a) {
            System.err.println("Error processing event: " + a.getMessage()); //$NON-NLS-1$
        }
    }


    private static boolean isWorkloadEvent(ITmfEvent event) {
        String eventName = event.getType().getName();

        if (eventName.equals(EVENT_MARKER)) {
            Object filenameField = event.getContent().getField("filename"); //$NON-NLS-1$
            if (filenameField == null) {
                return false;
            }

            String value = filenameField.toString();
            String[] words = value.split("="); //$NON-NLS-1$
            if (words.length == 0 || words.length > 2) {
                return false;
            }

            if (words[1].contains(MARKER)) {
                return true;
            }

            return false;
        }

        return eventName.startsWith(WORKLOAD_UST_PROVIDER + ":"); //$NON-NLS-1$
    }


    private static boolean isKernelEvent(ITmfEvent event) {
        String eventName = event.getType().getName();
        return eventName.startsWith("syscall_") || //$NON-NLS-1$
                eventName.startsWith("sched_") || //$NON-NLS-1$
                eventName.startsWith("irq_") || //$NON-NLS-1$
                eventName.startsWith("mm_") || //$NON-NLS-1$
                eventName.startsWith("kvm_"); //$NON-NLS-1$  I want to also get kvm entry exit xapic etc ...
    }


 // Méthode utilitaire pour extraire les kernel events entre deux timestamps
    private static List<KernelEventInfo> extractKernelEventsBetween(
            ITmfTrace trace,
            long startNanos,
            long endNanos, TraceType source
    ) throws InterruptedException {
        List<KernelEventInfo> result = new ArrayList<>();

        ITmfTimestamp startTs = TmfTimestamp.fromNanos(startNanos);
        ITmfTimestamp endTs = TmfTimestamp.fromNanos(endNanos);
        TmfTimeRange range = new TmfTimeRange(startTs, endTs);

        ITmfEventRequest req = new TmfEventRequest(
            ITmfEvent.class,
            range,
            0,
            ITmfEventRequest.ALL_DATA,
            ITmfEventRequest.ExecutionType.FOREGROUND
        ) {
            @Override
            public void handleData(ITmfEvent event) {
                long ts = event.getTimestamp().toNanos();
                if (ts < startNanos || ts > endNanos) {
                    return;
                }
                String name = event.getType().getName();
                Integer pid = getIntField(event, PID);
                Integer tid = getIntField(event, TID);
                Integer cpuid = getIntField(event, CPUID);
                Integer vcpuid = getIntField(event, VCPUID);
                String processName = getProcessName(event);
                String exitReason = getExitReason(event);
                result.add(new KernelEventInfo(
                        name,
                        ts,
                        pid != null ? pid : -1,
                        tid != null ? tid : -1,
                        processName,
                        source,
                        cpuid != null ? cpuid : -1,
                        vcpuid != null ? vcpuid : -1,
                        exitReason
                    ));
                }
            };
        trace.sendRequest(req);
        req.waitForCompletion();
        return result;
    }


    private static String getExitReason(ITmfEvent event) {
        Object exitField = event.getContent().getField(EXIT_REASON);
        if (exitField == null) {
            return "UNKNOWN_EXIT_REASON"; //$NON-NLS-1$
        }

        String value = exitField.toString();
        String[] words = value.split("="); //$NON-NLS-1$
        if (words.length == 0 || words.length > 2) {
            return null;
        }
        int code = Integer.parseInt(words[1]);
        return ExitReasonMap.getExitReasonName(code);

    }

    private static Integer extractVcpuFromProcName(ITmfEvent event) {
        Object commField = event.getContent().getField(PROCESS_NAME);
        if (commField == null) {
            return null;
        }

        String procName = commField.toString();

        if (procName == null) {
            return null;
        }

        Pattern p = Pattern.compile("CPU (\\d+)/KVM"); //$NON-NLS-1$
        Matcher m = p.matcher(procName);

        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }

        return null;
    }


    // get the field PID/TID
    private static Integer getIntField(ITmfEvent event, String fieldName) {

        if (event.getType().getName().equals("kvm_x86_entry") && fieldName.equals(VCPUID)) { //$NON-NLS-1$
            return extractVcpuFromProcName(event);
        }



        Object obj = event.getContent().getField(fieldName);
        if (obj == null) {
            return null;
        }

        String value = obj.toString();
        String[] words = value.split("="); //$NON-NLS-1$
        if (words.length == 0 || words.length > 2) {
            return null;
        }

        try {
            return Integer.parseInt(words[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // get the process name
    private static String getProcessName(ITmfEvent event) {
        Object commField = event.getContent().getField(PROCESS_NAME);

        if (commField == null) {
            return "unknown"; //$NON-NLS-1$
        }

        String value = commField.toString();
        String[] words = value.split("="); //$NON-NLS-1$
        if (words.length == 0 || words.length > 2) {
            return "unknown"; //$NON-NLS-1$
        }

        return words[1];
    }

    private void handleWorkloadEvent(ITmfEvent event, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            String eventName = event.getType().getName();
            Integer pid = getIntField(event, PID);
            String procName = getProcessName(event);
            long timestamp = event.getTimestamp().toNanos();

            if (eventName.equals(EVENT_MARKER)) {
                if (this.begin) {
                    SyncPoint syncPoint = new SyncPoint("workload_start", timestamp, //$NON-NLS-1$
                            context.type, 0,
                            pid != null ? pid : -1, procName);
                    syncPoints.add(syncPoint);
                    this.begin = false;

                } else {
                    SyncPoint syncPoint = new SyncPoint("workload_end", timestamp, //$NON-NLS-1$
                            context.type, 0,
                            pid != null ? pid : -1, procName);
                    syncPoints.add(syncPoint);
                }
                return;
            }

            String[] parts = eventName.split(":"); //$NON-NLS-1$
            if (parts.length != 2) {
                return;
            }

            String workloadEventType = parts[1];
            /*if (workloadEventType.endsWith("_start")) { //$NON-NLS-1$
                String phase = extractPhase(workloadEventType);
                if (context.type == TraceType.NATIVE_UST) {
                    currentNativePhase = phase;
                }
                if (context.type == TraceType.VM_GUEST_UST) {
                    currentVmPhase = phase;
                }
            } else if (workloadEventType.endsWith("_end")) { //$NON-NLS-1$
                if (context.type == TraceType.NATIVE_UST) {
                    currentNativePhase = null;
                }
                if (context.type == TraceType.VM_GUEST_UST) {
                    currentVmPhase = null;
                }
            }*/


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

            SyncPoint syncPoint = new SyncPoint(workloadEventType, timestamp,
                    context.type, dataValue,
                    pid != null ? pid : -1, procName);
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
                //String eventClass = null;

                if (eventName.startsWith("syscall_entry_")) { //$NON-NLS-1$
                    //eventClass = "syscall"; //$NON-NLS-1$
                    handleSyscallEntry(event, context, ss);
                } else if (eventName.startsWith("syscall_exit_")) { //$NON-NLS-1$
                    //eventClass = "syscall"; //$NON-NLS-1$
                    handleSyscallExit(event, context, ss);
                } else if (eventName.startsWith("sched_switch")) { //$NON-NLS-1$
                    //eventClass = "context_switch"; //$NON-NLS-1$
                    handleSchedulerSwitch(event, context, ss);
                } /*else if (eventName.startsWith("mm_page_fault")) { //$NON-NLS-1$
                    //eventClass = "page_fault"; //$NON-NLS-1$
                }*/

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
        String root = (type == TraceType.NATIVE_UST) ? NATIVE_ROOT : VM_ROOT;
        return root + "/Workload/" + eventType; //$NON-NLS-1$
    }

    private static String getSyscallAttributePath(TraceType type, String syscallName) {
        String root = (type == TraceType.NATIVE_KERNEL) ? NATIVE_ROOT : VM_ROOT;
        return root + "/Syscalls/" + syscallName; //$NON-NLS-1$
    }

    private static String getSchedulerAttributePath(TraceType type) {
        String root = (type == TraceType.NATIVE_KERNEL) ? NATIVE_ROOT : VM_ROOT;
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
        return "workload"; //"unknown"; //$NON-NLS-1$
    }

    private static String getPhaseAttributePath(TraceType type, String phase) {
        String root = (type == TraceType.NATIVE_UST) ? NATIVE_ROOT : VM_ROOT;
        return root + "/Phases/" + phase; //$NON-NLS-1$
    }

    private static String createStateValue(long timestamp, Integer data) {
        return timestamp + (data != null ? ":" + data : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public void done() {
        rateEvent();
        correlateSyncPoints();
        super.done();
    }

    private void rateEvent() {
        ITmfStateSystemBuilder ss = getStateSystemBuilder();
        if (ss == null) {
            return;
        }

        // globally
        /*int eventRateRoot = ss.getQuarkAbsoluteAndAdd("EventRates"); //$NON-NLS-1$
        for (String eventClass: List.of("syscall", "context_switch", "page_fault")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            int natAttr = ss.getQuarkRelativeAndAdd(eventRateRoot, "native_" + eventClass); //$NON-NLS-1$
            int vmAttr = ss.getQuarkRelativeAndAdd(eventRateRoot, "vm_" + eventClass); //$NON-NLS-1$
            ss.modifyAttribute(ss.getCurrentEndTime(), nativeEventCounts.getOrDefault(eventClass, 0), natAttr);
            ss.modifyAttribute(ss.getCurrentEndTime(), vmEventCounts.getOrDefault(eventClass, 0), vmAttr);
        }

        // By phase
        int phaseRateRoot = ss.getQuarkAbsoluteAndAdd("EventRatesByPhase"); //$NON-NLS-1$
        for (String phase: List.of("compute", "memory", "io")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map<String, Integer> natMap = nativePhaseEventCounts.getOrDefault(phase, Map.of());
            Map<String, Integer> vmMap = vmPhaseEventCounts.getOrDefault(phase, Map.of());

            for (String eventClass : List.of("syscall", "context_switch", "page_fault")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                int natAttr = ss.getQuarkRelativeAndAdd(phaseRateRoot, phase + "_native_" + eventClass); //$NON-NLS-1$
                int vmAttr = ss.getQuarkRelativeAndAdd(phaseRateRoot, phase + "_vm_" + eventClass); //$NON-NLS-1$
                ss.modifyAttribute(ss.getCurrentEndTime(), natMap.getOrDefault(eventClass, 0), natAttr);
                ss.modifyAttribute(ss.getCurrentEndTime(), vmMap.getOrDefault(eventClass, 0), vmAttr);
            }
        }*/
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
        /*Long nativeWorkloadStart = null;
        Long vmWorkloadStart = null;
        for (SyncPoint point : syncPoints) {
            if ("workload_start".equals(point.eventType)) { //$NON-NLS-1$
                if (point.traceType == TraceType.NATIVE_UST) {
                    nativeWorkloadStart = point.timestamp;
                } else if (point.traceType == TraceType.VM_GUEST_UST) {
                    vmWorkloadStart = point.timestamp;
                }
            }
        }
        if (nativeWorkloadStart == null || vmWorkloadStart == null) {
            System.err.println("workload_start missing for native or VM."); //$NON-NLS-1$
            return;
        }*/

        try {
            // Crée la racine "SyncPoints"
            // int syncPointsRoot = ss.getQuarkAbsoluteAndAdd(SYNC_POINTS);

            /*for (Map.Entry<String, List<SyncPoint>> entry : syncPointGroups.entrySet()) {
                List<SyncPoint> points = entry.getValue();

                SyncPoint nativePoint = null;
                SyncPoint vmPoint = null;

                for (SyncPoint point : points) {
                    if (point.traceType == TraceType.NATIVE_UST) {
                        nativePoint = point;
                    } else if (point.traceType == TraceType.VM_GUEST_UST) {
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

                    // On utilise max(vmElapsed, nativeElapsed) + workload_start natif/VM pour le timestamp global
                    long correlationTime = Math.max(nativePoint.timestamp, vmPoint.timestamp);
                    ss.modifyAttribute(correlationTime, correlationData, attribute);
                }
            }*/

            // === Analyse par phase (compute, memory, io) ===
            String[] phases = {"workload", "compute", "memory", "io"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            int phaseRoot = ss.getQuarkAbsoluteAndAdd("PhaseDurations"); //$NON-NLS-1$
            for (String phase : phases) {
                SyncPoint nativeStart = null, nativeEnd = null;
                SyncPoint vmStart = null, vmEnd = null;

                for (SyncPoint point : syncPoints) {
                    if ((phase + "_start").equals(point.eventType)) { //$NON-NLS-1$
                        if (point.traceType == TraceType.NATIVE_KERNEL) {
                            nativeStart = point;
                        }
                        if (point.traceType == TraceType.VM_GUEST_KERNEL) {
                            vmStart = point;
                        }
                    }
                    if ((phase + "_end").equals(point.eventType)) { //$NON-NLS-1$
                        if (point.traceType == TraceType.NATIVE_KERNEL) {
                            nativeEnd = point;
                        }
                        if (point.traceType == TraceType.VM_GUEST_KERNEL) {
                            vmEnd = point;
                        }
                    }
                }

                if (nativeStart != null && nativeEnd != null && vmStart != null && vmEnd != null) {

                    long nativeDuration = nativeEnd.timestamp - nativeStart.timestamp;
                    long vmDuration = vmEnd.timestamp - vmStart.timestamp;

                    long deltaDuration = vmDuration - nativeDuration;
                    double overheadPercent = nativeDuration == 0 ? 0.0 : ((double) deltaDuration / (double) nativeDuration) * 100.0;

                    // Stocke dans le state system sous "PhaseDurations"
                    int attr = ss.getQuarkRelativeAndAdd(phaseRoot, phase);
                    String phaseData = String.format("native:%d,vm:%d,delta:%d,percent:%.2f", //$NON-NLS-1$
                            nativeDuration, vmDuration, deltaDuration, overheadPercent);

                    // Utilise la fin de la phase comme timestamp pour l'attribut
                    long correlationTime = Math.max(nativeEnd.timestamp, vmEnd.timestamp);
                    ss.modifyAttribute(correlationTime, phaseData, attr);
                }

                if (nativeStart != null && nativeEnd != null) {
                    analyzeNatifStack(phase, nativeStart, nativeEnd);
                }

                if (vmStart != null && vmEnd != null) {
                    analyzeCompleVirtualizationStack(phase, vmStart, vmEnd);
                }
            }
        } catch (Exception e) {
            System.err.println("Error correlating sync points: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void analyzeNatifStack(String phase, SyncPoint nativeStart,
            SyncPoint nativeEnd) {

        if (nativeStart == null || nativeEnd == null) {
            return;
        }

        try {
            // Get the natif trace
            ITmfTrace nativeTrace = traceContexts.values().stream()
                    .filter(ctx -> ctx.type == TraceType.NATIVE_KERNEL)
                    .map(ctx -> ctx.trace)
                    .findFirst().orElse(null);

            if (nativeTrace == null) {
                System.out.println("Missing native trace for the analysis"); //$NON-NLS-1$
                return;
            }

            List<KernelEventInfo> nativeEvents = extractKernelEventsBetween(
                    nativeTrace, nativeStart.timestamp, nativeEnd.timestamp, TraceType.NATIVE_KERNEL);


            // Group guest events by process
            Map<String, List<KernelEventInfo>> nativeProcessEvents = nativeEvents.stream()
                    .filter(e -> !"unknown".equals(e.processName)) //$NON-NLS-1$
                    .filter(e -> e.pid == nativeStart.pid)
                    .filter(e -> e.processName.equals(nativeStart.procName))
                    .collect(Collectors.groupingBy(e -> e.processName));


            System.out.printf("\n=== UNIFIED Natif FLOW: %s ===\n", phase.toUpperCase()); //$NON-NLS-1$

            // Analyze each process
            for (Map.Entry<String, List<KernelEventInfo>> entry : nativeProcessEvents.entrySet()) {
                String processName = entry.getKey();
                List<KernelEventInfo> processNativeEvents = entry.getValue();

                // Create the process flow for the native system
                ProcessFlowInfo processFlow = new ProcessFlowInfo(phase, processName); // here we don't want to track the hypervisor

                for (KernelEventInfo event : processNativeEvents) {
                    processFlow.addEvent(event);
                }

                // Finalize and print the unified flow
                processFlow.finalizeFlow();
                processFlow.printUnifiedFlow();
            }



        } catch (Exception e) {
            System.err.println("Error in complet native analysis: " + e.getMessage()); //$NON-NLS-1$
        }
    }


    private static void analyzeCompleVirtualizationStack(String phase,
            SyncPoint vmStart,
            SyncPoint vmEnd) {

        if (vmStart == null || vmEnd == null) {
            return;
        }

        try {
            // Get guest kernel trace
            ITmfTrace guestTrace = traceContexts.values().stream()
                    .filter(ctx -> ctx.type == TraceType.VM_GUEST_KERNEL)
                    .map(ctx -> ctx.trace)
                    .findFirst().orElse(null);

            // Get host kernel trace
            ITmfTrace hostTrace = traceContexts.values().stream()
                    .filter(ctx -> ctx.type == TraceType.VM_HOST)
                    .map(ctx -> ctx.trace)
                    .findFirst().orElse(null);

            if (guestTrace == null || hostTrace == null) {
                System.out.println("Missing guest or host trace for complete analysis"); //$NON-NLS-1$
                return;
            }

            // Extract events from both traces in the same time window
            List<KernelEventInfo> guestEvents = extractKernelEventsBetween(
                    guestTrace, vmStart.timestamp, vmEnd.timestamp, TraceType.VM_GUEST_KERNEL);


            List<KernelEventInfo> hostEvents = extractKernelEventsBetween(
                    hostTrace, vmStart.timestamp, vmEnd.timestamp, TraceType.VM_HOST);


            // Group guest events by process
            Map<String, List<KernelEventInfo>> guestProcessEvents = guestEvents.stream()
                    .filter(e -> !"unknown".equals(e.processName)) //$NON-NLS-1$
                    .filter(e -> e.pid == vmStart.pid)
                    .filter(e -> e.processName.equals(vmStart.procName))
                    .collect(Collectors.groupingBy(e -> e.processName));



            System.out.printf("\n=== UNIFIED VIRTUALIZATION FLOW: %s ===\n", phase.toUpperCase()); //$NON-NLS-1$

            // Analyze each guest process with its associated hypervisor activity

            for (Map.Entry<String, List<KernelEventInfo>> entry : guestProcessEvents.entrySet()) {
                String processName = entry.getKey();
                List<KernelEventInfo> processGuestEvents = entry.getValue();

                // Create enhanced ProcessFlowInfo with hypervisor tracking
                ProcessFlowInfo processFlow = new ProcessFlowInfo(phase, processName, true, vmStart.pid);

                // Correlate hypervisor events with guest events
                correlateHypervisorEvents(processFlow, processGuestEvents, hostEvents);

                // Finalize and print the unified flow
                processFlow.finalizeFlow();
                processFlow.printUnifiedFlow();
            }

        } catch (Exception e) {
            System.err.println("Error in complete virtualization analysis: " + e.getMessage()); //$NON-NLS-1$
        }

    }

    /**
     * Correlate hypervisor events with guest process events
     * Optimized: using index to avoid iterating on the entire list
     */
    // TODO find a more efficient way
    private static void correlateHypervisorEvents(ProcessFlowInfo processFlow,
            List<KernelEventInfo> guestEvents,
            List<KernelEventInfo> hostEvents) {

        // Step 1: Filter guest events for the target thread only
        List<KernelEventInfo> relevantGuestEvents = guestEvents.stream()
                .filter(e -> processFlow.isTrackingThread(e.tid))
                .sorted(Comparator.comparing(e -> e.timestamp))
                .collect(Collectors.toList());

        // Step 2: Add guest events first to establish vCPU mapping
        for (KernelEventInfo guestEvent : relevantGuestEvents) {
            processFlow.addGuestEvent(guestEvent);
        }

        // Step 3: Get the target vCPU ID after establishing the mapping
        //processFlow.SetTargetVcpuId(relevantGuestEvents.get(0).cpuid);

        Integer targetVcpuId = processFlow.getTargetVcpuId();
        if (targetVcpuId == null) {
            System.err.println("Warning: Could not establish vCPU mapping for thread " + //$NON-NLS-1$
                              processFlow.getTargetThreadId());
            return;
        }

        // Step 4: Filter VM transitions for our target vCPU only
        List<KernelEventInfo> relevantVmTransitions = hostEvents.stream()
                .filter(e -> (isVMExit(e) || isVMEntry(e)) && e.vcpuid == targetVcpuId)
                .sorted(Comparator.comparing(e -> e.timestamp))
                .collect(Collectors.toList());

         // Step 5: Build VM execution periods to understand when our vCPU is running
         // List<VMExecutionPeriod> vmPeriods = buildVMExecutionPeriods(relevantVmTransitions);

        // Step 6: Filter host events that could be related to our vCPU
        List<KernelEventInfo> otherHostEvents = hostEvents.stream()
                .filter(e -> !isVMExit(e) && !isVMEntry(e))
                .sorted(Comparator.comparing(e -> e.timestamp))
                .collect(Collectors.toList());

        // Step 7: Create unified timeline
        List<TimedEvent> timeline = new ArrayList<>();

        for (KernelEventInfo guestEvent : relevantGuestEvents) {
            timeline.add(new TimedEvent(guestEvent, EventType.GUEST));
        }

        for (KernelEventInfo vmEvent : relevantVmTransitions) {
            timeline.add(new TimedEvent(vmEvent, isVMExit(vmEvent) ? EventType.VM_EXIT : EventType.VM_ENTRY));
        }

        for (KernelEventInfo hostEvent : otherHostEvents) {
            timeline.add(new TimedEvent(hostEvent, EventType.HOST));
        }

        // Step 8: Sort by timestamp
        timeline.sort(Comparator.comparing(te -> te.event.timestamp));

        // Step 9: Process events chronologically with migration awareness
        VMExecutionState vmState = new VMExecutionState();

        for (TimedEvent timedEvent : timeline) {
            KernelEventInfo event = timedEvent.event;

            switch (timedEvent.type) {
                case VM_ENTRY:
                    vmState.enterGuest(event);
                    processFlow.addVMTransition(event, false);
                    System.out.printf("VM_ENTRY: vCPU %d -> CPU %d at %d\n", //$NON-NLS-1$
                                     event.vcpuid, event.cpuid, event.timestamp);
                    break;

                case VM_EXIT:
                    vmState.exitGuest(event);
                    processFlow.addVMTransition(event, true);
                    System.out.printf("VM_EXIT: vCPU %d from CPU %d at %d (reason: %s)\n", //$NON-NLS-1$
                                     event.vcpuid, event.cpuid, event.timestamp, event.exitReason);
                    break;

                case GUEST:
                    if (vmState.isInGuest()) {
                        System.out.printf("GUEST: %s (TID:%d, CPU:%d->vCPU:%d) at %d\n", //$NON-NLS-1$
                                         event.name, event.tid, event.cpuid, targetVcpuId, event.timestamp);
                        //processFlow.addGuestEvent(event);
                    } else {
                        System.err.printf("Warning: Guest event outside VM execution: %s at %d\n", //$NON-NLS-1$
                                         event.name, event.timestamp);
                    }
                    break;

                case HOST:
                    if (vmState.isInHypervisorOverhead() &&
                        isHostEventRelevant(event, vmState)) {
                        processFlow.addHypervisorEvent(event, vmState.getLastExitTimestamp());
                        System.out.printf("HOST: %s on CPU %d at %d (overhead for vCPU %d)\n", //$NON-NLS-1$
                                         event.name, event.cpuid, event.timestamp, targetVcpuId);
                    }
                    break;
            default:
                break;
            }
        }

        processFlow.finalizeFlow();
    }

    // Helper class to track VM execution state with migration awareness
    private static class VMExecutionState {
        private boolean inGuest = false;
        private long lastExitTimestamp = -1;
        private int lastExitCpuId = -1;
        private int currentPhysicalCpu = -1;

        void enterGuest(KernelEventInfo vmEntry) {
            inGuest = true;
            currentPhysicalCpu = vmEntry.cpuid;
            // Note: Physical CPU may have changed since last exit
        }

        void exitGuest(KernelEventInfo vmExit) {
            inGuest = false;
            lastExitTimestamp = vmExit.timestamp;
            lastExitCpuId = vmExit.cpuid;
            currentPhysicalCpu = vmExit.cpuid;
        }

        boolean isInGuest() {
            return inGuest;
        }

        boolean isInHypervisorOverhead() {
            return !inGuest && lastExitTimestamp != -1;
        }

        long getLastExitTimestamp() {
            return lastExitTimestamp;
        }

        int getLastExitCpuId() {
            return lastExitCpuId;
        }
    }

    // Check if a host event is relevant during hypervisor overhead
    private static boolean isHostEventRelevant(KernelEventInfo hostEvent, VMExecutionState vmState) {
        // Accept events on the CPU that handled the VM exit
        if (hostEvent.cpuid == vmState.getLastExitCpuId()) {
            return true;
        }

        // TODO maybe there is more events

        return false;
    }

    /**
     * Check if an event is a VM exit
     */
    private static boolean isVMExit(KernelEventInfo event) {
        return event.name.contains("kvm_x86_exit"); //$NON-NLS-1$

    }

    /**
     * Check if an event is a VM entry
     */
    private static boolean isVMEntry(KernelEventInfo event) {
        return event.name.contains("kvm_x86_entry"); //$NON-NLS-1$

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
        final int pid;
        final String procName;

        SyncPoint(String eventType, long timestamp, TraceType traceType, Integer dataValue, int pid, String procName) {
            this.eventType = eventType;
            this.timestamp = timestamp;
            this.traceType = traceType;
            this.dataValue = dataValue;
            this.pid = pid;
            this.procName = procName;
        }
    }


    // Helper classes
    private static class TimedEvent {
        final KernelEventInfo event;
        final EventType type;

        TimedEvent(KernelEventInfo event, EventType type) {
            this.event = event;
            this.type = type;
        }
    }

    private enum EventType {
        VM_ENTRY, VM_EXIT, GUEST, HOST
    }
}