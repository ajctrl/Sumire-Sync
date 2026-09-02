package com.ajctrl.sumiresync;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

public final class MainActivity extends Activity {
    private static final long STATUS_REFRESH_MILLIS = 1_500;
    private EditText url;
    private EditText user;
    private EditText password;
    private CheckBox foreground;
    private TextView source;
    private TextView status;
    private Handler statusHandler;
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            if (status != null) refreshStatus();
            statusHandler.postDelayed(this, STATUS_REFRESH_MILLIS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        statusHandler = new Handler(Looper.getMainLooper());
        setContentView(buildContent());
        loadSettings();
        SyncScheduler.requestCatchUp(this);
        SyncScheduler.schedulePeriodicCatchUp(this);
    }

    @Override protected void onResume() {
        super.onResume();
        statusHandler.removeCallbacks(statusRefresh);
        statusRefresh.run();
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    private View buildContent() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("Sumire Sync", 28);
        root.addView(title);
        source = text(sourceLabel(new AppSettings(this).connectedProviderAuthority()), 13);
        source.setPadding(0, dp(4), 0, dp(20));
        root.addView(source);

        url = field("NextcloudサーバーURL (例: https://url.com:8443)");
        root.addView(url);
        user = field("ユーザー名");
        root.addView(user);
        password = field("パスワード / アプリパスワード");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);

        foreground = new CheckBox(this);
        foreground.setText(R.string.foreground_inference);
        root.addView(foreground);

        Button usageAccess = new Button(this);
        usageAccess.setText("使用状況へのアクセスを設定");
        usageAccess.setOnClickListener(v -> showUsageAccessHelp());
        root.addView(usageAccess);

        Button save = new Button(this);
        save.setText("設定を保存して同期");
        save.setOnClickListener(v -> saveAndSync());
        root.addView(save);

        Button sync = new Button(this);
        sync.setText(R.string.sync_now);
        sync.setOnClickListener(v -> {
            SyncScheduler.requestManualSync(this);
            Toast.makeText(this, "同期を開始しました", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        root.addView(sync);

        status = text("", 14);
        status.setPadding(0, dp(20), 0, 0);
        status.setTextIsSelectable(true);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void loadSettings() {
        AppSettings settings = new AppSettings(this);
        url.setText(settings.webDavUrl());
        user.setText(settings.webDavUser());
        foreground.setChecked(settings.foregroundInferenceEnabled());
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
            new AppSettings(this).save(value, username, foreground.isChecked());
            new SecretStore(this).setPassword(password.getText().toString());
            url.setText(value);
            SyncScheduler.requestManualSync(this);
            Toast.makeText(this, "設定を保存して同期を開始しました", Toast.LENGTH_SHORT).show();
            refreshStatus();
        } catch (Exception error) {
            Toast.makeText(this, "設定を保存できません: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        SyncStateStore.State state;
        try (SyncStateStore states = new SyncStateStore(this)) {
            state = states.read();
        }
        AppSettings settings = new AppSettings(this);
        String connectedAuthority = settings.connectedProviderAuthority();
        source.setText(sourceLabel(connectedAuthority));
        StringBuilder value = new StringBuilder();
        value.append("同期状況: ").append(syncProgress(state)).append('\n');
        value.append("Provider API: ").append(state.apiVersion == null ? "未接続" : state.apiVersion).append('\n');
        value.append("databaseInstanceId: ").append(state.instanceId == null ? "—" : state.instanceId).append('\n');
        value.append("clipboardGeneration: ").append(state.generation == null ? "—" : state.generation).append('\n');
        value.append("lastId: ").append(state.lastId).append('\n');
        value.append("最終ローカル保存: ").append(state.lastSyncAt == null ? "—" :
                DateFormat.getDateTimeInstance().format(new Date(state.lastSyncAt))).append('\n');
        value.append("Usage Access: ").append(hasUsageAccess() ? "許可" : "未許可").append('\n');
        if (state.lastError != null) value.append("エラー: ").append(state.lastError).append('\n');
        if (connectedAuthority == null) {
            value.append("Provider URI候補:\n");
            for (String authority : SumireContract.authorityCandidates(null)) {
                value.append("  ").append(SumireContract.statusUri(authority)).append('\n');
            }
        } else {
            value.append("接続済みProvider URI: ")
                    .append(SumireContract.statusUri(connectedAuthority));
        }
        status.setText(value.toString());
    }

    private String sourceLabel(String connectedAuthority) {
        if (connectedAuthority != null && !connectedAuthority.trim().isEmpty()) {
            return "接続先: " + SumireContract.packageIdForAuthority(connectedAuthority);
        }
        return "接続先候補: " + TextUtils.join(", ", SumireContract.packageIdCandidates());
    }

    private String syncProgress(SyncStateStore.State state) {
        if (state.lastError != null) return "失敗（下のエラー詳細を確認してください）";
        if (state.requestRevision > state.handledRequestRevision) return "ローカル同期中…";
        if (state.dirtyArchiveCount > 0) {
            return "Nextcloudへアップロード中／待機中（"
                    + state.dirtyArchiveCount + "ファイル）";
        }
        if (state.requestRevision > 0) return "同期完了";
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

    private EditText field(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        return view;
    }

    private TextView text(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
