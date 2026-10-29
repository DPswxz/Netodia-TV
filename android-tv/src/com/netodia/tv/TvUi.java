package com.netodia.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.*;

/** Direct desktop palette data, shared source fonts and Fluent controls. */
final class TvUi {
  static int BASE = 0xff202020, MANTLE = 0xff1c1c1c, CRUST = 0xff171717;
  static int SURFACE = 0xff2d2d2d, SURFACE1 = 0xff383838, TEXT = 0xffffffff;
  static int MUTED = 0xffc5c5c5,
      ACCENT = 0xff60cdff,
      BORDER = 0xff3d3d3d,
      GREEN = 0xff6ccb5f,
      RED = 0xffff6767;
  static float SCALE = 1;
  static Typeface symbols, filled, font, fontBold;
  static JSONArray themes = new JSONArray();

  static void initialize(Context c) {
    if (symbols == null) {
      symbols = Typeface.createFromAsset(c.getAssets(), "fonts/MaterialSymbolsRounded.ttf");
      filled = Typeface.createFromAsset(c.getAssets(), "fonts/MaterialSymbolsRoundedFilled.ttf");
      font =
          new Typeface.Builder(c.getAssets(), "fonts/NotoSansSC-VF.ttf")
              .setFontVariationSettings("'wght' 400")
              .setWeight(400)
              .build();
      fontBold =
          new Typeface.Builder(c.getAssets(), "fonts/NotoSansSC-VF.ttf")
              .setFontVariationSettings("'wght' 600")
              .setWeight(600)
              .build();
      try {
        themes = new JSONObject(Native.call("themes", "{}")).getJSONArray("data");
      } catch (Exception ignored) {
      }
    }
    apply(c);
  }

  static JSONObject theme(Context c) {
    String id = c.getSharedPreferences("music", 0).getString("theme", "windows-fluent");
    for (int i = 0; i < themes.length(); i++)
      if (themes.optJSONObject(i).optString("id").equals(id)) return themes.optJSONObject(i);
    return new JSONObject();
  }

  static String matchVariant(JSONObject oldTheme, JSONObject newTheme, String chosen) {
    if (chosen.equals("system")) return chosen;
    JSONArray oldVariants = oldTheme.optJSONArray("variants"),
        variants = newTheme.optJSONArray("variants");
    boolean light = false;
    if (oldVariants != null)
      for (int i = 0; i < oldVariants.length(); i++) {
        JSONObject variant = oldVariants.optJSONObject(i);
        if (variant.optString("id").equals(chosen)) light = lightPalette(variant);
      }
    if (variants != null) {
      for (int i = 0; i < variants.length(); i++)
        if (variants.optJSONObject(i).optString("id").equals(chosen)) return chosen;
      for (int i = 0; i < variants.length(); i++)
        if (lightPalette(variants.optJSONObject(i)) == light)
          return variants.optJSONObject(i).optString("id");
    }
    return newTheme.optString("defaultVariant", "dark");
  }

  private static boolean lightPalette(JSONObject variant) {
    int color = variant.optInt("base");
    return ((color >> 16) & 255) * .2126 + ((color >> 8) & 255) * .7152 + (color & 255) * .0722
        > 127;
  }

  static void apply(Context c) {
    android.content.SharedPreferences p = c.getSharedPreferences("music", 0);
    SCALE = p.getInt("ui_scale", 100) / 100f;
    JSONObject theme = theme(c);
    JSONArray variants = theme.optJSONArray("variants");
    if (variants == null) return;
    String variant = p.getString("variant", theme.optString("defaultVariant", "dark"));
    if (variant.equals("system")) {
      boolean light = (c.getResources().getConfiguration().uiMode & 0x30) != 0x20;
      for (int i = 0; i < variants.length(); i++)
        if (lightPalette(variants.optJSONObject(i)) == light) {
          variant = variants.optJSONObject(i).optString("id");
          break;
        }
    }
    JSONObject selected = variants.optJSONObject(0);
    for (int i = 0; i < variants.length(); i++)
      if (variants.optJSONObject(i).optString("id").equals(variant))
        selected = variants.optJSONObject(i);
    BASE = color(selected, "base");
    MANTLE = color(selected, "mantle");
    CRUST = color(selected, "crust");
    SURFACE = color(selected, "surface0");
    SURFACE1 = color(selected, "surface1");
    TEXT = color(selected, "text");
    MUTED = color(selected, "muted");
    BORDER = color(selected, "border");
    RED = color(selected, "red");
    GREEN = color(selected, "green");
    JSONArray accents = theme.optJSONArray("accents");
    String accent = p.getString("accent", theme.optString("defaultAccent", "blue"));
    if (accents != null)
      for (int i = 0; i < accents.length(); i++) {
        JSONObject a = accents.optJSONObject(i);
        if (!a.optString("id").equals(accent)) continue;
        JSONArray colors = a.optJSONArray("colors");
        if (colors != null && colors.length() > 0) {
          ACCENT = color(colors.optJSONObject(0), "color");
          for (int j = 0; j < colors.length(); j++)
            if (colors.optJSONObject(j).optString("variant").equals(variant))
              ACCENT = color(colors.optJSONObject(j), "color");
        }
      }
  }

  private static int color(JSONObject object, String key) {
    return 0xff000000 | object.optInt(key);
  }

  static int dp(Context c, float value) {
    return Math.round(value * SCALE * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable fill(Context c, int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static StateListDrawable focus(Context c, boolean transparent) {
    StateListDrawable d = new StateListDrawable();
    GradientDrawable focused = fill(c, SURFACE1, 8);
    focused.setStroke(dp(c, 1.5f), ACCENT);
    d.addState(new int[] {android.R.attr.state_focused}, focused);
    d.addState(new int[] {android.R.attr.state_pressed}, fill(c, SURFACE1, 8));
    d.addState(new int[] {android.R.attr.state_selected}, fill(c, SURFACE1, 8));
    d.addState(new int[] {}, fill(c, transparent ? Color.TRANSPARENT : SURFACE, 8));
    return d;
  }

  static StateListDrawable artworkFocus(Context c) {
    StateListDrawable states = new StateListDrawable();
    GradientDrawable focused = fill(c, 0x18ffffff, 8);
    focused.setStroke(dp(c, 1.2f), 0xb3ffffff);
    states.addState(new int[] {android.R.attr.state_focused}, focused);
    states.addState(new int[] {android.R.attr.state_pressed}, fill(c, 0x24ffffff, 8));
    states.addState(new int[] {}, fill(c, Color.TRANSPARENT, 8));
    return states;
  }

  static StateListDrawable lyricFocus(Context c) {
    StateListDrawable states = new StateListDrawable();
    LayerDrawable selected =
        new LayerDrawable(new Drawable[] {fill(c, 0x0cffffff, 8), fill(c, ACCENT, 2)});
    selected.setLayerWidth(1, dp(c, 2));
    selected.setLayerHeight(1, dp(c, 24));
    selected.setLayerGravity(1, Gravity.LEFT | Gravity.CENTER_VERTICAL);
    states.addState(new int[] {android.R.attr.state_focused}, selected);
    states.addState(new int[] {android.R.attr.state_pressed}, fill(c, 0x14ffffff, 8));
    states.addState(new int[] {}, fill(c, Color.TRANSPARENT, 8));
    return states;
  }

  static TextView text(Context c, String label, int size, int color) {
    TextView v = new TextView(c);
    v.setText(label);
    v.setTextSize(size * SCALE);
    v.setTextColor(color);
    v.setTypeface(font);
    v.setIncludeFontPadding(false);
    return v;
  }

  static TextView glyph(Context c, String name, int size, int color) {
    if (name.equals("qr_code") || name.equals("logout")) {
      final android.graphics.drawable.Drawable icon =
          c.getDrawable(
                  c.getResources()
                      .getIdentifier(
                          name.equals("qr_code") ? "ic_qr_code" : "ic_log_out",
                          "drawable",
                          c.getPackageName()))
              .mutate();
      icon.setTint(color);
      return new TextView(c) {
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
          int side = Math.min(dp(c, size), Math.min(getWidth(), getHeight()));
          int x = (getWidth() - side) / 2, y = (getHeight() - side) / 2;
          icon.setBounds(x, y, x + side, y + side);
          icon.draw(canvas);
        }
      };
    }
    if (name.equals("check_box") || name.equals("check_box_outline_blank")) {
      final boolean checked = name.equals("check_box");
      return new TextView(c) {
        final android.graphics.Paint paint =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
          float side = Math.min(dp(c, size), Math.min(getWidth(), getHeight()));
          float x = (getWidth() - side) / 2 + side * .15f,
              y = (getHeight() - side) / 2 + side * .15f,
              w = side * .7f;
          paint.setColor(color);
          paint.setStyle(
              checked ? android.graphics.Paint.Style.FILL : android.graphics.Paint.Style.STROKE);
          paint.setStrokeWidth(dp(c, 1));
          canvas.drawRoundRect(x, y, x + w, y + w, side * .14f, side * .14f, paint);
          if (checked) {
            paint.setColor(MANTLE);
            paint.setStrokeWidth(dp(c, 1.6f));
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x + w * .2f, y + w * .5f);
            path.lineTo(x + w * .43f, y + w * .72f);
            path.lineTo(x + w * .8f, y + w * .28f);
            canvas.drawPath(path, paint);
          }
        }
      };
    }
    TextView v = text(c, name, size, color);
    v.setTypeface(symbols);
    v.setFontFeatureSettings("liga");
    v.setGravity(Gravity.CENTER);
    v.setSingleLine(true);
    return v;
  }

  static void icon(Button button, String name) {
    button.setTypeface(symbols);
    button.setFontFeatureSettings("liga");
    button.setText(name);
    button.setTextSize(24 * SCALE);
  }

  static void setText(TextView v, String value) {
    if (v != null && !value.contentEquals(v.getText())) v.setText(value);
  }

  static void eagerTouchFocus(View view) {
    view.setOnTouchListener(
        (v, event) -> {
          if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && !v.isFocused())
            v.requestFocus();
          return false;
        });
  }

  static Button button(Context c, String label, Runnable action) {
    Button b = new Button(c);
    b.setText(label);
    b.setTypeface(font);
    b.setTextSize(17 * SCALE);
    b.setTextColor(TEXT);
    b.setAllCaps(false);
    b.setIncludeFontPadding(false);
    b.setGravity(Gravity.CENTER);
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(c, 44));
    b.setMinimumHeight(dp(c, 44));
    b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
    b.setBackground(focus(c, false));
    b.setId(View.generateViewId());
    b.setOnClickListener(v -> action.run());
    b.setSoundEffectsEnabled(true);
    eagerTouchFocus(b);
    return b;
  }

  static LinearLayout column(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(1);
    return v;
  }

  static LinearLayout row(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(0);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  static ImageView cover(Context c, int size) {
    ImageView image = new ImageView(c);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackground(fill(c, SURFACE, 8));
    image.setClipToOutline(true);
    image.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
    return image;
  }

  static void heading(TextView v) {
    v.setTypeface(fontBold);
  }

  static void seekStyle(Context c, SeekBar seek) {
    GradientDrawable track = fill(c, BORDER, 2), played = fill(c, ACCENT, 2);
    ClipDrawable clip = new ClipDrawable(played, Gravity.LEFT, ClipDrawable.HORIZONTAL);
    LayerDrawable layers = new LayerDrawable(new Drawable[] {track, clip});
    layers.setId(0, android.R.id.background);
    layers.setId(1, android.R.id.progress);
    for (int i = 0; i < 2; i++) {
      layers.setLayerHeight(i, dp(c, 4));
      layers.setLayerGravity(i, Gravity.CENTER_VERTICAL);
    }
    seek.setProgressDrawable(layers);
    GradientDrawable thumb = fill(c, ACCENT, 2);
    thumb.setSize(dp(c, 4), dp(c, 24));
    seek.setThumb(thumb);
    seek.setThumbOffset(dp(c, 2));
  }
}
