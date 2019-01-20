package com.tqz.service.impl;

import com.tqz.dao.SeckillDao;
import com.tqz.dao.SuccessKilledDao;
import com.tqz.dao.cache.RedisDao;
import com.tqz.dto.Exposer;
import com.tqz.dto.SeckillExecution;
import com.tqz.entity.Seckill;
import com.tqz.entity.SuccessKilled;
import com.tqz.enums.SeckillStateEnum;
import com.tqz.exception.RepeatKillException;
import com.tqz.exception.SeckillCloseException;
import com.tqz.exception.SeckillException;
import com.tqz.service.SeckillService;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeckillServiceImpl implements SeckillService {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    private final String salt = "skdfjksjdf7787%^%^%^FSKJFK*(&&%^%&^8DF8^%^^*7hFJDHFJ";

    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private SuccessKilledDao successKilledDao;

    @Autowired
    private RedisDao redisDao;

    //优化/list查询页面
    @Override
    public List<Seckill> getSeckillList(int offset, int limit) {
        List<Seckill> seckillList = redisDao.getSeckillList(offset, limit);
        if (seckillList == null) {
            // 这个参数的意义在于分页查询
            seckillList = seckillDao.queryAll(offset, limit);
            redisDao.putSeckillList(seckillList, offset, limit);
        }
        return seckillList;
    }

    ///{seckillId}/detail
    @Override
    public Seckill getById(long seckillId) {
        //return seckillDao.queryById(seckillId);
        //return redisDao.getOrPutSeckill(seckillId, id -> seckillDao.queryById(id));
       // todo 压测时会出问题？需要分布式锁？？ 此方法测试了一遍目前没有发现问题
        Seckill seckill = redisDao.getSeckill(seckillId);
        if (seckill == null) {
            seckill = seckillDao.queryById(seckillId);
            redisDao.putSeckill(seckill);
        }
        return seckill;
    }

    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        Seckill seckill = getById(seckillId);
        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        Date nowTime = new Date();
        //若秒杀未开启/秒杀已结束，返回三个时间
        if (startTime.getTime() > nowTime.getTime() || endTime.getTime() < nowTime.getTime()) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
        }
        //若秒杀已开启，返回秒杀商品的id、用给接口加密的md5
        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }

    private String getMD5(long seckillId) {
        String base = seckillId + "/" + salt;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }

    //秒杀是否成功，成功:减库存，增加明细；失败:抛出异常，事务回滚
    @Override
    @Transactional
    /**
     * 使用注解控制事务方法的优点:
     * 1.开发团队达成一致约定，明确标注事务方法的编程风格
     * 2.保证事务方法的执行时间尽可能短，不要穿插其他网络操作RPC/HTTP请求或者剥离到事务方法外部
     * 3.不是所有的方法都需要事务，如只有一条修改操作、只读操作不要事务控制
     */
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5)
            throws SeckillException, RepeatKillException, SeckillCloseException {

        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            //秒杀数据被重写了，用户违规操作，抛异常！
            return new SeckillExecution(seckillId, SeckillStateEnum.DATA_REWRITE);
        }
        //执行秒杀逻辑:减库存 + 增加购买明细
        Date nowTime = new Date();

        try {
            /**
             * 1. 先插入秒杀成功
             * 2. 再减库存
             */

            //否则更新了库存，秒杀成功,增加明细
            int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);
            // 看是否该明细被重复插入，即用户是否重复秒杀
            if (insertCount <= 0) {
                // 重复秒杀会返回0，就需要抛异常
                throw new RepeatKillException("seckill repeated");
            } else {

                //减库存,热点商品竞争
                int updateCount = seckillDao.reduceNumber(seckillId, nowTime);
                if (updateCount <= 0) {
                    //没有更新库存记录，说明秒杀结束 rollback
                    throw new SeckillCloseException("seckill is closed");
                } else {
                    //秒杀成功,得到成功插入的明细记录,并返回成功秒杀的信息 commit
                    SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                    return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
                }
            }
        } catch (SeckillCloseException e1) {
            throw e1;
        } catch (RepeatKillException e2) {
            throw e2;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            //所以编译期异常转化为运行期异常
            throw new SeckillException("seckill inner error :" + e.getMessage());
        }

    }

    /**
     * todo 使用存储过程完成秒杀逻辑
     * 等先压测一把后再使用存储过程,当前只把代码写好，数据库里面还没有执行存储过程的代码
     */
    @Override
    public SeckillExecution executeSeckillProcedure(long seckillId, long userPhone, String md5) {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            return new SeckillExecution(seckillId, SeckillStateEnum.DATA_REWRITE);
        }
        Date killTime = new Date();
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("seckillId", seckillId);
        map.put("phone", userPhone);
        map.put("killTime", killTime);
        map.put("result", null);

        try {
            // 执行存储过程，result会被赋值
            seckillDao.killByProcedure(map);
            // 获取result并判断存储过程执行状态
            int result = MapUtils.getInteger(map, "result", -2);
            if (result == 1) {
                SuccessKilled sk = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, sk);
            } else {
                return new SeckillExecution(seckillId, SeckillStateEnum.stateOf(result));
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new SeckillExecution(seckillId, SeckillStateEnum.INNER_ERROR);
        }
    }
}







