package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;


public class KernelEventInfo {
    public final String name;
    public final long timestamp;
    public final int pid;
    public final int tid;
    public final String processName;
    public final TraceType source;
    public final int cpuid;
    public final int vcpuid;

    /**
     * @param name
     * @param timestamp
     * @param pid
     * @param tid
     * @param processName
     */
    public KernelEventInfo(String name, long timestamp, int pid, int tid, String processName,
            TraceType source, int cpuid, int vcpuid) {
        this.name = name;
        this.timestamp = timestamp;
        this.pid = pid;
        this.tid = tid;
        this.processName = processName;
        this.source = source;
        this.cpuid = cpuid;
        this.vcpuid = vcpuid;
    }
}
