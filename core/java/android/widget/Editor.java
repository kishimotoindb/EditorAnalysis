/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.widget;

import android.R;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ClipData;
import android.content.ClipData.Item;
import android.content.Context;
import android.content.Intent;
import android.content.UndoManager;
import android.content.UndoOperation;
import android.content.UndoOwner;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelableParcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.method.KeyListener;
import android.text.method.MetaKeyKeyListener;
import android.text.method.MovementMethod;
import android.text.method.WordIterator;
import android.text.style.EasyEditSpan;
import android.text.style.SuggestionRangeSpan;
import android.text.style.SuggestionSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.URLSpan;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
import android.view.ContextMenu;
import android.view.ContextThemeWrapper;
import android.view.DisplayListCanvas;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.RenderNode;
import android.view.SubMenu;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.View.OnClickListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.textclassifier.TextClassification;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.TextView.Drawables;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.EditableInputConnection;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;


/**
 * Helper class used by TextView to handle editable(可编辑的概念，应该不但包括修改文字，也包括选中文字，
 * 所以可以选择文字进行复制的TextView，应该也会用到Editor) text views.
 *
 * @hide
 */
public class Editor {
    private static final String TAG = "Editor";
    private static final boolean DEBUG_UNDO = false;

    static final int BLINK = 500;
    private static final int DRAG_SHADOW_MAX_TEXT_LENGTH = 20;
    private static final float LINE_SLOP_MULTIPLIER_FOR_HANDLEVIEWS = 0.5f;
    private static final int UNSET_X_VALUE = -1;
    private static final int UNSET_LINE = -1;
    // Tag used when the Editor maintains its own separate UndoManager.
    private static final String UNDO_OWNER_TAG = "Editor";

    // Ordering constants used to place the Action Mode or context menu items in their menu.
    private static final int MENU_ITEM_ORDER_ASSIST = 0;
    private static final int MENU_ITEM_ORDER_UNDO = 2;
    private static final int MENU_ITEM_ORDER_REDO = 3;
    private static final int MENU_ITEM_ORDER_CUT = 4;
    private static final int MENU_ITEM_ORDER_COPY = 5;
    private static final int MENU_ITEM_ORDER_PASTE = 6;
    private static final int MENU_ITEM_ORDER_SHARE = 7;
    private static final int MENU_ITEM_ORDER_SELECT_ALL = 8;
    private static final int MENU_ITEM_ORDER_REPLACE = 9;
    private static final int MENU_ITEM_ORDER_AUTOFILL = 10;
    private static final int MENU_ITEM_ORDER_PASTE_AS_PLAIN_TEXT = 11;
    private static final int MENU_ITEM_ORDER_PROCESS_TEXT_INTENT_ACTIONS_START = 100;

    // Each Editor manages its own undo stack.
    private final UndoManager mUndoManager = new UndoManager();
    private UndoOwner mUndoOwner = mUndoManager.getOwner(UNDO_OWNER_TAG, this);
    final UndoInputFilter mUndoInputFilter = new UndoInputFilter(this);
    boolean mAllowUndo = true;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    // Cursor Controllers.
    private InsertionPointCursorController mInsertionPointCursorController;
    SelectionModifierCursorController mSelectionModifierCursorController;
    // Action mode used when text is selected or when actions on an insertion cursor are triggered.
    /*
     * 1.text is selected：文字被选中的时候，一定会有弹框
     * 2.actions on an insertion cursor are triggered：双击的时候，cursor上也会有一个弹框
     * 3.insertion cursor出现的时候，不一定有popupWindow弹出
     * 所以说只有在popUpWindow展示出来的时候，才会有ActionMode。并不是cursor出现了，ActionMode就不为空。
     *
     * 这两个变量都是在有光标以后才被赋值为true的，所以文本中如果还没有出现过光标，或者还没有开启过ActionMode
     * 的情况下，这两个值都是false。
     */
    private ActionMode mTextActionMode;
    private boolean mInsertionControllerEnabled;
    private boolean mSelectionControllerEnabled;

    // Used to highlight a word when it is corrected by the IME
    private CorrectionHighlighter mCorrectionHighlighter;

    InputContentType mInputContentType;
    InputMethodState mInputMethodState;

    private static class TextRenderNode {
        // Render node has 3 recording states:
        // 1. Recorded operations are valid.
        // #needsRecord() returns false, but needsToBeShifted is false.
        // 2. Recorded operations are not valid, but just the position needed to be updated.
        // #needsRecord() returns false, but needsToBeShifted is true.
        // 3. Recorded operations are not valid. Need to record operations. #needsRecord() returns
        // true.
        RenderNode renderNode;
        boolean isDirty;
        // Becomes true when recorded operations can be reused, but the position has to be updated.
        boolean needsToBeShifted;
        public TextRenderNode(String name) {
            renderNode = RenderNode.create(name, null);
            isDirty = true;
            needsToBeShifted = true;
        }
        boolean needsRecord() {
            return isDirty || !renderNode.isValid();
        }
    }
    private TextRenderNode[] mTextRenderNodes;

    boolean mFrozenWithFocus;
    boolean mSelectionMoved;
    boolean mTouchFocusSelected;

    KeyListener mKeyListener;
    int mInputType = EditorInfo.TYPE_NULL;

    /*
     * 在TextView的performLongClick()方法中，如果Editor处理了longClick事件，那么 mDiscardNextActionUp
     * 就会被置为true。所以说 mDiscardNextActionUp 用在Editor对longClick的处理。
     * 从语义上来说，是Editor自己处理了UP事件之后就主动丢弃up事件，不让TextView处理。
     */
    boolean mDiscardNextActionUp;
    /*
     * 目前只有TextView的cancelLongPress()方法中被设置为true。
     * （这段为错误的理解）从语义来说，是指Editor自己无视up事件，至于TextView处理不处理我不管。所以cancelLongPress方法中将该
     * 变量设置为true，意思就是没有处理长按事件，所以up事件对我来说没有用，textView你该怎么处理怎么处理。
     *
     *
     */
    boolean mIgnoreActionUpEvent;

    long mShowCursor;
    private Blink mBlink;

    boolean mCursorVisible = true;
    boolean mSelectAllOnFocus;
    boolean mTextIsSelectable;

    CharSequence mError;
    boolean mErrorWasChanged;
    private ErrorPopup mErrorPopup;

    /**
     * This flag is set if the TextView tries to display an error before it
     * is attached to the window (so its position is still unknown).
     * It causes the error to be shown later, when onAttachedToWindow()
     * is called.
     */
    private boolean mShowErrorAfterAttach;

    boolean mInBatchEditControllers;
    boolean mShowSoftInputOnFocus = true;
    private boolean mPreserveSelection;
    private boolean mRestartActionModeOnNextRefresh;

    private android.widget.SelectionActionModeHelper mSelectionActionModeHelper;

    boolean mIsBeingLongClicked;

    private SuggestionsPopupWindow mSuggestionsPopupWindow;
    SuggestionRangeSpan mSuggestionRangeSpan;
    private Runnable mShowSuggestionRunnable;

    final Drawable[] mCursorDrawable = new Drawable[2];
    int mCursorCount; // Current number of used mCursorDrawable: 0 (resource=0), 1 or 2 (split)

    private Drawable mSelectHandleLeft;
    private Drawable mSelectHandleRight;
    private Drawable mSelectHandleCenter;

    // Global listener that detects changes in the global position of the TextView
    private PositionListener mPositionListener;

    private float mLastDownPositionX, mLastDownPositionY;
    private float mContextMenuAnchorX, mContextMenuAnchorY;
    Callback mCustomSelectionActionModeCallback;
    Callback mCustomInsertionActionModeCallback;

    // Set when this TextView gained focus with some text selected. Will start selection mode.
    boolean mCreatedWithASelection;

    // Indicates the current tap state (first tap, double tap, or triple click).
    private int mTapState = TAP_STATE_INITIAL;
    private long mLastTouchUpTime = 0;
    private static final int TAP_STATE_INITIAL = 0;
    private static final int TAP_STATE_FIRST_TAP = 1;
    private static final int TAP_STATE_DOUBLE_TAP = 2;
    // Only for mouse input.
    private static final int TAP_STATE_TRIPLE_CLICK = 3;

    // The button state as of the last time #onTouchEvent is called.
    private int mLastButtonState;

    private Runnable mInsertionActionModeRunnable;

    // The span controller helps monitoring the changes to which the Editor needs to react:
    // - EasyEditSpans, for which we have some UI to display on attach and on hide
    // - SelectionSpans, for which we need to call updateSelection if an IME is attached
    private SpanController mSpanController;

    private WordIterator mWordIterator;
    android.widget.SpellChecker mSpellChecker;

    // This word iterator is set with text and used to determine word boundaries
    // when a user is selecting text.
    private WordIterator mWordIteratorWithText;
    // Indicate that the text in the word iterator needs to be updated.
    private boolean mUpdateWordIteratorText;

    private Rect mTempRect;

    private final TextView mTextView;

    final ProcessTextIntentActionsHandler mProcessTextIntentActionsHandler;

    private final CursorAnchorInfoNotifier mCursorAnchorInfoNotifier =
            new CursorAnchorInfoNotifier();

    private final Runnable mShowFloatingToolbar = new Runnable() {
        @Override
        public void run() {
            if (mTextActionMode != null) {
                mTextActionMode.hide(0);  // hide off.
            }
        }
    };

    /*
     * 1. InsertionActionMode是在Editor的performLongClick方法中设置为true，
     * 然后在TextView的onTouchEvent()方法的Up事件中开启InsertionActionMode。
     *
     * 2. SelectionActionMode是在Editor的performLongClick方法中将mRestartActionModeOnNextRefresh
     * 设置为true，然后在Editor的onTouchEvent()中的SelectionModifierCursorController的
     * onTouchEvent方法中开启SelectionActionMode。
     *
     * 两个模式均是通过View的startActionMode()方法开启ActionMode模式，并且开启ActionMode
     * 的回调也是使用同一个TextActionModeCallBack。
     *
     */
    boolean mIsInsertionActionModeStartPending = false;

    private final SuggestionHelper mSuggestionHelper = new SuggestionHelper();

    Editor(TextView textView) {
        mTextView = textView;
        // Synchronize the filter list, which places the undo input filter at the end.
        mTextView.setFilters(mTextView.getFilters());
        mProcessTextIntentActionsHandler = new ProcessTextIntentActionsHandler(this);
    }

    ParcelableParcel saveInstanceState() {
        ParcelableParcel state = new ParcelableParcel(getClass().getClassLoader());
        Parcel parcel = state.getParcel();
        mUndoManager.saveInstanceState(parcel);
        mUndoInputFilter.saveInstanceState(parcel);
        return state;
    }

    void restoreInstanceState(ParcelableParcel state) {
        Parcel parcel = state.getParcel();
        mUndoManager.restoreInstanceState(parcel, state.getClassLoader());
        mUndoInputFilter.restoreInstanceState(parcel);
        // Re-associate this object as the owner of undo state.
        mUndoOwner = mUndoManager.getOwner(UNDO_OWNER_TAG, this);
    }

    /**
     * Forgets all undo and redo operations for this Editor.
     */
    void forgetUndoRedo() {
        UndoOwner[] owners = { mUndoOwner };
        mUndoManager.forgetUndos(owners, -1 /* all */);
        mUndoManager.forgetRedos(owners, -1 /* all */);
    }

    boolean canUndo() {
        UndoOwner[] owners = { mUndoOwner };
        return mAllowUndo && mUndoManager.countUndos(owners) > 0;
    }

    boolean canRedo() {
        UndoOwner[] owners = { mUndoOwner };
        return mAllowUndo && mUndoManager.countRedos(owners) > 0;
    }

    void undo() {
        if (!mAllowUndo) {
            return;
        }
        UndoOwner[] owners = { mUndoOwner };
        mUndoManager.undo(owners, 1);  // Undo 1 action.
    }

    void redo() {
        if (!mAllowUndo) {
            return;
        }
        UndoOwner[] owners = { mUndoOwner };
        mUndoManager.redo(owners, 1);  // Redo 1 action.
    }

    void replace() {
        if (mSuggestionsPopupWindow == null) {
            mSuggestionsPopupWindow = new SuggestionsPopupWindow();
        }
        hideCursorAndSpanControllers();
        mSuggestionsPopupWindow.show();

        int middle = (mTextView.getSelectionStart() + mTextView.getSelectionEnd()) / 2;
        Selection.setSelection((Spannable) mTextView.getText(), middle);
    }

    void onAttachedToWindow() {
        if (mShowErrorAfterAttach) {
            showError();
            mShowErrorAfterAttach = false;
        }

        final ViewTreeObserver observer = mTextView.getViewTreeObserver();
        // No need to create the controller.
        // The get method will add the listener on controller creation.
        if (mInsertionPointCursorController != null) {
            observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
        }
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.resetTouchOffsets();
            observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
        }
        updateSpellCheckSpans(0, mTextView.getText().length(),
                true /* create the spell checker if needed */);

        if (mTextView.hasSelection()) {
            refreshTextActionMode();
        }

        getPositionListener().addSubscriber(mCursorAnchorInfoNotifier, true);
        resumeBlink();
    }

    void onDetachedFromWindow() {
        getPositionListener().removeSubscriber(mCursorAnchorInfoNotifier);

        if (mError != null) {
            hideError();
        }

        suspendBlink();

        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.onDetached();
        }

        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.onDetached();
        }

        if (mShowSuggestionRunnable != null) {
            mTextView.removeCallbacks(mShowSuggestionRunnable);
        }

        // Cancel the single tap delayed runnable.
        if (mInsertionActionModeRunnable != null) {
            mTextView.removeCallbacks(mInsertionActionModeRunnable);
        }

        mTextView.removeCallbacks(mShowFloatingToolbar);

        discardTextDisplayLists();

        if (mSpellChecker != null) {
            mSpellChecker.closeSession();
            // Forces the creation of a new SpellChecker next time this window is created.
            // Will handle the cases where the settings has been changed in the meantime.
            mSpellChecker = null;
        }

        hideCursorAndSpanControllers();
        stopTextActionModeWithPreservingSelection();
    }

    private void discardTextDisplayLists() {
        if (mTextRenderNodes != null) {
            for (int i = 0; i < mTextRenderNodes.length; i++) {
                RenderNode displayList = mTextRenderNodes[i] != null
                        ? mTextRenderNodes[i].renderNode : null;
                if (displayList != null && displayList.isValid()) {
                    displayList.discardDisplayList();
                }
            }
        }
    }

    private void showError() {
        if (mTextView.getWindowToken() == null) {
            mShowErrorAfterAttach = true;
            return;
        }

        if (mErrorPopup == null) {
            LayoutInflater inflater = LayoutInflater.from(mTextView.getContext());
            final TextView err = (TextView) inflater.inflate(
                    com.android.internal.R.layout.textview_hint, null);

            final float scale = mTextView.getResources().getDisplayMetrics().density;
            mErrorPopup =
                    new ErrorPopup(err, (int) (200 * scale + 0.5f), (int) (50 * scale + 0.5f));
            mErrorPopup.setFocusable(false);
            // The user is entering text, so the input method is needed.  We
            // don't want the popup to be displayed on top of it.
            mErrorPopup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NEEDED);
        }

        TextView tv = (TextView) mErrorPopup.getContentView();
        chooseSize(mErrorPopup, mError, tv);
        tv.setText(mError);

        mErrorPopup.showAsDropDown(mTextView, getErrorX(), getErrorY());
        mErrorPopup.fixDirection(mErrorPopup.isAboveAnchor());
    }

    public void setError(CharSequence error, Drawable icon) {
        mError = TextUtils.stringOrSpannedString(error);
        mErrorWasChanged = true;

        if (mError == null) {
            setErrorIcon(null);
            if (mErrorPopup != null) {
                if (mErrorPopup.isShowing()) {
                    mErrorPopup.dismiss();
                }

                mErrorPopup = null;
            }
            mShowErrorAfterAttach = false;
        } else {
            setErrorIcon(icon);
            if (mTextView.isFocused()) {
                showError();
            }
        }
    }

    private void setErrorIcon(Drawable icon) {
        Drawables dr = mTextView.mDrawables;
        if (dr == null) {
            mTextView.mDrawables = dr = new Drawables(mTextView.getContext());
        }
        dr.setErrorDrawable(icon, mTextView);

        mTextView.resetResolvedDrawables();
        mTextView.invalidate();
        mTextView.requestLayout();
    }

    private void hideError() {
        if (mErrorPopup != null) {
            if (mErrorPopup.isShowing()) {
                mErrorPopup.dismiss();
            }
        }

        mShowErrorAfterAttach = false;
    }

    /**
     * Returns the X offset to make the pointy top of the error point
     * at the middle of the error icon.
     */
    private int getErrorX() {
        /*
         * The "25" is the distance between the point and the right edge
         * of the background
         */
        final float scale = mTextView.getResources().getDisplayMetrics().density;

        final Drawables dr = mTextView.mDrawables;

        final int layoutDirection = mTextView.getLayoutDirection();
        int errorX;
        int offset;
        switch (layoutDirection) {
            default:
            case View.LAYOUT_DIRECTION_LTR:
                offset = -(dr != null ? dr.mDrawableSizeRight : 0) / 2 + (int) (25 * scale + 0.5f);
                errorX = mTextView.getWidth() - mErrorPopup.getWidth()
                        - mTextView.getPaddingRight() + offset;
                break;
            case View.LAYOUT_DIRECTION_RTL:
                offset = (dr != null ? dr.mDrawableSizeLeft : 0) / 2 - (int) (25 * scale + 0.5f);
                errorX = mTextView.getPaddingLeft() + offset;
                break;
        }
        return errorX;
    }

    /**
     * Returns the Y offset to make the pointy top of the error point
     * at the bottom of the error icon.
     */
    private int getErrorY() {
        /*
         * Compound, not extended, because the icon is not clipped
         * if the text height is smaller.
         */
        final int compoundPaddingTop = mTextView.getCompoundPaddingTop();
        int vspace = mTextView.getBottom() - mTextView.getTop()
                - mTextView.getCompoundPaddingBottom() - compoundPaddingTop;

        final Drawables dr = mTextView.mDrawables;

        final int layoutDirection = mTextView.getLayoutDirection();
        int height;
        switch (layoutDirection) {
            default:
            case View.LAYOUT_DIRECTION_LTR:
                height = (dr != null ? dr.mDrawableHeightRight : 0);
                break;
            case View.LAYOUT_DIRECTION_RTL:
                height = (dr != null ? dr.mDrawableHeightLeft : 0);
                break;
        }

        int icontop = compoundPaddingTop + (vspace - height) / 2;

        /*
         * The "2" is the distance between the point and the top edge
         * of the background.
         */
        final float scale = mTextView.getResources().getDisplayMetrics().density;
        return icontop + height - mTextView.getHeight() - (int) (2 * scale + 0.5f);
    }

    void createInputContentTypeIfNeeded() {
        if (mInputContentType == null) {
            mInputContentType = new InputContentType();
        }
    }

    void createInputMethodStateIfNeeded() {
        if (mInputMethodState == null) {
            mInputMethodState = new InputMethodState();
        }
    }

    boolean isCursorVisible() {
        // The default value is true, even when there is no associated Editor
        return mCursorVisible && mTextView.isTextEditable();
    }

    /*
     * 主要是设置 mInsertionControllerEnabled 和 mSelectionControllerEnabled，如果enable=false，
     * 关闭相应的cursor
     */
    void prepareCursorControllers() {
        boolean windowSupportsHandles = false;

        ViewGroup.LayoutParams params = mTextView.getRootView().getLayoutParams();
        if (params instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
            windowSupportsHandles = windowParams.type < WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    || windowParams.type > WindowManager.LayoutParams.LAST_SUB_WINDOW;
        }

        boolean enabled = windowSupportsHandles && mTextView.getLayout() != null;
        mInsertionControllerEnabled = enabled && isCursorVisible(); // text.isTextEditable()
        mSelectionControllerEnabled = enabled && mTextView.textCanBeSelected();

        if (!mInsertionControllerEnabled) {
            hideInsertionPointCursorController();
            if (mInsertionPointCursorController != null) {
                mInsertionPointCursorController.onDetached();
                mInsertionPointCursorController = null;
            }
        }

        if (!mSelectionControllerEnabled) {
            stopTextActionMode();
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.onDetached();
                mSelectionModifierCursorController = null;
            }
        }
    }

    void hideInsertionPointCursorController() {
        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.hide();
        }
    }

    /**
     * Hides the insertion and span controllers.
     */
    void hideCursorAndSpanControllers() {
        hideCursorControllers();
        hideSpanControllers();
    }

    private void hideSpanControllers() {
        if (mSpanController != null) {
            mSpanController.hide();
        }
    }

    private void hideCursorControllers() {
        // When mTextView is not ExtractEditText, we need to distinguish two kinds of focus-lost.
        // One is the true focus lost where suggestions pop-up (if any) should be dismissed, and the
        // other is an side effect of showing the suggestions pop-up itself. We use isShowingUp()
        // to distinguish one from the other.
        if (mSuggestionsPopupWindow != null && ((mTextView.isInExtractedMode()/*直接返回的false*/)
                || !mSuggestionsPopupWindow.isShowingUp())) {
            // Should be done before hide insertion point controller since it triggers a show of it
            mSuggestionsPopupWindow.hide();
        }
        hideInsertionPointCursorController();
    }

    /**
     * Create new SpellCheckSpans on the modified region.
     */
    private void updateSpellCheckSpans(int start, int end, boolean createSpellChecker) {
        // Remove spans whose adjacent characters are text not punctuation
        mTextView.removeAdjacentSuggestionSpans(start);
        mTextView.removeAdjacentSuggestionSpans(end);

        if (mTextView.isTextEditable() && mTextView.isSuggestionsEnabled()
                && !(mTextView.isInExtractedMode())) {
            if (mSpellChecker == null && createSpellChecker) {
                mSpellChecker = new android.widget.SpellChecker(mTextView);
            }
            if (mSpellChecker != null) {
                mSpellChecker.spellCheck(start, end);
            }
        }
    }

    void onScreenStateChanged(int screenState) {
        switch (screenState) {
            case View.SCREEN_STATE_ON:
                resumeBlink();
                break;
            case View.SCREEN_STATE_OFF:
                suspendBlink();
                break;
        }
    }

    private void suspendBlink() {
        if (mBlink != null) {
            mBlink.cancel();
        }
    }

    private void resumeBlink() {
        if (mBlink != null) {
            mBlink.uncancel();
            makeBlink();
        }
    }

    void adjustInputType(boolean password, boolean passwordInputType,
            boolean webPasswordInputType, boolean numberPasswordInputType) {
        // mInputType has been set from inputType, possibly modified by mInputMethod.
        // Specialize mInputType to [web]password if we have a text class and the original input
        // type was a password.
        if ((mInputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT) {
            if (password || passwordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
            }
            if (webPasswordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            }
        } else if ((mInputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER) {
            if (numberPasswordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD;
            }
        }
    }

    private void chooseSize(android.widget.PopupWindow pop, CharSequence text, TextView tv) {
        int wid = tv.getPaddingLeft() + tv.getPaddingRight();
        int ht = tv.getPaddingTop() + tv.getPaddingBottom();

        int defaultWidthInPixels = mTextView.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.textview_error_popup_default_width);
        Layout l = new StaticLayout(text, tv.getPaint(), defaultWidthInPixels,
                                    Layout.Alignment.ALIGN_NORMAL, 1, 0, true);
        float max = 0;
        for (int i = 0; i < l.getLineCount(); i++) {
            max = Math.max(max, l.getLineWidth(i));
        }

        /*
         * Now set the popup size to be big enough for the text plus the border capped
         * to DEFAULT_MAX_POPUP_WIDTH
         */
        pop.setWidth(wid + (int) Math.ceil(max));
        pop.setHeight(ht + l.getHeight());
    }

    void setFrame() {
        if (mErrorPopup != null) {
            TextView tv = (TextView) mErrorPopup.getContentView();
            chooseSize(mErrorPopup, mError, tv);
            mErrorPopup.update(mTextView, getErrorX(), getErrorY(),
                    mErrorPopup.getWidth(), mErrorPopup.getHeight());
        }
    }

    private int getWordStart(int offset) {
        // FIXME - For this and similar methods we're not doing anything to check if there's
        // a LocaleSpan in the text, this may be something we should try handling or checking for.
        int retOffset = getWordIteratorWithText().prevBoundary(offset);
        if (getWordIteratorWithText().isOnPunctuation(retOffset)) {
            // On punctuation boundary or within group of punctuation, find punctuation start.
            retOffset = getWordIteratorWithText().getPunctuationBeginning(offset);
        } else {
            // Not on a punctuation boundary, find the word start.
            retOffset = getWordIteratorWithText().getPrevWordBeginningOnTwoWordsBoundary(offset);
        }
        if (retOffset == BreakIterator.DONE) {
            return offset;
        }
        return retOffset;
    }

    private int getWordEnd(int offset) {
        int retOffset = getWordIteratorWithText().nextBoundary(offset);
        if (getWordIteratorWithText().isAfterPunctuation(retOffset)) {
            // On punctuation boundary or within group of punctuation, find punctuation end.
            retOffset = getWordIteratorWithText().getPunctuationEnd(offset);
        } else {
            // Not on a punctuation boundary, find the word end.
            retOffset = getWordIteratorWithText().getNextWordEndOnTwoWordBoundary(offset);
        }
        if (retOffset == BreakIterator.DONE) {
            return offset;
        }
        return retOffset;
    }

    private boolean needsToSelectAllToSelectWordOrParagraph() {
        // 只要与密码相关就全选
        if (mTextView.hasPasswordTransformationMethod()) {
            // Always select all on a password field.
            // Cut/copy menu entries are not available for passwords, but being able to select all
            // is however useful to delete or paste to replace the entire content.
            return true;
        }

        int inputType = mTextView.getInputType();
        int klass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;

        // Specific text field types: select the entire text for these
        if (klass == InputType.TYPE_CLASS_NUMBER
                || klass == InputType.TYPE_CLASS_PHONE
                || klass == InputType.TYPE_CLASS_DATETIME
                || variation == InputType.TYPE_TEXT_VARIATION_URI
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
            return true;
        }
        return false;
    }

    /**
     * Adjusts selection to the word under last touch offset. Return true if the operation was
     * successfully performed.
     */
    /*
     * 1.全选
     * 2.选中第一个URL
     * 3.选中一个单词
     */
    boolean selectCurrentWord() {
        if (!mTextView.canSelectText()) {
            return false;
        }

        if (needsToSelectAllToSelectWordOrParagraph()) {
            return mTextView.selectAllText();
        }

        long lastTouchOffsets = getLastTouchOffsets();
        final int minOffset = TextUtils.unpackRangeStartFromLong(lastTouchOffsets);
        final int maxOffset = TextUtils.unpackRangeEndFromLong(lastTouchOffsets);

        // Safety check in case standard touch event handling has been bypassed
        if (minOffset < 0 || minOffset > mTextView.getText().length()) return false;
        if (maxOffset < 0 || maxOffset > mTextView.getText().length()) return false;

        int selectionStart, selectionEnd;

        // If a URLSpan (web address, email, phone...) is found at that position, select it.
        URLSpan[] urlSpans =
                ((Spanned) mTextView.getText()).getSpans(minOffset, maxOffset, URLSpan.class);
        if (urlSpans.length >= 1) {
            URLSpan urlSpan = urlSpans[0];
            selectionStart = ((Spanned) mTextView.getText()).getSpanStart(urlSpan);
            selectionEnd = ((Spanned) mTextView.getText()).getSpanEnd(urlSpan);
        } else {
            // FIXME - We should check if there's a LocaleSpan in the text, this may be
            // something we should try handling or checking for.
            final WordIterator wordIterator = getWordIterator();
            wordIterator.setCharSequence(mTextView.getText(), minOffset, maxOffset);

            selectionStart = wordIterator.getBeginning(minOffset);
            selectionEnd = wordIterator.getEnd(maxOffset);

            if (selectionStart == BreakIterator.DONE || selectionEnd == BreakIterator.DONE
                    || selectionStart == selectionEnd) {
                // Possible when the word iterator does not properly handle the text's language
                long range = getCharClusterRange(minOffset);
                selectionStart = TextUtils.unpackRangeStartFromLong(range);
                selectionEnd = TextUtils.unpackRangeEndFromLong(range);
            }
        }

        Selection.setSelection((Spannable) mTextView.getText(), selectionStart, selectionEnd);
        return selectionEnd > selectionStart;
    }

    /**
     * Adjusts selection to the paragraph under last touch offset. Return true if the operation was
     * successfully performed.
     */
    private boolean selectCurrentParagraph() {
        if (!mTextView.canSelectText()) {
            return false;
        }

        if (needsToSelectAllToSelectWordOrParagraph()) {
            return mTextView.selectAllText();
        }

        long lastTouchOffsets = getLastTouchOffsets();
        final int minLastTouchOffset = TextUtils.unpackRangeStartFromLong(lastTouchOffsets);
        final int maxLastTouchOffset = TextUtils.unpackRangeEndFromLong(lastTouchOffsets);

        final long paragraphsRange = getParagraphsRange(minLastTouchOffset, maxLastTouchOffset);
        final int start = TextUtils.unpackRangeStartFromLong(paragraphsRange);
        final int end = TextUtils.unpackRangeEndFromLong(paragraphsRange);
        if (start < end) {
            Selection.setSelection((Spannable) mTextView.getText(), start, end);
            return true;
        }
        return false;
    }

    /**
     * Get the minimum range of paragraphs that contains startOffset and endOffset.
     */
    private long getParagraphsRange(int startOffset, int endOffset) {
        final Layout layout = mTextView.getLayout();
        if (layout == null) {
            return TextUtils.packRangeInLong(-1, -1);
        }
        final CharSequence text = mTextView.getText();
        int minLine = layout.getLineForOffset(startOffset);
        // Search paragraph start.
        while (minLine > 0) {
            final int prevLineEndOffset = layout.getLineEnd(minLine - 1);
            if (text.charAt(prevLineEndOffset - 1) == '\n') {
                break;
            }
            minLine--;
        }
        int maxLine = layout.getLineForOffset(endOffset);
        // Search paragraph end.
        while (maxLine < layout.getLineCount() - 1) {
            final int lineEndOffset = layout.getLineEnd(maxLine);
            if (text.charAt(lineEndOffset - 1) == '\n') {
                break;
            }
            maxLine++;
        }
        return TextUtils.packRangeInLong(layout.getLineStart(minLine), layout.getLineEnd(maxLine));
    }

    void onLocaleChanged() {
        // Will be re-created on demand in getWordIterator and getWordIteratorWithText with the
        // proper new locale
        mWordIterator = null;
        mWordIteratorWithText = null;
    }

    public WordIterator getWordIterator() {
        if (mWordIterator == null) {
            mWordIterator = new WordIterator(mTextView.getTextServicesLocale());
        }
        return mWordIterator;
    }

    private WordIterator getWordIteratorWithText() {
        if (mWordIteratorWithText == null) {
            mWordIteratorWithText = new WordIterator(mTextView.getTextServicesLocale());
            mUpdateWordIteratorText = true;
        }
        if (mUpdateWordIteratorText) {
            // FIXME - Shouldn't copy all of the text as only the area of the text relevant
            // to the user's selection is needed. A possible solution would be to
            // copy some number N of characters near the selection and then when the
            // user approaches N then we'd do another copy of the next N characters.
            CharSequence text = mTextView.getText();
            mWordIteratorWithText.setCharSequence(text, 0, text.length());
            mUpdateWordIteratorText = false;
        }
        return mWordIteratorWithText;
    }

    private int getNextCursorOffset(int offset, boolean findAfterGivenOffset) {
        final Layout layout = mTextView.getLayout();
        if (layout == null) return offset;
        return findAfterGivenOffset == layout.isRtlCharAt(offset)
                ? layout.getOffsetToLeftOf(offset) : layout.getOffsetToRightOf(offset);
    }

    private long getCharClusterRange(int offset) {
        final int textLength = mTextView.getText().length();
        if (offset < textLength) {
            final int clusterEndOffset = getNextCursorOffset(offset, true);
            return TextUtils.packRangeInLong(
                    getNextCursorOffset(clusterEndOffset, false), clusterEndOffset);
        }
        if (offset - 1 >= 0) {
            final int clusterStartOffset = getNextCursorOffset(offset, false);
            return TextUtils.packRangeInLong(clusterStartOffset,
                    getNextCursorOffset(clusterStartOffset, true));
        }
        return TextUtils.packRangeInLong(offset, offset);
    }

    private boolean touchPositionIsInSelection() {
        int selectionStart = mTextView.getSelectionStart();
        int selectionEnd = mTextView.getSelectionEnd();

        if (selectionStart == selectionEnd) {
            return false;
        }

        if (selectionStart > selectionEnd) {
            int tmp = selectionStart;
            selectionStart = selectionEnd;
            selectionEnd = tmp;
            Selection.setSelection((Spannable) mTextView.getText(), selectionStart, selectionEnd);
        }

        SelectionModifierCursorController selectionController = getSelectionController();
        int minOffset = selectionController.getMinTouchOffset();
        int maxOffset = selectionController.getMaxTouchOffset();

        return ((minOffset >= selectionStart) && (maxOffset < selectionEnd));
    }

    private PositionListener getPositionListener() {
        if (mPositionListener == null) {
            mPositionListener = new PositionListener();
        }
        return mPositionListener;
    }

    private interface TextViewPositionListener {
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled);
    }

    private boolean isOffsetVisible(int offset) {
        Layout layout = mTextView.getLayout();
        if (layout == null) return false;

        final int line = layout.getLineForOffset(offset);
        final int lineBottom = layout.getLineBottom(line);
        final int primaryHorizontal = (int) layout.getPrimaryHorizontal(offset);
        return mTextView.isPositionVisible(
                primaryHorizontal + mTextView.viewportToContentHorizontalOffset(),
                lineBottom + mTextView.viewportToContentVerticalOffset());
    }

    /** Returns true if the screen coordinates position (x,y) corresponds to a character displayed
     * in the view. Returns false when the position is in the empty space of left/right of text.
     */
    private boolean isPositionOnText(float x, float y) {
        Layout layout = mTextView.getLayout();
        if (layout == null) return false;

        final int line = mTextView.getLineAtCoordinate(y);
        x = mTextView.convertToLocalHorizontalCoordinate(x);

        if (x < layout.getLineLeft(line)) return false;
        if (x > layout.getLineRight(line)) return false;
        return true;
    }

    private void startDragAndDrop() {
        // TODO: Fix drag and drop in full screen extracted mode.
        if (mTextView.isInExtractedMode()) {
            return;
        }
        final int start = mTextView.getSelectionStart();
        final int end = mTextView.getSelectionEnd();
        CharSequence selectedText = mTextView.getTransformedText(start, end);
        ClipData data = ClipData.newPlainText(null, selectedText);
        DragLocalState localState = new DragLocalState(mTextView, start, end);
        mTextView.startDragAndDrop(data, getTextThumbnailBuilder(start, end), localState,
                View.DRAG_FLAG_GLOBAL);
        stopTextActionMode();
        if (hasSelectionController()) {
            getSelectionController().resetTouchOffsets();
        }
    }

    public boolean performLongClick(boolean handled) {
        // Long press in empty space moves cursor and starts the insertion action mode.
        /*
         * 1.在文本中没有光标、没有开启任何ActionMode的时候，mInsertionControllerEnabled和
         *
         */
        if (!handled && !isPositionOnText(mLastDownPositionX, mLastDownPositionY)
                && mInsertionControllerEnabled) {
            final int offset = mTextView.getOffsetForPosition(mLastDownPositionX,
                    mLastDownPositionY);
            /*
             * 开启InsertionActionMode的步骤：
             * 1.设置selection
             * 2.显示handleView
             * 3.开启insertionActionMode：1)全局进入FloatingActionMode模式 2)显示FloatingToolbarPopupWindow
             */
            Selection.setSelection((Spannable) mTextView.getText(), offset);
            // handleView都是在
            getInsertionController().show();
            // 在TextView.onTouchEvent()的up事件中真正开启ActionMode
            mIsInsertionActionModeStartPending = true;
            handled = true;
            MetricsLogger.action(
                    mTextView.getContext(),
                    MetricsEvent.TEXT_LONGPRESS,
                    android.widget.TextViewMetrics.SUBTYPE_LONG_PRESS_OTHER);
        }

        if (!handled && mTextActionMode != null) {
            if (touchPositionIsInSelection()) {
                startDragAndDrop();
                MetricsLogger.action(
                        mTextView.getContext(),
                        MetricsEvent.TEXT_LONGPRESS,
                        android.widget.TextViewMetrics.SUBTYPE_LONG_PRESS_DRAG_AND_DROP);
            } else {
                stopTextActionMode();
                selectCurrentWordAndStartDrag();
                MetricsLogger.action(
                        mTextView.getContext(),
                        MetricsEvent.TEXT_LONGPRESS,
                        android.widget.TextViewMetrics.SUBTYPE_LONG_PRESS_SELECTION);
            }
            handled = true;
        }

        // Start a new selection
        if (!handled) {
            handled = selectCurrentWordAndStartDrag();
            if (handled) {
                MetricsLogger.action(
                        mTextView.getContext(),
                        MetricsEvent.TEXT_LONGPRESS,
                        android.widget.TextViewMetrics.SUBTYPE_LONG_PRESS_SELECTION);
            }
        }

        return handled;
    }

    private long getLastTouchOffsets() {
        SelectionModifierCursorController selectionController = getSelectionController();
        final int minOffset = selectionController.getMinTouchOffset();
        final int maxOffset = selectionController.getMaxTouchOffset();
        return TextUtils.packRangeInLong(minOffset, maxOffset);
    }

    void onFocusChanged(boolean focused, int direction) {
        mShowCursor = SystemClock.uptimeMillis();
        ensureEndedBatchEdit();

        if (focused) {
            int selStart = mTextView.getSelectionStart();
            int selEnd = mTextView.getSelectionEnd();

            // SelectAllOnFocus fields are highlighted and not selected. Do not start text selection
            // mode for these, unless there was a specific selection already started.
            final boolean isFocusHighlighted = mSelectAllOnFocus && selStart == 0
                    && selEnd == mTextView.getText().length();

            mCreatedWithASelection = mFrozenWithFocus && mTextView.hasSelection()
                    && !isFocusHighlighted;

            if (!mFrozenWithFocus || (selStart < 0 || selEnd < 0)) {
                // If a tap was used to give focus to that view, move cursor at tap position.
                // Has to be done before onTakeFocus, which can be overloaded.
                final int lastTapPosition = getLastTapPosition();
                if (lastTapPosition >= 0) {
                    Selection.setSelection((Spannable) mTextView.getText(), lastTapPosition);
                }

                // Note this may have to be moved out of the Editor class
                MovementMethod mMovement = mTextView.getMovementMethod();
                if (mMovement != null) {
                    mMovement.onTakeFocus(mTextView, (Spannable) mTextView.getText(), direction);
                }

                // The DecorView does not have focus when the 'Done' ExtractEditText button is
                // pressed. Since it is the ViewAncestor's mView, it requests focus before
                // ExtractEditText clears focus, which gives focus to the ExtractEditText.
                // This special case ensure that we keep current selection in that case.
                // It would be better to know why the DecorView does not have focus at that time.
                if (((mTextView.isInExtractedMode()) || mSelectionMoved)
                        && selStart >= 0 && selEnd >= 0) {
                    /*
                     * Someone intentionally set the selection, so let them
                     * do whatever it is that they wanted to do instead of
                     * the default on-focus behavior.  We reset the selection
                     * here instead of just skipping the onTakeFocus() call
                     * because some movement methods do something other than
                     * just setting the selection in theirs and we still
                     * need to go through that path.
                     */
                    Selection.setSelection((Spannable) mTextView.getText(), selStart, selEnd);
                }

                if (mSelectAllOnFocus) {
                    mTextView.selectAllText();
                }

                mTouchFocusSelected = true;
            }

            mFrozenWithFocus = false;
            mSelectionMoved = false;

            if (mError != null) {
                showError();
            }

            makeBlink();
        } else {
            if (mError != null) {
                hideError();
            }
            // Don't leave us in the middle of a batch edit.
            mTextView.onEndBatchEdit();

            if (mTextView.isInExtractedMode()) {
                hideCursorAndSpanControllers();
                stopTextActionModeWithPreservingSelection();
            } else {
                hideCursorAndSpanControllers();
                if (mTextView.isTemporarilyDetached()) {
                    stopTextActionModeWithPreservingSelection();
                } else {
                    stopTextActionMode();
                }
                downgradeEasyCorrectionSpans();
            }
            // No need to create the controller
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.resetTouchOffsets();
            }
        }
    }

    /**
     * Downgrades to simple suggestions all the easy correction spans that are not a spell check
     * span.
     */
    private void downgradeEasyCorrectionSpans() {
        CharSequence text = mTextView.getText();
        if (text instanceof Spannable) {
            Spannable spannable = (Spannable) text;
            SuggestionSpan[] suggestionSpans = spannable.getSpans(0,
                    spannable.length(), SuggestionSpan.class);
            for (int i = 0; i < suggestionSpans.length; i++) {
                int flags = suggestionSpans[i].getFlags();
                if ((flags & SuggestionSpan.FLAG_EASY_CORRECT) != 0
                        && (flags & SuggestionSpan.FLAG_MISSPELLED) == 0) {
                    flags &= ~SuggestionSpan.FLAG_EASY_CORRECT;
                    suggestionSpans[i].setFlags(flags);
                }
            }
        }
    }

    void sendOnTextChanged(int start, int after) {
        updateSpellCheckSpans(start, start + after, false);

        // Flip flag to indicate the word iterator needs to have the text reset.
        mUpdateWordIteratorText = true;

        // Hide the controllers as soon as text is modified (typing, procedural...)
        // We do not hide the span controllers, since they can be added when a new text is
        // inserted into the text view (voice IME).
        hideCursorControllers();
        // Reset drag accelerator.
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.resetTouchOffsets();
        }
        stopTextActionMode();
    }

    private int getLastTapPosition() {
        // No need to create the controller at that point, no last tap position saved
        if (mSelectionModifierCursorController != null) {
            int lastTapPosition = mSelectionModifierCursorController.getMinTouchOffset();
            if (lastTapPosition >= 0) {
                // Safety check, should not be possible.
                if (lastTapPosition > mTextView.getText().length()) {
                    lastTapPosition = mTextView.getText().length();
                }
                return lastTapPosition;
            }
        }

        return -1;
    }

    void onWindowFocusChanged(boolean hasWindowFocus) {
        if (hasWindowFocus) {
            if (mBlink != null) {
                mBlink.uncancel();
                makeBlink();
            }
            if (mTextView.hasSelection() && !extractedTextModeWillBeStarted()) {
                refreshTextActionMode();
            }
        } else {
            if (mBlink != null) {
                mBlink.cancel();
            }
            if (mInputContentType != null) {
                mInputContentType.enterDown = false;
            }
            // Order matters! Must be done before onParentLostFocus to rely on isShowingUp
            hideCursorAndSpanControllers();
            stopTextActionModeWithPreservingSelection();
            if (mSuggestionsPopupWindow != null) {
                mSuggestionsPopupWindow.onParentLostFocus();
            }

            // Don't leave us in the middle of a batch edit. Same as in onFocusChanged
            ensureEndedBatchEdit();
        }
    }

    // View本身只有click(tap)和longClick，并没有double tap。
    // 这里的tap只判断两次tap的时间间隔是否满足要求，
    // 这个tapState是Editor自己的
    private void updateTapState(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            final boolean isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE);
            // Detect double tap and triple click.
            // 按照这里的说法，应该只有鼠标才有triple click
            if (((mTapState == TAP_STATE_FIRST_TAP)
                    || ((mTapState == TAP_STATE_DOUBLE_TAP) && isMouse))
                            && (SystemClock.uptimeMillis() - mLastTouchUpTime)
                                    <= ViewConfiguration.getDoubleTapTimeout()) {
                if (mTapState == TAP_STATE_FIRST_TAP) {
                    mTapState = TAP_STATE_DOUBLE_TAP;
                } else {
                    mTapState = TAP_STATE_TRIPLE_CLICK;
                }
            } else {
                mTapState = TAP_STATE_FIRST_TAP;
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            mLastTouchUpTime = SystemClock.uptimeMillis();
        }
    }

    private boolean shouldFilterOutTouchEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false;
        }
        final boolean primaryButtonStateChanged =
                ((mLastButtonState ^ event.getButtonState()) & MotionEvent.BUTTON_PRIMARY) != 0;
        final int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)
                && !primaryButtonStateChanged) {
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE
                && !event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) {
            return true;
        }
        return false;
    }

    /*
     * 这个方法是在TextView中与TextView父类View的onTouchEvent方法并行执行的
     */
    void onTouchEvent(MotionEvent event) {
        // 这里可以直接当成false，因为只有eventSource是mouse的情况下
        final boolean filterOutEvent = shouldFilterOutTouchEvent(event);
        // ButtonState是针对鼠标的，所以触摸的情况下就不需要考虑这个字段
        mLastButtonState = event.getButtonState();
        // 鉴于上面的情况，这里不用考虑
        if (filterOutEvent) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mDiscardNextActionUp = true;
            }
            return;
        }
        // 更新Editor自己的tap状态，不做具体操作，只是更新、记录tapState这个变量的状态
        // tap分为两种 doubleTap,TripleTap,后者只针对鼠标才有。
        updateTapState(event);
        // 这个方法是在mTextActionMode!=null，即弹框已经弹出的情况下，更新弹框的状态用的，不是用来开启弹框的
        // 移动手指的时候，如果popupWindow已经显示，那么隐藏popupWindow
        // 抬起手指或者cancel事件的时候，展示popupWindow
        // floatingToolbar指的就是SuggestionPopupWindow和ActionPopupWindow
        // EditText的弹框，其实本质都是Editor触发和操作的，TextView其实啥也没做，只是展示文字
        updateFloatingToolbarVisibility(event);

        /*
         * TextActionMode!=null,说明现在text view，要么是有个文字被选中，弹出了复制弹框；
         * 要么是insertionCursor可见，doubleTap的弹框弹出。其它时候，TextActionMode都是空的。
         *
         * 问题：
         * 那么controller是一直不为空，还是和ActionMode是一样的？
         *
         * -------------------------------------------------------------------------------
         * Editor对弹框的控制，感觉主要依靠SelectionController和insertionController。(应该是不对的)
         *
         * -------------------------------------------------------------------------------
         *
         * 问题：
         * 这两个controller各自负责的哪个光标？
         * Selection负责的是选择文字两端的一对光标？
         * insertion负责的是在文字中间一直闪烁的光标，还是展示一会儿就会消失的底部带实心圆的光标？
         *
         *
         *
         * -------------------------------------------------------------------------------
         * onTouchEvent方法中，只涉及到了SelectionController。在performLongClick中，涉及到了
         * insertionController。
         *
         *
         *
         */
        if (hasSelectionController()) {
            getSelectionController().onTouchEvent(event);
        }

        // 只要有新的事件到来，就不显示SuggestionPopupWindow
        if (mShowSuggestionRunnable != null) {
            mTextView.removeCallbacks(mShowSuggestionRunnable);
            mShowSuggestionRunnable = null;
        }

        // Down事件时的处理
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // 拿到这个坐标的目的，是为了定位cursor
            mLastDownPositionX = event.getX();
            mLastDownPositionY = event.getY();

            // Reset this state; it will be re-set if super.onTouchEvent
            // causes focus to move to the view.
            mTouchFocusSelected = false;
            mIgnoreActionUpEvent = false;
        }
    }

    // 移动手指的时候，隐藏popupWindow
    // 抬起手指或者cancel事件的时候，展示popupWindow
    private void updateFloatingToolbarVisibility(MotionEvent event) {
        // TextActionMode!=null,说明现在text view，要么是有个文字被选中，弹出了复制弹框；
        // 要么是insertionCursor可见，doubleTap的弹框弹出。
        // 所以这里处理的是有popupWindow的情况下，隐藏和恢复显示的问题。
        if (mTextActionMode != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    hideFloatingToolbar();
                    break;
                case MotionEvent.ACTION_UP:  // fall through
                case MotionEvent.ACTION_CANCEL:
                    showFloatingToolbar();
            }
        }
    }

    private void hideFloatingToolbar() {
        if (mTextActionMode != null) {
            mTextView.removeCallbacks(mShowFloatingToolbar);
            mTextActionMode.hide(ActionMode.DEFAULT_HIDE_DURATION);
        }
    }

    private void showFloatingToolbar() {
        if (mTextActionMode != null) {
            // Delay "show" so it doesn't interfere with click confirmations
            // or double-clicks that could "dismiss" the floating toolbar.
            int delay = ViewConfiguration.getDoubleTapTimeout();
            mTextView.postDelayed(mShowFloatingToolbar, delay);

            // This classifies the text and most likely returns before the toolbar is actually
            // shown. If not, it will update the toolbar with the result when classification
            // returns. We would rather not wait for a long running classification process.
            invalidateActionModeAsync();
        }
    }

    public void beginBatchEdit() {
        mInBatchEditControllers = true;
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            int nesting = ++ims.mBatchEditNesting;
            if (nesting == 1) {
                ims.mCursorChanged = false;
                ims.mChangedDelta = 0;
                if (ims.mContentChanged) {
                    // We already have a pending change from somewhere else,
                    // so turn this into a full update.
                    ims.mChangedStart = 0;
                    ims.mChangedEnd = mTextView.getText().length();
                } else {
                    ims.mChangedStart = EXTRACT_UNKNOWN;
                    ims.mChangedEnd = EXTRACT_UNKNOWN;
                    ims.mContentChanged = false;
                }
                mUndoInputFilter.beginBatchEdit();
                mTextView.onBeginBatchEdit();
            }
        }
    }

    public void endBatchEdit() {
        mInBatchEditControllers = false;
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            int nesting = --ims.mBatchEditNesting;
            if (nesting == 0) {
                finishBatchEdit(ims);
            }
        }
    }

    void ensureEndedBatchEdit() {
        final InputMethodState ims = mInputMethodState;
        if (ims != null && ims.mBatchEditNesting != 0) {
            ims.mBatchEditNesting = 0;
            finishBatchEdit(ims);
        }
    }

    void finishBatchEdit(final InputMethodState ims) {
        mTextView.onEndBatchEdit();
        mUndoInputFilter.endBatchEdit();

        if (ims.mContentChanged || ims.mSelectionModeChanged) {
            mTextView.updateAfterEdit();
            reportExtractedText();
        } else if (ims.mCursorChanged) {
            // Cheesy way to get us to report the current cursor location.
            mTextView.invalidateCursor();
        }
        // sendUpdateSelection knows to avoid sending if the selection did
        // not actually change.
        sendUpdateSelection();

        // Show drag handles if they were blocked by batch edit mode.
        if (mTextActionMode != null) {
            final CursorController cursorController = mTextView.hasSelection()
                    ? getSelectionController() : getInsertionController();
            if (cursorController != null && !cursorController.isActive()
                    && !cursorController.isCursorBeingModified()) {
                cursorController.show();
            }
        }
    }

    static final int EXTRACT_NOTHING = -2;
    static final int EXTRACT_UNKNOWN = -1;

    boolean extractText(ExtractedTextRequest request, ExtractedText outText) {
        return extractTextInternal(request, EXTRACT_UNKNOWN, EXTRACT_UNKNOWN,
                EXTRACT_UNKNOWN, outText);
    }

    private boolean extractTextInternal(@Nullable ExtractedTextRequest request,
            int partialStartOffset, int partialEndOffset, int delta,
            @Nullable ExtractedText outText) {
        if (request == null || outText == null) {
            return false;
        }

        final CharSequence content = mTextView.getText();
        if (content == null) {
            return false;
        }

        if (partialStartOffset != EXTRACT_NOTHING) {
            final int N = content.length();
            if (partialStartOffset < 0) {
                outText.partialStartOffset = outText.partialEndOffset = -1;
                partialStartOffset = 0;
                partialEndOffset = N;
            } else {
                // Now use the delta to determine the actual amount of text
                // we need.
                partialEndOffset += delta;
                // Adjust offsets to ensure we contain full spans.
                if (content instanceof Spanned) {
                    Spanned spanned = (Spanned) content;
                    Object[] spans = spanned.getSpans(partialStartOffset,
                            partialEndOffset, ParcelableSpan.class);
                    int i = spans.length;
                    while (i > 0) {
                        i--;
                        int j = spanned.getSpanStart(spans[i]);
                        if (j < partialStartOffset) partialStartOffset = j;
                        j = spanned.getSpanEnd(spans[i]);
                        if (j > partialEndOffset) partialEndOffset = j;
                    }
                }
                outText.partialStartOffset = partialStartOffset;
                outText.partialEndOffset = partialEndOffset - delta;

                if (partialStartOffset > N) {
                    partialStartOffset = N;
                } else if (partialStartOffset < 0) {
                    partialStartOffset = 0;
                }
                if (partialEndOffset > N) {
                    partialEndOffset = N;
                } else if (partialEndOffset < 0) {
                    partialEndOffset = 0;
                }
            }
            if ((request.flags & InputConnection.GET_TEXT_WITH_STYLES) != 0) {
                outText.text = content.subSequence(partialStartOffset,
                        partialEndOffset);
            } else {
                outText.text = TextUtils.substring(content, partialStartOffset,
                        partialEndOffset);
            }
        } else {
            outText.partialStartOffset = 0;
            outText.partialEndOffset = 0;
            outText.text = "";
        }
        outText.flags = 0;
        if (MetaKeyKeyListener.getMetaState(content, MetaKeyKeyListener.META_SELECTING) != 0) {
            outText.flags |= ExtractedText.FLAG_SELECTING;
        }
        if (mTextView.isSingleLine()) {
            outText.flags |= ExtractedText.FLAG_SINGLE_LINE;
        }
        outText.startOffset = 0;
        outText.selectionStart = mTextView.getSelectionStart();
        outText.selectionEnd = mTextView.getSelectionEnd();
        return true;
    }

    boolean reportExtractedText() {
        final Editor.InputMethodState ims = mInputMethodState;
        if (ims != null) {
            final boolean contentChanged = ims.mContentChanged;
            if (contentChanged || ims.mSelectionModeChanged) {
                ims.mContentChanged = false;
                ims.mSelectionModeChanged = false;
                final ExtractedTextRequest req = ims.mExtractedTextRequest;
                if (req != null) {
                    InputMethodManager imm = InputMethodManager.peekInstance();
                    if (imm != null) {
                        if (TextView.DEBUG_EXTRACT) {
                            Log.v(TextView.LOG_TAG, "Retrieving extracted start="
                                    + ims.mChangedStart
                                    + " end=" + ims.mChangedEnd
                                    + " delta=" + ims.mChangedDelta);
                        }
                        if (ims.mChangedStart < 0 && !contentChanged) {
                            ims.mChangedStart = EXTRACT_NOTHING;
                        }
                        if (extractTextInternal(req, ims.mChangedStart, ims.mChangedEnd,
                                ims.mChangedDelta, ims.mExtractedText)) {
                            if (TextView.DEBUG_EXTRACT) {
                                Log.v(TextView.LOG_TAG,
                                        "Reporting extracted start="
                                                + ims.mExtractedText.partialStartOffset
                                                + " end=" + ims.mExtractedText.partialEndOffset
                                                + ": " + ims.mExtractedText.text);
                            }

                            imm.updateExtractedText(mTextView, req.token, ims.mExtractedText);
                            ims.mChangedStart = EXTRACT_UNKNOWN;
                            ims.mChangedEnd = EXTRACT_UNKNOWN;
                            ims.mChangedDelta = 0;
                            ims.mContentChanged = false;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // selectiona挨着键盘啥事儿了？
    private void sendUpdateSelection() {
        if (null != mInputMethodState && mInputMethodState.mBatchEditNesting <= 0) {
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (null != imm) {
                final int selectionStart = mTextView.getSelectionStart();
                final int selectionEnd = mTextView.getSelectionEnd();
                int candStart = -1;
                int candEnd = -1;
                if (mTextView.getText() instanceof Spannable) {
                    final Spannable sp = (Spannable) mTextView.getText();
                    candStart = EditableInputConnection.getComposingSpanStart(sp);
                    candEnd = EditableInputConnection.getComposingSpanEnd(sp);
                }
                // InputMethodManager#updateSelection skips sending the message if
                // none of the parameters have changed since the last time we called it.
                imm.updateSelection(mTextView,
                        selectionStart, selectionEnd, candStart, candEnd);
            }
        }
    }

    void onDraw(Canvas canvas, Layout layout, Path highlight, Paint highlightPaint,
            int cursorOffsetVertical) {
        final int selectionStart = mTextView.getSelectionStart();
        final int selectionEnd = mTextView.getSelectionEnd();

        final InputMethodState ims = mInputMethodState;
        if (ims != null && ims.mBatchEditNesting == 0) {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                if (imm.isActive(mTextView)) {
                    if (ims.mContentChanged || ims.mSelectionModeChanged) {
                        // We are in extract mode and the content has changed
                        // in some way... just report complete new text to the
                        // input method.
                        reportExtractedText();
                    }
                }
            }
        }

        if (mCorrectionHighlighter != null) {
            mCorrectionHighlighter.draw(canvas, cursorOffsetVertical);
        }

        if (highlight != null && selectionStart == selectionEnd && mCursorCount > 0) {
            drawCursor(canvas, cursorOffsetVertical);
            // Rely on the drawable entirely, do not draw the cursor line.
            // Has to be done after the IMM related code above which relies on the highlight.
            highlight = null;
        }

        if (mTextView.canHaveDisplayList() && canvas.isHardwareAccelerated()) {
            drawHardwareAccelerated(canvas, layout, highlight, highlightPaint,
                    cursorOffsetVertical);
        } else {
            layout.draw(canvas, highlight, highlightPaint, cursorOffsetVertical);
        }
    }

    private void drawHardwareAccelerated(Canvas canvas, Layout layout, Path highlight,
            Paint highlightPaint, int cursorOffsetVertical) {
        final long lineRange = layout.getLineRangeForDraw(canvas);
        int firstLine = TextUtils.unpackRangeStartFromLong(lineRange);
        int lastLine = TextUtils.unpackRangeEndFromLong(lineRange);
        if (lastLine < 0) return;

        layout.drawBackground(canvas, highlight, highlightPaint, cursorOffsetVertical,
                firstLine, lastLine);

        if (layout instanceof DynamicLayout) {
            if (mTextRenderNodes == null) {
                mTextRenderNodes = ArrayUtils.emptyArray(TextRenderNode.class);
            }

            DynamicLayout dynamicLayout = (DynamicLayout) layout;
            int[] blockEndLines = dynamicLayout.getBlockEndLines();
            int[] blockIndices = dynamicLayout.getBlockIndices();
            final int numberOfBlocks = dynamicLayout.getNumberOfBlocks();
            final int indexFirstChangedBlock = dynamicLayout.getIndexFirstChangedBlock();

            final ArraySet<Integer> blockSet = dynamicLayout.getBlocksAlwaysNeedToBeRedrawn();
            if (blockSet != null) {
                for (int i = 0; i < blockSet.size(); i++) {
                    final int blockIndex = dynamicLayout.getBlockIndex(blockSet.valueAt(i));
                    if (blockIndex != DynamicLayout.INVALID_BLOCK_INDEX
                            && mTextRenderNodes[blockIndex] != null) {
                        mTextRenderNodes[blockIndex].needsToBeShifted = true;
                    }
                }
            }

            int startBlock = Arrays.binarySearch(blockEndLines, 0, numberOfBlocks, firstLine);
            if (startBlock < 0) {
                startBlock = -(startBlock + 1);
            }
            startBlock = Math.min(indexFirstChangedBlock, startBlock);

            int startIndexToFindAvailableRenderNode = 0;
            int lastIndex = numberOfBlocks;

            for (int i = startBlock; i < numberOfBlocks; i++) {
                final int blockIndex = blockIndices[i];
                if (i >= indexFirstChangedBlock
                        && blockIndex != DynamicLayout.INVALID_BLOCK_INDEX
                        && mTextRenderNodes[blockIndex] != null) {
                    mTextRenderNodes[blockIndex].needsToBeShifted = true;
                }
                if (blockEndLines[i] < firstLine) {
                    // Blocks in [indexFirstChangedBlock, firstLine) are not redrawn here. They will
                    // be redrawn after they get scrolled into drawing range.
                    continue;
                }
                startIndexToFindAvailableRenderNode = drawHardwareAcceleratedInner(canvas, layout,
                        highlight, highlightPaint, cursorOffsetVertical, blockEndLines,
                        blockIndices, i, numberOfBlocks, startIndexToFindAvailableRenderNode);
                if (blockEndLines[i] >= lastLine) {
                    lastIndex = Math.max(indexFirstChangedBlock, i + 1);
                    break;
                }
            }
            if (blockSet != null) {
                for (int i = 0; i < blockSet.size(); i++) {
                    final int block = blockSet.valueAt(i);
                    final int blockIndex = dynamicLayout.getBlockIndex(block);
                    if (blockIndex == DynamicLayout.INVALID_BLOCK_INDEX
                            || mTextRenderNodes[blockIndex] == null
                            || mTextRenderNodes[blockIndex].needsToBeShifted) {
                        startIndexToFindAvailableRenderNode = drawHardwareAcceleratedInner(canvas,
                                layout, highlight, highlightPaint, cursorOffsetVertical,
                                blockEndLines, blockIndices, block, numberOfBlocks,
                                startIndexToFindAvailableRenderNode);
                    }
                }
            }

            dynamicLayout.setIndexFirstChangedBlock(lastIndex);
        } else {
            // Boring layout is used for empty and hint text
            layout.drawText(canvas, firstLine, lastLine);
        }
    }

    private int drawHardwareAcceleratedInner(Canvas canvas, Layout layout, Path highlight,
            Paint highlightPaint, int cursorOffsetVertical, int[] blockEndLines,
            int[] blockIndices, int blockInfoIndex, int numberOfBlocks,
            int startIndexToFindAvailableRenderNode) {
        final int blockEndLine = blockEndLines[blockInfoIndex];
        int blockIndex = blockIndices[blockInfoIndex];

        final boolean blockIsInvalid = blockIndex == DynamicLayout.INVALID_BLOCK_INDEX;
        if (blockIsInvalid) {
            blockIndex = getAvailableDisplayListIndex(blockIndices, numberOfBlocks,
                    startIndexToFindAvailableRenderNode);
            // Note how dynamic layout's internal block indices get updated from Editor
            blockIndices[blockInfoIndex] = blockIndex;
            if (mTextRenderNodes[blockIndex] != null) {
                mTextRenderNodes[blockIndex].isDirty = true;
            }
            startIndexToFindAvailableRenderNode = blockIndex + 1;
        }

        if (mTextRenderNodes[blockIndex] == null) {
            mTextRenderNodes[blockIndex] = new TextRenderNode("Text " + blockIndex);
        }

        final boolean blockDisplayListIsInvalid = mTextRenderNodes[blockIndex].needsRecord();
        RenderNode blockDisplayList = mTextRenderNodes[blockIndex].renderNode;
        if (mTextRenderNodes[blockIndex].needsToBeShifted || blockDisplayListIsInvalid) {
            final int blockBeginLine = blockInfoIndex == 0 ?
                    0 : blockEndLines[blockInfoIndex - 1] + 1;
            final int top = layout.getLineTop(blockBeginLine);
            final int bottom = layout.getLineBottom(blockEndLine);
            int left = 0;
            int right = mTextView.getWidth();
            if (mTextView.getHorizontallyScrolling()) {
                float min = Float.MAX_VALUE;
                float max = Float.MIN_VALUE;
                for (int line = blockBeginLine; line <= blockEndLine; line++) {
                    min = Math.min(min, layout.getLineLeft(line));
                    max = Math.max(max, layout.getLineRight(line));
                }
                left = (int) min;
                right = (int) (max + 0.5f);
            }

            // Rebuild display list if it is invalid
            if (blockDisplayListIsInvalid) {
                final DisplayListCanvas displayListCanvas = blockDisplayList.start(
                        right - left, bottom - top);
                try {
                    // drawText is always relative to TextView's origin, this translation
                    // brings this range of text back to the top left corner of the viewport
                    displayListCanvas.translate(-left, -top);
                    layout.drawText(displayListCanvas, blockBeginLine, blockEndLine);
                    mTextRenderNodes[blockIndex].isDirty = false;
                    // No need to untranslate, previous context is popped after
                    // drawDisplayList
                } finally {
                    blockDisplayList.end(displayListCanvas);
                    // Same as drawDisplayList below, handled by our TextView's parent
                    blockDisplayList.setClipToBounds(false);
                }
            }

            // Valid display list only needs to update its drawing location.
            blockDisplayList.setLeftTopRightBottom(left, top, right, bottom);
            mTextRenderNodes[blockIndex].needsToBeShifted = false;
        }
        ((DisplayListCanvas) canvas).drawRenderNode(blockDisplayList);
        return startIndexToFindAvailableRenderNode;
    }

    private int getAvailableDisplayListIndex(int[] blockIndices, int numberOfBlocks,
            int searchStartIndex) {
        int length = mTextRenderNodes.length;
        for (int i = searchStartIndex; i < length; i++) {
            boolean blockIndexFound = false;
            for (int j = 0; j < numberOfBlocks; j++) {
                if (blockIndices[j] == i) {
                    blockIndexFound = true;
                    break;
                }
            }
            if (blockIndexFound) continue;
            return i;
        }

        // No available index found, the pool has to grow
        mTextRenderNodes = GrowingArrayUtils.append(mTextRenderNodes, length, null);
        return length;
    }

    private void drawCursor(Canvas canvas, int cursorOffsetVertical) {
        final boolean translate = cursorOffsetVertical != 0;
        if (translate) canvas.translate(0, cursorOffsetVertical);
        for (int i = 0; i < mCursorCount; i++) {
            mCursorDrawable[i].draw(canvas);
        }
        if (translate) canvas.translate(0, -cursorOffsetVertical);
    }

    void invalidateHandlesAndActionMode() {
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.invalidateHandles();
        }
        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.invalidateHandle();
        }
        if (mTextActionMode != null) {
            invalidateActionMode();
        }
    }

    /**
     * Invalidates all the sub-display lists that overlap the specified character range
     */
    void invalidateTextDisplayList(Layout layout, int start, int end) {
        if (mTextRenderNodes != null && layout instanceof DynamicLayout) {
            final int firstLine = layout.getLineForOffset(start);
            final int lastLine = layout.getLineForOffset(end);

            DynamicLayout dynamicLayout = (DynamicLayout) layout;
            int[] blockEndLines = dynamicLayout.getBlockEndLines();
            int[] blockIndices = dynamicLayout.getBlockIndices();
            final int numberOfBlocks = dynamicLayout.getNumberOfBlocks();

            int i = 0;
            // Skip the blocks before firstLine
            while (i < numberOfBlocks) {
                if (blockEndLines[i] >= firstLine) break;
                i++;
            }

            // Invalidate all subsequent blocks until lastLine is passed
            while (i < numberOfBlocks) {
                final int blockIndex = blockIndices[i];
                if (blockIndex != DynamicLayout.INVALID_BLOCK_INDEX) {
                    mTextRenderNodes[blockIndex].isDirty = true;
                }
                if (blockEndLines[i] >= lastLine) break;
                i++;
            }
        }
    }

    void invalidateTextDisplayList() {
        if (mTextRenderNodes != null) {
            for (int i = 0; i < mTextRenderNodes.length; i++) {
                if (mTextRenderNodes[i] != null) mTextRenderNodes[i].isDirty = true;
            }
        }
    }

    void updateCursorsPositions() {
        if (mTextView.mCursorDrawableRes == 0) {
            mCursorCount = 0;
            return;
        }

        Layout layout = mTextView.getLayout();
        final int offset = mTextView.getSelectionStart();
        final int line = layout.getLineForOffset(offset);
        final int top = layout.getLineTop(line);
        final int bottom = layout.getLineTop(line + 1);

        mCursorCount = layout.isLevelBoundary(offset) ? 2 : 1;

        int middle = bottom;
        if (mCursorCount == 2) {
            // Similar to what is done in {@link Layout.#getCursorPath(int, Path, CharSequence)}
            middle = (top + bottom) >> 1;
        }

        boolean clamped = layout.shouldClampCursor(line);
        updateCursorPosition(0, top, middle, layout.getPrimaryHorizontal(offset, clamped));

        if (mCursorCount == 2) {
            updateCursorPosition(1, middle, bottom, layout.getSecondaryHorizontal(offset, clamped));
        }
    }

    void refreshTextActionMode() {
        if (extractedTextModeWillBeStarted()) {
            mRestartActionModeOnNextRefresh = false;
            return;
        }
        final boolean hasSelection = mTextView.hasSelection();
        final SelectionModifierCursorController selectionController = getSelectionController();
        final InsertionPointCursorController insertionController = getInsertionController();
        if ((selectionController != null && selectionController.isCursorBeingModified())
                || (insertionController != null && insertionController.isCursorBeingModified())) {
            // ActionMode should be managed by the currently active cursor controller.
            mRestartActionModeOnNextRefresh = false;
            return;
        }
        if (hasSelection) {
            hideInsertionPointCursorController();
            if (mTextActionMode == null) {
                if (mRestartActionModeOnNextRefresh) {
                    // To avoid distraction, newly start action mode only when selection action
                    // mode is being restarted.
                    startSelectionActionModeAsync(false);
                }
            } else if (selectionController == null || !selectionController.isActive()) {
                // Insertion action mode is active. Avoid dismissing the selection.
                stopTextActionModeWithPreservingSelection();
                startSelectionActionModeAsync(false);
            } else {
                mTextActionMode.invalidateContentRect();
            }
        } else {
            // Insertion action mode is started only when insertion controller is explicitly
            // activated.
            if (insertionController == null || !insertionController.isActive()) {
                stopTextActionMode();
            } else if (mTextActionMode != null) {
                mTextActionMode.invalidateContentRect();
            }
        }
        mRestartActionModeOnNextRefresh = false;
    }

    /**
     * Start an Insertion action mode.
     */
    void startInsertionActionMode() {
        if (mInsertionActionModeRunnable != null) {
            mTextView.removeCallbacks(mInsertionActionModeRunnable);
        }
        if (extractedTextModeWillBeStarted()) {
            return;
        }
        // 隐藏selectionController
        stopTextActionMode();

        ActionMode.Callback actionModeCallback =
                new TextActionModeCallback(false /* hasSelection */);
        mTextActionMode = mTextView.startActionMode(
                actionModeCallback, ActionMode.TYPE_FLOATING);
        // 估计修bug加的，正常流程是先显示InsertHandleView，然后调用startInsertionActionMode方法，
        // 这里并不需要再调用一次handleView的展示代码。
        if (mTextActionMode != null && getInsertionController() != null) {
            getInsertionController().show();
        }
    }

    @NonNull
    TextView getTextView() {
        return mTextView;
    }

    @Nullable
    ActionMode getTextActionMode() {
        return mTextActionMode;
    }

    void setRestartActionModeOnNextRefresh(boolean value) {
        mRestartActionModeOnNextRefresh = value;
    }

    /**
     * Asynchronously starts a selection action mode using the TextClassifier.
     */
    void startSelectionActionModeAsync(boolean adjustSelection) {
        getSelectionActionModeHelper().startActionModeAsync(adjustSelection);
    }

    /**
     * Asynchronously invalidates an action mode using the TextClassifier.
     */
    void invalidateActionModeAsync() {
        getSelectionActionModeHelper().invalidateActionModeAsync();
    }

    /**
     * Synchronously invalidates an action mode without the TextClassifier.
     */
    private void invalidateActionMode() {
        if (mTextActionMode != null) {
            mTextActionMode.invalidate();
        }
    }

    private android.widget.SelectionActionModeHelper getSelectionActionModeHelper() {
        if (mSelectionActionModeHelper == null) {
            mSelectionActionModeHelper = new android.widget.SelectionActionModeHelper(this);
        }
        return mSelectionActionModeHelper;
    }

    /**
     * If the TextView allows text selection, selects the current word when no existing selection
     * was available and starts a drag.
     *
     * @return true if the drag was started.
     */
    private boolean selectCurrentWordAndStartDrag() {
        if (mInsertionActionModeRunnable != null) {
            mTextView.removeCallbacks(mInsertionActionModeRunnable);
        }
        // 感觉这一直会是false
        if (extractedTextModeWillBeStarted()) {
            return false;
        }
        if (!checkField()) {
            return false;
        }
        /*
         * selectcurrentword-> setSelection()
         */
        if (!mTextView.hasSelection() && !selectCurrentWord()) {
            // No selection and cannot select a word.
            return false;
        }
        /*
         * restartActionModeOnNextRefresh=true;
         * preserve selection
         */
        /*
         * 1.CursorController可能只与Cursor进行交互，不控制ActionMode，ActionMode与
         *   Cursor的控制是并行的，但是又是独立各自控制各自的。
         * 2.ActionMode控制的是ActionMode.Callback和FloatToolBar(弹框)
         * 3.Selection控制Selection
         */
        stopTextActionModeWithPreservingSelection();
        getSelectionController().enterDrag(
                SelectionModifierCursorController.DRAG_ACCELERATOR_MODE_WORD);
        return true;
    }

    /**
     * Checks whether a selection can be performed on the current TextView.
     *
     * @return true if a selection can be performed
     */
    boolean checkField() {
        if (!mTextView.canSelectText() || !mTextView.requestFocus()) {
            Log.w(TextView.LOG_TAG,
                    "TextView does not support text selection. Selection cancelled.");
            return false;
        }
        return true;
    }

    boolean startSelectionActionModeInternal() {
        if (extractedTextModeWillBeStarted()) {
            return false;
        }
        if (mTextActionMode != null) {
            // Text action mode is already started
            invalidateActionMode();
            return false;
        }

        if (!checkField() || !mTextView.hasSelection()) {
            return false;
        }

        ActionMode.Callback actionModeCallback =
                new TextActionModeCallback(true /* hasSelection */);
        mTextActionMode = mTextView.startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING);

        final boolean selectionStarted = mTextActionMode != null;
        if (selectionStarted && !mTextView.isTextSelectable() && mShowSoftInputOnFocus) {
            // Show the IME to be able to replace text, except when selecting non editable text.
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                imm.showSoftInput(mTextView, 0, null);
            }
        }
        return selectionStarted;
    }

    private boolean extractedTextModeWillBeStarted() {
        if (!(mTextView.isInExtractedMode())) {
            final InputMethodManager imm = InputMethodManager.peekInstance();
            return  imm != null && imm.isFullscreenMode();
        }
        return false;
    }

    /**
     * @return <code>true</code> if it's reasonable to offer to show suggestions depending on
     * the current cursor position or selection range. This method is consistent with the
     * method to show suggestions {@link SuggestionsPopupWindow#updateSuggestions}.
     */
    private boolean shouldOfferToShowSuggestions() {
        CharSequence text = mTextView.getText();
        if (!(text instanceof Spannable)) return false;

        final Spannable spannable = (Spannable) text;
        final int selectionStart = mTextView.getSelectionStart();
        final int selectionEnd = mTextView.getSelectionEnd();
        final SuggestionSpan[] suggestionSpans = spannable.getSpans(selectionStart, selectionEnd,
                SuggestionSpan.class);
        if (suggestionSpans.length == 0) {
            return false;
        }
        if (selectionStart == selectionEnd) {
            // Spans overlap the cursor.
            for (int i = 0; i < suggestionSpans.length; i++) {
                if (suggestionSpans[i].getSuggestions().length > 0) {
                    return true;
                }
            }
            return false;
        }
        int minSpanStart = mTextView.getText().length();
        int maxSpanEnd = 0;
        int unionOfSpansCoveringSelectionStartStart = mTextView.getText().length();
        int unionOfSpansCoveringSelectionStartEnd = 0;
        boolean hasValidSuggestions = false;
        for (int i = 0; i < suggestionSpans.length; i++) {
            final int spanStart = spannable.getSpanStart(suggestionSpans[i]);
            final int spanEnd = spannable.getSpanEnd(suggestionSpans[i]);
            minSpanStart = Math.min(minSpanStart, spanStart);
            maxSpanEnd = Math.max(maxSpanEnd, spanEnd);
            if (selectionStart < spanStart || selectionStart > spanEnd) {
                // The span doesn't cover the current selection start point.
                continue;
            }
            hasValidSuggestions =
                    hasValidSuggestions || suggestionSpans[i].getSuggestions().length > 0;
            unionOfSpansCoveringSelectionStartStart =
                    Math.min(unionOfSpansCoveringSelectionStartStart, spanStart);
            unionOfSpansCoveringSelectionStartEnd =
                    Math.max(unionOfSpansCoveringSelectionStartEnd, spanEnd);
        }
        if (!hasValidSuggestions) {
            return false;
        }
        if (unionOfSpansCoveringSelectionStartStart >= unionOfSpansCoveringSelectionStartEnd) {
            // No spans cover the selection start point.
            return false;
        }
        if (minSpanStart < unionOfSpansCoveringSelectionStartStart
                || maxSpanEnd > unionOfSpansCoveringSelectionStartEnd) {
            // There is a span that is not covered by the union. In this case, we soouldn't offer
            // to show suggestions as it's confusing.
            return false;
        }
        return true;
    }

    /**
     * @return <code>true</code> if the cursor is inside an {@link SuggestionSpan} with
     * {@link SuggestionSpan#FLAG_EASY_CORRECT} set.
     */
    private boolean isCursorInsideEasyCorrectionSpan() {
        Spannable spannable = (Spannable) mTextView.getText();
        SuggestionSpan[] suggestionSpans = spannable.getSpans(mTextView.getSelectionStart(),
                mTextView.getSelectionEnd(), SuggestionSpan.class);
        for (int i = 0; i < suggestionSpans.length; i++) {
            if ((suggestionSpans[i].getFlags() & SuggestionSpan.FLAG_EASY_CORRECT) != 0) {
                return true;
            }
        }
        return false;
    }

    void onTouchUpEvent(MotionEvent event) {
        if (getSelectionActionModeHelper().resetSelection(
                getTextView().getOffsetForPosition(event.getX(), event.getY()))) {
            return;
        }

        boolean selectAllGotFocus = mSelectAllOnFocus && mTextView.didTouchFocusSelect();
        hideCursorAndSpanControllers();
        stopTextActionMode();
        CharSequence text = mTextView.getText();
        if (!selectAllGotFocus && text.length() > 0) {
            // Move cursor
            final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());
            Selection.setSelection((Spannable) text, offset);
            if (mSpellChecker != null) {
                // When the cursor moves, the word that was typed may need spell check
                mSpellChecker.onSelectionChanged();
            }

            if (!extractedTextModeWillBeStarted()) {
                if (isCursorInsideEasyCorrectionSpan()) {
                    // Cancel the single tap delayed runnable.
                    if (mInsertionActionModeRunnable != null) {
                        mTextView.removeCallbacks(mInsertionActionModeRunnable);
                    }

                    mShowSuggestionRunnable = new Runnable() {
                        public void run() {
                            replace();
                        }
                    };
                    // removeCallbacks is performed on every touch
                    mTextView.postDelayed(mShowSuggestionRunnable,
                            ViewConfiguration.getDoubleTapTimeout());
                } else if (hasInsertionController()) {
                    getInsertionController().show();
                }
            }
        }
    }

    protected void stopTextActionMode() {
        if (mTextActionMode != null) {
            // This will hide the mSelectionModifierCursorController
            mTextActionMode.finish();
        }
    }

    private void stopTextActionModeWithPreservingSelection() {
        if (mTextActionMode != null) {
            mRestartActionModeOnNextRefresh = true;
        }
        mPreserveSelection = true;
        stopTextActionMode();
        mPreserveSelection = false;
    }

    /**
     * @return True if this view supports insertion handles.
     */
    boolean hasInsertionController() {
        return mInsertionControllerEnabled;
    }

    /**
     * @return True if this view supports selection handles.
     */
    boolean hasSelectionController() {
        return mSelectionControllerEnabled;
    }

    private InsertionPointCursorController getInsertionController() {
        if (!mInsertionControllerEnabled) {
            return null;
        }

        if (mInsertionPointCursorController == null) {
            mInsertionPointCursorController = new InsertionPointCursorController();

            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
        }

        return mInsertionPointCursorController;
    }

    @Nullable
    SelectionModifierCursorController getSelectionController() {
        if (!mSelectionControllerEnabled) {
            return null;
        }

        if (mSelectionModifierCursorController == null) {
            mSelectionModifierCursorController = new SelectionModifierCursorController();

            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
        }

        return mSelectionModifierCursorController;
    }

    @VisibleForTesting
    public Drawable[] getCursorDrawable() {
        return mCursorDrawable;
    }

    private void updateCursorPosition(int cursorIndex, int top, int bottom, float horizontal) {
        if (mCursorDrawable[cursorIndex] == null) {
            mCursorDrawable[cursorIndex] = mTextView.getContext().getDrawable(
                    mTextView.mCursorDrawableRes);
        }
        final Drawable drawable = mCursorDrawable[cursorIndex];
        final int left = clampHorizontalPosition(drawable, horizontal);
        final int width = drawable.getIntrinsicWidth();
        drawable.setBounds(left, top - mTempRect.top, left + width,
                bottom + mTempRect.bottom);
    }

    /**
     * Return clamped position for the drawable. If the drawable is within the boundaries of the
     * view, then it is offset with the left padding of the cursor drawable. If the drawable is at
     * the beginning or the end of the text then its drawable edge is aligned with left or right of
     * the view boundary. If the drawable is null, horizontal parameter is aligned to left or right
     * of the view.
     *
     * @param drawable Drawable. Can be null.
     * @param horizontal Horizontal position for the drawable.
     * @return The clamped horizontal position for the drawable.
     */
    private int clampHorizontalPosition(@Nullable final Drawable drawable, float horizontal) {
        horizontal = Math.max(0.5f, horizontal - 0.5f);
        if (mTempRect == null) mTempRect = new Rect();

        int drawableWidth = 0;
        if (drawable != null) {
            drawable.getPadding(mTempRect);
            drawableWidth = drawable.getIntrinsicWidth();
        } else {
            mTempRect.setEmpty();
        }

        int scrollX = mTextView.getScrollX();
        float horizontalDiff = horizontal - scrollX;
        int viewClippedWidth = mTextView.getWidth() - mTextView.getCompoundPaddingLeft()
                - mTextView.getCompoundPaddingRight();

        final int left;
        if (horizontalDiff >= (viewClippedWidth - 1f)) {
            // at the rightmost position
            left = viewClippedWidth + scrollX - (drawableWidth - mTempRect.right);
        } else if (Math.abs(horizontalDiff) <= 1f
                || (TextUtils.isEmpty(mTextView.getText())
                        && (TextView.VERY_WIDE - scrollX) <= (viewClippedWidth + 1f)
                        && horizontal <= 1f)) {
            // at the leftmost position
            left = scrollX - mTempRect.left;
        } else {
            left = (int) horizontal - mTempRect.left;
        }
        return left;
    }

    /**
     * Called by the framework in response to a text auto-correction (such as fixing a typo using a
     * a dictionary) from the current input method, provided by it calling
     * {@link InputConnection#commitCorrection} InputConnection.commitCorrection()}. The default
     * implementation flashes the background of the corrected word to provide feedback to the user.
     *
     * @param info The auto correct info about the text that was corrected.
     */
    public void onCommitCorrection(CorrectionInfo info) {
        if (mCorrectionHighlighter == null) {
            mCorrectionHighlighter = new CorrectionHighlighter();
        } else {
            mCorrectionHighlighter.invalidate(false);
        }

        mCorrectionHighlighter.highlight(info);
        mUndoInputFilter.freezeLastEdit();
    }

    void onScrollChanged() {
        if (mPositionListener != null) {
            mPositionListener.onScrollChanged();
        }
        if (mTextActionMode != null) {
            mTextActionMode.invalidateContentRect();
        }
    }

    /**
     * @return True when the TextView isFocused and has a valid zero-length selection (cursor).
     */
    private boolean shouldBlink() {
        if (!isCursorVisible() || !mTextView.isFocused()) return false;

        final int start = mTextView.getSelectionStart();
        if (start < 0) return false;

        final int end = mTextView.getSelectionEnd();
        if (end < 0) return false;

        return start == end;
    }

    void makeBlink() {
        if (shouldBlink()) {
            mShowCursor = SystemClock.uptimeMillis();
            if (mBlink == null) mBlink = new Blink();
            mTextView.removeCallbacks(mBlink);
            mTextView.postDelayed(mBlink, BLINK);
        } else {
            if (mBlink != null) mTextView.removeCallbacks(mBlink);
        }
    }

    private class Blink implements Runnable {
        private boolean mCancelled;

        public void run() {
            if (mCancelled) {
                return;
            }

            mTextView.removeCallbacks(this);

            if (shouldBlink()) {
                if (mTextView.getLayout() != null) {
                    mTextView.invalidateCursorPath();
                }

                mTextView.postDelayed(this, BLINK);
            }
        }

        void cancel() {
            if (!mCancelled) {
                mTextView.removeCallbacks(this);
                mCancelled = true;
            }
        }

        void uncancel() {
            mCancelled = false;
        }
    }

    private DragShadowBuilder getTextThumbnailBuilder(int start, int end) {
        TextView shadowView = (TextView) View.inflate(mTextView.getContext(),
                com.android.internal.R.layout.text_drag_thumbnail, null);

        if (shadowView == null) {
            throw new IllegalArgumentException("Unable to inflate text drag thumbnail");
        }

        if (end - start > DRAG_SHADOW_MAX_TEXT_LENGTH) {
            final long range = getCharClusterRange(start + DRAG_SHADOW_MAX_TEXT_LENGTH);
            end = TextUtils.unpackRangeEndFromLong(range);
        }
        final CharSequence text = mTextView.getTransformedText(start, end);
        shadowView.setText(text);
        shadowView.setTextColor(mTextView.getTextColors());

        shadowView.setTextAppearance(R.styleable.Theme_textAppearanceLarge);
        shadowView.setGravity(Gravity.CENTER);

        shadowView.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int size = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        shadowView.measure(size, size);

        shadowView.layout(0, 0, shadowView.getMeasuredWidth(), shadowView.getMeasuredHeight());
        shadowView.invalidate();
        return new DragShadowBuilder(shadowView);
    }

    private static class DragLocalState {
        public TextView sourceTextView;
        public int start, end;

        public DragLocalState(TextView sourceTextView, int start, int end) {
            this.sourceTextView = sourceTextView;
            this.start = start;
            this.end = end;
        }
    }

    void onDrop(DragEvent event) {
        SpannableStringBuilder content = new SpannableStringBuilder();

        final DragAndDropPermissions permissions = DragAndDropPermissions.obtain(event);
        if (permissions != null) {
            permissions.takeTransient();
        }

        try {
            ClipData clipData = event.getClipData();
            final int itemCount = clipData.getItemCount();
            for (int i = 0; i < itemCount; i++) {
                Item item = clipData.getItemAt(i);
                content.append(item.coerceToStyledText(mTextView.getContext()));
            }
        } finally {
            if (permissions != null) {
                permissions.release();
            }
        }

        mTextView.beginBatchEdit();
        mUndoInputFilter.freezeLastEdit();
        try {
            final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());
            Object localState = event.getLocalState();
            DragLocalState dragLocalState = null;
            if (localState instanceof DragLocalState) {
                dragLocalState = (DragLocalState) localState;
            }
            boolean dragDropIntoItself = dragLocalState != null
                    && dragLocalState.sourceTextView == mTextView;

            if (dragDropIntoItself) {
                if (offset >= dragLocalState.start && offset < dragLocalState.end) {
                    // A drop inside the original selection discards the drop.
                    return;
                }
            }

            final int originalLength = mTextView.getText().length();
            int min = offset;
            int max = offset;

            Selection.setSelection((Spannable) mTextView.getText(), max);
            mTextView.replaceText_internal(min, max, content);

            if (dragDropIntoItself) {
                int dragSourceStart = dragLocalState.start;
                int dragSourceEnd = dragLocalState.end;
                if (max <= dragSourceStart) {
                    // Inserting text before selection has shifted positions
                    final int shift = mTextView.getText().length() - originalLength;
                    dragSourceStart += shift;
                    dragSourceEnd += shift;
                }

                // Delete original selection
                mTextView.deleteText_internal(dragSourceStart, dragSourceEnd);

                // Make sure we do not leave two adjacent spaces.
                final int prevCharIdx = Math.max(0,  dragSourceStart - 1);
                final int nextCharIdx = Math.min(mTextView.getText().length(), dragSourceStart + 1);
                if (nextCharIdx > prevCharIdx + 1) {
                    CharSequence t = mTextView.getTransformedText(prevCharIdx, nextCharIdx);
                    if (Character.isSpaceChar(t.charAt(0)) && Character.isSpaceChar(t.charAt(1))) {
                        mTextView.deleteText_internal(prevCharIdx, prevCharIdx + 1);
                    }
                }
            }
        } finally {
            mTextView.endBatchEdit();
            mUndoInputFilter.freezeLastEdit();
        }
    }

    public void addSpanWatchers(Spannable text) {
        final int textLength = text.length();

        if (mKeyListener != null) {
            text.setSpan(mKeyListener, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        if (mSpanController == null) {
            mSpanController = new SpanController();
        }
        text.setSpan(mSpanController, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    void setContextMenuAnchor(float x, float y) {
        mContextMenuAnchorX = x;
        mContextMenuAnchorY = y;
    }

    void onCreateContextMenu(ContextMenu menu) {
        if (mIsBeingLongClicked || Float.isNaN(mContextMenuAnchorX)
                || Float.isNaN(mContextMenuAnchorY)) {
            return;
        }
        final int offset = mTextView.getOffsetForPosition(mContextMenuAnchorX, mContextMenuAnchorY);
        if (offset == -1) {
            return;
        }

        stopTextActionModeWithPreservingSelection();
        if (mTextView.canSelectText()) {
            final boolean isOnSelection = mTextView.hasSelection()
                    && offset >= mTextView.getSelectionStart()
                    && offset <= mTextView.getSelectionEnd();
            if (!isOnSelection) {
                // Right clicked position is not on the selection. Remove the selection and move the
                // cursor to the right clicked position.
                Selection.setSelection((Spannable) mTextView.getText(), offset);
                stopTextActionMode();
            }
        }

        if (shouldOfferToShowSuggestions()) {
            final SuggestionInfo[] suggestionInfoArray =
                    new SuggestionInfo[SuggestionSpan.SUGGESTIONS_MAX_SIZE];
            for (int i = 0; i < suggestionInfoArray.length; i++) {
                suggestionInfoArray[i] = new SuggestionInfo();
            }
            final SubMenu subMenu = menu.addSubMenu(Menu.NONE, Menu.NONE, MENU_ITEM_ORDER_REPLACE,
                    com.android.internal.R.string.replace);
            final int numItems = mSuggestionHelper.getSuggestionInfo(suggestionInfoArray, null);
            for (int i = 0; i < numItems; i++) {
                final SuggestionInfo info = suggestionInfoArray[i];
                subMenu.add(Menu.NONE, Menu.NONE, i, info.mText)
                        .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                            @Override
                            public boolean onMenuItemClick(MenuItem item) {
                                replaceWithSuggestion(info);
                                return true;
                            }
                        });
            }
        }

        menu.add(Menu.NONE, TextView.ID_UNDO, MENU_ITEM_ORDER_UNDO,
                com.android.internal.R.string.undo)
                .setAlphabeticShortcut('z')
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener)
                .setEnabled(mTextView.canUndo());
        menu.add(Menu.NONE, TextView.ID_REDO, MENU_ITEM_ORDER_REDO,
                com.android.internal.R.string.redo)
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener)
                .setEnabled(mTextView.canRedo());

        menu.add(Menu.NONE, TextView.ID_CUT, MENU_ITEM_ORDER_CUT,
                com.android.internal.R.string.cut)
                .setAlphabeticShortcut('x')
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener)
                .setEnabled(mTextView.canCut());
        menu.add(Menu.NONE, TextView.ID_COPY, MENU_ITEM_ORDER_COPY,
                com.android.internal.R.string.copy)
                .setAlphabeticShortcut('c')
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener)
                .setEnabled(mTextView.canCopy());
        menu.add(Menu.NONE, TextView.ID_PASTE, MENU_ITEM_ORDER_PASTE,
                com.android.internal.R.string.paste)
                .setAlphabeticShortcut('v')
                .setEnabled(mTextView.canPaste())
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener);
        menu.add(Menu.NONE, TextView.ID_PASTE_AS_PLAIN_TEXT, MENU_ITEM_ORDER_PASTE_AS_PLAIN_TEXT,
                com.android.internal.R.string.paste_as_plain_text)
                .setEnabled(mTextView.canPasteAsPlainText())
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener);
        menu.add(Menu.NONE, TextView.ID_SHARE, MENU_ITEM_ORDER_SHARE,
                com.android.internal.R.string.share)
                .setEnabled(mTextView.canShare())
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener);
        menu.add(Menu.NONE, TextView.ID_SELECT_ALL, MENU_ITEM_ORDER_SELECT_ALL,
                com.android.internal.R.string.selectAll)
                .setAlphabeticShortcut('a')
                .setEnabled(mTextView.canSelectAllText())
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener);
        menu.add(Menu.NONE, TextView.ID_AUTOFILL, MENU_ITEM_ORDER_AUTOFILL,
                com.android.internal.R.string.autofill)
                .setEnabled(mTextView.canRequestAutofill())
                .setOnMenuItemClickListener(mOnContextMenuItemClickListener);

        mPreserveSelection = true;
    }

    @Nullable
    private SuggestionSpan findEquivalentSuggestionSpan(
            @NonNull SuggestionSpanInfo suggestionSpanInfo) {
        final Editable editable = (Editable) mTextView.getText();
        if (editable.getSpanStart(suggestionSpanInfo.mSuggestionSpan) >= 0) {
            // Exactly same span is found.
            return suggestionSpanInfo.mSuggestionSpan;
        }
        // Suggestion span couldn't be found. Try to find a suggestion span that has the same
        // contents.
        final SuggestionSpan[] suggestionSpans = editable.getSpans(suggestionSpanInfo.mSpanStart,
                suggestionSpanInfo.mSpanEnd, SuggestionSpan.class);
        for (final SuggestionSpan suggestionSpan : suggestionSpans) {
            final int start = editable.getSpanStart(suggestionSpan);
            if (start != suggestionSpanInfo.mSpanStart) {
                continue;
            }
            final int end = editable.getSpanEnd(suggestionSpan);
            if (end != suggestionSpanInfo.mSpanEnd) {
                continue;
            }
            if (suggestionSpan.equals(suggestionSpanInfo.mSuggestionSpan)) {
                return suggestionSpan;
            }
        }
        return null;
    }

    private void replaceWithSuggestion(@NonNull final SuggestionInfo suggestionInfo) {
        final SuggestionSpan targetSuggestionSpan = findEquivalentSuggestionSpan(
                suggestionInfo.mSuggestionSpanInfo);
        if (targetSuggestionSpan == null) {
            // Span has been removed
            return;
        }
        final Editable editable = (Editable) mTextView.getText();
        final int spanStart = editable.getSpanStart(targetSuggestionSpan);
        final int spanEnd = editable.getSpanEnd(targetSuggestionSpan);
        if (spanStart < 0 || spanEnd <= spanStart) {
            // Span has been removed
            return;
        }

        final String originalText = TextUtils.substring(editable, spanStart, spanEnd);
        // SuggestionSpans are removed by replace: save them before
        SuggestionSpan[] suggestionSpans = editable.getSpans(spanStart, spanEnd,
                SuggestionSpan.class);
        final int length = suggestionSpans.length;
        int[] suggestionSpansStarts = new int[length];
        int[] suggestionSpansEnds = new int[length];
        int[] suggestionSpansFlags = new int[length];
        for (int i = 0; i < length; i++) {
            final SuggestionSpan suggestionSpan = suggestionSpans[i];
            suggestionSpansStarts[i] = editable.getSpanStart(suggestionSpan);
            suggestionSpansEnds[i] = editable.getSpanEnd(suggestionSpan);
            suggestionSpansFlags[i] = editable.getSpanFlags(suggestionSpan);

            // Remove potential misspelled flags
            int suggestionSpanFlags = suggestionSpan.getFlags();
            if ((suggestionSpanFlags & SuggestionSpan.FLAG_MISSPELLED) != 0) {
                suggestionSpanFlags &= ~SuggestionSpan.FLAG_MISSPELLED;
                suggestionSpanFlags &= ~SuggestionSpan.FLAG_EASY_CORRECT;
                suggestionSpan.setFlags(suggestionSpanFlags);
            }
        }

        // Notify source IME of the suggestion pick. Do this before swapping texts.
        targetSuggestionSpan.notifySelection(
                mTextView.getContext(), originalText, suggestionInfo.mSuggestionIndex);

        // Swap text content between actual text and Suggestion span
        final int suggestionStart = suggestionInfo.mSuggestionStart;
        final int suggestionEnd = suggestionInfo.mSuggestionEnd;
        final String suggestion = suggestionInfo.mText.subSequence(
                suggestionStart, suggestionEnd).toString();
        mTextView.replaceText_internal(spanStart, spanEnd, suggestion);

        String[] suggestions = targetSuggestionSpan.getSuggestions();
        suggestions[suggestionInfo.mSuggestionIndex] = originalText;

        // Restore previous SuggestionSpans
        final int lengthDelta = suggestion.length() - (spanEnd - spanStart);
        for (int i = 0; i < length; i++) {
            // Only spans that include the modified region make sense after replacement
            // Spans partially included in the replaced region are removed, there is no
            // way to assign them a valid range after replacement
            if (suggestionSpansStarts[i] <= spanStart && suggestionSpansEnds[i] >= spanEnd) {
                mTextView.setSpan_internal(suggestionSpans[i], suggestionSpansStarts[i],
                        suggestionSpansEnds[i] + lengthDelta, suggestionSpansFlags[i]);
            }
        }
        // Move cursor at the end of the replaced word
        final int newCursorPosition = spanEnd + lengthDelta;
        mTextView.setCursorPosition_internal(newCursorPosition, newCursorPosition);
    }

    private final MenuItem.OnMenuItemClickListener mOnContextMenuItemClickListener =
            new MenuItem.OnMenuItemClickListener() {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            if (mProcessTextIntentActionsHandler.performMenuItemAction(item)) {
                return true;
            }
            return mTextView.onTextContextMenuItem(item.getItemId());
        }
    };

    /**
     * Controls the {@link EasyEditSpan} monitoring when it is added, and when the related
     * pop-up should be displayed.
     * Also monitors {@link Selection} to call back to the attached input method.
     *
     * 就是一个Span的监听器
     */
    private class SpanController implements SpanWatcher {

        private static final int DISPLAY_TIMEOUT_MS = 3000; // 3 secs

        private EasyEditPopupWindow mPopupWindow;

        private Runnable mHidePopup;

        // This function is pure but inner classes can't have static functions
        /*
         * Selection的两端各有一个span，updateSelection的时候，肯定是先更新其中的一个，后更新另一个，
         * 更新第一个时候，这个span就是Intermediate的，这个时候updateSelection并没有执行完，还会有后面的
         * 另一个span，所以第一个span就叫IntermediateSelectionSpan。
         */
        private boolean isNonIntermediateSelectionSpan(final Spannable text,
                final Object span) {
            return (Selection.SELECTION_START == span || Selection.SELECTION_END == span)
                    && (text.getSpanFlags(span) & Spanned.SPAN_INTERMEDIATE) == 0;
        }

        @Override
        public void onSpanAdded(Spannable text, Object span, int start, int end) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                // Selection和selectionSpan是两码事儿
                sendUpdateSelection();
            } else if (span instanceof EasyEditSpan) {
                if (mPopupWindow == null) {
                    mPopupWindow = new EasyEditPopupWindow();
                    mHidePopup = new Runnable() {
                        @Override
                        public void run() {
                            hide();
                        }
                    };
                }

                // Make sure there is only at most one EasyEditSpan in the text
                if (mPopupWindow.mEasyEditSpan != null) {
                    mPopupWindow.mEasyEditSpan.setDeleteEnabled(false);
                }

                mPopupWindow.setEasyEditSpan((EasyEditSpan) span);
                mPopupWindow.setOnDeleteListener(new EasyEditDeleteListener() {
                    @Override
                    public void onDeleteClick(EasyEditSpan span) {
                        Editable editable = (Editable) mTextView.getText();
                        int start = editable.getSpanStart(span);
                        int end = editable.getSpanEnd(span);
                        if (start >= 0 && end >= 0) {
                            sendEasySpanNotification(EasyEditSpan.TEXT_DELETED, span);
                            mTextView.deleteText_internal(start, end);
                        }
                        editable.removeSpan(span);
                    }
                });

                if (mTextView.getWindowVisibility() != View.VISIBLE) {
                    // The window is not visible yet, ignore the text change.
                    return;
                }

                if (mTextView.getLayout() == null) {
                    // The view has not been laid out yet, ignore the text change
                    return;
                }

                if (extractedTextModeWillBeStarted()) {
                    // The input is in extract mode. Do not handle the easy edit in
                    // the original TextView, as the ExtractEditText will do
                    return;
                }

                mPopupWindow.show();
                mTextView.removeCallbacks(mHidePopup);
                mTextView.postDelayed(mHidePopup, DISPLAY_TIMEOUT_MS);
            }
        }

        @Override
        public void onSpanRemoved(Spannable text, Object span, int start, int end) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (mPopupWindow != null && span == mPopupWindow.mEasyEditSpan) {
                hide();
            }
        }

        @Override
        public void onSpanChanged(Spannable text, Object span, int previousStart, int previousEnd,
                int newStart, int newEnd) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (mPopupWindow != null && span instanceof EasyEditSpan) {
                EasyEditSpan easyEditSpan = (EasyEditSpan) span;
                sendEasySpanNotification(EasyEditSpan.TEXT_MODIFIED, easyEditSpan);
                text.removeSpan(easyEditSpan);
            }
        }

        public void hide() {
            if (mPopupWindow != null) {
                mPopupWindow.hide();
                mTextView.removeCallbacks(mHidePopup);
            }
        }

        private void sendEasySpanNotification(int textChangedType, EasyEditSpan span) {
            try {
                PendingIntent pendingIntent = span.getPendingIntent();
                if (pendingIntent != null) {
                    Intent intent = new Intent();
                    intent.putExtra(EasyEditSpan.EXTRA_TEXT_CHANGED_TYPE, textChangedType);
                    pendingIntent.send(mTextView.getContext(), 0, intent);
                }
            } catch (CanceledException e) {
                // This should not happen, as we should try to send the intent only once.
                Log.w(TAG, "PendingIntent for notification cannot be sent", e);
            }
        }
    }

    /**
     * Listens for the delete event triggered by {@link EasyEditPopupWindow}.
     */
    private interface EasyEditDeleteListener {

        /**
         * Clicks the delete pop-up.
         */
        void onDeleteClick(EasyEditSpan span);
    }

    /**
     * Displays the actions associated to an {@link EasyEditSpan}. The pop-up is controlled
     * by {@link SpanController}.
     */
    private class EasyEditPopupWindow extends PinnedPopupWindow
            implements OnClickListener {
        private static final int POPUP_TEXT_LAYOUT =
                com.android.internal.R.layout.text_edit_action_popup_text;
        private TextView mDeleteTextView;
        private EasyEditSpan mEasyEditSpan;
        private EasyEditDeleteListener mOnDeleteListener;

        @Override
        protected void createPopupWindow() {
            mPopupWindow = new android.widget.PopupWindow(mTextView.getContext(), null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mPopupWindow.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
            mPopupWindow.setClippingEnabled(true);
        }

        @Override
        protected void initContentView() {
            android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(mTextView.getContext());
            linearLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            mContentView = linearLayout;
            mContentView.setBackgroundResource(
                    com.android.internal.R.drawable.text_edit_side_paste_window);

            LayoutInflater inflater = (LayoutInflater) mTextView.getContext()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            LayoutParams wrapContent = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            mDeleteTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mDeleteTextView.setLayoutParams(wrapContent);
            mDeleteTextView.setText(com.android.internal.R.string.delete);
            mDeleteTextView.setOnClickListener(this);
            mContentView.addView(mDeleteTextView);
        }

        public void setEasyEditSpan(EasyEditSpan easyEditSpan) {
            mEasyEditSpan = easyEditSpan;
        }

        private void setOnDeleteListener(EasyEditDeleteListener listener) {
            mOnDeleteListener = listener;
        }

        @Override
        public void onClick(View view) {
            if (view == mDeleteTextView
                    && mEasyEditSpan != null && mEasyEditSpan.isDeleteEnabled()
                    && mOnDeleteListener != null) {
                mOnDeleteListener.onDeleteClick(mEasyEditSpan);
            }
        }

        @Override
        public void hide() {
            if (mEasyEditSpan != null) {
                mEasyEditSpan.setDeleteEnabled(false);
            }
            mOnDeleteListener = null;
            super.hide();
        }

        @Override
        protected int getTextOffset() {
            // Place the pop-up at the end of the span
            Editable editable = (Editable) mTextView.getText();
            return editable.getSpanEnd(mEasyEditSpan);
        }

        @Override
        protected int getVerticalLocalPosition(int line) {
            return mTextView.getLayout().getLineBottom(line);
        }

        @Override
        protected int clipVertically(int positionY) {
            // As we display the pop-up below the span, no vertical clipping is required.
            return positionY;
        }
    }

    // 监听当前TextView的位置（左上角的坐标），在TextView每次绘制之前，更新一下这个类里保存的TextView
    // 坐标。然后当位置发生变化时，触发listener。
    /*
     * 在TextView每次绘制之前，首先更新TextView的位置，然后更新handleView的位置
     */
    private class PositionListener implements ViewTreeObserver.OnPreDrawListener {
        // 3 handles
        // 3 ActionPopup [replace, suggestion, easyedit] (suggestionsPopup first hides the others)
        // 1 CursorAnchorInfoNotifier
        private static final int MAXIMUM_NUMBER_OF_LISTENERS = 7;
        private TextViewPositionListener[] mPositionListeners =
                new TextViewPositionListener[MAXIMUM_NUMBER_OF_LISTENERS];

        // canMove应该是指cursor是否会移动，待确定？
        private boolean[] mCanMove = new boolean[MAXIMUM_NUMBER_OF_LISTENERS];
        // 相对于window的坐标是否改变了，注意是window，而非screen。
        private boolean mPositionHasChanged = true;
        // Absolute position of the TextView with respect to its parent window
        // TextView相对于window的坐标
        private int mPositionX, mPositionY;
        // TextView相对于屏幕的坐标。分屏，Dialog，window就比Screen要小
        private int mPositionXOnScreen, mPositionYOnScreen;
        private int mNumberOfListeners;
        private boolean mScrollHasChanged;
        final int[] mTempCoords = new int[2];

        public void addSubscriber(TextViewPositionListener positionListener, boolean canMove) {
            if (mNumberOfListeners == 0) {
                updatePosition();
                ViewTreeObserver vto = mTextView.getViewTreeObserver();
                vto.addOnPreDrawListener(this);
            }

            // 这个算法有可能在Listeners[]中重复加入同一个listener
            int emptySlotIndex = -1;
            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                TextViewPositionListener listener = mPositionListeners[i];
                if (listener == positionListener) {
                    return;
                } else if (emptySlotIndex < 0 && listener == null) {
                    emptySlotIndex = i;
                }
            }

            mPositionListeners[emptySlotIndex] = positionListener;
            mCanMove[emptySlotIndex] = canMove;
            mNumberOfListeners++;
        }

        public void removeSubscriber(TextViewPositionListener positionListener) {
            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                if (mPositionListeners[i] == positionListener) {
                    mPositionListeners[i] = null;
                    mNumberOfListeners--;
                    break;
                }
            }

            if (mNumberOfListeners == 0) {
                ViewTreeObserver vto = mTextView.getViewTreeObserver();
                vto.removeOnPreDrawListener(this);
            }
        }

        public int getPositionX() {
            return mPositionX;
        }

        public int getPositionY() {
            return mPositionY;
        }

        public int getPositionXOnScreen() {
            return mPositionXOnScreen;
        }

        public int getPositionYOnScreen() {
            return mPositionYOnScreen;
        }

        // 核心方法
        @Override
        public boolean onPreDraw() {
            updatePosition();

            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                // mPositionHasChanged指的是textView
                if (mPositionHasChanged || mScrollHasChanged || mCanMove[i]) {
                    TextViewPositionListener positionListener = mPositionListeners[i];
                    if (positionListener != null) {
                        positionListener.updatePosition(mPositionX, mPositionY,
                                mPositionHasChanged, mScrollHasChanged);
                    }
                }
            }

            mScrollHasChanged = false;
            return true;
        }

        // update的是TextView的position
        private void updatePosition() {
            mTextView.getLocationInWindow(mTempCoords);

            mPositionHasChanged = mTempCoords[0] != mPositionX || mTempCoords[1] != mPositionY;

            mPositionX = mTempCoords[0];
            mPositionY = mTempCoords[1];

            mTextView.getLocationOnScreen(mTempCoords);

            mPositionXOnScreen = mTempCoords[0];
            mPositionYOnScreen = mTempCoords[1];
        }

        public void onScrollChanged() {
            mScrollHasChanged = true;
        }
    }

    // TextView所有弹框的父类
    private abstract class PinnedPopupWindow implements TextViewPositionListener {
        protected android.widget.PopupWindow mPopupWindow;
        protected ViewGroup mContentView;
        // 当前popupWindow相对于TextView的坐标。注意，这里的positionX与positionListener里的positionX不是
        // 一回事儿，这里的是popupWindow要展示的位置，positionListener里的是TextView的位置。
        int mPositionX, mPositionY;
        int mClippingLimitLeft, mClippingLimitRight;

        protected abstract void createPopupWindow();
        protected abstract void initContentView();
        protected abstract int getTextOffset();
        protected abstract int getVerticalLocalPosition(int line);
        protected abstract int clipVertically(int positionY);
        protected void setUp() {
        }

        public PinnedPopupWindow() {
            // Due to calling subclass methods in base constructor, subclass constructor is not
            // called before subclass methods, e.g. createPopupWindow or initContentView. To give
            // a chance to initialize subclasses, call setUp() method here.
            // TODO: It is good to extract non trivial initialization code from constructor.
            setUp();

            createPopupWindow();

            mPopupWindow.setWindowLayoutType(
                    WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL);
            mPopupWindow.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mPopupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);

            initContentView();

            LayoutParams wrapContent = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mContentView.setLayoutParams(wrapContent);

            mPopupWindow.setContentView(mContentView);
        }

        public void show() {
            /*
             * 这里注册的listener，是用于监听textView的位置变化。如果在当前popupWindow显示以后，
             * TextView的位置发生了变化，那么通过这个listener来更新popupWindow的位置。所以虽然
             * 在监听器的实现里，包括了mPopupWindow.showAtLocation()这样的语句，但是这个监听器
             * 的主要目的并不是用来弹出popupWindow，而是更新已显示popupWindow的位置。
             *
             */
            getPositionListener().addSubscriber(this, false /* offset is fixed */);

            // 计算popupWindow需要固定的位置，也就是这个类的mPositionX和mPositionY
            computeLocalPosition();

            final PositionListener positionListener = getPositionListener();
            // 同步方法。未显示popupWindow时开启popupWindow；已开启时，更新popupWindow的位置。
            // pinnedPopupWindow类有两个updatePosition方法，一个是监听器，一个是这里的同步方法。
            updatePosition(positionListener.getPositionX(), positionListener.getPositionY());
        }

        // popupWindow的最大尺寸就是屏幕的尺寸
        protected void measureContent() {
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels,
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels,
                            View.MeasureSpec.AT_MOST));
        }

        /* The popup window will be horizontally centered on the getTextOffset() and vertically
         * positioned according to viewportToContentHorizontalOffset.
         *
         * This method assumes that mContentView has properly been measured from its content.
         *
         *
         * 计算popupWindow需要固定的位置（这个位置是相对于TextView的，所以后面的updatePosition方法里需要
         * 加上TextView的位置）
         */
        private void computeLocalPosition() {
            // 给一个屏幕的宽高限制，计算popupWindow的大小
            measureContent();
            final int width = mContentView.getMeasuredWidth();
            final int offset = getTextOffset(); //当前选中文字的中间位置的字符偏移量
            //                                     相当于将popupWindow的中心移动到selection的位置
            //                                 selection的位置           -         popupWindow的一半宽度
            mPositionX = (int) (mTextView.getLayout().getPrimaryHorizontal(offset) - width / 2.0f);
            mPositionX += mTextView.viewportToContentHorizontalOffset();

            final int line = mTextView.getLayout().getLineForOffset(offset);
            mPositionY = getVerticalLocalPosition(line);
            mPositionY += mTextView.viewportToContentVerticalOffset();
        }

        private void updatePosition(int parentPositionX, int parentPositionY) {
            int positionX = parentPositionX/*TextView的坐标*/ + mPositionX;
            int positionY = parentPositionY + mPositionY;

            positionY = clipVertically(positionY);

            // Horizontal clipping
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            final int width = mContentView.getMeasuredWidth();
            positionX = Math.min(
                    displayMetrics.widthPixels - width + mClippingLimitRight, positionX);
            positionX = Math.max(-mClippingLimitLeft, positionX);

            if (isShowing()) {
                mPopupWindow.update(positionX, positionY, -1, -1);
            } else {
                mPopupWindow.showAtLocation(mTextView, Gravity.NO_GRAVITY,
                        positionX, positionY);
            }
        }

        public void hide() {
            if (!isShowing()) {
                return;
            }
            mPopupWindow.dismiss();
            getPositionListener().removeSubscriber(this);
        }

        @Override
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            // Either parentPositionChanged or parentScrolled is true, check if still visible
            if (isShowing() && Visible(getTextOffset())) {
                if (parentScrolled) computeLocalPosition();
                updatePosition(parentPositionX, parentPositionY);
            } else {
                hide();
            }
        }

        public boolean isShowing() {
            return mPopupWindow.isShowing();
        }
    }

    private static final class SuggestionInfo {
        // Range of actual suggestion within mText
        int mSuggestionStart, mSuggestionEnd;

        // The SuggestionSpan that this TextView represents
        final SuggestionSpanInfo mSuggestionSpanInfo = new SuggestionSpanInfo();

        // The index of this suggestion inside suggestionSpan
        int mSuggestionIndex;

        final SpannableStringBuilder mText = new SpannableStringBuilder();

        void clear() {
            mSuggestionSpanInfo.clear();
            mText.clear();
        }

        // Utility method to set attributes about a SuggestionSpan.
        void setSpanInfo(SuggestionSpan span, int spanStart, int spanEnd) {
            mSuggestionSpanInfo.mSuggestionSpan = span;
            mSuggestionSpanInfo.mSpanStart = spanStart;
            mSuggestionSpanInfo.mSpanEnd = spanEnd;
        }
    }

    private static final class SuggestionSpanInfo {
        // The SuggestionSpan;
        @Nullable
        SuggestionSpan mSuggestionSpan;

        // The SuggestionSpan start position
        int mSpanStart;

        // The SuggestionSpan end position
        int mSpanEnd;

        void clear() {
            mSuggestionSpan = null;
        }
    }

    private class SuggestionHelper {
        private final Comparator<SuggestionSpan> mSuggestionSpanComparator =
                new SuggestionSpanComparator();
        private final HashMap<SuggestionSpan, Integer> mSpansLengths =
                new HashMap<SuggestionSpan, Integer>();

        private class SuggestionSpanComparator implements Comparator<SuggestionSpan> {
            public int compare(SuggestionSpan span1, SuggestionSpan span2) {
                final int flag1 = span1.getFlags();
                final int flag2 = span2.getFlags();
                if (flag1 != flag2) {
                    // The order here should match what is used in updateDrawState
                    final boolean easy1 = (flag1 & SuggestionSpan.FLAG_EASY_CORRECT) != 0;
                    final boolean easy2 = (flag2 & SuggestionSpan.FLAG_EASY_CORRECT) != 0;
                    final boolean misspelled1 = (flag1 & SuggestionSpan.FLAG_MISSPELLED) != 0;
                    final boolean misspelled2 = (flag2 & SuggestionSpan.FLAG_MISSPELLED) != 0;
                    if (easy1 && !misspelled1) return -1;
                    if (easy2 && !misspelled2) return 1;
                    if (misspelled1) return -1;
                    if (misspelled2) return 1;
                }

                return mSpansLengths.get(span1).intValue() - mSpansLengths.get(span2).intValue();
            }
        }

        /**
         * Returns the suggestion spans that cover the current cursor position. The suggestion
         * spans are sorted according to the length of text that they are attached to.
         */
        private SuggestionSpan[] getSortedSuggestionSpans() {
            int pos = mTextView.getSelectionStart();
            Spannable spannable = (Spannable) mTextView.getText();
            SuggestionSpan[] suggestionSpans = spannable.getSpans(pos, pos, SuggestionSpan.class);

            mSpansLengths.clear();
            for (SuggestionSpan suggestionSpan : suggestionSpans) {
                int start = spannable.getSpanStart(suggestionSpan);
                int end = spannable.getSpanEnd(suggestionSpan);
                mSpansLengths.put(suggestionSpan, Integer.valueOf(end - start));
            }

            // The suggestions are sorted according to their types (easy correction first, then
            // misspelled) and to the length of the text that they cover (shorter first).
            Arrays.sort(suggestionSpans, mSuggestionSpanComparator);
            mSpansLengths.clear();

            return suggestionSpans;
        }

        /**
         * Gets the SuggestionInfo list that contains suggestion information at the current cursor
         * position.
         *
         * @param suggestionInfos SuggestionInfo array the results will be set.
         * @param misspelledSpanInfo a struct the misspelled SuggestionSpan info will be set.
         * @return the number of suggestions actually fetched.
         */
        public int getSuggestionInfo(SuggestionInfo[] suggestionInfos,
                @Nullable SuggestionSpanInfo misspelledSpanInfo) {
            final Spannable spannable = (Spannable) mTextView.getText();
            final SuggestionSpan[] suggestionSpans = getSortedSuggestionSpans();
            final int nbSpans = suggestionSpans.length;
            if (nbSpans == 0) return 0;

            int numberOfSuggestions = 0;
            for (final SuggestionSpan suggestionSpan : suggestionSpans) {
                final int spanStart = spannable.getSpanStart(suggestionSpan);
                final int spanEnd = spannable.getSpanEnd(suggestionSpan);

                if (misspelledSpanInfo != null
                        && (suggestionSpan.getFlags() & SuggestionSpan.FLAG_MISSPELLED) != 0) {
                    misspelledSpanInfo.mSuggestionSpan = suggestionSpan;
                    misspelledSpanInfo.mSpanStart = spanStart;
                    misspelledSpanInfo.mSpanEnd = spanEnd;
                }

                final String[] suggestions = suggestionSpan.getSuggestions();
                final int nbSuggestions = suggestions.length;
                suggestionLoop:
                for (int suggestionIndex = 0; suggestionIndex < nbSuggestions; suggestionIndex++) {
                    final String suggestion = suggestions[suggestionIndex];
                    for (int i = 0; i < numberOfSuggestions; i++) {
                        final SuggestionInfo otherSuggestionInfo = suggestionInfos[i];
                        if (otherSuggestionInfo.mText.toString().equals(suggestion)) {
                            final int otherSpanStart =
                                    otherSuggestionInfo.mSuggestionSpanInfo.mSpanStart;
                            final int otherSpanEnd =
                                    otherSuggestionInfo.mSuggestionSpanInfo.mSpanEnd;
                            if (spanStart == otherSpanStart && spanEnd == otherSpanEnd) {
                                continue suggestionLoop;
                            }
                        }
                    }

                    SuggestionInfo suggestionInfo = suggestionInfos[numberOfSuggestions];
                    suggestionInfo.setSpanInfo(suggestionSpan, spanStart, spanEnd);
                    suggestionInfo.mSuggestionIndex = suggestionIndex;
                    suggestionInfo.mSuggestionStart = 0;
                    suggestionInfo.mSuggestionEnd = suggestion.length();
                    suggestionInfo.mText.replace(0, suggestionInfo.mText.length(), suggestion);
                    numberOfSuggestions++;
                    if (numberOfSuggestions >= suggestionInfos.length) {
                        return numberOfSuggestions;
                    }
                }
            }
            return numberOfSuggestions;
        }
    }

    @VisibleForTesting
    public class SuggestionsPopupWindow extends PinnedPopupWindow implements OnItemClickListener {
        private static final int MAX_NUMBER_SUGGESTIONS = SuggestionSpan.SUGGESTIONS_MAX_SIZE;

        // Key of intent extras for inserting new word into user dictionary.
        private static final String USER_DICTIONARY_EXTRA_WORD = "word";
        private static final String USER_DICTIONARY_EXTRA_LOCALE = "locale";

        private SuggestionInfo[] mSuggestionInfos;
        private int mNumberOfSuggestions;
        private boolean mCursorWasVisibleBeforeSuggestions;
        private boolean mIsShowingUp = false;
        private SuggestionAdapter mSuggestionsAdapter;
        private TextAppearanceSpan mHighlightSpan;  // TODO: Make mHighlightSpan final.
        private TextView mAddToDictionaryButton;
        private TextView mDeleteButton;
        private android.widget.ListView mSuggestionListView;
        private final SuggestionSpanInfo mMisspelledSpanInfo = new SuggestionSpanInfo();
        private int mContainerMarginWidth;
        private int mContainerMarginTop;
        private android.widget.LinearLayout mContainerView;
        private Context mContext;  // TODO: Make mContext final.

        private class CustomPopupWindow extends android.widget.PopupWindow {

            @Override
            public void dismiss() {
                if (!isShowing()) {
                    return;
                }
                super.dismiss();
                getPositionListener().removeSubscriber(SuggestionsPopupWindow.this);

                // Safe cast since show() checks that mTextView.getText() is an Editable
                ((Spannable) mTextView.getText()).removeSpan(mSuggestionRangeSpan);

                mTextView.setCursorVisible(mCursorWasVisibleBeforeSuggestions);
                if (hasInsertionController() && !extractedTextModeWillBeStarted()) {
                    getInsertionController().show();
                }
            }
        }

        public SuggestionsPopupWindow() {
            mCursorWasVisibleBeforeSuggestions = mCursorVisible;
        }

        @Override
        protected void setUp() {
            mContext = applyDefaultTheme(mTextView.getContext());
            mHighlightSpan = new TextAppearanceSpan(mContext,
                    mTextView.mTextEditSuggestionHighlightStyle);
        }

        private Context applyDefaultTheme(Context originalContext) {
            TypedArray a = originalContext.obtainStyledAttributes(
                    new int[]{com.android.internal.R.attr.isLightTheme});
            boolean isLightTheme = a.getBoolean(0, true);
            int themeId = isLightTheme ? R.style.ThemeOverlay_Material_Light
                    : R.style.ThemeOverlay_Material_Dark;
            a.recycle();
            return new ContextThemeWrapper(originalContext, themeId);
        }

        @Override
        protected void createPopupWindow() {
            mPopupWindow = new CustomPopupWindow();
            mPopupWindow.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
            mPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            mPopupWindow.setFocusable(true);
            mPopupWindow.setClippingEnabled(false);
        }

        @Override
        protected void initContentView() {
            final LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            mContentView = (ViewGroup) inflater.inflate(
                    mTextView.mTextEditSuggestionContainerLayout, null);

            mContainerView = (android.widget.LinearLayout) mContentView.findViewById(
                    com.android.internal.R.id.suggestionWindowContainer);
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mContainerView.getLayoutParams();
            mContainerMarginWidth = lp.leftMargin + lp.rightMargin;
            mContainerMarginTop = lp.topMargin;
            mClippingLimitLeft = lp.leftMargin;
            mClippingLimitRight = lp.rightMargin;

            mSuggestionListView = (android.widget.ListView) mContentView.findViewById(
                    com.android.internal.R.id.suggestionContainer);

            mSuggestionsAdapter = new SuggestionAdapter();
            mSuggestionListView.setAdapter(mSuggestionsAdapter);
            mSuggestionListView.setOnItemClickListener(this);

            // Inflate the suggestion items once and for all.
            mSuggestionInfos = new SuggestionInfo[MAX_NUMBER_SUGGESTIONS];
            for (int i = 0; i < mSuggestionInfos.length; i++) {
                mSuggestionInfos[i] = new SuggestionInfo();
            }

            mAddToDictionaryButton = (TextView) mContentView.findViewById(
                    com.android.internal.R.id.addToDictionaryButton);
            mAddToDictionaryButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    final SuggestionSpan misspelledSpan =
                            findEquivalentSuggestionSpan(mMisspelledSpanInfo);
                    if (misspelledSpan == null) {
                        // Span has been removed.
                        return;
                    }
                    final Editable editable = (Editable) mTextView.getText();
                    final int spanStart = editable.getSpanStart(misspelledSpan);
                    final int spanEnd = editable.getSpanEnd(misspelledSpan);
                    if (spanStart < 0 || spanEnd <= spanStart) {
                        return;
                    }
                    final String originalText = TextUtils.substring(editable, spanStart, spanEnd);

                    final Intent intent = new Intent(Settings.ACTION_USER_DICTIONARY_INSERT);
                    intent.putExtra(USER_DICTIONARY_EXTRA_WORD, originalText);
                    intent.putExtra(USER_DICTIONARY_EXTRA_LOCALE,
                            mTextView.getTextServicesLocale().toString());
                    intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                    mTextView.getContext().startActivity(intent);
                    // There is no way to know if the word was indeed added. Re-check.
                    // TODO The ExtractEditText should remove the span in the original text instead
                    editable.removeSpan(mMisspelledSpanInfo.mSuggestionSpan);
                    Selection.setSelection(editable, spanEnd);
                    updateSpellCheckSpans(spanStart, spanEnd, false);
                    hideWithCleanUp();
                }
            });

            mDeleteButton = (TextView) mContentView.findViewById(
                    com.android.internal.R.id.deleteButton);
            mDeleteButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    final Editable editable = (Editable) mTextView.getText();

                    final int spanUnionStart = editable.getSpanStart(mSuggestionRangeSpan);
                    int spanUnionEnd = editable.getSpanEnd(mSuggestionRangeSpan);
                    if (spanUnionStart >= 0 && spanUnionEnd > spanUnionStart) {
                        // Do not leave two adjacent spaces after deletion, or one at beginning of
                        // text
                        if (spanUnionEnd < editable.length()
                                && Character.isSpaceChar(editable.charAt(spanUnionEnd))
                                && (spanUnionStart == 0
                                        || Character.isSpaceChar(
                                                editable.charAt(spanUnionStart - 1)))) {
                            spanUnionEnd = spanUnionEnd + 1;
                        }
                        mTextView.deleteText_internal(spanUnionStart, spanUnionEnd);
                    }
                    hideWithCleanUp();
                }
            });

        }

        public boolean isShowingUp() {
            return mIsShowingUp;
        }

        public void onParentLostFocus() {
            mIsShowingUp = false;
        }

        private class SuggestionAdapter extends android.widget.BaseAdapter {
            private LayoutInflater mInflater = (LayoutInflater) mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);

            @Override
            public int getCount() {
                return mNumberOfSuggestions;
            }

            @Override
            public Object getItem(int position) {
                return mSuggestionInfos[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView textView = (TextView) convertView;

                if (textView == null) {
                    textView = (TextView) mInflater.inflate(mTextView.mTextEditSuggestionItemLayout,
                            parent, false);
                }

                final SuggestionInfo suggestionInfo = mSuggestionInfos[position];
                textView.setText(suggestionInfo.mText);
                return textView;
            }
        }

        @VisibleForTesting
        public ViewGroup getContentViewForTesting() {
            return mContentView;
        }

        @Override
        public void show() {
            if (!(mTextView.getText() instanceof Editable)) return;
            if (extractedTextModeWillBeStarted()) {
                return;
            }

            if (updateSuggestions()) {
                mCursorWasVisibleBeforeSuggestions = mCursorVisible;
                mTextView.setCursorVisible(false);
                mIsShowingUp = true;
                super.show();
            }
        }

        @Override
        protected void measureContent() {
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            final int horizontalMeasure = View.MeasureSpec.makeMeasureSpec(
                    displayMetrics.widthPixels, View.MeasureSpec.AT_MOST);
            final int verticalMeasure = View.MeasureSpec.makeMeasureSpec(
                    displayMetrics.heightPixels, View.MeasureSpec.AT_MOST);

            int width = 0;
            View view = null;
            for (int i = 0; i < mNumberOfSuggestions; i++) {
                view = mSuggestionsAdapter.getView(i, view, mContentView);
                view.getLayoutParams().width = LayoutParams.WRAP_CONTENT;
                view.measure(horizontalMeasure, verticalMeasure);
                width = Math.max(width, view.getMeasuredWidth());
            }

            if (mAddToDictionaryButton.getVisibility() != View.GONE) {
                mAddToDictionaryButton.measure(horizontalMeasure, verticalMeasure);
                width = Math.max(width, mAddToDictionaryButton.getMeasuredWidth());
            }

            mDeleteButton.measure(horizontalMeasure, verticalMeasure);
            width = Math.max(width, mDeleteButton.getMeasuredWidth());

            width += mContainerView.getPaddingLeft() + mContainerView.getPaddingRight()
                    + mContainerMarginWidth;

            // Enforce the width based on actual text widths
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    verticalMeasure);

            Drawable popupBackground = mPopupWindow.getBackground();
            if (popupBackground != null) {
                if (mTempRect == null) mTempRect = new Rect();
                popupBackground.getPadding(mTempRect);
                width += mTempRect.left + mTempRect.right;
            }
            mPopupWindow.setWidth(width);
        }

        @Override
        protected int getTextOffset() {
            return (mTextView.getSelectionStart() + mTextView.getSelectionStart()) / 2;
        }

        @Override
        protected int getVerticalLocalPosition(int line) {
            return mTextView.getLayout().getLineBottom(line) - mContainerMarginTop;
        }

        @Override
        protected int clipVertically(int positionY) {
            final int height = mContentView.getMeasuredHeight();
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            return Math.min(positionY, displayMetrics.heightPixels - height);
        }

        private void hideWithCleanUp() {
            for (final SuggestionInfo info : mSuggestionInfos) {
                info.clear();
            }
            mMisspelledSpanInfo.clear();
            hide();
        }

        private boolean updateSuggestions() {
            Spannable spannable = (Spannable) mTextView.getText();
            mNumberOfSuggestions =
                    mSuggestionHelper.getSuggestionInfo(mSuggestionInfos, mMisspelledSpanInfo);
            if (mNumberOfSuggestions == 0 && mMisspelledSpanInfo.mSuggestionSpan == null) {
                return false;
            }

            int spanUnionStart = mTextView.getText().length();
            int spanUnionEnd = 0;

            for (int i = 0; i < mNumberOfSuggestions; i++) {
                final SuggestionSpanInfo spanInfo = mSuggestionInfos[i].mSuggestionSpanInfo;
                spanUnionStart = Math.min(spanUnionStart, spanInfo.mSpanStart);
                spanUnionEnd = Math.max(spanUnionEnd, spanInfo.mSpanEnd);
            }
            if (mMisspelledSpanInfo.mSuggestionSpan != null) {
                spanUnionStart = Math.min(spanUnionStart, mMisspelledSpanInfo.mSpanStart);
                spanUnionEnd = Math.max(spanUnionEnd, mMisspelledSpanInfo.mSpanEnd);
            }

            for (int i = 0; i < mNumberOfSuggestions; i++) {
                highlightTextDifferences(mSuggestionInfos[i], spanUnionStart, spanUnionEnd);
            }

            // Make "Add to dictionary" item visible if there is a span with the misspelled flag
            int addToDictionaryButtonVisibility = View.GONE;
            if (mMisspelledSpanInfo.mSuggestionSpan != null) {
                if (mMisspelledSpanInfo.mSpanStart >= 0
                        && mMisspelledSpanInfo.mSpanEnd > mMisspelledSpanInfo.mSpanStart) {
                    addToDictionaryButtonVisibility = View.VISIBLE;
                }
            }
            mAddToDictionaryButton.setVisibility(addToDictionaryButtonVisibility);

            if (mSuggestionRangeSpan == null) mSuggestionRangeSpan = new SuggestionRangeSpan();
            final int underlineColor;
            if (mNumberOfSuggestions != 0) {
                underlineColor =
                        mSuggestionInfos[0].mSuggestionSpanInfo.mSuggestionSpan.getUnderlineColor();
            } else {
                underlineColor = mMisspelledSpanInfo.mSuggestionSpan.getUnderlineColor();
            }

            if (underlineColor == 0) {
                // Fallback on the default highlight color when the first span does not provide one
                mSuggestionRangeSpan.setBackgroundColor(mTextView.mHighlightColor);
            } else {
                final float BACKGROUND_TRANSPARENCY = 0.4f;
                final int newAlpha = (int) (Color.alpha(underlineColor) * BACKGROUND_TRANSPARENCY);
                mSuggestionRangeSpan.setBackgroundColor(
                        (underlineColor & 0x00FFFFFF) + (newAlpha << 24));
            }
            spannable.setSpan(mSuggestionRangeSpan, spanUnionStart, spanUnionEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            mSuggestionsAdapter.notifyDataSetChanged();
            return true;
        }

        private void highlightTextDifferences(SuggestionInfo suggestionInfo, int unionStart,
                int unionEnd) {
            final Spannable text = (Spannable) mTextView.getText();
            final int spanStart = suggestionInfo.mSuggestionSpanInfo.mSpanStart;
            final int spanEnd = suggestionInfo.mSuggestionSpanInfo.mSpanEnd;

            // Adjust the start/end of the suggestion span
            suggestionInfo.mSuggestionStart = spanStart - unionStart;
            suggestionInfo.mSuggestionEnd = suggestionInfo.mSuggestionStart
                    + suggestionInfo.mText.length();

            suggestionInfo.mText.setSpan(mHighlightSpan, 0, suggestionInfo.mText.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Add the text before and after the span.
            final String textAsString = text.toString();
            suggestionInfo.mText.insert(0, textAsString.substring(unionStart, spanStart));
            suggestionInfo.mText.append(textAsString.substring(spanEnd, unionEnd));
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            SuggestionInfo suggestionInfo = mSuggestionInfos[position];
            replaceWithSuggestion(suggestionInfo);
            hideWithCleanUp();
        }
    }

    /**
     * An ActionMode Callback class that is used to provide actions while in text insertion or
     * selection mode.
     *
     * The default callback provides a subset of Select All, Cut, Copy, Paste, Share and Replace
     * actions, depending on which of these this TextView supports and the current selection.
     */
    private class TextActionModeCallback extends ActionMode.Callback2 {
        private final Path mSelectionPath = new Path();
        private final RectF mSelectionBounds = new RectF();
        private final boolean mHasSelection;
        private final int mHandleHeight;

        // hasSelection：有selected的文字，应该展示selectionController的两个handleView，没有selected的文字，
        // 应该展示insertionController的handleView
        public TextActionModeCallback(boolean hasSelection) {
            mHasSelection = hasSelection;
            if (mHasSelection) {
                SelectionModifierCursorController selectionController = getSelectionController();
                if (selectionController.mStartHandle == null) {
                    // As these are for initializing selectionController, hide() must be called.
                    selectionController.initDrawables();
                    // initHandles()不但初始化了两个HandleView，而且调用了show()方法
                    selectionController.initHandles();
                    selectionController.hide();
                }
                mHandleHeight = Math.max(
                        mSelectHandleLeft.getMinimumHeight(),
                        mSelectHandleRight.getMinimumHeight());
            } else {
                InsertionPointCursorController insertionController = getInsertionController();
                if (insertionController != null) {
                    // getHandle()方法执行了相当于initDrawable和initHandle两步的操作
                    insertionController.getHandle();
                    mHandleHeight = mSelectHandleCenter.getMinimumHeight();
                } else {
                    mHandleHeight = 0;
                }
            }
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.setTitle(null);
            mode.setSubtitle(null);
            mode.setTitleOptionalHint(true);
            populateMenuWithItems(menu);

            Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                if (!customCallback.onCreateActionMode(mode, menu)) {
                    // The custom mode can choose to cancel the action mode, dismiss selection.
                    Selection.setSelection((Spannable) mTextView.getText(),
                            mTextView.getSelectionEnd());
                    return false;
                }
            }

            if (mTextView.canProcessText()) {
                mProcessTextIntentActionsHandler.onInitializeMenu(menu);
            }

            if (menu.hasVisibleItems() || mode.getCustomView() != null) {
                if (mHasSelection && !mTextView.hasTransientState()) {
                    mTextView.setHasTransientState(true);
                }
                return true;
            } else {
                return false;
            }
        }

        private Callback getCustomCallback() {
            return mHasSelection
                    ? mCustomSelectionActionModeCallback
                    : mCustomInsertionActionModeCallback;
        }

        private void populateMenuWithItems(Menu menu) {
            if (mTextView.canCut()) {
                menu.add(Menu.NONE, TextView.ID_CUT, MENU_ITEM_ORDER_CUT,
                        com.android.internal.R.string.cut)
                                .setAlphabeticShortcut('x')
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            if (mTextView.canCopy()) {
                menu.add(Menu.NONE, TextView.ID_COPY, MENU_ITEM_ORDER_COPY,
                        com.android.internal.R.string.copy)
                                .setAlphabeticShortcut('c')
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            if (mTextView.canPaste()) {
                menu.add(Menu.NONE, TextView.ID_PASTE, MENU_ITEM_ORDER_PASTE,
                        com.android.internal.R.string.paste)
                                .setAlphabeticShortcut('v')
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            if (mTextView.canShare()) {
                menu.add(Menu.NONE, TextView.ID_SHARE, MENU_ITEM_ORDER_SHARE,
                        com.android.internal.R.string.share)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            }

            if (mTextView.canRequestAutofill()) {
                final String selected = mTextView.getSelectedText();
                if (selected == null || selected.isEmpty()) {
                    menu.add(Menu.NONE, TextView.ID_AUTOFILL, MENU_ITEM_ORDER_AUTOFILL,
                            com.android.internal.R.string.autofill)
                            .setShowAsAction(MenuItem.SHOW_AS_OVERFLOW_ALWAYS);
                }
            }

            if (mTextView.canPasteAsPlainText()) {
                menu.add(
                        Menu.NONE,
                        TextView.ID_PASTE_AS_PLAIN_TEXT,
                        MENU_ITEM_ORDER_PASTE_AS_PLAIN_TEXT,
                        com.android.internal.R.string.paste_as_plain_text)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            }

            updateSelectAllItem(menu);
            updateReplaceItem(menu);
            updateAssistMenuItem(menu);
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            updateSelectAllItem(menu);
            updateReplaceItem(menu);
            updateAssistMenuItem(menu);

            Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                return customCallback.onPrepareActionMode(mode, menu);
            }
            return true;
        }

        private void updateSelectAllItem(Menu menu) {
            boolean canSelectAll = mTextView.canSelectAllText();
            boolean selectAllItemExists = menu.findItem(TextView.ID_SELECT_ALL) != null;
            if (canSelectAll && !selectAllItemExists) {
                menu.add(Menu.NONE, TextView.ID_SELECT_ALL, MENU_ITEM_ORDER_SELECT_ALL,
                        com.android.internal.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            } else if (!canSelectAll && selectAllItemExists) {
                menu.removeItem(TextView.ID_SELECT_ALL);
            }
        }

        private void updateReplaceItem(Menu menu) {
            boolean canReplace = mTextView.isSuggestionsEnabled() && shouldOfferToShowSuggestions();
            boolean replaceItemExists = menu.findItem(TextView.ID_REPLACE) != null;
            if (canReplace && !replaceItemExists) {
                menu.add(Menu.NONE, TextView.ID_REPLACE, MENU_ITEM_ORDER_REPLACE,
                        com.android.internal.R.string.replace)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            } else if (!canReplace && replaceItemExists) {
                menu.removeItem(TextView.ID_REPLACE);
            }
        }

        private void updateAssistMenuItem(Menu menu) {
            menu.removeItem(TextView.ID_ASSIST);
            final TextClassification textClassification =
                    getSelectionActionModeHelper().getTextClassification();
            if (textClassification != null) {
                final Drawable icon = textClassification.getIcon();
                final CharSequence label = textClassification.getLabel();
                final OnClickListener onClickListener =
                        textClassification.getOnClickListener();
                final Intent intent = textClassification.getIntent();
                if ((icon != null || !TextUtils.isEmpty(label))
                        && (onClickListener != null || intent != null)) {
                    menu.add(TextView.ID_ASSIST, TextView.ID_ASSIST, MENU_ITEM_ORDER_ASSIST, label)
                            .setIcon(icon)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    mMetricsLogger.write(
                            new LogMaker(MetricsEvent.TEXT_SELECTION_MENU_ITEM_ASSIST)
                                    .setType(MetricsEvent.TYPE_OPEN)
                                    .setSubtype(textClassification.getLogType()));
                }
            }
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            // 这个方法内部就是打印了一个log
            getSelectionActionModeHelper().onSelectionAction();

            if (mProcessTextIntentActionsHandler.performMenuItemAction(item)) {
                return true;
            }
            Callback customCallback = getCustomCallback();
            if (customCallback != null && customCallback.onActionItemClicked(mode, item)) {
                return true;
            }
            final TextClassification textClassification =
                    getSelectionActionModeHelper().getTextClassification();
            if (TextView.ID_ASSIST == item.getItemId() && textClassification != null) {
                final OnClickListener onClickListener =
                        textClassification.getOnClickListener();
                if (onClickListener != null) {
                    onClickListener.onClick(mTextView);
                } else {
                    final Intent intent = textClassification.getIntent();
                    if (intent != null) {
                        TextClassification.createStartActivityOnClickListener(
                                mTextView.getContext(), intent)
                                .onClick(mTextView);
                    }
                }
                mMetricsLogger.action(
                        MetricsEvent.ACTION_TEXT_SELECTION_MENU_ITEM_ASSIST,
                        textClassification.getLogType());
                stopTextActionMode();
                return true;
            }
            return mTextView.onTextContextMenuItem(item.getItemId());
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // Clear mTextActionMode not to recursively destroy action mode by clearing selection.
            getSelectionActionModeHelper().onDestroyActionMode();
            mTextActionMode = null;
            Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                customCallback.onDestroyActionMode(mode);
            }

            if (!mPreserveSelection) {
                /*
                 * Leave current selection when we tentatively destroy action mode for the
                 * selection. If we're detaching from a window, we'll bring back the selection
                 * mode when (if) we get reattached.
                 */
                Selection.setSelection((Spannable) mTextView.getText(),
                        mTextView.getSelectionEnd());
            }

            // 从这可以看出FloatingToolbarPopupWindow的开启并不是在Editor里，所以在actionMode销毁的时候
            // 只需要将SelectionHandleView隐藏即可。insertionHandleView因为会自动消失，所以不需要在这里
            // 隐藏。
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.hide();
            }
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (!view.equals(mTextView) || mTextView.getLayout() == null) {
                super.onGetContentRect(mode, view, outRect);
                return;
            }
            if (mTextView.getSelectionStart() != mTextView.getSelectionEnd()) {
                // We have a selection.
                mSelectionPath.reset();
                mTextView.getLayout().getSelectionPath(
                        mTextView.getSelectionStart(), mTextView.getSelectionEnd(), mSelectionPath);
                mSelectionPath.computeBounds(mSelectionBounds, true);
                mSelectionBounds.bottom += mHandleHeight;
            } else if (mCursorCount == 2) {
                // We have a split cursor. In this case, we take the rectangle that includes both
                // parts of the cursor to ensure we don't obscure either of them.
                Rect firstCursorBounds = mCursorDrawable[0].getBounds();
                Rect secondCursorBounds = mCursorDrawable[1].getBounds();
                mSelectionBounds.set(
                        Math.min(firstCursorBounds.left, secondCursorBounds.left),
                        Math.min(firstCursorBounds.top, secondCursorBounds.top),
                        Math.max(firstCursorBounds.right, secondCursorBounds.right),
                        Math.max(firstCursorBounds.bottom, secondCursorBounds.bottom)
                                + mHandleHeight);
            } else {
                // We have a single cursor.
                Layout layout = mTextView.getLayout();
                int line = layout.getLineForOffset(mTextView.getSelectionStart());
                float primaryHorizontal = clampHorizontalPosition(null,
                        layout.getPrimaryHorizontal(mTextView.getSelectionStart()));
                mSelectionBounds.set(
                        primaryHorizontal,
                        layout.getLineTop(line),
                        primaryHorizontal,
                        layout.getLineTop(line + 1) + mHandleHeight);
            }
            // Take TextView's padding and scroll into account.
            int textHorizontalOffset = mTextView.viewportToContentHorizontalOffset();
            int textVerticalOffset = mTextView.viewportToContentVerticalOffset();
            outRect.set(
                    (int) Math.floor(mSelectionBounds.left + textHorizontalOffset),
                    (int) Math.floor(mSelectionBounds.top + textVerticalOffset),
                    (int) Math.ceil(mSelectionBounds.right + textHorizontalOffset),
                    (int) Math.ceil(mSelectionBounds.bottom + textVerticalOffset));
        }
    }

    /**
     * A listener to call {@link InputMethodManager#updateCursorAnchorInfo(View, CursorAnchorInfo)}
     * while the input method is requesting the cursor/anchor position. Does nothing as long as
     * {@link InputMethodManager#isWatchingCursor(View)} returns false.
     */
    private final class CursorAnchorInfoNotifier implements TextViewPositionListener {
        final CursorAnchorInfo.Builder mSelectionInfoBuilder = new CursorAnchorInfo.Builder();
        final int[] mTmpIntOffset = new int[2];
        final Matrix mViewToScreenMatrix = new Matrix();

        @Override
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            final InputMethodState ims = mInputMethodState;
            if (ims == null || ims.mBatchEditNesting > 0) {
                return;
            }
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (null == imm) {
                return;
            }
            if (!imm.isActive(mTextView)) {
                return;
            }
            // Skip if the IME has not requested the cursor/anchor position.
            if (!imm.isCursorAnchorInfoEnabled()) {
                return;
            }
            Layout layout = mTextView.getLayout();
            if (layout == null) {
                return;
            }

            final CursorAnchorInfo.Builder builder = mSelectionInfoBuilder;
            builder.reset();

            final int selectionStart = mTextView.getSelectionStart();
            builder.setSelectionRange(selectionStart, mTextView.getSelectionEnd());

            // Construct transformation matrix from view local coordinates to screen coordinates.
            mViewToScreenMatrix.set(mTextView.getMatrix());
            mTextView.getLocationOnScreen(mTmpIntOffset);
            mViewToScreenMatrix.postTranslate(mTmpIntOffset[0], mTmpIntOffset[1]);
            builder.setMatrix(mViewToScreenMatrix);

            final float viewportToContentHorizontalOffset =
                    mTextView.viewportToContentHorizontalOffset();
            final float viewportToContentVerticalOffset =
                    mTextView.viewportToContentVerticalOffset();

            final CharSequence text = mTextView.getText();
            if (text instanceof Spannable) {
                final Spannable sp = (Spannable) text;
                int composingTextStart = EditableInputConnection.getComposingSpanStart(sp);
                int composingTextEnd = EditableInputConnection.getComposingSpanEnd(sp);
                if (composingTextEnd < composingTextStart) {
                    final int temp = composingTextEnd;
                    composingTextEnd = composingTextStart;
                    composingTextStart = temp;
                }
                final boolean hasComposingText =
                        (0 <= composingTextStart) && (composingTextStart < composingTextEnd);
                if (hasComposingText) {
                    final CharSequence composingText = text.subSequence(composingTextStart,
                            composingTextEnd);
                    builder.setComposingText(composingTextStart, composingText);
                    mTextView.populateCharacterBounds(builder, composingTextStart,
                            composingTextEnd, viewportToContentHorizontalOffset,
                            viewportToContentVerticalOffset);
                }
            }

            // Treat selectionStart as the insertion point.
            if (0 <= selectionStart) {
                final int offset = selectionStart;
                final int line = layout.getLineForOffset(offset);
                final float insertionMarkerX = layout.getPrimaryHorizontal(offset)
                        + viewportToContentHorizontalOffset;
                final float insertionMarkerTop = layout.getLineTop(line)
                        + viewportToContentVerticalOffset;
                final float insertionMarkerBaseline = layout.getLineBaseline(line)
                        + viewportToContentVerticalOffset;
                final float insertionMarkerBottom = layout.getLineBottom(line)
                        + viewportToContentVerticalOffset;
                final boolean isTopVisible = mTextView
                        .isPositionVisible(insertionMarkerX, insertionMarkerTop);
                final boolean isBottomVisible = mTextView
                        .isPositionVisible(insertionMarkerX, insertionMarkerBottom);
                int insertionMarkerFlags = 0;
                if (isTopVisible || isBottomVisible) {
                    insertionMarkerFlags |= CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION;
                }
                if (!isTopVisible || !isBottomVisible) {
                    insertionMarkerFlags |= CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
                }
                if (layout.isRtlCharAt(offset)) {
                    insertionMarkerFlags |= CursorAnchorInfo.FLAG_IS_RTL;
                }
                builder.setInsertionMarkerLocation(insertionMarkerX, insertionMarkerTop,
                        insertionMarkerBaseline, insertionMarkerBottom, insertionMarkerFlags);
            }

            imm.updateCursorAnchorInfo(mTextView, builder.build());
        }
    }

    @VisibleForTesting
    public abstract class HandleView extends View implements TextViewPositionListener {
        protected Drawable mDrawable;
        protected Drawable mDrawableLtr;
        protected Drawable mDrawableRtl;
        private final android.widget.PopupWindow mContainer;
        // Position with respect to the parent TextView
        private int mPositionX, mPositionY;
        private boolean mIsDragging;
        // Offset from touch position to mPosition
        private float mTouchToWindowOffsetX, mTouchToWindowOffsetY;
        protected int mHotspotX;
        protected int mHorizontalGravity;
        // Offsets the hotspot point up, so that cursor is not hidden by the finger when moving up
        private float mTouchOffsetY;
        // Where the touch position should be on the handle to ensure a maximum cursor visibility
        private float mIdealVerticalOffset;
        // Parent's (TextView) previous position in window
        private int mLastParentX, mLastParentY;
        // Parent's (TextView) previous position on screen
        private int mLastParentXOnScreen, mLastParentYOnScreen;
        // Previous text character offset
        protected int mPreviousOffset = -1;
        // Previous text character offset
        private boolean mPositionHasChanged = true;
        // Minimum touch target size for handles
        private int mMinSize;
        // Indicates the line of text that the handle is on.
        protected int mPrevLine = UNSET_LINE;
        // Indicates the line of text that the user was touching. This can differ from mPrevLine
        // when selecting text when the handles jump to the end / start of words which may be on
        // a different line.
        protected int mPreviousLineTouched = UNSET_LINE;

        private HandleView(Drawable drawableLtr, Drawable drawableRtl, final int id) {
            super(mTextView.getContext());
            setId(id);
            mContainer = new android.widget.PopupWindow(mTextView.getContext(), null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mContainer.setSplitTouchEnabled(true);
            mContainer.setClippingEnabled(false);
            mContainer.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            mContainer.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mContainer.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            mContainer.setContentView(this);

            mDrawableLtr = drawableLtr;
            mDrawableRtl = drawableRtl;
            mMinSize = mTextView.getContext().getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.text_handle_min_size);

            updateDrawable();

            final int handleHeight = getPreferredHeight();
            mTouchOffsetY = -0.3f * handleHeight;
            mIdealVerticalOffset = 0.7f * handleHeight;
        }

        public float getIdealVerticalOffset() {
            return mIdealVerticalOffset;
        }

        protected void updateDrawable() {
            if (mIsDragging) {
                // Don't update drawable during dragging.
                return;
            }
            final Layout layout = mTextView.getLayout();
            if (layout == null) {
                return;
            }
            final int offset = getCurrentCursorOffset();
            final boolean isRtlCharAtOffset = isAtRtlRun(layout, offset);
            final Drawable oldDrawable = mDrawable;
            mDrawable = isRtlCharAtOffset ? mDrawableRtl : mDrawableLtr;
            mHotspotX = getHotspotX(mDrawable, isRtlCharAtOffset);
            mHorizontalGravity = getHorizontalGravity(isRtlCharAtOffset);
            if (oldDrawable != mDrawable && isShowing()) {
                // Update popup window position.
                // 光标的位置
                mPositionX = getCursorHorizontalPosition(layout, offset) - mHotspotX
                        - getHorizontalOffset() + getCursorOffset();
                mPositionX += mTextView.viewportToContentHorizontalOffset();
                mPositionHasChanged = true;
                updatePosition(mLastParentX, mLastParentY, false, false);
                postInvalidate();
            }
        }

        protected abstract int getHotspotX(Drawable drawable, boolean isRtlRun);
        protected abstract int getHorizontalGravity(boolean isRtlRun);

        // Touch-up filter: number of previous positions remembered
        private static final int HISTORY_SIZE = 5;
        private static final int TOUCH_UP_FILTER_DELAY_AFTER = 150;
        private static final int TOUCH_UP_FILTER_DELAY_BEFORE = 350;
        private final long[] mPreviousOffsetsTimes = new long[HISTORY_SIZE];
        private final int[] mPreviousOffsets = new int[HISTORY_SIZE];
        private int mPreviousOffsetIndex = 0;
        private int mNumberPreviousOffsets = 0;

        private void startTouchUpFilter(int offset) {
            mNumberPreviousOffsets = 0;
            addPositionToTouchUpFilter(offset);
        }

        private void addPositionToTouchUpFilter(int offset) {
            mPreviousOffsetIndex = (mPreviousOffsetIndex + 1) % HISTORY_SIZE;
            mPreviousOffsets[mPreviousOffsetIndex] = offset;
            mPreviousOffsetsTimes[mPreviousOffsetIndex] = SystemClock.uptimeMillis();
            mNumberPreviousOffsets++;
        }

        private void filterOnTouchUp() {
            final long now = SystemClock.uptimeMillis();
            int i = 0;
            int index = mPreviousOffsetIndex;
            final int iMax = Math.min(mNumberPreviousOffsets, HISTORY_SIZE);
            while (i < iMax && (now - mPreviousOffsetsTimes[index]) < TOUCH_UP_FILTER_DELAY_AFTER) {
                i++;
                index = (mPreviousOffsetIndex - i + HISTORY_SIZE) % HISTORY_SIZE;
            }

            if (i > 0 && i < iMax
                    && (now - mPreviousOffsetsTimes[index]) > TOUCH_UP_FILTER_DELAY_BEFORE) {
                positionAtCursorOffset(mPreviousOffsets[index], false);
            }
        }

        public boolean offsetHasBeenChanged() {
            return mNumberPreviousOffsets > 1;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(getPreferredWidth(), getPreferredHeight());
        }

        @Override
        public void invalidate() {
            super.invalidate();
            if (isShowing()) {
                positionAtCursorOffset(getCurrentCursorOffset(), true);
            }
        }

        private int getPreferredWidth() {
            return Math.max(mDrawable.getIntrinsicWidth(), mMinSize);
        }

        private int getPreferredHeight() {
            return Math.max(mDrawable.getIntrinsicHeight(), mMinSize);
        }

        /*
         * handleView的show流程：
         * 1.注册TextView的onPreDrawListener监听
         * 2.在TextView每次绘制之前，首先更新textView的位置，然后根据selection更新handleView的坐标，
         * 最后根据上面两者的坐标实际更新handleView的位置。
         *
         * 所以说handleView的show方法只与handleView自身的展示有关系，与其他都无关。
         */
        public void show() {
            if (isShowing()) return;

            getPositionListener().addSubscriber(this, true /* local position may change */);

            // Make sure the offset is always considered new, even when focusing at same position
            mPreviousOffset = -1;
            positionAtCursorOffset(getCurrentCursorOffset(), false);
        }

        protected void dismiss() {
            mIsDragging = false;
            mContainer.dismiss();
            onDetached();
        }

        public void hide() {
            dismiss();

            getPositionListener().removeSubscriber(this);
        }

        public boolean isShowing() {
            return mContainer.isShowing();
        }

        private boolean isVisible() {
            // Always show a dragging handle.
            if (mIsDragging) {
                return true;
            }

            if (mTextView.isInBatchEditMode()) {
                return false;
            }

            return mTextView.isPositionVisible(
                    mPositionX + mHotspotX + getHorizontalOffset(), mPositionY);
        }

        public abstract int getCurrentCursorOffset();

        protected abstract void updateSelection(int offset);

        public abstract void updatePosition(float x, float y);

        protected boolean isAtRtlRun(@NonNull Layout layout, int offset) {
            return layout.isRtlCharAt(offset);
        }

        @VisibleForTesting
        public float getHorizontal(@NonNull Layout layout, int offset) {
            return layout.getPrimaryHorizontal(offset);
        }

        protected int getOffsetAtCoordinate(@NonNull Layout layout, int line, float x) {
            return mTextView.getOffsetAtCoordinate(line, x);
        }

        /**
         * @param offset Cursor offset. Must be in [-1, length].
         * @param forceUpdatePosition whether to force update the position.  This should be true
         * when If the parent has been scrolled, for example.
         */
        /*
         * 主要是当Selection发生变化的时候，更新handleView的位置，即handleView的positionX和positionY.
         *
         * 位置变化的链条是：
         * 手指点击位置（down事件） -> selection变化 -> handleView变化
         *
         * offset变化的时候，也会同步更新Selection。
         *
         * 1.这里的cursor指的就是相应的handleView。比如selectionStartHandleView，cursorOffset指的就是
         * startHandleView所在位置的offset，即Selection.getSelectionStart()方法返回的offset
         *
         * update the position of HandleView according to the current selection
         * 把handleView放到当前cursor的位置，但是没有真的放，只是把坐标更新了。
         */
        protected void positionAtCursorOffset(int offset, boolean forceUpdatePosition) {
            // A HandleView relies on the layout, which may be nulled by external methods
            Layout layout = mTextView.getLayout();
            if (layout == null) {
                // Will update controllers' state, hiding them and stopping selection mode if needed
                prepareCursorControllers();
                return;
            }
            layout = mTextView.getLayout();

            boolean offsetChanged = offset != mPreviousOffset;
            if (offsetChanged || forceUpdatePosition) {
                if (offsetChanged) {
                    updateSelection(offset);
                    addPositionToTouchUpFilter(offset);
                }
                final int line = layout.getLineForOffset(offset);
                mPrevLine = line;

                mPositionX = getCursorHorizontalPosition(layout, offset) - mHotspotX
                        - getHorizontalOffset() + getCursorOffset();
                mPositionY = layout.getLineBottom(line);

                // Take TextView's padding and scroll into account.
                mPositionX += mTextView.viewportToContentHorizontalOffset();
                mPositionY += mTextView.viewportToContentVerticalOffset();

                mPreviousOffset = offset;
                mPositionHasChanged = true;
            }
        }

        /**
         * Return the clamped horizontal position for the first cursor.
         *
         * @param layout Text layout.
         * @param offset Character offset for the cursor.
         * @return The clamped horizontal position for the cursor.
         */
        int getCursorHorizontalPosition(Layout layout, int offset) {
            return (int) (getHorizontal(layout, offset) - 0.5f);
        }

        @Override
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            positionAtCursorOffset(getCurrentCursorOffset(), parentScrolled);
            /*
             * 1.mPositionHasChanged指的是handleView的位置是否发生改变，这个值由上面刚刚调用的方法
             * positionAtCursorOffset()决定。
             * 2.如果TextView位置或者handleView位置发生了变化，在下面的代码中，真正更新handleView在屏幕
             * 上的位置
             */
            if (parentPositionChanged || mPositionHasChanged) {
                if (mIsDragging) {
                    // Update touchToWindow offset in case of parent scrolling while dragging
                    if (parentPositionX != mLastParentX || parentPositionY != mLastParentY) {
                        mTouchToWindowOffsetX += parentPositionX - mLastParentX;
                        mTouchToWindowOffsetY += parentPositionY - mLastParentY;
                        mLastParentX = parentPositionX;
                        mLastParentY = parentPositionY;
                    }

                    onHandleMoved();
                }

                if (isVisible()) {
                    // Transform to the window coordinates to follow the view tranformation.
                    final int[] pts = { mPositionX + mHotspotX + getHorizontalOffset(), mPositionY};
                    mTextView.transformFromViewToWindowSpace(pts);
                    pts[0] -= mHotspotX + getHorizontalOffset();

                    if (isShowing()) {
                        mContainer.update(pts[0], pts[1], -1, -1);
                    } else {
                        mContainer.showAtLocation(mTextView, Gravity.NO_GRAVITY, pts[0], pts[1]);
                    }
                } else {
                    if (isShowing()) {
                        dismiss();
                    }
                }

                mPositionHasChanged = false;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            final int drawWidth = mDrawable.getIntrinsicWidth();
            final int left = getHorizontalOffset();

            mDrawable.setBounds(left, 0, left + drawWidth, mDrawable.getIntrinsicHeight());
            mDrawable.draw(c);
        }

        private int getHorizontalOffset() {
            final int width = getPreferredWidth();
            final int drawWidth = mDrawable.getIntrinsicWidth();
            final int left;
            switch (mHorizontalGravity) {
                case Gravity.LEFT:
                    left = 0;
                    break;
                default:
                case Gravity.CENTER:
                    left = (width - drawWidth) / 2;
                    break;
                case Gravity.RIGHT:
                    left = width - drawWidth;
                    break;
            }
            return left;
        }

        protected int getCursorOffset() {
            return 0;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            updateFloatingToolbarVisibility(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    startTouchUpFilter(getCurrentCursorOffset());

                    final PositionListener positionListener = getPositionListener();
                    mLastParentX = positionListener.getPositionX();
                    mLastParentY = positionListener.getPositionY();
                    mLastParentXOnScreen = positionListener.getPositionXOnScreen();
                    mLastParentYOnScreen = positionListener.getPositionYOnScreen();

                    final float xInWindow = ev.getRawX() - mLastParentXOnScreen + mLastParentX;
                    final float yInWindow = ev.getRawY() - mLastParentYOnScreen + mLastParentY;
                    mTouchToWindowOffsetX = xInWindow - mPositionX;
                    mTouchToWindowOffsetY = yInWindow - mPositionY;

                    mIsDragging = true;
                    mPreviousLineTouched = UNSET_LINE;
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    final float xInWindow = ev.getRawX() - mLastParentXOnScreen + mLastParentX;
                    final float yInWindow = ev.getRawY() - mLastParentYOnScreen + mLastParentY;

                    // Vertical hysteresis: vertical down movement tends to snap to ideal offset
                    final float previousVerticalOffset = mTouchToWindowOffsetY - mLastParentY;
                    final float currentVerticalOffset = yInWindow - mPositionY - mLastParentY;
                    float newVerticalOffset;
                    if (previousVerticalOffset < mIdealVerticalOffset) {
                        newVerticalOffset = Math.min(currentVerticalOffset, mIdealVerticalOffset);
                        newVerticalOffset = Math.max(newVerticalOffset, previousVerticalOffset);
                    } else {
                        newVerticalOffset = Math.max(currentVerticalOffset, mIdealVerticalOffset);
                        newVerticalOffset = Math.min(newVerticalOffset, previousVerticalOffset);
                    }
                    mTouchToWindowOffsetY = newVerticalOffset + mLastParentY;

                    final float newPosX =
                            xInWindow - mTouchToWindowOffsetX + mHotspotX + getHorizontalOffset();
                    final float newPosY = yInWindow - mTouchToWindowOffsetY + mTouchOffsetY;

                    updatePosition(newPosX, newPosY);
                    break;
                }

                case MotionEvent.ACTION_UP:
                    filterOnTouchUp();
                    mIsDragging = false;
                    updateDrawable();
                    break;

                case MotionEvent.ACTION_CANCEL:
                    mIsDragging = false;
                    updateDrawable();
                    break;
            }
            return true;
        }

        public boolean isDragging() {
            return mIsDragging;
        }

        void onHandleMoved() {}

        public void onDetached() {}
    }

    private class InsertionHandleView extends HandleView {
        private static final int DELAY_BEFORE_HANDLE_FADES_OUT = 4000;
        private static final int RECENT_CUT_COPY_DURATION = 15 * 1000; // seconds

        // Used to detect taps on the insertion handle, which will affect the insertion action mode
        private float mDownPositionX, mDownPositionY;
        private Runnable mHider;

        public InsertionHandleView(Drawable drawable) {
            super(drawable, drawable, com.android.internal.R.id.insertion_handle);
        }

        @Override
        public void show() {
            super.show();

            final long durationSinceCutOrCopy =
                    SystemClock.uptimeMillis() - TextView.sLastCutCopyOrTextChangedTime;

            // Cancel the single tap delayed runnable.
            if (mInsertionActionModeRunnable != null
                    && ((mTapState == TAP_STATE_DOUBLE_TAP)
                            || (mTapState == TAP_STATE_TRIPLE_CLICK)
                            || isCursorInsideEasyCorrectionSpan())) {
                mTextView.removeCallbacks(mInsertionActionModeRunnable);
            }
            // todo 这里什么情况下会调用？
            // Prepare and schedule the single tap runnable to run exactly after the double tap
            // timeout has passed.
            if ((mTapState != TAP_STATE_DOUBLE_TAP) && (mTapState != TAP_STATE_TRIPLE_CLICK)
                    && !isCursorInsideEasyCorrectionSpan()
                    && (durationSinceCutOrCopy < RECENT_CUT_COPY_DURATION)) {
                if (mTextActionMode == null) {
                    if (mInsertionActionModeRunnable == null) {
                        mInsertionActionModeRunnable = new Runnable() {
                            @Override
                            public void run() {
                                startInsertionActionMode();
                            }
                        };
                    }
                    mTextView.postDelayed(
                            mInsertionActionModeRunnable,
                            ViewConfiguration.getDoubleTapTimeout() + 1);
                }

            }

            hideAfterDelay();
        }

        private void hideAfterDelay() {
            if (mHider == null) {
                mHider = new Runnable() {
                    public void run() {
                        hide();
                    }
                };
            } else {
                removeHiderCallback();
            }
            mTextView.postDelayed(mHider, DELAY_BEFORE_HANDLE_FADES_OUT);
        }

        private void removeHiderCallback() {
            if (mHider != null) {
                mTextView.removeCallbacks(mHider);
            }
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            return drawable.getIntrinsicWidth() / 2;
        }

        @Override
        protected int getHorizontalGravity(boolean isRtlRun) {
            return Gravity.CENTER_HORIZONTAL;
        }

        @Override
        protected int getCursorOffset() {
            int offset = super.getCursorOffset();
            final Drawable cursor = mCursorCount > 0 ? mCursorDrawable[0] : null;
            if (cursor != null) {
                cursor.getPadding(mTempRect);
                offset += (cursor.getIntrinsicWidth() - mTempRect.left - mTempRect.right) / 2;
            }
            return offset;
        }

        @Override
        int getCursorHorizontalPosition(Layout layout, int offset) {
            final Drawable drawable = mCursorCount > 0 ? mCursorDrawable[0] : null;
            if (drawable != null) {
                final float horizontal = getHorizontal(layout, offset);
                return clampHorizontalPosition(drawable, horizontal) + mTempRect.left;
            }
            return super.getCursorHorizontalPosition(layout, offset);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            final boolean result = super.onTouchEvent(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownPositionX = ev.getRawX();
                    mDownPositionY = ev.getRawY();
                    break;

                case MotionEvent.ACTION_UP:
                    if (!offsetHasBeenChanged()) {
                        final float deltaX = mDownPositionX - ev.getRawX();
                        final float deltaY = mDownPositionY - ev.getRawY();
                        final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                        final ViewConfiguration viewConfiguration = ViewConfiguration.get(
                                mTextView.getContext());
                        final int touchSlop = viewConfiguration.getScaledTouchSlop();

                        if (distanceSquared < touchSlop * touchSlop) {
                            // Tapping on the handle toggles the insertion action mode.
                            if (mTextActionMode != null) {
                                stopTextActionMode();
                            } else {
                                startInsertionActionMode();
                            }
                        }
                    } else {
                        if (mTextActionMode != null) {
                            mTextActionMode.invalidateContentRect();
                        }
                    }
                    hideAfterDelay();
                    break;

                case MotionEvent.ACTION_CANCEL:
                    hideAfterDelay();
                    break;

                default:
                    break;
            }

            return result;
        }

        @Override
        public int getCurrentCursorOffset() {
            return mTextView.getSelectionStart();
        }

        @Override
        public void updateSelection(int offset) {
            Selection.setSelection((Spannable) mTextView.getText(), offset);
        }

        @Override
        public void updatePosition(float x, float y) {
            Layout layout = mTextView.getLayout();
            int offset;
            if (layout != null) {
                if (mPreviousLineTouched == UNSET_LINE) {
                    mPreviousLineTouched = mTextView.getLineAtCoordinate(y);
                }
                int currLine = getCurrentLineAdjustedForSlop(layout, mPreviousLineTouched, y);
                offset = getOffsetAtCoordinate(layout, currLine, x);
                mPreviousLineTouched = currLine;
            } else {
                offset = -1;
            }
            positionAtCursorOffset(offset, false);
            if (mTextActionMode != null) {
                invalidateActionMode();
            }
        }

        @Override
        void onHandleMoved() {
            super.onHandleMoved();
            removeHiderCallback();
        }

        @Override
        public void onDetached() {
            super.onDetached();
            removeHiderCallback();
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HANDLE_TYPE_SELECTION_START, HANDLE_TYPE_SELECTION_END})
    public @interface HandleType {}
    public static final int HANDLE_TYPE_SELECTION_START = 0;
    public static final int HANDLE_TYPE_SELECTION_END = 1;

    private class SelectionHandleView extends HandleView {
        // Indicates the handle type, selection start (HANDLE_TYPE_SELECTION_START) or selection
        // end (HANDLE_TYPE_SELECTION_END).
        @HandleType
        private final int mHandleType;
        // Indicates whether the cursor is making adjustments within a word.
        private boolean mInWord = false;
        // Difference between touch position and word boundary position.
        private float mTouchWordDelta;
        // X value of the previous updatePosition call.
        private float mPrevX;
        // Indicates if the handle has moved a boundary between LTR and RTL text.
        private boolean mLanguageDirectionChanged = false;
        // Distance from edge of horizontally scrolling text view
        // to use to switch to character mode.
        private final float mTextViewEdgeSlop;
        // Used to save text view location.
        private final int[] mTextViewLocation = new int[2];

        public SelectionHandleView(Drawable drawableLtr, Drawable drawableRtl, int id,
                @HandleType int handleType) {
            super(drawableLtr, drawableRtl, id);
            mHandleType = handleType;
            ViewConfiguration viewConfiguration = ViewConfiguration.get(mTextView.getContext());
            mTextViewEdgeSlop = viewConfiguration.getScaledTouchSlop() * 4;
        }

        private boolean isStartHandle() {
            return mHandleType == HANDLE_TYPE_SELECTION_START;
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            if (isRtlRun == isStartHandle()) {
                return drawable.getIntrinsicWidth() / 4;
            } else {
                return (drawable.getIntrinsicWidth() * 3) / 4;
            }
        }

        @Override
        protected int getHorizontalGravity(boolean isRtlRun) {
            return (isRtlRun == isStartHandle()) ? Gravity.LEFT : Gravity.RIGHT;
        }

        @Override
        public int getCurrentCursorOffset() {
            return isStartHandle() ? mTextView.getSelectionStart() : mTextView.getSelectionEnd();
        }

        @Override
        protected void updateSelection(int offset) {
            if (isStartHandle()) {
                Selection.setSelection((Spannable) mTextView.getText(), offset,
                        mTextView.getSelectionEnd());
            } else {
                Selection.setSelection((Spannable) mTextView.getText(),
                        mTextView.getSelectionStart(), offset);
            }
            updateDrawable();
            if (mTextActionMode != null) {
                invalidateActionMode();
            }
        }

        @Override
        public void updatePosition(float x, float y) {
            final Layout layout = mTextView.getLayout();
            if (layout == null) {
                // HandleView will deal appropriately in positionAtCursorOffset when
                // layout is null.
                positionAndAdjustForCrossingHandles(mTextView.getOffsetForPosition(x, y));
                return;
            }

            if (mPreviousLineTouched == UNSET_LINE) {
                mPreviousLineTouched = mTextView.getLineAtCoordinate(y);
            }

            boolean positionCursor = false;
            final int anotherHandleOffset =
                    isStartHandle() ? mTextView.getSelectionEnd() : mTextView.getSelectionStart();
            int currLine = getCurrentLineAdjustedForSlop(layout, mPreviousLineTouched, y);
            int initialOffset = getOffsetAtCoordinate(layout, currLine, x);

            if (isStartHandle() && initialOffset >= anotherHandleOffset
                    || !isStartHandle() && initialOffset <= anotherHandleOffset) {
                // Handles have crossed, bound it to the first selected line and
                // adjust by word / char as normal.
                currLine = layout.getLineForOffset(anotherHandleOffset);
                initialOffset = getOffsetAtCoordinate(layout, currLine, x);
            }

            int offset = initialOffset;
            final int wordEnd = getWordEnd(offset);
            final int wordStart = getWordStart(offset);

            if (mPrevX == UNSET_X_VALUE) {
                mPrevX = x;
            }

            final int currentOffset = getCurrentCursorOffset();
            final boolean rtlAtCurrentOffset = isAtRtlRun(layout, currentOffset);
            final boolean atRtl = isAtRtlRun(layout, offset);
            final boolean isLvlBoundary = layout.isLevelBoundary(offset);

            // We can't determine if the user is expanding or shrinking the selection if they're
            // on a bi-di boundary, so until they've moved past the boundary we'll just place
            // the cursor at the current position.
            if (isLvlBoundary || (rtlAtCurrentOffset && !atRtl) || (!rtlAtCurrentOffset && atRtl)) {
                // We're on a boundary or this is the first direction change -- just update
                // to the current position.
                mLanguageDirectionChanged = true;
                mTouchWordDelta = 0.0f;
                positionAndAdjustForCrossingHandles(offset);
                return;
            } else if (mLanguageDirectionChanged && !isLvlBoundary) {
                // We've just moved past the boundary so update the position. After this we can
                // figure out if the user is expanding or shrinking to go by word or character.
                positionAndAdjustForCrossingHandles(offset);
                mTouchWordDelta = 0.0f;
                mLanguageDirectionChanged = false;
                return;
            }

            boolean isExpanding;
            final float xDiff = x - mPrevX;
            if (isStartHandle()) {
                isExpanding = currLine < mPreviousLineTouched;
            } else {
                isExpanding = currLine > mPreviousLineTouched;
            }
            if (atRtl == isStartHandle()) {
                isExpanding |= xDiff > 0;
            } else {
                isExpanding |= xDiff < 0;
            }

            if (mTextView.getHorizontallyScrolling()) {
                if (positionNearEdgeOfScrollingView(x, atRtl)
                        && ((isStartHandle() && mTextView.getScrollX() != 0)
                                || (!isStartHandle()
                                        && mTextView.canScrollHorizontally(atRtl ? -1 : 1)))
                        && ((isExpanding && ((isStartHandle() && offset < currentOffset)
                                || (!isStartHandle() && offset > currentOffset)))
                                        || !isExpanding)) {
                    // If we're expanding ensure that the offset is actually expanding compared to
                    // the current offset, if the handle snapped to the word, the finger position
                    // may be out of sync and we don't want the selection to jump back.
                    mTouchWordDelta = 0.0f;
                    final int nextOffset = (atRtl == isStartHandle())
                            ? layout.getOffsetToRightOf(mPreviousOffset)
                            : layout.getOffsetToLeftOf(mPreviousOffset);
                    positionAndAdjustForCrossingHandles(nextOffset);
                    return;
                }
            }

            if (isExpanding) {
                // User is increasing the selection.
                int wordBoundary = isStartHandle() ? wordStart : wordEnd;
                final boolean snapToWord = (!mInWord
                        || (isStartHandle() ? currLine < mPrevLine : currLine > mPrevLine))
                                && atRtl == isAtRtlRun(layout, wordBoundary);
                if (snapToWord) {
                    // Sometimes words can be broken across lines (Chinese, hyphenation).
                    // We still snap to the word boundary but we only use the letters on the
                    // current line to determine if the user is far enough into the word to snap.
                    if (layout.getLineForOffset(wordBoundary) != currLine) {
                        wordBoundary = isStartHandle()
                                ? layout.getLineStart(currLine) : layout.getLineEnd(currLine);
                    }
                    final int offsetThresholdToSnap = isStartHandle()
                            ? wordEnd - ((wordEnd - wordBoundary) / 2)
                            : wordStart + ((wordBoundary - wordStart) / 2);
                    if (isStartHandle()
                            && (offset <= offsetThresholdToSnap || currLine < mPrevLine)) {
                        // User is far enough into the word or on a different line so we expand by
                        // word.
                        offset = wordStart;
                    } else if (!isStartHandle()
                            && (offset >= offsetThresholdToSnap || currLine > mPrevLine)) {
                        // User is far enough into the word or on a different line so we expand by
                        // word.
                        offset = wordEnd;
                    } else {
                        offset = mPreviousOffset;
                    }
                }
                if ((isStartHandle() && offset < initialOffset)
                        || (!isStartHandle() && offset > initialOffset)) {
                    final float adjustedX = getHorizontal(layout, offset);
                    mTouchWordDelta =
                            mTextView.convertToLocalHorizontalCoordinate(x) - adjustedX;
                } else {
                    mTouchWordDelta = 0.0f;
                }
                positionCursor = true;
            } else {
                final int adjustedOffset =
                        getOffsetAtCoordinate(layout, currLine, x - mTouchWordDelta);
                final boolean shrinking = isStartHandle()
                        ? adjustedOffset > mPreviousOffset || currLine > mPrevLine
                        : adjustedOffset < mPreviousOffset || currLine < mPrevLine;
                if (shrinking) {
                    // User is shrinking the selection.
                    if (currLine != mPrevLine) {
                        // We're on a different line, so we'll snap to word boundaries.
                        offset = isStartHandle() ? wordStart : wordEnd;
                        if ((isStartHandle() && offset < initialOffset)
                                || (!isStartHandle() && offset > initialOffset)) {
                            final float adjustedX = getHorizontal(layout, offset);
                            mTouchWordDelta =
                                    mTextView.convertToLocalHorizontalCoordinate(x) - adjustedX;
                        } else {
                            mTouchWordDelta = 0.0f;
                        }
                    } else {
                        offset = adjustedOffset;
                    }
                    positionCursor = true;
                } else if ((isStartHandle() && adjustedOffset < mPreviousOffset)
                        || (!isStartHandle() && adjustedOffset > mPreviousOffset)) {
                    // Handle has jumped to the word boundary, and the user is moving
                    // their finger towards the handle, the delta should be updated.
                    mTouchWordDelta = mTextView.convertToLocalHorizontalCoordinate(x)
                            - getHorizontal(layout, mPreviousOffset);
                }
            }

            if (positionCursor) {
                mPreviousLineTouched = currLine;
                positionAndAdjustForCrossingHandles(offset);
            }
            mPrevX = x;
        }

        @Override
        protected void positionAtCursorOffset(int offset, boolean forceUpdatePosition) {
            super.positionAtCursorOffset(offset, forceUpdatePosition);
            mInWord = (offset != -1) && !getWordIteratorWithText().isBoundary(offset);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean superResult = super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Reset the touch word offset and x value when the user
                // re-engages the handle.
                mTouchWordDelta = 0.0f;
                mPrevX = UNSET_X_VALUE;
            }
            return superResult;
        }

        private void positionAndAdjustForCrossingHandles(int offset) {
            final int anotherHandleOffset =
                    isStartHandle() ? mTextView.getSelectionEnd() : mTextView.getSelectionStart();
            if ((isStartHandle() && offset >= anotherHandleOffset)
                    || (!isStartHandle() && offset <= anotherHandleOffset)) {
                mTouchWordDelta = 0.0f;
                final Layout layout = mTextView.getLayout();
                if (layout != null && offset != anotherHandleOffset) {
                    final float horiz = getHorizontal(layout, offset);
                    final float anotherHandleHoriz = getHorizontal(layout, anotherHandleOffset,
                            !isStartHandle());
                    final float currentHoriz = getHorizontal(layout, mPreviousOffset);
                    if (currentHoriz < anotherHandleHoriz && horiz < anotherHandleHoriz
                            || currentHoriz > anotherHandleHoriz && horiz > anotherHandleHoriz) {
                        // This handle passes another one as it crossed a direction boundary.
                        // Don't minimize the selection, but keep the handle at the run boundary.
                        final int currentOffset = getCurrentCursorOffset();
                        final int offsetToGetRunRange = isStartHandle()
                                ? currentOffset : Math.max(currentOffset - 1, 0);
                        final long range = layout.getRunRange(offsetToGetRunRange);
                        if (isStartHandle()) {
                            offset = TextUtils.unpackRangeStartFromLong(range);
                        } else {
                            offset = TextUtils.unpackRangeEndFromLong(range);
                        }
                        positionAtCursorOffset(offset, false);
                        return;
                    }
                }
                // Handles can not cross and selection is at least one character.
                offset = getNextCursorOffset(anotherHandleOffset, !isStartHandle());
            }
            positionAtCursorOffset(offset, false);
        }

        private boolean positionNearEdgeOfScrollingView(float x, boolean atRtl) {
            mTextView.getLocationOnScreen(mTextViewLocation);
            boolean nearEdge;
            if (atRtl == isStartHandle()) {
                int rightEdge = mTextViewLocation[0] + mTextView.getWidth()
                        - mTextView.getPaddingRight();
                nearEdge = x > rightEdge - mTextViewEdgeSlop;
            } else {
                int leftEdge = mTextViewLocation[0] + mTextView.getPaddingLeft();
                nearEdge = x < leftEdge + mTextViewEdgeSlop;
            }
            return nearEdge;
        }

        @Override
        protected boolean isAtRtlRun(@NonNull Layout layout, int offset) {
            final int offsetToCheck = isStartHandle() ? offset : Math.max(offset - 1, 0);
            return layout.isRtlCharAt(offsetToCheck);
        }

        @Override
        public float getHorizontal(@NonNull Layout layout, int offset) {
            return getHorizontal(layout, offset, isStartHandle());
        }

        private float getHorizontal(@NonNull Layout layout, int offset, boolean startHandle) {
            final int line = layout.getLineForOffset(offset);
            final int offsetToCheck = startHandle ? offset : Math.max(offset - 1, 0);
            final boolean isRtlChar = layout.isRtlCharAt(offsetToCheck);
            final boolean isRtlParagraph = layout.getParagraphDirection(line) == -1;
            return (isRtlChar == isRtlParagraph)
                    ? layout.getPrimaryHorizontal(offset) : layout.getSecondaryHorizontal(offset);
        }

        @Override
        protected int getOffsetAtCoordinate(@NonNull Layout layout, int line, float x) {
            final float localX = mTextView.convertToLocalHorizontalCoordinate(x);
            final int primaryOffset = layout.getOffsetForHorizontal(line, localX, true);
            if (!layout.isLevelBoundary(primaryOffset)) {
                return primaryOffset;
            }
            final int secondaryOffset = layout.getOffsetForHorizontal(line, localX, false);
            final int currentOffset = getCurrentCursorOffset();
            final int primaryDiff = Math.abs(primaryOffset - currentOffset);
            final int secondaryDiff = Math.abs(secondaryOffset - currentOffset);
            if (primaryDiff < secondaryDiff) {
                return primaryOffset;
            } else if (primaryDiff > secondaryDiff) {
                return secondaryOffset;
            } else {
                final int offsetToCheck = isStartHandle()
                        ? currentOffset : Math.max(currentOffset - 1, 0);
                final boolean isRtlChar = layout.isRtlCharAt(offsetToCheck);
                final boolean isRtlParagraph = layout.getParagraphDirection(line) == -1;
                return isRtlChar == isRtlParagraph ? primaryOffset : secondaryOffset;
            }
        }
    }

    private int getCurrentLineAdjustedForSlop(Layout layout, int prevLine, float y) {
        final int trueLine = mTextView.getLineAtCoordinate(y);
        if (layout == null || prevLine > layout.getLineCount()
                || layout.getLineCount() <= 0 || prevLine < 0) {
            // Invalid parameters, just return whatever line is at y.
            return trueLine;
        }

        if (Math.abs(trueLine - prevLine) >= 2) {
            // Only stick to lines if we're within a line of the previous selection.
            return trueLine;
        }

        final float verticalOffset = mTextView.viewportToContentVerticalOffset();
        final int lineCount = layout.getLineCount();
        final float slop = mTextView.getLineHeight() * LINE_SLOP_MULTIPLIER_FOR_HANDLEVIEWS;

        final float firstLineTop = layout.getLineTop(0) + verticalOffset;
        final float prevLineTop = layout.getLineTop(prevLine) + verticalOffset;
        final float yTopBound = Math.max(prevLineTop - slop, firstLineTop + slop);

        final float lastLineBottom = layout.getLineBottom(lineCount - 1) + verticalOffset;
        final float prevLineBottom = layout.getLineBottom(prevLine) + verticalOffset;
        final float yBottomBound = Math.min(prevLineBottom + slop, lastLineBottom - slop);

        // Determine if we've moved lines based on y position and previous line.
        int currLine;
        if (y <= yTopBound) {
            currLine = Math.max(prevLine - 1, 0);
        } else if (y >= yBottomBound) {
            currLine = Math.min(prevLine + 1, lineCount - 1);
        } else {
            currLine = prevLine;
        }
        return currLine;
    }

    /**
     * A CursorController instance can be used to control a cursor in the text.
     */
    private interface CursorController extends ViewTreeObserver.OnTouchModeChangeListener {
        /**
         * Makes the cursor controller visible on screen.
         * See also {@link #hide()}.
         */
        public void show();

        /**
         * Hide the cursor controller from screen.
         * See also {@link #show()}.
         */
        public void hide();

        /**
         * Called when the view is detached from window. Perform house keeping task, such as
         * stopping Runnable thread that would otherwise keep a reference on the context, thus
         * preventing the activity from being recycled.
         */
        public void onDetached();

        public boolean isCursorBeingModified();

        public boolean isActive();
    }

    private class InsertionPointCursorController implements CursorController {
        private InsertionHandleView mHandle;

        public void show() {
            getHandle().show();

            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.hide();
            }
        }

        public void hide() {
            if (mHandle != null) {
                mHandle.hide();
            }
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        private InsertionHandleView getHandle() {
            if (mSelectHandleCenter == null) {
                mSelectHandleCenter = mTextView.getContext().getDrawable(
                        mTextView.mTextSelectHandleRes);
            }
            if (mHandle == null) {
                mHandle = new InsertionHandleView(mSelectHandleCenter);
            }
            return mHandle;
        }

        @Override
        public void onDetached() {
            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.removeOnTouchModeChangeListener(this);

            if (mHandle != null) mHandle.onDetached();
        }

        @Override
        public boolean isCursorBeingModified() {
            return mHandle != null && mHandle.isDragging();
        }

        @Override
        public boolean isActive() {
            return mHandle != null && mHandle.isShowing();
        }

        public void invalidateHandle() {
            if (mHandle != null) {
                mHandle.invalidate();
            }
        }
    }

    class SelectionModifierCursorController implements CursorController {
        // The cursor controller handles, lazily created when shown.
        private SelectionHandleView mStartHandle;
        private SelectionHandleView mEndHandle;
        // The offsets of that last touch down event. Remembered to start selection there.
        private int mMinTouchOffset, mMaxTouchOffset;

        private float mDownPositionX, mDownPositionY;
        private boolean mGestureStayedInTapRegion;

        // Where the user first starts the drag motion.
        private int mStartOffset = -1;

        private boolean mHaventMovedEnoughToStartDrag;
        // The line that a selection happened most recently with the drag accelerator.
        private int mLineSelectionIsOn = -1;
        // Whether the drag accelerator has selected past the initial line.
        private boolean mSwitchedLines = false;

        // Indicates the drag accelerator mode that the user is currently using.
        private int mDragAcceleratorMode = DRAG_ACCELERATOR_MODE_INACTIVE;
        // Drag accelerator is inactive.
        private static final int DRAG_ACCELERATOR_MODE_INACTIVE = 0;
        // Character based selection by dragging. Only for mouse.
        private static final int DRAG_ACCELERATOR_MODE_CHARACTER = 1;
        // Word based selection by dragging. Enabled after long pressing or double tapping.
        private static final int DRAG_ACCELERATOR_MODE_WORD = 2;
        // Paragraph based selection by dragging. Enabled after mouse triple click.
        private static final int DRAG_ACCELERATOR_MODE_PARAGRAPH = 3;

        SelectionModifierCursorController() {
            resetTouchOffsets();
        }

        public void show() {
            if (mTextView.isInBatchEditMode()) {
                return;
            }
            initDrawables();
            initHandles();
        }

        private void initDrawables() {
            if (mSelectHandleLeft == null) {
                mSelectHandleLeft = mTextView.getContext().getDrawable(
                        mTextView.mTextSelectHandleLeftRes);
            }
            if (mSelectHandleRight == null) {
                mSelectHandleRight = mTextView.getContext().getDrawable(
                        mTextView.mTextSelectHandleRightRes);
            }
        }

        private void initHandles() {
            // Lazy object creation has to be done before updatePosition() is called.
            if (mStartHandle == null) {
                mStartHandle = new SelectionHandleView(mSelectHandleLeft, mSelectHandleRight,
                        com.android.internal.R.id.selection_start_handle,
                        HANDLE_TYPE_SELECTION_START);
            }
            if (mEndHandle == null) {
                mEndHandle = new SelectionHandleView(mSelectHandleRight, mSelectHandleLeft,
                        com.android.internal.R.id.selection_end_handle,
                        HANDLE_TYPE_SELECTION_END);
            }

            mStartHandle.show();
            mEndHandle.show();

            /*
             * 只做cursor.hide()，不涉及ActionMode和弹框。停止ActionMode的逻辑是在cursorController外
             * 调用的。
             */
            hideInsertionPointCursorController();
        }

        /*
         * 和InsertionPointCursorController.hide()方法一样，只对cursor作处理，只关闭cursor，不涉及
         * 对ActionMode和弹框的操作。
         */
        public void hide() {
            if (mStartHandle != null) mStartHandle.hide();
            if (mEndHandle != null) mEndHandle.hide();
        }

        public void enterDrag(int dragAcceleratorMode) {
            // Just need to init the handles / hide insertion cursor.
            // HandleView的show方法，就是注册一个textView的onPreDraw的回调，等待TextView下一次绘制
            // 的时候显示HandleView
            show();
            /*
             * 1.setCurrentWordAndStartDrag:
             * dragAcceleratorMode=Drag_Accelerator_mode_word
             */
            mDragAcceleratorMode = dragAcceleratorMode;
            // Start location of selection.
            mStartOffset = mTextView.getOffsetForPosition(mLastDownPositionX,
                    mLastDownPositionY);
            mLineSelectionIsOn = mTextView.getLineAtCoordinate(mLastDownPositionY);
            // Don't show the handles until user has lifted finger.
            hide();

            // This stops scrolling parents from intercepting the touch event, allowing
            // the user to continue dragging across the screen to select text; TextView will
            // scroll as necessary.
            mTextView.getParent().requestDisallowInterceptTouchEvent(true);
            mTextView.cancelLongPress();
        }

        public void onTouchEvent(MotionEvent event) {
            // This is done even when the View does not have focus, so that long presses can start
            // selection and tap can move cursor from this tap position.
            final float eventX = event.getX();
            final float eventY = event.getY();
            final boolean isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (extractedTextModeWillBeStarted()) {
                        // Prevent duplicating the selection handles until the mode starts.
                        hide();
                    } else {
                        // Remember finger down position, to be able to start selection from there.
                        mMinTouchOffset = mMaxTouchOffset = mTextView.getOffsetForPosition(
                                eventX, eventY);

                        // Double tap detection
                        if (mGestureStayedInTapRegion) {
                            if (mTapState == TAP_STATE_DOUBLE_TAP
                                    || mTapState == TAP_STATE_TRIPLE_CLICK) {
                                final float deltaX = eventX - mDownPositionX;
                                final float deltaY = eventY - mDownPositionY;
                                final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                                ViewConfiguration viewConfiguration = ViewConfiguration.get(
                                        mTextView.getContext());
                                int doubleTapSlop = viewConfiguration.getScaledDoubleTapSlop();
                                boolean stayedInArea =
                                        distanceSquared < doubleTapSlop * doubleTapSlop;

                                if (stayedInArea && (isMouse || isPositionOnText(eventX, eventY))) {
                                    if (mTapState == TAP_STATE_DOUBLE_TAP) {
                                        selectCurrentWordAndStartDrag();
                                    } else if (mTapState == TAP_STATE_TRIPLE_CLICK) {
                                        selectCurrentParagraphAndStartDrag();
                                    }
                                    mDiscardNextActionUp = true;
                                }
                            }
                        }

                        mDownPositionX = eventX;
                        mDownPositionY = eventY;
                        mGestureStayedInTapRegion = true;
                        mHaventMovedEnoughToStartDrag = true;
                    }
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    // Handle multi-point gestures. Keep min and max offset positions.
                    // Only activated for devices that correctly handle multi-touch.
                    if (mTextView.getContext().getPackageManager().hasSystemFeature(
                            PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)) {
                        updateMinAndMaxOffsets(event);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    final ViewConfiguration viewConfig = ViewConfiguration.get(
                            mTextView.getContext());
                    final int touchSlop = viewConfig.getScaledTouchSlop();

                    if (mGestureStayedInTapRegion || mHaventMovedEnoughToStartDrag) {
                        final float deltaX = eventX - mDownPositionX;
                        final float deltaY = eventY - mDownPositionY;
                        final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                        if (mGestureStayedInTapRegion) {
                            int doubleTapTouchSlop = viewConfig.getScaledDoubleTapTouchSlop();
                            mGestureStayedInTapRegion =
                                    distanceSquared <= doubleTapTouchSlop * doubleTapTouchSlop;
                        }
                        if (mHaventMovedEnoughToStartDrag) {
                            // We don't start dragging until the user has moved enough.
                            mHaventMovedEnoughToStartDrag =
                                    distanceSquared <= touchSlop * touchSlop;
                        }
                    }

                    if (isMouse && !isDragAcceleratorActive()) {
                        final int offset = mTextView.getOffsetForPosition(eventX, eventY);
                        if (mTextView.hasSelection()
                                && (!mHaventMovedEnoughToStartDrag || mStartOffset != offset)
                                && offset >= mTextView.getSelectionStart()
                                && offset <= mTextView.getSelectionEnd()) {
                            startDragAndDrop();
                            break;
                        }

                        if (mStartOffset != offset) {
                            // Start character based drag accelerator.
                            stopTextActionMode();
                            enterDrag(DRAG_ACCELERATOR_MODE_CHARACTER);
                            mDiscardNextActionUp = true;
                            mHaventMovedEnoughToStartDrag = false;
                        }
                    }

                    if (mStartHandle != null && mStartHandle.isShowing()) {
                        // Don't do the drag if the handles are showing already.
                        break;
                    }

                    updateSelection(event);
                    break;

                case MotionEvent.ACTION_UP:
                    if (!isDragAcceleratorActive()) {
                        break;
                    }
                    updateSelection(event);

                    // No longer dragging to select text, let the parent intercept events.
                    mTextView.getParent().requestDisallowInterceptTouchEvent(false);

                    // No longer the first dragging motion, reset.
                    resetDragAcceleratorState();

                    if (mTextView.hasSelection()) {
                        // Drag selection should not be adjusted by the text classifier.
                        startSelectionActionModeAsync(mHaventMovedEnoughToStartDrag);
                    }
                    break;
            }
        }

        private void updateSelection(MotionEvent event) {
            if (mTextView.getLayout() != null) {
                switch (mDragAcceleratorMode) {
                    case DRAG_ACCELERATOR_MODE_CHARACTER:
                        updateCharacterBasedSelection(event);
                        break;
                    case DRAG_ACCELERATOR_MODE_WORD:
                        updateWordBasedSelection(event);
                        break;
                    case DRAG_ACCELERATOR_MODE_PARAGRAPH:
                        updateParagraphBasedSelection(event);
                        break;
                }
            }
        }

        /**
         * If the TextView allows text selection, selects the current paragraph and starts a drag.
         *
         * @return true if the drag was started.
         */
        private boolean selectCurrentParagraphAndStartDrag() {
            if (mInsertionActionModeRunnable != null) {
                mTextView.removeCallbacks(mInsertionActionModeRunnable);
            }
            stopTextActionMode();
            if (!selectCurrentParagraph()) {
                return false;
            }
            enterDrag(SelectionModifierCursorController.DRAG_ACCELERATOR_MODE_PARAGRAPH);
            return true;
        }

        private void updateCharacterBasedSelection(MotionEvent event) {
            final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());
            Selection.setSelection((Spannable) mTextView.getText(), mStartOffset, offset);
        }

        private void updateWordBasedSelection(MotionEvent event) {
            if (mHaventMovedEnoughToStartDrag) {
                return;
            }
            final boolean isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE);
            final ViewConfiguration viewConfig = ViewConfiguration.get(
                    mTextView.getContext());
            final float eventX = event.getX();
            final float eventY = event.getY();
            final int currLine;
            if (isMouse) {
                // No need to offset the y coordinate for mouse input.
                currLine = mTextView.getLineAtCoordinate(eventY);
            } else {
                float y = eventY;
                if (mSwitchedLines) {
                    // Offset the finger by the same vertical offset as the handles.
                    // This improves visibility of the content being selected by
                    // shifting the finger below the content, this is applied once
                    // the user has switched lines.
                    final int touchSlop = viewConfig.getScaledTouchSlop();
                    final float fingerOffset = (mStartHandle != null)
                            ? mStartHandle.getIdealVerticalOffset()
                            : touchSlop;
                    y = eventY - fingerOffset;
                }

                currLine = getCurrentLineAdjustedForSlop(mTextView.getLayout(), mLineSelectionIsOn,
                        y);
                if (!mSwitchedLines && currLine != mLineSelectionIsOn) {
                    // Break early here, we want to offset the finger position from
                    // the selection highlight, once the user moved their finger
                    // to a different line we should apply the offset and *not* switch
                    // lines until recomputing the position with the finger offset.
                    mSwitchedLines = true;
                    return;
                }
            }

            int startOffset;
            int offset = mTextView.getOffsetAtCoordinate(currLine, eventX);
            // Snap to word boundaries.
            if (mStartOffset < offset) {
                // Expanding with end handle.
                offset = getWordEnd(offset);
                startOffset = getWordStart(mStartOffset);
            } else {
                // Expanding with start handle.
                offset = getWordStart(offset);
                startOffset = getWordEnd(mStartOffset);
                if (startOffset == offset) {
                    offset = getNextCursorOffset(offset, false);
                }
            }
            mLineSelectionIsOn = currLine;
            Selection.setSelection((Spannable) mTextView.getText(), startOffset, offset);
        }

        private void updateParagraphBasedSelection(MotionEvent event) {
            final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());

            final int start = Math.min(offset, mStartOffset);
            final int end = Math.max(offset, mStartOffset);
            final long paragraphsRange = getParagraphsRange(start, end);
            final int selectionStart = TextUtils.unpackRangeStartFromLong(paragraphsRange);
            final int selectionEnd = TextUtils.unpackRangeEndFromLong(paragraphsRange);
            Selection.setSelection((Spannable) mTextView.getText(), selectionStart, selectionEnd);
        }

        /**
         * @param event
         */
        // 获取所有手指在TextView上的边界
        private void updateMinAndMaxOffsets(MotionEvent event) {
            int pointerCount = event.getPointerCount();
            for (int index = 0; index < pointerCount; index++) {
                int offset = mTextView.getOffsetForPosition(event.getX(index), event.getY(index));
                if (offset < mMinTouchOffset) mMinTouchOffset = offset;
                if (offset > mMaxTouchOffset) mMaxTouchOffset = offset;
            }
        }

        public int getMinTouchOffset() {
            return mMinTouchOffset;
        }

        public int getMaxTouchOffset() {
            return mMaxTouchOffset;
        }

        public void resetTouchOffsets() {
            mMinTouchOffset = mMaxTouchOffset = -1;
            resetDragAcceleratorState();
        }

        private void resetDragAcceleratorState() {
            mStartOffset = -1;
            mDragAcceleratorMode = DRAG_ACCELERATOR_MODE_INACTIVE;
            mSwitchedLines = false;
            final int selectionStart = mTextView.getSelectionStart();
            final int selectionEnd = mTextView.getSelectionEnd();
            if (selectionStart > selectionEnd) {
                Selection.setSelection((Spannable) mTextView.getText(),
                        selectionEnd, selectionStart);
            }
        }

        /**
         * @return true iff this controller is currently used to move the selection start.
         */
        public boolean isSelectionStartDragged() {
            return mStartHandle != null && mStartHandle.isDragging();
        }

        @Override
        public boolean isCursorBeingModified() {
            return isDragAcceleratorActive() || isSelectionStartDragged()
                    || (mEndHandle != null && mEndHandle.isDragging());
        }

        /**
         * @return true if the user is selecting text using the drag accelerator.
         */
        public boolean isDragAcceleratorActive() {
            return mDragAcceleratorMode != DRAG_ACCELERATOR_MODE_INACTIVE;
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        @Override
        public void onDetached() {
            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.removeOnTouchModeChangeListener(this);

            if (mStartHandle != null) mStartHandle.onDetached();
            if (mEndHandle != null) mEndHandle.onDetached();
        }

        @Override
        public boolean isActive() {
            return mStartHandle != null && mStartHandle.isShowing();
        }

        public void invalidateHandles() {
            if (mStartHandle != null) {
                mStartHandle.invalidate();
            }
            if (mEndHandle != null) {
                mEndHandle.invalidate();
            }
        }
    }

    private class CorrectionHighlighter {
        private final Path mPath = new Path();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mStart, mEnd;
        private long mFadingStartTime;
        private RectF mTempRectF;
        private static final int FADE_OUT_DURATION = 400;

        public CorrectionHighlighter() {
            mPaint.setCompatibilityScaling(
                    mTextView.getResources().getCompatibilityInfo().applicationScale);
            mPaint.setStyle(Paint.Style.FILL);
        }

        public void highlight(CorrectionInfo info) {
            mStart = info.getOffset();
            mEnd = mStart + info.getNewText().length();
            mFadingStartTime = SystemClock.uptimeMillis();

            if (mStart < 0 || mEnd < 0) {
                stopAnimation();
            }
        }

        public void draw(Canvas canvas, int cursorOffsetVertical) {
            if (updatePath() && updatePaint()) {
                if (cursorOffsetVertical != 0) {
                    canvas.translate(0, cursorOffsetVertical);
                }

                canvas.drawPath(mPath, mPaint);

                if (cursorOffsetVertical != 0) {
                    canvas.translate(0, -cursorOffsetVertical);
                }
                invalidate(true); // TODO invalidate cursor region only
            } else {
                stopAnimation();
                invalidate(false); // TODO invalidate cursor region only
            }
        }

        private boolean updatePaint() {
            final long duration = SystemClock.uptimeMillis() - mFadingStartTime;
            if (duration > FADE_OUT_DURATION) return false;

            final float coef = 1.0f - (float) duration / FADE_OUT_DURATION;
            final int highlightColorAlpha = Color.alpha(mTextView.mHighlightColor);
            final int color = (mTextView.mHighlightColor & 0x00FFFFFF)
                    + ((int) (highlightColorAlpha * coef) << 24);
            mPaint.setColor(color);
            return true;
        }

        private boolean updatePath() {
            final Layout layout = mTextView.getLayout();
            if (layout == null) return false;

            // Update in case text is edited while the animation is run
            final int length = mTextView.getText().length();
            int start = Math.min(length, mStart);
            int end = Math.min(length, mEnd);

            mPath.reset();
            layout.getSelectionPath(start, end, mPath);
            return true;
        }

        private void invalidate(boolean delayed) {
            if (mTextView.getLayout() == null) return;

            if (mTempRectF == null) mTempRectF = new RectF();
            mPath.computeBounds(mTempRectF, false);

            int left = mTextView.getCompoundPaddingLeft();
            int top = mTextView.getExtendedPaddingTop() + mTextView.getVerticalOffset(true);

            if (delayed) {
                mTextView.postInvalidateOnAnimation(
                        left + (int) mTempRectF.left, top + (int) mTempRectF.top,
                        left + (int) mTempRectF.right, top + (int) mTempRectF.bottom);
            } else {
                mTextView.postInvalidate((int) mTempRectF.left, (int) mTempRectF.top,
                        (int) mTempRectF.right, (int) mTempRectF.bottom);
            }
        }

        private void stopAnimation() {
            Editor.this.mCorrectionHighlighter = null;
        }
    }

    private static class ErrorPopup extends android.widget.PopupWindow {
        private boolean mAbove = false;
        private final TextView mView;
        private int mPopupInlineErrorBackgroundId = 0;
        private int mPopupInlineErrorAboveBackgroundId = 0;

        ErrorPopup(TextView v, int width, int height) {
            super(v, width, height);
            mView = v;
            // Make sure the TextView has a background set as it will be used the first time it is
            // shown and positioned. Initialized with below background, which should have
            // dimensions identical to the above version for this to work (and is more likely).
            mPopupInlineErrorBackgroundId = getResourceId(mPopupInlineErrorBackgroundId,
                    com.android.internal.R.styleable.Theme_errorMessageBackground);
            mView.setBackgroundResource(mPopupInlineErrorBackgroundId);
        }

        void fixDirection(boolean above) {
            mAbove = above;

            if (above) {
                mPopupInlineErrorAboveBackgroundId =
                    getResourceId(mPopupInlineErrorAboveBackgroundId,
                            com.android.internal.R.styleable.Theme_errorMessageAboveBackground);
            } else {
                mPopupInlineErrorBackgroundId = getResourceId(mPopupInlineErrorBackgroundId,
                        com.android.internal.R.styleable.Theme_errorMessageBackground);
            }

            mView.setBackgroundResource(
                    above ? mPopupInlineErrorAboveBackgroundId : mPopupInlineErrorBackgroundId);
        }

        private int getResourceId(int currentId, int index) {
            if (currentId == 0) {
                TypedArray styledAttributes = mView.getContext().obtainStyledAttributes(
                        R.styleable.Theme);
                currentId = styledAttributes.getResourceId(index, 0);
                styledAttributes.recycle();
            }
            return currentId;
        }

        @Override
        public void update(int x, int y, int w, int h, boolean force) {
            super.update(x, y, w, h, force);

            boolean above = isAboveAnchor();
            if (above != mAbove) {
                fixDirection(above);
            }
        }
    }

    static class InputContentType {
        int imeOptions = EditorInfo.IME_NULL;
        String privateImeOptions;
        CharSequence imeActionLabel;
        int imeActionId;
        Bundle extras;
        OnEditorActionListener onEditorActionListener;
        boolean enterDown;
        LocaleList imeHintLocales;
    }

    static class InputMethodState {
        ExtractedTextRequest mExtractedTextRequest;
        final ExtractedText mExtractedText = new ExtractedText();
        int mBatchEditNesting;
        boolean mCursorChanged;
        boolean mSelectionModeChanged;
        boolean mContentChanged;
        int mChangedStart, mChangedEnd, mChangedDelta;
    }

    /**
     * @return True iff (start, end) is a valid range within the text.
     */
    private static boolean isValidRange(CharSequence text, int start, int end) {
        return 0 <= start && start <= end && end <= text.length();
    }

    @VisibleForTesting
    public SuggestionsPopupWindow getSuggestionsPopupWindowForTesting() {
        return mSuggestionsPopupWindow;
    }

    /**
     * An InputFilter that monitors text input to maintain undo history. It does not modify the
     * text being typed (and hence always returns null from the filter() method).
     *
     * TODO: Make this span aware.
     */
    public static class UndoInputFilter implements InputFilter {
        private final Editor mEditor;

        // Whether the current filter pass is directly caused by an end-user text edit.
        private boolean mIsUserEdit;

        // Whether the text field is handling an IME composition. Must be parceled in case the user
        // rotates the screen during composition.
        private boolean mHasComposition;

        // Whether the user is expanding or shortening the text
        private boolean mExpanding;

        // Whether the previous edit operation was in the current batch edit.
        private boolean mPreviousOperationWasInSameBatchEdit;

        public UndoInputFilter(Editor editor) {
            mEditor = editor;
        }

        public void saveInstanceState(Parcel parcel) {
            parcel.writeInt(mIsUserEdit ? 1 : 0);
            parcel.writeInt(mHasComposition ? 1 : 0);
            parcel.writeInt(mExpanding ? 1 : 0);
            parcel.writeInt(mPreviousOperationWasInSameBatchEdit ? 1 : 0);
        }

        public void restoreInstanceState(Parcel parcel) {
            mIsUserEdit = parcel.readInt() != 0;
            mHasComposition = parcel.readInt() != 0;
            mExpanding = parcel.readInt() != 0;
            mPreviousOperationWasInSameBatchEdit = parcel.readInt() != 0;
        }

        /**
         * Signals that a user-triggered edit is starting.
         */
        public void beginBatchEdit() {
            if (DEBUG_UNDO) Log.d(TAG, "beginBatchEdit");
            mIsUserEdit = true;
        }

        public void endBatchEdit() {
            if (DEBUG_UNDO) Log.d(TAG, "endBatchEdit");
            mIsUserEdit = false;
            mPreviousOperationWasInSameBatchEdit = false;
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            if (DEBUG_UNDO) {
                Log.d(TAG, "filter: source=" + source + " (" + start + "-" + end + ") "
                        + "dest=" + dest + " (" + dstart + "-" + dend + ")");
            }

            // Check to see if this edit should be tracked for undo.
            if (!canUndoEdit(source, start, end, dest, dstart, dend)) {
                return null;
            }

            final boolean hadComposition = mHasComposition;
            mHasComposition = isComposition(source);
            final boolean wasExpanding = mExpanding;
            boolean shouldCreateSeparateState = false;
            if ((end - start) != (dend - dstart)) {
                mExpanding = (end - start) > (dend - dstart);
                if (hadComposition && mExpanding != wasExpanding) {
                    shouldCreateSeparateState = true;
                }
            }

            // Handle edit.
            handleEdit(source, start, end, dest, dstart, dend, shouldCreateSeparateState);
            return null;
        }

        void freezeLastEdit() {
            mEditor.mUndoManager.beginUpdate("Edit text");
            EditOperation lastEdit = getLastEdit();
            if (lastEdit != null) {
                lastEdit.mFrozen = true;
            }
            mEditor.mUndoManager.endUpdate();
        }

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({MERGE_EDIT_MODE_FORCE_MERGE, MERGE_EDIT_MODE_NEVER_MERGE, MERGE_EDIT_MODE_NORMAL})
        private @interface MergeMode {}
        private static final int MERGE_EDIT_MODE_FORCE_MERGE = 0;
        private static final int MERGE_EDIT_MODE_NEVER_MERGE = 1;
        /** Use {@link EditOperation#mergeWith} to merge */
        private static final int MERGE_EDIT_MODE_NORMAL = 2;

        private void handleEdit(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend, boolean shouldCreateSeparateState) {
            // An application may install a TextWatcher to provide additional modifications after
            // the initial input filters run (e.g. a credit card formatter that adds spaces to a
            // string). This results in multiple filter() calls for what the user considers to be
            // a single operation. Always undo the whole set of changes in one step.
            @MergeMode
            final int mergeMode;
            if (isInTextWatcher() || mPreviousOperationWasInSameBatchEdit) {
                mergeMode = MERGE_EDIT_MODE_FORCE_MERGE;
            } else if (shouldCreateSeparateState) {
                mergeMode = MERGE_EDIT_MODE_NEVER_MERGE;
            } else {
                mergeMode = MERGE_EDIT_MODE_NORMAL;
            }
            // Build a new operation with all the information from this edit.
            String newText = TextUtils.substring(source, start, end);
            String oldText = TextUtils.substring(dest, dstart, dend);
            EditOperation edit = new EditOperation(mEditor, oldText, dstart, newText,
                    mHasComposition);
            if (mHasComposition && TextUtils.equals(edit.mNewText, edit.mOldText)) {
                return;
            }
            recordEdit(edit, mergeMode);
        }

        private EditOperation getLastEdit() {
            final UndoManager um = mEditor.mUndoManager;
            return um.getLastOperation(
                  EditOperation.class, mEditor.mUndoOwner, UndoManager.MERGE_MODE_UNIQUE);
        }
        /**
         * Fetches the last undo operation and checks to see if a new edit should be merged into it.
         * If forceMerge is true then the new edit is always merged.
         */
        private void recordEdit(EditOperation edit, @MergeMode int mergeMode) {
            // Fetch the last edit operation and attempt to merge in the new edit.
            final UndoManager um = mEditor.mUndoManager;
            um.beginUpdate("Edit text");
            EditOperation lastEdit = getLastEdit();
            if (lastEdit == null) {
                // Add this as the first edit.
                if (DEBUG_UNDO) Log.d(TAG, "filter: adding first op " + edit);
                um.addOperation(edit, UndoManager.MERGE_MODE_NONE);
            } else if (mergeMode == MERGE_EDIT_MODE_FORCE_MERGE) {
                // Forced merges take priority because they could be the result of a non-user-edit
                // change and this case should not create a new undo operation.
                if (DEBUG_UNDO) Log.d(TAG, "filter: force merge " + edit);
                lastEdit.forceMergeWith(edit);
            } else if (!mIsUserEdit) {
                // An application directly modified the Editable outside of a text edit. Treat this
                // as a new change and don't attempt to merge.
                if (DEBUG_UNDO) Log.d(TAG, "non-user edit, new op " + edit);
                um.commitState(mEditor.mUndoOwner);
                um.addOperation(edit, UndoManager.MERGE_MODE_NONE);
            } else if (mergeMode == MERGE_EDIT_MODE_NORMAL && lastEdit.mergeWith(edit)) {
                // Merge succeeded, nothing else to do.
                if (DEBUG_UNDO) Log.d(TAG, "filter: merge succeeded, created " + lastEdit);
            } else {
                // Could not merge with the last edit, so commit the last edit and add this edit.
                if (DEBUG_UNDO) Log.d(TAG, "filter: merge failed, adding " + edit);
                um.commitState(mEditor.mUndoOwner);
                um.addOperation(edit, UndoManager.MERGE_MODE_NONE);
            }
            mPreviousOperationWasInSameBatchEdit = mIsUserEdit;
            um.endUpdate();
        }

        private boolean canUndoEdit(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            if (!mEditor.mAllowUndo) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: undo is disabled");
                return false;
            }

            if (mEditor.mUndoManager.isInUndo()) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: skipping, currently performing undo/redo");
                return false;
            }

            // Text filters run before input operations are applied. However, some input operations
            // are invalid and will throw exceptions when applied. This is common in tests. Don't
            // attempt to undo invalid operations.
            if (!isValidRange(source, start, end) || !isValidRange(dest, dstart, dend)) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: invalid op");
                return false;
            }

            // Earlier filters can rewrite input to be a no-op, for example due to a length limit
            // on an input field. Skip no-op changes.
            if (start == end && dstart == dend) {
                if (DEBUG_UNDO) Log.d(TAG, "filter: skipping no-op");
                return false;
            }

            return true;
        }

        private static boolean isComposition(CharSequence source) {
            if (!(source instanceof Spannable)) {
                return false;
            }
            // This is a composition edit if the source has a non-zero-length composing span.
            Spannable text = (Spannable) source;
            int composeBegin = EditableInputConnection.getComposingSpanStart(text);
            int composeEnd = EditableInputConnection.getComposingSpanEnd(text);
            return composeBegin < composeEnd;
        }

        private boolean isInTextWatcher() {
            CharSequence text = mEditor.mTextView.getText();
            return (text instanceof SpannableStringBuilder)
                    && ((SpannableStringBuilder) text).getTextWatcherDepth() > 0;
        }
    }

    /**
     * An operation to undo a single "edit" to a text view.
     */
    public static class EditOperation extends UndoOperation<Editor> {
        private static final int TYPE_INSERT = 0;
        private static final int TYPE_DELETE = 1;
        private static final int TYPE_REPLACE = 2;

        private int mType;
        private String mOldText;
        private String mNewText;
        private int mStart;

        private int mOldCursorPos;
        private int mNewCursorPos;
        private boolean mFrozen;
        private boolean mIsComposition;

        /**
         * Constructs an edit operation from a text input operation on editor that replaces the
         * oldText starting at dstart with newText.
         */
        public EditOperation(Editor editor, String oldText, int dstart, String newText,
                boolean isComposition) {
            super(editor.mUndoOwner);
            mOldText = oldText;
            mNewText = newText;

            // Determine the type of the edit.
            if (mNewText.length() > 0 && mOldText.length() == 0) {
                mType = TYPE_INSERT;
            } else if (mNewText.length() == 0 && mOldText.length() > 0) {
                mType = TYPE_DELETE;
            } else {
                mType = TYPE_REPLACE;
            }

            mStart = dstart;
            // Store cursor data.
            mOldCursorPos = editor.mTextView.getSelectionStart();
            mNewCursorPos = dstart + mNewText.length();
            mIsComposition = isComposition;
        }

        public EditOperation(Parcel src, ClassLoader loader) {
            super(src, loader);
            mType = src.readInt();
            mOldText = src.readString();
            mNewText = src.readString();
            mStart = src.readInt();
            mOldCursorPos = src.readInt();
            mNewCursorPos = src.readInt();
            mFrozen = src.readInt() == 1;
            mIsComposition = src.readInt() == 1;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mType);
            dest.writeString(mOldText);
            dest.writeString(mNewText);
            dest.writeInt(mStart);
            dest.writeInt(mOldCursorPos);
            dest.writeInt(mNewCursorPos);
            dest.writeInt(mFrozen ? 1 : 0);
            dest.writeInt(mIsComposition ? 1 : 0);
        }

        private int getNewTextEnd() {
            return mStart + mNewText.length();
        }

        private int getOldTextEnd() {
            return mStart + mOldText.length();
        }

        @Override
        public void commit() {
        }

        @Override
        public void undo() {
            if (DEBUG_UNDO) Log.d(TAG, "undo");
            // Remove the new text and insert the old.
            Editor editor = getOwnerData();
            Editable text = (Editable) editor.mTextView.getText();
            modifyText(text, mStart, getNewTextEnd(), mOldText, mStart, mOldCursorPos);
        }

        @Override
        public void redo() {
            if (DEBUG_UNDO) Log.d(TAG, "redo");
            // Remove the old text and insert the new.
            Editor editor = getOwnerData();
            Editable text = (Editable) editor.mTextView.getText();
            modifyText(text, mStart, getOldTextEnd(), mNewText, mStart, mNewCursorPos);
        }

        /**
         * Attempts to merge this existing operation with a new edit.
         * @param edit The new edit operation.
         * @return If the merge succeeded, returns true. Otherwise returns false and leaves this
         * object unchanged.
         */
        private boolean mergeWith(EditOperation edit) {
            if (DEBUG_UNDO) {
                Log.d(TAG, "mergeWith old " + this);
                Log.d(TAG, "mergeWith new " + edit);
            }

            if (mFrozen) {
                return false;
            }

            switch (mType) {
                case TYPE_INSERT:
                    return mergeInsertWith(edit);
                case TYPE_DELETE:
                    return mergeDeleteWith(edit);
                case TYPE_REPLACE:
                    return mergeReplaceWith(edit);
                default:
                    return false;
            }
        }

        private boolean mergeInsertWith(EditOperation edit) {
            if (edit.mType == TYPE_INSERT) {
                // Merge insertions that are contiguous even when it's frozen.
                if (getNewTextEnd() != edit.mStart) {
                    return false;
                }
                mNewText += edit.mNewText;
                mNewCursorPos = edit.mNewCursorPos;
                mFrozen = edit.mFrozen;
                mIsComposition = edit.mIsComposition;
                return true;
            }
            if (mIsComposition && edit.mType == TYPE_REPLACE
                    && mStart <= edit.mStart && getNewTextEnd() >= edit.getOldTextEnd()) {
                // Merge insertion with replace as they can be single insertion.
                mNewText = mNewText.substring(0, edit.mStart - mStart) + edit.mNewText
                        + mNewText.substring(edit.getOldTextEnd() - mStart, mNewText.length());
                mNewCursorPos = edit.mNewCursorPos;
                mIsComposition = edit.mIsComposition;
                return true;
            }
            return false;
        }

        // TODO: Support forward delete.
        private boolean mergeDeleteWith(EditOperation edit) {
            // Only merge continuous deletes.
            if (edit.mType != TYPE_DELETE) {
                return false;
            }
            // Only merge deletions that are contiguous.
            if (mStart != edit.getOldTextEnd()) {
                return false;
            }
            mStart = edit.mStart;
            mOldText = edit.mOldText + mOldText;
            mNewCursorPos = edit.mNewCursorPos;
            mIsComposition = edit.mIsComposition;
            return true;
        }

        private boolean mergeReplaceWith(EditOperation edit) {
            if (edit.mType == TYPE_INSERT && getNewTextEnd() == edit.mStart) {
                // Merge with adjacent insert.
                mNewText += edit.mNewText;
                mNewCursorPos = edit.mNewCursorPos;
                return true;
            }
            if (!mIsComposition) {
                return false;
            }
            if (edit.mType == TYPE_DELETE && mStart <= edit.mStart
                    && getNewTextEnd() >= edit.getOldTextEnd()) {
                // Merge with delete as they can be single operation.
                mNewText = mNewText.substring(0, edit.mStart - mStart)
                        + mNewText.substring(edit.getOldTextEnd() - mStart, mNewText.length());
                if (mNewText.isEmpty()) {
                    mType = TYPE_DELETE;
                }
                mNewCursorPos = edit.mNewCursorPos;
                mIsComposition = edit.mIsComposition;
                return true;
            }
            if (edit.mType == TYPE_REPLACE && mStart == edit.mStart
                    && TextUtils.equals(mNewText, edit.mOldText)) {
                // Merge with the replace that replaces the same region.
                mNewText = edit.mNewText;
                mNewCursorPos = edit.mNewCursorPos;
                mIsComposition = edit.mIsComposition;
                return true;
            }
            return false;
        }

        /**
         * Forcibly creates a single merged edit operation by simulating the entire text
         * contents being replaced.
         */
        public void forceMergeWith(EditOperation edit) {
            if (DEBUG_UNDO) Log.d(TAG, "forceMerge");
            if (mergeWith(edit)) {
                return;
            }
            Editor editor = getOwnerData();

            // Copy the text of the current field.
            // NOTE: Using StringBuilder instead of SpannableStringBuilder would be somewhat faster,
            // but would require two parallel implementations of modifyText() because Editable and
            // StringBuilder do not share an interface for replace/delete/insert.
            Editable editable = (Editable) editor.mTextView.getText();
            Editable originalText = new SpannableStringBuilder(editable.toString());

            // Roll back the last operation.
            modifyText(originalText, mStart, getNewTextEnd(), mOldText, mStart, mOldCursorPos);

            // Clone the text again and apply the new operation.
            Editable finalText = new SpannableStringBuilder(editable.toString());
            modifyText(finalText, edit.mStart, edit.getOldTextEnd(),
                    edit.mNewText, edit.mStart, edit.mNewCursorPos);

            // Convert this operation into a replace operation.
            mType = TYPE_REPLACE;
            mNewText = finalText.toString();
            mOldText = originalText.toString();
            mStart = 0;
            mNewCursorPos = edit.mNewCursorPos;
            mIsComposition = edit.mIsComposition;
            // mOldCursorPos is unchanged.
        }

        private static void modifyText(Editable text, int deleteFrom, int deleteTo,
                CharSequence newText, int newTextInsertAt, int newCursorPos) {
            // Apply the edit if it is still valid.
            if (isValidRange(text, deleteFrom, deleteTo)
                    && newTextInsertAt <= text.length() - (deleteTo - deleteFrom)) {
                if (deleteFrom != deleteTo) {
                    text.delete(deleteFrom, deleteTo);
                }
                if (newText.length() != 0) {
                    text.insert(newTextInsertAt, newText);
                }
            }
            // Restore the cursor position. If there wasn't an old cursor (newCursorPos == -1) then
            // don't explicitly set it and rely on SpannableStringBuilder to position it.
            // TODO: Select all the text that was undone.
            if (0 <= newCursorPos && newCursorPos <= text.length()) {
                Selection.setSelection(text, newCursorPos);
            }
        }

        private String getTypeString() {
            switch (mType) {
                case TYPE_INSERT:
                    return "insert";
                case TYPE_DELETE:
                    return "delete";
                case TYPE_REPLACE:
                    return "replace";
                default:
                    return "";
            }
        }

        @Override
        public String toString() {
            return "[mType=" + getTypeString() + ", "
                    + "mOldText=" + mOldText + ", "
                    + "mNewText=" + mNewText + ", "
                    + "mStart=" + mStart + ", "
                    + "mOldCursorPos=" + mOldCursorPos + ", "
                    + "mNewCursorPos=" + mNewCursorPos + ", "
                    + "mFrozen=" + mFrozen + ", "
                    + "mIsComposition=" + mIsComposition + "]";
        }

        public static final Parcelable.ClassLoaderCreator<EditOperation> CREATOR =
                new Parcelable.ClassLoaderCreator<EditOperation>() {
            @Override
            public EditOperation createFromParcel(Parcel in) {
                return new EditOperation(in, null);
            }

            @Override
            public EditOperation createFromParcel(Parcel in, ClassLoader loader) {
                return new EditOperation(in, loader);
            }

            @Override
            public EditOperation[] newArray(int size) {
                return new EditOperation[size];
            }
        };
    }

    /**
     * A helper for enabling and handling "PROCESS_TEXT" menu actions.
     * These allow external applications to plug into currently selected text.
     */
    static final class ProcessTextIntentActionsHandler {

        private final Editor mEditor;
        private final TextView mTextView;
        private final Context mContext;
        private final PackageManager mPackageManager;
        private final String mPackageName;
        private final SparseArray<Intent> mAccessibilityIntents = new SparseArray<>();
        private final SparseArray<AccessibilityNodeInfo.AccessibilityAction> mAccessibilityActions =
                new SparseArray<>();
        private final List<ResolveInfo> mSupportedActivities = new ArrayList<>();

        private ProcessTextIntentActionsHandler(Editor editor) {
            mEditor = Preconditions.checkNotNull(editor);
            mTextView = Preconditions.checkNotNull(mEditor.mTextView);
            mContext = Preconditions.checkNotNull(mTextView.getContext());
            mPackageManager = Preconditions.checkNotNull(mContext.getPackageManager());
            mPackageName = Preconditions.checkNotNull(mContext.getPackageName());
        }

        /**
         * Adds "PROCESS_TEXT" menu items to the specified menu.
         */
        public void onInitializeMenu(Menu menu) {
            final int size = mSupportedActivities.size();
            loadSupportedActivities();
            for (int i = 0; i < size; i++) {
                final ResolveInfo resolveInfo = mSupportedActivities.get(i);
                menu.add(Menu.NONE, Menu.NONE,
                        Editor.MENU_ITEM_ORDER_PROCESS_TEXT_INTENT_ACTIONS_START + i++,
                        getLabel(resolveInfo))
                        .setIntent(createProcessTextIntentForResolveInfo(resolveInfo))
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            }
        }

        /**
         * Performs a "PROCESS_TEXT" action if there is one associated with the specified
         * menu item.
         *
         * @return True if the action was performed, false otherwise.
         */
        public boolean performMenuItemAction(MenuItem item) {
            return fireIntent(item.getIntent());
        }

        /**
         * Initializes and caches "PROCESS_TEXT" accessibility actions.
         */
        public void initializeAccessibilityActions() {
            mAccessibilityIntents.clear();
            mAccessibilityActions.clear();
            int i = 0;
            loadSupportedActivities();
            for (ResolveInfo resolveInfo : mSupportedActivities) {
                int actionId = TextView.ACCESSIBILITY_ACTION_PROCESS_TEXT_START_ID + i++;
                mAccessibilityActions.put(
                        actionId,
                        new AccessibilityNodeInfo.AccessibilityAction(
                                actionId, getLabel(resolveInfo)));
                mAccessibilityIntents.put(
                        actionId, createProcessTextIntentForResolveInfo(resolveInfo));
            }
        }

        /**
         * Adds "PROCESS_TEXT" accessibility actions to the specified accessibility node info.
         * NOTE: This needs a prior call to {@link #initializeAccessibilityActions()} to make the
         * latest accessibility actions available for this call.
         */
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo nodeInfo) {
            for (int i = 0; i < mAccessibilityActions.size(); i++) {
                nodeInfo.addAction(mAccessibilityActions.valueAt(i));
            }
        }

        /**
         * Performs a "PROCESS_TEXT" action if there is one associated with the specified
         * accessibility action id.
         *
         * @return True if the action was performed, false otherwise.
         */
        public boolean performAccessibilityAction(int actionId) {
            return fireIntent(mAccessibilityIntents.get(actionId));
        }

        private boolean fireIntent(Intent intent) {
            if (intent != null && Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
                intent.putExtra(Intent.EXTRA_PROCESS_TEXT, mTextView.getSelectedText());
                mEditor.mPreserveSelection = true;
                mTextView.startActivityForResult(intent, TextView.PROCESS_TEXT_REQUEST_CODE);
                return true;
            }
            return false;
        }

        private void loadSupportedActivities() {
            mSupportedActivities.clear();
            PackageManager packageManager = mTextView.getContext().getPackageManager();
            List<ResolveInfo> unfiltered =
                    packageManager.queryIntentActivities(createProcessTextIntent(), 0);
            for (ResolveInfo info : unfiltered) {
                if (isSupportedActivity(info)) {
                    mSupportedActivities.add(info);
                }
            }
        }

        private boolean isSupportedActivity(ResolveInfo info) {
            return mPackageName.equals(info.activityInfo.packageName)
                    || info.activityInfo.exported
                            && (info.activityInfo.permission == null
                                    || mContext.checkSelfPermission(info.activityInfo.permission)
                                            == PackageManager.PERMISSION_GRANTED);
        }

        private Intent createProcessTextIntentForResolveInfo(ResolveInfo info) {
            return createProcessTextIntent()
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, !mTextView.isTextEditable())
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name);
        }

        private Intent createProcessTextIntent() {
            return new Intent()
                    .setAction(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain");
        }

        private CharSequence getLabel(ResolveInfo resolveInfo) {
            return resolveInfo.loadLabel(mPackageManager);
        }
    }
}
