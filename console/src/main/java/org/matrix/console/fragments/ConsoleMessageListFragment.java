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

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.adapters.MessageRow;
import org.matrix.androidsdk.adapters.MessagesAdapter;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.db.MXMediasCache;
import org.matrix.androidsdk.fragments.IconAndTextDialogFragment;
import org.matrix.androidsdk.fragments.MatrixMessageListFragment;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.FileMessage;
import org.matrix.androidsdk.rest.model.ImageMessage;
import org.matrix.androidsdk.rest.model.Message;
import org.matrix.androidsdk.rest.model.Receipt;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.VideoMessage;
import org.matrix.androidsdk.util.EventDisplay;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.console.ConsoleApplication;
import org.matrix.console.Matrix;
import org.matrix.console.R;
import org.matrix.console.activity.CommonActivityUtils;
import org.matrix.console.activity.ImageSliderActivity;
import org.matrix.console.activity.MXCActionBarActivity;
import org.matrix.console.activity.MemberDetailsActivity;
import org.matrix.console.activity.RoomActivity;
import org.matrix.console.adapters.ConsoleMessagesAdapter;
import org.matrix.console.db.ConsoleContentProvider;
import org.matrix.console.util.SlidableImageInfo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class ConsoleMessageListFragment extends MatrixMessageListFragment {

    private static final String TAG_FRAGMENT_RECEIPTS_DIALOG = "ConsoleMessageListFragment.TAG_FRAGMENT_RECEIPTS_DIALOG";

    public static ConsoleMessageListFragment newInstance(String matrixId, String roomId, int layoutResId) {
        ConsoleMessageListFragment f = new ConsoleMessageListFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ROOM_ID, roomId);
        args.putInt(ARG_LAYOUT_ID, layoutResId);
        args.putString(ARG_MATRIX_ID, matrixId);
        f.setArguments(args);
        return f;
    }

    @Override
    public MXSession getSession(String matrixId) {
        return Matrix.getMXSession(getActivity(), matrixId);
    }

    @Override
    public MXMediasCache getMXMediasCache() {
       return Matrix.getInstance(getActivity()).getMediasCache();
    }

    @Override
    public MessagesAdapter createMessagesAdapter() {
        return new ConsoleMessagesAdapter(mSession, getActivity(), getMXMediasCache());
    }

    /**
     * The user scrolls the list.
     * Apply an expected behaviour
     * @param event the scroll event
     */
    @Override
    public void onListTouch(MotionEvent event) {
        // the user scroll over the keyboard
        // hides the keyboard
        if (mCheckSlideToHide && (event.getY() > mMessageListView.getHeight())) {
            mCheckSlideToHide = false;
            MXCActionBarActivity.dismissKeyboard(getActivity());
        }
    }

    /**
     * return true to display all the events.
     * else the unknown events will be hidden.
     */
    @Override
    public boolean isDisplayAllEvents() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return preferences.getBoolean(getString(R.string.settings_key_display_all_events), false);
    }

    /**
     * Display a global spinner or any UI item to warn the user that there are some pending actions.
     */
    @Override
    public void displayLoadingProgress() {
        if (null != getActivity()) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (null != getActivity()) {
                        final View progressView = getActivity().findViewById(R.id.loading_room_content_progress);

                        if (null != progressView) {
                            progressView.setVisibility(View.VISIBLE);
                        }
                    }
                }
            });
        }
    }

    /**
     * Dismiss any global spinner.
     */
    @Override
    public void dismissLoadingProgress() {
        if (null != getActivity()) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (null != getActivity()) {
                        final View progressView = getActivity().findViewById(R.id.loading_room_content_progress);

                        if (null != progressView) {
                            progressView.setVisibility(View.GONE);
                        }
                    }
                }
            });
        }
    }

    /**
     * logout from the application
     */
    @Override
    public void logout() {
        CommonActivityUtils.logout(ConsoleMessageListFragment.this.getActivity());
    }


    /***  MessageAdapter listener  ***/
    @Override
    public void onRowClick(int position) {
        final MessageRow messageRow = mAdapter.getItem(position);
        final List<Integer> textIds = new ArrayList<>();
        final List<Integer> iconIds = new ArrayList<Integer>();

        String mediaUrl = null;
        String mediaMimeType = null;
        Uri mediaUri = null;
        Message message = JsonUtils.toMessage(messageRow.getEvent().content);

        if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(messageRow.getEvent().type) ||
                Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(messageRow.getEvent().type) ||
                Event.EVENT_TYPE_STATE_ROOM_NAME.equals(messageRow.getEvent().type) ||
                Message.MSGTYPE_EMOTE.equals(message.msgtype)
                ) {

            if (!messageRow.getEvent().userId.equals(getSession().getCredentials().userId)) {
                textIds.add(R.string.paste_username);
                iconIds.add(R.drawable.ic_material_paste);
            }

            textIds.add(R.string.copy);
            iconIds.add(R.drawable.ic_material_copy);
        } else  {

            // copy the message body
            if (Event.EVENT_TYPE_MESSAGE.equals(messageRow.getEvent().type)) {

                if (!messageRow.getEvent().userId.equals(getSession().getCredentials().userId)) {
                    textIds.add(R.string.paste_username);
                    iconIds.add(R.drawable.ic_material_paste);
                }

                if (Message.MSGTYPE_TEXT.equals(message.msgtype)) {
                    textIds.add(R.string.copy);
                    iconIds.add(R.drawable.ic_material_copy);
                }
            }

            if (messageRow.getEvent().canBeResent()) {
                textIds.add(R.string.resend);
                iconIds.add(R.drawable.ic_material_send);
            } else if (messageRow.getEvent().mSentState == Event.SentState.SENT) {
                textIds.add(R.string.redact);
                iconIds.add(R.drawable.ic_material_clear);
                if (Event.EVENT_TYPE_MESSAGE.equals(messageRow.getEvent().type)) {
                    Boolean supportShare = true;

                    // check if the media has been downloaded
                    if ((message instanceof ImageMessage) || (message instanceof FileMessage)) {
                        if (message instanceof ImageMessage) {
                            ImageMessage imageMessage = (ImageMessage) message;

                            mediaUrl = imageMessage.url;
                            mediaMimeType = imageMessage.getMimeType();
                        } else {
                            FileMessage fileMessage = (FileMessage) message;

                            mediaUrl = fileMessage.url;
                            mediaMimeType = fileMessage.getMimeType();
                        }

                        supportShare = false;
                        MXMediasCache cache = getMXMediasCache();

                        File mediaFile = cache.mediaCacheFile(mediaUrl, mediaMimeType);

                        if (null != mediaFile) {
                            try {
                                mediaUri = ConsoleContentProvider.absolutePathToUri(getActivity(), mediaFile.getAbsolutePath());
                                supportShare = true;
                            } catch (Exception e) {
                            }
                        }
                    }

                    if (supportShare) {
                        textIds.add(R.string.share);
                        iconIds.add(R.drawable.ic_material_share);

                        textIds.add(R.string.forward);
                        iconIds.add(R.drawable.ic_material_forward);

                        textIds.add(R.string.save);
                        iconIds.add(R.drawable.ic_material_save);
                    }
                }
            }
        }

        // display the JSON
        textIds.add(R.string.message_details);
        iconIds.add(R.drawable.ic_material_description);

        FragmentManager fm = getActivity().getSupportFragmentManager();
        IconAndTextDialogFragment fragment = (IconAndTextDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_MESSAGE_OPTIONS);

        if (fragment != null) {
            fragment.dismissAllowingStateLoss();
        }

        Integer[] lIcons = iconIds.toArray(new Integer[iconIds.size()]);
        Integer[] lTexts = textIds.toArray(new Integer[iconIds.size()]);

        final String  fmediaMimeType = mediaMimeType;
        final Uri fmediaUri = mediaUri;
        final String fmediaUrl = mediaUrl;
        final Message fMessage = message;

        fragment = IconAndTextDialogFragment.newInstance(lIcons, lTexts);
        fragment.setOnClickListener(new IconAndTextDialogFragment.OnItemClickListener() {
            @Override
            public void onItemClick(IconAndTextDialogFragment dialogFragment, int position) {
                final Integer selectedVal = textIds.get(position);

                if (selectedVal == R.string.copy) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                            Event event = messageRow.getEvent();
                            String text = "";

                            if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(messageRow.getEvent().type) ||
                                    Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(messageRow.getEvent().type) ||
                                    Event.EVENT_TYPE_STATE_ROOM_NAME.equals(messageRow.getEvent().type)) {

                                RoomState roomState = messageRow.getRoomState();
                                EventDisplay display = new EventDisplay(getActivity(), event, roomState);
                                text = display.getTextualDisplay().toString();
                            } else {
                                text = JsonUtils.toMessage(event.content).body;
                            }

                            ClipData clip = ClipData.newPlainText("", text);
                            clipboard.setPrimaryClip(clip);
                        }
                    });
                } else if (selectedVal == R.string.resend) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            resend(messageRow.getEvent());
                        }
                    });
                } else if (selectedVal == R.string.save) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            save(fMessage, fmediaUrl, fmediaMimeType);
                        }
                    });
                } else if (selectedVal == R.string.redact) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            redactEvent(messageRow.getEvent().eventId);
                        }
                    });
                } else if ((selectedVal == R.string.share) || (selectedVal == R.string.forward)) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Intent sendIntent = new Intent();
                            sendIntent.setAction(Intent.ACTION_SEND);

                            Event event = messageRow.getEvent();
                            Message message = JsonUtils.toMessage(event.content);

                            if (null != fmediaUri) {
                                sendIntent.setType(fmediaMimeType);
                                sendIntent.putExtra(Intent.EXTRA_STREAM, fmediaUri);
                            } else {
                                sendIntent.putExtra(Intent.EXTRA_TEXT, message.body);
                                sendIntent.setType("text/plain");
                            }

                            if (selectedVal == R.string.forward) {
                                CommonActivityUtils.sendFilesTo(getActivity(), sendIntent);
                            } else {
                                startActivity(sendIntent);
                            }
                        }
                    });
                } else if (selectedVal == R.string.message_details) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            FragmentManager fm =  getActivity().getSupportFragmentManager();

                            MessageDetailsFragment fragment = (MessageDetailsFragment) fm.findFragmentByTag(TAG_FRAGMENT_MESSAGE_DETAILS);
                            if (fragment != null) {
                                fragment.dismissAllowingStateLoss();
                            }
                            fragment = MessageDetailsFragment.newInstance(messageRow.getEvent().toString());
                            fragment.show(fm, TAG_FRAGMENT_MESSAGE_DETAILS);
                        }
                    });
                } else if (selectedVal == R.string.paste_username) {
                    String displayName = messageRow.getEvent().userId;
                    RoomState state = messageRow.getRoomState();

                    if (null != state) {
                        displayName = state.getMemberName(displayName);
                    }

                    onSenderNameClick(messageRow.getEvent().userId, displayName);
                }
            }
        });

        // GA issue
        // can not perform this action after onSaveInstanceState....
        // it seems that the linked activity is stopped.
        try {
            fragment.show(fm, TAG_FRAGMENT_MESSAGE_OPTIONS);
        } catch (Exception e) {
        }
    }

    public Boolean onRowLongClick(int position) {
        return false;
    }

    /**
     * @return the imageMessages list
     */
    private ArrayList<SlidableImageInfo> listImageMessages() {
        ArrayList<SlidableImageInfo> res = new ArrayList<SlidableImageInfo>();

        for(int position = 0; position < mAdapter.getCount(); position++) {
            MessageRow row = mAdapter.getItem(position);
            Message message = JsonUtils.toMessage(row.getEvent().content);

            if (Message.MSGTYPE_IMAGE.equals(message.msgtype)) {
                ImageMessage imageMessage = (ImageMessage)message;

                SlidableImageInfo info = new SlidableImageInfo();

                info.mImageUrl = imageMessage.url;
                info.mRotationAngle = imageMessage.getRotation();
                info.mOrientation = imageMessage.getOrientation();
                info.mMimeType = imageMessage.getMimeType();
                info.midentifier = row.getEvent().eventId;
                res.add(info);
            }
        }

        return res;
    }

    /**
     * Returns the imageMessages position in listImageMessages.
     * @param listImageMessages the messages list.
     * @param imageMessage the imageMessage
     * @return the imageMessage position. -1 if not found.
     */
    private int getImageMessagePosition(ArrayList<SlidableImageInfo> listImageMessages, ImageMessage imageMessage) {
        for(int index = 0; index < listImageMessages.size(); index++) {
            if (listImageMessages.get(index).mImageUrl.equals(imageMessage.url)) {
                return index;
            }
        }

        return -1;
    }


    public void onContentClick(int position) {
        MessageRow row = mAdapter.getItem(position);
        Event event = row.getEvent();
        Message message = JsonUtils.toMessage(event.content);

        // image message -> display it within the medias swipper
        if (Message.MSGTYPE_IMAGE.equals(message.msgtype)) {
            ImageMessage imageMessage = JsonUtils.toImageMessage(event.content);

            if (null != imageMessage.url) {
                ArrayList<SlidableImageInfo> listImageMessages = listImageMessages();
                int listPosition = getImageMessagePosition(listImageMessages, imageMessage);

                if (listPosition >= 0) {
                    Intent viewImageIntent = new Intent(getActivity(), ImageSliderActivity.class);

                    viewImageIntent.putExtra(ImageSliderActivity.KEY_THUMBNAIL_WIDTH, mAdapter.getMaxThumbnailWith());
                    viewImageIntent.putExtra(ImageSliderActivity.KEY_THUMBNAIL_HEIGHT, mAdapter.getMaxThumbnailHeight());
                    viewImageIntent.putExtra(ImageSliderActivity.KEY_INFO_LIST, listImageMessages);
                    viewImageIntent.putExtra(ImageSliderActivity.KEY_INFO_LIST_INDEX, listPosition);

                    getActivity().startActivity(viewImageIntent);
                }
            }
        } else if (Message.MSGTYPE_FILE.equals(message.msgtype)) {
            FileMessage fileMessage = JsonUtils.toFileMessage(event.content);

            if (null != fileMessage.url) {
                File mediaFile =  mSession.getMediasCache().mediaCacheFile(fileMessage.url, fileMessage.getMimeType());

                // is the file already saved
                if (null != mediaFile) {
                    String savedMediaPath = CommonActivityUtils.saveMediaIntoDownloads(getActivity(), mediaFile, fileMessage.body, fileMessage.getMimeType());
                    CommonActivityUtils.openMedia(getActivity(), savedMediaPath, fileMessage.getMimeType());
                } else {
                    mSession.getMediasCache().downloadMedia(getActivity(), mSession.getHomeserverConfig(), fileMessage.url, fileMessage.getMimeType());
                    mAdapter.notifyDataSetChanged();
                }
            }
        } else if (Message.MSGTYPE_VIDEO.equals(message.msgtype)) {
            VideoMessage videoMessage = JsonUtils.toVideoMessage(event.content);

            if (null != videoMessage.url) {
                File mediaFile =   mSession.getMediasCache().mediaCacheFile(videoMessage.url, videoMessage.getVideoMimeType());

                // is the file already saved
                if (null != mediaFile) {
                    String savedMediaPath = CommonActivityUtils.saveMediaIntoDownloads(getActivity(), mediaFile, videoMessage.body, videoMessage.getVideoMimeType());
                    CommonActivityUtils.openMedia(getActivity(), savedMediaPath, videoMessage.getVideoMimeType());
                } else {
                    mSession.getMediasCache().downloadMedia(getActivity(), mSession.getHomeserverConfig(), videoMessage.url, videoMessage.getVideoMimeType());
                    mAdapter.notifyDataSetChanged();
                }
            }
        } else {
            onRowClick(position);
        }
    }

    public Boolean onContentLongClick(int position) {
        return false;
    }

    public void onAvatarClick(String userId) {
        Intent startRoomInfoIntent = new Intent(getActivity(), MemberDetailsActivity.class);
        startRoomInfoIntent.putExtra(MemberDetailsActivity.EXTRA_ROOM_ID, mRoom.getRoomId());
        startRoomInfoIntent.putExtra(MemberDetailsActivity.EXTRA_MEMBER_ID, userId);
        startRoomInfoIntent.putExtra(MemberDetailsActivity.EXTRA_MATRIX_ID, mSession.getCredentials().userId);
        getActivity().startActivity(startRoomInfoIntent);
    }

    public Boolean onAvatarLongClick(String userId) {
        return false;
    }

    public void onSenderNameClick(String userId, String displayName) {
        if (getActivity() instanceof RoomActivity) {
            ((RoomActivity)getActivity()).insertInTextEditor(mRoom.getLiveState().getMemberName(userId));
        }
    }

    public void onMediaDownloaded(int position) {
    }


    private void save(final Message message, final String mediaUrl, final String mediaMimeType) {
        AlertDialog.Builder builderSingle = new AlertDialog.Builder(getActivity());

        builderSingle.setTitle(getActivity().getText(R.string.save_files_in));
        final ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(getActivity(), R.layout.dialog_room_selection);

        ArrayList<String> entries = new ArrayList<String>();

        entries.add(getActivity().getText(R.string.downloads).toString());

        if ((null == mediaMimeType) || mediaMimeType.startsWith("image/")) {
            entries.add(getActivity().getText(R.string.gallery).toString());
        }

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            entries.add(getActivity().getText(R.string.other).toString());
        }

        arrayAdapter.addAll(entries);

        final ArrayList<String> fEntries = entries;

        builderSingle.setNegativeButton(getActivity().getText(R.string.cancel),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });

        builderSingle.setAdapter(arrayAdapter,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, final int which) {
                        dialog.dismiss();

                        MXMediasCache cache = getMXMediasCache();
                        File cacheFile = cache.mediaCacheFile(mediaUrl, mediaMimeType);

                        String entry = fEntries.get(which);
                        String savedFilename = null;

                        if (getActivity().getText(R.string.gallery).toString().equals(entry)) {
                            // save in the gallery
                            savedFilename = CommonActivityUtils.saveImageIntoGallery(getActivity(), cacheFile);
                        } else if (getActivity().getText(R.string.downloads).toString().equals(entry)) {
                            String filename = null;

                            if (message instanceof FileMessage)  {
                                filename = ((FileMessage)message).body;
                            }

                            // save into downloads
                            savedFilename = CommonActivityUtils.saveMediaIntoDownloads(getActivity(), cacheFile, filename, mediaMimeType);
                        } else {
                            if (getActivity() instanceof RoomActivity) {
                                ((RoomActivity)getActivity()).createDocument(message, mediaUrl, mediaMimeType);
                            }
                        }

                        if (null != savedFilename) {
                            final String fSavedFilename = new File(savedFilename).getName();
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(getActivity(), getActivity().getString(R.string.file_is_saved, fSavedFilename), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                });

        // GA issue
        // can not perform this action after onSaveInstanceState....
        // it seems that the linked activity is stopped.
        try {
            builderSingle.show();
        } catch (Exception e) {
        }
    }

    public void onReadReceiptClick(String eventId, String userId, Receipt receipt) {
        RoomMember member = mRoom.getMember(userId);

        // sanity check
        if (null != member) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

            SpannableStringBuilder body = new SpannableStringBuilder(getActivity().getString(R.string.read_receipt) + " : " + dateFormat.format(new Date(receipt.originServerTs)));
            body.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, getActivity().getString(R.string.read_receipt).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            new AlertDialog.Builder(ConsoleApplication.getCurrentActivity())
                    .setTitle(member.getName())
                    .setMessage(body)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                            .create()
                            .show();
        }
    }

    public void onMoreReadReceiptClick(String eventId) {
        FragmentManager fm = getActivity().getSupportFragmentManager();

        ReadReceiptsDialogFragment fragment = (ReadReceiptsDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_RECEIPTS_DIALOG);
        if (fragment != null) {
            fragment.dismissAllowingStateLoss();
        }
        fragment = ReadReceiptsDialogFragment.newInstance(mSession, mRoom.getRoomId(), eventId);
        fragment.show(fm, TAG_FRAGMENT_RECEIPTS_DIALOG);
    }
}
