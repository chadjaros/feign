package feign;

public interface LoadBalancerAwareTarget extends Target {

    /**
     * Creates a clone of the instance, except the clone should contain the
     * given load balancer key
     * @param loadBalancerKey an object used to choose a server from the load balancer
     * @return A new LoadBalancerAwareTarget
     */
    LoadBalancerAwareTarget loadBalancerKey(Object loadBalancerKey);
}
