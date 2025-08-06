package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a complete execution sequence: Guest → VM Exit → Host → VM Entry
 *
 * @author philippe
 */
public class ExecutionSequence {
    private final List<FlowEvent> guestEvents = new ArrayList<>();
    private final List<FlowEvent> hypervisorEvents = new ArrayList<>();
    private FlowEvent vmExit;
    private FlowEvent vmEntry;

    void addGuestEvent(FlowEvent event) {
        guestEvents.add(event);
    }

    void addHypervisorEvent(FlowEvent event) {
        hypervisorEvents.add(event);
    }

    void setVmExit(FlowEvent event) {
        this.vmExit = event;
    }

    void setVmEntry(FlowEvent event) {
        this.vmEntry = event;
    }

    void printSequence() {
        // Print guest events leading to VM exit
        for (FlowEvent guestEvent : guestEvents) {
            KernelEventInfo evt = guestEvent.kernelEvent;
            System.out.printf("    [GUEST] %s (TID:%d)\n", evt.name, evt.tid); //$NON-NLS-1$
        }

        // Print VM exit
        if (vmExit != null) {
            System.out.printf("    ↓ [VM_EXIT] %s\n", vmExit.kernelEvent.name); //$NON-NLS-1$
        }

        // Print hypervisor events
        for (FlowEvent hypervisorEvent : hypervisorEvents) {
            KernelEventInfo evt = hypervisorEvent.kernelEvent;
            System.out.printf("      [HOST] %s (PID:%d)\n", evt.name, evt.pid); //$NON-NLS-1$
        }

        // Print VM entry
        if (vmEntry != null) {
            System.out.printf("    ↑ [VM_ENTRY] %s\n", vmEntry.kernelEvent.name); //$NON-NLS-1$
        }

        // Print timing summary
        if (!guestEvents.isEmpty() && vmEntry != null) {
            long totalDuration = vmEntry.kernelEvent.timestamp -
                guestEvents.get(0).kernelEvent.timestamp;
            System.out.printf("    Total sequence duration: %d µs\n", totalDuration / 1000); //$NON-NLS-1$
        }
    }

    boolean isComplete() {
        return !guestEvents.isEmpty() && vmExit != null && vmEntry != null;
    }
}