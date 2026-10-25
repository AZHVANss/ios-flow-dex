package ni.iosflow.frame;

import android.os.Handler;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.view.FrameMetrics;
import android.view.Window;

public final class FrameBridge {
    private static final long WORK_MAX_NS = 1000000000L;
    private static final double SLOW_FACTOR = 1.5;
    private static final double EMA_ALPHA = 0.1;
    private static final int FAIL_LIMIT = 30;
    private static final int[] PARTS = {
            FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION,
            FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION};

    private static Reporter active;
    private static Window activeWindow;
    private static Handler mainHandler;

    private FrameBridge() {
    }

    public static void load() {
    }

    public static void unload() {
        stop();
    }

    public static synchronized void start(Object window, Object handler, Object session, Object verifyCallback) {
        stop();
        Reporter reporter = new Reporter();
        reporter.session = (PerformanceHintManager.Session) session;
        reporter.verifyCallback = (Runnable) verifyCallback;
        Window target = (Window) window;
        target.addOnFrameMetricsAvailableListener(reporter, (Handler) handler);
        active = reporter;
        activeWindow = target;
    }

    public static synchronized void stop() {
        Reporter reporter = active;
        Window window = activeWindow;
        active = null;
        activeWindow = null;
        if (reporter != null && window != null) {
            try {
                window.removeOnFrameMetricsAvailableListener(reporter);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void setSession(Object session, Object sink) {
        Reporter reporter = active;
        if (reporter != null) {
            reporter.sink = toBool(sink);
            reporter.session = (PerformanceHintManager.Session) session;
        }
    }

    public static void setSampleStep(Object step) {
        Reporter reporter = active;
        if (reporter != null) {
            reporter.sampStep = toInt(step);
        }
    }

    public static void setFrameNs(Object ns) {
        Reporter reporter = active;
        if (reporter != null) {
            reporter.frameNs = toDouble(ns);
        }
    }

    public static void setLagOn(Object on) {
        Reporter reporter = active;
        if (reporter != null) {
            reporter.lagOn = toBool(on);
        }
    }

    public static void setVerifyAt(Object frames) {
        Reporter reporter = active;
        if (reporter != null) {
            synchronized (reporter.lock) {
                reporter.verifyAt = toLong(frames);
            }
        }
    }

    public static void clearOff() {
        Reporter reporter = active;
        if (reporter != null) {
            synchronized (reporter.lock) {
                reporter.off = false;
                reporter.fail = 0;
                reporter.error = "";
            }
        }
    }

    public static void reset() {
        Reporter reporter = active;
        if (reporter != null) {
            reporter.clearCounters();
        }
    }

    public static void seed(Object csv) {
        Reporter reporter = active;
        if (reporter == null || csv == null) {
            return;
        }
        String[] p = String.valueOf(csv).split(";");
        if (p.length < 11) {
            return;
        }
        synchronized (reporter.lock) {
            reporter.reports = Long.parseLong(p[0]);
            reporter.slow = Long.parseLong(p[1]);
            reporter.workSum = Long.parseLong(p[2]);
            reporter.ema = Double.parseDouble(p[3]);
            reporter.lagN = Long.parseLong(p[4]);
            for (int i = 0; i < PARTS.length; i++) {
                reporter.lagSums[i] = Long.parseLong(p[5 + i]);
            }
        }
    }

    public static String snapshot() {
        Reporter reporter = active;
        if (reporter == null) {
            return "";
        }
        synchronized (reporter.lock) {
            StringBuilder sb = new StringBuilder(160);
            sb.append(reporter.reports).append(';').append(reporter.slow).append(';')
                    .append(reporter.workSum).append(';').append(reporter.ema).append(';')
                    .append(reporter.lagN);
            for (int i = 0; i < PARTS.length; i++) {
                sb.append(';').append(reporter.lagSums[i]);
            }
            sb.append(';').append(reporter.off ? 1 : 0).append(';').append(reporter.fail)
                    .append(';').append(reporter.verifyAt).append(';').append(reporter.error);
            return sb.toString();
        }
    }

    private static boolean toBool(Object o) {
        return o instanceof Boolean && ((Boolean) o).booleanValue();
    }

    private static int toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static long toLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static double toDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private static synchronized void postMain(Runnable callback) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        mainHandler.post(callback);
    }

    private static final class Reporter implements Window.OnFrameMetricsAvailableListener {
        final Object lock = new Object();
        final long[] tmp = new long[PARTS.length];
        final long[] lagSums = new long[PARTS.length];

        volatile PerformanceHintManager.Session session;
        volatile boolean sink;
        volatile int sampStep;
        volatile double frameNs;
        volatile boolean lagOn;
        volatile Runnable verifyCallback;
        volatile boolean off;

        long reports;
        long slow;
        long workSum;
        long lagN;
        long verifyAt;
        double ema;
        int fail;
        String error = "";

        private int frameCount;

        void clearCounters() {
            synchronized (lock) {
                reports = 0;
                slow = 0;
                workSum = 0;
                lagN = 0;
                ema = 0.0;
                verifyAt = 0;
                for (int i = 0; i < lagSums.length; i++) {
                    lagSums[i] = 0;
                }
            }
        }

        @Override
        public void onFrameMetricsAvailable(Window window, FrameMetrics metrics, int dropCount) {
            try {
                if (off) {
                    return;
                }
                PerformanceHintManager.Session s = session;
                boolean counting = sink;
                if (s == null && !counting) {
                    return;
                }
                int step = sampStep;
                if (step > 0) {
                    frameCount++;
                    double e = ema;
                    if (frameCount % step != 0 && e > 0) {
                        if (s != null) {
                            s.reportActualWorkDuration(Math.max(1L, (long) e));
                        }
                        return;
                    }
                }
                long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                        - metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION);
                double fns = frameNs;
                boolean slowFrame = fns > 0 && total > fns * SLOW_FACTOR;
                boolean record = lagOn && slowFrame;
                long work = 0;
                for (int i = 0; i < PARTS.length; i++) {
                    long v = metrics.getMetric(PARTS[i]);
                    work += v;
                    tmp[i] = v;
                }
                work = Math.max(1L, Math.min(WORK_MAX_NS, work));
                if (s != null) {
                    s.reportActualWorkDuration(work);
                }
                boolean fire = false;
                synchronized (lock) {
                    fail = 0;
                    reports++;
                    if (record) {
                        for (int i = 0; i < PARTS.length; i++) {
                            lagSums[i] += tmp[i];
                        }
                        lagN++;
                    }
                    if (verifyAt > 0 && reports >= verifyAt) {
                        verifyAt = 0;
                        fire = true;
                    }
                    workSum += work;
                    if (slowFrame) {
                        slow++;
                    }
                    ema = ema <= 0 ? (double) work : ema + EMA_ALPHA * (work - ema);
                }
                if (fire) {
                    Runnable callback = verifyCallback;
                    if (callback != null) {
                        postMain(callback);
                    }
                }
            } catch (Throwable t) {
                synchronized (lock) {
                    fail++;
                    if (fail >= FAIL_LIMIT) {
                        off = true;
                        error = t.getClass().getSimpleName();
                    }
                }
            }
        }
    }
}
