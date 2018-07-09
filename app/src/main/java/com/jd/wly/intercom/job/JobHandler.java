package com.jd.wly.intercom.job;

import android.os.Handler;
import android.util.Log;

import com.jd.wly.intercom.data.AudioData;
import com.jd.wly.intercom.data.MessageQueue;

import javax.security.auth.login.LoginException;

import static android.content.ContentValues.TAG;

/**
 * 数据处理节点
 *
 * @author yanghao1
 */

public abstract class JobHandler extends Thread implements Runnable {

    protected Handler handler;
    public JobHandler(Handler handler) {
        this.handler = handler;
    }


    public void free() {

    }

}

