package com.anthonyla.paperize.core.constants

object Constants {
    // Database
    const val DATABASE_NAME = "paperize_database"
    const val DATABASE_VERSION = 4

    // DataStore
    const val PREFERENCES_NAME = "paperize_preferences"

    // Notifications
    const val NOTIFICATION_CHANNEL_ID = "paperize_channel"
    const val NOTIFICATION_ID = 1
    const val UNLOCK_CHANNEL_ID = "change_on_unlock"

    // Services
    const val ACTION_RELOAD_WALLPAPER = "com.anthonyla.paperize.ACTION_RELOAD_WALLPAPER"
    /** Our live wallpaper shows the picture at [EXTRA_PICTURE_URI] until the album's next one. */
    const val ACTION_SHOW_PICTURE = "com.anthonyla.paperize.ACTION_SHOW_PICTURE"
    const val EXTRA_PICTURE_URI = "picture_uri"
    /** On [ACTION_RELOAD_WALLPAPER]: asked for by the person, so a live wallpaper hidden behind the app opens its next picture now. */
    const val EXTRA_NOW = "now"

    // WorkManager
    const val WORK_NAME_HOME = "wallpaper_change_home"
    const val WORK_NAME_LOCK = "wallpaper_change_lock"
    const val WORK_NAME_BOTH = "wallpaper_change_both"
    const val WORK_NAME_LIVE = "wallpaper_change_live"
    const val WORK_NAME_REFRESH = "album_refresh"
    const val MAX_WORK_RETRY_ATTEMPTS = 3

    // Intents
    const val EXTRA_SCREEN_TYPE = "screen_type"

    // Wallpaper
    const val DEFAULT_BLUR_PERCENTAGE = 0
    const val DEFAULT_DARKEN_PERCENTAGE = 0
    const val DEFAULT_VIGNETTE_PERCENTAGE = 0
    const val DEFAULT_PARALLAX_INTENSITY = 50
    const val DEFAULT_GRAYSCALE_PERCENTAGE = 0
    const val MAX_EFFECT_PERCENTAGE = 100
    const val SLIDER_EFFECT_STEPS = 99
    const val DIALOG_MESSAGE_MAX_LINES = 10
    const val MIN_EFFECT_PERCENTAGE = 0

    // Scheduling
    const val MIN_LIVE_INTERVAL_MINUTES = 1
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 43200  // 30 days in minutes
    const val DEFAULT_INTERVAL_MINUTES = 60

    // UI
    const val ANIMATION_DURATION_LONG_MS = 800  // For item reordering animations
    const val DEBOUNCE_DELAY_MS = 500L
    const val SETTINGS_DEBOUNCE_MS = 2000L
    const val WALLPAPER_ASPECT_RATIO = 9f / 16f
    const val GRID_THUMBNAIL_WIDTH = 300
    const val GRID_THUMBNAIL_HEIGHT = 500
    const val LIST_THUMBNAIL_SIZE = 150
    const val PREVIEW_THUMBNAIL_WIDTH = 600
    const val PREVIEW_THUMBNAIL_HEIGHT = 1200

    // Time conversion
    const val MINUTES_PER_HOUR = 60
    const val MINUTES_PER_DAY = 1440

    // Image processing
    const val MAX_BLUR_RADIUS = 25.0f
    const val BRIGHTNESS_SAMPLE_SIZE = 10

    // Luminance coefficients (ITU-R BT.709 standard)
    const val LUMINANCE_RED = 0.2126
    const val LUMINANCE_GREEN = 0.7152
    const val LUMINANCE_BLUE = 0.0722

    // Adaptive brightness: in dark mode a picture is scaled so its average luminance lands near this
    // target, clamped so it is always at least 25% dimmer and never brightened (light mode is untouched).
    const val DARK_MODE_TARGET_LUMINANCE = 0.38f
    const val DARK_MODE_MIN_MULTIPLIER = 0.4f
    const val DARK_MODE_MAX_MULTIPLIER = 0.75f

    // Vignette: a band along each screen edge, sized from the short side (GLSL copy in GLShaders)
    const val VIGNETTE_BAND_BASE = 0.12f
    const val VIGNETTE_BAND_RANGE = 0.38f
    const val VIGNETTE_MAX_DARK_BASE = 0.3f
    const val VIGNETTE_MAX_DARK_RANGE = 0.5f
    /** Cells along the short side of the bitmap vignette mask; the mask is stretched over the frame. */
    const val VIGNETTE_MASK_CELLS = 96

    // Input validation
    const val MAX_DAYS_INPUT_LENGTH = 3
    const val MAX_HOURS_MINUTES_INPUT_LENGTH = 2

    // File types
    val SUPPORTED_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "avif", "heic", "heif", "bmp", "gif", "tiff", "tif", "svg"
    )

    // Renderer
    const val CROSSFADE_DURATION_MS = 750f
    const val RELOAD_THROTTLE_MS = 250L
    const val BLUR_MIN_THRESHOLD = 0.01f
    const val PERCENTAGE_DIVISOR = 100f
    const val GL_ES_VERSION = 2

    // Wallpaper loading
    const val MAX_WALLPAPER_LOAD_RETRIES = 10
}

object PreferenceKeys {
    // Theme
    const val DARK_MODE = "dark_mode"
    const val DYNAMIC_THEMING = "dynamic_theming"
    const val ANIMATE = "animate"

    // Scheduling
    const val ENABLE_CHANGER = "enable_changer"
    const val SEPARATE_SCHEDULES = "separate_schedules"
    const val SHUFFLE_ENABLED = "shuffle_enabled"
    const val CHANGE_ON_UNLOCK = "change_on_unlock"
    const val INTERVAL_ENABLED = "interval_enabled"
    const val HOME_ENABLED = "home_enabled"
    const val LOCK_ENABLED = "lock_enabled"
    // Album lists are stored newline-joined in order; the *_ALBUM_ID keys are the single-album keys
    // older builds wrote, read once and removed by the migration in SettingsRepositoryImpl.
    const val HOME_ALBUM_IDS = "home_album_ids"
    const val LOCK_ALBUM_IDS = "lock_album_ids"
    const val HOME_ALBUM_ID = "home_album_id"
    const val LOCK_ALBUM_ID = "lock_album_id"
    const val HOME_INTERVAL_MINUTES = "home_interval_minutes"
    const val LOCK_INTERVAL_MINUTES = "lock_interval_minutes"

    // Live Wallpaper Mode Settings (effect keys are built in SettingsRepositoryImpl)
    const val LIVE_ALBUM_IDS = "live_album_ids"
    const val LIVE_ALBUM_ID = "live_album_id"
    const val LIVE_INTERVAL_MINUTES = "live_interval_minutes"

    // Scaling
    const val HOME_SCALING_TYPE = "home_scaling_type"
    const val LOCK_SCALING_TYPE = "lock_scaling_type"
    const val LIVE_SCALING_TYPE = "live_scaling_type"
    const val HOME_SCROLLING_ENABLED = "home_scrolling_enabled"

    const val ADAPTIVE_BRIGHTNESS = "adaptive_brightness"
    const val VISUAL_EFFECTS_ENABLED = "visual_effects_enabled"
    const val FIRST_LAUNCH = "first_launch"
    const val WALLPAPER_MODE = "wallpaper_mode"
}
