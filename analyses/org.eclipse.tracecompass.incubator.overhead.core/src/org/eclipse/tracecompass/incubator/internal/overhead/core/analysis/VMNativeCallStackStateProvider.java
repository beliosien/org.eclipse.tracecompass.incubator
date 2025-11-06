package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;

import static org.eclipse.tracecompass.common.core.NonNullUtils.checkNotNull;

import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.analysis.profiling.core.callstack.CallStackAnalysis;
import org.eclipse.tracecompass.analysis.profiling.core.callstack.CallStackStateProvider;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystemBuilder;
import org.eclipse.tracecompass.statesystem.core.statevalue.ITmfStateValue;
import org.eclipse.tracecompass.statesystem.core.statevalue.TmfStateValue;
import org.eclipse.tracecompass.tmf.core.event.ITmfEvent;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;

/**
 * provider for VM Native CallStack analysis - structures native flow for flame graph
 * Based on LttngUstCallStackProvider pattern
 *
 * @author philippe
 */

/**
 * use this as an example
 *  Timestamp   Channel CPU Event type  Contents    TID Prio    PID Source
15:56:54.035 629 906    my_channel_0    0   syscall_entry_close fd=3, context.packet_seq_num=0, context.cpu_id=0, context._vtid=3422, context._vpid=3422, context._procname="sysbench", context._prio=20    3422    20  3422    [fs/open.c:0]
 */

public class VMNativeCallStackStateProvider extends CallStackStateProvider {

    private static final int VERSION = 1;

    // Field names
    private static final String PID = "context._vpid"; //$NON-NLS-1$
    private static final String TID = "context._vtid"; //$NON-NLS-1$
    private static final String VCPUID = "vcpu_id"; //$NON-NLS-1$
    private static final String CPUID = "context.cpu_id"; //$NON-NLS-1$
    private static final String PROCESS_NAME = "context._procname"; //$NON-NLS-1$
    private static final String EVENT_MARKER = "syscall_entry_openat"; //$NON-NLS-1$
    private static final String MARKER = "./VM_ANALYSIS.txt"; //$NON-NLS-1$

    // Tracking state
    private SyncPoint nativeStart = null;
    private SyncPoint nativeEnd = null;
    private boolean beginNative = true;
    private String processName = ""; //$NON-NLS-1$
    private String threadName = ""; //$NON-NLS-1$

    // Tracking state of the vm
    private VMExecutionState vmState = new VMExecutionState();


    // Track ponctual events
    private Stack<PendingEvent> pendingPunctualEvents = new Stack<>();

    // Detect automatically which process flow to build
    private EnvironmentType environmentType = null;


    /**
     * Constructor
     *
     * @param experiment the experiment containing all traces
     */
    public VMNativeCallStackStateProvider(@NonNull ITmfTrace experiment) {
        super(experiment);
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public VMNativeCallStackStateProvider getNewInstance() {
        return new VMNativeCallStackStateProvider(getTrace());
    }

    @Override
    protected boolean considerEvent(@NonNull ITmfEvent event) {
        String traceName = event.getTrace().getName();

        // Auto detect environment type on first event
        if (this.environmentType == null) {
            if (traceName.contains("vm/guest") || traceName.contains("vm/host")) { //$NON-NLS-1$ //$NON-NLS-2$
                this.environmentType = EnvironmentType.VIRTUALIZED;
            } else if (traceName.contains("native/kernel")) { //$NON-NLS-1$
                this.environmentType = EnvironmentType.NATIVE;
            } else {
                return false; // Unknown trace type
            }
        }

        // Filter by environment type
        if (this.environmentType == EnvironmentType.VIRTUALIZED) {
            return this.considerVirtualizedEvent(event, traceName);
        }
        return this.considerNativeEvent(event, traceName);
    }

    @Override
    protected int getProcessId(@NonNull ITmfEvent event) {
        Integer pid = getIntField(event, PID);
        return pid != null ? pid : UNKNOWN_PID;
    }

    @Override
    protected long getThreadId(@NonNull ITmfEvent event) {
        Integer tid = getIntField(event, TID);
        return tid != null ? tid.longValue() : UNKNOWN_PID;
    }

    @Override
    public @Nullable String getThreadName(@NonNull ITmfEvent event) {

        String traceName = event.getTrace().getName();

        // For all host event we just want to put them under the guest thread
        if (traceName.toLowerCase().contains("vm/host") && vmState.isInHypervisorOverhead()) { //$NON-NLS-1$
            return threadName;
        }

        String procName = getProcessName(event);
        long tid = getThreadId(event);
        return procName + "-" + Long.toString(tid); //$NON-NLS-1$
    }

    @Override
    public @Nullable ITmfStateValue functionEntry(@NonNull ITmfEvent event) {
        String eventName = event.getName();
        long timestamp = event.getTimestamp().toNanos();


        if (isSystemCallEntry(eventName)) {
            // System call entry - push syscall name
            String syscallName = extractSyscallName(eventName);

            // For now i am not going to push special events on the pile
            if (isSpecialSyscall(syscallName)) {
                return null; // Pas de push
            }

            return TmfStateValue.newValueString(syscallName);

        } else if (isInterruptEntry(eventName)) {
            // Interrupt entry - push interrupt info
            String interruptName = "IRQ_" + extractInterruptInfo(event); //$NON-NLS-1$
            return TmfStateValue.newValueString(interruptName);

        }  else if (isPunctualEvent(eventName)) {
            // Traiter les événements ponctuels
            pendingPunctualEvents.push(new PendingEvent(eventName, timestamp));
            return TmfStateValue.newValueString(eventName);

        }

        return null;
    }


    @Override
    public @Nullable ITmfStateValue functionExit(@NonNull ITmfEvent event) {
        String eventName = event.getName();

        if (isSystemCallExit(eventName)) {
            // System call exit - pop (return null to indicate pop)
            return TmfStateValue.nullValue();

        } else if (isInterruptExit(eventName)) {
            // Interrupt exit - pop
            return TmfStateValue.nullValue();

        }

        return null;
    }

    private void closePendingPunctualEvent(ITmfEvent event) {
        PendingEvent pendingEvent = null;

        if (!pendingPunctualEvents.empty()) {
            pendingEvent = pendingPunctualEvents.pop();
        }


        if (pendingEvent != null) {

            long currentTimestamp = event.getTimestamp().toNanos();
            long duration = currentTimestamp - pendingEvent.startTimestamp;


            // Durée minimale pour assurer la visibilité
            long minDuration = 1000; // 1µs
            long effectiveDuration = Math.max(duration, minDuration);

            ITmfStateSystemBuilder ss = checkNotNull(getStateSystemBuilder());

            int quark = ss.getQuarkAbsoluteAndAdd(PROCESSES, processName, threadName, CallStackAnalysis.CALL_STACK);


            long closeTimestamp = pendingEvent.startTimestamp + effectiveDuration;

            ss.popAttribute(closeTimestamp, quark);
        }
    }

    private void processHypervisorEvent(@NonNull ITmfEvent event) {
        // we will treat entry/exit as punctual event because we want to see them and the
        // events in between
        String eventName = event.getName();
        long timestamp = event.getTimestamp().toNanos();
        Integer vcpuid = getIntField(event, VCPUID);

        if (vcpuid == null || vcpuid != this.vmState.getCurrentVirtualCpu()) {
            return;
        }

        if (isVMEntry(eventName)) {
            this.vmState.enterGuest(vcpuid);
        } else if (isVMExit(eventName)) {
            this.vmState.exitGuest(vcpuid, timestamp);
        }

        // push the event
        ITmfStateSystemBuilder ss = checkNotNull(getStateSystemBuilder());

        int processQuark = ss.getQuarkAbsoluteAndAdd(PROCESSES, this.nativeStart.procName);
        ss.updateOngoingState(TmfStateValue.newValueInt(this.nativeStart.pid), processQuark);

        int threadQuark = ss.getQuarkRelativeAndAdd(processQuark, threadName);
        ss.updateOngoingState(TmfStateValue.newValueLong(this.nativeStart.tid), threadQuark);

        int callStackQuark = ss.getQuarkRelativeAndAdd(threadQuark, CallStackAnalysis.CALL_STACK);
        ss.pushAttribute(timestamp, eventName, callStackQuark);

        pendingPunctualEvents.push(new PendingEvent(eventName, timestamp));
    }

    private static boolean isPunctualEvent(String eventName) {
        return !isSystemCall(eventName) && !isInterrupt(eventName);
    }

    private static boolean isSystemCall(String eventName) {
        return eventName.startsWith("syscall_"); //$NON-NLS-1$
    }

    private static boolean isInterrupt(String eventName) {
        return eventName.contains("irq_handler_") || eventName.contains("softirq_"); //$NON-NLS-1$ //$NON-NLS-2$

    }

    // Helper methods for event classification
    private static boolean isWorkloadMarker(ITmfEvent event) {
        String eventName = event.getName();
        if (eventName.equals(EVENT_MARKER)) {
            Object filenameField = event.getContent().getField("filename"); //$NON-NLS-1$
            if (filenameField != null) {
                String value = filenameField.toString();
                String[] words = value.split("="); //$NON-NLS-1$
                return words.length == 2 && words[1].contains(MARKER);
            }
        }
        return false;
    }

    private static boolean isSpecialSyscall(String syscallName) {
        return syscallName.equals("rt_sigreturn") || //$NON-NLS-1$
                syscallName.equals("sigreturn"); //$NON-NLS-1$
    }

    private void handleWorkloadMarker(@NonNull ITmfEvent event) {
        Integer pid = getIntField(event, PID);
        long tid = getThreadId(event);
        processName = getProcessName(event);
        threadName = getThreadName(event);
        long timestamp = event.getTimestamp().toNanos();


        if (beginNative) {
            Integer vcpuid = getIntField(event, CPUID);
            nativeStart = new SyncPoint("workload_start", timestamp,  //$NON-NLS-1$
                    pid != null ? pid : -1,  tid,
                            processName);
            beginNative = false;
            this.vmState.enterGuest(vcpuid != null ? vcpuid : -1);
        } else {
            nativeEnd = new SyncPoint("workload_end", timestamp,  //$NON-NLS-1$
                    pid != null ? pid : -1, tid,
                    processName);
        }
    }

    private static boolean isSystemCallEntry(String eventName) {
        return eventName.startsWith("syscall_entry_"); //$NON-NLS-1$
    }

    private static boolean isSystemCallExit(String eventName) {
        return eventName.startsWith("syscall_exit_"); //$NON-NLS-1$
    }

    private static boolean isInterruptEntry(String eventName) {
        return eventName.contains("irq_handler_entry") || eventName.contains("softirq_entry"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isInterruptExit(String eventName) {
        return eventName.contains("irq_handler_exit") || eventName.contains("softirq_exit"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String extractSyscallName(String eventName) {
        if (eventName.startsWith("syscall_entry_")) { //$NON-NLS-1$
            return eventName.substring("syscall_entry_".length()); //$NON-NLS-1$
        }
        return eventName;
    }

    /**
     * Check if an event is a VM entry
     */
    private static boolean isVMEntry(String eventName) {
        return eventName.contains("kvm_x86_entry"); //$NON-NLS-1$

    }

    private static boolean isVMExit(String eventName) {
        return eventName.contains("kvm_x86_exit"); //$NON-NLS-1$
    }

    private static String extractInterruptInfo(ITmfEvent event) {
        // Try to get IRQ number or other interrupt info from the event
        Object irqField = event.getContent().getField("irq"); //$NON-NLS-1$
        if (irqField != null) {
            String value = irqField.toString();
            String[] words = value.split("="); //$NON-NLS-1$
            return words.length == 2 ? words[1] : "unknown"; //$NON-NLS-1$
        }
        return "unknown"; //$NON-NLS-1$
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

    // Utility methods (same as your main state provider)
    private static @Nullable Integer getIntField(ITmfEvent event, String fieldName) {

        if (event.getType().getName().equals("kvm_x86_entry") && fieldName.equals(VCPUID)) { //$NON-NLS-1$
            return extractVcpuFromProcName(event);
        }


        Object obj = event.getContent().getField(fieldName);
        if (obj == null) {
            return null;
        }
        String value = obj.toString();
        String[] words = value.split("="); //$NON-NLS-1$
        if (words.length != 2) {
            return null;
        }
        try {
            return Integer.parseInt(words[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // temp fix this code need some refactoring
    private static String getProcessName_(ITmfEvent event) {

        Object commField = event.getContent().getField(PROCESS_NAME);
        if (commField == null) {
            return "unknown"; //$NON-NLS-1$
        }
        String value = commField.toString();
        String[] words = value.split("="); //$NON-NLS-1$
        if (words.length != 2) {
            return "unknown"; //$NON-NLS-1$
        }
        return words[1];
    }

    private boolean isRelevantHostEvent(@NonNull ITmfEvent event) {
        // Check if this host event belongs to our VM's vcPU thread
        String procName = getProcessName_(event);

        // cleaning
        if (procName == null) {
            return false;
        }

        procName = procName.trim().replaceAll("\"", ""); //$NON-NLS-1$ //$NON-NLS-2$

        // KVM threads are named like "CPU X/KVM" where is the vCPU number
        if (procName.matches("CPU \\d+/KVM")) { //$NON-NLS-1$
            // Extract vCPU number and compare with current vCPU
            Integer vcpu = extractVcpuFromProcName(event);
            return vcpu != null && vcpu == vmState.getCurrentVirtualCpu();
        }

        return false;
    }

    private boolean considerVirtualizedEvent(@NonNull ITmfEvent event, String traceName) {
        if (!traceName.toLowerCase().contains("vm/guest")  //$NON-NLS-1$
        && !traceName.toLowerCase().contains("vm/host")) { //$NON-NLS-1$
                return false;
        }


        // Handle workload markers first
        if (isWorkloadMarker(event)) {
            handleWorkloadMarker(event);
            return false; // Don't process markers as call stack events
        }

        // Only process events within the analysis window and for the target PID
        if (nativeStart != null) {

            // First you close pending punctual event if necessary
            closePendingPunctualEvent(event);

            // And then you decide if the next event is worth considering

            Integer pid = getIntField(event, PID);
            long timestamp = event.getTimestamp().toNanos();

            // Guest events - filter by PID
            if (traceName.toLowerCase().contains("vm/guest")) { //$NON-NLS-1$
                if (pid != null && pid.equals(nativeStart.pid)) {
                    return (nativeEnd == null || timestamp <= nativeEnd.timestamp);
                }

                return false;
            }

            // Host events - accept if we are in hypervisor overhead
            if (traceName.toLowerCase().contains("vm/host")) { //$NON-NLS-1$
                if (nativeEnd == null || timestamp <= nativeEnd.timestamp) {

                    // always proces kvm_entry/exit
                    String eventName = event.getName();
                    if (isVMEntry(eventName) || isVMExit(eventName)) {
                        this.processHypervisorEvent(event);
                        return false;
                    }

                    // Accept host events between kvm_exit and kvm_entry
                    if (vmState.isInHypervisorOverhead() && isRelevantHostEvent(event)) {
                        return true;
                    }
                }
                return false;
            }
        }

        return false;
    }

    private boolean considerNativeEvent(@NonNull ITmfEvent event, String traceName) {
        if (!traceName.contains("native/kernel")) { //$NON-NLS-1$
            return false;
        }

        // Handle workload markers
        if (isWorkloadMarker(event)) {
            handleWorkloadMarker(event);
            return false;
        }

        // Process events within analysis window
        if (nativeStart != null) {
            closePendingPunctualEvent(event);

            Integer pid = getIntField(event, PID);
            long timestamp = event.getTimestamp().toNanos();

            // Accept all events for our target PID
            if (pid != null && pid.equals(nativeStart.pid)) {
                return (nativeEnd == null || timestamp <= nativeEnd.timestamp);
            }
        }

        return false;
    }

    @Override
    protected String getProcessName(ITmfEvent event) {

        String traceName = event.getTrace().getName();

        // use the guest process name for host events
        if (traceName.toLowerCase().contains("vm/host") && vmState.isInHypervisorOverhead()) { //$NON-NLS-1$
            return processName;
        }


        Object commField = event.getContent().getField(PROCESS_NAME);
        if (commField == null) {
            return "unknown"; //$NON-NLS-1$
        }
        String value = commField.toString();
        String[] words = value.split("="); //$NON-NLS-1$
        if (words.length != 2) {
            return "unknown"; //$NON-NLS-1$
        }
        return words[1];
    }

    private static class SyncPoint {
        final String eventType;
        final long timestamp;
        final int pid;
        final String procName;
        final long tid;

        SyncPoint(String eventType, long timestamp, int pid, long tid, String procName) {
            this.eventType = eventType;
            this.timestamp = timestamp;
            this.pid = pid;
            this.tid = tid;
            this.procName = procName;
        }
    }


    private static class PendingEvent {
        String eventName;
        long startTimestamp;

        PendingEvent(String eventName, long startTimestamp) {
            this.eventName = eventName;
            this.startTimestamp = startTimestamp;
        }
    }



    // Helper class to track VM execution state with migration awareness
    private static class VMExecutionState {
        private boolean inGuest = false;
        private long lastExitTimestamp = -1;
        private int lastExitCpuId = -1;
        private int currentVirtualCpu = -1;

        void enterGuest(int vcpuid) {
            inGuest = true;
            this.currentVirtualCpu = vcpuid;
        }

        void exitGuest(int cpuid, long timestamp) {
            this.inGuest = false;
            this.lastExitTimestamp = timestamp;
            this.currentVirtualCpu = cpuid;
        }

        boolean isInGuest() {
            return this.inGuest;
        }

        boolean isInHypervisorOverhead() {
            return !inGuest && this.lastExitTimestamp != -1;
        }

        long getLastExitTimestamp() {
            return lastExitTimestamp;
        }

        int getLastExitCpuId() {
            return lastExitCpuId;
        }

        int getCurrentVirtualCpu() {
            return this.currentVirtualCpu;
        }
    }

    private enum EnvironmentType {
        VIRTUALIZED,
        NATIVE
    }
}