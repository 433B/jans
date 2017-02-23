package org.xdi.service.cache;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.Serializable;

/**
 * @author yuriyz on 02/21/2017.
 */
@XmlRootElement
public class CacheConfiguration implements Serializable {

    @XmlElement(name = "cacheProviderType", required = true)
    private CacheProviderType cacheProviderType = CacheProviderType.IN_MEMORY;

    @XmlElement(name = "memcachedConfiguration")
    private MemcachedConfiguration memcachedConfiguration;

    @XmlElement(name = "inMemoryConfiguration")
    private InMemoryConfiguration inMemoryConfiguration = new InMemoryConfiguration();

    @XmlElement(name = "redisConfiguration")
    private RedisConfiguration redisConfiguration;

    public RedisConfiguration getRedisConfiguration() {
        return redisConfiguration;
    }

    public void setRedisConfiguration(RedisConfiguration redisConfiguration) {
        this.redisConfiguration = redisConfiguration;
    }

    public CacheProviderType getCacheProviderType() {
        return cacheProviderType;
    }

    public void setCacheProviderType(CacheProviderType cacheProviderType) {
        this.cacheProviderType = cacheProviderType;
    }

    public InMemoryConfiguration getInMemoryConfiguration() {
        return inMemoryConfiguration;
    }

    public void setInMemoryConfiguration(InMemoryConfiguration inMemoryConfiguration) {
        this.inMemoryConfiguration = inMemoryConfiguration;
    }

    public MemcachedConfiguration getMemcachedConfiguration() {
        return memcachedConfiguration;
    }

    public void setMemcachedConfiguration(MemcachedConfiguration memcachedConfiguration) {
        this.memcachedConfiguration = memcachedConfiguration;
    }

    @Override
    public String toString() {
        return "CacheConfiguration{" +
                "cacheProviderType=" + cacheProviderType +
                ", memcachedConfiguration=" + memcachedConfiguration +
                ", redisConfiguration=" + redisConfiguration +
                ", inMemoryConfiguration=" + inMemoryConfiguration +
                '}';
    }
}
