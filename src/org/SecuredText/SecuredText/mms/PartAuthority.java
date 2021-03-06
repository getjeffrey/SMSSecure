package org.SecuredText.SecuredText.mms;

import android.content.ContentUris;
import android.content.Context;
import android.content.UriMatcher;
import android.net.Uri;

import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.database.DatabaseFactory;
import org.SecuredText.SecuredText.database.PartDatabase;
import org.SecuredText.SecuredText.providers.PartProvider;

import java.io.IOException;
import java.io.InputStream;

public class PartAuthority {

  private static final String PART_URI_STRING  = "content://org.SecuredText.SecuredText/part";
  public  static final Uri    PART_CONTENT_URI = Uri.parse(PART_URI_STRING);

  private static final int PART_ROW  = 1;

  private static final UriMatcher uriMatcher;

  static {
    uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    uriMatcher.addURI("org.SecuredText.SecuredText", "part/#", PART_ROW);
  }

  public static InputStream getPartStream(Context context, MasterSecret masterSecret, Uri uri)
      throws IOException
  {
    PartDatabase partDatabase = DatabaseFactory.getPartDatabase(context);
    int          match        = uriMatcher.match(uri);

    try {
      switch (match) {
      case PART_ROW:  return partDatabase.getPartStream(masterSecret, ContentUris.parseId(uri));
      default:        return context.getContentResolver().openInputStream(uri);
      }
    } catch (SecurityException se) {
      throw new IOException(se);
    }
  }

  public static InputStream getThumbnail(Context context, MasterSecret masterSecret, Uri uri)
      throws IOException
  {
    PartDatabase partDatabase = DatabaseFactory.getPartDatabase(context);
    int          match        = uriMatcher.match(uri);

    switch (match) {
    case PART_ROW: return partDatabase.getThumbnailStream(masterSecret, ContentUris.parseId(uri));
    default:       return null;
    }
  }

  public static Uri getPublicPartUri(Uri uri) {
    return ContentUris.withAppendedId(PartProvider.CONTENT_URI, ContentUris.parseId(uri));
  }
}
