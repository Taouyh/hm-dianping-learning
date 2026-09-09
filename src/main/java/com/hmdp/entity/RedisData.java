package com.hmdp.entity;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;


@Component
@Data
public class RedisData {
    private LocalDateTime expireTime; //过期时间
    private Object data; //存储数据
}
