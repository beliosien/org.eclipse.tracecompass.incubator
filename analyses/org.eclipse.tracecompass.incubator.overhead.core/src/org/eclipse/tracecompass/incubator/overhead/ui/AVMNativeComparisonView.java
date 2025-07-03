package org.eclipse.tracecompass.incubator.overhead.ui;

import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.text.html.HTML.Attribute;

import java.text.DecimalFormat;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.tracecompass.incubator.internal.overhead.core.analysis.VMNativeComparisonAnalysis;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystem;
import org.eclipse.tracecompass.statesystem.core.exceptions.StateSystemDisposedException;
import org.eclipse.tracecompass.statesystem.core.interval.ITmfStateInterval;
import org.eclipse.tracecompass.tmf.core.signal.TmfSignalHandler;
import org.eclipse.tracecompass.tmf.core.signal.TmfTraceSelectedSignal;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceManager;
import org.eclipse.tracecompass.tmf.ui.views.TmfView;
import org.eclipse.tracecompass.tmf.ui.widgets.timegraph.TimeGraphViewer;
import org.eclipse.tracecompass.tmf.ui.widgets.timegraph.model.*;
import org.eclipse.tracecompass.tmf.ui.widgets.timegraph.widgets.Utils.TimeFormat;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceUtils;

/**
 * Advanced view
 */
public class AVMNativeComparisonView extends TmfView {

    /** View ID */
    public static final String ID = "org.eclipse.tracecompass.analysis.avmcomparison.ui.view"; //$NON-NLS-1$

    private TimeGraphViewer fTimeGraphViewer;
    private TableViewer fSyncPointTable;
    private Text fPerformanceSummary;
    private Combo fPhaseFilter;

    private ITmfTrace fCurrentTrace;
    private VMNativeComparisonAnalysis fAnalysis;

    // Colors for different trace types
    private Color fNativeColor;
    private Color fVmColor;
    private Color fDeltaColor;

    // Data structures
    private Map<String, ComparisonData> fComparisonResults = new ConcurrentHashMap<>();
    private List<SyncPointEntry> fSyncPoints = new ArrayList<>();

    // Performance metrics
    private DecimalFormat fDecimalFormat = new DecimalFormat("#.##"); //$NON-NLS-1$

    /**
     * Data class for storing comparison results
     */
    private static class ComparisonData {
        public final String phase;
        public final long nativeTime;
        public final long vmTime;
        public final double overhead;

        public ComparisonData(String phase, long nativeTime, long vmTime) {
            this.phase = phase;
            this.nativeTime = nativeTime;
            this.vmTime = vmTime;
            this.overhead = nativeTime > 0 ? ((double) (vmTime - nativeTime) / nativeTime) * 100.0 : 0.0;
        }
    }

    /**
     * Data class for sync point entries
     */
    private static class SyncPointEntry {
        public final String event;
        public final long nativeTime;
        public final long vmTime;
        public final long delta;
        public final double percent;

        public SyncPointEntry(String event, long nativeTime, long vmTime, long delta, double percent)  {
            this.event = event;
            this.nativeTime = nativeTime;
            this.delta = delta;
            this.vmTime = vmTime;
            this.percent = percent;
        }
    }


    /**
     * Constructor
     */
    public AVMNativeComparisonView() {
        super("VM vs Native Comparison"); //$NON-NLS-1$
    }

    @Override
    public void createPartControl(Composite parent) {
        super.createPartControl(parent);

        initalizeColors();

        SashForm mainSash = new SashForm(parent, SWT.VERTICAL);

        // Create top section with timeline view
        createTimelineSection(mainSash);

        // Create bottom section with tables and summary
        createAnalysisSection(mainSash);

        mainSash.setWeights(new int[] {60, 40});

        createToolbar();

        // Initialize with current trace if available
        ITmfTrace activeTrace = TmfTraceManager.getInstance().getActiveTrace();
        if (activeTrace != null) {
            traceSelected(new TmfTraceSelectedSignal(this, activeTrace));
        }
    }

    @Override
    public void dispose() {
        if (fNativeColor != null) {
            this.fNativeColor.dispose();
        }

        if (fVmColor != null) {
            fVmColor.dispose();
        }

        if (fDeltaColor != null) {
            fDeltaColor.dispose();
        }
        super.dispose();
    }

    private void initializeColors() {
        Display display = Display.getCurrent();
        this.fNativeColor = new Color(display, 0, 120, 215);    // Blue
        this.fVmColor = new Color(display, 255, 140, 0);        // Orange
        this.fDeltaColor = new Color(display, 220, 20, 60);     // Crimson
    }

    private void createTimelineSelection(Composite parent) {
        Composite timelineComposite = new Composite(parent, SWT.NONE);
        timelineComposite.setLayout(new GridLayout(1, false));

        // Phase filter
        Composite filterComposite = new Composite(timelineComposite, SWT.NONE);
        filterComposite.setLayout(new GridLayout(2, false));
        filterComposite.setLayoutData(new GridData(SWT.FILL,  SWT.TOP, true, false));

        Label filterLabel = new Label(filterComposite, SWT.NONE);
        filterLabel.setText("Phase Filter:"); //$NON-NLS-1$

        fPhaseFilter = new Combo(filterComposite, SWT.READ_ONLY);
        fPhaseFilter.setItems(new String[] {"All", "Compute", "Memory", "I/O"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        fPhaseFilter.select(0);
        fPhaseFilter.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                refreshTimelineView();
            }
        });

        // Timeline viewer
        fTimeGraphViewer = new TimeGraphViewer(timelineComposite, SWT.NONE);
        fTimeGraphViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        fTimeGraphViewer.setTimeFormat(TimeFormat.RELATIVE);
        fTimeGraphViewer.setAutoExpandLevel(2);
    }

    private void createAnalysisSection(Composite parent) {
        SashForm analysisSash = new SashForm(parent, SWT.HORIZONTAL);

        // sync points table
        createSyncPointTable(analysisSash);

        // Performance summary
        createPerformanceSummary(analysisSash);

        analysisSash.setWeights(new int[] {60, 40});
    }

    private void createSyncPointTable(Composite parent) {
        Composite tableComposite = new Composite(parent, SWT.NONE);
        tableComposite.setLayout(new GridLayout(1, false));

        Label tableLabel = new Label(tableComposite, SWT.NONE);
        tableLabel.setText("Synchronization Points Analysis"); //$NON-NLS-1$

        Table table = new Table(tableComposite, SWT.BORDER | SWT.FULL_SELECTION);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        fSyncPointTable = new TableViewer(table);
        fSyncPointTable.setContentProvider(ArrayContentProvider.getInstance());

        // Create columns with proper label providers
        createTableColumn("Event", 150, entry -> ((SyncPointEntry) entry).event); //$NON-NLS-1$
        createTableColumn("Native Time (ms)", 120, entry ->  //$NON-NLS-1$
            fDecimalFormat.format(((SyncPointEntry) entry).nativeTime / 1_000_000.0));
        createTableColumn("VM Time (ms)", 120, entry ->  //$NON-NLS-1$
            fDecimalFormat.format(((SyncPointEntry) entry).vmTime / 1_000_000.0));
        createTableColumn("Delta (ms)", 100, entry ->  //$NON-NLS-1$
            fDecimalFormat.format(((SyncPointEntry) entry).delta / 1_000_000.0));
        createTableColumn("Overhead (%)", 100, entry ->  //$NON-NLS-1$
            fDecimalFormat.format(((SyncPointEntry) entry).percent));
    }

    private void createTableColumn(String title, int width, ColumnTextProvider textProvider) {
        TableViewerColumn column = new TableViewerColumn(fSyncPointTable, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return textProvider.getText(element);
            }
        });
    }

    @FunctionalInterface
    private interface ColumnTextProvider {
        String getText(Object element);
    }

    private void createPerformanceSummary(Composite parent) {
        Composite summaryComposite = new Composite(parent, SWT.NONE);
        summaryComposite.setLayout(new GridLayout(1, false));

        Label summaryLabel = new Label(summaryComposite, SWT.NONE);
        summaryLabel.setText("Performance Summary:"); //$NON-NLS-1$

        fPerformanceSummary = new Text(summaryComposite,
                SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.WRAP);
        fPerformanceSummary.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        fPerformanceSummary.setBackground(Display.getCurrent().getSystemColor(SWT.COLOR_WHITE));
    }

    private void createToolbar() {
        IToolBarManager toolbarManager = getViewSite().getActionBars().getToolBarManager();

        Action refreshAction = new Action("Refresh") { //$NON-NLS-1$
            @Override
            public void run() {
                refreshAnalysis();
            }
        };
        refreshAction.setToolTipText("Refresh the analysis"); //$NON-NLS-1$

        toolbarManager.add(refreshAction);
    }

    /**
     * @param signal
     */
    @TmfSignalHandler
    public void traceSelected(TmfTraceSelectedSignal signal) {
        ITmfTrace trace = signal.getTrace();
        if (trace == null) {
            return;
        }

        fCurrentTrace = trace;
        fAnalysis = TmfTraceUtils.getAnalysisModuleOfClass(trace, VMNativeComparisonAnalysis.class,
                VMNativeComparisonAnalysis.ID);

        if (fAnalysis != null) {
            fAnalysis.schedule();
            refreshAnalysis();
        }
    }

    private void refreshAnalysis() {
        if (fAnalysis == null || fCurrentTrace == null) {
            return;
        }

        // wait for analysis to complete
        fAnalysis.waitForCompletion();

        ITmfStateSystem ss = fAnalysis.getStateSystem();
        if (ss == null) {
            return;
        }

        try {
            loadComparisonData(stateSystem);
            updateTimelineView();
            updateSyncPointTable();
            updatePerformanceSummary();

        } catch (StateSystemDisposedException e) {
         // Handle error - log and show user-friendly message
         fPerformanceSummary.setText("Error loading analysis data: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void loadComparisonData(ITmfStateSystem stateSystem) throws StateSystemDisposedException {
        this.fComparisonResults.clear();
        this.fSyncPoints.clear();

        long startTime = stateSystem.getStartTime();
        long endTime = stateSystem.getCurrentEndTime();

        // Load sync point correlations
        List<Integer> syncAttributes = stateSystem.getSubAttributes(-1, false);
        for (Integer attr: syncAttributes) {
            String attributeName = stateSystem.getAttributeName(attr);
            if (attributeName.startsWith("SyncPoints/")) { //$NON-NLS-1$
                loadSyncPointData(stateSystem, attr, startTime, endTime);
            }
        }

        // Load phase performance data
        loadPhaseData(stateSystem, startTime, endTime);
    }

    private static void loadSyncPointData(ITmfStateSystem stateSystem, int attribute, long startTime, long endTime)
            throws StateSystemDisposedException {
        List<ITmfStateInterval> intervals = stateSystem.queryFullState(endTime);
        for (ITmfStateInterval interval: intervals) {
            Object value = interval.getValue();
            if (interval.getAttribute() == attribute && value != null) {
                String data = value.toString();
                parseSyncPointData(data, stateSystem.getAttributeName(attribute));

            }
        }
    }
}
