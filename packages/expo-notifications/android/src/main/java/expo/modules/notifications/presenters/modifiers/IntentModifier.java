package expo.modules.notifications.presenters.modifiers;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;

import java.util.UUID;

import static expo.modules.notifications.NotificationConstants.NOTIFICATION_OBJECT_KEY;
import static expo.modules.notifications.configuration.Configuration.MAIN_ACTIVITY_NAME;

public class IntentModifier implements NotificationModifier {
  @Override
  public void modify(NotificationCompat.Builder builder, Bundle notification, Context context, String appId) {
    Class activityClass = null;
    try {
      activityClass = Class.forName(MAIN_ACTIVITY_NAME);
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }

    Intent intent = new Intent(context, activityClass);
    intent.putExtra(NOTIFICATION_OBJECT_KEY, notification);

    PendingIntent contentIntent = PendingIntent.getActivity(
        context,
        UUID.randomUUID().hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT
    );
    builder.setContentIntent(contentIntent);
  }
}
