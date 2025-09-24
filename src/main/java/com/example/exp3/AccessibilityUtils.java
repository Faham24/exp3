package com.example.exp3;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

/**
 * Utility class to enhance accessibility features for visually impaired users
 */
public class AccessibilityUtils {

    /**
     * Check if TalkBack or other screen reader is enabled
     */
    public static boolean isScreenReaderEnabled(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;

        List<AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN);

        return !enabledServices.isEmpty();
    }

    /**
     * Check if touch exploration is enabled
     */
    public static boolean isTouchExplorationEnabled(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isTouchExplorationEnabled();
    }

    /**
     * Configure button for maximum accessibility
     */
    public static void setupAccessibleButton(Button button, String contentDescription) {
        // Set content description for screen readers
        button.setContentDescription(contentDescription);

        // Enable focus for keyboard/D-pad navigation
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);

        // Set minimum touch target size (48dp)
        int minSize = (int) (48 * button.getContext().getResources().getDisplayMetrics().density);
        button.setMinimumHeight(minSize);
        button.setMinimumWidth(minSize);

        // High contrast colors
        button.setTextColor(button.getContext().getColor(android.R.color.white));
        button.setBackgroundColor(button.getContext().getColor(R.color.primary_blue));

        // Large, bold text
        button.setTextSize(24);
        button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
    }

    /**
     * Configure TextView for maximum accessibility
     */
    public static void setupAccessibleTextView(TextView textView, String contentDescription) {
        // Set content description
        textView.setContentDescription(contentDescription);

        // High contrast
        textView.setTextColor(textView.getContext().getColor(android.R.color.white));

        // Large text size
        textView.setTextSize(20);

        // Add padding for better readability
        int padding = (int) (16 * textView.getContext().getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);

        // Line spacing for better readability
        textView.setLineSpacing(0, 1.5f);
    }

    /**
     * Announce text to screen reader immediately
     */
    public static void announceForAccessibility(Context context, String text) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null && am.isEnabled()) {
            // This will be announced by TalkBack
            android.view.accessibility.AccessibilityEvent event =
                    android.view.accessibility.AccessibilityEvent.obtain();
            event.setEventType(android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.setClassName(context.getClass().getName());
            event.setPackageName(context.getPackageName());
            event.getText().add(text);

            am.sendAccessibilityEvent(event);
        }
    }

    /**
     * Get recommended timeout for voice commands based on accessibility settings
     */
    public static int getVoiceCommandTimeout(Context context) {
        // Longer timeout if accessibility services are enabled
        if (isScreenReaderEnabled(context)) {
            return 10000; // 10 seconds
        } else {
            return 5000;  // 5 seconds
        }
    }

    /**
     * Get recommended speech rate for TTS based on accessibility settings
     */
    public static float getRecommendedSpeechRate(Context context) {
        // Slightly slower speech if screen reader is enabled
        if (isScreenReaderEnabled(context)) {
            return 0.8f; // 20% slower than normal
        } else {
            return 1.0f; // Normal rate
        }
    }

    /**
     * Create vibration pattern for feedback
     */
    public static long[] getVibrationPattern(VibrationPattern pattern) {
        switch (pattern) {
            case SHORT_PULSE:
                return new long[]{0, 100};
            case DOUBLE_PULSE:
                return new long[]{0, 100, 100, 100};
            case LONG_PULSE:
                return new long[]{0, 500};
            case SUCCESS_PATTERN:
                return new long[]{0, 100, 50, 100};
            case ERROR_PATTERN:
                return new long[]{0, 200, 100, 200, 100, 200};
            default:
                return new long[]{0, 100};
        }
    }

    public enum VibrationPattern {
        SHORT_PULSE,
        DOUBLE_PULSE,
        LONG_PULSE,
        SUCCESS_PATTERN,
        ERROR_PATTERN
    }

    /**
     * Format text for better TTS pronunciation
     */
    public static String formatTextForSpeech(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // Add pauses for better comprehension
        text = text.replaceAll("\\.", ". "); // Pause after periods
        text = text.replaceAll(",", ", "); // Pause after commas
        text = text.replaceAll(";", "; "); // Pause after semicolons
        text = text.replaceAll(":", ": "); // Pause after colons

        // Handle common abbreviations
        text = text.replaceAll("\\bDr\\.", "Doctor");
        text = text.replaceAll("\\bMr\\.", "Mister");
        text = text.replaceAll("\\bMrs\\.", "Missus");
        text = text.replaceAll("\\bMs\\.", "Miss");
        text = text.replaceAll("\\bSt\\.", "Saint");
        text = text.replaceAll("\\bAve\\.", "Avenue");
        text = text.replaceAll("\\bRd\\.", "Road");
        text = text.replaceAll("\\bSt\\.", "Street");

        // Handle numbers for better pronunciation
        text = text.replaceAll("\\b1st\\b", "first");
        text = text.replaceAll("\\b2nd\\b", "second");
        text = text.replaceAll("\\b3rd\\b", "third");
        text = text.replaceAll("\\b(\\d+)th\\b", "$1th");

        // Remove extra whitespace
        text = text.replaceAll("\\s+", " ").trim();

        return text;
    }

    /**
     * Create accessible error messages
     */
    public static String createAccessibleErrorMessage(String error, String suggestion) {
        return "Error: " + error + ". " + suggestion;
    }

    /**
     * Create accessible success messages
     */
    public static String createAccessibleSuccessMessage(String action, String result) {
        return "Success: " + action + ". " + result;
    }
}