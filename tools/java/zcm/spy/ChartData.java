package zcm.spy;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import javax.swing.Timer;
import info.monitorenter.gui.chart.ITrace2D;

/**
 * Global class allowing multiple charts to know about each other and make intelligent
 * decisions based on that.
 * 
 * Also ensures that we do not try to
 * create two charts based on the same data backend, which can cause conflicts.
 * 
 * @author abarry
 *
 */
public class ChartData
{
    private long startuTime; // start time of zcm-spy, which all X-axis are based off of
    
    // list of all charts displayed
    private LinkedList<ZoomableChartScrollWheel> charts = new LinkedList<ZoomableChartScrollWheel>();
    
    // constants for setting how much data we keep for each type of graph
    public final int sparklineChartSize = 500;
    public final int detailedSparklineChartSize =
            Math.max(1, Integer.getInteger("zcm.spy.chartSize", 15000));

    // These registrations and the chart library are owned by the Swing thread.
    private final LinkedHashMap<StreamingTrace, Runnable> streams =
            new LinkedHashMap<StreamingTrace, Runnable>();
    private final Timer refreshTimer = new Timer(33, new ActionListener() {
        public void actionPerformed(ActionEvent e)
        {
            flush();
        }
    });

    /**
     * Constructor for ChartData.  Initializes color list and sets the start time of zcm-spy
     * 
     * @param startuTime zcm-spy start time to base each x-axis off of
     */
    public ChartData(long startuTime)
    {
        this.startuTime = startuTime;

    }

    /**
     * Returns all charts being displayed
     * 
     * @return all chrats being displayed
     */
    public LinkedList<ZoomableChartScrollWheel> getCharts()
    {
        return charts;
    }

    void startTrace(StreamingTrace trace, Runnable onClose)
    {
        streams.put(trace, onClose);
        refreshTimer.start();
    }

    void stopTrace(ITrace2D trace)
    {
        Runnable onClose = streams.remove(trace);
        if (onClose != null)
            onClose.run();
        if (streams.isEmpty())
            refreshTimer.stop();
    }

    void flush()
    {
        for (StreamingTrace trace : streams.keySet())
            trace.flush();
        for (ZoomableChartScrollWheel chart : charts)
            chart.refreshView();
    }

    SignalCatalog.Source signalSource;
    private SignalCatalog.Favorites favorites;
    // One daemon writer preserves ordering without doing preferences I/O on the EDT.
    private static final java.util.concurrent.ExecutorService favoriteWriter =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "spy-favorites"); thread.setDaemon(true); return thread;
        });

    SignalCatalog.Favorites favorites()
    {
        if (favorites == null) favorites = new SignalCatalog.Favorites(
            java.util.prefs.Preferences.userNodeForPackage(ChartData.class).node("signalFavorites"));
        return favorites;
    }

    void saveFavorites(java.util.function.Consumer<String> onError)
    {
        final java.util.Set<String> snapshot = new java.util.LinkedHashSet<String>(favorites().ids);
        favoriteWriter.execute(() -> {
            try {
                SignalCatalog.Favorites saved = new SignalCatalog.Favorites(
                    java.util.prefs.Preferences.userNodeForPackage(ChartData.class).node("signalFavorites"));
                saved.ids.clear(); saved.ids.addAll(snapshot); saved.save();
            } catch (Exception error) {
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(error.getMessage()));
            }
        });
    }

    void linkView(ZoomableChartScrollWheel source)
    {
        if (!source.isTimeLinked()) return;
        for (ZoomableChartScrollWheel chart : charts)
            if (chart != source && chart.isTimeLinked()) chart.copyTimeView(source);
    }

    void linkCursors(ZoomableChartScrollWheel source)
    {
        if (!source.isTimeLinked()) return;
        for (ZoomableChartScrollWheel chart : charts)
            if (chart != source && chart.isTimeLinked()) chart.copyCursors(source);
    }

    void linkHover(ZoomableChartScrollWheel source)
    {
        if (!source.isTimeLinked()) return;
        for (ZoomableChartScrollWheel chart : charts)
            if (chart != source && chart.isTimeLinked()) chart.copyHover(source);
    }

    void linkPause(ZoomableChartScrollWheel source, boolean paused)
    {
        if (!source.isTimeLinked()) return;
        for (ZoomableChartScrollWheel chart : charts)
            if (chart != source && chart.isTimeLinked()) chart.applyPaused(paused);
    }

    double latestTime(ZoomableChartScrollWheel source)
    {
        double latest = source.latestTime();
        if (source.isTimeLinked())
            for (ZoomableChartScrollWheel chart : charts)
                if (chart.isTimeLinked()) latest = Math.max(latest, chart.latestTime());
        return latest;
    }

    double earliestTime(ZoomableChartScrollWheel source)
    {
        double earliest = source.earliestTime();
        if (source.isTimeLinked())
            for (ZoomableChartScrollWheel chart : charts)
                if (chart.isTimeLinked()) earliest = Math.min(earliest, chart.earliestTime());
        return earliest;
    }


    /**
     * Get start time in microseconds.
     * 
     * @return  start time of zcm-spy in microseconds
     */
    public long getStartTime()
    {
        return startuTime;
    }
}
