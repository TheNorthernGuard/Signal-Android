package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.support.annotation.NonNull;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.jobmanager.JobParameters;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;

public abstract class PushReceivedJob extends ContextJob {

  private static final String TAG = PushReceivedJob.class.getSimpleName();

  protected PushReceivedJob(Context context, JobParameters parameters) {
    super(context, parameters);
  }

  public static Optional<Long> processEnvelope(@NonNull Context context, @NonNull SignalServiceEnvelope envelope) {
    Address   source    = Address.fromExternal(context, envelope.getSource());
    Recipient recipient = Recipient.from(context, source, false);

    if (!isActiveNumber(recipient)) {
      DatabaseFactory.getRecipientDatabase(context).setRegistered(recipient, RecipientDatabase.RegisteredState.REGISTERED);
      ApplicationContext.getInstance(context).getJobManager().add(new DirectoryRefreshJob(context, recipient, false));
    }

    if (envelope.isReceipt()) {
      handleReceipt(context, envelope);
    } else if (envelope.isPreKeySignalMessage() || envelope.isSignalMessage()) {
      return Optional.of(handleMessage(context, envelope));
    } else {
      Log.w(TAG, "Received envelope of unknown type: " + envelope.getType());
    }

    return Optional.absent();
  }

  public void handle(SignalServiceEnvelope envelope) {
    Optional<Long> messageId = processEnvelope(context, envelope);

    if (messageId.isPresent()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new PushDecryptJob(context, messageId.get()));
    }
  }

  private static long handleMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope) {
    return DatabaseFactory.getPushDatabase(context).insert(envelope);
  }

  private static void handleReceipt(@NonNull Context context, @NonNull SignalServiceEnvelope envelope) {
    Log.i(TAG, String.format("Received receipt: (XXXXX, %d)", envelope.getTimestamp()));
    DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(new SyncMessageId(Address.fromExternal(context, envelope.getSource()),
                                                                                               envelope.getTimestamp()), System.currentTimeMillis());
  }

  private static boolean isActiveNumber(@NonNull Recipient recipient) {
    return recipient.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED;
  }
}
