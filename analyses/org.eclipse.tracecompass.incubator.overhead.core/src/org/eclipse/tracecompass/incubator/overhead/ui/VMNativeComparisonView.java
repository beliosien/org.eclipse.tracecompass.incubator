package org.eclipse.tracecompass.incubator.overhead.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.tracecompass.incubator.internal.overhead.core.analysis.VMNativeComparisonAnalysis;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystem;
import org.eclipse.tracecompass.statesystem.core.interval.ITmfStateInterval;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceManager;
import org.eclipse.tracecompass.tmf.ui.views.TmfView;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceUtils;

/**
 *
 */
public class VMNativeComparisonView extends TmfView {

    public static final String ID = "org.eclipse.tracecompass.analysis.vmcomparison.ui.view"; //$NON-NLS-1$
    private TableViewer tableViewer;
    private TableViewer phaseTableViewer;
    private TableViewer eventRateTableViewer;

    /**
     * Constructor
     */
    public VMNativeComparisonView() {
        super("VM vs Native SyncPoints"); //$NON-NLS-1$
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());

        // TableViewer setup
        tableViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        createColumn(tableViewer, "Event", 180, 0); //$NON-NLS-1$
        createColumn(tableViewer, "Native Time (ms)", 120, 1); //$NON-NLS-1$
        createColumn(tableViewer, "VM Time (ms)", 120, 2); //$NON-NLS-1$
        createColumn(tableViewer, "Delta (ms)", 100, 3); //$NON-NLS-1$
        createColumn(tableViewer, "Overhead (%)", 100, 4); //$NON-NLS-1$

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        // table viewer for the phase-based analysis
        phaseTableViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table phaseTable = phaseTableViewer.getTable();
        phaseTable.setHeaderVisible(true);
        phaseTable.setLinesVisible(true);

        createColumn(phaseTableViewer, "Phase", 120, 0); //$NON-NLS-1$
        createColumn(phaseTableViewer, "Native Duration (ms)", 120, 1); //$NON-NLS-1$
        createColumn(phaseTableViewer, "VM Duration (ms)", 120, 2); //$NON-NLS-1$
        createColumn(phaseTableViewer, "Delta (ms)", 100, 3); //$NON-NLS-1$
        createColumn(phaseTableViewer, "Overhead (%)", 100, 4); //$NON-NLS-1$

        phaseTableViewer.setContentProvider(ArrayContentProvider.getInstance());

        // table viewer for the rate event analysis
        eventRateTableViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table eventRateTable = eventRateTableViewer.getTable();
        eventRateTable.setHeaderVisible(true);
        eventRateTable.setLinesVisible(true);

        createColumn(eventRateTableViewer, "Metric", 200, 0); //$NON-NLS-1$
        createColumn(eventRateTableViewer, "Native", 80, 1); //$NON-NLS-1$
        createColumn(eventRateTableViewer, "VM", 80, 2); //$NON-NLS-1$
        createColumn(eventRateTableViewer, "Delta", 80, 3); //$NON-NLS-1$
        createColumn(eventRateTableViewer, "Overhead(%)", 100, 4); //$NON-NLS-1$

        eventRateTableViewer.setContentProvider(ArrayContentProvider.getInstance());

        createToolbar();

        loadData();
    }

    private static void createColumn(TableViewer viewer, String name, int width, int index) {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        TableColumn tableColumn = column.getColumn();
        tableColumn.setText(name);
        tableColumn.setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                String[] row = (String[]) element;
                return index < row.length ? row[index] : ""; //$NON-NLS-1$
            }
        });
    }

    private void createToolbar() {
        IToolBarManager toolbarManager = getViewSite().getActionBars().getToolBarManager();
        Action refreshAction = new Action("Refresh") { //$NON-NLS-1$
            @Override
            public void run() {
                loadData();
            }
        };
        refreshAction.setToolTipText("Refresh analysis"); //$NON-NLS-1$
        toolbarManager.add(refreshAction);
        toolbarManager.add(new Separator());
    }

    private void loadData() {
        List<String[]> syncRows = new ArrayList<>();
        List<String[]> phaseRows = new ArrayList<>();
        List<String[]> eventRateRows = new ArrayList<>();
        ITmfTrace trace = TmfTraceManager.getInstance().getActiveTrace();
        if (trace == null) {
            syncRows.add(new String[]{"No active trace", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            phaseRows.add(new String[]{"No active trace", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            eventRateRows.add(new String[]{"No active", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            tableViewer.setInput(syncRows);
            phaseTableViewer.setInput(phaseRows);
            eventRateTableViewer.setInput(eventRateRows);
            return;
        }

        VMNativeComparisonAnalysis analysis = TmfTraceUtils.getAnalysisModuleOfClass(
                trace, VMNativeComparisonAnalysis.class, VMNativeComparisonAnalysis.ID);
        if (analysis == null) {
            syncRows.add(new String[]{"No analysis module found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            phaseRows.add(new String[]{"No analysis module found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            eventRateRows.add(new String[]{"No analysis module found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            tableViewer.setInput(syncRows);
            phaseTableViewer.setInput(phaseRows);
            eventRateTableViewer.setInput(eventRateRows);
            return;
        }
        analysis.schedule();
        analysis.waitForCompletion();

        ITmfStateSystem ss = analysis.getStateSystem();
        if (ss == null) {
            syncRows.add(new String[]{"No state system available", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            phaseRows.add(new String[]{"No state system available", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            eventRateRows.add(new String[]{"No state system available", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            tableViewer.setInput(syncRows);
            phaseTableViewer.setInput(phaseRows);
            eventRateTableViewer.setInput(eventRateRows);
            return;
        }

        try {
            long start = ss.getStartTime();
            long end = ss.getCurrentEndTime();

            // SyncPoints Table
            int syncPointsQuark = ss.optQuarkAbsolute("SyncPoints"); //$NON-NLS-1$
            if (syncPointsQuark == ITmfStateSystem.INVALID_ATTRIBUTE) {
                syncRows.add(new String[]{"No SyncPoints found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            } else {
                List<Integer> children = ss.getSubAttributes(syncPointsQuark, false);
                for (Integer child : children) {
                    long ts = start;
                    Object lastValue = null;
                    while (ts <= end) {
                        ITmfStateInterval interval = ss.querySingleState(ts, child);
                        Object value = interval.getValue();
                        if (value != null && !value.equals(lastValue)) {
                            String eventLabel = ss.getAttributeName(child);
                            String[] data = parseSyncPointData(eventLabel, value.toString());
                            if (data != null) {
                                syncRows.add(data);
                            }
                            lastValue = value;
                        }
                        ts = interval.getEndTime() + 1;
                    }
                }
            }

            // Phase-Based Table
            int phaseRootQuark = ss.optQuarkAbsolute("PhaseDurations"); //$NON-NLS-1$
            if (phaseRootQuark == ITmfStateSystem.INVALID_ATTRIBUTE) {
                phaseRows.add(new String[]{"No PhaseDurations found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            } else {
                List<Integer> phaseChildren = ss.getSubAttributes(phaseRootQuark, false);
                for (Integer child : phaseChildren) {
                    long ts = start;
                    Object lastValue = null;
                    while (ts <= end) {
                        ITmfStateInterval interval = ss.querySingleState(ts, child);
                        Object value = interval.getValue();
                        if (value != null && !value.equals(lastValue)) {
                            String phaseLabel = ss.getAttributeName(child);
                            String[] data = parsePhaseData(phaseLabel, value.toString());
                            if (data != null) {
                                phaseRows.add(data);
                            }
                            lastValue = value;
                        }
                        ts = interval.getEndTime() + 1;
                    }
                }
            }

            // Event Rate Table -- uncomment if you want global stats
            /*int eventRatesRoot = ss.optQuarkAbsolute("EventRates"); //$NON-NLS-1$
            if (eventRatesRoot == ITmfStateSystem.INVALID_ATTRIBUTE) {
                eventRateRows.add(new String[]{"No EventRates found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            } else {
                // global printing for each of event
                for (String eventClass : new String[] {"syscall", "context_switch", "page_fault"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    int natAttr = ss.optQuarkRelative(eventRatesRoot, "native_" + eventClass); //$NON-NLS-1$
                    int vmAttr = ss.optQuarkRelative(eventRatesRoot, "vm_" + eventClass); //$NON-NLS-1$
                    if (natAttr != ITmfStateSystem.INVALID_ATTRIBUTE && vmAttr != ITmfStateSystem.INVALID_ATTRIBUTE) {
                        int nativeCount = getIntValue(ss, natAttr, end);
                        int vmCount = getIntValue(ss, vmAttr, end);
                        int delta = vmCount - nativeCount;
                        double percent = nativeCount == 0 ? 0.0 : ((double) delta / (double) nativeCount) * 100.0;
                        eventRateRows.add(new String[] {
                                eventClass + "s (total)", //$NON-NLS-1$
                                Integer.toString(nativeCount),
                                Integer.toString(vmCount),
                                Integer.toString(delta),
                                String.format("%2f", percent) //$NON-NLS-1$
                        });
                    }

                }
            }*/

            // Event rates by phase
            int eventRatesByPhaseRoot = ss.optQuarkAbsolute("EventRatesByPhase"); //$NON-NLS-1$
            if (eventRatesByPhaseRoot != ITmfStateSystem.INVALID_ATTRIBUTE) {
                for (String phase: new String[] {"compute", "memory", "io"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    for (String eventClass: new String[] {"syscall", "context_switch", "page_fault"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        int natAttr = ss.optQuarkRelative(eventRatesByPhaseRoot, phase + "_native_" + eventClass); //$NON-NLS-1$
                        int vmAttr = ss.optQuarkRelative(eventRatesByPhaseRoot, phase + "_vm_" + eventClass); //$NON-NLS-1$
                        if (natAttr != ITmfStateSystem.INVALID_ATTRIBUTE && vmAttr != ITmfStateSystem.INVALID_ATTRIBUTE) {
                            int nativeCount = getIntValue(ss, natAttr, end);
                            int vmCount = getIntValue(ss, vmAttr, end);
                            int delta = vmCount - nativeCount;
                            double percent = nativeCount == 0 ? 0.0 : ((double) delta / (double) nativeCount) * 100.0;
                            eventRateRows.add(new String[] {
                                    phase + " " + eventClass + "s", //$NON-NLS-1$ //$NON-NLS-2$
                                    Integer.toString(nativeCount),
                                    Integer.toString(vmCount),
                                    Integer.toString(delta),
                                    String.format("%.2f", percent) //$NON-NLS-1$
                            });
                        }
                    }
                }
            }

        } catch (Exception e) {
            syncRows.add(new String[]{"Error reading SyncPoints", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            phaseRows.add(new String[]{"Error reading PhaseDurations", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            eventRateRows.add(new String[]{"Error reading EventRates", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        tableViewer.setInput(syncRows);
        phaseTableViewer.setInput(phaseRows);
        eventRateTableViewer.setInput(eventRateRows);
    }


    private static int getIntValue(ITmfStateSystem ss, int quark, long ts) {
        try {
            ITmfStateInterval interval = ss.querySingleState(ts, quark);
            Object value = interval.getValue();
            if (value instanceof Integer) {
                return (Integer) value;
            } else if (value instanceof Long) {
                return ((Long) value).intValue();
            } else if (value != null) {
                return Integer.parseInt(value.toString());
            }
        } catch (Exception e) {
            // ignore
        }

        return 0;
    }

    /**
     * Parse les données du type "native:...,vm:...,delta:...,percent:..."
     * pour PhaseDurations, retourne un tableau String[] pour une ligne de la table phases.
     */
    private static String[] parsePhaseData(String phaseLabel, String value) {
        try {
            String phase = phaseLabel; // "compute", "memory" ou "io"
            String[] parts = value.split(","); //$NON-NLS-1$
            if (parts.length != 4) {
                return null;
            }
            double nativeTime = Long.parseLong(parts[0].split(":")[1]) / 1_000_000.0; // ns -> ms //$NON-NLS-1$
            double vmTime = Long.parseLong(parts[1].split(":")[1]) / 1_000_000.0;     // ns -> ms //$NON-NLS-1$
            double delta = Long.parseLong(parts[2].split(":")[1]) / 1_000_000.0; //$NON-NLS-1$
            double percent = Double.parseDouble(parts[3].split(":")[1]); //$NON-NLS-1$
            return new String[] {
                    phase,
                    String.format("%.4f", nativeTime), //$NON-NLS-1$
                    String.format("%.4f", vmTime), //$NON-NLS-1$
                    String.format("%.4f", delta), //$NON-NLS-1$
                    (percent > 100.0) ? "N/A" : String.format("%.2f", percent) //$NON-NLS-1$ //$NON-NLS-2$
                };
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * Parse les données du type "native:...,vm:...,delta:...,percent:..."
     * et retourne un tableau String[] pour une ligne de la table
     */
    private static String[] parseSyncPointData(String eventLabel, String value) {
        try {
            String eventLabel_t = eventLabel;
            if (eventLabel.endsWith(":null")) { //$NON-NLS-1$
                eventLabel_t = eventLabel.substring(0, eventLabel.length() - 5);
            }

            String[] parts = value.split(","); //$NON-NLS-1$
            if (parts.length != 4) {
                return null;
            }
            double nativeTime = Long.parseLong(parts[0].split(":")[1]) / 1_000_000.0; // ns -> ms //$NON-NLS-1$
            double vmTime = Long.parseLong(parts[1].split(":")[1]) / 1_000_000.0;     // ns -> ms //$NON-NLS-1$
            double delta = Long.parseLong(parts[2].split(":")[1]) / 1_000_000.0; //$NON-NLS-1$
            double percent = Double.parseDouble(parts[3].split(":")[1]); //$NON-NLS-1$

            String event = eventLabel.startsWith("SyncPoints/") ? eventLabel.substring("SyncPoints/".length()) : eventLabel_t; //$NON-NLS-1$ //$NON-NLS-2$

            return new String[] {
                event,
                String.format("%.4f", nativeTime), //$NON-NLS-1$
                String.format("%.4f", vmTime), //$NON-NLS-1$
                String.format("%.4f", delta), //$NON-NLS-1$
                (percent > 100.0) ? "N/A" : String.format("%.2f", percent) //$NON-NLS-1$ //$NON-NLS-2$
            };
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void setFocus() {
        if (tableViewer != null) {
            tableViewer.getTable().setFocus();
        }
    }
}