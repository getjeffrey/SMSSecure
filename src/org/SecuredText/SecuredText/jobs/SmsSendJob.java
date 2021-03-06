package org.SecuredText.SecuredText.jobs;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;

import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.crypto.SmsCipher;
import org.SecuredText.SecuredText.crypto.storage.SecuredTextAxolotlStore;
import org.SecuredText.SecuredText.database.DatabaseFactory;
import org.SecuredText.SecuredText.database.EncryptingSmsDatabase;
import org.SecuredText.SecuredText.database.NoSuchMessageException;
import org.SecuredText.SecuredText.database.SmsDatabase;
import org.SecuredText.SecuredText.database.model.SmsMessageRecord;
import org.SecuredText.SecuredText.jobs.requirements.MasterSecretRequirement;
import org.SecuredText.SecuredText.jobs.requirements.NetworkOrServiceRequirement;
import org.SecuredText.SecuredText.jobs.requirements.ServiceRequirement;
import org.SecuredText.SecuredText.notifications.MessageNotifier;
import org.SecuredText.SecuredText.recipients.Recipients;
import org.SecuredText.SecuredText.service.SmsDeliveryListener;
import org.SecuredText.SecuredText.sms.MultipartSmsMessageHandler;
import org.SecuredText.SecuredText.sms.OutgoingTextMessage;
import org.SecuredText.SecuredText.transport.InsecureFallbackApprovalException;
import org.SecuredText.SecuredText.transport.UndeliverableMessageException;
import org.SecuredText.SecuredText.util.NumberUtil;
import org.SecuredText.SecuredText.util.SecuredTextPreferences;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libaxolotl.NoSessionException;

import java.util.ArrayList;

public class SmsSendJob extends SendJob {

  private static final String TAG = SmsSendJob.class.getSimpleName();

  private final long messageId;

  public SmsSendJob(Context context, long messageId, String name) {
    super(context, constructParameters(context, name));
    this.messageId = messageId;
  }

  @Override
  public void onAdded() {
    SmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
    database.markAsSending(messageId);
  }

  @Override
  public void onSend(MasterSecret masterSecret) throws NoSuchMessageException {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
    SmsMessageRecord      record   = database.getMessage(masterSecret, messageId);

    try {
      Log.w(TAG, "Sending message: " + messageId);

      deliver(masterSecret, record);
    } catch (UndeliverableMessageException ude) {
      Log.w(TAG, ude);
      DatabaseFactory.getSmsDatabase(context).markAsSentFailed(record.getId());
      MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
    } catch (InsecureFallbackApprovalException ifae) {
      Log.w(TAG, ifae);
      DatabaseFactory.getSmsDatabase(context).markAsPendingInsecureSmsFallback(record.getId());
      MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception throwable) {
    return false;
  }

  @Override
  public void onCanceled() {
    Log.w(TAG, "onCanceled()");
    long       threadId   = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);
    MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
  }

  private void deliver(MasterSecret masterSecret, SmsMessageRecord record)
      throws UndeliverableMessageException, InsecureFallbackApprovalException
  {
    if (!NumberUtil.isValidSmsOrEmail(record.getIndividualRecipient().getNumber())) {
      throw new UndeliverableMessageException("Not a valid SMS destination! " + record.getIndividualRecipient().getNumber());
    }

    if (record.isSecure() || record.isKeyExchange() || record.isEndSession()) {
      deliverSecureMessage(masterSecret, record);
    } else {
      deliverPlaintextMessage(record);
    }
  }

  private void deliverSecureMessage(MasterSecret masterSecret, SmsMessageRecord message)
      throws UndeliverableMessageException, InsecureFallbackApprovalException
  {
    MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();
    OutgoingTextMessage        transportMessage        = OutgoingTextMessage.from(message);

    if (message.isSecure() || message.isEndSession()) {
      transportMessage = getAsymmetricEncrypt(masterSecret, transportMessage);
    }

    ArrayList<String> messages                = multipartMessageHandler.divideMessage(transportMessage);
    ArrayList<PendingIntent> sentIntents      = constructSentIntents(message.getId(), message.getType(), messages, message.isSecure());
    ArrayList<PendingIntent> deliveredIntents = constructDeliveredIntents(message.getId(), message.getType(), messages);

    Log.w("SmsTransport", "Secure divide into message parts: " + messages.size());

    for (int i=0;i<messages.size();i++) {
      // NOTE 11/04/14 -- There's apparently a bug where for some unknown recipients
      // and messages, this will throw an NPE.  We have no idea why, so we're just
      // catching it and marking the message as a failure.  That way at least it
      // doesn't repeatedly crash every time you start the app.
      try {
        SmsManager.getDefault().sendTextMessage(message.getIndividualRecipient().getNumber(), null, messages.get(i),
                                                sentIntents.get(i),
                                                deliveredIntents == null ? null : deliveredIntents.get(i));
      } catch (NullPointerException npe) {
        Log.w(TAG, npe);
        Log.w(TAG, "Recipient: " + message.getIndividualRecipient().getNumber());
        Log.w(TAG, "Message Total Parts/Current: " + messages.size() + "/" + i);
        Log.w(TAG, "Message Part Length: " + messages.get(i).getBytes().length);
        throw new UndeliverableMessageException(npe);
      } catch (IllegalArgumentException iae) {
        Log.w(TAG, iae);
        throw new UndeliverableMessageException(iae);
      }
    }
  }

  private void deliverPlaintextMessage(SmsMessageRecord message)
      throws UndeliverableMessageException
  {
    ArrayList<String> messages                = SmsManager.getDefault().divideMessage(message.getBody().getBody());
    ArrayList<PendingIntent> sentIntents      = constructSentIntents(message.getId(), message.getType(), messages, false);
    ArrayList<PendingIntent> deliveredIntents = constructDeliveredIntents(message.getId(), message.getType(), messages);
    String recipient                          = message.getIndividualRecipient().getNumber();

    // NOTE 11/04/14 -- There's apparently a bug where for some unknown recipients
    // and messages, this will throw an NPE.  We have no idea why, so we're just
    // catching it and marking the message as a failure.  That way at least it doesn't
    // repeatedly crash every time you start the app.
    try {
      SmsManager.getDefault().sendMultipartTextMessage(recipient, null, messages, sentIntents, deliveredIntents);
    } catch (NullPointerException npe) {
      Log.w(TAG, npe);
      Log.w(TAG, "Recipient: " + recipient);
      Log.w(TAG, "Message Parts: " + messages.size());

      try {
        for (int i=0;i<messages.size();i++) {
          SmsManager.getDefault().sendTextMessage(recipient, null, messages.get(i),
                                                  sentIntents.get(i),
                                                  deliveredIntents == null ? null : deliveredIntents.get(i));
        }
      } catch (NullPointerException npe2) {
        Log.w(TAG, npe);
        throw new UndeliverableMessageException(npe2);
      }
    }
  }

  private OutgoingTextMessage getAsymmetricEncrypt(MasterSecret masterSecret,
                                                   OutgoingTextMessage message)
      throws InsecureFallbackApprovalException
  {
    try {
      return new SmsCipher(new SecuredTextAxolotlStore(context, masterSecret)).encrypt(message);
    } catch (NoSessionException e) {
      throw new InsecureFallbackApprovalException(e);
    }
  }

  private ArrayList<PendingIntent> constructSentIntents(long messageId, long type,
                                                        ArrayList<String> messages, boolean secure)
  {
    ArrayList<PendingIntent> sentIntents = new ArrayList<>(messages.size());

    for (String ignored : messages) {
      sentIntents.add(PendingIntent.getBroadcast(context, 0,
                                                 constructSentIntent(context, messageId, type, secure, false),
                                                 0));
    }

    return sentIntents;
  }

  private ArrayList<PendingIntent> constructDeliveredIntents(long messageId, long type, ArrayList<String> messages) {
    if (!SecuredTextPreferences.isSmsDeliveryReportsEnabled(context)) {
      return null;
    }

    ArrayList<PendingIntent> deliveredIntents = new ArrayList<>(messages.size());

    for (String ignored : messages) {
      deliveredIntents.add(PendingIntent.getBroadcast(context, 0,
                                                      constructDeliveredIntent(context, messageId, type),
                                                      0));
    }

    return deliveredIntents;
  }

  private Intent constructSentIntent(Context context, long messageId, long type,
                                       boolean upgraded, boolean push)
  {
    Intent pending = new Intent(SmsDeliveryListener.SENT_SMS_ACTION,
                                Uri.parse("custom://" + messageId + System.currentTimeMillis()),
                                context, SmsDeliveryListener.class);

    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);
    pending.putExtra("upgraded", upgraded);
    pending.putExtra("push", push);

    return pending;
  }

  private Intent constructDeliveredIntent(Context context, long messageId, long type) {
    Intent pending = new Intent(SmsDeliveryListener.DELIVERED_SMS_ACTION,
                                Uri.parse("custom://" + messageId + System.currentTimeMillis()),
                                context, SmsDeliveryListener.class);
    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);

    return pending;
  }

  private static JobParameters constructParameters(Context context, String name) {
    JobParameters.Builder builder = JobParameters.newBuilder()
                                                 .withPersistence()
                                                 .withRequirement(new MasterSecretRequirement(context))
                                                 .withGroupId(name);

    if (SecuredTextPreferences.isWifiSmsEnabled(context)) {
      builder.withRequirement(new NetworkOrServiceRequirement(context));
    } else {
      builder.withRequirement(new ServiceRequirement(context));
    }

    return builder.create();
  }


}
