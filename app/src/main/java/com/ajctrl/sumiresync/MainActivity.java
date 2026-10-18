package com.ajctrl.sumiresync;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.content.res.ColorStateList;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageView;
import android.view.inputmethod.InputMethodManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ajctrl.sumiresync.data.SyncStateStore;
import com.ajctrl.sumiresync.settings.AppSettings;
import com.ajctrl.sumiresync.settings.SecretStore;
import com.ajctrl.sumiresync.sync.SumireContract;
import com.ajctrl.sumiresync.sync.SyncScheduler;
import com.ajctrl.sumiresync.upload.NextcloudUrl;

import java.text.DateFormat;
import java.util.Date;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final long STATUS_REFRESH_MILLIS = 1_500;
    private static final long[] FLAG_DURATIONS = {
            15 * 60 * 1000L,
            30 * 60 * 1000L,
            60 * 60 * 1000L,
            3 * 60 * 60 * 1000L,
            6 * 60 * 60 * 1000L,
            12 * 60 * 60 * 1000L
    };
    private static final String[] FLAG_DURATION_LABELS = {
            "15分", "30分", "1時間", "3時間", "6時間", "12時間"
    };
    private EditText url;
    private EditText user;
    private EditText password;
    private Switch foreground;
    private Switch flagging;
    private Spinner flagDuration;
    private TextView flagRemaining;
    private boolean updatingFlagUi;
    private TextView status;
    private TextView statusError;
    private TextView connectionValue;
    private TextView localSavedValue;
    private TextView syncCompletedValue;
    private TextView usageAccessValue;
    private LinearLayout statusDetails;
    private TextView apiVersionValue;
    private TextView databaseIdValue;
    private TextView generationValue;
    private TextView lastIdValue;
    private TextView providerUrisLabel;
    private LinearLayout providerUris;
    private List<String> displayedProviderUris = new ArrayList<>();
    private Button detailsToggle;
    private ImageView statusIcon;
    private LinearLayout statusBadge;
    private Disclosure connectionSection;
    private Disclosure optionsSection;
    private boolean detailsExpanded;
    private Handler statusHandler;
    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor();
    private boolean statusReadPending;
    private boolean statusResumed;
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            if (status != null) refreshStatus();
            statusHandler.postDelayed(this, STATUS_REFRESH_MILLIS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        statusHandler = new Handler(Looper.getMainLooper());
        detailsExpanded = state != null && state.getBoolean("detailsExpanded");
        setContentView(buildContent());
        loadSettings();
        if (state != null) {
            connectionSection.setExpanded(state.getBoolean("connectionExpanded"));
            optionsSection.setExpanded(state.getBoolean("optionsExpanded"));
        }
        stateExecutor.execute(() -> {
            SyncScheduler.requestCatchUp(getApplicationContext());
            SyncScheduler.schedulePeriodicCatchUp(getApplicationContext());
        });
    }

    @Override protected void onResume() {
        super.onResume();
        statusResumed = true;
        statusHandler.removeCallbacks(statusRefresh);
        statusRefresh.run();
    }

    @Override protected void onPause() {
        statusResumed = false;
        statusHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        statusHandler.removeCallbacksAndMessages(null);
        // Persist already requested syncs even if the screen is closed or rotated.
        // Their callbacks check isDestroyed() before touching the old UI.
        stateExecutor.shutdown();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("detailsExpanded", detailsExpanded);
        state.putBoolean("connectionExpanded", connectionSection.expanded);
        state.putBoolean("optionsExpanded", optionsSection.expanded);
        super.onSaveInstanceState(state);
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setBackgroundColor(0xfff7f2fa);
        root.setFocusableInTouchMode(true);

        TextView title = text("Sumire Sync", 28);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("クリップボードをNextcloudへ同期", 14);
        subtitle.setTextColor(0xff625b71);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        root.addView(subtitle);
        addSection(root, buildStatusCard());

        addSection(root, buildFlagCard());

        connectionSection = new Disclosure("接続設定", UiIcon.Kind.CLOUD);
        LinearLayout connection = connectionSection.body;
        url = labeledField(connection, R.id.server_url, "NextcloudサーバーURL", "https://cloud.example.com");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = labeledField(connection, R.id.server_user, "ユーザー名", "ユーザー名を入力");
        password = labeledField(connection, R.id.server_password, "パスワード / アプリパスワード", "パスワードを入力");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button save = actionButton("設定を保存して同期", true);
        save.setOnClickListener(v -> saveAndSync());
        LinearLayout.LayoutParams saveParams = fullWidth();
        saveParams.topMargin = dp(16);
        connection.addView(save, saveParams);
        addSection(root, connectionSection.card);

        optionsSection = new Disclosure("同期オプション", UiIcon.Kind.SLIDERS);
        LinearLayout options = optionsSection.body;
        foreground = new Switch(this);
        foreground.setText("コピー元のアプリを推定する");
        foreground.setTextColor(0xff1d1b20);
        foreground.setTextSize(15);
        foreground.setMinHeight(dp(48));
        foreground.setSwitchPadding(dp(12));
        options.addView(foreground, fullWidth());
        TextView foregroundHelp = text("コピー時に開いていたアプリを推定します。\n利用には使用状況へのアクセス許可が必要です。", 13);
        foregroundHelp.setTextColor(0xff625b71);
        foregroundHelp.setLineSpacing(dp(3), 1);
        foregroundHelp.setPadding(0, dp(4), 0, dp(4));
        options.addView(foregroundHelp, fullWidth());
        addDivider(options);

        LinearLayout permissionRow = new LinearLayout(this);
        boolean stackedPermission = getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale > 1.2f;
        permissionRow.setOrientation(stackedPermission ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        permissionRow.setGravity(Gravity.CENTER_VERTICAL);
        permissionRow.setPadding(0, dp(16), 0, 0);
        TextView permissionLabel = text("使用状況へのアクセス", 14);
        permissionRow.addView(permissionLabel, stackedPermission ? fullWidth()
                : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        usageAccessValue = text("", 12);
        usageAccessValue.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams permissionStateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (stackedPermission) permissionStateParams.topMargin = dp(8);
        else permissionStateParams.setMarginStart(dp(12));
        permissionRow.addView(usageAccessValue, permissionStateParams);
        options.addView(permissionRow, fullWidth());
        Button usageAccess = actionButton("アクセス許可を設定", false);
        usageAccess.setOnClickListener(v -> showUsageAccessHelp());
        LinearLayout.LayoutParams permissionButtonParams = fullWidth();
        permissionButtonParams.topMargin = dp(12);
        options.addView(usageAccess, permissionButtonParams);
        addSection(root, optionsSection.card);

        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.main_scroll);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff7f2fa);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll.addView(root);
        root.requestFocus();
        return scroll;
    }

    private View buildFlagCard() {
        LinearLayout card = card();
        flagging = new Switch(this);
        flagging.setText(R.string.flag_toggle);
        flagging.setTextColor(0xff1d1b20);
        flagging.setTextSize(18);
        flagging.setCompoundDrawablesRelativeWithIntrinsicBounds(
                new UiIcon(UiIcon.Kind.FLAG, 0xff6750a4, dp(28)), null, null, null);
        flagging.setCompoundDrawablePadding(dp(12));
        flagging.setSwitchPadding(dp(12));
        flagging.setTypeface(null, Typeface.BOLD);
        flagging.setMinHeight(dp(48));
        flagging.setPadding(0, dp(8), 0, 0);
        flagging.setOnCheckedChangeListener((button, checked) -> {
            if (updatingFlagUi) return;
            new AppSettings(this).setFlaggingEnabled(checked);
            refreshFlagUi();
        });
        card.addView(flagging);

        TextView flagDescription = text("同期した項目にフラグを付ける", 13);
        flagDescription.setPadding(0, dp(2), 0, dp(4));
        flagDescription.setTextColor(0xff625b71);
        card.addView(flagDescription);

        LinearLayout durationRow = new LinearLayout(this);
        durationRow.setOrientation(LinearLayout.HORIZONTAL);
        durationRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView durationLabel = text(getString(R.string.flag_auto_off), 16);
        durationRow.addView(durationLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        flagDuration = new Spinner(this);
        flagDuration.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, FLAG_DURATION_LABELS));
        flagDuration.setSelection(durationPosition(new AppSettings(this).flagDurationMillis()));
        flagDuration.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                  int position, long id) {
                if (updatingFlagUi) return;
                AppSettings settings = new AppSettings(MainActivity.this);
                if (settings.flagDurationMillis() != FLAG_DURATIONS[position]) {
                    settings.setFlagDurationMillis(FLAG_DURATIONS[position]);
                    refreshFlagUi();
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        LinearLayout durationSelector = new LinearLayout(this);
        durationSelector.setGravity(Gravity.CENTER_VERTICAL);
        durationSelector.setPadding(dp(4), 0, dp(10), 0);
        durationSelector.setBackground(roundedBackground(0xfff7f2fa, 0xfff7f2fa));
        flagDuration.setBackground(null);
        flagDuration.setMinimumHeight(dp(48));
        durationSelector.addView(flagDuration);
        ImageView durationArrow = new ImageView(this);
        durationArrow.setImageDrawable(new UiIcon(UiIcon.Kind.DOWN, 0xff625b71, dp(18)));
        durationArrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        durationSelector.addView(durationArrow, new LinearLayout.LayoutParams(dp(18), dp(18)));
        durationSelector.setOnClickListener(v -> flagDuration.performClick());
        durationRow.addView(durationSelector);
        durationRow.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams durationParams = fullWidth();
        durationParams.topMargin = dp(16);
        card.addView(durationRow, durationParams);

        flagRemaining = text("", 13);
        flagRemaining.setPadding(0, 0, 0, dp(8));
        flagRemaining.setTextColor(0xff6750a4);
        card.addView(flagRemaining);
        return card;
    }

    private View buildStatusCard() {
        LinearLayout card = card();
        boolean stacked = useStackedStatusLayout();
        LinearLayout headline = new LinearLayout(this);
        headline.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("同期状況", 18);
        title.setTypeface(null, Typeface.BOLD);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                new UiIcon(UiIcon.Kind.SYNC, 0xff6750a4, dp(28)), null, null, null);
        title.setCompoundDrawablePadding(dp(12));
        title.setAccessibilityHeading(true);
        headline.addView(title, stacked ? fullWidth() : new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        statusBadge = new LinearLayout(this);
        statusBadge.setGravity(Gravity.CENTER_VERTICAL);
        statusBadge.setPadding(dp(8), dp(6), dp(10), dp(6));
        statusIcon = new ImageView(this);
        statusIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconParams.setMarginEnd(dp(6));
        statusBadge.addView(statusIcon, iconParams);
        status = text("確認中…", 12);
        status.setMaxWidth(dp(128));
        status.setTypeface(null, Typeface.BOLD);
        statusBadge.addView(status);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (stacked) badgeParams.topMargin = dp(12);
        else badgeParams.setMarginStart(dp(12));
        headline.addView(statusBadge, badgeParams);
        card.addView(headline, fullWidth());

        statusError = text("", 14);
        statusError.setTextColor(0xff8a4300);
        statusError.setPadding(0, dp(8), 0, dp(4));
        statusError.setTextIsSelectable(true);
        statusError.setVisibility(View.GONE);
        card.addView(statusError);

        LinearLayout information = new LinearLayout(this);
        information.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        information.setGravity(Gravity.TOP);
        LinearLayout connection = new LinearLayout(this);
        connection.setOrientation(LinearLayout.VERTICAL);
        connectionValue = addSummaryValue(connection, "接続先");
        connectionValue.setTextSize(16);
        information.addView(connection, stacked ? fullWidth() : new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        View separator = new View(this);
        separator.setBackgroundColor(0xffe7e0ec);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                stacked ? LinearLayout.LayoutParams.MATCH_PARENT : dp(1),
                stacked ? dp(1) : LinearLayout.LayoutParams.MATCH_PARENT);
        if (stacked) {
            separatorParams.topMargin = dp(16);
            separatorParams.bottomMargin = dp(16);
        } else {
            separatorParams.setMarginStart(dp(12));
            separatorParams.setMarginEnd(dp(16));
        }
        information.addView(separator, separatorParams);
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.VERTICAL);
        syncCompletedValue = addSummaryValue(times, "最終同期");
        syncCompletedValue.setTextSize(28);
        syncCompletedValue.setTypeface(null, Typeface.BOLD);
        LinearLayout localTime = new LinearLayout(this);
        localTime.setOrientation(LinearLayout.VERTICAL);
        localSavedValue = addSummaryValue(localTime, "最終ローカル保存");
        localSavedValue.setTextSize(14);
        localSavedValue.setTextColor(0xff625b71);
        LinearLayout.LayoutParams localTimeParams = fullWidth();
        localTimeParams.topMargin = dp(16);
        times.addView(localTime, localTimeParams);
        information.addView(times, stacked ? fullWidth() : new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        LinearLayout.LayoutParams informationParams = fullWidth();
        informationParams.topMargin = dp(24);
        informationParams.bottomMargin = dp(8);
        card.addView(information, informationParams);
        addDivider(card);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = fullWidth();
        actionsParams.topMargin = dp(12);
        card.addView(actions, actionsParams);
        Button sync = actionButton("今すぐ同期", true);
        UiIcon syncIcon = new UiIcon(UiIcon.Kind.SYNC, 0xffffffff, dp(20));
        syncIcon.setBounds(0, 0, dp(20), dp(20));
        SpannableString syncLabel = new SpannableString("  今すぐ同期");
        syncLabel.setSpan(new ImageSpan(syncIcon, ImageSpan.ALIGN_BASELINE), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sync.setText(syncLabel);
        sync.setContentDescription("今すぐ同期");
        sync.setOnClickListener(v -> requestSync(false));
        detailsToggle = new Button(this, null, android.R.attr.borderlessButtonStyle);
        detailsToggle.setTextColor(0xff6750a4);
        detailsToggle.setTextSize(14);
        detailsToggle.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        detailsToggle.setPadding(0, 0, 0, 0);
        detailsToggle.setMinHeight(dp(48));
        detailsToggle.setMinimumHeight(dp(48));
        detailsToggle.setAllCaps(false);
        detailsToggle.setOnClickListener(v -> {
            detailsExpanded = !detailsExpanded;
            updateDetailsVisibility();
        });
        detailsToggle.setMinWidth(0);
        detailsToggle.setMinimumWidth(0);
        actions.addView(detailsToggle, stacked ? fullWidth() : new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams syncParams = stacked ? fullWidth() : new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f);
        if (stacked) syncParams.topMargin = dp(8);
        else syncParams.setMarginStart(dp(16));
        actions.addView(sync, syncParams);
        statusDetails = new LinearLayout(this);
        statusDetails.setOrientation(LinearLayout.VERTICAL);
        statusDetails.setPadding(dp(14), dp(2), dp(14), dp(16));
        statusDetails.setBackground(roundedBackground(0xfff7f5fa, 0xfff7f5fa));
        apiVersionValue = addStatusValue(statusDetails, "Provider API");
        generationValue = addStatusValue(statusDetails, "クリップボード世代");
        lastIdValue = addStatusValue(statusDetails, "最終取得ID");
        addDivider(statusDetails);
        TextView databaseLabel = detailLabel("データベースID");
        statusDetails.addView(databaseLabel, fullWidth());
        databaseIdValue = technicalValue("");
        statusDetails.addView(databaseIdValue, fullWidth());
        providerUrisLabel = detailLabel("");
        statusDetails.addView(providerUrisLabel, fullWidth());
        providerUris = new LinearLayout(this);
        providerUris.setOrientation(LinearLayout.VERTICAL);
        statusDetails.addView(providerUris, fullWidth());
        LinearLayout.LayoutParams detailParams = fullWidth();
        detailParams.topMargin = dp(12);
        detailParams.bottomMargin = dp(4);
        card.addView(statusDetails, detailParams);
        updateDetailsVisibility();
        return card;
    }

    private boolean useStackedStatusLayout() {
        return getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale > 1.2f;
    }

    private TextView addSummaryValue(LinearLayout column, String label) {
        TextView name = text(label, 13);
        name.setTextColor(0xff625b71);
        column.addView(name, fullWidth());
        TextView value = text("—", 18);
        value.setTextIsSelectable(true);
        value.setPadding(0, dp(6), 0, 0);
        column.addView(value, fullWidth());
        return value;
    }

    private TextView addStatusValue(LinearLayout card, String label) {
        LinearLayout row = new LinearLayout(this);
        boolean stacked = getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale > 1.2f;
        row.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(12), 0, 0);
        TextView name = text(label, 13);
        name.setTextColor(0xff625b71);
        row.addView(name, stacked ? fullWidth() : new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text("—", 14);
        value.setGravity(Gravity.START);
        if (stacked) value.setPadding(0, dp(4), 0, 0);
        value.setTextIsSelectable(true);
        row.addView(value, stacked ? fullWidth() : new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
        card.addView(row, fullWidth());
        return value;
    }

    private void updateDetailsVisibility() {
        UiIcon chevron = new UiIcon(detailsExpanded ? UiIcon.Kind.DOWN : UiIcon.Kind.RIGHT,
                0xff625b71, dp(16));
        chevron.setBounds(0, 0, dp(16), dp(16));
        SpannableString label = new SpannableString("詳細情報  \uFFFC");
        label.setSpan(new ImageSpan(chevron) {
            @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                       float x, int top, int y, int bottom, Paint paint) {
                // Align to the text's vertical center, independent of font size or line padding.
                float centerY = y + (paint.ascent() + paint.descent()) / 2f;
                int saved = canvas.save();
                canvas.translate(x, centerY - chevron.getBounds().height() / 2f);
                chevron.draw(canvas);
                canvas.restoreToCount(saved);
            }
        }, label.length() - 1,
                label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        detailsToggle.setText(label);
        detailsToggle.setContentDescription(detailsExpanded ? "詳細情報を閉じる" : "詳細情報を開く");
        statusDetails.setVisibility(detailsExpanded ? View.VISIBLE : View.GONE);
    }

    private void loadSettings() {
        AppSettings settings = new AppSettings(this);
        url.setText(settings.webDavUrl());
        user.setText(settings.webDavUser());
        foreground.setChecked(settings.foregroundInferenceEnabled());
        foreground.setOnCheckedChangeListener((button, checked) -> {
            new AppSettings(this).setForegroundInferenceEnabled(checked);
            refreshSettingsSummaries();
        });
        refreshSettingsSummaries();
        refreshFlagUi();
        try {
            password.setText(new SecretStore(this).getPassword());
        } catch (Exception error) {
            Toast.makeText(this, "保存済みパスワードを復号できません", Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndSync() {
        String value = url.getText().toString().trim();
        String username = user.getText().toString().trim();
        if (!value.isEmpty()) {
            try {
                value = NextcloudUrl.serverOrigin(value);
            } catch (IllegalArgumentException error) {
                url.setError(error.getMessage());
                return;
            }
            if (username.isEmpty()) {
                user.setError("ユーザー名を入力してください");
                return;
            }
        }
        try {
            new AppSettings(this).save(value, username, foreground.isChecked(),
                    password.getText().toString());
            url.setText(value);
            connectionSection.setExpanded(false);
            refreshSettingsSummaries();
            requestSync(true);
        } catch (Exception error) {
            Toast.makeText(this, "設定を保存できません: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestSync(boolean settingsSaved) {
        stateExecutor.execute(() -> {
            boolean scheduled = SyncScheduler.requestManualSync(getApplicationContext());
            statusHandler.post(() -> {
                if (isDestroyed()) return;
                String message = scheduled ? "同期を予約しました" : "同期を予約できませんでした";
                Toast.makeText(this, (settingsSaved ? "設定を保存しました。" : "") + message,
                        Toast.LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    private void refreshStatus() {
        refreshFlagUi();
        // Coalesce timer ticks while a DB operation is waiting; never queue unbounded reads.
        if (statusReadPending || isDestroyed()) return;
        statusReadPending = true;
        stateExecutor.execute(() -> {
            try (SyncStateStore states = new SyncStateStore(getApplicationContext())) {
                SyncStateStore.State state = states.read();
                statusHandler.post(() -> finishStatusRead(state, null));
            } catch (SQLiteException error) {
                statusHandler.post(() -> finishStatusRead(null, error));
            }
        });
    }

    private void finishStatusRead(SyncStateStore.State state, SQLiteException error) {
        statusReadPending = false;
        if (!statusResumed || isDestroyed()) return;
        if (error != null) showStateReadError(error);
        else showState(state);
    }

    private void showState(SyncStateStore.State state) {
        AppSettings settings = new AppSettings(this);
        String connectedAuthority = settings.connectedProviderAuthority();
        boolean configured = !settings.webDavUrl().isEmpty() && !settings.webDavUser().isEmpty();
        status.setText(syncProgress(state, configured));
        boolean syncing = state.requestRevision > state.handledRequestRevision
                || (configured && (state.dirtyArchiveCount > 0
                || (state.requestRevision > 0 && state.lastSyncCompletedAt == null)));
        boolean completed = configured && !syncing && state.requestRevision > 0
                && state.lastSyncCompletedAt != null;
        int stateColor = state.lastError != null ? 0xff8a4300
                : syncing ? 0xff6750a4 : completed ? 0xff2d9d58 : 0xff625b71;
        UiIcon.Kind stateSymbol = state.lastError != null ? UiIcon.Kind.ERROR
                : syncing ? UiIcon.Kind.SYNC : completed ? UiIcon.Kind.CHECK : UiIcon.Kind.CLOCK;
        statusIcon.setImageDrawable(new UiIcon(stateSymbol, stateColor, dp(16)));
        status.setTextColor(stateColor);
        int badgeBackground = state.lastError != null ? 0xfffff1e5
                : completed ? 0xffeaf5ee : 0xfff2edf7;
        statusBadge.setBackground(roundedBackground(badgeBackground, badgeBackground));
        statusError.setVisibility(state.lastError == null ? View.GONE : View.VISIBLE);
        statusError.setText(state.lastError == null ? "" : state.lastError
                + "\n\n接続先や設定を確認し、「今すぐ同期」で再試行してください。");
        connectionValue.setText(connectedAuthority == null ? "未接続"
                : providerName(connectedAuthority));
        localSavedValue.setText(formatStatusTime(state.lastSyncAt));
        syncCompletedValue.setText(formatStatusTime(state.lastSyncCompletedAt));
        boolean usageAllowed = hasUsageAccess();
        usageAccessValue.setText(usageAllowed ? "許可済み" : "未許可");
        usageAccessValue.setTextColor(usageAllowed ? 0xff246b45 : 0xff8a4300);
        int permissionBackground = usageAllowed ? 0xffeaf5ee : 0xfffff1e5;
        usageAccessValue.setBackground(roundedBackground(permissionBackground, permissionBackground));
        setDetailValue(apiVersionValue, state.apiVersion == null ? "未接続" : state.apiVersion.toString());
        setDetailValue(generationValue, state.generation == null ? "—" : state.generation.toString());
        setDetailValue(lastIdValue, Long.toString(state.lastId));
        setDetailValue(databaseIdValue, state.instanceId == null ? "—" : state.instanceId);
        setDetailValue(providerUrisLabel, connectedAuthority == null ? "接続先候補のURI" : "接続先のURI");
        List<String> uris = new ArrayList<>();
        if (connectedAuthority == null) {
            for (String authority : SumireContract.authorityCandidates(null)) {
                uris.add(SumireContract.statusUri(authority).toString());
            }
        } else {
            uris.add(SumireContract.statusUri(connectedAuthority).toString());
        }
        // Keep selectable text views intact while the status refreshes in the background.
        if (!uris.equals(displayedProviderUris)) {
            providerUris.removeAllViews();
            for (String uri : uris) {
                TextView value = technicalValue(uri);
                LinearLayout.LayoutParams params = fullWidth();
                if (providerUris.getChildCount() > 0) params.topMargin = dp(12);
                providerUris.addView(value, params);
            }
            displayedProviderUris = uris;
        }
    }

    private void showStateReadError(SQLiteException error) {
        status.setText("同期を停止しています");
        status.setTextColor(0xff8a4300);
        statusIcon.setImageDrawable(new UiIcon(UiIcon.Kind.ERROR, 0xff8a4300, dp(16)));
        statusBadge.setBackground(roundedBackground(0xfffff1e5, 0xfffff1e5));
        statusError.setVisibility(View.VISIBLE);
        statusError.setText("同期状態を読み込めません。\n" + error.getMessage());
        localSavedValue.setText("読み取り不可");
        syncCompletedValue.setText("読み取り不可");
        for (TextView value : new TextView[]{apiVersionValue, generationValue, lastIdValue, databaseIdValue}) {
            setDetailValue(value, "読み取り不可");
        }
    }

    private void setDetailValue(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private TextView detailLabel(String label) {
        TextView view = text(label, 12);
        view.setTextColor(0xff625b71);
        view.setPadding(0, dp(16), 0, dp(6));
        return view;
    }

    private TextView technicalValue(String value) {
        TextView view = text(value, 13);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(0xff49454f);
        view.setTextIsSelectable(true);
        view.setLineSpacing(dp(3), 1);
        view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
        return view;
    }

    private String formatStatusTime(Long timestamp) {
        if (timestamp == null) return "—";
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(timestamp);
        Calendar today = Calendar.getInstance();
        if (date.get(Calendar.ERA) == today.get(Calendar.ERA)
                && date.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return "今日 " + android.text.format.DateFormat.getTimeFormat(this).format(new Date(timestamp));
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    private String providerName(String authority) {
        String packageId = SumireContract.packageIdForAuthority(authority);
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageId, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return packageId;
        }
    }

    private void refreshFlagUi() {
        if (flagging == null || flagDuration == null || flagRemaining == null) return;
        AppSettings settings = new AppSettings(this);
        boolean enabled = settings.flaggingEnabled();
        updatingFlagUi = true;
        try {
            flagging.setChecked(enabled);
            flagDuration.setSelection(durationPosition(settings.flagDurationMillis()));
        } finally {
            updatingFlagUi = false;
        }
        if (enabled) {
            long remaining = Math.max(0, settings.flagExpiresAt() - System.currentTimeMillis());
            flagRemaining.setText("あと" + remainingLabel(remaining) + "でOFF");
            flagRemaining.setVisibility(View.VISIBLE);
        } else {
            flagRemaining.setVisibility(View.GONE);
        }
    }

    private static int durationPosition(long durationMillis) {
        for (int i = 0; i < FLAG_DURATIONS.length; i++) {
            if (FLAG_DURATIONS[i] == durationMillis) return i;
        }
        return 1;
    }

    private static String remainingLabel(long remainingMillis) {
        long minutes = Math.max(1, (remainingMillis + 60_000 - 1) / 60_000);
        if (minutes < 60) return minutes + "分";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + "時間" : hours + "時間" + rest + "分";
    }

    private String syncProgress(SyncStateStore.State state, boolean configured) {
        if (state.lastError != null) return "同期に失敗しました";
        if (state.requestRevision > state.handledRequestRevision) return "ローカル同期中…";
        if (!configured) return "Nextcloud未設定";
        if (state.dirtyArchiveCount > 0) {
            return "Nextcloudへアップロード中／待機中（"
                    + state.dirtyArchiveCount + "ファイル）";
        }
        if (state.requestRevision > 0) {
            return state.lastSyncCompletedAt == null ? "同期完了を確認中…" : "同期完了";
        }
        return "待機中";
    }

    private boolean hasUsageAccess() {
        AppOpsManager manager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private void showUsageAccessHelp() {
        if (hasUsageAccess() || Build.VERSION.SDK_INT < 33) {
            openUsageAccessSettings();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("スイッチがグレーの場合")
                .setMessage("APKからインストールしたアプリでは、Androidの制限付き設定により"
                        + "使用状況アクセスが無効になることがあります。\n\n"
                        + "「アプリ情報を開く」→右上の︙→「制限付き設定を許可」を選び、"
                        + "この画面へ戻ってもう一度設定ボタンを押してください。\n\n"
                        + "端末によって項目名や場所が異なる場合があります。")
                .setPositiveButton("アプリ情報を開く", (dialog, which) -> openAppDetails())
                .setNegativeButton("使用状況アクセスへ", (dialog, which) -> openUsageAccessSettings())
                .setNeutralButton("キャンセル", null)
                .show();
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openUsageAccessSettings() {
        Intent appSettings = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(appSettings);
        } catch (ActivityNotFoundException error) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void refreshSettingsSummaries() {
        AppSettings settings = new AppSettings(this);
        boolean configured = !settings.webDavUrl().isEmpty() && !settings.webDavUser().isEmpty();
        connectionSection.setSummary(configured ? "Nextcloud・設定済み" : "Nextcloud・未設定");
        optionsSection.setSummary("コピー元アプリの推定："
                + (settings.foregroundInferenceEnabled() ? "ON" : "OFF"));
    }

    private final class Disclosure {
        final LinearLayout card = card();
        final LinearLayout body = new LinearLayout(MainActivity.this);
        final LinearLayout header = new LinearLayout(MainActivity.this) {
            @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
        };
        final TextView summary = text("", 13);
        final ImageView chevron = new ImageView(MainActivity.this);
        final String title;
        boolean expanded;

        Disclosure(String title, UiIcon.Kind icon) {
            this.title = title;
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(dp(48));
            header.setFocusable(true);
            header.setBackground(new RippleDrawable(ColorStateList.valueOf(0x226750a4), null,
                    roundedBackground(0xffffffff, 0xffffffff)));
            ImageView leading = new ImageView(MainActivity.this);
            leading.setImageDrawable(new UiIcon(icon, 0xff6750a4, dp(28)));
            header.addView(leading, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(16), 0, dp(8), 0);
            TextView name = text(title, 16);
            name.setTypeface(null, Typeface.BOLD);
            labels.addView(name);
            summary.setTextColor(0xff625b71);
            summary.setPadding(0, dp(4), 0, 0);
            labels.addView(summary);
            header.addView(labels, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            header.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
            leading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            header.setOnClickListener(v -> setExpanded(!expanded));
            card.addView(header, fullWidth());
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, dp(12), 0, 0);
            card.addView(body, fullWidth());
            setExpanded(false);
        }

        void setSummary(String value) {
            summary.setText(value);
            updateDescription();
        }

        void setExpanded(boolean value) {
            expanded = value;
            if (!value && body.hasFocus()) {
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(body.getWindowToken(), 0);
                header.requestFocus();
            }
            body.setVisibility(value ? View.VISIBLE : View.GONE);
            chevron.setImageDrawable(new UiIcon(value ? UiIcon.Kind.DOWN : UiIcon.Kind.RIGHT,
                    0xff625b71, dp(20)));
            updateDescription();
        }

        private void updateDescription() {
            header.setContentDescription(title + "、" + summary.getText()
                    + (expanded ? "、閉じる" : "、開く"));
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable roundedBackground(int color, int border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), border);
        return background;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundedBackground(0xffffffff, 0xfff0eaf5));
        return card;
    }

    private LinearLayout section(String title) {
        LinearLayout card = card();
        TextView heading = text(title, 16);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        card.addView(heading);
        return card;
    }

    private void addSection(LinearLayout root, View section) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(16);
        root.addView(section, params);
    }

    private void addDivider(LinearLayout card) {
        View divider = new View(this);
        divider.setBackgroundColor(0xffe7e0ec);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(12);
        card.addView(divider, params);
    }

    private Button actionButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setTextColor(primary ? 0xffffffff : 0xff6750a4);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x226750a4),
                roundedBackground(primary ? 0xff6750a4 : 0xfff2edf7,
                        primary ? 0xff6750a4 : 0xfff2edf7), null));
        return button;
    }

    private EditText labeledField(LinearLayout card, int id, String label, String hint) {
        TextView name = text(label, 13);
        name.setTextColor(0xff625b71);
        name.setPadding(0, dp(16), 0, dp(6));
        card.addView(name);
        EditText view = new EditText(this);
        view.setId(id);
        name.setLabelFor(view.getId());
        view.setHint(hint);
        view.setTextSize(16);
        view.setTextColor(0xff1d1b20);
        view.setHintTextColor(0xff79747e);
        view.setSingleLine(true);
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused},
                roundedBackground(0xfffdfbff, 0xff6750a4));
        background.addState(new int[]{}, roundedBackground(0xfffdfbff, 0xffcac4d0));
        view.setBackground(background);
        card.addView(view, fullWidth());
        return view;
    }

    private TextView text(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xff1d1b20);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
