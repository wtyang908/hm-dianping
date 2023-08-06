package com.hmdp.config;


import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;


@Component
public class RabbitMQListener {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @RabbitListener(queues = "boot_topic_queue")
    public void ListenerQueue(Message message){
        VoucherOrder voucherOrder = JSONUtil.toBean(new String(message.getBody()), VoucherOrder.class);
        voucherOrderService.handleVoucherOrder(voucherOrder);
    }
}
