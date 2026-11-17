package com.victorious.bots;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Turns {@link Markdown} blocks into platform views, using only framework classes and the neon palette. */
public final class MarkdownView {
  /** What the host screen does with taps; the view itself never opens or copies anything. */
  public interface Actions {
    void copy(String text, String what);

    /** Called on tap only; the host must show the exact address and ask before opening it. */
    void openLink(String url, boolean mismatch);

    /** Long-press on reply text (the existing message menu). */
    void longPress();
  }

  private final Ui ui;

  public MarkdownView(Ui ui) {
    this.ui = ui;
  }

  public void render(LinearLayout parent, String body, Actions actions) {
    for (Markdown.Block b : Markdown.parse(body)) {
      switch (b.type) {
        case CODE_BLOCK:
          parent.addView(codeBlock(b, actions));
          break;
        case PARAGRAPH:
        case HEADING:
        case BULLET:
        case NUMBERED:
          parent.addView(textBlock(b, actions));
          break;
        default:
          throw new IllegalStateException("Unhandled block type " + b.type);
      }
    }
  }

  private TextView textBlock(Markdown.Block b, final Actions actions) {
    TextView t = ui.text("", b.type == Markdown.Type.HEADING ? 18 : 16, Ui.FG);
    SpannableStringBuilder sb = new SpannableStringBuilder();
    String prefix = b.type == Markdown.Type.BULLET ? "•  " : b.type == Markdown.Type.NUMBERED ? b.number + ".  " : "";
    sb.append(prefix);
    boolean links = false;
    for (final Markdown.Span s : b.spans) {
      int start = sb.length();
      sb.append(s.text);
      int end = sb.length();
      if ((s.style & Markdown.BOLD) != 0) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if ((s.style & Markdown.ITALIC) != 0) sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if ((s.style & Markdown.CODE) != 0) {
        sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BackgroundColorSpan(Ui.SURFACE_HI), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      if (s.isLink()) {
        links = true;
        sb.setSpan(new ClickableSpan() {
          @Override
          public void onClick(View v) {
            actions.openLink(s.url, s.mismatch);
          }

          @Override
          public void updateDrawState(TextPaint ds) {
            ds.setColor(Ui.ACCENT);
            ds.setUnderlineText(true);
          }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    if (b.type == Markdown.Type.BULLET || b.type == Markdown.Type.NUMBERED) {
      int first = ui.dp(6 + 14 * b.depth), rest = ui.dp(6 + 14 * b.depth + 16);
      sb.setSpan(new LeadingMarginSpan.Standard(first, rest), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    t.setText(sb);
    if (links) {
      t.setMovementMethod(LinkMovementMethod.getInstance());
      t.setHighlightColor(Color.TRANSPARENT);
    }
    else t.setTextIsSelectable(true);
    t.setOnLongClickListener(v -> {
      actions.longPress();
      return true;
    });
    return t;
  }

  private LinearLayout codeBlock(final Markdown.Block b, final Actions actions) {
    LinearLayout box = ui.column();
    box.setBackground(ui.bg(Ui.SURFACE_HI, 12));
    box.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, ui.dp(6), 0, ui.dp(6));
    box.setLayoutParams(lp);
    LinearLayout head = ui.row();
    String label = b.lang.isEmpty() ? "code" : b.lang;
    head.addView(ui.text(label, 11, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(ui.chip("Copy code", "Copy this code block", () -> actions.copy(b.code, "Code")));
    box.addView(head);
    TextView code = new TextView(ui.c);
    code.setText(b.code);
    code.setTypeface(Typeface.MONOSPACE);
    code.setTextSize(13);
    code.setTextColor(Ui.FG);
    code.setTextIsSelectable(true);
    code.setHorizontallyScrolling(true);
    HorizontalScrollView h = new HorizontalScrollView(ui.c);
    h.setHorizontalScrollBarEnabled(false);
    h.addView(code);
    box.addView(h, new LinearLayout.LayoutParams(-1, -2));
    return box;
  }
}
