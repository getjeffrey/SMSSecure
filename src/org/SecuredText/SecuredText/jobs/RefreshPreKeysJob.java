package org.SecuredText.SecuredText.jobs;

import android.content.Context;
import android.util.Log;

import org.SecuredText.SecuredText.ApplicationContext;
import org.SecuredText.SecuredText.crypto.IdentityKeyUtil;
import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.crypto.PreKeyUtil;
import org.SecuredText.SecuredText.dependencies.InjectableType;
import org.SecuredText.SecuredText.jobs.requirements.MasterSecretRequirement;
import org.SecuredText.SecuredText.util.SecuredTextPreferences;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.libaxolotl.IdentityKeyPair;
import org.whispersystems.libaxolotl.state.PreKeyRecord;
import org.whispersystems.libaxolotl.state.SignedPreKeyRecord;
import org.whispersystems.textsecure.api.TextSecureAccountManager;
import org.whispersystems.textsecure.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.textsecure.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

public class RefreshPreKeysJob extends MasterSecretJob implements InjectableType {

  private static final String TAG = RefreshPreKeysJob.class.getSimpleName();

  private static final int PREKEY_MINIMUM = 10;

  @Inject transient TextSecureAccountManager accountManager;

  public RefreshPreKeysJob(Context context) {
    super(context, JobParameters.newBuilder()
                                .withGroupId(RefreshPreKeysJob.class.getSimpleName())
                                .withRequirement(new NetworkRequirement(context))
                                .withRequirement(new MasterSecretRequirement(context))
                                .withRetryCount(5)
                                .create());
  }

  @Override
  public void onAdded() {

  }

  @Override
  public void onRun(MasterSecret masterSecret) throws IOException {
    if (!SecuredTextPreferences.isPushRegistered(context)) return;

    int availableKeys = accountManager.getPreKeysCount();

    if (availableKeys >= PREKEY_MINIMUM && SecuredTextPreferences.isSignedPreKeyRegistered(context)) {
      Log.w(TAG, "Available keys sufficient: " + availableKeys);
      return;
    }

    List<PreKeyRecord> preKeyRecords       = PreKeyUtil.generatePreKeys(context, masterSecret);
    PreKeyRecord       lastResortKeyRecord = PreKeyUtil.generateLastResortKey(context, masterSecret);
    IdentityKeyPair    identityKey         = IdentityKeyUtil.getIdentityKeyPair(context, masterSecret);
    SignedPreKeyRecord signedPreKeyRecord  = PreKeyUtil.generateSignedPreKey(context, masterSecret, identityKey);

    Log.w(TAG, "Registering new prekeys...");

    accountManager.setPreKeys(identityKey.getPublicKey(), lastResortKeyRecord, signedPreKeyRecord, preKeyRecords);

    SecuredTextPreferences.setSignedPreKeyRegistered(context, true);

    ApplicationContext.getInstance(context)
                      .getJobManager()
                      .add(new CleanPreKeysJob(context));
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    if (exception instanceof NonSuccessfulResponseCodeException) return false;
    if (exception instanceof PushNetworkException)               return true;

    return false;
  }

  @Override
  public void onCanceled() {

  }

}
