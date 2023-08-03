package com.cama.utils;

import com.cama.dto.UserDTO;

/**
 * 使用ThreadLocal进行线程隔离，使得不同的用户访问各自的内容
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
