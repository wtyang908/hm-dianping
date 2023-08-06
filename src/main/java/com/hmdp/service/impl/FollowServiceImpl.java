package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_FELLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserServiceImpl userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        String key=BLOG_LIKED_FELLOW_KEY+userId;
        //判断是关注还是取关
        if(isFollow){
            Follow follow = new Follow().setUserId(userId).setFollowUserId(followUserId);
            boolean sucess = save(follow);
            if(sucess){
                //将关注的人id放入

                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            boolean sucess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(sucess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();

    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断并返回
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //使用redis中set的求交集的方法求共同关注
        Long userId = UserHolder.getUser().getId();
        String key1 = BLOG_LIKED_FELLOW_KEY + userId;
        String key2 = BLOG_LIKED_FELLOW_KEY + id;
        //求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);

        if (intersect == null || intersect.isEmpty()) {
            // 无交集，返回空列表
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合，把Set<String>交集转换为List<Long>，方便下面使用
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户，同时把List<User>转为List<UserDTO>，由于共同关注不需要有序性，所以这里可以直接使用listByIds()
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 返回
        return Result.ok(userDTOS);

    }
}
