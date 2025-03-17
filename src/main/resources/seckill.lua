-- 1. 参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2. 数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key
local orderKey = 'seckill:order:' .. voucherId

--3. 脚本逻辑
-- 3.1. 判断库存
if(tonumber(redis.call('get',stockKey)) <= 0) then
    --  库存不足，返回 1
    return 1
end
-- 3.2.判断是否下单 
if(redis.call('sismember',orderKey,userId) == 1) then
    return 2
end
-- 3.3 扣库存
redis.call('incrby',stockKey,-1)
-- 3.4 下单（保存用户至redis set）
redis.call('sadd',orderKey,userId)

return 0
