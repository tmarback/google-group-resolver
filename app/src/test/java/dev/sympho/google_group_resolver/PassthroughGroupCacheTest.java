package dev.sympho.google_group_resolver;

/**
 * Unit tests for {@link PassthroughGroupCache}.
 */
public class PassthroughGroupCacheTest extends GroupCacheTest<PassthroughGroupCache> {

    @Override
    protected PassthroughGroupCache makeIUT() {

        return new PassthroughGroupCache( directory );

    }
    
}
