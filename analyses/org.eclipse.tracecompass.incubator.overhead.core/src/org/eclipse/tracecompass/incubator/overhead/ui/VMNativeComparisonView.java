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
    private TableViewer tableViewer;  // table

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

        createColumn("Event", 180, 0); //$NON-NLS-1$
        createColumn("Native Time (ns)", 120, 1); //$NON-NLS-1$
        createColumn("VM Time (ns)", 120, 2); //$NON-NLS-1$
        createColumn("Delta (ns)", 100, 3); //$NON-NLS-1$
        createColumn("Overhead (%)", 100, 4); //$NON-NLS-1$

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        createToolbar();

        loadData();
    }

    private void createColumn(String name, int width, int index) {
        TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.NONE);
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
        List<String[]> rows = new ArrayList<>();
        ITmfTrace trace = TmfTraceManager.getInstance().getActiveTrace();
        if (trace == null) {
            rows.add(new String[]{"No active trace", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            tableViewer.setInput(rows);
            return;
        }

        // Adapter le module si besoin selon ton ID d'analyse
        VMNativeComparisonAnalysis analysis = TmfTraceUtils.getAnalysisModuleOfClass(
                trace, VMNativeComparisonAnalysis.class, VMNativeComparisonAnalysis.ID);
        if (analysis == null) {
            rows.add(new String[]{"No analysis module found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            tableViewer.setInput(rows);
            return;
        }
        analysis.schedule();
        analysis.waitForCompletion();

        ITmfStateSystem ss = analysis.getStateSystem();
        if (ss == null) {
            rows.add(new String[]{"No state system available", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            tableViewer.setInput(rows);
            return;
        }

        try {
            // On cherche tous les attributs SyncPoints/*
            int syncPointsQuark = ss.optQuarkAbsolute("SyncPoints"); //$NON-NLS-1$
            if (syncPointsQuark == ITmfStateSystem.INVALID_ATTRIBUTE) {
                rows.add(new String[]{"No SyncPoints found", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                tableViewer.setInput(rows);
                return;
            }
            List<Integer> children = ss.getSubAttributes(syncPointsQuark, false);

            long start = ss.getStartTime();
            long end = ss.getCurrentEndTime();

            for (Integer child : children) {
                // On balaye tout le temps du state system pour trouver les valeurs valides
                long ts = start;
                Object lastValue = null;
                while (ts <= end) {
                    ITmfStateInterval interval = ss.querySingleState(ts, child);
                    Object value = interval.getValue();
                    // Ne prend que les changements (évite les doublons)
                    if (value != null && !value.equals(lastValue)) {
                        String eventLabel = ss.getAttributeName(child);
                        String[] data = parseSyncPointData(eventLabel, value.toString());
                        if (data != null) {
                            rows.add(data);
                        }
                        lastValue = value;
                    }
                    ts = interval.getEndTime() + 1; // Passe au prochain intervalle
                }
            }
        } catch (Exception e) {
            rows.add(new String[]{"Error reading state system", "", "", "", ""}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        tableViewer.setInput(rows);
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