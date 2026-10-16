package top.wherewego.vnt2_app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * VNT2 通知服务
 * 显示连接状态并支持快速切换
 */
public class VntNotificationService extends Service {
    private static final String TAG = "VntNotificationService";
    // NotificationChannel 的 importance 创建后不可修改，且旧的 vnt_notification_channel
    // 是 IMPORTANCE_LOW，会被 Flyme 归入"不重要通知"，折叠态下自定义按钮不生效。
    // 因此换到新 id 并用默认重要性重建，同时删除旧渠道。
    private static final String CHANNEL_ID = "vnt_notification_channel_v2";
    private static final String LEGACY_CHANNEL_ID = "vnt_notification_channel";
    private static final int NOTIFICATION_ID = 1001;

    // 通知动作
    private static final String ACTION_TOGGLE = "top.wherewego.vnt2_app.NOTIFICATION_TOGGLE";
    private static final String ACTION_UPDATE = "top.wherewego.vnt2_app.NOTIFICATION_UPDATE";
    private static final String ACTION_DISMISS = "top.wherewego.vnt2_app.NOTIFICATION_DISMISS";

    // 只有 Flyme 会把"不重要通知"收进收纳分组、且之后不再重建那一行（表现为连接状态真的
    // 变了、通知显示却不变）。其它 ROM 更新通知是正常的，不需要"移除再发布"那一下闪烁。
    private static final boolean FORCE_REPOST_ON_CHANGE = isFlyme();

    // SharedPreferences 相关
    private static final String PREFS_NAME = "FlutterSharedPreferences";
    private static final String DATA_KEY_NATIVE = "flutter.data-key-native";
    private static final String DEFAULT_KEY = "flutter.default-key";

    // 静态实例，用于外部直接调用
    private static VntNotificationService instance;

    // 静态状态缓存：进程被系统杀掉后 Service 重建时，仍能恢复到正确的连接状态
    private static volatile boolean lastKnownConnected = false;
    private static String lastKnownConfigName = null;
    // 防止 updateNotification 与 异步回查 交替覆盖状态
    private volatile long stateVersion = 0;

    private NotificationManager notificationManager;
    private boolean isConnected = false;
    private String configName = "默认配置";
    // 上一次真正下发到通知栏的状态：用于判断状态是否真的变化，
    // 变化时强制重发通知，好让 Flyme 等 ROM 在"收纳/不重要通知"分组里重建那一行
    private Boolean lastPaintedConnected = null;
    private String lastPaintedConfigName = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "VntNotificationService 创建");

        // 保存静态实例
        instance = this;

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // 创建通知渠道（Android 8.0+）
        createNotificationChannel();

        // 读取配置名称
        loadConfigName();

        // 恢复到上次已知的连接状态：避免进程被杀重建后误显示"未连接"，
        // 也避免首次发布通知时就用默认 false 值
        isConnected = lastKnownConnected;
        if (lastKnownConfigName != null && !lastKnownConfigName.isEmpty()) {
            configName = lastKnownConfigName;
        }

        // 发布通知（不再走前台服务：前台服务通知会被 ROM 归入"常驻/置顶"分组，
        // 与其它常驻通知在同一分组里互相重叠压住）
        try {
            Notification notification = buildNotification();
            if (notification != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
                lastPaintedConnected = isConnected;
                lastPaintedConfigName = configName;
                Log.i(TAG, "通知已发布: isConnected=" + isConnected);
            } else {
                Log.e(TAG, "通知创建失败");
                // 如果通知创建失败，停止服务避免残留
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "发布通知失败: " + e.getMessage(), e);
            // 捕获异常，避免崩溃
            stopSelf();
        }

        // Flutter 已初始化时异步校正一次状态（不阻塞通知发布）
        if (FlutterMethodChannel.initialized()) {
            updateNotificationFromFlutter();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "VntNotificationService onStartCommand");

        // 处理来自通知按钮的 Intent
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            Log.i(TAG, "收到切换连接状态的 Intent");
            handleToggle();
            return START_STICKY;
        }

        // 用户划掉了通知：移除通知并停止服务，不再重新弹出
        if (intent != null && ACTION_DISMISS.equals(intent.getAction())) {
            Log.i(TAG, "通知被划掉，停止通知服务");
            synchronized (this) {
                if (!isConnected) {
                    // 仅未连接时清缓存：已连接状态下划掉只是收起通知，真实状态仍然有效
                    lastKnownConnected = false;
                    lastPaintedConnected = null;
                }
            }
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        // 如果 Flutter 已初始化，获取当前连接状态
        if (FlutterMethodChannel.initialized()) {
            updateNotificationFromFlutter();
        }

        return START_STICKY; // 服务被杀死后自动重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "VntNotificationService 销毁");

        // 未连接时通知已降级为可划掉的普通通知，服务销毁后手动清理，避免残留
        if (!isConnected && notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }

        // 清除静态实例（保留 lastKnown* 缓存，供 Service 重建时恢复状态）
        instance = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // manifest 里 stopWithTask=false，划掉任务不会停服务；
        // 这里再主动重绘一次，避免部分 ROM 在清理任务时把通知一并丢掉
        Log.i(TAG, "任务被移除，保持通知服务存活");
        updateNotification();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 是否 Flyme（魅族）ROM。只有它需要"移除再发布"来逼通知栏重建收纳分组里的那一行。
     */
    private static boolean isFlyme() {
        String info = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.PRODUCT + " " + Build.DISPLAY)
            .toLowerCase();
        return info.contains("meizu") || info.contains("mblu") || info.contains("flyme");
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VNT2 连接状态",
                NotificationManager.IMPORTANCE_DEFAULT // 默认重要性：不再被归入"不重要通知"
            );
            channel.setDescription("显示 VNT2 连接状态和快速切换按钮");
            channel.setShowBadge(false); // 不显示角标
            channel.enableLights(false); // 不显示指示灯
            channel.enableVibration(false); // 不振动
            channel.setSound(null, null); // 重要性归默认，但保持静音，避免状态更新时反复响铃

            notificationManager.createNotificationChannel(channel);

            // 删除旧的 LOW 渠道：渠道一旦创建就无法提升重要性，
            // 不删除会让用户仍停留在"不重要通知"分组里
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
        }
    }

    /**
     * 构建通知
     */
    private Notification buildNotification() {
        try {
            // 创建点击通知的 PendingIntent（打开应用）
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,  // requestCode=0 用于打开应用
                notificationIntent,
                pendingIntentFlags
            );

            // 创建切换按钮的 PendingIntent
            // 使用 getService() 而不是 getBroadcast()，避免广播限制问题
            Intent toggleIntent = new Intent(this, VntNotificationService.class);
            toggleIntent.setAction(ACTION_TOGGLE);

            PendingIntent togglePendingIntent = PendingIntent.getService(
                this,
                1,  // requestCode=1 用于切换按钮，避免与 contentIntent 冲突
                toggleIntent,
                pendingIntentFlags
            );

            // 划掉通知时的回调：未连接状态允许划掉，划掉后停止服务，避免再次弹出
            Intent dismissIntent = new Intent(this, VntNotificationService.class);
            dismissIntent.setAction(ACTION_DISMISS);
            PendingIntent dismissPendingIntent = PendingIntent.getService(
                this,
                2,  // requestCode=2 用于划掉通知，避免与其他 PendingIntent 冲突
                dismissIntent,
                pendingIntentFlags
            );

            Log.d(TAG, "创建 PendingIntent: 使用 getService() 方式，action=" + ACTION_TOGGLE);

            // 状态文本
            String statusText = isConnected ? "已连接" : "未连接";
            String description = isConnected ? "已连接到 " + configName : "点击连接到 " + configName;
            int statusColor = isConnected ? 0xFF4CAF50 : 0xFFF44336; // 绿色或红色
            String buttonText = isConnected ? "断开" : "连接";

            // 自定义布局：连接/断开按钮内嵌在通知里，折叠态下也直接可见可点
            RemoteViews notificationLayout = new RemoteViews(getPackageName(), R.layout.notification_layout);
            notificationLayout.setTextViewText(R.id.notification_status, statusText);
            notificationLayout.setTextColor(R.id.notification_status, statusColor);
            notificationLayout.setTextViewText(R.id.notification_description, description);
            notificationLayout.setTextColor(R.id.notification_description, 0xFF1565C0); // 深蓝色
            int buttonBackground = isConnected
                ? R.drawable.notification_button_connected
                : R.drawable.notification_button_disconnected;
            notificationLayout.setTextViewText(R.id.notification_button, buttonText);
            notificationLayout.setInt(R.id.notification_button, "setBackgroundResource", buttonBackground);
            notificationLayout.setOnClickPendingIntent(R.id.notification_button, togglePendingIntent);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_icon) // 状态栏图标（使用专门的通知图标）
                .setContentIntent(contentIntent)
                .setCustomContentView(notificationLayout) // 使用自定义布局
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle()) // 使用装饰样式
                // 不设 setOngoing：常驻标记会被 ROM 归入"置顶"分组，与其它常驻通知重叠
                .setDeleteIntent(dismissPendingIntent) // 划掉后停止服务，避免再次弹出
                .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 与渠道 importance 保持一致
                .setSilent(true) // 保持静音，避免状态更新时响铃
                .setOnlyAlertOnce(true) // 同一通知只提醒一次
                .setShowWhen(false) // 不显示时间
                .setAutoCancel(false); // 点击后不自动取消

            // Android 5.0+ 设置通知颜色（影响小图标）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setColor(statusColor);
            }

            Log.d(TAG, "创建通知: isConnected=" + isConnected + ", action=" + buttonText);
            return builder.build();
        } catch (Exception e) {
            Log.e(TAG, "创建通知失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 应用一份新的通知状态。
     * @param connected   连接状态
     * @param configName2 配置名，null 表示保持不变
     */
    private void applyState(boolean connected, String configName2) {
        long version;
        synchronized (this) {
            version = ++stateVersion;
            isConnected = connected;
            if (configName2 != null && !configName2.isEmpty()) {
                configName = configName2;
            }
            lastKnownConnected = connected;
            lastKnownConfigName = configName;
        }
        updateNotification(version);
    }

    /**
     * 更新通知（沿用服务内当前状态）
     */
    private void updateNotification() {
        updateNotification(0);
    }

    /**
     * 更新通知。若在构建/下发期间状态又被更新（version 已推进），
     * 本次绘制就已过期，直接丢弃，避免旧状态覆盖新状态。
     */
    private void updateNotification(long version) {
        try {
            Notification notification = buildNotification();
            if (notification == null) {
                Log.e(TAG, "通知创建失败，无法更新");
                return;
            }
            if (version != 0 && version != stateVersion) {
                Log.i(TAG, "丢弃过期的通知绘制: version=" + version + ", current=" + stateVersion);
                return;
            }
            // 状态是否真的变化：用于判断要不要"移除再发布"来逼通知栏重建那一行
            boolean changed = version != 0
                && (lastPaintedConnected == null
                    || lastPaintedConnected != isConnected
                    || !configName.equals(lastPaintedConfigName));
            if (changed && FORCE_REPOST_ON_CHANGE) {
                Log.i(TAG, "状态变化，强制重发通知以触发通知栏重建");
                notificationManager.cancel(NOTIFICATION_ID);
            }

            notificationManager.notify(NOTIFICATION_ID, notification);
            lastPaintedConnected = isConnected;
            lastPaintedConfigName = configName;
            Log.i(TAG, "通知已更新: isConnected=" + isConnected + ", configName=" + configName);
        } catch (Exception e) {
            Log.e(TAG, "更新通知失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 Flutter 获取最新状态并更新通知。
     * 仅用于校正（如进程重建后的首次刷新），不再是连接的唯一判据。
     */
    private void updateNotificationFromFlutter() {
        Log.i(TAG, "开始从 Flutter 更新通知状态");

        if (!FlutterMethodChannel.initialized()) {
            Log.w(TAG, "Flutter 未初始化，无法更新通知");
            return;
        }

        final long version;
        synchronized (this) {
            version = ++stateVersion;
        }

        FlutterMethodChannel.getDeviceInfo(deviceInfo -> {
            // 异步返回期间如果已有更新的推送状态到达，丢弃本次过期结果
            if (version != stateVersion) {
                Log.i(TAG, "放弃过期的异步回查结果: version=" + version);
                return null;
            }
            boolean newIsConnected = Boolean.TRUE.equals(deviceInfo.get("isConnected"));
            String newConfigName = (String) deviceInfo.get("configName");

            if (newConfigName == null || newConfigName.isEmpty()) {
                newConfigName = "默认配置";
            }

            Log.i(TAG, "从 Flutter 获取到状态: isConnected=" + newIsConnected + ", configName=" + newConfigName);

            // 更新状态
            isConnected = newIsConnected;
            configName = newConfigName;
            lastKnownConnected = newIsConnected;
            lastKnownConfigName = newConfigName;

            // 更新通知
            updateNotification(version);

            return null;
        });
    }

    /**
     * 从 SharedPreferences 加载配置名称
     */
    private void loadConfigName() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String defaultKey = prefs.getString(DEFAULT_KEY, "");

            if (!defaultKey.isEmpty()) {
                String jsonArrayStr = prefs.getString(DATA_KEY_NATIVE, null);
                if (jsonArrayStr != null && !jsonArrayStr.isEmpty()) {
                    JSONArray jsonArray = new JSONArray(jsonArrayStr);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        String configJsonStr = jsonArray.getString(i);
                        JSONObject configJson = new JSONObject(configJsonStr);
                        String key = configJson.optString("itemKey", "");
                        if (key.equals(defaultKey)) {
                            configName = configJson.optString("config_name", "未命名配置");
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载配置名称失败", e);
            configName = "默认配置";
        }
    }

    /**
     * 处理切换连接状态
     */
    private void handleToggle() {
        Log.i(TAG, "收到切换连接状态的请求");

        // 检查 Flutter 是否已初始化
        if (!FlutterMethodChannel.initialized()) {
            Log.i(TAG, "Flutter 未初始化，启动应用");
            // 启动应用
            FlutterMethodChannel.setTileStart(true);
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }

        // 检查当前连接状态
        FlutterMethodChannel.isRunning(isRunning -> {
            if (isRunning) {
                Log.i(TAG, "当前已连接，立即更新通知为未连接状态，然后执行断开操作");
                // 立即更新通知为未连接状态（乐观更新）
                applyState(false, null);
                // 执行断开操作
                FlutterMethodChannel.stopVnt();
                // 更新磁贴和小组件状态
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    MyTileService.setState(false);
                }
                VntWidget.updateAllWidgets(getApplicationContext());
            } else {
                Log.i(TAG, "当前未连接，立即更新通知为已连接状态，然后执行连接操作");
                // 立即更新通知为已连接状态（乐观更新）
                applyState(true, null);
                // 执行连接操作
                FlutterMethodChannel.startVnt(null, success -> {
                    Log.i(TAG, "连接结果: " + success);
                    // 根据实际连接结果更新通知状态
                    if (success) {
                        Log.i(TAG, "连接成功，确认通知为已连接状态");
                        applyState(true, null);
                        // 更新磁贴和小组件状态
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            MyTileService.setState(true);
                        }
                    } else {
                        Log.i(TAG, "连接失败，恢复通知为未连接状态");
                        applyState(false, null);
                        // 更新磁贴和小组件状态
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            MyTileService.setState(false);
                        }
                    }
                    VntWidget.updateAllWidgets(getApplicationContext());
                    return null;
                });
            }
            return null;
        });
    }

    /**
     * 静态方法：更新通知状态（供外部调用）。
     *
     * @param isConnected true=已连接 / false=未连接 / null=沿用服务内已缓存状态。
     *                    null 用于调用方暂时无法确定状态的场景（如 Flutter handler
     *                    刚注册、异步回查尚未返回），避免按"未连接"下发把已连接的
     *                    通知误刷成未连接。
     */
    public static void updateNotification(Context context, @Nullable Boolean isConnected) {
        try {
            if (instance == null) {
                if (isConnected != null) {
                    lastKnownConnected = isConnected;
                }
                Log.w(TAG, "Service 实例不存在，仅缓存状态并尝试启动 Service: isConnected=" + isConnected);
                Intent serviceIntent = new Intent(context, VntNotificationService.class);
                context.startService(serviceIntent);
                return;
            }

            if (isConnected == null) {
                // 沿用缓存状态，仅重绘一次（配置名可能变了）
                Log.d(TAG, "沿用缓存状态重绘通知: isConnected=" + instance.isConnected);
                instance.updateNotification();
                return;
            }

            Log.d(TAG, "直接按推送状态更新通知: isConnected=" + isConnected);
            instance.applyState(isConnected, null);
        } catch (Exception e) {
            Log.e(TAG, "更新通知失败: " + e.getMessage(), e);
        }
    }
}
