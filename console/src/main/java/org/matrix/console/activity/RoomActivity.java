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

package org.matrix.console.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.support.v4.app.FragmentManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.call.IMXCall;
import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.db.MXLatestChatMessageCache;
import org.matrix.androidsdk.db.MXMediasCache;
import org.matrix.androidsdk.fragments.IconAndTextDialogFragment;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.FileMessage;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.Message;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;
import org.matrix.androidsdk.util.BingRulesManager;
import org.matrix.androidsdk.util.ImageUtils;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.console.ConsoleApplication;
import org.matrix.console.Matrix;
import org.matrix.console.R;
import org.matrix.console.ViewedRoomTracker;
import org.matrix.console.adapters.ImageCompressionDescription;
import org.matrix.console.fragments.ConsoleMessageListFragment;
import org.matrix.console.fragments.ImageSizeSelectionDialogFragment;
import org.matrix.console.fragments.MembersInvitationDialogFragment;
import org.matrix.console.fragments.RoomInfoUpdateDialogFragment;
import org.matrix.console.fragments.RoomMembersDialogFragment;
import org.matrix.console.services.EventStreamService;
import org.matrix.console.util.NotificationUtils;
import org.matrix.console.util.RageShake;
import org.matrix.console.util.ResourceUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Displays a single room with messages.
 */
public class RoomActivity extends MXCActionBarActivity {

    public static final String EXTRA_ROOM_ID = "org.matrix.console.RoomActivity.EXTRA_ROOM_ID";

    // the room is launched but it expects to start the dedicated call activity
    public static final String EXTRA_START_CALL_ID = "org.matrix.console.RoomActivity.EXTRA_START_CALL_ID";

    private static final String TAG_FRAGMENT_MATRIX_MESSAGE_LIST = "org.matrix.console.RoomActivity.TAG_FRAGMENT_MATRIX_MESSAGE_LIST";
    private static final String TAG_FRAGMENT_MEMBERS_DIALOG = "org.matrix.console.RoomActivity.TAG_FRAGMENT_MEMBERS_DIALOG";
    private static final String TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG = "org.matrix.console.RoomActivity.TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG";
    private static final String TAG_FRAGMENT_ATTACHMENTS_DIALOG = "org.matrix.console.RoomActivity.TAG_FRAGMENT_ATTACHMENTS_DIALOG";
    private static final String TAG_FRAGMENT_IMAGE_SIZE_DIALOG = "org.matrix.console.RoomActivity.TAG_FRAGMENT_IMAGE_SIZE_DIALOG";
    private static final String TAG_FRAGMENT_ROOM_INFO = "org.matrix.console.RoomActivity.TAG_FRAGMENT_ROOM_INFO";


    private static final String LOG_TAG = "RoomActivity";
    private static final int TYPING_TIMEOUT_MS = 10000;

    private static final String PENDING_THUMBNAIL_URL = "PENDING_THUMBNAIL_URL";
    private static final String PENDING_MEDIA_URL = "PENDING_MEDIA_URL";
    private static final String PENDING_MIMETYPE = "PENDING_MIMETYPE";
    private static final String PENDING_FILENAME = "PENDING_FILENAME";

    private static final String FIRST_VISIBLE_ROW = "FIRST_VISIBLE_ROW";

    private static final String CAMERA_VALUE_TITLE = "attachment"; // Samsung devices need a filepath to write to or else won't return a Uri (!!!)

    // defines the command line operations
    // the user can write theses messages to perform some room events
    private static final String CMD_CHANGE_DISPLAY_NAME = "/nick";
    private static final String CMD_EMOTE = "/me";
    private static final String CMD_JOIN_ROOM = "/join";
    private static final String CMD_KICK_USER = "/kick";
    private static final String CMD_BAN_USER = "/ban";
    private static final String CMD_UNBAN_USER = "/unban";
    private static final String CMD_SET_USER_POWER_LEVEL = "/op";
    private static final String CMD_RESET_USER_POWER_LEVEL = "/deop";

    private static final int REQUEST_FILES = 0;
    private static final int TAKE_IMAGE = 1;
    private static final int CREATE_DOCUMENT = 2;
    private static final int TAKE_VIDEO = 3;

    // max image sizes
    private static final int LARGE_IMAGE_SIZE  = 2000;
    private static final int MEDIUM_IMAGE_SIZE = 1000;
    private static final int SMALL_IMAGE_SIZE  = 500;

    private ConsoleMessageListFragment mConsoleMessageListFragment;
    private MXSession mSession;
    private Room mRoom;
    private String mMyUserId;

    private MXLatestChatMessageCache mLatestChatMessageCache;
    private MXMediasCache mMediasCache;

    private ImageButton mSendButton;
    private ImageButton mAttachmentButton;
    private EditText mEditText;

    private View mImagePreviewLayout;
    private ImageView mImagePreviewView;
    private ImageButton mImagePreviewButton;

    private String mPendingThumbnailUrl;
    private String mPendingMediaUrl;
    private String mPendingMimeType;
    private String mPendingFilename;

    private MenuItem mVoiceMenuItem = null;
    private MenuItem mVideoMenuItem = null;

    private Boolean mRuleInProgress = false;
    private BingRule mBingRule = null;
    private MenuItem mEnableNotifItem = null;
    private MenuItem mDisableNotifItem = null;

    private String mCallId = null;

    private static String mLatestTakePictureCameraUri = null; // has to be String not Uri because of Serializable

    // typing event management
    private Timer mTypingTimer = null;
    private TimerTask mTypingTimerTask;
    private long  mLastTypingDate = 0;

    // scroll to a dedicated index
    private int mScrollToIndex = -1;

    private Boolean mIgnoreTextUpdate = false;

    private MXEventListener mEventListener = new MXEventListener() {
        @Override
        public void onLiveEvent(final Event event, RoomState roomState) {
            RoomActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    // The various events that could possibly change the room title
                    if (Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)
                            || Event.EVENT_TYPE_STATE_ROOM_ALIASES.equals(event.type)
                            || Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)) {
                        setTitle(mRoom.getName(mMyUserId));
                        updateMenuEntries();

                        // check if the user does not leave the room with another client
                        if (Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type) && mMyUserId.equals(event.stateKey)) {
                            RoomMember myMember = mRoom.getMember(mMyUserId);

                            if ((null != myMember) && (RoomMember.MEMBERSHIP_LEAVE.equals(myMember.membership))) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        RoomActivity.this.finish();
                                    }
                                });
                            }
                        }
                    }
                    else if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(event.type)) {
                        Log.e(LOG_TAG, "Updating room topic.");
                        RoomState roomState = JsonUtils.toRoomState(event.content);
                        setTopic(roomState.topic);
                    }

                    if (!ConsoleApplication.isAppInBackground()) {
                        mRoom.sendReadReceipt();
                    }
                }
            });
        }

        @Override
        public void onRoomInitialSyncComplete(String roomId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // set general room information
                    setTitle(mRoom.getName(mMyUserId));
                    setTopic(mRoom.getTopic());

                    mConsoleMessageListFragment.onInitialMessagesLoaded();

                    updateMenuEntries();
                }
            });
        }

        @Override
        public void onBingRulesUpdate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateMenuEntries();
                    mConsoleMessageListFragment.onBingRulesUpdate();
                }
            });
        }
    };

    public void insertInTextEditor(String text) {
        if (null != text) {
            if (TextUtils.isEmpty(mEditText.getText())) {
                mEditText.append(text + ": ");
            } else {
                mEditText.getText().insert(mEditText.getSelectionStart(), text);
            }
        }
    }

    /**
     * Launch the files selection intent
     */
    private void launchFileSelectionIntent() {
        Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            fileIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        fileIntent.setType("*/*");
        startActivityForResult(fileIntent, REQUEST_FILES);
    }

    /**
     * Launch the camera
     */
    private void launchVideo() {
        final Intent captureIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        // lowest quality
        captureIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
        RoomActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startActivityForResult(captureIntent, TAKE_VIDEO);
            }
        });
    }

    /**
     * Launch the camera
     */
    private void launchCamera() {
        final Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        // the following is a fix for buggy 2.x devices
        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, CAMERA_VALUE_TITLE + formatter.format(date));
        // The Galaxy S not only requires the name of the file to output the image to, but will also not
        // set the mime type of the picture it just took (!!!). We assume that the Galaxy S takes image/jpegs
        // so the attachment uploader doesn't freak out about there being no mimetype in the content database.
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        Uri dummyUri = null;
        try {
            dummyUri = RoomActivity.this.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (null == dummyUri) {
                Log.e(LOG_TAG, "Cannot use the external storage media to save image");
            }
        }
        catch (UnsupportedOperationException uoe) {
            Log.e(LOG_TAG, "Unable to insert camera URI into MediaStore.Images.Media.EXTERNAL_CONTENT_URI - no SD card? Attempting to insert into device storage.");
        }
        catch (Exception e) {
            Log.e(LOG_TAG, "Unable to insert camera URI into MediaStore.Images.Media.EXTERNAL_CONTENT_URI. "+e);
        }

        if (null == dummyUri) {
            try {
                dummyUri = RoomActivity.this.getContentResolver().insert(MediaStore.Images.Media.INTERNAL_CONTENT_URI, values);
                if (null == dummyUri) {
                    Log.e(LOG_TAG, "Cannot use the internal storage to save media to save image");
                }

            } catch (Exception e) {
                Log.e(LOG_TAG, "Unable to insert camera URI into internal storage. Giving up. " + e);
            }
        }

        if (dummyUri != null) {
            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, dummyUri);
            Log.d(LOG_TAG, "trying to take a photo on " + dummyUri.toString());
        } else {
            Log.d(LOG_TAG, "trying to take a photo with no predefined uri");
        }

        // Store the dummy URI which will be set to a placeholder location. When all is lost on samsung devices,
        // this will point to the data we're looking for.
        // Because Activities tend to use a single MediaProvider for all their intents, this field will only be the
        // *latest* TAKE_PICTURE Uri. This is deemed acceptable as the normal flow is to create the intent then immediately
        // fire it, meaning onActivityResult/getUri will be the next thing called, not another createIntentFor.
        RoomActivity.this.mLatestTakePictureCameraUri = dummyUri == null ? null : dummyUri.toString();

        RoomActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startActivityForResult(captureIntent, TAKE_IMAGE);
            }
        });
    }

    private class ImageSize {
        public int mWidth;
        public int mHeight;

        public ImageSize(ImageSize other) {
            mWidth = other.mWidth;
            mHeight = other.mHeight;
        }

        public ImageSize(int width, int height) {
            mWidth = width;
            mHeight = height;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (CommonActivityUtils.shouldRestartApp()) {
            Log.e(LOG_TAG, "Restart the application.");
            CommonActivityUtils.restartApp(this);
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        Intent intent = getIntent();
        if (!intent.hasExtra(EXTRA_ROOM_ID)) {
            Log.e(LOG_TAG, "No room ID extra.");
            finish();
            return;
        }

        if (intent.hasExtra(EXTRA_START_CALL_ID)) {
            mCallId = intent.getStringExtra(EXTRA_START_CALL_ID);
        }

        // the user has tapped on the "View" notification button
        if ((null != intent.getAction()) && (intent.getAction().startsWith(NotificationUtils.TAP_TO_VIEW_ACTION))) {
            // remove any pending notifications
            NotificationManager notificationsManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationsManager.cancelAll();
        }

        mPendingThumbnailUrl = null;
        mPendingMediaUrl = null;
        mPendingMimeType = null;
        mPendingFilename = null;

        if (null != savedInstanceState) {
            if (savedInstanceState.containsKey(PENDING_THUMBNAIL_URL)) {
                mPendingThumbnailUrl = savedInstanceState.getString(PENDING_THUMBNAIL_URL);
                Log.d(LOG_TAG, "Restore mPendingThumbnailUrl " +  mPendingThumbnailUrl);
            }

            if (savedInstanceState.containsKey(PENDING_MEDIA_URL)) {
                mPendingMediaUrl = savedInstanceState.getString(PENDING_MEDIA_URL);
                Log.d(LOG_TAG, "Restore mPendingMediaUrl " +  mPendingMediaUrl);
            }

            if (savedInstanceState.containsKey(PENDING_MIMETYPE)) {
                mPendingMimeType = savedInstanceState.getString(PENDING_MIMETYPE);
                Log.d(LOG_TAG, "Restore mPendingMimeType " +  mPendingMimeType);
            }

            if (savedInstanceState.containsKey(PENDING_FILENAME)) {
                mPendingFilename = savedInstanceState.getString(PENDING_FILENAME);
                Log.d(LOG_TAG, "Restore mPendingFilename " +  mPendingFilename);
            }
        }

        String roomId = intent.getStringExtra(EXTRA_ROOM_ID);
        Log.i(LOG_TAG, "Displaying " + roomId);

        mEditText = (EditText) findViewById(R.id.editText_messageBox);

        mSendButton = (ImageButton) findViewById(R.id.button_send);
        mSendButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                // send the previewed image ?
                if (null != mPendingThumbnailUrl) {
                    boolean sendMedia = true;

                    // check if the media could be resized
                    if ("image/jpeg".equals(mPendingMimeType)) {

                        System.gc();
                        FileInputStream imageStream = null;

                        try {
                            Uri uri = Uri.parse(mPendingMediaUrl);
                            final String filename = uri.getPath();

                            final int rotationAngle = ImageUtils.getRotationAngleForBitmap(RoomActivity.this, uri);

                            imageStream = new FileInputStream (new File(filename));

                            int fileSize =  imageStream.available();

                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inJustDecodeBounds = true;
                            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                            options.outWidth = -1;
                            options.outHeight = -1;

                            // get the full size bitmap
                            Bitmap fullSizeBitmap = null;
                            try {
                                fullSizeBitmap = BitmapFactory.decodeStream(imageStream, null, options);
                            } catch (OutOfMemoryError e) {
                                Log.e(LOG_TAG, "Onclick BitmapFactory.decodeStream : " + e.getMessage());
                            }

                            final ImageSize fullImageSize = new ImageSize(options.outWidth, options.outHeight);

                            imageStream.close();

                            int maxSide = (fullImageSize.mHeight >  fullImageSize.mWidth) ? fullImageSize.mHeight : fullImageSize.mWidth;

                            // can be rescaled ?
                            if (maxSide > SMALL_IMAGE_SIZE) {
                                ImageSize largeImageSize = null;

                                int divider = 2;

                                if (maxSide > LARGE_IMAGE_SIZE) {
                                    largeImageSize = new ImageSize((fullImageSize.mWidth + (divider-1)) / divider, (fullImageSize.mHeight + (divider-1)) / divider);
                                    divider *= 2;
                                }

                                ImageSize mediumImageSize = null;

                                if (maxSide > MEDIUM_IMAGE_SIZE)  {
                                    mediumImageSize = new ImageSize((fullImageSize.mWidth + (divider-1)) / divider, (fullImageSize.mHeight + (divider-1)) / divider);
                                    divider *= 2;
                                }

                                ImageSize smallImageSize = null;

                                if (maxSide > SMALL_IMAGE_SIZE)  {
                                    smallImageSize = new ImageSize((fullImageSize.mWidth + (divider-1)) / divider, (fullImageSize.mHeight + (divider-1)) / divider);
                                }

                                FragmentManager fm = getSupportFragmentManager();
                                ImageSizeSelectionDialogFragment fragment = (ImageSizeSelectionDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_IMAGE_SIZE_DIALOG);

                                if (fragment != null) {
                                    fragment.dismissAllowingStateLoss();
                                }

                                final ArrayList<ImageCompressionDescription> textsList = new ArrayList<ImageCompressionDescription>();
                                final ArrayList<ImageSize> sizesList = new ArrayList<ImageSize>();

                                ImageCompressionDescription description = new ImageCompressionDescription();
                                description.mCompressionText = getString(R.string.compression_opt_list_original);
                                description.mCompressionInfoText = fullImageSize.mWidth + "x" + fullImageSize.mHeight + " (" + android.text.format.Formatter.formatFileSize(RoomActivity.this, fileSize) + ")";

                                textsList.add(description);
                                sizesList.add(fullImageSize);

                                if (null != largeImageSize) {
                                    int estFileSize = largeImageSize.mWidth * largeImageSize.mHeight * 2 / 10 / 1024 * 1024;

                                    description = new ImageCompressionDescription();
                                    description.mCompressionText = getString(R.string.compression_opt_list_large);
                                    description.mCompressionInfoText = largeImageSize.mWidth + "x" + largeImageSize.mHeight + " (~" + android.text.format.Formatter.formatFileSize(RoomActivity.this, estFileSize) + ")";

                                    textsList.add(description);
                                    sizesList.add(largeImageSize);
                                }

                                if (null != mediumImageSize) {
                                    int estFileSize = mediumImageSize.mWidth * mediumImageSize.mHeight * 2 / 10 / 1024 * 1024;

                                    description = new ImageCompressionDescription();
                                    description.mCompressionText = getString(R.string.compression_opt_list_medium);
                                    description.mCompressionInfoText = mediumImageSize.mWidth + "x" + mediumImageSize.mHeight + " (~" + android.text.format.Formatter.formatFileSize(RoomActivity.this, estFileSize) + ")";

                                    textsList.add(description);
                                    sizesList.add(mediumImageSize);
                                }

                                if (null != smallImageSize) {
                                    int estFileSize = smallImageSize.mWidth * smallImageSize.mHeight * 2 / 10 / 1024 * 1024;

                                    description = new ImageCompressionDescription();
                                    description.mCompressionText = getString(R.string.compression_opt_list_small);
                                    description.mCompressionInfoText = smallImageSize.mWidth + "x" + smallImageSize.mHeight + " (~" + android.text.format.Formatter.formatFileSize(RoomActivity.this, estFileSize) + ")";

                                    textsList.add(description);
                                    sizesList.add(smallImageSize);
                                }

                                fragment = ImageSizeSelectionDialogFragment.newInstance(textsList);
                                fragment.setListener( new ImageSizeSelectionDialogFragment.ImageSizeListener() {
                                    @Override
                                    public void onSelected(int pos) {
                                        final int fPos = pos;

                                        RoomActivity.this.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    // pos == 0 -> original
                                                    if (0 != fPos) {
                                                        FileInputStream imageStream = new FileInputStream (new File(filename));

                                                        ImageSize imageSize = sizesList.get(fPos);
                                                        InputStream resizeBitmapStream = null;

                                                        try {
                                                            resizeBitmapStream = ImageUtils.resizeImage(imageStream, -1, (fullImageSize.mWidth + imageSize.mWidth - 1) / imageSize.mWidth, 75);
                                                        } catch (OutOfMemoryError ex) {
                                                            Log.e(LOG_TAG, "Onclick BitmapFactory.createScaledBitmap : " + ex.getMessage());
                                                        } catch (Exception e) {
                                                            Log.e(LOG_TAG, "Onclick BitmapFactory.createScaledBitmap failed : " + e.getMessage());
                                                        }

                                                        if (null != resizeBitmapStream) {
                                                            String bitmapURL = mMediasCache.saveMedia(resizeBitmapStream, null, "image/jpeg");


                                                            if (null != bitmapURL) {
                                                                mPendingMediaUrl = bitmapURL;
                                                            }

                                                            resizeBitmapStream.close();

                                                            // try to apply exif rotation
                                                            if (0 != rotationAngle) {
                                                                // rotate the image content
                                                                ImageUtils.rotateImage(RoomActivity.this, mPendingMediaUrl, rotationAngle, mMediasCache);
                                                            }
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    Log.e(LOG_TAG, "Onclick " + e.getMessage());
                                                }

                                                //
                                                mConsoleMessageListFragment.uploadImageContent(mPendingThumbnailUrl, mPendingMediaUrl, mPendingFilename, mPendingMimeType);
                                                mPendingThumbnailUrl = null;
                                                mPendingMediaUrl = null;
                                                mPendingMimeType = null;
                                                mPendingFilename = null;
                                                manageSendMoreButtons();
                                            }
                                        });
                                    }
                                });

                                fragment.show(fm, TAG_FRAGMENT_IMAGE_SIZE_DIALOG);
                                sendMedia = false;
                            }

                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Onclick " + e.getMessage());
                        }
                    }

                    if (sendMedia) {
                        mConsoleMessageListFragment.uploadImageContent(mPendingThumbnailUrl, mPendingMediaUrl, mPendingFilename, mPendingMimeType);
                        mPendingThumbnailUrl = null;
                        mPendingMediaUrl = null;
                        mPendingMimeType = null;
                        mPendingFilename = null;
                        manageSendMoreButtons();
                    }
                } else {
                    String body = mEditText.getText().toString();
                    sendMessage(body);
                    mEditText.setText("");
                }
            }
        });

        mAttachmentButton = (ImageButton) findViewById(R.id.button_more);
        mAttachmentButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                FragmentManager fm = getSupportFragmentManager();
                IconAndTextDialogFragment fragment = (IconAndTextDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ATTACHMENTS_DIALOG);

                if (fragment != null) {
                    fragment.dismissAllowingStateLoss();
                }

                final Integer[] messages = new Integer[]{
                        R.string.option_send_files,
                        R.string.option_take_photo,
                        R.string.option_take_video
                };

                final Integer[] icons = new Integer[]{
                        R.drawable.ic_material_file,  // R.string.option_send_files
                        R.drawable.ic_material_camera, // R.string.action_members
                        R.drawable.ic_material_videocam, // R.string.action_members
                };

                fragment = IconAndTextDialogFragment.newInstance(icons, messages);
                fragment.setOnClickListener(new IconAndTextDialogFragment.OnItemClickListener() {
                    @Override
                    public void onItemClick(IconAndTextDialogFragment dialogFragment, int position) {
                        Integer selectedVal = messages[position];

                        if (selectedVal == R.string.option_send_files) {
                            RoomActivity.this.launchFileSelectionIntent();
                        } else if (selectedVal == R.string.option_take_photo) {
                            RoomActivity.this.launchCamera();
                        } else if (selectedVal == R.string.option_take_video) {
                            RoomActivity.this.launchVideo();
                        }
                    }
                });

                fragment.show(fm, TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG);
            }
        });

        mEditText.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(android.text.Editable s) {
                MXLatestChatMessageCache latestChatMessageCache = RoomActivity.this.mLatestChatMessageCache;

                String textInPlace = latestChatMessageCache.getLatestText(RoomActivity.this, mRoom.getRoomId());

                // check if there is really an update
                // avoid useless updates (initializations..)
                if (!mIgnoreTextUpdate && !textInPlace.equals(mEditText.getText().toString())) {
                    latestChatMessageCache.updateLatestMessage(RoomActivity.this, mRoom.getRoomId(), mEditText.getText().toString());
                    handleTypingNotification(mEditText.getText().length() != 0);
                }

                manageSendMoreButtons();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        mSession = getSession(intent);

        if (mSession == null) {
            Log.e(LOG_TAG, "No MXSession.");
            finish();
            return;
        }

        mMyUserId = mSession.getCredentials().userId;

        CommonActivityUtils.resumeEventStream(this);

        mRoom = mSession.getDataHandler().getRoom(roomId);


        FragmentManager fm = getSupportFragmentManager();
        mConsoleMessageListFragment = (ConsoleMessageListFragment) fm.findFragmentByTag(TAG_FRAGMENT_MATRIX_MESSAGE_LIST);

        if (mConsoleMessageListFragment == null) {
            // this fragment displays messages and handles all message logic
            mConsoleMessageListFragment = ConsoleMessageListFragment.newInstance(mMyUserId, mRoom.getRoomId(), org.matrix.androidsdk.R.layout.fragment_matrix_message_list_fragment);
            fm.beginTransaction().add(R.id.anchor_fragment_messages, mConsoleMessageListFragment, TAG_FRAGMENT_MATRIX_MESSAGE_LIST).commit();
        }

        // set general room information
        setTitle(mRoom.getName(mMyUserId));
        setTopic(mRoom.getTopic());

        mImagePreviewLayout = findViewById(R.id.room_image_preview_layout);
        mImagePreviewView   = (ImageView)findViewById(R.id.room_image_preview);
        mImagePreviewButton = (ImageButton)findViewById(R.id.room_image_preview_cancel_button);

        // the user cancels the image selection
        mImagePreviewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mPendingThumbnailUrl = null;
                mPendingMediaUrl = null;
                mPendingMimeType = null;
                mPendingFilename = null;
                manageSendMoreButtons();
            }
        });

        mLatestChatMessageCache = Matrix.getInstance(this).getDefaultLatestChatMessageCache();
        mMediasCache = Matrix.getInstance(this).getMediasCache();

        // some medias must be sent while opening the chat
        if (intent.hasExtra(HomeActivity.EXTRA_ROOM_INTENT)) {
            final Intent mediaIntent = intent.getParcelableExtra(HomeActivity.EXTRA_ROOM_INTENT);

            // sanity check
            if (null != mediaIntent) {
                mEditText.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sendMediasIntent(mediaIntent);
                    }
                }, 1000);
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);

        if (null != mPendingThumbnailUrl) {
            savedInstanceState.putString(PENDING_THUMBNAIL_URL, mPendingThumbnailUrl);
            Log.d(LOG_TAG, "onSaveInstanceState mPendingThumbnailUrl " + mPendingThumbnailUrl);
        }

        if (null != mPendingMediaUrl) {
            savedInstanceState.putString(PENDING_MEDIA_URL, mPendingMediaUrl);
            Log.d(LOG_TAG, "onSaveInstanceState mPendingMediaUrl " + mPendingMediaUrl);
        }

        if (null != mPendingMimeType) {
            savedInstanceState.putString(PENDING_MIMETYPE, mPendingMimeType);
            Log.d(LOG_TAG, "onSaveInstanceState mPendingMimeType " + mPendingMimeType);
        }

        if (null != mPendingFilename) {
            savedInstanceState.putString(PENDING_FILENAME, mPendingFilename);
            Log.d(LOG_TAG, "onSaveInstanceState mPendingFilename " + mPendingFilename);
        }

        savedInstanceState.putInt(FIRST_VISIBLE_ROW, mConsoleMessageListFragment.mMessageListView.getFirstVisiblePosition());
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (savedInstanceState.containsKey(FIRST_VISIBLE_ROW)) {
            // the scroll will be done in resume.
            // the listView will be refreshed so the offset might be lost.
            mScrollToIndex = savedInstanceState.getInt(FIRST_VISIBLE_ROW);
        }
    }

    /**
     *
     */
    private void manageSendMoreButtons() {
        boolean hasText = mEditText.getText().length() > 0;
        boolean hasPreviewedMedia = (null != mPendingThumbnailUrl);


        if (hasPreviewedMedia) {
            mMediasCache.loadBitmap(mSession.getHomeserverConfig(), mImagePreviewView, mPendingThumbnailUrl, 0, ExifInterface.ORIENTATION_UNDEFINED, mPendingMimeType);
        }

        mImagePreviewLayout.setVisibility(hasPreviewedMedia ? View.VISIBLE : View.GONE);
        mEditText.setVisibility(hasPreviewedMedia ? View.INVISIBLE : View.VISIBLE);

        mSendButton.setVisibility((hasText || hasPreviewedMedia) ? View.VISIBLE : View.INVISIBLE);
        mAttachmentButton.setVisibility((hasText || hasPreviewedMedia) ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public void onDestroy() {

        if (null != mConsoleMessageListFragment) {
            mConsoleMessageListFragment.onDestroy();
        }

        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // warn other member that the typing is ended
        cancelTypingNotification();

        // listen for room name or topic changes
        mRoom.removeEventListener(mEventListener);
    }

    @Override
    public void finish() {
        super.finish();

        // do not reset ViewedRoomTracker in onPause
        // else the messages received while the application is in background
        // are marked as unread in the home/recents activity.
        // Assume that the finish method is the right place to manage it.
        ViewedRoomTracker.getInstance().setViewedRoomId(null);
        ViewedRoomTracker.getInstance().setMatrixId(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // listen for room name or topic changes
        mRoom.addEventListener(mEventListener);

        ViewedRoomTracker.getInstance().setViewedRoomId(mRoom.getRoomId());
        ViewedRoomTracker.getInstance().setMatrixId(mSession.getCredentials().userId);
        EventStreamService.cancelNotificationsForRoomId(mRoom.getRoomId());

        // reset the unread messages counter
        mRoom.sendReadReceipt();

        String cachedText = Matrix.getInstance(this).getDefaultLatestChatMessageCache().getLatestText(this, mRoom.getRoomId());

        if (!cachedText.equals(mEditText.getText().toString())) {
            mIgnoreTextUpdate = true;
            mEditText.setText("");
            mEditText.append(cachedText);
            mIgnoreTextUpdate = false;
        }

        manageSendMoreButtons();

        updateMenuEntries();

        // refresh the UI : the timezone could have been updated
        mConsoleMessageListFragment.refresh();

        // the device has been rotated
        // so try to keep the same top/left item;
        if (mScrollToIndex > 0) {
            mConsoleMessageListFragment.mMessageListView.post(new Runnable() {
                @Override
                public void run() {
                    mConsoleMessageListFragment.mMessageListView.setSelection(mScrollToIndex);
                    mScrollToIndex = -1;
                }
            });
        }

        if (null != mCallId) {
            IMXCall call = CallViewActivity.getActiveCall();

            // can only manage one call instance.
            // either there is no active call or resume the active one
            if ((null == call) || call.getCallId().equals(mCallId)) {
                final Intent intent = new Intent(RoomActivity.this, CallViewActivity.class);
                intent.putExtra(CallViewActivity.EXTRA_MATRIX_ID, mSession.getCredentials().userId);
                intent.putExtra(CallViewActivity.EXTRA_CALL_ID, mCallId);

                if (null == call) {
                    intent.putExtra(CallViewActivity.EXTRA_AUTO_ACCEPT, "anything");
                }

                RoomActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            RoomActivity.this.startActivity(intent);
                        }
                    });
            }

            mCallId = null;
        }

        // set general room information
        setTitle(mRoom.getName(mMyUserId));
        setTopic(mRoom.getTopic());
        updateMenuEntries();
    }

    /**
     * Refresh the calls buttons
     */
    private void updateMenuEntries() {
        Boolean visible = mRoom.canPerformCall() && mSession.isVoipCallSupported() && (null == CallViewActivity.getActiveCall());

        if (null != mVoiceMenuItem) {
            mVoiceMenuItem.setVisible(visible);
        }

        if (null != mVideoMenuItem) {
            mVideoMenuItem.setVisible(visible);
        }

        Boolean isPushDownloaded = (null != mSession.getDataHandler().pushRules());

        if (isPushDownloaded) {
            // search if there is a rule for this room
            List<BingRule> roomsRulesList = mSession.getDataHandler().pushRules().getRoomRules();

            if (null != roomsRulesList) {
                for (BingRule rule : roomsRulesList) {
                    if (TextUtils.equals(rule.ruleId, mRoom.getRoomId())) {
                        mBingRule = rule;
                    }
                }
            }
        }

        boolean hasActiveRule = (null == mBingRule) || (mBingRule.isEnabled && mBingRule.shouldNotify());

        if (null != mEnableNotifItem) {
            mEnableNotifItem.setVisible(!hasActiveRule && !mRuleInProgress && isPushDownloaded);
        }

        if (null != mDisableNotifItem) {
            mDisableNotifItem.setVisible(hasActiveRule && !mRuleInProgress && isPushDownloaded);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.room, menu);

        mVoiceMenuItem = menu.findItem(R.id.ic_action_voice_call);
        mVideoMenuItem = menu.findItem(R.id.ic_action_video_call);
        mEnableNotifItem =  menu.findItem(R.id.ic_action_enable_notification);
        mDisableNotifItem =  menu.findItem(R.id.ic_action_disable_notification);

        updateMenuEntries();

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        // mBingRulesManager.toggleRule(rule, mOnBingRuleUpdateListener);
        if (!mRuleInProgress && ((id == R.id.ic_action_enable_notification) || (id == R.id.ic_action_disable_notification))) {
            final BingRulesManager bingRulesManager = mSession.getDataHandler().getBingRulesManager();
            final Boolean shouldNotify = (id == R.id.ic_action_enable_notification);

            mRuleInProgress = true;

            // if there is no dedicated rule -> add a new one
            if (null == mBingRule) {
                bingRulesManager.addRule(new BingRule(BingRule.KIND_ROOM, mRoom.getRoomId(), shouldNotify, shouldNotify, shouldNotify), new BingRulesManager.onBingRuleUpdateListener() {
                    @Override
                    public void onBingRuleUpdateSuccess() {
                        mRuleInProgress = false;
                        RoomActivity.this.runOnUiThread(new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                updateMenuEntries();
                                                            }
                                                        }
                        );
                    }

                    @Override
                    public void onBingRuleUpdateFailure(String errorMessage) {
                        mRuleInProgress = false;
                    }
                });

            } else {
                // replace the existing one
                bingRulesManager.deleteRule(mBingRule, new BingRulesManager.onBingRuleUpdateListener() {
                    @Override
                    public void onBingRuleUpdateSuccess() {
                        bingRulesManager.addRule(new BingRule(BingRule.KIND_ROOM, mRoom.getRoomId(), shouldNotify, shouldNotify, shouldNotify), new BingRulesManager.onBingRuleUpdateListener() {
                            @Override
                            public void onBingRuleUpdateSuccess() {
                                mRuleInProgress = false;
                                RoomActivity.this.runOnUiThread(new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        updateMenuEntries();
                                                                    }
                                                                }
                                );
                            }

                            @Override
                            public void onBingRuleUpdateFailure(String errorMessage) {
                                mRuleInProgress = false;
                            }
                        });
                    }

                    @Override
                    public void onBingRuleUpdateFailure(String errorMessage) {
                        mRuleInProgress = false;
                    }
                });
            }
        } else if ((id == R.id.ic_action_voice_call) || (id == R.id.ic_action_video_call)) {
            // create the call object
            IMXCall call = mSession.mCallsManager.createCallInRoom(mRoom.getRoomId());

            if (null != call) {
                call.setIsVideo((id != R.id.ic_action_voice_call));
                call.setRoom(mRoom);
                call.setIsIncoming(false);

                final Intent intent = new Intent(RoomActivity.this, CallViewActivity.class);

                intent.putExtra(CallViewActivity.EXTRA_MATRIX_ID, mSession.getCredentials().userId);
                intent.putExtra(CallViewActivity.EXTRA_CALL_ID, call.getCallId());

                RoomActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        RoomActivity.this.startActivity(intent);
                    }
                });
            }
        } else if (id == R.id.ic_action_invite_by_list) {
            FragmentManager fm = getSupportFragmentManager();

            MembersInvitationDialogFragment fragment = (MembersInvitationDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG);
            if (fragment != null) {
                fragment.dismissAllowingStateLoss();
            }
            fragment = MembersInvitationDialogFragment.newInstance(mSession, mRoom.getRoomId());
            fragment.show(fm, TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG);
        } else if (id == R.id.ic_action_invite_by_name) {
            AlertDialog alert = CommonActivityUtils.createEditTextAlert(RoomActivity.this, RoomActivity.this.getResources().getString(R.string.title_activity_invite_user), RoomActivity.this.getResources().getString(R.string.room_creation_participants_hint), null, new CommonActivityUtils.OnSubmitListener() {
                @Override
                public void onSubmit(final String text) {
                    if (TextUtils.isEmpty(text)) {
                        return;
                    }

                    // get the user suffix
                    String homeServerSuffix = mMyUserId.substring(mMyUserId.indexOf(":"), mMyUserId.length());

                    ArrayList<String> userIDsList = CommonActivityUtils.parseUserIDsList(text, homeServerSuffix);

                    if (userIDsList.size() > 0) {
                        mRoom.invite(userIDsList, new SimpleApiCallback<Void>(RoomActivity.this) {
                            @Override
                            public void onSuccess(Void info) {
                                Toast.makeText(getApplicationContext(), "Sent invite to " + text.trim() + ".", Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }

                @Override
                public void onCancelled() {

                }
            });

            alert.show();
        } else if (id ==  R.id.ic_action_members) {
            FragmentManager fm = getSupportFragmentManager();

            RoomMembersDialogFragment fragment = (RoomMembersDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_MEMBERS_DIALOG);
            if (fragment != null) {
                fragment.dismissAllowingStateLoss();
            }
            fragment = RoomMembersDialogFragment.newInstance(mSession, mRoom.getRoomId());
            fragment.show(fm, TAG_FRAGMENT_MEMBERS_DIALOG);
        } else if (id ==  R.id.ic_action_room_info) {

            FragmentManager fm = getSupportFragmentManager();

            RoomInfoUpdateDialogFragment roomInfoFragment = (RoomInfoUpdateDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ROOM_INFO);
            if (roomInfoFragment != null) {
                roomInfoFragment.dismissAllowingStateLoss();
            }

            roomInfoFragment = RoomInfoUpdateDialogFragment.newInstance(mMyUserId, mRoom.getRoomId());
            roomInfoFragment.show(fm, TAG_FRAGMENT_ROOM_INFO);

        } else if (id ==  R.id.ic_action_leave) {
            mRoom.leave(new SimpleApiCallback<Void>(RoomActivity.this) {
            });
            RoomActivity.this.finish();
        } else if (id == R.id.ic_action_settings) {
            RoomActivity.this.startActivity(new Intent(RoomActivity.this, SettingsActivity.class));
        } else if (id ==  R.id.ic_send_bug_report) {
            RageShake.getInstance().sendBugReport();
        }

        return super.onOptionsItemSelected(item);
    }

    private void setTopic(String topic) {
        if (null != this.getSupportActionBar()) {
            this.getSupportActionBar().setSubtitle(topic);
        }
    }

    /**
     * check if the text message is an IRC command.
     * If it is an IRC command, it is executed
     * @param body
     * @return true if body defines an IRC command
     */
    private boolean manageIRCCommand(String body) {
        boolean isIRCCmd = false;

        // check if it has the IRC marker
        if ((null != body) && (body.startsWith("/"))) {
            final ApiCallback callback = new SimpleApiCallback<Void>(this) {
                @Override
                public void onMatrixError(MatrixError e) {
                    if (MatrixError.FORBIDDEN.equals(e.errcode)) {
                        Toast.makeText(RoomActivity.this, e.error, Toast.LENGTH_LONG).show();
                    }
                }
            };

            if (body.startsWith(CMD_CHANGE_DISPLAY_NAME)) {
                isIRCCmd = true;

                String newDisplayname = body.substring(CMD_CHANGE_DISPLAY_NAME.length()).trim();

                if (newDisplayname.length() > 0) {
                    MyUser myUser = mSession.getMyUser();

                    myUser.updateDisplayName(newDisplayname, callback);
                }
            } else if (body.startsWith(CMD_EMOTE)) {
                isIRCCmd = true;

                String message = body.substring(CMD_EMOTE.length()).trim();

                if (message.length() > 0) {
                    mConsoleMessageListFragment.sendEmote(message);
                }
            } else if (body.startsWith(CMD_JOIN_ROOM)) {
                isIRCCmd = true;

                String roomAlias = body.substring(CMD_JOIN_ROOM.length()).trim();

                if (roomAlias.length() > 0) {
                    mSession.joinRoom(roomAlias,new SimpleApiCallback<String>(this) {

                        @Override
                        public void onSuccess(String roomId) {
                            if (null != roomId) {
                                CommonActivityUtils.goToRoomPage(mSession, roomId, RoomActivity.this, null);
                            }
                        }
                    });
                }
            } else if (body.startsWith(CMD_KICK_USER)) {
                isIRCCmd = true;

                String params = body.substring(CMD_KICK_USER.length()).trim();
                String[] paramsList = params.split(" ");

                String kickedUserID = paramsList[0];

                if (kickedUserID.length() > 0) {
                    mRoom.kick(kickedUserID, callback);
                }
            } else if (body.startsWith(CMD_BAN_USER)) {
                isIRCCmd = true;

                String params = body.substring(CMD_BAN_USER.length()).trim();
                String[] paramsList = params.split(" ");

                String bannedUserID = paramsList[0];
                String reason = params.substring(bannedUserID.length()).trim();

                if (bannedUserID.length() > 0) {
                    mRoom.ban(bannedUserID, reason, callback);
                }
            } else if (body.startsWith(CMD_UNBAN_USER)) {
                isIRCCmd = true;

                String params = body.substring(CMD_UNBAN_USER.length()).trim();
                String[] paramsList = params.split(" ");

                String unbannedUserID = paramsList[0];

                if (unbannedUserID.length() > 0) {
                    mRoom.unban(unbannedUserID, callback);
                }
            } else if (body.startsWith(CMD_SET_USER_POWER_LEVEL)) {
                isIRCCmd = true;

                String params = body.substring(CMD_SET_USER_POWER_LEVEL.length()).trim();
                String[] paramsList = params.split(" ");

                String userID = paramsList[0];
                String powerLevelsAsString  = params.substring(userID.length()).trim();

                try {
                    if ((userID.length() > 0) && (powerLevelsAsString.length() > 0)) {
                        mRoom.updateUserPowerLevels(userID, Integer.parseInt(powerLevelsAsString), callback);
                    }
                } catch(Exception e){
                    Log.e(LOG_TAG, "mRoom.updateUserPowerLevels " + e.getMessage());
                }
            } else if (body.startsWith(CMD_RESET_USER_POWER_LEVEL)) {
                isIRCCmd = true;

                String params = body.substring(CMD_RESET_USER_POWER_LEVEL.length()).trim();
                String[] paramsList = params.split(" ");

                String userID = paramsList[0];

                if (userID.length() > 0) {
                    mRoom.updateUserPowerLevels(userID, 0, callback);
                }
            }
        }

        return isIRCCmd;
    }

    private void sendMessage(String body) {
        if (!TextUtils.isEmpty(body)) {
            if (!manageIRCCommand(body)) {
                mConsoleMessageListFragment.sendTextMessage(body);
            }
        }
    }

    /**
     * Send a list of images from their URIs
     * @param mediaUris the media URIs
     */
    private void sendMedias(final ArrayList<Uri> mediaUris) {

        final View progressBackground =  findViewById(R.id.medias_processing_progress_background);
        final View progress = findViewById(R.id.medias_processing_progress);

        progressBackground.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);

        final HandlerThread handlerThread = new HandlerThread("MediasEncodingThread");
        handlerThread.start();

        final android.os.Handler handler = new android.os.Handler(handlerThread.getLooper());

        Runnable r = new Runnable() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    public void run() {
                        final int mediaCount = mediaUris.size();

                        for (Uri anUri : mediaUris) {
                            // crash from Google Analytics : null URI on a nexus 5
                            if (null != anUri) {
                                final Uri mediaUri = anUri;
                                String filename = null;

                                if (mediaUri.toString().startsWith("content://")) {
                                    Cursor cursor = null;
                                    try {
                                        cursor = RoomActivity.this.getContentResolver().query(mediaUri, null, null, null, null);
                                        if (cursor != null && cursor.moveToFirst()) {
                                            filename = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                                        }
                                    } catch (Exception e) {
                                        Log.e(LOG_TAG, "cursor.getString " + e.getMessage());
                                    } finally {
                                        if (null != cursor) {
                                            cursor.close();
                                        }
                                    }

                                    if (TextUtils.isEmpty(filename)) {
                                        List uriPath = mediaUri.getPathSegments();
                                        filename = (String) uriPath.get(uriPath.size() - 1);
                                    }
                                } else if (mediaUri.toString().startsWith("file://")) {
                                    // try to retrieve the filename from the file url.
                                    try {
                                        filename = anUri.getLastPathSegment();
                                    } catch (Exception e) {
                                    }

                                    if (TextUtils.isEmpty(filename)) {
                                        filename = null;
                                    }
                                }

                                final String fFilename = filename;

                                ResourceUtils.Resource resource = ResourceUtils.openResource(RoomActivity.this, mediaUri);

                                if (null == resource) {
                                    RoomActivity.this.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            handlerThread.quit();
                                            progressBackground.setVisibility(View.GONE);
                                            progress.setVisibility(View.GONE);

                                            Toast.makeText(RoomActivity.this,
                                                    getString(R.string.message_failed_to_upload),
                                                    Toast.LENGTH_LONG).show();
                                        }

                                        ;
                                    });

                                    return;
                                }

                                // save the file in the filesystem
                                String mediaUrl = mMediasCache.saveMedia(resource.contentStream, null, resource.mimeType);
                                String mimeType = resource.mimeType;
                                Boolean isManaged = false;

                                if ((null != resource.mimeType) && resource.mimeType.startsWith("image/")) {
                                    // manage except if there is an error
                                    isManaged = true;

                                    // try to retrieve the gallery thumbnail
                                    // if the image comes from the gallery..
                                    Bitmap thumbnailBitmap = null;

                                    try {
                                        ContentResolver resolver = getContentResolver();

                                        List uriPath = mediaUri.getPathSegments();
                                        long imageId = -1;
                                        String lastSegment = (String) uriPath.get(uriPath.size() - 1);

                                        // > Kitkat
                                        if (lastSegment.startsWith("image:")) {
                                            lastSegment = lastSegment.substring("image:".length());
                                        }

                                        imageId = Long.parseLong(lastSegment);

                                        thumbnailBitmap = MediaStore.Images.Thumbnails.getThumbnail(resolver, imageId, MediaStore.Images.Thumbnails.MINI_KIND, null);
                                    } catch (Exception e) {
                                        Log.e(LOG_TAG, "MediaStore.Images.Thumbnails.getThumbnail " + e.getMessage());
                                    }

                                    double thumbnailWidth = mConsoleMessageListFragment.getMaxThumbnailWith();
                                    double thumbnailHeight = mConsoleMessageListFragment.getMaxThumbnailHeight();

                                    // no thumbnail has been found or the mimetype is unknown
                                    if ((null == thumbnailBitmap) || (thumbnailBitmap.getHeight() > thumbnailHeight) || (thumbnailBitmap.getWidth() > thumbnailWidth)) {
                                        // need to decompress the high res image
                                        BitmapFactory.Options options = new BitmapFactory.Options();
                                        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                                        resource = ResourceUtils.openResource(RoomActivity.this, mediaUri);

                                        // get the full size bitmap
                                        Bitmap fullSizeBitmap = null;

                                        if (null == thumbnailBitmap) {
                                            fullSizeBitmap = BitmapFactory.decodeStream(resource.contentStream, null, options);
                                        }

                                        if ((fullSizeBitmap != null) || (thumbnailBitmap != null)) {
                                            double imageWidth;
                                            double imageHeight;

                                            if (null == thumbnailBitmap) {
                                                imageWidth = fullSizeBitmap.getWidth();
                                                imageHeight = fullSizeBitmap.getHeight();
                                            } else {
                                                imageWidth = thumbnailBitmap.getWidth();
                                                imageHeight = thumbnailBitmap.getHeight();
                                            }

                                            if (imageWidth > imageHeight) {
                                                thumbnailHeight = thumbnailWidth * imageHeight / imageWidth;
                                            } else {
                                                thumbnailWidth = thumbnailHeight * imageWidth / imageHeight;
                                            }

                                            try {
                                                thumbnailBitmap = Bitmap.createScaledBitmap((null == fullSizeBitmap) ? thumbnailBitmap : fullSizeBitmap, (int) thumbnailWidth, (int) thumbnailHeight, false);
                                            } catch (OutOfMemoryError ex) {
                                                Log.e(LOG_TAG, "Bitmap.createScaledBitmap " + ex.getMessage());
                                            }
                                        }

                                        // the valid mimetype is not provided
                                        if ("image/*".equals(mimeType)) {
                                            // make a jpg snapshot.
                                            mimeType = null;
                                        }

                                        // unknown mimetype
                                        if ((null == mimeType) || (mimeType.startsWith("image/"))) {
                                            try {
                                                // try again
                                                if (null == fullSizeBitmap) {
                                                    System.gc();
                                                    fullSizeBitmap = BitmapFactory.decodeStream(resource.contentStream, null, options);
                                                }

                                                if (null != fullSizeBitmap) {
                                                    Uri uri = Uri.parse(mediaUrl);

                                                    if (null == mimeType) {
                                                        // the images are save in jpeg format
                                                        mimeType = "image/jpeg";
                                                    }

                                                    resource.contentStream.close();
                                                    resource = ResourceUtils.openResource(RoomActivity.this, mediaUri);

                                                    try {
                                                        mMediasCache.saveMedia(resource.contentStream, uri.getPath(), mimeType);
                                                    } catch (OutOfMemoryError ex) {
                                                        Log.e(LOG_TAG, "mMediasCache.saveMedia" + ex.getMessage());
                                                    }

                                                } else {
                                                    isManaged = false;
                                                }

                                                resource.contentStream.close();

                                            } catch (Exception e) {
                                                isManaged = false;
                                                Log.e(LOG_TAG, "sendMedias " + e.getMessage());
                                            }
                                        }

                                        // reduce the memory consumption
                                        if (null != fullSizeBitmap) {
                                            fullSizeBitmap.recycle();
                                            System.gc();
                                        }
                                    }

                                    String thumbnailURL = mMediasCache.saveBitmap(thumbnailBitmap, null);

                                    if (null != thumbnailBitmap) {
                                        thumbnailBitmap.recycle();
                                    }

                                    //
                                    if (("image/jpg".equals(mimeType) || "image/jpeg".equals(mimeType)) && (null != mediaUrl)) {

                                        Uri imageUri = Uri.parse(mediaUrl);
                                        // get the exif rotation angle
                                        final int rotationAngle = ImageUtils.getRotationAngleForBitmap(RoomActivity.this, imageUri);

                                        if (0 != rotationAngle) {
                                            // always apply the rotation to the image
                                            ImageUtils.rotateImage(RoomActivity.this, thumbnailURL, rotationAngle, mMediasCache);

                                            // the high res media orientation should be not be done on uploading
                                            //ImageUtils.rotateImage(RoomActivity.this, mediaUrl, rotationAngle, mMediasCache))
                                        }
                                    }

                                    // is the image content valid ?
                                    if (isManaged && (null != thumbnailURL)) {

                                        final String fThumbnailURL = thumbnailURL;
                                        final String fMediaUrl = mediaUrl;
                                        final String fMimeType = mimeType;

                                        RoomActivity.this.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                // if there is only one image
                                                if (mediaCount == 1) {
                                                    // display an image preview before sending it
                                                    mPendingThumbnailUrl = fThumbnailURL;
                                                    mPendingMediaUrl = fMediaUrl;
                                                    mPendingMimeType = fMimeType;
                                                    mPendingFilename = fFilename;

                                                    mConsoleMessageListFragment.scrollToBottom();

                                                    manageSendMoreButtons();
                                                } else {
                                                    mConsoleMessageListFragment.uploadImageContent(fThumbnailURL, fMediaUrl, fFilename, fMimeType);
                                                }
                                            }
                                        });
                                    }
                                }

                                // default behaviour
                                if ((!isManaged) && (null != mediaUrl)) {
                                    final String fMediaUrl = mediaUrl;
                                    final String fMimeType = mimeType;

                                    RoomActivity.this.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if ((null != fMimeType) && fMimeType.startsWith("video/")) {
                                                mConsoleMessageListFragment.uploadVideoContent(fMediaUrl, null, fMimeType);
                                            } else {
                                                mConsoleMessageListFragment.uploadFileContent(fMediaUrl, fMimeType, fFilename);
                                            }
                                        }
                                    });
                                }
                            }
                        }

                        RoomActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                handlerThread.quit();
                                progressBackground.setVisibility(View.GONE);
                                progress.setVisibility(View.GONE);
                            };
                        });
                    }
                });
            }
        };

        Thread t = new Thread(r);
        t.start();
    }

    @SuppressLint("NewApi")
    private void sendMediasIntent(final Intent data) {
        // sanity check
        if ((null == data) && (null == mLatestTakePictureCameraUri)) {
            return;
        }

        ArrayList<Uri> uris = new ArrayList<Uri>();

        if (null != data) {
            ClipData clipData = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                clipData = data.getClipData();
            }

            // multiple data
            if (null != clipData) {
                int count = clipData.getItemCount();

                for (int i = 0; i < count; i++) {
                    ClipData.Item item = clipData.getItemAt(i);
                    Uri uri = item.getUri();

                    if (null != uri) {
                        uris.add(uri);
                    }
                }

            } else if (null != data.getData()) {
                uris.add(data.getData());
            }
        } else if (null != mLatestTakePictureCameraUri) {
            uris.add(Uri.parse(mLatestTakePictureCameraUri));
            mLatestTakePictureCameraUri = null;
        }

        // check the extras
        if (0 == uris.size()) {
            Bundle bundle = data.getExtras();

            // sanity checks
            if (null != bundle) {
                if (bundle.containsKey(Intent.EXTRA_STREAM)) {
                    Object streamUri = bundle.get(Intent.EXTRA_STREAM);

                    if (streamUri instanceof Uri) {
                        uris.add((Uri) streamUri);
                    }
                } else if (bundle.containsKey(Intent.EXTRA_TEXT)) {
                    this.sendMessage(bundle.getString(Intent.EXTRA_TEXT));
                }
            } else {
                uris.add( mLatestTakePictureCameraUri == null ? null : Uri.parse(mLatestTakePictureCameraUri));
                mLatestTakePictureCameraUri = null;
            }
        }

        if (0 != uris.size()) {
            sendMedias(uris);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(LOG_TAG, "onActivityResult requestCode " + resultCode + " for " + requestCode + " with " + data);

        if (requestCode == TAKE_IMAGE) {
            Log.d(LOG_TAG, "onActivityResult mLatestTakePictureCameraUri " + mLatestTakePictureCameraUri);
        }

        if (resultCode == RESULT_OK) {
            if ((requestCode == REQUEST_FILES) || (requestCode == TAKE_IMAGE) || (requestCode == TAKE_VIDEO)) {
                sendMediasIntent(data);
            } else if (requestCode == CREATE_DOCUMENT) {
                Uri currentUri = data.getData();
                writeMediaUrl(currentUri);
            }
        } else {
            Log.e(LOG_TAG, "onActivityResult fails " + resultCode + " for " + requestCode + " with " + data);
        }

        if (requestCode == CREATE_DOCUMENT) {
            mPendingMediaUrl = null;
            mPendingMimeType = null;
        }
    }

    /**
     *
     * @param message
     * @param mediaUrl
     * @param mediaMimeType
     */
    public void createDocument(Message message, final String mediaUrl, final String mediaMimeType) {
        String filename = "MatrixConsole_" + System.currentTimeMillis();

        MimeTypeMap mime = MimeTypeMap.getSingleton();
        filename += "." + mime.getExtensionFromMimeType(mediaMimeType);

        if (message instanceof FileMessage) {
            FileMessage fileMessage = (FileMessage)message;

            if (null != fileMessage.body) {
                filename = fileMessage.body;
            }
        }

        mPendingMediaUrl = mediaUrl;
        mPendingMimeType = mediaMimeType;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mediaMimeType)
                .putExtra(Intent.EXTRA_TITLE, filename);

        startActivityForResult(intent, CREATE_DOCUMENT);

    }

	private void writeMediaUrl(Uri destUri)
	{
		try{
			ParcelFileDescriptor pfd =
				this.getContentResolver().
                		openFileDescriptor(destUri, "w");

			FileOutputStream fileOutputStream =
                           new FileOutputStream(pfd.getFileDescriptor());

            File sourceFile = mMediasCache.mediaCacheFile(mPendingMediaUrl, mPendingMimeType);

            FileInputStream inputStream = new FileInputStream(sourceFile);

            byte[] buffer = new byte[1024 * 10];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, len);
            }

			fileOutputStream.close();
			pfd.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

    /**
     * send a typing event notification
     * @param isTyping typing param
     */
    void handleTypingNotification(boolean isTyping) {
        int notificationTimeoutMS = -1;
        if (isTyping) {
            // Check whether a typing event has been already reported to server (We wait for the end of the local timout before considering this new event)
            if (null != mTypingTimer) {
                // Refresh date of the last observed typing
                System.currentTimeMillis();
                mLastTypingDate = System.currentTimeMillis();
                return;
            }

            int timerTimeoutInMs = TYPING_TIMEOUT_MS;

            if (0 != mLastTypingDate) {
                long lastTypingAge = System.currentTimeMillis() - mLastTypingDate;
                if (lastTypingAge < timerTimeoutInMs) {
                    // Subtract the time interval since last typing from the timer timeout
                    timerTimeoutInMs -= lastTypingAge;
                } else {
                    timerTimeoutInMs = 0;
                }
            } else {
                // Keep date of this typing event
                mLastTypingDate = System.currentTimeMillis();
            }

            if (timerTimeoutInMs > 0) {
                mTypingTimer = new Timer();
                mTypingTimerTask = new TimerTask() {
                    public void run() {
                        if (mTypingTimerTask != null) {
                            mTypingTimerTask.cancel();
                            mTypingTimerTask = null;
                        }

                        if (mTypingTimer != null) {
                            mTypingTimer.cancel();
                            mTypingTimer = null;
                        }
                        // Post a new typing notification
                        RoomActivity.this.handleTypingNotification(0 != mLastTypingDate);
                    }
                };
                mTypingTimer.schedule(mTypingTimerTask, TYPING_TIMEOUT_MS);

                // Compute the notification timeout in ms (consider the double of the local typing timeout)
                notificationTimeoutMS = TYPING_TIMEOUT_MS * 2;
            } else {
                // This typing event is too old, we will ignore it
                isTyping = false;
            }
        }
        else {
            // Cancel any typing timer
            if (mTypingTimerTask != null) {
                mTypingTimerTask.cancel();
                mTypingTimerTask = null;
            }

            if (mTypingTimer != null) {
                mTypingTimer.cancel();
                mTypingTimer = null;
            }
            // Reset last typing date
            mLastTypingDate = 0;
        }

        final boolean typingStatus = isTyping;

        mRoom.sendTypingNotification(typingStatus, notificationTimeoutMS, new SimpleApiCallback<Void>(RoomActivity.this) {
            @Override
            public void onSuccess(Void info) {
                // Reset last typing date
                mLastTypingDate = 0;
            }

            @Override
            public void onNetworkError(Exception e) {
                if (mTypingTimerTask != null) {
                    mTypingTimerTask.cancel();
                    mTypingTimerTask = null;
                }

                if (mTypingTimer != null) {
                    mTypingTimer.cancel();
                    mTypingTimer = null;
                }
                // do not send again
                // assume that the typing event is optional
            }
        });
    }

    void cancelTypingNotification() {
        if (0 != mLastTypingDate) {
            if (mTypingTimerTask != null) {
                mTypingTimerTask.cancel();
                mTypingTimerTask = null;
            }
            if (mTypingTimer != null) {
                mTypingTimer.cancel();
                mTypingTimer = null;
            }

            mLastTypingDate = 0;

            mRoom.sendTypingNotification(false, -1, new SimpleApiCallback<Void>(RoomActivity.this) {
            });
        }
    }
}
