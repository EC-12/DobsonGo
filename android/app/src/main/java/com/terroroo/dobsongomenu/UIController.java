package com.terroroo.dobsongomenu;


import static com.terroroo.dobsongomenu.Prefs.findParentById;
import static com.terroroo.dobsongomenu.Utils.Logcat;
import static com.terroroo.dobsongomenu.Utils.dpToPx;
import static com.terroroo.dobsongomenu.Utils.getRessourceIdWithoutR;
import static com.terroroo.dobsongomenu.Utils.showToast;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * <p>Controller of the former native version of the menu, built with Android views (an XML layout) instead of the
 * current HTML page of {@link AppMenuService}.</p>
 *
 * <p><b>Automatic binding of the views.</b> The constructor fills, by reflection, every field of this class whose
 * type is a {@link View}: the field is bound to the view whose identifier is the name of the field preceded by
 * <code>terroroo_</code>. For example, the field <code>scan_btn</code> is bound to the view
 * <code>terroroo_scan_btn</code>. The name of a field must therefore match the identifier of its view exactly.</p>
 *
 * <p>This class also contains the custom views used in this layout: {@link SelectionMenu},
 * {@link ScrollableLayout}, {@link SubMenuLayout}, {@link ExpandableLayout} and {@link ObjectTrackerLayout}.</p>
 */
public final class UIController {
    /** States of the menu, displayed with {@link #changeView(Views, Object...)}. */
    public enum Views {
        /** No telescope connected: start screen of the connection. */
        CONN_MAIN,
        /** The connection failed. Argument: the message to display (<code>String</code>). */
        CONN_TRY_AGAIN,
        /** Scan in progress: the list of the devices found is displayed. */
        CONN_SCANNING,
        /** Scan stopped by the user. */
        CONN_STOPPED_SCAN,
        /** Connection in progress. Argument: the name of the telescope. */
        CONN_TO_SCOPE,
        /** Editing of the connection key. Argument: the current key (<code>String</code>). */
        CONN_KEY_EDIT,
        /** Telescope connected. Argument: the model of the telescope (<code>String</code>). */
        SCOPE_INIT,
        /** Object displayed in the tracker. Argument: the object ({@link RealAstronomy.SkyObject}, or <code>null</code>). */
        SCOPE_SELECT_OBJECT,
        /**
         * <p>Error of the tracker. Arguments: the message (<code>String</code>), and <code>true</code> to display it under
         * the tracker instead of hiding it (<code>boolean</code>).</p>
         */
        SCOPE_ERROR
    }
    /** State currently displayed, or <code>null</code> before the first call to {@link #changeView}. */
    public Views currentView = null;

    // Views of the layout, bound by name in the constructor (see the class documentation)
    public ImageButton fb;
    public LinearLayout main_layout;
    public ImageButton close_btn;
    public Button scan_btn;
    public LinearLayout floating_menu;
    public ScrollableLayout ble_list;
    public TextView connection_text;
    public LinearLayout connkey_layout;
    public EditText connkey_input;
    public ImageButton validate_connkey_btn;
    public ImageButton edit_connkey_btn;
    public LinearLayout ble_main_layout;
    public TextView connection_status;
    public LinearLayout telescope_info_layout;
    public TextView telescope_model;
    public TextView telescope_battery;
    public EditText eyepiece_focal_input;
    public ImageButton edit_focal_btn;
    //public TextView add_object_horizons;
    public ObjectTrackerLayout object_tracking_layout;
    //public TextView tracking_arrow_indicator;
    //public LinearLayout object_info_layout;
    //public TextView tracking_indicator;
    //public TextView object_name;
    //public TextView object_type;
    //public TextView object_id;
    //public TextView horizon_id;
    //public Button track_object_btn;
    //public ImageButton btn_pushto_object;
    public LinearLayout track_error_layout;
    public TextView track_error_text;
    //public LinearLayout settings_expandable;
    //public LinearLayout horizons_search_layout;
    public EditText horizons_search_input;
    public ImageButton edit_horizons_search_btn;
    public ScrollableLayout horizons_list;
    //public SelectionMenu view_selection_menu;
    //public View separator_23;
    public View separator_34;
    public View separator_45;

    /** Tabs of the {@link SelectionMenu} of the former native menu. */
    public enum SelectionMenuTypes implements SelectionMenu.BaseSelection {
        /** No tab. */
        NULL(-1),
        /** Search in JPL Horizons. */
        HORIZONS(0),
        /** Object currently selected in the host app. */
        CURRENT_VIEW(1);

        /** Index of the tab (<code>-1</code> for {@link #NULL}). */
        public final int value;

        /**
         * <p>Creates a tab.</p>
         *
         * @param value index of the tab
         */
        SelectionMenuTypes(int value) {
            this.value = value;
        }

        /**
         * <p>Returns the tab that matches an index.</p>
         *
         * @param value index of the tab
         * @return the matching tab, or {@link #NULL} if there is none
         */
        public static SelectionMenuTypes fromInt(int value) {
            for (SelectionMenuTypes type : SelectionMenuTypes.values()) {
                if (type.value == value) return type;
            }
            return NULL;
        }
    }

    /**
     * <p>Binds every {@link View} field of this class to its view in the layout (see the class documentation).</p>
     *
     * <p>The text fields are disabled at start. If a view cannot be found, the app is closed with an error.</p>
     *
     * @param root root view of the layout of the menu
     */
    public UIController(View root) {
        // WARNING: the name of each UI field //
        // must be the same as the ID of its view in the menu, without the "terroroo_" prefix //

        Context ctx = root.getContext();
        Field[] fields = this.getClass().getDeclaredFields();
        for (Field field : fields) {
            Class<?> type = field.getType();

            if (View.class.isAssignableFrom(type)) {
                String viewIdName = "terroroo_" + field.getName();

                try {
                    field.setAccessible(true);
                    View viewObj = getViewObject(root, viewIdName);
                    if (viewObj == null) throw new ActivityNotFoundException("view not found '" + viewIdName + "'");
                    field.set(this, type.cast(viewObj));
                    if (viewObj instanceof EditText) setEditTextEnabledState((EditText)viewObj, false);
                } catch (Exception e) {
                    ExitActivity.exitApplication(ctx,
                        "Impossible d'initialiser l'UI ! (voir logcat)",
                        "UIController error: unable to bind view for object '" + viewIdName + "'"
                            + "\n" + Log.getStackTraceString(e)
                    );
                }
            }
        }


    }

    /**
     * <p>Finds a view of the layout from the name of its identifier.</p>
     *
     * <p>The identifier is looked up by name, without the <code>R</code> class of the app (see
     * {@link Utils#getRessourceIdWithoutR}).</p>
     *
     * @param root the view in which to search
     * @param id   name of the identifier, for example <code>terroroo_scan_btn</code>
     * @param <T>  type of the view
     * @return the view, or <code>null</code> if it is not found
     */
    private <T extends View> T getViewObject(View root, String id) {
        Context ctx = root.getContext();
        int viewId = getRessourceIdWithoutR(ctx, id, "id");
        return root.findViewById(viewId);
    }

    /**
     * <p>Displays a state of the menu.</p>
     *
     * <p>Some states need arguments, given in <code>vars</code> (see {@link Views}):</p>
     * <ul>
     *   <li>{@link Views#CONN_TRY_AGAIN}: the error message;</li>
     *   <li>{@link Views#CONN_TO_SCOPE}: the name of the telescope;</li>
     *   <li>{@link Views#CONN_KEY_EDIT}: the current connection key;</li>
     *   <li>{@link Views#SCOPE_INIT}: the model of the telescope;</li>
     *   <li>{@link Views#SCOPE_SELECT_OBJECT}: the object to display, or <code>null</code>;</li>
     *   <li>{@link Views#SCOPE_ERROR}: the error message, then whether it is displayed under the tracker.</li>
     * </ul>
     *
     * @param view the state to display
     * @param vars the arguments of the state
     */
    public void changeView(Views view, Object... vars) {
        currentView = view;
        switch (view) {
            case CONN_MAIN:
                connMenus(
                    "Télescope non connecté", 0xFFaaaaaa, 0xFFff0000,
                    "Démarrer le scan", 0xFF0066cc, true, true,
                    false, true,
                    false
                );
                break;

            case CONN_TRY_AGAIN:
                telescope_info_layout.setVisibility(View.GONE);
                connMenus(
                    (String)vars[0], 0xFFeaa2a2, 0xFFff0000,
                    "Recommencer le scan", 0xFF0066cc, true, true,
                    false, true,
                    false
                );
                break;

            case CONN_SCANNING:
                connMenus(
                    "En attente de connexion...", 0xFFaaaaaa, 0xFFff0000,
                    "Arrêter le scan", 0xFFcc0066, true, true,
                    false, false,
                    true
                );
                break;

            case CONN_STOPPED_SCAN:
                connMenus(
                    "Scan arrêté", 0xFFaaaaaa, 0xFFff0000,
                    "Démarrer le scan", 0xFF0066cc, true, true,
                    false, true,
                    true
                );
                break;

            case CONN_TO_SCOPE:
                connMenus(
                    "Connexion en cours à " + vars[0] + "...", 0xFFa4eaa9, 0xFFff8000,
                    "En attente...", 0xFF0066cc, false, true,
                    false, false,
                    false
                );
                break;

            case CONN_KEY_EDIT:
                connMenus(
                    "Modifier la clé de connexion", 0xFFaaaaaa, 0xFFff0000,
                    "", 0xFFF, false, false,
                    true, false,
                    false
                );
                connkey_input.getText().clear();
                connkey_input.setText((String)vars[0]);
                break;

            case SCOPE_INIT:
                telescope_model.setText((String)vars[0]);
                setEditTextEnabledState(eyepiece_focal_input, false);
                scopeMenus(
                    null,
                    null, false
                );
                //horizons_search_layout.setVisibility(View.VISIBLE);
                setEditTextEnabledState(horizons_search_input, false);
                break;

            case SCOPE_SELECT_OBJECT:
                scopeMenus(
                    vars[0] == null ? null : (RealAstronomy.SkyObject)vars[0],
                    null, false
                );
                break;

            case SCOPE_ERROR:
                scopeMenus(null, (String)vars[0], (boolean)vars[1]);
                break;
        }
    }

    /**
     * <p>Enables or disables a text field, and changes its look: dark background when it is enabled, rounded frame
     * with a black border when it is disabled.</p>
     *
     * @param editText the text field
     * @param enabled  <code>true</code> to enable it
     */
    public void setEditTextEnabledState(EditText editText, boolean enabled) {
        editText.setEnabled(enabled);
        if (enabled) {
            editText.setBackgroundColor(0xDD1a1a1a);
        } else {
            GradientDrawable gradient = new GradientDrawable();
            gradient.setShape(GradientDrawable.RECTANGLE);
            gradient.setStroke(2, Color.BLACK);
            gradient.setCornerRadius(12);
            editText.setBackground(gradient);
        }
    }

    /**
     * <p>Displays the part of the menu used when a telescope is connected: its information and the tracker of the
     * selected object.</p>
     *
     * @param virtualObj      object to display in the tracker, or <code>null</code>
     * @param error           error message to display, or <code>null</code> if there is none
     * @param cumulativeError <code>true</code> to display the error under the tracker, <code>false</code> to hide the
     *                        tracker
     */
    private void scopeMenus(
        RealAstronomy.SkyObject virtualObj,
        String error, boolean cumulativeError
    ) {
        ble_main_layout.setVisibility(View.GONE);
        telescope_info_layout.setVisibility(View.VISIBLE);
        connection_status.setBackgroundColor(0xFF00ff80);
        object_tracking_layout.setVisibility(View.VISIBLE);

        object_tracking_layout.setSkyObject(virtualObj);

        if (error != null) {
            if (!cumulativeError) object_tracking_layout.setVisibility(View.GONE);
            track_error_layout.setVisibility(View.VISIBLE);
            track_error_text.setText(error);
        } else track_error_layout.setVisibility(View.GONE);
    }

    /**
     * <p>Displays the connection part of the menu, used while no telescope is connected, and configures it.</p>
     *
     * @param statusText        text of the connection status
     * @param statusTextColor   color of this text (ARGB)
     * @param connStatusBgColor color of the connection indicator (ARGB)
     * @param scanBtnText       text of the scan button
     * @param scanBtnColor      background color of the scan button (ARGB)
     * @param scanBtnEnabled    <code>true</code> if the scan button can be clicked
     * @param scanBtnVis        <code>true</code> if the scan button is visible
     * @param connKeyLayoutVis  <code>true</code> to show the fields used to edit the connection key
     * @param connKeyEditBtnVis <code>true</code> to show the button that opens the editing of the key
     * @param bleListVis        <code>true</code> to show the list of the devices found; when it is hidden, it is
     *                          emptied
     */
    private void connMenus(
        String statusText, int statusTextColor, int connStatusBgColor,
        String scanBtnText, int scanBtnColor, boolean scanBtnEnabled, boolean scanBtnVis,
        boolean connKeyLayoutVis, boolean connKeyEditBtnVis,
        boolean bleListVis
    ) {
        ble_main_layout.setVisibility(View.VISIBLE);

        connection_status.setBackgroundColor(connStatusBgColor);
        connection_text.setText(statusText);
        connection_text.setTextColor(statusTextColor);

        if (scanBtnVis) {
            scan_btn.setVisibility(View.VISIBLE);
            scan_btn.setEnabled(scanBtnEnabled);
            scan_btn.setText(scanBtnText);
            scan_btn.setBackgroundColor(scanBtnColor);
        } else {
            scan_btn.setVisibility(View.GONE);
            scan_btn.setEnabled(false);
        }

        if (connKeyEditBtnVis) edit_connkey_btn.setVisibility(View.VISIBLE);
        else edit_connkey_btn.setVisibility(View.GONE);

        if (connKeyLayoutVis) connkey_layout.setVisibility(View.VISIBLE);
        else connkey_layout.setVisibility(View.GONE);

        ble_list.setVisibility(bleListVis ? View.VISIBLE : View.GONE, !bleListVis);
    }

    /**
     * <p>Horizontal tab bar: buttons of equal width, with a blue indicator behind the selected tab.</p>
     *
     * <p>Its children, declared in XML, must be {@link Button}s. Once the view has a size, they are moved into a
     * horizontal {@link LinearLayout} and the indicator is added behind them. A click on a tab calls the
     * {@link OnSelectionChangeListener}, which is in charge of moving the indicator and changing the content.</p>
     */
    public static class SelectionMenu extends FrameLayout {
        /**
         * <code>true</code> once the children have been arranged.
         */
        private boolean isInit = false;
        /** Blue rectangle displayed behind the selected tab. */
        public View slideIndicator;
        /** Selected tab. */
        public BaseSelection currentSelection;
        /** Called when a tab is clicked. */
        private OnSelectionChangeListener selectionListener;
        /** Buttons of the tabs, in order. */
        public final List<Button> viewButtons = new ArrayList<>();

        /**
         * <p>Called when a tab is selected, with: the menu, the index of the tab, and whether the indicator should move with
         * an animation (<code>true</code> after a click, <code>false</code> when the selection is made by code).</p>
         */
        public interface OnSelectionChangeListener { void onChange(SelectionMenu thiz, int index, boolean animate); }
        /** Common type of the enums that list the tabs of a menu, such as {@link SelectionMenuTypes}. */
        public interface BaseSelection {}

        /**
         * <p>Creates the menu from code.</p>
         *
         * @param context the context of the view
         */
        public SelectionMenu(Context context) { super(context); }

        /**
         * <p>Creates the menu from an XML layout.</p>
         *
         * @param context the context of the view
         * @param attrs   the attributes of the XML layout
         */
        public SelectionMenu(Context context, AttributeSet attrs) { super(context, attrs); }

        /**
         * <p>Arranges the children the first time the view has a size, because {@link #initializeChildren()} needs the
         * final width of the view.</p>
         *
         * @param changed <code>true</code> if the size or the position of the view changed
         * @param left    left position, relative to the parent
         * @param top     top position, relative to the parent
         * @param right   right position, relative to the parent
         * @param bottom  bottom position, relative to the parent
         */
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);

            if (!isInit && getWidth() > 0 && getHeight() > 0) {
                isInit = true;
                post(this::initializeChildren);
            }
        }

        /**
         * <p>Turns the buttons declared in XML into tabs:</p>
         * <ol>
         *   <li>the buttons are kept, and all the children are removed;</li>
         *   <li>the buttons are added to a horizontal {@link LinearLayout}, with equal widths, small margins and the style
         *       of the tabs (the first one is selected);</li>
         *   <li>once the height of the buttons is known, the blue indicator is added behind them.</li>
         * </ol>
         */
        private void initializeChildren() {
            Context ctx = getContext();
            int childCount = getChildCount();
            assert childCount > 0;

            // Keep all the children
            viewButtons.clear();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child instanceof Button) viewButtons.add((Button) child);
            }

            // Remove all the children
            removeAllViews();

            // Create the horizontal LinearLayout
            LinearLayout containerLayout = new LinearLayout(ctx);
            containerLayout.setBackgroundColor(Color.TRANSPARENT);
            containerLayout.setOrientation(LinearLayout.HORIZONTAL);
            containerLayout.setGravity(Gravity.CENTER);

            FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            );
            frameParams.gravity = Gravity.CENTER;
            containerLayout.setLayoutParams(frameParams);

            // Compute the width of each button (equal split)
            int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            int buttonWidth = availableWidth / childCount;

            // Set the properties of each child and add it to the LinearLayout
            for (int i = 0; i < viewButtons.size(); i++) {
                Button child = viewButtons.get(i);
                //assert child != null && selectionListener != null;

                int finalI = i;
                child.setOnClickListener(v -> {
                    if (selectionListener != null) selectionListener.onChange(this, finalI, true);
                });

                // Set the LayoutParams for the LinearLayout
                child.setTextColor(i == 0 ? 0xFFffffff : 0xFFaaaaaa);
                child.setBackgroundColor(Color.TRANSPARENT);
                child.setTextAlignment(TEXT_ALIGNMENT_CENTER);
                child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                child.setAllCaps(false);
                child.setGravity(Gravity.CENTER);

                LinearLayout.LayoutParams childParams = new LinearLayout.LayoutParams(
                    buttonWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                childParams.gravity = Gravity.CENTER;

                int px_2dp = dpToPx(ctx, 2);
                if (i == 0) childParams.setMarginEnd(px_2dp);
                else if (i == viewButtons.size() - 1) childParams.setMarginStart(px_2dp);
                else childParams.setMargins(px_2dp, 0, px_2dp, 0);

                child.setLayoutParams(childParams);
                containerLayout.addView(child);
            }

            addView(containerLayout);

            post(() -> {
                slideIndicator = new View(ctx);
                slideIndicator.setBackgroundColor(0xFF0066cc);
                FrameLayout.LayoutParams slideParams = new FrameLayout.LayoutParams(
                    buttonWidth,
                    containerLayout.getHeight()
                );
                slideParams.gravity = Gravity.START;
                slideIndicator.setLayoutParams(slideParams);

                // Add the blue rectangle first, then the LinearLayout, so that it is drawn behind the buttons
                removeView(containerLayout);
                addView(slideIndicator);
                addView(containerLayout);

                // Force a new layout pass
                requestLayout();
            });
        }

        /**
         * <p>Sets the listener of the tabs and the tab selected at start.</p>
         *
         * @param selectionListener called when a tab is selected
         * @param defaultSelection  tab selected at start
         */
        public void setup(OnSelectionChangeListener selectionListener, BaseSelection defaultSelection) {
            this.selectionListener = selectionListener;
            this.currentSelection = defaultSelection;
        }

        /**
         * <p>Selects a tab by code: the listener is called without animation.</p>
         *
         * @param newIndex index of the tab
         */
        public void triggerChangeListener(int newIndex) {
            if (selectionListener != null) selectionListener.onChange(this, newIndex, false);
        }

        /*public void visualSelect(BaseSelection selectionIndex) {
            currentSelection.
            if ((selectionIndex < 0 || selectionIndex >= viewButtons.size())
                || (currentSelection == selectionIndex)
                || (interceptor == null || !interceptor.canSelect(getContext(), index))
            ) return false;

            Button target = viewButtons.get(index);
            int[] targetPos = new int[2];
            int[] parentPos = new int[2];

            target.getLocationOnScreen(targetPos);
            getLocationOnScreen(parentPos);

            float absoluteX = (targetPos[0] - parentPos[0]) - getPaddingLeft();
            if (animate) {
                slideIndicator.animate()
                    .translationX(absoluteX)
                    .setDuration(200)
                    .start();
            } else slideIndicator.setX(absoluteX);

            for (int i = 0; i < viewButtons.size(); i++) {
                viewButtons.get(i).setTextColor(
                    i == index ? 0xFFFFFFFF : 0xFFAAAAAA
                );
            }

            selectionChoice = index;
            return true;
        }*/
    }

    /**
     * <p>List with a title and a maximum height, whose items scroll. It is used for the list of the Bluetooth
     * devices found and for the results of the Horizons search.</p>
     *
     * <p>It is made of a separator, a title, and a {@link ScrollView} containing the items. Two XML attributes are
     * required: <code>scrollable_title</code> (the title) and <code>scrollable_max_height</code> (the maximum height of
     * the list). It must therefore be created from an XML layout.</p>
     */
    public static class ScrollableLayout extends LinearLayout {
        /** Layout that contains the items of the list. */
        private LinearLayout scrollableList;

        /**
         * <p>Creates the list from code. The XML attributes are then missing (see the class documentation).</p>
         *
         * @param context the context of the view
         */
        public ScrollableLayout(Context context) { super(context); init(context, null); }

        /**
         * <p>Creates the list from an XML layout.</p>
         *
         * @param context the context of the view
         * @param attrs   the attributes of the XML layout, including the title and the maximum height
         */
        public ScrollableLayout(Context context, AttributeSet attrs) { super(context, attrs); init(context, attrs); }

        /**
         * <p>Shows or hides the list, and can empty it at the same time.</p>
         *
         * @param visibility        {@link View#VISIBLE}, {@link View#INVISIBLE} or {@link View#GONE}
         * @param removeAllChildren <code>true</code> to remove all the items of the list
         */
        public void setVisibility(int visibility, boolean removeAllChildren) {
            if (removeAllChildren) scrollableList.removeAllViews();
            super.setVisibility(visibility);
        }

        /**
         * <p>Builds the content of the view: separator, title and scrolling area, from the XML attributes.</p>
         *
         * @param context the context of the view
         * @param attrs   the attributes of the XML layout
         */
        private void init(Context context, AttributeSet attrs) {
            assert attrs != null;
            String title; int height;

            int titleAttr = getRessourceIdWithoutR(context,
                "scrollable_title",
                "attr"
            );
            int heightAttr = getRessourceIdWithoutR(context,
                "scrollable_max_height",
                "attr"
            );

            int[] attrsArray = new int[]{ titleAttr, heightAttr };
            TypedArray a = context.obtainStyledAttributes(attrs, attrsArray);

            title = a.getString(0);
            height = a.getDimensionPixelSize(1, -1);
            a.recycle();
            assert title != null && height != -1;

            setOrientation(VERTICAL);
            // Separator
            View separator = new View(context);
            LayoutParams sepParams = new LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(context, 1));
            separator.setLayoutParams(sepParams);
            separator.setBackgroundColor(0xFF444444);
            addView(separator);

            // Title
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(0xFF888888);
            titleView.setTextSize(14);
            titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
            LayoutParams titleParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            );
            titleParams.topMargin = dpToPx(context, 6);
            titleParams.bottomMargin = dpToPx(context, 10);
            titleView.setLayoutParams(titleParams);
            addView(titleView);

            // ScrollView
            ScrollView scrollView = new ScrollView(context);
            LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, height);
            scrollView.setLayoutParams(scrollParams);

            // Scrollable inner layout
            scrollableList = new LinearLayout(context);
            scrollableList.setOrientation(VERTICAL);
            scrollableList.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ));

            scrollView.addView(scrollableList);
            addView(scrollView);
        }

        /**
         * <p>Replaces the content of the list with a centered message, for example when nothing was found.</p>
         *
         * @param description the message to display
         */
        public void addEmpty(String description) {
            Context ctx = this.getContext();
            TextView textView = new TextView(ctx);
            LinearLayout.LayoutParams textviewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            );
            textviewParams.gravity = Gravity.CENTER;
            textView.setLayoutParams(textviewParams);
            textView.setText(description);
            textView.setTextColor(0xFF888888);
            textView.setTextSize(13);
            textView.setGravity(Gravity.CENTER);
            textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            scrollableList.removeAllViews();
            scrollableList.addView(textView);
        }

        /**
         * <p>Adds an item to the list: a title in bold, and a description below it.</p>
         *
         * @param name        title of the item
         * @param description second line, for example the MAC address of a device
         * @param clkListener called with the context when the item is clicked
         */
        public void addChildItem(String name, String description, Consumer<Context> clkListener) {
            Context ctx = this.getContext();
            LinearLayout itemLayout = new LinearLayout(ctx);
            itemLayout.setOrientation(LinearLayout.VERTICAL);

            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            itemParams.setMargins(0, 0, 0, dpToPx(ctx, 6));
            itemLayout.setLayoutParams(itemParams);
            itemLayout.setBackgroundColor(0xFF2A2A2A);

            int padding = dpToPx(ctx, 5);
            itemLayout.setPadding(padding, padding, padding, padding);

            itemLayout.setClickable(true);
            itemLayout.setFocusable(true);

            // ===== First TextView (title) ===== //
            TextView title = new TextView(ctx);
            title.setText(name);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            title.setTypeface(null, Typeface.BOLD);

            // ===== Second TextView (description, e.g. a MAC address) ===== //
            TextView mac = new TextView(ctx);
            mac.setText(description);
            mac.setTextColor(0xFF888888);
            mac.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);

            LinearLayout.LayoutParams macParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            macParams.topMargin = dpToPx(ctx, 4);
            mac.setLayoutParams(macParams);

            // ===== Assembly ===== //
            itemLayout.addView(title);
            itemLayout.addView(mac);
            itemLayout.setOnClickListener(v -> clkListener.accept(ctx));
            scrollableList.addView(itemLayout);
        }
    }

    /**
     * <p>Button that opens a sub-menu with a sliding animation.</p>
     *
     * <p>It must contain exactly two children, declared in XML:</p>
     * <ol>
     *   <li>the button that opens the sub-menu (a {@link LinearLayout});</li>
     *   <li>the content of the sub-menu (a {@link LinearLayout}).</li>
     * </ol>
     *
     * <p>Once the view has a size, the content is moved into the floating menu (<code>terroroo_floating_menu</code>),
     * and a back button showing the title of the sub-menu (XML attribute <code>sub_menu_title</code>) is added at its
     * top. Opening the sub-menu slides the main menu (<code>terroroo_main_layout</code>) out and the sub-menu in; the
     * back button does the opposite.</p>
     */
    public static class SubMenuLayout extends LinearLayout {
        /** Title of the sub-menu, displayed next to the back button. */
        private String childSubLayoutTitle;
        /**
         * <code>true</code> once the children have been arranged.
         */
        private boolean isInit = false;

        /**
         * <p>Creates the view from code. The title is then missing (see the class documentation).</p>
         *
         * @param context the context of the view
         */
        public SubMenuLayout(Context context) { super(context); }

        /**
         * <p>Creates the view from an XML layout, and reads the title of the sub-menu.</p>
         *
         * @param context the context of the view
         * @param attrs   the attributes of the XML layout
         */
        public SubMenuLayout(Context context, AttributeSet attrs) { super(context, attrs); getAttributes(context, attrs); }

        /**
         * <p>Arranges the children the first time the view has a size, because {@link #initializeChildren()} needs the
         * final width of the view.</p>
         *
         * @param changed <code>true</code> if the size or the position of the view changed
         * @param left    left position, relative to the parent
         * @param top     top position, relative to the parent
         * @param right   right position, relative to the parent
         * @param bottom  bottom position, relative to the parent
         */
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (!isInit && getWidth() > 0 && getHeight() > 0) {
                isInit = true;
                post(this::initializeChildren);
            }
        }

        /**
         * <p>Reads the title of the sub-menu from the XML attribute <code>sub_menu_title</code>.</p>
         *
         * @param context the context of the view
         * @param attrs   the attributes of the XML layout
         */
        protected void getAttributes(Context context, AttributeSet attrs) {
            assert attrs != null;

            int titleAttr = getRessourceIdWithoutR(context,
                "sub_menu_title",
                "attr"
            );
            int[] attrsArray = new int[]{ titleAttr };
            TypedArray a = context.obtainStyledAttributes(attrs, attrsArray);

            childSubLayoutTitle = a.getString(0);
            a.recycle();
            assert childSubLayoutTitle != null;
        }

        /**
         * <p>Moves the content of the sub-menu into the floating menu, adds the back button at its top, and sets up the
         * sliding animations of the two buttons.</p>
         */
        protected void initializeChildren() {
            Context ctx = getContext();

            assert getChildCount() == 2;
            View buttonLayout = getChildAt(0);
            View childSubLayout = getChildAt(1);
            childSubLayout.setVisibility(View.GONE);
            assert childSubLayout instanceof LinearLayout
                    && buttonLayout instanceof LinearLayout;
            buttonLayout.setClickable(true);

            View parent_menu_layout = findParentById(this, getRessourceIdWithoutR(ctx, "terroroo_floating_menu", "id"));
            assert parent_menu_layout instanceof LinearLayout;

            LinearLayout returnLayout = new LinearLayout(ctx);
            returnLayout.setOrientation(LinearLayout.HORIZONTAL);
            returnLayout.setGravity(Gravity.CENTER);
            returnLayout.setClickable(true);
            LinearLayout.LayoutParams rtnLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rtnLayoutParams.gravity = Gravity.CENTER;
            rtnLayoutParams.setMargins(0, 0, 0, dpToPx(ctx, 16));
            returnLayout.setLayoutParams(rtnLayoutParams);

            ImageView arrowRtn = new ImageView(ctx);
            arrowRtn.setBackgroundResource(android.R.drawable.ic_menu_revert);
            LinearLayout.LayoutParams arrowRtnParams = new LinearLayout.LayoutParams(
                dpToPx(ctx, 20),
                dpToPx(ctx, 20)
            );
            arrowRtnParams.setMarginEnd(dpToPx(ctx, 8));
            arrowRtn.setLayoutParams(arrowRtnParams);

            TextView rtnTextView = new TextView(ctx);
            rtnTextView.setTextColor(0xFF888888);
            rtnTextView.setTextSize(13);
            rtnTextView.setText(childSubLayoutTitle);
            rtnTextView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            rtnTextView.setAllCaps(true);
            rtnTextView.setTypeface(rtnTextView.getTypeface(), Typeface.BOLD);
            LinearLayout.LayoutParams rtnTextViewParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rtnTextViewParams.weight = 1.0f;
            rtnTextView.setLayoutParams(rtnTextViewParams);

            returnLayout.addView(arrowRtn);
            returnLayout.addView(rtnTextView);

            View main_menu_layout = findParentById(this, getRessourceIdWithoutR(ctx, "terroroo_main_layout", "id"));
            assert main_menu_layout instanceof LinearLayout;

            returnLayout.setOnClickListener(v -> {
                main_menu_layout.setVisibility(View.VISIBLE);

                int width = main_menu_layout.getWidth();
                childSubLayout.animate()
                    .translationX(-width)
                    .setDuration(300);

                main_menu_layout.setTranslationX(width);
                main_menu_layout.animate()
                    .translationX(0)
                    .setDuration(300)
                    .withStartAction(() -> childSubLayout.setVisibility(View.GONE));
            });

            ((LinearLayout) childSubLayout).addView(returnLayout, 0);
            removeView(childSubLayout);
            ((LinearLayout) parent_menu_layout).addView(childSubLayout);

            buttonLayout.setOnClickListener(v -> {
                childSubLayout.setVisibility(View.VISIBLE);

                int width = main_menu_layout.getWidth();
                main_menu_layout.animate()
                    .translationX(width)
                    .setDuration(300);

                childSubLayout.setTranslationX(-width);
                childSubLayout.animate()
                    .translationX(0)
                    .setDuration(300)
                    .withStartAction(() -> main_menu_layout.setVisibility(View.GONE));
            });
        }
    }

    /**
     * <p>Section that can be folded: a clickable header with an arrow, and a content shown or hidden by a click on the
     * header.</p>
     *
     * <p>In XML, it contains {@link TextView}s, which form the header next to the arrow, and exactly one
     * {@link LinearLayout}, the content. The section is folded at start.</p>
     */
    public static class ExpandableLayout extends LinearLayout {
        /**
         * <code>true</code> once the children have been arranged.
         */
        public boolean isInit = false;
        /** Arrow of the header, turned by 90° while the section is open. */
        private TextView arrowIndicator;
        /** Content of the section, shown while it is open. */
        private LinearLayout expandedLayout;

        /**
         * <p>Creates the section from code.</p>
         *
         * @param context the context of the view
         */
        public ExpandableLayout(Context context) { super(context); }

        /**
         * <p>Creates the section from an XML layout.</p>
         *
         * @param context the context of the view
         * @param attrs   the attributes of the XML layout
         */
        public ExpandableLayout(Context context, AttributeSet attrs) { super(context, attrs); }

        /**
         * <p>Arranges the children the first time the view has a size, because {@link #initializeChildren()} needs the
         * final width of the view.</p>
         *
         * @param changed <code>true</code> if the size or the position of the view changed
         * @param left    left position, relative to the parent
         * @param top     top position, relative to the parent
         * @param right   right position, relative to the parent
         * @param bottom  bottom position, relative to the parent
         */
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);

            if (!isInit && getWidth() > 0 && getHeight() > 0) {
                isInit = true;
                post(this::initializeChildren);
            }
        }

        /**
         * <p>Builds the header from the arrow and the {@link TextView}s declared in XML. A click on the header opens or
         * closes the content, and turns the arrow with an animation.</p>
         *
         * <p>Subclasses can override this method to build the section differently (see {@link ObjectTrackerLayout}).</p>
         */
        protected void initializeChildren() {
            Context ctx = getContext();
            int childCount = getChildCount();
            List<View> originalChildren = new ArrayList<>();

            int LinearLayoutChildCount = 0;

            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child instanceof LinearLayout) {
                    expandedLayout = (LinearLayout) child;
                    LinearLayoutChildCount++;
                } else assert child instanceof TextView;
                originalChildren.add(child);
            }
            assert LinearLayoutChildCount == 1;

            // Remove all the children
            removeAllViews();

            // Header of the folded section
            LinearLayout closedTab = new LinearLayout(ctx);
            closedTab.setOrientation(HORIZONTAL);
            closedTab.setGravity(Gravity.START);
            closedTab.setClickable(true);
            closedTab.setOnClickListener(v -> {
                if (expandedLayout.getVisibility() == View.GONE) {
                    arrowIndicator.animate()
                        .rotation(90f)
                        .setDuration(200)
                        .start();
                    expandedLayout.setVisibility(View.VISIBLE);
                } else {
                    arrowIndicator.animate()
                        .rotation(0f)
                        .setDuration(200)
                        .start();
                    expandedLayout.setVisibility(View.GONE);
                }
            });
            LinearLayout.LayoutParams closedTabParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            closedTabParams.gravity = Gravity.START;
            closedTab.setLayoutParams(closedTabParams);

            // Folding arrow
            arrowIndicator = new TextView(ctx);
            arrowIndicator.setText("➤");
            arrowIndicator.setTextColor(0xFF888888);
            arrowIndicator.setTextSize(13);
            arrowIndicator.setTypeface(arrowIndicator.getTypeface(), Typeface.BOLD);
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            arrowParams.setMarginEnd(dpToPx(ctx, 8));
            arrowIndicator.setLayoutParams(arrowParams);
            closedTab.addView(arrowIndicator);

            for (View child : originalChildren) {
                if (child instanceof TextView) closedTab.addView(child);
            }

            addView(closedTab);
            expandedLayout.setVisibility(View.GONE);
            addView(expandedLayout);
            requestLayout();
        }
    }

    /**
     * <p>Expandable section that displays the selected object and lets the user track it.</p>
     *
     * <ul>
     *   <li><b>Header</b>: the arrow, a label and the tracking state;</li>
     *   <li><b>Content</b>: the name, the type, the identifier and the Horizons identifier of the object, the button to
     *       start or stop the tracking, and a Push-To button.</li>
     * </ul>
     *
     * <p>Unlike {@link ExpandableLayout}, its content is built entirely by code: it must be empty in XML.</p>
     */
    public static class ObjectTrackerLayout extends ExpandableLayout {
        /** Object displayed, or <code>null</code>. */
        private RealAstronomy.SkyObject skyObject;
        /** Text of the header that shows the tracking state. */
        private TextView trackingIndicator;
        /** Button to start or stop the tracking. */
        public Button trackObjectBtn;
        /** Called when the tracking button is clicked. */
        private clickListener trackListener;
        /** Listener of the Push-To button. Not called at the moment. */
        private clickListener pushListener;
        /** Push-To button. No action is attached to it at the moment. */
        private ImageButton pushtoObjectBtn;
        /** Text views of the values of the content, by key: <code>name</code>, <code>type</code>, <code>id</code> and <code>horizons</code>. */
        private final Map<String, TextView> skyObjectValues = new HashMap<>();

        /** Listener of the buttons of the section, which receives the section. */
        public interface clickListener { void trigger(ObjectTrackerLayout thiz); }
        /** Button a listener is attached to: tracking (<code>TRACK</code>) or Push-To (<code>PUSH</code>). */
        public enum ListenerType { TRACK, PUSH }

        /**
         * <p>Attaches a listener to one of the buttons of the section.</p>
         *
         * @param clickListener the listener
         * @param type          the button
         */
        public void setOnClickListener(clickListener clickListener, ListenerType type) {
            if (type == ListenerType.TRACK) {
                trackListener = clickListener;
            } else if (type == ListenerType.PUSH) {
                pushListener = clickListener;
            }
        }

        /**
         * <p>Creates the section from code.</p>
         *
         * @param context the context of the view
         */
        public ObjectTrackerLayout(Context context) { super(context); }

        /**
         * <p>Creates the section from an XML layout.</p>
         *
         * @param context the context of the view
         * @param attrs   the attributes of the XML layout
         */
        public ObjectTrackerLayout(Context context, AttributeSet attrs) { super(context, attrs); }

        /**
         * <p>Builds the header and the content of the section by code (see the class documentation), then displays the
         * current object.</p>
         */
        @Override
        protected void initializeChildren() {
            // This override is the one called by ExpandableLayout.onLayout() (polymorphism)
            assert getChildCount() == 0;
            Context ctx = getContext();

            // Closed Layout
            LinearLayout closedTab = new LinearLayout(ctx);
            closedTab.setOrientation(HORIZONTAL);
            closedTab.setGravity(Gravity.CENTER);
            closedTab.setClickable(true);
            closedTab.setOnClickListener(v -> {
                if (super.expandedLayout.getVisibility() == View.GONE) {
                    super.arrowIndicator.animate()
                        .rotation(90f)
                        .setDuration(200)
                        .start();
                    super.expandedLayout.setVisibility(View.VISIBLE);
                } else {
                    super.arrowIndicator.animate()
                        .rotation(0f)
                        .setDuration(200)
                        .start();
                    super.expandedLayout.setVisibility(View.GONE);
                }
            });
            LinearLayout.LayoutParams closedTabParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            closedTabParams.gravity = Gravity.CENTER;
            closedTab.setLayoutParams(closedTabParams);

            // Close Layout Content
            String[] displayElems = new String[]{"➤", "Sélectionné", "|", "tracking inactif"};
            for (int i = 0; i < displayElems.length; i++) {
                TextView textView = new TextView(ctx);

                textView.setText(displayElems[i]);
                textView.setTextColor(i == 3 ? 0xFFff0000 : 0xFF888888);
                textView.setTextSize(i == 3 ? 11 : 13);
                textView.setTypeface(textView.getTypeface(), Typeface.BOLD);
                if (i == 3) textView.setAllCaps(true);
                if (i == 2) textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);

                LinearLayout.LayoutParams textViewParams = new LinearLayout.LayoutParams(
                    i == 2 ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                if (i == 0) textViewParams.setMarginEnd(dpToPx(ctx, 8));
                else if (i == 2) textViewParams.weight = 1.0f;

                textView.setLayoutParams(textViewParams);
                closedTab.addView(textView);

                if (i == 0) super.arrowIndicator = textView;
                else if (i == 3) trackingIndicator = textView;
            }

            // Expanded Layout
            super.expandedLayout = new LinearLayout(ctx);
            super.expandedLayout.setOrientation(VERTICAL);
            super.expandedLayout.setPadding(dpToPx(ctx, 4), 0, dpToPx(ctx, 4), 0);
            LinearLayout.LayoutParams expandedLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            expandedLayoutParams.setMargins(0, dpToPx(ctx, 8), 0, 0);
            super.expandedLayout.setLayoutParams(expandedLayoutParams);

            // Expanded Layout Content
            String[] expandedDisplayElems = new String[]{"Nom :", "Type :", "ID :", "Horizon :"};
            for (int i = 0; i < expandedDisplayElems.length; i++) {
                LinearLayout layout = new LinearLayout(ctx);
                layout.setOrientation(HORIZONTAL);
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                layout.setLayoutParams(layoutParams);

                TextView textViewIndicator = new TextView(ctx);
                textViewIndicator.setText(expandedDisplayElems[i]);
                textViewIndicator.setTextColor(0xFF888888);
                textViewIndicator.setTextSize(12);
                LinearLayout.LayoutParams textViewIndiParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                textViewIndiParams.weight = 1.0f;
                textViewIndicator.setLayoutParams(textViewIndiParams);

                TextView textViewValue = new TextView(ctx);
                textViewValue.setText("--");
                textViewValue.setTextColor(0xFFffffff);
                textViewValue.setTextSize(11);
                textViewValue.setTypeface(textViewValue.getTypeface(), Typeface.BOLD);
                LinearLayout.LayoutParams textViewValParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                textViewValue.setLayoutParams(textViewValParams);

                switch (i) {
                    case 0:
                        skyObjectValues.put("name", textViewValue);
                        break;
                    case 1:
                        skyObjectValues.put("type", textViewValue);
                        break;
                    case 2:
                        skyObjectValues.put("id", textViewValue);
                        break;
                    case 3:
                        skyObjectValues.put("horizons", textViewValue);
                        break;
                }

                layout.addView(textViewIndicator);
                layout.addView(textViewValue);
                super.expandedLayout.addView(layout);
            }

            // Layout Expanded Track / Push Btn
            LinearLayout trackPushLayout = new LinearLayout(ctx);
            trackPushLayout.setOrientation(HORIZONTAL);
            trackPushLayout.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams trackPushParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            trackPushParams.gravity = Gravity.CENTER;
            trackPushParams.setMargins(0, dpToPx(ctx, 12), 0, dpToPx(ctx, 4));
            trackPushLayout.setLayoutParams(trackPushParams);

            trackObjectBtn = new Button(ctx);
            trackObjectBtn.setText("Tracker l'objet");
            trackObjectBtn.setBackgroundColor(0xFF1e7a1e);
            trackObjectBtn.setAllCaps(false);
            trackObjectBtn.setTextColor(0xFFffffff);
            trackObjectBtn.setTypeface(trackObjectBtn.getTypeface(), Typeface.BOLD);
            LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                0,
                dpToPx(ctx, 40)
            );
            trackParams.weight = 1.0f;
            trackObjectBtn.setLayoutParams(trackParams);
            trackObjectBtn.setOnClickListener(v -> {
                if (skyObject == null) showToast(v.getContext(), "Aucun objet sélectionné !");
                else {
                    assert trackListener != null;
                    trackListener.trigger(this);
                }
            });

            pushtoObjectBtn = new ImageButton(ctx);
            pushtoObjectBtn.setBackgroundResource(android.R.drawable.ic_menu_compass);
            LinearLayout.LayoutParams pushParams = new LinearLayout.LayoutParams(
                dpToPx(ctx, 32),
                dpToPx(ctx, 32)
            );
            pushParams.setMarginStart(dpToPx(ctx, 5));
            pushtoObjectBtn.setLayoutParams(pushParams);

            trackPushLayout.addView(trackObjectBtn);
            trackPushLayout.addView(pushtoObjectBtn);
            super.expandedLayout.addView(trackPushLayout);

            // Final assembly
            addView(closedTab);
            super.expandedLayout.setVisibility(View.GONE);
            addView(super.expandedLayout);
            requestLayout();

            updateVisuals();
        }

        /**
         * <p>Updates the header and the tracking button according to the tracking state. It is called by the object each
         * time its state changes.</p>
         *
         * @param isTracking <code>true</code> if the object is being tracked
         */
        protected void setTrackingState(boolean isTracking) {
            if (isTracking) {
                trackingIndicator.setText("tracking actif");
                trackingIndicator.setTextColor(0xFF00ff00);
                trackObjectBtn.setText("Arrêter le tracking");
                trackObjectBtn.setBackgroundColor(0xFFcc0066);
            } else {
                trackingIndicator.setText("tracking inactif");
                trackingIndicator.setTextColor(0xFFff0000);
                trackObjectBtn.setText("Tracker l'objet");
                trackObjectBtn.setBackgroundColor(0xFF1e7a1e);
            }
        }

        /**
         * <p>Displays the information of the current object, or <code>--</code> if there is none, and starts observing its
         * tracking state.</p>
         */
        protected void updateVisuals() {
            if (this.skyObject != null) {
                this.skyObject.setTrackingObserver(this::setTrackingState);
                setTrackingState(this.skyObject.isTracking());

                Objects.requireNonNull(this.skyObjectValues.get("name")).setText(this.skyObject.name);
                Objects.requireNonNull(this.skyObjectValues.get("type")).setText(this.skyObject.type.name_fr);
                Objects.requireNonNull(this.skyObjectValues.get("id")).setText(this.skyObject.id);
                if (this.skyObject instanceof RealAstronomy.OrbitalObject
                        && ((RealAstronomy.OrbitalObject) this.skyObject).horizons != null
                ) {
                    Objects.requireNonNull(this.skyObjectValues.get("horizons")).setText(
                            ((RealAstronomy.OrbitalObject) this.skyObject).horizons
                    );
                } else Objects.requireNonNull(this.skyObjectValues.get("horizons")).setText("--");
            } else {
                setTrackingState(false);
                Objects.requireNonNull(skyObjectValues.get("name")).setText("--");
                Objects.requireNonNull(skyObjectValues.get("type")).setText("--");
                Objects.requireNonNull(skyObjectValues.get("id")).setText("--");
                Objects.requireNonNull(skyObjectValues.get("horizons")).setText("--");
            }
        }

        /**
         * <p>Changes the object displayed. The previous object is no longer observed.</p>
         *
         * @param newObj the new object, or <code>null</code>
         */
        private void setSkyObject(RealAstronomy.SkyObject newObj) {
            if (this.skyObject != null) {
                this.skyObject.setTrackingObserver(null);
            }

            this.skyObject = newObj;
            if (this.isInit) updateVisuals();
        }

        /**
         * <p>Returns the object displayed.</p>
         *
         * @return the object, or <code>null</code> if there is none
         */
        public RealAstronomy.SkyObject getSkyObject() { return this.skyObject; }
    }
}
