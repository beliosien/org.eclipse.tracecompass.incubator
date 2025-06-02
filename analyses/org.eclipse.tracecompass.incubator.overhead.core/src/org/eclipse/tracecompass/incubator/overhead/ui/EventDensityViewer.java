package org.eclipse.tracecompass.incubator.overhead.ui;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swtchart.IAxisSet;
import org.eclipse.swtchart.LineStyle;
import org.eclipse.tracecompass.incubator.overhead.core.data.provider.KvmExitRateDataProvider;
import org.eclipse.tracecompass.tmf.core.model.OutputElementStyle;
import org.eclipse.tracecompass.tmf.core.model.StyleProperties;
import org.eclipse.tracecompass.tmf.ui.viewers.xychart.barchart.TmfHistogramTooltipProvider;
import org.eclipse.tracecompass.tmf.ui.viewers.xychart.linechart.TmfFilteredXYChartViewer;
import org.eclipse.tracecompass.tmf.ui.viewers.xychart.linechart.TmfXYChartSettings;

/**
 * Histogram Viewer implementation based on TmfBarChartViewer.
 */
public class EventDensityViewer extends TmfFilteredXYChartViewer {

    private static final int DEFAULT_SERIES_WIDTH = 1;

    /**
     * Creates a Histogram Viewer instance.
     *
     * @param parent
     *            The parent composite to draw in.
     * @param settings
     *            See {@link TmfXYChartSettings} to know what it contains
     */
    public EventDensityViewer(Composite parent, TmfXYChartSettings settings) {
        super(parent, settings, KvmExitRateDataProvider.ID);

        /* Hide the grid */
        IAxisSet axisSet = getSwtChart().getAxisSet();
        axisSet.getXAxis(0).getGrid().setStyle(LineStyle.NONE);
        axisSet.getYAxis(0).getGrid().setStyle(LineStyle.NONE);

        setTooltipProvider(new TmfHistogramTooltipProvider(this));
    }

    @Override
    public @NonNull OutputElementStyle getSeriesStyle(@NonNull Long seriesId) {
        return getPresentationProvider().getSeriesStyle(seriesId, StyleProperties.SeriesType.BAR, DEFAULT_SERIES_WIDTH);
    }
}