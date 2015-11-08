/*
 * Copyright 2015 OpenMarket Ltd
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

package org.matrix.console.fragments;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.IMXStore;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.db.MXMediasCache;
import org.matrix.androidsdk.listeners.IMXEventListener;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.Receipt;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.console.ConsoleApplication;
import org.matrix.console.Matrix;
import org.matrix.console.R;
import org.matrix.console.activity.MemberDetailsActivity;
import org.matrix.console.adapters.ConsoleRoomMembersAdapter;
import org.matrix.console.adapters.ReadReceiptsAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * A dialog fragment showing the read receipts
 */
public class ReadReceiptsDialogFragment extends DialogFragment {
    private static final String LOG_TAG = "ReadRctDlgFragment";

    public static final String ARG_ROOM_ID = "ReadReceiptsDialogFragment.ARG_ROOM_ID";
    public static final String ARG_EVENT_ID = "ReadReceiptsDialogFragment.ARG_EVENT_ID";


    public static ReadReceiptsDialogFragment newInstance(MXSession session, String roomId, String eventId) {
        ReadReceiptsDialogFragment f = new ReadReceiptsDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ROOM_ID, roomId);
        args.putString(ARG_EVENT_ID, eventId);
        f.setArguments(args);
        f.setSession(session);
        return f;
    }

    private ListView mListView;
    private ReadReceiptsAdapter mAdapter;
    private String mRoomId;
    private String mEventId;
    private MXSession mSession;


    public void setSession(MXSession session) {
        mSession = session;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRoomId = getArguments().getString(ARG_ROOM_ID);
        mEventId = getArguments().getString(ARG_EVENT_ID);

        if (mSession == null) {
            throw new RuntimeException("No MXSession.");
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog d = super.onCreateDialog(savedInstanceState);
        d.setTitle(getString(R.string.read_receipts_list));
        return d;
    }

    /**
     * Return the used medias cache.
     * This method can be overridden to use another medias cache
     * @return the used medias cache
     */
    public MXMediasCache getMXMediasCache() {
        return Matrix.getInstance(getActivity()).getMediasCache();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View v = inflater.inflate(R.layout.fragment_dialog_member_list, container, false);
        mListView = ((ListView)v.findViewById(R.id.listView_members));

        final Room room = mSession.getDataHandler().getRoom(mRoomId);
        mAdapter = new ReadReceiptsAdapter(getActivity(), mSession.getHomeserverConfig(), R.layout.adapter_item_read_receipt, room, getMXMediasCache());

        mAdapter.addAll(new ArrayList<Receipt>(mSession.getDataHandler().getStore().getEventReceipts(mRoomId, mEventId)));
        mListView.setAdapter(mAdapter);

        return v;
    }
}
