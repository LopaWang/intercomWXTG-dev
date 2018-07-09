package com.jd.wly.intercom.output;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.Handler;
import android.util.Log;

import com.jd.wly.intercom.R;
import com.jd.wly.intercom.data.AudioData;
import com.jd.wly.intercom.data.MessageQueue;
import com.jd.wly.intercom.input.Recorder;
import com.jd.wly.intercom.job.JobHandler;
import com.jd.wly.intercom.job.ThreadCallback;
import com.jd.wly.intercom.util.Constants;

import static android.content.ContentValues.TAG;

/**
 * AudioTrack音频播放
 *
 * @author yanghao1
 */
public class Tracker extends JobHandler {

    private AudioTrack audioTrack;
    protected Handler handler;
    private ThreadCallback mThreadCallback;
    // 音频大小
    private int outAudioBufferSize;
    // 播放标志
    private boolean isPlaying = true;

    //有其他手机在讲话
    private boolean isOtherPlaying = false;

    private int mWeiChatAudio;
    private SoundPool mSoundPool;//摇一摇音效
    private Context context;

    public Tracker(Handler handler, ThreadCallback threadCallback , Context context) {
        super(handler ,threadCallback);
        this.mThreadCallback = threadCallback;
        this.handler = handler;
        this.context = context;
        // 获取音频数据缓冲段大小
        outAudioBufferSize = AudioTrack.getMinBufferSize(
                Constants.sampleRateInHz, Constants.outputChannelConfig, Constants.audioFormat);
        // 初始化音频播放
        audioTrack = new AudioTrack(Constants.streamType,
                Constants.sampleRateInHz, Constants.outputChannelConfig, Constants.audioFormat,
                outAudioBufferSize, Constants.trackMode);
        audioTrack.play();
        //初始化SoundPool
        mSoundPool = new SoundPool(1, AudioManager.STREAM_SYSTEM, 5);
        mWeiChatAudio = mSoundPool.load(context, R.raw.weichat_audio, 1);
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlaying(boolean playing) {
        isPlaying = playing;
    }
    public boolean isOtherPlaying(){
        return isOtherPlaying;
    }
    public void setOtherPlaying(boolean playing) {
        isOtherPlaying = playing;
    }

    @Override
    public void run() {
        AudioData audioData;
        while ((audioData = MessageQueue.getInstance(MessageQueue.TRACKER_DATA_QUEUE).take()) != null) {
                if (isPlaying()) {
                    short[] bytesPkg = audioData.getRawData();
                    try {
                        mThreadCallback.threadStartLisener();
                        audioTrack.write(bytesPkg, 0, bytesPkg.length);
                        Log.i(TAG, "run: audioTrack" + audioTrack.getPlayState());
//                        isOtherPlaying =true;
                    } catch (Exception e) {
                        Log.i(TAG, "run: e = " + e);
                        e.printStackTrace();
                    }
            }else {
                    Log.i(TAG, "run: isNotPlaying()");
                    isOtherPlaying =false;
                    //发出提示音
                    mSoundPool.play(mWeiChatAudio, 1, 1, 0, 0, 1);
                }

        }


    }

    @Override
    public void free() {
        Log.i(TAG, "free: stop");
        audioTrack.stop();
        audioTrack.release();
        audioTrack = null;
    }
}
