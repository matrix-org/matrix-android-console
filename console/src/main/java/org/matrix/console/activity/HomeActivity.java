/*
 * Copyright 2014 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.console.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.v4.app.FragmentManager;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.TextView;
import android.widget.Toast;

import org.matrix.androidsdk.HomeserverConnectionConfig;
import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.call.IMXCall;
import org.matrix.androidsdk.call.MXCallsManager;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.data.RoomSummary;
import org.matrix.androidsdk.fragments.IconAndTextDialogFragment;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.client.LoginRestClient;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.PublicRoom;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.login.Credentials;
import org.matrix.androidsdk.util.EventUtils;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.console.ConsoleApplication;
import org.matrix.console.LoginHandler;
import org.matrix.console.Matrix;
import org.matrix.console.MyPresenceManager;
import org.matrix.console.R;
import org.matrix.console.ViewedRoomTracker;
import org.matrix.console.adapters.ConsoleRoomSummaryAdapter;
import org.matrix.console.adapters.DrawerAdapter;
import org.matrix.console.db.ConsoleContentProvider;
import org.matrix.console.fragments.AccountsSelectionDialogFragment;
import org.matrix.console.fragments.ContactsListDialogFragment;
import org.matrix.console.fragments.RoomCreationDialogFragment;
import org.matrix.console.gcm.GcmRegistrationManager;
import org.matrix.console.services.EventStreamService;
import org.matrix.console.util.RageShake;
import org.matrix.console.view.AddAccountAlertDialog;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;


/**
 * Displays the main screen of the app, with rooms the user has joined and the ability to create
 * new rooms.
 */
public class HomeActivity extends MXCActionBarActivity {

    private static final String LOG_TAG = "HomeActivity";

    private ExpandableListView mMyRoomList = null;

    private static final String PUBLIC_ROOMS_LIST_LIST = "PUBLIC_ROOMS_LIST_LIST";

    private static final String TAG_FRAGMENT_CONTACTS_LIST = "org.matrix.console.HomeActivity.TAG_FRAGMENT_CONTACTS_LIST";
    private static final String TAG_FRAGMENT_CREATE_ROOM_DIALOG = "org.matrix.console.HomeActivity.TAG_FRAGMENT_CREATE_ROOM_DIALOG";

    private static final String TAG_FRAGMENT_ROOM_OPTIONS = "org.matrix.console.HomeActivity.TAG_FRAGMENT_ROOM_OPTIONS";

    public static final String EXTRA_JUMP_TO_ROOM_ID = "org.matrix.console.HomeActivity.EXTRA_JUMP_TO_ROOM_ID";
    public static final String EXTRA_JUMP_MATRIX_ID = "org.matrix.console.HomeActivity.EXTRA_JUMP_MATRIX_ID";
    public static final String EXTRA_ROOM_INTENT = "org.matrix.console.HomeActivity.EXTRA_ROOM_INTENT";

    private ArrayList<String> mHomeServerNames = null;
    private ArrayList<List<PublicRoom>> mPublicRoomsListList = null;

    private boolean mIsPaused = false;

    private ArrayList<Integer> mExpandedGroups = null;

    private String mAutomaticallyOpenedRoomId = null;
    private String mAutomaticallyOpenedMatrixId = null;
    private Intent mOpenedRoomIntent = null;

    private boolean refreshOnChunkEnd = false;

    private MenuItem mCallMenuItem = null;

    // about
    private AlertDialog mMainAboutDialog = null;
    private String mLicenseString = null;

    // sliding menu
    private final Integer[] mSlideMenuTitleIds = new Integer[]{
            //R.string.action_search_contact,
            //R.string.action_search_room,
            R.string.create_room,
            R.string.join_room,
           // R.string.action_mark_all_as_read,
            R.string.action_add_account,
            R.string.action_remove_account,
            R.string.action_settings,
            R.string.action_disconnect,
            R.string.action_logout,
            R.string.send_bug_report,
            R.string.about,
    };

    // sliding menu
    private final Integer[] mSlideMenuResourceIds = new Integer[]{
            //R.drawable.ic_material_search, // R.string.action_search_contact,
            //R.drawable.ic_material_find_in_page, // R.string.action_search_room,
            R.drawable.ic_material_group_add, //R.string.create_room,
            R.drawable.ic_material_group, // R.string.join_room,
            //R.drawable.ic_material_done_all, // R.string.action_mark_all_as_read,
            R.drawable.ic_material_person_add, // R.string.action_add_account,
            R.drawable.ic_material_remove_circle_outline, // R.string.action_remove_account,
            R.drawable.ic_material_settings, //  R.string.action_settings,
            R.drawable.ic_material_clear, // R.string.action_disconnect,
            R.drawable.ic_material_exit_to_app, // R.string.action_logout,
            R.drawable.ic_material_bug_report, // R.string.send_bug_report,
            R.drawable.ic_menu_matrix_transparent, // R.string.about,
    };

    private HashMap<MXSession, MXEventListener> mListenersBySession = new HashMap<MXSession, MXEventListener>();
    private HashMap<MXSession, MXCallsManager.MXCallsManagerListener> mCallListenersBySession = new HashMap<MXSession, MXCallsManager.MXCallsManagerListener>();

    private ConsoleRoomSummaryAdapter mAdapter;
    private EditText mSearchRoomEditText;

    private void refreshPublicRoomsList() {
        refreshPublicRoomsList(new ArrayList<MXSession>(Matrix.getInstance(getApplicationContext()).getSessions()), new ArrayList<String>(), 0, new ArrayList<List<PublicRoom>>());
    }

    private void refreshPublicRoomsList(final ArrayList<MXSession> sessions, final ArrayList<String> checkedHomeServers, final int index, final ArrayList<List<PublicRoom>> publicRoomsListList) {
        // sanity checks
        if ((null == sessions) || (index >= sessions.size())) {
            Log.d(LOG_TAG, "notifyDataSetChanged after the public rooms update.");

            mAdapter.setPublicRoomsList(publicRoomsListList, checkedHomeServers);
            mAdapter.notifyDataSetChanged();
            mPublicRoomsListList = publicRoomsListList;
            mHomeServerNames = checkedHomeServers;
            return;
        }

        final MXSession session = sessions.get(index);

        // check if the session is still active
        if (session.isActive()) {
            final String homeServerUrl = session.getHomeserverConfig().getHomeserverUri().toString();

            // the home server has already been checked ?
            if (checkedHomeServers.indexOf(homeServerUrl) >= 0) {
                // jump to the next session
                refreshPublicRoomsList(sessions, checkedHomeServers, index + 1, publicRoomsListList);
            } else {
                // use any session to get the public rooms list
                session.getEventsApiClient().loadPublicRooms(new SimpleApiCallback<List<PublicRoom>>(this) {
                    @Override
                    public void onSuccess(List<PublicRoom> publicRooms) {
                        checkedHomeServers.add(homeServerUrl);
                        publicRoomsListList.add(publicRooms);

                        // jump to the next session
                        refreshPublicRoomsList(sessions, checkedHomeServers, index + 1, publicRoomsListList);
                    }
                });
            }
        } else {
            refreshPublicRoomsList(sessions, checkedHomeServers, index + 1, publicRoomsListList);
        }
    }

    private void joinPublicRoom(final String homeServerURL, final PublicRoom publicRoom) {
        Collection<MXSession> sessions = Matrix.getMXSessions(HomeActivity.this);
        ArrayList<MXSession> matchedSessions = new ArrayList<MXSession>();

        for(MXSession session : sessions) {
            String sessionHsUrl = session.getHomeserverConfig().getHomeserverUri().toString();
            if (sessionHsUrl.equals(homeServerURL)) {
                matchedSessions.add(session);
            }
        }

        if (matchedSessions.size() == 1) {
            CommonActivityUtils.goToRoomPage(matchedSessions.get(0), publicRoom.roomId, HomeActivity.this, null);
        } else if (matchedSessions.size() > 1) {

            FragmentManager fm = getSupportFragmentManager();

            AccountsSelectionDialogFragment fragment = (AccountsSelectionDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);
            if (fragment != null) {
                fragment.dismissAllowingStateLoss();
            }

            fragment = AccountsSelectionDialogFragment.newInstance(matchedSessions);

            fragment.setListener(new AccountsSelectionDialogFragment.AccountsListener() {
                @Override
                public void onSelected(final MXSession session) {

                    HomeActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            CommonActivityUtils.goToRoomPage(session, publicRoom.roomId, HomeActivity.this, null);
                        }
                    });
                }
            });

            fragment.show(fm, TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);

        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (CommonActivityUtils.shouldRestartApp()) {
            Log.e(LOG_TAG, "Restart the application.");
            CommonActivityUtils.restartApp(this);
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        mMyRoomList = (ExpandableListView) findViewById(R.id.listView_myRooms);
        // the chevron is managed in the header view
        mMyRoomList.setGroupIndicator(null);
        mAdapter = new ConsoleRoomSummaryAdapter(this, Matrix.getMXSessions(this), R.layout.adapter_item_my_rooms, R.layout.adapter_room_section_header);

        if (null != savedInstanceState) {
            if (savedInstanceState.containsKey(PUBLIC_ROOMS_LIST_LIST)) {
                Serializable map = savedInstanceState.getSerializable(PUBLIC_ROOMS_LIST_LIST);

                if (null != map) {
                    HashMap<String, List<PublicRoom>> hash = (HashMap<String, List<PublicRoom>>) map;
                    mPublicRoomsListList = new ArrayList<List<PublicRoom>>(hash.values());
                    mHomeServerNames = new ArrayList<>(hash.keySet());
                }
            }
        }

        final Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_JUMP_TO_ROOM_ID)) {
            mAutomaticallyOpenedRoomId = intent.getStringExtra(EXTRA_JUMP_TO_ROOM_ID);
        }

        if (intent.hasExtra(EXTRA_JUMP_MATRIX_ID)) {
            mAutomaticallyOpenedMatrixId = intent.getStringExtra(EXTRA_JUMP_MATRIX_ID);
        }

        if (intent.hasExtra(EXTRA_ROOM_INTENT)) {
            mOpenedRoomIntent = intent.getParcelableExtra(EXTRA_ROOM_INTENT);
        }

        String action = intent.getAction();
        String type = intent.getType();

        // send files from external application
        if ((Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) && type != null) {
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    CommonActivityUtils.sendFilesTo(HomeActivity.this, intent);
                }
            });
        }

        mMyRoomList.setAdapter(mAdapter);
        Collection<MXSession> sessions = Matrix.getMXSessions(HomeActivity.this);

        // check if  there is some valid session
        // the home activity could be relaunched after an application crash
        // so, reload the sessions before displaying the hidtory
        if (sessions.size() == 0) {
            Log.e(LOG_TAG, "Weird : onCreate : no session");

            if (null != Matrix.getInstance(this).getDefaultSession()) {
                Log.e(LOG_TAG, "No loaded session : reload them");
                startActivity(new Intent(HomeActivity.this, SplashActivity.class));
                HomeActivity.this.finish();
                return;
            }
        }

        for(MXSession session : sessions) {
            addSessionListener(session);
        }

        mMyRoomList.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView parent, View v,
                                        int groupPosition, int childPosition, long id) {

                if (mAdapter.isRecentsGroupIndex(groupPosition)) {
                    String roomId = null;
                    MXSession session = null;

                    RoomSummary roomSummary = mAdapter.getRoomSummaryAt(groupPosition, childPosition);
                    session = Matrix.getInstance(HomeActivity.this).getSession(roomSummary.getMatrixId());

                    roomId = roomSummary.getRoomId();
                    Room room = session.getDataHandler().getRoom(roomId);
                    // cannot join a leaving room
                    if ((null == room) || room.isLeaving()) {
                        roomId = null;
                    }

                    if (mAdapter.resetUnreadCount(groupPosition, roomId)) {
                        session.getDataHandler().getStore().flushSummary(roomSummary);
                    }

                    if (null != roomId){
                        CommonActivityUtils.goToRoomPage(session, roomId, HomeActivity.this, null);
                    }

                } else if (mAdapter.isPublicsGroupIndex(groupPosition)) {
                    joinPublicRoom(mAdapter.getHomeServerURLAt(groupPosition), mAdapter.getPublicRoomAt(groupPosition, childPosition));
                }

                return true;
            }
        });

        mMyRoomList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {

                if (ExpandableListView.getPackedPositionType(id) == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
                    long packedPos = ((ExpandableListView) parent).getExpandableListPosition(position);
                    final int groupPosition = ExpandableListView.getPackedPositionGroup(packedPos);

                    if (mAdapter.isRecentsGroupIndex(groupPosition)) {
                        final int childPosition = ExpandableListView.getPackedPositionChild(packedPos);

                        FragmentManager fm = HomeActivity.this.getSupportFragmentManager();
                        IconAndTextDialogFragment fragment = (IconAndTextDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ROOM_OPTIONS);

                        if (fragment != null) {
                            fragment.dismissAllowingStateLoss();
                        }

                        final Integer[] lIcons = new Integer[]{R.drawable.ic_material_exit_to_app};
                        final Integer[] lTexts = new Integer[]{R.string.action_leave};

                        fragment = IconAndTextDialogFragment.newInstance(lIcons, lTexts);
                        fragment.setOnClickListener(new IconAndTextDialogFragment.OnItemClickListener() {
                            @Override
                            public void onItemClick(IconAndTextDialogFragment dialogFragment, int position) {
                                Integer selectedVal = lTexts[position];

                                if (selectedVal == R.string.action_leave) {
                                    HomeActivity.this.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            final RoomSummary roomSummary = mAdapter.getRoomSummaryAt(groupPosition, childPosition);
                                            final MXSession session = Matrix.getInstance(HomeActivity.this).getSession(roomSummary.getMatrixId());

                                            String roomId = roomSummary.getRoomId();
                                            Room room = session.getDataHandler().getRoom(roomId);

                                            if (null != room) {
                                                room.leave(new SimpleApiCallback<Void>(HomeActivity.this) {
                                                    @Override
                                                    public void onSuccess(Void info) {
                                                        mAdapter.removeRoomSummary(groupPosition, roomSummary);
                                                        mAdapter.notifyDataSetChanged();
                                                    }
                                                });
                                            }
                                        }
                                    });
                                }
                            }
                        });

                        fragment.show(fm, TAG_FRAGMENT_ROOM_OPTIONS);

                        return true;
                    }
                }

                return false;
            }
        });

        mMyRoomList.setOnGroupExpandListener(new ExpandableListView.OnGroupExpandListener() {
            @Override
            public void onGroupExpand(int groupPosition) {
                if (mAdapter.isPublicsGroupIndex(groupPosition)) {
                    refreshPublicRoomsList();
                }
            }
        });

        mMyRoomList.setOnGroupClickListener(new ExpandableListView.OnGroupClickListener() {
            @Override
            public boolean onGroupClick(ExpandableListView parent, View v, int groupPosition, long id) {
                return mAdapter.getGroupCount() < 2;
            }
        });

        mSearchRoomEditText = (EditText) this.findViewById(R.id.editText_search_room);
        mSearchRoomEditText.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(android.text.Editable s) {
                mAdapter.setSearchedPattern(s.toString());
                mMyRoomList.smoothScrollToPosition(0);
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);

        if (null != mPublicRoomsListList) {
            HashMap<String, List<PublicRoom>> hash = new HashMap<String, List<PublicRoom>>();

            for(int index = 0; index < mHomeServerNames.size(); index++) {
                hash.put(mHomeServerNames.get(index), mPublicRoomsListList.get(index));
            }

            savedInstanceState.putSerializable(PUBLIC_ROOMS_LIST_LIST, hash);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
    }

    public ArrayList<Integer> getExpandedGroupsList() {
        ArrayList<Integer> expList = new ArrayList<Integer>();

        int groupCount = mMyRoomList.getExpandableListAdapter().getGroupCount();

        for(int groupindex = 0; groupindex < groupCount; groupindex++) {
            if (mMyRoomList.isGroupExpanded(groupindex)) {
                expList.add(groupindex);
            }
        }

        return expList;
    }

    public void expandGroupIndexes(ArrayList<Integer> indexesList) {
        if (null == indexesList) {
            expandAllGroups();
        } else {

            int groupCount = mMyRoomList.getExpandableListAdapter().getGroupCount();

            for (Integer group : indexesList) {
                // check bounds else it could trigger weird UI effect (a list section is duplicated).
                if (group < groupCount) {
                    mMyRoomList.expandGroup(group);
                }
            }
        }
    }

    private void expandAllGroups() {
        final int groupCount = mMyRoomList.getExpandableListAdapter().getGroupCount();

        for(int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            mMyRoomList.expandGroup(groupIndex);
        }
    }

    private void collapseAllGroups() {
        final int groupCount = mMyRoomList.getExpandableListAdapter().getGroupCount();

        for(int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            mMyRoomList.collapseGroup(groupIndex);
        }
    }

    private void toggleSearchButton() {
        if (mSearchRoomEditText.getVisibility() == View.GONE) {
            mSearchRoomEditText.setVisibility(View.VISIBLE);

            // need to collapse/expand the groups to avoid invalid refreshes
            collapseAllGroups();
            mAdapter.setDisplayAllGroups(true);
            expandAllGroups();
        } else {
            // need to collapse/expand the groups to avoid invalid refreshes
            collapseAllGroups();
            mAdapter.setDisplayAllGroups(false);
            expandAllGroups();

            mSearchRoomEditText.setVisibility(View.GONE);

            expandAllGroups();

            // force to hide the keyboard
            mSearchRoomEditText.postDelayed(new Runnable() {
                public void run() {
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(getApplication().INPUT_METHOD_SERVICE);
                    keyboard.hideSoftInputFromWindow(
                            mSearchRoomEditText.getWindowToken(), 0);
                }
            }, 200);
        }

        mSearchRoomEditText.setText("");
    }

    @SuppressLint("NewApi")
    private boolean isScreenOn() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            return powerManager.isInteractive();
        } else {
            return powerManager.isScreenOn();
        }
    }

    /**
     * Add a MXEventListener to the session listeners.
     * @param session the sessions.
     */
    private void addSessionListener(final MXSession session) {
        removeSessionListener(session);

        MXEventListener listener = new MXEventListener() {
            private boolean mInitialSyncComplete = false;

            @Override
            public void onInitialSyncComplete() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mInitialSyncComplete = true;

                        Collection<RoomSummary> summaries = session.getDataHandler().getStore().getSummaries();

                        Log.e(LOG_TAG, ">>> onInitialSyncComplete : summaries " + summaries.size());


                        for (RoomSummary summary : summaries) {
                            addSummary(summary);
                        }

                        // highlighted public rooms
                        mAdapter.highlightRoom("Matrix HQ");
                        mAdapter.highlightRoom("#matrix:matrix.org");
                        mAdapter.highlightRoom("#matrix-dev:matrix.org");
                        mAdapter.highlightRoom("#matrix-fr:matrix.org");

                        mAdapter.sortSummaries();
                        mAdapter.setPublicRoomsList(mPublicRoomsListList, mHomeServerNames);


                        expandAllGroups();

                        // load the public load in background
                        // done onResume
                        //refreshPublicRoomsList();
                    }
                });
            }

            @Override
            public void onRoomInitialSyncComplete(final String roomId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAdapter.sortSummaries();
                        mAdapter.notifyDataSetChanged();
                    }
                });
            }

            @Override
            public void onRoomInternalUpdate(String roomId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAdapter.sortSummaries();
                        mAdapter.notifyDataSetChanged();
                    }
                });
            }


            @Override
            public void onReceiptEvent(String roomId) {
                HomeActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshOnChunkEnd = true;
                    }
                });

            }

            @Override
            public void onLiveEventsChunkProcessed() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(LOG_TAG, "onLiveEventsChunkProcessed");
                        if (!mIsPaused && refreshOnChunkEnd) {
                            mAdapter.sortSummaries();
                            mAdapter.notifyDataSetChanged();
                        }

                        refreshOnChunkEnd = false;
                    }
                });
            }

            @Override
            public void onLiveEvent(final Event event, final RoomState roomState) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if ((event.roomId != null) && isDisplayableEvent(event)) {
                            List<MXSession> sessions = new ArrayList<MXSession>(Matrix.getMXSessions(HomeActivity.this));
                            final int section = sessions.indexOf(session);
                            String matrixId = session.getCredentials().userId;

                            mAdapter.setLatestEvent(section, event, roomState, false);

                            RoomSummary summary = mAdapter.getSummaryByRoomId(section, event.roomId);

                            if (summary == null) {
                                // ROOM_CREATE events will be sent during initial sync. We want to ignore them
                                // until the initial sync is done (that is, only refresh the list when there
                                // are new rooms created AFTER we have synced).
                                if (mInitialSyncComplete) {
                                    if (Event.EVENT_TYPE_STATE_ROOM_CREATE.equals(event.type)) {
                                        addNewRoom(event.roomId);
                                    } else if (Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)) {
                                        RoomMember member = JsonUtils.toRoomMember(event.content);

                                        // add the room summary if the user has
                                        if ((RoomMember.MEMBERSHIP_INVITE.equals(member.membership) || RoomMember.MEMBERSHIP_JOIN.equals(member.membership))
                                                && event.stateKey.equals(matrixId)) {
                                            // we were invited to a new room.
                                            addNewRoom(event.roomId);
                                        }
                                    }
                                }
                            }

                            // If we've left the room, remove it from the list
                            else if (mInitialSyncComplete && Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)) {
                                if (!(session.getDataHandler().doesRoomExist(event.roomId)) || isMembershipInRoom(RoomMember.MEMBERSHIP_LEAVE, matrixId, summary)) {
                                    mAdapter.removeRoomSummary(section, summary);
                                }
                            }
                            // Watch for potential room name changes
                            else if (Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)
                                    || Event.EVENT_TYPE_STATE_ROOM_ALIASES.equals(event.type)
                                    || Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)) {
                                summary.setName(session.getDataHandler().getRoom(event.roomId).getName(matrixId));
                            }

                            ViewedRoomTracker rTracker = ViewedRoomTracker.getInstance();
                            String viewedRoomId = rTracker.getViewedRoomId();
                            String fromMatrixId = rTracker.getMatrixId();

                            // If we're not currently viewing this room or not sent by myself, increment the unread count
                            if ((!event.roomId.equals(viewedRoomId) || !matrixId.equals(fromMatrixId))  && !event.userId.equals(matrixId)) {
                                if (null != summary) {
                                    summary.setHighlighted(summary.isHighlighted() || EventUtils.shouldHighlight(session, event));
                                }
                            }

                            refreshOnChunkEnd = true;
                        }
                    }
                });
            }

            // White list of displayable events
            private boolean isDisplayableEvent(Event event) {
                return Event.EVENT_TYPE_MESSAGE.equals(event.type)
                        || Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)
                        || Event.EVENT_TYPE_STATE_ROOM_CREATE.equals(event.type)
                        || Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)
                        || Event.EVENT_TYPE_STATE_ROOM_ALIASES.equals(event.type)
                        || Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(event.type);
            }

            private void addNewRoom(String roomId) {
                RoomSummary summary = session.getDataHandler().getStore().getSummary(roomId);
                addSummary(summary);
                mAdapter.sortSummaries();
            }

            private boolean isMembershipInRoom(String membership, String selfUserId, RoomSummary summary) {
                Room room = session.getDataHandler().getStore().getRoom(summary.getRoomId());

                if (null != room) {
                    Collection <RoomMember> members = room.getMembers();

                    for (RoomMember member : members) {
                        if (membership.equals(member.membership) && selfUserId.equals(member.getUserId())) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private void addSummary(RoomSummary summary) {
                String selfUserId = session.getCredentials().userId;

                if (summary.isInvited()) {
                    Room room = session.getDataHandler().getStore().getRoom(summary.getRoomId());

                    // display the room name instead of "Room invitation"
                    // at least, you know who invited you
                    if (null != room) {
                        summary.setName(room.getName(session.getCredentials().userId));
                    } else if (null == summary.getRoomName()) {
                        summary.setName(getString(R.string.summary_invitation));
                    }
                }

                // only add summaries to rooms we have not left.
                if (!isMembershipInRoom(RoomMember.MEMBERSHIP_LEAVE, selfUserId, summary)) {
                    List<MXSession> sessions = new ArrayList<MXSession>(Matrix.getMXSessions(HomeActivity.this));
                    int section = sessions.indexOf(session);

                    mAdapter.addRoomSummary(section, summary);
                }
            }
        };

        session.getDataHandler().addListener(listener);
        mListenersBySession.put(session, listener);

        // call listener
        MXCallsManager.MXCallsManagerListener callsManagerListener = new MXCallsManager.MXCallsManagerListener() {

            /**
             * Called when there is an incoming call within the room.
             */
            @Override
            public void onIncomingCall(final IMXCall call) {
                // can only manage one call instance.
                if (null == CallViewActivity.getActiveCall()) {
                    // display the call activity only if the application is in background.
                    if (HomeActivity.this.isScreenOn()) {
                        // create the call object
                        if (null != call) {
                            final Intent intent = new Intent(HomeActivity.this, CallViewActivity.class);

                            intent.putExtra(CallViewActivity.EXTRA_MATRIX_ID, session.getCredentials().userId);
                            intent.putExtra(CallViewActivity.EXTRA_CALL_ID, call.getCallId());

                            HomeActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    HomeActivity.this.startActivity(intent);
                                }
                            });
                        }
                    }
                } else {
                    HomeActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            call.hangup("busy");
                        }
                    });
                }
            }

            /**
             * Called when a called has been hung up
             */
            @Override
            public void onCallHangUp(IMXCall call) {
                final Boolean isActiveCall = CallViewActivity.isBackgroundedCallId(call.getCallId());

                HomeActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isActiveCall) {
                            ConsoleApplication.getInstance().onCallEnd();
                            HomeActivity.this.manageCallButton();

                            CallViewActivity.startEndCallSound(HomeActivity.this);
                        }
                    }
                });
            }
        };

        session.mCallsManager.addListener(callsManagerListener);
        mCallListenersBySession.put(session, callsManagerListener);
    }

    /**
     * Remove the MXEventListener to the session listeners.
     * @param session the sessions.
     */
    private void removeSessionListener(final MXSession session) {
        if (mListenersBySession.containsKey(session)) {
            session.getDataHandler().removeListener(mListenersBySession.get(session));
            mListenersBySession.remove(session);
        }

        if (mCallListenersBySession.containsKey(session)) {
            session.mCallsManager.removeListener(mCallListenersBySession.get(session));
            mCallListenersBySession.remove(session);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Collection<MXSession> sessions = Matrix.getInstance(this).getSessions();

        for(MXSession session : sessions) {
            removeSessionListener(session);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mExpandedGroups = getExpandedGroupsList();
        mIsPaused = true;
    }

    private void refreshSlidingList() {
        // adjust the sliding menu entries
        ArrayList<Integer> slideMenuTitleIds = new ArrayList<Integer>(Arrays.asList(mSlideMenuTitleIds));
        ArrayList<Integer> slideMenuResourceIds = new ArrayList<Integer>(Arrays.asList(mSlideMenuResourceIds));

        Matrix matrix = Matrix.getInstance(this);

        // only one account, do not offer to remove it
        if (matrix.getSessions().size() == 1) {

            int pos = slideMenuTitleIds.indexOf(R.string.action_remove_account);

            if (pos >= 0) {
                slideMenuTitleIds.remove(pos);
                slideMenuResourceIds.remove(pos);
            }
        }

        GcmRegistrationManager gcmManager = Matrix.getInstance(this).getSharedGcmRegistrationManager();

        // hide the disconnect when GCM is enabled.
        if ((null != gcmManager) && gcmManager.useGCM()) {
            int pos = slideMenuTitleIds.indexOf(R.string.action_disconnect);

            if (pos >= 0) {
                slideMenuTitleIds.remove(pos);
                slideMenuResourceIds.remove(pos);
            }
        }

        // apply the updated sliding list
        Integer[] slideMenuTitleIds2 = new Integer[slideMenuTitleIds.size()];
        Integer[] slideMenuResourceIds2 = new Integer[slideMenuTitleIds.size()];
        addSlidingMenu(slideMenuResourceIds.toArray(slideMenuResourceIds2), slideMenuTitleIds.toArray(slideMenuTitleIds2), true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mIsPaused = false;

        // some unsent messages could have been added
        // it does not trigger any live event.
        // So, it is safer to sort the messages when debackgrounding
        mAdapter.sortSummaries();
        // expand/collapse to force th group refresh
        collapseAllGroups();
        // all the groups must be displayed during a search
        mAdapter.setDisplayAllGroups(mSearchRoomEditText.getVisibility() == View.VISIBLE);
        mAdapter.notifyDataSetChanged();

        // expand previously expanded groups.
        // to restore the same UX
        expandGroupIndexes(mExpandedGroups);

        if (null != mAutomaticallyOpenedRoomId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    CommonActivityUtils.goToRoomPage(mAutomaticallyOpenedMatrixId, HomeActivity.this.mAutomaticallyOpenedRoomId, HomeActivity.this, mOpenedRoomIntent);
                    HomeActivity.this.mAutomaticallyOpenedRoomId = null;
                    HomeActivity.this.mAutomaticallyOpenedMatrixId = null;
                    HomeActivity.this.mOpenedRoomIntent = null;
                }
            });
        }

        refreshSlidingList();
        manageCallButton();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent.hasExtra(EXTRA_JUMP_TO_ROOM_ID)) {
            mAutomaticallyOpenedRoomId = intent.getStringExtra(EXTRA_JUMP_TO_ROOM_ID);
        }

        if (intent.hasExtra(EXTRA_JUMP_MATRIX_ID)) {
            mAutomaticallyOpenedMatrixId = intent.getStringExtra(EXTRA_JUMP_MATRIX_ID);
        }

        if (intent.hasExtra(EXTRA_ROOM_INTENT)) {
            mOpenedRoomIntent = intent.getParcelableExtra(EXTRA_ROOM_INTENT);
        }
    }

    @Override
    protected void selectDrawItem(int position) {
        // Highlight the selected item, update the title, and close the drawer
        mDrawerList.setItemChecked(position, true);

        final int id =  ((DrawerAdapter.Entry)(mDrawerList.getAdapter().getItem(position))).mIconResourceId;

        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (id == R.drawable.ic_material_search) {
                    toggleSearchContacts();
                } else if (id == R.drawable.ic_material_find_in_page) {
                    toggleSearchButton();
                } else if (id == R.drawable.ic_material_group_add) {
                    createRoom();
                } else if (id == R.drawable.ic_material_group) {
                    joinRoomByName();
                } else if (id == R.drawable.ic_material_done_all) {
                    markAllMessagesAsRead();
                } else if (id == R.drawable.ic_material_settings) {
                    HomeActivity.this.startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
                } else if (id == R.drawable.ic_material_clear) {
                    CommonActivityUtils.disconnect(HomeActivity.this);
                } else if (id == R.drawable.ic_material_bug_report) {
                    RageShake.getInstance().sendBugReport();
                } else if (id == R.drawable.ic_material_exit_to_app) {
                    CommonActivityUtils.logout(HomeActivity.this);
                } else if (id == R.drawable.ic_material_person_add) {
                    HomeActivity.this.addAccount();
                } else if (id == R.drawable.ic_material_remove_circle_outline) {
                    HomeActivity.this.removeAccount();
                } else if (id == R.drawable.ic_menu_matrix_transparent) {
                    HomeActivity.this.displayAbout();
                }
            }
        });

        mDrawerLayout.closeDrawer(mDrawerList);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_SEARCH)) {
            toggleSearchButton();
            return true;
        }

        if ((keyCode == KeyEvent.KEYCODE_MENU)) {
            HomeActivity.this.startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    /**
     * Display or hide the the call button.
     * it is used to resume a call.
     */
    private void manageCallButton() {
        if (null != mCallMenuItem) {
            mCallMenuItem.setVisible(CallViewActivity.getActiveCall() != null);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.home, menu);

        mCallMenuItem = menu.findItem(R.id.ic_action_resume_call);
        manageCallButton();

        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.ic_action_search_contact) {
            toggleSearchContacts();
        } else if (id == R.id.ic_action_search_room) {
            toggleSearchButton();
        } else if (id == R.id.ic_action_mark_all_as_read) {
            markAllMessagesAsRead();
        } else if (id == R.id.ic_action_resume_call) {
            IMXCall call = CallViewActivity.getActiveCall();
            if (null != call) {
                final Intent intent = new Intent(HomeActivity.this, CallViewActivity.class);
                intent.putExtra(CallViewActivity.EXTRA_MATRIX_ID, call.getSession().getCredentials().userId);
                intent.putExtra(CallViewActivity.EXTRA_CALL_ID, call.getCallId());

                HomeActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        HomeActivity.this.startActivity(intent);
                    }
                });
            }

        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleSearchContacts() {
        FragmentManager fm = getSupportFragmentManager();

        ContactsListDialogFragment fragment = (ContactsListDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_CONTACTS_LIST);
        if (fragment != null) {
            fragment.dismissAllowingStateLoss();
        }
        fragment = ContactsListDialogFragment.newInstance();
        fragment.show(fm, TAG_FRAGMENT_CONTACTS_LIST);
    }

    private void joinRoomByName() {
        if (Matrix.getMXSessions(this).size() == 1) {
            joinRoomByName(Matrix.getMXSession(this, null));
        } else {
            FragmentManager fm = getSupportFragmentManager();

            AccountsSelectionDialogFragment fragment = (AccountsSelectionDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);
            if (fragment != null) {
                fragment.dismissAllowingStateLoss();
            }

            fragment = AccountsSelectionDialogFragment.newInstance(Matrix.getMXSessions(getApplicationContext()));

            fragment.setListener(new AccountsSelectionDialogFragment.AccountsListener() {
                @Override
                public void onSelected(final MXSession session) {

                    HomeActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            HomeActivity.this.joinRoomByName(session);
                        }
                    });
                }
            });

            fragment.show(fm, TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);
        }
    }

    private void joinRoomByName(final MXSession session) {
        AlertDialog alert = CommonActivityUtils.createEditTextAlert(this, getString(R.string.join_room_title),  getString(R.string.join_room_hint), null, new CommonActivityUtils.OnSubmitListener() {
            @Override
            public void onSubmit(String text) {
                session.joinRoom(text, new ApiCallback<String>() {
                    @Override
                    public void onSuccess(String roomId) {
                        if (null != roomId) {
                            CommonActivityUtils.goToRoomPage((MXSession) null, roomId, HomeActivity.this, null);
                        }
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        Toast.makeText(HomeActivity.this,
                                getString(R.string.network_error),
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        Toast.makeText(HomeActivity.this,
                                e.error,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        Toast.makeText(HomeActivity.this,
                                e.getLocalizedMessage(),
                                Toast.LENGTH_LONG).show();

                    }
                });
            }

            @Override
            public void onCancelled() {}
        });
        alert.show();
    }

    private void createRoom() {
        if (Matrix.getMXSessions(this).size() == 1) {
            // use the default session
            createRoom(Matrix.getMXSession(this, null));
        } else {
            FragmentManager fm = getSupportFragmentManager();

            AccountsSelectionDialogFragment fragment = (AccountsSelectionDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);
            if (fragment != null) {
                fragment.dismissAllowingStateLoss();
            }

            fragment = AccountsSelectionDialogFragment.newInstance(Matrix.getMXSessions(getApplicationContext()));

            fragment.setListener(new AccountsSelectionDialogFragment.AccountsListener() {
                @Override
                public void onSelected(final MXSession session) {

                    HomeActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            HomeActivity.this.createRoom(session);
                        }
                    });
                }
            });

            fragment.show(fm, TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);
        }
    }

    private void createRoom(MXSession session) {
        FragmentManager fm = getSupportFragmentManager();

        RoomCreationDialogFragment fragment = (RoomCreationDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_CREATE_ROOM_DIALOG);
        if (fragment != null) {
            fragment.dismissAllowingStateLoss();
        }
        fragment = RoomCreationDialogFragment.newInstance(session);
        fragment.show(fm, TAG_FRAGMENT_CREATE_ROOM_DIALOG);
    }


    private void markAllMessagesAsRead() {
        // flush the summaries
        ArrayList<MXSession> sessions = new ArrayList<MXSession>(Matrix.getMXSessions(HomeActivity.this));
        ;
        for (int index = 0; index < sessions.size(); index++) {
            MXSession session = sessions.get(index);

            // flush only if there is an update
            if (mAdapter.resetUnreadCounts(index)) {
                session.getDataHandler().getStore().flushSummaries();
            }
        }

        mAdapter.notifyDataSetChanged();
    }

    /**
     * Add an existing account
     */
    private void addAccount() {
        (new AddAccountAlertDialog(this)).show();
    }

    private void removeAccount() {
        final ArrayList<MXSession> sessions = new ArrayList<MXSession>(Matrix.getMXSessions(this));

        // only one session -> loggout
        if (sessions.size() == 1) {
            CommonActivityUtils.logout(HomeActivity.this);
        }

        FragmentManager fm = getSupportFragmentManager();

        AccountsSelectionDialogFragment fragment = (AccountsSelectionDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);
        if (fragment != null) {
            fragment.dismissAllowingStateLoss();
        }

        fragment = AccountsSelectionDialogFragment.newInstance(Matrix.getMXSessions(getApplicationContext()));

        fragment.setListener(new AccountsSelectionDialogFragment.AccountsListener() {
            @Override
            public void onSelected(final MXSession session) {
                HomeActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        final int sectionPos = sessions.indexOf(session);

                        CommonActivityUtils.logout(HomeActivity.this, session, true);

                        HomeActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // expand/collapse to force the group refresh
                                collapseAllGroups();

                                mAdapter.removeSection(sectionPos);
                                mAdapter.notifyDataSetChanged();

                                // all the groups must be displayed during a search
                                mAdapter.setDisplayAllGroups(mSearchRoomEditText.getVisibility() == View.VISIBLE);
                                expandAllGroups();
                            }
                        });
                    }
                });
            }
        });

        fragment.show(fm, TAG_FRAGMENT_ACCOUNT_SELECTION_DIALOG);
    }

    // trick to trap the clink on the Licenses link
    class MovementCheck extends LinkMovementMethod {
        @Override
        public boolean onTouchEvent(TextView widget,
                                    Spannable buffer, MotionEvent event ) {
            int action = event.getAction();

            if (action == MotionEvent.ACTION_UP)
            {
                int x = (int) event.getX();
                int y = (int) event.getY();

                x -= widget.getTotalPaddingLeft();
                y -= widget.getTotalPaddingTop();

                x += widget.getScrollX();
                y += widget.getScrollY();

                Layout layout = widget.getLayout();
                int line = layout.getLineForVertical(y);
                int off = layout.getOffsetForHorizontal(line, x);

                URLSpan[] link = buffer.getSpans(off, off, URLSpan.class);
                if (link.length != 0)
                {
                    // display the license
                    displayLicense();
                    return true;
                }
            }

            return super.onTouchEvent(widget, buffer, event);
        }
    }

    /**
     * Display the licenses text
     */
    private void displayLicense() {
        if (null != mMainAboutDialog) {
            mMainAboutDialog.dismiss();
            mMainAboutDialog = null;
        }

        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final AlertDialog dialog = new AlertDialog.Builder(HomeActivity.this)
                        .setPositiveButton(android.R.string.ok, null)
                        .setMessage(mLicenseString)
                        .setTitle("Third Part licenses")
                        .create();
                dialog.show();
            }
        });
    }

    /**
     * Display third party licenses
     */
    private void displayAbout() {

        if (null == mLicenseString) {
            // build a local license file
            InputStream inputStream = this.getResources().openRawResource(R.raw.all_licenses);
            StringBuilder buf = new StringBuilder();

            try {
                String str;
                BufferedReader in = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

                while ((str = in.readLine()) != null) {
                    buf.append(str);
                    buf.append("\n");
                }

                in.close();
            } catch (Exception e) {

            }

            mLicenseString = buf.toString();
        }

        // sanity check
        if (null == mLicenseString) {
            return;
        }

        File cachedLicenseFile = new File(getFilesDir(), "Licenses.txt");
        // convert the file to content:// uri
        Uri uri = ConsoleContentProvider.absolutePathToUri(this, cachedLicenseFile.getAbsolutePath());

        if (null == uri) {
            return;
        }

        String message = "<div class=\"banner\"> <div class=\"l-page no-clear align-center\"> <h2 class=\"s-heading\">"+ getString(R.string.settings_title_config) + "</h2> </div> </div>";

        String versionName = "";
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pInfo.versionName;
        } catch (Exception e) {

        }

        message += "<strong>matrixConsole version</strong> <br>" + versionName;
        message += "<p><strong>SDK version</strong> <br>" + versionName;
        message += "<div class=\"banner\"> <div class=\"l-page no-clear align-center\"> <h2 class=\"s-heading\">Third Party Library Licenses</h2> </div> </div>";
        message += "<a href=\"" + uri.toString() + "\">Licenses</a>";

        Spanned text = Html.fromHtml(message);

        mMainAboutDialog = new AlertDialog.Builder(this)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(text)
                .setIcon(R.drawable.ic_menu_small_matrix_transparent)
                .create();
        mMainAboutDialog.show();

        // allow link to be clickable
        ((TextView)mMainAboutDialog.findViewById(android.R.id.message)).setMovementMethod(new MovementCheck());
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data)  {
        if (AddAccountAlertDialog.FALLBACK_LOGIN_ACTIVITY_REQUEST_CODE == requestCode) {
            AddAccountAlertDialog.onFlowActivityResult(this, requestCode, resultCode, data);
        }
    }
}
