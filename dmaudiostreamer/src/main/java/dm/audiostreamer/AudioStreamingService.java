/*
 * This is the source code of DMAudioStreaming for Android v. 1.0.0.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Copyright @Dibakar_Mistry(dibakar.ece@gmail.com), 2017.
 */
package dm.audiostreamer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.NotificationTarget;
import com.bumptech.glide.request.target.Target;

public class AudioStreamingService extends Service implements NotificationManager.NotificationCenterDelegate {
    private static final String TAG = Logger.makeLogTag(AudioStreamingService.class);

    public static final String EXTRA_CONNECTED_CAST = "dm.audiostreaming.CAST_NAME";
    public static final String ACTION_CMD = "dm.audiostreaming.ACTION_CMD";
    public static final String CMD_NAME = "CMD_NAME";
    public static final String CMD_PAUSE = "CMD_PAUSE";
    public static final String CMD_STOP_CASTING = "CMD_STOP_CASTING";
    private static final int STOP_DELAY = 30000;

    public static final String NOTIFY_PREVIOUS = "dm.audiostreamer.previous";
    public static final String NOTIFY_CLOSE = "dm.audiostreamer.close";
    public static final String NOTIFY_PAUSE = "dm.audiostreamer.pause";
    public static final String NOTIFY_PLAY = "dm.audiostreamer.play";
    public static final String NOTIFY_NEXT = "dm.audiostreamer.next";

    private static boolean supportBigNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
    private static boolean supportLockScreenControls = Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
    private RemoteControlClient remoteControlClient;
    private AudioManager audioManager;
    private AudioStreamingManager audioStreamingManager;
    private PhoneStateListener phoneStateListener;
    public PendingIntent pendingIntent;

    private static final int NOTIFICATION_ID = 523;
    private static final String CHANNEL_ID = "GRAMOPHONE_CHANNEL";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        System.out.println("****** onCreate is called.");
        registerReceivers();
        audioStreamingManager = AudioStreamingManager.getInstance(AudioStreamingService.this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager.getInstance().addObserver(this, NotificationManager.audioProgressDidChanged);
        NotificationManager.getInstance().addObserver(this, NotificationManager.setAnyPendingIntent);
        NotificationManager.getInstance().addObserver(this, NotificationManager.audioPlayStateChanged);
        try {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    System.out.println("****** onCallStateChanged is called.");
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        if (audioStreamingManager.isPlaying()) {
                            audioStreamingManager.handlePauseRequest();
                        }
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) {

                    } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {

                    }
                    super.onCallStateChanged(state, incomingNumber);
                }
            };
            TelephonyManager mgr = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (mgr != null) {
                mgr.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
        } catch (Exception e) {
            Log.e("tmessages", e.toString());
        }
        super.onCreate();
    }

    AudioStreamingReceiver audioStreamingReceiver;

    private void registerReceivers() {
        System.out.println("****** registerReceivers is called.");
        audioStreamingReceiver = new AudioStreamingReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("dm.audiostreamer.close");
        filter.addAction("dm.audiostreamer.pause");
        filter.addAction("dm.audiostreamer.next");
        filter.addAction("dm.audiostreamer.play");
        filter.addAction("dm.audiostreamer.previous");
        filter.addAction("android.intent.action.MEDIA_BUTTON");
        filter.addAction("android.media.AUDIO_BECOMING_NOISY");
        registerReceiver(audioStreamingReceiver, filter);
    }

    private void unregisterReceivers() {
        System.out.println("****** unregisterReceivers is called.");
        if (audioStreamingReceiver != null)
            unregisterReceiver(audioStreamingReceiver);
    }

    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        System.out.println("****** onStartCommand is called.");
        NotificationManager.notifCreated = 0;
        try {
            MediaMetaData messageObject = AudioStreamingManager.getInstance(AudioStreamingService.this).getCurrentAudio();
            if (messageObject == null) {
                Handler handler = new Handler(AudioStreamingService.this.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        stopSelf();
                    }
                });
                return START_STICKY;
            }

            if (supportLockScreenControls) {
                ComponentName remoteComponentName = new ComponentName(getApplicationContext(), AudioStreamingReceiver.class.getName());
                try {
                    if (remoteControlClient == null) {
                        audioManager.registerMediaButtonEventReceiver(remoteComponentName);
                        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                        mediaButtonIntent.setComponent(remoteComponentName);
                        PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
                        remoteControlClient = new RemoteControlClient(mediaPendingIntent);
                        audioManager.registerRemoteControlClient(remoteControlClient);
                    }
                    remoteControlClient.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                            | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE | RemoteControlClient.FLAG_KEY_MEDIA_STOP
                            | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS | RemoteControlClient.FLAG_KEY_MEDIA_NEXT);
                } catch (Exception e) {
                    Log.e("tmessages", e.toString());
                }
            }
            createNotification(messageObject);
        } catch (Exception e) {

        }
        if (startIntent != null) {
            String action = startIntent.getAction();
            String command = startIntent.getStringExtra(CMD_NAME);
            if (ACTION_CMD.equals(action)) {
                if (CMD_PAUSE.equals(command)) {
                    audioStreamingManager.handlePauseRequest();
                } else if (CMD_STOP_CASTING.equals(command)) {
                    //TODO FOR EXTERNAL DEVICE
                }
            }
        }
        return START_NOT_STICKY;
    }

    Bitmap albumArt = null;
    Notification notification = null;

    private void createNotification(MediaMetaData mSongDetail) {
        System.out.println("****** createNotification is called.");
        try {
            String songName = mSongDetail.getMediaTitle();
            String authorName = mSongDetail.getMediaArtist();
            String albumName = mSongDetail.getMediaAlbum();
            MediaMetaData audioInfo = AudioStreamingManager.getInstance(AudioStreamingService.this).getCurrentAudio();

            RemoteViews simpleContentView = new RemoteViews(getApplicationContext().getPackageName(), R.layout.player_small_notification);
            RemoteViews expandedView = null;
            if (supportBigNotifications) {
                expandedView = new RemoteViews(getApplicationContext().getPackageName(), R.layout.player_big_notification);
            }

            CharSequence name = getString(R.string.app_name);
            boolean androidOPlus = false;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int importance = android.app.NotificationManager.IMPORTANCE_LOW;
                NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, name, importance);
                android.app.NotificationManager mNotificationManager =
                        (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                mChannel.setSound(null, null);
                if (mNotificationManager != null) {
                    mNotificationManager.createNotificationChannel(mChannel);
                }
                androidOPlus = true;
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID).setSmallIcon(R.drawable.player)
                    .setContentTitle(songName);
            if (pendingIntent != null)
                builder = builder.setContentIntent(pendingIntent);
            if (androidOPlus)
                builder = builder.setChannelId(CHANNEL_ID);

//            builder.setSound(Uri.EMPTY);
//            System.out.println("****** raw.path1<" + "android.resource://" + getPackageName() + "/raw/silence.mp3" + ">");
//            System.out.println("****** raw.path2<" + ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/" + R.raw.silence + ">");
//            builder.setSound(Uri.parse("android.resource://" + getPackageName() + "/raw/silence"), AudioManager.STREAM_NOTIFICATION);
//            builder.setSound(Uri.parse("android.resource://" + getPackageName() + "/raw/silence.mp3"), AudioManager.STREAM_NOTIFICATION);
//            builder.setSound(Uri.parse("android.resource://dm.audiostreamerdemo/" + R.raw.silence));
//            builder.setSound(Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/" + R.raw.silence));
//            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            builder.setSound(RingtoneManager.getDefaultUri(R.raw.silence));

            notification = builder.build();

            notification.contentView = simpleContentView;
            if (supportBigNotifications) {
                notification.bigContentView = expandedView;
            }

            setListeners(simpleContentView);
            if (supportBigNotifications) {
                setListeners(expandedView);
            }

//            try {
//                ImageLoader imageLoader = ImageLoader.getInstance();
//                albumArt = imageLoader.loadImageSync(audioInfo.getMediaArt());
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//
//            if (albumArt != null) {
//                notification.contentView.setImageViewBitmap(R.id.player_album_art, albumArt);
//                if (supportBigNotifications) {
//                    notification.bigContentView.setImageViewBitmap(R.id.player_album_art, albumArt);
//                }
//            } else {
//                notification.contentView.setImageViewResource(R.id.player_album_art, R.drawable.bg_default_album_art);
//                if (supportBigNotifications) {
//                    notification.bigContentView.setImageViewResource(R.id.player_album_art, R.drawable.bg_default_album_art);
//                }
//            }


            RequestOptions options = new RequestOptions();
            options.diskCacheStrategy(DiskCacheStrategy.ALL);
            options.centerCrop();
            options.placeholder(R.drawable.bg_default_album_art);


            if (supportBigNotifications) {
                NotificationTarget notificationTargetBig = new NotificationTarget(
                        this,
                        R.id.player_album_art,
                        notification.bigContentView,
                        notification,
                        NOTIFICATION_ID);
                Glide.with(this)
                        .applyDefaultRequestOptions(options)
                        .asBitmap()
                        .load(audioInfo.getMediaArt())
                        .into(notificationTargetBig);
            }
            NotificationTarget notificationTarget = new NotificationTarget(
                    this,
                    R.id.player_album_art,
                    notification.contentView,
                    notification,
                    NOTIFICATION_ID);

            Glide.with(this)
                    .applyDefaultRequestOptions(options)
                    .asBitmap()
                    .load(audioInfo.getMediaArt())
                    .listener(new RequestListener<Bitmap>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                            albumArt = resource;
                            return false;
                        }
                    })
                    .into(notificationTarget);


            notification.contentView.setViewVisibility(R.id.player_progress_bar, View.GONE);
            notification.contentView.setViewVisibility(R.id.player_next, View.VISIBLE);
            notification.contentView.setViewVisibility(R.id.player_previous, View.VISIBLE);
            if (supportBigNotifications) {
                notification.bigContentView.setViewVisibility(R.id.player_next, View.VISIBLE);
                notification.bigContentView.setViewVisibility(R.id.player_previous, View.VISIBLE);
                notification.bigContentView.setViewVisibility(R.id.player_progress_bar, View.GONE);
            }

            if (!AudioStreamingManager.getInstance(AudioStreamingService.this).isPlaying()) {
                notification.contentView.setViewVisibility(R.id.player_pause, View.GONE);
                notification.contentView.setViewVisibility(R.id.player_play, View.VISIBLE);
                if (supportBigNotifications) {
                    notification.bigContentView.setViewVisibility(R.id.player_pause, View.GONE);
                    notification.bigContentView.setViewVisibility(R.id.player_play, View.VISIBLE);
                }
            } else {
                notification.contentView.setViewVisibility(R.id.player_pause, View.VISIBLE);
                notification.contentView.setViewVisibility(R.id.player_play, View.GONE);
                if (supportBigNotifications) {
                    notification.bigContentView.setViewVisibility(R.id.player_pause, View.VISIBLE);
                    notification.bigContentView.setViewVisibility(R.id.player_play, View.GONE);
                }
            }

            notification.contentView.setTextViewText(R.id.player_song_name, songName);
            notification.contentView.setTextViewText(R.id.player_author_name, authorName);
            if (supportBigNotifications) {
                notification.bigContentView.setTextViewText(R.id.player_song_name, songName);
                notification.bigContentView.setTextViewText(R.id.player_author_name, authorName);
//                notification.bigContentView.setTextViewText(R.id.player_albumname, albumName);
            }
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            startForeground(NOTIFICATION_ID, notification);

            if (remoteControlClient != null) {
                RemoteControlClient.MetadataEditor metadataEditor = remoteControlClient.editMetadata(true);
                metadataEditor.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, authorName);
                metadataEditor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, songName);
                if (albumArt != null) {
                    metadataEditor.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, albumArt);
                }
                metadataEditor.apply();
            }
        } catch (
                Exception e) {
            e.printStackTrace();
        }

    }

    public void setListeners(RemoteViews view) {
        try {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_PREVIOUS),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            view.setOnClickPendingIntent(R.id.player_previous, pendingIntent);
            pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_CLOSE), PendingIntent.FLAG_UPDATE_CURRENT);
            view.setOnClickPendingIntent(R.id.player_close, pendingIntent);
            pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT);
            view.setOnClickPendingIntent(R.id.player_pause, pendingIntent);
            pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_NEXT), PendingIntent.FLAG_UPDATE_CURRENT);
            view.setOnClickPendingIntent(R.id.player_next, pendingIntent);
            pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(NOTIFY_PLAY), PendingIntent.FLAG_UPDATE_CURRENT);
            view.setOnClickPendingIntent(R.id.player_play, pendingIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        System.out.println("****** onDestroy is called.");
        super.onDestroy();
        unregisterReceivers();
        if (remoteControlClient != null) {
            RemoteControlClient.MetadataEditor metadataEditor = remoteControlClient.editMetadata(true);
            metadataEditor.clear();
            metadataEditor.apply();
            audioManager.unregisterRemoteControlClient(remoteControlClient);
        }
        try {
            TelephonyManager mgr = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (mgr != null) {
                mgr.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        } catch (Exception e) {
            Log.e("tmessages", e.toString());
        }
        NotificationManager.getInstance().removeObserver(this, NotificationManager.audioProgressDidChanged);
        NotificationManager.getInstance().removeObserver(this, NotificationManager.audioPlayStateChanged);
    }

    @Override
    public void didReceivedNotification(int id, Object... args) {
        System.out.println("****** didReceivedNotification is called: id<" + id + "> NC<" + NotificationManager.notifCreated + ">");
        if (id == NotificationManager.setAnyPendingIntent) {
            PendingIntent pendingIntent = (PendingIntent) args[0];
            if (pendingIntent != null) {
                this.pendingIntent = pendingIntent;
            }
        } else if (id == NotificationManager.audioPlayStateChanged && NotificationManager.notifCreated < 10) {
            NotificationManager.notifCreated++;
//            AudioPlaybackListener.onAudioFocusChange
            MediaMetaData mSongDetail = AudioStreamingManager.getInstance(AudioStreamingService.this).getCurrentAudio();
            if (mSongDetail != null) {
                createNotification(mSongDetail);
            } else {
                stopSelf();
            }
        }
    }

    @Override
    public void newSongLoaded(Object... args) {
    }
}
