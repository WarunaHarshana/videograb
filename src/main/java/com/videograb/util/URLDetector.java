package com.videograb.util;

import java.util.regex.Pattern;

/**
 * Utility class for detecting video platform types from URLs
 * Ported from Python detect_type function
 */
public class URLDetector {

    // Platform detection patterns
    private static final Pattern YOUTUBE_PATTERN = Pattern.compile(
        "(youtube\\.com|youtu\\.be)", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern VIMEO_PATTERN = Pattern.compile(
        "vimeo\\.com", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern TIKTOK_PATTERN = Pattern.compile(
        "tiktok\\.com", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern TWITTER_PATTERN = Pattern.compile(
        "(twitter\\.com|x\\.com)", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern INSTAGRAM_PATTERN = Pattern.compile(
        "instagram\\.com", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern REDDIT_PATTERN = Pattern.compile(
        "reddit\\.com", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern STREAMABLE_PATTERN = Pattern.compile(
        "streamable\\.com", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern SOUNDCLOUD_PATTERN = Pattern.compile(
        "soundcloud\\.com", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern FACEBOOK_PATTERN = Pattern.compile(
        "facebook\\.com", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern DROPBOX_PATTERN = Pattern.compile(
        "dropbox\\.com", 
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Detect platform type from URL
     * @param url The URL to analyze
     * @return Platform name or "generic" if unknown
     */
    public static String detectType(String url) {
        if (url == null || url.isEmpty()) {
            return "generic";
        }

        if (YOUTUBE_PATTERN.matcher(url).find()) {
            return "youtube";
        }
        if (VIMEO_PATTERN.matcher(url).find()) {
            return "vimeo";
        }
        if (TIKTOK_PATTERN.matcher(url).find()) {
            return "tiktok";
        }
        if (TWITTER_PATTERN.matcher(url).find()) {
            return "twitter";
        }
        if (INSTAGRAM_PATTERN.matcher(url).find()) {
            return "instagram";
        }
        if (REDDIT_PATTERN.matcher(url).find()) {
            return "reddit";
        }
        if (STREAMABLE_PATTERN.matcher(url).find()) {
            return "streamable";
        }
        if (SOUNDCLOUD_PATTERN.matcher(url).find()) {
            return "soundcloud";
        }
        if (FACEBOOK_PATTERN.matcher(url).find()) {
            return "facebook";
        }
        if (DROPBOX_PATTERN.matcher(url).find()) {
            return "dropbox";
        }

        return "generic";
    }
}