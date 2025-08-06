package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flow analysis of a process. Should work on a native system and on a virtualized system
 *
 * @author philippe
 */
public class ProcessFlowInfo {
    final String phase;
    final String processName;
    final Map<Integer, ThreadFlowInfo> threadsByTid = new HashMap<>();

    // Unified timeline containing both guest and hypervisor events
    final List<FlowEvent> unifiedFlow = new ArrayList<>();

    // Track virtualization context
    private final boolean trackHypervisor;
    private final Map<Integer, Integer> vcpuMapping = new HashMap<>(); // TID to VCPU mapping

    // Store execution sequences for analysis
    private List<ExecutionSequence> executionSequences = new ArrayList<>();

    // adding unicity criteria
    private final Set<String> seenTransitions = new HashSet<>();

    // Constructor for non-virtualized environments
    ProcessFlowInfo(String phase, String processName) {
        this(phase, processName, false);
    }

    // Constructor with virtualization support
    ProcessFlowInfo(String phase, String processName, boolean trackHypervisor) {
        this.phase = phase;
        this.processName = processName;
        this.trackHypervisor = trackHypervisor;
    }

    /**
     * Add a guest process event
     */
    void addGuestEvent(KernelEventInfo evt) {
        if (!evt.processName.equals(this.processName)) {
            return;
        }

        ThreadFlowInfo threadInfo = threadsByTid.computeIfAbsent(evt.tid,
            k -> new ThreadFlowInfo(k, processName));
        threadInfo.addEvent(evt);

        // Add to unified flow
        FlowEvent flowEvent = new FlowEvent(evt, FlowEventType.GUEST_EVENT);
        unifiedFlow.add(flowEvent);

        // Track VCPU mapping if available
        if (evt.cpuid >= 0) {
            vcpuMapping.put(evt.tid, evt.cpuid);
        }
    }

    /**
     * Add a hypervisor event correlated with this process
     */
    void addHypervisorEvent(KernelEventInfo hypervisorEvt, long guestEventTimestamp) {
        if (!trackHypervisor) {
            return;
        }

        FlowEvent flowEvent = new FlowEvent(hypervisorEvt, FlowEventType.HYPERVISOR_EVENT);
        flowEvent.correlatedGuestTimestamp = guestEventTimestamp;
        unifiedFlow.add(flowEvent);
    }

    /**
     * Add a VM exit/entry event
     */
    void addVMTransition(KernelEventInfo evt, boolean isExit) {
        if (!trackHypervisor) {
            return;
        }

        String key = (isExit ? "EXIT" : "ENTRY") + "_" +  //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                evt.timestamp + "_" + evt.cpuid + "_"  //$NON-NLS-1$ //$NON-NLS-2$
                    + evt.vcpuid + "_" + evt.tid; //$NON-NLS-1$

        if (seenTransitions.contains(key)) {
            return;
        }
        seenTransitions.add(key);

        FlowEventType type = isExit ? FlowEventType.VM_EXIT : FlowEventType.VM_ENTRY;
        FlowEvent flowEvent = new FlowEvent(evt, type);
        unifiedFlow.add(flowEvent);
    }

    /**
     * Finalize the flow analysis by sorting events chronologically
     */
    void finalizeFlow() {
        // Sort unified flow by timestamp
        unifiedFlow.sort(Comparator.comparing(fe -> fe.kernelEvent.timestamp));

        // Build execution sequences
        buildExecutionSequences();
    }

    /**
     * Build execution sequences showing the flow between guest and hypervisor
     */
    private void buildExecutionSequences() {
        if (!trackHypervisor || unifiedFlow.isEmpty()) {
            return;
        }

        List<ExecutionSequence> sequences = new ArrayList<>();
        ExecutionSequence currentSequence = null;

        for (FlowEvent flowEvent : unifiedFlow) {
            switch (flowEvent.type) {
                case GUEST_EVENT:
                    if (currentSequence == null) {
                        currentSequence = new ExecutionSequence();
                    }
                    currentSequence.addGuestEvent(flowEvent);
                    break;

                case VM_EXIT:
                    if (currentSequence != null) {
                        currentSequence.setVmExit(flowEvent);
                    }
                    break;

                case HYPERVISOR_EVENT:
                    if (currentSequence != null) {
                        currentSequence.addHypervisorEvent(flowEvent);
                    }
                    break;

                case VM_ENTRY:
                    if (currentSequence != null) {
                        currentSequence.setVmEntry(flowEvent);
                        sequences.add(currentSequence);
                        currentSequence = null; // Start new sequence
                    }
                    break;
            default:
                break;
            }
        }

        // Add any remaining sequence
        if (currentSequence != null) {
            sequences.add(currentSequence);
        }

        this.executionSequences = sequences;
    }

    /**
     * Print the unified execution flow
     */
    void printUnifiedFlow() {
        System.out.printf("\n=== Unified Flow for Process %s (Phase: %s) ===\n",  //$NON-NLS-1$
            processName, phase);

        if (!trackHypervisor) {
            printSimpleFlow();
            return;
        }

        printVirtualizedFlow();
    }

    private void printSimpleFlow() {
        System.out.printf("Events: %d, Threads: %d\n", unifiedFlow.size(), threadsByTid.size()); //$NON-NLS-1$

        for (FlowEvent flowEvent : unifiedFlow) {
            KernelEventInfo evt = flowEvent.kernelEvent;
            System.out.printf("  [%d] %s (TID:%d)\n",  //$NON-NLS-1$
                evt.timestamp, evt.name, evt.tid);
        }
    }

    private void printVirtualizedFlow() {
        System.out.printf("Execution Sequences: %d\n", executionSequences.size()); //$NON-NLS-1$

        int sequenceNum = 1;
        for (ExecutionSequence seq : executionSequences) {
            System.out.printf("\n--- Sequence %d ---\n", sequenceNum++); //$NON-NLS-1$
            seq.printSequence();
        }

        // Also print raw chronological flow
        System.out.println("\n--- Raw Chronological Flow ---"); //$NON-NLS-1$
        for (FlowEvent flowEvent : unifiedFlow) {
            printFlowEvent(flowEvent);
        }
    }

    private static void printFlowEvent(FlowEvent flowEvent) {
        KernelEventInfo evt = flowEvent.kernelEvent;
        String prefix = getEventPrefix(flowEvent.type);

        System.out.printf("  [%d] %s%s", evt.timestamp, prefix, evt.name); //$NON-NLS-1$

        if (evt.tid >= 0) {
            System.out.printf(" (TID:%d", evt.tid); //$NON-NLS-1$
            if (evt.cpuid >= 0) {
                System.out.printf(", CPU:%d", evt.cpuid); //$NON-NLS-1$
            }
            if (evt.vcpuid >= 0) {
                System.out.printf(", VCPU:%d", evt.vcpuid); //$NON-NLS-1$
            }
            System.out.print(")"); //$NON-NLS-1$
        }

        System.out.println();
    }

    private static String getEventPrefix(FlowEventType type) {
        switch (type) {
            case GUEST_EVENT: return "[GUEST] "; //$NON-NLS-1$
            case VM_EXIT: return "[VM_EXIT] "; //$NON-NLS-1$
            case HYPERVISOR_EVENT: return "[HOST] "; //$NON-NLS-1$
            case VM_ENTRY: return "[VM_ENTRY] "; //$NON-NLS-1$
            default: return ""; //$NON-NLS-1$
        }
    }

    boolean isMultiThreaded() {
        return threadsByTid.size() > 1;
    }

    boolean isVirtualized() {
        return trackHypervisor && unifiedFlow.stream()
            .anyMatch(fe -> fe.type == FlowEventType.HYPERVISOR_EVENT ||
                           fe.type == FlowEventType.VM_EXIT ||
                           fe.type == FlowEventType.VM_ENTRY);
    }
}
