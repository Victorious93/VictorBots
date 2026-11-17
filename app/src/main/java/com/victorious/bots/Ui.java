package com.victorious.bots;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.concurrent.Callable;

/** Small programmatic view toolkit shared by all screens (the app deliberately uses no UI framework). */
public final class Ui {
  /** Fleet neon theme; mirrors res/values/colors.xml (tests/jvm/ThemeTest.java keeps both honest). Dark surfaces only. */
  public static final int BG = 0xff0b0f1a, CARD = 0xff121829, SURFACE_HI = 0xff1a2236, USER_BUBBLE = 0xff2a1738;
  public static final int FG = 0xffe8f1ff, MUTED = 0xff9aa7c7, ACCENT = 0xff00e5ff, ACCENT2 = 0xffff2bd6, WARN = 0xffffc46b, BAD = 0xffff7a8a;
  public final Context c;

  public Ui(Context c) {
    this.c = c;
  }

  public int dp(int n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  public GradientDrawable bg(int color, int radius, int stroke) {
    GradientDrawable d = bg(color, radius);
    d.setStroke(dp(1), stroke);
    return d;
  }

  public LinearLayout column() {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public LinearLayout row() {
    LinearLayout l = new LinearLayout(c);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public TextView text(String s, int size, int color) {
    TextView t = new TextView(c);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(4), 0, dp(4));
    return t;
  }

  public TextView heading(String s, int size) {
    TextView t = text(s, size, FG);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  public void gap(LinearLayout l, int h) {
    l.addView(new View(c), new LinearLayout.LayoutParams(1, dp(h)));
  }

  public Button button(String label, Runnable run, boolean primary) {
    Button b = new Button(c);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(primary ? BG : FG);
    b.setBackground(primary ? bg(ACCENT, 16) : bg(SURFACE_HI, 16, ACCENT2));
    b.setMinHeight(dp(46));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    b.setOnClickListener(v -> run.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(6), 0, dp(6));
    b.setLayoutParams(p);
    return b;
  }

  /** Cyan-outlined action on the dark background; used by the optional VictorLinux preset. */
  public Button neonButton(String label, Runnable run) {
    Button b = button(label, run, false);
    b.setBackground(bg(BG, 16, ACCENT));
    b.setTextColor(ACCENT);
    return b;
  }

  /** A compact action button (copy, retry, edit) that sits in a bubble's action row. */
  public Button chip(String label, String description, Runnable run) {
    Button b = new Button(c);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(12);
    b.setTextColor(FG);
    b.setBackground(bg(SURFACE_HI, 12, ACCENT2));
    b.setMinHeight(0);
    b.setMinimumHeight(dp(36));
    b.setMinWidth(0);
    b.setMinimumWidth(dp(56));
    b.setPadding(dp(12), dp(4), dp(12), dp(4));
    b.setContentDescription(description);
    b.setOnClickListener(v -> run.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
    p.setMargins(0, dp(4), dp(8), dp(2));
    b.setLayoutParams(p);
    return b;
  }

  public LinearLayout card(LinearLayout parent) {
    LinearLayout l = column();
    l.setPadding(dp(18), dp(16), dp(18), dp(16));
    l.setBackground(bg(CARD, 22));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(6), 0, dp(8));
    parent.addView(l, p);
    return l;
  }

  public EditText field(LinearLayout parent, String label, String value, boolean multi) {
    parent.addView(text(label, 12, ACCENT));
    EditText e = new EditText(c);
    e.setText(value);
    e.setTextSize(16);
    e.setTextColor(FG);
    e.setHintTextColor(MUTED);
    e.setBackground(bg(SURFACE_HI, 12));
    e.setPadding(dp(12), dp(10), dp(12), dp(10));
    e.setSingleLine(!multi);
    e.setInputType(InputType.TYPE_CLASS_TEXT | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    if (multi) {
      e.setMinLines(3);
      e.setGravity(Gravity.TOP);
    }
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    gap(parent, 10);
    return e;
  }

  public ScrollView scroll(View child) {
    ScrollView s = new ScrollView(c);
    s.setFillViewport(true);
    s.addView(child);
    return s;
  }

  public Switch toggle(LinearLayout parent, String label, boolean on) {
    Switch s = new Switch(c);
    s.setText(label);
    s.setTextColor(FG);
    s.setChecked(on);
    parent.addView(s);
    return s;
  }

  public void toast(String s) {
    Toast.makeText(c, s, Toast.LENGTH_LONG).show();
  }

  public void info(String title, String msg) {
    new AlertDialog.Builder(c).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
  }

  public void confirm(String title, String msg, String yes, Runnable onYes) {
    new AlertDialog.Builder(c).setTitle(title).setMessage(msg).setNegativeButton("Cancel", null).setPositiveButton(yes, (d, w) -> onYes.run()).show();
  }

  public void choose(String title, String[] items, java.util.function.IntConsumer onPick) {
    new AlertDialog.Builder(c).setTitle(title).setItems(items, (d, w) -> onPick.accept(w)).show();
  }

  /** A form dialog whose Save button stays open until validation passes. */
  public void form(String title, View body, String positive, Callable<Boolean> onSave, Runnable onError) {
    AlertDialog dialog = new AlertDialog.Builder(c).setTitle(title).setView(scroll(body)).setNegativeButton("Cancel", null).setPositiveButton(positive, null).create();
    dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
      try {
        if (onSave.call()) dialog.dismiss();
      } catch (Exception e) {
        new AlertDialog.Builder(c).setTitle("Could not continue").setMessage(e.getMessage() == null ? "The operation failed. Nothing was changed." : e.getMessage()).setPositiveButton("OK", null).show();
      }
    }));
    dialog.show();
  }

  public static String clip(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n) + "…";
  }
}
