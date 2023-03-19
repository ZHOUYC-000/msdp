package com.msdp.service;

import com.msdp.dto.Result;
import com.msdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Eason
 * @since 2023-03-14
 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    Result queryHotBlog(Integer current);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result savaBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
