/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.SecuredText.SecuredText.preferences;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.preference.PreferenceFragment;
import android.util.Log;

import org.SecuredText.SecuredText.PassphraseRequiredActionBarActivity;
import org.SecuredText.SecuredText.R;
import org.SecuredText.SecuredText.components.CustomDefaultPreference;
import org.SecuredText.SecuredText.database.ApnDatabase;
import org.SecuredText.SecuredText.mms.MmsConnection;
import org.SecuredText.SecuredText.util.TelephonyUtil;
import org.SecuredText.SecuredText.util.SecuredTextPreferences;

import java.io.IOException;


public class MmsPreferencesFragment extends PreferenceFragment {

  private static final String TAG = MmsPreferencesFragment.class.getSimpleName();

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);
    addPreferencesFromResource(R.xml.preferences_manual_mms);

    ((PassphraseRequiredActionBarActivity) getActivity()).getSupportActionBar()
        .setTitle(R.string.preferences__advanced_mms_access_point_names);
  }

  @Override
  public void onResume() {
    super.onResume();
    new LoadApnDefaultsTask().execute();
  }

  private class LoadApnDefaultsTask extends AsyncTask<Void, Void, MmsConnection.Apn> {

    @Override
    protected MmsConnection.Apn doInBackground(Void... params) {
      try {
        Context context = getActivity();

        if (context != null) {
          return ApnDatabase.getInstance(context)
                            .getDefaultApnParameters(TelephonyUtil.getMccMnc(context),
                                                     TelephonyUtil.getApn(context));
        }
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      return null;
    }

    @Override
    protected void onPostExecute(MmsConnection.Apn apnDefaults) {
      ((CustomDefaultPreference)findPreference(SecuredTextPreferences.MMSC_HOST_PREF))
          .setValidator(new CustomDefaultPreference.UriValidator())
          .setDefaultValue(apnDefaults.getMmsc());

      ((CustomDefaultPreference)findPreference(SecuredTextPreferences.MMSC_PROXY_HOST_PREF))
          .setValidator(new CustomDefaultPreference.HostnameValidator())
          .setDefaultValue(apnDefaults.getProxy());

      ((CustomDefaultPreference)findPreference(SecuredTextPreferences.MMSC_PROXY_PORT_PREF))
          .setValidator(new CustomDefaultPreference.PortValidator())
          .setDefaultValue(apnDefaults.getPort());

      ((CustomDefaultPreference)findPreference(SecuredTextPreferences.MMSC_USERNAME_PREF))
          .setDefaultValue(apnDefaults.getPort());

      ((CustomDefaultPreference)findPreference(SecuredTextPreferences.MMSC_PASSWORD_PREF))
          .setDefaultValue(apnDefaults.getPassword());
    }
  }

}
