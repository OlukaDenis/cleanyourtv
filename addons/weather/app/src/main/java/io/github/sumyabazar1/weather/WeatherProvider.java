package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only provider (content://io.github.sumyabazar1.weather.provider/summary) that supplies the
 * current weather and tomorrow's forecast to the launcher's Home screen and Clock screensaver widget
 * while keeping the launcher itself completely offline.
 */
public final class WeatherProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.provider";
    static final Uri SUMMARY_URI = Uri.parse("content://" + AUTHORITY + "/summary");
    private static final String[] COLUMNS = {"summary", "current", "tomorrow"};
    private static final String WIDGET_PREFS = "widget";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) ensureDefaultPlace(ctx);
        return true;
    }

    static void ensureDefaultPlace(Context ctx) {
        SharedPreferences place = ctx.getSharedPreferences("place", Context.MODE_PRIVATE);
        if (!place.contains("name")) {
            place.edit()
                    .putString("name", "Kampala")
                    .putLong("lat", Double.doubleToRawLongBits(0.3476))
                    .putLong("lon", Double.doubleToRawLongBits(32.5825))
                    .apply();
        }
        SharedPreferences widget = ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE);
        if (!widget.contains("summary")) {
            String cur = "\u263D 22\u00B0C Mostly clear";
            String tom = "Tomorrow: \u26C8 Thunderstorm 28\u00B0 / 20\u00B0C";
            widget.edit()
                    .putString("current", cur)
                    .putString("tomorrow", tom)
                    .putString("summary", cur + "   \u00B7   " + tom)
                    .putLong("fetched_ms", 0L)
                    .apply();
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) return null;
        ensureDefaultPlace(ctx);
        SharedPreferences widget = ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE);
        long fetched = widget.getLong("fetched_ms", 0L);
        long now = System.currentTimeMillis();
        if (now - fetched > Forecast.REFRESH_MS) {
            triggerRefresh(ctx.getApplicationContext());
        }
        String summary = widget.getString("summary", "");
        String current = widget.getString("current", "");
        String tomorrow = widget.getString("tomorrow", "");
        MatrixCursor cursor = new MatrixCursor(COLUMNS, 1);
        if (!summary.isEmpty()) {
            cursor.addRow(new Object[]{summary, current, tomorrow});
        }
        return cursor;
    }

    private void triggerRefresh(Context appCtx) {
        if (!refreshing.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                SharedPreferences place = appCtx.getSharedPreferences("place", Context.MODE_PRIVATE);
                SharedPreferences options = appCtx.getSharedPreferences("options", Context.MODE_PRIVATE);
                double lat = Double.longBitsToDouble(place.getLong("lat", Double.doubleToRawLongBits(0.3476)));
                double lon = Double.longBitsToDouble(place.getLong("lon", Double.doubleToRawLongBits(32.5825)));
                boolean fahrenheit = options.getBoolean("fahrenheit",
                        "f".equalsIgnoreCase(appCtx.getString(R.string.temperature_unit).trim()));
                Forecast.Report report = Forecast.fetch(lat, lon, Forecast.MIN_DAYS);
                if (report != null) {
                    saveReport(appCtx, report, fahrenheit);
                }
            } catch (Exception ignored) {
            } finally {
                refreshing.set(false);
            }
        });
    }

    static void saveReport(Context ctx, Forecast.Report r, boolean fahrenheit) {
        String unit = fahrenheit ? "F" : "C";
        String curIcon = iconFor(r.code, r.day);
        String curSky = ctx.getString(Forecast.sky(r.code, r.day));
        int curTemp = Forecast.degrees(r.tempC, fahrenheit);
        String current = curIcon + " " + curTemp + "\u00B0" + unit + " " + curSky;

        String tomorrow = "";
        if (!r.days.isEmpty()) {
            Forecast.Day d = r.days.get(0);
            String tomIcon = iconFor(d.code, true);
            String tomSky = ctx.getString(Forecast.sky(d.code, true));
            int hi = Forecast.degrees(d.maxC, fahrenheit);
            int lo = Forecast.degrees(d.minC, fahrenheit);
            tomorrow = "Tomorrow: " + tomIcon + " " + tomSky + " " + hi + "\u00B0 / " + lo + "\u00B0" + unit;
        }
        String summary = tomorrow.isEmpty() ? current : current + "   \u00B7   " + tomorrow;
        ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("current", current)
                .putString("tomorrow", tomorrow)
                .putString("summary", summary)
                .putLong("fetched_ms", System.currentTimeMillis())
                .apply();
        ctx.getContentResolver().notifyChange(SUMMARY_URI, null);
    }

    private static String iconFor(int code, boolean day) {
        if (code == 0 || code == 1) return day ? "\u2600" : "\u263D";
        if (code == 2) return "\u26C5";
        if (code == 3 || code == 45 || code == 48) return "\u2601";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\u2744";
        if (code >= 95) return "\u26C8";
        return "\uD83C\uDF27";
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
