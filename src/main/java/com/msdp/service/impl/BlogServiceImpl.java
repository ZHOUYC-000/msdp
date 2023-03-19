package com.msdp.service.impl;

import com.msdp.entity.Blog;
import com.msdp.mapper.BlogMapper;
import com.msdp.service.IBlogService;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

}
