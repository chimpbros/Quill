package com.yugentech.quill.personal

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.notification.NotificationHelper
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDeliveryTest {
 @Test fun reminderAppearsWhenDeviceOwnerHasEnabledNotifications() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  assumeTrue("Enable notifications on the test device to validate delivery",manager.areNotificationsEnabled())
  try {
   NotificationHelper(context).showReminderNotification("Quill Personal reminder validation")
   val deadline=System.currentTimeMillis()+3000
   while(manager.activeNotifications.none { it.id==NotificationHelper.REMINDER_NOTIFICATION_ID } && System.currentTimeMillis()<deadline) Thread.sleep(50)
   assertTrue(manager.activeNotifications.any { it.id==NotificationHelper.REMINDER_NOTIFICATION_ID })
  } finally { manager.cancel(NotificationHelper.REMINDER_NOTIFICATION_ID) }
 }
}
