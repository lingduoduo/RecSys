package com.recsys.config;

import com.recsys.config.NeedLogin;
import com.recsys.model.dto.ApiResponseUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class NeedLoginAspect {

    @Autowired
    private RequestScopeData requestScopeData;

    @Around("@annotation(needLogin)")
    public Object around(ProceedingJoinPoint joinPoint, NeedLogin needLogin) throws Throwable {
        if (!requestScopeData.isLogin()) {
            return ApiResponseUtil.error("user not logged in");
        }
        if (requestScopeData.getUserId() == null) {
            return ApiResponseUtil.error("user ID is invalid");
        }
        return joinPoint.proceed();
    }
}
