package com.sonia.uploader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The app's main screen: one status line, an explanation under it, an optional progress bar
 * with percentage and speed, the web page's address once the camera is online, and a key hint at
 * the bottom. Dark, large type for the camera LCD.
 */
final class StatusView extends LinearLayout {
    /** Immutable screen content. */
    static final class Status {
        static final int NORMAL = 0;
        static final int OK = 1;
        static final int WARNING = 2;
        static final int ERROR = 3;

        final int tone;
        final String headline;
        final String detail;
        /** Large accent line under the detail (e.g. the web address to open); empty if none. */
        final String emphasis;
        /** Smaller text under the emphasis; empty if none. */
        final String footnote;
        final String hint;
        final boolean progress;
        final int permille;
        final String progressLeft;
        final String progressRight;
        final String progressBottom;

        private Status(int tone, String headline, String detail, String emphasis, String footnote, String hint,
                boolean progress, int permille, String progressLeft, String progressRight, String progressBottom) {
            this.tone = tone;
            this.headline = headline;
            this.detail = detail;
            this.emphasis = emphasis;
            this.footnote = footnote;
            this.hint = hint;
            this.progress = progress;
            this.permille = permille;
            this.progressLeft = progressLeft;
            this.progressRight = progressRight;
            this.progressBottom = progressBottom;
        }

        static Status message(int tone, String headline, String detail, String hint) {
            return new Status(tone, headline, detail, "", "", hint, false, 0, "", "", "");
        }

        static Status withAddress(int tone, String headline, String detail, String address, String footnote,
                String hint) {
            return new Status(tone, headline, detail, address, footnote, hint, false, 0, "", "", "");
        }

        static Status progress(String headline, String detail, int permille, String left, String right,
                String bottom, String hint) {
            return new Status(NORMAL, headline, detail, "", "", hint, true, permille, left, right, bottom);
        }

        /** Same content with another hint line. */
        Status withHint(String newHint) {
            return new Status(tone, headline, detail, emphasis, footnote, newHint, progress, permille, progressLeft,
                    progressRight, progressBottom);
        }
    }

    static final int BACKGROUND = 0xFF121212;
    static final int SURFACE = 0xFF2A2D31;
    static final int TEXT = 0xFFE8EAED;
    static final int TEXT_SECONDARY = 0xFFBDC1C6;
    static final int TEXT_MUTED = 0xFF9AA0A6;
    private static final int TRACK = 0xFF3C4043;
    static final int ACCENT = 0xFF8AB4F8;
    private static final int GREEN = 0xFF81C995;
    private static final int AMBER = 0xFFFDD663;
    private static final int RED = 0xFFF28B82;

    private final TextView headline;
    private final TextView detail;
    private final TextView emphasis;
    private final TextView footnote;
    private final LinearLayout progressBlock;
    private final ProgressBar bar;
    private final TextView progressLeft;
    private final TextView progressRight;
    private final TextView progressBottom;
    private final TextView address;
    private final TextView hint;
    private String pageUrl = "";
    private Status shown;

    StatusView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(BACKGROUND);
        setPadding(dp(context, 28), dp(context, 18), dp(context, 28), dp(context, 14));

        TextView title = text(context, 14, TEXT_MUTED);
        title.setText("Upload to Google Photos");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        addView(title);

        LinearLayout middle = new LinearLayout(context);
        middle.setOrientation(VERTICAL);
        middle.setGravity(Gravity.CENTER_VERTICAL);
        addView(middle, new LayoutParams(LayoutParams.FILL_PARENT, 0, 1f));

        headline = text(context, 26, TEXT);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        middle.addView(headline);

        detail = text(context, 17, TEXT_SECONDARY);
        detail.setPadding(0, dp(context, 8), 0, 0);
        detail.setLineSpacing(0, 1.15f);
        middle.addView(detail);

        emphasis = text(context, 24, ACCENT);
        emphasis.setTypeface(Typeface.DEFAULT_BOLD);
        emphasis.setPadding(0, dp(context, 10), 0, dp(context, 2));
        middle.addView(emphasis);

        footnote = text(context, 17, TEXT_SECONDARY);
        footnote.setPadding(0, dp(context, 6), 0, 0);
        footnote.setLineSpacing(0, 1.15f);
        middle.addView(footnote);

        progressBlock = new LinearLayout(context);
        progressBlock.setOrientation(VERTICAL);
        progressBlock.setPadding(0, dp(context, 22), 0, 0);
        middle.addView(progressBlock);

        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        bar.setProgressDrawable(barDrawable(context));
        progressBlock.addView(bar, new LayoutParams(LayoutParams.FILL_PARENT, dp(context, 12)));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(context, 10), 0, 0);
        progressLeft = text(context, 17, TEXT);
        row.addView(progressLeft, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        progressRight = text(context, 17, TEXT);
        progressRight.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(progressRight);
        progressBlock.addView(row);

        progressBottom = text(context, 15, TEXT_MUTED);
        progressBottom.setPadding(0, dp(context, 4), 0, 0);
        progressBlock.addView(progressBottom);

        address = addressLine(context);
        address.setPadding(0, 0, 0, dp(context, 4));
        addView(address);

        hint = text(context, 14, TEXT_MUTED);
        addView(hint);
    }

    /** The web page's address, shown under every status once the camera is online. */
    void setPageUrl(String url) {
        pageUrl = url;
        if (shown != null) {
            show(shown);
        }
    }

    void show(Status status) {
        shown = status;
        headline.setText(status.headline);
        headline.setTextColor(toneColor(status.tone));
        detail.setText(status.detail);
        detail.setVisibility(status.detail.length() > 0 ? View.VISIBLE : View.GONE);
        emphasis.setText(status.emphasis);
        emphasis.setVisibility(status.emphasis.length() > 0 ? View.VISIBLE : View.GONE);
        footnote.setText(status.footnote);
        footnote.setVisibility(status.footnote.length() > 0 ? View.VISIBLE : View.GONE);
        progressBlock.setVisibility(status.progress ? View.VISIBLE : View.GONE);
        if (status.progress) {
            bar.setProgress(status.permille);
            progressLeft.setText(status.progressLeft);
            progressRight.setText(status.progressRight);
            progressBottom.setText(status.progressBottom);
        }
        // Screens that already show the address in large type do not repeat it.
        setAddress(address, pageUrl.length() > 0 && !status.emphasis.equals(pageUrl) ? pageUrl : "");
        hint.setText(status.hint);
    }

    /** "Settings on the web: http://..." line, shared with the options screen. */
    static TextView addressLine(Context context) {
        TextView line = text(context, 15, TEXT_SECONDARY);
        line.setVisibility(View.GONE);
        return line;
    }

    static void setAddress(TextView line, String url) {
        if (url.length() == 0) {
            line.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder text = new SpannableStringBuilder("Options on the web:  ");
        int start = text.length();
        text.append(url);
        text.setSpan(new ForegroundColorSpan(ACCENT), start, text.length(), 0);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), 0);
        line.setText(text);
        line.setVisibility(View.VISIBLE);
    }

    private static int toneColor(int tone) {
        switch (tone) {
            case Status.OK: return GREEN;
            case Status.WARNING: return AMBER;
            case Status.ERROR: return RED;
            default: return TEXT;
        }
    }

    private static Drawable barDrawable(Context context) {
        float radius = dp(context, 6);
        GradientDrawable track = new GradientDrawable();
        track.setColor(TRACK);
        track.setCornerRadius(radius);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(ACCENT);
        fill.setCornerRadius(radius);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {
            track, new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    static TextView text(Context context, float sp, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setBackgroundColor(Color.TRANSPARENT);
        return view;
    }

    static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
