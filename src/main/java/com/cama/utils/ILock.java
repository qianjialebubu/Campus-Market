package com.cama.utils;

/**
 * @author qjl
 * @create 2022-10-07 11:10
 */
public interface ILock {
    boolean tryLock(long timeoutSec);
    void unlock();
}
