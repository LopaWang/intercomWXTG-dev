package com.jd.wly.intercom;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.jd.wly.intercom.discover.AudioHandler;
import com.jd.wly.intercom.discover.SignInAndOutReq;
import com.jd.wly.intercom.input.Encoder;
import com.jd.wly.intercom.input.Recorder;
import com.jd.wly.intercom.input.Sender;
import com.jd.wly.intercom.network.Multicast;
import com.jd.wly.intercom.output.Decoder;
import com.jd.wly.intercom.output.Receiver;
import com.jd.wly.intercom.output.Tracker;
import com.jd.wly.intercom.users.IntercomAdapter;
import com.jd.wly.intercom.users.IntercomUserBean;
import com.jd.wly.intercom.users.VerticalSpaceItemDecoration;
import com.jd.wly.intercom.util.Command;
import com.jd.wly.intercom.util.Constants;
import com.jd.wly.intercom.util.IPUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static android.content.ContentValues.TAG;

public class AudioActivity extends Activity implements View.OnClickListener, View.OnTouchListener{

    private RecyclerView localNetworkUser;
    private TextView startIntercom;
    private Button closeIntercom;
    private TextView currentIp;
    private ImageView chatRecord;
    private int count = 0;
    private List<IntercomUserBean> userBeanList = new ArrayList<>();
    private IntercomAdapter intercomAdapter;

    private AudioHandler audioHandler = new AudioHandler(this);
    private SignInAndOutReq discoverRequest;

    // 创建循环任务线程用于间隔的发送上线消息，获取局域网内其他的用户
    private ScheduledExecutorService discoverService = Executors.newScheduledThreadPool(1);
    // 创建7个线程的固定大小线程池，分别执行DiscoverServer，以及输入、输出音频
    private ExecutorService threadPool = Executors.newFixedThreadPool(7);

    // 音频输入
    private Recorder recorder;
    private Encoder encoder;
    private Sender sender;

    // 音频输出
    private Receiver receiver;
    private Decoder decoder;
    private Tracker tracker;


    private int mWeiChatAudioError ,mWeiChatAudioBegin , mWeiChatAudioUp;
    private SoundPool mSoundPool;//摇一摇音效


    private static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private boolean isOtherPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                    MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
        }else {
            initData();
        }
        initView();

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == MY_PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(AudioActivity.this, getResources().getText(R.string.permission_success), Toast.LENGTH_SHORT).show();
                initData();
            } else {
                Toast.makeText(AudioActivity.this,  getResources().getText(R.string.permission_fail), Toast.LENGTH_SHORT).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }


    private void initView() {
        // 设置用户列表
        localNetworkUser = (RecyclerView) findViewById(R.id.activity_audio_local_network_user_rv);
        localNetworkUser.setLayoutManager(new LinearLayoutManager(this));
        localNetworkUser.addItemDecoration(new VerticalSpaceItemDecoration(10));
        localNetworkUser.setItemAnimator(new DefaultItemAnimator());
        intercomAdapter = new IntercomAdapter(userBeanList);
        localNetworkUser.setAdapter(intercomAdapter);
        // 添加自己
        addNewUser(new IntercomUserBean(IPUtil.getLocalIPAddress(), getResources().getString(R.string.me)));

        startIntercom = (TextView) findViewById(R.id.start_intercom);
        startIntercom.setOnTouchListener(this);
        chatRecord = (ImageView) findViewById(R.id.chat_record);
        chatRecord.setOnTouchListener(this);
        closeIntercom = (Button) findViewById(R.id.close_intercom);
        closeIntercom.setOnClickListener(this);
        // 设置当前IP地址
        currentIp = (TextView) findViewById(R.id.activity_audio_current_ip);
        currentIp.setText(IPUtil.getLocalIPAddress());

        //初始化SoundPool
        mSoundPool = new SoundPool(1, AudioManager.STREAM_SYSTEM, 5);
        mWeiChatAudioError = mSoundPool.load(this, R.raw.talkroom_sasasa, 1);
        mWeiChatAudioBegin = mSoundPool.load(this, R.raw.talkroom_begin_ham, 1);
        mWeiChatAudioUp = mSoundPool.load(this, R.raw.talkroom_up_ham, 1);
    }

    private void initData() {
        // 初始化探测线程
        discoverRequest = new SignInAndOutReq(audioHandler);
        discoverRequest.setCommand(Command.DISC_REQUEST);
        // 启动探测局域网内其余用户的线程（每分钟扫描一次）
        discoverService.scheduleAtFixedRate(discoverRequest, 0, 10, TimeUnit.SECONDS);
        // 初始化AudioManager配置
        initAudioManager();
        // 初始化JobHandler
        initJobHandler();
    }


    /**
     * 初始化AudioManager配置
     */
    private void initAudioManager() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.STREAM_MUSIC);
        audioManager.setSpeakerphoneOn(true);
    }

    /**
     * 初始化JobHandler
     */
    private void initJobHandler() {
        // 初始化音频输入节点
        recorder = new Recorder(audioHandler);
        encoder = new Encoder(audioHandler);
        sender = new Sender(audioHandler);
        // 初始化音频输出节点
        receiver = new Receiver(audioHandler);
        decoder = new Decoder(audioHandler);
        tracker = new Tracker(audioHandler,recorder,this);
        // 开启音频输入、输出

        threadPool.execute(encoder);
        threadPool.execute(sender);
        threadPool.execute(receiver);
        threadPool.execute(decoder);
        threadPool.execute(tracker);

    }

    @Override
    public void onClick(View v) {
        if (v == closeIntercom) {
//            discoverRequest.setCommand(Command.DISC_LEAVE);
            Process.killProcess(Process.myPid());
        }
    }

    /**
     * 更新自身IP
     */
    public void updateMyself() {
        currentIp.setText(IPUtil.getLocalIPAddress());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_F2 ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (!isOtherPlaying) {
                keyDown();
            }else {
                mSoundPool.play(mWeiChatAudioError, 1, 1, 0, 0, 1);
            }
            return true;
        }else if(keyCode==KeyEvent.KEYCODE_BACK){
            Process.killProcess(Process.myPid());
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_F2 ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (!isOtherPlaying) {
                keyUp();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v == chatRecord && recorder != null) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (!isOtherPlaying) {
                    keyDown();
            }else {
                    mSoundPool.play(mWeiChatAudioError, 1, 1, 0, 0, 1);
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (!isOtherPlaying) {
                    keyUp();
                }

            }
            return true;
        }
        return false;
    }


    private void keyDown() {
        chatRecord.setImageDrawable(getResources().getDrawable(R.drawable.se_icon_voice_pressed));
        startIntercom.setText(getResources().getText(R.string.leave_end));
        startIntercom.setTextColor(getResources().getColor(R.color.colorBlue));
        mSoundPool.play(mWeiChatAudioBegin, 1, 1, 0, 0, 1);
        if (!recorder.isRecording()) {
            recorder.setRecording(true);
            tracker.setPlaying(false);
            threadPool.execute(recorder);
            receiveReleaseCallEvent();
        }
    }

    private void keyUp() {
        chatRecord.setImageDrawable(getResources().getDrawable(R.drawable.se_icon_voice_default));
        startIntercom.setText(getResources().getText(R.string.press_speak));
        startIntercom.setTextColor(getResources().getColor(R.color.white));
        if (recorder.isRecording()) {
            recorder.setRecording(false);
            tracker.setPlaying(true);
            sendReleaseCallEvent();
        }
        mSoundPool.play(mWeiChatAudioUp, 1, 1, 0, 0, 1);
    }


    private void sendReleaseCallEvent(){
        Runnable callRelease=new Runnable() {
            @Override
            public void run() {
                byte[] data = Command.DISC_CALL_RELEASE.getBytes();
                DatagramPacket datagramPacket = new DatagramPacket(
                        data, data.length, Multicast.getMulticast().getInetAddress(), Constants.MULTI_BROADCAST_PORT);
                try {
                    MulticastSocket multicastSocket = Multicast.getMulticast().getMulticastSocket();
                    if (multicastSocket != null) {
                        multicastSocket.send(datagramPacket);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        threadPool.execute(callRelease);
    }

    private void receiveReleaseCallEvent(){
        Runnable callRelease=new Runnable() {
            @Override
            public void run() {
                byte[] data = Command.DISC_CALL_RELEASE_RECEVICE.getBytes();
                DatagramPacket datagramPacket = new DatagramPacket(
                        data, data.length, Multicast.getMulticast().getInetAddress(), Constants.MULTI_BROADCAST_PORT);
                try {
                    MulticastSocket multicastSocket = Multicast.getMulticast().getMulticastSocket();
                    if (multicastSocket != null) {
                        multicastSocket.send(datagramPacket);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        threadPool.execute(callRelease);
    }

    /**
     * 发现新的用户地址
     *
     * @param ipAddress
     */
    public void foundNewUser(String ipAddress) {
        IntercomUserBean userBean = new IntercomUserBean(ipAddress);
        if (!userBeanList.contains(userBean)) {
            addNewUser(userBean);
        }
    }

    /**
     * 删除用户
     *
     * @param ipAddress
     */
    public void removeExistUser(final String ipAddress) {
        IntercomUserBean userBean = new IntercomUserBean(ipAddress);
        if (userBeanList.contains(userBean)) {
            int position = userBeanList.indexOf(userBean);
            userBeanList.remove(position);
            intercomAdapter.notifyItemRemoved(position);
            intercomAdapter.notifyItemRangeChanged(0, userBeanList.size());
        }
    }

    /**
     *抬起发送线程
     * @param ipAddress
     */
    public void releasePTT(final String ipAddress) {
        IntercomUserBean userBean = new IntercomUserBean(ipAddress);
        startIntercom.setText(getResources().getText(R.string.press_speak));
        chatRecord.setImageDrawable(getResources().getDrawable(R.drawable.comment_voice_selector));
        if (userBeanList.contains(userBean)) {
            int position = userBeanList.indexOf(userBean);
            View view= localNetworkUser.getChildAt(position);
            TextView tv = (TextView) view.findViewById(R.id.tv_speaking);
            tv.setVisibility(View.GONE);
            isOtherPlaying = false;
        }
    }
    /**
     *按下发送线程
     * @param ipAddress
     */
    public void releaseBTT(final String ipAddress) {
        IntercomUserBean userBean = new IntercomUserBean(ipAddress);
        startIntercom.setText(getResources().getText(R.string.other_speak));
        chatRecord.setImageDrawable(getResources().getDrawable(R.drawable.se_icon_voice_error));
        if (userBeanList.contains(userBean)) {
            int position = userBeanList.indexOf(userBean);
            View view= localNetworkUser.getChildAt(position);
            TextView tv = (TextView) view.findViewById(R.id.tv_speaking);
            tv.setVisibility(View.VISIBLE);
            isOtherPlaying = true;
        }
    }



    /**
     * 增加新的用户
     *
     * @param userBean 新用户
     */
    public void addNewUser(IntercomUserBean userBean) {
        userBeanList.add(userBean);
        intercomAdapter.notifyItemInserted(userBeanList.size() - 1);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        free();
    }

    /**
     * 释放系统资源
     */
    private void free() {
        // 释放线程资源
        recorder.free();
        encoder.free();
        sender.free();
        receiver.free();
        decoder.free();
        tracker.free();
        // 释放线程池
        discoverService.shutdown();
        threadPool.shutdown();
    }

}
