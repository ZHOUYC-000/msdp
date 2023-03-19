package com.msdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msdp.dto.LoginFormDTO;
import com.msdp.dto.Result;
import com.msdp.dto.UserDTO;
import com.msdp.entity.User;
import com.msdp.mapper.UserMapper;
import com.msdp.service.IUserService;
import com.msdp.utils.RedisConstants;
import com.msdp.utils.RegexUtils;
import com.msdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Eason
 * @since 2023-03-15
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate template;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if( RegexUtils.isPhoneInvalid(phone) ){
            // 2.不符合
            return Result.fail("手机号格式错误");
        }
        // 3.符合
        String code = RandomUtil.randomNumbers(6);
        // 4.发送，保存验证码
        // session.setAttribute("code", code);
        // 4.使用Redis
        template.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone , code , RedisConstants.LOGIN_CODE_TTL , TimeUnit.MINUTES);
        log.debug("已发送验证码 ：{} ", code);
        // 5.返回结果
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 1.校验手机号
        if( RegexUtils.isPhoneInvalid(phone) ){
            // 2.不符合
            return Result.fail("手机号格式错误");
        }

        // 3.校验验证码
        // String cacheCode = (String) session.getAttribute("code");
        String cacheCode = template.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(cacheCode == null || !cacheCode.equals(code)){
            // 4.不一致
            return Result.fail("验证码不一致");
        }
        // 5.判断用户是否存在
        User user = query().eq("phone", phone).one();
        if(user == null){
            // 6.不存在，创建用户
            user = createUserWithPhone(phone);
        }

        // 7.保存用户到session
        // session.setAttribute("user",user);

        // 7.1 生成一个随机token
        String token = UUID.randomUUID().toString();
        ObjectMapper objectMapper = new ObjectMapper();
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        String userJson = null;
        try {
            userJson = objectMapper.writeValueAsString(userDTO);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        template.opsForValue().set(RedisConstants.LOGIN_USER_KEY + token ,userJson, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        template.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
