package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_TOP;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author
 * @since
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IBlogService blogService;
    @Override
    public Result queryBlogById(Long id) {
        //1、根据博客id查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("Blog not found");
        }
        //2、根据id查询用户
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);

    }

    private void isBlogLiked(Blog blog) {
        //3、查询笔记是否被点赞，点赞就将点赞字段设置为true
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录直接返回
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY+ blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
//        if (BooleanUtil.isTrue(member)){
//            //已经点赞，将字段设置为true
//            blog.setIsLike(true);
//        }else {
//            //未点赞设置为false
//            blog.setIsLike(false);
//        }
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 创建点赞功能进行限制，一人一个点赞。点赞以后会进行高亮显示，再次点击进行取消。
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //获取用户,把点赞信息使用redis的set集合进行接收，笔记id作为key，用户id作为value
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY+ id;
        //1、判断是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            //1.1 未点赞，可以进行点赞
            //1.1.1 保存用户的点赞信息到redis的set集合
            //1.1.2 修改数据库的点赞数+1
            boolean is_success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (is_success) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //1.2 已经点赞，取消点赞
            //1.2.2 修改数据库的点赞数-1
            boolean is_success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(is_success){
                //1.2.1 删除点赞信息到redis
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());

            }
        }
        return Result.ok();
    }

    /**
     * 实现查询top5点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //1查询获得点赞的top5
        String key = BLOG_LIKED_KEY+ id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, BLOG_LIKED_TOP - 1);
        if (top5==null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idstr = StrUtil.join(",",ids);
        //2解析出用户
        //3将用户UserDTO返回
        List<UserDTO> userDTOS = userService.query().in("id",ids)
                .last("ORDER BY FIELD(id,"+idstr+")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
