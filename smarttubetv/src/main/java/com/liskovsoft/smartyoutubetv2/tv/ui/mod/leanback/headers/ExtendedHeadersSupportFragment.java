package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.headers;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.HeadersSupportFragment;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowHeaderPresenter;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ExtendedHeadersSupportFragment extends HeadersSupportFragment {
    public interface OnHeaderLongPressedListener {
        /**
         * Called when a header item has been long pressed.
         *
         * @param viewHolder Row ViewHolder object corresponding to the selected Header.
         * @param row Row object corresponding to the selected Header.
         */
        void onHeaderLongPressed(RowHeaderPresenter.ViewHolder viewHolder, Row row);
    }

    private OnHeaderLongPressedListener mOnHeaderLongPressedListener;
    private LinearLayout mBuildInfo;

    public ExtendedHeadersSupportFragment() {
        Helpers.setField(this, "mAdapterListener", mCustomAdapterListener);
    }

    public void setOnHeaderLongPressedListener(OnHeaderLongPressedListener listener) {
        mOnHeaderLongPressedListener = listener;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        addBuildInfo(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBuildInfo = null;
    }

    /**
     * Claude variant: version, build time and upstream state right below the last sidebar item (not focusable).
     */
    private void addBuildInfo(View root) {
        VerticalGridView grid = getVerticalGridView();

        if (!(root instanceof ViewGroup) || grid == null) {
            return;
        }

        Context context = root.getContext();
        int green = ContextCompat.getColor(context, R.color.claude_green);
        int grey = Color.parseColor("#9E9E9E");

        mBuildInfo = new LinearLayout(context);
        mBuildInfo.setOrientation(LinearLayout.VERTICAL);
        mBuildInfo.setFocusable(false);
        mBuildInfo.setVisibility(View.INVISIBLE);

        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(context, 180), Math.max(1, dp(context, 1) / 2));
        dividerParams.bottomMargin = dp(context, 8);
        mBuildInfo.addView(divider, dividerParams);

        mBuildInfo.addView(createInfoRow(context, R.drawable.ic_claude_info_version,
                "SmartTube " + BuildConfig.VERSION_NAME + " Claude", green, 14, true));
        mBuildInfo.addView(createInfoRow(context, R.drawable.ic_claude_info_build,
                formatTime(BuildConfig.TIMESTAMP), grey, 12, false));
        String upstream = BuildConfig.CLAUDE_UPSTREAM_TIME > 0
                ? formatTime(BuildConfig.CLAUDE_UPSTREAM_TIME) + "  " + BuildConfig.CLAUDE_UPSTREAM_COMMIT : "unknown";
        mBuildInfo.addView(createInfoRow(context, R.drawable.ic_claude_info_upstream, upstream, grey, 12, false));

        ((ViewGroup) root).addView(mBuildInfo, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateBuildInfoPosition());
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateBuildInfoPosition();
            }
        });
    }

    private void updateBuildInfoPosition() {
        VerticalGridView grid = getVerticalGridView();
        View root = getView();

        if (mBuildInfo == null || grid == null || root == null || grid.getAdapter() == null) {
            return;
        }

        int lastPosition = grid.getAdapter().getItemCount() - 1;
        RecyclerView.ViewHolder lastHolder = lastPosition >= 0 ? grid.findViewHolderForAdapterPosition(lastPosition) : null;

        // Only when the last item (Settings) is on screen, so the block sits right below it
        if (lastHolder == null) {
            mBuildInfo.setVisibility(View.INVISIBLE);
            return;
        }

        View item = lastHolder.itemView;
        View icon = item.findViewById(R.id.header_icon);
        View anchor = icon != null ? icon : item;
        int[] rootPos = new int[2];
        int[] anchorPos = new int[2];
        int[] itemPos = new int[2];
        root.getLocationInWindow(rootPos);
        anchor.getLocationInWindow(anchorPos);
        item.getLocationInWindow(itemPos);

        mBuildInfo.setTranslationX(anchorPos[0] - rootPos[0]);
        mBuildInfo.setTranslationY(itemPos[1] - rootPos[1] + item.getHeight() + dp(root.getContext(), 10));
        mBuildInfo.setVisibility(View.VISIBLE);
    }

    private static TextView createInfoRow(Context context, int iconResId, String text, int color, int textSizeSp, boolean bold) {
        TextView row = new TextView(context);
        row.setText(text);
        row.setTextColor(color);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        row.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        row.setSingleLine(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(iconResId, 0, 0, 0);
        row.setCompoundDrawablePadding(dp(context, 8));
        row.setPadding(0, dp(context, 2), 0, dp(context, 2));
        row.setFocusable(false);
        return row;
    }

    private static String formatTime(long timeMs) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date(timeMs));
    }

    private static int dp(Context context, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }

    private final ItemBridgeAdapter.AdapterListener mCustomAdapterListener =
            new ItemBridgeAdapter.AdapterListener() {
                @Override
                public void onCreate(final ItemBridgeAdapter.ViewHolder viewHolder) {
                    ItemBridgeAdapter.Wrapper wrapper = (ItemBridgeAdapter.Wrapper) Helpers.getField(ExtendedHeadersSupportFragment.this, "mWrapper");
                    OnLayoutChangeListener layoutChangeListener = (OnLayoutChangeListener) Helpers.getField(ExtendedHeadersSupportFragment.this, "sLayoutChangeListener");

                    View headerView = viewHolder.getViewHolder().view;
                    headerView.setOnClickListener(v -> {
                        OnHeaderClickedListener onHeaderClickedListener =
                                (OnHeaderClickedListener) Helpers.getField(ExtendedHeadersSupportFragment.this, "mOnHeaderClickedListener");
                        if (onHeaderClickedListener != null) {
                            onHeaderClickedListener.onHeaderClicked(
                                    (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                    (Row) viewHolder.getItem());
                        }
                    });

                    // NEW CODE
                    headerView.setOnLongClickListener(v -> {
                        if (mOnHeaderLongPressedListener != null) {
                            mOnHeaderLongPressedListener.onHeaderLongPressed(
                                    (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                    (Row) viewHolder.getItem());
                            return true; // don't provoke single click event
                        }

                        return false; // work as usual
                    });

                    // NEW CODE
                    headerView.setOnKeyListener((v, keyCode, event) -> {
                        if (mOnHeaderLongPressedListener != null) {
                            if (KeyHelpers.isMenuKey(keyCode)) {
                                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                    mOnHeaderLongPressedListener.onHeaderLongPressed(
                                            (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                            (Row) viewHolder.getItem());
                                }
                            }
                        }

                        return false; // enable navigation events
                    });

                    if (wrapper != null) {
                        viewHolder.itemView.addOnLayoutChangeListener(layoutChangeListener);
                    } else {
                        headerView.addOnLayoutChangeListener(layoutChangeListener);
                    }
                }

            };
}
