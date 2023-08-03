package com.cama.service.impl;

import com.cama.entity.BlogComments;
import com.cama.mapper.BlogCommentsMapper;
import com.cama.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
