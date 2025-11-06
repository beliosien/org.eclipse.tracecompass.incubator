package org.eclipse.tracecompass.incubator.internal.overhead.core.analysis;

/**
 *
 */
public class SequenceEvent {
    private final String label;
    private final long startTime;
    private final long duration;

    @SuppressWarnings("javadoc")
    public SequenceEvent(String label, long startTime, long duration) {
        this.label = label;
        this.startTime = startTime;
        this.duration = duration;
    }

    @SuppressWarnings("javadoc")
    public String getLabel() {
        return label;
    }

    @SuppressWarnings("javadoc")
    public long getStartTime() {
        return startTime;
    }

    @SuppressWarnings("javadoc")
    public long getDuration() {
        return duration;
    }
}
