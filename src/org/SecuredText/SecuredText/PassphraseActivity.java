/**
 * Copyright (C) 2011 Whisper Systems
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
package org.SecuredText.SecuredText;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v7.app.ActionBarActivity;

import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.service.KeyCachingService;
import org.SecuredText.SecuredText.util.MemoryCleaner;


/**
 * Base Activity for changing/prompting local encryption passphrase.
 *
 * @author Moxie Marlinspike
 */
public abstract class PassphraseActivity extends BaseActionBarActivity {

  private KeyCachingService keyCachingService;
  private MasterSecret masterSecret;

  protected void setMasterSecret(MasterSecret masterSecret) {
    this.masterSecret = masterSecret;
    Intent bindIntent = new Intent(this, KeyCachingService.class);
    bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
  }

  protected MasterSecret getMasterSecret() {
    return masterSecret;
  }

  protected abstract void cleanup();

  private ServiceConnection serviceConnection = new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName className, IBinder service) {
        keyCachingService = ((KeyCachingService.KeySetBinder)service).getService();
        keyCachingService.setMasterSecret(masterSecret);

        PassphraseActivity.this.unbindService(PassphraseActivity.this.serviceConnection);

        MemoryCleaner.clean(masterSecret);
        cleanup();

        PassphraseActivity.this.setResult(RESULT_OK);
        PassphraseActivity.this.finish();
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        keyCachingService = null;
      }
  };
}
