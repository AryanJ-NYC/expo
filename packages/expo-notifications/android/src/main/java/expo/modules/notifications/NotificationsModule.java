// Copyright 2015-present 650 Industries. All rights reserved.

package versioned.host.exp.exponent.modules.api.notifications;

import com.cronutils.model.Cron;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.support.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;

import org.json.JSONException;
import org.json.JSONObject;
import org.unimodules.core.ModuleRegistry;
import org.unimodules.core.arguments.MapArguments;
import org.unimodules.core.interfaces.RegistryLifecycleListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.inject.Inject;

import host.exp.exponent.Constants;
import host.exp.exponent.ExponentManifest;
import host.exp.exponent.di.NativeModuleDepsProvider;
import host.exp.exponent.network.ExponentNetwork;
import host.exp.exponent.notifications.NotificationActionCenter;
import host.exp.exponent.notifications.NotificationHelper;
import host.exp.exponent.notifications.channels.ChannelManager;
import host.exp.exponent.notifications.channels.ChannelPOJO;
import host.exp.exponent.notifications.channels.ChannelScopeManager;
import host.exp.exponent.notifications.schedulers.IntervalSchedulerModel;
import host.exp.exponent.notifications.schedulers.SchedulerImpl;
import host.exp.exponent.notifications.postoffice.Mailbox;
import host.exp.exponent.notifications.postoffice.PostOfficeProxy;
import host.exp.exponent.notifications.presenters.NotificationPresenterImpl;
import host.exp.exponent.notifications.presenters.NotificationPresenter;
import host.exp.exponent.storage.ExponentSharedPreferences;
import host.exp.exponent.notifications.exceptions.UnableToScheduleException;
import host.exp.exponent.notifications.managers.SchedulersManagerProxy;
import host.exp.exponent.notifications.schedulers.CalendarSchedulerModel;
import host.exp.expoview.R;

import static host.exp.exponent.notifications.NotificationConstants.NOTIFICATION_CHANNEL_ID;
import static host.exp.exponent.notifications.NotificationConstants.NOTIFICATION_DEFAULT_CHANNEL_ID;
import static host.exp.exponent.notifications.NotificationConstants.NOTIFICATION_DEFAULT_CHANNEL_NAME;
import static host.exp.exponent.notifications.NotificationConstants.NOTIFICATION_EXPERIENCE_ID_KEY;
import static host.exp.exponent.notifications.NotificationConstants.NOTIFICATION_ID_KEY;
import static host.exp.exponent.notifications.helpers.ExpoCronParser.createCronInstance;

public class NotificationsModule extends ReactContextBaseJavaModule implements RegistryLifecycleListener, Mailbox {

  private static final String TAG = NotificationsModule.class.getSimpleName();

  @Inject
  ExponentSharedPreferences mExponentSharedPreferences;

  @Inject
  ExponentNetwork mExponentNetwork;

  private final JSONObject mManifest;

  private ReactContext mReactContext;

  private static final String ON_USER_INTERACTION_EVENT = "Exponent.onUserInteraction";
  private static final String ON_FOREGROUND_NOTIFICATION_EVENT = "Exponent.onForegroundNotification";

  private String mExperienceId;
  private ChannelManager mChannelManager;

  public NotificationsModule(ReactApplicationContext reactContext,
                             JSONObject manifest, Map<String, Object> experienceProperties) {
    super(reactContext);
    NativeModuleDepsProvider.getInstance().inject(NotificationsModule.class, this);
    mManifest = manifest;
    mReactContext = reactContext;
  }

  @Override
  public String getName() {
    return "ExponentNotifications";
  }

  @ReactMethod
  public void createCategoryAsync(final String categoryIdParam, final ReadableArray actions, final Promise promise) {
    String categoryId = getScopedIdIfNotDetached(categoryIdParam);
    List<Map<String, Object>> newActions = new ArrayList<>();

    for (Object actionObject : actions.toArrayList()) {
      if (actionObject instanceof Map) {
        Map<String, Object> action = (Map<String, Object>) actionObject;
        newActions.add(action);
      }
    }

    NotificationActionCenter.putCategory(categoryId, newActions);
    promise.resolve(null);
  }

  @ReactMethod
  public void deleteCategoryAsync(final String categoryIdParam, final Promise promise) {
    String categoryId = getScopedIdIfNotDetached(categoryIdParam);
    NotificationActionCenter.removeCategory(categoryId);
    promise.resolve(null);
  }

  private String getScopedIdIfNotDetached(String string) {
    if (!Constants.isStandaloneApp()) {
      return mExperienceId + ":" + string;
    }
    return string;
  }

  @ReactMethod
  public void getDevicePushTokenAsync(final ReadableMap config, final Promise promise) {
    if (!Constants.isStandaloneApp()) {
      promise.reject("getDevicePushTokenAsync is only accessible within standalone applications");
    }
    FirebaseInstanceId.getInstance().getInstanceId()
        .addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
          @Override
          public void onComplete(@NonNull Task<InstanceIdResult> task) {
            if (!task.isSuccessful()) {
              promise.reject(task.getException());
            }
            String token = task.getResult().getToken();
            promise.resolve(token);
          }
        });
  }

  @ReactMethod
  public void getExponentPushTokenAsync(final Promise promise) {
    String uuid = mExponentSharedPreferences.getUUID();
    if (uuid == null) {
      // This should have been set by ExponentNotificationIntentService when Activity was created/resumed.
      promise.reject("Couldn't get GCM token on device.");
      return;
    }

    NotificationHelper.getPushNotificationToken(uuid, mExperienceId, mExponentNetwork, mExponentSharedPreferences, new NotificationHelper.TokenListener() {
      @Override
      public void onSuccess(String token) {
        promise.resolve(token);
      }

      @Override
      public void onFailure(Exception e) {
        promise.reject("E_GET_GCM_TOKEN_FAILED", "Couldn't get GCM token for device", e);
      }
    });
  }

  @ReactMethod
  public void createChannel(String channelId, final ReadableMap data, final Promise promise) {
    HashMap channelData = data.toHashMap();
    channelData.put(NOTIFICATION_CHANNEL_ID, channelId);

    ChannelPOJO channelPOJO = ChannelPOJO.createChannelPOJO(channelData);

    mChannelManager.addChannel(channelId, channelPOJO, getReactApplicationContext().getApplicationContext());
    promise.resolve(null);
  }

  @ReactMethod
  public void deleteChannel(String channelId, final Promise promise) {
    mChannelManager.deleteChannel(channelId, getReactApplicationContext().getApplicationContext());
    promise.resolve(null);
  }

  @ReactMethod
  public void createChannelGroup(String groupId, String groupName, final Promise promise) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager notificationManager =
          (NotificationManager) mReactContext.getSystemService(Context.NOTIFICATION_SERVICE);
      notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(groupId, groupName));
    }
    promise.resolve(null);
  }

  @ReactMethod
  public void deleteChannelGroup(String groupId, final Promise promise) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager notificationManager =
          (NotificationManager) mReactContext.getSystemService(Context.NOTIFICATION_SERVICE);
      notificationManager.deleteNotificationChannelGroup(groupId);
    }
    promise.resolve(null);
  }

  @ReactMethod
  public void presentLocalNotification(final ReadableMap data, final ReadableMap legacyChannelData, final Promise promise) {
    Bundle bundle = new MapArguments(data.toHashMap()).toBundle();
    bundle.putString(NOTIFICATION_EXPERIENCE_ID_KEY, mExperienceId);

    Integer notificationId = Math.abs( new Random().nextInt() );
    bundle.putString(NOTIFICATION_ID_KEY, notificationId.toString());

    NotificationPresenter notificationPresenter = new NotificationPresenterImpl();
    notificationPresenter.presentNotification(
        getReactApplicationContext().getApplicationContext(),
        mExperienceId,
        bundle,
        notificationId
    );

    promise.resolve(notificationId.toString());
  }

  @ReactMethod
  public void dismissNotification(final String notificationId, final Promise promise) {
    int id = Integer.parseInt(notificationId);
    NotificationManager notificationManager = (NotificationManager) mReactContext
        .getSystemService(Context.NOTIFICATION_SERVICE);
    notificationManager.cancel(id);
    promise.resolve(null);
  }

  @ReactMethod
  public void dismissAllNotifications(final Promise promise) {
    NotificationManager notificationManager = (NotificationManager) mReactContext
        .getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();

      for (StatusBarNotification notification : activeNotifications) {
        if (notification.getTag().equals(mExperienceId)) {
          notificationManager.cancel(notification.getId());
        }
      }

      promise.resolve(null);
    } else {
      promise.reject("Function dismissAllNotifications is available from android 6.0");
    }
  }

  @ReactMethod
  public void cancelScheduledNotificationAsync(final String notificationId, final Promise promise) {
    SchedulersManagerProxy.getInstance(getReactApplicationContext()
        .getApplicationContext())
        .removeScheduler(notificationId);

    dismissNotification(notificationId, promise);
  }

  @ReactMethod
  public void cancelAllScheduledNotificationsAsync(final Promise promise) {
    SchedulersManagerProxy
        .getInstance(getReactApplicationContext().getApplicationContext())
        .removeAll(mExperienceId);

    dismissAllNotifications(promise);
  }

  @ReactMethod
  public void scheduleNotificationWithTimer(final ReadableMap data, final ReadableMap optionsMap, final Promise promise) {
    HashMap<String, Object> options = optionsMap.toHashMap();
    HashMap<String, Object> hashMap = data.toHashMap();
    if (data.hasKey("categoryId")) {
      hashMap.put("categoryId", getScopedIdIfNotDetached(data.getString("categoryId")));
    }
    HashMap<String, Object> details = new HashMap<>();
    details.put("data", hashMap);
    String experienceId;

    try {
      experienceId = mManifest.getString(ExponentManifest.MANIFEST_ID_KEY);
      details.put("experienceId", experienceId);
    } catch (Exception e) {
      promise.reject(new Exception("Requires Experience Id"));
      return;
    }

    IntervalSchedulerModel intervalSchedulerModel = new IntervalSchedulerModel();
    intervalSchedulerModel.setExperienceId(experienceId);
    intervalSchedulerModel.setDetails(details);
    intervalSchedulerModel.setRepeat(options.containsKey("repeat") && (Boolean) options.get("repeat"));
    intervalSchedulerModel.setScheduledTime(System.currentTimeMillis() + ((Double) options.get("interval")).longValue());
    intervalSchedulerModel.setInterval(((Double) options.get("interval")).longValue()); // on iOS we cannot change interval

    SchedulerImpl scheduler = new SchedulerImpl(intervalSchedulerModel);

    SchedulersManagerProxy.getInstance(getReactApplicationContext().getApplicationContext()).addScheduler(
        scheduler,
        (String id) -> {
          if (id == null) {
            promise.reject(new UnableToScheduleException());
            return false;
          }
          promise.resolve(id);
          return true;
        }
    );
  }

  @ReactMethod
  public void scheduleNotificationWithCalendar(final ReadableMap data, final ReadableMap optionsMap, final Promise promise) {
    HashMap<String, Object> options = optionsMap.toHashMap();
    HashMap<String, Object> hashMap = data.toHashMap();
    if (data.hasKey("categoryId")) {
      hashMap.put("categoryId", getScopedIdIfNotDetached(data.getString("categoryId")));
    }
    HashMap<String, Object> details = new HashMap<>();
    details.put("data", hashMap);
    String experienceId;

    try {
      experienceId = mManifest.getString(ExponentManifest.MANIFEST_ID_KEY);
      details.put("experienceId", experienceId);
    } catch (Exception e) {
      promise.reject(new Exception("Requires Experience Id"));
      return;
    }

    Cron cron = createCronInstance(options);

    CalendarSchedulerModel calendarSchedulerModel = new CalendarSchedulerModel();
    calendarSchedulerModel.setExperienceId(experienceId);
    calendarSchedulerModel.setDetails(details);
    calendarSchedulerModel.setRepeat(options.containsKey("repeat") && (Boolean) options.get("repeat"));
    calendarSchedulerModel.setCalendarData(cron.asString());

    SchedulerImpl scheduler = new SchedulerImpl(calendarSchedulerModel);

    SchedulersManagerProxy.getInstance(getReactApplicationContext().getApplicationContext()).addScheduler(
        scheduler,
        (String id) -> {
          if (id == null) {
            promise.reject(new UnableToScheduleException());
            return false;
          }
          promise.resolve(id);
          return true;
        }
    );
  }

  @ReactMethod
  public void scheduleLocalNotificationWithChannel(final ReadableMap data, final ReadableMap options, final ReadableMap legacyChannelData, final Promise promise) {
    String experienceId = mManifest.optString(ExponentManifest.MANIFEST_ID_KEY, null);
    if (legacyChannelData != null) {
      String channelId = data.getString("channelId");
      if (channelId == null || experienceId == null) {
        promise.reject("E_FAILED_PRESENTING_NOTIFICATION", "legacyChannelData was nonnull with no channelId or no experienceId");
        return;
      }
      NotificationHelper.maybeCreateLegacyStoredChannel(
          getReactApplicationContext(),
          experienceId,
          channelId,
          legacyChannelData.toHashMap());
    }

    int notificationId = Math.abs(new Random().nextInt(Integer.MAX_VALUE));

    HashMap<String, Object> hashMapOfData = data.toHashMap();
    hashMapOfData.put("experienceId", experienceId);

    NotificationHelper.scheduleLocalNotification(
        getReactApplicationContext(),
        notificationId,
        hashMapOfData,
        options.toHashMap(),
        mManifest,
        new NotificationHelper.Listener() {
          public void onSuccess(int id) {
            promise.resolve(Integer.valueOf(id).toString());
          }

          public void onFailure(Exception e) {
            promise.reject(e);
          }
        });
  }

  public void onCreate(ModuleRegistry moduleRegistry) {
    try {
      mExperienceId = mManifest.getString(ExponentManifest.MANIFEST_ID_KEY);
    } catch (JSONException e) {
      e.printStackTrace();
    }

    createDefaultChannel();

    mChannelManager = new ChannelScopeManager(mExperienceId);

    PostOfficeProxy.getInstance().registerModuleAndGetPendingDeliveries(mExperienceId, this);
  }

  private void createDefaultChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      CharSequence name = NOTIFICATION_DEFAULT_CHANNEL_NAME;
      int importance = NotificationManager.IMPORTANCE_DEFAULT;
      NotificationChannel channel = new NotificationChannel(NOTIFICATION_DEFAULT_CHANNEL_ID, name, importance);
      NotificationManager notificationManager = mReactContext.getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
    }
  }

  public void onDestory() {
    PostOfficeProxy.getInstance().unregisterModule(mExperienceId);
  }

  @Override
  public void onUserInteraction(Bundle userInteraction) {
    mReactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit(ON_USER_INTERACTION_EVENT, Arguments.fromBundle(userInteraction));
  }

  @Override
  public void onForegroundNotification(Bundle notification) {
    mReactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit(ON_FOREGROUND_NOTIFICATION_EVENT, Arguments.fromBundle(notification));
  }

}
