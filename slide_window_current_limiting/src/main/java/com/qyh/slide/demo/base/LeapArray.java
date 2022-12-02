package com.qyh.slide.demo.base;


import ch.qos.logback.core.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author K0n9D1KuA
 * @version 1.0
 * @description: 循环数组
 * @date 2022/12/2 21:23
 */
@Slf4j
public class LeapArray {

    //每个窗口的长度
    private long windowLengthInMs;
    //要把一个周期划分为几个小周期？
    private int sampleCount;
    //一个周期的总时间
    private int intervalInMs;
    //一个周期的总时间 转化为s
    private double intervalInSecond;
    //锁
    private final ReentrantLock updateLock = new ReentrantLock();

    //原子引用数组
    protected final AtomicReferenceArray<WindowWrap> array;

    //构造函数
    public LeapArray(int sampleCount, int intervalInMs) {
        if (sampleCount < 0) {
            throw new RuntimeException("bucket count is invalid: " + sampleCount);
        }
        if (intervalInMs < 0) {
            throw new RuntimeException("total time interval of the sliding window should be positive");
        }
        if (intervalInMs % sampleCount != 0) {
            throw new RuntimeException("total time interval of the sliding window should be positive");
        }
        windowLengthInMs = intervalInMs / sampleCount;
        this.intervalInMs = intervalInMs;
        this.intervalInSecond = intervalInMs / 1000.0;
        this.sampleCount = sampleCount;
        //创建循环数组
        this.array = new AtomicReferenceArray<>(sampleCount);
    }

    /*
     * @author: K0n9D1KuA
     * @description: 获得当前时间所对应的窗口
     * @param: null
     * @return:
     * @date: 2022/12/2 21:37
     */

    public WindowWrap currentWindow() {
        return this.currentWindow(System.currentTimeMillis());
    }
    /*
     * @author: K0n9D1KuA
     * @description: 获得当前时间所对应的窗口
     * @param: long timeMillis 当前系统时间时间戳
     * @return:
     * @date: 2022/12/2 21:37
     */

    public WindowWrap currentWindow(long timeMillis) {
        if (timeMillis < 0) {
            //健壮性判断
            return null;
        }
        //计算当前时间戳对应的下标
        int idx = calculateTimeIdx(timeMillis);
        //获得当前时间戳所对应窗口对象的起始时间
        long windowStart = calculateWindowStart(timeMillis);

        //因为可能会出现修改失败的情况 所以无限循环
        while (true) {
            //根据计算出来的下标获得旧的窗口对象
            WindowWrap old = array.get(idx);
            if (old == null) {
                //说明该窗口还没有人使用过 那么直接创建返回
                WindowWrap newWindow = new WindowWrap(windowStart, windowLengthInMs, 0);
                //cas操作
                if (array.compareAndSet(idx, null, newWindow)) {
                    return newWindow;
                } else {
                    //修改失败
                    //yield()的作用是让步。它能让当前线程由“运行状态”进入到“就绪状态”，从而让其它具有相同优先级的等待线程获取执行权；但是，并不能保
                    //证在当前线程调用yield()之后，其它具有相同优先级的线程就一定能获得执行权；也有可能是当前线程又进入到“运行状态”继续运行！
                    Thread.yield();
                }
            } else if (old.getWindowStart() == windowStart) {
                //说明是同一个时间段 有别人已经帮我创建了
                //我直接返回当前窗口就行
                return old;
            } else if (old.getWindowStart() < windowStart) {
                //说明我将会覆盖该窗口
                //修改原子引用数组里面的具体某一个对象无法保证线程安全
                //所以我们要上锁
                if (updateLock.tryLock()) {
                    try {
                        old.setWindowStart(windowStart);
                        return old;
                    } finally {
                        //释放锁
                        updateLock.unlock();
                    }
                }
                //获取锁失败了 放弃cpu使用权
                else {
                    Thread.yield();
                }
            }
        }

    }

    //计算当前时间戳对应的下标
    private int calculateTimeIdx(long timeMillis) {
        //假设 [0 500] [500 1000] [1000,1500] 三个时间段一直循环
        //2250属于哪个时间段？？
        //等比例放缩  将 [0     500] [500 1000]  [1000,1500] 变为[0,1] [1,2] [2,3]
        //                ↑             ↑           ↑
        //            [1500 2000] [2000 2500] [2500,3000]
        //                0            1          2
        //所以2250也除以500  变为4
        //再用4%3（元素个数） =  1 所以应该放在 位置[2000 2500] 也就是 [500,1000]里面
        return (int) ((timeMillis / windowLengthInMs) % sampleCount);
    }

    private long calculateWindowStart(long timeMillis) {
        //假设 [0 500] [500 1000] [1000,1500] 三个时间段一直循环
        //假设现在的时间是 1250 那么怎么获得它所属窗口的起始时间呢
        // 1000 = 1250(timeMillis) - 250
        // 250 = 1250(timeMillis) % 500(windowLengthInm)
        // windowStart = timeMillis - timeMillis % windowLengthInMs
        return timeMillis - timeMillis % windowLengthInMs;
    }

    public int values() {
        return values(System.currentTimeMillis());
    }


    public int values(long timeMillis) {
        //健壮性判断
        if (timeMillis < 0) {
            throw new RuntimeException("the param timeMillis is not valid");
        }
        //获得循环数组的长度
        int size = array.length();
        int allQps = 0;
        for (int i = 0; i < size; i++) {
            WindowWrap windowWrap = array.get(i);
            //如果是已经被弃用的窗口 或者空窗口 都应该跳过
            //为什么需要判断是否有窗口被弃用?
            //这sampleCount个窗口不就应该是一个周期内的么？
            // 看这么一种情况
            // =======================================
            // 现在一个数组的状态
            //       ↓
            // [0,500]   [500,1000]  [1000,1500]
            // 假设过了很久很久 现在的时间已经来到了 5560s
            // 那么现在循环数组的状态
            // [0,500]   [500,1000]  [1000,1500]
            //   ↑           ↑            ↑
            // [0,500]   [500,1000]  [5500,6000]
            // 此时 [0,500]   [500,1000] 这两个窗口都应该被弃用
            if (array.get(i) == null || isWindowDeprecated(timeMillis, windowWrap)) {
                //跳过这一个
                continue;
            } else {
                allQps += windowWrap.getValue();
            }
        }
        return allQps;
    }

    //判断是否已经是被弃用的窗口
    private boolean isWindowDeprecated(long timeMillis, WindowWrap windowWrap) {
        // 现在一个数组的状态
        //       ↓
        // [0,500]   [500,1000]  [1000,1500]
        // 假设过了很久很久 现在的时间已经来到了 5560s
        // 那么现在循环数组的状态
        // [0,500]   [500,1000]  [1000,1500]
        //   ↑           ↑            ↑
        // [0,500]   [500,1000]  [5500,6000]
        // 此时 [0,500]   [500,1000] 这两个窗口都应该被启用
        // 理应靠近的两个窗口应该是
        // [4500,5000] [5000,5500]
        //他们都满足 timeMillis - windowStart = 1000 或者 timeMillis - windowStart = 500 都 <= 1000
        //
        //哪些窗口应该被弃用？
        //满足 timeMillis - windowStart >
        return timeMillis - windowWrap.getWindowStart() > windowLengthInMs;
    }
}
