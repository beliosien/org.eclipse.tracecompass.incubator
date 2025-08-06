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


    /** Rate event analysis **/
    // Global event counters
    // private final static Map<String, Integer> nativeEventCounts = new HashMap<>();
    // private final static Map<String, Integer> vmEventCounts = new HashMap<>();

    // Phase-counters
    // private final static Map<String, Map<String, Integer>> nativePhaseEventCounts = new HashMap<>();
    // private final static Map<String, Map<String, Integer>> vmPhaseEventCounts = new HashMap<>();

    // Phase tracking per trace
    //private static String currentNativePhase = null;
    // private static String currentVmPhase = null;

    // for the flow analysis
    // private final static Map<String, Map<String, List<KernelEventInfo>>> nativeEventsByPhaseAndName = new HashMap<>();
    // private final static Map<String, Map<String, List<KernelEventInfo>>> vmEventsByPhaseAndName = new HashMap<>();



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
                result.add(new KernelEventInfo(
                        name,
                        ts,
                        pid != null ? pid : -1,
                        tid != null ? tid : -1,
                        processName,
                        source,
                        cpuid != null ? cpuid : -1,
                        vcpuid != null ? vcpuid : -1
                    ));
                }
            };
        trace.sendRequest(req);
        req.waitForCompletion();
        return result;
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

    private static void handleWorkloadEvent(ITmfEvent event, TraceContext context, ITmfStateSystemBuilder ss) {
        try {
            String eventName = event.getType().getName();
            Integer pid = getIntField(event, PID);
            long timestamp = event.getTimestamp().toNanos();

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

            SyncPoint syncPoint = new SyncPoint(workloadEventType, timestamp, context.type, dataValue, pid != null ? pid : -1);
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
        Long nativeWorkloadStart = null;
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
        }

        try {
            // Crée la racine "SyncPoints"
            int syncPointsRoot = ss.getQuarkAbsoluteAndAdd(SYNC_POINTS);

            for (Map.Entry<String, List<SyncPoint>> entry : syncPointGroups.entrySet()) {
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
            }

            // === Analyse par phase (compute, memory, io) ===
            String[] phases = {"workload", "compute", "memory", "io"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            int phaseRoot = ss.getQuarkAbsoluteAndAdd("PhaseDurations"); //$NON-NLS-1$
            for (String phase : phases) {
                SyncPoint nativeStart = null, nativeEnd = null;
                SyncPoint vmStart = null, vmEnd = null;

                for (SyncPoint point : syncPoints) {
                    if ((phase + "_start").equals(point.eventType)) { //$NON-NLS-1$
                        if (point.traceType == TraceType.NATIVE_UST) {
                            nativeStart = point;
                        }
                        if (point.traceType == TraceType.VM_GUEST_UST) {
                            vmStart = point;
                        }
                    }
                    if ((phase + "_end").equals(point.eventType)) { //$NON-NLS-1$
                        if (point.traceType == TraceType.NATIVE_UST) {
                            nativeEnd = point;
                        }
                        if (point.traceType == TraceType.VM_GUEST_UST) {
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

                // TODO add also native

                if (vmStart != null && vmEnd != null) {
                    analyzeCompleVirtualizationStack(phase, vmStart, vmEnd);
                }
            }
        } catch (Exception e) {
            System.err.println("Error correlating sync points: " + e.getMessage()); //$NON-NLS-1$
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

            // get only kvm exit
            List<KernelEventInfo> kvmExitEvents = hostEvents.stream()
                    .filter(event -> "kvm_x86_exit".equals(event.name) || "kvm_x86_entry".equals(event.name)) //$NON-NLS-1$ //$NON-NLS-2$
                    .collect(Collectors.toList());


            // Group guest events by process
            Map<String, List<KernelEventInfo>> guestProcessEvents = guestEvents.stream()
                    .filter(e -> !"unknown".equals(e.processName)) //$NON-NLS-1$
                    .filter(e -> e.pid == vmStart.pid)
                    .collect(Collectors.groupingBy(e -> e.processName));

            System.out.printf("\n=== UNIFIED VIRTUALIZATION FLOW: %s ===\n", phase.toUpperCase()); //$NON-NLS-1$

            // Analyze each guest process with its associated hypervisor activity
            for (Map.Entry<String, List<KernelEventInfo>> entry : guestProcessEvents.entrySet()) {
                String processName = entry.getKey();
                List<KernelEventInfo> processGuestEvents = entry.getValue();

                // Create enhanced ProcessFlowInfo with hypervisor tracking
                ProcessFlowInfo processFlow = new ProcessFlowInfo(phase, processName, true);

                // Add guest events
                for (KernelEventInfo guestEvent : processGuestEvents) {
                    processFlow.addGuestEvent(guestEvent);
                }

                // Correlate hypervisor events with guest events
                correlateHypervisorEvents(processFlow, processGuestEvents, kvmExitEvents);

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
    private static void correlateHypervisorEvents(ProcessFlowInfo processFlow,
            List<KernelEventInfo> guestEvents,
            List<KernelEventInfo> hostEvents) {

        // INDEX des host events par cpuid et par timestamp (TreeMap pour les recherches rapides)
        Map<Integer, TreeMap<Long, List<KernelEventInfo>>> hostEventIndex = new HashMap<>();

        for (KernelEventInfo hostEvent : hostEvents) {
            int cpuid = hostEvent.vcpuid;
            hostEventIndex
                .computeIfAbsent(cpuid, k -> new TreeMap<>())
                .computeIfAbsent(hostEvent.timestamp, t -> new ArrayList<>())
                .add(hostEvent);
        }

        // Fenêtre de corrélation temporelle en nanosecondes (par exemple 1ms = 1_000_000 ns)
       final long correlationWindow = 1_000_000;

       for (KernelEventInfo guestEvent : guestEvents) {
           int guestCpuid = guestEvent.cpuid;
           long guestTime = guestEvent.timestamp;

           TreeMap<Long, List<KernelEventInfo>> hostEventsForCpu = hostEventIndex.get(guestCpuid);
           if (hostEventsForCpu == null) {
               continue;
           }

        // Recherche dans la fenêtre temporelle autour de guestTime
           SortedMap<Long, List<KernelEventInfo>> subMap =
               hostEventsForCpu.subMap(guestTime - correlationWindow, true, guestTime + correlationWindow, true);

           for (List<KernelEventInfo> eventsAtTimestamp : subMap.values()) {
               for (KernelEventInfo hostEvent : eventsAtTimestamp) {
                   if (isVMExit(hostEvent)) {
                       processFlow.addVMTransition(hostEvent, true); // VM Exit
                   } else if (isVMEntry(hostEvent)) {
                       processFlow.addVMTransition(hostEvent, false); // VM Entry
                   } else {
                       processFlow.addHypervisorEvent(hostEvent, guestEvent.timestamp);
                   }
               }
           }
       }
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

        SyncPoint(String eventType, long timestamp, TraceType traceType, Integer dataValue, int pid) {
            this.eventType = eventType;
            this.timestamp = timestamp;
            this.traceType = traceType;
            this.dataValue = dataValue;
            this.pid = pid;
        }
    }

}