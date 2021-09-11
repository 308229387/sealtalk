package io.rong.imkit.manager;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.File;

import io.rong.common.RLog;
import io.rong.imkit.IMCenter;
import io.rong.imkit.R;
import io.rong.imkit.config.RongConfigCenter;
import io.rong.imkit.feature.destruct.DestructManager;
import io.rong.imlib.IRongCallback;
import io.rong.imlib.RongIMClient;
import io.rong.imlib.common.SavePathUtils;
import io.rong.imlib.model.Conversation;
import io.rong.imlib.model.Message;
import io.rong.imlib.typingmessage.TypingMessageManager;
import io.rong.message.HQVoiceMessage;
import io.rong.message.VoiceMessage;


public class AudioRecordManager implements Handler.Callback {
    private final static String TAG = "AudioRecordManager";
    private final int AUDIO_RECORD_EVENT_TRIGGER = 1;
    private final int AUDIO_RECORD_EVENT_SAMPLING = 2;
    private final int AUDIO_RECORD_EVENT_WILL_CANCEL = 3;
    private final int AUDIO_RECORD_EVENT_CONTINUE = 4;
    private final int AUDIO_RECORD_EVENT_RELEASE = 5;
    private final int AUDIO_RECORD_EVENT_ABORT = 6;
    private final int AUDIO_RECORD_EVENT_TIME_OUT = 7;
    private final int AUDIO_RECORD_EVENT_TICKER = 8;
    private final int AUDIO_RECORD_EVENT_SEND_FILE = 9;
    private final int AUDIO_AA_ENCODING_BIT_RATE = 32000;

    private int RECORD_INTERVAL = 60;
    private SamplingRate mSampleRate = SamplingRate.RC_SAMPLE_RATE_8000;
    private IAudioState mCurAudioState;
    private View mRootView;
    private Context mContext;
    private Conversation.ConversationType mConversationType;
    private String mTargetId;

    private Handler mHandler;

    private AudioManager mAudioManager;
    private MediaRecorder mMediaRecorder;
    private Uri mAudioPath;
    private long smStartRecTime;
    private AudioManager.OnAudioFocusChangeListener mAfChangeListener;

    private PopupWindow mRecordWindow;
    private RelativeLayout voiceBackLayout;
    private ImageView mStateIV;
    private ImageView recordBottomImage;
    private ImageView recordCancelImage;
    private TextView mStateTV;
    private TextView mTimerTV;
    private TextView recordCancelText;
    private TextView recordSendText;
    private int originalWidth = 0;

    private static final int RC_SAMPLE_RATE_8000 = 8000;
    private static final int RC_SAMPLE_RATE_16000 = 16000;
    private static final String VOICE_PATH = "/voice/";

    static class SingletonHolder {
        static AudioRecordManager sInstance = new AudioRecordManager();
    }

    public static AudioRecordManager getInstance() {
        return SingletonHolder.sInstance;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private AudioRecordManager() {
        RLog.d(TAG, "AudioRecordManager");
        mHandler = new Handler(Looper.getMainLooper(), this);
        mCurAudioState = idleState;
        idleState.enter();
    }

    IAudioState idleState = new IdleState();

    class IdleState extends IAudioState {
        public IdleState() {
            RLog.d(TAG, "IdleState");
        }

        @Override
        void enter() {
            super.enter();
            if (mHandler != null) {
                mHandler.removeMessages(AUDIO_RECORD_EVENT_TIME_OUT);
                mHandler.removeMessages(AUDIO_RECORD_EVENT_TICKER);
                mHandler.removeMessages(AUDIO_RECORD_EVENT_SAMPLING);
            }
        }

        @Override
        void handleMessage(AudioStateMessage msg) {
            RLog.d(TAG, "IdleState handleMessage : " + msg.what);
            switch (msg.what) {
                case AUDIO_RECORD_EVENT_TRIGGER:
                    initView(mRootView);
                    setRecordingView();
                    startRec();
                    setCallStateChangeListener();
                    smStartRecTime = SystemClock.elapsedRealtime();
                    mCurAudioState = recordState;
                    sendEmptyMessage(AUDIO_RECORD_EVENT_SAMPLING);
                    break;
            }
        }
    }

    IAudioState recordState = new RecordState();

    class RecordState extends IAudioState {
        @Override
        void handleMessage(AudioStateMessage msg) {
            RLog.d(TAG, getClass().getSimpleName() + " handleMessage : " + msg.what);
            switch (msg.what) {
                case AUDIO_RECORD_EVENT_SAMPLING:
                    audioDBChanged();
                    mHandler.sendEmptyMessageDelayed(AUDIO_RECORD_EVENT_SAMPLING, 150);
                    break;
                case AUDIO_RECORD_EVENT_WILL_CANCEL:
                    setCancelView();
                    mCurAudioState = cancelState;
                    break;
                case AUDIO_RECORD_EVENT_RELEASE:
                    final boolean checked = checkAudioTimeLength();
                    boolean activityFinished = false;
                    if (msg.obj != null) {
                        activityFinished = (boolean) msg.obj;
                    }
                    if (checked && !activityFinished) {
                        mStateIV.setImageResource(R.drawable.rc_voice_volume_warning);
                        mStateTV.setText(R.string.rc_voice_short);
                        mHandler.removeMessages(AUDIO_RECORD_EVENT_SAMPLING);
                    }
                    if (!activityFinished && mHandler != null) {
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                AudioStateMessage message = new AudioStateMessage();
                                message.what = AUDIO_RECORD_EVENT_SEND_FILE;
                                message.obj = !checked;
                                sendMessage(message);
                            }
                        }, 500);
                        mCurAudioState = sendingState;
                    } else {
                        stopRec();
                        if (!checked && activityFinished) {
                            sendAudioFile();
                        }
                        destroyView();
                        mCurAudioState = idleState;
                    }
                    break;
                case AUDIO_RECORD_EVENT_TIME_OUT:
                    int counter = (int) msg.obj;
                    setTimeoutView(counter);
                    mCurAudioState = timerState;

                    if (counter >= 0) {
                        android.os.Message message = android.os.Message.obtain();
                        message.what = AUDIO_RECORD_EVENT_TICKER;
                        message.obj = counter - 1;
                        mHandler.sendMessageDelayed(message, 1000);
                    } else {
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                stopRec();
                                sendAudioFile();
                                destroyView();
                            }
                        }, 500);
                        mCurAudioState = idleState;

                    }
                    break;
                case AUDIO_RECORD_EVENT_ABORT:
                    stopRec();
                    destroyView();
                    deleteAudioFile();
                    mCurAudioState = idleState;
                    idleState.enter();
                    break;
            }
        }
    }

    IAudioState sendingState = new SendingState();

    class SendingState extends IAudioState {
        @Override
        void handleMessage(AudioStateMessage message) {
            RLog.d(TAG, "SendingState handleMessage " + message.what);
            switch (message.what) {
                case AUDIO_RECORD_EVENT_SEND_FILE:
                    stopRec();
                    if ((boolean) message.obj) sendAudioFile();
                    destroyView();
                    mCurAudioState = idleState;
                    break;
            }
        }
    }

    IAudioState cancelState = new CancelState();

    class CancelState extends IAudioState {
        @Override
        void handleMessage(AudioStateMessage msg) {
            RLog.d(TAG, getClass().getSimpleName() + " handleMessage : " + msg.what);
            switch (msg.what) {
                case AUDIO_RECORD_EVENT_TRIGGER:
                    break;
                case AUDIO_RECORD_EVENT_CONTINUE:
                    setRecordingView();
                    mCurAudioState = recordState;
                    sendEmptyMessage(AUDIO_RECORD_EVENT_SAMPLING);
                    break;
                case AUDIO_RECORD_EVENT_ABORT:
                case AUDIO_RECORD_EVENT_RELEASE:
                    stopRec();
                    destroyView();
                    deleteAudioFile();
                    mCurAudioState = idleState;
                    idleState.enter();
                    break;
                case AUDIO_RECORD_EVENT_TIME_OUT:
                    final int counter = (int) msg.obj;
                    if (counter > 0) {
                        android.os.Message message = android.os.Message.obtain();
                        message.what = AUDIO_RECORD_EVENT_TICKER;
                        message.obj = counter - 1;
                        mHandler.sendMessageDelayed(message, 1000);
                    } else {
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                stopRec();
                                sendAudioFile();
                                destroyView();
                            }
                        }, 500);
                        mCurAudioState = idleState;
                        idleState.enter();
                    }
                    break;
            }
        }
    }

    IAudioState timerState = new TimerState();

    class TimerState extends IAudioState {
        @Override
        void handleMessage(AudioStateMessage msg) {
            RLog.d(TAG, getClass().getSimpleName() + " handleMessage : " + msg.what);
            switch (msg.what) {
                case AUDIO_RECORD_EVENT_TIME_OUT:
                    final int counter = (int) msg.obj;
                    if (counter >= 0) {
                        android.os.Message message = android.os.Message.obtain();
                        message.what = AUDIO_RECORD_EVENT_TICKER;
                        message.obj = counter - 1;
                        mHandler.sendMessageDelayed(message, 1000);
                        setTimeoutView(counter);
                    } else {
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                stopRec();
                                sendAudioFile();
                                destroyView();
                            }
                        }, 500);
                        mCurAudioState = idleState;
                    }
                    break;
                case AUDIO_RECORD_EVENT_RELEASE:
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            stopRec();
                            sendAudioFile();
                            destroyView();
                        }
                    }, 500);
                    mCurAudioState = idleState;
                    idleState.enter();
                    break;
                case AUDIO_RECORD_EVENT_ABORT:
                    stopRec();
                    destroyView();
                    deleteAudioFile();
                    mCurAudioState = idleState;
                    idleState.enter();
                    break;
                case AUDIO_RECORD_EVENT_WILL_CANCEL:
                    setCancelView();
                    mCurAudioState = cancelState;
                    break;
            }
        }
    }

    @Override
    final public boolean handleMessage(android.os.Message msg) {
        RLog.i(TAG, "handleMessage " + msg.what);
        switch (msg.what) {
            case AUDIO_RECORD_EVENT_TIME_OUT:
                AudioStateMessage m = new AudioStateMessage();
                m.what = msg.what;
                m.obj = msg.obj;
                sendMessage(m);
                break;
            case AUDIO_RECORD_EVENT_TICKER:
                m = new AudioStateMessage();
                m.what = AUDIO_RECORD_EVENT_TIME_OUT;
                m.obj = msg.obj;
                sendMessage(m);
                break;
            case AUDIO_RECORD_EVENT_SAMPLING:
                sendEmptyMessage(AUDIO_RECORD_EVENT_SAMPLING);
                break;
        }
        return false;
    }

    private void initView(View root) {

        LayoutInflater inflater = LayoutInflater.from(root.getContext());
        View view = inflater.inflate(R.layout.rc_voice_record_popup, null);

        mStateIV = (ImageView) view.findViewById(R.id.rc_audio_state_image);
        recordBottomImage = (ImageView) view.findViewById(R.id.record_bottom_image);
        recordCancelImage = (ImageView) view.findViewById(R.id.record_cancel_image);
        voiceBackLayout = (RelativeLayout) view.findViewById(R.id.voice_back_layout);

        mStateTV = (TextView) view.findViewById(R.id.rc_audio_state_text);
        mTimerTV = (TextView) view.findViewById(R.id.rc_audio_timer);
        recordCancelText = (TextView) view.findViewById(R.id.record_cancel_text);
        recordSendText = (TextView) view.findViewById(R.id.record_send_text);

        mRecordWindow = new PopupWindow(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mRecordWindow.showAtLocation(root, Gravity.CENTER, 0, 0);
        mRecordWindow.setFocusable(true);
        mRecordWindow.setOutsideTouchable(false);
        mRecordWindow.setTouchable(false);

        originalWidth = getViewWidth(voiceBackLayout);
        Log.d("song_test", "originalWidth = " + originalWidth);
    }

    public int getViewWidth(RelativeLayout view) {
        view.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return view.getMeasuredWidth();
    }

    private void setTimeoutView(int counter) {
        if (counter > 0) {
            if (mRecordWindow != null) {
                mStateIV.setVisibility(View.GONE);
                mStateTV.setVisibility(View.VISIBLE);
                mStateTV.setText(R.string.rc_voice_rec);
                mStateTV.setBackgroundResource(android.R.color.transparent);
                mTimerTV.setText(String.format("%s", counter));
                mTimerTV.setVisibility(View.VISIBLE);
            }
        } else {
            if (mRecordWindow != null) {
                mStateIV.setVisibility(View.VISIBLE);
                mStateIV.setImageResource(R.drawable.rc_voice_volume_warning);
                mStateTV.setText(R.string.rc_voice_too_long);
                mStateTV.setBackgroundResource(android.R.color.transparent);
                mTimerTV.setVisibility(View.GONE);
            }
        }

    }

    private void setRecordingView() {
        RLog.d(TAG, "setRecordingView");

        if (mRecordWindow != null) {
            mStateIV.setVisibility(View.VISIBLE);
            mStateIV.setImageResource(R.drawable.rc_voice_volume_1);
            mStateTV.setVisibility(View.VISIBLE);
            mStateTV.setText(R.string.rc_voice_rec);
            mStateTV.setBackgroundResource(android.R.color.transparent);
            recordBottomImage.setBackgroundResource(R.drawable.record_bottom_white);
            recordCancelImage.setBackgroundResource(R.drawable.delete_no_activation);
            recordCancelText.setVisibility(View.GONE);
            recordSendText.setVisibility(View.VISIBLE);
            mTimerTV.setVisibility(View.GONE);
            voiceBackLayout.setBackgroundColor(Color.parseColor("#C2CBEE"));
            startZoomAnim(voiceBackLayout, originalWidth);
        }
    }

    private void setCancelView() {
        RLog.d(TAG, "setCancelView");

        if (mRecordWindow != null) {
            mTimerTV.setVisibility(View.GONE);
            mStateIV.setVisibility(View.VISIBLE);
            mStateIV.setImageResource(R.drawable.rc_voice_volume_cancel);
            mStateTV.setVisibility(View.VISIBLE);
            mStateTV.setText(R.string.rc_voice_cancel);
            mStateTV.setBackgroundResource(R.drawable.rc_voice_cancel_background);
            recordBottomImage.setBackgroundResource(R.drawable.record_bottom_black);
            recordCancelImage.setBackgroundResource(R.drawable.delete_activation);
            recordCancelText.setVisibility(View.VISIBLE);
            recordSendText.setVisibility(View.GONE);
            voiceBackLayout.setBackgroundColor(Color.parseColor("#BB33F6"));
            startZoomAnim(voiceBackLayout, 240);

        }
    }

    private void setCallStateChangeListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (mContext == null) {
                    return;
                }
                TelephonyManager manager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
                manager.listen(new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String incomingNumber) {
                        switch (state) {
                            case TelephonyManager.CALL_STATE_IDLE:
                            case TelephonyManager.CALL_STATE_OFFHOOK:
                                break;
                            case TelephonyManager.CALL_STATE_RINGING:
                                sendEmptyMessage(AUDIO_RECORD_EVENT_ABORT);
                                break;
                        }
                        super.onCallStateChanged(state, incomingNumber);
                    }
                }, PhoneStateListener.LISTEN_CALL_STATE);
            } catch (SecurityException e) {
                RLog.e(TAG, "AudioRecordManager", e);
            }
        }
    }

    private void destroyView() {
        RLog.d(TAG, "destroyView");
        if (mRecordWindow != null) {
            mHandler.removeMessages(AUDIO_RECORD_EVENT_TIME_OUT);
            mHandler.removeMessages(AUDIO_RECORD_EVENT_TICKER);
            mHandler.removeMessages(AUDIO_RECORD_EVENT_SAMPLING);
            mRecordWindow.dismiss();
            mRecordWindow = null;
            mStateIV = null;
            mStateTV = null;
            mTimerTV = null;
            mContext = null;
            mRootView = null;
        }
    }

    public void setMaxVoiceDuration(int maxVoiceDuration) {
        RECORD_INTERVAL = maxVoiceDuration;
    }

    public int getMaxVoiceDuration() {
        return RECORD_INTERVAL;
    }

    public void startRecord(View rootView, Conversation.ConversationType conversationType, String targetId) {
        if (rootView == null) return;
        this.mRootView = rootView;
        this.mContext = rootView.getContext().getApplicationContext();
        this.mConversationType = conversationType;
        this.mTargetId = targetId;
        this.mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);


        if (this.mAfChangeListener != null) {
            mAudioManager.abandonAudioFocus(mAfChangeListener);
            mAfChangeListener = null;
        }
        this.mAfChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int focusChange) {
                RLog.d(TAG, "OnAudioFocusChangeListener " + focusChange);
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    mAudioManager.abandonAudioFocus(mAfChangeListener);
                    mAfChangeListener = null;
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            sendEmptyMessage(AUDIO_RECORD_EVENT_ABORT);
                        }
                    });
                }
            }
        };
        sendEmptyMessage(AUDIO_RECORD_EVENT_TRIGGER);

        if (TypingMessageManager.getInstance().isShowMessageTyping()) {
            RongIMClient.getInstance().sendTypingStatus(conversationType, targetId, "RC:VcMsg");
        }
    }

    public void willCancelRecord() {
        sendEmptyMessage(AUDIO_RECORD_EVENT_WILL_CANCEL);
    }

    public void continueRecord() {
        sendEmptyMessage(AUDIO_RECORD_EVENT_CONTINUE);
    }

    public void stopRecord() {
        sendEmptyMessage(AUDIO_RECORD_EVENT_RELEASE);
    }


    public void destroyRecord() {
        AudioStateMessage msg = new AudioStateMessage();
        msg.obj = true;
        msg.what = AUDIO_RECORD_EVENT_RELEASE;
        sendMessage(msg);
    }

    void sendMessage(AudioStateMessage message) {
        mCurAudioState.handleMessage(message);
    }

    void sendEmptyMessage(int event) {
        AudioStateMessage message = new AudioStateMessage();
        message.what = event;
        mCurAudioState.handleMessage(message);
    }

    private void startRec() {
        RLog.d(TAG, "startRec");
        try {
            muteAudioFocus(mAudioManager, true);
            mAudioManager.setMode(AudioManager.MODE_NORMAL);
            mMediaRecorder = new MediaRecorder();
            int bpsNb = RongConfigCenter.featureConfig().getAudioNBEncodingBitRate();
            int bpsWb = RongConfigCenter.featureConfig().getAudioWBEncodingBitRate();
            if (RongConfigCenter.featureConfig().getVoiceMessageType() == IMCenter.VoiceMessageType.HighQuality) {
                mMediaRecorder.setAudioEncodingBitRate(AUDIO_AA_ENCODING_BIT_RATE);
            } else {
                mMediaRecorder.setAudioSamplingRate(mSampleRate.value);
                if (mSampleRate.equals(SamplingRate.RC_SAMPLE_RATE_8000)) {
                    mMediaRecorder.setAudioEncodingBitRate(bpsNb);
                } else {
                    mMediaRecorder.setAudioEncodingBitRate(bpsWb);
                }
            }

            mMediaRecorder.setAudioChannels(1);
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (RongConfigCenter.featureConfig().getVoiceMessageType().equals(IMCenter.VoiceMessageType.HighQuality)) {
                mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
                } else {
                    mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                }
            } else {
                if (mSampleRate.equals(SamplingRate.RC_SAMPLE_RATE_8000)) {
                    mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
                    mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                } else {
                    mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_WB);
                    mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB);
                }
            }
            File savePath = SavePathUtils.getSavePath(mContext.getCacheDir());
            mAudioPath = Uri.fromFile(new File(savePath, System.currentTimeMillis() + "temp.voice"));
            mMediaRecorder.setOutputFile(mAudioPath.getPath());
            mMediaRecorder.prepare();
            mMediaRecorder.start();

            android.os.Message message = android.os.Message.obtain();
            message.what = AUDIO_RECORD_EVENT_TIME_OUT;
            message.obj = 10;
            mHandler.sendMessageDelayed(message, RECORD_INTERVAL * 1000 - 10 * 1000);
        } catch (Exception e) {
            RLog.e(TAG, "startRec", e);
        }
    }

    private boolean checkAudioTimeLength() {
        long delta = SystemClock.elapsedRealtime() - smStartRecTime;
        return (delta < 1000);
    }

    private void stopRec() {
        RLog.d(TAG, "stopRec");
        try {
            muteAudioFocus(mAudioManager, false);
            if (mMediaRecorder != null) {
                mMediaRecorder.stop();
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (Exception e) {
            RLog.e(TAG, "stopRec", e);
        }
    }

    private void deleteAudioFile() {
        RLog.d(TAG, "deleteAudioFile");

        if (mAudioPath != null) {
            File file = new File(mAudioPath.getPath());
            if (file.exists()) {
                boolean deleteResult = file.delete();
                if (!deleteResult) {
                    RLog.e(TAG, "deleteAudioFile delete file failed. path :" + mAudioPath.getPath());
                }
            }
        }
    }

    private void sendAudioFile() {
        RLog.d(TAG, "sendAudioFile path = " + mAudioPath);
        if (mAudioPath != null) {
            File file = new File(mAudioPath.getPath());
            if (!file.exists() || file.length() == 0) {
                RLog.e(TAG, "sendAudioFile fail cause of file length 0 or audio permission denied");
                return;
            }
            int duration = (int) (SystemClock.elapsedRealtime() - smStartRecTime) / 1000;
            if (RongConfigCenter.featureConfig().getVoiceMessageType() == IMCenter.VoiceMessageType.HighQuality) {
                HQVoiceMessage hqVoiceMessage = HQVoiceMessage.obtain(mAudioPath, Math.min(duration, RECORD_INTERVAL));
                if (DestructManager.isActive()) {
                    hqVoiceMessage.setDestructTime(DestructManager.VOICE_DESTRUCT_TIME);
                }
                Message message = Message.obtain(mTargetId, mConversationType, hqVoiceMessage);
                IMCenter.getInstance().sendMediaMessage(message, DestructManager.isActive() ? mContext.getResources().getString(R.string.rc_conversation_summary_content_burn) : null, null,
                        new IRongCallback.ISendMediaMessageCallback() {
                            @Override
                            public void onProgress(Message message, int progress) {

                            }

                            @Override
                            public void onCanceled(Message message) {

                            }

                            @Override
                            public void onAttached(Message message) {

                            }

                            @Override
                            public void onSuccess(Message message) {
                            }

                            @Override
                            public void onError(Message message, RongIMClient.ErrorCode errorCode) {
                                RLog.d(TAG, "onError = " + errorCode.toString());
                            }
                        });
            } else {
                VoiceMessage voiceMessage = VoiceMessage.obtain(mAudioPath, Math.min(duration, RECORD_INTERVAL));
                if (DestructManager.isActive()) {
                    voiceMessage.setDestructTime(DestructManager.VOICE_DESTRUCT_TIME);
                }
                IMCenter.getInstance().sendMessage(Message.obtain(mTargetId, mConversationType, voiceMessage), DestructManager.isActive() ? mContext.getResources().getString(R.string.rc_conversation_summary_content_burn) : null, null, new IRongCallback.ISendMessageCallback() {
                    @Override
                    public void onAttached(Message message) {

                    }

                    @Override
                    public void onSuccess(Message message) {

                    }

                    @Override
                    public void onError(Message message, RongIMClient.ErrorCode errorCode) {

                    }
                });
            }
        }
    }

    private void audioDBChanged() {
        if (mMediaRecorder != null) {
            int db = 0;
            try {
                db = mMediaRecorder.getMaxAmplitude() / 600;
            } catch (IllegalStateException e) {
                RLog.e(TAG, "audioDBChanged IllegalStateException");
            }

            switch (db / 5) {
                case 0:
                    mStateIV.setImageResource(R.drawable.rc_voice_volume_1);
                    break;
                case 1:
                    mStateIV.setImageResource(R.drawable.rc_voice_volume_2);
                    break;
                case 2:
                    mStateIV.setImageResource(R.drawable.rc_voice_volume_3);
                    break;
                case 3:
                    mStateIV.setImageResource(R.drawable.rc_voice_volume_4);
                    break;
                case 4:
                    mStateIV.setImageResource(R.drawable.rc_voice_volume_5);
                    break;
                case 5:
                    mStateIV.setImageResource(R.drawable.rc_voice_volume_6);
                    break;
                default:
                    mStateIV.setImageResource(R.drawable.rc_voice_volume_6);
                    break;
            }
        }
    }

    private void muteAudioFocus(AudioManager audioManager, boolean bMute) {
        if (Build.VERSION.SDK_INT < 8) {
            // 2.1以下的版本不支持下面的API：requestAudioFocus和abandonAudioFocus
            RLog.d(TAG, "muteAudioFocus Android 2.1 and below can not stop music");
            return;
        }
        if (audioManager == null) {
            RLog.e(TAG, "audioManager is null");
            return;
        }
        if (bMute) {
            audioManager.requestAudioFocus(mAfChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } else {
            audioManager.abandonAudioFocus(mAfChangeListener);
            mAfChangeListener = null;
        }
    }

    /**
     * 设置语音消息采样率
     *
     * @param sampleRate 消息采样率{@link SamplingRate}
     */
    public void setSamplingRate(SamplingRate sampleRate) {
        this.mSampleRate = sampleRate;
    }

    /**
     * 语音消息采样率
     *
     * @return 当前设置的语音采样率
     */
    public int getSamplingRate() {
        return mSampleRate.getValue();
    }

    abstract class IAudioState {
        void enter() {
        }

        abstract void handleMessage(AudioStateMessage message);
    }

    class AudioStateMessage {
        public int what;
        public Object obj;

        public AudioStateMessage obtain() {
            return new AudioStateMessage();
        }
    }

    /**
     * 语音消息采样率
     */
    public enum SamplingRate {
        /**
         * 8KHz
         */
        RC_SAMPLE_RATE_8000(8000),

        /**
         * 16KHz
         */
        RC_SAMPLE_RATE_16000(16000);

        SamplingRate(int sampleRate) {
            this.value = sampleRate;
        }

        private int value;

        public int getValue() {
            return this.value;
        }
    }

    public static void startValAnim(int from, int to, ValueAnimator.AnimatorUpdateListener listener,
                                    long duration) {
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        //设置动画时长
        animator.setDuration(duration);
        //设置插值器，当然你可以不用
        animator.setInterpolator(new DecelerateInterpolator());
        //回调监听
        animator.addUpdateListener(listener);
        //启动动画
        animator.start();
    }


    public static <V extends View> void startZoomAnim(@NonNull final V v, int to) {
        Log.d("song_test", "to = " + to);

        startValAnim(v.getWidth(), to, new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                /* 主要还是通过获取布局参数设置宽高 */
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                //获取改变时的值
                int size = Integer.valueOf(animation.getAnimatedValue().toString());
                /* 因为我的需求是一个正方形，如果不规则可以等比例设置，这里我就不写了 */
                lp.width = size;
//                lp.height = size;
                v.setLayoutParams(lp);
            }
        }, 200);
    }
}