package com.securedoc.platform.application.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0023 B8 (#328): URLs are logged without user info, query or fragment, and a non-URL is never echoed. */
class SafeUrlsTest {
    @Test
    void userInfoQueryAndFragmentAreRemoved() {
        assertEquals("http://ai-east:11434/api", SafeUrls.forLog("http://admin:s3cret@ai-east:11434/api?key=abc#x"));
        assertEquals("https://sdv.example.test", SafeUrls.forLog("https://user@sdv.example.test"));
    }

    @Test
    void somethingThatIsNotAnAbsoluteUrlIsNotEchoed() {
        assertEquals(SafeUrls.UNREADABLE, SafeUrls.forLog("admin:s3cret@ai-east:11434"));
        assertEquals(SafeUrls.UNREADABLE, SafeUrls.forLog("http://bad host/s3cret"));
        assertEquals(SafeUrls.UNREADABLE, SafeUrls.forLog((String) null));
    }
}
