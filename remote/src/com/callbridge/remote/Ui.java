package com.callbridge.remote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers so screens can be built in code (no XML resources needed). */
final class Ui {
    static final int BG = Color.rgb(0x12, 0x14, 0x18);
    static final int SURFACE = Color.rgb(0x1e, 0x22, 0x29);
    static final int TEXT = Color.rgb(0xec, 0xee, 0xf2);
    static final int MUTED = Color.rgb(0x8a, 0x93, 0xa3);
    static final int ACCENT = Color.rgb(0x4f, 0xa3, 0xf7);
    static final int GREEN = Color.rgb(0x2e, 0xb8, 0x5c);
    static final int RED = Color.rgb(0xe5, 0x48, 0x4d);

    private Ui() {}

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Button button(Context c, String label, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(16);
        b.setBackground(round(bg, dp(c, 28)));
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    /** Round button for keypads / call controls. */
    static TextView circle(Context c, String label, int bg, int fg, float sp, int sizeDp) {
        TextView t = text(c, label, sp, fg, false);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(bg);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        lp.setMargins(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        t.setLayoutParams(lp);
        t.setClickable(true);
        return t;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** Two-line list row: title + subtitle (+ optional right-side meta). */
    static View listRow(Context c, String title, String subtitle, String meta, boolean bold) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        LinearLayout texts = col(c);
        TextView t = text(c, title, 17, TEXT, bold);
        t.setSingleLine(true);
        TextView s = text(c, subtitle, 14, bold ? TEXT : MUTED, false);
        s.setSingleLine(true);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) texts.addView(s);
        r.addView(texts, weight(1));
        if (meta != null) {
            TextView m = text(c, meta, 12, MUTED, false);
            m.setPadding(dp(c, 8), 0, 0, 0);
            r.addView(m);
        }
        return r;
    }

    static String when(long ms) {
        if (ms <= 0) return "";
        return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    /** Compare phone numbers loosely (last 9 digits), so +44 7700 900123 matches 07700 900123 */
    static boolean sameNumber(String a, String b) {
        if (a == null || b == null) return false;
        String da = a.replaceAll("[^0-9]", ""), db = b.replaceAll("[^0-9]", "");
        if (da.isEmpty() || db.isEmpty()) return a.equalsIgnoreCase(b);
        int n = Math.min(9, Math.min(da.length(), db.length()));
        return da.substring(da.length() - n).equals(db.substring(db.length() - n));
    }
}
