
package com.cama.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cama.dto.Result;
import com.cama.dto.ScrollResult;
import com.cama.dto.UserDTO;
import com.cama.entity.Blog;
import com.cama.entity.Follow;
import com.cama.entity.User;
import com.cama.mapper.BlogMapper;
import com.cama.service.IBlogService;
import com.cama.service.IFollowService;
import com.cama.service.IUserService;
import com.cama.utils.SystemConstants;
import com.cama.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.cama.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.cama.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        System.out.println(records);
//         查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的set集合  zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        System.out.println(userId);
        User user = userService.getById(userId);
//        System.out.println(user.toString());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}


///**
// * 问题代码
// */
//package com.hmdp.service.impl;
//
//import cn.hutool.core.bean.BeanUtil;
//import cn.hutool.core.util.StrUtil;
//import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
//import com.hmdp.dto.Result;
//import com.hmdp.dto.ScrollResult;
//import com.hmdp.dto.UserDTO;
//import com.hmdp.entity.Blog;
//import com.hmdp.entity.Follow;
//import com.hmdp.entity.User;
//import com.hmdp.mapper.BlogMapper;
//import com.hmdp.service.IBlogService;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.hmdp.service.IFollowService;
//import com.hmdp.service.IUserService;
//import com.hmdp.utils.SystemConstants;
//import com.hmdp.utils.UserHolder;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.data.redis.core.ZSetOperations;
//import org.springframework.stereotype.Service;
//
//import javax.annotation.Resource;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//import java.util.Set;
//import java.util.stream.Collectors;
//
//import static com.hmdp.utils.RedisConstants.*;
//
///**
// * <p>
// *  服务实现类
// * </p>
// *
// * @author
// * @since
// */
//@Service
//public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
//    @Resource
//    private IUserService userService;
//    @Resource
//    private StringRedisTemplate stringRedisTemplate;
//    @Resource
//    private IBlogService blogService;
//    @Resource
//    private IFollowService iFollowService;
//    @Override
//    public Result queryBlogById(Long id) {
//        //1、根据博客id查询博客
//        Blog blog = getById(id);
//        if (blog == null) {
//            return Result.fail("Blog not found");
//        }
//        //2、根据id查询用户
//        queryBlogUser(blog);
//        isBlogLiked(blog);
//        return Result.ok(blog);
//
//    }
//
//    private void isBlogLiked(Blog blog) {
//        //3、查询笔记是否被点赞，点赞就将点赞字段设置为true
//        UserDTO user = UserHolder.getUser();
//        if (user == null) {
//            //用户未登录直接返回
//            return;
//        }
//        Long userId = user.getId();
//        String key = BLOG_LIKED_KEY+ blog.getId();
//        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
//        blog.setIsLike(score!=null);
////        if (BooleanUtil.isTrue(member)){
////            //已经点赞，将字段设置为true
////            blog.setIsLike(true);
////        }else {
////            //未点赞设置为false
////            blog.setIsLike(false);
////        }
//    }
//
//    private void queryBlogUser(Blog blog) {
//        Long userId = blog.getUserId();
//        User user = userService.getById(userId);
//        blog.setName(user.getNickName());
//        blog.setIcon(user.getIcon());
//    }
//
//    @Override
//    public Result queryHotBlog(Integer current) {
//        // 根据用户查询
//        Page<Blog> page = query()
//                .orderByDesc("liked")
//                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
//        // 获取当前页数据
//        List<Blog> records = page.getRecords();
//        // 查询用户
//        records.forEach(blog -> {
//            this.queryBlogUser(blog);
//            this.isBlogLiked(blog);
//        });
//        return Result.ok(records);
//    }
//
//    /**
//     * 创建点赞功能进行限制，一人一个点赞。点赞以后会进行高亮显示，再次点击进行取消。
//     * @param id
//     * @return
//     */
//    @Override
//    public Result likeBlog(Long id) {
//        //获取用户,把点赞信息使用redis的set集合进行接收，笔记id作为key，用户id作为value
//        UserDTO user = UserHolder.getUser();
//        Long userId = user.getId();
//        String key = BLOG_LIKED_KEY+ id;
//        //1、判断是否已经点赞
//        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
//        if(score == null) {
//            //1.1 未点赞，可以进行点赞
//            //1.1.1 保存用户的点赞信息到redis的set集合
//            //1.1.2 修改数据库的点赞数+1
//            boolean is_success = update().setSql("liked = liked + 1").eq("id", id).update();
//            if (is_success) {
//                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
//            }
//        }else {
//            //1.2 已经点赞，取消点赞
//            //1.2.2 修改数据库的点赞数-1
//            boolean is_success = update().setSql("liked = liked - 1").eq("id", id).update();
//            if(is_success){
//                //1.2.1 删除点赞信息到redis
//                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
//
//            }
//        }
//        return Result.ok();
//    }
//
//    /**
//     * 实现查询top5点赞用户
//     * @param id
//     * @return
//     */
//    @Override
//    public Result queryBlogLikes(Long id) {
//        //1查询获得点赞的top5
//        String key = BLOG_LIKED_KEY+ id;
//        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, BLOG_LIKED_TOP - 1);
//        if (top5==null || top5.isEmpty()) {
//            return Result.ok(Collections.emptyList());
//        }
//        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
//        String idstr = StrUtil.join(",",ids);
//        //2解析出用户
//        //3将用户UserDTO返回
//        List<UserDTO> userDTOS = userService.query().in("id",ids)
//                .last("ORDER BY FIELD(id,"+idstr+")").list()
//                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
//        return Result.ok(userDTOS);
//    }
//
//    @Override
//    public Result saveBlog(Blog blog) {
//        // 获取登录用户
//        UserDTO user = UserHolder.getUser();
//        blog.setUserId(user.getId());
//        // 保存探店博文
//        boolean isSuccess = save(blog);
//        if (!isSuccess) {
//            return Result.fail("笔记更新失败");
//        }
//
//        List<Follow> followList = iFollowService.query().eq("follow_user_id", user.getId()).list();
//        for (Follow follow : followList) {
//            //获取粉丝的id
//            Long userId = follow.getUserId();
//            //推送给粉丝博客id
//            String key = "feed:"+userId;
//            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
//        }
//        return Result.ok(blog.getId());
//    }
//
//    /**
//     * 滚动查询收件箱，
//     * @param max
//     * @param offset
//     * @return
//     */
//    @Override
//    public Result queryBlogOfFollow(Long max, Integer offset) {
//        //获取当前用户
//        UserDTO user = UserHolder.getUser();
//        Long userId = user.getId();
//        String key = FOLLOWS+userId;
//
////        stringRedisTemplate.opsForZSet().
//        //查询收件箱
//        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
//                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
//
////        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
////                .reverseRangeByScoreWithScores(key,0,max,offset,2);
////                .reverseRangeByScoreWithScores(key, 0, max, offset, System.currentTimeMillis());
//        if (typedTuples==null || typedTuples.isEmpty()) {
//            return Result.ok();
//        }
//        List<Long> ids = new ArrayList<>(typedTuples.size());
//        //解析数据，blogid，时间戳，offset(与最小时间一致的个数，需要跳过)
//        Long minTime = 0L;
//        int os = 0;
//
//        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
//            //获取id（值）,需要把值放到集合中
//            String value = tuple.getValue();
//            ids.add(Long.valueOf(value));
//            //获取分数（时间戳）
//            long time = tuple.getScore().longValue();
//            //获取offset
//            if (time == minTime){
//                os++;
//            }else {
//                minTime = time;
//                os = 1;
//            }
//        }
//
//        //封装blog
//        String idstr = StrUtil.join(",",ids);
//        List<Blog> blogs = query().in("id", ids)
//                .last("ORDER BY FIELD(id," + idstr + ")").list();
//        for (Blog blog : blogs) {
//            queryBlogUser(blog);
//            isBlogLiked(blog);
//        }
//        //返回
//        ScrollResult r = new ScrollResult();
//        r.setList(blogs);
//        r.setOffset(os);
//        r.setMinTime(minTime);
//        return Result.ok(r);
//    }
//}