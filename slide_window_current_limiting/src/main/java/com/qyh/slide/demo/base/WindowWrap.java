package com.qyh.slide.demo.base;


/**
 * @author K0n9D1KuA
 * @version 1.0
 * @description: 窗口对象
 * @date 2022/12/2 21:15
 */

public class WindowWrap {
    //该窗口的起始时间
    private long windowStart;
    //窗口的总长
    private final long windowLengthInMs;
    //该窗口里面的值 qps
    private int value;
    //构造方法

    public WindowWrap(long windowStart, long windowLengthInMs, int value) {
        this.windowStart = windowStart;
        this.windowLengthInMs = windowLengthInMs;
        this.value = value;
    }


    //get set

    public Long getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Long windowStart) {
        this.windowStart = windowStart;
    }

    public Long getWindowLengthInMs() {
        return windowLengthInMs;
    }

    public Integer getValue() {
        return value;
    }

    public void setValue(Integer value) {
        this.value = value;
    }
}
