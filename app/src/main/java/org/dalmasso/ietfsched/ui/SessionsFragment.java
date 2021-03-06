/*
 * Copyright 2011 Google Inc. *
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

package org.dalmasso.ietfsched.ui;

import org.dalmasso.ietfsched.R;
import org.dalmasso.ietfsched.provider.ScheduleContract;
import org.dalmasso.ietfsched.util.ActivityHelper;
//import org.dalmasso.ietfsched.util.AnalyticsUtils;
import org.dalmasso.ietfsched.util.NotifyingAsyncQueryHandler;
import org.dalmasso.ietfsched.util.UIUtils;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.support.v4.app.ListFragment;
import android.text.Spannable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;

import static org.dalmasso.ietfsched.util.UIUtils.buildStyledSnippet;
import static org.dalmasso.ietfsched.util.UIUtils.formatSessionSubtitle;

/**
 * A {@link ListFragment} showing a list of sessions.
 */
public class SessionsFragment extends ListFragment implements NotifyingAsyncQueryHandler.AsyncQueryListener {

    public static final String EXTRA_SCHEDULE_TIME_STRING = "org.dalmasso.ietfsched.extra.SCHEDULE_TIME_STRING";
    private static final String STATE_CHECKED_POSITION = "checkedPosition";
    private static final String TAG = "SessionsFragment";
    private static final boolean debbug = true;

    private Uri mTrackUri;
    private Cursor mCursor;
    private CursorAdapter mAdapter;
    private int mCheckedPosition = -1;
    private boolean mHasSetEmptyText = false;

    private NotifyingAsyncQueryHandler mHandler;
    private Handler mMessageQueueHandler = new Handler();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new NotifyingAsyncQueryHandler(getActivity().getContentResolver(), this);
        reloadFromArguments(getArguments());
    }

    public void reloadFromArguments(Bundle arguments) {
        // Teardown from previous arguments
        if (debbug) Log.d(TAG, "reloadFromArguments" + mCheckedPosition);
        if (mCursor != null) {
            getActivity().stopManagingCursor(mCursor);
            mCursor = null;
        }

        mCheckedPosition = -1;
        setListAdapter(null);

        mHandler.cancelOperation(SearchQuery._TOKEN);
        mHandler.cancelOperation(SessionsQuery._TOKEN);
        mHandler.cancelOperation(TracksQuery._TOKEN);

        // Load new arguments
        final Intent intent = BaseActivity.fragmentArgumentsToIntent(arguments);
        final Uri sessionsUri = intent.getData();
        final int sessionQueryToken;

        if (sessionsUri == null) {
            return;
        }

        String[] projection;
        if (!ScheduleContract.Sessions.isSearchUri(sessionsUri)) {
            mAdapter = new SessionsAdapter(getActivity());
            projection = SessionsQuery.PROJECTION;
            sessionQueryToken = SessionsQuery._TOKEN;

        } else {
            mAdapter = new SearchAdapter(getActivity());
            projection = SearchQuery.PROJECTION;
            sessionQueryToken = SearchQuery._TOKEN;
        }

        setListAdapter(mAdapter);

        // Start background query to load sessions
        mHandler.startQuery(sessionQueryToken, null, sessionsUri, projection, null, null,
                ScheduleContract.Sessions.DEFAULT_SORT);

        // If caller launched us with specific track hint, pass it along when
        // launching session details. Also start a query to load the track info.
        mTrackUri = intent.getParcelableExtra(SessionDetailFragment.EXTRA_TRACK);
        if (mTrackUri != null) {
            mHandler.startQuery(TracksQuery._TOKEN, mTrackUri, TracksQuery.PROJECTION);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (debbug) Log.d(TAG, "onActivityCreated");
        getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        if (savedInstanceState != null) {
            mCheckedPosition = savedInstanceState.getInt(STATE_CHECKED_POSITION, -1);
        }
        if (debbug) Log.d(TAG, "onActivityCreated mChekedPosition " + mCheckedPosition);

        if (!mHasSetEmptyText) {
            // Could be a bug, but calling this twice makes it become visible when it shouldn't
            // be visible.
            setEmptyText(getString(R.string.empty_sessions));
            mHasSetEmptyText = true;
        }
    }

    /** {@inheritDoc} */
    public void onQueryComplete(int token, Object cookie, Cursor cursor) {
    	if (debbug) Log.d(TAG, "onQueryComplete" + mCheckedPosition);
        if (getActivity() == null) {
            return;
        }

        if (token == SessionsQuery._TOKEN || token == SearchQuery._TOKEN) {
            onSessionOrSearchQueryComplete(cursor);
        } else if (token == TracksQuery._TOKEN) {
            onTrackQueryComplete(cursor);
        } else {
        	if (debbug) Log.d("SF/complt",
                    "Query complete, Not Actionable: " + token);
            cursor.close();
        }
    }

    /**
     * Handle {@link SessionsQuery} {@link Cursor}.
     */
    private void onSessionOrSearchQueryComplete(Cursor cursor) {
        mCursor = cursor;
        if (debbug) Log.d(TAG, "OnSessionOrSearchQueryComplete mCheckedPosition" + mCheckedPosition);
        getActivity().startManagingCursor(mCursor);
        mAdapter.changeCursor(mCursor);
        if (mCheckedPosition >= 0 && getView() != null) {
            getListView().setItemChecked(mCheckedPosition, true);
        }
    }

    /**
     * Handle {@link TracksQuery} {@link Cursor}.
     */
    private void onTrackQueryComplete(Cursor cursor) {
    	if (debbug) Log.d(TAG, "onTrackQueryComplete");
        try {
            if (!cursor.moveToFirst()) {
                return;
            }

            // Use found track to build title-bar
            ActivityHelper activityHelper = ((BaseActivity) getActivity()).getActivityHelper();
            String trackName = cursor.getString(TracksQuery.TRACK_NAME);
            activityHelper.setActionBarTitle(trackName);
            activityHelper.setActionBarColor(cursor.getInt(TracksQuery.TRACK_COLOR));

//            AnalyticsUtils.getInstance(getActivity()).trackPageView("/Tracks/" + trackName);
        } finally {
            cursor.close();
        }
    }

    @Override
    public void onResume() {	
        super.onResume();
        if (debbug) Log.d(TAG, "OnResume called");
        mMessageQueueHandler.post(mRefreshSessionsRunnable);
        getActivity().getContentResolver().registerContentObserver(
                ScheduleContract.Sessions.CONTENT_URI, true, mSessionChangesObserver);
        if (mCursor != null) {
            mCursor.requery();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (debbug) Log.d(TAG, "onPause" + mCheckedPosition);
        mMessageQueueHandler.removeCallbacks(mRefreshSessionsRunnable);
        getActivity().getContentResolver().unregisterContentObserver(mSessionChangesObserver);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {  	
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CHECKED_POSITION, mCheckedPosition);
        if (debbug) Log.d(TAG, "onSaveInstanceState " + mCheckedPosition);
    }

    /** {@inheritDoc} */
    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        // Launch viewer for specific session, passing along any track knowledge
        // that should influence the title-bar.
        final Cursor cursor = (Cursor)mAdapter.getItem(position);
        final String sessionId = cursor.getString(cursor.getColumnIndex(
                ScheduleContract.Sessions.SESSION_ID));
        final Uri sessionUri = ScheduleContract.Sessions.buildSessionUri(sessionId);
        final Intent intent = new Intent(Intent.ACTION_VIEW, sessionUri);
        intent.putExtra(SessionDetailFragment.EXTRA_TRACK, mTrackUri);
        ((BaseActivity) getActivity()).openActivityOrFragment(intent);

        getListView().setItemChecked(position, true);
        mCheckedPosition = position;
        if (debbug) Log.d(TAG, "onListItemClick" + mCheckedPosition);
    }

    public void clearCheckedPosition() {
    	if (debbug) Log.d(TAG, "clearCheckedPosition" + mCheckedPosition);
        if (mCheckedPosition >= 0) {
            getListView().setItemChecked(mCheckedPosition, false);
            mCheckedPosition = -1;
        }
    }

    /**
     * {@link CursorAdapter} that renders a {@link SessionsQuery}.
     */
    private class SessionsAdapter extends CursorAdapter {
        public SessionsAdapter(Context context) {
            super(context, null);
        }
        
        @Override
        public Object getItem(int position) {
            if (debbug) Log.d(TAG, "getItem position " + position +  super.getItem(position));
            return super.getItem(position);
        }

        @Override
        public long getItemId(int position) {
            if (debbug) Log.d(TAG, " position : " + position);
            return super.getItemId(position);
        }        
    

        /** {@inheritDoc} */
        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return getActivity().getLayoutInflater().inflate(R.layout.list_item_session, parent,
                    false);
        }

        /** {@inheritDoc} */
        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final TextView titleView = (TextView) view.findViewById(R.id.session_title);
            final TextView subtitleView = (TextView) view.findViewById(R.id.session_subtitle);

            titleView.setText(cursor.getString(SessionsQuery.TITLE));

            // Format time block this session occupies
            final long blockStart = cursor.getLong(SessionsQuery.BLOCK_START);
            final long blockEnd = cursor.getLong(SessionsQuery.BLOCK_END);
            final String roomName = cursor.getString(SessionsQuery.ROOM_NAME);
            final String subtitle = formatSessionSubtitle(blockStart, blockEnd, roomName, context);

            subtitleView.setText(subtitle);

            final boolean starred = cursor.getInt(SessionsQuery.STARRED) != 0;
            view.findViewById(R.id.star_button).setVisibility(
                    starred ? View.VISIBLE : View.INVISIBLE);

            // Possibly indicate that the session has occurred in the past.
            UIUtils.setSessionTitleColor(blockStart, blockEnd, titleView, subtitleView);
        }
    }

    /**
     * {@link CursorAdapter} that renders a {@link SearchQuery}.
     */
    private class SearchAdapter extends CursorAdapter {
        public SearchAdapter(Context context) {
            super(context, null);
        }
        
        @Override
        public Object getItem(int position) {
            if (debbug) Log.d(TAG, "getItem position " + position +  super.getItem(position));
            return super.getItem(position);
        }

        @Override
        public long getItemId(int position) {
            if (debbug) Log.d(TAG, " position : " + position);
            return super.getItemId(position);
        }        

        /** {@inheritDoc} */
        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return getActivity().getLayoutInflater().inflate(R.layout.list_item_session, parent,
                    false);
        }

        /** {@inheritDoc} */
        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            ((TextView) view.findViewById(R.id.session_title)).setText(cursor
                    .getString(SearchQuery.TITLE));

            final String snippet = cursor.getString(SearchQuery.SEARCH_SNIPPET);

            final Spannable styledSnippet = buildStyledSnippet(snippet);
            ((TextView) view.findViewById(R.id.session_subtitle)).setText(styledSnippet);

            final boolean starred = cursor.getInt(SearchQuery.STARRED) != 0;
            view.findViewById(R.id.star_button).setVisibility(
                    starred ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private ContentObserver mSessionChangesObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            if (mCursor != null) {
                mCursor.requery();
            }
        }
    };

    private Runnable mRefreshSessionsRunnable = new Runnable() {
        public void run() {
            if (mAdapter != null) {
                // This is used to refresh session title colors.
                mAdapter.notifyDataSetChanged();
            }

            // Check again next third hour, with some padding to account for network
            // time differences.
            long thirdHour = (SystemClock.uptimeMillis() / 900000 + 1) *
                    (900000 * 4) + ((int)Math.random() * 100000);
            mMessageQueueHandler.postAtTime(mRefreshSessionsRunnable, thirdHour);
        }
    };

    /**
     * {@link org.dalmasso.ietfsched.provider.ScheduleContract.Sessions} query parameters.
     */
    private interface SessionsQuery {
        int _TOKEN = 0x1;

        String[] PROJECTION = {
                BaseColumns._ID,
                ScheduleContract.Sessions.SESSION_ID,
                ScheduleContract.Sessions.SESSION_TITLE,
                ScheduleContract.Sessions.SESSION_STARRED,
                ScheduleContract.Blocks.BLOCK_START,
                ScheduleContract.Blocks.BLOCK_END,
                ScheduleContract.Rooms.ROOM_NAME,
        };

        int _ID = 0;
        int SESSION_ID = 1;
        int TITLE = 2;
        int STARRED = 3;
        int BLOCK_START = 4;
        int BLOCK_END = 5;
        int ROOM_NAME = 6;
    }

    /**
     * {@link org.dalmasso.ietfsched.provider.ScheduleContract.Tracks} query parameters.
     */
    private interface TracksQuery {
        int _TOKEN = 0x2;

        String[] PROJECTION = {
                ScheduleContract.Tracks.TRACK_NAME,
                ScheduleContract.Tracks.TRACK_COLOR,
        };

        int TRACK_NAME = 0;
        int TRACK_COLOR = 1;
    }

    /** {@link org.dalmasso.ietfsched.provider.ScheduleContract.Sessions} search query
     * parameters. */
    private interface SearchQuery {
        int _TOKEN = 0x3;

        String[] PROJECTION = {
                BaseColumns._ID,
                ScheduleContract.Sessions.SESSION_ID,
                ScheduleContract.Sessions.SESSION_TITLE,
                ScheduleContract.Sessions.SEARCH_SNIPPET,
                ScheduleContract.Sessions.SESSION_STARRED,
        };

        int _ID = 0;
        int SESSION_ID = 1;
        int TITLE = 2;
        int SEARCH_SNIPPET = 3;
        int STARRED = 4;
    }
}
