package com.cvdcms.service;

import com.cvdcms.event.CustomerCareDomainEvents.InstallmentPaymentRecordedCareEvent;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * BeanPostProcessor sử dụng JDK Dynamic Proxy (ProxyFactory) bọc ngoài bean TreatmentPlanService
 * để phát sự kiện InstallmentPaymentRecordedCareEvent khi recordInstallmentPayment(...) thành công,
 * giữ nguyên 100% TransactionInterceptor và code TreatmentPlanServiceImpl.
 */
@Slf4j
@Component
public class InstallmentPaymentSyncPostProcessor implements BeanPostProcessor, Ordered {

    private final ObjectProvider<ApplicationEventPublisher> eventPublisherProvider;

    public InstallmentPaymentSyncPostProcessor(ObjectProvider<ApplicationEventPublisher> eventPublisherProvider) {
        this.eventPublisherProvider = eventPublisherProvider;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof TreatmentPlanService)) {
            return bean;
        }
        if (bean instanceof CskhWrappedTreatmentPlanService) {
            return bean;
        }

        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(bean);
        proxyFactory.setInterfaces(TreatmentPlanService.class, CskhWrappedTreatmentPlanService.class);
        proxyFactory.setProxyTargetClass(false);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if ("recordInstallmentPayment".equals(invocation.getMethod().getName())) {
                Object[] args = invocation.getArguments();
                Long planId = (args != null && args.length > 0 && args[0] instanceof Long id) ? id : null;
                if (planId != null) {
                    try {
                        ApplicationEventPublisher publisher = eventPublisherProvider.getIfAvailable();
                        if (publisher != null) {
                            publisher.publishEvent(new InstallmentPaymentRecordedCareEvent(planId));
                        }
                    } catch (Throwable ex) {
                        log.warn("Lỗi phát sự kiện đồng bộ CSKH trả góp cho planId={} (không ảnh hưởng giao dịch thu tiền): {}",
                                planId, ex.getMessage());
                    }
                }
            }
            return result;
        });

        return proxyFactory.getProxy(bean.getClass().getClassLoader());
    }

    /** Marker interface nội bộ tránh bọc proxy trùng lặp. */
    public interface CskhWrappedTreatmentPlanService {
    }
}
