package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Reads the current weather and tomorrow's forecast from the local weather provider
 * (io.github.sumyabazar1.weather.provider) off the UI thread and displays it on the Home
 * header and Clock screensaver without network access in the launcher.
 */
final class WeatherWidget {
    private static final String AUTHORITY = "io.github.sumyabazar1.weather.provider";
    private static final Uri URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .appendPath("summary")
            .build();
    private static final String[] COLUMNS = {"summary", "current", "tomorrow"};

    private final Context ctx;
    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ContentObserver observer = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            read();
        }
    };
    private boolean watching;

    WeatherWidget(Context ctx, TextView view) {
        this.ctx = ctx.getApplicationContext();
        this.view = view;
    }

    void start() {
        if (view == null) return;
        ProviderInfo info = ctx.getPackageManager().resolveContentProvider(AUTHORITY, 0);
        if (info == null) {
            view.setVisibility(View.GONE);
            return;
        }
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(URI, false, observer);
                watching = true;
            } catch (SecurityException ignored) {
            }
        }
        read();
    }

    void stop() {
        if (watching) {
            try {
                ctx.getContentResolver().unregisterContentObserver(observer);
            } catch (RuntimeException ignored) {
            }
            watching = false;
        }
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    void read() {
        if (view == null) return;
        try {
            io.execute(() -> {
                String text = "";
                try (Cursor c = ctx.getContentResolver().query(URI, COLUMNS, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        String s = c.getString(0);
                        if (s != null) text = s.trim();
                    }
                } catch (RuntimeException ignored) {
                    return;
                }
                final String result = text;
                main.post(() -> {
                    if (result.isEmpty()) {
                        view.setVisibility(View.GONE);
                    } else {
                        view.setText(result);
                        view.setVisibility(View.VISIBLE);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
        }
    }
}
