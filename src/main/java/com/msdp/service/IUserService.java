package com.msdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.msdp.dto.LoginFormDTO;
import com.msdp.dto.Result;
import com.msdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Eason
 * @since 2023-03-15
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
