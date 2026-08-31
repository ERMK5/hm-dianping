-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]

-- String 字符串 秒杀券剩余库存，存数字
local stockKey = 'seckill:stock:' .. voucherId
-- Set 集合 记录已经下单的用户 ID 集合
local orderKey = 'seckill:order:' .. voucherId

-- 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回1
    return 1
end
-- 判断用户是否下过单 判断给定的值(userId)是否属于Set集合(orderKey)用s is member
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 下过单，重复下单返回2
    return 2
end
-- 有下单资格
-- 扣库存
redis.call('incrby', stockKey, -1)
-- 下单s add
redis.call('sadd', orderKey, userId)
-- 发送消息到队列
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0