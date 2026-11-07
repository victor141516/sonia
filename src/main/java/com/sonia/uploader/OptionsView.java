package com.sonia.uploader;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The camera's options screen: one row per option (label, value), the selected row highlighted
 * with arrows around its value, an explanation of that row, the web page's address and the keys.
 * Same look as {@link StatusView}; the activity owns the values and handles the keys.
 */
final class OptionsView extends LinearLayout {
    static final String HINT = "UP/DOWN  Choose        LEFT/RIGHT  Change        MENU  Back";

    private final TextView[] labels;
    private final TextView[] values;
    private final TextView[] leftArrows;
    private final TextView[] rightArrows;
    private final LinearLayout[] rows;
    private final TextView note;
    private final TextView address;

    OptionsView(Context context, String[] rowLabels) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(StatusView.BACKGROUND);
        int pad = StatusView.dp(context, 28);
        setPadding(pad, StatusView.dp(context, 18), pad, StatusView.dp(context, 14));

        TextView title = StatusView.text(context, 14, StatusView.TEXT_MUTED);
        title.setText("Upload to Google Photos");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        addView(title);

        TextView headline = StatusView.text(context, 26, StatusView.TEXT);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setText("Options");
        headline.setPadding(0, StatusView.dp(context, 6), 0, StatusView.dp(context, 8));
        addView(headline);

        labels = new TextView[rowLabels.length];
        values = new TextView[rowLabels.length];
        leftArrows = new TextView[rowLabels.length];
        rightArrows = new TextView[rowLabels.length];
        rows = new LinearLayout[rowLabels.length];
        for (int i = 0; i < rowLabels.length; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rowPad = StatusView.dp(context, 10);
            row.setPadding(rowPad, StatusView.dp(context, 7), rowPad, StatusView.dp(context, 7));
            labels[i] = StatusView.text(context, 19, StatusView.TEXT_SECONDARY);
            labels[i].setText(rowLabels[i]);
            row.addView(labels[i], new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            leftArrows[i] = arrow(context, "\u25C0", true);
            row.addView(leftArrows[i]);
            values[i] = StatusView.text(context, 19, StatusView.TEXT);
            values[i].setTypeface(Typeface.DEFAULT_BOLD);
            values[i].setSingleLine(true);
            values[i].setEllipsize(TextUtils.TruncateAt.END);
            values[i].setMaxWidth(StatusView.dp(context, 280));
            row.addView(values[i]);
            rightArrows[i] = arrow(context, "\u25B6", false);
            row.addView(rightArrows[i]);
            rows[i] = row;
            addView(row, new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT));
        }

        note = StatusView.text(context, 16, StatusView.TEXT_SECONDARY);
        note.setPadding(0, StatusView.dp(context, 10), 0, 0);
        note.setLineSpacing(0, 1.15f);
        addView(note, new LayoutParams(LayoutParams.FILL_PARENT, 0, 1f));

        address = StatusView.addressLine(getContext());
        address.setPadding(0, 0, 0, StatusView.dp(context, 4));
        addView(address);

        TextView hint = StatusView.text(context, 14, StatusView.TEXT_MUTED);
        hint.setText(HINT);
        addView(hint);
    }

    void show(String[] rowValues, int selected, String noteText, String pageUrl) {
        for (int i = 0; i < rows.length; i++) {
            boolean on = i == selected;
            values[i].setText(rowValues[i]);
            values[i].setTextColor(on ? StatusView.ACCENT : StatusView.TEXT);
            leftArrows[i].setVisibility(on ? View.VISIBLE : View.GONE);
            rightArrows[i].setVisibility(on ? View.VISIBLE : View.GONE);
            labels[i].setTextColor(on ? StatusView.TEXT : StatusView.TEXT_SECONDARY);
            if (on) {
                GradientDrawable highlight = new GradientDrawable();
                highlight.setColor(StatusView.SURFACE);
                highlight.setCornerRadius(StatusView.dp(getContext(), 8));
                rows[i].setBackgroundDrawable(highlight);
            } else {
                rows[i].setBackgroundDrawable(null);
            }
        }
        note.setText(noteText);
        StatusView.setAddress(address, pageUrl);
    }

    /** The arrows around the selected value; kept out of the value so a long album name cannot hide them. */
    private static TextView arrow(Context context, String glyph, boolean left) {
        TextView arrow = StatusView.text(context, 13, StatusView.ACCENT);
        arrow.setText(glyph);
        int gap = StatusView.dp(context, 10);
        arrow.setPadding(left ? 0 : gap, 0, left ? gap : 0, 0);
        arrow.setVisibility(View.GONE);
        return arrow;
    }
}
