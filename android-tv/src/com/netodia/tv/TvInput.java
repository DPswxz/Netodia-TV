package com.netodia.tv;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** A TV keyboard exit must return to Submit, rather than immediately reopening the IME. */
final class TvInput extends EditText {
  boolean keyboardRequested;
  Runnable closed, submit;
  private boolean closingBack;

  TvInput(Context context) {
    super(context);
    setShowSoftInputOnFocus(false);
  }

  void openKeyboard() {
    keyboardRequested = true;
    setShowSoftInputOnFocus(true);
    requestFocus();
    if (hasWindowFocus()) {
      post(() -> {
        if (!keyboardRequested || !isAttachedToWindow() || !hasFocus()) return;
        InputMethodManager manager =
            (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.restartInput(this);
        // A remote OK/search action is an explicit request. Some TV IMEs reject
        // SHOW_IMPLICIT even while Android reports that the keyboard is shown.
        manager.showSoftInput(this, 0);
      });
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && keyboardRequested) openKeyboard();
  }

  @Override
  public boolean performClick() {
    boolean handled = super.performClick();
    openKeyboard();
    return handled;
  }

  @Override
  public boolean onKeyPreIme(int key, KeyEvent event) {
    if (key == KeyEvent.KEYCODE_MENU) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && submit != null) submit.run();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BACK && (keyboardRequested || closingBack)) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) {
        closingBack = true;
        keyboardRequested = false;
        setShowSoftInputOnFocus(false);
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(getWindowToken(), 0);
        if (closed != null) closed.run();
      } else if (event.getAction() == KeyEvent.ACTION_UP) closingBack = false;
      return true;
    }
    return super.onKeyPreIme(key, event);
  }
}
