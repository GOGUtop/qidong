package io.github.stlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.webkit.WebView;

/** Suppresses WebView's Android copy/paste/select-all toolbar, when requested. */
public final class BlockableWebView extends WebView {
    private boolean blockNativeSelection;

    public BlockableWebView(Context context) { super(context); }
    public BlockableWebView(Context context, AttributeSet attrs) { super(context, attrs); }

    void setBlockNativeSelection(boolean value) {
        blockNativeSelection = value;
        if (value) clearFocus();
    }

    @Override public ActionMode startActionMode(ActionMode.Callback callback) {
        return blockNativeSelection ? null : super.startActionMode(callback);
    }

    @Override public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        return blockNativeSelection ? null : super.startActionMode(callback, type);
    }
}
