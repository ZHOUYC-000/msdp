package com.msdp.service.impl;

import com.msdp.entity.BlogComments;
import com.msdp.mapper.BlogCommentsMapper;
import com.msdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Eason
 * @since 2023-03-15
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
