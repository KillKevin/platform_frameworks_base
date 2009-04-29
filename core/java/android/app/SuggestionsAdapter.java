/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.server.search.SearchableInfo;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;

import java.io.FileNotFoundException;
import java.util.WeakHashMap;

/**
 * Provides the contents for the suggestion drop-down list.in {@link SearchDialog}.
 * 
 * @hide
 */
class SuggestionsAdapter extends ResourceCursorAdapter {
    private static final boolean DBG = false;
    private static final String LOG_TAG = "SuggestionsAdapter";
    
    private SearchableInfo mSearchable;
    private Context mProviderContext;
    private WeakHashMap<String, Drawable> mOutsideDrawablesCache;

    // Cached column indexes, updated when the cursor changes. 
    private int mFormatCol;
    private int mText1Col;
    private int mText2Col;
    private int mIconName1Col;
    private int mIconName2Col;
    private int mIconBitmap1Col;
    private int mIconBitmap2Col;
    
    public SuggestionsAdapter(Context context, SearchableInfo searchable,
            WeakHashMap<String, Drawable> outsideDrawablesCache) {
        super(context,
                com.android.internal.R.layout.search_dropdown_item_icons_2line,
                null,   // no initial cursor
                true);  // auto-requery
        mSearchable = searchable;
        
        // set up provider resources (gives us icons, etc.)
        Context activityContext = mSearchable.getActivityContext(mContext);
        mProviderContext = mSearchable.getProviderContext(mContext, activityContext);
        
        mOutsideDrawablesCache = outsideDrawablesCache;
    }
    
    /**
     * Overridden to always return <code>false</code>, since we cannot be sure that
     * suggestion sources return stable IDs.
     */
    @Override
    public boolean hasStableIds() {
        return false;
    }

    /**
     * Use the search suggestions provider to obtain a live cursor.  This will be called
     * in a worker thread, so it's OK if the query is slow (e.g. round trip for suggestions).
     * The results will be processed in the UI thread and changeCursor() will be called.
     */
    @Override
    public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
        if (DBG) Log.d(LOG_TAG, "runQueryOnBackgroundThread(" + constraint + ")");
        String query = (constraint == null) ? "" : constraint.toString();
        try {
            return SearchManager.getSuggestions(mContext, mSearchable, query);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Search suggestions query threw an exception.", e);
            return null;
        }
    }
    
    /**
     * Cache columns.
     */
    @Override
    public void changeCursor(Cursor c) {
        if (DBG) Log.d(LOG_TAG, "changeCursor(" + c + ")");
        super.changeCursor(c);
        if (c != null) {
            mFormatCol = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_FORMAT);
            mText1Col = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1);
            mText2Col = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2);
            mIconName1Col = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_1);
            mIconName2Col = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_2);
            mIconBitmap1Col = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_1_BITMAP);
            mIconBitmap2Col = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_2_BITMAP);
        }
    }
    
    /**
     * Tags the view with cached child view look-ups.
     */
    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View v = super.newView(context, cursor, parent);
        v.setTag(new ChildViewCache(v));
        return v;
    }
    
    /**
     * Cache of the child views of drop-drown list items, to avoid looking up the children
     * each time the contents of a list item are changed.
     */
    private final static class ChildViewCache {
        public final TextView mText1;
        public final TextView mText2;
        public final ImageView mIcon1;
        public final ImageView mIcon2;
        
        public ChildViewCache(View v) {
            mText1 = (TextView) v.findViewById(com.android.internal.R.id.text1);
            mText2 = (TextView) v.findViewById(com.android.internal.R.id.text2);
            mIcon1 = (ImageView) v.findViewById(com.android.internal.R.id.icon1);
            mIcon2 = (ImageView) v.findViewById(com.android.internal.R.id.icon2);
        }
    }
    
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        ChildViewCache views = (ChildViewCache) view.getTag();            
        String format = cursor.getString(mFormatCol);
        boolean isHtml = "html".equals(format); 
        setViewText(cursor, views.mText1, mText1Col, isHtml);
        setViewText(cursor, views.mText2, mText2Col, isHtml);
        setViewIcon(cursor, views.mIcon1, mIconBitmap1Col, mIconName1Col);
        setViewIcon(cursor, views.mIcon2, mIconBitmap2Col, mIconName2Col);
    }
    
    private void setViewText(Cursor cursor, TextView v, int textCol, boolean isHtml) {
        if (v == null) {
            return;
        }
        CharSequence text = null;
        if (textCol >= 0) {
            String str = cursor.getString(textCol);
            text = (str != null && isHtml) ? Html.fromHtml(str) : str;
        }
        // Set the text even if it's null, since we need to clear any previous text.
        v.setText(text);
        
        if (TextUtils.isEmpty(text)) {
            v.setVisibility(View.GONE);
        } else {
            v.setVisibility(View.VISIBLE);
        }
    }
    
    private void setViewIcon(Cursor cursor, ImageView v, int iconBitmapCol, int iconNameCol) {
        if (v == null) {
            return;
        }
        Drawable drawable = null;
        // First try the bitmap column
        if (iconBitmapCol >= 0) {
            byte[] data = cursor.getBlob(iconBitmapCol);
            if (data != null) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap != null) {
                    drawable = new BitmapDrawable(bitmap);
                }
            }
        }
        // If there was no bitmap, try the icon resource column.
        if (drawable == null && iconNameCol >= 0) {
            String value = cursor.getString(iconNameCol);
            drawable = getDrawableFromResourceValue(value);
        }
        // Set the icon even if the drawable is null, since we need to clear any
        // previous icon.
        v.setImageDrawable(drawable);
        
        if (drawable == null) {
            v.setVisibility(View.GONE);
        } else {
            v.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * Gets the text to show in the query field when a suggestion is selected.
     * 
     * @param cursor The Cursor to read the suggestion data from. The Cursor should already 
     *        be moved to the suggestion that is to be read from.
     * @return The text to show, or <code>null</code> if the query should not be
     *         changed when selecting this suggestion.
     */
    @Override
    public CharSequence convertToString(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        
        String query = getColumnString(cursor, SearchManager.SUGGEST_COLUMN_QUERY);
        if (query != null) {
            return query;
        }
        
        if (mSearchable.mQueryRewriteFromData) {
            String data = getColumnString(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
            if (data != null) {
                return data;
            }
        }
        
        if (mSearchable.mQueryRewriteFromText) {
            String text1 = getColumnString(cursor, SearchManager.SUGGEST_COLUMN_TEXT_1);
            if (text1 != null) {
                return text1;
            }
        }
        
        return null;
    }
    
    /**
     * This method is overridden purely to provide a bit of protection against
     * flaky content providers.
     * 
     * @see android.widget.ListAdapter#getView(int, View, ViewGroup)
     */
    @Override 
    public View getView(int position, View convertView, ViewGroup parent) {
        try {
            return super.getView(position, convertView, parent);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Search suggestions cursor threw exception.", e);
            // Put exception string in item title
            View v = newView(mContext, mCursor, parent);
            if (v != null) {
                ChildViewCache views = (ChildViewCache) v.getTag(); 
                TextView tv = views.mText1;
                tv.setText(e.toString());
            }
            return v;
        }
    }
    
    /**
     * Gets a drawable given a value provided by a suggestion provider.
     * 
     * This value could be just the string value of a resource id
     * (e.g., "2130837524"), in which case we will try to retrieve a drawable from
     * the provider's resources. If the value is not an integer, it is
     * treated as a Uri and opened with  
     * {@link ContentResolver#openOutputStream(android.net.Uri, String)}.
     *
     * All resources and URIs are read using the suggestion provider's context.
     *
     * If the string is not formatted as expected, or no drawable can be found for
     * the provided value, this method returns null.
     * 
     * @param drawableId a string like "2130837524",
     *        "android.resource://com.android.alarmclock/2130837524",
     *        or "content://contacts/photos/253".
     * @return a Drawable, or null if none found
     */
    private Drawable getDrawableFromResourceValue(String drawableId) {
        if (drawableId == null || drawableId.length() == 0 || "0".equals(drawableId)) {
            return null;
        }
        
        // First, check the cache.
        Drawable drawable = mOutsideDrawablesCache.get(drawableId);
        if (drawable != null) return drawable;

        try {
            // Not cached, try using it as a plain resource ID in the provider's context.
            int resourceId = Integer.parseInt(drawableId);
            drawable = mProviderContext.getResources().getDrawable(resourceId);
        } catch (NumberFormatException nfe) {
            // The id was not an integer resource id.
            // Let the ContentResolver handle content, android.resource and file URIs.
            try {
                Uri uri = Uri.parse(drawableId);
                drawable = Drawable.createFromStream(
                        mProviderContext.getContentResolver().openInputStream(uri),
                        null);
            } catch (FileNotFoundException fnfe) {
                // drawable = null;
            }
                    
            // If we got a drawable for this resource id, then stick it in the
            // map so we don't do this lookup again.
            if (drawable != null) {
                mOutsideDrawablesCache.put(drawableId, drawable);
            }
        } catch (NotFoundException nfe) {
            // Resource could not be found
            // drawable = null;
        }
        
        return drawable;
    }
    
    /**
     * Gets the value of a string column by name.
     * 
     * @param cursor Cursor to read the value from.
     * @param columnName The name of the column to read.
     * @return The value of the given column, or <code>null</null>
     *         if the cursor does not contain the given column.
     */
    public static String getColumnString(Cursor cursor, String columnName) {
        int col = cursor.getColumnIndex(columnName);
        if (col == -1) {
            return null;
        }
        return cursor.getString(col);
    }

}
