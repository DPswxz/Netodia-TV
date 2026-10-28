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
    ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
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
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(getWindowToken(), 0);
        if (closed != null) closed.run();
      } else if (event.getAction() == KeyEvent.ACTION_UP) closingBack = false;
      return true;
    }
    return super.onKeyPreIme(key, event);
  }
}
